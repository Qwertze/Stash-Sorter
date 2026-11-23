package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * Which furnace a craft order smelts in (CRAFT-CONTRACT-v1.2.md 14, milestone B2), one for the whole order, in the order of preference of
 * {@link CraftingTableFinder}: the furnace the bot remembers, then the nearest furnace standing in the stash area that is empty (and that the bot
 * can stand next to), then a furnace taken from the stash and put down (air-placing is allowed on 2b2t) and left standing and remembered, then one
 * crafted from 8 cobblestone and put down. When none of that works the reason is {@code no_furnace} ({@link #NONE}). Pure: the world comes in through
 * {@link World}. Only a plain furnace is one: never a blast furnace or a smoker.
 */
public final class FurnaceFinder {
    private FurnaceFinder() {
    }

    /** The reason when no furnace can be had (the order's reason code is {@code no_furnace}). */
    public static final String NONE = "no furnace available";

    public static final String FURNACE_ID = "minecraft:furnace";
    /** What a furnace is crafted from: 8 of this (a ring in the 3x3 grid, so it needs a crafting table). */
    public static final String COBBLESTONE_ID = "minecraft:cobblestone";
    public static final int COBBLESTONE = 8;

    /** How the remembered furnace came to be (the heartbeat's {@code botFurnace.source}). */
    public static final String FOUND = "found", PLACED = "placed", CRAFTED = "crafted";

    /** The furnace the settings remember: where, and how it came to be. */
    public record Memory(BlockPos pos, String source) {
    }

    public interface World {
        /** The block there is a furnace; an unloaded chunk counts as one (nothing is forgotten on a guess). */
        boolean isFurnace(BlockPos p);

        /** The nearest standing furnace in the stash area, empty as far as is known, that the bot can stand next to and has not given up on; or null. */
        @Nullable BlockPos search();

        /** A furnace item the stash can give (not one an order line needs). */
        boolean stockHasFurnace();

        /** A furnace could be crafted from cobblestone the stash can give (8 of it, not what an order line needs, and a crafting table to make it at). */
        boolean canCraft();
    }

    public enum Step { USE, FOUND, PLACE_FROM_STOCK, CRAFT, FAIL }

    /**
     * @param forget the remembered position is gone and is to be dropped
     */
    public record Decision(Step step, @Nullable BlockPos pos, String source, @Nullable String failure, boolean forget) {
    }

    public static Decision decide(@Nullable Memory memory, World world) {
        boolean forget = false;
        if (memory != null && memory.pos() != null) {
            if (world.isFurnace(memory.pos())) {
                String s = memory.source();
                return new Decision(Step.USE, memory.pos(), s == null || s.isEmpty() ? FOUND : s, null, false);
            }
            // The furnace that was remembered is gone: forgotten, then looked for again
            forget = true;
        }
        BlockPos found = world.search();
        if (found != null) return new Decision(Step.FOUND, found, FOUND, null, forget);
        if (world.stockHasFurnace()) return new Decision(Step.PLACE_FROM_STOCK, null, PLACED, null, forget);
        if (world.canCraft()) return new Decision(Step.CRAFT, null, CRAFTED, null, forget);
        return new Decision(Step.FAIL, null, "", NONE, forget);
    }

    /**
     * A furnace the craft limits rule out (CraftBounds.areaWhy says too far, or another dimension than the stash's): never walked to or opened, and a debt or a
     * remembered furnace there is dropped. No verdict (no area known, no dimension known) is not out of limits.
     */
    public static boolean outOfLimits(@Nullable String areaWhy) {
        return CraftBounds.TOO_FAR.equals(areaWhy) || CraftBounds.WRONG_DIMENSION.equals(areaWhy);
    }

    /** The block name of a plain furnace (not a blast furnace or a smoker). */
    public static boolean isFurnaceName(@Nullable String blockName) {
        return "furnace".equals(blockName);
    }

    /** The nearest of these furnaces to {@code from} that pass the test (inside the area, a spot to stand next to, not given up on); ties by x, y, z. */
    public static @Nullable BlockPos nearest(List<BlockPos> furnaces, BlockPos from, Predicate<BlockPos> usable) {
        return CraftingTableFinder.nearest(furnaces, from, usable);
    }

    /** As {@link #nearest}, but a furnace that is known to be empty (one a trip opened and found empty) wins over a nearer one nobody has looked into. */
    public static @Nullable BlockPos nearestPreferring(List<BlockPos> furnaces, BlockPos from, Predicate<BlockPos> usable, Predicate<BlockPos> preferred) {
        BlockPos p = nearest(furnaces, from, q -> usable.test(q) && preferred.test(q));
        return p != null ? p : nearest(furnaces, from, usable);
    }

    /** A furnace the bot put down or crafted itself: leftovers of smelting in it are its own to take out (for a found one, anything in it is somebody else's). */
    public static boolean isOwn(@Nullable String source) {
        return PLACED.equals(source) || CRAFTED.equals(source);
    }
}
