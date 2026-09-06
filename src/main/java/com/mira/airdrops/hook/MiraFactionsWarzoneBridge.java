package com.mira.airdrops.hook;

import com.mira.airdrops.WarzoneResolver;
import com.mira.core.api.MiraCore;
import com.mira.factions.api.MiraFactionsApi;
import org.bukkit.Location;

public final class MiraFactionsWarzoneBridge implements WarzoneResolver {
    private final MiraFactionsApi factions;

    public MiraFactionsWarzoneBridge(MiraCore core) {
        this.factions = core.services().get(MiraFactionsApi.class).orElse(null);
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
