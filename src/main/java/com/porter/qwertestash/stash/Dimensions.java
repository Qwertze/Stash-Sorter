package com.porter.qwertestash.stash;

import com.porter.qwertestash.orders.OrderBlocker;
import org.jspecify.annotations.Nullable;

/**
 * Dimension ids as the game and the server write them: the bot's own is bare ("overworld"), the website's is namespaced ("minecraft:overworld").
 * Every comparison of two dimensions goes through here, so the two forms are the same dimension.
 */
public final class Dimensions {
    private Dimensions() {
    }

    /** Equal after dropping a namespace and ignoring case; two unknown (null) dimensions are equal, one unknown is not equal to a known one. */
    public static boolean same(@Nullable String a, @Nullable String b) {
        if (a == null || b == null) return a == b;
        return OrderBlocker.sameDimension(a, b);
    }

    /** How long the bot waits for the area's dimension before saying so to the server (a website rescan that cannot go ahead). */
    public static final long WAIT_REPORT_MS = 120_000L;

    /** As below for a stash: nothing to report when it has no area, or the area applies where the bot is now (the wait is over, whatever the timer says). */
    public static @Nullable String waitReport(Stash stash, long waitSince, long now, @Nullable String here) {
        if (stash.area == null || stash.hasAreaIn(here)) return null;
        return waitReport(waitSince, now, stash.area.dimension, here);
    }

    /** The status for the server when a website rescan has been waiting this long behind an area in another dimension, or null. */
    public static @Nullable String waitReport(long waitSince, long now, @Nullable String areaDimension, @Nullable String here) {
        if (waitSince <= 0 || now - waitSince < WAIT_REPORT_MS) return null;
        return "the stash area is in another dimension (" + (areaDimension == null ? "?" : areaDimension) + "), the bot is in " + (here == null ? "?" : here);
    }
}
