package com.porter.qwertestash.orders;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.KitRouting;
import com.porter.qwertestash.stash.KitTemplate;
import com.porter.qwertestash.stash.Stash.Box;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fixes of the 1.16.0 code and security reviews (M1 to M4, L1 to L6, K2 to K4, T1 to T3). */
class KitReviewFixesTest {
    private static final String D = "minecraft:diamond", P = "minecraft:ender_pearl", C = "minecraft:end_crystal", TOTEM = "minecraft:totem_of_undying";

    private static KitTemplate template(String id, String name, Object... slotsDefs) {
        List<KitTemplate.Slot> slots = new ArrayList<>();
        for (int i = 0; i < slotsDefs.length; i += 2) slots.add(new KitTemplate.Slot((String) slotsDefs[i], (String) slotsDefs[i], (Integer) slotsDefs[i + 1]));
        while (slots.size() < 27) slots.add(null);
        return new KitTemplate(id, name, "pvp", null, null, 0, slots);
    }

    private static KitOrder.Snapshot snap(KitTemplate... ts) {
        List<KitOrder.Line> lines = new ArrayList<>();
        Map<String, Integer> take = new HashMap<>();
        for (KitTemplate t : ts) {
            lines.add(new KitOrder.Line(t, 1, 0, 1));
            for (KitTemplate.Slot s : t.slots()) if (s != null) take.merge(s.match(), s.count(), Integer::sum);
        }
        List<KitOrder.Take> list = new ArrayList<>();
        take.forEach((k, v) -> list.add(new KitOrder.Take(k, v * 4)));
        return new KitOrder.Snapshot(1, lines, list, false);
    }

    private static Item stack(String id, int count) {
        Item it = new Item();
        it.id = id;
        it.count = count;
        it.maxStack = 64;
        return it;
    }

    private static Box box(int x, Item... items) {
        BlockPos p = new BlockPos(x, 64, 0);
        Box b = new Box(p, new ArrayList<>(List.of(p)));
        b.contents = new Item[27];
        for (int i = 0; i < items.length; i++) b.contents[i] = items[i];
        b.readAt = 1;
        return b;
    }

    private static Item copy(String name, Item... inside) {
        Item s = new Item();
        s.id = "red_shulker_box";
        s.count = 1;
        s.maxStack = 1;
        s.customName = name != null;
        s.customText = name;
        for (int i = 0; i < inside.length; i++) {
            inside[i].slot = i;
            s.contents.add(inside[i]);
        }
        return s;
    }

    private static StockPlanner.Survey survey(Map<String, Long> loose) {
        Map<String, List<StockPlanner.Var>> vars = new HashMap<>();
        loose.forEach((k, v) -> vars.put(k, List.of(new StockPlanner.Var(k, v))));
        return new StockPlanner.Survey(loose, Map.of(), Set.of(), Set.of(), vars, Map.of());
    }

    private static KitPlanner.Scan scanOf(Map<String, String> matchOf) {
        return new KitPlanner.Scan(new java.util.HashSet<>(matchOf.keySet()), matchOf, new HashMap<>(), Map.of(), Map.of());
    }

    // M1: a base holding part of a slot is topped up

    @Test
    void aShortBuiltCopyIsToppedUpByTheMissingCountPerSlot() {
        KitTemplate t = template("t-0000aaaa", "Pack", TOTEM, 1, C, 64);
        KitOrder.Snapshot snap = snap(t);
        Item base = copy("Pack", stack("totem_of_undying", 1), stack("end_crystal", 40));
        Box target = box(7, base);
        KitPlanner.Scan scan = KitPlanner.scan(snap, List.of(target, box(1, stack("end_crystal", 100))), b -> true, x -> KitRouting.canonicalId(target));
        assertEquals(1, scan.base().get("t-0000aaaa").size(), "a 40-of-64 copy is an incomplete copy");
        KitPlanner.Plan plan = KitPlanner.plan(snap, scan, survey(Map.of(C, 100L)), id -> 64, 0, Set.of());
        assertEquals(1, plan.units().size());
        Unit u = plan.units().get(0);
        assertNotNull(u.kitBase, "built on the base, no empty shulker needed");
        assertFalse(u.kitShort);
        assertEquals(List.of(40, 24), u.lots.stream().filter(l -> l.key().equals(C)).map(Order.Lot::count).toList(), "what it holds and what is added");
        assertEquals(1, u.kitGathered, "one load gathered");
        // The shulker with the merged stack of 64 counts as holding both lots
        List<BinTracker.Held> merged = List.of(new BinTracker.Held(0, TOTEM, null, 1), new BinTracker.Held(1, C, null, 64));
        assertTrue(BinTracker.notPacked(u.lots, merged).isEmpty());
        // Before the merge only the held stack is there, and the 24 are still to come
        List<BinTracker.Held> before = List.of(new BinTracker.Held(0, TOTEM, null, 1), new BinTracker.Held(1, C, null, 40));
        assertEquals(1, BinTracker.notPacked(u.lots, before).size());
    }

