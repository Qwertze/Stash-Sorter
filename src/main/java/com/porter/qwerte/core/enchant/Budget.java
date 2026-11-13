package com.porter.qwerte.core.enchant;

/**
 * Work budget of one plan call. The unit limit is deterministic (counted units), so every bot and the server stop at the
 * same point; an optional wall-clock deadline (off by default) is a safety net only and is reported separately
 * ({@link #timedOut()}). A budget is shared by every pass of one call.
 */
final class Budget {
    private final long limit;
    private final long deadlineNanos; // 0 = none
    private final Budget parent;
    private final long nodeLimit;
    private long used;
    private long nodes;
    private boolean exhausted;
    private boolean timedOut;

    Budget(long limit, long maxMillis) {
        this(limit, maxMillis > 0 ? System.nanoTime() + maxMillis * 1_000_000L : 0, null, Long.MAX_VALUE);
    }

    private Budget(long limit, long deadlineNanos, Budget parent, long nodeLimit) {
        this.limit = limit;
        this.deadlineNanos = deadlineNanos;
        this.parent = parent;
        this.nodeLimit = nodeLimit;
    }

    static Budget unlimited() {
        return new Budget(Long.MAX_VALUE, 0);
    }

    /** A slice with its own unit cap and live-node cap that also spends units from this budget (and shares its deadline). */
    Budget slice(long cap, long maxNodes) {
        return new Budget(cap, deadlineNanos, this, maxNodes);
    }

    /** Spends n units; false (and sticky) once the budget is gone or the deadline passed. */
    boolean spend(long n) {
        if (exhausted) {
            return false;
        }
        if (parent != null && !parent.spend(n)) {
            exhausted = true;
            timedOut = parent.timedOut;
            return false;
        }
        long before = used;
        used += n;
        if (used > limit) {
            exhausted = true;
        } else if (deadlineNanos != 0 && (before >> 10) != (used >> 10) && System.nanoTime() - deadlineNanos > 0) {
            exhausted = true;
            timedOut = true;
        }
        return !exhausted;
    }

    /** Counts one live search node; false once the node cap is hit. */
    boolean node() {
        if (++nodes > nodeLimit) {
            exhausted = true;
            return false;
        }
        return true;
    }

    boolean exhausted() {
        return exhausted;
    }

    boolean timedOut() {
        return timedOut;
    }

    long used() {
        return used;
    }
}
