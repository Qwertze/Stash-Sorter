package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sub-plan with the constants of a v2 block (CRAFT-CONTRACT-v1.2.md 7): maxDepth from the block, 12 estimate rounds, 200,000 calls. The table is the
 * test table plus three recipes written by hand here (hopper, minecart, hopper minecart), so the vectors D1 to D5 can be run. The v1 vectors are run again in
 * v2 mode: the contract says they should give the same results ("should", [UNVERIFIED, B1]).
 */
class CraftSubV2Test {
    private static final Recipes R = withHopper();
    private static final CraftSub.Limits V2 = CraftSub.Limits.v2(12);

    private static Recipes withHopper() {
        JsonObject root = JsonParser.parseString(TestRecipes.json()).getAsJsonObject();
        JsonArray recipes = root.getAsJsonArray("recipes");
        String iron = "[\"minecraft:iron_ingot\"]";
        // hopper: 5 iron ingots round a chest
        recipes.add(JsonParser.parseString("{\"id\":\"minecraft:hopper\",\"type\":\"shaped\",\"w\":3,\"h\":3,\"cells\":[" + iron + ",null," + iron + "," + iron
            + ",[\"minecraft:chest\"]," + iron + ",null," + iron + ",null],\"result\":\"minecraft:hopper\",\"count\":1}"));
        // minecart: 5 iron ingots in a trough
        recipes.add(JsonParser.parseString("{\"id\":\"minecraft:minecart\",\"type\":\"shaped\",\"w\":3,\"h\":2,\"cells\":[" + iron + ",null," + iron + "," + iron + "," + iron
            + "," + iron + "],\"result\":\"minecraft:minecart\",\"count\":1}"));
        // hopper minecart: the hopper over the minecart
        recipes.add(JsonParser.parseString("{\"id\":\"minecraft:hopper_minecart\",\"type\":\"shaped\",\"w\":1,\"h\":2,\"cells\":[[\"minecraft:hopper\"],[\"minecraft:minecart\"]],"
            + "\"result\":\"minecraft:hopper_minecart\",\"count\":1}"));
        return Recipes.fromJson(root);
    }

