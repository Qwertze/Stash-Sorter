package com.porter.qwertestash.pearl;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The trip driver on a fake bot: the world checks before the whisper, the arrival and late-pull rules, the re-arm, the journal and the heartbeat body. */
class PearlTripDriverTest {
    private static final String TRIP = "t-0123456789ab", STATION = "ps-0a1b2c3d", HOME = "ps-11111111";

    /** A bot in a world of one place, with a clock that only moves when the test says. */
    static class FakeWorld implements PearlTripDriver.World {
        long now = 1_767_225_600_000L;
        boolean connected = true, inQueue, inTab = true, whisperReady = true, nonFriend;
        long joinedAt = now - 120_000L;
        double x = 100, y = 64, z = -200, health = 20;
        int food = 20, pearls = 4;
        String dim = "minecraft:overworld";
        @Nullable String hold;
        final List<String> whispers = new ArrayList<>();
        final List<String> alerts = new ArrayList<>();
        int rescans, rearmsStarted;
        boolean rearmRunning, rearmRefused;
        @Nullable String rearmResult;

        @Override public long now() { return now; }
        @Override public boolean connected() { return connected; }
        @Override public boolean inQueue() { return inQueue; }
        @Override public boolean selfInTabList() { return inTab; }
        @Override public boolean whisperReady() { return whisperReady; }
        @Override public long joinedAt() { return connected ? joinedAt : 0; }
        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public @Nullable String dimension() { return dim; }
        @Override public int pearlsCarried() { return pearls; }
        @Override public double health() { return health; }
        @Override public int food() { return food; }
        @Override public boolean nonFriendWithin(double radius) { return nonFriend; }
        @Override public @Nullable String sorterHold() { return hold; }
        boolean whisperFails;
        @Override public boolean whisper(String player, String text) { if (whisperFails) return false; whispers.add(player + ": " + text); return true; }
        boolean lastStrict; @Override public boolean startRearm(boolean strict) { lastStrict = strict; rearmsStarted++; if (rearmRefused) return false; rearmRunning = true; return true; }
        @Override public boolean rearmRunning() { return rearmRunning; }
        @Override public @Nullable String rearmResult() { return rearmResult; }
        @Override public void stopRearm() { rearmRunning = false; }
        @Override public void requestRescan() { rescans++; }
        @Override public void alert(String text) { alerts.add(text); }
        @Override public void info(String text) { }
        @Override public @Nullable String uuid() { return "069a79f4-44e9-4726-a5be-fca90e38aaf5"; }
        int deaths;
        boolean loaderInTab = true;
        @Override public int deaths() { return deaths; }
        @Override public boolean tabListHas(String name) { return loaderInTab; }

        void advance(long ms) { now += ms; }
    }

    @TempDir
    Path dir;
    FakeWorld w;
    PearlTripDriver d;
    boolean enabled = true;

    @BeforeEach
    void setUp() {
        AwayGuard.off();
        w = new FakeWorld();
        d = new PearlTripDriver(w, dir.resolve("pearl-trip.json"), () -> new PearlTripDriver.Config(enabled, 45));
    }

    @AfterEach
    void tearDown() {
        AwayGuard.off();
    }

    private static String reply(String action, String state, String station, String extra) {
        return "{\"ok\":true,\"pearlTrip\":{\"id\":\"" + TRIP + "\",\"action\":\"" + action + "\",\"state\":\"" + state + "\",\"step\":\"ready\",\"station\":\"" + station
            + "\",\"dim\":\"minecraft:the_nether\",\"loader\":\"LoaderBot\",\"pearlId\":\"pearl-1\",\"oneWay\":false,\"go\":false,\"armHome\":false,\"retry\":false,"
            + "\"rearm\":false,\"hold\":false,\"cancel\":false,\"whispers\":0,\"pullDeadlineAt\":null,\"code\":null" + extra + "}}";
    }

    private JsonObject body() {
        JsonObject o = d.body().object();
        assertNotNull(o);
        assertNull(TravelBodyRules.check(o), String.valueOf(TravelBodyRules.check(o)) + " in " + o);
        return o;
    }

    /** Offered, prechecked, told to go: the whisper is out and the bot is awaiting the pull. */
    private void toAwaitingPull() {
        d.onReply(reply("go", "running", STATION, ""));
        assertEquals("ready", d.machine().step());
        d.onReply(reply("go", "running", STATION, "").replace("\"go\":false", "\"go\":true"));
        assertEquals("awaiting_pull", d.machine().step());
    }

