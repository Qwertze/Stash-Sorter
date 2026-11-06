package com.porter.qwertestash.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.StashConfig;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shulker naming on the heartbeat and in the settings: the naming field, the anvil setting's defaults, and its position kept off the website. */
class NamingSettingsTest {
    private static JsonObject beat(ManagerIndex.Naming naming) {
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        return JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, naming), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void theHeartbeatSaysWhetherTheBotCanNameShulkers() {
        JsonObject o = beat(new ManagerIndex.Naming(true, 37));
        JsonObject n = o.getAsJsonObject("naming");
        assertTrue(n.get("anvil").getAsBoolean());
        assertEquals(37, n.get("xpLevels").getAsInt());
        assertEquals(2, n.size(), "no position in it: " + n);

        JsonObject unknown = beat(new ManagerIndex.Naming(false, -1)).getAsJsonObject("naming");
        assertFalse(unknown.get("anvil").getAsBoolean());
        assertEquals(-1, unknown.get("xpLevels").getAsInt());
        assertEquals(-1, beat(new ManagerIndex.Naming(false, -5)).getAsJsonObject("naming").get("xpLevels").getAsInt());
    }

    @Test
    void withoutNamingSupportTheFieldIsLeftOut() {
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        JsonObject old = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null), StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(old.has("naming"));
        assertFalse(beat(null).has("naming"));
    }

    @Test
    void anOldSettingsFileLoadsWithNoAnvil() {
        StashConfig c = new StashConfig();
        assertEquals("", c.anvilDimension);
        assertEquals(0, c.anvilX);
        // A settings file from 1.6.1 has no anvil keys: they load as the defaults, and the others as they were
        StashConfig old = new com.google.gson.Gson().fromJson("{\"sorting\":true,\"stashName\":\"x\",\"deliveryDimension\":\"overworld\",\"deliveryX\":5}", StashConfig.class);
        assertEquals("", old.anvilDimension);
        assertEquals(0, old.anvilY);
        assertEquals("overworld", old.deliveryDimension);
        assertEquals(5, old.deliveryX);
        // And a new one round-trips
        c.anvilDimension = "minecraft:the_nether";
        c.anvilX = 12;
        c.anvilY = 70;
        c.anvilZ = -34;
        StashConfig back = new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(c), StashConfig.class);
        assertEquals("minecraft:the_nether", back.anvilDimension);
        assertEquals(-34, back.anvilZ);
    }

    @Test
    void theAnvilPositionIsNeverOnTheWebsiteSettingsTab() {
        StashConfig c = new StashConfig();
        c.anvilDimension = "minecraft:the_end";
        c.anvilX = 424242;
        c.anvilY = 99;
        c.anvilZ = -515151;
        ConfigSchema.Built built = ConfigSchema.build(c, ConfigSchema.PLUGIN);
        String schema = built.schema().toString(), values = built.values().toString();
        for (String secret : new String[] {"424242", "-515151", "the_end", "anvilX", "anvilY", "anvilZ", "anvilDimension"}) {
            assertFalse(values.contains(secret), "values leak " + secret + ": " + values);
            assertFalse(schema.contains(secret), "schema leaks " + secret);
        }
        for (String key : new String[] {"anvilDimension", "anvilX", "anvilY", "anvilZ"}) assertTrue(SettingsSpec.PLUGIN.get(key).hidden, key);
    }
}
