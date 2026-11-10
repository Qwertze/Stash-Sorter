package com.porter.qwertestash.stash;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.ManagerIndex;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stash area editor on the bot (AREA-CONTRACT.md): the hash, the derived box, what the website may send, and when and why an area waits. */
class AreaRulesTest {
    private static final String OW = "minecraft:overworld";

    @Test
    void theHashVectorsOfTheContract() {
        assertEquals("2a5139e37493cc1588efdc9d86155c55374e0fc1", AreaRules.hashOf(new int[] {100, 60, -40, 163, 90, 23}, OW));
        assertEquals("382410b4646b2c35b8ce6911faf42958ec60b6e2", AreaRules.hashOf(null, null));
        assertEquals("382410b4646b2c35b8ce6911faf42958ec60b6e2", AreaRules.hashOf(null, OW));
    }

    @Test
    void theDerivedBoxIsTheContainersWidenedBy2SidewaysAnd1UpAndDown() {
        assertNull(AreaRules.derived(List.of()));
        assertEquals(List.of(8, 63, 18, 22, 66, 32), java.util.Arrays.stream(AreaRules.derived(List.of(new int[] {10, 64, 20}, new int[] {20, 65, 30}))).boxed().toList());
        // Bigger than the caps: cut to them around the centre
        int[] big = AreaRules.derived(List.of(new int[] {0, 64, 0}, new int[] {1000, 200, 1000}));
        assertEquals(256, big[3] - big[0] + 1);
        assertEquals(128, big[4] - big[1] + 1);
        assertEquals(256, big[5] - big[2] + 1);
        assertEquals(500 - 128, big[0], "centre of -2..1002 is 500");
        assertEquals(132 - 64, big[1], "centre of 63..201 is 132");
    }

    @Test
    void anAreaFromTheWebsiteIsCheckedForShapeCapsHeightAndDimension() {
        int[] ok = {100, 60, -40, 163, 90, 23};
        assertNull(AreaRules.validate(ok, OW, "overworld"), "the stash's dimension with or without the prefix");
        assertNull(AreaRules.validate(ok, OW, OW));
        assertNotNull(AreaRules.validate(ok, OW, null), "a stash with no known dimension takes none");
        assertNotNull(AreaRules.validate(ok, OW, "minecraft:the_nether"));
        assertNotNull(AreaRules.validate(new int[] {10, 0, 0, 5, 5, 5}, OW, OW), "a minimum above a maximum");
        assertTrue(AreaRules.validate(new int[] {0, 0, 0, 299, 10, 39}, OW, OW).contains("300x11x40"));
        assertNotNull(AreaRules.validate(new int[] {0, 0, 0, 10, 128, 10}, OW, OW), "129 high");
        assertNull(AreaRules.validate(new int[] {0, 0, 0, 255, 127, 255}, OW, OW), "exactly the caps");
        assertNotNull(AreaRules.validate(new int[] {30_000_000, 0, 0, 30_000_001, 5, 5}, OW, OW));
        // Y limits: the overworld -64..319, the nether and the end 0..255
        assertNotNull(AreaRules.validate(new int[] {0, -64, 0, 5, 319, 5}, OW, OW), "384 high is over the cap");
        assertNull(AreaRules.validate(new int[] {0, -64, 0, 5, 20, 5}, OW, OW));
        assertNotNull(AreaRules.validate(new int[] {0, -65, 0, 5, 5, 5}, OW, OW));
        assertNotNull(AreaRules.validate(new int[] {0, 300, 0, 5, 320, 5}, OW, OW));
        assertNotNull(AreaRules.validate(new int[] {0, -10, 0, 5, 5, 5}, "minecraft:the_nether", "the_nether"));
        assertNull(AreaRules.validate(new int[] {0, 0, 0, 5, 127, 5}, "minecraft:the_end", "the_end"));
        assertNotNull(AreaRules.validate(new int[] {0, 200, 0, 5, 256, 5}, "minecraft:the_end", "the_end"), "the end ends at 255");
        assertNotNull(AreaRules.validate(ok, "Over World", OW));
    }

    @Test
    void theAreaObjectIsReadStrictly() throws Exception {
        AreaRules.Parsed p = AreaRules.parse(JsonParser.parseString("{\"box\":[1,2,3,4,5,6],\"dimension\":\"minecraft:overworld\"}"));
        assertEquals(List.of(1, 2, 3, 4, 5, 6), java.util.Arrays.stream(p.box()).boxed().toList());
        for (String bad : new String[] {"{\"box\":[1,2,3],\"dimension\":\"x:y\"}", "{\"box\":[1,2,3,4,5,\"6\"],\"dimension\":\"x:y\"}", "{\"box\":[1,2,3,4,5,6.5],\"dimension\":\"x:y\"}",
            "{\"box\":[1,2,3,4,5,6]}", "{\"dimension\":\"x:y\"}", "[]", "{\"box\":[1,2,3,4,5,99999999999],\"dimension\":\"x:y\"}"}) {
            assertThrows(AreaRules.Invalid.class, () -> AreaRules.parse(JsonParser.parseString(bad)), bad);
        }
    }

