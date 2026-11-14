package com.porter.qwertestash.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.ManagerHttp;
import com.porter.qwertestash.tasks.TaskSync.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** The sync, claim and report client against a fake server and a fake clock (TASK slice, FARM E2, E3). */
class TaskSyncTest {
    static final String TASK = "t-0123456789ab";

    record Call(String path, JsonObject body) {
    }

    final List<Call> calls = new ArrayList<>();
    /** Answers by path; the default is an empty ok sync. */
    Function<Call, ManagerHttp.Response> server;
    boolean farmerOn = true, localStop, busy;
    TaskSync sync;
    long now = 1_000_000L;

    static ManagerHttp.Response ok(String json) {
        return new ManagerHttp.Response(200, json, null);
    }

    static ManagerHttp.Response code(int c, String json) {
        return new ManagerHttp.Response(c, json, null);
    }

    static String syncReply(String extra) {
        return "{\"ok\":true,\"now\":1000000,\"roles\":[\"farmer\"],\"stop\":{\"global\":false,\"bot\":false,\"rev\":0,\"reason\":null},\"lease\":null,"
            + "\"offer\":null,\"cancel\":[]" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    @BeforeEach
    void setUp() {
        server = c -> ok(syncReply(""));
        sync = new TaskSync((path, json) -> {
            Call c = new Call(path, JsonParser.parseString(json).getAsJsonObject());
            calls.add(c);
            return server.apply(c);
        }, new TaskSync.Host() {
            public boolean farmerEnabled() {
                return farmerOn;
            }

            public JsonObject status() {
                JsonObject o = new JsonObject();
                o.addProperty("inGame", true);
                o.addProperty("dim", "minecraft:overworld");
                o.addProperty("health", 20);
                return o;
            }

            public JsonObject farmer() {
                JsonObject o = new JsonObject();
                o.addProperty("enabled", true);
                o.addProperty("acceptUploads", false);
                o.addProperty("version", "1.20.0");
                return o;
            }

            public JsonArray programs() {
                return new JsonArray();
            }

            public JsonObject draft() {
                return null;
            }

            public String botId() {
                return "farmer-1";
            }

            public String player() {
                return "FarmBot";
            }

            public String server() {
                return "2b2t.org";
            }

            public boolean localStopped() {
                return localStop;
            }

            public boolean busy() {
                return busy;
            }

            public JsonObject helloBody() {
                JsonObject o = new JsonObject();
                o.addProperty("botId", "farmer-1");
                return o;
            }
        });
    }

    List<Event> drain() {
        List<Event> out = new ArrayList<>();
        Event e;
        while ((e = sync.events().poll()) != null) out.add(e);
        return out;
    }

    static String offer(String id, String kind) {
        return "\"offer\":{\"id\":\"" + id + "\",\"kind\":\"" + kind + "\",\"title\":\"Farm session\",\"params\":{\"farm\":\"f-1a2b3c4d\",\"programHash\":\""
            + "a".repeat(64) + "\",\"maxMs\":7200000,\"origin\":\"website\"},\"target\":{\"botId\":\"farmer-1\",\"role\":\"farmer\"}}";
    }

    static String claimReply() {
        return "{\"ok\":true,\"task\":{\"id\":\"" + TASK + "\",\"params\":{\"farm\":\"f-1a2b3c4d\",\"programHash\":\"" + "a".repeat(64)
            + "\",\"maxMs\":7200000,\"origin\":\"website\"}},\"lease\":{\"taskId\":\"" + TASK + "\",\"epoch\":2,\"expiresAt\":1090000},\"now\":1000000}";
    }

    /** Syncs once, a claim included, and starts "running" as the owner would. */
    void claimed() {
        server = c -> c.path().endsWith("/claim") ? ok(claimReply()) : ok(syncReply(offer(TASK, "farm.session")));
        sync.step(now);
        sync.step(now);
        List<Event> ev = drain();
        assertEquals(1, ev.stream().filter(e -> e instanceof TaskSync.Claimed).count());
        server = c -> ok(syncReply(""));
        sync.setRunning(new TaskSync.Running(TASK, 2, "running", "setup", 0, 0));
    }

    // ---- the sync ----------------------------------------------------------------------------------------------------

    @Test
    void theFirstRequestIsASyncWithTheGenericAndTheFarmerFieldsAndNoPosition() {
        sync.step(now);
        assertEquals(1, calls.size());
        assertEquals("/api/v1/tasks/sync", calls.get(0).path());
        JsonObject b = calls.get(0).body();
        assertEquals("farmer-1", b.get("botId").getAsString());
        assertEquals("plugin", b.get("type").getAsString());
        assertEquals("1.0", b.get("contract").getAsString());
        assertEquals("farm", b.getAsJsonArray("caps").get(0).getAsString());
        assertTrue(b.get("running").isJsonNull());
        assertEquals(0, b.get("stopAck").getAsInt());
        assertTrue(b.getAsJsonObject("farmer").get("enabled").getAsBoolean());
        assertTrue(b.getAsJsonObject("farmer").has("farmsRev"));
        assertTrue(b.getAsJsonObject("farmer").has("forgot"));
        assertTrue(b.has("programs"));
        assertNull(ReportGuard.positions(b));
    }

    @Test
    void itSyncsEveryFifteenSecondsIdleAndEveryFiveWhileATaskRuns() {
        sync.step(now);
        sync.step(now + 14_000);
        assertEquals(1, calls.size());
        sync.step(now + 15_000);
        assertEquals(2, calls.size());
        sync.setRunning(new TaskSync.Running(TASK, 2, "running", "loop", 0, 0));
        sync.step(now + 15_000 + 4_000);
        assertEquals(2, calls.size());
        sync.step(now + 15_000 + 5_000);
        assertEquals(3, calls.size());
        sync.requestSoon();
        sync.step(now + 15_000 + 5_001);
        assertEquals(4, calls.size(), "a state change syncs at once");
    }

    @Test
    void aRunningTaskIsShownInTheSyncBodyWithItsEpochAndAClosedPhase() {
        sync.setRunning(new TaskSync.Running(TASK, 2, "running", "loop", 12, 0));
        sync.step(now);
        JsonObject r = calls.get(0).body().getAsJsonObject("running");
        assertEquals(TASK, r.get("taskId").getAsString());
        assertEquals(2, r.get("epoch").getAsInt());
        assertEquals("loop", r.get("phase").getAsString());
        assertEquals(12, r.getAsJsonObject("progress").get("done").getAsInt());
    }

    // ---- offers and claims -------------------------------------------------------------------------------------------

    @Test
    void anOfferIsClaimedAndTheClaimedEventCarriesTheFarmAndHash() {
        claimed();
        assertEquals("/api/v1/tasks/" + TASK + "/claim", calls.get(1).path());
        assertEquals("farmer-1", calls.get(1).body().get("botId").getAsString());
    }

    @Test
    void anOfferIsIgnoredWithoutTheRoleTheLocalSwitchAPresentStopOrAGoodShape() {
        // the role is the server's word and the switch is the bot's: both are needed
        server = c -> ok(syncReply(offer(TASK, "farm.session")).replace("\"roles\":[\"farmer\"]", "\"roles\":[\"loader\"]"));
        sync.step(now);
        assertEquals(1, calls.size());
        farmerOn = false;
        server = c -> ok(syncReply(offer(TASK, "farm.session")));
        sync.step(now + 15_000);
        assertEquals(2, calls.size());
        farmerOn = true;
        localStop = true;
        sync.step(now + 30_000);
        assertEquals(3, calls.size());
        localStop = false;
        busy = true;
        sync.step(now + 45_000);
        assertEquals(4, calls.size());
        busy = false;
        server = c -> ok(syncReply(offer("t-not-an-id", "farm.session")));
        sync.step(now + 60_000);
        server = c -> ok(syncReply(offer(TASK, "order.deliver")));
        sync.step(now + 75_000);
        server = c -> ok(syncReply(offer(TASK, "farm.session")).replace("\"global\":false,\"bot\":false", "\"global\":false,\"bot\":true"));
        sync.step(now + 90_000);
        assertEquals(7, calls.size(), "no claim in any of these cases");
        assertTrue(calls.stream().noneMatch(c -> c.path().endsWith("/claim")));
    }

    @Test
    void aTaskRunningBlocksANewOffer() {
        sync.setRunning(new TaskSync.Running(TASK, 2, "running", "loop", 0, 0));
        server = c -> ok(syncReply(offer("t-aaaaaaaaaaaa", "farm.session")));
        sync.step(now);
        assertTrue(calls.stream().noneMatch(c -> c.path().endsWith("/claim")));
    }

    @Test
    void aRefusedClaimIsNotRetriedAndProducesNoEvent() {
        server = c -> c.path().endsWith("/claim") ? code(409, "{\"ok\":false,\"code\":\"taken\"}") : ok(syncReply(offer(TASK, "farm.session")));
        sync.step(now);
        sync.step(now);
        sync.step(now);
        assertEquals(2, calls.size() , "one sync, one claim; the next step has nothing due");
        assertTrue(drain().stream().noneMatch(e -> e instanceof TaskSync.Claimed));
    }

    @Test
    void aBotWithNoRoleSaysHelloOnceAfterItsFirstSyncAndNeverBefore() {
        server = c -> c.path().endsWith("/bot/hello") ? ok("{\"ok\":true,\"protocol\":1}") : ok(syncReply("").replace("\"roles\":[\"farmer\"]", "\"roles\":[]"));
        sync.step(now);
        assertEquals("/api/v1/tasks/sync", calls.get(0).path(), "the first request is the sync");
        assertEquals("/api/v1/bot/hello", calls.get(1).path());
        assertNull(ReportGuard.positions(calls.get(1).body()));
        assertFalse(calls.get(1).body().has("scopes"), "no settings scope goes with it");
        sync.step(now + 15_000);
        sync.step(now + 30_000);
        assertEquals(1, calls.stream().filter(c -> c.path().endsWith("/bot/hello")).count());
    }

    @Test
    void aBotThatAlreadyHasTheRoleSaysNoHello() {
        claimed();
        assertTrue(calls.stream().noneMatch(c -> c.path().endsWith("/bot/hello")));
    }

    // ---- 401 retry (N8), lease loss, old server, rate ---------------------------------------------------------------------

    @Test
    void a401IsRetriedWithBackoffBeforeItCountsAsAKeyProblem() {
        server = c -> code(401, "");
        long t = now;
        sync.step(t);
        assertEquals(1, calls.size());
        sync.step(t + 1_999);
        assertEquals(1, calls.size());
        t += 2_000;
        sync.step(t); // retry 1 after 2 s
        assertEquals(2, calls.size());
        t += 4_000;
        sync.step(t - 1);
        assertEquals(2, calls.size());
        sync.step(t); // retry 2 after 4 s
        assertEquals(3, calls.size());
        t += 8_000;
        sync.step(t); // retry 3 after 8 s
        assertEquals(4, calls.size());
        t += 15_000;
        sync.step(t); // then every 15 s
        assertEquals(5, calls.size());
        assertFalse(sync.keyProblem());
        // keep going until two minutes have passed since the first 401
        while (t - now < 121_000) {
            t += 15_000;
            sync.step(t);
        }
        assertTrue(sync.keyProblem());
        assertEquals(1, drain().stream().filter(e -> e instanceof TaskSync.KeyProblem).count());
        // the server comes back (a restart): the first good answer clears it
        server = c -> ok(syncReply(""));
        t += 15_000;
        sync.step(t);
        assertFalse(sync.keyProblem());
        assertTrue(sync.everOk());
    }

    @Test
    void aLeaseLostReportIsNeverRetriedAndEndsTheTaskForTheOwner() {
        claimed();
        calls.clear();
        server = c -> code(409, "{\"ok\":false,\"code\":\"lease_lost\"}");
        sync.queueReport(new TaskSync.Report("running", "loop", 3, 0, "loop", 3, "a1", 140, 0, 0, null));
        sync.step(now);
        assertEquals(1, calls.size());
        sync.step(now + 10_000);
        sync.step(now + 20_000);
        assertEquals(1, calls.stream().filter(c -> c.path().endsWith("/report")).count(), "lease_lost is never retried");
        assertEquals(1, drain().stream().filter(e -> e instanceof TaskSync.LeaseLost).count());
    }

    @Test
    void aServerWithoutTasksSyncIsTooOldAndTriedAgainInFiveMinutes() {
        server = c -> code(404, "");
        sync.step(now);
        assertTrue(sync.serverOld());
        assertEquals(1, drain().stream().filter(e -> e instanceof TaskSync.ServerOld).count());
        sync.step(now + 299_000);
        assertEquals(1, calls.size());
        sync.step(now + 300_000);
        assertEquals(2, calls.size());
    }

    @Test
    void slowDownIsHonouredForTwoSeconds() {
        server = c -> code(429, "{\"code\":\"slow_down\"}");
        sync.step(now);
        sync.step(now + 1_000);
        assertEquals(1, calls.size());
        sync.step(now + 2_000);
        assertEquals(2, calls.size());
    }

    // ---- STOP, cancel ------------------------------------------------------------------------------------------------

    @Test
    void aStopFromTheServerIsReportedOnceAckedAndLiftedByTheServer() {
        server = c -> ok(syncReply("").replace("\"global\":false,\"bot\":false,\"rev\":0", "\"global\":false,\"bot\":true,\"rev\":3"));
        sync.step(now);
        List<Event> ev = drain();
        assertEquals(1, ev.size());
        TaskSync.StopChanged s = assertInstanceOf(TaskSync.StopChanged.class, ev.get(0));
        assertTrue(s.stopped());
        assertEquals(3, s.rev());
        assertTrue(sync.stopped());
        sync.step(now + 15_000);
        assertTrue(drain().isEmpty(), "the same STOP is not announced again");
        sync.ackStop(3);
        sync.step(now + 15_001);
        assertEquals(3, calls.get(calls.size() - 1).body().get("stopAck").getAsInt());
        server = c -> ok(syncReply("").replace("\"rev\":0", "\"rev\":4"));
        sync.step(now + 31_000);
        List<Event> off = drain();
        assertFalse(assertInstanceOf(TaskSync.StopChanged.class, off.get(0)).stopped());
        assertFalse(sync.stopped());
    }

    @Test
    void aCancelNamingTheRunningTaskIsAnEventAndOneNamingAnotherIsNot() {
        claimed();
        server = c -> ok(syncReply("").replace("\"cancel\":[]", "\"cancel\":[\"t-ffffffffffff\"]"));
        sync.step(now + 20_000);
        assertTrue(drain().isEmpty());
        server = c -> ok(syncReply("").replace("\"cancel\":[]", "\"cancel\":[\"" + TASK + "\"]"));
        sync.step(now + 40_000);
        assertEquals(new TaskSync.Cancel(TASK), drain().get(0));
    }

    // ---- reports -----------------------------------------------------------------------------------------------------

    @Test
    void reportsCarryAnIncreasingSeqAndOnlyClosedVocabulary() {
        claimed();
        calls.clear();
        sync.queueReport(new TaskSync.Report("running", "setup", 0, 0, "setup", 0, "a0", 0, 0, 0, null));
        sync.step(now);
        sync.queueReport(new TaskSync.Report("running", "loop", 3, 0, "loop", 3, "a1", 140, 1, 0, null));
        sync.step(now);
        List<Call> reports = calls.stream().filter(c -> c.path().endsWith("/report")).toList();
        assertEquals(2, reports.size());
        assertEquals(1, reports.get(0).body().get("seq").getAsInt());
        assertEquals(2, reports.get(1).body().get("seq").getAsInt());
        assertEquals(2, reports.get(1).body().get("epoch").getAsInt());
        JsonObject d = reports.get(1).body().getAsJsonObject("checkpoint").getAsJsonObject("data");
        assertEquals("a1", d.get("anchorId").getAsString());
        assertFalse(d.has("anchor"), "the old alias is gone (draft 2.4)");
        assertTrue(reports.get(1).body().get("result").isJsonNull(), "a result only goes with a terminal state");
        assertTrue(reports.get(1).body().get("error").isJsonNull());
        for (Call r : reports) {
            assertNull(ReportGuard.farmReport(r.body()));
            assertNull(ReportGuard.positions(r.body()));
        }
    }

    @Test
    void theErrorCodeMatchesTheStateAndATerminalReportClearsTheRunningTask() {
        claimed();
        calls.clear();
        sync.queueReport(new TaskSync.Report("paused", "parked", 3, 0, "parked", 3, "a1", 140, 0, 0, "controller_connected"));
        sync.step(now);
        JsonObject p = calls.get(0).body();
        assertEquals("paused", p.get("state").getAsString());
        assertEquals("controller_connected", p.getAsJsonObject("error").get("code").getAsString());
        assertTrue(p.get("result").isJsonNull());
        sync.queueReport(new TaskSync.Report("completed", "stop_steps", 12, 0, "stop_steps", 12, "a0", 0, 2, 7_200_000, "time_up"));
        sync.step(now);
        JsonObject c = calls.get(1).body();
        assertEquals("completed", c.get("state").getAsString());
        assertTrue(c.get("error").isJsonNull(), "a code with completed is a 400 on the server");
        JsonObject res = c.getAsJsonObject("result");
        assertEquals("time_up", res.get("endedBy").getAsString());
        assertEquals(5, res.size());
        assertEquals(0, res.getAsJsonArray("gained").size());
        assertNull(sync.running(), "after the terminal report the task is no longer ours");
    }

    @Test
    void failedAndCancelledReportsCarryTheirOwnGroupOfCodes() {
        for (String[] c : new String[][] {{"failed", "player_near"}, {"cancelled", "ops_stop"}, {"cancelled", "stopped"}, {"failed", "unsafe_logging"}}) {
            setUp();
            claimed();
            calls.clear();
            sync.queueReport(new TaskSync.Report(c[0], "loop", 1, 0, "loop", 1, "a0", 0, 0, 10, c[1]));
            sync.step(now);
            JsonObject b = calls.get(0).body();
            assertEquals(c[1], b.getAsJsonObject("error").get("code").getAsString());
            assertEquals(c[1], b.getAsJsonObject("result").get("endedBy").getAsString());
            assertNull(ReportGuard.farmReport(b), c[1]);
        }
    }

    @Test
    void aReportThatCouldNotBeSentStaysAndGoesAgain() {
        claimed();
        calls.clear();
        server = c -> code(503, "");
        sync.queueReport(new TaskSync.Report("running", "loop", 1, 0, "loop", 1, "a0", 0, 0, 0, null));
        sync.step(now);
        server = c -> ok("{\"ok\":true}");
        sync.step(now + 1_000);
        assertEquals(1, calls.size(), "not before the retry delay");
        sync.step(now + 2_100);
        assertEquals(2, calls.size());
        assertEquals(1, calls.get(1).body().get("seq").getAsInt(), "the same seq: the server dedups");
    }

    // ---- offline, farms, forget, attach ----------------------------------------------------------------------------------

    @Test
    void afterTheGracePeriodWithoutASyncTheOwnerIsToldOnceAndToldAgainWhenItWorksAgain() {
        sync.setGraceMs(60_000);
        sync.step(now);
        server = c -> code(-1, "");
        long t = now + 15_000;
        for (int i = 0; i < 20; i++) {
            sync.step(t);
            t += 5_000;
        }
        assertEquals(1, drain().stream().filter(e -> e instanceof TaskSync.Unreachable).count());
        server = c -> ok(syncReply(""));
        sync.step(t + 10_000);
        assertEquals(1, drain().stream().filter(e -> e instanceof TaskSync.Reachable).count());
    }

    @Test
    void theFarmListForgetAndAttachCommandsBecomeEvents() {
        String farms = "\"farmsRev\":7,\"farms\":[{\"id\":\"f-1a2b3c4d\",\"name\":\"Bone\",\"mode\":\"custom\",\"enabled\":true,\"session\":{\"minMs\":0,\"maxMs\":3600000},"
            + "\"thresholds\":{\"healthStop\":8,\"food\":5,\"totems\":1,\"playerRadius\":20,\"playerSeconds\":15,\"invFullPct\":80}}],\"forget\":[\"f-0a0a0a0a\"],"
            + "\"attach\":{\"farm\":\"f-1a2b3c4d\",\"draftHash\":\"" + "b".repeat(64) + "\"}";
        server = c -> ok(syncReply(farms));
        sync.step(now);
        List<Event> ev = drain();
        TaskSync.Farms f = (TaskSync.Farms) ev.stream().filter(e -> e instanceof TaskSync.Farms).findFirst().orElseThrow();
        assertEquals(7, f.rev());
        assertEquals("Bone", f.farms().get(0).name());
        assertEquals(20, f.farms().get(0).playerRadius());
        assertEquals(3_600_000, f.farms().get(0).maxMs());
        assertEquals(List.of("f-0a0a0a0a"), ((TaskSync.Forget) ev.stream().filter(e -> e instanceof TaskSync.Forget).findFirst().orElseThrow()).ids());
        assertEquals("f-1a2b3c4d", ((TaskSync.Attach) ev.stream().filter(e -> e instanceof TaskSync.Attach).findFirst().orElseThrow()).farm());
        // the applied rev is sent back and the forgotten ids until the server stops listing them
        sync.forgot(List.of("f-0a0a0a0a"));
        server = c -> ok(syncReply("\"forget\":[\"f-0a0a0a0a\"]"));
        sync.step(now + 15_000);
        JsonObject b1 = calls.get(calls.size() - 1).body().getAsJsonObject("farmer");
        assertEquals(7, b1.get("farmsRev").getAsInt());
        assertEquals("f-0a0a0a0a", b1.getAsJsonArray("forgot").get(0).getAsString());
        server = c -> ok(syncReply(""));
        sync.step(now + 30_000);
        sync.step(now + 45_000);
        JsonObject b3 = calls.get(calls.size() - 1).body().getAsJsonObject("farmer");
        assertEquals(0, b3.getAsJsonArray("forgot").size(), "acknowledged by the server");
    }

    @Test
    void aBodyWithAPositionIsNeverSent() {
        JsonObject b = JsonParser.parseString("{\"phase\":\"loop\",\"note\":\"12 64 -30\"}").getAsJsonObject();
        assertEquals("position_like", ReportGuard.positions(b));
        assertEquals("position_field", ReportGuard.positions(JsonParser.parseString("{\"checkpoint\":{\"data\":{\"x\":1}}}")));
        assertEquals("position_field", ReportGuard.positions(JsonParser.parseString("{\"a\":[{\"Position\":[1,2,3]}]}")));
        assertNull(ReportGuard.positions(JsonParser.parseString("{\"anchorId\":\"a1\",\"anchors\":4}")), "a count and an anchor id are not positions");
        assertNotNull(ReportGuard.farmReport(JsonParser.parseString("{\"state\":\"running\",\"phase\":\"12 64 -30\",\"progress\":{\"done\":0,\"total\":0}}").getAsJsonObject()));
        assertEquals("bad_vocab", ReportGuard.farmReport(JsonParser.parseString(
            "{\"state\":\"running\",\"phase\":\"loop\",\"progress\":{\"done\":0,\"total\":0},\"checkpoint\":{\"step\":\"loop\",\"data\":{\"anchor\":\"a1\"}},\"result\":null,\"error\":null}").getAsJsonObject()));
        assertEquals("bad_vocab", ReportGuard.farmReport(JsonParser.parseString(
            "{\"state\":\"completed\",\"phase\":\"loop\",\"progress\":{\"done\":0,\"total\":0},\"checkpoint\":null,\"result\":null,\"error\":{\"code\":\"time_up\"}}").getAsJsonObject()));
        assertEquals("bad_vocab", ReportGuard.farmReport(JsonParser.parseString(
            "{\"state\":\"failed\",\"phase\":\"loop\",\"progress\":{\"done\":0,\"total\":0},\"checkpoint\":null,\"result\":null,\"error\":{\"code\":\"ops_stop\"}}").getAsJsonObject()));
    }

    // ---- review fixes ------------------------------------------------------------------------------------------------

    @Test
    void aRefusedReportDropsTheTaskClearsTheQueueAndStopsSayingRunning() {
        for (int status : new int[] {400, 404, 413}) {
            setUp();
            claimed();
            calls.clear();
            server = c -> code(status, "{\"ok\":false,\"code\":\"bad_field\"}");
            sync.queueReport(new TaskSync.Report("running", "loop", 1, 0, "loop", 1, "a0", 0, 0, 0, null));
            sync.queueReport(new TaskSync.Report("running", "loop", 2, 0, "loop", 2, "a0", 0, 0, 0, null));
            sync.step(now);
            assertNull(sync.running(), "status " + status);
            assertEquals(0, sync.pendingReports());
            assertEquals(1, drain().stream().filter(e -> e instanceof TaskSync.Dropped).count());
            server = c -> ok(syncReply(""));
            sync.step(now + 20_000);
            assertTrue(calls.get(calls.size() - 1).body().get("running").isJsonNull(), "never running for a task we no longer run");
        }
    }

    @Test
    void anotherConflictAndALostLeaseDropTheTaskToo() {
        claimed();
        server = c -> code(409, "{\"ok\":false,\"code\":\"bad_transition\"}");
        sync.queueReport(new TaskSync.Report("running", "loop", 1, 0, "loop", 1, "a0", 0, 0, 0, null));
        sync.step(now);
        assertNull(sync.running());
        assertEquals(1, drain().stream().filter(e -> e instanceof TaskSync.Dropped).count());
        setUp();
        claimed();
        server = c -> code(409, "{\"ok\":false,\"code\":\"lease_lost\"}");
        sync.queueReport(new TaskSync.Report("running", "loop", 1, 0, "loop", 1, "a0", 0, 0, 0, null));
        sync.step(now);
        assertNull(sync.running());
        assertEquals(1, drain().stream().filter(e -> e instanceof TaskSync.LeaseLost).count());
    }

    @Test
    void aBusyServerOnAReportIsRetriedNotDropped() {
        claimed();
        server = c -> code(429, "{\"code\":\"slow_down\"}");
        sync.queueReport(new TaskSync.Report("running", "loop", 1, 0, "loop", 1, "a0", 0, 0, 0, null));
        sync.step(now);
        assertNotNull(sync.running());
        assertEquals(1, sync.pendingReports());
    }

    @Test
    void theStopFlagInTheJournalIsComparedWithTheFirstSync() {
        sync.seedStopped(true); // the journal says stopped, the server says no STOP: announced
        sync.step(now);
        assertEquals(1, drain().stream().filter(e -> e instanceof TaskSync.StopChanged st && !st.stopped()).count());
        String stopOn = "\"global\":true,\"bot\":false,\"rev\":5";
        setUp(); // the journal says not stopped, the server says STOP: announced
        sync.seedStopped(false);
        server = c -> ok(syncReply("").replace("\"global\":false,\"bot\":false,\"rev\":0", stopOn));
        sync.step(now);
        assertEquals(1, drain().stream().filter(e -> e instanceof TaskSync.StopChanged st && st.stopped()).count());
        setUp(); // both say stopped: announced once so the ack goes out
        sync.seedStopped(true);
        server = c -> ok(syncReply("").replace("\"global\":false,\"bot\":false,\"rev\":0", stopOn));
        sync.step(now);
        assertEquals(1, drain().stream().filter(e -> e instanceof TaskSync.StopChanged st && st.stopped()).count());
        setUp(); // both say not stopped: nothing
        sync.seedStopped(false);
        sync.step(now);
        assertEquals(0, drain().stream().filter(e -> e instanceof TaskSync.StopChanged).count());
    }

    @Test
    void aSecondClaimWhileATaskRunsIsNotMadeAndDoesNotResetSeq() {
        claimed();
        // a report goes out: seq 1 of epoch 2
        sync.queueReport(new TaskSync.Report("running", "loop", 1, 0, null, 1, null, 0, 0, 0, null));
        server = c -> ok("{\"ok\":true}");
        sync.step(now + 1_000);
        assertEquals(1, calls.get(calls.size() - 1).body().get("seq").getAsInt());
        // an offer was already pending when the task started running: it is dropped, nothing is claimed, seq goes on
        server = c -> c.path().endsWith("/claim") ? ok(claimReply()) : ok(syncReply(offer("t-aaaaaaaaaaaa", "farm.session")));
        sync.setRunning(null);
        sync.step(now + 20_000); // the sync that sets the pending offer
        sync.setRunning(new TaskSync.Running(TASK, 2, "running", "loop", 1, 0)); // the owner began a session in between
        int before = calls.size();
        sync.step(now + 20_001);
        assertTrue(calls.subList(before, calls.size()).stream().noneMatch(c -> c.path().endsWith("/claim")), "no claim POST");
        assertTrue(drain().stream().noneMatch(e -> e instanceof TaskSync.Claimed));
        server = c -> ok("{\"ok\":true}");
        sync.queueReport(new TaskSync.Report("running", "loop", 2, 0, null, 2, null, 0, 0, 0, null));
        sync.step(now + 21_000);
        assertEquals(2, calls.get(calls.size() - 1).body().get("seq").getAsInt(), "seq was not reset to 1");
    }

    @Test
    void queueReportIfRunningOnlyActsForThatTaskAndEpoch() {
        sync.setRunning(new TaskSync.Running(TASK, 2, "running", "loop", 0, 0));
        TaskSync.Report r = new TaskSync.Report("paused", "parked", 0, 0, "parked", 0, null, 0, 0, 0, "disconnected");
        assertFalse(sync.queueReportIfRunning(TASK, 3, r, new TaskSync.Running(TASK, 3, "paused", "parked", 0, 0)));
        assertFalse(sync.queueReportIfRunning("t-bbbbbbbbbbbb", 2, r, new TaskSync.Running("t-bbbbbbbbbbbb", 2, "paused", "parked", 0, 0)));
        assertEquals(0, sync.pendingReports());
        assertTrue(sync.queueReportIfRunning(TASK, 2, r, new TaskSync.Running(TASK, 2, "paused", "parked", 0, 0)));
        assertEquals(1, sync.pendingReports());
        sync.setRunning(null);
        assertFalse(sync.queueReportIfRunning(TASK, 2, r, new TaskSync.Running(TASK, 2, "paused", "parked", 0, 0)));
    }
}
