package com.porter.qwertestash.orders;

import com.porter.qwertestash.orders.DeliveryBook.Pos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The next row of the delivery storage: where it goes, why it can't, and where the bot can stand to put it there. */
class ChestStackTest {
    /** A flat floor at y 63 with a double chest at y 64 (x 10 and 11, z 20) facing south (+z), and what the test adds. */
    private static final class World implements ChestStack.View {
        final Set<Pos> solid = new HashSet<>();
        final Set<Pos> chests = new HashSet<>(List.of(new Pos(10, 64, 20), new Pos(11, 64, 20)));
        int maxY = 320;
        boolean loaded = true;

        @Override
        public boolean loaded(int x, int z) {
            return loaded;
        }

        @Override
        public boolean air(int x, int y, int z) {
            Pos p = new Pos(x, y, z);
            return !solid.contains(p) && !chests.contains(p) && y > 63;
        }

        @Override
        public boolean conductor(int x, int y, int z) {
            return solid.contains(new Pos(x, y, z));
        }

        @Override
        public String name(int x, int y, int z) {
            return solid.contains(new Pos(x, y, z)) ? "stone" : null;
        }

        @Override
        public boolean standable(int x, int y, int z) {
            Pos p = new Pos(x, y, z);
            if (solid.contains(p) || chests.contains(p) || solid.contains(new Pos(x, y + 1, z)) || chests.contains(new Pos(x, y + 1, z))) return false;
            // The floor is at y 63, and a solid block below also holds a bot
            return y == 64 || solid.contains(new Pos(x, y - 1, z));
        }

        @Override
        public int minY() {
            return -64;
        }

        @Override
        public int maxY() {
            return maxY;
        }
    }

    private static final List<Pos> TOP = List.of(new Pos(10, 64, 20), new Pos(11, 64, 20));
    private static final Pos NEAR = new Pos(10, 64, 22);

    private static ChestStack.Result next(World w, List<Pos> top) {
        return ChestStack.next(top, 0, 1, true, 64, NEAR, w);
    }

    @Test
    void aDoubleChestGetsADoubleChestAboveBothHalves() {
        ChestStack.Result r = next(new World(), TOP);
        assertNull(r.blocked());
        ChestStack.Plan plan = r.plan();
        assertEquals(new Pos(10, 65, 20), plan.first());
        assertEquals(new Pos(11, 65, 20), plan.second());
        assertFalse(plan.firstStands().isEmpty());
        assertFalse(plan.secondStands().isEmpty());
    }

    @Test
    void theFirstChestIsPutUpFromTheFrontOrTheBackSoThatItFacesAlongTheOneBelow() {
        ChestStack.Plan plan = next(new World(), TOP).plan();
        for (ChestStack.Stand s : plan.firstStands()) {
            // Facing +z or -z (the base faces +z): never across it, so the two halves line up
            assertEquals(0, s.facingX());
            assertEquals(1, Math.abs(s.facingZ()));
            // A stand south of the chest looks north, and the chest then faces south: the opposite of the look
            int dz = 20 - s.cell().z();
            assertEquals(dz > 0 ? -1 : 1, s.facingZ(), s.toString());
        }
        // The nearest to the bot (south of the chest, in front) comes first
        assertEquals(1, plan.firstStands().get(0).facingZ());
    }

    @Test
    void theSecondHalfIsClickedOnTheSideOfTheFirstFromOutsideIt() {
        ChestStack.Plan plan = next(new World(), TOP).plan();
        for (Pos s : plan.secondStands()) {
            assertFalse(s.equals(new Pos(11, 65, 20)));
            assertFalse(s.x() <= 10 && s.z() == 20, "behind the first chest: " + s);
        }
        assertTrue(plan.secondStands().contains(new Pos(12, 64, 20)), "the cell beyond the second half sees the face");
    }

