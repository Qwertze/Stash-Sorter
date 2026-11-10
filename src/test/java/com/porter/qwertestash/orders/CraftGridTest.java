package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The crafting table window: which slot each cell is, and what goes where. */
class CraftGridTest {
    private final Recipes r = TestRecipes.table();

    @Test
    void theTableWindowPutsTheInventoryAfterTheGrid() {
        // Result 0, grid 1-9, main inventory 10-36, hotbar 37-45
        assertEquals(10, CraftGrid.windowSlot(9));
        assertEquals(36, CraftGrid.windowSlot(35));
        assertEquals(37, CraftGrid.windowSlot(0));
        assertEquals(45, CraftGrid.windowSlot(8));
    }

    @Test
    void cellsGoToTheirGridSlots() {
        // A shulker box is one wide, put in the left column: slots 1, 4, 7
        assertArrayEquals(new int[] {1, 4, 7}, CraftGrid.slots(r.forResult("minecraft:shulker_box").get(0)));
        // A chest is a ring: everything but the middle
        assertArrayEquals(new int[] {1, 2, 3, 4, 6, 7, 8, 9}, CraftGrid.slots(r.forResult("minecraft:chest").get(0)));
        // Planks are shapeless: the first slot
        assertArrayEquals(new int[] {1}, CraftGrid.slots(r.forResult("minecraft:oak_planks").get(0)));
    }

    @Test
    void aBatchFillsEachSlotWithOneKind() {
        Recipes.Recipe box = r.forResult("minecraft:shulker_box").get(0);
        CraftGrid.Batch b = CraftGrid.batch(box, Map.of("minecraft:shulker_shell", 4, "minecraft:chest", 2), 2, 64);
        assertEquals(2, b.fills());
        assertEquals("minecraft:shulker_shell", b.item()[1]);
        assertEquals("minecraft:chest", b.item()[4]);
        assertEquals("minecraft:shulker_shell", b.item()[7]);
        assertNull(b.item()[2]);
        assertEquals(3, b.cells());
        assertEquals(Map.of("minecraft:shulker_shell", 4, "minecraft:chest", 2), b.uses());
        // Too few for two: one at a time
        assertEquals(1, CraftGrid.batch(box, Map.of("minecraft:shulker_shell", 3, "minecraft:chest", 2), 2, 64).fills());
        assertNull(CraftGrid.batch(box, Map.of("minecraft:shulker_shell", 1, "minecraft:chest", 2), 1, 64));
    }

    @Test
    void mixedPlanksGoInWholeSlots() {
        Recipes.Recipe chest = r.forResult("minecraft:chest").get(0);
        CraftGrid.Batch b = CraftGrid.batch(chest, Map.of("minecraft:oak_planks", 5, "minecraft:birch_planks", 3), 1, 64);
        assertEquals(1, b.fills());
        assertEquals(Map.of("minecraft:oak_planks", 5, "minecraft:birch_planks", 3), b.uses());
        // Two chests' worth of planks of one kind: two fills at once, 2 in each slot
        CraftGrid.Batch two = CraftGrid.batch(chest, Map.of("minecraft:oak_planks", 16), 2, 64);
        assertEquals(2, two.fills());
        // No more than a stack in one slot
        assertEquals(64, CraftGrid.batch(r.forResult("minecraft:oak_planks").get(0), Map.of("minecraft:oak_log", 200), 100, 64).fills());
    }
}
