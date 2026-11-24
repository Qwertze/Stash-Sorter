package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.Extras;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.porter.qwertestash.orders.DyedFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colour arithmetic of the armour-dye recipe as the bot has it (CRAFT-CONTRACT-v1.2.md 13 and 15, B5), against the server's own vectors: the hand-worked
 * ones of its {@code DyeMathTest} and 400 mixes (and 25 two-coat paths) that its {@code DyeMath} wrote into {@code dye-vectors-b5.txt}. [UNVERIFIED in game:
 * both sides are written from the same description of the recipe; a vector captured in game is what settles it.]
 */
class DyedSpecsTest {
    private static String hex(int mix) {
        return String.format("%06x", mix);
    }

    private static String mixHex(int start, String... dyes) {
        return hex(DyedSpecs.mix(start, mix(dyes), PALETTE));
    }

    @Test
    void thePaletteIsTheExtrasArmourTableInTheContractsDyeOrder() {
        assertEquals(16, PALETTE.length);
        assertEquals(0xf9fffe, PALETTE[NAMES.indexOf("white")]);
        assertEquals(0xb02e26, PALETTE[NAMES.indexOf("red")]);
        assertEquals(0x1d1d21, PALETTE[NAMES.indexOf("black")]);
        assertEquals(0x3c44aa, PALETTE[NAMES.indexOf("blue")]);
        assertTrue(DYED.can());
    }

    @Test
    void oneDyeGivesItsOwnColour_S14() {
        for (String d : NAMES) assertEquals(hex(PALETTE[NAMES.indexOf(d)]), mixHex(-1, d), d);
        assertEquals("b02e26", mixHex(-1, "red"));
    }

    @Test
    void theServersHandComputedVectors() {
        assertEquals("b02e26", mixHex(-1, "red", "red"));
        assertEquals("d78331", mixHex(-1, "red", "yellow"));
        assertEquals("d78331", mixHex(-1, "red", "yellow", "red", "yellow", "red", "yellow"), "a mix and its multiples are one colour");
        assertEquals("8b8e90", mixHex(-1, "white", "black"));
        assertEquals("ae4368", mixHex(-1, "red", "red", "blue"));
        // An already dyed item counts as one more dye
        assertEquals("ad5398", mixHex(0xb02e26, "blue"));
        assertEquals(mixHex(-1, "red", "blue"), mixHex(0xb02e26, "blue"));
        // Cut off, not rounded: 139.97 stays 139
        assertEquals(139, (DyedSpecs.mix(-1, mix("white", "black"), PALETTE) >> 16) & 255);
        // Black (rgb 0) is a colour: an item of it counts as one more dye
        assertEquals("000000", hex(DyedSpecs.mix(0, new int[16], PALETTE)), "no dye on a black item is black");
    }

    @Test
    void theServersFourHundredMixesAreTheSameInTheBot() {
        List<Vector> all = vectors();
        assertEquals(400, all.size());
        int dyed = 0;
        for (Vector v : all) {
            assertEquals(hex(v.result()), hex(DyedSpecs.mix(v.start(), v.counts(), PALETTE)), "start " + v.start() + " dyes " + java.util.Arrays.toString(v.counts()));
            if (v.start() >= 0) dyed++;
        }
        assertTrue(dyed > 80, "mixes on dyed items too: " + dyed);
    }

    @Test
    void theServersTwoCoatPathsAreTheSameInTheBot() {
        List<Two> paths = twoCoats();
        assertEquals(25, paths.size());
        for (Two t : paths) {
            int mid = DyedSpecs.mix(-1, t.first(), PALETTE);
            assertEquals(hex(t.mid()), hex(mid));
            assertEquals(hex(t.result()), hex(DyedSpecs.mix(mid, t.second(), PALETTE)));
            assertTrue(java.util.Arrays.stream(t.first()).sum() <= DyedSpecs.FIRST_COAT);
            assertTrue(java.util.Arrays.stream(t.second()).sum() <= DyedSpecs.SECOND_COAT);
        }
    }

