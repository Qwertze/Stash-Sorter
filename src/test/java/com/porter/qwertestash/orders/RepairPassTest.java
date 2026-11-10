package com.porter.qwertestash.orders;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.ManagerIndex;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.KitRouting;
import com.porter.qwertestash.stash.RepairSettings;
import com.porter.qwertestash.stash.RepairStore;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.Stash.Box;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Repair passes (REPAIR-CONTRACT v2): the shared vectors, the settings, the candidates, the batch plan, the gate, the memory, the report and the heartbeat. */
class RepairPassTest {
    // The shared vectors

    @Test
    void theLowDurabilityVectors() {
        assertTrue(RepairRules.low(781, 1561, 50));
        assertFalse(RepairRules.low(780, 1561, 50));
        assertFalse(RepairRules.low(216, 432, 50), "exactly 50% is not low");
        assertTrue(RepairRules.low(217, 432, 50));
        assertTrue(RepairRules.low(1016, 2031, 50));
        assertTrue(RepairRules.low(300, 592, 50));
        assertFalse(RepairRules.low(1, 250, 50));
        assertTrue(RepairRules.low(1, 250, 100), "T = 100 means any damage");
        assertTrue(RepairRules.low(1555, 1561, 1));
        assertFalse(RepairRules.low(0, 250, 100), "undamaged is never low");
    }

    @Test
    void theBottlesForVectors() {
        assertEquals(56, Repair.bottlesFor(781));
        assertEquals(56, Repair.bottlesFor(780));
        assertEquals(16, Repair.bottlesFor(216));
        assertEquals(16, Repair.bottlesFor(217));
        assertEquals(73, Repair.bottlesFor(1016));
        assertEquals(22, Repair.bottlesFor(300));
        assertEquals(1, Repair.bottlesFor(1));
        assertEquals(112, Repair.bottlesFor(1555));
    }

    private static RepairRules.Cand cand(String container, int slot, String key, int damage, int maxDamage) {
        return new RepairRules.Cand(container, slot, null, key, null, damage, maxDamage, true);
    }

    @Test
    void theBottleReserveBatchVector() {
        // stashBottles 1800, reserve 1728 -> usable 72
        long usable = RepairRules.usable(1800, 0, 1728);
        assertEquals(72, usable);
        assertEquals(0, RepairRules.usable(1000, 0, 1728));
        assertEquals(0, RepairRules.usable(1800, 100, 1728));
        // Route order: netherite_sword 73, diamond_pickaxe 56, netherite_chestplate 22
        List<RepairRules.Cand> mend = List.of(cand("1,64,1", 0, "minecraft:netherite_sword", 1016, 2031), cand("1,64,1", 1, "minecraft:diamond_pickaxe", 781, 1561),
            cand("1,64,1", 2, "minecraft:netherite_chestplate", 300, 592));
        RepairRules.Scan scan = new RepairRules.Scan(mend, List.of(), List.of(), 0, Map.of());
        RepairRules.Plan plan = RepairRules.plan(scan, usable, 30, 0, false, 0, 0, id -> new double[] {1, 1}, 64, 8);
        assertEquals(List.of("minecraft:diamond_pickaxe"), plan.batch().loose().stream().map(RepairRules.Cand::key).toList(), "the sword (73 > 72) and the chestplate (22 > 16) are skipped");
        assertEquals(List.of("minecraft:netherite_sword", "minecraft:netherite_chestplate"), plan.skipped().stream().map(s -> s.cand().key()).toList());
        assertTrue(plan.skipped().stream().allMatch(s -> s.reason().equals(RepairRules.RESERVE)));
        assertEquals(59, plan.batch().bottles(), "min(72, 56 + 3)");
    }

    // The settings

