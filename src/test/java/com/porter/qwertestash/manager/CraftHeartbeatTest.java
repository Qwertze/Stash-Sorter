package com.porter.qwertestash.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.StashConfig;
import com.porter.qwertestash.stash.CraftSettings;
import com.porter.qwertestash.stash.CraftStore;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The craft fields of the heartbeat and its reply, the craft settings and their cache, and the new settings (CRAFT-CONTRACT.md "Heartbeat"). */
class CraftHeartbeatTest {
    private static final String HASH = "a".repeat(40);
    private static final String TABLE_HASH = "fef8d74b69d3c5d41db659eb326a7e9a5d78cc85";

    private static JsonObject body(ManagerIndex.Craft craft) {
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        byte[] bytes = ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, null, null, craft);
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void aBotWithATableSendsCraftOrdersAndTheHashOfItsSettings() {
        ManagerIndex.CraftOrders c = new ManagerIndex.CraftOrders(true, HASH, "1.21.4", "ok", 2048, List.of("118,64,-30"));
        JsonObject o = body(new ManagerIndex.Craft(c, TABLE_HASH));
        JsonObject co = o.getAsJsonObject("craftOrders");
        assertEquals(1, co.get("v").getAsInt());
        assertTrue(co.get("enabled").getAsBoolean());
        assertEquals(HASH, co.get("recipes").getAsString());
        assertEquals("1.21.4", co.get("mc").getAsString());
        assertEquals("ok", co.get("table").getAsString());
        assertEquals(2048, co.get("maxFills").getAsInt());
        assertEquals("118,64,-30", co.getAsJsonArray("seen").get(0).getAsString());
        assertEquals(TABLE_HASH, o.get("craftHash").getAsString());
    }

    @Test
    void withNoCachedSettingsCraftHashIsAnExplicitNullSoTheServerSendsThem() {
        JsonObject o = body(new ManagerIndex.Craft(new ManagerIndex.CraftOrders(true, HASH, "1.21.4", "unset", 2048, List.of()), null));
        assertTrue(o.has("craftHash") && o.get("craftHash").isJsonNull());
        assertFalse(o.getAsJsonObject("craftOrders").has("seen"), "no tables seen: the list is left out");
    }

    @Test
    void aBotWithNoRecipeTableSaysNullAndSendsNoHash() {
        JsonObject o = body(new ManagerIndex.Craft(null, null));
        assertTrue(o.has("craftOrders") && o.get("craftOrders").isJsonNull());
        assertFalse(o.has("craftHash"));
    }

