package com.porter.qwertestash.pearl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwertestash.manager.SafeJson;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Carries a pearl trip out on the bot (PEARL-CONTRACT v1.0 rev 6): reads the world through {@link World}, feeds what happens into the
 * {@link PearlTrip} machine and does what it answers with (whisper, throw a pearl, turn the {@link AwayGuard} on or off), keeps the
 * {@link PearlJournal}, builds the heartbeat's {@code pearlTravel} object and reads the reply's {@code pearlTrip} and {@code pearlAwayClear}.
 * <p>
 * Threads: everything runs on the <b>bot tick thread</b> ({@link #tick()}, {@link #onReply}, {@link #body}); heartbeat replies reach it through
 * the manager's result queue. Only {@link #requestLocal} and {@link #requestStop} (called from command threads) are thread-safe: they leave a
 * request that the next tick takes. Nothing here logs, whispers or sends a coordinate: the place the bot whispered from stays in the journal
 * file and in memory and is only compared.
 * <p>
 * Robustness, because the in-game spike was not run: a pearl that is not seen is never read as "gone" here (that is the loader's report and it
 * waits out a grace period); before a whisper the bot must be in the world, out of the queue, in the tab list and in the game for the grace
 * period; the PearlDrop result word is mapped (an unknown one is a failed re-arm); the trapdoor and the pearl count are for the loader and the
 * server to judge from their own reports, T only counts its own pearls before and after.
 */
public final class PearlTripDriver implements PearlTrip.Host {
    /** The bot's game, read on the tick. Every method is for the tick thread. */
    public interface World {
        long now();

        /** Connected to the game server and alive. */
        boolean connected();

        boolean inQueue();

        /** The bot's own entry is in the tab list (the server counts it as a player of the world). */
        boolean selfInTabList();

        /** A whisper can be sent now (the command tree has arrived). */
        boolean whisperReady();

        /** When the bot entered the game (this connection), 0 when it is not in the game. */
        long joinedAt();

        double x();

        double y();

        double z();

        /** "minecraft:overworld" form, or null when not known. */
        @Nullable String dimension();

        int pearlsCarried();

        double health();

        int food();

        /** A player that is not a friend is within this many blocks (flat). Nobody's name leaves this call. */
        boolean nonFriendWithin(double radius);

        /** busy_order, busy_repair, sorter_busy, or null when the sorter is at a checkpoint. */
        @Nullable String sorterHold();

        /** Sends {@code /w player text}. False when it could not be sent. */
        boolean whisper(String player, String text);

        /**
         * Starts a PearlDrop deposit of one pearl at the bot's feet; false when it was refused at once (the result word is then available).
         * @param strict only PearlDrop's own 2-block tolerance applies (the home throw: a wrong chamber is worse than none); else the nearest free chamber within 4 blocks is a fallback
         */
        boolean startRearm(boolean strict);

        boolean rearmRunning();

        /** PearlDrop's last result word (SUCCESS, PARTIAL, NO_PEARLS, NO_RIM, NO_CHAMBER, OCCUPIED, ABORTED, ...), or null. */
        @Nullable String rearmResult();

        void stopRearm();

        /** Asks for one read of the stash (the bot is home again). */
        void requestRescan();

        /** Something the owner has to see (a failed re-arm): no coordinates. */
        void alert(String text);

        void info(String text);

        @Nullable String uuid();

        /** How many times the bot has died (a counter that only goes up): a respawn after the whisper is not a pull. */
        int deaths();

        /** A player of this name is in the tab list (the loader must be in the world before it is whispered to). */
        boolean tabListHas(String name);
    }

    /** The settings the driver reads each time. */
    public record Config(boolean enabled, int graceSeconds) {
    }

    public static final double JUMP_BLOCKS = 8.0;
    public static final double STILL_HERE_BLOCKS = 4.0;
    public static final double NEAR_DEPARTURE = 16.0, NEAR_REARM = 8.0;
    public static final long WATCH_MS = 5 * 60_000L;
    public static final long PICKUP_MS = 5_000L, SETTLE_MS = 2_000L, THROW_WAIT_MS = 60_000L, THROW_RUN_MS = 150_000L, PRECHECK_RETRY_MS = 30_000L;
    public static final long LOCAL_BEATS = 4;
    /** The least time between two {@code go} whispers (the plan's rate limit), and how long a go waits for the sorter, a bystander or the loader before it fails. */
    public static final long GO_SPACING_MS = 5 * 60_000L, GO_WAIT_MS = 90_000L;
    /** The global limits on whispers of any kind (the first, the retry, a way back): at least 30 s apart and at most 10 in a rolling hour. Both are kept in the journal. */
    public static final long WHISPER_GAP_MS = 30_000L;
    public static final int WHISPERS_PER_HOUR = 10;
    private static final int MAX_LOCAL_IDS = 20;

    private final World world;
    private final PearlJournal journal;
    private final Supplier<Config> config;
    private final PearlTrip machine;
    private final PearlJournal.Data data;

    /** True while the machine holds a trip (open, or ended but still to be reported or watched). */
    private boolean have;
    private boolean ownDirty, sentObject;
    private @Nullable String ownError;
    private int pearlsBefore = -1, pearlsAfter = -1;
    private boolean finalAcked, haveFrom, haveLast, wasIn;
    private double fromX, fromY, fromZ, lastX, lastY, lastZ;
    private @Nullable String fromDim, lastDim;
    private long watchUntil, retryAt, pickupStart, throwStart, throwWaitUntil, localBeatsLeft;
    private int pickupBase = -1, missingReplies;
    private long joinedSeen;
    /** The whisper was in a heartbeat body, and a reply to a heartbeat came after that: the server has registered it (it only does so from step load_sent or awaiting_pull). */
    private boolean loadSentBuilt, loadSentAcked;

    private enum Throw { NONE, SETTLE, RUNNING }

    private Throw throwing = Throw.NONE;
    private boolean throwForHome;

    private record Local(String id, String action, @Nullable String station, boolean oneWay) {
    }

    private final AtomicReference<Local> localRequest = new AtomicReference<>();
    private final AtomicReference<Boolean> stopRequest = new AtomicReference<>();
    private volatile boolean disconnectSeen;
    private @Nullable Local localSending;
    private final AtomicReference<Boolean> forceHomeRequest = new AtomicReference<>();
    private volatile String statusText = "";
    private volatile @Nullable String note;
    private volatile long lastGoAt;
    private long lastWhisperAt;
    private final java.util.ArrayList<Long> whisperTimes = new java.util.ArrayList<>();
    private int deathsSeen;
    private long goWaitSince;
    private boolean restoredInThrow;

    public PearlTripDriver(World world, Path journalFile, Supplier<Config> config) {
        this.world = world;
        this.journal = new PearlJournal(journalFile);
        this.config = config;
        this.machine = new PearlTrip(this);
        this.data = journal.load(world.now());
        lastGoAt = data.lastGoAt;
        lastWhisperAt = data.lastWhisperAt;
        if (data.whisperTimes != null) whisperTimes.addAll(data.whisperTimes);
        restore();
        deathsSeen = world.deaths();
        refreshStatus();
    }

    @Override
    public long now() {
        return world.now();
    }

    // The state, for the status command and the tests

    public PearlTrip machine() {
        return machine;
    }

    /** The machine holds a trip (open, or ended but not yet acknowledged by the server or still watched). */
    public boolean hasTrip() {
        return have;
    }

    public boolean tripOpen() {
        return have && machine.open();
    }

    /** One line for `stash pearl status` (no coordinates, no names of other players). */
    public String statusLine() {
        return statusText;
    }

    private void refreshStatus() {
        statusText = buildStatus();
    }

    private String buildStatus() {
        AwayGuard.Away away = AwayGuard.get();
        StringBuilder sb = new StringBuilder();
        sb.append(config.get().enabled() ? "pearl travel on" : "pearl travel off");
        if (have) {
            sb.append("; trip ").append(machine.action()).append(' ').append(machine.state()).append(", step ").append(machine.step());
            if (machine.code() != null) sb.append(" (").append(machine.code()).append(')');
            if (machine.watch()) sb.append(", watching for a late pull");
            if (machine.hold()) sb.append(", held: the owner has to clear it");
        }
        else sb.append("; no trip");
        sb.append(away != null ? "; away from the stash (sorting, orders and rescans are off)" : "; at the stash");
        if (localSending != null || localRequest.get() != null) sb.append("; a request is waiting for the server");
        if (note != null) sb.append("; ").append(note);
        return sb.toString();
    }

    // Restoring

    private void restore() {
        if (data.awayStation != null) AwayGuard.on(new AwayGuard.Away(data.awayStation, data.awayTrip, data.awaySince));
        PearlJournal.Trip t = data.trip;
        if (t == null) return;
        long now = world.now();
        boolean open = PearlTrip.RUNNING.equals(t.state) || PearlTrip.PAUSED.equals(t.state);
        boolean watching = PearlTrip.FAILED.equals(t.state) && t.watch && now < t.watchUntil;
        // Before the whisper nothing has happened: the server offers the trip again if it still wants it
        boolean keep = (open && t.whispers > 0) || watching || t.hold || (!open && !t.finalAcked && t.whispers > 0);
        if (!keep) {
            data.trip = null;
            return;
        }
        machine.restore(t.action == null ? "go" : t.action, t.id, t.station, t.dim, t.pearlId, t.loader, t.step == null ? PearlTrip.STEP_PRECHECK : t.step,
            t.state == null ? PearlTrip.FAILED : t.state, t.whispers, t.tries, t.away, watching, t.hold, t.armHomeUsed, t.code, t.warning, t.pauseCode,
            t.rearmResult, t.loadSentAt, t.deadlineAt, t.teleportAt, t.teleportDim);
        have = true;
        ownError = t.ownError;
        pearlsBefore = t.pearlsBefore;
        pearlsAfter = t.pearlsAfter;
        finalAcked = t.finalAcked;
        haveFrom = t.haveFrom;
        fromX = t.fromX;
        fromY = t.fromY;
        fromZ = t.fromZ;
        fromDim = t.fromDim;
        watchUntil = watching ? t.watchUntil : 0;
        restoredInThrow = PearlTrip.STEP_REARM.equals(t.step) || PearlTrip.STEP_ARM_HOME.equals(t.step);
    }

    /** The sorter starts no new job from the local precheck until the whisper (or the end of the trip): the bot is at a checkpoint when it goes. */
    private void syncQuiesce() {
        boolean q = have && machine.open() && !machine.whispered() && (machine.step().equals(PearlTrip.STEP_READY) || machine.step().equals(PearlTrip.STEP_ARM_HOME));
        AwayGuard.quiesce(q);
        if (!q) quiesceSince = 0;
        else if (quiesceSince == 0) quiesceSince = world.now();
    }

    /** The sorter must never be held for ever by a trip that does not go: travel switched off, or no go within the wait and the server's 2 minutes, drops the trip. */
    private void releaseStaleQuiesce(long now) {
        if (!AwayGuard.quiesced()) return;
        boolean stuck = quiesceSince > 0 && now - quiesceSince > GO_WAIT_MS + 120_000L;
        if (!config.get().enabled() || stuck || !have || !machine.open() || machine.whispered()) {
            if (have && machine.open() && !machine.whispered()) {
                run(machine.stopLocal());
                have = false;
            }
            AwayGuard.quiesce(false);
            quiesceSince = 0;
            persist();
        }
    }

    private long quiesceSince;

    private boolean persist() {
        syncQuiesce();
        refreshStatus();
        data.lastGoAt = lastGoAt;
        data.lastWhisperAt = lastWhisperAt;
        data.whisperTimes = new java.util.ArrayList<>(whisperTimes);
        AwayGuard.Away away = AwayGuard.get();
        data.awayStation = away == null ? null : away.station();
        data.awayTrip = away == null ? null : away.trip();
        data.awaySince = away == null ? 0 : away.since();
        if (!have) data.trip = null;
        else {
            PearlJournal.Trip t = new PearlJournal.Trip();
            t.id = machine.id();
            t.action = machine.action();
            t.station = machine.station();
            t.dim = machine.dim();
            t.pearlId = machine.pearlId();
            t.loader = machine.loader();
            t.step = machine.step();
            t.state = machine.state();
            t.code = machine.code();
            t.warning = machine.warning();
            t.pauseCode = machine.pauseCode();
            t.rearmResult = machine.rearmResult();
            t.teleportDim = machine.teleportDim();
            t.ownError = ownError;
            t.whispers = machine.whispers();
            t.tries = machine.tries();
            t.pearlsBefore = pearlsBefore;
            t.pearlsAfter = pearlsAfter;
            t.away = machine.away();
            t.watch = machine.watch();
            t.hold = machine.hold();
            t.armHomeUsed = machine.armHomeUsed();
            t.finalAcked = finalAcked;
            t.loadSentAt = machine.loadSentAt();
            t.deadlineAt = machine.deadlineAt();
            t.teleportAt = machine.teleportAt();
            t.watchUntil = watchUntil;
            t.haveFrom = haveFrom;
            t.fromX = fromX;
            t.fromY = fromY;
            t.fromZ = fromZ;
            t.fromDim = fromDim;
            data.trip = t;
        }
        try {
            journal.save(data);
            return true;
        } catch (IOException | RuntimeException e) {
            world.info("pearl travel: couldn't save the trip journal (" + e.getClass().getSimpleName() + ")");
            return false;
        }
    }

    // Requests from commands (any thread)

    /**
     * An in-game {@code go}, {@code back} or {@code home} (contract: {@code pearlTravel.local}). Returns why it can't be done, or null when it is
     * on its way to the server. {@code go} needs a station id; {@code home} needs the away mark.
     */
    public @Nullable String requestLocal(String action, @Nullable String station, boolean oneWay) {
        // Getting home works while travel is off (a bot that is away must be clearable)
        if (!config.get().enabled() && !action.equals("home")) return "pearl travel is off here (`stash pearl travel on`)";
        if (action.equals("go") && lastGoAt > 0 && world.now() - lastGoAt < GO_SPACING_MS) return "wait 5 minutes between trips";
        if (action.equals("go") || action.equals("back")) {
            if (station != null && !PearlReply.STATION.matcher(station).matches()) return "that is not a station id (like ps-3f2a9c1d; copy it from the website's Pearl travel panel)";
            if (action.equals("go") && station == null) return "give the station id (like ps-3f2a9c1d; copy it from the website's Pearl travel panel)";
            if (action.equals("go") && AwayGuard.away()) return "this bot is away from its stash: use `stash pearl back`";
            if (action.equals("back") && !AwayGuard.away()) return "this bot is not away";
        }
        else if (action.equals("home")) {
            if (!AwayGuard.away()) return "this bot is not away";
        }
        else return "unknown action";
        String id = action.equals("home") ? null : "l-" + String.format("%08x", java.util.concurrent.ThreadLocalRandom.current().nextInt());
        if (!localRequest.compareAndSet(null, new Local(id, action, station, oneWay))) return "another request is still waiting";
        return null;
    }

    /** The game connection ended (any thread): the next tick treats the bot as disconnected even if it is back by then (the bot's ticks do not run while it is offline). */
    public void onDisconnect() {
        disconnectSeen = true;
    }

    /** `stash pearl home force` (terminal only): the away mark and the trip are dropped here, whatever the server says. */
    public void requestForceHome() {
        forceHomeRequest.set(Boolean.TRUE);
    }

    private void forceHome() {
        stopThrowing();
        AwayGuard.off();
        have = false;
        localSending = null;
        note = null;
        persist();
    }

    /** `stash pearl stop`: a trip not yet whispered is dropped. */
    public void requestStop() {
        stopRequest.set(Boolean.TRUE);
    }

    // The tick

    public void tick() {
        if (forceHomeRequest.getAndSet(null) != null) forceHome();
        if (config.get().enabled() || have || AwayGuard.away()) refreshStatus();
        releaseStaleQuiesce(world.now());
        Local request = localRequest.get();
        if (request != null && localSending == null) {
            localRequest.set(null);
            localSending = request;
            localBeatsLeft = LOCAL_BEATS;
            ownDirty = true;
        }
        long now = world.now();
        if (disconnectSeen) {
            disconnectSeen = false;
            if (wasIn) {
                wasIn = false;
                haveLast = false;
                interruptThrow();
                if (have) run(machine.disconnect());
            }
        }
        long ja = world.joinedAt();
        if (wasIn && joinedSeen != 0 && ja != joinedSeen) {
            // A new login without a disconnect (a server switch): what was seen before says nothing about where the bot is now
            wasIn = false;
            haveLast = false;
            interruptThrow();
            if (have) run(machine.disconnect());
        }
        int deaths = world.deaths();
        if (deaths != deathsSeen) {
            deathsSeen = deaths;
            // A respawn is not a pull: after the whisper nothing is known, so the bot is held
            if (have && machine.open() && machine.whispered()) run(machine.holdUnknown());
        }
        // In the queue the bot is not in the world: its position there says nothing
        boolean in = world.connected() && !world.inQueue();
        if (!in) {
            if (wasIn) {
                wasIn = false;
                haveLast = false;
                interruptThrow();
                if (have) run(machine.disconnect());
            }
            return;
        }
        if (!wasIn) {
            wasIn = true;
            reconnected(now);
        }
        if (stopRequest.getAndSet(null) != null && have && machine.open()) run(machine.stopLocal());
        if (!have) {
            track();
            return;
        }

        if (machine.state().equals(PearlTrip.FAILED) && machine.watch() && now >= watchUntil) {
            run(machine.watchEnd());
            persist();
        }
        boolean watchingTeleport = (machine.step().equals(PearlTrip.STEP_AWAITING) && machine.state().equals(PearlTrip.RUNNING))
            || (machine.state().equals(PearlTrip.FAILED) && machine.watch());
        if (watchingTeleport) {
            String dim = world.dimension();
            if (haveLast) {
                boolean dimChange = dim != null && lastDim != null && !dim.equals(lastDim);
                double d = Math.sqrt(sq(world.x() - lastX) + sq(world.y() - lastY) + sq(world.z() - lastZ));
                if (d >= JUMP_BLOCKS || dimChange) run(machine.teleport(d >= JUMP_BLOCKS, dimChange, validDim(dim)));
            }
        }
        track();

        if (machine.step().equals(PearlTrip.STEP_AWAITING) && machine.state().equals(PearlTrip.RUNNING) && machine.deadlineAt() > 0 && now >= machine.deadlineAt()) {
            run(machine.pullDeadline(stillHere()));
        }
        if (machine.step().equals(PearlTrip.STEP_PRECHECK) && machine.state().equals(PearlTrip.PAUSED) && now >= retryAt) {
            retryAt = now + PRECHECK_RETRY_MS;
            run(machine.retryTick());
        }
        else if (machine.step().equals(PearlTrip.STEP_PICKUP) && machine.state().equals(PearlTrip.RUNNING)) tickPickup(now);
        else if (throwing != Throw.NONE) tickThrow(now);
        else if ((machine.step().equals(PearlTrip.STEP_REARM) || machine.step().equals(PearlTrip.STEP_ARM_HOME)) && machine.state().equals(PearlTrip.RUNNING)) {
            // A throw to start (after a restart of the driver the step is the only thing left)
            beginThrow(now, machine.step().equals(PearlTrip.STEP_ARM_HOME));
        }
    }

    private void track() {
        haveLast = true;
        lastX = world.x();
        lastY = world.y();
        lastZ = world.z();
        lastDim = world.dimension();
    }

    private static double sq(double v) {
        return v * v;
    }

    private static @Nullable String validDim(@Nullable String dim) {
        return dim != null && PearlReply.DIM.matcher(dim).matches() ? dim : null;
    }

    /** T is where it whispered: the same dimension and within 4 blocks, with no teleport seen. Without a record of the place it is not known (hold). */
    private boolean stillHere() {
        if (!haveFrom || !world.connected()) return false;
        String dim = world.dimension();
        if (dim == null || fromDim == null || !dim.equals(fromDim)) return false;
        return Math.sqrt(sq(world.x() - fromX) + sq(world.y() - fromY) + sq(world.z() - fromZ)) < STILL_HERE_BLOCKS;
    }

    /** The bot is in the game again (a reconnect, or the first tick): what its journal and its place say happened. */
    private void reconnected(long now) {
        joinedSeen = world.joinedAt();
        haveLast = false;
        if (!have) return;
        boolean moved = false;
        String dim = world.dimension();
        if (haveFrom) {
            moved = (dim != null && fromDim != null && !dim.equals(fromDim))
                || Math.sqrt(sq(world.x() - fromX) + sq(world.y() - fromY) + sq(world.z() - fromZ)) >= JUMP_BLOCKS;
        }
        if (machine.step().equals(PearlTrip.STEP_AWAITING) && machine.state().equals(PearlTrip.RUNNING)) run(machine.reconnect(moved, now, validDim(dim)));
        else if (machine.state().equals(PearlTrip.FAILED) && machine.watch() && moved) run(machine.teleport(true, false, validDim(dim)));
        else run(machine.reconnect(false));
    }

    // Prechecks

    private long joinWaitMs() {
        // The contract's 10 s, and at least the grace period for the pearls to be back in the world after a join (B2) and the chat to be ready (15 s)
        return Math.max(15, config.get().graceSeconds()) * 1000L;
    }

    /** The local precheck. A fatal code, a pause code (counted or not), or null when all is well. */
    @Nullable String precheck(boolean back) {
        if (!world.connected()) return "not_ingame";
        if (world.inQueue()) return "in_queue";
        if (!world.selfInTabList()) return "not_ingame";
        long joined = world.joinedAt();
        if (joined <= 0 || world.now() - joined < joinWaitMs() || !world.whisperReady()) return "just_joined";
        if (!back) {
            if (lastGoAt > 0 && world.now() - lastGoAt < GO_SPACING_MS) return "stopped";
            String hold = world.sorterHold();
            if (hold != null) return hold;
        }
        if (world.nonFriendWithin(NEAR_DEPARTURE)) return "player_near";
        if (world.health() < 10) return "low_health";
        if (world.food() < 6) return "low_food";
        if (world.pearlsCarried() < 2) return "low_pearls";
        return null;
    }

    /** Checked at the moment the server says go: the whisper is never sent into the queue, to a disconnected bot or before the chat is ready. */
    @Nullable String goRefusal() {
        if (!world.connected()) return "not_ingame";
        if (world.inQueue()) return "in_queue";
        if (!world.selfInTabList()) return "not_ingame";
        long joined = world.joinedAt();
        if (joined <= 0 || world.now() - joined < joinWaitMs() || !world.whisperReady()) return "just_joined";
        return null;
    }

    /** "gap" or "cap" while a whisper would break the global limits, else null. */
    @Nullable String whisperLimit(long now) {
        whisperTimes.removeIf(t -> now - t >= 3_600_000L);
        if (lastWhisperAt > 0 && now - lastWhisperAt < WHISPER_GAP_MS) return "gap";
        if (whisperTimes.size() >= WHISPERS_PER_HOUR) return "cap";
        return null;
    }

    /**
     * Checked again when the server says go: the sorter, a bystander or a loader that is not in the world is a local wait, not a failure. Returns the code of
     * what is in the way (a wait code) or null.
     */
    @Nullable String goWait(boolean back, @Nullable String loader) {
        if (!back) {
            String hold = world.sorterHold();
            if (hold != null) return hold;
        }
        if (world.nonFriendWithin(NEAR_DEPARTURE)) return "player_near";
        if (loader != null && !world.tabListHas(loader)) return "loader_stale";
        return null;
    }

    // Executing what the machine decided

    private void run(List<PearlTrip.Action> actions) {
        boolean changed = !actions.isEmpty();
        for (PearlTrip.Action a : actions) {
            switch (a.kind()) {
                case "runPrecheck" -> {
                    String why = precheck(machine.action().equals("back"));
                    run(machine.precheck(why == null, why));
                }
                case "report" -> {
                    ownDirty = true;
                    String arg = a.arg() == null ? "" : a.arg();
                    if (arg.equals("ready")) {
                        if (pearlsBefore < 0) pearlsBefore = world.pearlsCarried();
                    }
                    else if (arg.startsWith("error:")) ownError = arg.substring(6);
                    else if (arg.equals("cancelled")) ownError = "stopped";
                    else if (arg.equals("done")) pearlsAfter = world.pearlsCarried();
                }
                case "awayOn" -> {
                    AwayGuard.Away was = AwayGuard.get();
                    String station = was != null ? was.station() : machine.station();
                    if (station != null) AwayGuard.on(new AwayGuard.Away(station, machine.id(), was != null ? was.since() : world.now()));
                }
                case "awayOff" -> AwayGuard.off();
                case "whisper" -> {
                    String loader = machine.loader();
                    String text = a.arg();
                    if (loader == null || text == null || !PearlReply.validLoader(loader) || !text.startsWith("load ") || !PearlReply.PEARL_ID.matcher(text.substring(5)).matches()) break;
                    if (!haveFrom) {
                        haveFrom = true;
                        fromX = world.x();
                        fromY = world.y();
                        fromZ = world.z();
                        fromDim = world.dimension();
                    }
                    // Write ahead: the whisper is sent only once the journal says "whispered, away" (a crash right after must resume, not forget)
                    long oldGo = lastGoAt, oldLast = lastWhisperAt, stamp = world.now();
                    if (machine.action().equals("go") && machine.whispers() == 1) lastGoAt = stamp;
                    lastWhisperAt = stamp;
                    whisperTimes.add(stamp);
                    if (!persist()) {
                        lastGoAt = oldGo;
                        lastWhisperAt = oldLast;
                        whisperTimes.remove(whisperTimes.size() - 1);
                        world.info("pearl travel: no whisper was sent because the journal could not be written");
                        run(machine.revokeWhisper());
                        return;
                    }
                    if (!world.whisper(loader, text)) {
                        // Not sent: nothing happened, so the trip does not claim it did (no load_sent, the guard goes off again for a go)
                        lastGoAt = oldGo;
                        lastWhisperAt = oldLast;
                        whisperTimes.remove(whisperTimes.size() - 1);
                        world.info("pearl travel: the load whisper could not be sent");
                        run(machine.revokeWhisper());
                        persist();
                        return;
                    }
                }
                case "watch" -> watchUntil = world.now() + WATCH_MS;
                case "pickup" -> {
                    pickupBase = world.pearlsCarried();
                    pickupStart = world.now();
                }
                case "rearm" -> beginThrow(world.now(), false);
                case "armHome" -> beginThrow(world.now(), true);
                case "requestRescan" -> world.requestRescan();
                case "alert" -> world.alert("Pearl travel: the pearl could not be thrown back into its chamber (" + machine.rearmResult() + "); this bot has no return pearl there. Look at the Pearl travel panel.");
                default -> {
                    // setDeadline, hold: the machine holds the state
                }
            }
        }
        if (!machine.open()) stopThrowing();
        if (changed) persist();
    }

    // Pickup and throws

    private void tickPickup(long now) {
        if (pickupBase < 0) {
            pickupBase = world.pearlsCarried();
            pickupStart = now;
        }
        if (world.pearlsCarried() > pickupBase || now - pickupStart >= PICKUP_MS) {
            pickupBase = -1;
            run(machine.pickupDone());
        }
    }

    private void beginThrow(long now, boolean home) {
        throwForHome = home;
        throwing = Throw.SETTLE;
        throwStart = now;
        // The pearl dropped for the bot has had its chance; 2 s to settle (the throw for the home chamber needs no settling)
        throwWaitUntil = home ? now : now + SETTLE_MS;
    }

    /** The connection went while a throw was on: the first OCCUPIED after it is that throw's own pearl. */
    private void interruptThrow() {
        if (throwing != Throw.NONE) restoredInThrow = true;
        stopThrowing();
    }

    private void stopThrowing() {
        if (throwing == Throw.RUNNING) world.stopRearm();
        throwing = Throw.NONE;
    }

    private void tickThrow(long now) {
        if (throwing == Throw.SETTLE) {
            if (now < throwWaitUntil) return;
            if (world.nonFriendWithin(NEAR_REARM)) {
                if (now - throwStart < THROW_WAIT_MS) return;
                throwDone("ABORTED");
                return;
            }
            if (world.pearlsCarried() < 1) {
                throwDone("NO_PEARLS");
                return;
            }
            throwing = Throw.RUNNING;
            throwStart = now;
            if (!world.startRearm(throwForHome)) {
            // Refused at once: PearlDrop's last result may still be an earlier throw's SUCCESS, so only a failure word is believed (the home throw: always NO_CHAMBER)
            String word = world.rearmResult();
            boolean failure = word != null && (word.equals("NO_CHAMBER") || word.equals("OCCUPIED") || word.equals("NO_RIM") || word.equals("NO_PEARLS") || word.equals("ABORTED"));
            throwDone(throwForHome || !failure ? "NO_CHAMBER" : word);
        }
            return;
        }
        if (throwing == Throw.RUNNING) {
            if (!world.rearmRunning() && now - throwStart >= 100) {
                throwDone(world.rearmResult());
            }
            else if (now - throwStart >= THROW_RUN_MS) {
                world.stopRearm();
                throwDone("ABORTED");
            }
        }
    }

    /** The throw is over with this PearlDrop word (null or an unknown word: ABORTED, a failed re-arm). */
    private void throwDone(@Nullable String word) {
        throwing = Throw.NONE;
        String w = word == null ? "ABORTED" : word.trim().toUpperCase(java.util.Locale.ROOT);
        if (!PearlTrip.REARM_RESULTS.contains(w)) w = "ABORTED";
        // A throw that was already done before a restart finds its chamber occupied: that is a success
        if (restoredInThrow) {
            restoredInThrow = false;
            if (w.equals("OCCUPIED")) w = "SUCCESS";
        }
        if (throwForHome) {
            if (w.equals("SUCCESS") || w.equals("PARTIAL")) run(machine.armHomeDone());
            else {
                note = "Stand next to your home chamber (within 2 blocks) and try again";
                world.alert("Pearl travel: the return pearl could not be thrown (" + w + "). Stand next to your home chamber (within 2 blocks) and try again; the trip was not started.");
                run(machine.abort("stopped"));
            }
        }
        else run(machine.rearmResult(w));
    }

    // The heartbeat

    /** A string that changes whenever the body has something new to say (the manager sends a heartbeat at once then). */
    public String signature() {
        if (!config.get().enabled() && !AwayGuard.away() && !sentObject) return "-";
        StringBuilder sb = new StringBuilder();
        sb.append(config.get().enabled()).append('|').append(have).append('|');
        if (have) {
            sb.append(machine.id()).append('|').append(machine.step()).append('|').append(machine.state()).append('|').append(machine.whispers()).append('|')
                .append(machine.teleportAt()).append('|').append(machine.rearmResult()).append('|').append(ownError).append('|').append(machine.pauseCode()).append('|').append(finalAcked);
        }
        sb.append('|').append(AwayGuard.away()).append('|').append(localSending == null ? "" : localSending.id() + localSending.action() + localBeatsLeft);
        return sb.toString();
    }

    /** True while a trip is on or a request is waiting: the heartbeat comes every few seconds then. */
    public boolean active() {
        return (config.get().enabled() || AwayGuard.away()) && ((have && (machine.open() || machine.watch() || !finalAcked)) || localSending != null);
    }

    /** What the heartbeat says about pearl travel; call once per heartbeat sent. */
    public ManagerBody body() {
        Config c = config.get();
        // A bot that is away keeps reporting (with enabled false when travel is off): the server must hear "away: null" after a clear
        if (!c.enabled() && !AwayGuard.away()) {
            boolean clear = sentObject;
            sentObject = false;
            return new ManagerBody(null, clear);
        }
        long now = world.now();
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("enabled", c.enabled());
        o.addProperty("at", now);
        String uuid = world.uuid();
        if (uuid != null && uuid.length() == 36) o.addProperty("uuid", uuid.toLowerCase(java.util.Locale.ROOT));
        boolean in = world.connected();
        o.addProperty("game", in ? (world.inQueue() ? "queue" : "in") : "out");
        long joined = world.joinedAt();
        if (in && joined > 0) o.addProperty("joinedAt", joined);
        else o.add("joinedAt", com.google.gson.JsonNull.INSTANCE);
        o.addProperty("client", "plugin");
        AwayGuard.Away away = AwayGuard.get();
        if (away == null) o.add("away", com.google.gson.JsonNull.INSTANCE);
        else {
            JsonObject a = new JsonObject();
            a.addProperty("station", away.station());
            if (away.trip() != null) a.addProperty("trip", away.trip());
            else a.add("trip", com.google.gson.JsonNull.INSTANCE);
            a.addProperty("since", Math.max(1, away.since()));
            o.add("away", a);
        }
        JsonObject pearls = new JsonObject();
        pearls.addProperty("carried", Math.max(0, Math.min(2304, world.pearlsCarried())));
        o.add("pearls", pearls);
        if (have && machine.id() != null && (machine.open() || machine.watch() || !finalAcked)) o.add("trip", tripObject());
        else o.add("trip", com.google.gson.JsonNull.INSTANCE);
        if (localSending != null) {
            JsonObject l = new JsonObject();
            l.addProperty("action", localSending.action());
            if (localSending.id() != null) l.addProperty("localId", localSending.id());
            if (localSending.station() != null) l.addProperty("station", localSending.station());
            l.addProperty("oneWay", localSending.oneWay());
            o.add("local", l);
        }
        else o.add("local", com.google.gson.JsonNull.INSTANCE);
        sentObject = true;
        return new ManagerBody(o, false);
    }

    /** The body for the heartbeat: the object to send, or (null, clear) for an explicit null once. */
    public record ManagerBody(@Nullable JsonObject object, boolean clear) {
    }

    private JsonObject tripObject() {
        JsonObject t = new JsonObject();
        t.addProperty("id", machine.id());
        String step = machine.step();
        if (machine.whispers() > 0) {
            // A pull that came before the server heard of the whisper is reported as awaiting_pull with its teleport time: the server registers the whisper and the
            // arrival from the one report (it takes the whisper only from those two steps)
            if (!loadSentAcked && (step.equals(PearlTrip.STEP_ARRIVED) || step.equals(PearlTrip.STEP_PICKUP) || step.equals(PearlTrip.STEP_REARM) || step.equals(PearlTrip.STEP_DONE))) {
                step = PearlTrip.STEP_AWAITING;
            }
            loadSentBuilt = true;
        }
        t.addProperty("step", step);
        boolean localPause = machine.state().equals(PearlTrip.PAUSED) && machine.step().equals(PearlTrip.STEP_PRECHECK) && machine.pauseCode() != null;
        if (localPause) t.addProperty("pause", machine.pauseCode());
        else t.add("pause", com.google.gson.JsonNull.INSTANCE);
        if (ownError != null && PearlTrip.BOT_ERRORS.contains(ownError) && !machine.state().equals(PearlTrip.RUNNING)) t.addProperty("error", ownError);
        else t.add("error", com.google.gson.JsonNull.INSTANCE);
        t.addProperty("whispers", Math.min(machine.whispers(), PearlTrip.MAX_WHISPERS));
        addTime(t, "loadSentAt", machine.loadSentAt());
        addTime(t, "teleportAt", machine.teleportAt());
        String dim = machine.teleportDim();
        if (dim != null && machine.teleportAt() > 0) t.addProperty("teleportDim", dim);
        else t.add("teleportDim", com.google.gson.JsonNull.INSTANCE);
        String rearm = machine.rearmResult();
        if (rearm != null) {
            JsonObject r = new JsonObject();
            r.addProperty("result", rearm);
            t.add("rearm", r);
        }
        else t.add("rearm", com.google.gson.JsonNull.INSTANCE);
        JsonObject p = new JsonObject();
        if (pearlsBefore >= 0) p.addProperty("before", pearlsBefore);
        else p.add("before", com.google.gson.JsonNull.INSTANCE);
        if (pearlsAfter >= 0) p.addProperty("after", pearlsAfter);
        else p.add("after", com.google.gson.JsonNull.INSTANCE);
        t.add("pearls", p);
        return t;
    }

    private static void addTime(JsonObject o, String f, long v) {
        if (v > 0) o.addProperty(f, v);
        else o.add(f, com.google.gson.JsonNull.INSTANCE);
    }

    // The reply

    /** A heartbeat reply (a 200): the trip it offers or updates, and an away clear. Tick thread. */
    public void onReply(String body) {
        JsonObject o;
        try {
            JsonElement e = SafeJson.parse(body);
            if (!(e instanceof JsonObject obj)) return;
            o = obj;
        } catch (RuntimeException e) {
            return;
        }
        // A heartbeat that got an answer counts toward the local request being delivered
        // A home request is answered when the mark is gone (the heartbeat that shows away: null), not by an offer
        if (localSending != null && localSending.action().equals("home") && !AwayGuard.away()) localSending = null;
        // A home request is answered when the mark is gone (the heartbeat that shows away: null), not by an offer
        if (localSending != null && localSending.action().equals("home") && !AwayGuard.away()) localSending = null;
        if (localSending != null && --localBeatsLeft <= 0) {
            Local gone = localSending;
            localSending = null;
            localUnanswered(gone);
        }
        if (o.get("pearlAwayClear") instanceof com.google.gson.JsonPrimitive p && p.isBoolean() && p.getAsBoolean()) clearAway();
        if (!config.get().enabled()) return;
        PearlReply r = PearlReply.parse(o.get("pearlTrip"));
        if (r == null) {
            if (have && machine.open() && !machine.whispered() && ++missingReplies >= 3) {
                // The server no longer has this trip and nothing was sent: it is dropped (it is offered again if it comes back)
                run(machine.stopLocal());
                have = false;
                persist();
            }
            else if (have && !machine.open()) {
                // The final notice is no longer repeated: the server saw the report
                finalAcked = true;
                release();
            }
            return;
        }
        missingReplies = 0;
        if (have && r.id().equals(machine.id()) && loadSentBuilt) loadSentAcked = true;
        if (!have || !r.id().equals(machine.id())) {
            // One trip at a time: an older one still open, watched or held is not left for a new offer
            // (a new way back is let through while the watch of a failed one runs: the server retries the return)
            if (have && (machine.open() || machine.hold() || (machine.watch() && !r.action().equals("back")))) return;
            if (!r.state().equals(PearlTrip.RUNNING) && !r.state().equals(PearlTrip.PAUSED)) return; // the end of a trip this bot never took
            // An offer that does not fit where the bot is: a way back only from away, a trip there only from home
            if (r.action().equals("back") != AwayGuard.away()) return;
            goWaitSince = 0;
            note = null;
            have = true;
            finalAcked = false;
            loadSentBuilt = loadSentAcked = false;
            ownError = null;
            pearlsBefore = pearlsAfter = -1;
            haveFrom = false;
            watchUntil = 0;
            retryAt = world.now() + PRECHECK_RETRY_MS;
            run(machine.offer(r.action(), r.id(), r.station(), r.dim()));
            if (localSending != null && localSending.action().equals(r.action())) localSending = null;
            persist();
            return;
        }
        if (localSending != null && localSending.action().equals(r.action())) localSending = null;
        // The go is only acted on with the bot in the world and out of the queue
        if (r.go() && machine.step().equals(PearlTrip.STEP_READY) && machine.state().equals(PearlTrip.RUNNING) && !machine.whispered()) {
            String why = goRefusal();
            if (why != null) {
                run(machine.abort(why));
                persist();
                return;
            }
            String wait = goWait(machine.action().equals("back"), r.loader());
            // The global whisper limits wait like the other conditions (and then fail with the closest code)
            if (wait == null && whisperLimit(world.now()) != null) wait = "stopped";
            if (wait != null) {
                // The sorter, a bystander or the loader's absence: wait (the server's own 2 minutes cap it), then fail with the closest contract code
                long nowMs = world.now();
                if (goWaitSince == 0) goWaitSince = nowMs;
                if (nowMs - goWaitSince >= GO_WAIT_MS) {
                    run(machine.abort(wait.equals("loader_stale") ? "loader_stale" : wait.equals("player_near") ? "player_near" : "stopped"));
                    persist();
                }
                return;
            }
            goWaitSince = 0;
        }
        else if (r.retry() && machine.step().equals(PearlTrip.STEP_AWAITING) && machine.state().equals(PearlTrip.RUNNING) && machine.whispers() == 1) {
            // The retry whisper meets the same conditions as the first
            String loader = machine.loader();
            if (goRefusal() != null || (loader != null && !world.tabListHas(loader)) || whisperLimit(world.now()) != null) r = r.withoutRetry();
        }
        boolean serverEnded = r.state().equals(PearlTrip.COMPLETED) || r.state().equals(PearlTrip.FAILED) || r.state().equals(PearlTrip.CANCELLED);
        run(machine.reply(r, serverEnded && stillHere()));
        if (!machine.open() && serverEnded) finalAcked = true;
        release();
        persist();
    }

    /** The trip is over, the server has shown its end, nothing is watched or held: the machine lets go of it. */
    private void release() {
        if (have && !machine.open() && !machine.watch() && !machine.hold() && finalAcked) have = false;
    }

    /** A local request was sent in four answered heartbeats and nothing came of it: the user is told (a status note and an alert), not left guessing. */
    private void localUnanswered(Local l) {
        String text = l.action().equals("home")
            ? "Pearl travel: the server did not clear the away mark (it only does so for a bot it sees away with no trip and no alert; otherwise clear it on the website)."
            : "Pearl travel: no trip started for `stash pearl " + l.action() + "`. This server can't do pearl travel (needs Stash Manager 1.3.0), or it refused the request: check the station id and the Pearl travel panel.";
        note = "the last request got no answer: " + (l.action().equals("home") ? "no away clear" : "server too old (needs Stash Manager 1.3.0) or refused");
        world.alert(text);
        refreshStatus();
    }

    private void clearAway() {
        // A clear while a trip is open is not honoured: it would drop the guard under a trip that is waiting for a pull
        if (have && machine.open()) return;
        boolean was = AwayGuard.away() || (have && machine.hold());
        AwayGuard.off();
        if (localSending != null && localSending.action().equals("home")) localSending = null;
        if (have && !machine.open()) {
            machine.clearHold();
            finalAcked = true;
            release();
        }
        if (was) persist();
    }
}