    @Test
    void aSingleChestGetsASingleOne() {
        World w = new World();
        w.chests.remove(new Pos(11, 64, 20));
        ChestStack.Plan plan = ChestStack.next(List.of(new Pos(10, 64, 20)), 0, 1, true, 64, NEAR, w).plan();
        assertNotNull(plan);
        assertEquals(new Pos(10, 65, 20), plan.first());
        assertNull(plan.second());
        assertTrue(plan.secondStands().isEmpty());
        // A single with no facing may face any way: stands on every side
        ChestStack.Plan free = ChestStack.next(List.of(new Pos(10, 64, 20)), 0, 0, true, 64, NEAR, w).plan();
        assertTrue(free.firstStands().stream().map(ChestStack.Stand::facingX).anyMatch(x -> x != 0));
    }

    @Test
    void somethingInTheWayStopsIt() {
        World w = new World();
        w.solid.add(new Pos(11, 65, 20));
        ChestStack.Result r = next(w, TOP);
        assertNull(r.plan());
        assertEquals("stone is in the way above the top chest", r.blocked());
    }

    @Test
    void aSolidBlockAboveTheNextRowKeepsTheLidShut() {
        World w = new World();
        w.solid.add(new Pos(10, 66, 20));
        ChestStack.Result r = next(w, TOP);
        assertNull(r.plan());
        assertTrue(r.blocked().contains("would keep its lid from opening"), r.blocked());
    }

    @Test
    void theBuildHeightLimitStopsIt() {
        World w = new World();
        w.maxY = 65;
        ChestStack.Result r = next(w, TOP);
        assertNull(r.plan());
        assertEquals("the build height limit is reached", r.blocked());
    }

    @Test
    void anUnloadedChunkStopsIt() {
        World w = new World();
        w.loaded = false;
        assertEquals("the chunk above the top chest is not loaded", next(w, TOP).blocked());
    }

    @Test
    void fromTheFloorOnlyTheFirstRowAboveTheBaseIsInReach() {
        World w = new World();
        // Two rows stand now (y 64 and 65): a third row at y 66 needs the top of the second (65.875) in sight, above the eyes
        // of a sneaking bot on the floor (65.27)
        w.chests.add(new Pos(10, 65, 20));
        w.chests.add(new Pos(11, 65, 20));
        ChestStack.Result r = ChestStack.next(List.of(new Pos(10, 65, 20), new Pos(11, 65, 20)), 0, 1, true, 64, NEAR, w);
        assertNull(r.plan());
        assertTrue(r.blocked().startsWith("out of reach"), r.blocked());
        assertFalse(r.blocked().matches(".*[0-9].*"), "no numbers in the reason: " + r.blocked());
    }

    @Test
    void aStepNextToTheStackMakesTheNextRowReachable() {
        World w = new World();
        w.chests.add(new Pos(10, 65, 20));
        w.chests.add(new Pos(11, 65, 20));
        // A solid block to stand on beside the stack: the bot stands on it at y 65, eyes at 66.27, above the top at 65.875
        w.solid.add(new Pos(10, 64, 22));
        ChestStack.Result r = ChestStack.next(List.of(new Pos(10, 65, 20), new Pos(11, 65, 20)), 0, 1, true, 64, new Pos(10, 65, 22), w);
        assertNotNull(r.plan(), r.blocked());
        assertTrue(r.plan().firstStands().stream().anyMatch(s -> s.cell().equals(new Pos(10, 65, 22))));
    }

    @Test
    void aimLooksTheRightWay() {
        // From the origin toward +z (south) is yaw 0; toward -x (west) is yaw 90
        assertEquals(0f, ChestStack.yawTo(0, 0, 0, 5), 0.01f);
        assertEquals(90f, ChestStack.yawTo(0, 0, -5, 0), 0.01f);
        assertEquals(-45f, ChestStack.pitchTo(0, 5, 0, 0, 6, 1), 0.5f);
        assertTrue(ChestStack.pitchTo(0, 5, 0, 0, 4, 1) > 0);
    }
}
