package com.porter.qwertestash.manager;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.KitItems;
import com.porter.qwertestash.stash.KitNames;
import com.porter.qwertestash.stash.KitScore;
import com.porter.qwertestash.stash.KitSettings;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.Stash.Box;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kit report (KIT-CONTRACT.md v1.1): its shape, its limits, and that its hash ignores read times. */
class KitReportTest {
    private static Item stack(String id) {
        Item it = new Item();
        it.id = id;
        it.count = 1;
        it.maxStack = 64;
        return it;
    }

    /** A shulker with 27 slots of totems and stone (5 points, full: a kit by the defaults, 7 with the word kit in the name). */
    private static Item kit(String name) {
        Item it = shulker(name);
        for (int i = 0; i < 27; i++) it.contents.add(stack(i % 2 == 0 ? "totem_of_undying" : "stone"));
        return it;
    }

    /** A shulker with only some slots filled (totems and stone alternating). */
    private static Item partKit(String name, int slots) {
        Item it = shulker(name);
        for (int i = 0; i < slots; i++) it.contents.add(stack(i % 2 == 0 ? "totem_of_undying" : "stone"));
        return it;
    }

    private static Item shulker(String name) {
        Item it = new Item();
        it.id = "red_shulker_box";
        it.count = 1;
        it.maxStack = 1;
        it.customName = name != null;
        it.customText = name;
        return it;
    }

    private static Box box(int x, int y, int z, Item... items) {
        BlockPos p = new BlockPos(x, y, z);
        Box box = new Box(p, new ArrayList<>(List.of(p)));
        box.contents = new Item[27];
        for (int i = 0; i < items.length; i++) box.contents[i] = items[i];
        box.readAt = 1000;
        return box;
    }