    private static Map<String, Long> pool(Object... kv) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put("minecraft:" + kv[i], ((Number) kv[i + 1]).longValue());
        return m;
    }

    private static CraftSub.Session session(CraftSub.Limits limits, Map<String, Long> p) {
        return new CraftSub.Session(R, null, p, p, Map.of(), limits);
    }

    private static CraftSub.Outcome row(CraftSub.Session s, String item, int need) {
        return s.planUpTo("minecraft:" + item, need);
    }

    private static long made(CraftSub.Outcome o) {
        return o.ok() ? o.plan().count() : 0;
    }

    private static Map<String, Integer> fillsByRecipe(CraftPlanner.Plan plan) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (CraftPlanner.Step s : plan.steps()) out.put(s.recipe().id().replace("minecraft:", ""), s.fills());
        return out;
    }

    // The constants

    @Test
    void theLimitsOfAV1AndAV2Block() {
        assertEquals(new CraftSub.Limits(4, 4, 50_000), CraftSub.Limits.V1);
        assertEquals(new CraftSub.Limits(12, 12, 200_000), CraftSub.Limits.v2(12));
        assertEquals(new CraftSub.Limits(1, 12, 200_000), CraftSub.Limits.v2(1));
        assertEquals(12, CraftSub.Limits.v2(99).maxDepth());
        assertEquals(CraftSub.Limits.V1, CraftSub.Limits.of(null));
        assertEquals(CraftSub.Limits.V1, CraftSub.Limits.of(new Order.Craft(1, "1.21.4", "", List.of(), List.of(), List.of(), List.of(), 0, false)));
        assertEquals(CraftSub.Limits.v2(7),
            CraftSub.Limits.of(new Order.Craft(2, "1.21.4", "", List.of(), List.of(), List.of(), List.of(), 0, false, List.of(), 7, null, List.of(), null)));
        assertEquals(4, CraftSub.MAX_DEPTH);
    }

    @Test
    void theLedgerPlansWithTheBlocksLimits() {
        Order.Craft v2 = new Order.Craft(2, "1.21.4", R.hash(), List.of(), List.of(), List.of(), List.of(), 0, false, List.of(), 1, null, List.of(), null);
        assertEquals(CraftSub.Limits.v2(1), new CraftLedger(v2, List.of(), R, 2048).limits());
        Order.Craft v1 = new Order.Craft(1, "1.21.4", R.hash(), List.of(), List.of(), List.of(), List.of(), 0, false);
        assertEquals(CraftSub.Limits.V1, new CraftLedger(v1, List.of(), R, 2048).limits());
    }

    @Test
    void theCallBudgetAndTheDepthAreTheLimitsOfTheSession() {
        CraftSub.Session tiny = session(new CraftSub.Limits(12, 12, 2), pool("spruce_log", 10));
        CraftSub.Outcome o = tiny.plan("minecraft:spruce_stairs", 8);
        assertFalse(o.ok());
        assertEquals(CraftSub.TOO_LARGE, o.plan().refusal());
        CraftSub.Session shallow = session(CraftSub.Limits.v2(1), pool("oak_log", 2));
        CraftSub.Outcome chest = shallow.plan("minecraft:chest", 1);
        assertFalse(chest.ok());
        assertTrue(chest.plan().refusal().contains("too deep"), chest.plan().refusal());
    }

    // D1 to D5

    @Test
    void d1_aHopperMinecartIsTooDeepInV1AndWorksInV2() {
        Map<String, Long> p = pool("bamboo", 36, "iron_ingot", 10);
        CraftSub.Outcome v1 = session(CraftSub.Limits.V1, p).plan("minecraft:hopper_minecart", 1);
        assertFalse(v1.ok());
        assertTrue(v1.plan().refusal().contains("too deep"), v1.plan().refusal());

        CraftSub.Session s = session(V2, p);
        CraftSub.Outcome o = s.plan("minecraft:hopper_minecart", 1);
        assertTrue(o.ok(), o.plan().refusal());
        assertEquals(Map.of("minecraft:bamboo", 36L, "minecraft:iron_ingot", 10L), o.plan().fromStock());
        assertEquals(Map.of("bamboo_block", 4, "bamboo_planks", 4, "chest", 1, "hopper", 1, "minecart", 1, "hopper_minecart", 1), fillsByRecipe(o.plan()));
        assertEquals(12, o.plan().fills());
        assertTrue(s.surplus().isEmpty(), s.surplus().toString());
    }

    @Test
    void d2_aChestFromEightPlanksNeedsOnlyDepthOne() {
        CraftSub.Session s = session(CraftSub.Limits.v2(1), pool("oak_planks", 8));
        CraftSub.Outcome o = s.plan("minecraft:chest", 1);
        assertTrue(o.ok(), o.plan().refusal());
        assertEquals(Map.of("minecraft:oak_planks", 8L), o.plan().fromStock());
        assertEquals(1, o.plan().fills());
        assertFalse(session(CraftSub.Limits.v2(1), pool("oak_log", 2)).plan("minecraft:chest", 1).ok());
    }

    @Test
    void d3_anIronBlockFromNuggetsSkipsTheIngotFromBlockLoop() {
        CraftSub.Session s = session(V2, pool("iron_nugget", 81));
        CraftSub.Outcome o = s.plan("minecraft:iron_block", 1);
        assertTrue(o.ok(), o.plan().refusal());
        assertEquals(Map.of("minecraft:iron_nugget", 81L), o.plan().fromStock());
        assertEquals(Map.of("iron_ingot_from_nuggets", 9, "iron_block", 1), fillsByRecipe(o.plan()));
        assertTrue(s.surplus().isEmpty(), s.surplus().toString());
    }

    @Test
    void d4_eighteenNuggetsFromABlockLeaveSevenIngots() {
        CraftSub.Session s = session(V2, pool("iron_block", 1));
        CraftSub.Outcome o = s.plan("minecraft:iron_nugget", 18);
        assertTrue(o.ok(), o.plan().refusal());
        assertEquals(Map.of("minecraft:iron_block", 1L), o.plan().fromStock());
        assertEquals(Map.of("iron_ingot_from_iron_block", 1, "iron_nugget", 2), fillsByRecipe(o.plan()));
        assertEquals(Map.of("minecraft:iron_ingot", 7L), s.surplus());
        for (CraftPlanner.Step st : o.plan().steps()) assertFalse(st.recipe().id().equals("minecraft:iron_block"));
    }

    @Test
    void d5_eightSpruceStairsOfWhichThreeAreInTheStash() {
        // The split is the server's: item 3 + craft 5. The bot plans the 5.
        CraftSub.Session s = session(V2, pool("spruce_log", 10));
        CraftSub.Outcome o = s.plan("minecraft:spruce_stairs", 5);
        assertTrue(o.ok(), o.plan().refusal());
        assertEquals(Map.of("minecraft:spruce_log", 3L), o.plan().fromStock());
        assertEquals(Map.of("spruce_planks", 3, "spruce_stairs", 2), fillsByRecipe(o.plan()));
        assertEquals(5, o.plan().fills());
        assertEquals(Map.of("minecraft:spruce_stairs", 3L), s.surplus());
    }

    // The v1 vectors in v2 mode (the contract: they should give the same results)

    @Test
    void vectors1To3GiveTheSameResultsInV2Mode() {
        CraftSub.Session s = session(V2, pool("spruce_log", 10));
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
        List<CraftPlanner.Step> steps = a.plan().steps();
        assertEquals("minecraft:spruce_planks", steps.get(0).recipe().id());
        assertEquals(3, steps.get(0).fills());

        CraftSub.Session two = session(V2, pool("spruce_log", 4));
        assertEquals(8, made(row(two, "spruce_stairs", 8)));
        assertEquals(6, made(row(two, "spruce_slab", 6)));
        CraftSub.Outcome fence = row(two, "spruce_fence", 3);
        assertEquals(0, made(fence));
        assertTrue(fence.plan().refusal().contains("not enough Spruce Log"), fence.plan().refusal());
        assertEquals(Map.of("minecraft:spruce_planks", 1L), two.surplus());

        CraftSub.Session three = session(V2, pool("spruce_log", 2));
        CraftSub.Outcome half = row(three, "spruce_stairs", 8);
        assertEquals(4, made(half));
        assertEquals(Map.of("minecraft:spruce_log", 2L), half.plan().fromStock());
        assertEquals(Map.of("minecraft:spruce_planks", 2L), three.surplus());
    }

    @Test
    void vectors5to7And11GiveTheSameResultsInV2Mode() {
        CraftSub.Session a = session(V2, pool("oak_planks", 4, "bamboo", 14));
        CraftSub.Outcome ladders = row(a, "ladder", 3);
        assertEquals(3, made(ladders));
        assertEquals(Map.of("minecraft:oak_planks", 4L), ladders.plan().fromStock());
        assertEquals(Map.of("minecraft:stick", 1L), a.surplus());

        Map<String, Long> p2 = pool("oak_planks", 2, "bamboo", 14);
        CraftSub.Session b = new CraftSub.Session(R, java.util.Set.of("minecraft:ladder", "minecraft:stick", "minecraft:stick_from_bamboo_item"), p2, p2, Map.of(), V2);
        CraftSub.Outcome viaBamboo = row(b, "ladder", 3);
        assertEquals(3, made(viaBamboo));
        assertEquals(Map.of("minecraft:bamboo", 14L), viaBamboo.plan().fromStock());
        assertTrue(b.surplus().isEmpty(), b.surplus().toString());

        CraftSub.Session fromBlock = session(V2, pool("iron_block", 1));
        CraftSub.Outcome bars = row(fromBlock, "iron_bars", 16);
        assertEquals(16, made(bars));
        assertEquals(Map.of("minecraft:iron_block", 1L), bars.plan().fromStock());
        assertEquals(Map.of("minecraft:iron_ingot", 3L), fromBlock.surplus());
        CraftSub.Session fromNuggets = session(V2, pool("iron_nugget", 54));
        CraftSub.Outcome bars2 = row(fromNuggets, "iron_bars", 16);
        assertEquals(16, made(bars2));
        assertEquals(Map.of("minecraft:iron_nugget", 54L), bars2.plan().fromStock());
        assertTrue(fromNuggets.surplus().isEmpty());

        CraftSub.Outcome bed = row(session(V2, pool("red_bed", 1, "white_dye", 1)), "white_bed", 1);
        assertEquals(0, made(bed));
        assertTrue(bed.plan().refusal().contains("White Wool"), bed.plan().refusal());

        CraftSub.Session chest = session(V2, pool("oak_log", 1, "birch_log", 1));
        CraftSub.Outcome c = row(chest, "chest", 1);
        assertEquals(1, made(c), String.valueOf(c.plan().refusal()));
        assertEquals(Map.of("minecraft:birch_log", 1L, "minecraft:oak_log", 1L), c.plan().fromStock());
        assertTrue(chest.surplus().isEmpty(), chest.surplus().toString());
    }

    @Test
    void planCallsAreRepeatableAcrossSessions() {
        List<String> first = new ArrayList<>(), second = new ArrayList<>();
        for (List<String> out : List.of(first, second)) {
            CraftSub.Outcome o = session(V2, pool("bamboo", 36, "iron_ingot", 10)).plan("minecraft:hopper_minecart", 1);
            for (CraftPlanner.Step st : o.plan().steps()) out.add(st.recipe().id() + "x" + st.fills());
        }
        assertEquals(first, second);
    }
}
