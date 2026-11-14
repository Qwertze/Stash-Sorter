package com.porter.qwertestash.farm;

import com.porter.qwerte.core.farm.AnchorMath;
import com.porter.qwerte.core.farm.ClickCompiler;
import com.porter.qwerte.core.farm.FarmRecording;
import com.porter.qwerte.core.farm.FarmRecording.Anchor;
import com.porter.qwerte.core.farm.FarmRecording.Vec;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

/**
 * The state machine that plays a program (FARM Part D): verify, setup steps, approach the start anchor, then the loop, with drift
 * correction against the anchors (D5), pause and resume by recovery (D3b) and a stop. Pure: the proxy is seen through {@link Port},
 * time is passed in, nothing is logged. Called on the bot tick thread only.
 *
 * <p>Timing (D3, draft 2.4): movement keys, jumps, sneaks, sprints and the look advance one track tick per bot tick, so the bot's physics
 * steps match the recording whatever the tick rate (every one-tick tap is applied; none is dropped when ticks lag). Wall clock only spaces
 * clicks: a click waits for the held slot to be the chosen item and keeps the recorded gap (50 ms per track tick) from the click before it.
 *  * The origin is absolute and exists only in this object's memory; no method returns or logs it.
 */
public final class FarmPlayer {
    public static final long TICK_MS = 50;
    public static final int NEAR_LIMIT = 64;
    public static final long CHUNK_WAIT_MS = 60_000;
    public static final long PATH_LIMIT_MS = 30_000;
    public static final long FINE_LIMIT_MS = 8_000;
    public static final int MAX_FAILED_RECOVERIES = 3;
    public static final int MAX_RECOVERIES_PER_HOUR = 6;
    public static final int MISS_LIMIT = 3;
    private static final int SAMPLE_STEP = 20;

    public enum Phase { SETUP, LOOP, RECOVER, STOP_STEPS, PARKED, ENDED }

    /** What the input of one bot tick holds. */
    public record Cmd(int keys, boolean look, float yaw, float pitch, boolean left, boolean right, boolean offHand, Aim aim) {
        public static final Cmd NONE = new Cmd(0, false, 0, 0, false, false, false, Aim.NONE);
    }

    /** Where a click lands: nothing, a block (absolute, in memory only) or an entity type. */
    public record Aim(int kind, int x, int y, int z, String entity) {
        public static final Aim NONE = new Aim(0, 0, 0, 0, null);

        @Override
        public String toString() {
            return "Aim[" + kind + "]"; // never the position
        }
    }

    public interface Path {
        enum S { RUNNING, DONE, FAILED }

        S state();

        void cancel();
    }

    public interface Port {
        /** Feet position, absolute. */
        double[] feet();

        String dimension();

        /** Full block id ("minecraft:hopper"), or null when unknown. */
        String blockAt(int x, int y, int z);

        boolean chunkLoaded(int blockX, int blockZ);

        /** Submits the input of this bot tick. */
        void submit(Cmd cmd);

        /** Selects an item in the hotbar; false when the bot does not have it. */
        boolean select(String itemId);

        /** True when the held slot now holds that item (a selection is only a request until the bot has switched). */
        boolean holding(String itemId);

        /** True when another pathfinder process is in control. */
        boolean pathfinderBusy();

        /** Starts a path to a block; null when it could not be started. */
        Path startPath(int x, int y, int z);
    }

    private final FarmProgram prog;
    private final int ox, oy, oz;
    private final Port port;
    private final boolean afkStill;
    private final int afkAnchor;
    private final boolean repeat;
    private final int maxLoops;

    private Phase phase = Phase.SETUP;
    private String ended;
    private String parked;
    private String parkedWhy;

    // setup
    private enum Setup { VERIFY, STEPS, APPROACH, RESUME_VERIFY }
    private Setup setup = Setup.VERIFY;
    private long startedAt;
    private int stepIdx;
    private long stepUntil = -1;
    private int stepTicks;
    private List<Map<String, Object>> stepList;

    // loop: movement advances one track tick per bot tick (review item 5), so the bot's physics steps match the recording; wall clock
    // only spaces clicks (the use cadence, the attack cooldown)
    private int curTick = -1;
    private int loops;
    private int lastAnchor;
    private int misses;
    private boolean inputsPaused;
    private long lastPassiveCheck;
    private long lastClickAt = Long.MIN_VALUE / 4;
    private int lastClickTick = Integer.MIN_VALUE / 2;
    private String wantItem;
    private int holdTicks;
    private static final int HOLD_LIMIT_TICKS = 60;

