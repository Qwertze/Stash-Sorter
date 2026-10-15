package com.porter.qwertestash.module;

import com.aquarius.feature.pathfinder.PathingRequestFuture;
import com.aquarius.feature.pathfinder.goals.Goal;
import com.aquarius.feature.pathfinder.process.IBaritoneProcess;
import com.aquarius.feature.player.InputRequestFuture;
import com.aquarius.util.RequestFuture;
import org.jspecify.annotations.Nullable;

import static com.aquarius.Globals.BARITONE;
import static com.aquarius.Globals.INPUTS;
import static com.aquarius.Globals.INVENTORY;

/**
 * Tells what the sorter itself is doing apart from what other modules are doing, so it can wait for them and give way.
 * The proxy has one inventory queue, one Baritone and one open window shared by every module, and nothing says who is
 * using them. The sorter remembers every request it makes, and what is going on that it didn't start is foreign.
 * Ticks are the sorter's own count.
 */
final class Activity {
    enum Reason {
        Pathing("pathing"),
        Inventory("inventory actions"),
        Container("an open container"),
        Input("movement input");

        final String label;

        Reason(String label) {
            this.label = label;
        }
    }

    // The proxy answers a few ticks late: a request isn't in control of Baritone until its next pre-tick, and a stopped
    // goal is only cleared by it. Within this many ticks of our own request, or of letting go of one, what Baritone and
    // the input queue show is not held against anyone
    private static final int GRACE_TICKS = 2;
    private static final long LONG_AGO = Long.MIN_VALUE / 4;

    private long now;

    // Inventory requests of ours: the last one, and the last close
    private @Nullable RequestFuture pending;
    private @Nullable RequestFuture closing;
    private long rejectedTick = LONG_AGO;
    private long inputRejectedTick = LONG_AGO;
    private long evictedTick = LONG_AGO;

    // The Baritone request of ours that is outstanding: its future, the process it went to, and for a path the goal
    private @Nullable PathingRequestFuture ourPath;
    private @Nullable IBaritoneProcess ourProcess;
    private @Nullable Goal ourGoal;
    private long ourRequestTick = LONG_AGO;
    private long lastOwnTick = LONG_AGO;

    // The window we opened or took over
    private volatile int ourContainerId;
    private volatile long ourOpenedAt;

    void reset() {
        pending = null;
        closing = null;
        rejectedTick = evictedTick = inputRejectedTick = LONG_AGO;
        ourPath = null;
        ourProcess = null;
        ourGoal = null;
        ourRequestTick = lastOwnTick = LONG_AGO;
        ourContainerId = 0;
        ourOpenedAt = 0;
    }

    // Inventory

    /**
     * Remembers an inventory request we just made. A refused one is noted too: whoever is ahead of us is working.
     */
    void noteSubmit(RequestFuture future, boolean close, long tick) {
        if (future == RequestFuture.rejected) {
            rejectedTick = tick;
            return;
        }
        if (close) closing = future;
        else pending = future;
    }

    /**
     * A request of ours was refused this tick.
     */
    boolean rejectedAt(long tick) {
        return rejectedTick == tick;
    }

    /**
     * One of our requests is waiting or running. The proxy only counts a request as running once its first action has
     * gone out, so this is what covers the ticks before that.
     */
    boolean ownInventoryLive() {
        return live(pending) || live(closing);
    }

    boolean closingLive() {
        return live(closing);
    }

    private static boolean live(@Nullable RequestFuture future) {
        return future != null && !future.isDone();
    }

    // Inputs

    /**
     * Remembers an input request of ours (aiming, sneaking, clicking). Ours never counts as another module's inputs; one
     * the input queue refused means something else is controlling the bot.
     */
    void noteInput(InputRequestFuture future, long tick) {
        lastOwnTick = tick;
        if (future == InputRequestFuture.rejected) inputRejectedTick = tick;
    }

    // Baritone

    /**
     * Remembers a request of ours to Baritone. Asking again replaces the earlier one, as it does in Baritone.
     */
    void notePath(PathingRequestFuture future, IBaritoneProcess process, @Nullable Goal goal, long tick) {
        ourPath = future;
        ourProcess = process;
        ourGoal = goal;
        ourRequestTick = lastOwnTick = tick;
    }

