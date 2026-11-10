package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import com.porter.qwertestash.stash.KitRouting.Kind;
import com.porter.qwertestash.stash.Stash.Box;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where a kit shulker goes: the decision, container ids, and which containers are kit-reserved. */
class KitRoutingTest {
    private static final String HASH = "0".repeat(40);

    private static KitSettings settings(String chest, Map<String, String> rules) {
        return new KitSettings(1, HASH, chest, rules, KitScore.Scoring.DEFAULT);
    }

    private static Box box(int x, int y, int z, boolean read) {
        BlockPos p = new BlockPos(x, y, z);
        Box box = new Box(p, new ArrayList<>(List.of(p)));
        if (read) {
            box.contents = new Item[27];
            box.readAt = 1;
        }
        return box;
    }

    private static Box doubleBox(BlockPos first, BlockPos second) {
        Box box = new Box(first, new ArrayList<>(List.of(first, second)));
        box.contents = new Item[54];
        box.readAt = 1;
        return box;
    }

    private static Item stack(String id) {
        Item it = new Item();
        it.id = id;
        it.count = 1;
        it.maxStack = 64;
        return it;
    }

    /** A shulker with the given custom name (null: unnamed) and 27 stacks: 22 of three pvp items and 5 of stone, a pvp assortment by the type rules. */
    private static Item kit(String name) {
        Item it = shulker(name);
        for (int i = 0; i < 27; i++) it.contents.add(stack(i % 5 == 4 ? "stone" : new String[]{"totem_of_undying", "end_crystal", "obsidian", "end_crystal"}[i % 5]));
        return it;
    }

    /** A shulker that is not a kit: a name alone is only 2 points. */
    private static Item shulker(String name) {
        Item it = new Item();
        it.id = "red_shulker_box";
        it.count = 1;
        it.maxStack = 1;
        it.customName = name != null;
        it.customText = name;
        return it;
    }

    // decide

    @Test
    void aRuleBeatsTheKitsChest() {
        KitSettings s = settings("1,64,1", Map.of("pvp kit", "2,64,2"));
        KitRouting.Decision d = KitRouting.decide("pvp kit", true, s, id -> true);
        assertEquals(Kind.RULE, d.kind());
        assertEquals("2,64,2", d.container());
        assertEquals("pvp kit", d.key());
        assertTrue(d.routed());
    }

    @Test
    void withoutARuleTheKitsChestTakesIt() {
        KitSettings s = settings("1,64,1", Map.of("pvp kit", "2,64,2"));
        KitRouting.Decision d = KitRouting.decide("base kit", true, s, id -> true);
        assertEquals(Kind.KITS_CHEST, d.kind());
        assertEquals("1,64,1", d.container());
    }

    @Test
    void anUnnamedKitOnlyEverGoesToTheKitsChest() {
        KitSettings s = settings("1,64,1", Map.of("pvp kit", "2,64,2"));
        KitRouting.Decision d = KitRouting.decide(null, true, s, id -> true);
        assertEquals(Kind.KITS_CHEST, d.kind());
        assertNull(d.key());
        assertEquals("1,64,1", d.container());
        assertEquals(Kind.NONE, KitRouting.decide(null, true, settings(null, Map.of("pvp kit", "2,64,2")), id -> true).kind(), "no kits chest: today's sorting");
        assertEquals(Kind.MISSING, KitRouting.decide(null, true, s, id -> false).kind());
    }

    @Test
    void aRuleKeyNeedsNoKitWordButOnlyAppliesToAKit() {
        KitSettings s = settings(null, Map.of("mixed concrete", "2,64,2"));
        assertEquals(Kind.RULE, KitRouting.decide("mixed concrete", true, s, id -> true).kind());
        assertEquals(Kind.NOT_KIT, KitRouting.decide("mixed concrete", false, s, id -> true).kind(), "a box that does not score as a kit has no rule");
    }

