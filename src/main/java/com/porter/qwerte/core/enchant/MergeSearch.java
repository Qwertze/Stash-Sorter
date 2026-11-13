package com.porter.qwerte.core.enchant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Exact merge-tree search over a fixed set of leaves (the base item plus books): a Pareto DP over leaf subsets.
 *
 * <p>For every subset of leaves it keeps every result that is not beaten on all of (stored repair cost, XP points so
 * far, levels so far, steps) for the same enchantment outcome. A single best per subset is not enough: a cheaper
 * subtree can carry more prior work, which makes later steps cost more, and merging equal books makes the levels
 * depend on the tree's shape.
 *
 * <p>Enchantments are packed into one long (3 bits of level per enchantment id used in this search), so a merge is a
 * few shifts. {@link Anvil} is the readable twin; tests check both agree.
 */
final class MergeSearch {
    private MergeSearch() {
    }

    record Leaf(boolean base, Map<String, Integer> enchants, int repairCost) {
    }

    /** One merge tree result. Leaves have {@code leaf >= 0}. */
    static final class Node {
        long ench;
        int rc;
        int points;
        int levels;
        int steps;
        int cost;
        Node left;
        Node right;
        int leaf = -1;
    }

    static final class Outcome {
        final String[] ids;
        final Map<Long, List<Node>> roots;
        final boolean sawCap;
        /** True when the work budget ran out; the tables are then incomplete and must not be trusted. */
        boolean aborted;
        private final long wantedMask;
        private final long[] wantedLevels; // per local id, 0 when not wanted

        Outcome(String[] ids, Map<Long, List<Node>> roots, boolean sawCap, long wantedMask, long[] wantedLevels) {
            this.ids = ids;
            this.roots = roots;
            this.sawCap = sawCap;
            this.wantedMask = wantedMask;
            this.wantedLevels = wantedLevels;
        }

        Map<String, Integer> decode(long ench) {
            Map<String, Integer> m = new LinkedHashMap<>();
            for (int i = 0; i < ids.length; i++) {
                int l = (int) ((ench >>> (3 * i)) & 7);
                if (l > 0) {
                    m.put(ids[i], l);
                }
            }
            return m;
        }

        /** Number of enchantments on the result that were not ordered. */
        int extras(long ench) {
            int n = 0;
            for (int i = 0; i < ids.length; i++) {
                if (((ench >>> (3 * i)) & 7) > 0 && (wantedMask & (1L << i)) == 0) {
                    n++;
                }
            }
            return n;
        }

        boolean accepts(long ench) {
            for (int i = 0; i < ids.length; i++) {
                if ((wantedMask & (1L << i)) != 0 && ((ench >>> (3 * i)) & 7) != wantedLevels[i]) {
                    return false;
                }
            }
            return true;
        }

        /** True when a is a better plan than b: points, then fewer extras, levels, steps, final repair cost. */
        boolean better(Node a, Node b) {
            if (a.points != b.points) {
                return a.points < b.points;
            }
            int ea = extras(a.ench);
            int eb = extras(b.ench);
            if (ea != eb) {
                return ea < eb;
            }
            if (a.levels != b.levels) {
                return a.levels < b.levels;
            }
            if (a.steps != b.steps) {
                return a.steps < b.steps;
            }
            return a.rc < b.rc;
        }

        /** Best root that gives exactly the wanted levels, or null. */
        Node best() {
            Node best = null;
            for (Map.Entry<Long, List<Node>> e : roots.entrySet()) {
                if (!accepts(e.getKey())) {
                    continue;
                }
                for (Node n : e.getValue()) {
                    if (best == null || better(n, best)) {
                        best = n;
                    }
                }
            }
            return best;
        }
    }

