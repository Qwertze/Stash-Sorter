package com.porter.qwertestash.manager;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.QwerteStashPlugin;
import com.porter.qwertestash.StashConfig;
import com.porter.qwertestash.orders.CraftPool;
import com.porter.qwertestash.stash.CraftBounds;
import com.porter.qwertestash.stash.CraftSettings;
import com.porter.qwertestash.stash.CraftTableCap;
import com.porter.qwertestash.stash.CraftTableState;
import com.porter.qwertestash.stash.CraftingTableFinder;
import com.porter.qwertestash.stash.CraftingTableSpot;
import com.porter.qwertestash.stash.PlaceSearch;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The review of 1.15.1: the area and dimension of a stash, a table set by hand outside the limits, the claim flag, the older server, plain table materials, the caps. */
class CraftReviewTest {
    private static final String OW = "minecraft:overworld";

    @Test
    void theAreaIsThePerimeterElseTheContainersBoxCappedAt128AroundTheBot() {
        BlockPos feet = new BlockPos(0, 64, 0);
        assertEquals(List.of(1, 2, 3, 4, 5, 6), java.util.Arrays.stream(CraftBounds.area(new int[] {1, 2, 3, 4, 5, 6}, List.of(new BlockPos(900, 64, 900)), feet, 8, 3)).boxed().toList());
        // Containers 1000 blocks apart: the box is cut to 128 across around the bot, not the whole 1000
        int[] big = CraftBounds.area(null, List.of(new BlockPos(0, 64, 0), new BlockPos(1000, 64, 1000)), feet, 8, 3);
        assertTrue(big[3] - big[0] <= 128 && big[5] - big[2] <= 128, java.util.Arrays.toString(big));
        assertTrue(CraftBounds.inside(big, new BlockPos(5, 64, 5), 0));
        assertFalse(CraftBounds.inside(big, new BlockPos(900, 64, 900), 64));
        assertFalse(CraftBounds.inside(null, new BlockPos(0, 64, 0), 64));
    }

    @Test
    void theAreaForATableDoesNotDependOnWhereTheBotStands() {
        // A stash wider than 128: containers at x 0 and x 300. A table beside the far one is accepted, one in the middle is not, whoever stands where
        CraftBounds.StashArea wide = new CraftBounds.StashArea(null, List.of(new BlockPos(0, 64, 0), new BlockPos(300, 64, 0)), 8, 3);
        assertNull(CraftBounds.areaWhy(OW, wide, new CraftSettings.Table(310, 64, 5, OW), 64));
        assertNull(CraftBounds.areaWhy(OW, wide, new CraftSettings.Table(-20, 64, 0, OW), 64));
        assertEquals(CraftBounds.TOO_FAR, CraftBounds.areaWhy(OW, wide, new CraftSettings.Table(150, 64, 0, OW), 64));
        assertEquals(CraftBounds.TOO_FAR, CraftBounds.areaWhy(OW, wide, new CraftSettings.Table(310, 200, 5, OW), 64), "height too");
        assertEquals(CraftBounds.NO_AREA, CraftBounds.areaWhy(OW, new CraftBounds.StashArea(null, List.of(), 8, 3), new CraftSettings.Table(0, 64, 0, OW), 64));
        assertNull(CraftBounds.areaWhy(OW, new CraftBounds.StashArea(new int[] {0, 60, 0, 10, 70, 10}, List.of(), 8, 3), new CraftSettings.Table(60, 64, 5, OW), 64));
        assertTrue(CraftBounds.contains(wide, new BlockPos(300, 64, 70), 0) == false);
    }

    @Test
    void aTableIsCountedWhenItIsReallyPutDownNotWhenItIsDecidedOn() {
        CraftTableCap.State s = CraftTableCap.State.empty();
        // Decided on, twice, with the placement failing both times: nothing is counted and the allowance is still there
        assertTrue(CraftTableCap.allowed(s, "o-1", 1000));
        assertTrue(CraftTableCap.allowed(s, "o-1", 2000));
        // Put down: counted
        s = CraftTableCap.record(s, "o-1", 3000);
        assertEquals(1, CraftTableCap.forOrder(s, "o-1"));
        assertFalse(CraftTableCap.allowed(s, "o-1", 4000));
    }

    @Test
    void theDimensionIsFallenBackToOnlyForContainersReadThisSession() {
        // The recorded dimension, else the one a container was read in (not the one the bot is in now)
        assertEquals(OW, CraftBounds.dimension(OW, "minecraft:the_nether"));
        assertEquals("minecraft:the_nether", CraftBounds.dimension(null, "minecraft:the_nether"));
        assertNull(CraftBounds.dimension(null, null));
        CraftBounds.StashArea one = new CraftBounds.StashArea(null, List.of(new BlockPos(0, 64, 0)), 8, 3);
        assertEquals(CraftBounds.NO_DIMENSION, CraftBounds.areaWhy(CraftBounds.dimension(null, null), one, new CraftSettings.Table(0, 64, 0, OW), 64));
    }

