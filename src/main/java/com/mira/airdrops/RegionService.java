package com.mira.airdrops;

import com.mira.core.api.MiraCore;
import com.mira.factions.api.MiraFactionsApi;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.regions.Region;
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

    public RegionService(MiraAirdropsPlugin plugin, MiraCore core) {
        this.plugin = plugin;
        this.core = core;
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
        if (Bukkit.getPluginManager().getPlugin("WorldEdit") == null) return false;
        try {
            var actor = BukkitAdapter.adapt(player);
            var session = WorldEdit.getInstance().getSessionManager().get(actor);
            var selectionWorld = session.getSelectionWorld();
            if (selectionWorld == null) return false;
            Region region = session.getSelection(selectionWorld);
            FileConfiguration cfg = plugin.getConfig();
            cfg.set("region.worldedit.world", selectionWorld.getName());
            cfg.set("region.worldedit.min-x", region.getMinimumPoint().x());
            cfg.set("region.worldedit.min-y", region.getMinimumPoint().y());
            cfg.set("region.worldedit.min-z", region.getMinimumPoint().z());
            cfg.set("region.worldedit.max-x", region.getMaximumPoint().x());
            cfg.set("region.worldedit.max-y", region.getMaximumPoint().y());
            cfg.set("region.worldedit.max-z", region.getMaximumPoint().z());
            cfg.set("region.worldedit.configured", true);
            plugin.saveConfig();
            return true;
        } catch (IncompleteRegionException ex) {
            return false;
        }
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
                && core.services().get(MiraFactionsApi.class).isPresent();
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
        MiraFactionsApi factions = core.services().get(MiraFactionsApi.class).orElse(null);
        if (world == null || factions == null) return null;

        WorldBorder border = world.getWorldBorder();
        double half = border.getSize() / 2.0D - 16.0D;
        double cx = border.getCenter().getX();
        double cz = border.getCenter().getZ();

        for (int attempt = 0; attempt < 250; attempt++) {
            int x = (int) Math.floor(ThreadLocalRandom.current().nextDouble(cx - half, cx + half));
            int z = (int) Math.floor(ThreadLocalRandom.current().nextDouble(cz - half, cz + half));
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (y >= world.getMaxHeight()) continue;
            Location target = new Location(world, x, y, z);
            if (!target.getBlock().getType().isAir()) continue;
            if (factions.isWarZone(target)) return target;
        }
        return null;
    }

    public String summary() {
        if (mode() == RegionMode.WARZONE) {
            return "MiraFactions Warzone (" + plugin.getConfig().getString("region.warzone-world", "world") + ")";
        }
        if (!plugin.getConfig().getBoolean("region.worldedit.configured", false)) return "WorldEdit (not configured)";
        return "WorldEdit (" + plugin.getConfig().getString("region.worldedit.world", "?") + ")";
    }
}
