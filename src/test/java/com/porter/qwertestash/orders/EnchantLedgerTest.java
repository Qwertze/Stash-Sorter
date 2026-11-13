package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The order's enchant block as the server writes it, the ledger's budget, journal and result.enchant. */
class EnchantLedgerTest {
    /** The block of PROTOCOL.md "Enchant orders" (one sword, one book). */
    private static final String BLOCK = """
        { "v": 1, "mc": "1.21.4",
          "lines": [ { "key": "minecraft:diamond_sword", "count": 1, "enchants": [ { "id": "minecraft:sharpness", "lvl": 5 } ],
                       "plans": [ { "materials": [ { "kind": "base", "key": "minecraft:diamond_sword", "ench": [], "rc": 0 },
                                                   { "kind": "book", "key": "minecraft:enchanted_book#a1111111", "ench": [ { "id": "minecraft:sharpness", "lvl": 5 } ], "rc": 0 } ],
                                    "steps": [ { "left": 0, "right": 1, "cost": 5, "rc": 1 } ], "levels": [ { "id": "minecraft:sharpness", "lvl": 5 } ],
                                    "extras": [], "curse": [], "totalLevels": 5, "points": 55, "bottles": 14, "finalRc": 1 } ] } ],
          "take": [ { "key": "minecraft:diamond_sword", "count": 1 }, { "key": "minecraft:enchanted_book#a1111111", "count": 1 },
                    { "key": "minecraft:experience_bottle", "count": 14 }, { "key": "minecraft:anvil", "count": 2 } ],
          "curse": [] }
        """;

    private static EnchantOrder.Block block() {
        EnchantOrder.Block b = EnchantOrder.parse(JsonParser.parseString(BLOCK));
        assertNotNull(b);
        return b;
    }

    @Test
    void theBlockIsReadAsTheServerWritesIt() {
        EnchantOrder.Block b = block();
        assertFalse(b.malformed());
        assertEquals("1.21.4", b.mc());
        assertEquals(1, b.items().size());
        EnchantOrder.ItemRef ref = b.items().get(0);
        assertEquals("minecraft:diamond_sword", ref.key());
        assertEquals(Map.of("sharpness", 5), ref.enchants());
        assertEquals(2, ref.plan().materials().size());
        assertEquals("minecraft:enchanted_book#a1111111", ref.plan().materials().get(1).key());
        assertEquals(Map.of("sharpness", 5), ref.plan().materials().get(1).ench());
        assertEquals(55, ref.plan().points());
        assertEquals(14, ref.plan().bottles());
        assertEquals(1, ref.plan().steps().size());
        assertEquals(5, ref.plan().steps().get(0).cost());
        assertEquals(4, b.take().size());
        assertEquals(14L, b.takeMap().get(EnchantOrder.BOTTLE_KEY));
        assertEquals("minecraft:diamond_sword", ref.plan().baseKey());
        assertEquals(Map.of("minecraft:enchanted_book#a1111111", 1), ref.plan().bookKeys());
    }

    @Test
    void aWrongBlockIsUnreadableNotAnException() {
        assertTrue(EnchantOrder.parse(JsonParser.parseString("{\"v\":1}")).malformed());
        assertTrue(EnchantOrder.parse(JsonParser.parseString("[]")).malformed());
        assertNull(EnchantOrder.parse(null));
        assertNull(EnchantOrder.parse(com.google.gson.JsonNull.INSTANCE));
        // a first material that is not the base
        assertTrue(EnchantOrder.parse(JsonParser.parseString(BLOCK.replace("\"kind\": \"base\"", "\"kind\": \"book\""))).malformed());
        // a level out of range
        assertTrue(EnchantOrder.parse(JsonParser.parseString(BLOCK.replace("{ \"id\": \"minecraft:sharpness\", \"lvl\": 5 } ]", "{ \"id\": \"minecraft:sharpness\", \"lvl\": 999 } ]"))).malformed());
        // a negative take count
        assertTrue(EnchantOrder.parse(JsonParser.parseString(BLOCK.replace("\"count\": 14", "\"count\": -1"))).malformed());
    }