    @Test
    void aTableSetByHandOutsideTheLimitsIsKeptAndCraftOrdersUseASlotOfTheirOwn() {
        QwerteStashPlugin.CONFIG = new StashConfig();
        try {
            CraftingTableSpot.set(OW, 5000, 64, 5000);
            assertEquals(OW, CraftingTableSpot.persisted().dimension());
            CraftingTableSpot.sideMode(true);
            assertNull(CraftingTableSpot.get(), "the table set by hand is not used: nothing is known for the order yet");
            assertEquals(CraftingTableSpot.NONE, CraftingTableSpot.source());
            // A table found or placed for the order goes to the slot, not over the user's table
            CraftingTableSpot.remember(OW, 10, 64, 10, CraftingTableSpot.PLACED);
            assertEquals(10, CraftingTableSpot.get().x());
            assertEquals(CraftingTableSpot.PLACED, CraftingTableSpot.source());
            assertEquals(5000, CraftingTableSpot.persisted().x());
            assertEquals("set", CraftingTableSpot.persistedSource());
            assertEquals(5000, QwerteStashPlugin.CONFIG.craftingTableX);
            // Forgetting it forgets the slot only
            CraftingTableSpot.clear();
            assertNull(CraftingTableSpot.get());
            assertEquals(5000, CraftingTableSpot.persisted().x());
            // After the order the user's table is the table again
            CraftingTableSpot.sideMode(false);
            CraftingTableSpot.sideClear();
            assertEquals(5000, CraftingTableSpot.get().x());
        } finally {
            CraftingTableSpot.sideMode(false);
            CraftingTableSpot.sideClear();
            CraftingTableSpot.clear();
        }
    }

    @Test
    void theStateIsNotAutoWhileTheOnlyOwnTableIsOutsideTheLimits() {
        int[] area = {0, 60, 0, 20, 70, 20};
        // The own table is outside the limits (ownUsable false) and nothing else is there
        assertEquals("unset", CraftTableState.auto(OW, area, false, false, false, false).status());
        assertEquals("auto", CraftTableState.auto(OW, area, false, false, true, false).status());
    }

