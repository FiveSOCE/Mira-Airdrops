package com.mira.airdrops;

import com.mira.core.api.MiraCore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class AirdropGuiService {
    private final MiraAirdropsPlugin plugin;
    private final MiraCore core;
    private final AirdropService service;
    private final RegionService regions;

    public AirdropGuiService(MiraAirdropsPlugin plugin, MiraCore core, AirdropService service, RegionService regions) {
        this.plugin = plugin;
        this.core = core;
        this.service = service;
        this.regions = regions;
    }

    public void openMain(Player player) {
        AirdropHolder holder = new AirdropHolder(AirdropHolder.Type.MAIN);
        Inventory inv = Bukkit.createInventory(holder, 54, core.messages().parse("&5MiraAirdrops Control"));
        holder.bind(inv);

        String state = service.active() ? "&aACTIVE" : service.inbound() ? "&eINBOUND" : "&7IDLE";
        inv.setItem(10, item(Material.BEACON, "&fStatus", List.of(
                "&7State: " + state,
                "&7Remaining: &f" + service.remaining(),
                "&7Region: &f" + regions.summary(),
                "&7Loot entries: &f" + service.lootPool().size())));

        inv.setItem(12, item(Material.LIME_DYE, "&aStart Airdrop", List.of("&7Begins the configured inbound countdown.")));
        inv.setItem(13, item(Material.BARRIER, "&cCancel Airdrop", List.of("&7Cancels inbound/active event and removes crates.")));

        inv.setItem(15, item(Material.COMPASS, "&fRegion Mode: &d" + regions.mode(), List.of(
                "&eClick to toggle",
                "&7WARZONE uses MiraFactions.",
                "&7WORLDEDIT uses the saved selection.")));
        inv.setItem(16, item(Material.WOODEN_AXE, "&fCapture WorldEdit Selection", List.of(
                "&7Uses your current WorldEdit selection.",
                "&7The cuboid is saved into MiraAirdrops.")));
        inv.setItem(17, item(Material.GRASS_BLOCK, "&fSet Warzone World", List.of(
                "&7Current: &f" + plugin.getConfig().getString("region.warzone-world", "world"),
                "&eClick to use your current world.")));

        inv.setItem(20, numberItem(Material.CHEST, "Minimum Crates", "event.min-crates", 20));
        inv.setItem(21, numberItem(Material.ENDER_CHEST, "Maximum Crates", "event.max-crates", 50));
        inv.setItem(23, numberItem(Material.HOPPER, "Minimum Loot Items", "event.min-loot-items", 1));
        inv.setItem(24, numberItem(Material.BUNDLE, "Maximum Loot Items", "event.max-loot-items", 5));

        boolean auto = plugin.getConfig().getBoolean("event.auto-enabled", false);
        inv.setItem(28, item(auto ? Material.CLOCK : Material.GRAY_DYE, "&fAutomatic Airdrops: " + (auto ? "&aON" : "&cOFF"),
                List.of("&eClick to toggle")));
        inv.setItem(29, item(Material.REPEATER, "&fInterval: &d"
                        + plugin.getConfig().getLong("event.interval-minutes", 120L) + " minutes",
                List.of("&eLeft-click: +15 minutes", "&eRight-click: -15 minutes", "&7Minimum: 15 minutes")));

        inv.setItem(32, item(Material.CHEST_MINECART, "&dEdit Loot Pool", List.of(
                "&7Current entries: &f" + service.lootPool().size(),
                "&eClick to edit real ItemStacks.")));
        inv.setItem(34, item(Material.ENDER_PEARL, "&dTeleport to nearest Crate", List.of(
                "&7Landed crates: &f" + service.landedCount(),
                "&7Each click resolves the nearest crate still active.",
                "&7Claimed/removed crates are skipped automatically.")));
        inv.setItem(49, item(Material.BARRIER, "&cClose", List.of()));
        player.openInventory(inv);
    }

    public void openLoot(Player player) {
        AirdropHolder holder = new AirdropHolder(AirdropHolder.Type.LOOT);
        Inventory inv = Bukkit.createInventory(holder, 54, core.messages().parse("&5Airdrop Loot Pool"));
        holder.bind(inv);
        List<ItemStack> loot = service.lootPool();
        for (int i = 0; i < loot.size() && i < 45; i++) inv.setItem(i, loot.get(i));
        inv.setItem(48, item(Material.EMERALD_BLOCK, "&aSave Loot Pool", List.of("&7Saves slots 1-45 as equal-chance loot entries.")));
        inv.setItem(50, item(Material.BARRIER, "&cCancel", List.of("&7Discard this GUI's changes.")));
        player.openInventory(inv);
    }

    public void handleMain(Player player, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        ClickType click = event.getClick();
        switch (slot) {
            case 12 -> service.start(player);
            case 13 -> service.cancel(true);
            case 15 -> regions.toggleMode();
            case 16 -> {
                boolean ok = regions.captureWorldEdit(player);
                core.messages().send(player, ok
                        ? "&aSaved your current WorldEdit selection as the airdrop region."
                        : "&cCould not capture a complete WorldEdit selection.");
            }
            case 17 -> {
                regions.setWarzoneWorld(player.getWorld());
                core.messages().send(player, "&aWarzone sampling world set to &f" + player.getWorld().getName() + "&a.");
            }
            case 20 -> adjust("event.min-crates", click, 5, 20, 50);
            case 21 -> adjust("event.max-crates", click, 5, 20, 50);
            case 23 -> adjust("event.min-loot-items", click, 1, 1, 5);
            case 24 -> adjust("event.max-loot-items", click, 1, 1, 5);
            case 28 -> {
                plugin.getConfig().set("event.auto-enabled",
                        !plugin.getConfig().getBoolean("event.auto-enabled", false));
                plugin.saveConfig();
                service.rescheduleAuto();
            }
            case 29 -> {
                long current = plugin.getConfig().getLong("event.interval-minutes", 120L);
                long next = click.isRightClick() ? Math.max(15L, current - 15L) : current + 15L;
                plugin.getConfig().set("event.interval-minutes", next);
                plugin.saveConfig();
                service.rescheduleAuto();
            }
            case 32 -> { openLoot(player); return; }
            case 34 -> {
                player.closeInventory();
                service.teleportToNearestCrate(player);
                return;
            }
            case 49 -> { player.closeInventory(); return; }
            default -> { return; }
        }

        normalizeRanges();
        plugin.saveConfig();
        openMain(player);
    }

    public void saveLoot(Player player, Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        for (int slot = 0; slot < 45; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && !item.getType().isAir()) items.add(item.clone());
        }
        service.setLootPool(items);
        core.messages().send(player, "&aSaved &f" + items.size() + " &aairdrop loot entries.");
        openMain(player);
    }

    private void adjust(String path, ClickType click, int step, int min, int max) {
        int current = plugin.getConfig().getInt(path, min);
        int next = click.isRightClick() ? current - step : current + step;
        plugin.getConfig().set(path, Math.max(min, Math.min(max, next)));
    }

    private void normalizeRanges() {
        int minCrates = plugin.getConfig().getInt("event.min-crates", 20);
        int maxCrates = plugin.getConfig().getInt("event.max-crates", 50);
        if (minCrates > maxCrates) plugin.getConfig().set("event.max-crates", minCrates);

        int minLoot = plugin.getConfig().getInt("event.min-loot-items", 1);
        int maxLoot = plugin.getConfig().getInt("event.max-loot-items", 5);
        if (minLoot > maxLoot) plugin.getConfig().set("event.max-loot-items", minLoot);
    }

    private ItemStack numberItem(Material material, String label, String path, int fallback) {
        return item(material, "&f" + label + ": &d" + plugin.getConfig().getInt(path, fallback),
                List.of("&eLeft-click: increase", "&eRight-click: decrease"));
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(core.messages().parse(name).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> core.messages().parse(line).decoration(TextDecoration.ITALIC, false)).toList());
        stack.setItemMeta(meta);
        return stack;
    }
}
