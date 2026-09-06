package com.mira.airdrops;

import com.mira.core.api.MiraCore;
import com.mira.core.api.MiraCoreProvider;
import com.mira.core.api.ModuleHealth;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class MiraAirdropsPlugin extends JavaPlugin {
    private MiraCore core;
    private RegionService regions;
    private AirdropService service;
    private AirdropGuiService gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("loot.yml", false);

        core = MiraCoreProvider.require();
        regions = new RegionService(this, core);
        service = new AirdropService(this, core, regions);
        gui = new AirdropGuiService(this, core, service, regions);

        core.modules().register(this, "MiraAirdrops");
        getServer().getPluginManager().registerEvents(new AirdropListener(this, service, gui), this);

        PluginCommand command = getCommand("airdrop");
        if (command == null) {
            core.modules().setHealth(this, ModuleHealth.UNHEALTHY, "airdrop command missing from plugin.yml");
            throw new IllegalStateException("airdrop command missing from plugin.yml");
        }
        command.setExecutor(new AirdropCommand(core, service, gui, regions));

        core.modules().setHealth(this, ModuleHealth.HEALTHY,
                "Randomized Warzone/WorldEdit airdrop events ready");
        getLogger().info("MiraAirdrops v" + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (service != null) service.shutdown();
        if (core != null) core.modules().unregister(this);
    }
}