    @Test
    void theOrderParsesEnchantLinesAndTheBlock() {
        JsonObject o = JsonParser.parseString("{\"id\":\"o1\",\"lines\":[{\"type\":\"enchant\",\"key\":\"minecraft:diamond_sword\",\"count\":1,"
            + "\"enchants\":[{\"id\":\"minecraft:sharpness\",\"lvl\":5}]},{\"type\":\"item\",\"key\":\"minecraft:diamond\",\"count\":3}],\"enchant\":" + BLOCK + "}").getAsJsonObject();
        Order order = Order.parse(o);
        assertNotNull(order);
        assertTrue(order.hasEnchant());
        assertEquals(1, order.enchantLines().size());
        assertNotNull(order.enchant);
        assertFalse(order.enchant.malformed());
        // the enchant line is requested under its key, so result.lines[].delivered is under it
        assertEquals(1, order.requested().get("minecraft:diamond_sword"));
        // an enchant line with no block is unreadable (every item will be short)
        JsonObject bad = JsonParser.parseString("{\"id\":\"o1\",\"lines\":[{\"type\":\"enchant\",\"key\":\"minecraft:diamond_sword\",\"count\":1}]}").getAsJsonObject();
        assertTrue(Order.parse(bad).enchant.malformed());
        // an order without them has no block
        JsonObject plain = JsonParser.parseString("{\"id\":\"o1\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:diamond\",\"count\":3}]}").getAsJsonObject();
        assertNull(Order.parse(plain).enchant);
    }

    @Test
    void theBudgetNeverLetsMoreOfAKeyBeTakenThanTheOrderReserved() {
        EnchantLedger l = new EnchantLedger(block());
        assertEquals(14, l.remaining(EnchantOrder.BOTTLE_KEY));
        assertTrue(l.charge(EnchantOrder.BOTTLE_KEY, 10));
        assertFalse(l.charge(EnchantOrder.BOTTLE_KEY, 5));
        assertEquals(4, l.remaining(EnchantOrder.BOTTLE_KEY));
        assertEquals(10, l.taken(EnchantOrder.BOTTLE_KEY));
        l.refund(EnchantOrder.BOTTLE_KEY, 8);
        assertEquals(12, l.remaining(EnchantOrder.BOTTLE_KEY));
        // refunds never go above what the order reserved, and unknown keys are ignored
        l.refund(EnchantOrder.BOTTLE_KEY, 100);
        assertEquals(14, l.remaining(EnchantOrder.BOTTLE_KEY));
        l.refund("minecraft:stick", 5);
        assertEquals(0, l.remaining("minecraft:stick"));
        assertEquals(List.of("minecraft:anvil"), l.anvilKeys());
    }

    @Test
    void theTopUpAllowanceLeavesTheBottlesOtherOrdersHold() {
        // the stash has 500, open orders reserve 300 of which this order already took 100 (they are out of the stash): 500 - 200 = 300
        assertEquals(300, EnchantLedger.topUpAllowance(500, 300, 100));
        assertEquals(0, EnchantLedger.topUpAllowance(100, 300, 0));
        assertEquals(50, EnchantLedger.topUpAllowance(50, 0, 0));
        assertEquals(0, EnchantLedger.topUpAllowance(0, 0, 0));
        // the repair reserve is not counted: bottles above the other orders' share may all be used
        assertEquals(1728, EnchantLedger.topUpAllowance(1728, 14, 14));
    }

    @Test
    void anItemIsPastStepOneOnlyWhileRunningWithAStepDone() {
        EnchantLedger l = new EnchantLedger(block());
        assertFalse(l.pastStepOne());
        l.begin(0, l.block().items().get(0).plan());
        assertTrue(l.running());
        assertFalse(l.pastStepOne());
        l.stepDone(0, 0);
        assertTrue(l.pastStepOne());
        l.done(0, Map.of("sharpness", 5));
        assertFalse(l.pastStepOne());
        assertFalse(l.anyOpen());
        assertEquals(1, l.doneCount());
    }

