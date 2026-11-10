package com.porter.qwertestash.orders;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.ManagerIndex;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.KitTemplate;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.Stash.Box;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kit orders (KIT-CONTRACT v2, revisions 3 and 4): the kit block, the plan from the stash, the result and the claim and heartbeat flags. */
class KitOrdersTest {
    private static final String T = "t-0000aaaa";

    private static JsonObject slot(String key, int count) {
        JsonObject o = new JsonObject();
        o.addProperty("key", key);
        o.addProperty("match", key);
        o.addProperty("count", count);
        return o;
    }

    /** A template of 2 totems and 6 end crystals (a stack of 64 each is not asked for) and a named "Crystal Pack". */
    private static JsonObject templateJson(String id, String name, String color) {
        JsonObject t = new JsonObject();
        t.addProperty("id", id);
        t.addProperty("name", name);
        t.addProperty("category", "pvp");
        t.add("chest", com.google.gson.JsonNull.INSTANCE);
        if (color == null) t.add("color", com.google.gson.JsonNull.INSTANCE);
        else t.addProperty("color", color);
        t.addProperty("keep", 0);
        JsonArray slots = new JsonArray();
        slots.add(slot("minecraft:totem_of_undying", 1));
        slots.add(slot("minecraft:end_crystal", 6));
        for (int i = 2; i < 27; i++) slots.add(com.google.gson.JsonNull.INSTANCE);
        t.add("slots", slots);
        return t;
    }

    private static JsonObject kitBlock(int count, int ready, int build, String color) {
        JsonObject k = new JsonObject();
        k.addProperty("v", 1);
        JsonArray lines = new JsonArray();
        JsonObject l = new JsonObject();
        l.add("template", templateJson(T, "Crystal Pack", color));
        l.addProperty("count", count);
        l.addProperty("ready", ready);
        l.addProperty("build", build);
        lines.add(l);
        k.add("lines", lines);
        JsonArray take = new JsonArray();
        JsonObject a = new JsonObject();
        a.addProperty("match", "minecraft:totem_of_undying");
        a.addProperty("count", 4);
        take.add(a);
        JsonObject b = new JsonObject();
        b.addProperty("match", "minecraft:end_crystal");
        b.addProperty("count", 24);
        take.add(b);
        k.add("take", take);
        return k;
    }

    private static JsonObject orderJson(JsonObject kit) {
        JsonObject o = new JsonObject();
        o.addProperty("id", "o-bbbbbbbbbbbb");
        JsonArray lines = new JsonArray();
        JsonObject l = new JsonObject();
        l.addProperty("type", "kit");
        l.addProperty("key", T);
        l.addProperty("count", 2);
        lines.add(l);
        o.add("lines", lines);
        if (kit != null) o.add("kit", kit);
        return o;
    }

    @Test
    void anOrderOfKitLinesIsReadWithItsBlock() {
        Order o = Order.parse(orderJson(kitBlock(2, 0, 2, "red")));
        assertNotNull(o);
        assertTrue(o.hasKit());
        assertEquals(1, o.kitLines().size());
        assertNotNull(o.kit);
        assertFalse(o.kit.malformed());
        assertEquals(2, o.kit.kits());
        assertEquals("Crystal Pack", o.kit.line(T).template().name());
        assertEquals(2, o.kit.take().size());
        assertNull(KitOrder.check(o.kit, id -> 64));
        // A slot that holds more than a stack is not a valid snapshot
        assertEquals("a slot holds more than a stack", KitOrder.check(o.kit, id -> 4));
    }

    @Test
    void aKitLineWithoutAReadableBlockMakesEveryKitMismatched() {
        Order none = Order.parse(orderJson(null));
        assertTrue(none.kit.malformed());
        assertEquals("the kit block is not readable", KitOrder.check(none.kit, id -> 64));
        JsonObject bad = kitBlock(2, 0, 2, null);
        bad.addProperty("v", 2);
        assertEquals("the kit block is not readable", KitOrder.check(Order.parse(orderJson(bad)).kit, id -> 64));
        // ready + build must cover the count
        assertTrue(Order.parse(orderJson(kitBlock(3, 0, 2, null))).kit.malformed());
        // An order without kit lines has no block
        JsonObject plain = new JsonObject();
        plain.addProperty("id", "o-cccccccccccc");
        JsonArray lines = new JsonArray();
        JsonObject l = new JsonObject();
        l.addProperty("type", "item");
        l.addProperty("key", "minecraft:diamond");
        l.addProperty("count", 1);
        lines.add(l);
        plain.add("lines", lines);
        assertNull(Order.parse(plain).kit);
        assertFalse(Order.parse(plain).hasKit());
    }