    // M2: at most 27 stacks

    @Test
    void aSlotThatNeedsTwoVariantsPastTwentySevenStacksIsUnavailable() {
        List<Object> defs = new ArrayList<>();
        Map<String, Long> loose = new HashMap<>();
        Map<String, String> matchOf = new HashMap<>();
        for (int i = 0; i < 26; i++) {
            String id = "minecraft:item_" + (char) ('a' + i);
            defs.add(id);
            defs.add(1);
            loose.put(id, 5L);
            matchOf.put(id, id);
        }
        defs.add(D);
        defs.add(64);
        loose.put(D, 80L);
        matchOf.put(D, D);
        KitTemplate t = template("t-0000aaaa", "Big", defs.toArray());
        Map<String, List<StockPlanner.Var>> vars = new HashMap<>();
        loose.forEach((k, v) -> vars.put(k, List.of(new StockPlanner.Var(k, v))));
        vars.put(D, List.of(new StockPlanner.Var("x", 40), new StockPlanner.Var("y", 40)));
        StockPlanner.Survey sv = new StockPlanner.Survey(loose, Map.of(), Set.of(), Set.of(), vars, Map.of());
        KitPlanner.Plan plan = KitPlanner.plan(snap(t), scanOf(matchOf), sv, id -> 64, 1, Set.of());
        Unit u = plan.units().get(0);
        assertTrue(u.lots.size() <= 27, "never more than 27 stacks: " + u.lots.size());
        assertTrue(u.kitShort);
        assertEquals(D, u.kitMissing.get(0).key());
        assertEquals(64, u.kitMissing.get(0).count());
        // The same variant in two pieces is one stack
        Map<String, List<StockPlanner.Var>> one = new HashMap<>(vars);
        one.put(D, List.of(new StockPlanner.Var("x", 80)));
        KitPlanner.Plan merged = KitPlanner.plan(snap(t), scanOf(matchOf), new StockPlanner.Survey(loose, Map.of(), Set.of(), Set.of(), one, Map.of()), id -> 64, 1, Set.of());
        assertFalse(merged.units().get(0).kitShort);
        assertEquals(27, merged.units().get(0).lots.size());
    }

    // M3: a kit that is not built gives back what it drew

    @Test
    void aKitThatIsNotBuiltReturnsWhatItDrewToTheNextKit() {
        KitTemplate a = template("t-0000aaaa", "A", TOTEM, 1, C, 1);
        KitTemplate b = template("t-0000bbbb", "B", TOTEM, 1, P, 1);
        Map<String, Long> stock = Map.of(TOTEM, 1L, P, 1L);
        Map<String, String> matchOf = Map.of(TOTEM, TOTEM, P, P);
        // A has only the totem (one kind): not started and its totem goes back; B can then be built
        KitPlanner.Plan plan = KitPlanner.plan(snap(a, b), scanOf(matchOf), survey(stock), id -> 64, 2, Set.of());
        assertEquals(1, plan.units().size());
        assertEquals("t-0000bbbb", plan.units().get(0).kitTemplate);
        assertFalse(plan.units().get(0).kitShort);
        assertEquals(KitResult.STORED_RETURNED, plan.shorts().get(0).stored());
        // No empty shulker for the first kit: it gives its items back for the second, which has none either
        KitPlanner.Plan none = KitPlanner.plan(snap(b, b), scanOf(matchOf), survey(stock), id -> 64, 1, Set.of());
        assertEquals(1, none.units().size());
        assertEquals(KitOrder.NO_SHULKER, none.shorts().get(0).reason());
    }

