package com.mira.airdrops;

import com.mira.core.api.MiraCore;
import com.mira.core.api.RewardService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
    private final List<ItemStack> lootPool = new ArrayList<>();
    private final Map<UUID, DropPayload> falling = new HashMap<>();
    private final Map<BlockKey, DropPayload> landed = new HashMap<>();

    private boolean inbound;
    private boolean active;
    private int total;
    private BukkitTask inboundTask;
    private BukkitTask autoTask;

    public AirdropService(MiraAirdropsPlugin plugin, MiraCore core, RegionService regions) {
        this.plugin = plugin;
        this.core = core;
        this.regions = regions;
        this.lootFile = new File(plugin.getDataFolder(), "loot.yml");
        loadLoot();
        scheduleAuto();
    }

    public boolean inbound() { return inbound; }
    public boolean active() { return active; }
    public int total() { return total; }
    public int remaining() { return falling.size() + landed.size(); }
    public List<ItemStack> lootPool() { return lootPool.stream().map(ItemStack::clone).toList(); }

    public void setLootPool(Collection<ItemStack> items) {
        lootPool.clear();
        if (items != null) {
            items.stream().filter(Objects::nonNull).filter(item -> !item.getType().isAir())
                    .limit(45).map(ItemStack::clone).forEach(lootPool::add);
        }
        saveLoot();
    }

    public boolean start(Player actor) {
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
        broadcast("messages.inbound", Map.of("%seconds%", Integer.toString(seconds)));
        inboundTask = Bukkit.getScheduler().runTaskLater(plugin, this::beginNow, seconds * 20L);
        return true;
    }

    private void beginNow() {
        inbound = false;
        inboundTask = null;
        if (active) return;

        int min = clamp(plugin.getConfig().getInt("event.min-crates", 20), 20, 50);
        int max = clamp(plugin.getConfig().getInt("event.max-crates", 50), min, 50);
        int requested = ThreadLocalRandom.current().nextInt(min, max + 1);

        total = 0;
        active = true;
        for (int i = 0; i < requested; i++) {
            DropPayload payload = new DropPayload(UUID.randomUUID(), rollLoot());
            if (spawnFalling(payload)) total++;
        }

        if (total == 0) {
            active = false;
            Bukkit.broadcast(core.messages().prefix().append(core.messages().parse(
                    "&cAirdrop aborted because no valid drop locations could be found.")));
            return;
        }

        broadcast("messages.started", Map.of("%total%", Integer.toString(total)));
        broadcastRemaining();
    }

    public void cancel(boolean announce) {
        if (inboundTask != null) inboundTask.cancel();
        inboundTask = null;
        inbound = false;

        for (UUID entityId : new ArrayList<>(falling.keySet())) {
            var entity = Bukkit.getEntity(entityId);
            if (entity != null) entity.remove();
        }
        falling.clear();

        for (BlockKey key : new ArrayList<>(landed.keySet())) {
            Block block = key.location().getBlock();
            if (block.getType() == Material.CHEST) block.setType(Material.AIR, false);
        }
        landed.clear();
        boolean wasActive = active;
        active = false;
        total = 0;
        if (announce && wasActive) broadcast("messages.cancelled", Map.of());
    }

    public void shutdown() {
        cancel(false);
        if (autoTask != null) autoTask.cancel();
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

    private boolean spawnFalling(DropPayload payload) {
        int attempts = Math.max(25, plugin.getConfig().getInt("event.max-placement-attempts-per-crate", 250));
        for (int i = 0; i < attempts; i++) {
            Location target = regions.randomLanding();
            if (target == null || !target.getBlock().getType().isAir()) continue;
            World world = target.getWorld();
            if (world == null) continue;
            double spawnY = Math.min(world.getMaxHeight() - 2.0D, target.getY() + 25.0D);
            if (spawnY <= target.getY()) spawnY = target.getY() + 1.0D;

            FallingBlock entity = world.spawnFallingBlock(
                    new Location(world, target.getBlockX() + 0.5D, spawnY, target.getBlockZ() + 0.5D),
                    Material.CHEST.createBlockData());
            entity.setDropItem(false);
            entity.setHurtEntities(false);
            falling.put(entity.getUniqueId(), payload);
            return true;
        }
        return false;
    }

    public void handleLanding(FallingBlock entity, EntityChangeBlockEvent event) {
        DropPayload payload = falling.remove(entity.getUniqueId());
        if (payload == null) return;

        if (!event.getBlock().getType().isAir()) {
            event.setCancelled(true);
            entity.remove();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (active && !spawnFalling(payload)) {
                    total = Math.max(0, total - 1);
                    checkComplete();
                }
            });
            return;
        }

        Location landing = event.getBlock().getLocation();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!active) {
                if (landing.getBlock().getType() == Material.CHEST) landing.getBlock().setType(Material.AIR, false);
                return;
            }
            if (landing.getBlock().getType() != Material.CHEST) {
                if (!spawnFalling(payload)) {
                    total = Math.max(0, total - 1);
                    checkComplete();
                }
                return;
            }
            landed.put(BlockKey.of(landing), payload);
        });
    }

    public boolean claim(Player player, Block block) {
        if (!active || block == null) return false;
        BlockKey key = BlockKey.of(block.getLocation());
        DropPayload payload = landed.remove(key);
        if (payload == null) return false;

        block.setType(Material.AIR, false);

        RewardService rewardService = core.rewards();
        UUID rewardId = rewardService.queue(player.getUniqueId(), "MiraAirdrops", "Airdrop Cache",
                payload.items(), List.of());
        RewardService.ClaimResult result = rewardService.claim(player, rewardId);

        player.sendMessage(core.messages().prefix()
                .append(core.messages().parse(plugin.getConfig().getString("messages.claim-prefix",
                        "&6&lAirdrop &8>> &aYou found: "))));
        for (ItemStack item : payload.items()) {
            player.sendMessage(Component.text(" • ")
                    .append(itemName(item))
                    .append(Component.text(" x" + item.getAmount())));
        }
        if (!result.complete()) {
            player.sendMessage(core.messages().prefix().append(core.messages().parse(
                    plugin.getConfig().getString("messages.overflow",
                            "&eSome rewards did not fit and were moved to &f/rewards&e."))));
        }

        broadcastRemaining();
        checkComplete();
        return true;
    }

    private void checkComplete() {
        if (!active || remaining() > 0) return;
        active = false;
        broadcast("messages.complete", Map.of());
        total = 0;
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
        for (Map.Entry<String, String> entry : placeholders.entrySet()) raw = raw.replace(entry.getKey(), entry.getValue());
        Bukkit.broadcast(core.messages().parse(raw));
    }

    private void loadLoot() {
        lootPool.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(lootFile);
        List<?> raw = yaml.getList("loot", List.of());
        for (Object object : raw) if (object instanceof ItemStack item && !item.getType().isAir()) lootPool.add(item.clone());
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record DropPayload(UUID id, List<ItemStack> items) {
        private DropPayload {
            items = items.stream().map(ItemStack::clone).toList();
        }
    }

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
    }
}