    @Test
    void theSettingsHashVectorsAndValidation() throws Exception {
        assertEquals("cc21b16556872c97d51a1a7d5c9164beae9a7860", RepairSettings.DEFAULTS.hash());
        assertEquals("cc21b16556872c97d51a1a7d5c9164beae9a7860", RepairSettings.hashOf(true, true, 50, 1728, null, true));
        assertEquals("52a63a5332bc3f89e9c9d6431a60864e678e9132", RepairSettings.hashOf(true, false, 40, 0, "120,64,-33", false));
        RepairSettings s = RepairSettings.of(4, true, false, 40, 0, "120,64,-33", false);
        RepairSettings back = RepairSettings.parse(s.toJson());
        assertEquals(s, back);
        JsonObject bad = s.toJson();
        bad.addProperty("threshold", 101);
        assertThrows(RepairSettings.Invalid.class, () -> RepairSettings.parse(bad));
        JsonObject wrongHash = s.toJson();
        wrongHash.addProperty("hash", "0".repeat(40));
        assertThrows(RepairSettings.Invalid.class, () -> RepairSettings.parse(wrongHash));
        // An older server has no shulkers field and the hash without it (the draft vector): boxes stay closed
        JsonObject legacy = RepairSettings.DEFAULTS.toJson();
        legacy.remove("shulkers");
        legacy.addProperty("hash", "860a4e01a25372bd1b762bbf2365dde38e13e2be");
        RepairSettings old = RepairSettings.parse(legacy);
        assertFalse(old.shulkers(), "a missing shulkers is false");
        assertEquals("860a4e01a25372bd1b762bbf2365dde38e13e2be", old.hash(), "the hash is kept as the server gave it");
        assertEquals(old, RepairSettings.parse(old.toJson()), "and it reads back from the cache file");
        JsonObject newHashNoField = RepairSettings.DEFAULTS.toJson();
        newHashNoField.remove("shulkers");
        assertThrows(RepairSettings.Invalid.class, () -> RepairSettings.parse(newHashNoField), "the new hash does not fit settings read as shulkers false");
        assertThrows(RepairSettings.Invalid.class, () -> RepairSettings.parse(JsonParser.parseString("[]")));
    }

    @Test
    void theStoreKeepsSettingsAcrossARestart(@TempDir Path dir) {
        Path file = dir.resolve("repair-settings.json");
        RepairStore a = new RepairStore(file);
        RepairSettings s = RepairSettings.of(2, true, true, 30, 100, "1,2,3", true);
        assertTrue(a.put("srv", "stash", s));
        assertFalse(a.put("srv", "stash", s));
        RepairStore b = new RepairStore(file);
        assertEquals(s, b.get("srv", "stash"));
        assertNull(b.get("srv", "other"));
    }

    // The candidates

    private static Item tool(String id, int damage, int maxDamage, boolean mending) {
        Item it = new Item();
        it.id = id;
        it.count = 1;
        it.maxStack = 1;
        it.damage = damage;
        it.maxDamage = maxDamage;
        it.key = "minecraft:" + id + "#d" + damage;
        if (mending) it.enchants.add("minecraft:mending");
        return it;
    }

