package com.porter.qwertestash.orders;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.ManagerIndex;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REPAIR-CONTRACT.md on the bot side: the fields read from the claimed order, those sent in the index, heartbeat and result, the numbers. */
class RepairContractTest {
    private static final String OLD = "minecraft:diamond_pickaxe#old", NEW = "minecraft:diamond_pickaxe#new", D = "minecraft:diamond";

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    // The order

    @Test
    void repairAllAndTheLineFlagAreRead() {
        Order o = Order.parse(json("{\"id\":\"o-1\",\"repairAll\":true,\"lines\":[{\"type\":\"item\",\"key\":\"" + OLD + "\",\"count\":1},"
            + "{\"type\":\"item\",\"key\":\"minecraft:diamond\",\"count\":5,\"repair\":false},{\"type\":\"shulker\",\"key\":\"s:abc\",\"count\":1,\"repair\":true}]}"));
        assertNotNull(o);
        assertTrue(o.repairAll);
        assertTrue(o.wantsRepair(o.lines.get(0)));
        assertTrue(o.wantsRepair(o.lines.get(1)), "repair all covers every item line");
        assertFalse(o.wantsRepair(o.lines.get(2)), "a shulker line is never wanted for repair");
        assertEquals(java.util.Set.of(OLD, D), o.repairKeys());
    }

    @Test
    void aLineFlagAloneWantsOnlyThatLine() {
        Order o = Order.parse(json("{\"id\":\"o-2\",\"lines\":[{\"type\":\"item\",\"key\":\"" + OLD + "\",\"count\":2,\"repair\":true},"
            + "{\"type\":\"item\",\"key\":\"minecraft:diamond\",\"count\":5}]}"));
        assertNotNull(o);
        assertFalse(o.repairAll);
        assertTrue(o.lines.get(0).repair());
        assertFalse(o.lines.get(1).repair());
        assertEquals(java.util.Set.of(OLD), o.repairKeys());
        assertTrue(o.anyRepair());
    }

    @Test
    void anOrderFromAnOlderServerAsksForNoRepair() {
        Order o = Order.parse(json("{\"id\":\"o-3\",\"lines\":[{\"type\":\"item\",\"key\":\"" + OLD + "\",\"count\":1}]}"));
        assertNotNull(o);
        assertFalse(o.repairAll);
        assertFalse(o.anyRepair());
        assertTrue(o.repairKeys().isEmpty());
    }

    @Test
    void wrongTypesAreReadAsFalseAndNeverBreakTheOrder() {
        Order o = Order.parse(json("{\"id\":\"o-4\",\"repairAll\":\"yes\",\"lines\":[{\"type\":\"item\",\"key\":\"" + OLD + "\",\"count\":1,\"repair\":1}]}"));
        assertNotNull(o);
        assertFalse(o.repairAll);
        assertFalse(o.lines.get(0).repair());
    }

    // The numbers

    @Test
    void oneBottleIsFourteenDurability() {
        assertEquals(0, Repair.bottlesFor(0));
        assertEquals(0, Repair.bottlesFor(-5));
        assertEquals(1, Repair.bottlesFor(1));
        assertEquals(1, Repair.bottlesFor(14));
        assertEquals(2, Repair.bottlesFor(15));
        assertEquals(112, Repair.bottlesFor(1561));
        assertEquals(10, Repair.bottlesFor(140));
    }

    @Test
    void theBottlesTakenAreTheEstimateAndAFewSpare() {
        assertEquals(0, Repair.bottlesToTake(List.of()));
        assertEquals(0, Repair.bottlesToTake(List.of(0, 0)));
        assertEquals(10 + 3, Repair.bottlesToTake(List.of(140)));
        assertEquals(10 + 5 + 3 + 0, Repair.bottlesToTake(List.of(140, 70)));
        // One more spare per four items
        assertEquals(4 * 10 + 3 + 1, Repair.bottlesToTake(List.of(140, 140, 140, 140)));
        assertTrue(Repair.spare(1) >= 3);
    }

