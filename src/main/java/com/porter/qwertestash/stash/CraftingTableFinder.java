package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import com.porter.qwertestash.orders.CraftPlanner;
import com.porter.qwertestash.orders.Recipes;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which crafting table crafting uses without one being set (1.10.0), the same order of preference as {@link AnvilFinder} and
 * as the Meteor addon: the table set by hand or remembered, then a placed table found in the stash area (the nearest the bot
 * can stand next to), then a crafting table taken from the stash and placed, then one crafted in the inventory's 2x2 grid (from
 * 4 planks, or logs made into planks first) and placed. When none of that works crafting fails with {@link #NONE}. Pure:
 * the world comes in through {@link World}.
 */
public final class CraftingTableFinder {
    private CraftingTableFinder() {
    }

    /** The reason crafting is refused when no table can be had. The addon says the same. */
    public static final String NONE = "no crafting table available";

    public static final String TABLE_ID = "minecraft:crafting_table";

    /** What a table can be crafted from. */
    public enum Material { NONE, PLANKS, LOGS }

    /** The table the settings remember: where, and how it came to be ({@link CraftingTableSpot#SET} ... CRAFTED). */
    public record Memory(BlockPos pos, String source) {
    }

    public interface World {
        /** The block there is a crafting table; an unloaded chunk counts as one (nothing is forgotten on a guess). */
        boolean isTable(BlockPos p);

        /** The nearest placed table in the stash area that the bot can stand next to, or null. */
        @Nullable BlockPos search();

        /** A crafting table item the stash can give (not one an order line needs). */
        boolean stockHasTable();

        /** What a table could be crafted from (the stash's planks, or logs), or NONE. */
        Material craftMaterial();
    }

    public enum Step { USE, FOUND, PLACE_FROM_STOCK, CRAFT, FAIL }

    /**
     * @param forget   the remembered position is gone and is to be dropped
     * @param material for CRAFT: what the table is made from
     */
    public record Decision(Step step, @Nullable BlockPos pos, String source, @Nullable String failure, boolean forget, Material material) {
    }

    public static Decision decide(@Nullable Memory memory, World world) {
        boolean forget = false;
        if (memory != null && memory.pos() != null) {
            if (world.isTable(memory.pos())) {
                String s = memory.source();
                return new Decision(Step.USE, memory.pos(), s == null || s.isEmpty() ? CraftingTableSpot.SET : s, null, false, Material.NONE);
            }
            // The table that was set or remembered is gone: forgotten, then looked for again
            forget = true;
        }
        BlockPos found = world.search();
        if (found != null) return new Decision(Step.FOUND, found, CraftingTableSpot.FOUND, null, forget, Material.NONE);
        if (world.stockHasTable()) return new Decision(Step.PLACE_FROM_STOCK, null, CraftingTableSpot.PLACED, null, forget, Material.NONE);
        Material made = world.craftMaterial();
        if (made != Material.NONE) return new Decision(Step.CRAFT, null, CraftingTableSpot.CRAFTED, null, forget, made);
        return new Decision(Step.FAIL, null, CraftingTableSpot.NONE, NONE, forget, Material.NONE);
    }

    public static boolean isTable(@Nullable String blockName) {
        return CraftingTableSpot.isTableName(blockName);
    }

    /** The bot can stand in a cell beside the table: one of the 8 horizontal neighbours, at dy -1, 0 or +1. */
    public static boolean standBeside(BlockPos t, AnvilFinder.Blocks w) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = -1; dy <= 1; dy++) if (w.standable(t.x() + dx, t.y() + dy, t.z() + dz)) return true;
            }
        }
        return false;
    }

    /** The nearest of these tables to {@code from} that pass the test (inside the area, a spot to stand next to); ties by x, y, z. */
    public static @Nullable BlockPos nearest(List<BlockPos> tables, BlockPos from, Predicate<BlockPos> usable) {
        List<BlockPos> ok = new ArrayList<>();
        for (BlockPos p : tables) if (usable.test(p)) ok.add(p);
        ok.sort(Comparator.comparingLong((BlockPos p) -> distSq(p, from)).thenComparingInt(BlockPos::x).thenComparingInt(BlockPos::y).thenComparingInt(BlockPos::z));
        return ok.isEmpty() ? null : ok.get(0);
    }

    private static long distSq(BlockPos a, BlockPos b) {
        long dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /** The limits a table is planned with: no caps. */
    public static CraftPlanner.Limits limits(Set<String> neverUse) {
        return new CraftPlanner.Limits(true, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, neverUse);
    }

    /** True when every step of the plan fits the 2x2 inventory grid. */
    public static boolean fitsInventoryGrid(CraftPlanner.Plan plan) {
        for (CraftPlanner.Step s : plan.steps()) {
            if (s.recipe().needsTable()) return false;
        }
        return true;
    }

    /**
     * What a crafting table can be made from out of this pool (the stash's, nothing the bot carries): planks (one craft) or logs
     * (planks first), any wood type, 2x2 recipes only. The never-use list is not touched.
     */
    public static Material material(@Nullable Recipes recipes, Map<String, Long> pool, Set<String> neverUse) {
        if (recipes == null) return Material.NONE;
        CraftPlanner.Plan plan = CraftPlanner.plan(recipes, TABLE_ID, 1, pool, limits(neverUse));
        if (!plan.ok() || plan.count() < 1 || plan.steps().isEmpty()) return Material.NONE;
        if (!fitsInventoryGrid(plan)) return Material.NONE;
        return plan.steps().size() <= 1 ? Material.PLANKS : Material.LOGS;
    }
}