    @Test
    void aWholeTripThereWithAnArrivalAndAReArm() {
        d.tick();
        toAwaitingPull();
        assertEquals(List.of("LoaderBot: load pearl-1"), w.whispers);
        assertTrue(AwayGuard.away(), "the guard is on before the pull");
        assertEquals(STATION, AwayGuard.get().station());
        JsonObject b = body();
        assertEquals(1, b.getAsJsonObject("trip").get("whispers").getAsInt());
        assertEquals(4, b.getAsJsonObject("trip").getAsJsonObject("pearls").get("before").getAsInt());

        // A pearl pull: one tick of 400 blocks and another dimension
        w.advance(5_000);
        w.x = 3000;
        w.dim = "minecraft:the_nether";
        d.tick();
        assertEquals("arrived", d.machine().step());
        assertEquals("minecraft:the_nether", body().getAsJsonObject("trip").get("teleportDim").getAsString());

        d.onReply(reply("go", "running", STATION, "").replace("\"rearm\":false", "\"rearm\":true"));
        assertEquals("pickup", d.machine().step());
        w.advance(1000);
        w.pearls = 5; // the loader dropped one and it was picked up
        d.tick();
        assertEquals("rearm", d.machine().step());
        d.tick();
        assertEquals(0, w.rearmsStarted, "2 s to settle first");
        w.advance(2_100);
        d.tick();
        assertEquals(1, w.rearmsStarted);
        w.rearmRunning = false;
        w.rearmResult = "SUCCESS";
        w.pearls = 4;
        w.advance(500);
        d.tick();
        assertEquals("done", d.machine().step());
        assertEquals("completed", d.machine().state());
        JsonObject done = body().getAsJsonObject("trip");
        assertEquals("SUCCESS", done.getAsJsonObject("rearm").get("result").getAsString());
        assertEquals(4, done.getAsJsonObject("pearls").get("after").getAsInt());
        assertTrue(AwayGuard.away(), "still away from the stash after a trip there");
        assertTrue(w.alerts.isEmpty());
    }

    @Test
    void aTripIsNeverWhisperedFromTheQueueOrWithoutBeingInTheWorld() {
        for (String[] c : new String[][] {{"queue", "in_queue"}, {"tab", "not_ingame"}, {"offline", "not_ingame"}}) {
            AwayGuard.off();
            w = new FakeWorld();
            d = new PearlTripDriver(w, dir.resolve("j-" + c[0] + ".json"), () -> new PearlTripDriver.Config(true, 45));
            d.tick();
            d.onReply(reply("go", "running", STATION, ""));
            assertEquals("ready", d.machine().step());
            // The queue (or a disconnect) starts after the local precheck, before the server's go arrives
            if (c[0].equals("queue")) w.inQueue = true;
            if (c[0].equals("tab")) w.inTab = false;
            if (c[0].equals("offline")) w.connected = false;
            d.onReply(reply("go", "running", STATION, "").replace("\"go\":false", "\"go\":true"));
            assertEquals("failed", d.machine().state(), c[0]);
            assertEquals(c[1], d.machine().code(), c[0]);
            assertTrue(w.whispers.isEmpty(), "no whisper for " + c[0]);
            assertFalse(AwayGuard.away(), "the guard never went on for " + c[0]);
        }
    }

    @Test
    void aBotThatJustJoinedWaitsTheGracePeriodBeforeAnyTrip() {
        w.joinedAt = w.now - 20_000L; // 20 s in the game: the contract's 10 s is not enough, the grace period (45 s) is
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        assertEquals("paused", d.machine().state());
        assertEquals("just_joined", d.machine().pauseCode());
        assertEquals("just_joined", body().getAsJsonObject("trip").get("pause").getAsString());
        w.advance(31_000);
        d.tick();
        assertEquals("ready", d.machine().step(), "the 30 s retry passed the check");
    }

    @Test
    void theLocalPrecheckPausesAndRefuses() {
        w.hold = "busy_order";
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        assertEquals("busy_order", d.machine().pauseCode());
        assertEquals(0, d.machine().tries(), "a busy order does not use up tries");
        w.hold = null;
        w.advance(31_000);
        w.nonFriend = true;
        d.tick();
        assertEquals("player_near", d.machine().pauseCode());
        assertEquals(1, d.machine().tries());
        w.nonFriend = false;
        w.advance(31_000);
        w.pearls = 1;
        d.tick();
        assertEquals("failed", d.machine().state());
        assertEquals("low_pearls", d.machine().code());
        assertEquals("low_pearls", body().getAsJsonObject("trip").get("error").getAsString());
    }

    @Test
    void theLoaderNameAndPearlIdComeOnlyFromAValidReply() {
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        String bad = reply("go", "running", STATION, "").replace("\"go\":false", "\"go\":true").replace("LoaderBot", "Loader Bot; /op me");
        d.onReply(bad);
        assertTrue(w.whispers.isEmpty(), "a name that is not a player name is never whispered to");
        assertFalse(AwayGuard.away());
        assertEquals("failed", d.machine().state());
    }

    @Test
    void aJumpOfSevenBlocksIsNotAnArrivalButEightIs() {
        d.tick();
        toAwaitingPull();
        w.x += 7;
        d.tick();
        assertEquals("awaiting_pull", d.machine().step());
        w.x += 8;
        d.tick();
        assertEquals("arrived", d.machine().step());
    }

    @Test
    void aDimensionChangeAloneIsAnArrivalAndItsDimensionIsReported() {
        d.tick();
        toAwaitingPull();
        w.dim = "minecraft:the_end";
        d.tick();
        assertEquals("arrived", d.machine().step());
        assertEquals("minecraft:the_end", body().getAsJsonObject("trip").get("teleportDim").getAsString());
    }

    @Test
    void noPullByTheDeadlineAndStillHereIsAPullTimeoutWithTheGuardOff() {
        d.tick();
        toAwaitingPull();
        w.advance(PearlTrip.DEFAULT_PULL_MS + 1000);
        d.tick();
        assertEquals("failed", d.machine().state());
        assertEquals("pull_timeout", d.machine().code());
        assertFalse(AwayGuard.away());
        assertTrue(d.machine().watch());
        assertEquals("pull_timeout", body().getAsJsonObject("trip").get("error").getAsString());
        // A late pull inside the watch: held, guard back on, and told
        w.advance(60_000);
        w.x += 500;
        d.tick();
        assertEquals("unexpected_location", d.machine().code());
        assertTrue(AwayGuard.away());
        assertTrue(d.machine().hold());
        assertNotNull(body().getAsJsonObject("trip").get("teleportAt"));
    }

