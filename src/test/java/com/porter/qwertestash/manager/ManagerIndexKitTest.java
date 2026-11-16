package com.porter.qwertestash.manager;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.orders.SourceRules;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.KitTemplate;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.22.0 (CRAFT-KITVIEW A1): the index names complete kits ({@code shulkers[].completeKit}, root {@code kitRule}) only while kit sorting is on. */
class ManagerIndexKitTest {
    private static final String ARROW = "minecraft:arrow", PEARL = "minecraft:ender_pearl", TEMPLATE = "t-abcdefgh";
    private static final List<KitTemplate> TEMPLATES = List.of(new KitTemplate(TEMPLATE, "Arrows", "pvp", null, null, 0, List.of(
        new KitTemplate.Slot(ARROW, ARROW, 64), new KitTemplate.Slot(PEARL, PEARL, 16))));

    private static Item stack(String key, int count, int max) {
        Item it = new Item();
        it.id = key.substring("minecraft:".length());
        it.name = Item.prettyName(it.id);
        it.count = count;
        it.maxStack = max;
        it.key = key;
        it.variant = key;
        return it;
    }

    private static Item shulker(Item... inside) {
        Item s = stack("minecraft:red_shulker_box", 1, 1);
        s.contents = new ArrayList<>();
        for (int i = 0; i < inside.length; i++) {
            inside[i].slot = i;
            s.contents.add(inside[i]);
        }
        return s;
    }

    private static Item completeKit() {
        return shulker(stack(ARROW, 64, 64), stack(PEARL, 16, 16));
    }

    private static Item incompleteKit() {
        return shulker(stack(ARROW, 64, 64), stack(PEARL, 8, 16));
    }

    private static Item plain() {
        return shulker(stack("minecraft:diamond", 64, 64));
    }

    private static Stash stash(Item... items) {
        Stash s = new Stash("s", "2b2t.org");
        Stash.Box box = new Stash.Box(new BlockPos(1, 2, 3), new ArrayList<>(List.of(new BlockPos(1, 2, 3))));
        box.contents = new Item[27];
        for (int i = 0; i < items.length; i++) box.contents[i] = items[i];
        s.boxes.add(box);
        return s;
    }

    private static final Function<Item, String> RULE = sh -> SourceRules.completeTemplateId(TEMPLATES, sh);

    private static ManagerIndex.Built build(Stash s, Function<Item, String> kit) {
        return ManagerIndex.build(s, false, "Bot", "1.21.4", "overworld", id -> null, kit);
    }

    private static JsonObject json(ManagerIndex.Built b) {
        return JsonParser.parseString(new String(b.json(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject groupOf(JsonObject root, Item shulker) {
        String key = ManagerIndex.shulkerKey(shulker);
        JsonArray all = root.getAsJsonArray("shulkers");
        for (var e : all) if (e.getAsJsonObject().get("key").getAsString().equals(key)) return e.getAsJsonObject();
        throw new AssertionError("no group " + key);
    }

    @Test
    void aCompleteKitCarriesItsTemplateAndTheRootSaysTheRuleIsLive() {
        Item kit = completeKit();
        JsonObject root = json(build(stash(kit, plain(), incompleteKit()), RULE));
        JsonObject g = groupOf(root, kit);
        assertTrue(g.get("completeKit").isJsonPrimitive() && g.get("completeKit").getAsJsonPrimitive().isString());
        assertEquals(TEMPLATE, g.get("completeKit").getAsString());
        JsonObject rule = root.getAsJsonObject("kitRule");
        assertEquals(1, rule.get("v").getAsInt());
        assertTrue(rule.get("completeKitsKept").getAsBoolean());
        assertEquals(2, rule.size());
    }

    @Test
    void aPlainOrIncompleteShulkerHasNoCompleteKit() {
        Item plain = plain(), incomplete = incompleteKit();
        JsonObject root = json(build(stash(completeKit(), plain, incomplete), RULE));
        assertFalse(groupOf(root, plain).has("completeKit"));
        assertFalse(groupOf(root, incomplete).has("completeKit"));
        // The rule is live even though these two are not kits
        assertTrue(root.has("kitRule"));
    }

    @Test
    void anIdThatIsNotATemplateIdIsNotSent() {
        Item kit = completeKit();
        JsonObject root = json(build(stash(kit), sh -> "T-NOPE"));
        assertFalse(groupOf(root, kit).has("completeKit"));
    }

    @Test
    void kitSortingOffMeansNoKitFieldsAtAll() {
        Item kit = completeKit();
        Stash s = stash(kit, plain());
        ManagerIndex.Built off = build(s, null);
        JsonObject root = json(off);
        assertFalse(root.has("kitRule"));
        for (var e : root.getAsJsonArray("shulkers")) assertFalse(e.getAsJsonObject().has("completeKit"));
        // The old overload is that same index byte for byte
        ManagerIndex.Built old = ManagerIndex.build(s, false, "Bot", "1.21.4", "overworld", id -> null);
        assertEquals(off.hash(), old.hash());
        assertEquals(root.getAsJsonArray("shulkers"), json(old).getAsJsonArray("shulkers"));
        assertNull(root.get("kitRule"));
    }

    @Test
    void aVerdictThatFlipsChangesTheIndexHash() {
        Stash s = stash(completeKit());
        String on = build(s, RULE).hash();
        String off = build(s, sh -> null).hash();
        assertNotEquals(on, off);
        // Rule live with no kit found hashes like the old index (the rule itself is not hashed)
        assertEquals(build(s, null).hash(), off);
    }

    @Test
    void theTemplateIdIsTheOneTheSorterTestUses() {
        assertEquals(TEMPLATE, SourceRules.completeTemplateId(TEMPLATES, completeKit()));
        assertNull(SourceRules.completeTemplateId(TEMPLATES, incompleteKit()));
        assertNull(SourceRules.completeTemplateId(TEMPLATES, plain()));
        assertNull(SourceRules.completeTemplateId(List.of(), completeKit()));
        assertTrue(SourceRules.isCompleteKit(TEMPLATES, completeKit()));
    }

    @Test
    void noSettingsOrNoLookupMeansNeitherFieldIsSent() {
        var settings = new com.porter.qwertestash.stash.KitSettings(1, "h", null, java.util.Map.of(), null);
        var withTypes = new com.porter.qwertestash.stash.KitSettings(1, "h", null, java.util.Map.of(), null,
            new com.porter.qwertestash.stash.KitSettings.Types("h", List.of(), java.util.Map.of(), java.util.Map.of(), TEMPLATES));
        assertEquals(RULE, ManagerIndex.kitLookup(true, withTypes, RULE), "the verdict can be made");
        assertNull(ManagerIndex.kitLookup(true, settings, RULE), "settings without templates");
        assertNull(ManagerIndex.kitLookup(true, null, RULE), "no kit settings for the stash");
        assertNull(ManagerIndex.kitLookup(true, settings, null), "no sorter / no lookup");
        assertNull(ManagerIndex.kitLookup(false, settings, RULE), "kit sorting off");
        Stash s = stash(completeKit());
        JsonObject root = json(build(s, ManagerIndex.kitLookup(true, null, RULE)));
        assertFalse(root.has("kitRule"));
        for (var e : root.getAsJsonArray("shulkers")) assertFalse(e.getAsJsonObject().has("completeKit"));
    }
}
