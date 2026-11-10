package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Matches the slot loads of one bin against stacks: what is already in the shulker, what is carried, and what is still to
 * be fetched. Plain data, so the state of a bin can be decided (and tested) without the game. Stacks match a lot by item
 * key and, when the lot names one, the exact variant; a lot is satisfied by a stack of exactly its count.
 */
public final class BinTracker {
    private BinTracker() {
    }

    /** A stack seen in the inventory, a shulker or a container. {@code slot} is where, in whatever numbering the caller uses. */
    public record Held(int slot, String key, @Nullable String variant, int count) {
    }

    /** The next load to fetch: {@code topUpSlot} is a carried part of it to add to, or -1 to start in a free slot. */
    public record Need(Order.Lot lot, int index, int topUpSlot, int have) {
    }

    public static boolean sameKind(Order.Lot lot, String key, @Nullable String variant) {
        return lot.key().equals(key) && (lot.variant() == null || variant == null || lot.variant().equals(variant));
    }

    /** Whether a stack could be (part of) one of the lots: a lot of its kind that is at least as big. */
    public static boolean owns(List<Order.Lot> lots, String key, @Nullable String variant, int count) {
        for (Order.Lot lot : lots) if (sameKind(lot, key, variant) && count <= lot.count()) return true;
        return false;
    }

    /** The lots (by index) left once stacks of exactly their count and kind are taken away, each stack once. */
    public static List<Integer> unmatched(List<Order.Lot> lots, List<Held> have) {
        List<Integer> left = new ArrayList<>();
        boolean[] used = new boolean[have.size()];
        for (int i = 0; i < lots.size(); i++) {
            Order.Lot lot = lots.get(i);
            boolean found = false;
            for (int h = 0; h < have.size() && !found; h++) {
                Held s = have.get(h);
                if (used[h] || s.count() != lot.count() || !sameKind(lot, s.key(), s.variant())) continue;
                used[h] = true;
                found = true;
            }
            if (!found) left.add(i);
        }
        return left;
    }

    /**
     * As {@link #unmatched} for what is in the shulker: a stack there may be the sum of several lots of its kind (a kit top-up is shift-clicked
     * into the base copy's stack and merges with it), so a stack that is exactly the sum of the lots of its kind still unmatched counts for them all.
     */
    public static List<Integer> unmatchedPacked(List<Order.Lot> lots, List<Held> have) {
        List<Integer> left = new ArrayList<>(unmatched(lots, have));
        if (left.size() < 2) return left;
        boolean[] used = new boolean[have.size()];
        for (int i = 0; i < lots.size(); i++) {
            if (left.contains(i)) continue;
            Order.Lot lot = lots.get(i);
            for (int h = 0; h < have.size(); h++) {
                Held s = have.get(h);
                if (!used[h] && s.count() == lot.count() && sameKind(lot, s.key(), s.variant())) {
                    used[h] = true;
                    break;
                }
            }
        }
        for (int h = 0; h < have.size(); h++) {
            if (used[h]) continue;
            Held s = have.get(h);
            List<Integer> group = new ArrayList<>();
            int sum = 0;
            for (int i : left) {
                Order.Lot lot = lots.get(i);
                if (!sameKind(lot, s.key(), s.variant())) continue;
                group.add(i);
                sum += lot.count();
                if (sum == s.count() && group.size() >= 2) {
                    left.removeAll(group);
                    used[h] = true;
                    break;
                }
                if (sum > s.count()) break;
            }
        }
        return left;
    }

    /** The lots left after what is in the shulker and what is carried exactly. */
    public static List<Order.Lot> remaining(List<Order.Lot> lots, List<Held> packed, List<Held> carried) {
        List<Order.Lot> afterPacked = new ArrayList<>();
        for (int i : unmatched(lots, packed)) afterPacked.add(lots.get(i));
        List<Order.Lot> out = new ArrayList<>();
        for (int i : unmatched(afterPacked, carried)) out.add(afterPacked.get(i));
        return out;
    }

