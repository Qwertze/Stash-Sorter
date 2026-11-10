package com.porter.qwertestash.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.stash.CraftBounds;
import com.porter.qwertestash.stash.CraftSettings;
import com.porter.qwertestash.stash.CraftTableState;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.15.1: a stash with no perimeter has the box of its containers as its area (the "unreachable" bug), the reasons a table is unusable, and the auto table. */
class CraftAutoTest {
    private static final String OW = "minecraft:overworld";

    private static CraftSettings.Table table(int x, int y, int z, String dim) {
        return new CraftSettings.Table(x, y, z, dim);
    }

    private static CraftSettings.Table table(int x, int y, int z) {
        return table(x, y, z, OW);
    }

    @Test
    void aStashWithNoPerimeterUsesTheBoxOfItsContainersAsItsArea() {
        // The bug: bounds() is null for a stash with no `stash area`, and a null area meant "out of bounds" for every table
        assertEquals(CraftBounds.NO_AREA, CraftBounds.boundsWhy(OW, null, table(100, 64, 100), 64));
        List<com.aquarius.mc.block.BlockPos> boxes = List.of(new com.aquarius.mc.block.BlockPos(90, 64, 90), new com.aquarius.mc.block.BlockPos(110, 66, 105));
        int[] area = CraftBounds.area(null, boxes, new com.aquarius.mc.block.BlockPos(100, 64, 100), 8, 3);
        assertEquals(List.of(82, 61, 82, 118, 69, 113), java.util.Arrays.stream(area).boxed().toList());
        // A table beside the chests, and one 60 blocks away, are inside (margin 64); 200 away is not
        assertNull(CraftBounds.boundsWhy(OW, area, table(100, 64, 100), 64));
        assertNull(CraftBounds.boundsWhy(OW, area, table(170, 64, 100), 64));
        assertEquals(CraftBounds.TOO_FAR, CraftBounds.boundsWhy(OW, area, table(400, 64, 100), 64));
        assertNull(CraftBounds.area(null, List.of(), new com.aquarius.mc.block.BlockPos(0, 64, 0), 8, 3), "no container, no area");
    }

    @Test
    void theReasonsAreTheContractsAndTheDimensionIsComparedWithoutItsNamespace() {
        int[] area = {0, 60, 0, 20, 70, 20};
        assertEquals(CraftBounds.NO_DIMENSION, CraftBounds.boundsWhy(null, area, table(5, 64, 5, OW), 64));
        assertEquals(CraftBounds.NO_AREA, CraftBounds.boundsWhy(OW, null, table(5, 64, 5, OW), 64));
        assertEquals(CraftBounds.WRONG_DIMENSION, CraftBounds.boundsWhy(OW, area, table(5, 64, 5, "minecraft:the_nether"), 64));
        assertEquals(CraftBounds.TOO_FAR, CraftBounds.boundsWhy(OW, area, table(500, 64, 5, OW), 64));
        assertEquals(CraftBounds.TOO_FAR, CraftBounds.boundsWhy(OW, area, table(5, 400, 5, OW), 64));
        // The proxy may name the dimension "overworld"
        assertNull(CraftBounds.boundsWhy("overworld", area, table(5, 64, 5, OW), 64));
        for (String code : new String[] {"no_area", "no_dimension", "wrong_dimension", "too_far", "not_table", "no_stand", "path_failed", "not_loaded"}) {
            assertTrue(List.of(CraftBounds.NO_AREA, CraftBounds.NO_DIMENSION, CraftBounds.WRONG_DIMENSION, CraftBounds.TOO_FAR, CraftBounds.NOT_TABLE, CraftBounds.NO_STAND,
                CraftBounds.PATH_FAILED, CraftBounds.NOT_LOADED).contains(code));
        }
    }

