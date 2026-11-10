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
        Map<String, Long> out = new LinkedHashMap<>();
        for (Order.Lot lot : loads) {
            String key = lot.key();
            int hash = key.indexOf('#');
            out.merge(Recipes.ns(hash < 0 ? key : key.substring(0, hash)), (long) lot.count(), Long::sum);
        }
        return out;
    }

    /**
     * The charge: all that was gathered once at least one grid fill was made (what the crafts did not use stays carried as surplus), less
     * what was put back to the stash; nothing when no fill was made (everything goes back, the budget is untouched).
     */
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
