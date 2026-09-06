package com.mira.airdrops;

import com.mira.core.api.MiraCore;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

public final class AirdropCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("gui", "start", "cancel", "status");

    private final MiraCore core;
    private final AirdropService service;
    private final AirdropGuiService gui;
    private final RegionService regions;

    public AirdropCommand(MiraCore core, AirdropService service, AirdropGuiService gui, RegionService regions) {
        this.core = core;
        this.service = service;
        this.gui = gui;
        this.regions = regions;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String action = args.length == 0 ? "gui" : args[0].toLowerCase(Locale.ROOT);

        if (action.equals("status")) {
            if (!sender.hasPermission("miraairdrops.status")) {
                core.messages().send(sender, "&cYou do not have permission.");
                return true;
            }
            core.messages().send(sender, "&6MiraAirdrops");
            core.messages().send(sender, "&7State: &f" + (service.active() ? "ACTIVE" : service.inbound() ? "INBOUND" : "IDLE"));
            core.messages().send(sender, "&7Remaining: &f" + service.remaining() + "/" + service.total());
            core.messages().send(sender, "&7Region: &f" + regions.summary());
            return true;
        }

        if (!sender.hasPermission("miraairdrops.admin")) {
            core.messages().send(sender, "&cYou do not have permission.");
            return true;
        }

        switch (action) {
            case "gui" -> {
                if (!(sender instanceof Player player)) {
                    core.messages().send(sender, "&cPlayers only for the GUI.");
                } else {
                    gui.openMain(player);
                }
            }
            case "start" -> service.start(sender);
            case "cancel" -> service.cancel(sender, true);
            default -> core.messages().send(sender, "&7/airdrop <gui|start|cancel|status>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream()
                .filter(sub -> (sub.equals("status") ? sender.hasPermission("miraairdrops.status")
                        : sender.hasPermission("miraairdrops.admin")))
                .filter(sub -> sub.startsWith(prefix))
                .toList();
    }
}
