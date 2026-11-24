package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.porter.qwertestash.orders.SpecialFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Craft blocks with tipped arrows, suspicious stew, copies and shields on the bot (CRAFT-CONTRACT-v1.2.md, milestone B4): the block as the real server writes it
 * is read and passes the bot's check; each special grid is built again from the spec and the bot's own extras and compared cell by cell; the remainder of a copy
 * is read and judged; the inputs of a block with a copy are in hand when each craft is made (a map copy takes its source from an earlier craft's result);
 * the features are gated by the bot's {@code can}, its tables and the block's {@code needs}; blocks of B1 to B3 are judged as before.
 */
class SpecialBlockTest {
    private static JsonObject step(JsonObject b, int i) {
        return b.getAsJsonArray("steps").get(i).getAsJsonObject();
    }

    private static JsonObject changed(Supplier<JsonObject> block, Consumer<JsonObject> change) {
        JsonObject b = block.get();
        change.accept(b);
        return b;
    }

    private static void setCell(JsonObject b, int step, int cell, String id, String spec) {
        JsonArray g = step(b, step).getAsJsonArray("grid");
        if (id == null) {
            g.set(cell, JsonNull.INSTANCE);
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        if (spec != null) o.addProperty("spec", spec);
        g.set(cell, o);
    }

    // The server's blocks

    @Test
    void everyBlockTheServerWritesIsReadAndPassesTheBotsCheck() {
        for (var e : java.util.Map.<String, JsonObject>of("tipped", tipped(), "stew", stew(), "banner copy", bannerCopy(), "map copy", mapCopy(), "map copy in one step", mapCopyInOneStep(),
            "book copy", bookCopy(), "shield", shield()).entrySet()) {
            Order.Craft c = parse(e.getValue());
            assertNull(c.reserved(), e.getKey());
            assertTrue(c.usesSpecs(), e.getKey());
            assertNull(check(c, CAN), e.getKey());
        }
    }

    @Test
    void theRemainderOfACopyIsReadAndTheFeaturesAreTheKindsOfTheSpecs() {
        Order.Craft banner = parse(bannerCopy());
        Order.CraftStep s = banner.steps().get(0);
        assertEquals(1, s.remainder().size());
        assertEquals(BANNER, s.remainder().get(0).id());
        assertEquals(K_BANNER, s.remainder().get(0).key());
        assertEquals(2, s.remainder().get(0).count());
        assertEquals(Set.of("copy"), banner.features());
        assertEquals(Set.of("tipped"), parse(tipped()).features());
        assertEquals(Set.of("stew"), parse(stew()).features());
        assertTrue(banner.hasCopies());
        assertFalse(parse(tipped()).hasCopies());
        assertTrue(parse(shield()).features().contains("copy"));
        // A written book's original is a different stack from its copies: the planner's name for it is its source
        Order.Craft book = parse(bookCopy());
        assertEquals("minecraft:written_book@src:" + K_BOOK, book.take().get(0).stockRef());
        assertEquals("minecraft:written_book@copy:" + K_BOOK, book.lines().get(0).ref());
        assertEquals("minecraft:written_book@src:" + K_BOOK, book.steps().get(0).grid().get(0).ref());
        assertEquals("minecraft:written_book@copy:" + K_BOOK, book.steps().get(0).resultRef());
        // A banner's copy is the banner of the key itself
        assertEquals("minecraft:white_banner@copy:" + K_BANNER, banner.take().get(0).stockRef());
        assertEquals("minecraft:white_banner@copy:" + K_BANNER, banner.steps().get(0).resultRef());
    }

    // The gates

    @Test
    void aBlockOfAKindNeedsThatFeatureInTheBotsCanItsTablesTheBlocksNeedsAndTheBotsExtras() {
        for (var c : List.<String[]>of(new String[] {"tipped"}, new String[] {"stew"}, new String[] {"copy"})) {
            Supplier<JsonObject> block = c[0].equals("tipped") ? SpecialFixtures::tipped : c[0].equals("stew") ? SpecialFixtures::stew : SpecialFixtures::bannerCopy;
            assertNull(check(parse(block.get()), CAN), c[0]);
            // The feature is not in can
            java.util.Set<String> without = new java.util.HashSet<>(CAN);
            without.remove(c[0]);
            assertNotNull(check(parse(block.get()), without), c[0] + ": not in can");
            // Another B4 feature is not enough, and a firework bot that has none of them neither
            assertNotNull(check(parse(block.get()), Set.of("firework")), c[0] + ": a firework-only bot");
            // The block does not say it needs the feature
            assertNotNull(check(changed(block, b -> b.add("needs", new JsonArray()))), c[0] + ": needs does not say it");
            // The block names no extras / other extras
            assertNotNull(check(parse(changed(block, b -> b.add("extras", JsonNull.INSTANCE))), CAN), c[0] + ": no extras named");
            assertNotNull(check(parse(changed(block, b -> b.addProperty("extras", "0".repeat(40)))), CAN), c[0] + ": other extras");
            // The bot has no tables for it
            assertNotNull(CraftOrder.check(parse(block.get()), R, 2048, SERVER_HASH, CAN, null, 0, FIREWORKS, null), c[0] + ": no B4 tables");
        }
    }

    @Test
    void theExtrasOfTheBotMustListTheRecipe() {
        // The same block against extras that don't list tipped: the step is refused even though can says tipped
        VariantSpecs.Tables noTipped = new VariantSpecs.Tables(Set.of("stew", "banner_copy", "map_copy", "book_copy", "shield"), TABLES.stew());
        assertNotNull(CraftOrder.check(parse(tipped()), R, 2048, SERVER_HASH, CAN, null, 0, FIREWORKS, noTipped));
        VariantSpecs.Tables noShield = new VariantSpecs.Tables(Set.of("tipped", "stew", "banner_copy", "map_copy", "book_copy"), TABLES.stew());
        assertNotNull(CraftOrder.check(parse(shield()), R, 2048, SERVER_HASH, CAN, null, 0, FIREWORKS, noShield));
        assertNull(CraftOrder.check(parse(bannerCopy()), R, 2048, SERVER_HASH, CAN, null, 0, FIREWORKS, noShield));
    }

    // The grid, built again from the spec and the bot's own extras

    @Test
    void theTippedRingHasThePotionInCellFourAndEightArrowsAround() {
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> {
            setCell(b, 0, 4, "minecraft:arrow", null);
            setCell(b, 0, 3, "minecraft:lingering_potion", POISON);
        })));
        // Another potion than the spec's
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> setCell(b, 0, 4, "minecraft:lingering_potion", "tipped:minecraft:strength"))));
        // A potion with no spec, a splash potion, a ninth cell filled or one short
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> setCell(b, 0, 4, "minecraft:lingering_potion", null))));
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> setCell(b, 0, 4, "minecraft:splash_potion", POISON))));
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> setCell(b, 0, 8, null, null))));
        // The count a craft makes, the spec on the step and the item it makes
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> step(b, 0).addProperty("count", 6))));
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> step(b, 0).addProperty("spec", "tipped:minecraft:strength"))));
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> step(b, 0).addProperty("result", "minecraft:arrow"))));
        // Inputs that are not the grid times the fills
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> step(b, 0).getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("count", 7))));
        // No remainder is left by a tipped arrow
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> step(b, 0).add("remainder",
            JsonParser.parseString("[ { \"id\": \"minecraft:glass_bottle\", \"count\": 1 } ]")))));
    }

    @Test
    void aStewIsABowlBothMushroomsAndAFlowerThatGivesTheEffect() {
        // The flower: a poppy and a torchflower give night vision 5 s; a dandelion saturation
        assertNull(check(changed(SpecialFixtures::stew, b -> {
            setCell(b, 0, 3, "minecraft:torchflower", null);
            step(b, 0).getAsJsonArray("inputs").set(3, JsonParser.parseString(q("minecraft:torchflower", 2)));
            b.getAsJsonArray("take").get(4).getAsJsonObject().addProperty("id", "minecraft:torchflower");
        })));
        assertNotNull(check(changed(SpecialFixtures::stew, b -> {
            setCell(b, 0, 3, "minecraft:dandelion", null);
            step(b, 0).getAsJsonArray("inputs").set(3, JsonParser.parseString(q("minecraft:dandelion", 2)));
            b.getAsJsonArray("take").get(4).getAsJsonObject().addProperty("id", "minecraft:dandelion");
        })));
        // A duration no flower gives
        assertNotNull(check(changed(SpecialFixtures::stew, b -> step(b, 0).addProperty("spec", "stew:minecraft:night_vision/101"))));
        // The cells in another order, a missing mushroom
        assertNotNull(check(changed(SpecialFixtures::stew, b -> {
            setCell(b, 0, 1, "minecraft:red_mushroom", null);
            setCell(b, 0, 2, "minecraft:brown_mushroom", null);
        })));
        assertNotNull(check(changed(SpecialFixtures::stew, b -> setCell(b, 0, 2, null, null))));
        // The result is the stew, and a craft makes one
        assertNotNull(check(changed(SpecialFixtures::stew, b -> step(b, 0).addProperty("count", 2))));
    }

    @Test
    void aBannerCopyHasTheSourceThenABlankOfTheSameColourAndHandsTheSourceBack() {
        assertNotNull(check(changed(SpecialFixtures::bannerCopy, b -> setCell(b, 0, 1, "minecraft:red_banner", null))), "a blank of another colour");
        assertNotNull(check(changed(SpecialFixtures::bannerCopy, b -> setCell(b, 0, 1, BANNER, "copy:" + K_BANNER))), "the blank is a copy of the key too");
        assertNotNull(check(changed(SpecialFixtures::bannerCopy, b -> setCell(b, 0, 0, BANNER, null))), "no source");
        assertNotNull(check(changed(SpecialFixtures::bannerCopy, b -> setCell(b, 0, 0, BANNER, "copy:minecraft:white_banner#ffff"))), "a source of another key than the spec's");
        assertNotNull(check(changed(SpecialFixtures::bannerCopy, b -> setCell(b, 0, 2, BANNER, null))), "two blanks to one copy");
        assertNotNull(check(changed(SpecialFixtures::bannerCopy, b -> step(b, 0).remove("remainder"))), "no remainder: the source is not given back");
        assertNotNull(check(changed(SpecialFixtures::bannerCopy, b -> step(b, 0).getAsJsonArray("remainder").get(0).getAsJsonObject().addProperty("count", 1))),
            "the source once per craft");
        assertNotNull(check(changed(SpecialFixtures::bannerCopy, b -> step(b, 0).addProperty("result", "minecraft:red_banner"))));
        assertNotNull(check(changed(SpecialFixtures::bannerCopy, b -> step(b, 0).addProperty("spec", "copy:minecraft:red_banner#a1b2c3d4"))), "a key of another banner");
    }

    @Test
    void aMapCopyIsTheSourceAndBlanksMakingOneMoreMapThanBlanksAndUsesTheSourceUp() {
        assertNotNull(check(changed(SpecialFixtures::mapCopy, b -> step(b, 0).addProperty("count", 8))), "9 maps from 8 blanks and the source");
        assertNotNull(check(changed(SpecialFixtures::mapCopy, b -> setCell(b, 0, 8, null, null))), "7 blanks make 8");
        assertNotNull(check(changed(SpecialFixtures::mapCopy, b -> setCell(b, 0, 1, "minecraft:paper", null))), "a blank that is no empty map");
        assertNotNull(check(changed(SpecialFixtures::mapCopy, b -> step(b, 0).add("remainder", JsonParser.parseString("[ " + q(MAP, K_MAP, "copy:" + K_MAP, 1) + " ]")))),
            "a map's source is used up: no remainder");
    }

    @Test
    void aBookCopyIsTheOriginalAndWritableBooksOneCopyEachAndTheOriginalComesBack() {
        assertNotNull(check(changed(SpecialFixtures::bookCopy, b -> step(b, 0).addProperty("count", 7))), "a copy for each writable book");
        assertNotNull(check(changed(SpecialFixtures::bookCopy, b -> setCell(b, 0, 6, "minecraft:book", null))), "a plain book is no writable book");
        assertNotNull(check(changed(SpecialFixtures::bookCopy, b -> step(b, 0).remove("remainder"))), "the original must be handed back");
        assertNotNull(check(changed(SpecialFixtures::bookCopy, b -> step(b, 0).add("remainder", JsonParser.parseString("[ " + q(BOOK, K_BOOK, "copy:" + K_BOOK, 2) + " ]")))));
    }

    @Test
    void aShieldIsAPlainShieldAndTheBannerOfTheKeyWhichIsConsumed() {
        assertNotNull(check(changed(SpecialFixtures::shield, b -> setCell(b, 0, 0, "minecraft:bow", null))));
        assertNotNull(check(changed(SpecialFixtures::shield, b -> setCell(b, 0, 1, BANNER, null))), "a banner with no key");
        assertNotNull(check(changed(SpecialFixtures::shield, b -> setCell(b, 0, 1, "minecraft:red_banner", "copy:" + K_BANNER))), "the banner of another colour than the key");
        assertNotNull(check(changed(SpecialFixtures::shield, b -> step(b, 0).add("remainder", JsonParser.parseString("[ " + q(BANNER, K_BANNER, "copy:" + K_BANNER, 2) + " ]")))),
            "the banner is consumed");
        // A spec of a shield on a key that is no banner's
        assertNotNull(check(changed(SpecialFixtures::shield, b -> step(b, 0).addProperty("spec", "shield:" + K_MAP))));
    }

    // Takes, lines, leftovers and the inputs in hand

    @Test
    void aTakeOfACopyNamesTheKeyOfItsSpecAndEverySpecFitsItsItem() {
        assertNotNull(check(changed(SpecialFixtures::bannerCopy, b -> b.getAsJsonArray("take").get(0).getAsJsonObject().addProperty("key", "minecraft:white_banner#ffff"))));
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> b.getAsJsonArray("take").get(1).getAsJsonObject().addProperty("id", "minecraft:arrow"))), "a tipped spec on an arrow");
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> b.getAsJsonArray("take").get(1).getAsJsonObject().addProperty("spec", NV))), "a stew spec on a tipped arrow");
        assertNotNull(check(changed(SpecialFixtures::stew, b -> b.getAsJsonArray("lines").get(0).getAsJsonObject().addProperty("key", "minecraft:tipped_arrow"))));
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> b.getAsJsonArray("leftovers").get(0).getAsJsonObject().addProperty("spec", "tipped:long_poison"))), "not canonical");
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> b.getAsJsonArray("lines").get(0).getAsJsonObject().addProperty("spec", "dyed:b02e26"))), "a kind of B5");
        assertNull(check(tipped()), "the block as it is");
    }

    @Test
    void theInputsOfAnyCraftOfABlockWithACopyAreInHandTheTakeAndWhatEarlierCraftsMade() {
        // The second craft of a map copy takes its source from the first one's result (two steps, or two crafts of one step)
        assertNull(check(mapCopy()));
        assertNull(check(mapCopyInOneStep()));
        // No source map in the take: even the first craft has nothing to copy
        assertNotNull(check(changed(SpecialFixtures::mapCopy, b -> b.getAsJsonArray("take").remove(0))));
        assertNotNull(check(changed(SpecialFixtures::mapCopyInOneStep, b -> b.getAsJsonArray("take").remove(0))));
        // Fewer blanks in the take than the crafts use
        assertNotNull(check(changed(SpecialFixtures::mapCopy, b -> b.getAsJsonArray("take").get(1).getAsJsonObject().addProperty("count", 10))));
        // A step made before the one that makes what it takes (the order of the steps is the order they are made in)
        assertNotNull(check(changed(SpecialFixtures::mapCopy, b -> {
            JsonArray steps = b.getAsJsonArray("steps");
            JsonObject first = steps.remove(0).getAsJsonObject();
            steps.add(first);
            b.getAsJsonArray("take").remove(0);
        })));
        // A banner copy with no stock of the key beyond its source: each craft's source is the one handed back by the one before
        JsonObject onlySource = bannerCopy();
        onlySource.getAsJsonArray("take").remove(0);
        assertNull(check(onlySource), "one source in the take is enough for any number of crafts");
        JsonObject noSource = bannerCopy();
        noSource.getAsJsonArray("take").remove(0);
        noSource.getAsJsonArray("take").remove(0);
        assertNotNull(check(noSource), "no source at all");
        // A book copy needs the original in the take, and it is not credited by the copies it makes
        assertNotNull(check(changed(SpecialFixtures::bookCopy, b -> b.getAsJsonArray("take").remove(0))));
        // A shield takes a banner of the key, and the shields themselves
        assertNotNull(check(changed(SpecialFixtures::shield, b -> b.getAsJsonArray("take").remove(0))));
        assertNotNull(check(changed(SpecialFixtures::shield, b -> b.getAsJsonArray("take").get(1).getAsJsonObject().addProperty("count", 1))));
    }

    @Test
    void anyB4StepIsCheckedForInputsInHandButABlockOfB1ToB3IsJudgedAsBefore() {
        // A tipped block with no arrows in the take: the craft has nothing to put around the potion
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> b.getAsJsonArray("take").remove(2))));
        assertNull(check(tipped()));
        // A B3 block has no such check (it never had one)
        JsonObject rocket = CraftFireworkTest.rocketFromStock();
        rocket.addProperty("extras", SERVER_HASH);
        rocket.getAsJsonArray("take").remove(0);
        assertNull(CraftOrder.check(parse(rocket), R, 2048, SERVER_HASH, Set.of("firework"), null, 0, FIREWORKS, TABLES));
    }

    // B1 to B3 unchanged

    @Test
    void aFireworkBlockIsStillJudgedByTheFireworkGatesAndNeedsNoB4Feature() {
        JsonObject rocket = CraftFireworkTest.rocketFromStock();
        Order.Craft c = Order.parseCraft(rocket);
        assertNotNull(c);
        // The extras of this test are the B4 ones: the B3 block names the B3 hash, so it is another block of extras
        rocket.addProperty("extras", SERVER_HASH);
        c = Order.parseCraft(rocket);
        assertEquals(Set.of("firework"), c.features());
        assertNull(CraftOrder.check(c, R, 2048, SERVER_HASH, Set.of("firework"), null, 0, FIREWORKS, null));
        assertNull(CraftOrder.check(c, R, 2048, SERVER_HASH, Set.of("firework"), null, 0, FIREWORKS, TABLES));
        assertNotNull(CraftOrder.check(c, R, 2048, SERVER_HASH, Set.of("tipped", "stew", "copy"), null, 0, FIREWORKS, TABLES), "a bot that does not say firework");
    }

    @Test
    void aSpecOfAKindNoMilestoneCarriesOutIsRefused() {
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> b.getAsJsonArray("take").get(1).getAsJsonObject().addProperty("spec", "dyed:b02e26"))));
        assertNotNull(check(changed(SpecialFixtures::tipped, b -> step(b, 0).addProperty("recipe", "qwerte:special/dye"))));
    }
}
