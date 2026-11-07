package com.porter.qwertestash.manager;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.DeliveryContents.Chest;
import com.porter.qwertestash.orders.DeliveryBook;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The delivery contents report (1.11.0): the snapshot builder, the hash, the debounce, and that no position is in it. */
class DeliveryContentsTest {
    @org.junit.jupiter.api.Test
    void sentLogIsThrottledButAlwaysLogsTheFirstSuccessAfterAFailure() {
        DeliveryContents.SentLog log = new DeliveryContents.SentLog();
        org.junit.jupiter.api.Assertions.assertTrue(log.sent(1_000));
        org.junit.jupiter.api.Assertions.assertFalse(log.sent(2_000));
        org.junit.jupiter.api.Assertions.assertTrue(log.sent(1_000 + DeliveryContents.SentLog.INTERVAL_MS));
        log.failed();
        org.junit.jupiter.api.Assertions.assertTrue(log.sent(61_500));
        org.junit.jupiter.api.Assertions.assertFalse(log.sent(62_000));
        org.junit.jupiter.api.Assertions.assertEquals("Delivery report sent: 2 chests", DeliveryContents.SentLog.line(2));
        org.junit.jupiter.api.Assertions.assertEquals("Delivery report sent: 1 chest", DeliveryContents.SentLog.line(1));
    }

    private static Item item(String id, int count, int maxStack) {
        Item it = new Item();
        it.id = id;
        it.name = Item.prettyName(id);
        it.count = count;
        it.maxStack = maxStack;
        return it;
    }

    private static Item shulker(Item... inside) {
        Item s = item("red_shulker_box", 1, 1);
        s.contents = new ArrayList<>(List.of(inside));
        return s;
    }

    private static Item[] slots(int size, Object... pairs) {
        Item[] c = new Item[size];
        for (int i = 0; i < pairs.length; i += 2) c[(Integer) pairs[i]] = (Item) pairs[i + 1];
        return c;
    }

