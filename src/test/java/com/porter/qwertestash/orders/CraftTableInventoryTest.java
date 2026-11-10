package com.porter.qwertestash.orders;

import com.porter.qwertestash.stash.CraftingTableFinder;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A crafting table crafted in the inventory's own 2x2 grid (window slots 1-4): no walking, no window opened or closed, and
 * nothing ever left in the grid or on the cursor, whatever ends the trip.
 */
class CraftTableInventoryTest {
    private static final Recipes R = TestRecipes.table();

    /** The inventory's grid: always "open" unless another module's window is up (foreignFrom..foreignTo ticks). */
    static final class Inventory extends CraftTripTest.FakeTable {
        int ticks, foreignFrom = -1, foreignTo = -1;

        Inventory(java.util.List<Recipes.Recipe> recipes) {
            super(recipes);
            width = 2;
            open = true;
        }

        @Override
        Inventory carry(String id, int n) {
            super.carry(id, n);
            return this;
        }

        @Override
        void tick() {
            ticks++;
            super.tick();
        }

        private boolean foreign() {
            return foreignFrom >= 0 && ticks >= foreignFrom && ticks < foreignTo;
        }

        @Override
        public boolean tableOpen() {
            return !foreign();
        }

        @Override
        public boolean place(int gridSlot, String item, int goal) {
            return !foreign() && super.place(gridSlot, item, goal);
        }

        @Override
        public int take(String item, int count) {
            return foreign() ? -1 : super.take(item, count);
        }

        @Override
        public int retrieve(int gridSlot) {
            return foreign() ? -1 : super.retrieve(gridSlot);
        }

        @Override
        public boolean close() {
            // There is no window to close: the grid keeps whatever is in it
            if (gridTotal() > 0) closedWithItems = true;
            return true;
        }
    }

    static CraftPlanner.Plan table(String id, long n) {
        CraftPlanner.Plan p = CraftPlanner.plan(R, CraftingTableFinder.TABLE_ID, 1, CraftPlannerTest.pool(id, n), CraftingTableFinder.limits(Set.of()));
        assertTrue(p.ok(), p.refusal());
        return p;
    }

    static Inventory grid(CraftPlanner.Plan p) {
        return new Inventory(p.steps().stream().map(CraftPlanner.Step::recipe).toList());
    }

    private static CraftTrip.Outcome run(CraftTrip t, CraftTripTest.FakeTable f, int budget) {
        return CraftTripTest.run(t, f, budget);
    }

    @Test
    void aTableFromFourPlanks() {
        CraftPlanner.Plan p = table("minecraft:oak_planks", 4);
        Inventory f = grid(p).carry("minecraft:oak_planks", 4);
        CraftTrip.Outcome o = run(new CraftTrip(f, p, CraftTrip.INVENTORY_GRID), f, 1000);
        assertNull(o.failure());
        assertEquals(1, o.made());
        assertEquals(1, f.inv.get("minecraft:crafting_table"));
        assertEquals(0, f.inv.getOrDefault("minecraft:oak_planks", 0));
        assertEquals(0, f.walks);
        assertEquals(0, f.opensTried);
        assertEquals(0, f.gridTotal());
        assertFalse(f.closedWithItems);
        // One craft: the result taken with one left click, nothing shift-clicked
        assertEquals(1, f.takes);
    }

    @Test
    void aTableFromLogsMakesPlanksFirst() {
        CraftPlanner.Plan p = table("minecraft:birch_log", 1);
        Inventory f = grid(p).carry("minecraft:birch_log", 1);
        CraftTrip.Outcome o = run(new CraftTrip(f, p, CraftTrip.INVENTORY_GRID), f, 2000);
        assertNull(o.failure());
        assertEquals(1, f.inv.get("minecraft:crafting_table"));
        assertEquals(0, f.inv.getOrDefault("minecraft:birch_log", 0));
        assertEquals(0, f.inv.getOrDefault("minecraft:birch_planks", 0));
        assertEquals(0, f.gridTotal());
        assertEquals(2, f.takes);
    }

