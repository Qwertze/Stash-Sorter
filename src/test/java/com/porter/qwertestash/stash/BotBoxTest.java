package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.21.0: no block is put down inside the bot's own hitbox. */
class BotBoxTest {
    @Test
    void centredBotBlocksOnlyItsOwnCellsAndTheOneAbove() {
        assertTrue(BotBox.overlapsCell(10.5, 64, 10.5, 10, 64, 10));
        assertTrue(BotBox.overlapsCell(10.5, 64, 10.5, 10, 65, 10));
        assertFalse(BotBox.overlapsCell(10.5, 64, 10.5, 10, 66, 10), "above the head");
        assertFalse(BotBox.overlapsCell(10.5, 64, 10.5, 10, 63, 10), "the floor");
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            assertFalse(BotBox.overlapsCell(10.5, 64, 10.5, 10 + d[0], 64, 10 + d[1]), "neighbours of a centred bot are free");
        }
    }

    @Test
    void offCentreBotReachesIntoTheNeighbour() {
        assertTrue(BotBox.overlapsCell(10.85, 64, 10.5, 11, 64, 10), "box reaches x 11.17");
        assertTrue(BotBox.overlapsCell(10.15, 64, 10.5, 9, 64, 10), "box reaches x 9.83");
        assertTrue(BotBox.overlapsCell(10.5, 64, 10.85, 10, 64, 11));
        assertFalse(BotBox.overlapsCell(10.67, 64, 10.5, 11, 64, 10), "0.01 short of the edge");
    }

    @Test
    void feetAboveTheFloorStillCountTheCellAtTheFeet() {
        assertTrue(BotBox.overlapsCell(10.5, 64.25, 10.5, 10, 64, 10));
        assertTrue(BotBox.overlapsCell(10.5, 64.25, 10.5, 10, 65, 10));
        assertTrue(BotBox.overlapsCell(10.5, 64.25, 10.5, 10, 66, 10), "the head reaches y 66.05");
        assertFalse(BotBox.overlapsCell(10.5, 64.25, 10.5, 10, 67, 10));
        assertTrue(BotBox.overlapsCell(10.5, 64.5, 10.5, 10, 66, 10), "on a slab the head reaches y 66.3");
        assertFalse(BotBox.overlapsCell(10.5, 64.25, 10.5, 10, 63, 10));
    }

    @Test
    void placeSearchNeverPicksACellTheRealBoxOverlaps() {
        PlaceSearchTest.Flat f = new PlaceSearchTest.Flat();
        BlockPos feet = new BlockPos(0, 64, 0);
        PlaceSearch.Body body = new PlaceSearch.Body(0.85, 64.25, 0.5);
        PlaceSearch.Result r = PlaceSearch.search(feet, body, f, Set.of());
        assertNotNull(r.found());
        BlockPos spot = r.found().spot();
        assertFalse(BotBox.overlapsCell(0.85, 64.25, 0.5, spot.x(), spot.y(), spot.z()), "picked " + spot);
        assertNotEquals(new BlockPos(1, 64, 0), spot);
    }

    @Test
    void withOnlyAnOverlappingCellFreeBesideTheBotTheSearchWalksOn() {
        PlaceSearchTest.Flat f = new PlaceSearchTest.Flat();
        f.wall(-1, 0).wall(0, 1).wall(0, -1);
        BlockPos feet = new BlockPos(0, 64, 0);
        PlaceSearch.Result r = PlaceSearch.search(feet, new PlaceSearch.Body(0.85, 64.25, 0.5), f, Set.of());
        assertNotNull(r.found());
        assertNotEquals(new BlockPos(1, 64, 0), r.found().spot());
        assertTrue(r.found().steps() >= 1, "walks to another cell first");
    }
}
