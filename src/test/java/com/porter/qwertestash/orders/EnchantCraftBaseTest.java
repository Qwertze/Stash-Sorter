package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crafting a missing base for an enchant order (ENCHANT-CONTRACT draft 3.4, item 1.9): {@code craft.forEnchant} and {@code crafted} are read, the craft runs first on the
 * existing craft engine, the crafted item is the plan's base (nothing taken for it, counted in the carry formula, planned again with the real stack), unused bases go
 * back with the leftovers, a craft that came up short makes the plans that need it short with {@code no_base}, and a restart between the craft and the enchanting does
 * not craft again. The world is fake ({@link OrderSim} for the order, {@link EnchantSim} for the anvil); the engines are the real ones.
 */
class EnchantCraftBaseTest {
    private static final String MC = "1.21.4", SWORD = "minecraft:diamond_sword", DIAMOND = "minecraft:diamond", LOG = "minecraft:spruce_log",
        PLANKS = "minecraft:spruce_planks", STICK = "minecraft:stick", BOOK = "minecraft:enchanted_book#a1111111", RED = "minecraft:red_shulker_box";

    /** A small recipe table: a sword from two diamonds and a stick, sticks from planks, planks from a log. */
    private static final String RECIPES = """
        { "version": "1.21.4", "format": 1, "recipes": [
          { "id": "minecraft:diamond_sword", "type": "shaped", "w": 1, "h": 3,
            "cells": [ [ "minecraft:diamond" ], [ "minecraft:diamond" ], [ "minecraft:stick" ] ], "result": "minecraft:diamond_sword", "count": 1 },
          { "id": "minecraft:stick", "type": "shaped", "w": 1, "h": 2,
            "cells": [ [ "minecraft:spruce_planks" ], [ "minecraft:spruce_planks" ] ], "result": "minecraft:stick", "count": 4 },
          { "id": "minecraft:spruce_planks", "type": "shapeless", "ingredients": [ [ "minecraft:spruce_log" ] ], "result": "minecraft:spruce_planks", "count": 4 }
        ] }
        """;
    private static final Recipes R = Recipes.fromJson(JsonParser.parseString(RECIPES).getAsJsonObject());

    // The order

    private static String planJson(boolean crafted) {
        return "{ \"materials\": [ { \"kind\": \"base\", \"key\": \"" + SWORD + "\", \"ench\": [], \"rc\": 0" + (crafted ? ", \"crafted\": true" : "") + " },"
            + " { \"kind\": \"book\", \"key\": \"" + BOOK + "\", \"ench\": [ { \"id\": \"minecraft:sharpness\", \"lvl\": 5 } ], \"rc\": 0 } ],"
            + " \"steps\": [ { \"left\": 0, \"right\": 1, \"cost\": 5, \"rc\": 1 } ], \"levels\": [ { \"id\": \"minecraft:sharpness\", \"lvl\": 5 } ],"
            + " \"extras\": [], \"curse\": [], \"totalLevels\": 5, \"points\": 55, \"bottles\": 14, \"finalRc\": 1 }";
    }

    /** An enchant block with one plan per item, the base of each crafted or not; the take has no crafted base (and a real one for the others). */
    private static String blockJson(boolean... crafted) {
        int items = crafted.length, real = 0;
        StringBuilder plans = new StringBuilder();
        for (int i = 0; i < items; i++) {
            if (i > 0) plans.append(",");
            plans.append(planJson(crafted[i]));
            if (!crafted[i]) real++;
        }
        StringBuilder take = new StringBuilder();
        if (real > 0) take.append("{ \"key\": \"" + SWORD + "\", \"count\": " + real + " },");
        take.append("{ \"key\": \"" + BOOK + "\", \"count\": " + items + " }, { \"key\": \"minecraft:experience_bottle\", \"count\": " + (14 * items)
            + " }, { \"key\": \"minecraft:anvil\", \"count\": " + items + " }");
        return "{ \"v\": 1, \"mc\": \"1.21.4\", \"lines\": [ { \"key\": \"" + SWORD + "\", \"count\": " + items + ", \"enchants\": [ { \"id\": \"minecraft:sharpness\", \"lvl\": 5 } ],"
            + " \"plans\": [" + plans + "] } ], \"take\": [" + take + "], \"curse\": [] }";
    }

    private static EnchantOrder.Block block(boolean... crafted) {
        EnchantOrder.Block b = EnchantOrder.parse(JsonParser.parseString(blockJson(crafted)));
        assertNotNull(b);
        assertFalse(b.malformed());
        return b;
    }

    private static Order.Qty q(String id, int n) {
        return new Order.Qty(id, n);
    }

