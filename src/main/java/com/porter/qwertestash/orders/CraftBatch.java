package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * How much of a craft load one chunk makes (CRAFT-CONTRACT.md "Bot execution", point 5): the biggest count, up to the load, whose
 * sub-plan the order's budget covers, whose grid fills are within the caps, and whose stacks fit the free carry slots minus one: the
 * stacks taken from the stash, one slot for each step's product, the result, and a spare. Not even one fill fitting is
 * {@link CraftOrder#INVENTORY_FULL}. The result of a chunk is one stack (it is never more than a stack of the item), so it can be put in
 * the bin as one load. Pure.
 */
public final class CraftBatch {
    private CraftBatch() {
    }

    /**
     * @param count  how many to make in this chunk, 0 when none
     * @param plan   the sub-plan for {@code count} (null when 0)
     * @param reason why not (a {@link CraftOrder} reason) when {@code count} is 0
     * @param detail the planner's own words for the log
     */
    public record Choice(int count, CraftPlanner.@Nullable Plan plan, @Nullable String reason, @Nullable String detail) {
        public boolean ok() {
            return count > 0;
        }
    }

    /** Carry slots a plan needs: its stash loads, a stack for each step's product, the result, and one spare. */
    public static int room(CraftPlanner.Plan plan, int stashLoads, String key, ToIntFunction<String> stackOf) {
        long slots = stashLoads;
        boolean result = false;
        for (CraftPlanner.Step s : plan.steps()) {
            String id = s.recipe().result();
            long made = s.made();
            long stack = Math.max(1, stackOf.applyAsInt(id));
            slots += (made + stack - 1) / stack;
            if (id.equals(key)) result = true;
        }
        if (!result) slots += (plan.count() + Math.max(1, stackOf.applyAsInt(key)) - 1) / Math.max(1, stackOf.applyAsInt(key));
        return (int) Math.min(Integer.MAX_VALUE, slots + 1);
    }

    /**
     * The largest count up to {@code want} a chunk makes, found by a binary search (each try planned afresh from the ledger).
     * @param ledger    the budget and surplus now
     * @param freeSlots free carry slots now
     * @param fillsLeft grid fills the order and the hour still allow
     * @param stashLoads how many stacks it takes to carry what a plan takes from the stash (the host knows the stash's stack layout)
     */
    public static Choice choose(CraftLedger ledger, String key, int want, int freeSlots, long fillsLeft, ToIntFunction<String> stackOf,
                                ToIntFunction<Map<String, Long>> stashLoads) {
        int stack = Math.max(1, stackOf.applyAsInt(key));
        int top = Math.min(want, stack);
        // The recipe count of the final step is not known before planning: a chunk must come out as one stack
        String firstFailure = null;
        Choice best = null;
        String reasonForOne = null;
        int lo = 0, hi = top;
        // The largest count that works, if any; works(n) is true for every smaller n as far as the budget and room go
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            Try t = attempt(ledger, key, mid, stack, freeSlots, fillsLeft, stackOf, stashLoads);
            if (t.ok) {
                lo = mid;
                best = new Choice(mid, t.plan, null, null);
            }
            else {
                hi = mid - 1;
                if (firstFailure == null) firstFailure = t.detail;
                if (mid == 1 || hi == 0) reasonForOne = t.reason;
            }
        }
        if (lo >= 1) {
            if (best == null || best.count() != lo) {
                Try t = attempt(ledger, key, lo, stack, freeSlots, fillsLeft, stackOf, stashLoads);
                best = new Choice(lo, t.plan, null, null);
            }
            return best;
        }
        // Not even one: the reason one fails with
        Try one = attempt(ledger, key, 1, stack, freeSlots, fillsLeft, stackOf, stashLoads);
        return new Choice(0, null, one.reason != null ? one.reason : reasonForOne != null ? reasonForOne : CraftOrder.INGREDIENTS_SHORT, one.detail != null ? one.detail : firstFailure);
    }

    private record Try(boolean ok, CraftPlanner.@Nullable Plan plan, @Nullable String reason, @Nullable String detail) {
    }

    private static Try attempt(CraftLedger ledger, String key, int n, int stack, int freeSlots, long fillsLeft, ToIntFunction<String> stackOf,
                               ToIntFunction<Map<String, Long>> stashLoads) {
        CraftSub.Session session = ledger.session();
        // Surplus of the item itself is not used for its own load: the load must come out as one stack of its own
        session.dropSurplus(key);
        CraftSub.Outcome o = session.plan(key, n);
        if (!o.ok()) return new Try(false, null, CraftOrder.INGREDIENTS_SHORT, o.plan().refusal());
        CraftPlanner.Plan plan = o.plan();
        // The product of the final step must fit one stack: a recipe that makes 3 for 64 wanted is 21 fills (63), not 22 (66)
        long made = 0;
        for (CraftPlanner.Step s : plan.steps()) if (s.recipe().result().equals(key)) made += s.made();
        if (made > stack) return new Try(false, null, CraftOrder.INVENTORY_FULL, "one stack of " + Recipes.pretty(key) + " would be " + made);
        if (plan.fills() > fillsLeft) return new Try(false, null, CraftOrder.CAP, plan.fills() + " grid fills are more than the " + Math.max(0, fillsLeft) + " left");
        int need = room(plan, stashLoads.applyAsInt(plan.fromStock()), key, stackOf);
        if (need > freeSlots) return new Try(false, null, CraftOrder.INVENTORY_FULL, need + " carry slots wanted, " + freeSlots + " free");
        return new Try(true, plan, null, null);
    }
}
