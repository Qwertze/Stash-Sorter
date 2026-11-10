package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The crafting table's window, and which item goes in which grid slot. A crafting table window (ContainerType.CRAFTING) is
 * the result in slot 0, the 3x3 grid in slots 1-9 row by row, then the main inventory (10-36) and the hotbar (37-45): the
 * proxy's container cache holds it with 46 slots, so {@code Stashes.slots} gives 10 and the sorter's {@code toWindow} maps
 * the inventory the same way as {@link #windowSlot}. Pure.
 */
public final class CraftGrid {
    private CraftGrid() {
    }

    public static final int RESULT = 0;
    /** Slots in front of the inventory in a crafting table window. */
    public static final int TABLE_SLOTS = 10;

    /** The window slot of inventory slot {@code index} (0-8 hotbar, 9-35 main) in a crafting table window. */
    public static int windowSlot(int index) {
        return index < 9 ? TABLE_SLOTS + 27 + index : TABLE_SLOTS + index - 9;
    }

    /**
     * The grid slot (1-9) of each cell that takes an item: shaped recipes from the top left corner, row by row; shapeless
     * ones in slots 1, 2, 3, ...
     */
    public static int[] slots(Recipes.Recipe r) {
        return slots(r, 3);
    }

    /** As {@link #slots(Recipes.Recipe)} for a grid {@code width} across (3 for the table, 2 for the inventory's 2x2 grid, slots 1-4). */
    public static int[] slots(Recipes.Recipe r, int width) {
        List<Integer> out = new ArrayList<>();
        if (r.shaped()) {
            for (int row = 0; row < r.h(); row++) {
                for (int col = 0; col < r.w(); col++) {
                    List<String> cell = r.cells().get(row * r.w() + col);
                    if (cell != null && !cell.isEmpty()) out.add(1 + row * width + col);
                }
            }
        }
        else for (int i = 0; i < r.ingredients().size(); i++) out.add(1 + i);
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /**
     * One batch: {@code fills} grid fills at once, each grid slot (index 1-9) given {@code item[slot]} (a kind) x fills (null: empty).
     * Taking the result {@code fills} times uses it up.
     */
    public record Batch(int fills, @Nullable String[] item) {
        public int cells() {
            int n = 0;
            for (int i = 1; i <= 9; i++) if (item[i] != null) n++;
            return n;
        }

        /** What the batch takes from the inventory in all, by kind. */
        public Map<String, Integer> uses() {
            Map<String, Integer> out = new HashMap<>();
            for (int i = 1; i <= 9; i++) if (item[i] != null) out.merge(item[i], fills, Integer::sum);
            return out;
        }
    }

    /**
     * The biggest batch of at most {@code fills} grid fills that what is carried can make, every slot one kind of stack. Of the
     * items a cell accepts, the most plentiful carried one goes in first (then by id). Null when not even one fill can be made.
     * @param carried what the inventory holds that crafting may use, by kind ({@link CraftSlots#kind}: the item id, with its components when it has any)
     * @param maxPerSlot the most one grid slot may hold (the stack size, 64 for everything phase 1 makes)
     */
    public static @Nullable Batch batch(Recipes.Recipe r, Map<String, Integer> carried, int fills, int maxPerSlot) {
        return batch(r, carried, fills, maxPerSlot, 3);
    }

    /** As {@link #batch(Recipes.Recipe, Map, int, int)} for a grid {@code width} across. */
    public static @Nullable Batch batch(Recipes.Recipe r, Map<String, Integer> carried, int fills, int maxPerSlot, int width) {
        return batch(r, carried, fills, maxPerSlot, width, kind -> Integer.MAX_VALUE);
    }

    /**
     * As above, and a grid slot is never given more than the stack size of the kind in it (CRAFT-CONTRACT.md: {@code k <= min(64, the
     * smallest maxStack of the input kinds)}).
     * @param stackOf the stack size of a kind (an item id, with its components when it has any)
     */
    public static @Nullable Batch batch(Recipes.Recipe r, Map<String, Integer> carried, int fills, int maxPerSlot, int width, java.util.function.ToIntFunction<String> stackOf) {
        int[] at = slots(r, width);
        List<List<String>> cells = r.ingredients();
        for (int k = Math.min(fills, maxPerSlot); k >= 1; k--) {
            String[] item = assign(cells, at, carried, k);
            if (item == null) continue;
            boolean fits = true;
            for (int i = 1; i <= 9 && fits; i++) if (item[i] != null && k > stackOf.applyAsInt(item[i])) fits = false;
            if (fits) return new Batch(k, item);
        }
        return null;
    }

    private static @Nullable String[] assign(List<List<String>> cells, int[] at, Map<String, Integer> carried, int k) {
        Map<String, Integer> left = new HashMap<>(carried);
        String[] item = new String[10];
        for (int i = 0; i < cells.size(); i++) {
            String best = null;
            int bestLeft = 0;
            // Carried kinds (an item id, with its components when it has any) whose item the cell takes: one kind per slot,
            // since stacks that differ in a component can't share it (1.8.1)
            List<String> cell = cells.get(i);
            for (String kind : new java.util.TreeSet<>(left.keySet())) {
                if (!cell.contains(CraftSlots.baseOf(kind))) continue;
                int n = left.getOrDefault(kind, 0);
                if (n >= k && (best == null || n > bestLeft || (n == bestLeft && kind.compareTo(best) < 0))) {
                    best = kind;
                    bestLeft = n;
                }
            }
            if (best == null) return null;
            left.merge(best, -k, Integer::sum);
            item[at[i]] = best;
        }
        return item;
    }
}