    /** The craft block the server would make: {@code total} swords from the stash's diamonds and logs, {@code forEnchant} of them for the enchant plans. */
    private static Order.Craft craftBlock(int total, int forEnchant) {
        Map<String, Long> pool = Map.of(DIAMOND, 100L, LOG, 100L);
        CraftSub.Session s = new CraftSub.Session(R, null, pool, pool, Map.of());
        CraftSub.Outcome o = s.plan(SWORD, total);
        assertTrue(o.ok(), String.valueOf(o.plan().refusal()));
        List<Order.CraftStep> steps = new ArrayList<>();
        long fills = 0;
        for (CraftPlanner.Step st : o.plan().steps()) {
            List<Order.Qty> in = new ArrayList<>();
            st.uses().forEach((k, v) -> in.add(q(k, (int) (long) v)));
            steps.add(new Order.CraftStep(st.recipe().id(), st.recipe().result(), st.recipe().count(), st.fills(), in));
            fills += st.fills();
        }
        List<Order.Qty> take = new ArrayList<>();
        o.plan().fromStock().forEach((k, v) -> take.add(q(k, (int) (long) v)));
        List<Order.Qty> left = new ArrayList<>();
        s.surplus().forEach((k, v) -> left.add(q(k, (int) (long) v)));
        return new Order.Craft(1, MC, R.hash(), List.of(q(SWORD, total)), steps, take, left, (int) fills, false, forEnchant > 0 ? List.of(q(SWORD, forEnchant)) : List.of());
    }

    private static Order order(String id, Order.Craft craft, boolean... crafted) {
        return new Order(id, List.of(new Order.Line(Order.ENCHANT, SWORD, crafted.length)), null, false, craft, null, block(crafted));
    }

    // Parsing

    @Test
    void forEnchantAndCraftedAreReadAndTheOrderHasACraftStageWithoutCraftLines() {
        JsonObject o = JsonParser.parseString("{\"id\":\"o-1\",\"lines\":[{\"type\":\"enchant\",\"key\":\"" + SWORD + "\",\"count\":1,"
            + "\"enchants\":[{\"id\":\"minecraft:sharpness\",\"lvl\":5}]}],\"enchant\":" + blockJson(true) + ",\"craft\":{\"v\":1,\"mc\":\"1.21.4\",\"recipes\":\"" + "a".repeat(40)
            + "\",\"lines\":[{\"key\":\"" + SWORD + "\",\"count\":1}],\"steps\":[],\"take\":[{\"id\":\"" + DIAMOND + "\",\"count\":2}],\"leftovers\":[],\"fills\":3,"
            + "\"forEnchant\":[{\"key\":\"" + SWORD + "\",\"count\":1}]}}").getAsJsonObject();
        Order p = Order.parse(o);
        assertNotNull(p);
        assertNotNull(p.craft);
        assertFalse(p.craft.malformed());
        assertEquals(List.of(q(SWORD, 1)), p.craft.forEnchant());
        // No craft order line, and still a craft stage: the bases are made
        assertTrue(p.craftLines().isEmpty());
        assertTrue(p.hasCraft());
        assertTrue(p.hasEnchantBases());
        assertEquals(List.of(new Order.Line(Order.CRAFT, SWORD, 1)), p.craftWanted());
        // The plan's base is the crafted one, a plain item that is not in the take
        EnchantOrder.ItemPlan plan = p.enchant.items().get(0).plan();
        assertTrue(plan.baseCrafted());
        assertEquals(SWORD, plan.baseKey());
        assertFalse(plan.materials().get(1).crafted());
        assertFalse(p.enchant.takeMap().containsKey(SWORD));
    }

    @Test
    void withoutThoseFieldsNothingChanges() {
        JsonObject o = JsonParser.parseString("{\"id\":\"o-1\",\"lines\":[{\"type\":\"enchant\",\"key\":\"" + SWORD + "\",\"count\":1,"
            + "\"enchants\":[{\"id\":\"minecraft:sharpness\",\"lvl\":5}]}],\"enchant\":" + blockJson(false) + "}").getAsJsonObject();
        Order p = Order.parse(o);
        assertNotNull(p);
        assertNull(p.craft);
        assertFalse(p.hasCraft());
        assertFalse(p.hasEnchantBases());
        assertTrue(p.craftWanted().isEmpty());
        assertFalse(p.enchant.items().get(0).plan().baseCrafted());
        // A craft block with no forEnchant (every craft order before draft 3.4) has an empty list
        Order.Craft c = craftBlock(2, 0);
        assertTrue(c.forEnchant().isEmpty());
        assertEquals(List.of(), new Order.Craft(1, MC, R.hash(), List.of(), List.of(), List.of(), List.of(), 0, false).forEnchant());
    }

    @Test
    void aForEnchantThatIsThereButWrongMakesTheCraftBlockUnreadable() {
        String base = "{\"v\":1,\"mc\":\"1.21.4\",\"recipes\":\"" + "a".repeat(40) + "\",\"lines\":[{\"key\":\"" + SWORD + "\",\"count\":1}],\"steps\":[],\"take\":[],\"leftovers\":[],\"fills\":0";
        assertFalse(Order.parseCraft(JsonParser.parseString(base + ",\"forEnchant\":[{\"key\":\"" + SWORD + "\",\"count\":1}]}")).malformed());
        assertTrue(Order.parseCraft(JsonParser.parseString(base + ",\"forEnchant\":\"many\"}")).malformed());
        assertTrue(Order.parseCraft(JsonParser.parseString(base + ",\"forEnchant\":[{\"key\":\"" + SWORD + "\",\"count\":0}]}")).malformed());
        assertTrue(Order.parseCraft(JsonParser.parseString(base + ",\"forEnchant\":[{\"key\":\"" + SWORD + "#ab12\",\"count\":1}]}")).malformed());
        assertTrue(Order.parseCraft(JsonParser.parseString(base + ",\"forEnchant\":[{\"key\":\"" + SWORD + "\",\"count\":17}]}")).malformed());
        // null is the same as absent
        assertFalse(Order.parseCraft(JsonParser.parseString(base + ",\"forEnchant\":null}")).malformed());
    }