    // recovery
    private enum Why { INITIAL, DRIFT, RESUME }
    private Why recWhy = Why.INITIAL;
    private int recAnchor;
    private int recStage; // 0 path, 1 fine, 2 look
    private long recStarted;
    private Path path;
    private int lookTicks;
    private int failures;
    private int recoveries;
    private final ArrayDeque<Long> recoveryTimes = new ArrayDeque<>();
    private Phase resumePhase = Phase.LOOP;
    private boolean resumeNearest;
    private long verifyBadSince = -1;
    public static final long VERIFY_GRACE_MS = 10_000;

    public FarmPlayer(FarmProgram prog, int originX, int originY, int originZ, Port port) {
        this.prog = prog;
        this.ox = originX;
        this.oy = originY;
        this.oz = originZ;
        this.port = port;
        var h = prog.rec.header();
        this.afkStill = h.mode().equals("afk_still");
        int idx = 0;
        if (afkStill) {
            Object a = h.template().params().get("anchor");
            if (a instanceof String s) {
                for (int i = 0; i < h.anchors().size(); i++) if (h.anchors().get(i).id().equals(s)) idx = i;
            }
        }
        this.afkAnchor = idx;
        Object rp = h.template().params().get("repeat");
        this.repeat = h.loop().repeat() && (!(rp instanceof Boolean b) || b);
        Object ml = h.template().params().get("maxLoops");
        this.maxLoops = ml instanceof Number n ? n.intValue() : 0;
    }

    // ---- queries (no position) -------------------------------------------------------------------------------------

    public Phase phase() {
        return phase;
    }

    /** The code the session ended with, or null. */
    public String endedCode() {
        return ended;
    }

    public String parkedCode() {
        return parked;
    }

    public int loops() {
        return loops;
    }

    public int recoveries() {
        return recoveries;
    }

    public int failedRecoveries() {
        return failures;
    }

    public int trackTick() {
        return Math.max(0, curTick);
    }

    public String anchorId() {
        return prog.rec.header().anchors().get(lastAnchor).id();
    }

    public boolean inputsPaused() {
        return inputsPaused;
    }

    // ---- geometry (in memory) ------------------------------------------------------------------------------------

    private Vec standAbs(int anchorIdx) {
        Anchor a = prog.rec.header().anchors().get(anchorIdx);
        return AnchorMath.absolute(ox, oy, oz, AnchorMath.standPoint(a));
    }

    private double distTo(Vec target) {
        double[] f = port.feet();
        return AnchorMath.distance(new Vec(f[0], f[1], f[2]), target);
    }

    private double horizontalTo(Vec t) {
        double[] f = port.feet();
        return Math.hypot(f[0] - t.x(), f[2] - t.z());
    }

    private double tolOf(int anchorIdx) {
        return prog.rec.header().anchors().get(anchorIdx).tol();
    }

    private boolean fingerprintOk(int anchorIdx) {
        Anchor a = prog.rec.header().anchors().get(anchorIdx);
        if (a.fp() == null) return true;
        double[] f = port.feet();
        int bx = (int) Math.floor(f[0]);
        int by = (int) Math.floor(f[1]);
        int bz = (int) Math.floor(f[2]);
        if (a.fp().below() != null && !a.fp().below().equals(port.blockAt(bx, by - 1, bz))) return false;
        return a.fp().feet() == null || a.fp().feet().equals(port.blockAt(bx, by, bz));
    }

    // ---- control ----------------------------------------------------------------------------------------------------

    /** Begins the session: nothing is sent to the bot until the first {@link #tick}. */
    public void start(long now) {
        startedAt = now;
        phase = Phase.SETUP;
        setup = Setup.VERIFY;
        String m3 = prog.m3Only();
        if (m3 != null || !(afkStill || prog.rec.header().mode().equals("custom"))) {
            end("mode_not_ready");
        }
    }

    /** Ends the session with a code (idempotent). */
    public void end(String code) {
        if (phase == Phase.ENDED) return;
        phase = Phase.ENDED;
        ended = code;
        cancelPath();
    }

