package com.porter.qwertestash.farm;

import java.util.ArrayDeque;

/** At most 4 accepted {@code begin}s per 10 minutes in total for the farmer (C4). Thread-safe. */
public final class UploadRate {
    public static final int MAX = 4;
    public static final long WINDOW_MS = 10 * 60_000L;

    private final ArrayDeque<Long> times = new ArrayDeque<>();

    /** Records an upload and returns true, or returns false (and records nothing) when four are already in the window. */
    public synchronized boolean tryAcquire(long nowMs) {
        while (!times.isEmpty() && nowMs - times.peekFirst() >= WINDOW_MS) times.pollFirst();
        if (times.size() >= MAX) return false;
        times.addLast(nowMs);
        return true;
    }
}
