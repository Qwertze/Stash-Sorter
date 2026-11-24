package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.porter.qwertestash.orders.SpecialFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sub-plan of a chunk of a tipped, stew, copy or shield line from the block alone (CRAFT-CONTRACT-v1.2.md 7, 15 and the B4 clarifications): the stock of the
 * line's own item is taken by key first, the potion and the flower are exact stock, a banner or book copy holds its source (never delivered, handed back), a
 * map copy uses its source up and makes it again in crafts of at most 8 blanks, a book is copied from an original only, and the budget of the take caps all of
 * it.
 */
class SpecialPlanTest {
    private static final String TIPPED = "minecraft:tipped_arrow@" + POISON, STEW = "minecraft:suspicious_stew@" + NV;
    private static final String B = BANNER + "@copy:" + K_BANNER, M = MAP + "@copy:" + K_MAP, BOOK_COPY = BOOK + "@copy:" + K_BOOK, BOOK_SRC = BOOK + "@src:" + K_BOOK;
    private static final String SHIELD = "minecraft:shield@shield:" + K_BANNER;

    private static CraftLedger ledger(JsonObject block, String id, String spec, int count) {
        Order.Craft c = parse(block);
        assertNull(check(c, CAN), "the block passes its check");
        return new CraftLedger(c, List.of(new Order.Line(Order.CRAFT, id, count, false, spec)), R, 2048);
    }

    private static CraftPlanner.Step step(CraftPlanner.Plan p, String recipe) {
        for (CraftPlanner.Step s : p.steps()) if (s.recipe().id().startsWith(recipe)) return s;
        throw new AssertionError(recipe + " is not a step of " + p.steps());
    }

    // Tipped arrows and stew

    @Test
    void tippedArrowsTakeTheirStockByKeyAndMakeTheRestAroundALingeringPotion() {
        CraftLedger l = ledger(SpecialFixtures.tipped(), "minecraft:tipped_arrow", POISON, 10);
        CraftSub.Outcome o = l.session().plan(TIPPED, 10);
        assertTrue(o.ok(), o.plan().refusal());
        assertEquals(1, o.plan().steps().size());
        CraftPlanner.Step s = o.plan().steps().get(0);
        assertEquals(1, s.fills());
        assertEquals(8, s.recipe().count());
        assertEquals(TIPPED, s.recipe().result());
        // The potion is exact stock, by its key; the 5 arrows in stock are the line's own
        assertEquals(Map.of(TIPPED, 5L, "minecraft:lingering_potion@" + POISON, 1L, "minecraft:arrow", 8L), o.plan().fromStock());
        assertEquals(Map.of(K_ARROW, 5L, K_POTION, 1L), l.keysFor(o.plan().fromStock()));
        // Only stock: no craft at all
        CraftSub.Outcome some = l.session().plan(TIPPED, 4);
        assertTrue(some.ok());
        assertTrue(some.plan().steps().isEmpty());
        assertEquals(Map.of(TIPPED, 4L), some.plan().fromStock());
        // The potion cell of the grid is the lingering potion of the spec and the 8 arrows are plain
        List<List<String>> cells = s.recipe().ingredients();
        assertEquals(9, cells.size());
        assertEquals(List.of("minecraft:lingering_potion@" + POISON), cells.get(4));
        assertEquals(List.of("minecraft:arrow"), cells.get(0));
    }

    @Test
    void withoutThePotionOrTheArrowsTheLineIsShortNotMadeWithOthers() {
        JsonObject noPotion = SpecialFixtures.tipped();
        noPotion.getAsJsonArray("take").remove(0);
        Order.Craft c = parse(noPotion);
        CraftLedger l = new CraftLedger(c, List.of(new Order.Line(Order.CRAFT, "minecraft:tipped_arrow", 10, false, POISON)), R, 2048);
        CraftSub.Outcome o = l.session().plan(TIPPED, 10);
        assertFalse(o.ok());
        assertTrue(o.plan().refusal().contains("Lingering Potion"), o.plan().refusal());
    }

    @Test
    void aStewIsMadeFromABowlBothMushroomsAndTheFlowerAndTheStockOfItsOwnComesFirst() {
        CraftLedger l = ledger(SpecialFixtures.stew(), "minecraft:suspicious_stew", NV, 5);
        CraftSub.Outcome o = l.session().plan(STEW, 5);
        assertTrue(o.ok(), o.plan().refusal());
        CraftPlanner.Step s = o.plan().steps().get(0);
        assertEquals(2, s.fills());
        assertEquals(Map.of(STEW, 3L, "minecraft:bowl", 2L, "minecraft:brown_mushroom", 2L, "minecraft:red_mushroom", 2L, "minecraft:poppy", 2L), o.plan().fromStock());
        assertEquals(Map.of(K_STEW, 3L), l.keysFor(o.plan().fromStock()));
        assertFalse(l.session().plan(STEW, 6).ok(), "a sixth stew has no bowl");
    }

