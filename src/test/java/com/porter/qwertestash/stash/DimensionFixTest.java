package com.porter.qwertestash.stash;

import com.google.gson.Gson;
import com.porter.qwertestash.orders.OrderBlocker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.16.1: "minecraft:overworld" (the website, a saved area) and "overworld" (the bot) are one dimension everywhere, and a wait for the area says why. */
class DimensionFixTest {
    @Test
    void bothFormsAreTheSameDimensionInEveryHelper() {
        assertTrue(Dimensions.same("minecraft:overworld", "overworld"));
        assertTrue(Dimensions.same("overworld", "minecraft:overworld"));
        assertTrue(Dimensions.same("minecraft:the_nether", "the_nether"));
        assertTrue(Dimensions.same(null, null));
        assertFalse(Dimensions.same("minecraft:overworld", "the_nether"));
        assertFalse(Dimensions.same(null, "overworld"));
        assertFalse(Dimensions.same("overworld", null));
        assertTrue(OrderBlocker.sameDimension("minecraft:overworld", "overworld"));
        assertFalse(OrderBlocker.sameDimension("minecraft:overworld", "minecraft:the_end"));
        assertTrue(com.porter.qwertestash.stash.AreaRules.validate(new int[] {0, 60, 0, 10, 70, 10}, "minecraft:overworld", "overworld") == null);
    }

    @Test
    void anAreaAppliedWithANamespacedDimensionIsUsedInTheBotsDimension() {
        Stash s = new Stash("s", "srv");
        s.area = new Stash.Area(new int[] {0, 60, 0}, new int[] {10, 70, 10}, "minecraft:overworld");
        assertTrue(s.hasAreaIn("overworld"), "the area applies in the dimension it was set in");
        assertNotNull(s.bounds() == null && s.hasAreaIn("overworld") ? new int[0] : s.bounds() == null ? null : s.bounds().clone());
        assertFalse(s.hasAreaIn("the_nether"));
        assertTrue(s.hasAreaIn(null), "an unknown dimension of the bot does not block it");
        s.area = new Stash.Area(new int[] {0, 60, 0}, new int[] {10, 70, 10}, "overworld");
        assertTrue(s.hasAreaIn("minecraft:overworld"));
    }

    @Test
    void aSavedStashWithANamespacedAreaDimensionWorksWithoutAnyEdit() {
        // What a 1.15.2 stash file holds after the website set the area
        String json = "{\"name\":\"s\",\"server\":\"srv\",\"dimension\":\"overworld\",\"area\":{\"x1\":0,\"y1\":60,\"z1\":0,\"x2\":10,\"y2\":70,\"z2\":10,\"dimension\":\"minecraft:overworld\"}}";
        Stash s = new Gson().fromJson(json, Stash.class);
        assertEquals("minecraft:overworld", s.area.dimension, "the stored form is kept: the area hash is made from it, and the server's is the same");
        assertTrue(s.areaSet());
        assertTrue(s.hasAreaIn("overworld"));
        assertEquals(AreaRules.hashOf(new int[] {0, 60, 0, 10, 70, 10}, "minecraft:overworld"), AreaRules.hashOf(Stash.boundsOf(s.area), s.area.dimension));
    }

    @Test
    void aStaleWaitIsNotReportedOnceTheBotIsBackInTheAreasDimension() {
        Stash s = new Stash("s", "srv");
        s.area = new Stash.Area(new int[] {0, 60, 0}, new int[] {10, 70, 10}, "minecraft:overworld");
        long since = 1_000_000L, now = since + 600_000L;
        assertNotNull(Dimensions.waitReport(s, since, now, "the_nether"));
        assertNull(Dimensions.waitReport(s, since, now, "overworld"), "back in the right dimension: the old timer says nothing");
        s.area = null;
        assertNull(Dimensions.waitReport(s, since, now, "the_nether"));
    }

    @Test
    void aWaitForTheAreaIsReportedOnlyAfterTwoMinutes() {
        long since = 1_000_000L;
        assertNull(Dimensions.waitReport(0, since + 500_000, "minecraft:overworld", "the_nether"), "not waiting");
        assertNull(Dimensions.waitReport(since, since + 119_000, "minecraft:overworld", "the_nether"));
        String why = Dimensions.waitReport(since, since + 120_000, "minecraft:overworld", "the_nether");
        assertNotNull(why);
        assertTrue(why.contains("another dimension") && why.contains("minecraft:overworld") && why.contains("the_nether"), why);
    }
}
