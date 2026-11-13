package com.porter.qwertestash.orders;

import com.porter.qwerte.core.enchant.McData;
import com.porter.qwerte.core.enchant.Plan;
import com.porter.qwerte.core.enchant.PlanRequest;
import com.porter.qwerte.core.enchant.Planner;
import com.porter.qwerte.core.enchant.Stack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The plan made again with the real items (ENCHANT-CONTRACT v1.0, bot rule 2): what the bot picked from the stash is planned with
 * qwerte-core under {@code Limits.DEFAULT} (no clock limit, so a plan means the same everywhere). The index values the server planned
 * with are claims; this is the authority. Refused as {@code cost_mismatch} when the real plan costs more XP points than the server's, or
 * carries a curse nobody agreed to, or does not replay cleanly; the other refusals keep their own reason. Pure.
 */
public final class EnchantReplan {
    private EnchantReplan() {
    }

    /** @param plan the real plan (steps, levels, points) when ok; its materials carry the key of the held stack they are */
    public record Result(boolean ok, @Nullable String code, String message, EnchantOrder.@Nullable ItemPlan plan) {
        static Result refused(String code, String message) {
            return new Result(false, code, message, null);
        }
    }

    /**
     * @param mc       the Minecraft version of the order's block
     * @param itemKey  the line's item key
     * @param wanted   the ordered enchantments (plain id to level)
     * @param server   the server's plan for the item, whose points the real plan may not exceed
     * @param bases    the base stacks gathered (normally one)
     * @param books    the book stacks gathered
     * @param consent  the curses the orderer agreed to (plain ids)
     */
    public static Result replan(String mc, String itemKey, Map<String, Integer> wanted, EnchantOrder.ItemPlan server, List<EnchantItems.Held> bases,
                                List<EnchantItems.Held> books, Set<String> consent) {
        McData d;
        try {
            d = McData.load(mc);
        } catch (RuntimeException e) {
            return Result.refused(Codes.MC_MISMATCH, "no enchantment table for Minecraft " + mc);
        }
        if (bases.isEmpty()) return Result.refused(Codes.NO_BASE, "no base item was picked");
        for (EnchantItems.Held b : bases) {
            if (b.named()) return Result.refused(Codes.NO_BASE, "the base item carries a custom name");
        }
        List<PlanRequest.Wanted> w = new ArrayList<>();
        for (var e : wanted.entrySet()) w.add(new PlanRequest.Wanted(e.getKey(), e.getValue()));
        PlanRequest req = new PlanRequest(McData.norm(itemKey), w, stock(bases), stock(books));
        Plan p;
        try {
            p = Planner.plan(d, req, Planner.Limits.DEFAULT);
        } catch (RuntimeException | StackOverflowError e) {
            return Result.refused(Codes.COST_MISMATCH, "the plan could not be made again");
        }
        if (!p.ok()) return Result.refused(codeOf(p.code()), p.message() == null ? "no plan with the items picked" : p.message());
        List<String> problems = Planner.verify(d, McData.norm(itemKey), p);
        if (!problems.isEmpty()) return Result.refused(Codes.COST_MISMATCH, "the plan does not replay: " + problems.get(0));
        for (String c : p.cursed()) {
            if (!consent.contains(McData.norm(c))) return Result.refused(Codes.COST_MISMATCH, "a book carries a curse nobody agreed to");
        }
        if (p.points() > server.points()) {
            return Result.refused(Codes.COST_MISMATCH, "the items picked cost " + p.points() + " XP points, the server planned " + server.points());
        }
        return new Result(true, null, "", toPlan(p, bases, books));
    }

    /** The reason code of a refused core plan. */
    static String codeOf(String planCode) {
        if (planCode == null) return Codes.COST_MISMATCH;
        return switch (planCode) {
            case Planner.NO_BASE, Planner.BASE_UNUSABLE -> Codes.NO_BASE;
            case Planner.BOOKS_SHORT -> Codes.BOOKS_SHORT;
            case Planner.TOO_EXPENSIVE -> Codes.TOO_EXPENSIVE;
            case Planner.TOO_COMPLEX -> Codes.TOO_COMPLEX;
            default -> Codes.COST_MISMATCH;
        };
    }

