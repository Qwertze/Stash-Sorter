package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sub-plan runs the server planner's algorithm: the test vectors of CRAFT-CONTRACT.md ("Test vectors", 1.21.4 recipes), pool in, takes and leftovers out. */
class CraftSubTest {
    private static final Recipes R = TestRecipes.table();

    private static Map<String, Long> pool(Object... kv) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put("minecraft:" + kv[i], ((Number) kv[i + 1]).longValue());
        return m;
    }

    private static CraftSub.Session session(Map<String, Long> p) {
        return new CraftSub.Session(R, null, p, p, Map.of());
    }

    /** What the server does for one row: the most that can be made, up to the target. */
    private static CraftSub.Outcome row(CraftSub.Session s, String item, int need) {
        return s.planUpTo("minecraft:" + item, need);
    }

    private static long made(CraftSub.Outcome o) {
        return o.ok() ? o.plan().count() : 0;
    }

    @Test
    void vector1_stairsSlabsAndFencesFromTenLogsShareTheirPlanks() {
        CraftSub.Session s = session(pool("spruce_log", 10));
        CraftSub.Outcome a = row(s, "spruce_stairs", 8), b = row(s, "spruce_slab", 6), c = row(s, "spruce_fence", 3);
        assertTrue(a.ok() && b.ok() && c.ok());
        Map<String, Long> take = new HashMap<>();
        int fills = 0;
        for (CraftSub.Outcome o : List.of(a, b, c)) {
            o.plan().fromStock().forEach((k, v) -> take.merge(k, v, Long::sum));
            fills += o.plan().fills();
        }
        assertEquals(Map.of("minecraft:spruce_log", 6L), take);
        assertEquals(11, fills);
        assertEquals(Map.of("minecraft:spruce_planks", 3L, "minecraft:stick", 2L), s.surplus());
    }

    @Test
    void vector1_theStepsAreListedIngredientsFirst() {
        CraftSub.Session s = session(pool("spruce_log", 10));
        CraftSub.Outcome a = row(s, "spruce_stairs", 8);
        List<CraftPlanner.Step> steps = a.plan().steps();
        assertEquals("minecraft:spruce_planks", steps.get(0).recipe().id());
        // 8 stairs are 12 planks: three fills of four
        assertEquals(3, steps.get(0).fills());
        assertEquals("minecraft:spruce_stairs", steps.get(steps.size() - 1).recipe().id());
        assertEquals(2, steps.get(steps.size() - 1).fills());
    }

    @Test
    void vector2_fourLogsMakeStairsAndSlabsButNotTheFence() {
        CraftSub.Session s = session(pool("spruce_log", 4));
        CraftSub.Outcome a = row(s, "spruce_stairs", 8), b = row(s, "spruce_slab", 6), c = row(s, "spruce_fence", 3);
        assertEquals(8, made(a));
        assertEquals(6, made(b));
        assertEquals(0, made(c));
        assertTrue(c.plan().refusal().contains("not enough Spruce Log"), c.plan().refusal());
        // Four logs are 16 planks: stairs 12, slab 3 (leaving 1)
        assertEquals(Map.of("minecraft:spruce_planks", 1L), s.surplus());
    }

    @Test
    void vector3_twoLogsMakeFourStairsOfEight() {
        CraftSub.Session s = session(pool("spruce_log", 2));
        CraftSub.Outcome a = row(s, "spruce_stairs", 8);
        assertEquals(4, made(a));
        assertEquals(Map.of("minecraft:spruce_log", 2L), a.plan().fromStock());
        assertEquals(Map.of("minecraft:spruce_planks", 2L), s.surplus());
    }

    @Test
    void vector5_planksAreTheCheaperWayToSticks_andFallBackToBamboo() {
        // 5a: oak planks 4, bamboo 14 -> ladders from sticks made of the planks (cost 0.5 against 2)
        CraftSub.Session a = session(pool("oak_planks", 4, "bamboo", 14));
        CraftSub.Outcome ladders = row(a, "ladder", 3);
        assertEquals(3, made(ladders));
        assertEquals(Map.of("minecraft:oak_planks", 4L), ladders.plan().fromStock());
        assertEquals(Map.of("minecraft:stick", 1L), a.surplus());
        // 5b: oak planks 2: the planks route fails and is restored, the bamboo route works (the recipes of the server's plan: ladder, sticks from
        // planks, sticks from bamboo)
        Map<String, Long> p2 = pool("oak_planks", 2, "bamboo", 14);
        CraftSub.Session b = new CraftSub.Session(R, Set.of("minecraft:ladder", "minecraft:stick", "minecraft:stick_from_bamboo_item"), p2, p2, Map.of());
        CraftSub.Outcome viaBamboo = row(b, "ladder", 3);
        assertEquals(3, made(viaBamboo));
        assertEquals(Map.of("minecraft:bamboo", 14L), viaBamboo.plan().fromStock());
        assertTrue(b.surplus().isEmpty(), b.surplus().toString());
    }

    @Test
    void vector6_ironBarsFromABlockOrFromNuggets() {
        CraftSub.Session a = session(pool("iron_block", 1));
        CraftSub.Outcome fromBlock = row(a, "iron_bars", 16);
        assertEquals(16, made(fromBlock));
        assertEquals(Map.of("minecraft:iron_block", 1L), fromBlock.plan().fromStock());
        assertEquals(Map.of("minecraft:iron_ingot", 3L), a.surplus());
        CraftSub.Session b = session(pool("iron_nugget", 54));
        CraftSub.Outcome fromNuggets = row(b, "iron_bars", 16);
        assertEquals(16, made(fromNuggets));
        assertEquals(Map.of("minecraft:iron_nugget", 54L), fromNuggets.plan().fromStock());
        assertTrue(b.surplus().isEmpty());
    }

    @Test
    void vector7_aBedIsNotRecoloured() {
        CraftSub.Session s = session(pool("red_bed", 1, "white_dye", 1));
        CraftSub.Outcome o = row(s, "white_bed", 1);
        assertEquals(0, made(o));
        assertTrue(o.plan().refusal().contains("White Wool"), o.plan().refusal());
    }

    @Test
    void vector11_aChestFromOneOakLogAndOneBirchLogSplitsItsPlanks() {
        CraftSub.Session s = session(pool("oak_log", 1, "birch_log", 1));
        CraftSub.Outcome o = row(s, "chest", 1);
        assertEquals(1, made(o), String.valueOf(o.plan().refusal()));
        assertEquals(Map.of("minecraft:birch_log", 1L, "minecraft:oak_log", 1L), o.plan().fromStock());
        assertTrue(s.surplus().isEmpty(), s.surplus().toString());
    }

    @Test
    void onlyTheRecipesOfTheOrdersStepsAreUsed() {
        // The same logs, but a plan that names only the oak planks recipe: the birch route is not allowed
        Map<String, Long> p = pool("oak_log", 1, "birch_log", 1);
        CraftSub.Session s = new CraftSub.Session(R, Set.of("minecraft:oak_planks", "minecraft:chest"), p, p, Map.of());
        CraftSub.Outcome o = s.plan("minecraft:chest", 1);
        assertFalse(o.ok());
        assertTrue(o.plan().refusal().toLowerCase().contains("plank") || o.plan().refusal().toLowerCase().contains("log"), o.plan().refusal());
    }

    @Test
    void theBudgetIsNeverExceeded() {
        Map<String, Long> p = pool("spruce_log", 1);
        CraftSub.Session s = new CraftSub.Session(R, null, p, p, Map.of());
        CraftSub.Outcome o = s.plan("minecraft:spruce_fence", 3);
        assertFalse(o.ok());
        // Nothing changed by the failed try
        assertEquals(Map.of("minecraft:spruce_log", 1L), s.pool());
        assertTrue(s.surplus().isEmpty());
    }

    @Test
    void carriedSurplusIsUsedBeforeThePool() {
        Map<String, Long> start = pool("spruce_log", 10);
        CraftSub.Session s = new CraftSub.Session(R, null, start, pool("spruce_log", 10), pool("spruce_planks", 8));
        CraftSub.Outcome o = s.plan("minecraft:spruce_stairs", 8);
        assertTrue(o.ok());
        // 8 stairs need 12 planks: 8 carried, 4 from one log
        assertEquals(Map.of("minecraft:spruce_log", 1L), o.plan().fromStock());
        assertTrue(s.surplus().isEmpty() || s.surplus().values().stream().allMatch(v -> v >= 0));
    }

    @Test
    void depthIsFourLevelsBelowTheItem() {
        assertEquals(4, CraftSub.MAX_DEPTH);
    }
}