    // M4: emptied back

    private static OrderSim world() {
        OrderSim w = new OrderSim().stock(D, "v", 100, 64).stock(P, "p", 16, 16);
        w.empties.put("minecraft:red_shulker_box", 2);
        w.deliverySet = true;
        return w;
    }

    private static Order order(OrderSim w, boolean kitShort, boolean restock) {
        Unit u = new Unit(Unit.Kind.Bin, "", new ArrayList<>(List.of(new Order.Lot(D, "v", 4), new Order.Lot(P, "p", 2))));
        u.kitTemplate = "t-0000aaaa";
        u.name = "Pack";
        u.kitShort = kitShort;
        w.kitPlan = new KitPlanner.Plan(List.of(u), List.of(), List.of());
        KitTemplate t = template("t-0000aaaa", "Pack", D, 4, P, 2);
        Order o = new Order("o-1", List.of(new Order.Line(Order.KIT, t.id(), 1)), null, false, null, snap(t));
        o.restock = restock;
        return o;
    }

    @Test
    void aShortKitThatCannotBeStoredIsEmptiedBack() {
        OrderSim w = world();
        w.anvil = false;
        assertNull(w.flow.offer(order(w, true, false)));
        w.run(200);
        assertEquals(1, w.emptied.size(), "no name, no incomplete copy: the shulker is emptied and its items put back");
        assertTrue(w.delivery.isEmpty());
        assertEquals("returned", w.flow.report().kit().getAsJsonArray("short").get(0).getAsJsonObject().get("stored").getAsString());
    }

    @Test
    void aNamedShortKitIsKeptNotEmptied() {
        OrderSim w = world();
        assertNull(w.flow.offer(order(w, true, false)));
        w.run(200);
        assertTrue(w.emptied.isEmpty());
        assertEquals("incomplete", w.flow.report().kit().getAsJsonArray("short").get(0).getAsJsonObject().get("stored").getAsString());
    }

    @Test
    void aCancelledBuildThatWasBegunButNotFinishedIsEmptiedBack() {
        OrderSim w = world();
        Order o = order(w, false, false);
        w.kitPlan.units().get(0).begins = 1;
        assertNull(w.flow.offer(o));
        assertTrue(w.flow.cancel("o-1"));
        w.run(50);
        assertEquals("cancelled", w.flow.report().status());
        assertEquals(1, w.emptied.size());
    }

    @Test
    void aCompleteKitIsNotTakenApartWhenTheOrderIsCancelled() {
        OrderSim w = world();
        assertNull(w.flow.offer(order(w, false, false)));
        w.step();
        assertTrue(w.flow.cancel("o-1"));
        w.run(50);
        assertEquals("cancelled", w.flow.report().status());
        assertTrue(w.emptied.isEmpty(), "a finished kit stays carried and is filed in its chain target");
    }

    // N1: a 27-slot template with a part-filled base can be completed