    // A banner copy

    @Test
    void aBannerCopyKeepsOneBannerOfTheKeyAsItsSourceAndNeverDeliversIt() {
        CraftLedger l = ledger(SpecialFixtures.bannerCopy(), BANNER, "copy:" + K_BANNER, 3);
        assertNotNull(l.copyOf(B));
        CraftSub.Outcome o = l.session().plan(B, 3);
        assertTrue(o.ok(), o.plan().refusal());
        // 2 of the key are taken: 1 is delivered, 1 is the source; 2 copies are made, one blank banner each
        assertEquals(Map.of(B, 2L, BANNER, 2L), o.plan().fromStock());
        assertEquals(1, o.plan().steps().size());
        CraftPlanner.Step s = o.plan().steps().get(0);
        assertEquals(2, s.fills());
        assertEquals(1, s.recipe().count());
        assertEquals(B, s.recipe().result());
        assertEquals(Map.of(K_BANNER, 2L), l.keysFor(o.plan().fromStock()));
        // The grid of a craft: the source (the banner of the key), then a plain blank
        assertEquals(List.of(List.of(B), List.of(BANNER)), s.recipe().ingredients());
        // 3 delivered = 1 from stock + 2 made: the plan's stock part less the source is 1
        assertEquals(3, (o.plan().fromStock().get(B) - 1) + s.made());
    }

    @Test
    void aBannerCopyThatTheStockLessItsSourceCoversMakesNothing() {
        CraftLedger l = ledger(SpecialFixtures.bannerCopy(), BANNER, "copy:" + K_BANNER, 3);
        // 2 in stock, one kept as the source for the whole order: one is delivered with no craft and nothing of the source is taken
        CraftSub.Outcome o = l.session().plan(B, 1);
        assertTrue(o.ok());
        assertTrue(o.plan().steps().isEmpty());
        assertEquals(Map.of(B, 1L), o.plan().fromStock());
        // Two need the source and one craft
        CraftSub.Outcome two = l.session().plan(B, 2);
        assertTrue(two.ok(), two.plan().refusal());
        assertEquals(Map.of(B, 2L, BANNER, 1L), two.plan().fromStock());
        assertEquals(1, two.plan().steps().get(0).fills());
    }

    @Test
    void theSourceBannerOfAChunkIsCarriedAndIsTheNextChunksSourceNotItsStock() {
        // 4 copies wanted, 1 banner of the key in stock (only the source), 4 blanks: chunks of 2
        JsonObject b = SpecialFixtures.bannerCopy();
        b.getAsJsonArray("take").remove(0);
        b.getAsJsonArray("take").get(1).getAsJsonObject().addProperty("count", 4);
        b.getAsJsonArray("lines").get(0).getAsJsonObject().addProperty("count", 4);
        b.getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("fills", 4);
        b.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("count", 4);
        b.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonArray("inputs").get(1).getAsJsonObject().addProperty("count", 4);
        b.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonArray("remainder").get(0).getAsJsonObject().addProperty("count", 4);
        CraftLedger l = ledger(b, BANNER, "copy:" + K_BANNER, 4);
        CraftSub.Session first = l.session();
        first.dropSurplus(B);
        CraftSub.Outcome o1 = first.plan(B, 2);
        assertTrue(o1.ok(), o1.plan().refusal());
        // The one banner of the key is the source: it is taken (and charged), nothing of it is delivered
        assertEquals(Map.of(B, 1L, BANNER, 2L), o1.plan().fromStock());
        assertEquals(2, o1.plan().steps().get(0).fills());
        l.madeChunk(B, 2, 2, o1.plan().fromStock());
        // The bot carries the source now (the grid gave it back): the next chunk's session knows it as surplus, which the load itself must not use as stock
        l.setSurplus(Map.of(B, 1L));
        CraftSub.Session second = l.session();
        second.dropSurplus(B);
        CraftSub.Outcome o2 = second.plan(B, 2);
        assertTrue(o2.ok(), o2.plan().refusal());
        assertEquals(Map.of(BANNER, 2L), o2.plan().fromStock(), "no banner of the key is taken again: the carried source is held");
        assertEquals(2, o2.plan().steps().get(0).fills());
        // Without the carried source and without stock the copy has none to copy
        l.setSurplus(Map.of());
        CraftSub.Session third = l.session();
        third.dropSurplus(B);
        CraftSub.Outcome o3 = third.plan(B, 1);
        assertFalse(o3.ok());
        assertTrue(o3.plan().refusal().contains("White Banner"), o3.plan().refusal());
    }