    /**
     * Our request is outstanding, and the process it went to is the one in control of Baritone (or nobody is yet).
     */
    boolean ownPathing() {
        PathingRequestFuture path = ourPath;
        if (path == null || path.isDone()) return false;
        if (now - ourRequestTick <= GRACE_TICKS) return true;

        IBaritoneProcess control = BARITONE.getPathingControlManager().mostRecentInControl().orElse(null);
        return control == null || control == ourProcess;
    }

    /**
     * Our last request was completed as failed: someone else's replaced it, or it failed by itself (no path, nothing to
     * click). Baritone's active list is a tick late, so whether anything is running is no help in telling them apart;
     * the sorter counts failures per container and gives up on one that keeps failing.
     */
    boolean superseded() {
        PathingRequestFuture path = ourPath;
        return path != null && path.isDone() && !path.isAccepted();
    }

    /**
     * Cancels only the Baritone request that is ours, never someone else's: BARITONE.stop() cancels everything. A path
     * goes if its goal is still the one in the custom goal process (a newer request replaces it), an interaction or
     * pickup if it hasn't finished (a newer one finishes it).
     */
    void releaseOurPathing() {
        IBaritoneProcess process = ourProcess;
        if (process != null) {
            if (process == BARITONE.getCustomGoalProcess()) {
                if (ourGoal != null && BARITONE.getCustomGoalProcess().getGoal() == ourGoal) process.stop();
            }
            else if (ourPath != null && !ourPath.isDone()) {
                process.stop();
            }
        }
        ourPath = null;
        ourProcess = null;
        ourGoal = null;
        lastOwnTick = now;
    }

    // Windows

    /**
     * The window that is open now is the one we opened, so it's ours to read and to close.
     */
    void claimWindow(int id, long openedAt) {
        ourContainerId = id;
        ourOpenedAt = openedAt;
    }

    boolean isOwnWindow(int id, long openedAt) {
        return id != 0 && id == ourContainerId && openedAt == ourOpenedAt;
    }

    void forgetWindow() {
        ourContainerId = 0;
        ourOpenedAt = 0;
    }

    // What others are doing

    /**
     * What another module is doing right now that we should keep out of, or null if nothing.
     * @param foreignWindow a window is open that is not ours (the sorter judges that, it knows what it asked for)
     */
    @Nullable Reason foreign(long tick, boolean foreignWindow) {
        now = tick;
        pollEvictions(tick);

        // Our request finished unsuccessfully while something else is on Baritone or the inputs: another module's
        // request replaced it (a new request completes the one before it as failed). That is theirs, and no grace
        // applies: nothing of ours is asked again until we've given way and they've been quiet. A request that
        // failed with Baritone idle was just a path not found, and is ours to try again
        if (superseded()) return Reason.Pathing;

        boolean own = ownPathing();
        if (own) lastOwnTick = tick;
        boolean recentlyOwn = tick - lastOwnTick <= GRACE_TICKS;

        if (BARITONE.isActive() && !own && !recentlyOwn) return Reason.Pathing;

        // Inventory requests: running while none of ours is, refused to us a moment ago, or one of ours cut off by
        // a higher priority request
        boolean ours = ownInventoryLive();
        if ((INVENTORY.hasActiveRequest() && !ours)
            || tick - rejectedTick <= GRACE_TICKS
            || tick - evictedTick <= GRACE_TICKS) {
            return Reason.Inventory;
        }

        if (foreignWindow) return Reason.Container;
        if (tick - inputRejectedTick <= GRACE_TICKS) return Reason.Input;

        if (INPUTS.hasActiveRequest() && !own && !recentlyOwn) return Reason.Input;
        return null;
    }

    /**
     * A request of ours that finished without being accepted was replaced by a higher priority one before it started.
     */
    private void pollEvictions(long tick) {
        if (pending != null && pending.isDone() && !pending.isAccepted()) {
            evictedTick = tick;
            pending = null;
        }
        if (closing != null && closing.isDone() && !closing.isAccepted()) {
            evictedTick = tick;
            closing = null;
        }
    }
}
