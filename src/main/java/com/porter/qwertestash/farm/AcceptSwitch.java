package com.porter.qwertestash.farm;

/**
 * {@code farm.acceptUploads} (FARM Q4, C4): default OFF, set only by the local terminal command, turns itself off after one
 * successful upload or when its minutes run out. It lives in memory only: not a setting, not in the schema, gone after a restart.
 * Thread-safe.
 */
public final class AcceptSwitch {
    public static final int MIN_MINUTES = 1, MAX_MINUTES = 10, DEFAULT_MINUTES = 10;

    /** Where a command came from. Only {@link #TERMINAL} may turn the switch on. */
    public enum Source {
        TERMINAL, DASHBOARD, DISCORD, CONSOLE_ROUTE, BRIDGE, OTHER
    }

    private long untilMs;

    /** @return null when it is on now, else the refusal code ({@code source_refused} or {@code bad_minutes}) */
    public synchronized String enable(Source source, int minutes, long nowMs) {
        if (source != Source.TERMINAL) return "source_refused";
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) return "bad_minutes";
        untilMs = nowMs + minutes * 60_000L;
        return null;
    }

    public synchronized void disable() {
        untilMs = 0;
    }

    public synchronized boolean on(long nowMs) {
        if (untilMs != 0 && nowMs >= untilMs) untilMs = 0;
        return untilMs != 0;
    }

    /** Seconds left, 0 when off. */
    public synchronized long secondsLeft(long nowMs) {
        return on(nowMs) ? (untilMs - nowMs + 999) / 1000 : 0;
    }

    /** One successful upload turns it off. */
    public synchronized void uploaded() {
        untilMs = 0;
    }
}
