package com.porter.qwertestash.pearl;

import com.google.gson.JsonParser;
import com.porter.qwertestash.orders.OrderBlocker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The away guard and the order blocker, the journal's file handling, the reply's strictness, the machine's edge cases and the activity text. */
class PearlMiscTest {
    @AfterEach
    void off() {
        AwayGuard.off();
    }

    private static OrderBlocker.Snapshot atStash() {
        return new OrderBlocker.Snapshot(true, true, true, false, false, true, "minecraft:overworld", "minecraft:overworld", 10, null, 20, true, true);
    }

    @Test
    void whileAwayTheOrderBlockerSaysTravellingAwayAndOtherwiseNothingChanged() {
        assertNull(OrderBlocker.compute(atStash()));
        OrderBlocker.Snapshot a = atStash();
        OrderBlocker.Snapshot away = new OrderBlocker.Snapshot(a.acceptOrders(), a.deliverySet(), a.inGame(), a.inQueue(), a.playerControlled(), a.hasStash(), a.deliveryDimension(),
            a.currentDimension(), a.deliveryDistance(), a.busyWith(), a.freeCarrySlots(), a.shulkersKnown(), a.stashRead(), true);
        assertEquals("travelling/away", OrderBlocker.compute(away));
        assertEquals(AwayGuard.REASON, OrderBlocker.AWAY);
        // "orders off" still says so first
        OrderBlocker.Snapshot off = new OrderBlocker.Snapshot(false, true, true, false, false, true, null, null, 0, null, 20, true, true, true);
        assertEquals("orders off", OrderBlocker.compute(off));
    }

    @Test
    void theGuardIsAGlobalMark() {
        assertFalse(AwayGuard.away());
        assertNull(AwayGuard.blocker());
        AwayGuard.on(new AwayGuard.Away("ps-0a1b2c3d", null, 5));
        assertTrue(AwayGuard.away());
        assertEquals("travelling/away", AwayGuard.blocker());
        AwayGuard.off();
        assertFalse(AwayGuard.away());
    }

    @Test
    void aServerHoldOnATripThatNeverWhisperedDoesNotTurnTheGuardOn() {
        PearlTrip t = new PearlTrip(() -> 1000L);
        t.offer("go");
        t.precheck(true, null);
        PearlReply r = PearlReply.parse(JsonParser.parseString("{\"id\":\"t-0123456789ab\",\"action\":\"go\",\"state\":\"failed\",\"hold\":true,\"code\":\"unexpected_location\"}"));
        List<PearlTrip.Action> out = t.reply(r, true);
        assertEquals("failed", t.state());
        assertFalse(t.away());
        assertFalse(t.hold());
        assertTrue(out.isEmpty());
    }

    @Test
    void theJournalRoundTripsAndAnythingDamagedOrOddIsIgnored(@TempDir Path dir) throws Exception {
        PearlJournal j = new PearlJournal(dir.resolve("j.json"));
        assertNull(j.load().trip, "no file");
        PearlJournal.Data d = new PearlJournal.Data();
        d.awayStation = "ps-0a1b2c3d";
        d.trip = new PearlJournal.Trip();
        d.trip.id = "t-0123456789ab";
        d.trip.action = "go";
        d.trip.step = "awaiting_pull";
        d.trip.state = "running";
        d.trip.pearlId = "pearl-1";
        d.trip.loader = "LoaderBot";
        d.trip.whispers = 1;
        j.save(d);
        PearlJournal.Data back = j.load();
        assertEquals("ps-0a1b2c3d", back.awayStation);
        assertEquals("awaiting_pull", back.trip.step);

        d.trip.loader = "bad name; /op me";
        j.save(d);
        assertNull(j.load().trip, "a loader name that is not a player name is dropped on reading, like one from the network");
        d.trip.loader = "LoaderBot";
        d.awayStation = "not-a-station";
        j.save(d);
        assertNull(j.load().awayStation);

        Files.writeString(j.file(), "{not json");
        assertNull(j.load().trip);
        Files.writeString(j.file(), "{\"v\":99}");
        assertNull(j.load().trip);
        Files.writeString(j.file(), "x".repeat(100_000));
        assertNull(j.load().trip);
    }