    @Test
    void noTableAndNothingToMakeOneOfIsNoMaterialAndIsSentWithTheReasonNotAsNull() {
        CraftTableState.State none = CraftTableState.auto(OW, true, false, false, false, false);
        assertEquals("unset", none.status());
        assertEquals("no_material", none.why());
        assertFalse(CraftTableState.capable(true, false, none), "the claim flag stays off");
        // Crafting and craft orders on with a recipe table: craftOrders is sent, with the reason; off, or no recipe table: null
        assertTrue(CraftTableState.sendOrders(true, true, true));
        assertFalse(CraftTableState.sendOrders(false, true, true));
        assertFalse(CraftTableState.sendOrders(true, false, true));
        assertFalse(CraftTableState.sendOrders(true, true, false));
        ManagerIndex.CraftOrders sent = new ManagerIndex.CraftOrders(true, "a".repeat(40), "1.21.4", none.status(), 2048, List.of(), none.why(), null);
        JsonObject o = beat(sent);
        assertTrue(o.get("enabled").getAsBoolean());
        assertEquals("unset", o.get("table").getAsString());
        assertEquals("no_material", o.get("tableWhy").getAsString());
        // An older server never sees tableWhy
        assertFalse(beat(sent.asLegacy()).has("tableWhy"));
        // And null only when there is nothing to say
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        JsonObject whole = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, null, null, new ManagerIndex.Craft(null, null)), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(whole.get("craftOrders").isJsonNull());
    }

    @Test
    void aHeldCraftOrderIsClaimedWithTheFlagWhateverTheTableIsLikeForTheMoment() {
        CraftTableState.State unreachable = CraftTableState.website(CraftBounds.TOO_FAR);
        assertFalse(CraftTableState.capable(true, true, unreachable));
        assertFalse(CraftTableState.claimFlag(CraftTableState.capable(true, true, unreachable), false));
        assertTrue(CraftTableState.claimFlag(CraftTableState.capable(true, true, unreachable), true));
        assertTrue(CraftTableState.claimFlag(true, false));
    }

    // The older server

    private static JsonObject beat(ManagerIndex.CraftOrders c) {
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        byte[] bytes = ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, null, null, new ManagerIndex.Craft(c, null));
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("craftOrders");
    }

    @Test
    void anOlderServerThatRefusesTheNewFieldsGetsThe1150ShapeFromThenOn() {
        ManagerIndex.CraftOrders now = new ManagerIndex.CraftOrders(true, "a".repeat(40), "1.21.4", "auto", 2048, List.of("1,2,3"), null, new ManagerIndex.BotTable("1,2,3", "placed"));
        JsonObject full = beat(now);
        assertEquals("auto", full.get("table").getAsString());
        assertTrue(full.has("tableWhy") && full.has("botTable"));
        // A 400 to a heartbeat that carried craftOrders, the first time: fall back
        String craftError = "{\"error\":\"craftOrders.tableWhy must be null or a string\"}";
        assertTrue(ManagerIndex.craftShapeRefused(400, craftError, true, false));
        assertTrue(ManagerIndex.craftShapeRefused(400, "{\"error\":\"craftOrders.table is not one of unset, ok\"}", true, false));
        assertFalse(ManagerIndex.craftShapeRefused(400, craftError, true, true), "already the old shape: a 400 is a real failure");
        assertFalse(ManagerIndex.craftShapeRefused(400, craftError, false, false), "no craft fields were sent");
        assertFalse(ManagerIndex.craftShapeRefused(503, craftError, true, false));
        // A 400 about something else goes down the normal error path
        assertFalse(ManagerIndex.craftShapeRefused(400, "{\"error\":\"indexHash must be 40 hex digits\"}", true, false));
        assertFalse(ManagerIndex.craftShapeRefused(400, "not json at all", true, false));
        JsonObject legacy = beat(now.asLegacy());
        assertEquals("unset", legacy.get("table").getAsString(), "auto is unset for the old server");
        assertFalse(legacy.has("tableWhy"));
        assertFalse(legacy.has("botTable"));
        assertEquals(2048, legacy.get("maxFills").getAsInt());
        assertEquals("ok", beat(new ManagerIndex.CraftOrders(true, "a".repeat(40), "1.21.4", "ok", 5, List.of()).asLegacy()).get("table").getAsString());
    }

    // Table materials and caps

    @Test
    void theBotsTableIsMadeOfPlainStacksOnly() {
        CraftPool.Stock mixed = new CraftPool.Stock(Map.of("minecraft:oak_planks", 64L, "minecraft:oak_planks#ab12", 64L, "minecraft:crafting_table#cd34", 1L),
            Map.of("minecraft:oak_log#ef56", 9L, "minecraft:oak_log", 3L), Map.of(), Map.of());
        CraftPool.Stock plain = mixed.plainOnly();
        assertEquals(Map.of("minecraft:oak_planks", 64L), plain.loose());
        assertEquals(Map.of("minecraft:oak_log", 3L), plain.inShulkers());
    }

    @Test
    void oneTablePerOrderAndThreeAnHourAreKeptInTheSettings() {
        CraftTableCap.State s = CraftTableCap.State.empty();
        long t = 1_000_000;
        assertTrue(CraftTableCap.allowed(s, "o-1", t));
        s = CraftTableCap.record(s, "o-1", t);
        assertFalse(CraftTableCap.allowed(s, "o-1", t + 1000), "one for an order");
        assertTrue(CraftTableCap.allowed(s, "o-2", t + 1000));
        s = CraftTableCap.record(s, "o-2", t + 2000);
        s = CraftTableCap.record(s, "o-3", t + 3000);
        assertEquals(3, CraftTableCap.lastHour(s, t + 4000));
        assertFalse(CraftTableCap.allowed(s, "o-4", t + 4000), "three in an hour");
        assertTrue(CraftTableCap.allowed(s, "o-4", t + CraftTableCap.HOUR_MS + 1000), "an hour later");
        assertEquals(0, CraftTableCap.forOrder(s, "o-9"));
        // The counts are plain fields of the settings file
        StashConfig c = new com.google.gson.Gson().fromJson("{\"sorting\":true}", StashConfig.class);
        assertNotNull(c.craftTablePlacedAt);
        assertTrue(c.craftTablePlacedAt.isEmpty());
        assertEquals(0, c.craftTableOrderCount);
    }

    @Test
    void theReasonsTheTableSearchGivesNeverHoldAPosition() {
        for (String why : new String[] {PlaceSearch.ALL_BLOCKED, PlaceSearch.OUTSIDE, PlaceSearch.UNREACHABLE, PlaceSearch.GUARDED, PlaceSearch.FAILED, CraftingTableFinder.NONE}) {
            assertFalse(why.matches(".*-?\\d+\\s*,\\s*-?\\d+.*"), why);
            assertFalse(why.matches(".*\\d{3,}.*"), why);
        }
    }
}
