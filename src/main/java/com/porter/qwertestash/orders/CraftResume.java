package com.porter.qwertestash.orders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Setting aside again, after a restart, what a craft order carries (CRAFT-CONTRACT.md "Bot execution" point 7): the crafted loads waiting
 * for their bin, and the surplus the ledger saved per item id. Matching is by slot: every carried stack is given to at most one load or
 * surplus entry, so two loads of the same size get two different stacks. Surplus the carried stacks do not make up is refunded to the
 * budget (it was charged when gathered, and is not carried any more). Pure.
 */
public final class CraftResume {
    private CraftResume() {
    }

    /** A carried stack: its slot, namespaced item id and count (plain stacks only). */
    public record Stack(int slot, String id, int count) {
    }

    /** A crafted load wanted back: its key (namespaced item id) and count. */
    public record Load(String key, int count) {
    }

    /**
     * @param lotSlots     for each load by index in the list given, the slot of the stack set aside for it, or -1 when none was found
     * @param surplusSlots slot to item id of the stacks set aside as surplus
     * @param refund       surplus (by id) that could not be found: given back to the budget
     */
    public record Result(List<Integer> lotSlots, Map<Integer, String> surplusSlots, Map<String, Long> refund) {
    }

    public static Result remark(List<Load> loads, Map<String, Long> surplus, List<Stack> carried) {
        Set<Integer> used = new HashSet<>();
        List<Integer> lotSlots = new ArrayList<>();
        for (Load load : loads) {
            int found = -1;
            for (Stack s : carried) {
                if (used.contains(s.slot()) || !s.id().equals(Recipes.ns(load.key())) || s.count() != load.count()) continue;
                found = s.slot();
                break;
            }
            if (found >= 0) used.add(found);
            lotSlots.add(found);
        }
        Map<Integer, String> surplusSlots = new LinkedHashMap<>();
        Map<String, Long> refund = new HashMap<>();
        for (var want : surplus.entrySet()) {
            long left = want.getValue();
            boolean partial = false;
            for (Stack s : carried) {
                if (left <= 0) break;
                if (used.contains(s.slot()) || !s.id().equals(want.getKey())) continue;
                // A stack bigger than what is left to find may hold more than the surplus: it is left alone
                if (s.count() > left) {
                    partial = true;
                    continue;
                }
                used.add(s.slot());
                surplusSlots.put(s.slot(), s.id());
                left -= s.count();
            }
            // Nothing is refunded for a part that a bigger stack may still hold
            if (left > 0 && !partial) refund.put(want.getKey(), left);
        }
        return new Result(lotSlots, surplusSlots, refund);
    }
}
