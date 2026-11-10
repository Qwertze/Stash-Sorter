package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * What a bot remembers between repair passes, in memory only (REPAIR-CONTRACT v2): items that came back unrepaired (not retried by auto passes for 6 h), boxes
 * opened by a pass (24 h), variants that came out of a box not repaired (no repack for 24 h) and the {oldKey -> newKey} of items a pass repaired (24 h, for orders).
 * Every table is capped. Tick thread.
 */
public final class RepairMemory {
    public static final long FAILURE_MS = 6 * 3_600_000L, DAY_MS = 24 * 3_600_000L;
    public static final int MAX = 2000;

    private final LongSupplier clock;
    private final Map<String, Long> failed = new LinkedHashMap<>(), boxes = new LinkedHashMap<>(), noRepack = new LinkedHashMap<>(), renamedAt = new LinkedHashMap<>();
    private final Map<String, String> renamed = new HashMap<>();

    public RepairMemory(LongSupplier clock) {
        this.clock = clock;
    }

    private void put(Map<String, Long> m, String key) {
        m.remove(key);
        m.put(key, clock.getAsLong());
        while (m.size() > MAX) {
            Iterator<String> it = m.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    private boolean within(Map<String, Long> m, String key, long ms) {
        Long at = m.get(key);
        if (at == null) return false;
        if (clock.getAsLong() - at > ms) {
            m.remove(key);
            return false;
        }
        return true;
    }

    /** An item came back unrepaired. */
    public void failed(String variantOrKey) {
        put(failed, variantOrKey);
    }

    /** Auto passes leave it alone; a manual pass retries it. */
    public boolean recentlyFailed(String variantOrKey) {
        return within(failed, variantOrKey, FAILURE_MS);
    }

    /** A pass opened this box (its key changes with its contents). */
    public void boxOpened(String boxKey) {
        put(boxes, boxKey);
    }

    public boolean boxOnCooldown(String boxKey) {
        return within(boxes, boxKey, DAY_MS);
    }

    /** A variant that came out of a box and was not repaired: the compactor and bulk fill leave it alone for 24 h. */
    public void noRepack(String variantOrKey) {
        put(noRepack, variantOrKey);
    }

    public boolean noRepackHold(String variantOrKey) {
        return within(noRepack, variantOrKey, DAY_MS);
    }

    /** An item repaired in a pass has a new key. */
    public void repaired(String oldKey, String newKey) {
        if (oldKey.equals(newKey)) return;
        put(renamedAt, oldKey);
        renamed.put(oldKey, newKey);
        if (renamed.size() > MAX) renamed.keySet().retainAll(renamedAt.keySet());
    }

    /** The key a repaired item has now, within 24 h, or null. */
    public @Nullable String newKeyFor(String oldKey) {
        return within(renamedAt, oldKey, DAY_MS) ? renamed.get(oldKey) : null;
    }
}
