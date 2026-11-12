package com.porter.qwertestash.pearl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loader role: who may be reported or acted for (rev 4 and 5 rule: the sender's uuid must equal the owner-pinned one), the report's shape and
 * what it leaves out, the presence words and their grace period, and the load tracker.
 */
class PearlLoaderTest {
    private static final UUID T = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), OTHER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static List<LoadAuth.Allowed> allow(String name, @Nullable UUID uuid) {
        return List.of(new LoadAuth.Allowed(name, uuid));
    }

    // LoadAuth

    @Test
    void aMatchingUuidIsPermitted() {
        assertTrue(LoadAuth.permits(allow("qwerttze", T), "Qwerttze", T));
    }

    @Test
    void anotherUuidAMissingUuidAnUnpinnedNameAndAnUnlistedNameAreRefused() {
        assertFalse(LoadAuth.permits(allow("qwerttze", T), "Qwerttze", OTHER), "another uuid under the listed name");
        assertFalse(LoadAuth.permits(allow("qwerttze", T), "Qwerttze", null), "no verified uuid");
        assertFalse(LoadAuth.permits(allow("qwerttze", null), "Qwerttze", T), "a null pin authorises nobody");
        assertFalse(LoadAuth.permits(allow("qwerttze", T), "Someone", T), "the name is not on the list");
        assertFalse(LoadAuth.permits(List.of(), "Qwerttze", T), "an empty list");
        assertFalse(LoadAuth.permits(allow("qwerttze", T), null, T), "no name");
    }

    @Test
    void theServersAllowArrayIsReadStrictly() {
        JsonArray a = JsonParser.parseString("[{\"name\":\"Qwerttze\",\"uuid\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\"},{\"name\":\"NoPin\",\"uuid\":null},"
            + "{\"name\":\"bad name\",\"uuid\":null},{\"name\":\"Broken\",\"uuid\":\"nope\"},{\"uuid\":null},5]").getAsJsonArray();
        List<LoadAuth.Allowed> list = LoadAuth.parse(a);
        assertEquals(2, list.size());
        assertEquals("qwerttze", list.get(0).name());
        assertEquals(T, list.get(0).uuid());
        assertNull(list.get(1).uuid());
        assertTrue(LoadAuth.parse(null).isEmpty());
        assertTrue(LoadAuth.parse(JsonParser.parseString("{}")).isEmpty());
    }

    // The report

    private static PearlReport.Pearl pearl(UUID owner, String name, String id, boolean present, boolean online, long onlineSince) {
        return new PearlReport.Pearl(owner, name, id, present, true, true, online, onlineSince);
    }

    private static PearlReport.Snapshot snap(long now, long inGameSince, List<PearlReport.Pearl> pearls) {
        return new PearlReport.Snapshot("LoaderBot", now, true, false, true, false, inGameSince, 45_000L, false, false, pearls);
    }

    @Test
    void onlyApprovedAndPinnedTravellersAreReportedAndNothingElseLeaves() {
        long now = 1_000_000;
        List<PearlReport.Pearl> pearls = List.of(pearl(T, "Qwerttze", "pearl-1", true, true, 1), pearl(OTHER, "Stranger", "pearl-2", true, true, 1),
            pearl(OTHER, "Qwerttze", "pearl-3", true, true, 1), pearl(T, "NoPin", "pearl-4", true, true, 1));
        List<LoadAuth.Allowed> allow = List.of(new LoadAuth.Allowed("qwerttze", T), new LoadAuth.Allowed("nopin", null));
        JsonArray c = PearlReport.chambers(snap(now, 1, pearls), allow, k -> 7);
        assertEquals(2, c.size(), c.toString());
        assertEquals("pearl-1", c.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("Qwerttze", c.get(0).getAsJsonObject().get("owner").getAsString());
        assertTrue(c.get(0).getAsJsonObject().get("ownerKnown").getAsBoolean());
        assertEquals("pearl-4", c.get(1).getAsJsonObject().get("id").getAsString());
        assertTrue(c.get(1).getAsJsonObject().get("owner").isJsonNull(), "a listed name with no pin is an unknown owner");
        assertFalse(c.get(1).getAsJsonObject().get("ownerKnown").getAsBoolean());
        // Another uuid under the listed name (pearl-3) and the unlisted stranger (pearl-2) are not in the report at all
        assertFalse(c.toString().contains("Stranger") || c.toString().contains("pearl-2") || c.toString().contains("pearl-3"));
    }

    @Test
    void beforeTheFirstAnswerTheReportHasNoChambers() {
        JsonArray c = PearlReport.chambers(snap(1_000_000, 1, List.of(pearl(T, "Qwerttze", "pearl-1", true, true, 1))), List.of(), k -> 1);
        assertEquals(0, c.size());
    }

    @Test
    void aPearlThatIsNotSeenIsNotEmptyUntilTheGracePeriodHasPassed() {
        long now = 1_000_000;
        // The owner just rejoined: not seen yet is unknown, not empty
        assertEquals("unknown", PearlReport.presence(pearl(T, "Qwerttze", "p", false, true, now - 10_000), snap(now, now - 600_000, List.of())));
        // The owner has been back for longer than the grace period
        assertEquals("empty", PearlReport.presence(pearl(T, "Qwerttze", "p", false, true, now - 60_000), snap(now, now - 600_000, List.of())));
        // The loader itself just came into the world
        assertEquals("unknown", PearlReport.presence(pearl(T, "Qwerttze", "p", false, true, now - 600_000), snap(now, now - 10_000, List.of())));
        // The owner is not in the tab list (offline or in the queue): never empty
        assertEquals("owner_offline", PearlReport.presence(pearl(T, "Qwerttze", "p", false, false, 0), snap(now, now - 600_000, List.of())));
        // Seen is armed whatever else
        assertEquals("armed", PearlReport.presence(pearl(T, "Qwerttze", "p", true, true, now), snap(now, now - 1, List.of())));
        // Out of range, another dimension, or the loader in the queue: unknown
        assertEquals("unknown", PearlReport.presence(new PearlReport.Pearl(T, "Qwerttze", "p", false, false, true, true, 1), snap(now, now - 600_000, List.of())));
        assertEquals("unknown", PearlReport.presence(new PearlReport.Pearl(T, "Qwerttze", "p", false, true, false, true, 1), snap(now, now - 600_000, List.of())));
        PearlReport.Snapshot queued = new PearlReport.Snapshot("LoaderBot", now, true, false, false, true, 0, 45_000L, false, false, List.of());
        assertEquals("unknown", PearlReport.presence(pearl(T, "Qwerttze", "p", false, true, 1), queued));
    }

    @Test
    void aBodyThatTheServerWouldRefuseIsRecognised() {
        JsonObject body = PearlReport.body(snap(1_000_000, 1, List.of()), new JsonArray(), null);
        assertNull(PearlReport.check(body));
        body.addProperty("temporaryMode", "yes");
        assertNotNull(PearlReport.check(body));
    }

    // The reporter

    private static final class FakeSource implements PearlReporter.Source {
        long now = 1_000_000_000L;
        boolean enabled = true;
        String player = "LoaderBot";
        List<PearlReport.Pearl> pearls = new ArrayList<>();
        LoadTracker.@Nullable Target active;
        boolean watchPresent = true, watchNear;
        long inGameSince = 1;

        @Override public boolean enabled() { return enabled; }
        @Override public String player() { return player; }
        @Override public long now() { return now; }
        @Override public PearlReporter.Reading read(List<LoadAuth.Allowed> allow, LoadTracker.@Nullable Target watching, long graceMs) {
            // Like PearlSource: the pearl asked about is the one watched, or the one of the load in progress; with neither nothing is said
            boolean asked = watching != null || active != null;
            return new PearlReporter.Reading(new PearlReport.Snapshot(player, now, true, false, true, false, inGameSince, graceMs, false, false, pearls), active, asked && watchPresent, true, asked && watchNear, 0);
        }
    }

    private static final class FakeTransport implements PearlReporter.Transport {
        final List<JsonObject> bodies = new ArrayList<>();
        int code = 200;
        String reply = "{\"ok\":true,\"allow\":[{\"name\":\"qwerttze\",\"uuid\":\"069a79f4-44e9-4726-a5be-fca90e38aaf5\"}],\"intervalMs\":30000}";
        int refusedCalls;

        @Override public PearlReporter.Result send(JsonObject body) {
            bodies.add(body);
            return new PearlReporter.Result(code, reply);
        }

        @Override public void refused(int code) { refusedCalls++; }
    }

    @Test
    void theReporterSendsNoChambersFirstThenTheApprovedOnesAfterTheServersAnswer() {
        FakeSource s = new FakeSource();
        s.pearls.add(pearl(T, "Qwerttze", "pearl-1", true, true, 1));
        s.pearls.add(pearl(OTHER, "Stranger", "pearl-2", true, true, 1));
        FakeTransport t = new FakeTransport();
        PearlReporter r = new PearlReporter(s, t);
        r.tick();
        assertEquals(1, t.bodies.size());
        assertEquals(0, t.bodies.get(0).getAsJsonArray("chambers").size(), "nothing is said before the owner's approval arrives");
        assertEquals(1, r.allow().size());
        s.now += 3_000;
        r.tick();
        assertEquals(2, t.bodies.size(), "the chamber appeared: a report at once");
        JsonArray c = t.bodies.get(1).getAsJsonArray("chambers");
        assertEquals(1, c.size());
        assertEquals("pearl-1", c.get(0).getAsJsonObject().get("id").getAsString());
        for (JsonObject b : t.bodies) assertNull(PearlReport.check(b));
        assertFalse(t.bodies.get(1).toString().contains("Stranger"));
    }

    @Test
    void aLoadForAnUnapprovedOrUnpinnedRequesterIsNeverReported() {
        FakeSource s = new FakeSource();
        FakeTransport t = new FakeTransport();
        t.reply = "{\"ok\":true,\"allow\":[{\"name\":\"qwerttze\",\"uuid\":null},{\"name\":\"other\",\"uuid\":\"11111111-2222-3333-4444-555555555555\"}],\"intervalMs\":30000}";
        PearlReporter r = new PearlReporter(s, t);
        r.tick(); // the list arrives: one name unpinned, one pinned to another uuid
        s.active = new LoadTracker.Target(T, "Qwerttze", "pearl-1");
        s.now += 3_000;
        r.tick();
        s.active = new LoadTracker.Target(OTHER, "Qwerttze", "pearl-1");
        s.now += 3_000;
        r.tick();
        s.active = new LoadTracker.Target(OTHER, "Stranger", "pearl-9");
        s.now += 3_000;
        r.tick();
        for (JsonObject b : t.bodies) assertTrue(b.get("load").isJsonNull(), "no load entry for " + b);
        assertNull(r.tracker().load());
    }

    @Test
    void aLoadForAnApprovedTravellerIsReportedFromLoadingToLoaded() {
        FakeSource s = new FakeSource();
        FakeTransport t = new FakeTransport();
        PearlReporter r = new PearlReporter(s, t);
        s.pearls.add(pearl(T, "Qwerttze", "pearl-1", true, true, 1));
        r.tick(); // the list
        s.now += 3_000;
        r.tick();
        s.active = new LoadTracker.Target(T, "Qwerttze", "pearl-1");
        s.now += 3_000;
        r.tick();
        JsonObject loading = t.bodies.get(t.bodies.size() - 1).getAsJsonObject("load");
        assertEquals("loading", loading.get("state").getAsString());
        assertTrue(t.bodies.get(t.bodies.size() - 1).get("busy").getAsBoolean());
        // The walk and click end and the pearl is gone; the requester shows up near the chamber
        s.active = null;
        s.watchPresent = false;
        s.pearls.set(0, new PearlReport.Pearl(T, "Qwerttze", "pearl-1", false, true, true, true, 1));
        s.now += 3_000;
        r.tick();
        s.watchNear = true;
        s.now += 3_000;
        r.tick();
        JsonObject loaded = t.bodies.get(t.bodies.size() - 1).getAsJsonObject("load");
        assertEquals("loaded", loaded.get("state").getAsString());
        assertFalse(loaded.get("pearlGoneAt").isJsonNull());
        assertFalse(loaded.get("requesterNearAt").isJsonNull());
        assertFalse(loaded.get("pearlPresent").getAsBoolean());
        for (JsonObject b : t.bodies) assertNull(PearlReport.check(b));
    }

    @Test
    void aRefusalOfTheServerForgetsTheListAndStopsReportingForAWhile() {
        FakeSource s = new FakeSource();
        FakeTransport t = new FakeTransport();
        PearlReporter r = new PearlReporter(s, t);
        r.tick();
        assertEquals(1, r.allow().size());
        t.code = 409;
        s.now += 31_000;
        r.tick();
        assertTrue(r.allow().isEmpty());
        assertEquals(1, t.refusedCalls);
        int n = t.bodies.size();
        s.now += 10_000;
        r.tick();
        assertEquals(n, t.bodies.size(), "no hammering of a refusing server");
    }

    @Test
    void theListIsForgottenTenMinutesAfterTheLastAnswerAndNothingIsSentWhenTheRoleIsOff() {
        FakeSource s = new FakeSource();
        FakeTransport t = new FakeTransport();
        PearlReporter r = new PearlReporter(s, t);
        r.tick();
        t.code = 500;
        s.now += 11 * 60_000L;
        r.tick();
        assertTrue(r.allow().isEmpty());
        s.enabled = false;
        int n = t.bodies.size();
        s.now += 60_000;
        r.tick();
        assertEquals(n, t.bodies.size());
    }

    @Test
    void aBotWithoutAValidPlayerNameSendsNothing() {
        FakeSource s = new FakeSource();
        s.player = "";
        FakeTransport t = new FakeTransport();
        new PearlReporter(s, t).tick();
        assertTrue(t.bodies.isEmpty());
    }

    @Test
    void aPearlThatJustVanishedFromViewIsNotEmptyYet() {
        long now = 1_000_000;
        PearlReport.Snapshot s = snap(now, now - 600_000, List.of());
        assertEquals("unknown", PearlReport.presence(new PearlReport.Pearl(T, "Qwerttze", "p", false, true, true, true, now - 600_000, 1_000), s));
        assertEquals("empty", PearlReport.presence(new PearlReport.Pearl(T, "Qwerttze", "p", false, true, true, true, now - 600_000, 6_000), s));
    }

    @Test
    void anApprovalThatWentAwayEndsTheLoadWithANullLoad() {
        FakeSource s = new FakeSource();
        FakeTransport t = new FakeTransport();
        PearlReporter r = new PearlReporter(s, t);
        s.pearls.add(pearl(T, "Qwerttze", "pearl-1", true, true, 1));
        r.tick();
        s.active = new LoadTracker.Target(T, "Qwerttze", "pearl-1");
        s.now += 3_000;
        r.tick();
        assertFalse(t.bodies.get(t.bodies.size() - 1).get("load").isJsonNull());
        t.reply = "{\"ok\":true,\"allow\":[],\"intervalMs\":30000}";
        r.sendSoon();
        s.now += 3_000;
        r.tick();
        s.active = null;
        s.now += 3_000;
        r.tick();
        assertTrue(t.bodies.get(t.bodies.size() - 1).get("load").isJsonNull(), "no stale load for a traveller that is no longer approved");
        assertEquals(0, t.bodies.get(t.bodies.size() - 1).getAsJsonArray("chambers").size());
    }

    @Test
    void theFirstLoadOfAPearlIsReportedLoadedEvenWithNothingWatchedBefore() {
        FakeSource s = new FakeSource();
        FakeTransport t = new FakeTransport();
        PearlReporter r = new PearlReporter(s, t);
        s.pearls.add(pearl(T, "Qwerttze", "pearl-1", true, true, 1));
        r.tick();
        // The first sample of the load: the pearl is present, nothing was watched before (PearlSource must look at the load's own pearl)
        s.active = new LoadTracker.Target(T, "Qwerttze", "pearl-1");
        s.now += 3_000;
        r.tick();
        assertTrue(r.tracker().load().get("pearlPresent").getAsBoolean());
        s.active = null;
        s.watchPresent = false;
        s.pearls.set(0, new PearlReport.Pearl(T, "Qwerttze", "pearl-1", false, true, true, true, 1));
        s.now += 3_000;
        r.tick();
        assertEquals("loaded", r.tracker().load().get("state").getAsString());
    }

    // The load tracker

    @Test
    void aPearlStillThereFiveSecondsAfterTheWalkEndedIsAbortedNeverLoaded() {
        LoadTracker tr = new LoadTracker();
        LoadTracker.Target target = new LoadTracker.Target(T, "Qwerttze", "pearl-1");
        tr.sample(new LoadTracker.Input(1_000, target, true, true, true, false, 0));
        tr.sample(new LoadTracker.Input(2_000, null, true, true, true, false, 0));
        assertTrue(tr.busy());
        tr.sample(new LoadTracker.Input(8_000, null, true, true, true, false, 0));
        JsonObject o = tr.load();
        assertEquals("aborted", o.get("state").getAsString());
        assertEquals("timeout", o.get("reason").getAsString());
        assertTrue(o.get("pearlPresent").getAsBoolean());
        assertFalse(tr.busy());
    }

    @Test
    void threePathTriesMakePathFailedAndALostConnectionIsDisconnected() {
        LoadTracker tr = new LoadTracker();
        LoadTracker.Target target = new LoadTracker.Target(T, "Qwerttze", "pearl-1");
        tr.sample(new LoadTracker.Input(1_000, target, true, true, true, false, 3));
        tr.sample(new LoadTracker.Input(2_000, null, true, true, true, false, 3));
        tr.sample(new LoadTracker.Input(8_000, null, true, true, true, false, 3));
        assertEquals("path_failed", tr.load().get("reason").getAsString());
        LoadTracker tr2 = new LoadTracker();
        tr2.sample(new LoadTracker.Input(1_000, target, true, true, true, false, 0));
        tr2.sample(new LoadTracker.Input(2_000, target, false, true, true, false, 0));
        assertEquals("disconnected", tr2.load().get("reason").getAsString());
    }

    @Test
    void aPearlSeenOnlyOnceInRangeDuringTheLoadIsStillReportedGone() {
        LoadTracker tr = new LoadTracker();
        LoadTracker.Target target = new LoadTracker.Target(T, "Qwerttze", "pearl-1");
        // Out of range at the start: not seen, and nothing can be said
        tr.sample(new LoadTracker.Input(1_000, target, true, false, false, false, 0));
        // Walked into range: the pearl is there
        tr.sample(new LoadTracker.Input(2_000, target, true, true, true, false, 0));
        // The click: the pearl is gone
        tr.sample(new LoadTracker.Input(3_000, null, true, false, true, false, 0));
        assertEquals("loaded", tr.load().get("state").getAsString());
        assertFalse(tr.load().get("pearlGoneAt").isJsonNull());
    }

    @Test
    void aLoadOutOfRangeAllTheWayIsAbortedNeverLoaded() {
        LoadTracker tr = new LoadTracker();
        LoadTracker.Target target = new LoadTracker.Target(T, "Qwerttze", "pearl-1");
        tr.sample(new LoadTracker.Input(1_000, target, true, false, false, false, 0));
        tr.sample(new LoadTracker.Input(2_000, null, true, false, false, false, 0));
        tr.sample(new LoadTracker.Input(9_000, null, true, false, false, false, 0));
        assertEquals("aborted", tr.load().get("state").getAsString());
        assertTrue(tr.load().get("pearlGoneAt").isJsonNull());
    }

    @Test
    void aFinishedLoadIsDroppedAfterAMinute() {
        LoadTracker tr = new LoadTracker();
        LoadTracker.Target target = new LoadTracker.Target(T, "Qwerttze", "pearl-1");
        tr.sample(new LoadTracker.Input(1_000, target, true, true, true, false, 0));
        tr.sample(new LoadTracker.Input(2_000, null, true, false, true, false, 0));
        assertEquals("loaded", tr.load().get("state").getAsString());
        tr.sample(new LoadTracker.Input(2_000 + LoadTracker.KEEP_MS + 1, null, true, false, true, false, 0));
        assertNull(tr.load());
    }
}
