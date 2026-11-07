package com.porter.qwertestash.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.StashConfig;
import com.porter.qwertestash.orders.OrderFlow;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Crafting (1.8.0) in the settings and on the heartbeat: defaults, an old file, the table's position kept off the website. */
class CraftingSettingsTest {
    @Test
    void anOldSettingsFileLoadsWithCraftingOff() {
        StashConfig old = new com.google.gson.Gson().fromJson("{\"sorting\":true,\"stashName\":\"x\",\"anvilDimension\":\"overworld\",\"anvilX\":5}", StashConfig.class);
        assertFalse(old.enableCrafting);
        assertEquals(27, old.craftMaxPerOrder);
        assertEquals(128, old.craftMaxOpsPerTask);
        assertEquals(54, old.craftMaxPerHour);
        assertNotNull(old.craftNeverUse);
        assertTrue(old.craftNeverUse.isEmpty());
        assertEquals("", old.craftingTableDimension);
        assertEquals("overworld", old.anvilDimension);
        // An order saved by 1.7.2 has no crafted count
        OrderFlow.Saved saved = new com.google.gson.Gson().fromJson("{\"id\":\"o-1\",\"done\":1}", OrderFlow.Saved.class);
        assertEquals(0, saved.crafted);
    }

    @Test
    void theCraftingSettingsAreOnTheWebsiteButTheTableIsNot() {
        StashConfig c = new StashConfig();
        c.craftingTableDimension = "minecraft:the_end";
        c.craftingTableX = 424242;
        c.craftingTableZ = -515151;
        ConfigSchema.Built built = ConfigSchema.build(c, ConfigSchema.PLUGIN);
        String schema = built.schema().toString(), values = built.values().toString();
        for (String secret : new String[] {"424242", "-515151", "the_end", "craftingTableX", "craftingTableDimension"}) {
            assertFalse(values.contains(secret), "values leak " + secret);
            assertFalse(schema.contains(secret), "schema leaks " + secret);
        }
        for (String key : new String[] {"enableCrafting", "craftMaxPerOrder", "craftMaxOpsPerTask", "craftMaxPerHour", "craftNeverUse"}) {
            assertTrue(schema.contains(key), key);
            assertFalse(SettingsSpec.PLUGIN.get(key).hidden, key);
        }
        assertEquals(54.0, SettingsSpec.PLUGIN.get("craftMaxPerOrder").max);
        assertEquals("crafting {v}", SettingsSpec.PLUGIN.get("enableCrafting").command);
    }

    @Test
    void theHeartbeatSaysWhetherTheBotCrafts() {
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        JsonObject o = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, new ManagerIndex.Naming(true, 3), new ManagerIndex.Crafting(true, false)), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject c = o.getAsJsonObject("crafting");
        assertTrue(c.get("enabled").getAsBoolean());
        assertFalse(c.get("table").getAsBoolean());
        assertEquals(2, c.size(), "no position: " + c);
        for (String source : new String[] {"set", "found", "placed", "crafted", "none"}) {
            JsonObject withSource = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, new ManagerIndex.Naming(true, 3), new ManagerIndex.Crafting(true, !source.equals("none"), source)), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("crafting");
            assertEquals(source, withSource.get("tableSource").getAsString());
            assertEquals(3, withSource.size(), "enabled, table, tableSource: " + withSource);
        }
        assertTrue(SettingsSpec.PLUGIN.get("craftingTableSource").hidden);
        assertTrue(o.has("naming"));
        JsonObject old = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, new ManagerIndex.Naming(true, 3)), StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(old.has("crafting"));
    }

    @Test
    void theHeartbeatSaysHowTheAnvilCameToBeButNeverWhere() {
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        for (String source : new String[] {"set", "found", "placed", "none"}) {
            JsonObject n = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, new ManagerIndex.Naming(!source.equals("none"), 5, source)), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("naming");
            assertEquals(source, n.get("anvilSource").getAsString());
            assertEquals(3, n.size(), "anvil, xpLevels, anvilSource: " + n);
        }
        // An older caller without the source: the field is left out
        JsonObject old = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, new ManagerIndex.Naming(true, 5)), StandardCharsets.UTF_8))
            .getAsJsonObject().getAsJsonObject("naming");
        assertFalse(old.has("anvilSource"));
        // A settings file from before 1.8.1 has no anvilSource: it loads blank, which reads as "set"
        StashConfig c = new com.google.gson.Gson().fromJson("{\"anvilDimension\":\"overworld\",\"anvilX\":3}", StashConfig.class);
        assertEquals("", c.anvilSource);
        assertTrue(SettingsSpec.PLUGIN.get("anvilSource").hidden);
    }
}
