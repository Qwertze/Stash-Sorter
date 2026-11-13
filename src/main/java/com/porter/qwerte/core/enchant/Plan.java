package com.porter.qwerte.core.enchant;

import java.util.List;
import java.util.Map;

/**
 * The result of planning one enchant order line (one item).
 *
 * <p>Items are numbered: {@code 0 .. materials.size()-1} are the materials (index 0 is the base item), and
 * {@code materials.size() + i} is the result of step {@code i}. Steps are in post-order, so a step only refers to
 * materials and earlier steps. The left operand is the target, the right one the sacrifice.
 *
 * @param ok              true when an order was found; false with {@code code} and {@code message} otherwise
 * @param code            null when ok, else a reason code ({@link Planner} lists them)
 * @param message         plain refusal reason for the player, null when ok
 * @param levels          enchantments on the finished item (the wanted ones plus carried extras)
 * @param extras          the enchantments on the finished item that were not ordered
 * @param totalLevels     sum of the step costs
 * @param points          sum of points(cost) over the steps (the minimised objective)
 * @param bottles         estimate: points at 7 XP per bottle plus 10 percent, rounded up
 * @param finalRepairCost stored repair cost of the finished item
 * @param setsTried       leaf sets evaluated
 * @param cursed          curses (not ordered) that the finished item carries from a book; empty for a curse-free plan.
 *                        Not a refusal: the server asks the orderer before running such a plan.
 * @param timedOut        true when the wall-clock deadline of Planner.Limits cut the search short (distinct from
 *                        {@code truncated}, which means a cap or the unit budget did); such a plan is not authoritative
 * @param workUnits       budget units this call spent (both curse passes); the server sums it per order
 * @param truncated       true when more sets existed than the cap allowed (the plan is the best of those tried)
 */
public record Plan(boolean ok, String code, String message, List<Material> materials, List<Step> steps,
                   Map<String, Integer> levels, Map<String, Integer> extras, int totalLevels, int points, int bottles,
                   int finalRepairCost, int setsTried, boolean truncated, List<String> cursed, boolean timedOut, long workUnits) {

    /**
     * A material taken from stock.
     *
     * @param kind       {@code base} or {@code book}
     * @param stockIndex index in the request's {@code bases} (for the base) or {@code books} (for a book)
     * @param count      items in the slot when it is used as a left operand; the planner always uses 1
     */
    public record Material(String kind, Map<String, Integer> enchants, int repairCost, int count, int stockIndex) {
    }

    /** One anvil step: left and right item numbers, the cost in levels, the stored repair cost of the result, and points(cost). */
    public record Step(int left, int right, int cost, int repairCost, int points) {
    }

    public static Plan refused(String code, String message, int setsTried, boolean truncated) {
        return refused(code, message, setsTried, truncated, false);
    }

    public static Plan refused(String code, String message, int setsTried, boolean truncated, boolean timedOut) {
        return new Plan(false, code, message, List.of(), List.of(), Map.of(), Map.of(), 0, 0, 0, 0, setsTried, truncated, List.of(),
                timedOut, 0);
    }

    /** The same plan with the units the whole call spent. */
    public Plan withWorkUnits(long units) {
        return new Plan(ok, code, message, materials, steps, levels, extras, totalLevels, points, bottles, finalRepairCost, setsTried,
                truncated, cursed, timedOut, units);
    }
}
