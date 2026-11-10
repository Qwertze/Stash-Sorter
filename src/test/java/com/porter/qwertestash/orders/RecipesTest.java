package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recipe table (a small test table in the shape the server sends, 1.21.4): the recipes phase 1 uses are in it, and its tableHash. */
class RecipesTest {
    private final Recipes r = TestRecipes.table();

    @Test
    void theTestTableLoads() {
        assertEquals("1.21.4", r.version());
        assertTrue(r.size() > 20, "recipes: " + r.size());
        assertEquals(40, r.hash().length());
    }

    private static final String SYNTHETIC = "{\"version\":\"9.9\",\"format\":1,\"recipes\":["
        + "{\"id\":\"minecraft:b_stairs\",\"type\":\"shaped\",\"w\":2,\"h\":2,\"cells\":[[\"minecraft:p\"],null,[\"minecraft:p\",\"minecraft:q\"],[\"minecraft:p\"]],\"result\":\"minecraft:b_stairs\",\"count\":4},"
        + "{\"id\":\"minecraft:a_planks\",\"type\":\"shapeless\",\"ingredients\":[[\"minecraft:z_log\",\"minecraft:y_log\"],[\"minecraft:a\"]],\"result\":\"minecraft:a_planks\",\"count\":4}],"
        + "\"otherStations\":{\"minecraft:stone\":\"smelting\"}}";

    @Test
    void theTableHashIsTheContractsOnASmallSyntheticTable() {
        // Lines sorted by id, tab separated, "-" for an empty cell, a shapeless body sorted by ingredient: SHA-1 of
        //   minecraft:a_planks TAB shapeless TAB 0 TAB 0 TAB minecraft:a_planks TAB 4 TAB minecraft:a|minecraft:z_log,minecraft:y_log NL
        //   minecraft:b_stairs TAB shaped TAB 2 TAB 2 TAB minecraft:b_stairs TAB 4 TAB minecraft:p|-|minecraft:p,minecraft:q|minecraft:p NL
        Recipes t = Recipes.parse(new java.io.StringReader(SYNTHETIC));
        assertEquals("1ebfe58776a2663ed3a6d59f7f031b828d0cdcc6", t.hash());
        // otherStations and the order of the recipes in the file do not matter
        assertEquals(t.hash(), Recipes.tableHash(com.google.gson.JsonParser.parseString(SYNTHETIC.replace("\"minecraft:stone\":\"smelting\"", "")).getAsJsonObject()));
    }

    @Test
    void aChangedCellOrCountChangesTheHash() {
        String h = Recipes.parse(new java.io.StringReader(SYNTHETIC)).hash();
        assertFalse(h.equals(Recipes.parse(new java.io.StringReader(SYNTHETIC.replace("\"count\":4}],", "\"count\":3}],"))).hash()));
        assertFalse(h.equals(Recipes.parse(new java.io.StringReader(SYNTHETIC.replace("null,[\"minecraft:p\",\"minecraft:q\"]", "[\"minecraft:p\"],[\"minecraft:p\",\"minecraft:q\"]"))).hash()));
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
