package com.porter.qwertestash.pearl;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Follows the pearl loads PearlPlus carries out and says what the loader report's {@code load} entry is (PEARL-CONTRACT v1.0 rev 6). PearlPlus
 * sends no event, so a load is seen from the outside: its walk-and-click is the pathfinder's interact process aimed at a stored pearl
 * ({@code active}), and what happened is read from the pearl entity afterwards:
 * <ul>
 *   <li>{@code loading} from the first sample with the process on that pearl ({@code at} is that time, the start the 150 s pull deadline counts from);</li>
 *   <li>{@code loaded} when the process ended and the pearl entity that was there at the start is gone ({@code pearlGoneAt}); a pearl that is still there
 *       five seconds after the process ended is {@code aborted}, never "loaded" (the click proves nothing, the contract says so);</li>
 *   <li>{@code aborted} with a reason from the closed list: {@code disconnected} when the bot lost its connection, {@code path_failed} after three path
 *       tries, else {@code timeout} (the neutral one: it never counts toward a broken chamber the way a repeated {@code path_failed} does);</li>
 *   <li>{@code requesterNearAt}: the first time the requester's player entity is seen within 16 blocks of the chamber, up to a minute after the load.</li>
 * </ul>
 * Only loads for a permitted traveller reach this class (the caller filters with {@link LoadAuth}). Pure; one thread (the reporter's).
 */
public final class LoadTracker {
    /** The pearl a load is for. */
    public record Target(UUID owner, String ownerName, String pearlId) {
        boolean same(Target o) {
            return o != null && owner.equals(o.owner) && pearlId.equals(o.pearlId);
        }
    }

    /**
     * One look at the world.
     * @param active        the load in progress now (the process aimed at a permitted traveller's pearl), or null
     * @param pearlPresent  the watched pearl's entity is seen
     * @param inRange       the bot is in range of the watched chamber (so "not seen" is believed)
     * @param requesterNear the watched pearl's owner is within 16 blocks of the chamber
     */
    public record Input(long now, @Nullable Target active, boolean connected, boolean pearlPresent, boolean inRange, boolean requesterNear, int tries) {
    }

    public static final long SETTLE_MS = 5_000L, KEEP_MS = 60_000L;

    private static final class Job {
        final Target target;
        final long at;
        boolean presentSeen;
        String state = "loading";
        @Nullable String reason;
        long pearlGoneAt, requesterNearAt, endedAt;
        boolean pearlPresent;
        int tries;

        Job(Target target, long at, boolean presentAtStart) {
            this.target = target;
            this.at = at;
            this.presentSeen = presentAtStart;
            this.pearlPresent = presentAtStart;
        }

        boolean finished() {
            return !state.equals("loading");
        }
    }

    private @Nullable Job job;
    private boolean changed;
    private boolean settling;

    /** A load is going on (or being settled): the loader is busy. */
    public boolean busy() {
        Job j = job;
        return j != null && (!j.finished() || settling);
    }

    /** The pearl whose entity and requester the caller must look at, or null. */
    public @Nullable Target watching() {
        return job == null ? null : job.target;
    }

    /** Whether the entry changed since the last call (a report is sent at once then). */
    public boolean takeChanged() {
        boolean c = changed;
        changed = false;
        return c;
    }

    public void reset() {
        job = null;
        settling = false;
        changed = false;
    }

    public void sample(Input in) {
        Job j = job;
        if (j != null && j.finished() && in.now() - j.endedAt > KEEP_MS) {
            job = j = null;
            changed = true;
        }
        if (in.active() != null && (j == null || j.finished() || !j.target.same(in.active()))) {
            if (j != null && !j.finished()) finish(j, "aborted", "busy", in.now());
            job = j = new Job(in.active(), in.now(), in.pearlPresent());
            settling = false;
            changed = true;
            return;
        }
        if (j == null) return;
        if (!in.connected() && !j.finished()) {
            finish(j, "aborted", "disconnected", in.now());
            return;
        }
        if (in.requesterNear() && j.requesterNearAt == 0 && in.now() - (j.finished() ? j.endedAt : in.now()) <= KEEP_MS) {
            j.requesterNearAt = in.now();
            changed = true;
        }
        if (j.pearlPresent != in.pearlPresent() && in.inRange()) {
            j.pearlPresent = in.pearlPresent();
            changed = true;
        }
        // Out of range at the start: the pearl counts as seen once it is seen in range during the job, and gone when it then vanishes
        if (in.pearlPresent() && in.inRange()) j.presentSeen = true;
        if (j.presentSeen && !in.pearlPresent() && in.inRange() && j.pearlGoneAt == 0) {
            j.pearlGoneAt = in.now();
            changed = true;
        }
        j.tries = Math.max(j.tries, in.tries());
        if (j.finished()) {
            // A load that ended as aborted is turned into loaded if the pearl is seen gone late
            if (j.state.equals("aborted") && j.pearlGoneAt > 0 && in.now() - j.endedAt <= SETTLE_MS) {
                j.state = "loaded";
                j.reason = null;
                changed = true;
            }
            return;
        }
        if (in.active() != null) return; // the walk and click are going on
        // The process ended: the pearl leaves the world a tick or two later
        if (!settling) {
            settling = true;
            j.endedAt = in.now();
        }
        if (j.pearlGoneAt > 0) finish(j, "loaded", null, in.now());
        else if (in.now() - j.endedAt >= SETTLE_MS) finish(j, "aborted", j.tries >= 3 ? "path_failed" : "timeout", in.now());
    }

    private void finish(Job j, String state, @Nullable String reason, long now) {
        j.state = state;
        j.reason = reason;
        j.endedAt = now;
        settling = false;
        changed = true;
    }

    /** The {@code load} object for the report, or null when there is no load to tell (none, or it ended over a minute ago). */
    public @Nullable JsonObject load() {
        Job j = job;
        if (j == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("id", j.target.pearlId());
        o.addProperty("requester", j.target.ownerName());
        o.addProperty("state", j.state);
        if (j.reason != null) o.addProperty("reason", j.reason);
        else o.add("reason", JsonNull.INSTANCE);
        o.addProperty("at", Math.max(1, j.at));
        time(o, "pearlGoneAt", j.pearlGoneAt);
        time(o, "requesterNearAt", j.requesterNearAt);
        o.addProperty("pearlPresent", j.pearlPresent);
        o.add("reopened", JsonNull.INSTANCE);
        return o;
    }

    private static void time(JsonObject o, String f, long v) {
        if (v > 0) o.addProperty(f, v);
        else o.add(f, JsonNull.INSTANCE);
    }
}