    @Test
    void movedAtTheDeadlineWithNoTeleportSeenMeansAPullHappenedSoTheBotStaysAway() {
        d.tick();
        toAwaitingPull();
        // The bot was moved a short hop (under 8 blocks per tick, so no jump seen) to somewhere else
        w.x += 6;
        d.tick();
        w.x += 6;
        d.tick();
        assertEquals("awaiting_pull", d.machine().step());
        w.advance(PearlTrip.DEFAULT_PULL_MS + 1000);
        d.tick();
        assertEquals("unexpected_location", d.machine().code());
        assertTrue(AwayGuard.away(), "held: nothing sorts at the wrong place");
    }

    @Test
    void theWatchEndsAfterFiveMinutes() {
        d.tick();
        toAwaitingPull();
        w.advance(PearlTrip.DEFAULT_PULL_MS + 1000);
        d.tick();
        w.advance(PearlTripDriver.WATCH_MS + 1000);
        d.tick();
        assertFalse(d.machine().watch());
        w.x += 500;
        d.tick();
        assertEquals("pull_timeout", d.machine().code());
        assertFalse(AwayGuard.away());
    }

    @Test
    void aDisconnectAfterTheWhisperIsResumedFromTheJournalEvenAcrossARestart() {
        d.tick();
        toAwaitingPull();
        // The proxy restarts while the bot is waiting; the pull happens while it is offline-ish, so on return it is somewhere else
        w.connected = false;
        d.tick();
        PearlTripDriver again = new PearlTripDriver(w, dir.resolve("pearl-trip.json"), () -> new PearlTripDriver.Config(true, 45));
        assertTrue(AwayGuard.away(), "the away mark came back from the journal");
        assertTrue(again.hasTrip());
        assertEquals("awaiting_pull", again.machine().step());
        w.connected = true;
        w.x += 900;
        again.tick();
        assertEquals("arrived", again.machine().step(), "the journal knows where the whisper was sent from");
    }

    @Test
    void aRestartBeforeTheWhisperDropsTheTripButKeepsTheAwayMark() {
        // Away from an earlier trip, then a new offer that never got to the whisper
        AwayGuard.on(new AwayGuard.Away(STATION, TRIP, w.now));
        d.tick();
        d.onReply(reply("back", "running", HOME, ""));
        assertEquals("ready", d.machine().step());
        PearlTripDriver again = new PearlTripDriver(w, dir.resolve("pearl-trip.json"), () -> new PearlTripDriver.Config(true, 45));
        assertFalse(again.hasTrip());
        assertTrue(AwayGuard.away());
    }

    @Test
    void anUnknownOrFailedReArmWordIsAFailedReArmWithAnAlert() {
        d.tick();
        toAwaitingPull();
        w.x += 500;
        d.tick();
        d.onReply(reply("go", "running", STATION, "").replace("\"rearm\":false", "\"rearm\":true"));
        w.advance(6_000);
        d.tick();
        w.advance(2_100);
        d.tick();
        w.rearmRunning = false;
        w.rearmResult = "SOMETHING_NEW";
        w.advance(500);
        d.tick();
        assertEquals("completed", d.machine().state());
        assertEquals("ABORTED", d.machine().rearmResult());
        assertEquals("rearm_failed", d.machine().warning());
        assertEquals(1, w.alerts.size());
        assertFalse(w.alerts.get(0).matches(".*-?[0-9]{3,}.*"), "no numbers that could be coordinates in the alert: " + w.alerts.get(0));
    }

    @Test
    void aRefusedReArmGivesItsOwnResultWord() {
        d.tick();
        toAwaitingPull();
        w.x += 500;
        d.tick();
        d.onReply(reply("go", "running", STATION, "").replace("\"rearm\":false", "\"rearm\":true"));
        w.advance(6_000);
        d.tick();
        w.rearmRefused = true;
        w.rearmResult = "OCCUPIED";
        w.advance(2_100);
        d.tick();
        assertEquals("OCCUPIED", d.machine().rearmResult());
    }

    @Test
    void aWayBackThatFailsToPullKeepsTheGuardOnBecauseTheBotIsStillAway() {
        AwayGuard.on(new AwayGuard.Away(STATION, TRIP, w.now));
        d.tick();
        d.onReply(reply("back", "running", HOME, ""));
        assertEquals("ready", d.machine().step());
        d.onReply(reply("back", "running", HOME, "").replace("\"go\":false", "\"go\":true"));
        assertEquals("awaiting_pull", d.machine().step());
        w.advance(PearlTrip.DEFAULT_PULL_MS + 1000);
        d.tick();
        assertEquals("pull_timeout", d.machine().code());
        assertTrue(AwayGuard.away());
    }