    @Test
    void withNeitherItIsTodaysSorting() {
        KitRouting.Decision d = KitRouting.decide("base kit", true, settings(null, Map.of("pvp kit", "2,64,2")), id -> true);
        assertEquals(Kind.NONE, d.kind());
        assertFalse(d.routed());
        assertEquals(Kind.NONE, KitRouting.decide("base kit", true, KitSettings.EMPTY, id -> true).kind());
    }

    @Test
    void aChosenContainerThatCannotTakeKitsGivesNoDestinationAndDoesNotFallBack() {
        KitSettings s = settings("1,64,1", Map.of("pvp kit", "2,64,2"));
        KitRouting.Decision rule = KitRouting.decide("pvp kit", true, s, id -> id.equals("1,64,1"));
        assertEquals(Kind.MISSING, rule.kind(), "the rule's container is not pickable: the kits chest is not tried instead");
        assertTrue(rule.routed());
        KitRouting.Decision chest = KitRouting.decide("base kit", true, s, id -> false);
        assertEquals(Kind.MISSING, chest.kind());
    }

    // ids

    @Test
    void anIdIsTheSmallestPart() {
        BlockPos a = new BlockPos(10, 64, 5), b = new BlockPos(10, 64, 4), c = new BlockPos(9, 70, 99);
        assertEquals("10,64,4", KitRouting.canonicalId(doubleBox(a, b)));
        assertEquals("9,70,99", KitRouting.canonicalId(doubleBox(a, c)), "x first");
        assertEquals("10,64,5", KitRouting.canonicalId(doubleBox(a, new BlockPos(10, 65, 0))), "then y");
        assertEquals("10,64,4", KitRouting.canonicalId(doubleBox(b, a)), "whichever half was found first");
        assertEquals("-3,-60,-7", KitRouting.canonicalId(box(-3, -60, -7, true)));
    }

    @Test
    void anIdStillResolvesWhenAChestBecomesADouble() {
        Stash stash = new Stash("s", "srv");
        Box single = box(10, 64, 5, true);
        stash.boxes.add(single);
        KitRouting.View before = new KitRouting.View(stash, settings("10,64,5", Map.of()));
        assertSame(single, before.box("10,64,5"));

        // The other half is placed: the same box now has two parts, and the smaller id is the other one
        single.parts.add(new BlockPos(10, 64, 4));
        KitRouting.View after = new KitRouting.View(stash, settings("10,64,5", Map.of()));
        assertSame(single, after.box("10,64,5"), "boxAt matches any part");
        assertSame(single, after.box("10,64,4"));
        assertEquals("10,64,4", KitRouting.canonicalId(single));
    }

    // View

    @Test
    void theKitsChestAndEveryRuleTargetAreReserved() {
        Stash stash = new Stash("s", "srv");
        Box chest = box(1, 64, 1, true), ruleBox = box(2, 64, 2, true), other = box(3, 64, 3, true);
        stash.boxes.addAll(List.of(chest, ruleBox, other));
        KitRouting.View v = new KitRouting.View(stash, settings("1,64,1", Map.of("pvp kit", "2,64,2", "gone kit", "9,64,9")));
        assertTrue(v.reserved(chest));
        assertTrue(v.reserved(ruleBox));
        assertFalse(v.reserved(other));
        assertNull(v.box("9,64,9"), "a target the stash does not have is nothing, and reserves nothing");
    }

    @Test
    void aRoutedKitHasTheTargetAsItsOnlyDestination() {
        Stash stash = new Stash("s", "srv");
        Box chest = box(1, 64, 1, true), ruleBox = box(2, 64, 2, true);
        stash.boxes.addAll(List.of(chest, ruleBox));
        KitRouting.View v = new KitRouting.View(stash, settings("1,64,1", Map.of("pvp kit", "2,64,2")));

        KitRouting.Route rule = v.route(kit("PvP Kit"));
        assertEquals(Kind.RULE, rule.kind());
        assertEquals(List.of(ruleBox), v.destinations(rule));

        KitRouting.Route fallback = v.route(kit("Other Kit"));
        assertEquals(Kind.KITS_CHEST, fallback.kind());
        assertEquals(List.of(chest), v.destinations(fallback));

        KitRouting.Route unnamed = v.route(kit(null));
        assertEquals(Kind.KITS_CHEST, unnamed.kind());
        assertNull(unnamed.decision().key());
        assertEquals(List.of(chest), v.destinations(unnamed));
    }

