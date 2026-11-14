package com.porter.qwertestash.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.farm.FarmCodes;
import com.porter.qwertestash.manager.ManagerHttp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/**
 * The sync, claim and report client of TASK v1.0 (slice) for the farmer. A state machine over one blocking {@link Http}: the owner
 * calls {@link #step(long)} from one worker thread (never the bot tick, never a netty thread) and drains {@link #events()} on the
 * bot tick. Time is passed in, so the machine is tested with a fake clock and a fake server.
 *
 * <ul>
 *   <li>Every 5 s while a task runs, 15 s when idle, at once after {@link #requestSoon()}.</li>
 *   <li>A 401 is retried with backoff 2, 4, 8 s, then every 15 s, for up to 2 minutes before it counts as a key problem (N8).</li>
 *   <li>A 409 {@code lease_lost} is never retried; the task is dropped and the owner is told once.</li>
 *   <li>Offers are claimed only with the farmer role from the server, the local switch on, no task running and no STOP.</li>
 *   <li>Nothing here carries a position: every body goes through {@link ReportGuard} before it is sent.</li>
 * </ul>
 */
public final class TaskSync {
    public static final long RUNNING_MS = 5_000, IDLE_MS = 15_000;
    private static final Pattern TASK_ID = Pattern.compile("^t-[0-9a-f]{12}$");
    private static final long[] AUTH_BACKOFF = {2_000, 4_000, 8_000};
    private static final long AUTH_WINDOW_MS = 120_000, AUTH_TAIL_MS = 15_000;

    public interface Http {
        /** One blocking signed POST; never throws. {@code code} is -1 when the server could not be reached, -2 for a bad signature. */
        ManagerHttp.Response post(String path, String json);
    }

    /** What the sync says about the farmer; supplied by the plugin, read on the worker thread. Must be cheap and thread-safe. */
    public interface Host {
        boolean farmerEnabled();

        JsonObject status();

        JsonObject farmer();

        JsonArray programs();

        JsonObject draft();

        String botId();

        /** The player name or null; any other value is sent as null. */
        String player();

        String server();

        /** A local STOP is on: no offer is claimed. */
        boolean localStopped();

        /** A session is open or parked here (also a local one): no offer is claimed. */
        boolean busy();

        /** The minimal {@code bot/hello} body ({botId, player, plugin, proxy, caps}; no scopes, no settings): the server only gives a role to a bot it knows. */
        JsonObject helloBody();
    }

    /** The task the bot is running, as the sync and the reports say it. */
    public record Running(String taskId, int epoch, String state, String phase, int done, int total) {
    }

    /** A report the owner asks for. {@code code} is the end or park code (null while running). */
    public record Report(String state, String phase, int done, int total, String checkpointStep, int loop, String anchor, int tick, int recoveries,
                         long runtimeMs, String code) {
    }

    /** A farm as the server lists it. */
    public record Farm(String id, String name, String mode, boolean enabled, long minMs, long maxMs, int healthStop, int food, int totems,
                       int playerRadius, int playerSeconds, int invFullPct) {
    }

    public sealed interface Event permits Claimed, Cancel, Farms, Forget, Attach, LeaseLost, Dropped, KeyProblem, Reachable, Unreachable, ServerOld, StopChanged {
    }

    public record Claimed(String taskId, int epoch, String farmId, String programHash, long maxMs, String origin) implements Event {
    }

    public record Cancel(String taskId) implements Event {
    }

    public record Farms(List<Farm> farms, int rev) implements Event {
    }

    public record Forget(List<String> ids) implements Event {
    }

    public record Attach(String farm, String draftHash) implements Event {
    }

    public record LeaseLost(String taskId) implements Event {
    }

    /** The task was dropped here without a lease loss: a report was refused (4xx) or failed the guard. Nothing more is reported for it. */
    public record Dropped(String taskId) implements Event {
    }

    public record KeyProblem() implements Event {
    }

    public record Reachable() implements Event {
    }

    public record Unreachable() implements Event {
    }

    public record ServerOld() implements Event {
    }

    /** The STOP flags changed: {@code stopped} is global or per bot. */
    public record StopChanged(boolean stopped, int rev) implements Event {
    }

    private final Http http;
    private final Host host;
    private final ConcurrentLinkedQueue<Event> events = new ConcurrentLinkedQueue<>();