    @Test
    void aReplyIsReadStrictly() {
        String ok = "{\"id\":\"t-0123456789ab\",\"action\":\"go\",\"state\":\"running\",\"loader\":\"LoaderBot\",\"pearlId\":\"pearl-1\",\"go\":true,\"pullDeadlineAt\":1767225750000}";
        PearlReply r = PearlReply.parse(JsonParser.parseString(ok));
        assertNotNull(r);
        assertTrue(r.go());
        assertEquals(1767225750000L, r.pullDeadlineAt());
        assertNull(PearlReply.parse(JsonParser.parseString("{\"id\":\"x\",\"action\":\"go\"}")), "bad trip id");
        assertNull(PearlReply.parse(JsonParser.parseString("{\"id\":\"t-0123456789ab\",\"action\":\"teleport\"}")), "unknown action");
        assertNull(PearlReply.parse(JsonParser.parseString("5")));
        PearlReply odd = PearlReply.parse(JsonParser.parseString("{\"id\":\"t-0123456789ab\",\"action\":\"go\",\"go\":\"true\",\"loader\":\"a b\",\"pearlId\":\"x y\",\"pullDeadlineAt\":\"soon\",\"state\":\"running\"}"));
        assertNotNull(odd);
        assertFalse(odd.go(), "a flag that is not a boolean is false");
        assertNull(odd.loader());
        assertNull(odd.pearlId());
        assertEquals(0, odd.pullDeadlineAt());
        assertEquals("running", odd.state());
        assertNull(PearlReply.parse(JsonParser.parseString("{\"id\":\"t-0123456789ab\",\"action\":\"go\",\"state\":\"nope\"}")), "an unknown state");
        assertNull(PearlReply.parse(JsonParser.parseString("{\"id\":\"t-0123456789ab\",\"action\":\"go\"}")), "no state");
        assertNotNull(PearlReply.parse(JsonParser.parseString("{\"id\":\"t-0123456789ab\",\"action\":\"go\",\"state\":\"pending\"}")));
    }

    @Test
    void aGoWithoutALoaderOrPearlIdWhispersNothing() {
        PearlTrip t = new PearlTrip(() -> 1000L);
        t.offer("go");
        t.precheck(true, null);
        PearlReply r = PearlReply.parse(JsonParser.parseString("{\"id\":\"t-0123456789ab\",\"action\":\"go\",\"state\":\"running\",\"go\":true}"));
        List<PearlTrip.Action> out = t.reply(r, true);
        assertEquals(0, t.whispers());
        assertFalse(t.away());
        assertEquals("failed", t.state());
        assertEquals("report:error:stopped", out.get(0).toString());
    }

    @Test
    void anUnknownLocalCodeIsReportedAsOneTheServerKnows() {
        assertEquals("stopped", PearlTrip.safeError("made_up"));
        assertEquals("low_pearls", PearlTrip.safeError("low_pearls"));
        PearlTrip t = new PearlTrip(() -> 1000L);
        t.offer("go");
        t.precheck(false, "something_new");
        assertEquals("failed", t.state());
        assertEquals("stopped", t.code());
    }

    @Test
    void anArmHomeIsOnlyOnceAndAnAbortBeforeTheWhisperFailsTheTrip() {
        PearlTrip t = new PearlTrip(() -> 1000L);
        t.offer("go");
        t.precheck(true, null);
        PearlReply arm = PearlReply.parse(JsonParser.parseString("{\"id\":\"t-0123456789ab\",\"action\":\"go\",\"state\":\"running\",\"armHome\":true}"));
        assertEquals("armHome", t.reply(arm, true).get(0).toString());
        t.armHomeDone();
        assertTrue(t.reply(arm, true).isEmpty(), "the server repeating armHome does not throw a second pearl");
        assertEquals("report:error:in_queue", t.abort("in_queue").get(0).toString());
        assertEquals("failed", t.state());
        assertFalse(t.away());
    }

