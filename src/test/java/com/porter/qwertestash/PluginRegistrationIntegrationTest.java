package com.porter.qwertestash;

import com.aquarius.plugin.api.PluginAPI;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class PluginRegistrationIntegrationTest {
    @Test
    void loadingRegistersConfigurationFiveModulesAndTheStashCommand() {
        var modules = new ArrayList<String>();
        var commands = new ArrayList<String>();
        var config = new StashConfig();
        var oldConfig = QwerteStashPlugin.CONFIG;
        var oldLogger = QwerteStashPlugin.LOG;
        var api = (PluginAPI) Proxy.newProxyInstance(PluginAPI.class.getClassLoader(), new Class<?>[] {PluginAPI.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getLogger" -> ComponentLogger.logger("registration-test");
                case "registerConfig" -> {
                    assertEquals("qwerte-stash", args[0]);
                    assertSame(StashConfig.class, args[1]);
                    yield config;
                }
                case "registerModule" -> { modules.add(args[0].getClass().getSimpleName()); yield null; }
                case "registerCommand" -> { commands.add(args[0].getClass().getSimpleName()); yield null; }
                default -> throw new AssertionError(method.getName());
            });
        try {
            new QwerteStashPlugin().onLoad(api);
            assertSame(config, QwerteStashPlugin.CONFIG);
            assertEquals(List.of("StashTracker", "StashSorter", "AutoDrop", "GroundItems", "StashManager"), modules);
            assertEquals(List.of("StashCommand"), commands);
        } finally {
            QwerteStashPlugin.CONFIG = oldConfig;
            QwerteStashPlugin.LOG = oldLogger;
        }
    }
}
