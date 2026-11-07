package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Finding an anvil to name order shulkers at without one being set (1.8.1): the anvil set with `stash anvil` first, then
 * one remembered from before, then one standing in the stash area (the nearest the bot can stand next to), then one taken
 * from the stash and put down. When none of that works the shulker goes unnamed with {@link #NO_ANVILS}. Pure: the world
 * comes in through {@link Blocks}.
 */
public final class AnvilFinder {
    private AnvilFinder() {
    }

    /** The naming failure when no anvil can be had at all. The addon says the same. */
    public static final String NO_ANVILS = "no anvils available to rename with";

    /** The most blocks across, and up and down, that one search looks through. */
    public static final int MAX_WIDTH = 128, MAX_HEIGHT = 64;

    public interface Blocks {
        /** The chunk this column is in is loaded. */
        boolean loaded(int x, int z);

        /** The block name there ("anvil", "air", ...), or null when it is not known. */
        @Nullable String name(int x, int y, int z);

        /** The bot can stand with its feet in this cell. */
        boolean standable(int x, int y, int z);
    }

    /** What to do for an anvil now. */
    public enum Way {
        /** The anvil set or remembered is there: use it. */
        USE,
        /** One stands in the stash area: remember it and use it. */
        FOUND,
        /** None stands, one is in the stash: take it and put it down. */
        PLACE,
        /** None at all: the shulker goes unnamed with NO_ANVILS. */
        NONE,
        /** The search for one standing is still going (it is spread over a few ticks): ask again shortly. */
        WAIT
    }

    /** The order of choice: the known anvil, then one standing, then one from the stash. */
    public static Way decide(boolean knownUsable, boolean standing, boolean inStock) {
        if (knownUsable) return Way.USE;
        if (standing) return Way.FOUND;
        if (inStock) return Way.PLACE;
        return Way.NONE;
    }

    public static boolean isAnvil(@Nullable String name) {
        return AnvilSpot.isAnvilName(name);
    }

    /** "minecraft:anvil", "minecraft:chipped_anvil" and "minecraft:damaged_anvil": an anvil as an item, best first. */
    public static final List<String> ITEMS = List.of("minecraft:anvil", "minecraft:chipped_anvil", "minecraft:damaged_anvil");

    /**
     * Where to look, as minX, minY, minZ, maxX, maxY, maxZ: the perimeter when there is one; else the stash's containers
     * widened by the radius (and height) the sorter looks for containers in; else around the bot. At most MAX_WIDTH across
     * and MAX_HEIGHT high, around the bot when bigger.
     */
    public static int[] region(int @Nullable [] perimeter, List<BlockPos> boxes, BlockPos feet, int radius, int height) {
        int[] b;
        if (perimeter != null) b = perimeter.clone();
        else if (!boxes.isEmpty()) {
            b = new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (BlockPos p : boxes) {
                b[0] = Math.min(b[0], p.x() - radius);
                b[1] = Math.min(b[1], p.y() - height);
                b[2] = Math.min(b[2], p.z() - radius);
                b[3] = Math.max(b[3], p.x() + radius);
                b[4] = Math.max(b[4], p.y() + height);
                b[5] = Math.max(b[5], p.z() + radius);
            }
        }
        else b = new int[] {feet.x() - radius, feet.y() - height, feet.z() - radius, feet.x() + radius, feet.y() + height, feet.z() + radius};
        // Up and down only as far as the stash goes, and the height the sorter looks for containers in (1.8.1)
        if (!boxes.isEmpty()) {
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (BlockPos p : boxes) {
                lo = Math.min(lo, p.y());
                hi = Math.max(hi, p.y());
            }
            b[1] = Math.max(b[1], lo - height);
            b[4] = Math.min(b[4], hi + height);
            if (b[4] < b[1]) b[4] = b[1];
        }
        clamp(b, 0, 3, feet.x(), MAX_WIDTH);
        clamp(b, 1, 4, feet.y(), MAX_HEIGHT);
        clamp(b, 2, 5, feet.z(), MAX_WIDTH);
        return b;
    }

    /** Block lookups one tick of a {@link Scan} may make. */
    public static final int LOOKUPS_PER_TICK = 20_000;

    /**
     * A search of a region spread over ticks: chunk by chunk (16 by 16 columns), an unloaded chunk skipped with one look,
     * at most a budget of block lookups each {@link #step}. Bot tick thread only.
     */
    public static final class Scan {
        private final int[] region;
        private final Predicate<String> match;
        private final List<BlockPos> found = new ArrayList<>();
        private int cx, cz;
        private final int cx0, cx1, cz0, cz1;
        private boolean done;
        private long lookups;

        public Scan(int[] region) {
            this(region, AnvilFinder::isAnvil);
        }

        /** A search for blocks of another kind (the crafting table, 1.10.0): {@code match} gets the block name. */
        public Scan(int[] region, Predicate<String> match) {
            this.match = match;
            this.region = region.clone();
            cx0 = Math.floorDiv(region[0], 16);
            cx1 = Math.floorDiv(region[3], 16);
            cz0 = Math.floorDiv(region[2], 16);
            cz1 = Math.floorDiv(region[5], 16);
            cx = cx0;
            cz = cz0;
        }

        public boolean done() {
            return done;
        }

        /** Every anvil found so far (all of them once done). */
        public List<BlockPos> found() {
            return found;
        }

        /** Block lookups made in all. */
        public long lookups() {
            return lookups;
        }

        // The column within the chunk the scan is at, and whether that chunk is loaded
        private int col;
        private boolean chunkLoaded;

        /**
         * Looks through columns, chunk by chunk, until {@code budget} lookups would be passed: never more than that in one
         * step (one look tells an unloaded chunk). @return the lookups made this time
         */
        public int step(Blocks w, int budget) {
            int used = 0;
            int span = region[4] - region[1] + 1;
            while (!done && used < budget) {
                int x0 = Math.max(region[0], cx * 16), x1 = Math.min(region[3], cx * 16 + 15);
                int z0 = Math.max(region[2], cz * 16), z1 = Math.min(region[5], cz * 16 + 15);
                if (col == 0) {
                    used++;
                    chunkLoaded = w.loaded(x0, z0);
                }
                int width = x1 - x0 + 1, cols = width * (z1 - z0 + 1);
                if (!chunkLoaded) {
                    nextChunk();
                    continue;
                }
                if (used + span > budget && used > 0) break;
                int x = x0 + col % width, z = z0 + col / width;
                for (int y = region[1]; y <= region[4]; y++) {
                    used++;
                    if (match.test(w.name(x, y, z))) found.add(new BlockPos(x, y, z));
                }
                if (++col >= cols) nextChunk();
            }
            lookups += used;
            return used;
        }

        private void nextChunk() {
            col = 0;
            if (++cz > cz1) {
                cz = cz0;
                if (++cx > cx1) done = true;
            }
        }
    }

    /**
     * The hotbar slot (0-8) to put an anvil in for placing, or -1: where it is when that is an allowed hotbar slot; else an
     * empty allowed one; else one of the carry slots (what is there is the stash's too, it just swaps places); only last a
     * slot holding the bot's own things (swapped back afterwards).
     */
    public static int hotbarFor(int anvilSlot, Set<Integer> keep, Set<Integer> carry, java.util.function.IntPredicate occupied) {
        if (anvilSlot >= 0 && anvilSlot < 9 && !keep.contains(anvilSlot)) return anvilSlot;
        for (int i = 0; i < 9; i++) if (!keep.contains(i) && !occupied.test(i)) return i;
        for (int i = 0; i < 9; i++) if (!keep.contains(i) && carry.contains(i)) return i;
        for (int i = 0; i < 9; i++) if (!keep.contains(i)) return i;
        return -1;
    }

    private static void clamp(int[] b, int lo, int hi, int centre, int max) {
        if (b[hi] - b[lo] + 1 <= max) return;
        int from = Math.max(b[lo], Math.min(centre - max / 2, b[hi] - max + 1));
        b[lo] = from;
        b[hi] = from + max - 1;
    }

    /** Every anvil block (any damage) standing in the region, in loaded chunks. */
    public static List<BlockPos> scan(int[] region, Blocks w) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = region[0]; x <= region[3]; x++) {
            for (int z = region[2]; z <= region[5]; z++) {
                if (!w.loaded(x, z)) continue;
                for (int y = region[1]; y <= region[4]; y++) if (isAnvil(w.name(x, y, z))) out.add(new BlockPos(x, y, z));
            }
        }
        return out;
    }

    /**
     * The nearest anvil the bot can stand next to (a walkable cell beside it, a block up or down at most) inside the area,
     * not one given up on; null when there is none.
     */
    public static @Nullable BlockPos nearest(BlockPos feet, List<BlockPos> anvils, Blocks w, Predicate<BlockPos> inArea, Set<BlockPos> skip) {
        BlockPos best = null;
        long bestD = Long.MAX_VALUE;
        for (BlockPos a : anvils) {
            if (skip.contains(a) || !inArea.test(a) || !standBeside(a, w)) continue;
            long dx = a.x() - feet.x(), dy = a.y() - feet.y(), dz = a.z() - feet.z();
            long d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = a;
            }
        }
        return best;
    }

    static boolean standBeside(BlockPos a, Blocks w) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            for (int dy = -1; dy <= 1; dy++) if (w.standable(a.x() + d[0], a.y() + dy, a.z() + d[1])) return true;
        }
        return false;
    }
}
