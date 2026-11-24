package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grammar of milestone B4's specs (CRAFT-CONTRACT-v1.2.md 3), the grids of its recipes built from the spec and the bot's own extras (15), and the small
 * facts the trip and the sub-plan rest on (which recipes leave a source behind, what a map copy adds). The parse vectors are the server's own
 * ({@code SpecialExtrasTest} of stash-manager-server).
 */
class VariantSpecsTest {
    private static final VariantSpecs.Tables T = SpecialFixtures.TABLES;
    private static final String K = "minecraft:white_banner#a1b2c3d4";

    @Test
    void theServersValidSpecsParseToTheirCanonicalForm() {
        for (String ok : new String[] {"tipped:minecraft:long_poison", "stew:minecraft:night_vision/100", "stew:minecraft:night_vision/1", "stew:minecraft:night_vision/1000000",
            "copy:minecraft:white_banner#a1b2c3d4", "shield:minecraft:white_banner#a1b2c3d4", "copy:minecraft:filled_map#0", "tipped:custom:some_potion"}) {
            VariantSpecs.Spec s = VariantSpecs.parse(ok);
            assertNotNull(s, ok);
            assertEquals(ok, s.canonical(), ok);
        }
    }

    @Test
    void theServersInvalidSpecsAreRefused() {
        for (String bad : new String[] {"tipped:long_poison", "tipped:", "tipped:minecraft:", "tipped:Minecraft:long_poison", "stew:minecraft:night_vision/0", "stew:minecraft:night_vision/0100",
            "stew:minecraft:night_vision/1000001", "stew:minecraft:night_vision/-1", "stew:minecraft:night_vision", "stew:night_vision/100", "stew:minecraft:night_vision/1e2",
            "tipped:minecraft:long_poison ", "copy:minecraft:white_banner", "copy:minecraft:white_banner#", "copy:minecraft:white_banner#XYZ", "copy:white_banner#ab",
            "shield:minecraft:white_banner#a1b2c3d4e5f6a7b8c", "shield:", "copy:", "x".repeat(2049), "tipped:minecraft:" + "a".repeat(2049), "dyed:b02e26", "rocket:1", "", "copy"}) {
            assertNull(VariantSpecs.parse(bad), bad.length() > 60 ? "a very long one" : bad);
        }
        assertNull(VariantSpecs.parse(null));
    }

    @Test
    void theKindAndTheItemMustFit() {
        VariantSpecs.Spec tipped = VariantSpecs.parse("tipped:minecraft:long_poison"), stew = VariantSpecs.parse("stew:minecraft:night_vision/100");
        VariantSpecs.Spec copy = VariantSpecs.parse("copy:" + K), shield = VariantSpecs.parse("shield:" + K);
        // A line or a result is of the variant itself
        assertTrue(VariantSpecs.fitsItem(tipped, "minecraft:tipped_arrow"));
        assertFalse(VariantSpecs.fitsItem(tipped, "minecraft:arrow"));
        assertFalse(VariantSpecs.fitsItem(tipped, "minecraft:lingering_potion"));
        assertTrue(VariantSpecs.fitsItem(stew, "minecraft:suspicious_stew"));
        assertTrue(VariantSpecs.fitsItem(copy, "minecraft:white_banner"));
        assertFalse(VariantSpecs.fitsItem(copy, "minecraft:red_banner"), "another colour");
        assertFalse(VariantSpecs.fitsItem(copy, "minecraft:filled_map"));
        assertTrue(VariantSpecs.fitsItem(VariantSpecs.parse("copy:minecraft:written_book#ab"), "minecraft:written_book"));
        assertFalse(VariantSpecs.fitsItem(VariantSpecs.parse("copy:minecraft:stone#ab"), "minecraft:stone"), "only banners, maps and books are copied");
        assertTrue(VariantSpecs.fitsItem(shield, "minecraft:shield"));
        assertFalse(VariantSpecs.fitsItem(VariantSpecs.parse("shield:minecraft:stone#ab"), "minecraft:shield"), "a shield takes a banner");
        // A take, input or leftover may also be the lingering potion of a tipped spec
        assertTrue(VariantSpecs.fitsEntry(tipped, "minecraft:lingering_potion"));
        assertTrue(VariantSpecs.fitsEntry(tipped, "minecraft:tipped_arrow"));
        assertFalse(VariantSpecs.fitsEntry(tipped, "minecraft:potion"));
        assertFalse(VariantSpecs.fitsEntry(stew, "minecraft:bowl"));
        // The features
        assertEquals("tipped", tipped.feature());
        assertEquals("stew", stew.feature());
        assertEquals("copy", copy.feature());
        assertEquals("copy", shield.feature());
        assertEquals("firework", VariantSpecs.featureOf("rocket:1"));
        assertEquals("firework", VariantSpecs.featureOf("star:small_ball;red;;"));
        assertEquals("copy", VariantSpecs.featureOf("copy:" + K));
        assertNull(VariantSpecs.featureOf("dyed:b02e26"));
        assertNull(VariantSpecs.featureOf(null));
    }

