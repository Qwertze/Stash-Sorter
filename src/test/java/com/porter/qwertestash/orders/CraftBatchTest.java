package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How big a chunk is, what the ledger reports and saves, and the reasons failures map to. */
class CraftBatchTest {
    private static final Recipes R = TestRecipes.table();
    private static final String LOG = "minecraft:spruce_log", STAIRS = "minecraft:spruce_stairs", SLAB = "minecraft:spruce_slab";

    private static CraftLedger ledger(Map<String, Long> take, String key, int n) {
        Order.Craft c = new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty(key, n)), List.of(), take.entrySet().stream().map(e -> new Order.Qty(e.getKey(), (int) (long) e.getValue())).toList(), List.of(), 0, false);
        // The steps name no recipe: the test planner may use any
        return CraftLedger.unrestricted(c, List.of(new Order.Line(Order.CRAFT, key, n)), R, 2048);
    }

    private static int stack(String id) {
        return 64;
    }

    @Test
    void aChunkIsTheWholeLoadWhenItFits() {
        CraftLedger l = ledger(Map.of(LOG, 30L), STAIRS, 64);
        CraftBatch.Choice c = CraftBatch.choose(l, STAIRS, 64, 27, 2048, CraftBatchTest::stack, need -> 1);
        assertTrue(c.ok());
        // 64 stairs: 16 fills of stairs from 96 planks (24 fills) from 24 logs, in 64 items = a stack
        assertEquals(64, c.count());
        assertEquals(24L, c.plan().fromStock().get(LOG));
    }

    @Test
    void aChunkIsSmallerWhenTheBudgetIsShort() {
        CraftLedger l = ledger(Map.of(LOG, 3L), STAIRS, 64);
        CraftBatch.Choice c = CraftBatch.choose(l, STAIRS, 64, 27, 2048, CraftBatchTest::stack, need -> 1);
        // 3 logs = 12 planks = 8 stairs
        assertEquals(8, c.count());
    }

    @Test
    void aChunkIsSmallerWhenTheCarrySlotsAreFew() {
        // 64 chests need 512 planks (8 stacks) and as many slots again for logs: far more than 12 slots
        CraftLedger l = ledger(Map.of("minecraft:oak_log", 200L), "minecraft:chest", 64);
        CraftBatch.Choice c = CraftBatch.choose(l, "minecraft:chest", 64, 8, 2048, CraftBatchTest::stack, need -> (int) Math.ceil(need.values().stream().mapToLong(Long::longValue).sum() / 64.0));
        assertTrue(c.ok());
        assertTrue(c.count() < 64 && c.count() >= 1, "count " + c.count());
        int room = CraftBatch.room(c.plan(), (int) Math.ceil(c.plan().fromStock().values().stream().mapToLong(Long::longValue).sum() / 64.0), "minecraft:chest", CraftBatchTest::stack);
        assertTrue(room <= 8, "room " + room);
    }

    @Test
    void notEvenOneFitsIsInventoryFull() {
        CraftLedger l = ledger(Map.of(LOG, 10L), STAIRS, 8);
        CraftBatch.Choice c = CraftBatch.choose(l, STAIRS, 8, 1, 2048, CraftBatchTest::stack, need -> 1);
        assertFalse(c.ok());
        assertEquals(CraftOrder.INVENTORY_FULL, c.reason());
    }

    @Test
    void theBudgetsReasonIsIngredientsShort() {
        CraftLedger l = ledger(Map.of(), STAIRS, 8);
        CraftBatch.Choice c = CraftBatch.choose(l, STAIRS, 8, 27, 2048, CraftBatchTest::stack, need -> 1);
        assertEquals(CraftOrder.INGREDIENTS_SHORT, c.reason());
        assertTrue(c.detail().contains("not enough"), c.detail());
    }

    @Test
    void theFillCapReducesTheChunkAndNoFillsLeftIsCap() {
        CraftLedger l = ledger(Map.of(LOG, 30L), STAIRS, 64);
        // 8 stairs are 5 fills (3 planks + 2 stairs): with 5 left that is the most
        CraftBatch.Choice c = CraftBatch.choose(l, STAIRS, 64, 27, 5, CraftBatchTest::stack, need -> 1);
        assertEquals(8, c.count());
        CraftBatch.Choice none = CraftBatch.choose(l, STAIRS, 64, 27, 0, CraftBatchTest::stack, need -> 1);
        assertEquals(CraftOrder.CAP, none.reason());
    }

    @Test
    void aChunkNeverMakesMoreThanAStackOfTheItem() {
        // Slabs: 6 a fill. 64 slabs would be 11 fills = 66 > 64, so a chunk is 60 (10 fills) or the load is cut at 63? 64/6: the most under a stack
        CraftLedger l = ledger(Map.of(LOG, 20L), SLAB, 64);
        CraftBatch.Choice c = CraftBatch.choose(l, SLAB, 64, 27, 2048, CraftBatchTest::stack, need -> 1);
        assertTrue(c.ok());
        long made = c.plan().steps().stream().filter(s -> s.recipe().result().equals(SLAB)).mapToLong(CraftPlanner.Step::made).sum();
        assertTrue(made <= 64, "made " + made);
        assertTrue(c.count() <= 64);
    }

    @Test
    void surplusOfTheItemItselfIsNotUsedForItsOwnLoad() {
        CraftLedger l = ledger(Map.of(LOG, 10L), STAIRS, 8);
        l.setSurplus(Map.of(STAIRS, 4L));
        CraftBatch.Choice c = CraftBatch.choose(l, STAIRS, 8, 27, 2048, CraftBatchTest::stack, need -> 1);
        // All eight are crafted: the four carried stairs are not part of the chunk's stack
        assertEquals(8, c.count());
        assertEquals(5, c.plan().fills());
    }

    // The ledger

    @Test
    void theLedgerReportsWhatWasMadeUsedShortAndReturned() {
        CraftLedger l = ledger(Map.of(LOG, 10L), STAIRS, 8);
        l.madeChunk(STAIRS, 6, 5, Map.of(LOG, 3L));
        l.addShort(STAIRS, 2, CraftOrder.INGREDIENTS_SHORT);
        l.setReturned(Map.of("minecraft:spruce_planks", 3L));
        JsonObject o = l.toResult();
        assertEquals(5, o.get("fills").getAsInt());
        assertEquals(6, o.getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        assertEquals(8, o.getAsJsonArray("made").get(0).getAsJsonObject().get("wanted").getAsInt());
        assertEquals("ingredients_short", o.getAsJsonArray("short").get(0).getAsJsonObject().get("reason").getAsString());
        assertEquals(3, o.getAsJsonArray("used").get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals("minecraft:spruce_planks", o.getAsJsonArray("returned").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("2 item(s) not crafted: ingredients_short", l.note());
        assertEquals(7L, l.budget().get(LOG));
    }

    @Test
    void noShortMeansNoNote() {
        CraftLedger l = ledger(Map.of(LOG, 10L), STAIRS, 8);
        l.madeChunk(STAIRS, 8, 5, Map.of(LOG, 3L));
        assertNull(l.note());
        l.closeOpen(CraftOrder.INTERRUPTED);
        assertNull(l.note());
    }

    @Test
    void whatIsStillOpenWhenTheOrderEndsIsShortWithTheEndsReason() {
        CraftLedger l = ledger(Map.of(LOG, 10L), STAIRS, 8);
        l.closeOpen(CraftOrder.CANCELLED);
        assertEquals("8 item(s) not crafted: cancelled", l.note());
    }

    @Test
    void theLedgerIsSavedAndReadBack() {
        CraftLedger l = ledger(Map.of(LOG, 10L), STAIRS, 8);
        l.madeChunk(STAIRS, 4, 3, Map.of(LOG, 2L));
        l.addShort(STAIRS, 4, CraftOrder.TIMEOUT);
        JsonObject saved = l.toSaved();
        CraftLedger fresh = ledger(Map.of(LOG, 10L), STAIRS, 8);
        fresh.restore(saved);
        assertEquals(3, fresh.fills());
        assertEquals(8L, fresh.budget().get(LOG));
        assertEquals(4, fresh.made(STAIRS));
        assertEquals("4 item(s) not crafted: timeout", fresh.note());
        // A broken record leaves a fresh ledger
        CraftLedger broken = ledger(Map.of(LOG, 10L), STAIRS, 8);
        broken.restore(new JsonObject());
        assertEquals(0, broken.fills());
        assertEquals(10L, broken.budget().get(LOG));
    }

    @Test
    void listsInTheReportAreCutAtTwoHundredAndKeysAt256() {
        Order.Craft c = new Order.Craft(1, "1.21.4", R.hash(), List.of(), List.of(), List.of(), List.of(), 0, false);
        java.util.List<Order.Line> lines = new java.util.ArrayList<>();
        for (int i = 0; i < 250; i++) lines.add(new Order.Line(Order.CRAFT, "minecraft:item_" + i, 1));
        lines.add(new Order.Line(Order.CRAFT, "minecraft:" + "x".repeat(300), 1));
        CraftLedger l = new CraftLedger(c, lines, R, 10);
        JsonObject o = l.toResult();
        assertEquals(200, o.getAsJsonArray("made").size());
        CraftLedger refused = CraftLedger.refused(lines, R, 10, CraftOrder.RECIPE_MISMATCH);
        assertTrue(refused.toResult().getAsJsonArray("short").size() <= 200);
        assertTrue(refused.refusedAtStart());
    }

    // Reasons

    @Test
    void failuresMapToTheContractsReasons() {
        assertEquals(CraftOrder.TABLE_BLOCKED, CraftOrder.reasonOf(CraftTrip.NOT_REACHED, false, false));
        assertEquals(CraftOrder.TABLE_BLOCKED, CraftOrder.reasonOf(CraftTrip.NOT_OPENED, false, false));
        assertEquals(CraftOrder.INVENTORY_FULL, CraftOrder.reasonOf(CraftTrip.NO_ROOM, false, false));
        assertEquals(CraftOrder.INGREDIENTS_SHORT, CraftOrder.reasonOf(CraftTrip.MISSING + " (Chest)", false, false));
        assertEquals(CraftOrder.GRID_STUCK, CraftOrder.reasonOf(CraftTrip.GRID, false, false));
        assertEquals(CraftOrder.GRID_STUCK, CraftOrder.reasonOf(CraftTrip.NO_ROOM, true, false));
        assertEquals(CraftOrder.TIMEOUT, CraftOrder.reasonOf(CraftTrip.TOO_LONG, false, false));
        assertEquals(CraftOrder.TIMEOUT, CraftOrder.reasonOf(CraftTrip.NO_RESULT, false, false));
        assertEquals(CraftOrder.INTERRUPTED, CraftOrder.reasonOf(CraftTrip.CLOSED, false, false));
        assertEquals(CraftOrder.INTERRUPTED, CraftOrder.reasonOf(null, false, false));
        assertEquals(CraftOrder.CANCELLED, CraftOrder.reasonOf(CraftTrip.CLOSED, false, true));
        assertEquals(CraftOrder.NO_TABLE, CraftOrder.reasonOf("no crafting table available", false, false));
        assertTrue(CraftOrder.REASONS.contains(CraftOrder.CAP));
        assertEquals(11, CraftOrder.REASONS.size());
    }

    @Test
    void aBatchNeverGivesAGridSlotMoreThanTheStackSizeOfItsKind() {
        Recipes.Recipe r = R.byId("minecraft:spruce_planks");
        // A kind that stacks to 16 only: no batch of 64 fills of it
        CraftGrid.Batch b = CraftGrid.batch(r, Map.of(LOG, 64), 64, 64, 3, kind -> 16);
        assertEquals(16, b.fills());
        CraftGrid.Batch whole = CraftGrid.batch(r, Map.of(LOG, 64), 64, 64, 3);
        assertEquals(64, whole.fills());
    }
}
