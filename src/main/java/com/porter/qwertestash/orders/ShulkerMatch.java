package com.porter.qwertestash.orders;

import com.porter.qwertestash.manager.ManagerIndex;
import com.porter.qwertestash.stash.Item;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether a shulker (in the inventory, the stash or the delivery container) is the one an order unit delivers. Used for
 * finding the finished shulker in the carry slots, fetching one from the stash, and confirming that one reached the
 * delivery container, so a shulker the order renamed at the anvil is never taken for missing or for someone else's.
 */
public final class ShulkerMatch {
    private ShulkerMatch() {
    }

    /**
     * @param useNamedShulkers whether packed shulkers with a custom name may be delivered (StashConfig.useNamedShulkers)
     */
    public static boolean matches(Unit u, @Nullable Item it, boolean useNamedShulkers) {
        if (it == null || !it.isShulker()) return false;
        // Carries the very name the order asked for: renamed for it at the anvil
        boolean renamed = it.customName && u.name != null && u.name.equals(it.customText);
        switch (u.kind) {
            case Bin -> {
                return (!it.customName || renamed) && !it.contents.isEmpty() && BinTracker.finished(u.lots, heldOf(it.contents));
            }
            case Shulker -> {
                if (!useNamedShulkers && it.customName && !renamed) return false;
                String key = ManagerIndex.shulkerKey(it);
                return u.key.equals(key) || (u.namedKey != null && u.namedKey.equals(key)) || (renamed && u.key.equals(ManagerIndex.shulkerKey(it, true)));
            }
            default -> {
                if ((it.customName && !renamed) || !it.contents.isEmpty()) return false;
                String key = it.indexKey();
                return u.key.equals(key) || (u.namedKey != null && u.namedKey.equals(key)) || (renamed && u.key.equals(it.keyWithoutName()));
            }
        }
    }

    /**
     * The inventory slot (0-35) holding the unit's finished shulker, or -1: the carry slots first, then, with {@code anywhere}
     * (a shulker just back from the anvil, which the server may have put in another slot), every other slot.
     */
    public static int findSlot(Unit u, List<Integer> carried, boolean anywhere, java.util.function.IntFunction<@Nullable Item> at, boolean useNamedShulkers) {
        for (int slot : carried) if (matches(u, at.apply(slot), useNamedShulkers)) return slot;
        if (!anywhere) return -1;
        for (int slot = 0; slot < 36; slot++) if (!carried.contains(slot) && matches(u, at.apply(slot), useNamedShulkers)) return slot;
        return -1;
    }

    /** What a shulker holds, slot by slot, for {@link BinTracker}. */
    public static List<BinTracker.Held> heldOf(List<Item> items) {
        List<BinTracker.Held> out = new ArrayList<>();
        int at = 0;
        for (Item item : items) {
            int fallback = at++;
            if (item == null) continue;
            out.add(new BinTracker.Held(item.slot >= 0 ? item.slot : fallback, item.indexKey(), item.variant, item.count));
        }
        return out;
    }
}