    private static JsonObject json(DeliveryContents.Built b) {
        return JsonParser.parseString(new String(b.json(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static DeliveryContents.Built build(Chest... chests) {
        return DeliveryContents.build("2b2t.org", "stash", "Bot", List.of(chests));
    }

    private static JsonObject chest(DeliveryContents.Built b, int n) {
        return json(b).getAsJsonArray("chests").get(n).getAsJsonObject();
    }

    @Test
    void noChestsNoReport() {
        assertNull(build());
    }

    @Test
    void itemsKeepTheirSlotsAndAnUnreadChestHasNone() {
        DeliveryContents.Built b = build(new Chest(54, false, 1000, slots(54, 5, item("cobblestone", 64, 64), 0, shulker(item("diamond", 64, 64)))),
            new Chest(54, true, 0, null));
        JsonObject root = json(b);
        assertEquals(1, root.get("version").getAsInt());
        assertEquals(b.hash(), root.get("hash").getAsString());
        assertEquals(1000, root.get("readAt").getAsLong());
        JsonObject c0 = chest(b, 0);
        assertEquals(0, c0.get("n").getAsInt());
        assertEquals(54, c0.get("size").getAsInt());
        assertFalse(c0.get("full").getAsBoolean());
        JsonArray items = c0.getAsJsonArray("items");
        assertEquals(2, items.size());
        assertEquals(0, items.get(0).getAsJsonObject().get("slot").getAsInt());
        assertTrue(items.get(0).getAsJsonObject().get("shulker").getAsBoolean());
        JsonObject cobble = items.get(1).getAsJsonObject();
        assertEquals(5, cobble.get("slot").getAsInt());
        assertEquals("minecraft:cobblestone", cobble.get("id").getAsString());
        assertEquals("Cobblestone", cobble.get("name").getAsString());
        assertEquals(64, cobble.get("count").getAsInt());
        assertEquals(64, cobble.get("maxStack").getAsInt());
        assertFalse(cobble.has("shulker"));
        JsonObject c1 = chest(b, 1);
        assertEquals(1, c1.get("n").getAsInt());
        assertTrue(c1.get("full").getAsBoolean());
        assertTrue(c1.get("items").isJsonNull());
        assertEquals(0, c1.get("readAt").getAsLong());
    }

    @Test
    void contentsWithoutAReadTimeAreUnknown() {
        assertTrue(chest(build(new Chest(27, false, 0, slots(27, 0, item("stone", 1, 64)))), 0).get("items").isJsonNull());
    }

    @Test
    void slotsAreCappedAtTheChestSize() {
        Item[] big = slots(54, 0, item("stone", 1, 64), 40, item("dirt", 1, 64));
        JsonObject c = chest(build(new Chest(27, false, 5, big)), 0);
        assertEquals(27, c.get("size").getAsInt());
        assertEquals(1, c.getAsJsonArray("items").size());
    }

    @Test
    void shulkerContentsAreGroupedById() {
        Item box = shulker(item("diamond", 64, 64), item("diamond", 64, 64), item("gold_ingot", 10, 64), item("diamond", 1, 64));
        JsonArray contents = chest(build(new Chest(27, false, 5, slots(27, 3, box))), 0).getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonArray("contents");
        assertEquals(2, contents.size());
        assertEquals("minecraft:diamond", contents.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(129, contents.get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(10, contents.get(1).getAsJsonObject().get("count").getAsInt());
    }

    @Test
    void anEmptyShulkerStillSaysItIsOne() {
        JsonObject o = chest(build(new Chest(27, false, 5, slots(27, 0, shulker()))), 0).getAsJsonArray("items").get(0).getAsJsonObject();
        assertTrue(o.get("shulker").getAsBoolean());
        assertEquals(0, o.getAsJsonArray("contents").size());
    }

    @Test
    void theSummaryHasAtMost27Entries() {
        Item[] inside = new Item[40];
        for (int i = 0; i < inside.length; i++) inside[i] = item("item_" + i, 1, 64);
        assertEquals(27, DeliveryContents.summary(shulker(inside)).size());
    }

    @Test
    void namesAreCutAndStripped() {
        Item named = item("paper", 1, 64);
        named.customName = true;
        named.customText = "A\u0007B" + "x".repeat(200);
        String name = DeliveryContents.name(named);
        assertTrue(name.length() <= 64);
        assertTrue(name.startsWith("AB"));
        assertFalse(name.contains("\u0007"));
        assertEquals("Diamond Sword", DeliveryContents.name(item("diamond_sword", 1, 1)));
    }

    @Test
    void durabilityOnlyWhenDamaged() {
        Item pick = item("diamond_pickaxe", 1, 1);
        pick.damage = 100;
        pick.maxDamage = 1561;
        JsonArray items = chest(build(new Chest(27, false, 5, slots(27, 0, pick, 1, item("stone", 1, 64)))), 0).getAsJsonArray("items");
        assertEquals(100, items.get(0).getAsJsonObject().get("damage").getAsInt());
        assertEquals(1561, items.get(0).getAsJsonObject().get("maxDamage").getAsInt());
        assertFalse(items.get(1).getAsJsonObject().has("damage"));
    }

    @Test
    void atMostSixteenChests() {
        List<Chest> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) many.add(new Chest(54, false, 0, null));
        assertEquals(16, DeliveryContents.build("s", "t", "p", many).chests());
    }

    @Test
    void theHashIgnoresReadTimes() {
        DeliveryContents.Built one = build(new Chest(54, false, 1000, slots(54, 2, item("stone", 3, 64))));
        DeliveryContents.Built two = build(new Chest(54, false, 999_999, slots(54, 2, item("stone", 3, 64))));
        assertEquals(one.hash(), two.hash());
        assertNotEquals(one.readAt(), two.readAt());
        // A count, a slot or fullness does change it
        assertNotEquals(one.hash(), build(new Chest(54, false, 1000, slots(54, 2, item("stone", 4, 64)))).hash());
        assertNotEquals(one.hash(), build(new Chest(54, false, 1000, slots(54, 3, item("stone", 3, 64)))).hash());
        assertNotEquals(one.hash(), build(new Chest(54, true, 1000, slots(54, 2, item("stone", 3, 64)))).hash());
        // Never read versus read empty differ
        assertNotEquals(build(new Chest(54, false, 0, null)).hash(), build(new Chest(54, false, 5, new Item[54])).hash());
    }

    @Test
    void noPositionAnywhere() {
        String text = new String(build(new Chest(54, false, 1000, slots(54, 1, shulker(item("diamond", 1, 64))))).json(), StandardCharsets.UTF_8);
        for (String key : List.of("\"x\"", "\"y\"", "\"z\"", "\"pos\"", "\"at\"", "\"dimension\"")) assertFalse(text.contains(key), key);
    }

    private static void addBox(Stash stash, int y, long readAt, Item[] contents) {
        List<BlockPos> parts = new ArrayList<>(List.of(new BlockPos(0, y, 0), new BlockPos(1, y, 0)));
        Stash.Box box = new Stash.Box(parts.get(0), parts);
        box.delivery = true;
        box.contents = contents;
        box.readAt = readAt;
        stash.boxes.add(box);
    }

    @Test
    void theStashChestsComeInFillOrderWithContentsFromTheBoxes() {
        Stash stash = new Stash("s", "2b2t.org");
        DeliveryBook book = new DeliveryBook(stash.deliveryStorage);
        DeliveryBook.Entry low = book.add(List.of(new DeliveryBook.Pos(0, 64, 0), new DeliveryBook.Pos(1, 64, 0)));
        low.primary = true;
        low.full = true;
        book.add(List.of(new DeliveryBook.Pos(0, 66, 0), new DeliveryBook.Pos(1, 66, 0)));
        book.add(List.of(new DeliveryBook.Pos(0, 68, 0), new DeliveryBook.Pos(1, 68, 0)));
        addBox(stash, 64, 100, slots(54, 0, item("stone", 1, 64)));
        addBox(stash, 66, 200, slots(54, 1, item("dirt", 2, 64)));
        List<Chest> chests = DeliveryContents.collect(stash);
        assertEquals(3, chests.size());
        // Chests not known to be full first (lowest first), the full one last
        assertFalse(chests.get(0).full());
        assertEquals(200, chests.get(0).readAt());
        assertEquals(0, chests.get(1).readAt());
        assertNull(chests.get(1).contents());
        assertEquals(54, chests.get(1).size());
        assertTrue(chests.get(2).full());
        assertNotNull(chests.get(2).contents());
    }

    @Test
    void theDeliveryBoxesAreNeverInTheIndex() {
        Stash stash = new Stash("s", "2b2t.org");
        addBox(stash, 64, 100, slots(54, 0, item("stone", 1, 64)));
        ManagerIndex.Built b = ManagerIndex.build(stash, true, "Bot", "1.21.4", "overworld", id -> null);
        assertEquals(0, b.itemTypes());
        assertEquals(0, b.total());
    }

    @Test
    void theHeartbeatCarriesTheHashOnlyWhenThereIsOne() {
        ManagerIndex.Built idx = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        JsonObject with = JsonParser.parseString(new String(ManagerIndex.heartbeat(idx, -1, null, null, null, null, null, null, "abc"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("abc", with.get("deliveryHash").getAsString());
        JsonObject without = JsonParser.parseString(new String(ManagerIndex.heartbeat(idx, -1, null, null, null, null, null, null, null), StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(without.has("deliveryHash"));
    }

    @Test
    void needDeliveryIsReadFromTheReply() {
        assertTrue(DeliveryContents.needsDelivery("{\"ok\":true,\"needDelivery\":true}"));
        assertFalse(DeliveryContents.needsDelivery("{\"ok\":true,\"needDelivery\":false}"));
        assertFalse(DeliveryContents.needsDelivery("{\"ok\":true}"));
        assertFalse(DeliveryContents.needsDelivery("nonsense"));
    }

    // The schedule

    @Test
    void aNewHashIsSentOnceAndNotAgain() {
        DeliveryContents.Schedule s = new DeliveryContents.Schedule();
        s.supported(true);
        assertFalse(s.due(null, 0));
        assertTrue(s.due("a", 10_000));
        s.attempt(10_000);
        s.sent("a");
        assertFalse(s.due("a", 100_000));
        assertTrue(s.due("b", 100_000));
    }

    @Test
    void sendsAreDebouncedToFiveSeconds() {
        DeliveryContents.Schedule s = new DeliveryContents.Schedule();
        s.supported(true);
        s.attempt(10_000);
        s.sent("a");
        assertFalse(s.due("b", 12_000));
        assertFalse(s.due("b", 14_999));
        assertTrue(s.due("b", 15_000));
        // A failed attempt is not tried again at once either
        s.attempt(15_000);
        assertFalse(s.due("b", 16_000));
        assertTrue(s.due("b", 20_000));
    }

    @Test
    void theServerAskingForcesAResendOfTheSameHash() {
        DeliveryContents.Schedule s = new DeliveryContents.Schedule();
        s.supported(true);
        s.attempt(0);
        s.sent("a");
        assertFalse(s.due("a", 60_000));
        s.ask();
        assertTrue(s.due("a", 60_000));
        s.attempt(60_000);
        s.sent("a");
        assertFalse(s.due("a", 120_000));
    }

    @Test
    void anAskIsStillDebounced() {
        DeliveryContents.Schedule s = new DeliveryContents.Schedule();
        s.supported(true);
        s.attempt(10_000);
        s.sent("a");
        s.ask();
        assertFalse(s.due("a", 12_000));
        assertTrue(s.due("a", 15_000));
    }

    @Test
    void forgettingSendsTheSameHashAgain() {
        DeliveryContents.Schedule s = new DeliveryContents.Schedule();
        s.supported(true);
        s.attempt(0);
        s.sent("a");
        s.forget();
        assertTrue(s.due("a", 60_000));
    }

    @Test
    void anOldServerIsNeverSentAnything() {
        // Its heartbeat reply has no needDelivery key
        assertFalse(DeliveryContents.supportsDelivery("{\"ok\":true,\"needIndex\":false}"));
        assertTrue(DeliveryContents.supportsDelivery("{\"ok\":true,\"needDelivery\":false}"));
        assertFalse(DeliveryContents.supportsDelivery("nonsense"));
        DeliveryContents.Schedule s = new DeliveryContents.Schedule();
        s.supported(DeliveryContents.supportsDelivery("{\"ok\":true}"));
        s.ask();
        assertFalse(s.due("a", 1_000_000));
        s.supported(DeliveryContents.supportsDelivery("{\"ok\":true,\"needDelivery\":false}"));
        assertTrue(s.due("a", 1_000_000));
    }

    @Test
    void aFailureHasARetryTimerOfItsOwnThatDoubles() {
        DeliveryContents.Schedule s = new DeliveryContents.Schedule();
        s.supported(true);
        long now = 100_000;
        s.attempt(now);
        s.failed(now);
        assertFalse(s.due("a", now + 59_999));
        assertTrue(s.due("a", now + 60_000));
        now += 60_000;
        s.attempt(now);
        s.failed(now);
        assertFalse(s.due("a", now + 119_999));
        assertTrue(s.due("a", now + 120_000));
        for (int i = 0; i < 10; i++) {
            now += 1_000_000;
            s.attempt(now);
            s.failed(now);
        }
        assertFalse(s.due("a", now + 599_999));
        assertTrue(s.due("a", now + 600_000));
        // A success starts over
        s.attempt(now);
        s.sent("a");
        s.failed(now);
        assertFalse(s.due("b", now + 59_999));
        assertTrue(s.due("b", now + 60_000));
    }

    @Test
    void aRejectedKeyIsNotAskedForAgainUntilItChanges() {
        DeliveryContents.Schedule s = new DeliveryContents.Schedule();
        s.supported(true);
        s.attempt(0);
        s.rejected("a");
        s.ask();
        assertFalse(s.due("a", 1_000_000));
        assertTrue(s.due("b", 1_000_000));
    }

    @Test
    void aNewSessionForgetsTheServerSupport() {
        DeliveryContents.Schedule s = new DeliveryContents.Schedule();
        s.supported(true);
        s.reset();
        assertFalse(s.due("a", 1_000_000));
    }

    @Test
    void anOversizedReportIsDegradedToFit() {
        Item[] filled = new Item[54];
        for (int slot = 0; slot < 54; slot++) {
            Item[] inside = new Item[10];
            for (int i = 0; i < inside.length; i++) inside[i] = item("some_long_item_name_" + slot + "_" + i, 64, 64);
            Item box = shulker(inside);
            box.customName = true;
            box.customText = "n".repeat(64);
            filled[slot] = box;
        }
        List<Chest> chests = new ArrayList<>();
        for (int i = 0; i < 16; i++) chests.add(new Chest(54, true, 1000 + i, filled));
        // Over the cap with everything in
        DeliveryContents.Built full = null;
        for (int i = 0; i < 1; i++) full = DeliveryContents.build("s", "t", "p", chests);
        assertTrue(full.json().length <= DeliveryContents.MAX_BYTES, "length " + full.json().length);
        JsonArray array = json(full).getAsJsonArray("chests");
        assertEquals(16, array.size());
        // The first chests keep their items, shulkers without contents; any dropped chests are the last ones and unknown
        JsonObject first = array.get(0).getAsJsonObject();
        if (!first.get("items").isJsonNull()) {
            JsonObject sh = first.getAsJsonArray("items").get(0).getAsJsonObject();
            assertTrue(sh.get("shulker").getAsBoolean());
        }
        boolean seenUnknown = false;
        for (int i = 0; i < array.size(); i++) {
            boolean unknown = array.get(i).getAsJsonObject().get("items").isJsonNull();
            if (seenUnknown) assertTrue(unknown, "chest " + i + " must also be unknown");
            seenUnknown |= unknown;
            if (unknown) assertEquals(0, array.get(i).getAsJsonObject().get("readAt").getAsLong());
        }
    }

    @Test
    void aReportThatFitsKeepsTheShulkerContents() {
        Item[] one = slots(54, 0, shulker(item("diamond", 64, 64)));
        List<Chest> chests = List.of(new Chest(54, false, 5, one));
        assertTrue(chest(DeliveryContents.build("s", "t", "p", chests), 0).getAsJsonArray("items").get(0).getAsJsonObject().has("contents"));
    }

    @Test
    void theChestListIsAnEqualCheapSignatureUntilARead() {
        Item[] a = slots(27, 0, item("stone", 1, 64));
        assertEquals(List.of(new Chest(27, false, 5, a)), List.of(new Chest(27, false, 5, a)));
        // A read replaces the array and the time
        assertNotEquals(List.of(new Chest(27, false, 5, a)), List.of(new Chest(27, false, 6, slots(27, 0, item("stone", 1, 64)))));
        assertNotEquals(List.of(new Chest(27, false, 5, a)), List.of(new Chest(27, true, 5, a)));
    }
}