    @Test
    void aNamedKitWithAnyNameCanHaveARule() {
        Stash stash = new Stash("s", "srv");
        Box ruleBox = box(2, 64, 2, true);
        stash.boxes.add(ruleBox);
        KitRouting.View v = new KitRouting.View(stash, settings(null, Map.of("crystal pvp", "2,64,2")));
        assertEquals(List.of(ruleBox), v.destinations(v.route(kit("§bCrystal   PvP"))), "the name needs no word kit; the key is the normalised name");
        assertEquals(Kind.NOT_KIT, v.route(shulker("Crystal PvP")).kind(), "named, but not a kit by score: left to today's sorting");
    }

    @Test
    void aTargetThatIsNotPickableGivesNoDestination() {
        Stash stash = new Stash("s", "srv");
        Box unread = box(1, 64, 1, false), dropOff = box(2, 64, 2, true), excluded = box(3, 64, 3, true), delivery = box(4, 64, 4, true);
        dropOff.dropOff = true;
        excluded.excluded = true;
        delivery.delivery = true;
        stash.boxes.addAll(List.of(unread, dropOff, excluded, delivery));

        for (String id : List.of("1,64,1", "2,64,2", "3,64,3", "4,64,4", "5,64,5")) {
            KitRouting.View v = new KitRouting.View(stash, settings(id, Map.of()));
            KitRouting.Route route = v.route(kit("Base Kit"));
            assertEquals(Kind.MISSING, route.kind(), id);
            assertEquals(List.of(), v.destinations(route), id);
        }
        // They are still reserved where they exist: nothing else is put in them
        assertTrue(new KitRouting.View(stash, settings("2,64,2", Map.of())).reserved(dropOff));
    }

    @Test
    void anUnreachableTargetIsMissingOnlyWhileItIs() {
        Stash stash = new Stash("s", "srv");
        Box chest = box(1, 64, 1, true);
        stash.boxes.add(chest);
        KitRouting.View v = new KitRouting.View(stash, settings("1,64,1", Map.of()));
        Item k = kit("Base Kit");
        assertEquals(Kind.KITS_CHEST, v.route(k).kind());
        chest.unreachable = true;
        assertEquals(Kind.MISSING, v.route(k).kind(), "pickable is looked at every time, the score is not");
        chest.unreachable = false;
        assertEquals(Kind.KITS_CHEST, v.route(k).kind());
    }

    @Test
    void thingsThatDoNotScoreAsKitsAreLeftAlone() {
        Stash stash = new Stash("s", "srv");
        stash.boxes.add(box(1, 64, 1, true));
        KitRouting.View v = new KitRouting.View(stash, settings("1,64,1", Map.of()));

        Item cobble = stack("cobblestone");
        cobble.count = 64;
        cobble.customName = true;
        cobble.customText = "Kit";
        assertEquals(Kind.NOT_KIT, v.route(cobble).kind(), "a stack named kit that is not a shulker");

        assertEquals(Kind.NOT_KIT, v.route(shulker(null)).kind(), "an empty unnamed shulker");
        assertEquals(Kind.NOT_KIT, v.route(shulker("Kit")).kind(), "a name alone is 2 points");

        Item diorite = shulker(null);
        for (int i = 0; i < 27; i++) diorite.contents.add(stack("diorite"));
        assertEquals(Kind.NOT_KIT, v.route(diorite).kind(), "27 diorite is 1 point");

        Item totems = shulker(null);
        for (int i = 0; i < 10; i++) totems.contents.add(stack("totem_of_undying"));
        assertEquals(Kind.NOT_KIT, v.route(totems).kind(), "10 totems: one item type, not full");
        Item full = shulker(null);
        for (int i = 0; i < 27; i++) full.contents.add(stack("totem_of_undying"));
        assertEquals(Kind.NOT_KIT, v.route(full).kind(), "27 totems: one kind of item is stock, never a kit");
    }