    @Test
    void aWayBackThatCompletesClearsTheGuardAndAsksForARescan() {
        AwayGuard.on(new AwayGuard.Away(STATION, TRIP, w.now));
        d.tick();
        d.onReply(reply("back", "running", HOME, ""));
        d.onReply(reply("back", "running", HOME, "").replace("\"go\":false", "\"go\":true"));
        w.x += 600;
        d.tick();
        d.onReply(reply("back", "running", HOME, "").replace("\"rearm\":false", "\"rearm\":true"));
        w.advance(6_000);
        d.tick();
        w.advance(2_100);
        d.tick();
        w.rearmRunning = false;
        w.rearmResult = "SUCCESS";
        w.advance(500);
        d.tick();
        assertEquals("completed", d.machine().state());
        assertFalse(AwayGuard.away());
        assertEquals(1, w.rescans);
    }

    @Test
    void theAwayClearOfTheServerEndsAHold() {
        d.tick();
        toAwaitingPull();
        w.advance(PearlTrip.DEFAULT_PULL_MS + 1000);
        d.tick();
        w.x += 500;
        d.tick();
        assertTrue(d.machine().hold());
        d.onReply("{\"ok\":true,\"pearlAwayClear\":true}");
        assertFalse(AwayGuard.away());
        assertFalse(d.machine().hold());
    }

    @Test
    void aMissingTripForThreeRepliesBeforeTheWhisperDropsIt() {
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        for (int i = 0; i < 3; i++) d.onReply("{\"ok\":true}");
        assertEquals("cancelled", d.machine().state());
        assertFalse(d.hasTrip());
    }

    @Test
    void theBodyCarriesNoCoordinatesAndOnlyTheContractsKeys() {
        d.tick();
        toAwaitingPull();
        String json = body().toString();
        assertFalse(json.contains("\"x\"") || json.contains("\"y\"") || json.contains("\"z\""), json);
        assertFalse(json.contains("100") && json.contains("-200"), "no position values: " + json);
    }

    @Test
    void localRequestsAreCheckedAndSentWithAnIdAndTheStationOnly() {
        assertEquals("give the station id (like ps-3f2a9c1d; copy it from the website's Pearl travel panel)", d.requestLocal("go", null, false));
        assertNotNull(d.requestLocal("go", "not-a-station", false));
        assertNotNull(d.requestLocal("back", null, false), "not away");
        assertNotNull(d.requestLocal("home", null, false), "not away");
        assertNull(d.requestLocal("go", STATION, false));
        assertEquals("another request is still waiting", d.requestLocal("go", STATION, false));
        d.tick();
        JsonObject local = body().getAsJsonObject("local");
        assertEquals("go", local.get("action").getAsString());
        assertEquals(STATION, local.get("station").getAsString());
        assertTrue(local.get("localId").getAsString().matches("l-[0-9a-f]{8}"));
    }

    @Test
    void aDisabledTravellerSendsNoObjectAndAnExplicitNullOnceAfterBeingOn() {
        d.tick();
        assertNotNull(d.body().object());
        enabled = false;
        PearlTripDriver.ManagerBody off = d.body();
        assertNull(off.object());
        assertTrue(off.clear());
        PearlTripDriver.ManagerBody again = d.body();
        assertNull(again.object());
        assertFalse(again.clear());
        assertNotNull(d.requestLocal("go", STATION, false), "local commands are refused while off");
    }

    @Test
    void aTripOfferedWhileTravelIsOffIsIgnored() {
        enabled = false;
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        assertFalse(d.hasTrip());
    }

    @Test
    void theStopCommandDropsATripBeforeTheWhisperOnly() {
        w.hold = "sorter_busy";
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        d.requestStop();
        d.tick();
        assertEquals("cancelled", d.machine().state());
        AwayGuard.off();
        w.hold = null;
        d = new PearlTripDriver(w, dir.resolve("j2.json"), () -> new PearlTripDriver.Config(true, 45));
        d.tick();
        toAwaitingPull();
        d.requestStop();
        d.tick();
        assertEquals("running", d.machine().state(), "a pull can't be undone");
    }

    @Test
    void theStatusLineHasNoCoordinates() {
        d.tick();
        toAwaitingPull();
        String s = d.statusLine();
        assertFalse(s.matches(".*[0-9]{3,}.*"), s);
        assertTrue(s.contains("away"), s);
    }

    @Test
    void aFinishedTripIsReportedUntilTheServerShowsItsEnd() {
        d.tick();
        toAwaitingPull();
        w.x += 500;
        d.tick();
        d.onReply(reply("go", "running", STATION, "").replace("\"rearm\":false", "\"rearm\":true"));
        w.advance(6_000);
        d.tick();
        w.advance(2_100);
        d.tick();
        w.rearmRunning = false;
        w.rearmResult = "PARTIAL";
        w.advance(500);
        d.tick();
        assertTrue(d.hasTrip());
        assertTrue(body().get("trip").isJsonObject(), "the final state is still sent");
        d.onReply(reply("go", "completed", STATION, ""));
        assertFalse(d.hasTrip());
        assertTrue(body().get("trip").isJsonNull());
    }

    @Test
    void aDisconnectEventIsNoticedEvenWhenTheBotIsBackBeforeTheNextTick() {
        d.tick();
        toAwaitingPull();
        d.onDisconnect();
        w.x += 900; // back in the game somewhere else (the bot's ticks did not run meanwhile)
        d.tick();
        assertEquals("arrived", d.machine().step(), "the reconnect check against the whisper's place saw the pull");
    }