    @Test
    void theActivityTextOfTheLoaderHasNoCoordinates() throws Exception {
        // The planned leak fix: the "at x y z" part of the Pearl Loader activity item is gone for good
        String src = Files.readString(Path.of("src", "main", "java", "com", "porter", "qwertestash", "manager", "ProxySources.java"));
        assertFalse(src.contains("\"; at \" + now.x()"), "ProxySources still puts the pearl's position in the activity text");
        assertFalse(src.contains("now.x() + \" \" + now.y()"));
    }

    @Test
    void aJournalWithOddValuesIsCleanedOnReading(@TempDir Path dir) throws Exception {
        PearlJournal j = new PearlJournal(dir.resolve("j.json"));
        PearlJournal.Data d = new PearlJournal.Data();
        d.trip = new PearlJournal.Trip();
        d.trip.id = "t-0123456789ab";
        d.trip.action = "go";
        d.trip.step = "awaiting_pull";
        d.trip.state = "running";
        d.trip.whispers = 99;
        d.trip.tries = -4;
        d.trip.haveFrom = true;
        d.trip.fromDim = "not a dimension!";
        j.save(d);
        PearlJournal.Data back = j.load();
        assertEquals(2, back.trip.whispers);
        assertEquals(0, back.trip.tries);
        assertFalse(back.trip.haveFrom, "an invalid dimension drops the place");
        d.trip.step = "teleport";
        j.save(d);
        assertNull(j.load().trip, "a step that is not one of the contract's");
        d.trip.step = "awaiting_pull";
        d.trip.state = "exploded";
        j.save(d);
        assertNull(j.load().trip);
        d.trip.state = "running";
        d.trip.fromDim = "minecraft:overworld";
        j.save(d);
        Files.writeString(j.file(), Files.readString(j.file()).replace("\"fromX\":0.0", "\"fromX\":1e999"));
        PearlJournal.Data inf = j.load();
        assertTrue(inf.trip == null || !inf.trip.haveFrom, "a number that is not finite is not a place");
    }

    @Test
    void theJournalFileIsForItsOwnerAlone(@TempDir Path dir) throws Exception {
        PearlJournal j = new PearlJournal(dir.resolve("j.json"));
        j.save(new PearlJournal.Data());
        try {
            var perms = Files.getPosixFilePermissions(j.file());
            assertEquals(java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), perms);
        } catch (UnsupportedOperationException e) {
            assertTrue(j.file().toFile().canRead(), "no POSIX permissions here: the file flags are set instead");
        }
    }

    @Test
    void timesInTheJournalAreClampedToTheClock(@TempDir Path dir) throws Exception {
        PearlJournal j = new PearlJournal(dir.resolve("j.json"));
        PearlJournal.Data d = new PearlJournal.Data();
        d.awaySince = 9_000_000_000_000L;
        d.lastGoAt = 9_000_000_000_000L;
        d.trip = new PearlJournal.Trip();
        d.trip.id = "t-0123456789ab";
        d.trip.action = "go";
        d.trip.step = "awaiting_pull";
        d.trip.state = "running";
        d.trip.deadlineAt = 9_000_000_000_000L;
        d.trip.watchUntil = 9_000_000_000_000L;
        d.trip.teleportAt = -5;
        j.save(d);
        PearlJournal.Data back = j.load(1_000_000L);
        assertEquals(1_000_000L, back.awaySince);
        assertEquals(1_000_000L, back.lastGoAt);
        assertEquals(1_600_000L, back.trip.deadlineAt);
        assertEquals(1_600_000L, back.trip.watchUntil);
        assertEquals(0, back.trip.teleportAt);
    }
}
