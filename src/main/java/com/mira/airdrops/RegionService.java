package com.mira.airdrops;

import com.mira.core.api.MiraCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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

    public Location randomSpawn() {
        return mode() == RegionMode.WORLDEDIT ? randomWorldEditSpawn() : randomWarzoneSpawn();
    }

    private Location randomWorldEditSpawn() {
        FileConfiguration cfg = plugin.getConfig();
        World world = Bukkit.getWorld(cfg.getString("region.worldedit.world", ""));
        if (world == null) return null;

        int minX = Math.min(cfg.getInt("region.worldedit.min-x"), cfg.getInt("region.worldedit.max-x"));
        int maxX = Math.max(cfg.getInt("region.worldedit.min-x"), cfg.getInt("region.worldedit.max-x"));
        int minZ = Math.min(cfg.getInt("region.worldedit.min-z"), cfg.getInt("region.worldedit.max-z"));
        int maxZ = Math.max(cfg.getInt("region.worldedit.min-z"), cfg.getInt("region.worldedit.max-z"));
        int spawnY = spawnY(world);
        if (spawnY < world.getMinHeight() || spawnY >= world.getMaxHeight()) return null;

        int minChunkX = Math.floorDiv(minX, 16);
        int maxChunkX = Math.floorDiv(maxX, 16);
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkZ = Math.floorDiv(maxZ, 16);

        var loaded = new java.util.ArrayList<org.bukkit.Chunk>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    loaded.add(world.getChunkAt(chunkX, chunkZ));
                }
            }
        }
        if (loaded.isEmpty()) return null;

        int attempts = Math.max(50, plugin.getConfig().getInt("event.max-placement-attempts-per-crate", 250));
        for (int attempt = 0; attempt < attempts; attempt++) {
            org.bukkit.Chunk chunk = loaded.get(ThreadLocalRandom.current().nextInt(loaded.size()));
            int chunkMinX = Math.max(minX, chunk.getX() << 4);
            int chunkMaxX = Math.min(maxX, (chunk.getX() << 4) + 15);
            int chunkMinZ = Math.max(minZ, chunk.getZ() << 4);
            int chunkMaxZ = Math.min(maxZ, (chunk.getZ() << 4) + 15);
            if (chunkMinX > chunkMaxX || chunkMinZ > chunkMaxZ) continue;

            int x = ThreadLocalRandom.current().nextInt(chunkMinX, chunkMaxX + 1);
            int z = ThreadLocalRandom.current().nextInt(chunkMinZ, chunkMaxZ + 1);
            Location spawn = new Location(world, x, spawnY, z);
            if (spawn.getBlock().getType().isAir()) return spawn;
        }
        return null;
    }

    private Location randomWarzoneSpawn() {
        World world = Bukkit.getWorld(plugin.getConfig().getString("region.warzone-world", "world"));
        if (world == null || warzoneResolver == null || !warzoneResolver.available()) return null;

        int spawnY = spawnY(world);
        if (spawnY < world.getMinHeight() || spawnY >= world.getMaxHeight()) return null;

        var loaded = new java.util.ArrayList<org.bukkit.Chunk>();
        for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
            Location claimProbe = new Location(world, (chunk.getX() << 4) + 8, spawnY, (chunk.getZ() << 4) + 8);
            if (warzoneResolver.isWarZone(claimProbe)) loaded.add(chunk);
        }
        if (loaded.isEmpty()) return null;

        int attempts = Math.max(50, plugin.getConfig().getInt("region.warzone-search-attempts", 2000));
        for (int attempt = 0; attempt < attempts; attempt++) {
            org.bukkit.Chunk chunk = loaded.get(ThreadLocalRandom.current().nextInt(loaded.size()));
            int x = (chunk.getX() << 4) + ThreadLocalRandom.current().nextInt(16);
            int z = (chunk.getZ() << 4) + ThreadLocalRandom.current().nextInt(16);
            Location spawn = new Location(world, x, spawnY, z);

            // Special-zone chunks are kept loaded by MiraFactions. Only spawn into literal air.
            if (spawn.getBlock().getType().isAir() && warzoneResolver.isWarZone(spawn)) return spawn;
        }
        return null;
    }

    private int spawnY(World world) {
        return plugin.getConfig().getInt("event.spawn-y", 110);
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
