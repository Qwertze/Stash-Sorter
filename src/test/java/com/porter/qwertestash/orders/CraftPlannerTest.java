package com.porter.qwertestash.orders;

import com.aquarius.mc.block.BlockPos;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.Stash.Box;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What crafting a shulker box takes, worked out from the stash's pool: steps, aggregation, refusals with reasons, caps. */
class CraftPlannerTest {
    static final String BOX = "minecraft:shulker_box", SHELL = "minecraft:shulker_shell", CHEST = "minecraft:chest",
        OAK = "minecraft:oak_planks", BIRCH = "minecraft:birch_planks", LOG = "minecraft:oak_log";
    private final Recipes r = TestRecipes.table();

    static Map<String, Long> pool(Object... kv) {
        Map<String, Long> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], ((Number) kv[i + 1]).longValue());
        return m;
    }

    private CraftPlanner.Plan plan(int n, Map<String, Long> pool) {
        return CraftPlanner.plan(r, BOX, n, pool, CraftPlanner.Limits.open());
    }

    @Test
    void oneStepFromShellsAndAChest() {
        CraftPlanner.Plan p = plan(1, pool(SHELL, 5, CHEST, 3));
        assertTrue(p.ok(), p.refusal());
        assertEquals(1, p.steps().size());
        assertEquals(BOX, p.steps().get(0).recipe().result());
        assertEquals(Map.of(SHELL, 2L, CHEST, 1L), p.fromStock());
        assertEquals(1, p.fills());
    }

    @Test
    void threeStepsFromLogs() {
        CraftPlanner.Plan p = plan(1, pool(SHELL, 2, LOG, 10));
        assertTrue(p.ok(), p.refusal());
        assertEquals(List.of(OAK, CHEST, BOX), p.steps().stream().map(s -> s.recipe().result()).toList(), "made deepest first");
        // A chest is 8 planks: two fills of one log each
        assertEquals(2, p.steps().get(0).fills());
        assertEquals(Map.of(SHELL, 2L, LOG, 2L), p.fromStock());
        assertEquals(4, p.fills());
        String said = CraftPlanner.describe(p);
        assertTrue(said.startsWith("1 Shulker Box from 2 Shulker Shell + 1 Chest; 1 Chest from 8 Oak Planks; 8 Oak Planks from 2 Oak Log"), said);
    }

    @Test
    void needsAreAddedUpBeforeTheyAreRounded() {
        // 2 shulker boxes: 2 chests, 16 planks, 4 logs (not rounded per chest)
        CraftPlanner.Plan p = plan(2, pool(SHELL, 4, LOG, 64));
        assertTrue(p.ok(), p.refusal());
        assertEquals(4L, p.fromStock().get(LOG));
        assertEquals(16L, p.steps().get(0).made());
        assertEquals(2, p.steps().get(1).fills());
        // One box from logs: 8 planks are 2 logs
        assertEquals(2L, plan(1, pool(SHELL, 4, LOG, 64)).fromStock().get(LOG));
        // Planks in stock are used first: 3 oak planks leave 13, made from 4 logs
        CraftPlanner.Plan some = plan(2, pool(SHELL, 4, LOG, 64, OAK, 3));
        assertEquals(3L, some.fromStock().get(OAK));
        assertEquals(4L, some.fromStock().get(LOG));
    }

    @Test
    void thePlentifulKindIsUsedFirst() {
        CraftPlanner.Plan p = plan(1, pool(SHELL, 2, OAK, 3, BIRCH, 20));
        assertTrue(p.ok(), p.refusal());
        assertEquals(Map.of(SHELL, 2L, BIRCH, 8L), p.fromStock());
        // Mixed kinds when no one kind has enough
        CraftPlanner.Plan mixed = plan(1, pool(SHELL, 2, OAK, 5, BIRCH, 4));
        assertTrue(mixed.ok(), mixed.refusal());
        assertEquals(5L, mixed.fromStock().get(OAK));
        assertEquals(3L, mixed.fromStock().get(BIRCH));
    }

    @Test
    void whatIsMissingSaysWhy() {
        CraftPlanner.Plan noShells = plan(1, pool(SHELL, 1, CHEST, 1));
        assertFalse(noShells.ok());
        assertTrue(noShells.refusal().contains(CraftPlanner.SHELLS), noShells.refusal());
        CraftPlanner.Plan noWood = plan(1, pool(SHELL, 2));
        assertFalse(noWood.ok());
        assertTrue(noWood.refusal().startsWith("not enough planks"), noWood.refusal());
        assertTrue(noWood.steps().isEmpty());
    }

    @Test
    void withoutATableNothingIsPlanned() {
        CraftPlanner.Plan p = CraftPlanner.plan(r, BOX, 1, pool(SHELL, 2, CHEST, 1), new CraftPlanner.Limits(false, 128, 27, 54, Set.of()));
        assertEquals(CraftPlanner.NO_TABLE, p.refusal());
        assertEquals(0, CraftPlanner.maxCraftable(r, BOX, 5, pool(SHELL, 20, CHEST, 10), new CraftPlanner.Limits(false, 128, 27, 54, Set.of())));
    }

    @Test
    void capsAreKept() {
        Map<String, Long> lots = pool(SHELL, 64, LOG, 64);
        assertTrue(CraftPlanner.plan(r, BOX, 2, lots, new CraftPlanner.Limits(true, 128, 1, 54, Set.of())).refusal().startsWith("cap reached"));
        assertTrue(CraftPlanner.plan(r, BOX, 2, lots, new CraftPlanner.Limits(true, 128, 27, 1, Set.of())).refusal().startsWith("cap reached"));
        // A box from logs takes 4 grid fills
        assertTrue(CraftPlanner.plan(r, BOX, 1, lots, new CraftPlanner.Limits(true, 3, 27, 54, Set.of())).refusal().startsWith("cap reached"));
        assertTrue(CraftPlanner.plan(r, BOX, 1, lots, new CraftPlanner.Limits(true, 4, 27, 54, Set.of())).ok());
        assertEquals(5, CraftPlanner.maxCraftable(r, BOX, 30, lots, new CraftPlanner.Limits(true, 128, 5, 54, Set.of())));
        // 64 shells make 32; 64 logs make 32 chests too
        assertEquals(32, CraftPlanner.maxCraftable(r, BOX, 54, lots, CraftPlanner.Limits.open()));
        assertEquals(3, CraftPlanner.maxCraftable(r, BOX, 54, pool(SHELL, 7, CHEST, 10), CraftPlanner.Limits.open()));
    }

    @Test
    void theNeverUseListIsNotTakenFrom() {
        CraftPlanner.Plan p = CraftPlanner.plan(r, BOX, 1, pool(SHELL, 2, CHEST, 1, LOG, 2), new CraftPlanner.Limits(true, 128, 27, 54, Set.of("chest")));
        assertTrue(p.ok(), p.refusal());
        assertNull(p.fromStock().get(CHEST));
        assertEquals(2L, p.fromStock().get(LOG));
    }

    @Test
    void aRecipeLoopAndTooDeepAreRefused() {
        String json = "{\"version\":\"t\",\"recipes\":["
            + "{\"id\":\"t:a\",\"type\":\"shapeless\",\"ingredients\":[[\"t:b\"]],\"result\":\"t:a\",\"count\":1},"
            + "{\"id\":\"t:b\",\"type\":\"shapeless\",\"ingredients\":[[\"t:a\"]],\"result\":\"t:b\",\"count\":1},"
            + "{\"id\":\"t:c\",\"type\":\"shapeless\",\"ingredients\":[[\"t:d\"]],\"result\":\"t:c\",\"count\":1},"
            + "{\"id\":\"t:d\",\"type\":\"shapeless\",\"ingredients\":[[\"t:e\"]],\"result\":\"t:d\",\"count\":1},"
            + "{\"id\":\"t:e\",\"type\":\"shapeless\",\"ingredients\":[[\"t:f\"]],\"result\":\"t:e\",\"count\":1},"
            + "{\"id\":\"t:f\",\"type\":\"shapeless\",\"ingredients\":[[\"t:g\"]],\"result\":\"t:f\",\"count\":1}"
            + "],\"otherStations\":{}}";
        Recipes loop = Recipes.parse(new StringReader(json));
        CraftPlanner.Plan p = CraftPlanner.plan(loop, "t:a", 1, Map.of(), CraftPlanner.Limits.open());
        assertFalse(p.ok());
        assertTrue(p.refusal().contains("loop"), p.refusal());
        CraftPlanner.Plan deep = CraftPlanner.plan(loop, "t:c", 1, Map.of("t:g", 5L), CraftPlanner.Limits.open());
        assertFalse(deep.ok());
        assertTrue(deep.refusal().contains(CraftPlanner.TOO_DEEP), deep.refusal());
        // Within three steps it is fine
        assertTrue(CraftPlanner.plan(loop, "t:d", 1, Map.of("t:g", 5L), CraftPlanner.Limits.open()).ok());
    }

    // What the stash may give crafting

    static Item stack(String id, int count, int max) {
        Item it = new Item();
        it.id = id;
        it.name = Item.prettyName(id);
        it.count = count;
        it.maxStack = max;
        it.key = "minecraft:" + id;
        it.variant = it.key;
        return it;
    }

    private static Box box(int x, Item... items) {
        BlockPos p = new BlockPos(x, 64, 0);
        Box b = new Box(p, new ArrayList<>(List.of(p)));
        b.contents = new Item[27];
        for (int i = 0; i < items.length; i++) b.contents[i] = items[i];
        return b;
    }

    private static Item shulker(Item... in) {
        Item s = stack("red_shulker_box", 1, 1);
        for (int i = 0; i < in.length; i++) {
            in[i].slot = i;
            s.contents.add(in[i]);
        }
        return s;
    }

    @Test
    void namedAndEnchantedStacksMayBeUsedButDamagedAndFilledOnesNever() {
        Set<String> none = Set.of();
        Item named = stack("oak_log", 10, 64);
        named.customName = true;
        named.customText = "Logs";
        named.key = "minecraft:oak_log#00ff00ff";
        assertNull(SourceRules.craftRefusal(named, none));
        Item enchanted = stack("chest", 1, 64);
        enchanted.enchants.add("unbreaking");
        assertNull(SourceRules.craftRefusal(enchanted, none));
        Item damaged = stack("oak_log", 1, 64);
        damaged.damaged = true;
        damaged.key = "minecraft:oak_log#dddddddd";
        assertEquals("damaged", SourceRules.craftRefusal(damaged, none));
        Item full = stack("chest", 1, 64);
        full.contents.add(stack("diamond", 3, 64));
        assertEquals("it holds something", SourceRules.craftRefusal(full, none));
        Item entity = stack("chest", 1, 64);
        entity.blockData = true;
        assertEquals("it holds something", SourceRules.craftRefusal(entity, none));
        Item bundle = stack("bundle", 1, 1);
        bundle.bundleStacks = 2;
        assertEquals("it holds something", SourceRules.craftRefusal(bundle, none));
        assertTrue(SourceRules.craftRefusal(stack("shulker_box", 1, 1), none).contains("never"));
        assertEquals("on the never-use list", SourceRules.craftRefusal(stack("oak_log", 1, 64), Set.of("minecraft:oak_log")));

        // The survey counts the named stack under its base id; the damaged and filled ones not at all
        Box b = box(0, named, damaged, full, entity, stack("shulker_shell", 4, 64));
        CraftPool.Stock s = SourceRules.craftSurvey(List.of(b), Set.of(LOG, CHEST, SHELL), Set.of(), true, none, new SourceRulesTest.FakeLive());
        Map<String, Long> byBase = CraftPool.residual(s, List.of());
        assertEquals(10L, byBase.get(LOG));
        assertEquals(4L, byBase.get(SHELL));
        assertNull(byBase.get(CHEST));
        // And the load to gather is the named stack's own key, so the run takes that very stack
        assertEquals(List.of(new Order.Lot("minecraft:oak_log#00ff00ff", 2)), CraftPool.lots(s, Map.of(LOG, 2L), List.of()));
    }

    @Test
    void otherLinesKeepWhatTheyNeed() {
        Box b = box(0, stack("oak_log", 3, 64), stack("shulker_shell", 2, 64));
        Box inside = box(1, shulker(stack("oak_log", 2, 64)));
        CraftPool.Stock s = SourceRules.craftSurvey(List.of(b, inside), Set.of(LOG, SHELL), Set.of(), true, Set.of(), new SourceRulesTest.FakeLive());
        assertEquals(5L, CraftPool.residual(s, List.of()).get(LOG));
        // A bin of the order takes 4 logs: one is left, too few for a chest (2 logs)
        Unit bin = new Unit(Unit.Kind.Bin, "", new ArrayList<>(List.of(new Order.Lot(LOG, 4))));
        Map<String, Long> left = CraftPool.residual(s, List.of(bin));
        assertEquals(1L, left.get(LOG));
        assertFalse(CraftPlanner.plan(r, BOX, 1, left, CraftPlanner.Limits.open()).ok());
        assertTrue(CraftPlanner.plan(r, BOX, 1, CraftPool.residual(s, List.of()), CraftPlanner.Limits.open()).ok());
        // What lies in shulkers is gathered after the loose stacks
        assertEquals(List.of(new Order.Lot(LOG, 3), new Order.Lot(LOG, 1)), CraftPool.lots(s, Map.of(LOG, 4L), List.of()));
    }
}