    // state (guarded by this)
    private volatile Running running;
    private int seq;
    private int seqEpoch = -1;
    private final ArrayDeque<Report> reports = new ArrayDeque<>();
    private Set<String> roles = Set.of();
    private boolean stopGlobal, stopBot;
    private int stopRev;
    private int stopAck;
    private int farmsRev;
    private final Set<String> forgot = new LinkedHashSet<>();
    private long nextSyncAt;
    private long lastOkAt = -1;
    private long authFailSince = -1;
    private int authFails;
    private boolean keyProblem;
    private boolean serverOld;
    private boolean unreachableSent;
    private boolean everOk;
    private String pendingOffer;
    private JsonObject offer;
    private boolean syncSoon;
    private long leaseDeadline = -1;
    private boolean claimInFlight;
    private boolean stoppedSeen;
    private long reportRetryAt;
    private int helloTries;
    private long helloAt;
    private boolean helloDone;
    private long lastSyncAt;
    private long unreachableGraceMs = 600_000;

    public TaskSync(Http http, Host host) {
        this.http = http;
        this.host = host;
    }

    // ---- owner API --------------------------------------------------------------------------------------------------

    public ConcurrentLinkedQueue<Event> events() {
        return events;
    }

    public synchronized void setGraceMs(long ms) {
        unreachableGraceMs = Math.max(30_000, ms);
    }

    public synchronized void setRunning(Running r) {
        this.running = r;
        if (r == null) leaseDeadline = -1;
        else if (lastSyncAt > 0) nextSyncAt = Math.min(nextSyncAt, lastSyncAt + RUNNING_MS); // a running task syncs every 5 s, not every 15
    }

    public Running running() {
        return running;
    }

    /**
     * The bot no longer runs this task (a report was dropped, the lease was lost): it is forgotten at once, its queued reports are cleared
     * and no sync ever says {@code running} for it again. Returns true when it was the running task.
     */
    private synchronized boolean dropTask(String taskId, boolean announce) {
        Running r = running;
        if (r == null || !r.taskId().equals(taskId)) return false;
        running = null;
        reports.clear();
        leaseDeadline = -1;
        if (announce) events.add(new Dropped(taskId));
        return true;
    }

    /** Reports not yet delivered. */
    public synchronized int pendingReports() {
        return reports.size();
    }

    /** What the journal says about a STOP at start: a first sync that disagrees with it is announced (review item 13). */
    public synchronized void seedStopped(boolean stopped) {
        stoppedSeen = stopped;
    }

    public synchronized void requestSoon() {
        syncSoon = true;
    }

    public synchronized void queueReport(Report r) {
        if (reports.size() >= 20) {
            for (var it = reports.iterator(); it.hasNext(); ) {
                String st = it.next().state();
                if (st.equals("running") || st.equals("paused")) {
                    it.remove();
                    break;
                }
            }
        }
        reports.addLast(r);
        syncSoon = true;
    }

    /**
     * Queues {@code r} and shows {@code next} as the running state, but only while {@code taskId} at {@code epoch} is still the running task
     * (one atomic step, for a caller on another thread that must not overwrite a later session's state). True when it did.
     */
    public synchronized boolean queueReportIfRunning(String taskId, int epoch, Report r, Running next) {
        Running cur = running;
        if (cur == null || !cur.taskId().equals(taskId) || cur.epoch() != epoch) return false;
        queueReport(r);
        running = next;
        return true;
    }

    public synchronized void setFarms(int rev) {
        this.farmsRev = rev;
    }

    public synchronized void forgot(List<String> ids) {
        forgot.addAll(ids);
        syncSoon = true;
    }

    public synchronized boolean stopped() {
        return stopGlobal || stopBot;
    }

    public synchronized int stopRev() {
        return stopRev;
    }

    public synchronized void ackStop(int rev) {
        stopAck = Math.max(stopAck, rev);
        syncSoon = true;
    }

    public synchronized Set<String> roles() {
        return roles;
    }

    public synchronized boolean keyProblem() {
        return keyProblem;
    }

    public synchronized boolean serverOld() {
        return serverOld;
    }

    public synchronized boolean everOk() {
        return everOk;
    }

