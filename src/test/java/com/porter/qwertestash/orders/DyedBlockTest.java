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

import static com.porter.qwertestash.orders.DyedFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Craft blocks with dyed leather armour, leather horse armour and wolf armour on the bot (CRAFT-CONTRACT-v1.2.md, milestone B5, and the server's PROTOCOL.md for
 * it): the blocks as the real server writes them pass the bot's check; each dyeing grid is built again from the spec and the bot's own armour colours and the
 * colour it mixes must be the step's; a two-coat colour's first coat must come from an earlier step and is never stock; chunks of 8 are interleaved; bounds on
 * dyes, takes and lines; the feature is gated by the bot's {@code can}, its tables and the block's {@code needs}; blocks of B1 to B4 are judged as before.
 */
class DyedBlockTest {
    private static JsonObject stepOf(JsonObject b, int i) {
        return b.getAsJsonArray("steps").get(i).getAsJsonObject();
    }

    private static JsonObject changed(Supplier<JsonObject> block, Consumer<JsonObject> change) {
        JsonObject b = block.get();
        change.accept(b);
        return b;
    }

    private static void setCell(JsonObject b, int step, int cell, String id, String spec) {
        JsonArray g = stepOf(b, step).getAsJsonArray("grid");
        if (id == null) {
            g.set(cell, JsonNull.INSTANCE);
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        if (spec != null) o.addProperty("spec", spec);
        g.set(cell, o);
    }

    private static Two firstTwoCoat() {
        for (Two t : twoCoats()) if (DyedSpecs.mix(-1, t.first(), PALETTE) == t.mid() && t.first().length == 16) return t;
        throw new AssertionError("no two-coat path in the vectors");
    }

    private static String refused(JsonObject block) {
        String bad = check(block);
        assertNotNull(bad, "the block should be refused: " + block);
        return bad;
    }

    // The server's blocks

    @Test
    void everyBlockTheServerWritesIsReadAndPassesTheBotsCheck() {
        Two two = firstTwoCoat();
        for (var e : java.util.Map.<String, JsonObject>of("red with stock", red(5, 8), "red all crafted", red(0, 3), "orange", orange(4), "two coats, one piece", twoCoat(CHEST, two, 1),
            "two coats, one chunk", twoCoat(CHEST, two, 8), "two coats, three chunks", twoCoat(CHEST, two, 20), "horse armour", twoCoat(HORSE, two, 9)).entrySet()) {
            Order.Craft c = parse(e.getValue());
            assertNull(c.reserved(), e.getKey());
            assertTrue(c.usesSpecs(), e.getKey());
            assertEquals(Set.of("dyed"), c.features(), e.getKey());
            assertNull(check(c, CAN), e.getKey());
        }
        // Wolf armour and boots with the same step
        assertNull(check(changed(() -> red(0, 2), b -> {
        })));
    }

    @Test
    void everyDyeableItemOfTheExtrasPasses() {
        for (String item : DYED.dyeable()) {
            JsonObject b = block("[" + line(item, RED, 2) + "]", "[" + step(item, RED, null, mix("red"), 2) + "]", "[ " + q(item, 2) + ", " + q("minecraft:red_dye", 2) + " ]", "[]", 2);
            assertNull(check(b), item);
        }
        assertEquals(6, DYED.dyeable().size());
        assertTrue(DYED.dyeable().contains(WOLF) && DYED.dyeable().contains(HORSE));
    }

    @Test
    void theChunksOfATwoCoatOrderAreInterleavedAndPassWhateverTheChunkSize() {
        Two two = firstTwoCoat();
        for (int n : new int[] {1, 7, 8, 9, 16, 17, 33}) {
            JsonObject b = twoCoat(CHEST, two, n);
            int chunks = (n + 7) / 8;
            assertEquals(2 * chunks, b.getAsJsonArray("steps").size());
            assertNull(check(b), "n = " + n);
            // The second chunk's first coat comes after the first chunk's second coat
            if (chunks > 1) assertEquals(DyedSpecs.specOf(two.result()), stepOf(b, 1).get("spec").getAsString());
        }
        // 64 steps are the most a block may have: 32 chunks of two
        assertEquals(64, twoCoat(CHEST, two, 256).getAsJsonArray("steps").size());
        assertNull(check(twoCoat(CHEST, two, 256)));
    }

    // The mixing, checked by the bot's own arithmetic

    @Test
    void theGridIsRebuiltFromTheSpecAndTheBotsOwnColoursAndAColourItDoesNotMixIsRefused() {
        // The same grid, but the step says another colour: the bot mixes red and yellow and gets d78331, not this
        JsonObject wrong = changed(() -> orange(2), b -> stepOf(b, 0).addProperty("spec", "dyed:d78332"));
        assertTrue(refused(wrong).contains("mixes dyed:d78331 here, not dyed:d78332"), refused(wrong));
        // The line follows the step, so only the mix is wrong
        wrong.getAsJsonArray("lines").get(0).getAsJsonObject().addProperty("spec", "dyed:d78332");
        assertTrue(refused(wrong).contains("mixes"), refused(wrong));
        // A red dye only: the colour is the dye's own, not an average with the item
        assertTrue(refused(changed(() -> red(0, 2), b -> stepOf(b, 0).addProperty("spec", "dyed:b02e27"))).contains("mixes"));
        // Black is no mix of dyes: a step that says it is refused
        assertTrue(refused(changed(() -> red(0, 2), b -> stepOf(b, 0).addProperty("spec", BLACK))).contains("mixes"));
    }

    @Test
    void theDyesMustBeInTheContractsOrderWithRepeatsAndAtMostEight() {
        // yellow before red is the order; red before yellow is refused
        JsonObject swapped = changed(() -> orange(2), b -> {
            setCell(b, 0, 1, "minecraft:red_dye", null);
            setCell(b, 0, 2, "minecraft:yellow_dye", null);
        });
        assertTrue(refused(swapped).contains("out of the contract's order"), refused(swapped));
        // Eight of one dye is fine (a mix and its multiples are one colour), nine don't fit
        int[] eight = new int[16];
        eight[14] = 8;
        JsonObject eightRed = block("[" + line(CHEST, RED, 1) + "]", "[" + step(CHEST, RED, null, eight, 1) + "]", "[ " + q(CHEST, 1) + ", " + q("minecraft:red_dye", 8) + " ]", "[]", 1);
        assertNull(check(eightRed));
        // Not a dye in a dye cell
        assertTrue(refused(changed(() -> orange(2), b -> setCell(b, 0, 1, "minecraft:gunpowder", null))).contains("no dye in slot 2"));
        // A dye with a spec
        assertTrue(refused(changed(() -> orange(2), b -> setCell(b, 0, 1, "minecraft:yellow_dye", "dyed:fed83d"))).contains("no dye in slot 2"));
        // A gap in the cells
        assertTrue(refused(changed(() -> orange(2), b -> {
            setCell(b, 0, 2, null, null);
            setCell(b, 0, 3, "minecraft:red_dye", null);
        })).contains("gap"));
        // No dye at all
        assertTrue(refused(changed(() -> red(0, 2), b -> setCell(b, 0, 1, null, null))).contains("0 dyes"));
    }

    @Test
    void theItemInCellZeroIsTheResultAndDyeableByTheBotsExtras() {
        assertTrue(refused(changed(() -> red(0, 2), b -> setCell(b, 0, 0, "minecraft:leather_boots", null))).contains("wrong item in slot 1"));
        assertTrue(refused(changed(() -> red(0, 2), b -> setCell(b, 0, 0, null, null))).contains("wrong item in slot 1"));
        // White wool is not dyeable (S16): a result, a line and a take of it are refused whatever the grid says
        JsonObject wool = block("[" + line("minecraft:white_wool", RED, 2) + "]", "[" + step("minecraft:white_wool", RED, null, mix("red"), 2) + "]",
            "[ " + q("minecraft:white_wool", 2) + ", " + q("minecraft:red_dye", 2) + " ]", "[]", 2);
        assertTrue(refused(wool).contains("not dyeable"), refused(wool));
        // A dyed take on a wool, a leftover of one
        JsonObject takeWool = changed(() -> red(2, 4), b -> b.getAsJsonArray("take").get(0).getAsJsonObject().addProperty("id", "minecraft:white_wool"));
        assertTrue(refused(takeWool).contains("not dyeable") || refused(takeWool).contains("key"), refused(takeWool));
        JsonObject leftWool = changed(() -> red(0, 2), b -> b.add("leftovers", JsonParser.parseString("[ { \"id\": \"minecraft:white_wool\", \"spec\": \"" + RED + "\", \"count\": 1 } ]")));
        assertTrue(refused(leftWool).contains("leftover"), refused(leftWool));
        // A dyed piece of a dyeable item is never left over either (the contract: a first coat is only ever handed back in the result's returned list)
        JsonObject leftRed = changed(() -> red(0, 2), b -> b.add("leftovers", JsonParser.parseString("[ { \"id\": \"" + CHEST + "\", \"spec\": \"" + RED + "\", \"count\": 1 } ]")));
        assertTrue(refused(leftRed).contains("never left over"), refused(leftRed));
        // A plain leftover (the undyed base, a dye) is as before
        assertNull(check(changed(() -> red(0, 2), b -> b.add("leftovers", JsonParser.parseString("[ " + q("minecraft:red_dye", 0) + " ]")))));
        // Any input of a dyeing step that names a key is refused, a plain one too (no stock is named by a step's input)
        JsonObject keyedBase = changed(() -> red(0, 2), b -> stepOf(b, 0).getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("key", CHEST));
        assertTrue(refused(keyedBase).contains("by a key"), refused(keyedBase));
        JsonObject keyedDye = changed(() -> red(0, 2), b -> stepOf(b, 0).getAsJsonArray("inputs").get(1).getAsJsonObject().addProperty("key", "minecraft:red_dye"));
        assertTrue(refused(keyedDye).contains("by a key"), refused(keyedDye));
    }

    @Test
    void aCountOfMoreThanOneNoFillsARemainderOrAFurnaceIsRefused() {
        assertTrue(refused(changed(() -> red(0, 2), b -> stepOf(b, 0).addProperty("count", 2))).contains("not 1"));
        assertTrue(refused(changed(() -> red(0, 2), b -> stepOf(b, 0).addProperty("fills", 0))).contains("no fills"));
        assertTrue(refused(changed(() -> red(0, 2), b -> stepOf(b, 0).add("remainder", JsonParser.parseString("[ " + q("minecraft:red_dye", 2) + " ]")))).contains("remainder"));
        assertNotNull(check(changed(() -> red(0, 2), b -> stepOf(b, 0).addProperty("station", "furnace"))));
        // The grid must have nine cells
        assertTrue(refused(changed(() -> red(0, 2), b -> stepOf(b, 0).getAsJsonArray("grid").remove(8))).contains("8 cells"));
        // The inputs must be the grid times the fills
        assertTrue(refused(changed(() -> orange(3), b -> stepOf(b, 0).add("inputs", JsonParser.parseString("[ " + q(CHEST, 3) + ", " + q("minecraft:red_dye", 3) + " ]")))).contains("not its grid"));
    }

    @Test
    void aSpecThatIsNotCanonicalIsRefused() {
        for (String bad : new String[] {"dyed:B02E26", "dyed:b02e2", "dyed:b02e260", "dyed:", "dyed:b02e2g", "dyed:#b02e26", "dyed: b02e26"}) {
            JsonObject b = changed(() -> red(0, 2), x -> {
                stepOf(x, 0).addProperty("spec", bad);
                x.getAsJsonArray("lines").get(0).getAsJsonObject().addProperty("spec", bad);
            });
            assertNotNull(check(b), bad);
        }
    }

    // Two coats

    @Test
    void theFirstCoatIsTheResultOfAnEarlierStepAndNeverStock() {
        Two two = firstTwoCoat();
        JsonObject whole = twoCoat(CHEST, two, 2);
        assertNull(check(whole));
        // The second coat alone: nothing makes its first coat
        JsonObject alone = changed(() -> twoCoat(CHEST, two, 2), b -> b.getAsJsonArray("steps").remove(0));
        assertTrue(refused(alone).contains("no first coat"), refused(alone));
        // The second coat before the first
        JsonObject reversed = changed(() -> twoCoat(CHEST, two, 2), b -> {
            JsonArray s = b.getAsJsonArray("steps");
            JsonObject first = s.remove(0).getAsJsonObject();
            s.add(first);
        });
        assertNotNull(check(reversed));
        // A stock item of the first coat's colour in the take is no first coat: the block has no step that makes it
        String mid = DyedSpecs.specOf(two.mid());
        JsonObject stockMid = changed(() -> twoCoat(CHEST, two, 2), b -> {
            b.getAsJsonArray("steps").remove(0);
            JsonArray take = b.getAsJsonArray("take");
            take.add(JsonParser.parseString(q(CHEST, "minecraft:leather_chestplate#d0d0d0d0", mid, 2)));
        });
        assertNotNull(check(stockMid), "a dyed stock item would be used up as a base");
    }

    @Test
    void aFirstCoatThatDoesntMatchTheStepThatMakesItIsRefused() {
        Two two = firstTwoCoat();
        String mid = DyedSpecs.specOf(two.mid());
        // The second step starts from another colour than the first step makes
        JsonObject other = changed(() -> twoCoat(CHEST, two, 2), b -> {
            setCell(b, 1, 0, CHEST, "dyed:123456");
            JsonObject in = stepOf(b, 1).getAsJsonArray("inputs").get(0).getAsJsonObject();
            in.addProperty("spec", "dyed:123456");
        });
        String bad = refused(other);
        assertTrue(bad.contains("no first coat") || bad.contains("mixes"), bad);
        // The cell and the input disagree
        JsonObject cellOnly = changed(() -> twoCoat(CHEST, two, 2), b -> setCell(b, 1, 0, CHEST, "dyed:123456"));
        assertNotNull(check(cellOnly));
        // The first coat of another item
        JsonObject otherItem = changed(() -> twoCoat(CHEST, two, 2), b -> {
            setCell(b, 1, 0, BOOTS, mid);
            stepOf(b, 1).addProperty("result", BOOTS);
        });
        assertNotNull(check(otherItem));
        // A first coat with a key (as if it were stock)
        JsonObject keyed = changed(() -> twoCoat(CHEST, two, 2), b -> stepOf(b, 1).getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("key", "minecraft:leather_chestplate#d0d0d0d0"));
        assertTrue(refused(keyed).contains("by a key"), refused(keyed));
        // The first coat step says another colour than the dyes mix: the second coat then mixes from a colour that is not there
        JsonObject lie = changed(() -> twoCoat(CHEST, two, 2), b -> stepOf(b, 0).addProperty("spec", "dyed:111111"));
        assertNotNull(check(lie));
    }

    @Test
    void theSecondCoatTakesAtMostFourDyesAndTheFirstAtMostThree() {
        // A colour from a first coat of 4 dyes: made as a one-coat colour it is fine, used as a first coat it is refused
        int[] four = mix("red", "red", "yellow", "blue");
        int mid = DyedSpecs.mix(-1, four, PALETTE);
        int[] second = mix("white");
        int result = DyedSpecs.mix(mid, second, PALETTE);
        String midSpec = DyedSpecs.specOf(mid), resultSpec = DyedSpecs.specOf(result);
        JsonObject b = block("[" + line(CHEST, resultSpec, 1) + "]", "[" + step(CHEST, midSpec, null, four, 1) + "," + step(CHEST, resultSpec, midSpec, second, 1) + "]",
            "[ " + q(CHEST, 1) + ", " + q("minecraft:red_dye", 2) + ", " + q("minecraft:yellow_dye", 1) + ", " + q("minecraft:blue_dye", 1) + ", " + q("minecraft:white_dye", 1) + " ]", "[]", 2);
        String bad = refused(b);
        assertTrue(bad.contains("no first coat (at most " + DyedSpecs.FIRST_COAT + " dyes)"), bad);
        // The same first coat with 3 dyes passes
        int[] three = mix("red", "red", "yellow");
        int mid3 = DyedSpecs.mix(-1, three, PALETTE), res3 = DyedSpecs.mix(mid3, second, PALETTE);
        String m3 = DyedSpecs.specOf(mid3), r3 = DyedSpecs.specOf(res3);
        assertNull(check(block("[" + line(CHEST, r3, 1) + "]", "[" + step(CHEST, m3, null, three, 1) + "," + step(CHEST, r3, m3, second, 1) + "]",
            "[ " + q(CHEST, 1) + ", " + q("minecraft:red_dye", 2) + ", " + q("minecraft:yellow_dye", 1) + ", " + q("minecraft:white_dye", 1) + " ]", "[]", 2)));
        // A second coat of 5 dyes
        int[] five = mix("white", "white", "blue", "blue", "black");
        int res5 = DyedSpecs.mix(mid3, five, PALETTE);
        String r5 = DyedSpecs.specOf(res5);
        String lie = refused(block("[" + line(CHEST, r5, 1) + "]", "[" + step(CHEST, m3, null, three, 1) + "," + step(CHEST, r5, m3, five, 1) + "]",
            "[ " + q(CHEST, 1) + ", " + q("minecraft:red_dye", 2) + ", " + q("minecraft:yellow_dye", 1) + ", " + q("minecraft:white_dye", 2) + ", " + q("minecraft:blue_dye", 2) + ", " + q("minecraft:black_dye", 1) + " ]", "[]", 2));
        assertTrue(lie.contains("on a dyed item (at most " + DyedSpecs.SECOND_COAT + ")"), lie);
    }

    @Test
    void theSecondCoatsAreNotWhatTheOrderDeliversAndTheLinesMustBeCovered() {
        Two two = firstTwoCoat();
        // Lines of the colour: 2 made by 2 second coats; the first coats are used up
        assertNull(check(twoCoat(CHEST, two, 2)));
        // The line asks for more than the second coats make
        JsonObject more = changed(() -> twoCoat(CHEST, two, 2), b -> b.getAsJsonArray("lines").get(0).getAsJsonObject().addProperty("count", 3));
        assertTrue(refused(more).contains("don't make 3"), refused(more));
        // A line of the first coat's colour is not covered by the first coats the second coat used up
        String mid = DyedSpecs.specOf(two.mid());
        JsonObject midLine = changed(() -> twoCoat(CHEST, two, 2), b -> b.getAsJsonArray("lines").get(0).getAsJsonObject().addProperty("spec", mid));
        assertNotNull(check(midLine));
    }

    // Bounds

    @Test
    void aTakeIsBoundedByWhatTheStepsLinesAndLeftoversUse() {
        // One plain chestplate more than the step uses
        JsonObject moreBase = changed(() -> red(0, 2), b -> b.getAsJsonArray("take").get(0).getAsJsonObject().addProperty("count", 3));
        assertTrue(refused(moreBase).contains("takes 3"), refused(moreBase));
        // More dyes
        JsonObject moreDye = changed(() -> red(0, 2), b -> b.getAsJsonArray("take").get(1).getAsJsonObject().addProperty("count", 5));
        assertNotNull(check(moreDye));
        // More dyed stock than the line asks for
        JsonObject moreStock = changed(() -> red(3, 5), b -> b.getAsJsonArray("take").get(0).getAsJsonObject().addProperty("count", 6));
        assertNotNull(check(moreStock));
        // A dyed take that no line wants, only a step input could "use": the first coat is not stock
        Two two = firstTwoCoat();
        String mid = DyedSpecs.specOf(two.mid());
        JsonObject sneaky = changed(() -> twoCoat(CHEST, two, 2), b -> b.getAsJsonArray("take").add(JsonParser.parseString(q(CHEST, "minecraft:leather_chestplate#d0d0d0d0", mid, 2))));
        assertNotNull(check(sneaky), "stock of the first coat's colour taken for the first coat's input");
        // A key on a dyed take must be a key of that item
        assertNotNull(check(changed(() -> red(2, 4), b -> b.getAsJsonArray("take").get(0).getAsJsonObject().addProperty("key", "minecraft:leather_boots#a0a0a0a1"))));
        // A dyed take with no key
        assertNotNull(check(changed(() -> red(2, 4), b -> b.getAsJsonArray("take").get(0).getAsJsonObject().remove("key"))));
        // A step that dyes more than it takes: the inputs are not in hand
        JsonObject short1 = changed(() -> orange(3), b -> b.getAsJsonArray("take").get(1).getAsJsonObject().addProperty("count", 2));
        assertTrue(refused(short1).contains("in hand") || refused(short1).contains("no "), refused(short1));
    }

    @Test
    void theBaseMayBeCraftedByAnEarlierTableStepAndIsInHandForTheDyeing() {
        // The chestplates come from leather by a table step (the server writes it first), then the dyeing takes them
        JsonObject b = redFromLeather(2);
        assertNull(check(parse(b), CAN, LR));
        // The dyeing before its base is made: the plain chestplates are not in hand
        JsonObject reversed = redFromLeather(2);
        JsonArray s = reversed.getAsJsonArray("steps");
        s.add(s.remove(0));
        assertTrue(check(parse(reversed), CAN, LR).contains("in hand") || check(parse(reversed), CAN, LR).contains("no "), check(parse(reversed), CAN, LR));
        // With the bot's table that lacks the recipe the table step is refused as before
        assertNotNull(check(parse(redFromLeather(2)), CAN, R));
    }

    // The gate

    @Test
    void aBotThatDoesntSayDyedOrHasNoTablesOrOtherExtrasRefusesTheBlock() {
        JsonObject b = red(2, 4);
        Order.Craft c = parse(b);
        assertNull(check(c, CAN));
        Set<String> without = Set.of("firework", "tipped", "stew", "copy");
        String bad = check(c, without);
        assertNotNull(bad);
        assertTrue(bad.contains("needs dyed") || bad.contains("dyed"), bad);
        // No tables of its own
        assertNotNull(CraftOrder.check(c, R, 2048, SERVER_HASH, CAN, null, 0, FIREWORKS, TABLES, null));
        assertNotNull(CraftOrder.check(c, R, 2048, SERVER_HASH, CAN, null, 0, FIREWORKS, TABLES));
        // Other extras
        assertNotNull(CraftOrder.check(c, R, 2048, SpecialFixtures.SERVER_HASH, CAN, null, 0, FIREWORKS, TABLES, DYED));
        // The block doesn't list dyed in its needs
        JsonObject noNeeds = changed(() -> red(2, 4), x -> x.add("needs", new JsonArray()));
        assertTrue(refused(noNeeds).contains("doesn't say it needs dyed"), refused(noNeeds));
        // Extras that don't list the special, or lack an armour colour, or name no dyeable item: the bot can't dye
        JsonObject j = JsonParser.parseString(EXTRAS.json().toString()).getAsJsonObject();
        JsonObject noSpecial = JsonParser.parseString(j.toString()).getAsJsonObject();
        noSpecial.add("special", JsonParser.parseString("[\"rocket\",\"star\",\"star_fade\",\"tipped\",\"stew\",\"banner_copy\",\"map_copy\",\"book_copy\",\"shield\"]"));
        assertFalse(DyedSpecs.Tables.of(noSpecial).can());
        JsonObject noColour = JsonParser.parseString(j.toString()).getAsJsonObject();
        noColour.getAsJsonObject("dyes").getAsJsonObject("red").remove("armor");
        assertFalse(DyedSpecs.Tables.of(noColour).can());
        JsonObject noItems = JsonParser.parseString(j.toString()).getAsJsonObject();
        noItems.add("dyeable", new JsonArray());
        assertFalse(DyedSpecs.Tables.of(noItems).can());
        // An item the bot doesn't know is left out whatever the server lists (the six are hard-coded here); the rest still dye
        JsonObject odd = JsonParser.parseString(j.toString()).getAsJsonObject();
        odd.getAsJsonArray("dyeable").add("not an id");
        odd.getAsJsonArray("dyeable").add("minecraft:white_wool");
        odd.getAsJsonArray("dyeable").add("leather_boots");
        odd.getAsJsonArray("dyeable").add("minecraft:diamond_sword");
        assertTrue(DyedSpecs.Tables.of(odd).can());
        assertEquals(Set.copyOf(DYED.dyeable()), DyedSpecs.Tables.of(odd).dyeable(), "only the six, namespaced");
        JsonObject onlyOdd = JsonParser.parseString(j.toString()).getAsJsonObject();
        onlyOdd.add("dyeable", JsonParser.parseString("[\"minecraft:white_wool\",\"minecraft:diamond_sword\"]"));
        assertFalse(DyedSpecs.Tables.of(onlyOdd).can(), "none of them is one the bot allows");
        assertTrue(DyedSpecs.allowed("minecraft:wolf_armor") && DyedSpecs.allowed("leather_helmet"));
        assertFalse(DyedSpecs.allowed("minecraft:white_wool") || DyedSpecs.allowed(null) || DyedSpecs.allowed("minecraft:"));
        assertTrue(DyedSpecs.Tables.of(j).can());
        assertNull(DyedSpecs.Tables.of((JsonObject) null));
        // A bad palette entry
        JsonObject badColour = JsonParser.parseString(j.toString()).getAsJsonObject();
        badColour.getAsJsonObject("dyes").getAsJsonObject("red").addProperty("armor", "B02E26");
        assertFalse(DyedSpecs.Tables.of(badColour).can());
    }

    @Test
    void aDyedSpecWithoutTheFeatureIsNeverTakenForAnotherKind() {
        // A B3 or B4 bot that doesn't know dyed (can without it): the spec's feature is dyed, so the block is refused, not read as a firework
        JsonObject b = red(2, 4);
        assertNotNull(CraftOrder.check(parse(b), R, 2048, SERVER_HASH, Set.of("firework", "tipped", "stew", "copy"), null, 0, FIREWORKS, TABLES, DYED));
        assertEquals("dyed", VariantSpecs.featureOf(RED));
        assertNull(VariantSpecs.parse(RED), "a dyed spec is none of B4's");
    }

    @Test
    void blocksOfB1ToB4AreJudgedAsBefore() {
        // The B4 blocks with the B4 extras, with and without the B5 tables: the same
        for (JsonObject b : new JsonObject[] {SpecialFixtures.tipped(), SpecialFixtures.stew(), SpecialFixtures.bannerCopy(), SpecialFixtures.mapCopy(), SpecialFixtures.bookCopy(), SpecialFixtures.shield()}) {
            Order.Craft c = SpecialFixtures.parse(b);
            assertNull(CraftOrder.check(c, SpecialFixtures.R, 2048, SpecialFixtures.SERVER_HASH, SpecialFixtures.CAN, null, 0, SpecialFixtures.FIREWORKS, SpecialFixtures.TABLES));
            assertNull(CraftOrder.check(c, SpecialFixtures.R, 2048, SpecialFixtures.SERVER_HASH, SpecialFixtures.CAN, null, 0, SpecialFixtures.FIREWORKS, SpecialFixtures.TABLES, DYED));
            assertFalse(c.features().contains("dyed"));
        }
    }

    @Test
    void aBlockWithoutAnyDyedFieldNeedsNoDyedFeature() {
        JsonObject b = SpecialFixtures.tipped();
        Order.Craft c = SpecialFixtures.parse(b);
        // A bot that can't dye still makes tipped arrows
        assertNull(CraftOrder.check(c, SpecialFixtures.R, 2048, SpecialFixtures.SERVER_HASH, Set.of("tipped"), null, 0, SpecialFixtures.FIREWORKS, SpecialFixtures.TABLES, null));
    }

    @Test
    void aSecondCoatBlockWithAnIntermediateThatIsAlsoAFinalColourIsCounted() {
        // The item in the first coat's colour is also delivered: a line of the colour of the first coat, covered by stock, plus a two-coat line
        Two two = firstTwoCoat();
        String mid = DyedSpecs.specOf(two.mid());
        JsonObject b = twoCoat(CHEST, two, 2);
        b.getAsJsonArray("lines").add(JsonParser.parseString(line(CHEST, mid, 3)));
        b.getAsJsonArray("take").add(JsonParser.parseString(q(CHEST, "minecraft:leather_chestplate#d0d0d0d0", mid, 3)));
        assertNull(check(b), "3 of the colour from stock, 2 first coats made and used, 2 second coats delivered");
    }
}
