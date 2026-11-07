package com.porter.qwertestash.module;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * What happens after a shulker of ours could not be finished or picked up, kept apart from the world so it can be tested.
 * <ul>
 *   <li>{@link LostMarker}: "a shulker was lost, no more are put down" that ends by itself once the shulker is picked up or
 *       gone, instead of only when sorting is restarted.</li>
 *   <li>{@link Retries}: how often one shulker is gone back to before it is given up on for good.</li>
 *   <li>{@link QuietGate}: going back to it waits for a quiet area (nobody near) and a back-off.</li>
 *   <li>{@link #deadlineAfterWait}: time spent giving way does not count against a deadline.</li>
 * </ul>
 * Bot tick thread only.
 */
public final class ShulkerRecovery {
    /** How many times one shulker is gone back to before it is left standing and the pause becomes a stop. */
    public static final int MAX_RECOVERIES = 3;
    /** Ticks to wait after giving up before going back (a passer-by gets time to go). */
    public static final int BACKOFF_TICKS = 20 * 20;
    /** Ticks nobody else may have been near the shulker for before going back. */
    public static final int QUIET_TICKS = 20 * 15;
    /** Ticks of waiting for a quiet area after which the bot goes back anyway: the break is its own action, safe with a stranger near. */
    public static final int MAX_WAIT_TICKS = 20 * 60 * 5;
    /** Blocks around the shulker (and the bot) another player counts as near. */
    public static final double NEAR_BLOCKS = 32;

    private ShulkerRecovery() {
    }

    /**
     * A deadline moved on by the time spent waiting or giving way, so that time does not count against it.
     * @param waitStart tick the wait began, or a negative number if there was none
     */
    public static long deadlineAfterWait(long deadline, long waitStart, long now) {
        return waitStart < 0 || now <= waitStart ? deadline : deadline + (now - waitStart);
    }

    /** Whether a point is within {@code radius} blocks of another, flat (the height of a passer-by is no matter). */
    public static boolean within(double ax, double az, double bx, double bz, double radius) {
        double dx = ax - bx, dz = az - bz;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** The shulker that stopped the shulker handling, and whether handling is still stopped. */
    public static final class LostMarker<T> {
        // Read by the command and web threads too
        private volatile boolean lost;
        private volatile T at;

        public boolean lost() {
            return lost;
        }

        public T at() {
            return at;
        }

        public void mark(T where) {
            lost = true;
            at = where;
        }

        public void reset() {
            lost = false;
            at = null;
        }

        /** The shulker at this spot was picked up (or its record cleared). @return true if that ended the stop */
        public boolean cleared(T where) {
            if (!lost || at == null || !at.equals(where)) return false;
            reset();
            return true;
        }

        /**
         * The orphan records as they are now: with none for the lost shulker the stop is over (it was forgotten because it is
         * gone from the world).
         * @return true if that ended the stop
         */
        public boolean sync(Collection<T> orphanSpots) {
            if (!lost) return false;
            if (at != null && orphanSpots.contains(at)) return false;
            reset();
            return true;
        }
    }

    /** How many times each shulker spot was gone back to. */
    public static final class Retries<T> {
        private final Map<T, Integer> counts = new HashMap<>();

        /** @return true if the spot may be gone back to once more (and counts it) */
        public boolean allow(T spot) {
            int n = counts.getOrDefault(spot, 0);
            if (n >= MAX_RECOVERIES) return false;
            counts.put(spot, n + 1);
            return true;
        }

        /** Sets the count for a spot (read back from a saved record). */
        public void seed(T spot, int count) {
            if (count > 0) counts.put(spot, Math.min(count, MAX_RECOVERIES));
        }

        public int count(T spot) {
            return counts.getOrDefault(spot, 0);
        }

        public void forget(T spot) {
            counts.remove(spot);
        }

        public void clear() {
            counts.clear();
        }
    }

    /** Going back to a shulker waits for a back-off and then for the area to have been quiet for a while. */
    public static final class QuietGate {
        private final long backoff, quiet;
        private final long maxWait;
        private long armedAt, notBefore, quietSince = -1;
        private boolean forced;

        public QuietGate(long backoff, long quiet, long maxWait) {
            this.backoff = backoff;
            this.quiet = quiet;
            this.maxWait = maxWait;
        }

        public QuietGate(long backoff, long quiet) {
            this(backoff, quiet, MAX_WAIT_TICKS);
        }

        public QuietGate() {
            this(BACKOFF_TICKS, QUIET_TICKS, MAX_WAIT_TICKS);
        }

        /** Starts the back-off. */
        public void arm(long now) {
            armedAt = now;
            notBefore = now + backoff;
            quietSince = -1;
            forced = false;
        }

        /** Whether the last {@link #ready} was true only because the wait ran out (someone was still near). */
        public boolean forced() {
            return forced;
        }

        /** @return true once the back-off is over and nobody has been near for the quiet time */
        public boolean ready(long now, boolean someoneNear) {
            forced = false;
            // Waited long enough: goes back anyway
            if (now - armedAt >= maxWait && now >= notBefore) {
                forced = someoneNear;
                return true;
            }
            if (someoneNear) {
                quietSince = -1;
                return false;
            }
            if (quietSince < 0) quietSince = now;
            return now >= notBefore && now - quietSince >= quiet;
        }
    }
}