    @Test
    void theStashsV1ScoringNoLongerDecidesWhatAKitIs() {
        Stash stash = new Stash("s", "srv");
        stash.boxes.add(box(1, 64, 1, true));
        KitSettings strict = new KitSettings(1, HASH, "1,64,1", Map.of(), new KitScore.Scoring(6, 2, 3, 1, 4));
        assertEquals(Kind.KITS_CHEST, new KitRouting.View(stash, strict).route(kit(null)).kind(), "the stash's v1 scoring no longer decides: the type rules do");
        assertEquals(Kind.KITS_CHEST, new KitRouting.View(stash, strict).route(kit("Kit")).kind(), "7 points with the name");
    }

    @Test
    void aKitIsKeptWholeOnlyWhenTheSettingsHaveASayOverIt() {
        Stash stash = new Stash("s", "srv");
        Box chest = box(1, 64, 1, true), ruleBox = box(2, 64, 2, true);
        stash.boxes.addAll(List.of(chest, ruleBox));
        Item named = kit("Base Kit"), unnamed = kit(null), plain = shulker(null), notShulker = stack("chest");

        // No cached settings, or settings with no chest and no rule: today's sorting, a box that scores as a kit is repacked as in 1.13.0
        assertFalse(KitRouting.keptWhole(null, named));
        assertFalse(KitRouting.keptWhole(null, unnamed));
        KitRouting.View none = new KitRouting.View(stash, settings(null, Map.of()));
        assertFalse(KitRouting.keptWhole(none, unnamed));
        assertFalse(KitRouting.keptWhole(none, named));
        assertFalse(KitRouting.keptWhole(new KitRouting.View(stash, settings(null, Map.of("other kit", "2,64,2"))), named), "a rule for another name: NONE");

        // The kits chest applies to every kit, named or not
        KitRouting.View withChest = new KitRouting.View(stash, settings("1,64,1", Map.of()));
        assertTrue(KitRouting.keptWhole(withChest, unnamed));
        assertTrue(KitRouting.keptWhole(withChest, named));
        assertFalse(KitRouting.keptWhole(withChest, plain), "not a kit by score");
        assertFalse(KitRouting.keptWhole(withChest, notShulker));
        assertFalse(KitRouting.keptWhole(withChest, null));

        // A rule applies to its named kit only; an unnamed kit has no rule and, with no kits chest, is NONE
        KitRouting.View ruleOnly = new KitRouting.View(stash, settings(null, Map.of("base kit", "2,64,2")));
        assertTrue(KitRouting.keptWhole(ruleOnly, named));
        assertFalse(KitRouting.keptWhole(ruleOnly, unnamed));

        // A chosen chest that is missing or cannot take kits keeps the kit whole too
        KitRouting.View missing = new KitRouting.View(stash, settings("9,64,9", Map.of()));
        assertEquals(Kind.MISSING, missing.route(unnamed).kind());
        assertTrue(KitRouting.keptWhole(missing, unnamed));

        // The stash's own threshold decides what is a kit at all
        KitSettings strict = new KitSettings(1, HASH, "1,64,1", Map.of(), new KitScore.Scoring(6, 2, 3, 1, 4));
        assertTrue(KitRouting.keptWhole(new KitRouting.View(stash, strict), kit(null)), "v1 scoring is ignored by the type rules");
        assertTrue(KitRouting.keptWhole(new KitRouting.View(stash, strict), kit("Kit")));
    }

    @Test
    void emptySettingsRouteNothingAndReserveNothing() {
        Stash stash = new Stash("s", "srv");
        Box b = box(1, 64, 1, true);
        stash.boxes.add(b);
        KitRouting.View v = new KitRouting.View(stash, KitSettings.EMPTY);
        assertTrue(v.isEmpty());
        assertEquals(Kind.NOT_KIT, v.route(kit("Kit")).kind());
        assertFalse(v.reserved(b));
    }
}