    /** The lots not yet in the shulker (carried or still to fetch). */
    public static List<Order.Lot> notPacked(List<Order.Lot> lots, List<Held> packed) {
        List<Order.Lot> out = new ArrayList<>();
        for (int i : unmatchedPacked(lots, packed)) out.add(lots.get(i));
        return out;
    }

    /** The carried stacks that exactly satisfy a lot that is not yet in the shulker: ready to be put in. */
    public static List<Held> ready(List<Order.Lot> lots, List<Held> packed, List<Held> carried) {
        List<Order.Lot> open = notPacked(lots, packed);
        List<Held> out = new ArrayList<>();
        boolean[] taken = new boolean[open.size()];
        for (Held s : carried) {
            for (int i = 0; i < open.size(); i++) {
                Order.Lot lot = open.get(i);
                if (taken[i] || s.count() != lot.count() || !sameKind(lot, s.key(), s.variant())) continue;
                taken[i] = true;
                out.add(s);
                break;
            }
        }
        return out;
    }

    /** What to fetch next: the first lot not in the shulker and not carried whole, or null when everything is in hand. */
    public static @Nullable Need nextNeed(List<Order.Lot> lots, List<Held> packed, List<Held> carried) {
        return nextNeed(lots, packed, carried, java.util.Set.of());
    }

    /**
     * What to fetch next, leaving out the lots whose key is in {@code skip} (1.8.0: a load with no source is left out, and
     * the rest of the bin is still gathered), or null when everything else is in hand.
     */
    public static @Nullable Need nextNeed(List<Order.Lot> lots, List<Held> packed, List<Held> carried, java.util.Set<String> skip) {
        List<Order.Lot> open = notPacked(lots, packed);
        List<Held> pool = new ArrayList<>(carried);
        List<Order.Lot> missing = new ArrayList<>();
        List<Integer> missingAt = new ArrayList<>();
        List<Integer> lotIndex = new ArrayList<>(unmatchedPacked(lots, packed));
        for (int i = 0; i < open.size(); i++) {
            Order.Lot lot = open.get(i);
            Held exact = null;
            for (Held s : pool) {
                if (s.count() == lot.count() && sameKind(lot, s.key(), s.variant())) {
                    exact = s;
                    break;
                }
            }
            if (exact != null) pool.remove(exact);
            else if (!skip.contains(lot.key())) {
                missing.add(lot);
                missingAt.add(lotIndex.get(i));
            }
        }
        if (missing.isEmpty()) return null;

        Order.Lot lot = missing.get(0);
        Held part = null;
        for (Held s : pool) {
            if (s.count() < lot.count() && sameKind(lot, s.key(), s.variant()) && (part == null || s.count() > part.count())) part = s;
        }
        return new Need(lot, missingAt.get(0), part == null ? -1 : part.slot(), part == null ? 0 : part.count());
    }

    /** Item totals by key. */
    public static Map<String, Integer> totals(List<Held> stacks) {
        Map<String, Integer> out = new HashMap<>();
        for (Held s : stacks) out.merge(s.key(), s.count(), Integer::sum);
        return out;
    }

    /** Item totals by key of the lots. */
    public static Map<String, Integer> lotTotals(List<Order.Lot> lots) {
        Map<String, Integer> out = new HashMap<>();
        for (Order.Lot lot : lots) out.merge(lot.key(), lot.count(), Integer::sum);
        return out;
    }

    /** A finished shulker: it holds exactly the bin's totals, by item key. */
    public static boolean finished(List<Order.Lot> lots, List<Held> contents) {
        return lotTotals(lots).equals(totals(contents));
    }
    /** Lots of a key in {@code skip} that are neither in the shulker nor carried whole: what the bin goes without. */
    public static int skipped(List<Order.Lot> lots, List<Held> packed, List<Held> carried, java.util.Set<String> skip) {
        if (skip.isEmpty()) return 0;
        List<Order.Lot> open = notPacked(lots, packed);
        List<Order.Lot> left = new ArrayList<>();
        for (int i : unmatched(open, carried)) left.add(open.get(i));
        int n = 0;
        for (Order.Lot lot : left) if (skip.contains(lot.key())) n++;
        return n;
    }
}
