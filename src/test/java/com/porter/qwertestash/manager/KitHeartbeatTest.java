package com.porter.qwertestash.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kit fields of the heartbeat body and of its reply (KIT-CONTRACT.md "Heartbeat"). */
class KitHeartbeatTest {
    private static final String HASH = "a".repeat(40);

    private static JsonObject body(ManagerIndex.Kits kits) {
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        byte[] bytes = ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, null, kits);
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void bothKeysAreWrittenAndExplicitNullWhenTheBotHasNothing() {
        JsonObject o = body(new ManagerIndex.Kits(null, null));
        assertTrue(o.has("kitsHash") && o.get("kitsHash").isJsonNull(), "no cache: the key is there, null, so the server sends the settings");
        assertTrue(o.has("kitsReportHash") && o.get("kitsReportHash").isJsonNull());
    }

    @Test
    void theHashesAreSent() {
        JsonObject o = body(new ManagerIndex.Kits(HASH, "b".repeat(40)));
        assertEquals(HASH, o.get("kitsHash").getAsString());
        assertEquals("b".repeat(40), o.get("kitsReportHash").getAsString());
    }

    @Test
    void withKitSortingOffNeitherKeyIsSentAndTheRestIsUnchanged() {
        JsonObject o = body(null);
        assertFalse(o.has("kitsHash"));
        assertFalse(o.has("kitsReportHash"));
        assertTrue(o.has("indexHash"));
        assertTrue(o.get("repair").getAsBoolean());
    }

    @Test
    void theOlderOverloadsStillWorkWithoutKitKeys() {
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        JsonObject o = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, "h"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("h", o.get("deliveryHash").getAsString());
        assertFalse(o.has("kitsHash"));
    }

    @Test
    void kitSortingIsOnByDefaultAndAnOldSettingsFileLoadsWithItOn() {
        assertTrue(new com.porter.qwertestash.StashConfig().kitSorting);
        assertTrue(new com.google.gson.Gson().fromJson("{\"sorting\":true}", com.porter.qwertestash.StashConfig.class).kitSorting);
        assertEquals("kitSorting {v}", SettingsSpec.PLUGIN.get("kitSorting").command);
        assertTrue(ConfigSchema.build(new com.porter.qwertestash.StashConfig(), ConfigSchema.PLUGIN).schema().toString().contains("kitSorting"));
    }

    // The reply

    @Test
    void anOldServerSaysNothingAboutKits() {
        ManagerIndex.KitsReply r = ManagerIndex.kitsReply("{\"needIndex\":false,\"rescan\":null}");
        assertFalse(r.reportKnown());
        assertFalse(r.needReport());
        assertNull(r.settings());
        assertNull(r.invalid());
    }

    @Test
    void theReportRouteIsKnownOnlyFromANeedKitsReportKey() {
        assertTrue(ManagerIndex.kitsReply("{\"kitsHash\":\"" + HASH + "\",\"needKitsReport\":false}").reportKnown());
        assertFalse(ManagerIndex.kitsReply("{\"kitsHash\":\"" + HASH + "\",\"needKitsReport\":false}").needReport());
        ManagerIndex.KitsReply ask = ManagerIndex.kitsReply("{\"needKitsReport\":true}");
        assertTrue(ask.reportKnown());
        assertTrue(ask.needReport());
        assertFalse(ManagerIndex.kitsReply("{\"kitsHash\":\"" + HASH + "\"}").reportKnown(), "kitsHash alone does not unlock the route");
        assertFalse(ManagerIndex.kitsReply("{\"needKitsReport\":\"yes\"}").reportKnown(), "a wrong type is not the key");
        assertFalse(ManagerIndex.kitsReply("{\"needKitsReport\":1}").reportKnown());
    }

    @Test
    void validSettingsAreReadAndABadObjectIsRefusedWhole() {
        String ok = com.porter.qwertestash.stash.KitTestSupport.withHash("{\"rev\":2,\"hash\":\"" + HASH + "\",\"kitsChest\":\"1,2,3\",\"rules\":{\"pvp kit\":\"4,5,6\"}}");
        ManagerIndex.KitsReply r = ManagerIndex.kitsReply("{\"kitsHash\":\"" + HASH + "\",\"kits\":" + ok + ",\"needKitsReport\":false}");
        assertNotNull(r.settings());
        assertEquals(2, r.settings().rev());
        assertNull(r.invalid());
        assertTrue(r.reportKnown());

        ManagerIndex.KitsReply bad = ManagerIndex.kitsReply("{\"kits\":{\"rev\":2,\"hash\":\"" + HASH + "\",\"kitsChest\":null,\"rules\":{\"Not Normal\":\"4,5,6\"}},\"needKitsReport\":true}");
        assertNull(bad.settings());
        assertNotNull(bad.invalid());
        assertTrue(bad.needReport(), "the rest of the reply still counts");
    }

    @Test
    void junkIsNotAnAnswer() {
        assertFalse(ManagerIndex.kitsReply("not json").reportKnown());
        assertFalse(ManagerIndex.kitsReply("[]").reportKnown());
        assertFalse(ManagerIndex.kitsReply("").reportKnown());
        assertNull(ManagerIndex.kitsReply("{\"kits\":null}").settings());
        assertNull(ManagerIndex.kitsReply("{\"kits\":null}").invalid());
        assertNotNull(ManagerIndex.kitsReply("{\"kits\":5}").invalid());
    }
}