    @Test
    void aWebsiteTablesStateFollowsItsReason() {
        assertEquals("ok", CraftTableState.website(null).status());
        assertEquals("other_dimension", CraftTableState.website(CraftBounds.WRONG_DIMENSION).status());
        assertEquals("unloaded", CraftTableState.website(CraftBounds.NOT_LOADED).status());
        assertEquals("not_table", CraftTableState.website(CraftBounds.NOT_TABLE).status());
        for (String why : new String[] {CraftBounds.TOO_FAR, CraftBounds.NO_STAND, CraftBounds.PATH_FAILED, CraftBounds.NO_AREA}) {
            assertEquals("unreachable", CraftTableState.website(why).status(), why);
            assertEquals(why, CraftTableState.website(why).why());
        }
    }

    @Test
    void withNoWebsiteTableTheAutoPathIsAvailableWhenATableIsFoundOrCanBeHad() {
        int[] area = {0, 60, 0, 20, 70, 20};
        // Nothing found, nothing in the stash: unset, and why
        CraftTableState.State none = CraftTableState.auto(OW, area, false, false, false, false);
        assertEquals("unset", none.status());
        assertEquals("no_material", none.why());
        // Each source makes it auto
        assertEquals("auto", CraftTableState.auto(OW, area, true, false, false, false).status());
        assertEquals("auto", CraftTableState.auto(OW, area, false, true, false, false).status());
        assertEquals("auto", CraftTableState.auto(OW, area, false, false, true, false).status());
        CraftTableState.State planks = CraftTableState.auto(OW, area, false, false, false, true);
        assertEquals("auto", planks.status());
        assertNull(planks.why());
        // It needs an area and a dimension
        assertEquals("no_area", CraftTableState.auto(OW, null, true, true, true, true).why());
        assertEquals("no_dimension", CraftTableState.auto(null, area, true, true, true, true).why());
        assertEquals("unset", CraftTableState.auto(OW, null, true, true, true, true).status());
    }

    @Test
    void theClaimFlagNeedsTheSettingsAndEitherAWorkingWebsiteTableOrTheAutoPath() {
        CraftTableState.State auto = CraftTableState.auto(OW, new int[] {0, 0, 0, 1, 1, 1}, false, false, false, true);
        assertTrue(CraftTableState.capable(true, false, auto));
        assertFalse(CraftTableState.capable(false, false, auto), "settings off");
        assertFalse(CraftTableState.capable(true, false, CraftTableState.auto(OW, null, true, true, true, true)), "no area: no auto path");
        assertTrue(CraftTableState.capable(true, true, CraftTableState.website(null)));
        assertTrue(CraftTableState.capable(true, true, new CraftTableState.State("unloaded", "not_loaded")));
        assertFalse(CraftTableState.capable(true, true, CraftTableState.website(CraftBounds.NOT_TABLE)));
        // A website table that is set wins over the auto path: a broken one is not replaced by an auto one
        assertFalse(CraftTableState.capable(true, true, auto));
    }

    private static JsonObject heartbeat(ManagerIndex.CraftOrders c) {
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        byte[] bytes = ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, null, null, new ManagerIndex.Craft(c, null));
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("craftOrders");
    }

    @Test
    void theHeartbeatCarriesTheAutoStateTheReasonAndTheBotsOwnTable() {
        JsonObject auto = heartbeat(new ManagerIndex.CraftOrders(true, "a".repeat(40), "1.21.4", "auto", 2048, List.of(), null, new ManagerIndex.BotTable("118,64,-30", "placed")));
        assertEquals("auto", auto.get("table").getAsString());
        assertTrue(auto.get("tableWhy").isJsonNull());
        assertEquals("118,64,-30", auto.getAsJsonObject("botTable").get("pos").getAsString());
        assertEquals("placed", auto.getAsJsonObject("botTable").get("source").getAsString());
        JsonObject bad = heartbeat(new ManagerIndex.CraftOrders(true, "a".repeat(40), "1.21.4", "unreachable", 2048, List.of(), "too_far", null));
        assertEquals("too_far", bad.get("tableWhy").getAsString());
        assertTrue(bad.get("botTable").isJsonNull());
        // The older constructor says neither: explicit nulls
        JsonObject old = heartbeat(new ManagerIndex.CraftOrders(true, "a".repeat(40), "1.21.4", "ok", 2048, List.of()));
        assertTrue(old.has("tableWhy") && old.get("tableWhy").isJsonNull());
        assertNotNull(old.get("botTable"));
    }
}
