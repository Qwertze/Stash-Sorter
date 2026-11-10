package com.porter.qwertestash.stash;

import org.jspecify.annotations.Nullable;

/**
 * The area waiting to be applied (AREA-CONTRACT.md "Bots: applying an area"): from the website or from the `stash area` commands. Any thread may
 * queue one (the command thread, the heartbeat reply); only the sorter's tick applies it, between tasks. Also the edit made in game that the server
 * has not taken yet: the hash of the edited area is kept, and only an acknowledgement of that very hash ends it, so an older reply cannot.
 * Every method is synchronized.
 */
public final class AreaPending {
    private AreaPending() {
    }

    /** @param box minX, minY, minZ, maxX, maxY, maxZ, or null to clear the area; {@code dimension} may be null for a local edit (resolved when applied) */
    public record Request(int @Nullable [] box, @Nullable String dimension, boolean website) {
        public String hash() {
            return AreaRules.hashOf(box, dimension);
        }
    }

    private static @Nullable Request pending;
    private static String why = AreaRules.BUSY;
    private static @Nullable String editedHash;

    public static synchronized void queue(Request r) {
        why = AreaRules.BUSY;
        pending = r;
    }

    public static synchronized @Nullable Request get() {
        return pending;
    }

    /**
     * The request that was applied is removed, but only if it is still the one waiting: one queued meanwhile (a command while the tick was
     * applying) stays. @return true when it was removed
     */
    public static synchronized boolean clear(Request applied) {
        if (pending != applied) return false;
        pending = null;
        why = AreaRules.BUSY;
        return true;
    }

    /** Drops whatever is waiting (nothing is applying it any more). */
    public static synchronized void discard() {
        pending = null;
        why = AreaRules.BUSY;
    }

    /** Why the queued area waits: busy, far or dimension. */
    public static synchronized String why() {
        return why;
    }

    public static synchronized void setWhy(String w) {
        why = w;
    }

    /** An area was changed in game and applied: it is reported as {@code edited} until the server acknowledges this very hash. */
    public static synchronized void markEdited(String hash) {
        editedHash = hash;
    }

    public static synchronized boolean edited() {
        return editedHash != null;
    }

    public static synchronized @Nullable String editedHash() {
        return editedHash;
    }

    /** A reply carried this areaHash. @return true when it was the awaited one and the edit has ended */
    public static synchronized boolean acknowledge(@Nullable String replyHash) {
        if (editedHash == null || replyHash == null || !editedHash.equals(replyHash)) return false;
        editedHash = null;
        return true;
    }

    /** Forgets everything (tests). */
    public static synchronized void reset() {
        pending = null;
        why = AreaRules.BUSY;
        editedHash = null;
    }
}
