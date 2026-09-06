package com.mira.airdrops.hook;

import com.mira.airdrops.WarzoneResolver;
import com.mira.factions.api.MiraFactionsApi;
import org.bukkit.Bukkit;
import org.bukkit.Location;

public final class MiraFactionsWarzoneBridge implements WarzoneResolver {
    private final MiraFactionsApi factions;

    public MiraFactionsWarzoneBridge() {
        this.factions = Bukkit.getServicesManager().load(MiraFactionsApi.class);
    }

    @Override
    public boolean available() {
        return factions != null;
    }

    @Override
    public boolean isWarZone(Location location) {
        return factions != null && factions.isWarZone(location);
    }
}