    /** The owner's stop or any park: inputs stop at once, the path is cancelled, nothing is trusted afterwards. */
    public void park(String code, long now) {
        if (phase == Phase.ENDED || phase == Phase.PARKED) return;
        // A park during a recovery keeps the phase the recovery was started for (beginRecovery recorded it) and the stepAfterRecovery flag
        if (phase != Phase.RECOVER) resumePhase = phase == Phase.SETUP || phase == Phase.STOP_STEPS ? phase : Phase.LOOP;
        phase = Phase.PARKED;
        parked = code;
        cancelPath();
        inputsPaused = false;
        holdTicks = 0;
    }

    /**
     * After a pause the world kept running: nothing about the step counter is trusted (D3b). The player verifies dimension and distance
     * again and walks back to the last anchor passed (the nearest one after a disconnect), then restarts the track at that anchor's tick.
     */
    public void resume(long now, boolean nearest) {
        if (phase != Phase.PARKED) return;
        parked = null;
        if (resumePhase == Phase.SETUP) {
            phase = Phase.SETUP;
            setup = Setup.VERIFY;
            startedAt = now;
            stepAfterRecovery = false; // setup starts over (verify, steps); a stale walk-to-anchor flag must not send it back mid-way
            return;
        }
        // A verify state with a timeout, as setup has (review item 8): a blank dimension or a stale position right after a reconnect is
        // not an end. Only a wrong dimension, a far position or unloaded chunks that persist do end it, then the recovery to the anchor.
        resumeNearest = nearest;
        phase = Phase.SETUP;
        setup = Setup.RESUME_VERIFY;
        startedAt = now;
        verifyBadSince = -1;
    }

    private String dimExpected = "";

    /** The dimension the session expects (from the location store). */
    public void expectDimension(String dim) {
        this.dimExpected = dim;
    }

