package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Bookkeeping of the carry slots a craft uses, kept apart from the world so it can be tested (1.8.1). Pure.
 * <ul>
 *     <li>Which slots hold the shulker boxes it made: exactly the slots the table's result was put into, never a source
 *     shulker emptied for the craft (in 1.8.0 any empty plain box carried could be kept, and the crafted one put back).</li>
 *     <li>What a craft still owns when a cut-short trip is made again: the steps already made (chests, planks) as well as
 *     what was gathered.</li>
 *     <li>A stack's kind: its item id, and its components when it has any, since a named and a plain stack of one item
 *     can't share a grid slot.</li>
 * </ul>
 */
public final class CraftSlots {
    private CraftSlots() {
    }

    /**
     * @param resultSlots  carry slots the result of the last step was put into, in order
     * @param sourceSlots  carry slots holding a source shulker of the craft
     * @param emptyPlainBox whether the slot holds an unnamed empty plain shulker box now
     * @param max           how many were made
     */
    public static List<Integer> keep(Collection<Integer> resultSlots, Set<Integer> sourceSlots, IntPredicate emptyPlainBox, int max) {
        List<Integer> out = new ArrayList<>();
        for (int slot : new LinkedHashSet<>(resultSlots)) {
            if (out.size() >= max) break;
            if (sourceSlots.contains(slot) || !emptyPlainBox.test(slot)) continue;
            out.add(slot);
        }
        return out;
    }

    /** The slots a craft owns for the next trip: those it owned that still hold something, and the gathered ones. */
    public static Set<Integer> retained(Set<Integer> owned, IntPredicate holds, Collection<Integer> gathered) {
        Set<Integer> out = new LinkedHashSet<>();
        for (int slot : owned) if (holds.test(slot)) out.add(slot);
        out.addAll(gathered);
        return out;
    }

    /** "minecraft:oak_log" for a plain stack, "minecraft:oak_log|<variant>" for one with components. */
    public static String kind(String id, @Nullable String key, @Nullable String variant) {
        String base = Recipes.ns(id);
        return key != null && key.indexOf('#') >= 0 && variant != null ? base + "|" + variant : base;
    }

    /** The item id of a kind. */
    public static String baseOf(String kind) {
        int bar = kind.indexOf('|');
        return bar < 0 ? kind : kind.substring(0, bar);
    }

    /**
     * Minutes after which the n-th alert (0 first) about items held in the crafting table's grid is said: 1, 5, 15, then
     * every hour (1.8.1).
     */
    public static int alertMinutes(int n) {
        return switch (n) {
            case 0 -> 1;
            case 1 -> 5;
            case 2 -> 15;
            default -> 15 + 60 * (n - 2);
        };
    }

    /** Held this long with items in the grid, the table is closed (when they are only the craft's own inputs). */
    public static final int STUCK_ESCAPE_MINUTES = 20;

    /** Whether what is in the grid may be let go of by closing: only the craft's own inputs and what it made from them. */
    public static boolean ownInputsOnly(java.util.Collection<String> gridIds, Set<String> craftIds) {
        for (String id : gridIds) if (!craftIds.contains(Recipes.ns(baseOf(id)))) return false;
        return true;
    }
}