    @Test
    void aCraftedBaseIsAFreshPlainItem() {
        // enchanted, repair cost, an index key or a book marked crafted: not a plan this bot follows (the block is unreadable)
        assertTrue(EnchantOrder.parse(JsonParser.parseString(blockJson(true).replace("\"ench\": [], \"rc\": 0, \"crafted\": true", "\"ench\": [ { \"id\": \"minecraft:sharpness\", \"lvl\": 1 } ], \"rc\": 0, \"crafted\": true"))).malformed());
        assertTrue(EnchantOrder.parse(JsonParser.parseString(blockJson(true).replace("\"ench\": [], \"rc\": 0, \"crafted\": true", "\"ench\": [], \"rc\": 1, \"crafted\": true"))).malformed());
        assertTrue(EnchantOrder.parse(JsonParser.parseString(blockJson(true).replace("\"key\": \"" + SWORD + "\", \"ench\"", "\"key\": \"" + SWORD + "#ab12\", \"ench\""))).malformed());
        assertTrue(EnchantOrder.parse(JsonParser.parseString(blockJson(true).replace("\"rc\": 0 } ]", "\"rc\": 0, \"crafted\": true } ]"))).malformed());
        // crafted: false and crafted missing are the same
        assertFalse(EnchantOrder.parse(JsonParser.parseString(blockJson(false).replace("\"rc\": 0 }", "\"rc\": 0, \"crafted\": false }"))).malformed());
    }

    @Test
    void theCraftCheckKnowsForEnchantIsPartOfTheLines() {
        Order.Craft good = craftBlock(2, 2);
        assertNull(CraftOrder.check(good, R, 2048));
        Order.Craft more = new Order.Craft(1, MC, R.hash(), good.lines(), good.steps(), good.take(), good.leftovers(), good.fills(), false, List.of(q(SWORD, 3)));
        assertNotNull(CraftOrder.check(more, R, 2048));
        Order.Craft other = new Order.Craft(1, MC, R.hash(), good.lines(), good.steps(), good.take(), good.leftovers(), good.fills(), false, List.of(q("minecraft:stick", 1)));
        assertNotNull(CraftOrder.check(other, R, 2048));
        Order.Craft none = new Order.Craft(1, MC, R.hash(), good.lines(), good.steps(), good.take(), good.leftovers(), good.fills(), false, List.of(q(SWORD, 0)));
        assertNotNull(CraftOrder.check(none, R, 2048));
    }

    // The preflight

    private static EnchantItems.Held held(int slot, String key, String id, boolean book, Map<String, Integer> ench) {
        return new EnchantItems.Held(slot, key, id, 1, book, ench, 0, false);
    }

    private static List<EnchantItems.Held> materials(int bottles) {
        List<EnchantItems.Held> owned = new ArrayList<>();
        owned.add(held(10, BOOK, "enchanted_book", true, Map.of("sharpness", 5)));
        owned.add(new EnchantItems.Held(11, EnchantOrder.BOTTLE_KEY, "experience_bottle", bottles, false, Map.of(), 0, false));
        owned.add(held(12, "minecraft:anvil", "anvil", false, Map.of()));
        return owned;
    }

    @Test
    void nothingIsTakenFromTheStashForACraftedBase() {
        EnchantLedger ledger = new EnchantLedger(block(true));
        EnchantPreflight pre = new EnchantPreflight(ledger.block().items().get(0), MC, Set.of(), ledger, () -> 1_000L);
        // Nothing held yet: the preflight asks for the book, the bottles and the anvil, never for the sword
        EnchantPreflight.Gather g = assertInstanceOf(EnchantPreflight.Gather.class, pre.decide(List.of(), List.of(held(20, SWORD, "diamond_sword", false, Map.of())), 30));
        for (EnchantPreflight.Lot lot : g.lots()) assertFalse(lot.key().equals(SWORD), g.lots().toString());
        assertEquals(3, g.lots().size());
        // The same with everything held: ready, and the plan was made again with the crafted stack as the base
        EnchantPreflight pre2 = new EnchantPreflight(ledger.block().items().get(0), MC, Set.of(), ledger, () -> 1_000L);
        EnchantPreflight.Ready ready = assertInstanceOf(EnchantPreflight.Ready.class,
            pre2.decide(materials(14), List.of(held(20, SWORD, "diamond_sword", false, Map.of())), 30));
        assertEquals(SWORD, ready.real().baseKey());
        assertEquals(1, ready.real().steps().size());
        assertEquals(55, ready.real().points());
        // Not one sword came out of the order's budget (it has none)
        assertEquals(0, ledger.remaining(SWORD));
        assertEquals(0, ledger.taken(SWORD));
    }