    @Test
    void theSpecIsLowercaseHexOfSixDigits_S14_S15() {
        assertEquals("dyed:b02e26", DyedSpecs.specOf(0xB02E26));
        assertEquals("dyed:000000", DyedSpecs.specOf(0));
        assertEquals("dyed:00000a", DyedSpecs.specOf(10));
        assertEquals(0xb02e26, DyedSpecs.rgbOf("dyed:b02e26"));
        assertEquals(0, DyedSpecs.rgbOf("dyed:000000"));
        assertEquals(0xffffff, DyedSpecs.rgbOf("dyed:ffffff"));
        for (String bad : new String[] {null, "", "dyed:", "dyed:B02E26", "dyed:b02e2", "dyed:b02e260", "dyed:b02e2g", "dyed:+02e26", "dyed:-02e26", "red", "dyed:b02e26 ", " dyed:b02e26", "dyed:b02e26\n"}) {
            assertEquals(-1, DyedSpecs.rgbOf(bad), String.valueOf(bad));
        }
    }

    @Test
    void theRecipeIsRecognisedByItsIdOrItsPlanId() {
        assertTrue(DyedSpecs.isRecipe("qwerte:special/dyed"));
        assertTrue(DyedSpecs.isRecipe("qwerte:special/dyed#0123456789"));
        assertFalse(DyedSpecs.isRecipe("qwerte:special/dyed_other"));
        assertFalse(DyedSpecs.isRecipe("qwerte:special/tipped"));
        assertTrue(VariantSpecs.isB4("qwerte:special/dyed#0123456789"), "its plain cells take plain stacks only");
    }

    @Test
    void theGridOfADyeingStepHasTheItemThenTheDyesInPaletteOrder() {
        List<Order.Cell> g = DyedSpecs.grid(CHEST, null, mix("red", "yellow", "red"));
        assertEquals(9, g.size());
        assertEquals(new Order.Cell(CHEST, null), g.get(0));
        assertEquals("minecraft:yellow_dye", g.get(1).id());
        assertEquals("minecraft:red_dye", g.get(2).id());
        assertEquals("minecraft:red_dye", g.get(3).id());
        for (int i = 4; i < 9; i++) assertNull(g.get(i));
        assertEquals("dyed:b02e26", DyedSpecs.grid(CHEST, "dyed:b02e26", mix("white")).get(0).spec());
    }

    @Test
    void theTableAndTheServersExtrasHashAreAccepted() throws Exception {
        assertEquals(SERVER_HASH, EXTRAS.hash());
        assertEquals(SERVER_HASH, Extras.compute(EXTRAS.json()), "the normalised copy hashes to the same value");
        assertEquals(10, EXTRAS.json().getAsJsonArray("special").size());
        assertEquals(6, EXTRAS.json().getAsJsonArray("dyeable").size());
        assertNotEquals(SpecialFixtures.SERVER_HASH, SERVER_HASH, "the extras hash changed with B5");
        // An armour colour or a dyeable item is part of the hash
        JsonObject changed = JsonParser.parseString(EXTRAS.json().toString()).getAsJsonObject();
        changed.getAsJsonObject("dyes").getAsJsonObject("red").addProperty("armor", "b02e27");
        assertNotEquals(SERVER_HASH, Extras.compute(changed));
        JsonObject fewer = JsonParser.parseString(EXTRAS.json().toString()).getAsJsonObject();
        fewer.getAsJsonArray("dyeable").remove(0);
        assertNotEquals(SERVER_HASH, Extras.compute(fewer));
        // The B3 and B4 tables of the same block are still read
        assertNotNull(FIREWORKS);
        assertTrue(TABLES.canTipped() && TABLES.canStew() && TABLES.canCopy());
    }

    @Test
    void theDescriptionNeverShowsAKey() {
        assertEquals("colour b02e26", DyedSpecs.describe("dyed:b02e26"));
        assertEquals("Leather Chestplate (colour b02e26)", Recipes.pretty(CHEST + "@dyed:b02e26"));
    }
}
