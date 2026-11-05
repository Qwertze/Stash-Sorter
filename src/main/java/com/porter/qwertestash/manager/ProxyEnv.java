package com.porter.qwertestash.manager;

import com.aquarius.module.api.Module;

import java.util.List;
import java.util.function.Supplier;

import static com.porter.qwertestash.QwerteStashPlugin.LOG;

/**
 * How a change to the proxy's config (or another plugin's) takes effect: after the field is set, every module whose
 * {@code enabledSetting()} no longer matches its state is synced (each one on its own, so one failing does not stop the
 * rest), then the config is saved. There are no commands for these, every change is a direct set.
 */
public final class ProxyEnv implements ConfigApplier.Env {
    private final Supplier<List<Module>> modules;
    private final Runnable save;

    public ProxyEnv(Supplier<List<Module>> modules, Runnable save) {
        this.modules = modules;
        this.save = save;
    }

    @Override
    public ConsoleRunner.Outcome command(String line) {
        com.google.gson.JsonObject out = new com.google.gson.JsonObject();
        out.add("fields", new com.google.gson.JsonArray());
        out.add("lines", new com.google.gson.JsonArray());
        return new ConsoleRunner.Outcome(false, false, out, "this setting has no command");
    }

    @Override
    public void changed(String path) {
        try {
            for (Module m : modules.get()) {
                try {
                    if (m.enabledSetting() != m.isEnabled()) m.syncEnabledFromConfig();
                } catch (RuntimeException | LinkageError e) {
                    LOG.warn("Stash Manager dashboard: couldn't sync module {} after {} changed: {}", m.getClass().getSimpleName(), path, e.toString());
                }
            }
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Stash Manager dashboard: couldn't sync the modules after {} changed: {}", path, e.toString());
        }
        try {
            save.run();
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Stash Manager dashboard: couldn't save the config after {} changed: {}", path, e.toString());
        }
    }
}
