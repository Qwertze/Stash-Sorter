package com.porter.qwertestash.module;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * When a container whose read failed is tried again: after 1 minute, then 2, then every 5 minutes, each container on its own
 * schedule, and back to 1 minute once a read of it works. Checked every tick against the game tick count, never against a
 * modulo that a tick could skip. Bot tick thread only.
 */
public final class SurveyRetry<T> {
    public static final long[] DELAYS_TICKS = {20 * 60, 20 * 120, 20 * 300};

    private final Map<T, int[]> fails = new HashMap<>();
    private final Map<T, Long> due = new HashMap<>();

    /** A read of this container failed at {@code now}. @return the tick it is tried again at */
    public long failed(T box, long now) {
        int[] n = fails.computeIfAbsent(box, k -> new int[1]);
        long at = now + DELAYS_TICKS[Math.min(n[0], DELAYS_TICKS.length - 1)];
        n[0]++;
        due.put(box, at);
        return at;
    }

    /** A read of this container worked (or it is gone). */
    public void succeeded(T box) {
        fails.remove(box);
        due.remove(box);
    }

    public void clear() {
        fails.clear();
        due.clear();
    }

    /** Takes out of {@code failed} the containers whose time has come. @return how many */
    public int release(Set<T> failed, long now) {
        int released = 0;
        for (Iterator<T> it = failed.iterator(); it.hasNext(); ) {
            T box = it.next();
            Long at = due.get(box);
            if (at == null || now >= at) {
                it.remove();
                due.remove(box);
                released++;
            }
        }
        return released;
    }
}
