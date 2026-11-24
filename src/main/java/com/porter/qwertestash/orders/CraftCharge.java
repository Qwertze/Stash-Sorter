package com.porter.qwertestash.orders;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a chunk of a craft load is charged to the order's budget (CRAFT-CONTRACT.md "Bot execution" point 4): what was really gathered
 * from the stash for it, never what a later plan says. A trip that was cut short and planned again from what is carried has a plan
 * that takes nothing from the stash, so charging from the plan would charge nothing for items that are gone. Pure.
 */
public final class CraftCharge {
    private CraftCharge() {
    }

    /** What the gathered loads hold, by namespaced item id (the keys are plain: no component patch). */
    public static Map<String, Long> gathered(List<Order.Lot> loads) {
        return gathered(loads, k -> null);
    }

    /**
     * As above; a load whose key is one of the order's rocket or star keys (B3, {@code refOfKey} gives its pseudo id) is counted under that pseudo id, so
     * the budget of the spec'd take is charged, not the plain id's.
     */
    public static Map<String, Long> gathered(List<Order.Lot> loads, java.util.function.Function<String, String> refOfKey) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Order.Lot lot : loads) {
            String key = lot.key();
            String ref = refOfKey.apply(key);
            if (ref != null) {
                out.merge(ref, (long) lot.count(), Long::sum);
                continue;
            }
            int hash = key.indexOf('#');
            out.merge(Recipes.ns(hash < 0 ? key : key.substring(0, hash)), (long) lot.count(), Long::sum);
        }
        return out;
    }

    /**
     * The charge: all that was gathered once at least one grid fill was made (what the crafts did not use stays carried as surplus), less
     * what was put back to the stash; nothing when no fill was made (everything goes back, the budget is untouched).
     */
    /**
     * As {@link #charge}, for a chunk of a variant (B3, B4): with no grid fill made, what was gathered of the line's own item ({@code ownRef}, stock that went
     * into the load) is charged when the load was made, and nothing else (the ingredients are put back); with fills it is as {@link #charge}.
     */
    public static Map<String, Long> charge(Map<String, Long> gathered, long fillsMade, Map<String, Long> putBack, String ownRef, long made) {
        if (fillsMade > 0) return charge(gathered, fillsMade, putBack);
        Map<String, Long> out = new HashMap<>();
        if (made <= 0) return out;
        long n = gathered.getOrDefault(ownRef, 0L) - Math.max(0, putBack.getOrDefault(ownRef, 0L));
        if (n > 0) out.put(ownRef, n);
        return out;
    }

    public static Map<String, Long> charge(Map<String, Long> gathered, long fillsMade, Map<String, Long> putBack) {
        Map<String, Long> out = new HashMap<>();
        if (fillsMade <= 0) return out;
        for (var e : gathered.entrySet()) {
            long n = e.getValue() - Math.max(0, putBack.getOrDefault(e.getKey(), 0L));
            if (n > 0) out.put(e.getKey(), n);
        }
        return out;
    }
}
