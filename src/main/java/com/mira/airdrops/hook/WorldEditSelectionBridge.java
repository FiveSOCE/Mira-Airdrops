package com.mira.airdrops.hook;

import com.mira.airdrops.SelectionBounds;
import com.mira.airdrops.WorldEditSelectionCapture;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.entity.Player;

public final class WorldEditSelectionBridge implements WorldEditSelectionCapture {
    @Override
    public SelectionBounds capture(Player player) {
        try {
            var actor = BukkitAdapter.adapt(player);
            var session = WorldEdit.getInstance().getSessionManager().get(actor);
            var selectionWorld = session.getSelectionWorld();
            if (selectionWorld == null) return null;
            Region region = session.getSelection(selectionWorld);
            return new SelectionBounds(
                    selectionWorld.getName(),
                    region.getMinimumPoint().x(),
                    region.getMinimumPoint().y(),
                    region.getMinimumPoint().z(),
                    region.getMaximumPoint().x(),
                    region.getMaximumPoint().y(),
                    region.getMaximumPoint().z());
        } catch (IncompleteRegionException ex) {
            return null;
        }
    }
}
