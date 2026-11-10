package com.porter.qwertestash.orders;

import com.porter.qwertestash.stash.Item;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Which carried shulker a kit that is to be emptied back is (KIT-CONTRACT v2, revision 4, step 4). Only an unnamed shulker qualifies: a named one is
 * a kit the flow stored as an incomplete copy (or a finished kit), never one to take apart. Among the unnamed ones, a shulker holding exactly the
 * unit's loads comes first, then one holding only things the unit's loads cover. Pure; tick thread.
 */
public final class KitEmpty {
    private KitEmpty() {
    }

    /** @return the inventory slot of the shulker, or -1 */
    public static int pick(Unit unit, List<Integer> slots, IntFunction<@Nullable Item> at, Set<Integer> excluded) {
        int covered = -1;
        for (int slot : slots) {
            if (excluded.contains(slot)) continue;
            Item it = at.apply(slot);
            if (it == null || !it.isShulker() || it.customName || it.contents.isEmpty()) continue;
            boolean owns = true;
            List<BinTracker.Held> held = ShulkerMatch.heldOf(it.contents);
            for (Item in : it.contents) owns &= in != null && BinTracker.owns(unit.lots, in.indexKey(), in.variant, in.count);
            if (!owns) continue;
            if (BinTracker.finished(unit.lots, held)) return slot;
            if (covered < 0) covered = slot;
        }
        return covered;
    }
}
