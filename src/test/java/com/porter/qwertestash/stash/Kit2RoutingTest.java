package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.KitReport;
import com.porter.qwertestash.manager.ManagerIndex;
import com.porter.qwertestash.stash.KitRouting.Kind;
import com.porter.qwertestash.stash.Stash.Box;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KIT-CONTRACT v2 in the plugin: the chain (rule, template chest, category chest, kits chest), kit-reserved chests, the v2 report and the heartbeat's kitsHash2. */
class Kit2RoutingTest {
    private static final String HASH = "0".repeat(40);

    private static Box box(int x, int y, int z, int filled) {
        BlockPos p = new BlockPos(x, y, z);
        Box box = new Box(p, new ArrayList<>(List.of(p)));
        box.contents = new Item[27];
        for (int i = 0; i < filled; i++) box.contents[i] = shulker(null);
        box.readAt = 1000;
        return box;
    }

    private static Item stack(String id) {
        Item it = new Item();
        it.id = id;
        it.count = 1;
        it.maxStack = 64;
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

    /** A pvp assortment: 22 of three pvp items and 5 stone. */
    private static Item pvpKit(String name) {
        Item it = shulker(name);
        for (int i = 0; i < 27; i++) it.contents.add(stack(i % 5 == 4 ? "stone" : new String[]{"totem_of_undying", "end_crystal", "obsidian", "end_crystal"}[i % 5]));
        return it;
    }

    private static KitTemplate template(String id, String name, String chest) {
        List<KitTemplate.Slot> slots = new ArrayList<>();
        slots.add(new KitTemplate.Slot("minecraft:totem_of_undying", "minecraft:totem_of_undying", 1));
        slots.add(new KitTemplate.Slot("minecraft:end_crystal", "minecraft:end_crystal", 2));
        while (slots.size() < 27) slots.add(null);
        return new KitTemplate(id, name, "pvp", chest, null, 0, slots);
    }

    private static KitSettings settings(String kitsChest, Map<String, String> rules, Map<String, String> chests, List<KitTemplate> templates) {
        return new KitSettings(1, HASH, kitsChest, rules, KitScore.Scoring.DEFAULT, new KitSettings.Types(HASH, List.of(), chests, Map.of(), templates));
    }

    private static Stash stash(Box... boxes) {
        Stash s = new Stash("s", "srv");
        s.boxes.addAll(List.of(boxes));
        return s;
    }

    // The chain

    @Test
    void theChainIsRuleThenTemplateChestThenCategoryChestThenKitsChestWithoutRepeats() {
        KitTemplate t = template("t-0000aaaa", "Crystal Pack", "3,64,3");
        KitSettings s = settings("4,64,4", Map.of("crystal pack", "1,64,1"), Map.of("pvp", "2,64,2"), List.of(t));
        KitClassifier.Result why = new KitClassifier.Result(true, KitClassifier.TEMPLATE, "pvp", 27, 0, 0, 0, 0, List.of(), List.of(), "t-0000aaaa", List.of());
        assertEquals(List.of("rule", "template", "category", "kitsChest"), KitRouting.chain("crystal pack", why, s).stream().map(KitRouting.Level::source).toList());
        assertEquals(List.of("1,64,1", "3,64,3", "2,64,2", "4,64,4"), KitRouting.chain("crystal pack", why, s).stream().map(KitRouting.Level::container).toList());
        // A container that comes twice is listed at its first level
        KitSettings twice = settings("1,64,1", Map.of("crystal pack", "1,64,1"), Map.of(), List.of());
        assertEquals(1, KitRouting.chain("crystal pack", why, twice).size());
        // Nothing set: today's sorting
        assertTrue(KitRouting.chain("x", why, settings(null, Map.of(), Map.of(), List.of())).isEmpty());
        // The first level is where the copies of a template are kept
        assertEquals("1,64,1", KitRouting.templateTarget(t, s));
        assertEquals("3,64,3", KitRouting.templateTarget(t, settings("4,64,4", Map.of(), Map.of("pvp", "2,64,2"), List.of(t))));
    }

    @Test
    void aKitGoesToTheFirstUsableLevelAndAStoredKitOnlyMovesUp() {
        List<KitRouting.Level> chain = List.of(new KitRouting.Level("template", "a"), new KitRouting.Level("category", "b"), new KitRouting.Level("kitsChest", "c"));
        Set<String> all = Set.of("a", "b", "c");
        // To place: the first usable
        assertEquals(new KitRouting.Pick(Kind.TEMPLATE, 0, false), KitRouting.pick(chain, all::contains, all::contains, -1));
        assertEquals(new KitRouting.Pick(Kind.CATEGORY, 1, false), KitRouting.pick(chain, all::contains, Set.of("b", "c")::contains, -1));
        // Stored in the category chest, the template chest has room: up
        assertEquals(new KitRouting.Pick(Kind.TEMPLATE, 0, false), KitRouting.pick(chain, all::contains, all::contains, 1));
        // Stored in the category chest, the template chest is full: home, stays
        assertEquals(new KitRouting.Pick(Kind.CATEGORY, 1, true), KitRouting.pick(chain, all::contains, Set.of("b", "c")::contains, 1));
        // Never down: stored at the top, it stays even when the top is full
        assertEquals(new KitRouting.Pick(Kind.TEMPLATE, 0, true), KitRouting.pick(chain, all::contains, Set.of("b", "c")::contains, 0));
        // Everything full, but the chests exist: FULL (no warning); none exists: MISSING
        assertEquals(Kind.FULL, KitRouting.pick(chain, all::contains, s -> false, -1).kind());
        assertEquals(Kind.MISSING, KitRouting.pick(chain, s -> false, s -> false, -1).kind());
        assertEquals(Kind.NONE, KitRouting.pick(List.of(), s -> true, s -> true, -1).kind());
    }

    @Test
    void theViewRoutesByCategoryTemplateAndFallsThroughAFullChest() {
        Box general = box(1, 64, 1, 0), category = box(2, 64, 2, 0), tpl = box(3, 64, 3, 0);
        Stash stash = stash(general, category, tpl);
        KitTemplate t = template("t-0000aaaa", "Crystal Pack", "3,64,3");
        KitSettings s = settings("1,64,1", Map.of(), Map.of("pvp", "2,64,2"), List.of(t));
        KitRouting.View v = new KitRouting.View(stash, s);
        assertEquals(Kind.CATEGORY, v.route(pvpKit(null)).kind());
        assertEquals(List.of(category), v.destinations(v.route(pvpKit(null))));
        // A complete copy of the template goes to the template chest
        Item copy = shulker("Crystal Pack");
        copy.contents.add(stack("totem_of_undying"));
        Item two = stack("end_crystal");
        two.count = 2;
        copy.contents.add(two);
        assertEquals(Kind.TEMPLATE, v.route(copy).kind());
        assertEquals(List.of(tpl), v.destinations(v.route(copy)));
        // Not a kit: nothing (today's sorting)
        Item stock = shulker(null);
        for (int i = 0; i < 27; i++) stock.contents.add(stack("totem_of_undying"));
        assertEquals(Kind.NOT_KIT, v.route(stock).kind());

        // The category chest is full: the next level
        Stash full = stash(box(1, 64, 1, 0), box(2, 64, 2, 27), box(3, 64, 3, 0));
        KitRouting.View vf = new KitRouting.View(full, s);
        assertEquals(Kind.KITS_CHEST, vf.route(pvpKit(null)).kind());
        // Every level full: FULL, no destination, and not MISSING
        Stash allFull = stash(box(1, 64, 1, 27), box(2, 64, 2, 27), box(3, 64, 3, 27));
        KitRouting.View va = new KitRouting.View(allFull, s);
        assertEquals(Kind.FULL, va.route(pvpKit(null)).kind());
        assertTrue(va.destinations(va.route(pvpKit(null))).isEmpty());
        // Chests that are not in the stash: MISSING
        assertEquals(Kind.MISSING, new KitRouting.View(stash(), s).route(pvpKit(null)).kind());
    }

    @Test
    void everyChestOfTheChainIsKitReserved() {
        Box general = box(1, 64, 1, 0), category = box(2, 64, 2, 0), tpl = box(3, 64, 3, 0), other = box(9, 64, 9, 0);
        KitSettings s = settings("1,64,1", Map.of(), Map.of("pvp", "2,64,2"), List.of(template("t-0000aaaa", "Crystal Pack", "3,64,3")));
        KitRouting.View v = new KitRouting.View(stash(general, category, tpl, other), s);
        assertTrue(v.reserved(general));
        assertTrue(v.reserved(category));
        assertTrue(v.reserved(tpl));
        assertFalse(v.reserved(other));
    }

    // The report

    private static JsonObject json(KitReport.Built b) {
        return JsonParser.parseString(new String(b.json(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void theV2ReportHasTheClassifierTypesOthersTemplatesAndRestock() {
        Box chest = box(2, 64, 2, 0);
        for (int i = 0; i < 3; i++) chest.contents[i] = pvpKit(null);
        chest.contents[3] = shulker("Crystal Pack");
        chest.contents[3].contents.add(stack("totem_of_undying"));
        chest.contents[3].contents.add(stack("end_crystal"));
        chest.contents[4] = shulker("Just stock");
        for (int i = 0; i < 27; i++) chest.contents[4].contents.add(stack("cobblestone"));
        Stash stash = stash(chest);
        KitTemplate t = template("t-0000aaaa", "Crystal Pack", "2,64,2");
        KitSettings s = settings(null, Map.of(), Map.of("pvp", "2,64,2"), List.of(t));

        JsonObject root = json(KitReport.buildV2(stash, false, "Bot", List.of(), KitScore.Scoring.DEFAULT, KitItems.shared(), s));
        JsonObject classifier = root.getAsJsonObject("classifier");
        assertEquals(KitTypes.FINGERPRINT_V1, classifier.get("table").getAsString());
        assertNotNull(root.getAsJsonArray("others"));
        assertEquals("off", root.getAsJsonObject("restock").get("state").getAsString());
        assertTrue(root.getAsJsonObject("restock").get("template").isJsonNull());

        JsonArray templates = root.getAsJsonArray("templates");
        assertEquals(1, templates.size());
        JsonObject tr = templates.get(0).getAsJsonObject();
        assertEquals("t-0000aaaa", tr.get("id").getAsString());
        assertEquals(0, tr.get("complete").getAsInt());
        assertEquals(1, tr.get("incomplete").getAsInt(), "the named box with one of two kinds is an incomplete copy");
        assertEquals("minecraft:end_crystal", tr.getAsJsonArray("missing").get(0).getAsJsonObject().get("key").getAsString());

        // The pvp type counts its three assortments
        int pvp = 0;
        for (JsonElement ke : root.getAsJsonArray("kits")) {
            JsonObject k = ke.getAsJsonObject();
            if (!k.has("types")) continue;
            for (JsonElement te : k.getAsJsonArray("types")) if (te.getAsJsonObject().get("type").getAsString().equals("pvp")) {
                pvp += te.getAsJsonObject().get("count").getAsInt();
                assertEquals("assortment", te.getAsJsonObject().getAsJsonObject("why").get("code").getAsString().replace("template_incomplete", "assortment").replace("named_assortment", "assortment"));
            }
        }
        assertTrue(pvp >= 3, "each kit lists its types, with why");
    }

    @Test
    void theReportHashIgnoresReadTimes() {
        Box chest = box(2, 64, 2, 0);
        chest.contents[0] = pvpKit(null);
        KitSettings s = settings(null, Map.of(), Map.of("pvp", "2,64,2"), List.of());
        String h1 = KitReport.buildV2(stash(chest), false, "Bot", List.of(), KitScore.Scoring.DEFAULT, KitItems.shared(), s).hash();
        chest.readAt = 99999;
        assertEquals(h1, KitReport.buildV2(stash(chest), false, "Bot", List.of(), KitScore.Scoring.DEFAULT, KitItems.shared(), s).hash());
        assertTrue(h1.matches("[0-9a-f]{40}"));
    }

    // The heartbeat

    private static JsonObject heartbeat(ManagerIndex.Kits kits) {
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        return JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, null, kits), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void theHeartbeatCarriesKitsHash2OrAnExplicitNull() {
        JsonObject none = heartbeat(new ManagerIndex.Kits("a".repeat(40), "b".repeat(40)));
        assertTrue(none.has("kitsHash2") && none.get("kitsHash2").isJsonNull(), "no types in the cache: null, so the server sends the settings");
        JsonObject some = heartbeat(new ManagerIndex.Kits("a".repeat(40), "b".repeat(40), "c".repeat(40)));
        assertEquals("c".repeat(40), some.get("kitsHash2").getAsString());
        assertEquals("a".repeat(40), some.get("kitsHash").getAsString(), "the v1 hash is untouched");
    }
}