    @Test
    void aCraftedBaseIsNeverChargedToTheBudgetOfARealBaseOfTheSameKey() {
        // item 1 uses a real sword from the stash (take has one), item 0 the crafted one: same key, only the real one is charged
        EnchantLedger ledger = new EnchantLedger(block(true, false));
        EnchantPreflight pre = new EnchantPreflight(ledger.block().items().get(0), MC, Set.of(), ledger, () -> 1_000L);
        assertInstanceOf(EnchantPreflight.Ready.class, pre.decide(materials(14), List.of(held(20, SWORD, "diamond_sword", false, Map.of())), 30));
        assertEquals(1, ledger.remaining(SWORD), "the crafted base did not eat the real one's budget");
        EnchantPreflight pre1 = new EnchantPreflight(ledger.block().items().get(1), MC, Set.of(), ledger, () -> 1_000L);
        List<EnchantItems.Held> owned = new ArrayList<>(materials(14));
        owned.add(held(13, SWORD, "diamond_sword", false, Map.of()));
        assertInstanceOf(EnchantPreflight.Ready.class, pre1.decide(owned, List.of(held(20, SWORD, "diamond_sword", false, Map.of())), 30));
        assertEquals(0, ledger.remaining(SWORD));
    }

    @Test
    void withoutACraftedBaseInHandThePlanIsRefusedAsNoBase() {
        EnchantLedger ledger = new EnchantLedger(block(true));
        EnchantPreflight pre = new EnchantPreflight(ledger.block().items().get(0), MC, Set.of(), ledger, () -> 1_000L);
        EnchantPreflight.Refuse r = assertInstanceOf(EnchantPreflight.Refuse.class, pre.decide(materials(14), List.of(), 30));
        assertEquals(Codes.NO_BASE, r.code());
        // A stack that is not a fresh sword is no base: enchanted, damaged repair cost, named, or another item
        for (EnchantItems.Held bad : List.of(
            held(20, SWORD, "diamond_sword", false, Map.of("sharpness", 1)),
            new EnchantItems.Held(20, SWORD, "diamond_sword", 1, false, Map.of(), 2, false),
            new EnchantItems.Held(20, SWORD, "diamond_sword", 1, false, Map.of(), 0, true),
            held(20, "minecraft:iron_sword", "iron_sword", false, Map.of()))) {
            assertEquals(Codes.NO_BASE, assertInstanceOf(EnchantPreflight.Refuse.class, pre.decide(materials(14), List.of(bad), 30)).code());
        }
        // A crafted base in hand does not stand in for a plan whose base is a real one: that plan still gathers it
        EnchantLedger real = new EnchantLedger(block(false));
        EnchantPreflight pre2 = new EnchantPreflight(real.block().items().get(0), MC, Set.of(), real, () -> 1_000L);
        EnchantPreflight.Gather g = assertInstanceOf(EnchantPreflight.Gather.class, pre2.decide(materials(14), List.of(held(20, SWORD, "diamond_sword", false, Map.of())), 30));
        assertEquals(List.of(new EnchantPreflight.Lot(SWORD, 1)), g.lots());
    }

    @Test
    void theCraftedBaseCountsInTheCarryFormula() {
        EnchantLedger ledger = new EnchantLedger(block(true));
        EnchantOrder.ItemPlan plan = ledger.block().items().get(0).plan();
        // base 1 + 1 book + 1 anvil + 1 source shulker + 1 stack of bottles
        int needed = EnchantCarry.slotsNeeded(plan.books(), 1, plan.bottles());
        assertEquals(5, needed);
        List<EnchantItems.Held> base = List.of(held(20, SWORD, "diamond_sword", false, Map.of()));
        assertInstanceOf(EnchantPreflight.Ready.class, new EnchantPreflight(ledger.block().items().get(0), MC, Set.of(), ledger, () -> 1_000L).decide(materials(14), base, needed));
        EnchantPreflight.Refuse full = assertInstanceOf(EnchantPreflight.Refuse.class,
            new EnchantPreflight(ledger.block().items().get(0), MC, Set.of(), ledger, () -> 1_000L).decide(materials(14), base, needed - 1));
        assertEquals(Codes.INVENTORY_FULL, full.code());
    }

    // The order, end to end

    /** The enchant side of the sim: the anvil work is the real preflight and the real trip over {@link EnchantSim}; the stacks mirror what the order carries. */
    static final class Anvil {
        final OrderSim w;
        final String bookKey;
        final int bottles;
        final EnchantSim sim = new EnchantSim(SWORD);
        final List<String> outcomes = new ArrayList<>(), codes = new ArrayList<>();
        int trips;
        /** What the flow said it carried of made bases at each item (for the checks). */
        final List<Integer> basesInHand = new ArrayList<>();
        /** Craft chunks made when each item began: the craft came first when this is the full count at the first item. */
        final List<Integer> chunksAtItem = new ArrayList<>();