    // A map copy

    @Test
    void aMapCopyUsesItsSourceUpAndIsMadeInCraftsOfEightBlanksAndOneShorter() {
        CraftLedger l = ledger(SpecialFixtures.mapCopy(), MAP, "copy:" + K_MAP, 12);
        CraftSub.Outcome o = l.session().plan(M, 12);
        assertTrue(o.ok(), o.plan().refusal());
        // The one map in stock is delivered (and used as the source, the craft makes it again): 11 blanks, a craft of 8 and one of 3
        assertEquals(Map.of(M, 1L, "minecraft:map", 11L), o.plan().fromStock());
        assertEquals(2, o.plan().steps().size());
        CraftPlanner.Step a = o.plan().steps().get(0), b = o.plan().steps().get(1);
        assertEquals(9, a.recipe().count());
        assertEquals(1, a.fills());
        assertEquals(4, b.recipe().count());
        assertEquals(1, b.fills());
        // 12 = 1 stock + (9 - 1 used) + (4 - 1 used)
        assertEquals(12, 1 + (a.made() - 1) + (b.made() - 1));
        assertEquals(List.of(List.of(M), List.of("minecraft:map"), List.of("minecraft:map"), List.of("minecraft:map")), b.recipe().ingredients());
    }

    @Test
    void theCraftsOfAMapCopyAreAsManyAsTheBlankCountsAndASecondLoadKeepsAMapForItsSource() {
        // 17 copies from the one map in stock: two crafts of 8 blanks (one step of 2 fills)
        CraftLedger l = ledger(SpecialFixtures.mapCopyInOneStep(), MAP, "copy:" + K_MAP, 17);
        CraftSub.Outcome o = l.session().plan(M, 17);
        assertTrue(o.ok(), o.plan().refusal());
        assertEquals(1, o.plan().steps().size());
        assertEquals(2, o.plan().steps().get(0).fills());
        assertEquals(9, o.plan().steps().get(0).recipe().count());
        assertEquals(Map.of(M, 1L, "minecraft:map", 16L), o.plan().fromStock());
        // A load that is followed by another makes one map more (the next load's first craft needs a source): it needs one blank more than the server's budget
        // holds for a line that is one load, so it is refused here; the real two-load lines are in SpecialMultiLoadTest
        CraftSub.Session keep = l.session();
        keep.keepCopySource = true;
        assertFalse(keep.plan(M, 17).ok());
    }

    @Test
    void aMapCopyWithNoMapInStockHasNothingToCopyAndACarriedMapIsTheSource() {
        JsonObject b = SpecialFixtures.mapCopy();
        b.getAsJsonArray("take").remove(0);
        CraftLedger none = new CraftLedger(parse(b), List.of(new Order.Line(Order.CRAFT, MAP, 4, false, "copy:" + K_MAP)), R, 2048);
        CraftSub.Outcome o = none.session().plan(M, 4);
        assertFalse(o.ok());
        assertTrue(o.plan().refusal().contains("Filled Map"), o.plan().refusal());
        // A map carried over from the chunk before is the source of the next one's first craft: it is used up and made again
        none.setSurplus(Map.of(M, 1L));
        CraftSub.Session s = none.session();
        s.dropSurplus(M);
        CraftSub.Outcome again = s.plan(M, 4);
        assertTrue(again.ok(), again.plan().refusal());
        assertTrue(again.plan().fromStock().keySet().stream().noneMatch(k -> k.equals(M)), "nothing of the key is taken from the stash");
        long net = 0;
        for (CraftPlanner.Step st : again.plan().steps()) net += (long) st.fills() * VariantSpecs.netCount(st.recipe().id(), st.recipe().count());
        assertEquals(3, net, "the carried map is part of the 4 the stack holds after the crafts");
    }

