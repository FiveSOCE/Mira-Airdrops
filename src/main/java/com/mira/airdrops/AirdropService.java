package com.mira.airdrops;

import com.mira.core.api.MiraCore;
import com.mira.core.api.RewardService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class AirdropService {
    private final MiraAirdropsPlugin plugin;
    private final MiraCore core;
    private final RegionService regions;
    private final File lootFile;
    private final File stateFile;
    private final NamespacedKey dropKey;
    private final List<ItemStack> lootPool = new ArrayList<>();
    private final Map<UUID, ActiveDrop> falling = new HashMap<>();
    private final Map<BlockKey, ActiveDrop> landed = new HashMap<>();

    private boolean inbound;
    private boolean active;
    private int total;
    private long inboundBeginsAt;
    private BukkitTask inboundTask;
    private BukkitTask autoTask;
    private BukkitTask reconcileTask;

    public AirdropService(MiraAirdropsPlugin plugin, MiraCore core, RegionService regions) {
        this.plugin = plugin;
        this.core = core;
        this.regions = regions;
        this.lootFile = new File(plugin.getDataFolder(), "loot.yml");
        this.stateFile = new File(plugin.getDataFolder(), "event-state.yml");
        this.dropKey = new NamespacedKey(plugin, "airdrop_id");
        loadLoot();
        loadState();
        scheduleAuto();
        long reconcileTicks = Math.max(20L, plugin.getConfig().getLong("event.reconcile-seconds", 5L) * 20L);
        reconcileTask = Bukkit.getScheduler().runTaskTimer(plugin, this::reconcileActiveDrops, reconcileTicks, reconcileTicks);
    }

    public boolean inbound() { return inbound; }
    public boolean active() { return active; }
    public int total() { return total; }
    public int remaining() { return falling.size() + landed.size(); }
    public int landedCount() { return landed.size(); }
    public List<ItemStack> lootPool() { return lootPool.stream().map(ItemStack::clone).toList(); }

    public void setLootPool(Collection<ItemStack> items) {
        lootPool.clear();
        if (items != null) {
            items.stream().filter(Objects::nonNull).filter(item -> !item.getType().isAir())
                    .limit(45).map(ItemStack::clone).forEach(lootPool::add);
        }
        saveLoot();
    }

    public boolean start(CommandSender actor) {
        if (inbound || active) {
            if (actor != null) core.messages().send(actor, "&cAn airdrop is already inbound or active.");
            return false;
        }
        if (lootPool.isEmpty()) {
            if (actor != null) core.messages().send(actor, "&cThe airdrop loot pool is empty.");
            return false;
        }
        if (!regions.ready()) {
            if (actor != null) core.messages().send(actor, "&cThe selected airdrop region mode is not ready.");
            return false;
        }

        int seconds = Math.max(1, plugin.getConfig().getInt("event.inbound-seconds", 30));
        inbound = true;
        inboundBeginsAt = System.currentTimeMillis() + seconds * 1000L;
        broadcast("messages.inbound", Map.of("%seconds%", Integer.toString(seconds)));
        audit("INBOUND", actor, "Airdrop inbound countdown started.", Map.of(
                "seconds", Integer.toString(seconds),
                "region", regions.summary()));
        saveState();
        scheduleInbound(seconds * 20L);
        return true;
    }

    private void scheduleInbound(long ticks) {
        if (inboundTask != null) inboundTask.cancel();
        inboundTask = Bukkit.getScheduler().runTaskLater(plugin, this::beginNow, Math.max(1L, ticks));
    }

    private void beginNow() {
        inbound = false;
        inboundBeginsAt = 0L;
        inboundTask = null;
        if (active) return;

        int min = clamp(plugin.getConfig().getInt("event.min-crates", 20), 20, 50);
        int max = clamp(plugin.getConfig().getInt("event.max-crates", 50), min, 50);
        int requested = ThreadLocalRandom.current().nextInt(min, max + 1);

        total = 0;
        active = true;
        for (int i = 0; i < requested; i++) {
            DropPayload payload = new DropPayload(UUID.randomUUID(), rollLoot());
            if (respawn(payload, false)) total++;
        }

        if (total == 0) {
            active = false;
            saveState();
            broadcastRaw("&cAirdrop aborted because no valid drop locations could be found.");
            audit("ABORTED", null, "Airdrop aborted because no valid drop locations were found.", Map.of());
            return;
        }

        saveState();
        broadcast("messages.started", Map.of("%total%", Integer.toString(total)));
        broadcastRemaining();
        audit("STARTED", null, "Airdrop event started.", Map.of(
                "total", Integer.toString(total),
                "region", regions.summary()));
    }

    public void cancel(boolean announce) {
        cancel(null, announce);
    }

    public void cancel(CommandSender actor, boolean announce) {
        boolean hadEvent = inbound || active;

        if (inboundTask != null) inboundTask.cancel();
        inboundTask = null;
        inbound = false;
        inboundBeginsAt = 0L;

        removeTransientWorldObjects();
        falling.clear();
        landed.clear();

        active = false;
        total = 0;
        saveState();

        if (announce && hadEvent) broadcast("messages.cancelled", Map.of());
        if (hadEvent) audit("CANCELLED", actor, "Airdrop event cancelled.", Map.of());
    }

    public void shutdown() {
        if (inboundTask != null) inboundTask.cancel();
        if (autoTask != null) autoTask.cancel();
        if (reconcileTask != null) reconcileTask.cancel();

        // Preserve the logical event before removing transient world objects. This prevents
        // duplicate vanilla falling blocks/chests after a clean restart while allowing the
        // exact remaining rewards to be restored from event-state.yml.
        saveState();
        removeTransientWorldObjects();
    }

    public void rescheduleAuto() {
        if (autoTask != null) autoTask.cancel();
        scheduleAuto();
    }

    private void scheduleAuto() {
        if (!plugin.getConfig().getBoolean("event.auto-enabled", false)) return;
        long minutes = Math.max(15L, plugin.getConfig().getLong("event.interval-minutes", 120L));
        autoTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active && !inbound) start(null);
        }, minutes * 60L * 20L, minutes * 60L * 20L);
    }

    private boolean respawn(DropPayload payload, boolean persist) {
        int attempts = Math.max(25, plugin.getConfig().getInt("event.max-placement-attempts-per-crate", 250));
        for (int i = 0; i < attempts; i++) {
            Location target = regions.randomSpawn();
            if (target == null || !target.getBlock().getType().isAir()) continue;
            ActiveDrop drop = new ActiveDrop(payload, BlockKey.of(target));
            if (spawnFalling(drop, persist)) return true;
        }
        return false;
    }

    private boolean spawnFalling(ActiveDrop drop, boolean persist) {
        Location spawn = drop.target().location();
        World world = spawn.getWorld();
        if (world == null) return false;
        if (!world.isChunkLoaded(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4)) return false;
        if (!spawn.getBlock().getType().isAir()) return false;
        if (columnAlreadyTracked(drop.target(), drop.payload().id())) return false;

        FallingBlock entity = world.spawnFallingBlock(
                new Location(world, spawn.getBlockX() + 0.5D, spawn.getBlockY(), spawn.getBlockZ() + 0.5D),
                Material.SAND.createBlockData());
        entity.setGravity(true);
        entity.setDropItem(false);
        entity.setHurtEntities(false);
        entity.getPersistentDataContainer().set(dropKey, PersistentDataType.STRING, drop.payload().id().toString());
        falling.put(entity.getUniqueId(), drop);
        if (persist) saveState();
        return true;
    }

    private boolean columnAlreadyTracked(BlockKey candidate, UUID payloadId) {
        for (ActiveDrop existing : falling.values()) {
            if (existing.payload().id().equals(payloadId)) continue;
            if (existing.target().sameColumn(candidate)) return true;
        }
        for (ActiveDrop existing : landed.values()) {
            if (existing.payload().id().equals(payloadId)) continue;
            if (existing.target().sameColumn(candidate)) return true;
        }
        return false;
    }

    public void handleLanding(FallingBlock entity, EntityChangeBlockEvent event) {
        ActiveDrop drop = falling.remove(entity.getUniqueId());
        if (drop == null) return;

        if (!event.getBlock().getType().isAir()) {
            event.setCancelled(true);
            entity.remove();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (active && !respawn(drop.payload(), true)) {
                    total = Math.max(0, total - 1);
                    saveState();
                    checkComplete();
                }
            });
            return;
        }

        Location landing = event.getBlock().getLocation();
        event.setCancelled(true);
        entity.remove();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!active) return;

            Block block = landing.getBlock();
            if (!block.getType().isAir()) {
                if (!respawn(drop.payload(), true)) {
                    total = Math.max(0, total - 1);
                    saveState();
                    checkComplete();
                }
                return;
            }

            block.setType(Material.CHEST, false);
            ActiveDrop landedDrop = new ActiveDrop(drop.payload(), BlockKey.of(landing));
            markChest(block, drop.payload().id());
            landed.put(landedDrop.target(), landedDrop);
            saveState();
        });
    }

    public boolean claim(Player player, Block block) {
        if (!active || block == null) return false;

        BlockKey key = BlockKey.of(block.getLocation());
        ActiveDrop drop = landed.get(key);
        if (drop == null || !isMarkedChest(block, drop.payload().id())) return false;

        RewardService rewardService = core.rewards();
        UUID rewardId;
        try {
            rewardId = rewardService.queue(player.getUniqueId(), "MiraAirdrops", "Airdrop Cache",
                    drop.payload().items(), List.of());
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("Could not queue airdrop reward for " + player.getName() + ": " + ex.getMessage());
            core.messages().send(player, "&cThat airdrop could not be claimed safely. Please try again.");
            return true;
        }

        landed.remove(key);
        clearMarkedChest(block, drop.payload().id());
        saveState();

        RewardService.ClaimResult result = rewardService.claim(player, rewardId);

        player.sendMessage(core.messages().prefix()
                .append(core.messages().parse(plugin.getConfig().getString("messages.claim-prefix",
                        "&aYou found: "))));
        for (ItemStack item : drop.payload().items()) {
            player.sendMessage(Component.text(" • ")
                    .append(itemName(item))
                    .append(Component.text(" x" + item.getAmount())));
        }
        if (!result.complete()) {
            player.sendMessage(core.messages().prefix().append(core.messages().parse(
                    plugin.getConfig().getString("messages.overflow",
                            "&eSome rewards did not fit and were moved to &f/rewards&e."))));
        }

        audit("CLAIMED", player, "Airdrop crate claimed.", Map.of(
                "dropId", drop.payload().id().toString(),
                "rewardId", rewardId.toString(),
                "items", Integer.toString(drop.payload().items().size()),
                "remaining", Integer.toString(remaining())));

        broadcastRemaining();
        checkComplete();
        return true;
    }

    public boolean isAirdropChest(Block block) {
        if (block == null || block.getType() != Material.CHEST) return false;
        ActiveDrop drop = landed.get(BlockKey.of(block.getLocation()));
        return drop != null && isMarkedChest(block, drop.payload().id());
    }

    public boolean isAirdropFallingBlock(FallingBlock block) {
        if (block == null) return false;
        String id = block.getPersistentDataContainer().get(dropKey, PersistentDataType.STRING);
        return id != null;
    }

    public boolean teleportToNearestCrate(Player player) {
        if (player == null) return false;
        if (!active || landed.isEmpty()) {
            core.messages().send(player, falling.isEmpty()
                    ? "&cThere are no active airdrop crates to teleport to."
                    : "&eThe airdrop crates are still falling. Try again once one has landed.");
            return false;
        }

        ActiveDrop nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (ActiveDrop drop : landed.values()) {
            Location crate = drop.target().location();
            if (crate.getWorld() == null) continue;

            double distance;
            if (crate.getWorld().equals(player.getWorld())) {
                distance = crate.distanceSquared(player.getLocation());
            } else {
                // Prefer same-world crates, but still allow recovery when the admin is elsewhere.
                distance = Double.MAX_VALUE / 2.0D;
            }

            if (nearest == null || distance < nearestDistance) {
                nearest = drop;
                nearestDistance = distance;
            }
        }

        if (nearest == null) {
            core.messages().send(player, "&cNo landed airdrop crate could be located.");
            return false;
        }

        Location crate = nearest.target().location();
        Location destination = crate.clone().add(0.5D, 1.0D, 0.5D);
        player.teleportAsync(destination).thenAccept(success -> {
            if (success) {
                core.messages().send(player, "&aTeleported to the nearest active airdrop crate.");
            } else {
                core.messages().send(player, "&cCould not teleport to that airdrop crate.");
            }
        });
        return true;
    }


    private void reconcileActiveDrops() {
        if (!active) return;

        boolean changed = false;

        for (Map.Entry<UUID, ActiveDrop> entry : new ArrayList<>(falling.entrySet())) {
            UUID entityId = entry.getKey();
            ActiveDrop drop = entry.getValue();
            var entity = Bukkit.getEntity(entityId);

            boolean valid = entity instanceof FallingBlock fallingBlock
                    && !fallingBlock.isDead()
                    && drop.payload().id().toString().equals(
                    fallingBlock.getPersistentDataContainer().get(dropKey, PersistentDataType.STRING));

            if (valid) continue;

            falling.remove(entityId);
            if (entity != null) entity.remove();

            if (!respawn(drop.payload(), false)) {
                total = Math.max(0, total - 1);
            }
            changed = true;
        }

        for (Map.Entry<BlockKey, ActiveDrop> entry : new ArrayList<>(landed.entrySet())) {
            ActiveDrop drop = entry.getValue();
            Block block;
            try {
                block = entry.getKey().location().getBlock();
            } catch (IllegalStateException ex) {
                landed.remove(entry.getKey());
                total = Math.max(0, total - 1);
                changed = true;
                continue;
            }

            if (block.getType() == Material.CHEST && isMarkedChest(block, drop.payload().id())) continue;

            landed.remove(entry.getKey());
            if (!respawn(drop.payload(), false)) {
                total = Math.max(0, total - 1);
            }
            changed = true;
        }

        if (changed) {
            saveState();
            checkComplete();
        }
    }

    private void checkComplete() {
        if (!active || remaining() > 0) return;
        active = false;
        broadcast("messages.complete", Map.of());
        audit("COMPLETED", null, "All airdrop crates were claimed.", Map.of(
                "total", Integer.toString(total)));
        total = 0;
        saveState();
    }

    private void broadcastRemaining() {
        if (!active) return;
        broadcast("messages.remaining", Map.of(
                "%remaining%", Integer.toString(remaining()),
                "%total%", Integer.toString(total)));
    }

    private List<ItemStack> rollLoot() {
        int min = clamp(plugin.getConfig().getInt("event.min-loot-items", 1), 1, 5);
        int max = clamp(plugin.getConfig().getInt("event.max-loot-items", 5), min, 5);
        int count = ThreadLocalRandom.current().nextInt(min, max + 1);

        List<ItemStack> shuffled = new ArrayList<>(lootPool.stream().map(ItemStack::clone).toList());
        Collections.shuffle(shuffled);
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (shuffled.isEmpty()) shuffled.addAll(lootPool.stream().map(ItemStack::clone).toList());
            result.add(shuffled.removeFirst());
        }
        return result;
    }

    private Component itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta.hasDisplayName() && meta.displayName() != null) {
            return meta.displayName().decoration(TextDecoration.ITALIC, false);
        }
        return Component.translatable(item.getType().translationKey());
    }

    private void broadcast(String path, Map<String, String> placeholders) {
        String raw = plugin.getConfig().getString(path, "");
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            raw = raw.replace(entry.getKey(), entry.getValue());
        }
        broadcastRaw(raw);
    }

    private void broadcastRaw(String raw) {
        if (raw == null || raw.isBlank()) return;
        Bukkit.broadcast(core.messages().prefix().append(core.messages().parse(raw)));
    }

    private void audit(String action, CommandSender actor, String message, Map<String, String> metadata) {
        UUID actorId = actor instanceof Player player ? player.getUniqueId() : null;
        String actorName = actor == null ? "SYSTEM" : actor.getName();
        core.audit().record("MiraAirdrops", action, actorId, actorName, "airdrop", message, metadata);
    }

    private void markChest(Block block, UUID id) {
        if (!(block.getState() instanceof TileState state)) return;
        state.getPersistentDataContainer().set(dropKey, PersistentDataType.STRING, id.toString());
        state.update(true, false);
    }

    private boolean isMarkedChest(Block block, UUID expected) {
        if (!(block.getState() instanceof TileState state)) return false;
        String id = state.getPersistentDataContainer().get(dropKey, PersistentDataType.STRING);
        return expected.toString().equals(id);
    }

    private void clearMarkedChest(Block block, UUID expected) {
        if (block == null || block.getType() != Material.CHEST) return;
        if (isMarkedChest(block, expected)) block.setType(Material.AIR, false);
    }

    private void removeTransientWorldObjects() {
        for (UUID entityId : new ArrayList<>(falling.keySet())) {
            var entity = Bukkit.getEntity(entityId);
            if (entity != null) entity.remove();
        }
        for (ActiveDrop drop : new ArrayList<>(landed.values())) {
            Block block = drop.target().location().getBlock();
            clearMarkedChest(block, drop.payload().id());
        }
    }

    private void loadLoot() {
        lootPool.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(lootFile);
        List<?> raw = yaml.getList("loot", List.of());
        for (Object object : raw) {
            if (object instanceof ItemStack item && !item.getType().isAir()) lootPool.add(item.clone());
        }
    }

    private void saveLoot() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("loot", lootPool);
        try {
            yaml.save(lootFile);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not save loot.yml", ex);
        }
    }

    private void saveState() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("state.inbound", inbound);
        yaml.set("state.inbound-begins-at", inboundBeginsAt);
        yaml.set("state.active", active);
        yaml.set("state.total", total);

        Map<UUID, ActiveDrop> drops = new LinkedHashMap<>();
        for (ActiveDrop drop : falling.values()) drops.put(drop.payload().id(), drop);
        for (ActiveDrop drop : landed.values()) drops.put(drop.payload().id(), drop);

        for (ActiveDrop drop : drops.values()) {
            String base = "drops." + drop.payload().id();
            yaml.set(base + ".location", drop.target().location());
            yaml.set(base + ".items", drop.payload().items());
        }

        try {
            stateFile.getParentFile().mkdirs();
            yaml.save(stateFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save event-state.yml: " + ex.getMessage());
        }
    }

    private void loadState() {
        if (!stateFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(stateFile);

        boolean savedInbound = yaml.getBoolean("state.inbound", false);
        boolean savedActive = yaml.getBoolean("state.active", false);
        long savedBeginsAt = yaml.getLong("state.inbound-begins-at", 0L);
        int savedTotal = Math.max(0, yaml.getInt("state.total", 0));

        List<ActiveDrop> savedDrops = new ArrayList<>();
        ConfigurationSection section = yaml.getConfigurationSection("drops");
        if (section != null) {
            for (String idText : section.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(idText);
                    Location location = yaml.getLocation("drops." + idText + ".location");
                    if (location == null || location.getWorld() == null) continue;

                    List<ItemStack> items = new ArrayList<>();
                    for (Object object : yaml.getList("drops." + idText + ".items", List.of())) {
                        if (object instanceof ItemStack item && !item.getType().isAir()) items.add(item.clone());
                    }
                    if (items.isEmpty()) continue;

                    savedDrops.add(new ActiveDrop(new DropPayload(id, items), BlockKey.of(location)));
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("Skipped invalid persisted airdrop id " + idText);
                }
            }
        }

        if (savedActive && !savedDrops.isEmpty()) {
            active = true;
            total = Math.max(savedTotal, savedDrops.size());
            Bukkit.getScheduler().runTask(plugin, () -> restoreDrops(savedDrops));
            return;
        }

        if (savedInbound) {
            inbound = true;
            inboundBeginsAt = savedBeginsAt;
            long remainingMillis = Math.max(0L, savedBeginsAt - System.currentTimeMillis());
            long ticks = Math.max(1L, (remainingMillis + 49L) / 50L);
            scheduleInbound(ticks);
        }
    }

    private void restoreDrops(List<ActiveDrop> savedDrops) {
        if (!active) return;

        int restored = 0;
        for (ActiveDrop drop : savedDrops) {
            Block block = drop.target().location().getBlock();

            if (block.getType() == Material.CHEST && isMarkedChest(block, drop.payload().id())) {
                landed.put(drop.target(), drop);
                restored++;
                continue;
            }

            // Restored/missing crates always re-enter through the configured Y=110 spawn selector.
            // A persisted landed location must never become a new low-altitude falling spawn.
            if (respawn(drop.payload(), false)) restored++;
        }

        if (restored == 0) {
            active = false;
            total = 0;
            broadcastRaw("&cThe persisted airdrop could not be restored safely and was cancelled.");
            audit("RESTORE_FAILED", null, "Persisted airdrop could not be restored.", Map.of());
        } else {
            total = Math.max(restored, total - (savedDrops.size() - restored));
            plugin.getLogger().info("Restored " + restored + " persisted airdrop crate(s).");
        }

        saveState();
        checkComplete();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record DropPayload(UUID id, List<ItemStack> items) {
        private DropPayload {
            items = items.stream().filter(Objects::nonNull).map(ItemStack::clone).toList();
        }
    }

    private record ActiveDrop(DropPayload payload, BlockKey target) { }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey of(Location location) {
            return new BlockKey(Objects.requireNonNull(location.getWorld()).getUID(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        Location location() {
            World world = Bukkit.getWorld(worldId);
            if (world == null) throw new IllegalStateException("Airdrop world is unavailable");
            return new Location(world, x, y, z);
        }

        boolean sameColumn(BlockKey other) {
            return other != null && worldId.equals(other.worldId) && x == other.x && z == other.z;
        }
    }
}