    @Test
    void theJournalHoldsTheWhisperPlaceAndNoHeartbeatCarriesIt() throws Exception {
        d.tick();
        toAwaitingPull();
        String file = java.nio.file.Files.readString(dir.resolve("pearl-trip.json"));
        assertTrue(file.contains("awaiting_pull"));
        // The journal is for the bot alone: it is the only place with the whisper's position, and it is never part of a heartbeat
        assertFalse(body().toString().contains("from"));
    }

    @Test
    void aPullBeforeTheServerHeardOfTheWhisperIsReportedAsAwaitingPullWithItsTeleport() {
        d.tick();
        toAwaitingPull();
        w.x += 500;
        d.tick();
        assertEquals("arrived", d.machine().step());
        JsonObject first = body().getAsJsonObject("trip");
        assertEquals("awaiting_pull", first.get("step").getAsString(), "the server takes the whisper only from this step");
        assertFalse(first.get("teleportAt").isJsonNull());
        assertEquals(1, first.get("whispers").getAsInt());
        d.onReply(reply("go", "running", STATION, ""));
        assertEquals("arrived", body().getAsJsonObject("trip").get("step").getAsString(), "once a reply came after it, the real step is reported");
    }

    private String go() {
        return reply("go", "running", STATION, "").replace("\"go\":false", "\"go\":true");
    }

    @Test
    void aQueuePositionIsNotATeleportAndBeingBackInTheWorldIsNotAnArrival() {
        d.tick();
        toAwaitingPull();
        w.inQueue = true;
        w.x += 5000;
        d.tick();
        assertEquals("awaiting_pull", d.machine().step(), "nothing is read from the queue");
        w.x -= 5000;
        w.inQueue = false;
        d.tick();
        assertEquals("awaiting_pull", d.machine().step(), "back at the place it whispered from");
    }

    @Test
    void aDeathAfterTheWhisperIsHeldNotReadAsAnArrival() {
        d.tick();
        toAwaitingPull();
        w.deaths++;
        w.connected = false;
        d.tick();
        w.connected = true;
        w.x += 5000; // respawned far away
        d.tick();
        assertEquals("failed", d.machine().state());
        assertEquals("unexpected_location", d.machine().code());
        assertTrue(d.machine().hold());
        assertTrue(AwayGuard.away());
    }

    @Test
    void theSorterIsQuiescedFromTheCheckpointToTheWhisper() {
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        assertTrue(AwayGuard.quiesced());
        assertFalse(AwayGuard.away(), "quiesced is not away");
        d.onReply(go());
        assertFalse(AwayGuard.quiesced());
        assertTrue(AwayGuard.away());
        AwayGuard.off();
        d = new PearlTripDriver(w, dir.resolve("q.json"), () -> new PearlTripDriver.Config(true, 45));
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        assertTrue(AwayGuard.quiesced());
        d.requestStop();
        d.tick();
        assertFalse(AwayGuard.quiesced(), "cleared when the trip ends before the whisper");
    }

    @Test
    void aGoWaitsForTheSorterABystanderAndTheLoaderThenFails() {
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        w.hold = "sorter_busy";
        d.onReply(go());
        assertTrue(w.whispers.isEmpty());
        assertEquals("ready", d.machine().step());
        assertEquals("running", d.machine().state(), "a wait, not a failure");
        w.hold = null;
        w.nonFriend = true;
        d.onReply(go());
        assertTrue(w.whispers.isEmpty());
        w.nonFriend = false;
        w.loaderInTab = false;
        d.onReply(go());
        assertTrue(w.whispers.isEmpty(), "the loader is not in the tab list");
        w.advance(PearlTripDriver.GO_WAIT_MS + 1000);
        d.onReply(go());
        assertEquals("failed", d.machine().state());
        assertEquals("loader_stale", d.machine().code());
        assertTrue(w.whispers.isEmpty());
        assertFalse(AwayGuard.away());
    }

    @Test
    void aGoGoesOnceTheWaitIsOver() {
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        w.hold = "busy_order";
        d.onReply(go());
        w.hold = null;
        d.onReply(go());
        assertEquals(1, w.whispers.size());
    }

    @Test
    void homeWorksWhileTravelIsOffAndTheBodyKeepsGoingWhileAway() {
        AwayGuard.on(new AwayGuard.Away(STATION, TRIP, w.now));
        enabled = false;
        assertNull(d.requestLocal("home", null, false));
        assertNotNull(d.requestLocal("go", STATION, false));
        d.tick();
        JsonObject o = body();
        assertFalse(o.get("enabled").getAsBoolean());
        assertEquals("home", o.getAsJsonObject("local").get("action").getAsString());
        assertTrue(d.active());
        d.onReply("{\"ok\":true,\"pearlAwayClear\":true}");
        assertFalse(AwayGuard.away());
        assertNull(d.body().object(), "away: null was sent, then travel is off again");
    }

    @Test
    void homeForceDropsTheMarkAndTheTripWhateverTheServerSays() {
        d.tick();
        toAwaitingPull();
        d.requestForceHome();
        d.tick();
        assertFalse(AwayGuard.away());
        assertFalse(d.hasTrip());
        assertFalse(AwayGuard.quiesced());
    }

    @Test
    void anAwayClearDuringAnOpenTripIsNotHonoured() {
        d.tick();
        toAwaitingPull();
        d.onReply("{\"ok\":true,\"pearlAwayClear\":true}");
        assertTrue(AwayGuard.away());
    }