    private static JsonObject json(KitReport.Built b) {
        return JsonParser.parseString(new String(b.json(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject container(JsonObject root, String id) {
        for (var e : root.getAsJsonArray("containers")) if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        throw new AssertionError("no container " + id);
    }

    @Test
    void theShapeFollowsTheContract() {
        Stash stash = new Stash("stash", "2b2t.org");
        Box pvp = box(118, 64, -33, kit("PvP Kit"), kit("PvP Kit"), kit(null), shulker("Toolkit"), shulker(null));
        pvp.labels.add("PvP");
        stash.boxes.add(pvp);
        stash.boxes.add(box(120, 64, -33));

        KitReport.Built built = KitReport.build(stash, false, "Qwerttze", List.of(kit("pvp KIT"), shulker("Plain"), shulker(null), kit(null)));
        JsonObject root = json(built);
        assertEquals(1, root.get("version").getAsInt());
        assertEquals("2b2t.org", root.get("server").getAsString());
        assertEquals("Qwerttze", root.get("player").getAsString());
        assertEquals(built.hash(), root.get("hash").getAsString());
        assertTrue(built.hash().matches("[0-9a-f]{40}"));
        assertEquals(1000, root.get("readAt").getAsLong());

        JsonObject c = container(root, "118,64,-33");
        assertEquals("PvP", c.get("label").getAsString());
        assertEquals(27, c.get("size").getAsInt());
        assertEquals(22, c.get("free").getAsInt());
        assertTrue(c.get("pickable").getAsBoolean());
        assertTrue(container(root, "120,64,-33").get("label").isJsonNull());

        JsonArray kits = root.getAsJsonArray("kits");
        assertEquals(2, kits.size(), "the named kit and the unnamed ones; the empty and the 'Toolkit' shulkers are not kits");
        JsonObject unnamed = kits.get(0).getAsJsonObject();
        assertTrue(unnamed.get("key").isJsonNull(), "unnamed kits are grouped under key null, listed first");
        assertTrue(unnamed.get("name").isJsonNull());
        assertEquals(1, unnamed.get("count").getAsInt());
        JsonObject named = kits.get(1).getAsJsonObject();
        assertEquals("pvp kit", named.get("key").getAsString());
        assertEquals("PvP Kit", named.get("name").getAsString());
        assertEquals("118,64,-33", named.get("container").getAsString());
        assertEquals(2, named.get("count").getAsInt());
        JsonObject score = named.getAsJsonObject("score");
        assertEquals(7, score.get("total").getAsInt());
        assertEquals(2, score.get("name").getAsInt());
        assertEquals(0, score.get("mix").getAsInt());
        assertEquals(1, score.get("full").getAsInt());
        assertEquals(4, score.get("op").getAsInt());
        assertEquals(7, named.get("minTotal").getAsInt());
        assertEquals(5, unnamed.getAsJsonObject("score").get("total").getAsInt());

        JsonArray carried = root.getAsJsonArray("carried");
        assertEquals(2, carried.size());
        assertTrue(carried.get(0).getAsJsonObject().get("key").isJsonNull() || carried.get(1).getAsJsonObject().get("key").isJsonNull());
        for (var e : carried) {
            JsonObject o = e.getAsJsonObject();
            assertEquals(1, o.get("count").getAsInt());
            assertTrue(o.getAsJsonObject("score").get("total").getAsInt() >= 4);
        }
        assertEquals(2, built.kits());
        assertEquals(2, built.carried());
    }

    @Test
    void scoreIsTheHighestBoxAndMinTotalTheLowest() {
        Stash stash = new Stash("stash", "srv");
        // Named "Kit": a full box is 7, a 6-slot box (3 totems) is name 2 + op 4 = 6, so both are kits; unnamed 3 slots is only op 4
        stash.boxes.add(box(1, 64, 1, kit("Kit"), partKit("kit", 6), partKit(null, 6)));
        JsonArray kits = json(KitReport.build(stash, false, "p", List.of())).getAsJsonArray("kits");
        assertEquals(2, kits.size());
        JsonObject named = kits.get(1).getAsJsonObject();
        assertEquals(2, named.get("count").getAsInt());
        assertEquals(7, named.getAsJsonObject("score").get("total").getAsInt());
        assertEquals(6, named.get("minTotal").getAsInt());
        JsonObject unnamed = kits.get(0).getAsJsonObject();
        assertEquals(4, unnamed.getAsJsonObject("score").get("total").getAsInt());
        assertEquals(4, unnamed.get("minTotal").getAsInt());
    }

    @Test
    void onlyBoxesThatAreKitsByTheStashsScoringAreReported() {
        Stash stash = new Stash("stash", "srv");
        stash.boxes.add(box(1, 64, 1, kit(null), kit("Kit")));
        KitReport.Built strict = KitReport.build(stash, false, "p", List.of(kit(null)), new KitScore.Scoring(6, 2, 3, 1, 4), KitItems.shared());
        JsonArray kits = json(strict).getAsJsonArray("kits");
        assertEquals(1, kits.size(), "the unnamed 5-point box is below a threshold of 6");
        assertEquals("kit", kits.get(0).getAsJsonObject().get("key").getAsString());
        assertEquals(0, json(strict).getAsJsonArray("carried").size());
        assertNotEquals(strict.hash(), KitReport.build(stash, false, "p", List.of(kit(null))).hash());
    }

    @Test
    void everyKitsContainerIsInTheContainersAndIdsAreUnique() {
        Stash stash = new Stash("stash", "srv");
        stash.boxes.add(box(1, 64, 1, kit("A Kit")));
        stash.boxes.add(box(1, 64, 1, kit("B Kit")));
        stash.boxes.add(box(2, 64, 2, kit("A Kit")));
        JsonObject root = json(KitReport.build(stash, false, "p", List.of()));
        assertEquals(2, root.getAsJsonArray("containers").size(), "the second box with the same id is skipped");
        for (var e : root.getAsJsonArray("kits")) container(root, e.getAsJsonObject().get("container").getAsString());
    }

    @Test
    void aDoubleChestHasTheSmallestPartAsItsIdAndSize54() {
        Stash stash = new Stash("stash", "srv");
        BlockPos a = new BlockPos(10, 64, 5), b = new BlockPos(10, 64, 4);
        Box dbl = new Box(a, new ArrayList<>(List.of(a, b)));
        dbl.contents = new Item[54];
        dbl.contents[0] = kit("Kit");
        stash.boxes.add(dbl);
        JsonObject c = container(json(KitReport.build(stash, false, "p", List.of())), "10,64,4");
        assertEquals(54, c.get("size").getAsInt());
        assertEquals(53, c.get("free").getAsInt());
    }

    @Test
    void pickableMeansReadSortableNotExcludedNotDropOffNotDelivery() {
        Stash stash = new Stash("stash", "srv");
        Box unread = box(1, 64, 1);
        unread.contents = null;
        unread.readAt = 0;
        Box dropOff = box(2, 64, 2);
        dropOff.dropOff = true;
        Box excluded = box(3, 64, 3);
        excluded.excluded = true;
        Box delivery = box(4, 64, 4);
        delivery.delivery = true;
        Box unreachable = box(5, 64, 5);
        unreachable.unreachable = true;
        stash.boxes.addAll(List.of(unread, dropOff, excluded, delivery, unreachable, box(6, 64, 6)));

        JsonObject root = json(KitReport.build(stash, false, "p", List.of()));
        assertFalse(container(root, "1,64,1").get("pickable").getAsBoolean());
        assertEquals(0, container(root, "1,64,1").get("free").getAsInt(), "an unread container has no known free slots");
        assertEquals(0, container(root, "1,64,1").get("readAt").getAsLong());
        assertFalse(container(root, "2,64,2").get("pickable").getAsBoolean());
        assertFalse(container(root, "5,64,5").get("pickable").getAsBoolean());
        assertTrue(container(root, "6,64,6").get("pickable").getAsBoolean());
        assertEquals(4, root.getAsJsonArray("containers").size(), "delivery and excluded containers are left out");

        JsonObject with = json(KitReport.build(stash, true, "p", List.of()));
        assertFalse(container(with, "3,64,3").get("pickable").getAsBoolean(), "an excluded container is listed when the index lists it, never pickable");
        assertEquals(5, with.getAsJsonArray("containers").size());
    }

    @Test
    void theHashIgnoresReadTimesButNotContent() {
        Stash stash = new Stash("stash", "srv");
        Box b = box(1, 64, 1, kit("A Kit"));
        stash.boxes.add(b);
        String first = KitReport.build(stash, false, "p", List.of()).hash();
        b.readAt = 99_999;
        assertEquals(first, KitReport.build(stash, false, "p", List.of()).hash(), "a read that found nothing changed is not a change");
        b.contents[1] = kit("B Kit");
        assertNotEquals(first, KitReport.build(stash, false, "p", List.of()).hash());
        assertNotEquals(KitReport.build(stash, false, "p", List.of()).hash(), KitReport.build(stash, false, "p", List.of(kit("A Kit"))).hash(), "carried counts");
    }

    @Test
    void namesAndLabelsAreCleanedAndCut() {
        Stash stash = new Stash("stash", "srv");
        Box b = box(1, 64, 1, kit("Kit " + (char) 0x202E + "x\ny" + "z".repeat(100)));
        b.labels.add("L" + (char) 7 + "abel " + "q".repeat(80));
        stash.boxes.add(b);
        JsonObject root = json(KitReport.build(stash, false, "p", List.of()));
        String name = root.getAsJsonArray("kits").get(0).getAsJsonObject().get("name").getAsString();
        assertTrue(name.length() <= 64, name);
        assertTrue(name.endsWith("…"));
        assertFalse(name.chars().anyMatch(ch -> ch < 32 || ch == 0x202E));
        String label = container(root, "1,64,1").get("label").getAsString();
        assertTrue(label.length() <= 32, label);
        assertFalse(label.chars().anyMatch(ch -> ch < 32));
        assertTrue(label.startsWith("Label q"));
    }

    @Test
    void theLimitsHold() {
        Stash stash = new Stash("stash", "srv");
        for (int i = 0; i < 5000; i++) stash.boxes.add(box(i, 64, 0));
        // 40 distinct kits are carried: at most 36 are listed
        List<Item> carried = new ArrayList<>();
        for (int i = 0; i < 40; i++) carried.add(kit("Kit " + (char) ('a' + i % 26) + i));
        KitReport.Built built = KitReport.build(stash, false, "p", carried);
        assertTrue(built.containers() <= KitReport.MAX_CONTAINERS);
        assertEquals(4096, built.containers());
        assertEquals(36, built.carried());
        assertTrue(built.json().length <= KitReport.MAX_BYTES, "under the server's 512 KiB: " + built.json().length);
    }

    @Test
    void anOversizedReportKeepsTheContainersHoldingKits() {
        Stash stash = new Stash("stash", "srv");
        for (int i = 0; i < 4096; i++) {
            Box b = box(20_000_000 + i, 64, -20_000_000);
            b.labels.add("L".repeat(32));
            stash.boxes.add(b);
        }
        // The last container holds a kit and must survive any trimming
        stash.boxes.add(box(-5, 64, 0, kit("Last Kit")));
        KitReport.Built built = KitReport.build(stash, false, "p", List.of());
        JsonObject root = json(built);
        assertTrue(built.json().length <= KitReport.MAX_BYTES);
        assertTrue(built.containers() < 4097, "trimmed: " + built.containers());
        container(root, "-5,64,0");
        assertEquals("-5,64,0", root.getAsJsonArray("kits").get(0).getAsJsonObject().get("container").getAsString());
    }

    @Test
    void theNameIsTheSmallestSpellingSoMovingStacksNeverChangesTheHash() {
        Stash a = new Stash("stash", "srv"), b = new Stash("stash", "srv");
        a.boxes.add(box(1, 64, 1, kit("pvp KIT"), kit("PvP Kit")));
        b.boxes.add(box(1, 64, 1, kit("PvP Kit"), kit("pvp KIT")));
        KitReport.Built first = KitReport.build(a, false, "p", List.of(kit("pvp KIT"), kit("PvP Kit")));
        KitReport.Built second = KitReport.build(b, false, "p", List.of(kit("PvP Kit"), kit("pvp KIT")));
        assertEquals(first.hash(), second.hash());
        JsonObject k = json(first).getAsJsonArray("kits").get(0).getAsJsonObject();
        assertEquals("PvP Kit", k.get("name").getAsString());
        assertEquals(2, k.get("count").getAsInt(), "one entry for the pair (key, container)");
        assertEquals("PvP Kit", json(first).getAsJsonArray("carried").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void neverAnEntryTheServerWouldRefuse() {
        Stash stash = new Stash("stash", "srv");
        List<Item> in = new ArrayList<>();
        for (int i = 0; i < 27; i++) in.add(i % 3 == 0 ? kit("Kit " + (char) 0x200B) : i % 3 == 1 ? kit("KIT") : kit(null));
        stash.boxes.add(box(1, 64, 1, in.toArray(new Item[0])));
        stash.boxes.add(box(2, 64, 2, in.toArray(new Item[0])));
        List<Item> carried = new ArrayList<>();
        for (int i = 0; i < 50; i++) carried.add(kit(i % 2 == 0 ? "  PvP   KIT " : null));
        carried.add(kit("§aPvP Kit"));
        carried.add(kit("lone" + (char) 0xD83D));
        JsonObject root = json(KitReport.build(stash, false, "p", carried));

        Set<String> pairs = new HashSet<>(), keys = new HashSet<>();
        for (var e : root.getAsJsonArray("kits")) {
            JsonObject o = e.getAsJsonObject();
            String key = o.get("key").isJsonNull() ? null : o.get("key").getAsString();
            assertTrue(key == null || KitNames.validKey(key), String.valueOf(key));
            assertTrue(pairs.add(key + "|" + o.get("container").getAsString()), "a repeated (key, container)");
            assertTrue(o.get("count").getAsInt() >= 1 && o.get("count").getAsInt() <= 54);
            assertScore(o.getAsJsonObject("score"));
            assertTrue(o.get("minTotal").getAsInt() >= 0 && o.get("minTotal").getAsInt() <= 40);
        }
        for (var e : root.getAsJsonArray("carried")) {
            JsonObject o = e.getAsJsonObject();
            String key = o.get("key").isJsonNull() ? null : o.get("key").getAsString();
            assertTrue(key == null || KitNames.validKey(key));
            assertTrue(keys.add(String.valueOf(key)), "a repeated carried key (null at most once)");
            assertTrue(o.get("count").getAsInt() >= 1 && o.get("count").getAsInt() <= 36, "carried count " + o.get("count").getAsInt());
            assertScore(o.getAsJsonObject("score"));
        }
        // "pvp kit" (25 + 1 spellings), and null for the unnamed and the one with an unpaired surrogate
        assertEquals(2, keys.size());
        for (var e : root.getAsJsonArray("carried")) {
            JsonObject o = e.getAsJsonObject();
            if (!o.get("key").isJsonNull()) assertEquals(26, o.get("count").getAsInt());
        }
    }

    private static void assertScore(JsonObject s) {
        int sum = 0;
        for (String part : List.of("name", "mix", "full", "op")) {
            int v = s.get(part).getAsInt();
            assertTrue(v >= 0 && v <= 10, part);
            sum += v;
        }
        assertEquals(sum, s.get("total").getAsInt());
        assertTrue(s.get("total").getAsInt() <= 40);
    }

    @Test
    void anUnnamedKitAndAKitNamedNullAreTwoEntries() {
        Stash stash = new Stash("stash", "srv");
        stash.boxes.add(box(1, 64, 1, kit(null), kit("null"), kit("Null")));
        List<Item> carried = List.of(kit(null), kit("null"));
        JsonObject root = json(KitReport.build(stash, false, "p", carried));
        JsonArray kits = root.getAsJsonArray("kits");
        assertEquals(2, kits.size());
        JsonObject unnamed = kits.get(0).getAsJsonObject(), named = kits.get(1).getAsJsonObject();
        assertTrue(unnamed.get("key").isJsonNull());
        assertEquals(1, unnamed.get("count").getAsInt());
        assertEquals("null", named.get("key").getAsString());
        assertEquals(2, named.get("count").getAsInt(), "'null' and 'Null' are one key");
        JsonArray c = root.getAsJsonArray("carried");
        assertEquals(2, c.size());
        assertTrue(c.get(0).getAsJsonObject().get("key").isJsonNull());
        assertEquals("null", c.get(1).getAsJsonObject().get("key").getAsString());
    }

    @Test
    void theFingerprintChangesWithTheStashAndTheCarriedSignatureWithTheShulkers() {
        Stash stash = new Stash("stash", "srv");
        Box b = box(1, 64, 1, kit("A Kit"));
        stash.boxes.add(b);
        long first = KitReport.fingerprint(stash, false);
        assertEquals(first, KitReport.fingerprint(stash, false), "the same stash, the same number");
        b.contents = b.contents.clone();
        assertNotEquals(first, KitReport.fingerprint(stash, false), "a read replaces the contents array");
        long second = KitReport.fingerprint(stash, false);
        b.readAt = 5;
        assertNotEquals(second, KitReport.fingerprint(stash, false));
        long third = KitReport.fingerprint(stash, false);
        stash.boxes.add(box(2, 64, 2));
        assertNotEquals(third, KitReport.fingerprint(stash, false));

        assertEquals(KitReport.carriedSignature(List.of(kit("A"), kit(null))), KitReport.carriedSignature(List.of(kit("A"), kit(null))));
        assertNotEquals(KitReport.carriedSignature(List.of(kit("A"))), KitReport.carriedSignature(List.of(kit("B"))));
        assertNotEquals(KitReport.carriedSignature(List.of(kit("A"))), KitReport.carriedSignature(List.of(kit("A"), kit("A"))));
        assertNotEquals(KitReport.carriedSignature(List.of(kit("A"))), KitReport.carriedSignature(List.of(partKit("A", 3))));
    }

    @Test
    void theFirstReportGoesOnTheBotsOwnInitiativeOnceTheKeyWasSeen() {
        String h = "a".repeat(40);
        // Nothing sent yet and the server never asked: it still goes, once the reply carried needKitsReport
        assertTrue(KitReport.due(true, true, true, 60_000, h, null, null, false));
        assertFalse(KitReport.due(true, false, true, 60_000, h, null, null, true), "an older server never sees the route");
        assertFalse(KitReport.due(false, true, true, 60_000, h, null, null, true), "kit sorting off");
        assertFalse(KitReport.due(true, true, false, 60_000, h, null, null, true), "a report for another stash");
        assertFalse(KitReport.due(true, true, true, 9_999, h, null, null, true), "at most every 10 s");
        assertFalse(KitReport.due(true, true, true, 60_000, h, h, null, false), "unchanged and not asked");
        assertTrue(KitReport.due(true, true, true, 60_000, h, h, null, true), "asked again, for example after a server restart");
        assertTrue(KitReport.due(true, true, true, 60_000, h, "b".repeat(40), null, false), "changed");
        assertFalse(KitReport.due(true, true, true, 60_000, h, null, h, true), "a refused report is not repeated until it changes");
    }

    @Test
    void aContainerOutOfTheIdRangeIsLeftOut() {
        Stash stash = new Stash("stash", "srv");
        stash.boxes.add(box(30_000_001, 64, 0));
        stash.boxes.add(box(0, 5000, 0));
        stash.boxes.add(box(1, 64, 1));
        JsonObject root = json(KitReport.build(stash, false, "p", List.of()));
        assertEquals(1, root.getAsJsonArray("containers").size());
        assertTrue(KitSettings.validId(root.getAsJsonArray("containers").get(0).getAsJsonObject().get("id").getAsString()));
    }
}
