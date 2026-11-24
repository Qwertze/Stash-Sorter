package com.porter.qwertestash.module;

import com.porter.qwertestash.stash.Item;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/** Carry-slot configuration for one inventory scan. The caller supplies the current item at each eligible slot. */
final class CarrySlots {
    private static final int SLOT_COUNT = 36;
    private static final int PEARL_HOTBAR_SLOT = 0;
    private final Set<Integer> kept;
    private final Set<Integer> chosen;
    private final boolean designated;

    CarrySlots(@Nullable String carry, @Nullable String keep, boolean reservePearlSlot) {
        kept = kept(keep, reservePearlSlot);
        chosen = parse(carry);
        designated = carry != null && !carry.isBlank();
    }

    static Set<Integer> kept(@Nullable String text, boolean reservePearlSlot) {
        Set<Integer> slots = parse(text);
        if (reservePearlSlot) slots.add(PEARL_HOTBAR_SLOT);
        return slots;
    }

    /** Parse the operator's one-based ranges. A malformed component cannot invalidate the other components. */
    static Set<Integer> parse(@Nullable String text) {
        Set<Integer> slots = new HashSet<>();
        if (text == null) return slots;
        for (String part : text.split(",")) {
            String[] range = part.trim().split("-");
            if (range.length == 0 || range[0].isBlank()) continue;
            try {
                int from = Integer.parseInt(range[0].trim());
                int to = range.length > 1 ? Integer.parseInt(range[1].trim()) : from;
                for (int n = Math.max(1, Math.min(from, to)); n <= Math.min(SLOT_COUNT, Math.max(from, to)); n++) {
                    slots.add(n - 1);
                }
            } catch (NumberFormatException ignored) {
                // Legacy files may contain free text alongside valid ranges.
            }
        }
        return slots;
    }

    int keptCount() {
        return kept.size();
    }

    boolean isDesignated() {
        return designated;
    }

    int designatedCount() {
        return chosen.size();
    }

    private boolean eligible(int slot) {
        return !kept.contains(slot) && (!designated || chosen.contains(slot));
    }

    List<Integer> forSorting(IntFunction<@Nullable Item> inventory, Map<Integer, String> stashHeld) {
        List<Integer> result = new ArrayList<>();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (!eligible(slot)) continue;
            Item item = inventory.apply(slot);
            if (item == null || item.id.equals(stashHeld.get(slot))) result.add(slot);
        }
        return result;
    }

    int freeCount(IntFunction<@Nullable Item> inventory) {
        int count = 0;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (eligible(slot) && inventory.apply(slot) == null) count++;
        }
        return count;
    }
}