    @Test
    void anAreaWaitsWhileAnOrderIsHeldOrAnyTaskRuns() {
        // Between tasks: nothing at all going on
        assertNull(AreaRules.busyWhy(false, false, false, false, false, false));
        // An order held or resumable, a job, shulker handling, a placed or pending box, a repair or craft, carried stacks: each is busy
        assertEquals("busy", AreaRules.busyWhy(true, false, false, false, false, false));
        assertEquals("busy", AreaRules.busyWhy(false, true, false, false, false, false));
        assertEquals("busy", AreaRules.busyWhy(false, false, true, false, false, false));
        assertEquals("busy", AreaRules.busyWhy(false, false, false, true, false, false));
        assertEquals("busy", AreaRules.busyWhy(false, false, false, false, true, false));
        assertEquals("busy", AreaRules.busyWhy(false, false, false, false, false, true));
        // And the area does not apply while it is busy, nor does it wait for a reason that is not its own
        int[] box = {0, 60, 0, 10, 70, 10};
        assertEquals("busy", AreaRules.pendingWhy(box, OW, OW, true, 5, 5, "busy"));
        assertNull(AreaRules.pendingWhy(box, OW, OW, true, 5, 5, null));
    }

    @Test
    void aWebsiteAreaMoreThan128BlocksFromTheBotWaitsAsFar() {
        int[] box = {1000, 60, 1000, 1010, 70, 1010};
        assertEquals("far", AreaRules.pendingWhy(box, OW, OW, true, 0, 0, null));
        // Within 128 of the nearest point of the box: applied
        assertNull(AreaRules.pendingWhy(box, OW, OW, true, 900, 1005, null));
        assertEquals("far", AreaRules.pendingWhy(box, OW, OW, true, 871, 1005, null));
        assertNull(AreaRules.pendingWhy(box, OW, OW, true, 872, 1005, null));
        // Inside the box the distance is 0
        assertEquals(0, AreaRules.horizontalDistance(box, 1005, 1005), 1e-9);
        // A local edit (a command) is not far-guarded; a clear never is
        assertNull(AreaRules.pendingWhy(box, OW, OW, false, 0, 0, null));
        assertNull(AreaRules.pendingWhy(null, OW, OW, true, 0, 0, null));
        // Another dimension comes first
        assertEquals("dimension", AreaRules.pendingWhy(box, OW, "minecraft:the_nether", true, 0, 0, "busy"));
        assertNull(AreaRules.pendingWhy(box, OW, "overworld", true, 1005, 1005, null), "with or without the prefix");
    }

    // The heartbeat and its reply

    private static JsonObject beat(ManagerIndex.AreaReport r, String player) {
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, player, "1.21.4", "overworld", id -> null);
        return JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, null, null, null, r), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void theHeartbeatCarriesTheAreaFieldsAndNeedsAPlayer() {
        int[] box = {100, 60, -40, 163, 90, 23};
        ManagerIndex.AreaReport set = new ManagerIndex.AreaReport(AreaRules.hashOf(box, OW), box, OW, null, true, "b".repeat(40), "far", 3);
        JsonObject a = beat(set, "Bot").getAsJsonObject("area");
        assertEquals(1, a.get("v").getAsInt());
        assertEquals("2a5139e37493cc1588efdc9d86155c55374e0fc1", a.get("hash").getAsString());
        assertEquals(OW, a.getAsJsonObject("area").get("dimension").getAsString());
        assertEquals(6, a.getAsJsonObject("area").getAsJsonArray("box").size());
        assertTrue(a.get("derived").isJsonNull());
        assertTrue(a.get("edited").getAsBoolean());
        assertEquals("far", a.getAsJsonObject("pending").get("why").getAsString());
        assertEquals("b".repeat(40), a.getAsJsonObject("pending").get("hash").getAsString());
        assertEquals(3, a.get("containersOutside").getAsInt());
        // No area set: the derived box, no pending
        ManagerIndex.AreaReport none = new ManagerIndex.AreaReport(AreaRules.hashOf(null, null), null, null, new int[] {1, 2, 3, 4, 5, 6}, false, null, null, 0);
        JsonObject n = beat(none, "Bot").getAsJsonObject("area");
        assertTrue(n.get("area").isJsonNull());
        assertEquals(6, n.getAsJsonArray("derived").size());
        assertTrue(n.get("pending").isJsonNull());
        assertFalse(n.get("edited").getAsBoolean());
        // The server rejects area without a player: it is left out then
        assertFalse(beat(none, "").has("area"));
        // And an older call does not send it
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        assertFalse(JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, null, null, null), StandardCharsets.UTF_8)).getAsJsonObject().has("area"));
    }

    @Test
    void theReplyCarriesTheHashAndTheStoredArea() {
        ManagerIndex.AreaReply r = ManagerIndex.areaReply("{\"areaHash\":\"" + "c".repeat(40) + "\",\"area\":{\"box\":[1,2,3,4,5,6],\"dimension\":\"minecraft:overworld\"}}");
        assertEquals("c".repeat(40), r.hash());
        assertTrue(r.areaPresent());
        assertEquals(OW, r.area().dimension());
        // A cleared area is an explicit null
        ManagerIndex.AreaReply cleared = ManagerIndex.areaReply("{\"areaHash\":\"" + "c".repeat(40) + "\",\"area\":null}");
        assertTrue(cleared.areaPresent());
        assertNull(cleared.area());
        assertNull(cleared.invalid());
        // Only the hash, an older server, a bad area, garbage
        assertFalse(ManagerIndex.areaReply("{\"areaHash\":\"" + "c".repeat(40) + "\"}").areaPresent());
        ManagerIndex.AreaReply old = ManagerIndex.areaReply("{\"ok\":true}");
        assertNull(old.hash());
        assertFalse(old.areaPresent());
        ManagerIndex.AreaReply bad = ManagerIndex.areaReply("{\"area\":{\"box\":[1,2]}}");
        assertTrue(bad.areaPresent());
        assertNotNull(bad.invalid());
        assertFalse(ManagerIndex.areaReply("not json").areaPresent());
    }
}