    @Test
    void theSkipReasonsFollowTheContract() {
        assertNull(Repair.skipReason(true, 120, 1));
        assertEquals("no Mending", Repair.skipReason(false, 120, 1));
        assertEquals("not damaged", Repair.skipReason(true, 0, 1));
        assertEquals("not a single item", Repair.skipReason(true, 120, 64));
        assertEquals("no Mending", Repair.skipReason(false, 0, 64));
        assertEquals("no bottles", Repair.NO_BOTTLES);
        assertEquals("interrupted", Repair.INTERRUPTED);
    }

    @Test
    void mendingIsFoundInTheRegistryNamesTheBotSees() {
        assertTrue(Repair.isMending("mending"));
        assertTrue(Repair.isMending("mending:1"));
        assertTrue(Repair.isMending("minecraft:mending"));
        assertTrue(Repair.isMending("minecraft:mending:1"));
        assertFalse(Repair.isMending("unbreaking:3"));
        assertFalse(Repair.isMending("minecraft:unbreaking"));
        assertFalse(Repair.isMending(null));
    }

    // The index and the heartbeat

    private static Item item(String id, int max, String key, String... enchants) {
        Item it = new Item();
        it.id = id;
        it.name = Item.prettyName(id);
        it.count = 1;
        it.maxStack = max;
        it.key = key;
        it.variant = key;
        for (String e : enchants) {
            it.enchants.add(e.substring(0, e.indexOf(':')));
            it.enchantLevels.add(e);
        }
        return it;
    }

    private static JsonObject indexOf(Item... items) {
        Stash stash = new Stash("s", "2b2t.org");
        Stash.Box box = new Stash.Box(new BlockPos(1, 2, 3), new ArrayList<>(List.of(new BlockPos(1, 2, 3))));
        box.contents = new Item[27];
        for (int i = 0; i < items.length; i++) box.contents[i] = items[i];
        stash.boxes.add(box);
        ManagerIndex.Built b = ManagerIndex.build(stash, false, "Bot", "1.21.4", "overworld", id -> null);
        return json(new String(b.json(), StandardCharsets.UTF_8));
    }

    private static JsonObject entry(JsonObject index, String key) {
        for (var e : index.getAsJsonArray("items")) if (e.getAsJsonObject().get("key").getAsString().equals(key)) return e.getAsJsonObject();
        throw new AssertionError("no " + key);
    }

    @Test
    void theIndexMarksStacksWithMendingAndNoOthers() {
        Item mending = item("diamond_pickaxe", 1, "minecraft:diamond_pickaxe#m", "mending:1", "unbreaking:3");
        Item plain = item("diamond_pickaxe", 1, "minecraft:diamond_pickaxe#p", "unbreaking:3");
        Item stone = item("stone", 64, "minecraft:stone");
        // An enchanted book only stores Mending
        Item book = item("enchanted_book", 1, "minecraft:enchanted_book#b", "mending:1");
        JsonObject index = indexOf(mending, plain, stone, book);
        assertTrue(entry(index, "minecraft:diamond_pickaxe#m").get("mending").getAsBoolean());
        assertFalse(entry(index, "minecraft:diamond_pickaxe#p").has("mending"));
        assertFalse(entry(index, "minecraft:stone").has("mending"));
        assertFalse(entry(index, "minecraft:enchanted_book#b").has("mending"));
    }

