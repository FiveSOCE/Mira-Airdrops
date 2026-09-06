package com.mira.airdrops;

import org.bukkit.Location;

public interface WarzoneResolver {
    boolean available();
    boolean isWarZone(Location location);
}