    @Test
    void specsDerivedFromItemsAreInsideTheGrammarOrNone() {
        assertEquals("tipped:minecraft:long_poison", VariantSpecs.tippedSpec("long_poison"));
        assertEquals("tipped:minecraft:long_poison", VariantSpecs.tippedSpec("minecraft:long_poison"));
        assertNull(VariantSpecs.tippedSpec(null));
        assertNull(VariantSpecs.tippedSpec("Long Poison"));
        assertEquals("stew:minecraft:night_vision/100", VariantSpecs.stewSpec("minecraft:night_vision", 100));
        assertEquals("stew:minecraft:night_vision/1000000", VariantSpecs.stewSpec("night_vision", 1_000_000));
        assertNull(VariantSpecs.stewSpec("minecraft:night_vision", 0));
        assertNull(VariantSpecs.stewSpec("minecraft:night_vision", 1_000_001));
        assertNull(VariantSpecs.stewSpec(null, 5));
    }

    @Test
    void aWrittenBookWithACopySpecIsTheOriginalAndEverythingElseIsItsOwnItem() {
        assertEquals("minecraft:written_book@src:" + "minecraft:written_book#ab", VariantSpecs.sourceRef("minecraft:written_book", "copy:minecraft:written_book#ab"));
        assertEquals("minecraft:white_banner@copy:" + K, VariantSpecs.sourceRef("minecraft:white_banner", "copy:" + K));
        assertEquals("minecraft:filled_map@copy:minecraft:filled_map#ab", VariantSpecs.sourceRef("minecraft:filled_map", "copy:minecraft:filled_map#ab"));
        assertEquals("minecraft:lingering_potion@tipped:minecraft:x", VariantSpecs.sourceRef("minecraft:lingering_potion", "tipped:minecraft:x"));
        assertEquals("minecraft:written_book", VariantSpecs.sourceRef("minecraft:written_book", null));
        assertEquals("minecraft:written_book#ab", VariantSpecs.keyOfRef("minecraft:written_book@src:minecraft:written_book#ab"));
        assertEquals(K, VariantSpecs.keyOfRef("minecraft:white_banner@copy:" + K));
        assertNull(VariantSpecs.keyOfRef("minecraft:tipped_arrow@tipped:minecraft:x"));
    }

    @Test
    void theBannerColoursAreInTheGamesOrder() {
        String[] order = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
        for (int i = 0; i < order.length; i++) assertEquals(i, VariantSpecs.bannerColor("minecraft:" + order[i] + "_banner"), order[i]);
        assertEquals(-1, VariantSpecs.bannerColor("minecraft:shield"));
        assertEquals(-1, VariantSpecs.bannerColor("minecraft:white_wool"));
        assertEquals(-1, VariantSpecs.bannerColor("minecraft:chartreuse_banner"));
    }

    // The recipes' facts

