package com.porter.qwertestash.module;

import com.porter.qwertestash.stash.PlaceSearch;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.21.0: the recovery rules of a pinned bot. */
class PinPolicyTest {
    /** A small world: a flat stone floor at y 63, everything else air, blocks set by the test. */
    static final class World implements PinPolicy.Ground {
        final Map<String, String> blocks = new HashMap<>();

        static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        World() {
            for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) blocks.put(key(x, 63, z), "stone");
        }

        World set(int x, int y, int z, String name) {
            blocks.put(key(x, y, z), name);
            return this;
        }

        World remove(int x, int y, int z) {
            blocks.remove(key(x, y, z));
            return this;
        }

        @Override
        public String name(int x, int y, int z) {
            return blocks.getOrDefault(key(x, y, z), "air");
        }

        @Override
        public boolean solid(int x, int y, int z) {
            String n = name(x, y, z);
            return !n.equals("air") && !PinPolicy.HAZARDS.contains(n);
        }
    }

    // The bot's feet cell is (0, 64, 0)

    @Test
    void solidFlatFloorIsSafe() {
        assertTrue(PinPolicy.safeStep(1, 64, 0, new World()));
    }

    @Test
    void lavaWaterFireAndMagmaAreNeverSteppedInOrOnto() {
        for (String bad : new String[] {"lava", "water", "fire", "soul_fire"}) {
            assertFalse(PinPolicy.safeStep(1, 64, 0, new World().set(1, 64, 0, bad)), bad + " in the cell");
        }
        assertFalse(PinPolicy.safeStep(1, 64, 0, new World().set(1, 65, 0, "lava")), "lava at head height");
        assertFalse(PinPolicy.safeStep(1, 64, 0, new World().set(1, 63, 0, "magma_block")), "magma floor");
        assertFalse(PinPolicy.safeStep(1, 64, 0, new World().set(1, 63, 0, "lava")), "lava floor");
        assertFalse(PinPolicy.safeStep(1, 64, 0, new World().set(1, 63, 0, "cactus")));
    }

    @Test
    void aWallOrAnUnloadedCellIsNotAStep() {
        assertFalse(PinPolicy.safeStep(1, 64, 0, new World().set(1, 64, 0, "stone")));
        assertFalse(PinPolicy.safeStep(1, 64, 0, new World().set(1, 65, 0, "stone")), "no head room");
        assertFalse(PinPolicy.safeStep(1, 64, 0, new World().set(1, 64, 0, "unloaded")));
    }

    @Test
    void aDropOfOneIsFineMoreIsNot() {
        // Floor gone at 63 but there is one at 62: a drop of one
        assertTrue(PinPolicy.safeStep(1, 64, 0, new World().remove(1, 63, 0).set(1, 62, 0, "stone")));
        // Two down: no
        assertFalse(PinPolicy.safeStep(1, 64, 0, new World().remove(1, 63, 0).set(1, 61, 0, "stone")));
        // Nothing at all (a hole)
        assertFalse(PinPolicy.safeStep(1, 64, 0, new World().remove(1, 63, 0)));
        // A drop of one onto lava
        assertFalse(PinPolicy.safeStep(1, 64, 0, new World().remove(1, 63, 0).set(1, 62, 0, "lava")));
    }

    @Test
    void theFourDirectionsAreTriedInTurnAndNoneSafeMeansWait() {
        // Preferred east, but east is lava: the next nearest to the preference (a side) is taken, never east
        World w = new World().set(1, 64, 0, "lava").set(2, 64, 0, "lava");
        int[] d = PinPolicy.chooseStep(0, 64, 0, 1, 0.1, w);
        assertNotNull(d);
        assertFalse(d[0] == 1 && d[1] == 0);
        assertArrayEquals(new int[] {0, 1}, d, "the side the preference leans to");
        // Safe cell next to a chasm two cells on: not a step (the second cell drops too far)
        World chasm = new World();
        for (int x = 2; x <= 5; x++) for (int z = -5; z <= 5; z++) chasm.remove(x, 63, z);
        int[] d2 = PinPolicy.chooseStep(0, 64, 0, 1, 0, chasm);
        assertNotNull(d2);
        assertFalse(d2[0] == 1, "east leads off the edge");
        // All four unsafe: null
        World boxed = new World().set(1, 64, 0, "lava").set(-1, 64, 0, "lava").set(0, 64, 1, "fire").set(0, 64, -1, "water");
        assertNull(PinPolicy.chooseStep(0, 64, 0, 1, 0, boxed));
        // A wall right behind the next cell stops the body: fine
        assertNotNull(PinPolicy.chooseStep(0, 64, 0, 1, 0, new World().set(2, 64, 0, "stone")));
    }

    @Test
    void aBotThatIsMovingWhileCorrectedIsOnlyPaused() {
        assertEquals(PinPolicy.Move.Wait, PinPolicy.next(0, false, 1.5, true), "moving: no step-out");
        assertEquals(PinPolicy.Move.Wait, PinPolicy.next(0, true, 1.5, true), "moving: no break either");
        assertEquals(PinPolicy.Move.Wait, PinPolicy.next(2, false, 0.21, true));
        assertEquals(PinPolicy.Move.StepOut, PinPolicy.next(0, false, 0.2, true), "held in place with a job");
    }

    @Test
    void withNoJobItOnlyPauses() {
        assertEquals(PinPolicy.Move.Wait, PinPolicy.next(0, false, 0, false));
        assertEquals(PinPolicy.Move.Wait, PinPolicy.next(4, false, 0, false));
    }

    @Test
    void ownMinableBlockIsBrokenFirstThenItStepsOutAndRests() {
        assertEquals(PinPolicy.Move.Break, PinPolicy.next(0, true, 0, true));
        assertEquals(PinPolicy.Move.Break, PinPolicy.next(PinPolicy.BREAK_TRIES - 1, true, 0, true));
        assertEquals(PinPolicy.Move.StepOut, PinPolicy.next(4, true, 0, true));
        assertEquals(PinPolicy.Move.Wait, PinPolicy.next(5, true, 0, true));
        // Idle with a block of ours in the way: breaking it is still fine, stepping is not
        assertEquals(PinPolicy.Move.Break, PinPolicy.next(0, true, 0, false));
        assertEquals(PinPolicy.Move.Wait, PinPolicy.next(4, true, 0, false));
    }

    @Test
    void onlyAnvilsAndTablesAreMinableAndNeverInTheStashOrContainers() {
        for (String n : new String[] {"anvil", "chipped_anvil", "damaged_anvil", "crafting_table"}) {
            assertTrue(PinPolicy.minable(n, false), n);
            assertFalse(PinPolicy.minable(n, true), n + " inside the stash or a container");
        }
        for (String n : new String[] {"chest", "trapped_chest", "barrel", "ender_chest", "shulker_box", "red_shulker_box", "hopper", "stone", "air"}) {
            assertFalse(PinPolicy.minable(n, false), n);
        }
    }

    @Test
    void theScanAfterARestartFindsOnlyAnAnvilOrTableOutsideTheStash() {
        // A chest, a shulker and an anvil inside the stash area: none
        assertNull(PinPolicy.firstMinable(List.of(
            new PinPolicy.Cell(0, 64, 0, "chest", false),
            new PinPolicy.Cell(0, 65, 0, "blue_shulker_box", false),
            new PinPolicy.Cell(1, 64, 0, "anvil", true))));
        // The anvil outside it: found, even behind a chest
        PinPolicy.Cell hit = PinPolicy.firstMinable(List.of(
            new PinPolicy.Cell(0, 64, 0, "barrel", false),
            new PinPolicy.Cell(1, 64, 0, "damaged_anvil", false)));
        assertNotNull(hit);
        assertEquals(1, hit.x());
        assertNull(PinPolicy.firstMinable(List.of()));
    }

    @Test
    void pinnedAlertsComeAfterAMinuteThenEveryFiveMinutesAndOnceAtThirty() {
        long min = 60_000;
        assertEquals(PinPolicy.Alert.None, PinPolicy.alert(30_000, -1, false));
        assertEquals(PinPolicy.Alert.Pinned, PinPolicy.alert(min, -1, false));
        assertEquals(PinPolicy.Alert.None, PinPolicy.alert(5 * min, min, false), "4 minutes on");
        assertEquals(PinPolicy.Alert.Pinned, PinPolicy.alert(6 * min, min, false));
        assertEquals(PinPolicy.Alert.NeedsHelp, PinPolicy.alert(30 * min, 26 * min, false));
        // After that, the 5-minute repeats go on and the help alert does not come again
        assertEquals(PinPolicy.Alert.None, PinPolicy.alert(32 * min, 30 * min, true));
        assertEquals(PinPolicy.Alert.Pinned, PinPolicy.alert(35 * min, 30 * min, true));
    }

    @Test
    void aPlaceInTheBotsOwnBodyIsRefusedWithItsOwnReason() {
        // Off the cell's centre, above the floor: the cell east of the bot is in its box
        assertEquals(PlaceSearch.BODY, PinPolicy.placeRefusal(0.85, 64.25, 0.5, 1, 64, 0));
        assertEquals(PlaceSearch.BODY, PinPolicy.placeRefusal(0.5, 64, 0.5, 0, 65, 0));
        assertNull(PinPolicy.placeRefusal(0.5, 64, 0.5, 1, 64, 0));
        assertNull(PinPolicy.placeRefusal(0.5, 64, 0.5, 0, 63, 0), "the floor");
        assertFalse(PlaceSearch.BODY.equals(PlaceSearch.ALL_BLOCKED));
    }
}