    /** One entry per distinct (enchantments, repair cost), with how many are held. */
    private static List<PlanRequest.Stock> stock(List<EnchantItems.Held> held) {
        Map<EnchantItems.Spec, Integer> counts = new LinkedHashMap<>();
        for (EnchantItems.Held h : held) counts.merge(h.spec(), Math.max(1, h.count()), Integer::sum);
        List<PlanRequest.Stock> out = new ArrayList<>();
        for (var e : counts.entrySet()) out.add(new PlanRequest.Stock(new Stack(e.getKey().book(), e.getKey().ench(), e.getKey().rc()), e.getValue()));
        return out;
    }

    /** The core plan as an item plan; a material takes the key of a held stack of its kind. */
    static EnchantOrder.ItemPlan toPlan(Plan p, List<EnchantItems.Held> bases, List<EnchantItems.Held> books) {
        List<EnchantOrder.Mat> mats = new ArrayList<>();
        List<EnchantItems.Held> pool = new ArrayList<>(bases);
        pool.addAll(books);
        for (Plan.Material m : p.materials()) {
            boolean book = !"base".equals(m.kind());
            EnchantItems.Spec spec = new EnchantItems.Spec(book, m.enchants(), m.repairCost());
            String key = "";
            for (int i = 0; i < pool.size(); i++) {
                if (spec.matches(pool.get(i))) {
                    key = pool.remove(i).key();
                    break;
                }
            }
            mats.add(new EnchantOrder.Mat(book ? "book" : "base", key, m.enchants(), m.repairCost()));
        }
        List<EnchantOrder.Step> steps = new ArrayList<>();
        for (Plan.Step s : p.steps()) steps.add(new EnchantOrder.Step(s.left(), s.right(), s.cost(), s.repairCost()));
        return new EnchantOrder.ItemPlan(mats, steps, p.levels(), p.extras(), new ArrayList<>(p.cursed()), p.points(), p.bottles());
    }

    /**
     * Checks a plan that was read back from a journal (or from the server) before it is followed: step indices point only at items that exist before the step
     * (and are two different items, each used once), costs and repair costs are in range, and the library replays the whole plan to the recorded costs and
     * enchantments ({@link Planner#verify}). @return the first problem, or null when the plan is sound
     */
    public static @Nullable String validate(String mc, String itemKey, EnchantOrder.ItemPlan p) {
        int mat = p.materials().size();
        if (mat < 1 || mat > EnchantOrder.MAX_MATERIALS || p.steps().size() > EnchantOrder.MAX_STEPS || !"base".equals(p.materials().get(0).kind())) return "the materials are not a plan";
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (int i = 0; i < p.steps().size(); i++) {
            EnchantOrder.Step s = p.steps().get(i);
            if (s.left() < 0 || s.right() < 0 || s.left() >= mat + i || s.right() >= mat + i || s.left() == s.right()) return "step " + i + " uses an item that does not exist yet";
            if (!used.add(s.left()) || !used.add(s.right())) return "step " + i + " uses an item twice";
            if (s.cost() < 1 || s.cost() > 39 || s.rc() < 0) return "step " + i + " has a cost out of range";
        }
        McData d;
        try {
            d = McData.load(mc);
        } catch (RuntimeException e) {
            return "no enchantment table for Minecraft " + mc;
        }
        List<Plan.Material> mats = new ArrayList<>();
        for (EnchantOrder.Mat m : p.materials()) mats.add(new Plan.Material(m.kind(), m.ench(), m.rc(), 1, 0));
        List<Plan.Step> steps = new ArrayList<>();
        int total = 0;
        for (EnchantOrder.Step s : p.steps()) {
            steps.add(new Plan.Step(s.left(), s.right(), s.cost(), s.rc(), com.porter.qwerte.core.enchant.EnchantRules.points(s.cost())));
            total += s.cost();
        }
        int finalRc = p.steps().isEmpty() ? 0 : p.steps().get(p.steps().size() - 1).rc();
        Plan core = new Plan(true, null, null, mats, steps, p.levels(), p.extras(), total, p.points(), p.bottles(), finalRc, 0, false, new ArrayList<>(p.curse()), false, 0);
        try {
            List<String> problems = Planner.verify(d, McData.norm(itemKey), core);
            return problems.isEmpty() ? null : problems.get(0);
        } catch (RuntimeException e) {
            return "the plan could not be replayed";
        }
    }
}