    @Test
    void whichRecipesLeaveASourceInTheGridAndWhatAMapCopyAdds() {
        assertEquals(1, VariantSpecs.remainderCells(VariantSpecs.RECIPE_BANNER_COPY));
        assertEquals(1, VariantSpecs.remainderCells(VariantSpecs.RECIPE_BOOK_COPY + "#0123456789"), "a plan id carries a hash after the recipe");
        assertEquals(0, VariantSpecs.remainderCells(VariantSpecs.RECIPE_MAP_COPY));
        assertEquals(0, VariantSpecs.remainderCells(VariantSpecs.RECIPE_SHIELD));
        assertEquals(0, VariantSpecs.remainderCells(VariantSpecs.RECIPE_TIPPED));
        assertEquals(0, VariantSpecs.remainderCells("minecraft:chest"));
        assertEquals(8, VariantSpecs.netCount(VariantSpecs.RECIPE_MAP_COPY + "#abc", 9));
        assertEquals(0, VariantSpecs.netCount(VariantSpecs.RECIPE_MAP_COPY, 0));
        assertEquals(6, VariantSpecs.netCount(VariantSpecs.RECIPE_BOOK_COPY, 6));
        assertEquals(1, VariantSpecs.netCount(VariantSpecs.RECIPE_BANNER_COPY, 1));
        assertTrue(VariantSpecs.isB4("qwerte:special/stew#ab12"));
        assertTrue(VariantSpecs.isB4(VariantSpecs.RECIPE_SHIELD));
        assertFalse(VariantSpecs.isB4(FireworkSpecs.RECIPE_ROCKET), "B3's special recipes are not B4's: their plain cells keep matching what they matched");
        assertFalse(VariantSpecs.isB4("minecraft:chest"));
        assertTrue(VariantSpecs.isCopy(VariantSpecs.RECIPE_MAP_COPY));
        assertFalse(VariantSpecs.isCopy(VariantSpecs.RECIPE_SHIELD));
        assertEquals(8, VariantSpecs.countOf(VariantSpecs.RECIPE_TIPPED, 1));
        assertEquals(9, VariantSpecs.countOf(VariantSpecs.RECIPE_MAP_COPY, 8));
        assertEquals(8, VariantSpecs.countOf(VariantSpecs.RECIPE_BOOK_COPY, 8));
        assertEquals(1, VariantSpecs.countOf(VariantSpecs.RECIPE_BANNER_COPY, 1));
    }

    // The grids built from the spec and the tables

    @Test
    void theExpectedGridOfEachRecipe() {
        // Tipped: the lingering potion with the spec in cell 4, plain arrows in the other eight
        List<List<Order.Cell>> ring = VariantSpecs.expected(VariantSpecs.RECIPE_TIPPED, VariantSpecs.parse("tipped:minecraft:long_poison"), T, 1);
        assertEquals(9, ring.size());
        for (int i = 0; i < 9; i++) {
            assertEquals(1, ring.get(i).size());
            assertEquals(i == 4 ? new Order.Cell("minecraft:lingering_potion", "tipped:minecraft:long_poison") : new Order.Cell("minecraft:arrow", null), ring.get(i).get(0), "cell " + i);
        }
        // Stew: the flowers that give the effect are the alternatives of the fourth cell
        List<List<Order.Cell>> stew = VariantSpecs.expected(VariantSpecs.RECIPE_STEW, VariantSpecs.parse("stew:minecraft:night_vision/100"), T, 1);
        assertEquals(4, stew.size());
        assertEquals(List.of(new Order.Cell("minecraft:poppy", null), new Order.Cell("minecraft:torchflower", null)), stew.get(3));
        assertNull(VariantSpecs.expected(VariantSpecs.RECIPE_STEW, VariantSpecs.parse("stew:minecraft:night_vision/101"), T, 1), "no flower gives it");
        // Shield: a plain shield, then the banner of the key
        List<List<Order.Cell>> shield = VariantSpecs.expected(VariantSpecs.RECIPE_SHIELD, VariantSpecs.parse("shield:" + K), T, 1);
        assertEquals(List.of(List.of(new Order.Cell("minecraft:shield", null)), List.of(new Order.Cell("minecraft:white_banner", "copy:" + K))), shield);
        // Copies: the source with its key, then the blanks
        List<List<Order.Cell>> banner = VariantSpecs.expected(VariantSpecs.RECIPE_BANNER_COPY, VariantSpecs.parse("copy:" + K), T, 1);
        assertEquals(List.of(List.of(new Order.Cell("minecraft:white_banner", "copy:" + K)), List.of(new Order.Cell("minecraft:white_banner", null))), banner);
        assertNull(VariantSpecs.expected(VariantSpecs.RECIPE_BANNER_COPY, VariantSpecs.parse("copy:" + K), T, 2), "one blank to a banner copy");
        List<List<Order.Cell>> map = VariantSpecs.expected(VariantSpecs.RECIPE_MAP_COPY, VariantSpecs.parse("copy:minecraft:filled_map#ab"), T, 8);
        assertEquals(9, map.size());
        assertEquals(List.of(new Order.Cell("minecraft:map", null)), map.get(8));
        assertNull(VariantSpecs.expected(VariantSpecs.RECIPE_MAP_COPY, VariantSpecs.parse("copy:minecraft:filled_map#ab"), T, 9));
        assertNull(VariantSpecs.expected(VariantSpecs.RECIPE_MAP_COPY, VariantSpecs.parse("copy:minecraft:filled_map#ab"), T, 0));
        List<List<Order.Cell>> book = VariantSpecs.expected(VariantSpecs.RECIPE_BOOK_COPY, VariantSpecs.parse("copy:minecraft:written_book#ab"), T, 3);
        assertEquals(4, book.size());
        assertEquals("minecraft:writable_book", book.get(3).get(0).id());
        // The wrong kind of spec for the recipe, or the recipe not listed by the extras
        assertNull(VariantSpecs.expected(VariantSpecs.RECIPE_TIPPED, VariantSpecs.parse("stew:minecraft:night_vision/100"), T, 1));
        assertNull(VariantSpecs.expected(VariantSpecs.RECIPE_MAP_COPY, VariantSpecs.parse("copy:" + K), T, 1), "a banner key on the map recipe");
        assertNull(VariantSpecs.expected(VariantSpecs.RECIPE_TIPPED, VariantSpecs.parse("tipped:minecraft:long_poison"), new VariantSpecs.Tables(Set.of(), T.stew()), 1));
        assertNull(VariantSpecs.expected("qwerte:special/dye", VariantSpecs.parse("tipped:minecraft:long_poison"), T, 1));
    }

