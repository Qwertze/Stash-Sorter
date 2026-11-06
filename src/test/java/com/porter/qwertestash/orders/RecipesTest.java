package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recipe table shipped in the plugin (1.21.4): read from the jar's resources, the recipes phase 1 uses are in it. */
class RecipesTest {
    private final Recipes r = Recipes.shipped();

    @Test
    void theShippedTableLoads() {
        assertEquals("1.21.4", r.version());
        assertTrue(r.size() > 800, "recipes: " + r.size());
    }

    @Test
    void aShulkerBoxIsOneWideAndThreeHigh() {
        List<Recipes.Recipe> box = r.forResult("minecraft:shulker_box");
        assertEquals(1, box.size());
        Recipes.Recipe b = box.get(0);
        assertTrue(b.shaped());
        assertEquals(1, b.w());
        assertEquals(3, b.h());
        assertEquals(List.of("minecraft:shulker_shell"), b.cells().get(0));
        assertEquals(List.of("minecraft:chest"), b.cells().get(1));
        assertEquals(List.of("minecraft:shulker_shell"), b.cells().get(2));
        assertEquals(1, b.count());
        assertTrue(b.needsTable());
        // Ids may be given bare
        assertEquals(box, r.forResult("shulker_box"));
    }

    @Test
    void aChestTakesAnyPlanks() {
        Recipes.Recipe chest = r.forResult("minecraft:chest").get(0);
        assertEquals(8, chest.ingredients().size());
        for (List<String> cell : chest.ingredients()) {
            for (String plank : List.of("minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:crimson_planks", "minecraft:bamboo_planks", "minecraft:pale_oak_planks")) {
                assertTrue(cell.contains(plank), plank + " in " + cell);
            }
        }
        // The middle is empty
        assertEquals(null, chest.cells().get(4));
    }

    @Test
    void planksAreMadeFromLogsFourAtATime() {
        Recipes.Recipe planks = r.forResult("minecraft:oak_planks").get(0);
        assertFalse(planks.shaped());
        assertEquals(4, planks.count());
        assertTrue(planks.ingredients().get(0).contains("minecraft:oak_log"));
        assertFalse(planks.needsTable());
    }

    @Test
    void colouredShulkerBoxesAreNotCraftingRecipesHere() {
        for (String c : List.of("red", "white", "black", "light_blue")) {
            assertTrue(r.forResult("minecraft:" + c + "_shulker_box").isEmpty(), c);
            assertEquals("transmute", r.station("minecraft:" + c + "_shulker_box"), c);
        }
    }

    @Test
    void glassIsMadeInAFurnace() {
        assertTrue(r.forResult("minecraft:glass").isEmpty());
        assertEquals("smelting", r.station("minecraft:glass"));
        CraftPlanner.Plan p = CraftPlanner.plan(r, "minecraft:glass", 1, java.util.Map.of(), CraftPlanner.Limits.open());
        assertFalse(p.ok());
        assertTrue(p.refusal().contains("needs a furnace"), p.refusal());
    }

    @Test
    void whatAShulkerBoxCanBeMadeFrom() {
        var in = r.inputsOf("minecraft:shulker_box", 3);
        for (String id : List.of("minecraft:shulker_shell", "minecraft:chest", "minecraft:oak_planks", "minecraft:oak_log", "minecraft:stripped_birch_wood", "minecraft:crimson_stem")) {
            assertTrue(in.contains(id), id);
        }
        assertFalse(in.contains("minecraft:shulker_box"));
    }
}
