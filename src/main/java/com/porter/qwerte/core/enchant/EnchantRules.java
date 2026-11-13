package com.porter.qwerte.core.enchant;

/**
 * Every anvil and planner rule constant in one place, so a correction (for example after checking the anvil on a new
 * Minecraft version) is a one-line change. Formulas follow ENCHANT-FARM-plan.md section 2.3 and
 * ANVIL-RULES-verified.md (game facts, no game code).
 */
public final class EnchantRules {
    private EnchantRules() {
    }

    /** A step costing this many levels or more is "Too Expensive" in survival. */
    public static final int TOO_EXPENSIVE_AT = 40;
    /** The most levels one planned step may cost. */
    public static final int MAX_STEP_COST = TOO_EXPENSIVE_AT - 1;
    /** Cost the anvil forces when the left stack holds more than one item and an enchantment would be applied. */
    public static final int STACK_FORCED_COST = 40;
    /** Levels added for each enchantment on the left that conflicts with an enchantment on the right (the right one is dropped). */
    public static final int CONFLICT_PENALTY = 1;
    /** Leaves (the base item plus the books) in one merge search. */
    public static final int MAX_LEAVES = 12;
    /** Leaf sets (source choices) the optimizer evaluates per request. */
    public static final int MAX_SETS = 2000;
    /** Leaf sets generated before ordering. Beyond this the plan is flagged truncated. */
    public static final int MAX_GENERATED_SETS = 20000;
    /** A missing level may be made from two books one level lower; 2 allows four books two levels lower. */
    public static final int MAX_COMBINE_DEPTH = 2;
    /** Distinct enchantments one search can hold (3 bits of level each in one long). */
    public static final int MAX_DISTINCT_ENCHANTS = 21;
    /**
     * Deterministic work budget of one plan call (both curse passes share it): merge attempts, Pareto-list scans,
     * source-enumeration steps and stock pre-processing. Units are not equal in time; the production figures are measured
     * under Limits.DEFAULT in HardeningTest and quoted in ENCHANT-CONTRACT-v1.0.md.
     */
    public static final long MAX_WORK_UNITS = 6_000_000L;
    /** The most work one leaf set (one merge search) may take; a set over it is skipped. Big enough for realistic 12-leaf orders. */
    public static final long MAX_WORK_PER_SET = 3_000_000L;
    /** Distinct base items tried per plan (identical ones count once). */
    public static final int MAX_BASES_TRIED = 8;
    /** Distinct usable book entries (identical enchantments and repair cost count once) kept per plan. */
    public static final int MAX_STOCK_ENTRIES = 64;
    /** Request size caps; beyond them the plan is refused as too_complex. */
    public static final int MAX_WANTED = 12;
    public static final int MAX_RAW_BOOK_ENTRIES = 50_000;
    public static final int MAX_RAW_BASE_ENTRIES = 1024;
    /** The caps above count only RELEVANT usable entries (after filtering); this absolute cap bounds the filtering itself. */
    public static final int MAX_STOCK_LIST_SIZE = 500_000;
    /**
     * Search nodes one merge search may CREATE (an upper bound of the live ones: Pareto pruning frees some); beyond it the set
     * is skipped. Realistic 12-leaf orders create 0.4 to 1.1 million.
     */
    public static final long MAX_NODES_PER_SET = 1_500_000L;
    /** Entries kept per option list while combining (see Planner), and chain options per enchantment. */
    public static final int MAX_COMBINE_PARTS = 40;
    public static final int MAX_CHAIN_OPTIONS = 200;
    /** Average XP of one Bottle o' Enchanting (3 to 11 points, mean 7). */
    public static final int XP_PER_BOTTLE = 7;
    /** Spare bottles on top of the estimate, in percent. */
    public static final int BOTTLE_SPARE_PERCENT = 10;

    /** Prior work cost of a step: the plain sum of the two stored repair-cost values (long, so it cannot overflow). */
    public static long priorWorkCost(int repairCostLeft, int repairCostRight) {
        return (long) Math.max(0, repairCostLeft) + Math.max(0, repairCostRight);
    }

    /** Stored repair cost of the result of a step: 2 * max + 1 (0, 1, 3, 7, 15 ... after 0, 1, 2 ... steps), capped at int max. */
    public static int resultRepairCost(int repairCostLeft, int repairCostRight) {
        long v = 2L * Math.max(Math.max(0, repairCostLeft), Math.max(0, repairCostRight)) + 1;
        return (int) Math.min(v, Integer.MAX_VALUE);
    }

    /** Repair cost of an item that went through n plain anvil steps: 2^n - 1. */
    public static int repairCostAfterSteps(int n) {
        return n <= 0 ? 0 : (int) Math.min((1L << Math.min(n, 30)) - 1, Integer.MAX_VALUE);
    }

    /** Multiplier for an enchantment on an enchanted book on the right: max(1, floor(itemMultiplier / 2)). */
    public static int bookMultiplier(int itemMultiplier) {
        return Math.max(1, itemMultiplier / 2);
    }

    /** Level an enchantment ends with when the right item's level r is merged into the left item's level l (clamped to max). */
    public static int mergedLevel(int l, int r, int max) {
        int lv = l == r ? r + 1 : Math.max(l, r);
        return Math.min(lv, max);
    }

    /** Total XP points needed to go from level 0 to the given level (the player experience curve). */
    public static int points(int level) {
        if (level <= 0) {
            return 0;
        }
        if (level <= 16) {
            return level * level + 6 * level;
        }
        if (level <= 31) {
            return (5 * level * level - 81 * level) / 2 + 360;
        }
        return (9 * level * level - 325 * level) / 2 + 2220;
    }

    /**
     * Bottles to hold for the given points. One bottle gives 3 + nextInt(5) + nextInt(5) XP (mean 7, sd 2), so N bottles
     * give 7N with a spread of 2*sqrt(N). The spare is the smallest N with {@code 7N - 6 sqrt(N) >= points x 1.10} (three
     * standard deviations under the 10 percent margin), and at least {@code ceil(points x 1.10 / 7) + 5}.
     */
    public static int bottles(int points) {
        if (points <= 0) {
            return 0;
        }
        double target = points * (100.0 + BOTTLE_SPARE_PERCENT) / 100.0;
        long floor = ((long) points * (100 + BOTTLE_SPARE_PERCENT) + (long) XP_PER_BOTTLE * 100 - 1) / ((long) XP_PER_BOTTLE * 100) + 5;
        long n = Math.max(1, (long) Math.floor(target / XP_PER_BOTTLE));
        while (XP_PER_BOTTLE * n - 6 * Math.sqrt(n) < target) {
            n++;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(n, floor));
    }
}
