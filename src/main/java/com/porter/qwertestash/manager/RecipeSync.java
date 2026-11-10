package com.porter.qwertestash.manager;

import org.jspecify.annotations.Nullable;

/**
 * When the bot asks the server for its recipe table (CRAFT-CONTRACT.md "Recipe tables for the bots"): at start when it has none, and
 * whenever a heartbeat reply's {@code recipesHash} (the server's tableHash for this version) is not the hash of the table the bot
 * holds. The server lets a bot ask 6 times a minute; this asks at most once a minute, and backs off after a failure (a table the
 * server doesn't have is asked for again after 10 minutes). Pure; the tick thread.
 */
public final class RecipeSync {
    public static final long MIN_GAP_MS = 60_000, FAIL_MIN_MS = 30_000, FAIL_MAX_MS = 10 * 60_000L, MISSING_MS = 10 * 60_000L, BUILDING_MS = 60_000;

    private long nextAt;
    private long failBackoff;
    private @Nullable String serverHash;
    private boolean inFlight;

    /** What the last heartbeat reply said the server's table for this version is (null: it said nothing, e.g. an older server). */
    public void serverHash(@Nullable String hash) {
        serverHash = hash == null || hash.isBlank() ? null : hash;
    }

    public @Nullable String serverHash() {
        return serverHash;
    }

    /** A fetch is due: none is running, the gap passed, and the bot has no table or the server's differs from it. */
    public boolean due(long now, @Nullable String heldHash) {
        if (inFlight || now < nextAt) return false;
        if (heldHash == null) return true;
        return serverHash != null && !serverHash.equals(heldHash);
    }

    public void started() {
        inFlight = true;
    }

    public boolean inFlight() {
        return inFlight;
    }

    /** The answer was a table (200) or "not changed" (304). */
    public void succeeded(long now) {
        inFlight = false;
        failBackoff = 0;
        nextAt = now + MIN_GAP_MS;
    }

    /** The answer was not usable; {@code code} is the status (-1: not reached, -2: the signature failed). */
    public void failed(long now, int code) {
        inFlight = false;
        if (code == 404) {
            nextAt = now + MISSING_MS;
            return;
        }
        if (code == 503 || code == 429) {
            nextAt = now + BUILDING_MS;
            return;
        }
        failBackoff = failBackoff == 0 ? FAIL_MIN_MS : Math.min(FAIL_MAX_MS, failBackoff * 2);
        nextAt = now + failBackoff;
    }

    /** A new session: the server may be another one; asked again at once. */
    public void reset() {
        inFlight = false;
        nextAt = 0;
        failBackoff = 0;
        serverHash = null;
    }
}
