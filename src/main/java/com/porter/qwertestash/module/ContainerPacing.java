package com.porter.qwertestash.module;

/**
 * The container delay (ticks, 0 to 100): a container is not opened until this many ticks after the last one was closed, and the
 * first click in a freshly opened one waits this long too. Pure, so it can be tested; D = 0 is exactly the old behaviour. Counted on
 * the bot tick counter, bot tick thread (the close time is written from there; a close from the disable scheduler is read racily, which
 * only ever shifts a pause by a tick).
 */
public final class ContainerPacing {
    public static final int MAX = 100;
    /** Where the close time starts: long ago, so nothing is held at the start. */
    public static final long NEVER = Long.MIN_VALUE / 4;

    private ContainerPacing() {
    }

    public static int clamp(int delay) {
        return Math.max(0, Math.min(MAX, delay));
    }

    /** Whether an open click has to wait: a delay is set and the last close was less than that many ticks ago. */
    public static boolean hold(int delay, long now, long lastClose) {
        int d = clamp(delay);
        return d > 0 && now - lastClose < d;
    }

    /** Ticks to wait before the first click in a window that has just opened: the click delay plus the container delay. */
    public static int openWait(int clickDelay, int delay) {
        return clickDelay + clamp(delay);
    }
}