    @Test
    void aMapLineOfTwoLoadsKeepsOneMapForTheSecondLoadAndDeliversALastSingleMapFromIt() {
        // 17 maps in loads of 16 and 1, one map in stock, 16 blanks: the first load makes one map more, the second is that map
        CraftLedger l = ledger(SpecialFixtures.mapCopyInOneStep(), MAP, "copy:" + K_MAP, 17);
        CraftBatch.Choice first = CraftBatch.choose(l, M, 16, 27, 2048, id -> 16, need -> need.size());
        assertTrue(first.ok(), first.reason() + " " + first.detail());
        assertEquals(16, first.count());
        long net = 0;
        for (CraftPlanner.Step s : first.plan().steps()) net += (long) s.fills() * VariantSpecs.netCount(s.recipe().id(), s.recipe().count());
        assertEquals(16, net, "16 blanks: 15 for the load, 1 kept; the one in stock is the load's too");
        l.madeChunk(M, 16, 2, first.plan().fromStock());
        // The map that was kept is carried: it is the last load, nothing is made and nothing is taken
        l.setSurplus(Map.of(M, 1L));
        CraftBatch.Choice second = CraftBatch.choose(l, M, 1, 27, 2048, id -> 16, need -> need.size());
        assertTrue(second.ok(), second.reason() + " " + second.detail());
        assertEquals(1, second.count());
        assertTrue(second.plan().steps().isEmpty());
        assertTrue(second.plan().fromStock().isEmpty());
        // Without the kept map there is nothing to deliver or copy
        l.setSurplus(Map.of());
        assertFalse(CraftBatch.choose(l, M, 1, 27, 2048, id -> 16, need -> need.size()).ok());
    }

    // A book copy

    @Test
    void aBookCopyHoldsTheOriginalAndMakesOneCopyPerWritableBook() {
        CraftLedger l = ledger(SpecialFixtures.bookCopy(), BOOK, "copy:" + K_BOOK, 6);
        CraftSub.Outcome o = l.session().plan(BOOK_COPY, 6);
        assertTrue(o.ok(), o.plan().refusal());
        assertEquals(Map.of(BOOK_SRC, 1L, "minecraft:writable_book", 6L), o.plan().fromStock());
        assertEquals(1, o.plan().steps().size());
        CraftPlanner.Step s = o.plan().steps().get(0);
        assertEquals(6, s.recipe().count());
        assertEquals(BOOK_COPY, s.recipe().result());
        assertEquals(List.of(BOOK_SRC), s.recipe().ingredients().get(0));
        assertEquals(Map.of(K_BOOK, 1L), l.keysFor(o.plan().fromStock()));
        // The copies are another stack from the original: the original is no stock of the line
        assertEquals(0, l.session().plan(BOOK_COPY, 0).plan().steps().size());
    }

    @Test
    void moreThanEightBooksAreCraftsOfEightAndOneShorter() {
        JsonObject b = SpecialFixtures.bookCopy();
        // 20 copies: two crafts of 8 (one step of 2 fills) and one of 4
        String spec = "copy:" + K_BOOK;
        String grid8 = "[" + cell(BOOK, spec) + (("," + cell("minecraft:writable_book")).repeat(8)) + "]";
        String a = "{ \"recipe\": \"qwerte:special/book_copy\", \"result\": \"" + BOOK + "\", \"spec\": \"" + spec + "\", \"count\": 8, \"fills\": 2, \"grid\": " + grid8
            + ", \"inputs\": [ " + q(BOOK, K_BOOK, spec, 2) + ", " + q("minecraft:writable_book", 16) + " ], \"remainder\": [ " + q(BOOK, K_BOOK, spec, 2) + " ] }";
        String c = "{ \"recipe\": \"qwerte:special/book_copy\", \"result\": \"" + BOOK + "\", \"spec\": \"" + spec + "\", \"count\": 4, \"fills\": 1, \"grid\": "
            + "[" + cell(BOOK, spec) + (("," + cell("minecraft:writable_book")).repeat(4)) + "]"
            + ", \"inputs\": [ " + q(BOOK, K_BOOK, spec, 1) + ", " + q("minecraft:writable_book", 4) + " ], \"remainder\": [ " + q(BOOK, K_BOOK, spec, 1) + " ] }";
        JsonObject block = block("[\"copy\"]", "[ { \"key\": \"" + BOOK + "\", \"count\": 20, \"spec\": \"" + spec + "\" } ]", "[" + a + "," + c + "]",
            "[ " + q(BOOK, K_BOOK, spec, 1) + ", " + q("minecraft:writable_book", 20) + " ]", "[]", 3);
        // The grids above are 9 cells with nulls: build them the way the helper does
        block.getAsJsonArray("steps").forEach(e -> {
            com.google.gson.JsonArray g = e.getAsJsonObject().getAsJsonArray("grid");
            while (g.size() < 9) g.add(com.google.gson.JsonNull.INSTANCE);
        });
        CraftLedger l = ledger(block, BOOK, spec, 20);
        CraftSub.Outcome o = l.session().plan(BOOK_COPY, 20);
        assertTrue(o.ok(), o.plan().refusal());
        long crafts = 0, copies = 0;
        for (CraftPlanner.Step s : o.plan().steps()) {
            crafts += s.fills();
            copies += s.made();
        }
        assertEquals(3, crafts);
        assertEquals(20, copies);
        assertEquals(1L, o.plan().fromStock().get(BOOK_SRC), "one original, whatever the number of crafts");
    }