        Anvil(OrderSim w, int items) {
            this(w, items, BOOK, 14);
        }

        Anvil(OrderSim w, int items, String bookKey, int bottles) {
            this.w = w;
            this.bookKey = bookKey;
            this.bottles = bottles;
            sim.bottles = bottles * items;
            sim.xp(0);
            for (int i = 0; i < items; i++) sim.inv.put(40 + i, held(40 + i, bookKey, "enchanted_book", true, Map.of("sharpness", 5)));
            sim.inv.put(60, held(60, "minecraft:anvil", "anvil", false, Map.of()));
        }

        void handle(OrderFlow.EnchantLoads el) {
            OrderFlow flow = w.flow;
            Unit u = el.unit();
            int n = u.lots.get(u.nextEnchantLot()).enchantItem();
            EnchantLedger ledger = flow.enchantLedger();
            EnchantOrder.ItemRef ref = ledger.block().items().get(n);
            basesInHand.add(flow.madeBases().getOrDefault(SWORD, 0));
            chunksAtItem.add(w.craftChunks);
            // The bases the order carries, mirrored into the anvil's inventory (the flow knows exactly the unused ones)
            sim.inv.entrySet().removeIf(e -> e.getKey() >= 70 && e.getKey() < 80);
            List<EnchantItems.Held> made = new ArrayList<>();
            for (int i = 0; i < flow.madeBases().getOrDefault(SWORD, 0); i++) {
                EnchantItems.Held h = held(70 + i, SWORD, "diamond_sword", false, Map.of());
                sim.inv.put(h.slot(), h);
                made.add(h);
            }
            List<EnchantItems.Held> owned = new ArrayList<>(List.of(held(40 + n, bookKey, "enchanted_book", true, Map.of("sharpness", 5)),
                new EnchantItems.Held(61, EnchantOrder.BOTTLE_KEY, "experience_bottle", bottles, false, Map.of(), 0, false), held(60, "minecraft:anvil", "anvil", false, Map.of())));
            if (!ref.plan().baseCrafted()) {
                EnchantItems.Held real = held(80, SWORD, "diamond_sword", false, Map.of());
                owned.add(real);
                sim.inv.put(80, real);
            }
            EnchantPreflight pre = new EnchantPreflight(ref, MC, Set.of(), ledger, () -> 1_000L);
            EnchantPreflight.Decision d = pre.decide(owned, made, 30);
            if (d instanceof EnchantPreflight.Refuse r) {
                codes.add(r.code());
                flow.enchantItemShort(u, n, r.code());
                return;
            }
            EnchantOrder.ItemPlan real = assertInstanceOf(EnchantPreflight.Ready.class, d).real();
            ledger.begin(n, real);
            trips++;
            EnchantTrip trip = new EnchantTrip(sim, MC, SWORD, real, 0);
            sim.run(trip, 5_000);
            assertTrue(trip.done());
            for (int s : sim.stepsDone) ledger.stepDone(n, s);
            sim.stepsDone.clear();
            EnchantTrip.Outcome o = trip.outcome();
            outcomes.add(o.kind().name());
            if (!o.finished()) {
                flow.enchantItemShort(u, n, o.reason() == null ? Codes.WRONG_RESULT : o.reason());
                return;
            }
            // The finished sword is in the anvil's inventory; the order's carry follows what the flow's lots say
            EnchantItems.Held done = sim.stacks().stream().filter(h -> o.spec().matches(h) && !h.book()).findFirst().orElseThrow();
            String key = SWORD + "#fin" + n;
            sim.inv.remove(done.slot());
            if (ref.plan().baseCrafted()) w.craftCarry.merge(SWORD, -1L, Long::sum);
            w.craftCarry.merge(key, 1L, Long::sum);
            flow.enchantItemDone(u, n, key, key + "00", Map.of("sharpness", 5));
        }
    }

    private static OrderSim world(int diamonds, int logs) {
        OrderSim w = new OrderSim().craftOrders(R);
        w.craftStash.put(DIAMOND, (long) diamonds);
        w.craftStash.put(LOG, (long) logs);
        w.craftStack.put(SWORD, 1);
        w.empties.put(RED, 5);
        return w;
    }