    /** Milliseconds since the last successful sync, or -1 when there has been none. */
    public synchronized long sinceOk(long now) {
        return lastOkAt < 0 ? -1 : now - lastOkAt;
    }

    // ---- the step ---------------------------------------------------------------------------------------------------

    /** Does at most one exchange if one is due. Call every second or so from the worker thread. Never throws. */
    public void step(long now) {
        // one thread at a time: a worker that is still in an exchange when the owner's shutdown loop calls this (awaitTermination timed out)
        // makes that call a no-op instead of a second concurrent exchange
        if (!stepping.compareAndSet(false, true)) return;
        try {
            stepOnce(now);
        } finally {
            stepping.set(false);
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean stepping = new java.util.concurrent.atomic.AtomicBoolean();

    private void stepOnce(long now) {
        try {
            Report rep;
            synchronized (this) {
                rep = reports.peekFirst();
                if (rep != null && running == null) {
                    reports.pollFirst(); // a report for nothing: dropped
                    rep = null;
                }
            }
            if (rep != null && now >= reportRetryAt) {
                sendReport(now, rep);
                return;
            }
            String claim;
            synchronized (this) {
                claim = pendingOffer;
            }
            if (claim != null) {
                claim(now, claim);
                return;
            }
            boolean due;
            synchronized (this) {
                due = syncSoon || now >= nextSyncAt;
            }
            if (due) sync(now);
        } catch (RuntimeException e) {
            // a state machine step never takes the worker down
        }
    }

    private long interval() {
        return running != null ? RUNNING_MS : IDLE_MS;
    }

    private static JsonObject parse(ManagerHttp.Response r) {
        try {
            JsonElement e = JsonParser.parseString(r.body());
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String codeOf(ManagerHttp.Response r) {
        JsonObject o = parse(r);
        return o != null && o.has("code") && o.get("code").isJsonPrimitive() ? o.get("code").getAsString() : "";
    }

    private JsonObject syncBody() {
        JsonObject b = new JsonObject();
        b.addProperty("botId", host.botId());
        b.addProperty("type", "plugin");
        String player = host.player();
        if (player != null && player.matches("[A-Za-z0-9_]{1,16}")) b.addProperty("player", player);
        else b.add("player", com.google.gson.JsonNull.INSTANCE);
        String server = host.server();
        b.addProperty("server", server == null ? "" : server.length() > 64 ? server.substring(0, 64) : server);
        b.addProperty("contract", "1.0");
        JsonArray caps = new JsonArray();
        caps.add("farm");
        b.add("caps", caps);
        b.add("status", host.status());
        Running r = running;
        if (r == null) {
            b.add("running", com.google.gson.JsonNull.INSTANCE);
        } else {
            JsonObject o = new JsonObject();
            o.addProperty("taskId", r.taskId());
            o.addProperty("epoch", r.epoch());
            o.addProperty("state", r.state());
            o.addProperty("phase", r.phase());
            JsonObject p = new JsonObject();
            p.addProperty("done", r.done());
            p.addProperty("total", r.total());
            o.add("progress", p);
            o.addProperty("seq", Math.max(1, seqEpoch == r.epoch() ? seq : 1));
            b.add("running", o);
        }
        b.addProperty("stopAck", stopAck);
        JsonObject farmer = host.farmer();
        farmer.addProperty("farmsRev", farmsRev);
        JsonArray fg = new JsonArray();
        for (String id : forgot) fg.add(id);
        farmer.add("forgot", fg);
        b.add("farmer", farmer);
        b.add("programs", host.programs());
        JsonObject draft = host.draft();
        b.add("draft", draft == null ? com.google.gson.JsonNull.INSTANCE : draft);
        return b;
    }

    private synchronized void markReachable(long now) {
        lastOkAt = now;
        everOk = true;
        authFailSince = -1;
        authFails = 0;
        keyProblem = false;
        if (unreachableSent) {
            unreachableSent = false;
            events.add(new Reachable());
        }
    }

    /** Failure bookkeeping for a request that did not get a good answer; returns the delay before the next try. */
    private synchronized void markFailed(long now, int code) {
        if (code == 401) {
            if (authFailSince < 0) authFailSince = now;
            long delay = authFails < AUTH_BACKOFF.length ? AUTH_BACKOFF[authFails] : AUTH_TAIL_MS;
            authFails++;
            nextSyncAt = now + delay;
            if (!keyProblem && now - authFailSince >= AUTH_WINDOW_MS) {
                keyProblem = true;
                events.add(new KeyProblem());
            }
        } else {
            nextSyncAt = now + Math.min(interval(), 5_000);
        }
        if (lastOkAt >= 0 && !unreachableSent && now - lastOkAt >= unreachableGraceMs) {
            unreachableSent = true;
            events.add(new Unreachable());
        }
        syncSoon = false;
    }

    private void sync(long now) {
        JsonObject body = syncBody();
        if (ReportGuard.positions(body) != null) {
            synchronized (this) {
                nextSyncAt = now + interval(); // a body that fails the guard is never sent; the farmer is told nothing about the content
            }
            return;
        }
        ManagerHttp.Response r = http.post("/api/v1/tasks/sync", body.toString());
        int code = r.code();
        if (code == 404) {
            synchronized (this) {
                if (!serverOld) {
                    serverOld = true;
                    events.add(new ServerOld());
                }
                nextSyncAt = now + 300_000;
                syncSoon = false;
            }
            return;
        }
        if (code == 429) {
            synchronized (this) {
                nextSyncAt = now + 2_000;
                syncSoon = false;
            }
            return;
        }
        JsonObject o = code == 200 ? parse(r) : null;
        if (o == null || !o.has("ok") || !o.get("ok").getAsBoolean()) {
            markFailed(now, code);
            return;
        }
        serverOld = false;
        markReachable(now);
        apply(now, o);
        maybeHello(now);
    }

    /**
     * A farmer runs no dashboard link, so the server never hears a {@code bot/hello} from it and would never list it for a role. After the first sync
     * that gives no role at all, one minimal hello goes out (at most 3 tries a minute apart). It is sent after the sync, never before it, and it
     * carries no setting, no scope and no position.
     */
    private void maybeHello(long now) {
        synchronized (this) {
            if (helloDone || !roles.isEmpty() || helloTries >= 3 || now < helloAt) return;
            helloTries++;
            helloAt = now + 60_000;
        }
        JsonObject b = host.helloBody();
        if (b == null || ReportGuard.positions(b) != null) return;
        ManagerHttp.Response r = http.post("/api/v1/bot/hello", b.toString());
        if (r.code() == 200) {
            synchronized (this) {
                helloDone = true;
                syncSoon = true;
            }
        }
    }

    private synchronized void apply(long now, JsonObject o) {
        syncSoon = false;
        lastSyncAt = now;
        nextSyncAt = now + interval();
        long serverNow = o.has("now") ? o.get("now").getAsLong() : 0;
        Set<String> rs = new LinkedHashSet<>();
        if (o.has("roles") && o.get("roles").isJsonArray()) for (JsonElement e : o.getAsJsonArray("roles")) if (e.isJsonPrimitive()) rs.add(e.getAsString());
        roles = rs;
        boolean was = stopGlobal || stopBot;
        if (o.has("stop") && o.get("stop").isJsonObject()) {
            JsonObject s = o.getAsJsonObject("stop");
            stopGlobal = s.has("global") && s.get("global").getAsBoolean();
            stopBot = s.has("bot") && s.get("bot").getAsBoolean();
            stopRev = s.has("rev") ? s.get("rev").getAsInt() : stopRev;
        }
        boolean is = stopGlobal || stopBot;
        if (is != was || (is && !stoppedSeen) || (!is && stoppedSeen)) events.add(new StopChanged(is, stopRev));
        stoppedSeen = is;
        // lease
        Running r = running;
        if (o.has("lease") && o.get("lease").isJsonObject() && serverNow > 0) {
            JsonObject l = o.getAsJsonObject("lease");
            long exp = l.get("expiresAt").getAsLong();
            leaseDeadline = now + (exp - serverNow);
        }
        if (o.has("cancel") && o.get("cancel").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("cancel")) {
                if (e.isJsonPrimitive() && r != null && e.getAsString().equals(r.taskId())) events.add(new Cancel(r.taskId()));
            }
        }
        if (o.has("farms") && o.get("farms").isJsonArray()) {
            List<Farm> list = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("farms")) {
                Farm f = farm(e);
                if (f != null) list.add(f);
            }
            int rev = o.has("farmsRev") ? o.get("farmsRev").getAsInt() : farmsRev;
            farmsRev = rev;
            events.add(new Farms(list, rev));
        } else if (o.has("farmsRev") && o.get("farmsRev").isJsonPrimitive()) {
            // a rev with no list: nothing changed for this bot
            farmsRev = Math.max(farmsRev, 0);
        }
        // forget: the ids to delete; ids we reported as forgotten and the server no longer lists are done
        List<String> toForget = new ArrayList<>();
        if (o.has("forget") && o.get("forget").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("forget")) if (e.isJsonPrimitive()) toForget.add(e.getAsString());
        }
        forgot.removeIf(id -> !toForget.contains(id));
        if (!toForget.isEmpty()) events.add(new Forget(toForget));
        if (o.has("attach") && o.get("attach").isJsonObject()) {
            JsonObject a = o.getAsJsonObject("attach");
            if (a.has("farm") && a.has("draftHash")) events.add(new Attach(a.get("farm").getAsString(), a.get("draftHash").getAsString()));
        }
        // offer
        if (o.has("offer") && o.get("offer").isJsonObject() && r == null && !claimInFlight && !(stopGlobal || stopBot)
            && host.farmerEnabled() && !host.localStopped() && !host.busy() && roles.contains("farmer")) {
            JsonObject of = o.getAsJsonObject("offer");
            String id = of.has("id") ? of.get("id").getAsString() : "";
            String kind = of.has("kind") ? of.get("kind").getAsString() : "";
            if (TASK_ID.matcher(id).matches() && kind.equals("farm.session")) {
                offer = of;
                pendingOffer = id;
            }
        }
    }

    private static Farm farm(JsonElement e) {
        try {
            JsonObject o = e.getAsJsonObject();
            JsonObject s = o.has("session") && o.get("session").isJsonObject() ? o.getAsJsonObject("session") : new JsonObject();
            JsonObject t = o.has("thresholds") && o.get("thresholds").isJsonObject() ? o.getAsJsonObject("thresholds") : new JsonObject();
            return new Farm(o.get("id").getAsString(), o.has("name") ? o.get("name").getAsString() : "", o.get("mode").getAsString(),
                !o.has("enabled") || o.get("enabled").getAsBoolean(), s.has("minMs") ? s.get("minMs").getAsLong() : 0,
                s.has("maxMs") ? s.get("maxMs").getAsLong() : 7_200_000, i(t, "healthStop", 10), i(t, "food", 6), i(t, "totems", 1),
                i(t, "playerRadius", 16), i(t, "playerSeconds", 10), i(t, "invFullPct", 90));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static int i(JsonObject o, String k, int d) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : d;
    }

    private void claim(long now, String id) {
        synchronized (this) {
            if (running != null) {
                // a task is already running (it was set between the offer and this step): nothing is claimed, and seq and seqEpoch are untouched
                pendingOffer = null;
                offer = null;
                return;
            }
            claimInFlight = true;
        }
        JsonObject offerNow;
        synchronized (this) {
            offerNow = offer;
        }
        try {
            JsonObject b = new JsonObject();
            b.addProperty("botId", host.botId());
            ManagerHttp.Response r = http.post("/api/v1/tasks/" + id + "/claim", b.toString());
            JsonObject o = r.code() == 200 ? parse(r) : null;
            synchronized (this) {
                pendingOffer = null;
                offer = null;
            }
            if (o == null) {
                if (r.code() == 401) markFailed(now, 401);
                return; // taken, not_target, stopped, not_claimable, no_task and failures: the next sync offers again if it still can
            }
            JsonObject lease = o.getAsJsonObject("lease");
            JsonObject task = o.has("task") && o.get("task").isJsonObject() ? o.getAsJsonObject("task") : offerNow;
            JsonObject params = task != null && task.has("params") && task.get("params").isJsonObject() ? task.getAsJsonObject("params") : new JsonObject();
            String farm = str(params, "farm");
            String hash = str(params, "programHash");
            long maxMs = params.has("maxMs") ? params.get("maxMs").getAsLong() : 7_200_000;
            String origin = str(params, "origin");
            int epoch = lease.get("epoch").getAsInt();
            synchronized (this) {
                seq = 0;
                seqEpoch = epoch;
                if (lease.has("expiresAt") && o.has("now")) leaseDeadline = now + (lease.get("expiresAt").getAsLong() - o.get("now").getAsLong());
            }
            events.add(new Claimed(id, epoch, farm, hash, maxMs, origin));
        } finally {
            synchronized (this) {
                claimInFlight = false;
            }
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private void sendReport(long now, Report rep) {
        Running run = running;
        if (run == null) return;
        JsonObject b = new JsonObject();
        b.addProperty("botId", host.botId());
        b.addProperty("epoch", run.epoch());
        int s;
        synchronized (this) {
            if (seqEpoch != run.epoch()) {
                seqEpoch = run.epoch();
                seq = 0;
            }
            s = seq + 1;
        }
        b.addProperty("seq", s);
        b.addProperty("state", rep.state());
        b.addProperty("phase", rep.phase());
        JsonObject p = new JsonObject();
        p.addProperty("done", Math.max(0, rep.done()));
        p.addProperty("total", Math.max(0, rep.total()));
        b.add("progress", p);
        if (rep.checkpointStep() != null) {
            JsonObject cp = new JsonObject();
            cp.addProperty("step", rep.checkpointStep());
            JsonObject d = new JsonObject();
            d.addProperty("loop", Math.max(0, rep.loop()));
            if (rep.anchor() != null) d.addProperty("anchorId", rep.anchor());
            d.addProperty("tick", Math.max(0, rep.tick()));
            d.addProperty("recoveries", Math.max(0, rep.recoveries()));
            cp.add("data", d);
            b.add("checkpoint", cp);
        } else {
            b.add("checkpoint", com.google.gson.JsonNull.INSTANCE);
        }
        boolean terminal = rep.state().equals("completed") || rep.state().equals("failed") || rep.state().equals("cancelled");
        if (terminal) {
            JsonObject res = new JsonObject();
            res.addProperty("loops", Math.max(0, rep.loop()));
            res.addProperty("recoveries", Math.max(0, rep.recoveries()));
            res.addProperty("runtimeMs", Math.max(0, Math.min(Integer.MAX_VALUE, rep.runtimeMs())));
            res.addProperty("endedBy", rep.code());
            res.add("gained", new JsonArray());
            b.add("result", res);
        } else {
            b.add("result", com.google.gson.JsonNull.INSTANCE);
        }
        if (rep.code() != null && !rep.state().equals("completed") && !rep.state().equals("running")) {
            JsonObject er = new JsonObject();
            er.addProperty("code", rep.code());
            b.add("error", er);
        } else {
            b.add("error", com.google.gson.JsonNull.INSTANCE);
        }
        if (ReportGuard.positions(b) != null || ReportGuard.farmReport(b) != null) {
            dropTask(run.taskId(), true); // never sent, and a task whose report cannot be made is not reported as running either
            return;
        }
        ManagerHttp.Response r = http.post("/api/v1/tasks/" + run.taskId() + "/report", b.toString());
        int code = r.code();
        synchronized (this) {
            if (code == 200) {
                reports.pollFirst();
                seq = s;
                if (rep.state().equals("completed") || rep.state().equals("failed") || rep.state().equals("cancelled")) running = null;
                markReachable(now);
                nextSyncAt = Math.min(nextSyncAt, now + interval());
                return;
            }
            if (code == 409) {
                String c = codeOf(r);
                if (c.equals("lease_lost")) {
                    dropTask(run.taskId(), false);
                    events.add(new LeaseLost(run.taskId())); // never retried
                } else {
                    dropTask(run.taskId(), true); // bad_transition and the like: not retried, and the task is no longer reported
                }
                return;
            }
            if (code == 401) {
                markFailed(now, 401);
                reportRetryAt = nextSyncAt;
                return;
            }
            if (code >= 400 && code < 500 && code != 429 && code != 408) {
                dropTask(run.taskId(), true); // 400 and friends: the body is never resent, the task is dropped
                return;
            }
            markFailed(now, code); // unreachable or 5xx: the report stays and goes again
            reportRetryAt = now + 2_000;
        }
    }

    /** Local ms at which the lease runs out as the last reply told it, or -1. */
    public synchronized long leaseDeadline() {
        return leaseDeadline;
    }

    /** True when {@code code} is a park or end code this build reports. */
    public static boolean reportable(String code) {
        return code == null || FarmCodes.known(code);
    }
}
