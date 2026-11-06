package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.8.0: no room beside the bot is no longer a pause: the nearest good spot within 5 blocks is walked to. */
class PlaceSearchTest {
    /** A flat floor at y 63 (the bot stands at y 64), with walls and blocks set by the test. */
    static final class Flat implements PlaceSearch.View {
        final Set<BlockPos> solid = new HashSet<>(), guarded = new HashSet<>(), unsafe = new HashSet<>();
        int[] area;
        /** Everything two or more blocks off the corridor's line (z) is rock. */
        boolean rock;

        Flat wall(int x, int z) {
            solid.add(new BlockPos(x, 64, z));
            for (int y = 65; y <= 67; y++) solid.add(new BlockPos(x, y, z));
            return this;
        }

        boolean isSolid(int x, int y, int z) {
            return y <= 63 || (rock && Math.abs(z) >= 2 && y <= 66) || solid.contains(new BlockPos(x, y, z));
        }

        @Override
        public boolean open(int x, int y, int z) {
            return !isSolid(x, y, z);
        }

        @Override
        public boolean floor(int x, int y, int z) {
            return isSolid(x, y, z);
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return isSolid(x, y - 1, z) && !isSolid(x, y, z) && !isSolid(x, y + 1, z);
        }

        @Override
        public @Nullable String unsafe(BlockPos spot) {
            return unsafe.contains(spot) ? "there is a hopper next to it" : null;
        }

        @Override
        public boolean inArea(BlockPos spot) {
            return area == null || (spot.x() >= area[0] && spot.x() <= area[1] && spot.z() >= area[2] && spot.z() <= area[3]);
        }

        @Override
        public boolean guarded(BlockPos spot) {
            return guarded.contains(spot);
        }
    }

    private static final BlockPos FEET = new BlockPos(0, 64, 0);

    /** A corridor running east from the bot, walled on both sides, with every cell along it blocked until x = 3. */
    private static Flat corridor() {
        Flat f = new Flat();
        f.rock = true;
        for (int x = -6; x <= 8; x++) {
            f.wall(x, -1);
            f.wall(x, 1);
        }
        f.wall(-1, 0);
        // The corridor is one wide: a shulker in it would block the way, the sorter's rules say no until it widens at x 3
        for (int x = 0; x <= 8; x++) f.unsafe.add(new BlockPos(x, 64, 0));
        f.solid.remove(new BlockPos(3, 64, 1));
        f.solid.remove(new BlockPos(3, 65, 1));
        f.solid.remove(new BlockPos(3, 66, 1));
        return f;
    }

    @Test
    void blockedAllAroundFindsASpotThreeBlocksAway() {
        Flat f = corridor();
        PlaceSearch.Result r = PlaceSearch.search(FEET, f, Set.of());
        assertNotNull(r.found(), r.why());
        assertEquals(new BlockPos(3, 64, 1), r.found().spot());
        assertEquals(new BlockPos(3, 64, 0), r.found().stand());
        assertEquals(3, r.found().steps());
    }

    @Test
    void theNearestSpotWins() {
        Flat f = new Flat();
        PlaceSearch.Result r = PlaceSearch.search(FEET, f, Set.of());
        assertEquals(0, r.found().steps(), "room right beside the bot");
    }

    @Test
    void nothingWithinFiveBlocksPausesWithTheReason() {
        Flat f = new Flat();
        // A one block pit: walls all round
        f.wall(1, 0).wall(-1, 0).wall(0, 1).wall(0, -1);
        PlaceSearch.Result r = PlaceSearch.search(FEET, f, Set.of());
        assertNull(r.found());
        // Spots exist out there, the bot can't get to them
        assertEquals(PlaceSearch.UNREACHABLE, r.why());

        Flat tight = corridor();
        tight.unsafe.add(new BlockPos(3, 64, 0));
        tight.solid.add(new BlockPos(3, 64, 1));
        for (int x = 3; x <= 8; x++) tight.unsafe.add(new BlockPos(x, 64, 0));
        PlaceSearch.Result none = PlaceSearch.search(FEET, tight, Set.of());
        assertNull(none.found());
        assertTrue(!none.why().isEmpty());
    }

    @Test
    void thePerimeterIsRespected() {
        Flat f = corridor();
        f.area = new int[] {0, 2, -1, 1};
        PlaceSearch.Result r = PlaceSearch.search(FEET, f, Set.of());
        assertNull(r.found());
        assertEquals(PlaceSearch.OUTSIDE, r.why());
    }

    @Test
    void guardedAndFailedSpotsAreSkipped() {
        Flat f = corridor();
        f.guarded.add(new BlockPos(3, 64, 1));
        f.solid.remove(new BlockPos(4, 64, 1));
        f.solid.remove(new BlockPos(4, 65, 1));
        f.solid.remove(new BlockPos(4, 66, 1));
        assertEquals(new BlockPos(4, 64, 1), PlaceSearch.search(FEET, f, Set.of()).found().spot());
        PlaceSearch.Result r = PlaceSearch.search(FEET, f, Set.of(new BlockPos(4, 64, 1)));
        assertNull(r.found());
    }

    @Test
    void aSpotNeedsAFloorAndRoomAboveToOpen() {
        Flat f = corridor();
        f.solid.add(new BlockPos(3, 65, 1));
        f.solid.remove(new BlockPos(3, 64, 1));
        PlaceSearch.Result r = PlaceSearch.search(FEET, f, Set.of());
        assertNull(r.found(), "the lid can't open under a block");
    }

    @Test
    void aResumeDuringTheWalkSearchesAgainFromWhereTheBotIs() {
        Flat f = corridor();
        PlaceSearch.Found first = PlaceSearch.search(FEET, f, Set.of()).found();
        // Halfway there the bot gives way (or goes offline and comes back): the search is made again from where it stands,
        // and the spot it was walking to is still the one
        PlaceSearch.Found again = PlaceSearch.search(new BlockPos(2, 64, 0), f, Set.of()).found();
        assertEquals(first.spot(), again.spot());
        assertEquals(1, again.steps());
        // A walk that failed: the spot is skipped next time
        assertNull(PlaceSearch.search(new BlockPos(2, 64, 0), f, Set.of(first.spot())).found());
    }
}
