package com.mira.airdrops;

import org.bukkit.Material;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;

public final class AirdropListener implements Listener {
    private final MiraAirdropsPlugin plugin;
    private final AirdropService service;
    private final AirdropGuiService gui;

    public AirdropListener(MiraAirdropsPlugin plugin, AirdropService service, AirdropGuiService gui) {
        this.plugin = plugin;
        this.service = service;
        this.gui = gui;
    }

    @EventHandler(ignoreCancelled = true)
    public void onLand(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof FallingBlock falling)) return;
        service.handleLanding(falling, event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClaim(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.CHEST) return;
        if (service.claim(event.getPlayer(), event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof AirdropHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        if (!player.hasPermission("miraairdrops.admin")) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        int raw = event.getRawSlot();

        if (holder.type() == AirdropHolder.Type.LOOT) {
            if (raw >= topSize) return;
            if (raw >= 0 && raw < 45) return;

            event.setCancelled(true);
            if (raw == 48) {
                gui.saveLoot(player, event.getView().getTopInventory());
            } else if (raw == 50) {
                gui.openMain(player);
            }
            return;
        }

        event.setCancelled(true);
        if (raw < 0 || raw >= topSize) return;
        gui.handleMain(player, event);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof AirdropHolder holder)) return;
        if (holder.type() != AirdropHolder.Type.LOOT) {
            event.setCancelled(true);
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        boolean touchesReserved = event.getRawSlots().stream()
                .anyMatch(slot -> slot < topSize && slot >= 45);
        if (touchesReserved) event.setCancelled(true);
    }
}