    private static Item shulker(String name, Item... inside) {
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

    private static Box box(int x, Item... items) {
        BlockPos p = new BlockPos(x, 64, 0);
        Box b = new Box(p, new ArrayList<>(List.of(p)));
        b.contents = new Item[27];
        for (int i = 0; i < items.length; i++) b.contents[i] = items[i];
        b.readAt = 1;
        return b;
    }

    @Test
    void looseItemsAreMendOrNeedsRepairAndBoxesFollowTheRules() {
        Item sword = tool("diamond_sword", 1000, 1561, true), hoe = tool("diamond_hoe", 1000, 1561, false), fine = tool("diamond_axe", 10, 1561, true);
        Item openable = shulker(null, tool("diamond_pickaxe", 900, 1561, true), tool("iron_axe", 200, 250, false));
        Item named = shulker("Tools", tool("diamond_shovel", 900, 1561, true));
        Item onlyNeeds = shulker(null, tool("iron_hoe", 200, 250, false));
        Item kitBox = shulker(null, tool("diamond_boots", 300, 400, true));
        Item cooling = shulker(null, tool("diamond_helmet", 300, 400, true));
        Box b = box(1, sword, hoe, fine, openable, named, onlyNeeds, kitBox, cooling);
        String coolKey = ManagerIndex.shulkerKey(cooling);
        RepairRules.Scan scan = RepairRules.scan(List.of(b), x -> true, 50, true, it -> it == kitBox, k -> k.equals(coolKey), true);
        assertEquals(1, scan.mend().size(), "the sword; the fine axe is not low");
        assertEquals(1, scan.needs().size(), "the hoe");
        assertEquals(1, scan.boxes().size(), "one box is openable");
        assertEquals(List.of("minecraft:diamond_pickaxe#d900"), scan.boxes().get(0).mend().stream().map(RepairRules.Cand::key).toList());
        assertEquals(1, scan.boxes().get(0).needs().size(), "with a chest set the box's needs-repair items come out too");
        // named + only-needs + kit + cooling = 4 boxes, 1 item each
        assertEquals(4, scan.inShulkers());
        assertEquals(1, scan.inShulkerReasons().get(RepairRules.IN_A_NAMED_SHULKER));
        assertEquals(1, scan.inShulkerReasons().get(RepairRules.IN_A_KIT));
        assertEquals(2, scan.inShulkerReasons().get(RepairRules.IN_A_SHULKER));
        assertEquals(1, scan.inBoxes());
        // Boxes off: nothing is opened
        RepairRules.Scan off = RepairRules.scan(List.of(b), x -> true, 50, false, it -> false, k -> false, true);
        assertTrue(off.boxes().isEmpty());
        // A container that may not be taken from is not looked at
        assertTrue(RepairRules.scan(List.of(b), x -> false, 50, true, it -> false, k -> false, true).mend().isEmpty());
    }

    @Test
    void theRouteIsNearestNeighbourAndTheCapsApply() {
        List<RepairRules.Cand> mend = List.of(cand("far", 0, "k:far", 100, 1000), cand("near", 0, "k:near", 100, 1000), cand("mid", 0, "k:mid", 100, 1000));
        Map<String, double[]> pos = Map.of("far", new double[] {100, 0}, "near", new double[] {1, 0}, "mid", new double[] {20, 0});
        RepairRules.Scan scan = new RepairRules.Scan(mend, List.of(), List.of(), 0, Map.of());
        RepairRules.Plan plan = RepairRules.plan(scan, 1000, 30, 0, false, 0, 0, pos::get, 64, 8);
        assertEquals(List.of("k:near", "k:mid", "k:far"), plan.batch().loose().stream().map(RepairRules.Cand::key).toList());
        // The item cap of the pass leaves the rest
        RepairRules.Plan capped = RepairRules.plan(scan, 1000, 30, 0, false, 0, 0, pos::get, 2, 8);
        assertEquals(2, capped.batch().loose().size());
        assertEquals(1, capped.left());
        // The room: 5 free slots, 1 for the bottles' stack and 1 kept free leaves the items 3 at most (1 + 1 bottle stack + 1)
        RepairRules.Plan room = RepairRules.plan(scan, 1000, 4, 0, false, 0, 0, pos::get, 64, 8);
        assertTrue(room.batch().loose().size() <= 2, "" + room.batch().loose().size());
        // The box cap skips the boxes beyond it with box cap
        List<RepairRules.BoxStop> boxes = List.of(new RepairRules.BoxStop("near", 3, "s:one", List.of(cand("near", 3, "k:b1", 100, 1000)), List.of()),
            new RepairRules.BoxStop("mid", 3, "s:two", List.of(cand("mid", 3, "k:b2", 100, 1000)), List.of()));
        RepairRules.Plan boxed = RepairRules.plan(new RepairRules.Scan(List.of(), List.of(), boxes, 0, Map.of()), 1000, 30, 0, false, 0, 0, pos::get, 64, 1);
        assertEquals(1, boxed.batch().boxes().size());
        assertEquals(List.of(RepairRules.BOX_CAP), boxed.skipped().stream().map(RepairRules.Skipped::reason).toList());
        assertEquals(0, RepairRules.plan(new RepairRules.Scan(List.of(), List.of(), boxes, 0, Map.of()), 1000, 30, 0, false, 0, 0, pos::get, 64, 0).batch().boxes().size(), "0 means none");
    }

    // The gate

    private static RepairPassGate.Facts facts(boolean localOn, boolean known, boolean enabled, boolean auto, boolean manual, boolean orderBusy, boolean hasCand, long since) {
        return new RepairPassGate.Facts(localOn, known, enabled, auto, manual, true, true, orderBusy, false, true, false, true, false, hasCand, hasCand, false, since, 30 * 60_000L);
    }

    @Test
    void theGateDecidesWhenAPassMayStart() {
        long later = 31 * 60_000L;
        assertEquals(RepairPassGate.State.OFF, RepairPassGate.decide(facts(false, true, true, true, false, false, true, later)).state());
        RepairPassGate.Decision noSettings = RepairPassGate.decide(facts(true, false, true, true, false, false, true, later));
        assertEquals(RepairPassGate.NO_SETTINGS, noSettings.why());
        assertFalse(noSettings.start(), "no settings: no automatic passes");
        assertTrue(RepairPassGate.decide(facts(true, false, true, true, true, false, true, later)).start(), "a local run uses the defaults");
        assertEquals(RepairPassGate.DISABLED, RepairPassGate.decide(facts(true, true, false, true, true, false, true, later)).why());
        assertTrue(RepairPassGate.decide(facts(true, true, true, true, false, false, true, later)).start());
        assertEquals(RepairPassGate.ORDER, RepairPassGate.decide(facts(true, true, true, true, true, true, true, later)).why(), "orders always first, manual too");
        assertEquals(RepairPassGate.COOLDOWN, RepairPassGate.decide(facts(true, true, true, true, false, false, true, 60_000L)).why());
        assertTrue(RepairPassGate.decide(facts(true, true, true, true, true, false, true, 60_000L)).start(), "a manual run ignores the wait");
        assertFalse(RepairPassGate.decide(facts(true, true, true, false, false, false, true, later)).start(), "auto off");
        assertEquals(RepairPassGate.NO_CANDIDATES, RepairPassGate.decide(new RepairPassGate.Facts(true, true, true, true, false, true, true, false, false, true, false, true, false, false, false, false, later, 1)).why());
        assertEquals(RepairPassGate.RESERVE, RepairPassGate.decide(new RepairPassGate.Facts(true, true, true, true, false, true, true, false, false, true, false, true, false, false, true, false, later, 1)).why());
        assertEquals(RepairPassGate.PLAYER_NEAR, RepairPassGate.decide(new RepairPassGate.Facts(true, true, true, true, false, true, true, false, false, true, true, true, false, true, true, false, later, 1)).why());
        assertEquals(RepairPassGate.KIT_CONFLICT, RepairPassGate.decide(new RepairPassGate.Facts(true, true, true, true, false, true, true, false, false, true, false, true, true, true, true, false, later, 1)).why());
    }

    @Test
    void theHeartbeatObjectHasTheContractsShape() {
        JsonObject o = RepairPassGate.beat(RepairPassGate.State.WAITING, RepairPassGate.ORDER, 17, "auto", 3, 12, true, 12, 640, 4, 9, 5, 2, 2400, 1728, 672, 0, 0, null);
        assertEquals(1, o.get("v").getAsInt());
        assertEquals("waiting", o.get("state").getAsString());
        assertEquals("order", o.get("why").getAsString());
        assertEquals(3, o.getAsJsonObject("progress").get("done").getAsInt());
        assertEquals(5, o.getAsJsonObject("candidates").get("inBoxes").getAsInt());
        assertEquals(672, o.getAsJsonObject("bottles").get("usable").getAsInt());
        assertFalse(o.has("runDone"), "nothing acknowledged yet: absent keeps the server's value");
        assertTrue(o.get("reportHash").isJsonNull());
        JsonObject running = RepairPassGate.beat(RepairPassGate.State.RUNNING, "order", 17, "manual", 0, 1, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1767225600L, 5, "a".repeat(40));
        assertTrue(running.get("why").isJsonNull(), "why only with waiting, blocked or idle");
        assertTrue(running.get("progress").isJsonNull());
        assertEquals(1767225600L, running.get("runDone").getAsLong());
        assertEquals(5, running.get("stopDone").getAsLong());
    }

    // Memory

    @Test
    void theMemoryExpiresAndIsPerVariant() {
        AtomicLong now = new AtomicLong(1_000_000);
        RepairMemory m = new RepairMemory(now::get);
        m.failed("v1");
        assertTrue(m.recentlyFailed("v1"));
        assertFalse(m.recentlyFailed("v2"));
        now.addAndGet(RepairMemory.FAILURE_MS + 1);
        assertFalse(m.recentlyFailed("v1"), "6 h");
        m.boxOpened("s:abc");
        now.addAndGet(RepairMemory.FAILURE_MS);
        assertTrue(m.boxOnCooldown("s:abc"));
        now.addAndGet(RepairMemory.DAY_MS);
        assertFalse(m.boxOnCooldown("s:abc"), "24 h");
        m.repaired("minecraft:pick#d100", "minecraft:pick");
        assertEquals("minecraft:pick", m.newKeyFor("minecraft:pick#d100"));
        m.noRepack("v9");
        assertTrue(m.noRepackHold("v9"));
        now.addAndGet(RepairMemory.DAY_MS + 1);
        assertNull(m.newKeyFor("minecraft:pick#d100"));
        assertFalse(m.noRepackHold("v9"));
    }

    // The report

    @Test
    void theReportCountsItsItemsAndItsHashIgnoresTime() {
        RepairPassReport r = new RepairPassReport(17, "manual", 1000, 50);
        r.add(new RepairPassReport.Item("minecraft:pick#d1", "repaired", null, "118,64,-33", "121,64,-33", 0, 1561, null));
        r.add(new RepairPassReport.Item("minecraft:sword#d2", "partial", null, "118,64,-33", null, 100, 2031, "s:abc"));
        r.skip("minecraft:axe#d3", RepairRules.RESERVE, "118,64,-33", 500, 1561, null);
        r.skip("minecraft:axe#d4", RepairRules.RESERVE, "118,64,-33", 500, 1561, null);
        r.bottlesUsed = 212;
        r.boxesOpened = 1;
        String h1 = r.hash();
        r.end(RepairPassReport.DONE, 999_999);
        assertFalse(h1.equals(r.hash()), "ending changes the state, so the report is sent again");
        RepairPassReport twin = new RepairPassReport(17, "manual", 5000, 50);
        twin.add(new RepairPassReport.Item("minecraft:pick#d1", "repaired", null, "118,64,-33", "121,64,-33", 0, 1561, null));
        twin.add(new RepairPassReport.Item("minecraft:sword#d2", "partial", null, "118,64,-33", null, 100, 2031, "s:abc"));
        twin.skip("minecraft:axe#d3", RepairRules.RESERVE, "118,64,-33", 500, 1561, null);
        twin.skip("minecraft:axe#d4", RepairRules.RESERVE, "118,64,-33", 500, 1561, null);
        twin.bottlesUsed = 212;
        twin.boxesOpened = 1;
        twin.end(RepairPassReport.DONE, 123);
        assertEquals(r.hash(), twin.hash(), "the times are not in the hash");
        JsonObject body = JsonParser.parseString(new String(r.body("srv", "stash", "Bot", null), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, body.get("version").getAsInt());
        assertEquals(r.hash(), body.get("hash").getAsString());
        JsonObject pass = body.getAsJsonObject("pass");
        assertEquals(17, pass.get("id").getAsInt());
        assertEquals("done", pass.get("state").getAsString());
        assertEquals(1, pass.get("repaired").getAsInt());
        assertEquals(1, pass.get("partial").getAsInt());
        assertEquals(2, pass.get("skipped").getAsInt());
        assertEquals(2, pass.getAsJsonObject("skippedBy").get("reserve").getAsInt());
        assertEquals(212, pass.get("bottlesUsed").getAsInt());
        assertEquals(1, pass.get("boxesOpened").getAsInt());
        assertEquals(4, body.getAsJsonArray("items").size());
        assertEquals("s:abc", body.getAsJsonArray("items").get(1).getAsJsonObject().get("box").getAsString());
        assertFalse(body.has("containers"));
    }

    // Heartbeat and reply

    @Test
    void theHeartbeatCarriesRepairHashAndPassForABotWithPasses() {
        ManagerIndex.Built built = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        JsonObject pass = RepairPassGate.beat(RepairPassGate.State.IDLE, null, 0, null, 0, 0, false, 0, 0, 0, 0, 0, 0, 0, 1728, 0, 0, 0, null);
        byte[] with = ManagerIndex.heartbeat(built, -1, null, null, null, null, null, null, null, null, null, null, new ManagerIndex.RepairHb("b".repeat(40), pass));
        JsonObject o = JsonParser.parseString(new String(with, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("b".repeat(40), o.get("repairHash").getAsString());
        assertEquals("idle", o.getAsJsonObject("repairPass").get("state").getAsString());
        byte[] none = ManagerIndex.heartbeat(built, -1, null, null, null, null, null, null, null, null, null, null, new ManagerIndex.RepairHb(null, null));
        assertTrue(JsonParser.parseString(new String(none, StandardCharsets.UTF_8)).getAsJsonObject().get("repairHash").isJsonNull(), "no cache: explicit null");
        byte[] off = ManagerIndex.heartbeat(built, -1, null, null, null, null, null, null, null, null, null, null, null);
        JsonObject offObject = JsonParser.parseString(new String(off, StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(offObject.has("repairHash") || offObject.has("repairPass"), "passes off: neither key");
        assertTrue(offObject.get("repair").getAsBoolean(), "the v1.2 flag is unchanged");
    }

    @Test
    void theReplyIsReadWithSettingsRunStopAndNeedReport() {
        RepairSettings s = RepairSettings.of(5, true, true, 60, 10, null, true);
        JsonObject reply = new JsonObject();
        reply.addProperty("repairHash", s.hash());
        reply.add("repairSettings", s.toJson());
        reply.addProperty("repairRun", 1767225600L);
        reply.addProperty("repairStop", 1767225700L);
        reply.addProperty("needRepairReport", true);
        ManagerIndex.RepairReply r = ManagerIndex.repairReply(reply.toString());
        assertTrue(r.known());
        assertEquals(s, r.settings());
        assertEquals(1767225600L, r.run());
        assertEquals(1767225700L, r.stop());
        assertTrue(r.needReport());
        assertFalse(ManagerIndex.repairReply("{\"ok\":true}").known(), "an older server");
        reply.getAsJsonObject("repairSettings").addProperty("hash", "0".repeat(40));
        ManagerIndex.RepairReply bad = ManagerIndex.repairReply(reply.toString());
        assertNull(bad.settings());
        assertNotNull(bad.invalid());
        assertEquals(-1, ManagerIndex.repairReply("{\"repairHash\":null}").run());
    }

    @Test
    void needsRepairIsLowWithoutMendingAndMendIsLowWithIt() {
        Item hoe = tool("diamond_hoe", 1000, 1561, false), pick = tool("diamond_pickaxe", 1000, 1561, true), fine = tool("diamond_axe", 10, 1561, false);
        assertTrue(RepairRules.needsRepair(hoe, 50));
        assertFalse(RepairRules.needsRepair(pick, 50), "Mending items are never routed to the needs-repair chest");
        assertFalse(RepairRules.needsRepair(fine, 50));
        assertTrue(RepairRules.mend(pick, 50));
        assertFalse(RepairRules.mend(hoe, 50));
        Item book = tool("enchanted_book", 100, 200, true);
        assertFalse(RepairRules.mend(book, 100), "an enchanted book only stores Mending");
        Item box = shulker(null);
        assertFalse(RepairRules.needsRepair(box, 100));
        Item stackable = tool("diamond_hoe", 1000, 1561, false);
        stackable.maxStack = 64;
        assertFalse(RepairRules.needsRepair(stackable, 100), "only single items");
    }

    @Test
    void theContainerIdOfABoxIsItsSmallestPart() {
        assertEquals("5,64,0", KitRouting.canonicalId(box(5)));
    }

    // The review fixes

    @Test
    void theAcknowledgedIdsSurviveARestart(@TempDir Path dir) {
        Path file = dir.resolve("repair-settings.json");
        RepairStore a = new RepairStore(file);
        assertEquals(0, a.ids("srv", "stash")[0]);
        a.putIds("srv", "stash", 1767225600L, 1767225700L);
        a.putIds("srv", "stash", 5, 5);
        assertEquals(1767225600L, a.ids("srv", "stash")[0], "an id never goes down");
        RepairStore b = new RepairStore(file);
        assertEquals(1767225600L, b.ids("srv", "stash")[0]);
        assertEquals(1767225700L, b.ids("srv", "stash")[1]);
        assertEquals(0, b.ids("srv", "other")[0]);
        RepairSettings s = RepairSettings.of(2, true, true, 30, 100, null, true);
        b.put("srv", "stash", s);
        RepairStore c = new RepairStore(file);
        assertEquals(s, c.get("srv", "stash"));
        assertEquals(1767225700L, c.ids("srv", "stash")[1]);
    }

    @Test
    void aTooDeeplyNestedCacheFileIsIgnored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("repair-settings.json");
        Files.writeString(file, "{\"stashes\":" + "[".repeat(300) + "]".repeat(300) + "}");
        RepairStore store = new RepairStore(file);
        assertNull(store.get("srv", "stash"));
        assertEquals(0, store.ids("srv", "stash")[0]);
    }

    @Test
    void itemsTriedWithNoHeadwayAreLeftOutOfLaterPlans() {
        RepairRules.Cand a = cand("1,64,1", 0, "minecraft:a#d1", 500, 1000), b = cand("1,64,1", 1, "minecraft:b#d2", 500, 1000);
        RepairRules.BoxStop box = new RepairRules.BoxStop("1,64,1", 5, "s:x", List.of(cand("1,64,1", 5, "minecraft:c#d3", 500, 1000)), List.of());
        RepairRules.Scan scan = new RepairRules.Scan(List.of(a, b), List.of(), List.of(box), 0, Map.of());
        RepairRules.Scan cut = RepairRules.without(scan, java.util.Set.of(a.memoryKey(), "minecraft:c#d3"));
        assertEquals(List.of(b), cut.mend());
        assertTrue(cut.boxes().isEmpty(), "a box with nothing left to take is not opened");
        assertSame(scan, RepairRules.without(scan, java.util.Set.of()));
    }

    @Test
    void theLocalLimitsHoldForManualRunsToo() {
        long min = 60_000L;
        assertFalse(RepairPassGate.limitsOk(true, 3 * min, 5, 0, 4), "a manual run within the gap waits");
        assertTrue(RepairPassGate.limitsOk(true, 5 * min, 5, 0, 4));
        assertTrue(RepairPassGate.limitsOk(false, 0, 5, 0, 4), "the gap is for manual runs; automatic ones have their own");
        assertFalse(RepairPassGate.limitsOk(true, 60 * min, 5, 4, 4), "4 passes this hour");
        assertFalse(RepairPassGate.limitsOk(false, 60 * min, 5, 4, 4));
        assertTrue(RepairPassGate.limitsOk(false, 60 * min, 5, 3, 4));
        assertTrue(RepairPassGate.limitsOk(true, 60 * min, 0, 100, 0), "0 is no limit");
        assertEquals(1728, RepairPassGate.reserve(1728, 0));
        assertEquals(1728, RepairPassGate.reserve(1728, 100));
        assertEquals(5000, RepairPassGate.reserve(1728, 5000), "the higher of the two");
        assertEquals(0, RepairRules.usable(2000, 0, RepairPassGate.reserve(100, 5000)));
    }

    @Test
    void aQueuedManualRunExpiresAndIsDroppedWhileDisabled() {
        long now = 100_000_000L;
        assertTrue(RepairPassGate.manualLive(true, now - 9 * 60_000L, now, false));
        assertFalse(RepairPassGate.manualLive(true, now - 11 * 60_000L, now, false), "10 minutes");
        assertFalse(RepairPassGate.manualLive(true, now, now, true), "disabled on the website");
        assertFalse(RepairPassGate.manualLive(false, now, now, false));
    }
}