    private static Map<String, Long> ids(JsonArray a, String idName) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (JsonElement e : a) out.put(e.getAsJsonObject().get(idName).getAsString(), e.getAsJsonObject().get("count").getAsLong());
        return out;
    }

    @Test
    void theBaseIsCraftedFirstThenUsedAndNeitherTheBaseNorAnythingOfItIsDelivered() {
        OrderSim w = world(10, 4);
        Anvil anvil = new Anvil(w, 2);
        w.enchantHandler = anvil::handle;
        Order o = order("o-craft", craftBlock(2, 2), true, true);
        assertNull(w.flow.offer(o));
        // The bases are loads of the one bin that has the enchant items, flagged as bases: still to be made
        Unit u = w.flow.units().get(0);
        assertEquals(2, u.lots.stream().filter(Order.Lot::enchant).count());
        assertEquals(2, u.lots.stream().filter(l -> l.base() && l.craft()).count());
        // The craft block is the order's: its ledger counts the bases as what is wanted
        assertEquals(2, w.flow.craftLedger().wanted(SWORD));
        w.run(200);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        // Both swords were made before the first item began (the craft came first), and all enchant items ran
        assertEquals(List.of(2, 1), anvil.basesInHand);
        assertEquals(2, anvil.chunksAtItem.get(0));
        assertEquals(List.of("FINISHED", "FINISHED"), anvil.outcomes);
        // What was delivered is the two finished swords: no base in a shulker
        assertEquals(1, w.delivery.size());
        for (var e : w.deliveredTotals().entrySet()) assertTrue(e.getKey().contains("#fin") || e.getKey().equals(SWORD), e.toString());
        assertEquals(2, w.deliveredTotals().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(2, r.delivered().getOrDefault(SWORD, 0), r.delivered().toString());
        // result.craft as for any craft order: 2 wanted, 2 made, nothing short, nothing lost, nothing given back, the diamonds and the log used
        JsonObject craft = r.craft();
        assertNotNull(craft);
        JsonObject made = craft.getAsJsonArray("made").get(0).getAsJsonObject();
        assertEquals(SWORD, made.get("key").getAsString());
        assertEquals(2, made.get("wanted").getAsInt());
        assertEquals(2, made.get("made").getAsInt());
        assertEquals(0, craft.getAsJsonArray("short").size());
        assertEquals(4L, ids(craft.getAsJsonArray("used"), "id").get(DIAMOND));
        assertNull(ids(craft.getAsJsonArray("returned"), "id").get(SWORD), "no base went back: both were used");
        assertFalse(r.message().contains("not delivered"), r.message());
        assertFalse(r.message().contains("not crafted"), r.message());
        // result.enchant: both made
        assertEquals(2, r.enchant().getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        assertTrue(w.basesReleased.isEmpty());
    }

    @Test
    void aBaseTheEnchantPlansDidNotUseGoesBackWithTheLeftovers() {
        // two bases are crafted but only one plan needs one (a real base turned up for the other): the spare is let go with the bin and returned
        OrderSim w = world(10, 4);
        Anvil anvil = new Anvil(w, 2);
        w.enchantHandler = anvil::handle;
        assertNull(w.flow.offer(order("o-spare", craftBlock(2, 2), true, false)));
        w.run(200);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(List.of("FINISHED", "FINISHED"), anvil.outcomes);
        // one made base was left when the bin was let go: its stack is surplus of the craft now
        assertEquals(1, w.basesReleased.size());
        assertEquals(SWORD, w.basesReleased.get(0).key());
        assertTrue(w.basesReleased.get(0).crafted());
        assertEquals(2, w.deliveredTotals().values().stream().mapToInt(Integer::intValue).sum(), "no spare base in the shulker: " + w.deliveredTotals());
        JsonObject craft = r.craft();
        assertNotNull(craft);
        assertEquals(2, craft.getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        assertEquals(1L, ids(craft.getAsJsonArray("returned"), "id").get(SWORD), craft.toString());
        // the bases are not "lost" crafted items
        assertFalse(r.message().contains("not delivered"), r.message());
    }

    @Test
    void aCraftThatFailsMakesOnlyThePlansThatNeedItShortWithNoBase() {
        // no diamonds: the sword can't be made; item 0 needs the crafted base, item 1 has a real one and goes on
        OrderSim w = world(0, 4);
        Anvil anvil = new Anvil(w, 2);
        w.enchantHandler = anvil::handle;
        assertNull(w.flow.offer(order("o-fail", craftBlock(2, 2), true, false)));
        w.run(200);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(List.of(Codes.NO_BASE), anvil.codes);
        assertEquals(List.of("FINISHED"), anvil.outcomes);
        // result.craft: the sword is short with the craft's own reason; result.enchant: item 0 short no_base, one made
        JsonObject craft = r.craft();
        assertNotNull(craft);
        JsonObject sh = craft.getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals(SWORD, sh.get("key").getAsString());
        assertEquals(2, sh.get("count").getAsInt());
        assertEquals("ingredients_short", sh.get("reason").getAsString());
        JsonObject enchant = r.enchant();
        assertEquals(1, enchant.getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        assertEquals(Codes.NO_BASE, enchant.getAsJsonArray("short").get(0).getAsJsonObject().get("reason").getAsString());
        assertTrue(r.message().contains("1 item not enchanted: no_base"), r.message());
        assertTrue(r.message().contains("not crafted"), r.message());
        assertEquals(1, w.deliveredTotals().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void aCraftBlockTheBotCannotCheckIsTheSameAsAFailedCraft() {
        OrderSim w = world(10, 4);
        Anvil anvil = new Anvil(w, 2);
        w.enchantHandler = anvil::handle;
        Order.Craft good = craftBlock(2, 2);
        Order.Craft bad = new Order.Craft(1, MC, "0".repeat(40), good.lines(), good.steps(), good.take(), good.leftovers(), good.fills(), false, good.forEnchant());
        assertNull(w.flow.offer(order("o-hash", bad, true, true)));
        // nothing was added to the bin to craft; nothing is crafted
        assertTrue(w.flow.units().get(0).lots.stream().noneMatch(Order.Lot::base));
        w.run(100);
        OrderFlow.Report r = w.flow.report();
        assertEquals("failed", r.status(), r.message());
        assertTrue(r.message().contains("recipe_mismatch") && r.message().contains("no_base"), r.message());
        assertEquals(0, w.craftChunks);
        assertEquals(List.of(Codes.NO_BASE, Codes.NO_BASE), anvil.codes);
        assertEquals("recipe_mismatch", r.craft().getAsJsonArray("short").get(0).getAsJsonObject().get("reason").getAsString());
        assertEquals(0, r.enchant().getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
    }

    @Test
    void aRestartBetweenTheCraftAndTheEnchantingDoesNotCraftAgain() {
        OrderSim w = world(10, 4);
        Order o = order("o-resume", craftBlock(2, 2), true, true);
        assertNull(w.flow.offer(o));
        // the bases are made, no enchant item has begun
        for (int i = 0; i < 20 && w.craftChunks < 2; i++) w.step();
        assertEquals(2, w.craftChunks);
        assertEquals(2, w.flow.madeBases().get(SWORD));
        assertEquals(0, w.enchantRuns);
        long made = w.flow.craftLedger().made(SWORD);
        assertEquals(2, made);
        long diamonds = w.craftStash.get(DIAMOND);
        // the saved plan carries the base loads as made, and the craft ledger
        assertTrue(w.saved.units.get(0).lotCraft.contains(Order.Lot.CRAFTED));
        assertTrue(w.saved.units.get(0).lotVariants.contains(Order.Lot.BASE_VARIANT));
        assertNotNull(w.saved.craft);
        // the bot restarts: a new look at the same order and the saved plan
        w.flow.reset();
        assertNull(w.flow.offer(o));
        assertEquals(2, w.flow.madeBases().get(SWORD), "the carried bases are the order's again");
        assertEquals(2, w.flow.craftLedger().made(SWORD));
        Anvil anvil = new Anvil(w, 2);
        w.enchantHandler = anvil::handle;
        w.run(200);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        // not one more chunk, nothing more taken from the stash
        assertEquals(2, w.craftChunks);
        assertEquals(diamonds, w.craftStash.get(DIAMOND));
        assertEquals(List.of(2, 1), anvil.basesInHand);
        assertEquals(List.of("FINISHED", "FINISHED"), anvil.outcomes);
        assertEquals(2, r.craft().getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
    }

    @Test
    void aRestartAfterTheFirstItemKeepsTheOneBaseThatIsLeft() {
        OrderSim w = world(10, 4);
        Order o = order("o-resume2", craftBlock(2, 2), true, true);
        assertNull(w.flow.offer(o));
        Anvil first = new Anvil(w, 2);
        w.enchantHandler = first::handle;
        for (int i = 0; i < 50 && first.outcomes.isEmpty(); i++) w.step();
        assertEquals(List.of("FINISHED"), first.outcomes);
        // one base was used (its lot is gone from the saved plan), one is left
        assertEquals(1, w.flow.madeBases().get(SWORD));
        assertEquals(1, w.saved.units.get(0).lotCraft.stream().filter(c -> c == Order.Lot.CRAFTED).count() - 1, "the finished sword's lot is made too");
        w.flow.reset();
        assertNull(w.flow.offer(o));
        assertEquals(1, w.flow.madeBases().get(SWORD));
        assertEquals(2, w.craftChunks);
        Anvil second = new Anvil(w, 2);
        second.sim.inv.remove(40);
        w.enchantHandler = second::handle;
        w.run(200);
        assertEquals("delivered", w.flow.report().status(), w.flow.report().message());
        assertEquals(List.of("FINISHED"), second.outcomes);
        assertEquals(2, w.craftChunks);
    }

    @Test
    void anOrderThatEndsBeforeTheEnchantingGivesTheMadeBasesBackAsSurplus() {
        OrderSim w = world(10, 4);
        assertNull(w.flow.offer(order("o-cancel", craftBlock(2, 2), true, true)));
        for (int i = 0; i < 20 && w.craftChunks < 2; i++) w.step();
        assertEquals(2, w.craftChunks);
        assertTrue(w.flow.cancel("o-cancel"));
        w.run(10);
        OrderFlow.Report r = w.flow.report();
        assertEquals("cancelled", r.status());
        // the bases are surplus of the craft at the end: returned and reported as such, not counted as crafted items that were not delivered
        assertEquals(2, w.basesReleased.stream().mapToInt(Order.Lot::count).sum());
        assertEquals(2L, ids(r.craft().getAsJsonArray("returned"), "id").get(SWORD), r.craft().toString());
        assertFalse(r.message().contains("crafted item(s) not delivered"), r.message());
    }

    @Test
    void anEnchantOnlyOrderWithNoBasesInTheCraftBlockIsHandledAsBefore() {
        OrderSim w = world(10, 4);
        Anvil anvil = new Anvil(w, 1);
        w.enchantHandler = anvil::handle;
        assertNull(w.flow.offer(new Order("o-plain", List.of(new Order.Line(Order.ENCHANT, SWORD, 1)), null, false, null, null, block(false))));
        w.run(100);
        assertEquals("delivered", w.flow.report().status(), w.flow.report().message());
        assertEquals(0, w.craftChunks);
        assertNull(w.flow.report().craft());
        assertNull(w.flow.craftLedger());
        assertEquals(List.of(0), anvil.basesInHand);
        Map<String, Long> unused = new HashMap<>();
        assertTrue(unused.isEmpty());
    }

    // Review fixes

    @Test
    void theSlotFormulaAgreesWithTheServersThreeBaseCase() {
        // server: need(item) = slotsNeeded(1 book, 2 anvil slots, bottles <= 64) + L, L = later crafted-base items: 6 + 2, 6 + 1, 6 + 0
        int base = EnchantCarry.slotsNeeded(1, 2, 14);
        assertEquals(6, base);
        for (int carry : new int[] {7, 8}) {
            for (int items = 1; items <= 3; items++) {
                boolean server = true;
                for (int i = 0; i < items; i++) {
                    int later = items - 1 - i;
                    server &= base + later <= carry;
                }
                if (carry == 7 && items == 3) assertFalse(server);
                if (carry == 7 && items == 2) assertTrue(server);
                if (carry == 8 && items == 3) assertTrue(server);
            }
        }
        // the first item is the tightest: free after 3 bases = carry - 3, plus its own base
        assertFalse(EnchantCarry.fits(1, 2, 14, 7 - 3 + EnchantCarry.ownBaseSlot(true, true)));
        assertTrue(EnchantCarry.fits(1, 2, 14, 7 - 2 + EnchantCarry.ownBaseSlot(true, true)));
        assertTrue(EnchantCarry.fits(1, 2, 14, 8 - 3 + EnchantCarry.ownBaseSlot(true, true)));
        // a plan whose base is not crafted gets nothing added back
        assertEquals(0, EnchantCarry.ownBaseSlot(false, true));
    }

    @Test
    void aMadeBaseIsNeverClaimedForAPlainBinAndAStaleSlotIsNotABase() {
        // slots 0-2 hold the three stash swords of three plain lines; slot 3 the crafted base; the crafted claim is slot 3 only
        Map<String, Integer> made = Map.of(SWORD, 1);
        List<BaseSlots.Stack> crafted = List.of(new BaseSlots.Stack(3, SWORD, true));
        assertEquals(List.of(3), BaseSlots.claim(made, crafted));
        // the plain bin sees only what is not claimed: exactly its three swords
        List<BinTracker.Held> held = new ArrayList<>();
        for (int slot = 0; slot <= 3; slot++) if (!BaseSlots.claim(made, crafted).contains(slot)) held.add(new BinTracker.Held(slot, SWORD, SWORD, 1));
        List<Order.Lot> lots = List.of(new Order.Lot(SWORD, SWORD, 1), new Order.Lot(SWORD, SWORD, 1), new Order.Lot(SWORD, SWORD, 1));
        assertEquals(3, BinTracker.ready(lots, List.of(), held).size());
        assertEquals(3, held.size());
        assertFalse(held.stream().anyMatch(h -> h.slot() == 3));
        // two crafted bases; item 0 finished into the slot it vacated (not fresh now): the claim is the other, fresh one
        List<BaseSlots.Stack> two = List.of(new BaseSlots.Stack(5, SWORD, false), new BaseSlots.Stack(6, SWORD, true));
        assertEquals(List.of(6), BaseSlots.claim(Map.of(SWORD, 1), two));
        // never more than the made loads say
        assertEquals(List.of(), BaseSlots.claim(Map.of(), List.of(new BaseSlots.Stack(6, SWORD, true))));
    }

    @Test
    void anItemThatEndsShortKeepsItsBaseLoadAndTheCounterOfTheBasesIsItsOwn() {
        OrderSim w = world(10, 4);
        assertNull(w.flow.offer(order("o-short", craftBlock(2, 2), true, true)));
        Unit u = w.flow.units().get(0);
        w.flow.craftLoadsStarted(u);
        assertEquals(1, u.baseLoadRounds);
        assertEquals(0, u.craftLoadRounds);
        for (int i = 0; i < 20 && w.craftChunks < 2; i++) w.step();
        assertEquals(2, w.flow.madeBases().get(SWORD));
        EnchantLedger l = w.flow.enchantLedger();
        l.begin(0, l.block().items().get(0).plan());
        l.stepDone(0, 0);
        w.flow.enchantItemShort(u, 0, Codes.WRONG_RESULT);
        // the first step may have been a book merge: the base is not assumed used
        assertEquals(2, w.flow.madeBases().get(SWORD));
        assertEquals(1, l.shortCount());
    }
}