    @Test
    void aBookCopyWithNoOriginalIsShortAndACarriedOriginalIsHeld() {
        JsonObject b = SpecialFixtures.bookCopy();
        b.getAsJsonArray("take").remove(0);
        CraftLedger none = new CraftLedger(parse(b), List.of(new Order.Line(Order.CRAFT, BOOK, 6, false, "copy:" + K_BOOK)), R, 2048);
        assertFalse(none.session().plan(BOOK_COPY, 6).ok());
        none.setSurplus(Map.of(BOOK_SRC, 1L));
        CraftSub.Outcome o = none.session().plan(BOOK_COPY, 6);
        assertTrue(o.ok(), o.plan().refusal());
        assertTrue(o.plan().fromStock().keySet().stream().noneMatch(k -> k.equals(BOOK_SRC)), "the carried original is used, none is taken");
    }

    // A shield

    @Test
    void aShieldIsAPlainShieldAndOneBannerOfTheKeyWhichIsConsumed() {
        CraftLedger l = ledger(SpecialFixtures.shield(), "minecraft:shield", "shield:" + K_BANNER, 2);
        CraftSub.Outcome o = l.session().plan(SHIELD, 2);
        assertTrue(o.ok(), o.plan().refusal());
        assertEquals(Map.of("minecraft:shield", 2L, B, 2L), o.plan().fromStock());
        assertEquals(2, o.plan().steps().get(0).fills());
        assertEquals(Map.of(K_BANNER, 2L), l.keysFor(o.plan().fromStock()));
        assertEquals(List.of(List.of("minecraft:shield"), List.of(B)), o.plan().steps().get(0).recipe().ingredients());
        // A third has no banner
        assertFalse(l.session().plan(SHIELD, 3).ok());
    }

    // The budget

    @Test
    void thePlansTakeIsTheBudgetAndNothingIsTakenBeyondIt() {
        CraftLedger l = ledger(SpecialFixtures.bannerCopy(), BANNER, "copy:" + K_BANNER, 3);
        CraftSub.Outcome o = l.session().plan(B, 3);
        assertTrue(o.ok());
        l.madeChunk(B, 3, 2, o.plan().fromStock());
        assertEquals(0L, l.budget().getOrDefault(B, 0L), "both banners of the key are charged: the delivered one and the source");
        assertEquals(0L, l.budget().getOrDefault(BANNER, 0L));
        // Another plan after the charge finds no source in the budget
        CraftSub.Session s = l.session();
        s.dropSurplus(B);
        assertFalse(s.plan(B, 1).ok());
        // The report: used 2 + 2, nothing returned until the order ends and the carried source is counted
        JsonObject r = l.toResult();
        assertEquals(2, r.getAsJsonArray("used").size());
        l.setReturned(Map.of(B, 1L));
        JsonObject back = l.toResult().getAsJsonArray("returned").get(0).getAsJsonObject();
        assertEquals(BANNER, back.get("id").getAsString());
        assertEquals("copy:" + K_BANNER, back.get("spec").getAsString());
        assertEquals(1, back.get("count").getAsInt());
    }

    @Test
    void aBooksSourceIsReportedOnTheWireAsTheCopySpecOfItsKey() {
        CraftLedger l = ledger(SpecialFixtures.bookCopy(), BOOK, "copy:" + K_BOOK, 6);
        CraftSub.Outcome o = l.session().plan(BOOK_COPY, 6);
        l.madeChunk(BOOK_COPY, 6, 1, o.plan().fromStock());
        l.setReturned(Map.of(BOOK_SRC, 1L));
        JsonObject r = l.toResult();
        for (var e : r.getAsJsonArray("used")) {
            if (e.getAsJsonObject().get("id").getAsString().equals(BOOK)) assertEquals("copy:" + K_BOOK, e.getAsJsonObject().get("spec").getAsString());
        }
        assertEquals("copy:" + K_BOOK, r.getAsJsonArray("returned").get(0).getAsJsonObject().get("spec").getAsString());
        JsonObject made = r.getAsJsonArray("made").get(0).getAsJsonObject();
        assertEquals(BOOK, made.get("key").getAsString());
        assertEquals("copy:" + K_BOOK, made.get("spec").getAsString());
        assertEquals(6, made.get("made").getAsInt());
    }
}