    private int nearestAnchor() {
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < prog.rec.header().anchors().size(); i++) {
            double d = distTo(standAbs(i));
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    /** Someone is near (D7: within 8 blocks): inputs pause at once, in the loop and during a recovery; the track stands still. */
    public void pauseInputs(long now) {
        if (!inputsPaused && (phase == Phase.LOOP || phase == Phase.RECOVER)) {
            inputsPaused = true;
        }
    }

    public void resumeInputs(long now) {
        if (inputsPaused) {
            inputsPaused = false;
            if (phase == Phase.RECOVER) recStarted = now; // the recovery's limits count from here, not from before the pause
        }
    }

    /** A click's result, fed from the bot's answer: 3 misses in a row start a recovery. */
    public void clickResult(boolean hit, long now) {
        if (phase != Phase.LOOP) return;
        if (hit) {
            misses = 0;
        } else if (++misses >= MISS_LIMIT) {
            misses = 0;
            beginRecovery(Why.DRIFT, lastAnchor, now);
        }
    }

    // ---- the tick --------------------------------------------------------------------------------------------------

    public void tick(long now) {
        switch (phase) {
            case SETUP -> setupTick(now);
            case LOOP -> loopTick(now);
            case RECOVER -> recoverTick(now);
            case STOP_STEPS -> stepsTick(now);
            default -> {
            }
        }
    }

    private void setupTick(long now) {
        switch (setup) {
            case VERIFY -> {
                if (!port.dimension().equals(dimExpected)) {
                    end("wrong_dim");
                    return;
                }
                if (distTo(new Vec(ox, oy, oz)) > NEAR_LIMIT) {
                    end("not_at_farm");
                    return;
                }
                boolean loaded = true;
                var anchors = prog.rec.header().anchors();
                for (int i = 0; i < anchors.size() && loaded; i++) {
                    Vec s = standAbs(i);
                    loaded = port.chunkLoaded((int) Math.floor(s.x()), (int) Math.floor(s.z()));
                }
                if (!loaded) {
                    if (now - startedAt > CHUNK_WAIT_MS) end("chunk_not_loaded");
                    return;
                }
                setup = Setup.STEPS;
                stepList = prog.rec.header().steps().start();
                stepIdx = 0;
                stepUntil = -1;
                stepTicks = 0;
            }
            case RESUME_VERIFY -> {
                String d = port.dimension();
                String bad = null;
                if (d == null || d.isEmpty()) bad = null; // blank right after a reconnect: wait, it is not a verdict
                else if (!d.equals(dimExpected)) bad = "wrong_dim";
                else if (distTo(new Vec(ox, oy, oz)) > NEAR_LIMIT) bad = "not_at_farm";
                if (bad != null) {
                    if (verifyBadSince < 0) verifyBadSince = now;
                    if (now - verifyBadSince >= VERIFY_GRACE_MS) end(bad);
                    return;
                }
                verifyBadSince = -1;
                boolean loaded = d != null && !d.isEmpty() && port.chunkLoaded(ox, oz);
                var anchors = prog.rec.header().anchors();
                for (int i = 0; i < anchors.size() && loaded; i++) {
                    Vec st = standAbs(i);
                    loaded = port.chunkLoaded((int) Math.floor(st.x()), (int) Math.floor(st.z()));
                }
                if (!loaded) {
                    if (now - startedAt > CHUNK_WAIT_MS) end("chunk_not_loaded");
                    return;
                }
                int target = afkStill ? afkAnchor : (resumeNearest ? nearestAnchor() : lastAnchor);
                stepAfterRecovery = resumePhase == Phase.STOP_STEPS;
                beginRecovery(Why.RESUME, target, now);
            }
            case STEPS -> {
                String r = runSteps(now);
                if (r == null) return;
                if (r.equals("done")) {
                    setup = Setup.APPROACH;
                    beginRecovery(Why.INITIAL, afkStill ? afkAnchor : 0, now);
                } else {
                    end(r);
                }
            }
            default -> {
            }
        }
    }

    /** Runs the current step list; null while busy, "done" when finished, else an end code. */
    private String runSteps(long now) {
        while (stepIdx < stepList.size()) {
            Map<String, Object> s = stepList.get(stepIdx);
            String op = String.valueOf(s.get("op"));
            switch (op) {
                case "select" -> {
                    if (!port.select(String.valueOf(s.get("item")))) return "item_missing";
                    stepIdx++;
                }
                case "wait" -> {
                    if (stepUntil < 0) stepUntil = now + ((Number) s.get("ticks")).longValue() * TICK_MS;
                    if (now < stepUntil) {
                        port.submit(Cmd.NONE);
                        return null;
                    }
                    stepUntil = -1;
                    stepIdx++;
                }
                case "look" -> {
                    float y = ((Number) s.get("yaw")).floatValue();
                    float p = ((Number) s.get("pitch")).floatValue();
                    port.submit(new Cmd(0, true, y, p, false, false, false, Aim.NONE));
                    if (++stepTicks >= 2) {
                        stepTicks = 0;
                        stepIdx++;
                    }
                    return null;
                }
                case "walk-to-anchor" -> {
                    String id = String.valueOf(s.get("anchor"));
                    int idx = 0;
                    var anchors = prog.rec.header().anchors();
                    for (int i = 0; i < anchors.size(); i++) if (anchors.get(i).id().equals(id)) idx = i;
                    resumePhase = phase;
                    stepIdx++;
                    beginRecovery(Why.INITIAL, idx, now);
                    stepAfterRecovery = true;
                    return null;
                }
                case "use-block" -> {
                    int[] rel = relOf(s.get("rel"));
                    int bx = ox + rel[0], by = oy + rel[1], bz = oz + rel[2];
                    double[] f = port.feet();
                    double dx = bx + 0.5 - f[0], dy = by + 0.5 - (f[1] + 1.62), dz = bz + 0.5 - f[2];
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
                    port.submit(new Cmd(0, true, yaw, pitch, false, true, false, new Aim(1, bx, by, bz, null)));
                    stepIdx++;
                    return null;
                }
                default -> {
                    return "mode_not_ready";
                }
            }
        }
        return "done";
    }

    private boolean stepAfterRecovery;

    private static int[] relOf(Object o) {
        if (o instanceof List<?> l && l.size() == 3) {
            return new int[] {((Number) l.get(0)).intValue(), ((Number) l.get(1)).intValue(), ((Number) l.get(2)).intValue()};
        }
        return new int[] {0, 0, 0};
    }

    // ---- recovery (D5) ----------------------------------------------------------------------------------------------

    private void beginRecovery(Why why, int anchorIdx, long now) {
        cancelPath();
        // what this recovery returns to; a RESUME recovery keeps the phase the park came from
        if (why != Why.RESUME) resumePhase = phase == Phase.STOP_STEPS ? Phase.STOP_STEPS : phase == Phase.SETUP ? Phase.SETUP : Phase.LOOP;
        phase = Phase.RECOVER;
        recWhy = why;
        recAnchor = anchorIdx;
        recStage = 0;
        recStarted = now;
        lookTicks = 0;
        misses = 0;
        inputsPaused = false;
    }

    private void cancelPath() {
        if (path != null) {
            path.cancel();
            path = null;
        }
    }

    private void recoverTick(long now) {
        if (inputsPaused) {
            // someone is within 8 blocks: the walk stops too (the path is cancelled and starts again when they are gone)
            cancelPath();
            recStage = 0;
            port.submit(Cmd.NONE);
            return;
        }
        Vec stand = standAbs(recAnchor);
        double tol = tolOf(recAnchor);
        if (recStage == 0) {
            // already on the spot: no path
            if (horizontalTo(stand) <= 1.0 && Math.abs(port.feet()[1] - stand.y()) <= 1.5) {
                recStage = 1;
                recStarted = now;
                return;
            }
            if (path == null) {
                if (port.pathfinderBusy()) {
                    park("pathfinder_busy", now);
                    return;
                }
                path = port.startPath((int) Math.floor(stand.x()), (int) Math.floor(stand.y()), (int) Math.floor(stand.z()));
                if (path == null) {
                    failRecovery(now);
                    return;
                }
                recStarted = now;
            }
            Path.S st = path.state();
            if (st == Path.S.DONE) {
                path = null;
                recStage = 1;
                recStarted = now;
            } else if (st == Path.S.FAILED || now - recStarted > PATH_LIMIT_MS) {
                failRecovery(now);
            }
            return;
        }
        if (recStage == 1) {
            // fine walk: face the stand point, walk, sneak the last stretch so the bot stops inside the tolerance
            double[] f = port.feet();
            double dx = stand.x() - f[0], dz = stand.z() - f[2];
            double d = Math.hypot(dx, dz);
            if (d <= tol * 0.9 || (d <= tol && Math.abs(f[1] - stand.y()) <= 1.0)) {
                recStage = 2;
                lookTicks = 0;
                return;
            }
            if (now - recStarted > FINE_LIMIT_MS) {
                failRecovery(now);
                return;
            }
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            boolean sneak = d < 0.6;
            int keys = FarmRecording.KEY_FORWARD | (sneak ? FarmRecording.KEY_SNEAK : 0);
            port.submit(new Cmd(keys, true, yaw, 0, false, false, false, Aim.NONE));
            return;
        }
        // stage 2: set the anchor's look, check the fingerprint
        Anchor a = prog.rec.header().anchors().get(recAnchor);
        port.submit(new Cmd(0, true, (float) a.yaw(), (float) a.pitch(), false, false, false, Aim.NONE));
        if (++lookTicks < 2) return;
        if (!fingerprintOk(recAnchor)) {
            end("farm_changed");
            return;
        }
        recoverySucceeded(now);
    }

    private void failRecovery(long now) {
        cancelPath();
        failures++;
        if (failures >= MAX_FAILED_RECOVERIES) {
            end("drifted");
            return;
        }
        recStage = 0;
        recStarted = now;
    }

    private void recoverySucceeded(long now) {
        cancelPath();
        lastAnchor = recAnchor;
        if (recWhy == Why.DRIFT) {
            recoveries++;
            recoveryTimes.addLast(now);
            while (!recoveryTimes.isEmpty() && now - recoveryTimes.peekFirst() > 3_600_000L) recoveryTimes.pollFirst();
            if (recoveryTimes.size() > MAX_RECOVERIES_PER_HOUR) {
                end("drifted");
                return;
            }
        }
        if (stepAfterRecovery) {
            stepAfterRecovery = false;
            phase = resumePhase == Phase.STOP_STEPS ? Phase.STOP_STEPS : Phase.SETUP;
            return;
        }
        phase = Phase.LOOP;
        Anchor a = prog.rec.header().anchors().get(recAnchor);
        holdTicks = 0;
        lastClickTick = Integer.MIN_VALUE / 2;
        lastClickAt = Long.MIN_VALUE / 4; // the first click after a recovery is not spaced from one before it
        if (afkStill) {
            curTick = -1;
            lastPassiveCheck = now;
        } else {
            // restart the track at that anchor's tick
            curTick = a.tick();
        }
        if (recWhy == Why.INITIAL && !afkStill) {
            curTick = a.tick() - 1; // the first pass starts at a0's own tick (0), which is checked like any other anchor
        }
    }

    // ---- the loop ---------------------------------------------------------------------------------------------------

    private void loopTick(long now) {
        if (afkStill) {
            port.submit(new Cmd(0, true, (float) prog.rec.header().anchors().get(afkAnchor).yaw(),
                (float) prog.rec.header().anchors().get(afkAnchor).pitch(), false, false, false, Aim.NONE));
            if (now - lastPassiveCheck >= 1000) {
                lastPassiveCheck = now;
                if (AnchorMath.earlyDesync(distTo(standAbs(afkAnchor)), tolOf(afkAnchor))) beginRecovery(Why.DRIFT, afkAnchor, now);
            }
            return;
        }
        if (inputsPaused) {
            port.submit(Cmd.NONE);
            return;
        }
        var h = prog.rec.header();
        int segEnd = h.loop().end();
        int next = curTick + 1;
        if (next >= segEnd) {
            // the pass is over: the loop wraps or the session finishes (stop steps)
            loops++;
            boolean done = !repeat || (maxLoops > 0 && loops >= maxLoops);
            if (done) {
                stepList = h.steps().stop();
                stepIdx = 0;
                stepUntil = -1;
                stepTicks = 0;
                phase = Phase.STOP_STEPS;
                return;
            }
            curTick = h.loop().start() - 1;
            next = h.loop().start(); // the first tick of the next pass is played on this very bot tick
            lastClickTick = Integer.MIN_VALUE / 2;
            lastClickAt = Long.MIN_VALUE / 4; // the first click of a pass is not spaced from the last one of the pass before
        }
        int t = next;
        // anchors, the 20-tick samples and the hotbar of this tick (all idempotent: a held tick repeats them)
        for (int i = 0; i < h.anchors().size(); i++) {
            Anchor a = h.anchors().get(i);
            if (a.tick() == t) {
                if (AnchorMath.within(distTo(standAbs(i)), a.tol())) {
                    lastAnchor = i;
                } else {
                    curTick = t;
                    beginRecovery(Why.DRIFT, i, now);
                    return;
                }
            }
        }
        if (prog.sample[t] != null && t % SAMPLE_STEP == 0) {
            float[] sm = prog.sample[t];
            Vec expect = new Vec(ox + sm[0], oy + sm[1], oz + sm[2]);
            if (AnchorMath.earlyDesync(distTo(expect), tolOf(lastAnchor))) {
                curTick = t;
                beginRecovery(Why.DRIFT, lastAnchor, now);
                return;
            }
        }
        String item = prog.hotbarItem[t];
        if (item != null) {
            if (item.isEmpty()) {
                wantItem = null;
            } else {
                wantItem = item;
                if (!port.select(item)) {
                    end("item_missing");
                    return;
                }
            }
        }
        ClickCompiler.Click c = prog.clickAt[t];
        if (c != null) {
            // a click waits for the held slot to be the chosen one, and keeps the recorded wall-clock spacing from the click before it
            boolean itemOk = wantItem == null || port.holding(wantItem);
            boolean spaced = now - lastClickAt >= (long) Math.max(0, t - lastClickTick) * TICK_MS;
            if (!itemOk || !spaced) {
                if (++holdTicks > HOLD_LIMIT_TICKS) {
                    if (!itemOk) {
                        end("item_missing");
                        return;
                    }
                } else {
                    port.submit(Cmd.NONE);
                    return;
                }
            }
        }
        holdTicks = 0;
        curTick = t;
        int keys = prog.keys[t];
        float yaw = prog.yaw[t];
        float pitch = prog.pitch[t];
        boolean left = false, right = false;
        Aim aim = Aim.NONE;
        if (c != null) {
            left = c.left();
            right = c.right();
            yaw = (float) c.yaw();
            pitch = (float) c.pitch();
            aim = aimOf(c.target());
            lastClickAt = now;
            lastClickTick = t;
        }
        port.submit(new Cmd(keys, true, yaw, pitch, left, right, false, aim));
    }

    private Aim aimOf(ClickCompiler.ClickTarget t) {
        return switch (t.kind()) {
            case BLOCK -> new Aim(1, ox + t.dx(), oy + t.dy(), oz + t.dz(), null);
            case ENTITY -> new Aim(2, 0, 0, 0, t.entity());
            default -> Aim.NONE;
        };
    }

    private void stepsTick(long now) {
        String r = runSteps(now);
        if (r == null) return;
        end(r.equals("done") ? "time_up" : r);
    }
}