    @Test
    void whatIsNotNeededStaysAndTheGridIsEmpty() {
        CraftPlanner.Plan p = table("minecraft:oak_log", 3);
        Inventory f = grid(p).carry("minecraft:oak_log", 3);
        CraftTrip.Outcome o = run(new CraftTrip(f, p, CraftTrip.INVENTORY_GRID), f, 2000);
        assertNull(o.failure());
        assertEquals(2, f.inv.get("minecraft:oak_log"));
        assertEquals(0, f.gridTotal());
    }

    @Test
    void anotherWindowOpeningMidCraftIsWaitedForAndTheGridDrained() {
        CraftPlanner.Plan p = table("minecraft:oak_planks", 4);
        Inventory f = grid(p).carry("minecraft:oak_planks", 4);
        f.foreignFrom = 12;
        f.foreignTo = 200;
        CraftTrip.Outcome o = run(new CraftTrip(f, p, CraftTrip.INVENTORY_GRID), f, 3000);
        assertEquals(0, f.gridTotal(), "nothing left in the grid");
        assertFalse(f.closedWithItems);
        assertFalse(o.stuck());
        // Either it finished once the inventory was back, or it was cut short with everything back in the inventory
        int planks = f.inv.getOrDefault("minecraft:oak_planks", 0), tables = f.inv.getOrDefault("minecraft:crafting_table", 0);
        assertTrue(tables == 1 || planks == 4, "tables " + tables + ", planks " + planks);
    }

    @Test
    void anotherWindowThatNeverClosesLeavesTheGridHeldNotLost() {
        CraftPlanner.Plan p = table("minecraft:oak_planks", 4);
        Inventory f = grid(p).carry("minecraft:oak_planks", 4);
        f.foreignFrom = 12;
        f.foreignTo = Integer.MAX_VALUE;
        CraftTrip t = new CraftTrip(f, p, CraftTrip.INVENTORY_GRID);
        CraftTrip.Outcome o = run(t, f, CraftTrip.RETRIEVE_TICKS + CraftTrip.OPEN_TICKS + 2000);
        // The trip ends reporting that something is still in the grid (so the sorter keeps working at emptying it), and the
        // items are in the grid, not gone
        assertTrue(o.stuck() || f.gridTotal() == 0);
        assertEquals(4, f.gridTotal() + f.inv.getOrDefault("minecraft:oak_planks", 0) + f.inv.getOrDefault("minecraft:crafting_table", 0) * 4);
    }

    @Test
    void anAbortEmptiesTheGridAtOnce() {
        CraftPlanner.Plan p = table("minecraft:oak_planks", 4);
        Inventory f = grid(p).carry("minecraft:oak_planks", 4);
        CraftTrip t = new CraftTrip(f, p, CraftTrip.INVENTORY_GRID);
        for (int i = 0; i < 100 && f.gridTotal() == 0; i++) {
            f.tick();
            t.tick();
        }
        assertTrue(f.gridTotal() > 0, "the grid was filled");
        t.abort("the bot respawned");
        CraftTrip.Outcome o = run(t, f, 1000);
        assertTrue(o.retry());
        assertEquals(0, f.gridTotal());
        assertEquals(4, f.inv.getOrDefault("minecraft:oak_planks", 0) + 4 * f.inv.getOrDefault("minecraft:crafting_table", 0));
        assertFalse(f.closedWithItems);
    }

    @Test
    void withNoRoomTheGridIsKeptAndTheTripSaysSo() {
        CraftPlanner.Plan p = table("minecraft:oak_planks", 4);
        Inventory f = grid(p).carry("minecraft:oak_planks", 4);
        f.gives = false;
        f.full = true;
        CraftTrip t = new CraftTrip(f, p, CraftTrip.INVENTORY_GRID);
        CraftTrip.Outcome o = run(t, f, CraftTrip.RETRIEVE_TICKS + CraftTrip.RESULT_TICKS + 1000);
        assertTrue(o.stuck());
        assertTrue(f.gridTotal() > 0);
        assertFalse(f.closedWithItems);
    }

    @Test
    void theGridSlotsFollowTheTwoWideLayout() {
        CraftPlanner.Plan p = table("minecraft:oak_planks", 4);
        Recipes.Recipe table = p.steps().get(0).recipe();
        assertEquals("[1, 2, 3, 4]", java.util.Arrays.toString(CraftGrid.slots(table, 2)));
        assertEquals("[1, 2, 4, 5]", java.util.Arrays.toString(CraftGrid.slots(table)));
    }
}