    @Test
    void theResultHasTheContractsShapeAndOnlyClosedReasons() {
        EnchantLedger l = new EnchantLedger(block());
        l.fail(0, Codes.COST_MISMATCH);
        l.addBottles(9);
        l.anvilBroke(1);
        l.setReturned(Map.of("minecraft:enchanted_book#a1111111", 1L, "minecraft:diamond_sword", 1L));
        JsonObject r = l.toResult();
        JsonObject made = r.getAsJsonArray("made").get(0).getAsJsonObject();
        assertEquals("minecraft:diamond_sword", made.get("key").getAsString());
        assertEquals(1, made.get("wanted").getAsInt());
        assertEquals(0, made.get("made").getAsInt());
        JsonObject shortOne = r.getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals(Codes.COST_MISMATCH, shortOne.get("reason").getAsString());
        assertEquals(9, r.get("bottlesUsed").getAsInt());
        assertEquals(1, r.get("anvilsBroken").getAsInt());
        JsonArray back = r.getAsJsonArray("returned");
        assertEquals(2, back.size());
        for (var e : back) assertTrue(e.getAsJsonObject().get("key").getAsString().matches("[a-z0-9_.:/-]+(#([0-9a-f]+|[*]))?"));
        assertTrue(r.getAsJsonArray("levels").isEmpty());
        // a reason outside the set never leaves the bot
        EnchantLedger odd = new EnchantLedger(block());
        odd.fail(0, "interrupted");
        assertEquals(Codes.CAP, odd.toResult().getAsJsonArray("short").get(0).getAsJsonObject().get("reason").getAsString());
        assertEquals("1 item not enchanted: cap", odd.note());
    }

    @Test
    void whatWentBackIsAddedUpAcrossItemsAndTheEndOfTheOrder() {
        EnchantLedger l = new EnchantLedger(block());
        l.addReturned(Map.of("minecraft:experience_bottle", 5L, "minecraft:enchanted_book#a1111111", 1L));
        l.addReturned(Map.of("minecraft:experience_bottle", 3L, "minecraft:anvil", 1L, "minecraft:stick", 0L));
        JsonArray back = l.toResult().getAsJsonArray("returned");
        assertEquals(3, back.size());
        long bottles = 0;
        for (var e : back) if (e.getAsJsonObject().get("key").getAsString().equals("minecraft:experience_bottle")) bottles = e.getAsJsonObject().get("count").getAsLong();
        assertEquals(8, bottles);
        // a journal keeps it
        EnchantLedger again = new EnchantLedger(block());
        again.restore(JsonParser.parseString(l.toSaved().toString()).getAsJsonObject());
        assertEquals(3, again.toResult().getAsJsonArray("returned").size());
    }

