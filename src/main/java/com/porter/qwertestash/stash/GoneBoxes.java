package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import com.porter.qwertestash.stash.Stash.Box;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Containers a player broke. Until 1.7.1 a saved container was only forgotten when the sorter started (when its chunk is
 * rarely loaded yet) or when a perimeter was explored, so without a perimeter a broken one stayed for ever: its read failed,
 * was tried again every minute, and the bot walked to the air there over and over. Plain logic over what the world shows,
 * so it can be tested without one.
 */
public final class GoneBoxes {
    private GoneBoxes() {
    }

    /**
     * Known to be gone: every block of it is in a loaded chunk and none of them is a chest or barrel. A chunk that isn't
     * loaded says nothing, so a container there is kept.
     */
    public static boolean gone(Box box, Predicate<BlockPos> loaded, Predicate<BlockPos> container) {
        List<BlockPos> parts = new ArrayList<>();
        if (box.pos != null) parts.add(box.pos);
        if (box.parts != null) for (BlockPos p : box.parts) if (p != null && !parts.contains(p)) parts.add(p);
        if (parts.isEmpty()) return false;
        for (BlockPos p : parts) if (!loaded.test(p) || container.test(p)) return false;
        return true;
    }

    /**
     * The containers to forget: gone, and not the delivery container (its own checks say when that is gone). None at all
     * when none of the containers in loaded chunks is still there and three or more look gone: that is the bot standing in
     * another dimension at the same coordinates, not a player breaking the whole stash.
     */
    public static List<Box> toDrop(List<Box> boxes, Predicate<BlockPos> loaded, Predicate<BlockPos> container) {
        List<Box> gone = new ArrayList<>();
        int stillThere = 0;
        for (Box box : boxes) {
            if (box.delivery) continue;
            if (gone(box, loaded, container)) gone.add(box);
            else if (box.pos != null && loaded.test(box.pos) && container.test(box.pos)) stillThere++;
        }
        if (suspicious(gone.size(), stillThere)) return List.of();
        return gone;
    }

    /**
     * A double chest whose saved block (its {@code pos}) was broken while the other half still stands: that half, to build
     * the box again from. Null when the box isn't such a half (every block must be loaded to tell).
     */
    public static @Nullable BlockPos remainingHalf(Box box, Predicate<BlockPos> loaded, Predicate<BlockPos> container) {
        if (box.pos == null || box.parts == null || box.parts.size() < 2) return null;
        if (!loaded.test(box.pos) || container.test(box.pos)) return null;
        BlockPos left = null;
        for (BlockPos p : box.parts) {
            if (p == null || p.equals(box.pos)) continue;
            if (!loaded.test(p)) return null;
            if (container.test(p)) left = p;
        }
        return left;
    }

    /** Containers of the stash (not the delivery container) seen standing where they were saved. */
    public static int stillThere(List<Box> boxes, Predicate<BlockPos> loaded, Predicate<BlockPos> container) {
        int n = 0;
        for (Box box : boxes) if (!box.delivery && box.pos != null && loaded.test(box.pos) && container.test(box.pos)) n++;
        return n;
    }

    /** Every container in view looks gone, and more than a couple: not believed. */
    public static boolean suspicious(int gone, int stillThere) {
        return gone >= 3 && stillThere == 0;
    }
}