    @Test
    void offersThatDoNotFitWhereTheBotIsAreIgnored() {
        AwayGuard.on(new AwayGuard.Away(STATION, TRIP, w.now));
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        assertFalse(d.hasTrip(), "a trip there while away");
        AwayGuard.off();
        d.onReply(reply("back", "running", HOME, ""));
        assertFalse(d.hasTrip(), "a way back from home");
    }

    @Test
    void aPullDeadlineFromTheServerIsKeptWithinTenMinutes() {
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        d.onReply(go().replace("\"pullDeadlineAt\":null", "\"pullDeadlineAt\":" + (w.now + 86_400_000L)));
        assertTrue(d.machine().deadlineAt() <= w.now + 600_000L);
        assertTrue(d.machine().deadlineAt() >= w.now);
    }

    @Test
    void noWhisperIsSentWhenTheJournalCannotBeWritten() throws Exception {
        Path blocker = dir.resolve("blocker");
        java.nio.file.Files.writeString(blocker, "x");
        d = new PearlTripDriver(w, blocker.resolve("j.json"), () -> new PearlTripDriver.Config(true, 45));
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        d.onReply(go());
        assertTrue(w.whispers.isEmpty(), "the write-ahead failed, so nothing was whispered");
        assertEquals("failed", d.machine().state());
        assertEquals(0, d.machine().whispers());
        assertFalse(AwayGuard.away());
    }

    @Test
    void theRetryWhisperMeetsTheSameConditions() {
        d.tick();
        toAwaitingPull();
        w.loaderInTab = false;
        d.onReply(reply("go", "running", STATION, "").replace("\"retry\":false", "\"retry\":true"));
        assertEquals(1, d.machine().whispers());
        w.loaderInTab = true;
        d.onReply(reply("go", "running", STATION, "").replace("\"retry\":false", "\"retry\":true"));
        assertEquals(1, d.machine().whispers(), "30 s have not passed since the first whisper");
        w.advance(PearlTripDriver.WHISPER_GAP_MS + 1000);
        d.onReply(reply("go", "running", STATION, "").replace("\"retry\":false", "\"retry\":true"));
        assertEquals(2, d.machine().whispers());
    }

    @Test
    void anOccupiedChamberRightAfterARestartIsASuccess() {
        d.tick();
        toAwaitingPull();
        w.x += 500;
        d.tick();
        d.onReply(reply("go", "running", STATION, "").replace("\"rearm\":false", "\"rearm\":true"));
        w.advance(6_000);
        d.tick();
        assertEquals("rearm", d.machine().step());
        PearlTripDriver again = new PearlTripDriver(w, dir.resolve("pearl-trip.json"), () -> new PearlTripDriver.Config(true, 45));
        assertEquals("rearm", again.machine().step());
        w.rearmRefused = true;
        w.rearmResult = "OCCUPIED";
        again.tick();
        w.advance(2_100);
        again.tick();
        assertEquals("completed", again.machine().state());
        assertEquals("SUCCESS", again.machine().rearmResult());
        assertTrue(w.alerts.isEmpty());
    }

    @Test
    void aNewWayBackIsLetThroughWhileTheWatchOfAFailedOneRuns() {
        AwayGuard.on(new AwayGuard.Away(STATION, TRIP, w.now));
        d.tick();
        d.onReply(reply("back", "running", HOME, ""));
        d.onReply(reply("back", "running", HOME, "").replace("\"go\":false", "\"go\":true"));
        w.advance(PearlTrip.DEFAULT_PULL_MS + 1000);
        d.tick();
        assertTrue(d.machine().watch());
        d.onReply(reply("back", "running", HOME, "").replace(TRIP, "t-aaaaaaaaaaaa"));
        assertEquals("t-aaaaaaaaaaaa", d.machine().id());
        assertEquals("ready", d.machine().step());
    }

    @Test
    void aLocalRequestIsSentUntilFourRepliesCameBack() {
        assertNull(d.requestLocal("go", STATION, false));
        d.tick();
        for (int i = 0; i < 3; i++) {
            assertFalse(body().get("local").isJsonNull(), "still sent before reply " + i);
            d.onReply("{\"ok\":true}");
        }
        assertFalse(body().get("local").isJsonNull());
        d.onReply("{\"ok\":true}");
        assertTrue(body().get("local").isJsonNull());
    }

    @Test
    void twoGoTripsAreFiveMinutesApart() {
        d.tick();
        toAwaitingPull();
        AwayGuard.off();
        assertEquals("wait 5 minutes between trips", d.requestLocal("go", STATION, false));
        w.advance(PearlTripDriver.GO_SPACING_MS + 1000);
        assertNull(d.requestLocal("go", STATION, false));
    }

    @Test
    void theStatusSaysWhatToDoWhenTheHomeChamberIsOutOfReach() {
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        d.onReply(reply("go", "running", STATION, "").replace("\"armHome\":false", "\"armHome\":true"));
        w.rearmRefused = true;
        w.rearmResult = "NO_CHAMBER";
        d.tick();
        assertEquals("failed", d.machine().state());
        assertTrue(d.statusLine().contains("within 2 blocks"), d.statusLine());
        assertTrue(w.alerts.get(0).contains("within 2 blocks"));
    }

