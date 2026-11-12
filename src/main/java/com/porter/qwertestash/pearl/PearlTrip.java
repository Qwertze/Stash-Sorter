package com.porter.qwertestash.pearl;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The traveller's side of one pearl trip (PEARL-CONTRACT v1.0 rev 6, "Who does what"): a pure state machine in the style of
 * {@code OrderFlow}. Events go in (the offer, the local precheck, a heartbeat reply, a teleport, the pull deadline, the
 * re-arm result ...), the new step and state come out together with the {@link Action}s the driver has to carry out. It touches
 * no world, no clock except {@link Host#now()}, and no file, so the shared vectors ({@code pearl-vectors.json}, {@code machine})
 * run on it as they are. Bot tick thread only.
 * <p>
 * Safety rules kept here (the driver adds the world checks):
 * <ul>
 *   <li>one whisper, one retry, never a third; the loader name and the pearl id are taken only from the server's reply and only
 *       when they have the shape of a player name and a pearl id;</li>
 *   <li>a cancel after the whisper is ignored (a pull can't be undone);</li>
 *   <li>the away guard goes on <em>before</em> the whisper and goes off only when T is known to be where it started (a failed
 *       pull that never happened), or home again after a {@code back} trip; any doubt means {@code hold} (guard stays on);</li>
 *   <li>an unknown re-arm word is a failed re-arm ({@code ABORTED}), an unknown local code is reported as a code the server knows.</li>
 * </ul>
 * A {@code back} trip starts with the guard already on (T is away from its stash); if its pull fails T is still away, so the
 * guard is never turned off by a failure of a {@code back} trip.
 */
public final class PearlTrip {
    /** What the machine needs from outside. */
    public interface Host {
        /** The bot's clock, milliseconds. */
        long now();
    }

    /** A thing for the driver to do: {@code kind} and an optional argument, written {@code kind:arg} (the vectors' spelling). */
    public record Action(String kind, @Nullable String arg) {
        @Override
        public String toString() {
            return arg == null ? kind : kind + ":" + arg;
        }

        public boolean is(String kind) {
            return this.kind.equals(kind);
        }
    }

    public static final String STEP_PRECHECK = "precheck", STEP_ARM_HOME = "arm_home", STEP_READY = "ready", STEP_LOAD_SENT = "load_sent",
        STEP_AWAITING = "awaiting_pull", STEP_ARRIVED = "arrived", STEP_PICKUP = "pickup", STEP_REARM = "rearm", STEP_DONE = "done";

    public static final String RUNNING = "running", PAUSED = "paused", FAILED = "failed", COMPLETED = "completed", CANCELLED = "cancelled";

    /** The server pauses that use up tries (the plugin's own precheck uses the same list). */
    public static final Set<String> COUNTED = Set.of("player_near", "loader_player_near", "loader_busy", "owner_offline", "loader_stale",
        "chamber_locked", "loader_locked", "low_health", "low_food", "just_joined");
    /** The waits that never use up tries. */
    public static final Set<String> FREE = Set.of("busy_order", "busy_repair", "sorter_busy");
    /** The codes a traveller may report as its own error (the server answers 400 to any other). */
    public static final Set<String> BOT_ERRORS = Set.of("not_ingame", "in_queue", "low_pearls", "player_near", "loader_player_near", "loader_busy",
        "owner_offline", "loader_stale", "low_health", "low_food", "just_joined", "pull_timeout", "unexpected_location", "stopped", "order_arrived",
        "arrival_unconfirmed");
    public static final Set<String> REARM_RESULTS = Set.of("SUCCESS", "PARTIAL", "NO_PEARLS", "NO_RIM", "NO_CHAMBER", "OCCUPIED", "ABORTED");
    /** Failures after the whisper that are settled by the local still-here check. */
    private static final Set<String> LATE = Set.of("loader_no_answer", "load_aborted", "pull_timeout", "arrival_unconfirmed", "go_timeout");

    public static final int MAX_TRIES = 3;
    public static final int MAX_WHISPERS = 2;
    /** The pull deadline when the server has not said: 150 s after the whisper (PearlPlus aborts after 120 s). */
    public static final long DEFAULT_PULL_MS = 150_000L;

    private final Host host;
    private String action = "go";
    private @Nullable String id, station, dim, pearlId, loader;
    private String step = STEP_PRECHECK, state = RUNNING;
    private int whispers, tries;
    private boolean away, watch, hold, armHomeUsed;
    private @Nullable String code, warning, pauseCode, rearmResult, teleportDim;
    private long loadSentAt, deadlineAt, teleportAt;

    public PearlTrip(Host host) {
        this.host = host;
    }

    // What the driver reads

    public String action() { return action; }
    public @Nullable String id() { return id; }
    public @Nullable String station() { return station; }
    public @Nullable String dim() { return dim; }
    public @Nullable String pearlId() { return pearlId; }
    public @Nullable String loader() { return loader; }
    public String step() { return step; }
    public String state() { return state; }
    public int whispers() { return whispers; }
    public int tries() { return tries; }
    public boolean away() { return away; }
    public boolean watch() { return watch; }
    public boolean hold() { return hold; }
    public @Nullable String code() { return code; }
    public @Nullable String warning() { return warning; }
    public @Nullable String pauseCode() { return pauseCode; }
    public @Nullable String rearmResult() { return rearmResult; }
    public long loadSentAt() { return loadSentAt; }
    public long deadlineAt() { return deadlineAt; }
    public long teleportAt() { return teleportAt; }
    public @Nullable String teleportDim() { return teleportDim; }

    /** The trip is not over: running or paused. */
    public boolean open() {
        return state.equals(RUNNING) || state.equals(PAUSED);
    }

    /** The whisper was sent (the pull can't be undone from here on). */
    public boolean whispered() {
        return whispers > 0;
    }

    private static List<Action> none() {
        return new ArrayList<>();
    }

    private static Action a(String kind) {
        return new Action(kind, null);
    }

    private static Action a(String kind, String arg) {
        return new Action(kind, arg);
    }

    /** A code the server accepts in a traveller's report. */
    public static String safeError(@Nullable String code) {
        return code != null && BOT_ERRORS.contains(code) ? code : "stopped";
    }

    // Events

    /** The heartbeat reply carries this trip for the bot. */
    public List<Action> offer(String action, @Nullable String id, @Nullable String station, @Nullable String dim) {
        this.action = action.equals("back") ? "back" : "go";
        this.id = id;
        this.station = station;
        this.dim = dim;
        step = STEP_PRECHECK;
        state = RUNNING;
        whispers = tries = 0;
        // A way back starts away from the stash: the guard is on already
        away = this.action.equals("back");
        watch = hold = armHomeUsed = false;
        code = warning = pauseCode = rearmResult = teleportDim = null;
        pearlId = loader = null;
        loadSentAt = deadlineAt = teleportAt = 0;
        List<Action> out = none();
        out.add(a("runPrecheck"));
        return out;
    }

    public List<Action> offer(String action) {
        return offer(action, null, null, null);
    }

    /** The local precheck finished (also: the next one after {@link #retryTick()}). */
    public List<Action> precheck(boolean ok, @Nullable String why) {
        List<Action> out = none();
        if (!step.equals(STEP_PRECHECK) || !state.equals(RUNNING)) return out;
        if (ok) {
            step = STEP_READY;
            out.add(a("report", "ready"));
            return out;
        }
        String c = why == null ? "" : why;
        if (FREE.contains(c)) {
            state = PAUSED;
            pauseCode = c;
            out.add(a("report", "pause:" + c));
        }
        else if (COUNTED.contains(c)) {
            state = PAUSED;
            pauseCode = c;
            tries++;
            out.add(a("report", "pause:" + c));
        }
        else {
            state = FAILED;
            code = safeError(c);
            out.add(a("report", "error:" + code));
        }
        return out;
    }

    /** 30 s passed. A counted pause is retried at most three times; a free wait without limit (the server's 10 minutes end it). */
    public List<Action> retryTick() {
        List<Action> out = none();
        if (!state.equals(PAUSED) || !step.equals(STEP_PRECHECK) || pauseCode == null) return out;
        if (COUNTED.contains(pauseCode) && tries >= MAX_TRIES) return out;
        state = RUNNING;
        pauseCode = null;
        out.add(a("runPrecheck"));
        return out;
    }

    /**
     * A later heartbeat reply. {@code stillHere}: T is at the position and in the dimension where it whispered and has sent nothing out
     * (only asked when the reply reports a failure after the whisper).
     */
    public List<Action> reply(PearlReply r, boolean stillHere) {
        List<Action> out = none();
        if (!open()) return out;
        // The server's own verdicts first
        if (r.state().equals(FAILED)) {
            failFromServer(r, stillHere, out);
            return out;
        }
        if (r.state().equals(CANCELLED)) {
            if (!whispered()) state = CANCELLED;
            code = r.code();
            return out;
        }
        if (r.state().equals(PAUSED)) {
            if (state.equals(RUNNING) && !whispered()) {
                state = PAUSED;
                pauseCode = r.code();
                code = r.code();
            }
            return out;
        }
        if (state.equals(PAUSED) && r.state().equals(RUNNING) && !step.equals(STEP_PRECHECK)) {
            state = RUNNING;
            pauseCode = null;
            code = null;
        }
        if (!state.equals(RUNNING)) return out;

        if (r.cancel() && !whispered() && (step.equals(STEP_PRECHECK) || step.equals(STEP_ARM_HOME) || step.equals(STEP_READY))) {
            state = CANCELLED;
            out.add(a("report", "cancelled"));
            return out;
        }
        if (r.armHome() && step.equals(STEP_READY) && !armHomeUsed) {
            armHomeUsed = true;
            step = STEP_ARM_HOME;
            out.add(a("armHome"));
            return out;
        }
        if (r.go() && step.equals(STEP_READY) && whispers == 0) {
            String p = r.pearlId(), l = r.loader();
            if (p == null || l == null) {
                // The contract's go always names both; without them nothing is whispered
                state = FAILED;
                code = "stopped";
                out.add(a("report", "error:stopped"));
                return out;
            }
            pearlId = p;
            loader = l;
            step = STEP_AWAITING;
            whispers = 1;
            loadSentAt = host.now();
            deadlineAt = r.pullDeadlineAt() > 0 ? clampDeadline(r.pullDeadlineAt()) : loadSentAt + DEFAULT_PULL_MS;
            if (!away) {
                away = true;
                out.add(a("awayOn"));
            }
            out.add(a("whisper", "load " + p));
            out.add(a("report", "load_sent"));
            return out;
        }
        if (r.retry() && step.equals(STEP_AWAITING) && whispers > 0 && whispers < MAX_WHISPERS && pearlId != null) {
            whispers++;
            loadSentAt = host.now();
            deadlineAt = loadSentAt + DEFAULT_PULL_MS;
            out.add(a("whisper", "load " + pearlId));
            out.add(a("report", "load_sent"));
            return out;
        }
        if (r.rearm() && step.equals(STEP_ARRIVED)) {
            step = STEP_PICKUP;
            out.add(a("pickup"));
            return out;
        }
        long wanted = r.pullDeadlineAt() > 0 ? clampDeadline(r.pullDeadlineAt()) : 0;
        if (wanted > 0 && step.equals(STEP_AWAITING) && wanted != deadlineAt) {
            deadlineAt = wanted;
            out.add(a("setDeadline"));
        }
        return out;
    }

    /** A deadline from the server is taken only within the next ten minutes (a wrong or hostile value can neither end the wait at once nor hold the guard for ever). */
    private long clampDeadline(long v) {
        long now = host.now();
        return Math.max(now, Math.min(v, now + 10 * 60_000L));
    }

    private void failFromServer(PearlReply r, boolean stillHere, List<Action> out) {
        String c = r.code() == null ? "stopped" : r.code();
        if (!whispered()) {
            // Nothing was sent: nothing moved. The guard is not on (go) or stays on (back, which started away)
            // A hold on a trip that never whispered is only a failed trip: the bot never left, so no guard is turned on for it
            state = FAILED;
            code = c;
            return;
        }
        if (r.hold() || c.equals("unexpected_location")) {
            state = FAILED;
            code = "unexpected_location";
            hold = true;
            if (!away) {
                away = true;
                out.add(a("awayOn"));
            }
            out.add(a("hold"));
            return;
        }
        if (LATE.contains(c)) {
            lateFailure(c, stillHere && teleportAt == 0, out, false);
            return;
        }
        // A failure after the whisper that this machine doesn't know: T may have moved, so it stays away
        state = FAILED;
        code = c;
        hold = true;
        if (!away) {
            away = true;
            out.add(a("awayOn"));
        }
        out.add(a("hold"));
    }

    /**
     * No pull seen, for whatever reason (the deadline, the loader gave up, the server restarted): if T is still where it whispered it
     * is as if nothing happened (guard off for a {@code go}, watch for a late pull); else the pull already happened (hold).
     * @param report the failure is reported by T (a local deadline); a server verdict is not echoed back
     */
    private void lateFailure(String why, boolean still, List<Action> out, boolean report) {
        state = FAILED;
        if (still) {
            code = why;
            watch = true;
            if (action.equals("go") && away) {
                away = false;
                out.add(a("awayOff"));
            }
            out.add(a("watch"));
            if (report) out.add(a("report", "error:" + safeError(why)));
        }
        else {
            code = "unexpected_location";
            hold = true;
            if (!away) {
                away = true;
                out.add(a("awayOn"));
            }
            out.add(a("hold"));
            out.add(a("report", "error:unexpected_location"));
        }
    }

    public List<Action> armHomeDone() {
        List<Action> out = none();
        if (step.equals(STEP_ARM_HOME) && state.equals(RUNNING)) {
            step = STEP_READY;
            out.add(a("report", "ready"));
        }
        return out;
    }

    /** T saw a teleport: a jump of at least 8 blocks in one tick, or any dimension change. */
    public List<Action> teleport(boolean jump, boolean dimChange, @Nullable String toDim) {
        List<Action> out = none();
        if (!jump && !dimChange) return out;
        if (step.equals(STEP_AWAITING) && state.equals(RUNNING)) {
            teleportAt = host.now();
            teleportDim = toDim;
            step = STEP_ARRIVED;
            out.add(a("report", "arrived"));
        }
        else if (state.equals(FAILED) && watch) {
            // A late pull: T is somewhere it did not mean to be
            teleportAt = host.now();
            teleportDim = toDim;
            watch = false;
            hold = true;
            code = "unexpected_location";
            if (!away) {
                away = true;
                out.add(a("awayOn"));
            }
            out.add(a("hold"));
            out.add(a("report", "error:unexpected_location"));
        }
        return out;
    }

    /** The pull deadline passed with no teleport seen. */
    public List<Action> pullDeadline(boolean stillHere) {
        List<Action> out = none();
        if (!step.equals(STEP_AWAITING) || !state.equals(RUNNING)) return out;
        lateFailure("pull_timeout", stillHere && teleportAt == 0, out, true);
        return out;
    }

    /** Five minutes passed after a failure: the watch for a late pull is over. */
    public List<Action> watchEnd() {
        watch = false;
        return none();
    }

    public List<Action> pickupDone() {
        List<Action> out = none();
        if (step.equals(STEP_PICKUP) && state.equals(RUNNING)) {
            step = STEP_REARM;
            out.add(a("rearm"));
        }
        return out;
    }

    /** The re-arm finished with a PearlDrop result word (anything unknown is a failed re-arm). */
    public List<Action> rearmResult(@Nullable String result) {
        List<Action> out = none();
        if (!step.equals(STEP_REARM) || !state.equals(RUNNING)) return out;
        String word = result == null ? "ABORTED" : result.trim().toUpperCase(java.util.Locale.ROOT);
        if (!REARM_RESULTS.contains(word)) word = "ABORTED";
        rearmResult = word;
        step = STEP_DONE;
        state = COMPLETED;
        boolean ok = word.equals("SUCCESS") || word.equals("PARTIAL");
        if (action.equals("back")) {
            away = false;
            out.add(a("awayOff"));
            out.add(a("requestRescan"));
        }
        out.add(a("report", "done"));
        if (!ok) {
            warning = "rearm_failed";
            out.add(a("alert", "rearm_failed"));
        }
        return out;
    }

    /** T lost its connection: nothing can happen. */
    public List<Action> disconnect() {
        return none();
    }

    /** T is back; {@code teleported}: its journal saw a teleport (the time it saw it is {@code at}). */
    public List<Action> reconnect(boolean teleported, long at, @Nullable String toDim) {
        List<Action> out = none();
        if (teleported && step.equals(STEP_AWAITING) && state.equals(RUNNING)) {
            teleportAt = at > 0 ? at : host.now();
            teleportDim = toDim;
            step = STEP_ARRIVED;
            out.add(a("report", "arrived"));
        }
        return out;
    }

    public List<Action> reconnect(boolean teleported) {
        return reconnect(teleported, 0, null);
    }

    /** The driver refuses to go on before the whisper (not in the world, in the queue ...): the trip fails with this code. */
    public List<Action> abort(String why) {
        List<Action> out = none();
        if (!open() || whispered()) return out;
        state = FAILED;
        code = safeError(why);
        out.add(a("report", "error:" + code));
        return out;
    }

    /**
     * The whisper could not be written ahead to the journal, so it was not sent: the trip is back before the whisper and fails. Only right after the whisper
     * action (the guard goes off again for a {@code go}).
     */
    public List<Action> revokeWhisper() {
        List<Action> out = none();
        // A retry that was not sent: back to one whisper, nothing else changes (no load_sent is reported for it)
        if (whispers == 2 && step.equals(STEP_AWAITING) && state.equals(RUNNING)) {
            whispers = 1;
            return out;
        }
        if (whispers != 1 || !step.equals(STEP_AWAITING)) return out;
        whispers = 0;
        step = STEP_READY;
        loadSentAt = deadlineAt = 0;
        state = FAILED;
        code = "stopped";
        if (action.equals("go") && away) {
            away = false;
            out.add(a("awayOff"));
        }
        out.add(a("report", "error:stopped"));
        return out;
    }

    /** The bot died or was moved in a way the pull rules can't explain after the whisper: nothing is known, so it is held (guard on) for the owner. */
    public List<Action> holdUnknown() {
        List<Action> out = none();
        if (!open() || !whispered()) return out;
        state = FAILED;
        code = "unexpected_location";
        hold = true;
        watch = false;
        if (!away) {
            away = true;
            out.add(a("awayOn"));
        }
        out.add(a("hold"));
        out.add(a("report", "error:unexpected_location"));
        return out;
    }

    /** The owner's own `stash pearl stop`: only before the whisper. */
    public List<Action> stopLocal() {
        List<Action> out = none();
        if (!open() || whispered()) return out;
        state = CANCELLED;
        out.add(a("report", "cancelled"));
        return out;
    }

    // Restoring from the journal

    /** Puts the machine back where the journal says (after a restart or a reconnect). */
    public void restore(String action, @Nullable String id, @Nullable String station, @Nullable String dim, @Nullable String pearlId, @Nullable String loader,
                        String step, String state, int whispers, int tries, boolean away, boolean watch, boolean hold, boolean armHomeUsed,
                        @Nullable String code, @Nullable String warning, @Nullable String pauseCode, @Nullable String rearmResult,
                        long loadSentAt, long deadlineAt, long teleportAt, @Nullable String teleportDim) {
        this.action = action.equals("back") ? "back" : "go";
        this.id = id;
        this.station = station;
        this.dim = dim;
        this.pearlId = pearlId;
        this.loader = loader;
        this.step = step;
        this.state = state;
        this.whispers = whispers;
        this.tries = tries;
        this.away = away;
        this.watch = watch;
        this.hold = hold;
        this.armHomeUsed = armHomeUsed;
        this.code = code;
        this.warning = warning;
        this.pauseCode = pauseCode;
        this.rearmResult = rearmResult;
        this.loadSentAt = loadSentAt;
        this.deadlineAt = deadlineAt;
        this.teleportAt = teleportAt;
        this.teleportDim = teleportDim;
    }

    /** The owner cleared the hold (the server's away clear). */
    public void clearHold() {
        hold = false;
    }

    boolean armHomeUsed() {
        return armHomeUsed;
    }
}