    @Test
    void aCopyFamilyMakesTheSameRecipeIdsTheBlocksStepsHave() {
        Order.Craft map = SpecialFixtures.parse(SpecialFixtures.mapCopy());
        VariantSpecs.CopyFamily f = VariantSpecs.families(map).get("minecraft:filled_map@copy:" + SpecialFixtures.K_MAP);
        assertNotNull(f);
        assertEquals(VariantSpecs.CopyKind.MAP, f.kind());
        assertEquals(f.product(), f.source(), "a map's source is a map of the key, used up");
        assertFalse(f.sourceStays());
        assertEquals("minecraft:map", f.blank());
        // The crafts the bot makes by itself are the steps' own recipes when the blank counts agree
        assertEquals(FireworkSpecs.planId(map.steps().get(0)), f.recipeFor(8).id());
        assertEquals(FireworkSpecs.planId(map.steps().get(1)), f.recipeFor(3).id());
        assertEquals(9, f.recipeFor(8).count());
        assertEquals(4, f.recipeFor(3).count());
        Order.Craft book = SpecialFixtures.parse(SpecialFixtures.bookCopy());
        VariantSpecs.CopyFamily b = VariantSpecs.families(book).get("minecraft:written_book@copy:" + SpecialFixtures.K_BOOK);
        assertEquals("minecraft:written_book@src:" + SpecialFixtures.K_BOOK, b.source());
        assertTrue(b.sourceStays());
        assertEquals(FireworkSpecs.planId(book.steps().get(0)), b.recipeFor(6).id());
        Order.Craft banner = SpecialFixtures.parse(SpecialFixtures.bannerCopy());
        VariantSpecs.CopyFamily n = VariantSpecs.families(banner).get("minecraft:white_banner@copy:" + SpecialFixtures.K_BANNER);
        assertEquals(1, n.batch());
        assertEquals(FireworkSpecs.planId(banner.steps().get(0)), n.recipeFor(1).id());
        assertTrue(VariantSpecs.families(SpecialFixtures.parse(SpecialFixtures.tipped())).isEmpty());
        assertTrue(VariantSpecs.families(SpecialFixtures.parse(SpecialFixtures.shield())).isEmpty());
    }

    @Test
    void descriptionsNeverCarryAKey() {
        assertEquals("long poison", VariantSpecs.describe("tipped:minecraft:long_poison"));
        assertEquals("night vision 5 s", VariantSpecs.describe("stew:minecraft:night_vision/100"));
        assertEquals("saturation 0.35 s", VariantSpecs.describe("stew:minecraft:saturation/7"));
        assertEquals("a copy", VariantSpecs.describe("copy:" + K));
        assertEquals("with a banner", VariantSpecs.describe("shield:" + K));
        assertEquals("the original", VariantSpecs.describe("src:" + K));
        assertEquals("Written Book (the original)", Recipes.pretty("minecraft:written_book@src:minecraft:written_book#ab"));
        assertEquals("Tipped Arrow (long poison)", Recipes.pretty("minecraft:tipped_arrow@tipped:minecraft:long_poison"));
        assertEquals("Firework Star (star, small ball, red)", Recipes.pretty("minecraft:firework_star@star:small_ball;red;;"));
    }
}
