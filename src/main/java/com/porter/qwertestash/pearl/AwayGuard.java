package com.porter.qwertestash.pearl;

import org.jspecify.annotations.Nullable;

/**
 * The bot is away from its stash (PEARL-CONTRACT v1.0, "Away and back"): from the pearl whisper until it is home again. While it
 * is on, the sorter, the index push, a website rescan and the repair passes do nothing, and the order blocker says
 * {@link #REASON}, so no order is claimed. Shared like the other spots of the plugin; the state is written by the trip driver (bot tick
 * thread, and the owner's {@code home} command through it) and read from any thread.
 */
public final class AwayGuard {
    private AwayGuard() {
    }

    /** The order blocker text while away (the contract's wording). */
    public static final String REASON = "travelling/away";

    /** Where the bot went: the station it is away at, the trip that took it and when (the bot's clock). */
    public record Away(String station, @Nullable String trip, long since) {
    }

    private static volatile @Nullable Away away;

    private static volatile boolean quiesce;

    /**
     * A trip is about to leave (from its precheck until the whisper): the sorter starts no new job, so the bot is at a checkpoint when it goes. Not "away":
     * nothing else changes, and the flag is cleared when the trip ends before the whisper.
     */
    public static boolean quiesced() {
        return quiesce;
    }

    public static void quiesce(boolean on) {
        quiesce = on;
    }

    public static boolean away() {
        return away != null;
    }

    public static @Nullable Away get() {
        return away;
    }

    public static void on(Away to) {
        away = to;
    }

    public static void off() {
        away = null;
        quiesce = false;
    }

    /** The order blocker while away, else null. */
    public static @Nullable String blocker() {
        return away != null ? REASON : null;
    }
}