    /**
     * Searches every merge tree over the leaves (leaf 0 must be the base item, which can only be on the left).
     *
     * @param wanted ordered enchantments (id to level); decides {@link Outcome#best()} and the extras count
     * @throws IllegalArgumentException for an unknown enchantment or more than {@link EnchantRules#MAX_DISTINCT_ENCHANTS}
     */
    static Outcome run(McData d, String item, List<Leaf> leaves, Map<String, Integer> wanted) {
        return run(d, item, leaves, wanted, Budget.unlimited());
    }

    /** As {@link #run(McData, String, List, Map)} but stops (Outcome.aborted) when the budget is spent. */
    static Outcome run(McData d, String item, List<Leaf> leaves, Map<String, Integer> wanted, Budget budget) {
        int m = leaves.size();
        if (m < 1 || m > EnchantRules.MAX_LEAVES || !leaves.get(0).base()) {
            throw new IllegalArgumentException("leaf 0 must be the base item and there can be at most " + EnchantRules.MAX_LEAVES + " leaves");
        }
        TreeSet<String> idSet = new TreeSet<>();
        for (Leaf l : leaves) {
            for (Map.Entry<String, Integer> e : l.enchants().entrySet()) {
                if (e.getValue() < 1 || e.getValue() > 7) {
                    throw new IllegalArgumentException("level " + e.getValue() + " of " + e.getKey() + " is outside 1..7");
                }
            }
            idSet.addAll(l.enchants().keySet());
        }
        for (Map.Entry<String, Integer> e : wanted.entrySet()) {
            if (e.getValue() < 1 || e.getValue() > 7) {
                throw new IllegalArgumentException("wanted level " + e.getValue() + " is outside 1..7");
            }
        }
        idSet.addAll(wanted.keySet());
        if (idSet.size() > EnchantRules.MAX_DISTINCT_ENCHANTS) {
            throw new IllegalArgumentException("too many distinct enchantments");
        }
        String[] ids = idSet.toArray(new String[0]);
        int k = ids.length;
        Map<String, Integer> idx = new HashMap<>();
        int[] max = new int[k];
        int[] bookMult = new int[k];
        boolean[] supported = new boolean[k];
        int[] conflictMask = new int[k];
        for (int i = 0; i < k; i++) {
            idx.put(ids[i], i);
            McData.Enchantment e = d.get(ids[i]);
            if (e == null) {
                throw new IllegalArgumentException("unknown enchantment " + ids[i]);
            }
            max[i] = e.maxLevel();
            bookMult[i] = e.bookMult();
            supported[i] = d.supports(ids[i], item);
        }
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) {
                if (i != j && d.conflicts(ids[i], ids[j])) {
                    conflictMask[i] |= 1 << j;
                }
            }
        }
        long wantedMask = 0;
        long[] wantedLevels = new long[k];
        for (Map.Entry<String, Integer> w : wanted.entrySet()) {
            int i = idx.get(w.getKey());
            wantedMask |= 1L << i;
            wantedLevels[i] = w.getValue();
        }

        boolean[] sawCap = {false};
        int full = (1 << m) - 1;
        @SuppressWarnings("unchecked")
        Map<Long, List<Node>>[] dp = new Map[full + 1];
        for (int mask = 1; mask <= full; mask++) {
            Map<Long, List<Node>> table = new LinkedHashMap<>(); // insertion order: deterministic across runs and bots
            dp[mask] = table;
            if (Integer.bitCount(mask) == 1) {
                int li = Integer.numberOfTrailingZeros(mask);
                Leaf lf = leaves.get(li);
                Node n = new Node();
                n.leaf = li;
                n.rc = lf.repairCost();
                long en = 0;
                for (Map.Entry<String, Integer> e : lf.enchants().entrySet()) {
                    en |= (long) e.getValue() << (3 * idx.get(e.getKey()));
                }
                n.ench = en;
                table.computeIfAbsent(en, x -> new ArrayList<>(2)).add(n);
                continue;
            }
            for (int sub = (mask - 1) & mask; sub > 0; sub = (sub - 1) & mask) {
                int rmask = mask ^ sub;
                if ((rmask & 1) != 0) {
                    continue; // the base item is never the sacrifice
                }
                boolean leftItem = (sub & 1) != 0;
                for (List<Node> ll : dp[sub].values()) {
                    for (Node l : ll) {
                        for (List<Node> rl : dp[rmask].values()) {
                            for (Node r : rl) {
                                if (!budget.spend(1)) {
                                    Outcome o = new Outcome(ids, new LinkedHashMap<>(), sawCap[0], wantedMask, wantedLevels);
                                    o.aborted = true;
                                    return o;
                                }
                                Node n = merge(l, r, leftItem, k, max, bookMult, supported, conflictMask, sawCap);
                                if (n != null) {
                                    if (!budget.node()) {
                                        Outcome o = new Outcome(ids, new LinkedHashMap<>(), sawCap[0], wantedMask, wantedLevels);
                                        o.aborted = true;
                                        return o;
                                    }
                                    List<Node> bucket = table.computeIfAbsent(n.ench, x -> new ArrayList<>(2));
                                    if (!budget.spend(bucket.size())) { // the Pareto scan below is charged
                                        Outcome o = new Outcome(ids, new LinkedHashMap<>(), sawCap[0], wantedMask, wantedLevels);
                                        o.aborted = true;
                                        return o;
                                    }
                                    insert(bucket, n);
                                }
                            }
                        }
                    }
                }
            }
        }
        return new Outcome(ids, dp[full], sawCap[0], wantedMask, wantedLevels);
    }

    private static Node merge(Node l, Node r, boolean leftItem, int k, int[] max, int[] bookMult, boolean[] supported,
                              int[] conflictMask, boolean[] sawCap) {
        long le = l.ench;
        long re = r.ench;
        int presentL = 0;
        for (int i = 0; i < k; i++) {
            if (((le >>> (3 * i)) & 7) != 0) {
                presentL |= 1 << i;
            }
        }
        long cost = EnchantRules.priorWorkCost(l.rc, r.rc);
        long res = le;
        int applied = 0;
        for (int i = 0; i < k; i++) {
            int rl = (int) ((re >>> (3 * i)) & 7);
            if (rl == 0) {
                continue;
            }
            int cur = (int) ((le >>> (3 * i)) & 7);
            int lv = EnchantRules.mergedLevel(cur, rl, max[i]);
            boolean can = !leftItem || supported[i];
            int conflicts = Integer.bitCount(conflictMask[i] & presentL);
            if (conflicts > 0) {
                can = false;
                cost += (long) conflicts * EnchantRules.CONFLICT_PENALTY;
            }
            if (!can) {
                continue;
            }
            applied++;
            cost += (long) bookMult[i] * lv;
            res = (res & ~(7L << (3 * i))) | ((long) lv << (3 * i));
        }
        if (applied == 0 || res == le) {
            return null; // no result at all, or a step that changes nothing
        }
        if (cost >= EnchantRules.TOO_EXPENSIVE_AT) {
            sawCap[0] = true;
            return null;
        }
        Node n = new Node();
        n.left = l;
        n.right = r;
        n.ench = res;
        n.rc = EnchantRules.resultRepairCost(l.rc, r.rc);
        n.cost = (int) cost;
        n.points = l.points + r.points + EnchantRules.points((int) cost);
        n.levels = l.levels + r.levels + (int) cost;
        n.steps = l.steps + r.steps + 1;
        return n;
    }

    /** Adds n unless an entry is at least as good on all four measures; drops entries n beats. */
    private static void insert(List<Node> list, Node n) {
        for (Node o : list) {
            if (o.rc <= n.rc && o.points <= n.points && o.levels <= n.levels && o.steps <= n.steps) {
                return;
            }
        }
        list.removeIf(o -> n.rc <= o.rc && n.points <= o.points && n.levels <= o.levels && n.steps <= o.steps);
        list.add(n);
    }
}