    @Test
    void aFullTemplateWithAPartFilledBaseIsToppedUpToComplete() {
        List<Object> defs = new ArrayList<>();
        Map<String, Long> loose = new HashMap<>();
        Map<String, String> matchOf = new HashMap<>();
        List<Item> inside = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            String id = "minecraft:item_" + i;
            defs.add(id);
            defs.add(4);
            inside.add(stack("item_" + i, i == 26 ? 2 : 4));
            loose.put(id, 10L);
            matchOf.put(id, id);
        }
        KitTemplate t = template("t-0000aaaa", "Full", defs.toArray());
        KitOrder.Snapshot snap = snap(t);
        Box target = box(7, copy("Full", inside.toArray(new Item[0])));
        KitPlanner.Scan scan = KitPlanner.scan(snap, List.of(target), b -> true, x -> KitRouting.canonicalId(target));
        assertEquals(1, scan.base().get("t-0000aaaa").size());
        KitPlanner.Scan withKeys = new KitPlanner.Scan(scan.wanted(), matchOf, scan.damage(), scan.ready(), scan.base(), Set.of(), Set.of());
        KitPlanner.Plan plan = KitPlanner.plan(snap, withKeys, survey(loose), id -> 64, 0, Set.of());
        assertEquals(1, plan.units().size());
        Unit u = plan.units().get(0);
        assertFalse(u.kitShort, "the 28th lot was not counted against the 27 slots");
        assertNotNull(u.kitBase);
        assertEquals(28, u.lots.size(), "27 held lots and the 2 that top up the last slot");
    }

    // N2: the shulker to empty is the unit's own

    @Test
    void theShulkerToEmptyIsTheUnitsOwnUnnamedOneNotAStoredIncompleteCopy() {
        Unit u = new Unit(Unit.Kind.Bin, "", new ArrayList<>(List.of(new Order.Lot(D, "v", 4), new Order.Lot(P, "p", 2))));
        Item stored = copy("Pack", stack("diamond", 4), stack("ender_pearl", 1));
        Item own = copy(null, stack("diamond", 4), stack("ender_pearl", 2));
        Item other = copy(null, stack("cobblestone", 4));
        Item[] inv = {stored, other, own};
        assertEquals(2, KitEmpty.pick(u, List.of(0, 1, 2), i -> inv[i], Set.of()));
        assertEquals(-1, KitEmpty.pick(u, List.of(0, 1, 2), i -> inv[i], Set.of(2)), "a named copy and a foreign shulker never qualify");
        Item part = copy(null, stack("diamond", 4));
        Item[] inv2 = {stored, part};
        assertEquals(1, KitEmpty.pick(u, List.of(0, 1), i -> inv2[i], Set.of()), "a partly packed one of the unit's lots");
    }

    // The cap of 16

    @Test
    void onlyTheFirstSixteenTemplatesWithAKeepCountAreLookedAt() {
        List<KitTemplate> all = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            KitTemplate base = template(String.format("t-%08d", 19 - i), "T" + i, D, 1, P, 1);
            all.add(new KitTemplate(base.id(), base.name(), base.category(), null, null, i == 3 ? 0 : 2, base.slots()));
        }
        List<KitTemplate> got = KitRestock.candidates(all);
        assertEquals(16, got.size());
        assertEquals("t-00000000", got.get(0).id());
        assertTrue(got.stream().noneMatch(x -> x.keep() == 0));
        assertTrue(got.stream().noneMatch(x -> x.id().compareTo("t-00000018") > 0));
    }

    // T2 residual

    @Test
    void aDeeplyNestedHeartbeatReplyIsNotParsed() {
        OrderSim w = new OrderSim();
        OrderDriver d = new OrderDriver(w.flow, () -> 1_000_000L, (level, text) -> { });
        d.onHeartbeat("{\"orders\":" + "[".repeat(200) + "]".repeat(200) + "}");
        assertEquals(0, d.pending());
    }

    // L2, L3

    @Test
    void aRestockJobHasItsOwnWording() {
        OrderSim w = world();
        assertNull(w.flow.offer(order(w, false, true)));
        assertTrue(w.flow.summary().startsWith("Kit restock"), w.flow.summary());
        assertTrue(w.flow.activityTitle().startsWith("Kit restock"), w.flow.activityTitle());
        w.run(200);
        assertTrue(w.flow.summary().startsWith("Kit restock"));
        assertTrue(w.said.stream().noneMatch(s -> s.startsWith("Order ") || s.startsWith("Taking order")), w.said.toString());
        assertTrue(w.said.stream().anyMatch(s -> s.startsWith("Kit restock")));
    }

    @Test
    void aGivenUpKitSaysWhy() {
        assertEquals(KitOrder.NO_SHULKER, OrderFlow.kitReasonOf("no empty shulker of that kind is left"));
        assertEquals(KitOrder.ITEMS_SHORT, OrderFlow.kitReasonOf("a packed shulker is no longer in the stash"));
        assertEquals(KitOrder.INVENTORY_FULL, OrderFlow.kitReasonOf("no carry room"));
        assertEquals(KitOrder.TIMEOUT, OrderFlow.kitReasonOf("no headway for 5 minutes"));
        assertEquals(KitOrder.INTERRUPTED, OrderFlow.kitReasonOf("something odd"));
        OrderSim w = world();
        w.failBuilds = true;
        assertNull(w.flow.offer(order(w, false, false)));
        w.run(200);
        JsonObject shortOne = w.flow.report().kit().getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals("items_short", shortOne.get("reason").getAsString());
    }

    // L1, L5, L6

    @Test
    void restockStartsOnlyWhenFreeAndHeardAndStopsForOrders() {
        assertTrue(KitRestock.mayStart(false, false, true, true, false));
        assertFalse(KitRestock.mayStart(true, false, true, true, false), "an order is held");
        assertFalse(KitRestock.mayStart(false, true, true, true, false), "an order is saved to resume");
        assertFalse(KitRestock.mayStart(false, false, false, true, false), "no heartbeat reply yet after connecting");
        assertFalse(KitRestock.mayStart(false, false, true, false, false), "orders are off");
        assertFalse(KitRestock.mayStart(false, false, true, true, true), "the bot cannot work");
        assertFalse(KitRestock.mustStop(false, true, true, true, false));
        assertTrue(KitRestock.mustStop(true, true, true, true, false));
        assertTrue(KitRestock.mustStop(false, true, true, false, false), "orders switched off");
        assertTrue(KitRestock.mustStop(false, false, true, true, false));
        assertTrue(KitRestock.mustStop(false, true, true, true, true));
    }

    // K2

    @Test
    void restockPrefersPlainThenUnnamedAndNeverTakesEnchantedForAnyVariantSlots() {
        String plain = "minecraft:diamond_sword", named = "minecraft:diamond_sword#aaaaaaaa", ench = "minecraft:diamond_sword#bbbbbbbb", unnamedVariant = "minecraft:diamond_sword#cccccccc";
        Map<String, String> matchOf = new HashMap<>();
        for (String k : List.of(plain, named, ench, unnamedVariant)) matchOf.put(k, "minecraft:diamond_sword#*".equals(k) ? k : k);
        KitPlanner.Scan scan = new KitPlanner.Scan(new java.util.HashSet<>(matchOf.keySet()), matchOf, new HashMap<>(), Map.of(), Map.of(), Set.of(named), Set.of(ench));
        Map<String, Long> have = new HashMap<>();
        for (String k : matchOf.keySet()) have.put(k, 1L);
        StockPlanner.Survey sv = new StockPlanner.Survey(have, Map.of(), Set.of(), Set.of(), Map.of(), Map.of());
        KitTemplate.Slot any = new KitTemplate.Slot("minecraft:diamond_sword#*", "minecraft:diamond_sword#*", 1);
        // matchOf maps to the variants' own match keys; for an any-variant slot only the item id counts
        Map<String, String> byBase = new HashMap<>();
        for (String k : matchOf.keySet()) byBase.put(k, k);
        assertEquals(List.of(plain, unnamedVariant, named), KitPlanner.candidates(any, scan, sv, true), "plain, unnamed variants, named last; the enchanted one never");
        assertTrue(KitPlanner.candidates(any, scan, sv, false).contains(ench), "orders may take it");
        // A slot whose own match key is the enchanted variant takes it
        KitTemplate.Slot exact = new KitTemplate.Slot(ench, ench, 1);
        assertEquals(List.of(ench), KitPlanner.candidates(exact, scan, sv, true));
    }

    // K3

    @Test
    void stampsFromTheFutureAreClampedOrDroppedAndSorted(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        Path file = dir.resolve("kit-restock.json");
        Files.writeString(file, "[" + (now + 2 * KitRestockStamps.HOUR_MS) + "," + (now + 600_000) + "," + (now - 1_800_000) + "," + (now - 90 * 60_000L) + "]");
        KitRestockStamps s = new KitRestockStamps(file);
        assertEquals(2, s.lastHour(now), "the far-future stamp is dropped, the near one counts as now, the old one has expired");
        assertEquals(1, s.lastHour(now + 59 * 60_000L), "only the clamped stamp is left, and it does not outlive an hour from now");
        assertEquals(0, s.lastHour(now + 2 * KitRestockStamps.HOUR_MS));
    }

    // K4

    @Test
    void hiddenCharactersInNamesAreRefused() {
        assertTrue(ShulkerNames.hasHiddenChar("a​b"));
        assertTrue(ShulkerNames.hasHiddenChar("a‮b"));
        assertTrue(ShulkerNames.hasHiddenChar("a\uD800b"));
        assertTrue(ShulkerNames.hasHiddenChar("a\uDC00"));
        assertFalse(ShulkerNames.hasHiddenChar("Pack 😀 x"));
        assertNotNull(ShulkerNames.problem("a​b"));
        assertNull(ShulkerNames.normalise("a‮b"), "the anvil never writes one");
        JsonObject t = JsonParser.parseString("{\"id\":\"t-0000aaaa\",\"name\":\"Pa\\u200Bck\",\"category\":\"pvp\",\"chest\":null,\"color\":null,\"keep\":0,\"slots\":[]}").getAsJsonObject();
        JsonArray slots = new JsonArray();
        for (int i = 0; i < 27; i++) slots.add(com.google.gson.JsonNull.INSTANCE);
        t.add("slots", slots);
        assertThrows(KitTemplate.Invalid.class, () -> KitTemplate.parse(t));
    }

    // T1

    @Test
    void severalTemplatesArePlannedFromOneScanWithoutChangingIt() {
        KitTemplate a = template("t-0000aaaa", "A", TOTEM, 1, C, 1), b = template("t-0000bbbb", "B", TOTEM, 1, P, 1);
        Map<String, Long> stock = new HashMap<>(Map.of(TOTEM, 1L, C, 1L, P, 1L));
        Map<String, String> matchOf = Map.of(TOTEM, TOTEM, C, C, P, P);
        KitPlanner.Scan scan = scanOf(matchOf);
        StockPlanner.Survey sv = survey(stock);
        KitPlanner.Plan first = KitPlanner.plan(snap(a), scan, sv, id -> 64, 1, Set.of(), true);
        KitPlanner.Plan second = KitPlanner.plan(snap(b), scan, sv, id -> 64, 1, Set.of(), true);
        assertEquals(1, first.units().size());
        assertEquals(1, second.units().size(), "the first plan did not use up the shared survey");
        assertEquals(Map.of(TOTEM, 1L, C, 1L, P, 1L), stock);
    }

    // T2

    @Test
    void aDeeplyNestedClaimIsNotParsed() {
        OrderSim w = new OrderSim();
        OrderDriver d = new OrderDriver(w.flow, () -> 1_000_000L, (level, text) -> { });
        d.onHeartbeat("{\"ok\":true,\"orders\":{\"pending\":1,\"cancel\":[]}}");
        OrderDriver.Request claim = d.next(true, null, "2b2t.org", "stash", "Bot", true);
        String body = "{\"order\":" + "[".repeat(200) + "]".repeat(200) + "}";
        claim.reply().done(200, body);
        assertFalse(w.flow.holdsOrder());
    }

    // T3

    @Test
    void aTakeEntryThatIsNoSlotOfTheTemplateIsNotAKitOrder() {
        JsonObject block = JsonParser.parseString("{\"v\":1,\"lines\":[{\"count\":1,\"ready\":0,\"build\":1,\"template\":{\"id\":\"t-0000aaaa\",\"name\":\"Pack\",\"category\":\"pvp\",\"chest\":null,\"color\":null,\"keep\":0,\"slots\":[]}}],"
            + "\"take\":[{\"match\":\"minecraft:bedrock\",\"count\":5}]}").getAsJsonObject();
        JsonArray slots = new JsonArray();
        for (int i = 0; i < 27; i++) {
            if (i < 2) {
                JsonObject s = new JsonObject();
                s.addProperty("key", i == 0 ? TOTEM : P);
                s.addProperty("match", i == 0 ? TOTEM : P);
                s.addProperty("count", 1);
                slots.add(s);
            }
            else slots.add(com.google.gson.JsonNull.INSTANCE);
        }
        block.getAsJsonArray("lines").get(0).getAsJsonObject().getAsJsonObject("template").add("slots", slots);
        assertTrue(KitOrder.parse(block).malformed());
        block.getAsJsonArray("take").get(0).getAsJsonObject().addProperty("match", TOTEM);
        assertFalse(KitOrder.parse(block).malformed());
    }
}