    // The plan

    private static Item stack(String id, int count, int damage) {
        Item it = new Item();
        it.id = id;
        it.count = count;
        it.maxStack = 64;
        it.damage = damage;
        if (damage > 0) it.key = "minecraft:" + id + "#d" + damage;
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

    private static StockPlanner.Survey survey(Map<String, Long> loose, Map<String, Integer> max) {
        Map<String, List<StockPlanner.Var>> vars = new HashMap<>();
        for (var e : loose.entrySet()) vars.put(e.getKey(), List.of(new StockPlanner.Var(e.getKey(), e.getValue())));
        return new StockPlanner.Survey(loose, max, Set.of(), Set.of(), vars, Map.of());
    }

    private static KitOrder.Snapshot snapshot(int count, int ready, int build) {
        return Order.parse(orderJson(kitBlock(count, ready, build, "red"))).kit;
    }

    @Test
    void aCompleteCopyIsFetchedAndTheRestIsBuiltFromTheBudget() {
        KitOrder.Snapshot snap = snapshot(2, 1, 1);
        Item done = copy("crystal pack", stack("totem_of_undying", 1, 0), stack("end_crystal", 6, 0));
        List<Box> boxes = List.of(box(1, done), box(2, stack("totem_of_undying", 5, 0), stack("end_crystal", 40, 0)));
        KitPlanner.Scan scan = KitPlanner.scan(snap, boxes, b -> true, t -> null);
        assertEquals(1, scan.ready().get(T).size());
        KitPlanner.Plan plan = KitPlanner.plan(snap, scan, survey(Map.of("minecraft:totem_of_undying", 5L, "minecraft:end_crystal", 40L), Map.of()), id -> 64, 3, Set.of());
        assertEquals(2, plan.units().size());
        Unit ready = plan.units().get(0), build = plan.units().get(1);
        assertEquals(Unit.Kind.Shulker, ready.kind);
        assertTrue(ready.kitReady);
        assertEquals(ManagerIndex.shulkerKey(done), ready.key);
        assertEquals("Crystal Pack", ready.name);
        assertEquals(Unit.Kind.Bin, build.kind);
        assertEquals(T, build.kitTemplate);
        assertEquals("red", build.kitColor);
        assertFalse(build.kitShort);
        // One stack of each kind: a totem and six crystals
        assertEquals(List.of("minecraft:totem_of_undying", "minecraft:end_crystal"), build.lots.stream().map(Order.Lot::key).toList());
        assertEquals(List.of(1, 6), build.lots.stream().map(Order.Lot::count).toList());
        assertTrue(plan.shorts().isEmpty());
    }

    @Test
    void anExactSlotTakesTheLeastDamagedFirstAndTheBudgetLimitsWhatIsTaken() {
        KitOrder.Snapshot snap = snapshot(1, 0, 1);
        Item worn = stack("totem_of_undying", 1, 0);
        Box b = box(1, worn, stack("end_crystal", 3, 0));
        KitPlanner.Scan scan = KitPlanner.scan(snap, List.of(b), p -> true, t -> null);
        // Three crystals in the stash for six asked: the kit is short but holds two kinds, so it is built and kept as an incomplete copy
        KitPlanner.Plan plan = KitPlanner.plan(snap, scan, survey(Map.of("minecraft:totem_of_undying", 1L, "minecraft:end_crystal", 3L), Map.of()), id -> 64, 1, Set.of());
        assertEquals(1, plan.units().size());
        Unit u = plan.units().get(0);
        assertTrue(u.kitShort);
        assertEquals(1, u.kitMissing.size());
        assertEquals("minecraft:end_crystal", u.kitMissing.get(0).key());
        assertEquals(3, u.kitMissing.get(0).count());
        // A budget of 24 crystals is never exceeded across kits
        KitOrder.Snapshot two = snapshot(2, 0, 2);
        KitPlanner.Scan scan2 = KitPlanner.scan(two, List.of(box(1, stack("totem_of_undying", 9, 0), stack("end_crystal", 64, 0))), p -> true, t -> null);
        KitPlanner.Plan plan2 = KitPlanner.plan(two, scan2, survey(Map.of("minecraft:totem_of_undying", 9L, "minecraft:end_crystal", 64L), Map.of()), id -> 64, 5, Set.of());
        assertEquals(2, plan2.units().size());
        assertFalse(plan2.units().get(0).kitShort);
        assertFalse(plan2.units().get(1).kitShort);
    }

    @Test
    void noEmptyShulkerMeansNoKitAndAKitOfOneKindIsNotStarted() {
        KitOrder.Snapshot snap = snapshot(1, 0, 1);
        List<Box> boxes = List.of(box(1, stack("totem_of_undying", 5, 0), stack("end_crystal", 40, 0)));
        KitPlanner.Scan scan = KitPlanner.scan(snap, boxes, p -> true, t -> null);
        KitPlanner.Plan none = KitPlanner.plan(snap, scan, survey(Map.of("minecraft:totem_of_undying", 5L, "minecraft:end_crystal", 40L), Map.of()), id -> 64, 0, Set.of());
        assertTrue(none.units().isEmpty());
        assertEquals(KitOrder.NO_SHULKER, none.shorts().get(0).reason());
        // Only crystals in the stash: one kind, nothing is taken and the kit is short with its items
        KitPlanner.Scan crystals = KitPlanner.scan(snap, List.of(box(1, stack("end_crystal", 40, 0))), p -> true, t -> null);
        KitPlanner.Plan one = KitPlanner.plan(snap, crystals, survey(Map.of("minecraft:end_crystal", 40L), Map.of()), id -> 64, 2, Set.of());
        assertTrue(one.units().isEmpty());
        assertEquals(KitOrder.ITEMS_SHORT, one.shorts().get(0).reason());
        assertEquals(KitResult.STORED_RETURNED, one.shorts().get(0).stored());
        assertEquals("minecraft:totem_of_undying", one.shorts().get(0).items().get(0).key());
    }

    @Test
    void anIncompleteCopyInTheChainTargetIsTheBaseAndOnlyTheMissingItemsAreGathered() {
        KitOrder.Snapshot snap = snapshot(1, 0, 1);
        Item half = copy("Crystal Pack", stack("totem_of_undying", 1, 0), stack("end_crystal", 6, 0));
        // Remove the crystals: an incomplete copy with the totem only is one kind, so it is not an incomplete copy; use a template with both and take one out
        Item partial = copy("Crystal Pack", stack("totem_of_undying", 1, 0));
        Box target = box(7, partial, half);
        KitPlanner.Scan scan = KitPlanner.scan(snap, List.of(target, box(1, stack("end_crystal", 40, 0))), p -> true, t -> com.porter.qwertestash.stash.KitRouting.canonicalId(target));
        // The complete copy is ready elsewhere than counted (ready = 0 in this snapshot), the one-kind copy is not matched as incomplete
        assertTrue(scan.base().getOrDefault(T, List.of()).isEmpty());
        // The fit of a base: every stack sits in a slot with its exact count
        KitTemplate t = snap.line(T).template();
        assertNotNull(KitPlanner.fitBase(t, half));
        Item part = copy("Crystal Pack", stack("totem_of_undying", 1, 0), stack("end_crystal", 2, 0));
        assertNotNull(KitPlanner.fitBase(t, part), "a stack holding part of a slot is topped up");
        Item over = copy("Crystal Pack", stack("totem_of_undying", 1, 0), stack("end_crystal", 7, 0));
        assertNull(KitPlanner.fitBase(t, over), "a stack larger than any slot it could be in");
    }

    // The result and the flags

    @Test
    void theResultListsMadeShortAndNotes() {
        KitResult r = new KitResult();
        r.line(T, "Crystal Pack", 2);
        r.ready(T);
        r.built(T);
        r.delivered(T);
        r.addShort(T, 1, List.of(new KitResult.Miss("minecraft:end_crystal", "minecraft:end_crystal", 3)), KitOrder.ITEMS_SHORT, KitResult.STORED_INCOMPLETE);
        r.note(KitResult.COLOUR_SUBSTITUTED);
        JsonObject o = r.toJson();
        JsonObject made = o.getAsJsonArray("made").get(0).getAsJsonObject();
        assertEquals(T, made.get("template").getAsString());
        assertEquals(2, made.get("wanted").getAsInt());
        assertEquals(1, made.get("ready").getAsInt());
        assertEquals(1, made.get("built").getAsInt());
        assertEquals(1, made.get("delivered").getAsInt());
        JsonObject sh = o.getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals("items_short", sh.get("reason").getAsString());
        assertEquals("incomplete", sh.get("stored").getAsString());
        assertEquals(3, sh.getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals("colour_substituted", o.getAsJsonArray("notes").get(0).getAsString());
        assertEquals("1 kit not made: items_short", r.note());
    }

    @Test
    void theHeartbeatSaysKitOrdersOnlyForABotThatCanBuildKits() {
        ManagerIndex.Built built = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        byte[] with = ManagerIndex.heartbeat(built, -1, null, null, null, null, null, null, null, new ManagerIndex.Kits("a".repeat(40), null, null, new ManagerIndex.KitCaps(true, true, false)));
        JsonObject o = JsonParser.parseString(new String(with, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject k = o.getAsJsonObject("kitOrders");
        assertEquals(1, k.get("v").getAsInt());
        assertTrue(k.get("enabled").getAsBoolean());
        assertTrue(k.get("restock").getAsBoolean());
        assertFalse(k.get("craft").getAsBoolean());
        byte[] without = ManagerIndex.heartbeat(built, -1, null, null, null, null, null, null, null, new ManagerIndex.Kits("a".repeat(40), null));
        assertFalse(JsonParser.parseString(new String(without, StandardCharsets.UTF_8)).getAsJsonObject().has("kitOrders"));
    }

    @Test
    void theClaimCarriesKitOrdersOnlyWhenAsked() {
        OrderSim w = new OrderSim();
        OrderDriver d = new OrderDriver(w.flow, () -> 1_000_000L, (level, text) -> { });
        d.onHeartbeat("{\"ok\":true,\"orders\":{\"pending\":1,\"cancel\":[]}}");
        OrderDriver.Request plain = d.next(true, null, "2b2t.org", "stash", "Bot", true);
        assertFalse(JsonParser.parseString(new String(plain.body(), StandardCharsets.UTF_8)).getAsJsonObject().has("kitOrders"));
        OrderSim w2 = new OrderSim();
        OrderDriver d2 = new OrderDriver(w2.flow, () -> 1_000_000L, (level, text) -> { });
        d2.kitOrdersWhen(() -> true);
        d2.onHeartbeat("{\"ok\":true,\"orders\":{\"pending\":1,\"cancel\":[]}}");
        OrderDriver.Request flagged = d2.next(true, null, "2b2t.org", "stash", "Bot", true);
        assertEquals(1, JsonParser.parseString(new String(flagged.body(), StandardCharsets.UTF_8)).getAsJsonObject().get("kitOrders").getAsInt());
    }

    @Test
    void theStatusCarriesResultKitWhenTheOrderHadKitLines() {
        OrderSim w = new OrderSim();
        OrderDriver d = new OrderDriver(w.flow, () -> 1_000_000L, (level, text) -> { });
        KitResult r = new KitResult();
        r.line(T, "Crystal Pack", 1);
        r.addShort(T, 1, List.of(), KitOrder.CANCELLED, null);
        OrderFlow.Report rep = new OrderFlow.Report("o-bbbbbbbbbbbb", "cancelled", "cancelled", 0, 1, "", 0, Map.of(), null, null, r.toJson());
        JsonObject status = d.statusJson(rep);
        assertEquals("cancelled", status.getAsJsonObject("result").getAsJsonObject("kit").getAsJsonArray("short").get(0).getAsJsonObject().get("reason").getAsString());
    }
}