    @Test
    void theQuiesceNeverOutlivesTravelBeingOff() {
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        assertTrue(AwayGuard.quiesced());
        enabled = false;
        d.tick();
        assertFalse(AwayGuard.quiesced());
        assertFalse(d.hasTrip());
    }

    @Test
    void theQuiesceNeverOutlivesATripThatDoesNotGo() {
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        w.advance(PearlTripDriver.GO_WAIT_MS + 119_000L);
        d.tick();
        assertTrue(AwayGuard.quiesced(), "still within the wait and the server's 2 minutes");
        w.advance(2_000L);
        d.tick();
        assertFalse(AwayGuard.quiesced());
        assertFalse(d.hasTrip());
        assertEquals("cancelled", d.machine().state());
    }

    @Test
    void aWhisperThatCouldNotBeSentRevokesTheTripAndReportsNoLoadSent() {
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        w.whisperFails = true;
        d.onReply(go());
        assertEquals("failed", d.machine().state());
        assertEquals(0, d.machine().whispers());
        assertFalse(AwayGuard.away());
        assertEquals("ready", body().getAsJsonObject("trip").get("step").getAsString());
        assertTrue(body().getAsJsonObject("trip").get("loadSentAt").isJsonNull());
        assertEquals(0, d.requestLocal("go", STATION, false) == null ? 0 : 1, "no five-minute lockout for a whisper that never left");
    }

    @Test
    void anOccupiedChamberAfterAReconnectWithoutARestartIsASuccessToo() {
        d.tick();
        toAwaitingPull();
        w.x += 500;
        d.tick();
        d.onReply(reply("go", "running", STATION, "").replace("\"rearm\":false", "\"rearm\":true"));
        w.advance(6_000);
        d.tick();
        w.advance(2_100);
        d.tick();
        assertEquals(1, w.rearmsStarted);
        w.connected = false; // the throw was running when the connection went
        d.tick();
        w.connected = true;
        w.rearmRunning = false;
        w.rearmRefused = true;
        w.rearmResult = "OCCUPIED";
        d.tick();
        w.advance(2_100);
        d.tick();
        assertEquals("completed", d.machine().state());
        assertEquals("SUCCESS", d.machine().rearmResult());
    }

    @Test
    void theEndOfTheWatchIsWrittenToTheJournal() throws Exception {
        d.tick();
        toAwaitingPull();
        w.advance(PearlTrip.DEFAULT_PULL_MS + 1000);
        d.tick();
        assertTrue(java.nio.file.Files.readString(dir.resolve("pearl-trip.json")).contains("\"watch\":true"));
        w.advance(PearlTripDriver.WATCH_MS + 1000);
        d.tick();
        assertTrue(java.nio.file.Files.readString(dir.resolve("pearl-trip.json")).contains("\"watch\":false"));
    }

    @Test
    void theWhisperGapSurvivesARestart() {
        d.tick();
        toAwaitingPull();
        w.advance(10_000);
        PearlTripDriver again = new PearlTripDriver(w, dir.resolve("pearl-trip.json"), () -> new PearlTripDriver.Config(true, 45));
        assertEquals("gap", again.whisperLimit(w.now), "the last whisper is kept in the journal");
        w.advance(21_000);
        assertNull(again.whisperLimit(w.now));
    }

    @Test
    void aWayBackOfferedRightAfterAFailedGoWaitsForTheGapThenWhispers() {
        d.tick();
        toAwaitingPull();
        d.onReply(reply("go", "failed", STATION, "").replace("\"code\":null", "\"code\":\"loader_no_answer\""));
        assertEquals("failed", d.machine().state());
        AwayGuard.on(new AwayGuard.Away(STATION, TRIP, w.now)); // away again (a late pull), then the server offers the way back
        d.onReply(reply("back", "running", HOME, "").replace(TRIP, "t-bbbbbbbbbbbb"));
        assertEquals("t-bbbbbbbbbbbb", d.machine().id());
        String go = reply("back", "running", HOME, "").replace(TRIP, "t-bbbbbbbbbbbb").replace("\"go\":false", "\"go\":true");
        d.onReply(go);
        assertEquals(1, w.whispers.size(), "the way back waits: the gap since the first whisper");
        w.advance(PearlTripDriver.WHISPER_GAP_MS + 1000);
        d.onReply(go);
        assertEquals(2, w.whispers.size());
    }

    @Test
    void tenWhispersAnHourIsTheCap() {
        d.tick();
        for (int i = 0; i < PearlTripDriver.WHISPERS_PER_HOUR; i++) {
            String id = "t-" + String.format("%012x", i + 1);
            d.onReply(reply("go", "running", STATION, "").replace(TRIP, id));
            d.onReply(go().replace(TRIP, id));
            assertEquals(i + 1, w.whispers.size(), "whisper " + i);
            d.requestForceHome();
            d.tick();
            w.advance(PearlTripDriver.GO_SPACING_MS + 1000);
            d.tick();
        }
        // Eleven in under an hour would be 11 x 5 min = 55 min here: the eleventh waits, then fails
        d.onReply(reply("go", "running", STATION, "").replace(TRIP, "t-ffffffffffff"));
        d.onReply(go().replace(TRIP, "t-ffffffffffff"));
        assertEquals(PearlTripDriver.WHISPERS_PER_HOUR, w.whispers.size(), "the eleventh in the hour waits");
        assertEquals("running", d.machine().state(), "a local wait first");
        w.advance(PearlTripDriver.GO_WAIT_MS + 1000);
        d.onReply(go().replace(TRIP, "t-ffffffffffff"));
        assertEquals("failed", d.machine().state(), "then it fails with the closest code");
        assertEquals(PearlTripDriver.WHISPERS_PER_HOUR, w.whispers.size());
    }