    @Test
    void everyHeartbeatWithCraftFieldsCarriesThePlayerAndWithoutOneTheyAreLeftOut() {
        ManagerIndex.Craft craft = new ManagerIndex.Craft(new ManagerIndex.CraftOrders(true, HASH, "1.21.4", "ok", 10, List.of()), null);
        JsonObject with = body(craft);
        assertEquals("Bot", with.get("player").getAsString());
        assertTrue(with.has("craftOrders") && with.has("craftHash"));
        ManagerIndex.Built nameless = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "", "1.21.4", "overworld", id -> null);
        JsonObject without = JsonParser.parseString(new String(ManagerIndex.heartbeat(nameless, -1, null, null, null, null, null, null, null, null, craft), StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(without.has("craftOrders"));
        assertFalse(without.has("craftHash"));
    }

    @Test
    void aTableSeenStandingStaysOkWhenItsChunkUnloads() {
        assertEquals("unloaded", com.porter.qwertestash.stash.CraftBounds.unloadedStatus(false));
        assertEquals("ok", com.porter.qwertestash.stash.CraftBounds.unloadedStatus(true));
    }

    @Test
    void withoutCraftSupportNeitherKeyIsSentAndTheRestIsUnchanged() {
        JsonObject o = body(null);
        assertFalse(o.has("craftOrders"));
        assertFalse(o.has("craftHash"));
        assertTrue(o.has("indexHash"));
    }

    @Test
    void atMostEightTablesAreSeenAndNoPositionIsInAnyOtherField() {
        List<String> many = List.of("1,1,1", "2,2,2", "3,3,3", "4,4,4", "5,5,5", "6,6,6", "7,7,7", "8,8,8", "9,9,9", "10,10,10");
        JsonObject o = body(new ManagerIndex.Craft(new ManagerIndex.CraftOrders(true, HASH, "1.21.4", "ok", 5, many), HASH));
        assertEquals(8, o.getAsJsonObject("craftOrders").getAsJsonArray("seen").size());
        for (String tableState : new String[] {"unset", "ok", "unloaded", "not_table", "unreachable", "other_dimension"}) {
            assertEquals(tableState, body(new ManagerIndex.Craft(new ManagerIndex.CraftOrders(false, HASH, "1.21.4", tableState, 1, List.of()), null))
                .getAsJsonObject("craftOrders").get("table").getAsString());
        }
    }

    // The reply

    private static JsonObject settings(String table) {
        JsonObject o = new JsonObject();
        o.addProperty("rev", 3);
        CraftSettings.Table t = null;
        if (table != null) {
            JsonObject to = new JsonObject();
            to.addProperty("x", 118);
            to.addProperty("y", 64);
            to.addProperty("z", -30);
            to.addProperty("dimension", "minecraft:overworld");
            o.add("table", to);
            t = new CraftSettings.Table(118, 64, -30, "minecraft:overworld");
        }
        else o.add("table", com.google.gson.JsonNull.INSTANCE);
        o.addProperty("hash", CraftSettings.hashOf(t));
        o.add("neverUse", new com.google.gson.JsonArray());
        return o;
    }

    @Test
    void theHashIsTheContractsOverTheTablePosition() {
        assertEquals(TABLE_HASH, CraftSettings.hashOf(new CraftSettings.Table(118, 64, -30, "minecraft:overworld")));
        assertEquals("d356a9e31dd89167760395bb70f7d8f1204e84c0", CraftSettings.hashOf(null));
        assertEquals(CraftSettings.EMPTY.hash(), CraftSettings.hashOf(null));
    }

    @Test
    void aReplyWithSettingsIsReadAndOneWithTheHashAloneIsToo() throws Exception {
        JsonObject reply = new JsonObject();
        reply.addProperty("craftHash", TABLE_HASH);
        reply.addProperty("recipesHash", HASH);
        reply.add("craft", settings("t"));
        ManagerIndex.CraftReply r = ManagerIndex.craftReply(reply.toString());
        assertNull(r.invalid());
        assertEquals(TABLE_HASH, r.hash());
        assertEquals(HASH, r.recipesHash());
        assertNotNull(r.settings());
        assertEquals(3, r.settings().rev());
        assertEquals(new CraftSettings.Table(118, 64, -30, "minecraft:overworld"), r.settings().table());
        // A cleared table
        reply.add("craft", settings(null));
        assertNull(ManagerIndex.craftReply(reply.toString()).settings().table());
        // An older server says nothing
        ManagerIndex.CraftReply old = ManagerIndex.craftReply("{\"ok\":true}");
        assertNull(old.hash());
        assertNull(old.settings());
        assertNull(old.recipesHash());
        assertNull(old.invalid());
        // Garbage is nothing, not a crash
        assertNull(ManagerIndex.craftReply("not json").settings());
    }

    @Test
    void aSettingsObjectThatFailsAnyCheckIsRefusedWhole() {
        String[] bad = {
            // a hash that is not the content's
            "{\"rev\":1,\"hash\":\"" + HASH + "\",\"table\":null}",
            // a position out of range
            "{\"rev\":1,\"hash\":\"" + HASH + "\",\"table\":{\"x\":30000001,\"y\":64,\"z\":0,\"dimension\":\"minecraft:overworld\"}}",
            "{\"rev\":1,\"hash\":\"" + HASH + "\",\"table\":{\"x\":0,\"y\":2049,\"z\":0,\"dimension\":\"minecraft:overworld\"}}",
            // a bad dimension
            "{\"rev\":1,\"hash\":\"" + HASH + "\",\"table\":{\"x\":0,\"y\":64,\"z\":0,\"dimension\":\"Over World\"}}",
            // wrong types
            "{\"rev\":\"1\",\"hash\":\"" + HASH + "\",\"table\":null}",
            "{\"rev\":1,\"hash\":5,\"table\":null}",
            "{\"rev\":1,\"hash\":\"" + HASH + "\",\"table\":\"x\"}",
            "{\"rev\":1.5,\"hash\":\"" + HASH + "\",\"table\":null}",
        };
        for (String b : bad) {
            ManagerIndex.CraftReply r = ManagerIndex.craftReply("{\"craft\":" + b + "}");
            assertNull(r.settings(), b);
            assertNotNull(r.invalid(), b);
        }
    }

    @Test
    void theCacheIsWrittenAtomicallyReadBackAndABadEntryIsDropped(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("craft-settings.json");
        CraftStore store = new CraftStore(file);
        assertNull(store.get("2b2t.org", "main"));
        CraftSettings s = CraftSettings.parse(settings("t"));
        assertTrue(store.put("2b2t.org", "main", s));
        assertFalse(store.put("2b2t.org", "main", s), "the same settings again change nothing");
        assertFalse(Files.exists(dir.resolve("craft-settings.json.tmp")), "no temp file is left");
        CraftStore again = new CraftStore(file);
        assertEquals(s, again.get("2b2t.org", "main"));
        // An entry whose table was changed by hand no longer matches its hash: it is dropped, the rest stays
        String text = Files.readString(file).replace("\"x\":118", "\"x\":119");
        Files.writeString(file, text);
        assertNull(new CraftStore(file).get("2b2t.org", "main"));
        Files.writeString(file, "garbage");
        assertEquals(0, new CraftStore(file).size());
    }

    // The settings

    @Test
    void theNewSettingsHaveTheContractsDefaultsAndAnOldFileLoadsWithThem() {
        StashConfig c = new StashConfig();
        assertTrue(c.craftOrders);
        assertEquals(2048, c.craftOrderMaxFills);
        assertEquals(4096, c.craftOrderMaxPerHour);
        StashConfig old = new com.google.gson.Gson().fromJson("{\"sorting\":true,\"enableCrafting\":true}", StashConfig.class);
        assertTrue(old.craftOrders);
        assertEquals(2048, old.craftOrderMaxFills);
        assertEquals(4096, old.craftOrderMaxPerHour);
    }

    @Test
    void theNewSettingsAreOnTheWebsiteWithTheirCommands() {
        for (String key : new String[] {"craftOrders", "craftOrderMaxFills", "craftOrderMaxPerHour"}) {
            assertNotNull(SettingsSpec.PLUGIN.get(key), key);
            assertFalse(SettingsSpec.PLUGIN.get(key).hidden, key);
        }
        assertEquals("craftorders {v}", SettingsSpec.PLUGIN.get("craftOrders").command);
        assertEquals("craftorders maxfills {v}", SettingsSpec.PLUGIN.get("craftOrderMaxFills").command);
        assertEquals("craftorders maxperhour {v}", SettingsSpec.PLUGIN.get("craftOrderMaxPerHour").command);
        assertEquals(100000.0, SettingsSpec.PLUGIN.get("craftOrderMaxFills").max);
        String schema = ConfigSchema.build(new StashConfig(), ConfigSchema.PLUGIN).schema().toString();
        assertTrue(schema.contains("craftOrderMaxFills"));
    }

    @Test
    void noRecipeTableIsShippedInThePluginJar() {
        assertNull(CraftHeartbeatTest.class.getResource("/qwerte-stash/recipes/1.21.4.json"));
    }
}
