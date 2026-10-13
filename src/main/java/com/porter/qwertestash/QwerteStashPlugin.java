package com.porter.qwertestash;

import com.aquarius.plugin.api.AquariusProxyPlugin;
import com.aquarius.plugin.api.Plugin;
import com.aquarius.plugin.api.PluginAPI;
import com.porter.qwertestash.command.StashCommand;
import com.porter.qwertestash.module.StashSorter;
import com.porter.qwertestash.module.StashTracker;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Qwerte stash sorter: sorts a saved stash by tier or by sign, keeps it sorted, and lets you search it.",
    url = "https://github.com/Qwertze/Qwerte",
    authors = {"Qwertze"},
    mcVersions = {BuildConstants.MC_VERSION}
)
public class QwerteStashPlugin implements AquariusProxyPlugin {
    public static StashConfig CONFIG;
    public static ComponentLogger LOG;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        LOG = pluginAPI.getLogger();
        CONFIG = pluginAPI.registerConfig(BuildConstants.PLUGIN_ID, StashConfig.class);
        pluginAPI.registerModule(new StashTracker());
        pluginAPI.registerModule(new StashSorter());
        pluginAPI.registerCommand(new StashCommand());
        LOG.info("Qwerte Stash loaded");
    }
}