    @Test
    void aNewLoginWithoutADisconnectResetsTheMovementBaseline() {
        d.tick();
        toAwaitingPull();
        // A proxy server switch: a new join time and a new place, and the driver never saw a disconnect
        w.joinedAt = w.now + 1;
        w.x += 7000;
        w.dim = "minecraft:the_nether";
        d.tick();
        assertEquals("arrived", d.machine().step(), "read once, by the reconnect check against the whisper place (the server judges it from the loader's report)");
        long at = d.machine().teleportAt();
        w.x += 3;
        d.tick();
        assertEquals(at, d.machine().teleportAt(), "the first position of the new login is a baseline, not a second teleport");
    }

    @Test
    void theHomeThrowAsksForTheStrictChamberChoiceAndTheReArmDoesNot() {
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        d.onReply(reply("go", "running", STATION, "").replace("\"armHome\":false", "\"armHome\":true"));
        d.tick();
        assertEquals(1, w.rearmsStarted);
        assertTrue(w.lastStrict, "arm_home: no fallback to a chamber that merely is near");
        w.rearmRunning = false;
        w.rearmResult = "SUCCESS";
        w.advance(500);
        d.tick();
        assertEquals("ready", d.machine().step());
        d.onReply(go());
        w.x += 500;
        d.tick();
        d.onReply(reply("go", "running", STATION, "").replace("\"rearm\":false", "\"rearm\":true"));
        w.advance(6_000);
        d.tick();
        w.advance(2_100);
        d.tick();
        assertEquals(2, w.rearmsStarted);
        assertFalse(w.lastStrict, "the re-arm on arrival keeps the fallback");
    }

    @Test
    void aLocalRequestThatGetsNoAnswerIsReportedToTheUser() {
        assertNull(d.requestLocal("go", STATION, false));
        d.tick();
        for (int i = 0; i < 4; i++) d.onReply("{\"ok\":true}");
        assertTrue(w.alerts.get(0).contains("needs Stash Manager 1.3.0"), w.alerts.toString());
        assertTrue(d.statusLine().contains("1.3.0"), d.statusLine());
    }

    @Test
    void aHomeThatTheServerHonouredIsNotReportedAsUnanswered() {
        AwayGuard.on(new AwayGuard.Away(STATION, TRIP, w.now));
        assertNull(d.requestLocal("home", null, false));
        d.tick();
        d.onReply("{\"ok\":true,\"pearlAwayClear\":true}");
        assertFalse(AwayGuard.away());
        for (int i = 0; i < 5; i++) d.onReply("{\"ok\":true}");
        assertTrue(w.alerts.isEmpty(), w.alerts.toString());
        AwayGuard.on(new AwayGuard.Away(STATION, TRIP, w.now));
        assertNull(d.requestLocal("home", null, false));
        d.tick();
        AwayGuard.off(); // cleared on the website first
        for (int i = 0; i < 5; i++) d.onReply("{\"ok\":true}");
        assertTrue(w.alerts.isEmpty(), w.alerts.toString());
    }

    @Test
    void aStaleSuccessAfterARefusedStartIsNeverBelieved() {
        d.tick();
        d.onReply(reply("go", "running", STATION, ""));
        d.onReply(reply("go", "running", STATION, "").replace("\"armHome\":false", "\"armHome\":true"));
        w.rearmRefused = true;
        w.rearmResult = "SUCCESS";
        d.tick();
        assertEquals("failed", d.machine().state(), "the home throw did not happen");
        assertFalse(w.alerts.isEmpty());
    }

    @Test
    void aStaleSuccessAfterARefusedReArmIsAFailedReArm() {
        d.tick();
        toAwaitingPull();
        w.x += 500;
        d.tick();
        d.onReply(reply("go", "running", STATION, "").replace("\"rearm\":false", "\"rearm\":true"));
        w.advance(6_000);
        d.tick();
        w.rearmRefused = true;
        w.rearmResult = "SUCCESS";
        w.advance(2_100);
        d.tick();
        assertEquals("completed", d.machine().state());
        assertEquals("NO_CHAMBER", d.machine().rearmResult());
        assertEquals("rearm_failed", d.machine().warning());
    }

    @Test
    void aRetryWhisperThatCouldNotBeSentLeavesOneWhisperAndNoLoadSent() {
        d.tick();
        toAwaitingPull();
        w.advance(PearlTripDriver.WHISPER_GAP_MS + 1000);
        w.whisperFails = true;
        d.onReply(reply("go", "running", STATION, "").replace("\"retry\":false", "\"retry\":true"));
        assertEquals(1, d.machine().whispers());
        assertEquals("awaiting_pull", d.machine().step());
        assertEquals("running", d.machine().state());
        assertEquals(1, w.whispers.size());
        w.whisperFails = false;
        d.onReply(reply("go", "running", STATION, "").replace("\"retry\":false", "\"retry\":true"));
        assertEquals(2, d.machine().whispers(), "the retry is still possible");
    }
}
