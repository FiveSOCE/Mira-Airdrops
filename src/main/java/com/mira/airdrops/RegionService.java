package com.mira.airdrops;

import com.mira.core.api.MiraCore;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public final class RegionService {
    private final MiraAirdropsPlugin plugin;
    private final MiraCore core;
    private final WarzoneResolver warzoneResolver;
    private final WorldEditSelectionCapture worldEditCapture;

    public RegionService(MiraAirdropsPlugin plugin, MiraCore core) {
        this.plugin = plugin;
        this.core = core;
        this.warzoneResolver = loadWarzoneResolver();
        this.worldEditCapture = loadWorldEditCapture();
    }

    public RegionMode mode() {
        try {
            return RegionMode.valueOf(plugin.getConfig().getString("region.mode", "WARZONE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return RegionMode.WARZONE;
        }
    }

    public void toggleMode() {
        plugin.getConfig().set("region.mode", mode() == RegionMode.WARZONE ? "WORLDEDIT" : "WARZONE");
        plugin.saveConfig();
    }

    public boolean captureWorldEdit(Player player) {
        if (worldEditCapture == null) return false;
        SelectionBounds bounds;
        try {
            bounds = worldEditCapture.capture(player);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("WorldEdit selection capture failed: " + throwable.getMessage());
            return false;
        }
        if (bounds == null) return false;

        FileConfiguration cfg = plugin.getConfig();
        cfg.set("region.worldedit.world", bounds.world());
        cfg.set("region.worldedit.min-x", bounds.minX());
        cfg.set("region.worldedit.min-y", bounds.minY());
        cfg.set("region.worldedit.min-z", bounds.minZ());
        cfg.set("region.worldedit.max-x", bounds.maxX());
        cfg.set("region.worldedit.max-y", bounds.maxY());
        cfg.set("region.worldedit.max-z", bounds.maxZ());
        cfg.set("region.worldedit.configured", true);
        plugin.saveConfig();
        return true;
    }

    public void setWarzoneWorld(World world) {
        plugin.getConfig().set("region.warzone-world", world.getName());
        plugin.saveConfig();
    }

    public boolean ready() {
        if (mode() == RegionMode.WORLDEDIT) {
            if (!plugin.getConfig().getBoolean("region.worldedit.configured", false)) return false;
            return Bukkit.getWorld(plugin.getConfig().getString("region.worldedit.world", "")) != null;
        }

        return Bukkit.getWorld(plugin.getConfig().getString("region.warzone-world", "world")) != null
                && warzoneResolver != null
                && warzoneResolver.available();
    }

    public Location randomLanding() {
        return mode() == RegionMode.WORLDEDIT ? randomWorldEdit() : randomWarzone();
    }

    private Location randomWorldEdit() {
        FileConfiguration cfg = plugin.getConfig();
        World world = Bukkit.getWorld(cfg.getString("region.worldedit.world", ""));
        if (world == null) return null;

        int minX = Math.min(cfg.getInt("region.worldedit.min-x"), cfg.getInt("region.worldedit.max-x"));
        int maxX = Math.max(cfg.getInt("region.worldedit.min-x"), cfg.getInt("region.worldedit.max-x"));
        int minZ = Math.min(cfg.getInt("region.worldedit.min-z"), cfg.getInt("region.worldedit.max-z"));
        int maxZ = Math.max(cfg.getInt("region.worldedit.min-z"), cfg.getInt("region.worldedit.max-z"));
        int minY = Math.min(cfg.getInt("region.worldedit.min-y"), cfg.getInt("region.worldedit.max-y"));
        int maxY = Math.max(cfg.getInt("region.worldedit.min-y"), cfg.getInt("region.worldedit.max-y"));

        for (int attempt = 0; attempt < 100; attempt++) {
            int x = ThreadLocalRandom.current().nextInt(minX, maxX + 1);
            int z = ThreadLocalRandom.current().nextInt(minZ, maxZ + 1);
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (y < minY || y > maxY || y >= world.getMaxHeight()) continue;

            Location target = new Location(world, x, y, z);
            if (target.getBlock().getType().isAir()) return target;
        }
        return null;
    }

    private Location randomWarzone() {
        World world = Bukkit.getWorld(plugin.getConfig().getString("region.warzone-world", "world"));
        if (world == null || warzoneResolver == null || !warzoneResolver.available()) return null;

        var loaded = new java.util.ArrayList<org.bukkit.Chunk>();
        for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
            Location claimProbe = new Location(world, (chunk.getX() << 4) + 8, world.getMinHeight(), (chunk.getZ() << 4) + 8);
            if (warzoneResolver.isWarZone(claimProbe)) loaded.add(chunk);
        }
        if (loaded.isEmpty()) return null;

        int attempts = Math.max(50, plugin.getConfig().getInt("region.warzone-search-attempts", 2000));
        for (int attempt = 0; attempt < attempts; attempt++) {
            org.bukkit.Chunk chunk = loaded.get(ThreadLocalRandom.current().nextInt(loaded.size()));
            int x = (chunk.getX() << 4) + ThreadLocalRandom.current().nextInt(16);
            int z = (chunk.getZ() << 4) + ThreadLocalRandom.current().nextInt(16);

            // This chunk is already loaded, so terrain lookup cannot synchronously generate a new chunk.
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (y >= world.getMaxHeight()) continue;

            Location target = new Location(world, x, y, z);
            if (target.getBlock().getType().isAir()) return target;
        }
        return null;
    }

    public String summary() {
        if (mode() == RegionMode.WARZONE) {
            String availability = warzoneResolver == null || !warzoneResolver.available() ? ", integration unavailable" : "";
            return "MiraFactions Warzone (" + plugin.getConfig().getString("region.warzone-world", "world") + availability + ")";
        }
        if (!plugin.getConfig().getBoolean("region.worldedit.configured", false)) return "WorldEdit (not configured)";
        return "WorldEdit (" + plugin.getConfig().getString("region.worldedit.world", "?") + ")";
    }

    private WarzoneResolver loadWarzoneResolver() {
        if (!Bukkit.getPluginManager().isPluginEnabled("MiraFactions")) return null;
        try {
            Class<?> type = Class.forName("com.mira.airdrops.hook.MiraFactionsWarzoneBridge");
            Object instance = type.getConstructor().newInstance();
            return instance instanceof WarzoneResolver resolver ? resolver : null;
        } catch (Throwable throwable) {
            plugin.getLogger().warning("MiraFactions WarZone integration unavailable: " + throwable.getMessage());
            return null;
        }
    }

    private WorldEditSelectionCapture loadWorldEditCapture() {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) return null;
        try {
            Class<?> type = Class.forName("com.mira.airdrops.hook.WorldEditSelectionBridge");
            Object instance = type.getConstructor().newInstance();
            return instance instanceof WorldEditSelectionCapture capture ? capture : null;
        } catch (Throwable throwable) {
            plugin.getLogger().warning("WorldEdit selection integration unavailable: " + throwable.getMessage());
            return null;
        }
    }
}
