package com.porter.qwertestash;

import com.aquarius.plugin.api.AquariusProxyPlugin;
import com.aquarius.plugin.api.Plugin;
import com.aquarius.plugin.api.PluginAPI;
import com.porter.qwertestash.command.StashCommand;
import com.porter.qwertestash.module.AutoDrop;
import com.porter.qwertestash.module.GroundItems;
import com.porter.qwertestash.module.StashManager;
import com.porter.qwertestash.module.StashSorter;
import com.porter.qwertestash.module.StashTracker;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

@Plugin(
    id = BuildConstants.PLUGIN_ID,
    version = BuildConstants.VERSION,
    description = "Stash Sorter: sorts a stash of chests by tier or by sign, repacks shulkers, keeps it sorted, and lets you search it.",
    url = "https://github.com/Qwertze/Stash-Sorter",
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
        // The shared kit-items table is read now, so a broken copy is said once at load and not on the first sort
        com.porter.qwertestash.stash.KitItems.shared();
        pluginAPI.registerModule(new StashTracker());
        pluginAPI.registerModule(new StashSorter());
        pluginAPI.registerModule(new AutoDrop());
        pluginAPI.registerModule(new GroundItems());
        pluginAPI.registerModule(new StashManager());
        pluginAPI.registerModule(new com.porter.qwertestash.module.Farmer());
        pluginAPI.registerCommand(new StashCommand());
        try {
            ConsoleQuiet.install();
            com.porter.qwertestash.farm.FarmLogGuard.install(com.porter.qwertestash.farm.FarmerGuard::active);
        } catch (LinkageError | RuntimeException e) {
            LOG.debug("Qwerte Stash: quiet mode can not filter the terminal", e);
        }
        LOG.info("Qwerte Stash loaded");
    }
}