    @Test
    void aFinishedItemShowsItsLevelsAndCountsAsMade() {
        EnchantLedger l = new EnchantLedger(block());
        l.begin(0, l.block().items().get(0).plan());
        l.done(0, Map.of("sharpness", 5, "unbreaking", 3));
        JsonObject r = l.toResult();
        assertEquals(1, r.getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        assertEquals(0, r.getAsJsonArray("short").size());
        JsonArray levels = r.getAsJsonArray("levels");
        assertEquals(2, levels.size());
        assertEquals("minecraft:sharpness", levels.get(0).getAsJsonObject().get("id").getAsString());
        assertNull(l.note());
    }

    @Test
    void theResultCarriesNoPositions() {
        EnchantLedger l = new EnchantLedger(block());
        l.begin(0, l.block().items().get(0).plan());
        l.stepDone(0, 0);
        String text = l.toResult().toString() + l.toSaved();
        for (String bad : new String[] {"\"x\"", "\"y\"", "\"z\"", "pos", "coord", "dimension"}) assertFalse(text.contains(bad), bad);
    }

    @Test
    void theJournalIsWrittenAndTakenUpAgain() {
        EnchantOrder.Block b = block();
        EnchantLedger l = new EnchantLedger(b);
        l.charge(EnchantOrder.BOTTLE_KEY, 14);
        l.begin(0, b.items().get(0).plan());
        l.stepDone(0, 0);
        l.addBottles(11);
        l.anvilBroke(1);
        l.toppedUp();
        JsonObject saved = l.toSaved();
        EnchantLedger back = new EnchantLedger(b);
        back.restore(JsonParser.parseString(saved.toString()).getAsJsonObject());
        EnchantLedger.Item it = back.item(0);
        assertEquals(EnchantLedger.Status.RUNNING, it.status);
        assertEquals(1, it.stepsDone);
        assertNotNull(it.real);
        assertEquals(1, it.real.steps().size());
        assertEquals(55, it.real.points());
        assertEquals("minecraft:enchanted_book#a1111111", it.real.materials().get(1).key());
        assertEquals(11, back.bottlesUsed());
        assertEquals(1, back.anvilsBroken());
        assertEquals(1, back.topUps());
        assertEquals(0, back.remaining(EnchantOrder.BOTTLE_KEY));
        assertTrue(back.pastStepOne());
    }

    @Test
    void aJournalThatDoesNotFitTheOrderIsIgnored() {
        EnchantOrder.Block b = block();
        EnchantLedger l = new EnchantLedger(b);
        JsonObject junk = JsonParser.parseString("{\"items\":[],\"budget\":{},\"returned\":{},\"bottles\":0,\"broken\":0}").getAsJsonObject();
        l.restore(junk);
        assertEquals(EnchantLedger.Status.PENDING, l.item(0).status);
        l.restore(JsonParser.parseString("{\"nonsense\":true}").getAsJsonObject());
        assertEquals(EnchantLedger.Status.PENDING, l.item(0).status);
        assertEquals(14, l.remaining(EnchantOrder.BOTTLE_KEY));
    }

    @Test
    void failRestAndTheRefusedLedger() {
        EnchantOrder.Block b = block();
        EnchantLedger l = new EnchantLedger(b);
        l.failRest(Codes.CANCELLED);
        assertEquals(1, l.shortCount());
        assertEquals(Codes.CANCELLED, l.shortReasonOfLine(0));
        EnchantLedger refused = EnchantLedger.refused(b, Codes.MODULE_CONFLICT);
        assertTrue(refused.refusedAtStart());
        assertEquals(Codes.MODULE_CONFLICT, refused.item(0).reason);
        assertEquals(-1, refused.nextOpen());
        assertEquals("1 item not enchanted: module_conflict", refused.note());
    }

    @Test
    void enchantLotsAreAnItemStillToBeEnchantedUntilTheyAreDone() {
        Order.Lot lot = Order.Lot.toEnchant("minecraft:diamond_sword", 3);
        assertTrue(lot.enchant());
        assertFalse(lot.craft());
        assertFalse(lot.crafted());
        assertEquals(3, lot.enchantItem());
        assertEquals(1, lot.count());
        assertEquals(-1, new Order.Lot("minecraft:diamond_sword", null, 1).enchantItem());
        Unit u = new Unit(Unit.Kind.Bin, "", new java.util.ArrayList<>(List.of(new Order.Lot("minecraft:diamond", 5), lot)));
        assertEquals(1, u.nextEnchantLot());
    }

    @Test
    void aBlockWithNumbersNoOrderCanHoldIsUnreadable() {
        for (String bad : new String[] {BLOCK.replace("\"bottles\": 14", "\"bottles\": 2147483647"), BLOCK.replace("\"bottles\": 14", "\"bottles\": 2049"),
            BLOCK.replace("\"count\": 14", "\"count\": 100001"), BLOCK.replace("\"points\": 55", "\"points\": 2000000"),
            BLOCK.replace("\"left\": 0, \"right\": 1", "\"left\": 0, \"right\": 7"), BLOCK.replace("\"left\": 0, \"right\": 1", "\"left\": 1, \"right\": 1"),
            BLOCK.replace("\"cost\": 5", "\"cost\": 40")}) {
            assertTrue(EnchantOrder.parse(JsonParser.parseString(bad)).malformed(), bad);
        }
        // the ceiling itself is fine
        assertFalse(EnchantOrder.parse(JsonParser.parseString(BLOCK.replace("\"bottles\": 14", "\"bottles\": 2048"))).malformed());
    }

    @Test
    void aJournalWithABadPlanOrBrokenPartsIsNotFollowed() {
        EnchantOrder.Block b = block();
        EnchantLedger l = new EnchantLedger(b);
        l.begin(0, b.items().get(0).plan());
        l.stepDone(0, 0);
        l.charge(EnchantOrder.BOTTLE_KEY, 5);
        JsonObject saved = l.toSaved();
        // a step that points at an item that does not exist yet: the item can't be resumed (short, wrong_result), the rest of the record is taken
        JsonObject badStep = JsonParser.parseString(saved.toString()).getAsJsonObject();
        badStep.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("plan").getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("right", 9);
        EnchantLedger a = new EnchantLedger(b);
        a.restore(badStep);
        assertEquals(EnchantLedger.Status.SHORT, a.item(0).status);
        assertEquals(Codes.WRONG_RESULT, a.item(0).reason);
        assertNull(a.item(0).real);
        assertEquals(9, a.remaining(EnchantOrder.BOTTLE_KEY));
        // a plan that does not replay (a wrong cost)
        JsonObject wrongCost = JsonParser.parseString(saved.toString()).getAsJsonObject();
        wrongCost.getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("plan").getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("cost", 30);
        EnchantLedger w = new EnchantLedger(b);
        w.restore(wrongCost);
        assertEquals(EnchantLedger.Status.SHORT, w.item(0).status);
        // steps done are capped to the plan's steps
        JsonObject many = JsonParser.parseString(saved.toString()).getAsJsonObject();
        many.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("done", 99);
        EnchantLedger m = new EnchantLedger(b);
        m.restore(many);
        assertEquals(1, m.item(0).stepsDone);
        assertEquals(EnchantLedger.Status.RUNNING, m.item(0).status);
        // a record that is broken anywhere applies nothing at all: the ledger stays fresh
        JsonObject broken = JsonParser.parseString(saved.toString()).getAsJsonObject();
        broken.add("returned", com.google.gson.JsonNull.INSTANCE);
        EnchantLedger f = new EnchantLedger(b);
        f.restore(broken);
        assertEquals(EnchantLedger.Status.PENDING, f.item(0).status);
        assertEquals(14, f.remaining(EnchantOrder.BOTTLE_KEY));
        assertEquals(0, f.bottlesUsed());
        // the top-up count is kept
        l.addTopped(0, 7);
        EnchantLedger t = new EnchantLedger(b);
        t.restore(JsonParser.parseString(l.toSaved().toString()).getAsJsonObject());
        assertEquals(7, t.topped(0));
    }

    @Test
    void failNotStartedLeavesAnItemPastItsFirstStepAlone() {
        EnchantLedger l = new EnchantLedger(block());
        l.begin(0, l.block().items().get(0).plan());
        l.stepDone(0, 0);
        l.failNotStarted(Codes.MODULE_CONFLICT);
        assertEquals(EnchantLedger.Status.RUNNING, l.item(0).status);
        EnchantLedger before = new EnchantLedger(block());
        before.begin(0, before.block().items().get(0).plan());
        before.failNotStarted(Codes.MODULE_CONFLICT);
        assertEquals(EnchantLedger.Status.SHORT, before.item(0).status);
    }
}
