package com.porter.qwertestash.orders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What crafting may take from the stash for one unit of an order (or for compaction): the stock crafting may use (see
 * {@code SourceRules.craftSurvey}) less what every other remaining unit of the order is planned to take, so a craft never
 * eats what another line needs. Named and enchanted stacks count under their base item id; the stock is kept by index key
 * so the gather can take them by the same rules as an order's loads. Pure; the plan, the replan and the run all go through
 * it (via the sorter's {@code craftPool}), so they agree.
 */
public final class CraftPool {
    private CraftPool() {
    }

    /**
     * Stock crafting may use, by index key: loose stacks, what lies inside shulkers, each key's base item id ("minecraft:oak_log")
     * and stack size.
     */
    public record Stock(Map<String, Long> loose, Map<String, Long> inShulkers, Map<String, String> baseOf, Map<String, Integer> maxStack) {
        public static Stock empty() {
            return new Stock(Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    /** What the other units are planned to take, by index key. */
    public static Map<String, Long> reserved(List<Unit> others) {
        Map<String, Long> out = new HashMap<>();
        for (Unit u : others) {
            if (u.kind != Unit.Kind.Bin) continue;
            for (Order.Lot lot : u.lots) out.merge(lot.key(), (long) lot.count(), Long::sum);
        }
        return out;
    }

    /** Loose then inside shulkers, by key, after what is reserved (taken from the loose stacks first, as the plan does). */
    private static long[] left(Stock s, String key, Map<String, Long> reserved) {
        long loose = s.loose().getOrDefault(key, 0L), inside = s.inShulkers().getOrDefault(key, 0L), r = reserved.getOrDefault(key, 0L);
        long fromLoose = Math.min(loose, r);
        long fromInside = Math.min(inside, r - fromLoose);
        return new long[] {loose - fromLoose, inside - fromInside};
    }

    /** The pool by base item id: the stock less what the other units take. */
    public static Map<String, Long> residual(Stock s, List<Unit> others) {
        Map<String, Long> reserved = reserved(others);
        Map<String, Long> out = new HashMap<>();
        for (String key : keys(s)) {
            long[] l = left(s, key, reserved);
            long n = l[0] + l[1];
            if (n > 0) out.merge(base(s, key), n, Long::sum);
        }
        return out;
    }

    /**
     * The loads to gather for what a craft takes from the stash, by base item id: for each item the keys with most loose
     * stock first, loose stacks before what lies in shulkers, cut into stacks. Short when the pool is.
     */
    public static List<Order.Lot> lots(Stock s, Map<String, Long> needByBase, List<Unit> others) {
        Map<String, Long> reserved = reserved(others);
        List<Order.Lot> out = new ArrayList<>();
        for (var need : needByBase.entrySet()) {
            long want = need.getValue();
            List<String> keys = new ArrayList<>();
            for (String key : keys(s)) if (base(s, key).equals(Recipes.ns(need.getKey()))) keys.add(key);
            keys.sort((a, b) -> {
                long la = left(s, a, reserved)[0], lb = left(s, b, reserved)[0];
                return la != lb ? Long.compare(lb, la) : a.compareTo(b);
            });
            for (int part = 0; part < 2 && want > 0; part++) {
                for (String key : keys) {
                    if (want <= 0) break;
                    long n = Math.min(want, left(s, key, reserved)[part]);
                    if (n <= 0) continue;
                    int max = Math.max(1, s.maxStack().getOrDefault(key, 64));
                    // Each load is taken whole; what an earlier load of this key took is not offered again
                    reserved.merge(key, n, Long::sum);
                    want -= n;
                    for (long left = n; left > 0; left -= max) out.add(new Order.Lot(key, (int) Math.min(max, left)));
                }
            }
        }
        return out;
    }

    private static List<String> keys(Stock s) {
        java.util.Set<String> all = new java.util.TreeSet<>(s.loose().keySet());
        all.addAll(s.inShulkers().keySet());
        return new ArrayList<>(all);
    }

    private static String base(Stock s, String key) {
        String b = s.baseOf().get(key);
        if (b != null) return Recipes.ns(b);
        int hash = key.indexOf('#');
        return Recipes.ns(hash < 0 ? key : key.substring(0, hash));
    }
}