    @Test
    void theHeartbeatSaysTheBotCanRepair() {
        Stash stash = new Stash("s", "2b2t.org");
        ManagerIndex.Built b = ManagerIndex.build(stash, false, "Bot", "1.21.4", "overworld", id -> null);
        JsonObject hb = json(new String(ManagerIndex.heartbeat(b, -1, null, null), StandardCharsets.UTF_8));
        assertTrue(hb.get("repair").getAsBoolean());
        JsonObject full = json(new String(ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, null), StandardCharsets.UTF_8));
        assertTrue(full.get("repair").getAsBoolean());
    }

    // The result

    @Test
    void theResultJsonIsTheContractsShape() {
        Repair.Report r = new Repair.Report();
        r.thrown(10);
        r.repaired(2);
        r.partial(OLD, 120, 1561, Repair.NO_BOTTLES);
        r.skip(NEW, Repair.NO_MENDING);
        JsonObject o = r.toJson();
        assertEquals(10, o.get("bottlesUsed").getAsInt());
        assertEquals(2, o.get("repaired").getAsInt());
        JsonObject p = o.getAsJsonArray("partial").get(0).getAsJsonObject();
        assertEquals(OLD, p.get("key").getAsString());
        assertEquals(120, p.get("damage").getAsInt());
        assertEquals(1561, p.get("maxDamage").getAsInt());
        JsonObject s = o.getAsJsonArray("skipped").get(0).getAsJsonObject();
        assertEquals(NEW, s.get("key").getAsString());
        assertEquals("no Mending", s.get("reason").getAsString());
        assertEquals("2 item(s) not fully repaired: no bottles, no Mending", r.note());
    }

    @Test
    void entriesAreCappedAndCutToTheContractsLimits() {
        Repair.Report r = new Repair.Report();
        for (int i = 0; i < 450; i++) r.skip("minecraft:item_" + i, "x".repeat(100));
        for (int i = 0; i < 450; i++) r.partial("k".repeat(300), 5, 10, Repair.INTERRUPTED);
        JsonObject o = r.toJson();
        assertEquals(400, o.getAsJsonArray("skipped").size());
        assertEquals(400, o.getAsJsonArray("partial").size());
        assertEquals(60, o.getAsJsonArray("skipped").get(0).getAsJsonObject().get("reason").getAsString().length());
        assertEquals(256, o.getAsJsonArray("partial").get(0).getAsJsonObject().get("key").getAsString().length());
        // The note counts every item, not only those listed
        assertTrue(r.note().startsWith("900 item(s) not fully repaired"));
        assertEquals(900, r.notRepaired());
    }

    @Test
    void nothingToNoteWhenEverythingWasRepaired() {
        Repair.Report r = new Repair.Report();
        r.thrown(3);
        r.repaired(1);
        assertNull(r.note());
        assertTrue(r.toJson().getAsJsonArray("partial").isEmpty());
        assertFalse(r.untouched());
        assertTrue(new Repair.Report().untouched());
    }

    @Test
    void everyItemGetsItsOwnEntry() {
        Repair.Report r = new Repair.Report();
        r.skip(D, Repair.NO_MENDING);
        r.skip(D, Repair.NO_MENDING);
        assertEquals(2, r.skipped().size());
        assertEquals(2, r.notRepaired());
    }

    @Test
    void underRepairAllOnlyDamagedLinesAreWanted() {
        // A line the order marked itself always counts; under "repair all" the rest only when damaged
        assertTrue(Repair.wanted(true, 0));
        assertTrue(Repair.wanted(false, 5));
        assertFalse(Repair.wanted(false, 0));
    }

    @Test
    void aShulkerBoxIsNotASingleItemBeforeAnythingElse() {
        assertEquals("not a single item", Repair.skipReason(true, false, 0, 1));
        assertEquals("not a single item", Repair.skipReason(true, true, 10, 1));
        assertEquals("no Mending", Repair.skipReason(false, false, 10, 1));
        assertEquals("not damaged", Repair.skipReason(false, true, 0, 1));
        assertEquals("not a single item", Repair.skipReason(false, true, 10, 16));
    }

    @Test
    void theArmourSlotsTakeOnlyTheirOwnArmour() {
        assertTrue(Repair.fitsSlot(5, "diamond_helmet"));
        assertFalse(Repair.fitsSlot(5, "diamond_boots"));
        assertTrue(Repair.fitsSlot(6, "minecraft:elytra"));
        assertFalse(Repair.fitsSlot(7, "diamond_pickaxe"));
        assertTrue(Repair.fitsSlot(8, "netherite_boots"));
        assertTrue(Repair.fitsSlot(20, "diamond_pickaxe"));
        assertFalse(Repair.fitsSlot(5, null));
    }

    @Test
    void aStaleLedgerIsReplayedOnlyWhereItStillFits() {
        java.util.Map<Integer, String> inv = new java.util.HashMap<>();
        inv.put(12, "diamond_helmet");
        inv.put(13, "diamond_pickaxe");
        inv.put(14, "diamond_boots");
        // 5>12 fits; 6>13 would put a pickaxe in the chestplate slot; 7>14 a boot in the leggings slot; 8>15 has nothing there
        List<int[]> ledger = List.of(new int[] {5, 12}, new int[] {6, 13}, new int[] {7, 14}, new int[] {8, 15});
        List<int[]> plan = Repair.undoPlan(ledger, inv::get);
        assertEquals(1, plan.size());
        assertEquals(12, plan.get(0)[0]);
        assertEquals(5, plan.get(0)[1]);
        // The place is taken: dropped
        inv.put(5, "diamond_helmet");
        assertTrue(Repair.undoPlan(List.of(new int[] {5, 12}), inv::get).isEmpty());
    }

    @Test
    void theLedgerPlanFollowsItsOwnMoves() {
        java.util.Map<Integer, String> inv = new java.util.HashMap<>();
        inv.put(45, "experience_bottle");
        inv.put(36, "diamond_pickaxe");
        // The pickaxe went 10>36 after the bottles 20>45: back in reverse
        List<int[]> plan = Repair.undoPlan(List.of(new int[] {20, 45}, new int[] {10, 36}), inv::get);
        assertEquals(2, plan.size());
        assertEquals(36, plan.get(0)[0]);
        assertEquals(10, plan.get(0)[1]);
        assertEquals(45, plan.get(1)[0]);
        assertEquals(20, plan.get(1)[1]);
    }

    @Test
    void slotsNeededCoverGearHandsAndBottles() {
        assertEquals(1, Repair.slotsNeeded(0, false, 0));
        assertEquals(3 + 1 + 1 + 1, Repair.slotsNeeded(3, true, 64));
        assertEquals(1 + 2, Repair.slotsNeeded(0, false, 65));
    }

    @Test
    void theOrdersTimeAndRetryArePartOfTheSavedReport() {
        Repair.Report r = new Repair.Report();
        r.addTicks(500);
        r.addRetry();
        Repair.Report back = Repair.Report.fromSaved(json(r.toSaved().toString()));
        assertEquals(500, back.ticks());
        assertEquals(1, back.retries());
        assertFalse(RepairRun.exhausted(back));
        back.addTicks(Repair.MAX_THROW_TICKS);
        assertTrue(RepairRun.exhausted(back));
        // The time and the retry are not part of what the server is told
        assertFalse(r.toJson().has("ticks"));
    }

    @Test
    void theReportJsonIsKeptUntilItChanges() {
        Repair.Report r = new Repair.Report();
        JsonObject first = r.toJson();
        assertTrue(first == r.toJson());
        r.repaired(1);
        JsonObject second = r.toJson();
        assertTrue(first != second);
        assertEquals(1, second.get("repaired").getAsInt());
        // Saving does not change the cached copy
        r.toSaved();
        assertFalse(r.toJson().has("ticks"));
    }

    @Test
    void theRetryIsOncePerOrderNotPerBatch() {
        FakeRepairPort p = new FakeRepairPort();
        p.slots[20] = FakeRepairPort.bottles(64);
        p.slots[10] = FakeRepairPort.tool("diamond_pickaxe", 140, 1561);
        p.orbsEaten = true;
        Repair.Report r = new Repair.Report();
        RepairRun first = new RepairRun(r, List.of(new RepairRun.Item(OLD, 10, 140, 1561)));
        assertTrue(first.tripDone(p.run(new RepairTrip(p, first.targets(), first.budget()), 2000)));
        assertEquals(1, r.retries());
        // Another batch of the same order: the retry is used up
        RepairRun other = new RepairRun(r, List.of(new RepairRun.Item(OLD, 10, 140, 1561)));
        assertFalse(other.tripDone(p.run(new RepairTrip(p, other.targets(), other.budget()), 2000)));
    }

    @Test
    void theReportSurvivesARestart() {
        Repair.Report r = new Repair.Report();
        r.thrown(7);
        r.repaired(1);
        r.partial(OLD, 30, 100, Repair.INTERRUPTED);
        r.skip(NEW, Repair.NOT_DAMAGED);
        Repair.Report back = Repair.Report.fromSaved(json(r.toSaved().toString()));
        assertEquals(r.toJson(), back.toJson());
        assertEquals(r.note(), back.note());
        assertEquals(2, back.notRepaired());
        // A broken record is an empty one
        assertTrue(Repair.Report.fromSaved(null).untouched());
        assertTrue(Repair.Report.fromSaved(json("{\"bottlesUsed\":\"x\",\"partial\":5}")).untouched());
    }

    @Test
    void aRepairedItemIsCountedBackToTheKeyTheOrderAskedFor() {
        Map<String, Integer> content = Map.of(NEW, 2, D, 3);
        Map<String, Integer> back = Repair.translate(content, List.of(new String[] {NEW, OLD}, new String[] {NEW, "minecraft:diamond_axe#old"}));
        assertEquals(3, back.get(D));
        assertEquals(1, back.get(OLD));
        assertEquals(1, back.get("minecraft:diamond_axe#old"));
        assertFalse(back.containsKey(NEW));
        // Nothing to translate: as it is
        assertEquals(content, Repair.translate(content, List.of()));
        // A rename of something that is not there changes nothing
        assertEquals(content, Repair.translate(content, java.util.Collections.singletonList(new String[] {"minecraft:x", OLD})));
    }

    @Test
    void theLedgerRoundTripsAndDropsJunk() {
        List<int[]> moves = List.of(new int[] {5, 12}, new int[] {45, 13});
        List<String> saved = Repair.formatLedger(moves);
        assertEquals(List.of("5>12", "45>13"), saved);
        List<int[]> back = Repair.parseLedger(saved);
        assertEquals(2, back.size());
        assertEquals(45, back.get(1)[0]);
        assertTrue(Repair.parseLedger(null).isEmpty());
        assertTrue(Repair.parseLedger(List.of("", ">", "a>b", "5>5", "-1>3", "3>99")).isEmpty());
    }

    // Through the order

    private static OrderSim world() {
        return new OrderSim().stock(OLD, "vo", 1, 1).stock(NEW, "vn", 1, 1).stock(D, "v", 100, 64);
    }

    @Test
    void aRepairedItemKeepsItsLotAndTheDeliveredCountIsUnderTheKeyAsked() {
        OrderSim w = world();
        w.empties.put("minecraft:red_shulker_box", 2);
        Order o = new Order("o-r", List.of(new Order.Line("item", OLD, 1, true), new Order.Line("item", D, 5)), null, false);
        assertNull(w.flow.offer(o));
        assertEquals(java.util.Set.of(OLD), w.flow.repairKeys());
        Repair.Report report = w.flow.repairReport();
        assertNotNull(report);

        Unit u = w.flow.units().get(0);
        Order.Lot lot = u.lots.stream().filter(l -> l.key().equals(OLD)).findFirst().orElseThrow();
        // The bot repaired it: the stack in hand is the "new" one
        assertTrue(w.flow.rekey(u, OLD, lot.variant(), NEW, "vn"));
        assertTrue(u.lots.stream().anyMatch(l -> l.key().equals(NEW) && "vn".equals(l.variant())));
        assertFalse(u.lots.stream().anyMatch(l -> l.key().equals(OLD)));
        assertEquals(1, u.rekeys.size());
        // Saved with the plan
        assertEquals(2, w.saved.units.get(0).rekeys.size());
        assertTrue(w.saved.units.get(0).lotKeys.contains(NEW));
        report.thrown(10);
        report.repaired(1);
        w.flow.repairChanged();
        assertNotNull(w.saved.repair);

        w.run(50);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        // Under the key the order asked for: nothing is "delivered less than asked"
        assertEquals(1, r.delivered().get(OLD));
        assertNull(r.delivered().get(NEW));
        assertFalse(r.message().contains("less than asked"), r.message());
        assertEquals(10, r.repair().get("bottlesUsed").getAsInt());
        assertEquals(1, r.repair().get("repaired").getAsInt());
    }

    @Test
    void theOrdersMessageCarriesTheNotRepairedNote() {
        OrderSim w = world();
        w.empties.put("minecraft:red_shulker_box", 2);
        assertNull(w.flow.offer(new Order("o-n", List.of(new Order.Line("item", OLD, 1), new Order.Line("item", D, 5)), null, true)));
        Repair.Report report = w.flow.repairReport();
        report.skip(OLD, Repair.NO_MENDING);
        report.skip(D, Repair.NO_MENDING);
        w.run(50);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertTrue(r.message().contains("2 item(s) not fully repaired: no Mending"), r.message());
        assertTrue(r.message().length() <= OrderFlow.MESSAGE_MAX);
    }

    @Test
    void theStatusReportsResultRepairOnlyWhenRepairWasAsked() {
        OrderSim w = world();
        OrderDriver d = new OrderDriver(w.flow, () -> w.now, (level, text) -> { });
        JsonObject plain = d.statusJson(new OrderFlow.Report("o-1", "delivered", "delivered", 1, 1, "", 1, Map.of()));
        assertFalse(plain.getAsJsonObject("result").has("repair"));

        Repair.Report rep = new Repair.Report();
        rep.thrown(4);
        rep.repaired(1);
        JsonObject asked = d.statusJson(new OrderFlow.Report("o-1", "delivered", "delivered", 1, 1, "", 1, Map.of(), rep.toJson()));
        JsonObject repair = asked.getAsJsonObject("result").getAsJsonObject("repair");
        assertEquals(4, repair.get("bottlesUsed").getAsInt());
        assertEquals(1, repair.get("repaired").getAsInt());
        assertTrue(repair.get("partial") instanceof JsonArray);
        assertTrue(repair.get("skipped") instanceof JsonArray);
        // A cancelled order has no result at all
        assertFalse(d.statusJson(new OrderFlow.Report("o-1", "cancelled", "cancelled", 0, 1, "", 0, Map.of(), rep.toJson())).has("result"));
    }

    @Test
    void anOrderThatAsksForNoRepairHasNoRepairReport() {
        OrderSim w = world();
        w.empties.put("minecraft:red_shulker_box", 2);
        assertNull(w.flow.offer(new Order("o-0", List.of(new Order.Line("item", D, 5)))));
        assertNull(w.flow.repairReport());
        assertTrue(w.flow.repairKeys().isEmpty());
        w.run(50);
        assertNull(w.flow.report().repair());
    }

    @Test
    void theRepairResultCarriesOverAResume() {
        OrderSim w = world();
        w.empties.put("minecraft:red_shulker_box", 2);
        Order o = new Order("o-resume", List.of(new Order.Line("item", OLD, 1, true), new Order.Line("item", D, 5)), null, false);
        assertNull(w.flow.offer(o));
        w.flow.repairReport().thrown(6);
        w.flow.repairReport().skip(OLD, Repair.NO_BOTTLES);
        w.flow.repairChanged();
        OrderFlow.Saved saved = w.saved;
        assertNotNull(saved.repair);

        // The same order claimed again after a restart reads the result back
        OrderSim again = world();
        again.empties.put("minecraft:red_shulker_box", 2);
        again.saved = saved;
        assertNull(again.flow.offer(o));
        assertEquals(6, again.flow.repairReport().bottlesUsed());
        assertEquals(1, again.flow.repairReport().skipped().size());
    }
}
