package com.porter.qwerte.core.enchant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One anvil step on enchantments only (no repair, no rename), as ANVIL-RULES-verified.md describes it for 1.21.4 and
 * 1.21.11 (the same rules in both). This is the readable reference implementation; the optimizer has a packed twin
 * ({@link MergeSearch}) and the tests check they agree.
 */
public final class Anvil {
    private Anvil() {
    }

    public static final String REFUSAL_NOTHING = "nothing";
    public static final String REFUSAL_TOO_EXPENSIVE = "too_expensive";
    public static final String REFUSAL_OPERANDS = "bad_operands";

    /**
     * Outcome of a step.
     *
     * @param ok       true when the anvil gives a result item that can be taken (survival rules)
     * @param refusal  null when ok, else {@code nothing}, {@code too_expensive} or {@code bad_operands}
     * @param cost     levels the step costs (also set for a too_expensive step, 0 when there is no result at all)
     * @param result   the result item (when a result exists, even a too expensive one)
     * @param dropped  right-hand enchantments that were not applied (conflicts and unsupported ones)
     * @param changed  false when every applied enchantment left the left item's enchantments unchanged
     */
    public record Result(boolean ok, String refusal, int cost, Stack result, List<String> dropped, boolean changed) {
    }

    /** Combine for a left stack of one item. */
    public static Result combine(McData d, String itemKey, Stack left, Stack right) {
        return combine(d, itemKey, left, 1, right);
    }

    /**
     * Combines {@code right} into {@code left}.
     *
     * @param itemKey   the base item id (used to check what a non-book left item takes; ignored when the left is a book)
     * @param leftCount items in the left slot; more than one forces the cost to 40 as soon as an enchantment applies
     */
    public static Result combine(McData d, String itemKey, Stack left, int leftCount, Stack right) {
        if (left.book() && !right.book()) {
            return new Result(false, REFUSAL_OPERANDS, 0, null, List.of(), false);
        }
        Map<String, Integer> work = new TreeMap<>(left.enchants());
        List<String> dropped = new ArrayList<>();
        long extra = 0;
        int applied = 0;
        boolean changed = false;
        for (Map.Entry<String, Integer> re : right.enchants().entrySet()) {
            String id = re.getKey();
            McData.Enchantment e = d.get(id);
            if (e == null) {
                dropped.add(id);
                continue;
            }
            int cur = work.getOrDefault(id, 0);
            int lv = EnchantRules.mergedLevel(cur, re.getValue(), e.maxLevel());
            boolean can = left.book() || d.supports(id, itemKey);
            for (String other : work.keySet()) {
                if (!other.equals(id) && d.conflicts(id, other)) {
                    can = false;
                    extra += EnchantRules.CONFLICT_PENALTY;
                }
            }
            if (!can) {
                dropped.add(id);
                continue;
            }
            applied++;
            if (lv != cur) {
                changed = true;
            }
            work.put(id, lv);
            extra += (long) (right.book() ? e.bookMult() : e.itemMult()) * lv;
        }
        if (applied == 0 || extra <= 0) {
            return new Result(false, REFUSAL_NOTHING, 0, null, dropped, false);
        }
        if (leftCount > 1) {
            extra = EnchantRules.STACK_FORCED_COST;
        }
        long total = EnchantRules.priorWorkCost(left.repairCost(), right.repairCost()) + extra;
        int cost = (int) Math.min(total, Integer.MAX_VALUE);
        Stack res = new Stack(left.book(), work, EnchantRules.resultRepairCost(left.repairCost(), right.repairCost()));
        boolean ok = cost < EnchantRules.TOO_EXPENSIVE_AT;
        return new Result(ok, ok ? null : REFUSAL_TOO_EXPENSIVE, cost, res, dropped, changed);
    }
}
