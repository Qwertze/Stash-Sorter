package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Packing an order into shulkers: the protocol's order of loads, 27-slot bins, the 54 shulker cap, stack sizes, one variant per slot. */
class OrderPackingTest {
    private static StockPlanner.Survey survey(Map<String, Long> available, Map<String, Integer> max, Map<String, List<StockPlanner.Var>> variants) {
        return new StockPlanner.Survey(available, max, Set.of(), Set.of(), variants, Map.of());
    }

    private static Order order(Order.Line... lines) {
        return new Order("o-test", List.of(lines));
    }

    private static String name(String key) {
        return key;
    }

    @Test
    void expandSplitsIntoFullStacksAndRemainder() {
        List<Order.Lot> lots = Order.expand("minecraft:diamond", 150, 64);
        assertEquals(List.of(64, 64, 22), lots.stream().map(Order.Lot::count).toList());
        assertEquals(List.of(16, 16, 8), Order.expand("minecraft:ender_pearl", 40, 16).stream().map(Order.Lot::count).toList());
        assertEquals(List.of(1, 1, 1), Order.expand("minecraft:diamond_sword", 3, 1).stream().map(Order.Lot::count).toList());
    }

    @Test
    void packFillsBinsOf27InTheProtocolOrder() {
        // 30 loads of b (most loads, first), then 2 of a and 1 of c: keys ascending break ties, groups by number of loads
        List<Order.Lot> lots = new ArrayList<>();
        lots.addAll(Order.expand("minecraft:c", 10, 64));
        lots.addAll(Order.expand("minecraft:a", 70, 64));
        lots.addAll(Order.expand("minecraft:b", 30 * 64, 64));
        List<List<Order.Lot>> bins = Order.pack(lots);
        assertEquals(2, bins.size());
        assertEquals(27, bins.get(0).size());
        assertEquals(6, bins.get(1).size());
        // b has the most loads, so it is first; within a, the larger load first
        assertEquals("minecraft:b", bins.get(0).get(0).key());
        assertEquals("minecraft:b", bins.get(0).get(26).key());
        assertEquals(List.of("minecraft:b", "minecraft:b", "minecraft:b", "minecraft:a", "minecraft:a", "minecraft:c"),
            bins.get(1).stream().map(Order.Lot::key).toList());
        assertEquals(64, bins.get(1).get(3).count());
        assertEquals(6, bins.get(1).get(4).count());
    }

    @Test
    void binsAreCeilOfLoadsOver27() {
        for (int loads : new int[] {1, 26, 27, 28, 54, 55, 100}) {
            List<List<Order.Lot>> bins = Order.pack(Order.expand("minecraft:stone", loads * 64, 64));
            assertEquals((loads + 26) / 27, bins.size(), "loads " + loads);
            for (List<Order.Lot> bin : bins) assertTrue(bin.size() <= Order.SLOTS);
        }
    }

    @Test
    void mixedItemsShareAShulker() {
        List<Order.Lot> lots = new ArrayList<>();
        lots.addAll(Order.expand("minecraft:diamond", 128, 64));
        lots.addAll(Order.expand("minecraft:ender_pearl", 32, 16));
        lots.addAll(Order.expand("minecraft:totem_of_undying", 5, 1));
        List<List<Order.Lot>> bins = Order.pack(lots);
        assertEquals(1, bins.size());
        assertEquals(9, bins.get(0).size());
    }

    @Test
    void anOrderOf54ShulkersIsTheLimit() {
        long loads = 54L * 27;
        Order o = order(new Order.Line("item", "minecraft:stone", (int) (loads * 64)));
        StockPlanner.Plan plan = StockPlanner.plan(o, survey(Map.of("minecraft:stone", loads * 64),
            Map.of("minecraft:stone", 64), Map.of("minecraft:stone", List.of(new StockPlanner.Var("minecraft:stone", loads * 64)))), Map.of(), OrderPackingTest::name);
        assertNull(plan.failure());
        assertEquals(54, plan.units().size());

        long over = loads + 1;
        Order big = order(new Order.Line("item", "minecraft:stone", (int) (over * 64)));
        StockPlanner.Plan tooBig = StockPlanner.plan(big, survey(Map.of("minecraft:stone", over * 64),
            Map.of("minecraft:stone", 64), Map.of("minecraft:stone", List.of(new StockPlanner.Var("minecraft:stone", over * 64)))), Map.of(), OrderPackingTest::name);
        assertNotNull(tooBig.failure());
        assertTrue(tooBig.failure().contains("54"));
    }

    @Test
    void shulkerAndEmptyLinesCountTowardTheCap() {
        Order o = order(new Order.Line("shulker", "s:abcdef0123", 53), new Order.Line("item", "minecraft:red_shulker_box", 2));
        StockPlanner.Plan plan = StockPlanner.plan(o, survey(Map.of(), Map.of(), Map.of()), Map.of("minecraft:red_shulker_box", 2), OrderPackingTest::name);
        assertNotNull(plan.failure());
        assertTrue(plan.failure().contains("54"));

        Order fits = order(new Order.Line("shulker", "s:abcdef0123", 52), new Order.Line("item", "minecraft:red_shulker_box", 2));
        StockPlanner.Plan ok = StockPlanner.plan(fits, survey(Map.of(), Map.of(), Map.of()), Map.of("minecraft:red_shulker_box", 2), OrderPackingTest::name);
        assertNull(ok.failure());
        assertEquals(54, ok.units().size());
        // Shulker lines first, then empties, then bins
        assertEquals(Unit.Kind.Shulker, ok.units().get(0).kind);
        assertEquals(Unit.Kind.Empty, ok.units().get(52).kind);
    }

    @Test
    void stackSizesFollowTheItem() {
        Order o = order(new Order.Line("item", "minecraft:ender_pearl", 40), new Order.Line("item", "minecraft:diamond_sword", 3), new Order.Line("item", "minecraft:oak_log", 70));
        Map<String, Long> avail = Map.of("minecraft:ender_pearl", 100L, "minecraft:diamond_sword", 5L, "minecraft:oak_log", 500L);
        Map<String, Integer> max = Map.of("minecraft:ender_pearl", 16, "minecraft:diamond_sword", 1, "minecraft:oak_log", 64);
        Map<String, List<StockPlanner.Var>> vars = Map.of(
            "minecraft:ender_pearl", List.of(new StockPlanner.Var("minecraft:ender_pearl", 100)),
            "minecraft:diamond_sword", List.of(new StockPlanner.Var("minecraft:diamond_sword", 5)),
            "minecraft:oak_log", List.of(new StockPlanner.Var("minecraft:oak_log", 500)));
        StockPlanner.Plan plan = StockPlanner.plan(o, survey(avail, max, vars), Map.of(), OrderPackingTest::name);
        assertNull(plan.failure());
        assertEquals(1, plan.units().size());
        Map<String, List<Integer>> counts = new HashMap<>();
        for (Order.Lot lot : plan.units().get(0).lots) counts.computeIfAbsent(lot.key(), k -> new ArrayList<>()).add(lot.count());
        assertEquals(List.of(16, 16, 8), counts.get("minecraft:ender_pearl"));
        assertEquals(List.of(1, 1, 1), counts.get("minecraft:diamond_sword"));
        assertEquals(List.of(64, 6), counts.get("minecraft:oak_log"));
    }

    @Test
    void aSlotHoldsOneVariantAndALineMaySplitAcrossVariants() {
        // 100 diamonds: 60 of one variant, 40 of another (ViaVersion's custom_data, say). Raw variants can't stack
        StockPlanner.Var a = new StockPlanner.Var("minecraft:diamond#aaaaaaaaaaaaaaaa", 60), b = new StockPlanner.Var("minecraft:diamond#bbbbbbbbbbbbbbbb", 40);
        List<StockPlanner.Var> vars = List.of(a, b);
        Order o = order(new Order.Line("item", "minecraft:diamond", 100));
        StockPlanner.Plan plan = StockPlanner.plan(o, survey(Map.of("minecraft:diamond", 100L), Map.of("minecraft:diamond", 64), Map.of("minecraft:diamond", vars)), Map.of(), OrderPackingTest::name);
        assertNull(plan.failure());
        List<Order.Lot> lots = plan.units().get(0).lots;
        long total = lots.stream().mapToLong(Order.Lot::count).sum();
        assertEquals(100, total);
        // Every lot names exactly one variant, and never takes more of it than there is
        Map<String, Long> perVariant = new HashMap<>();
        for (Order.Lot lot : lots) {
            assertNotNull(lot.variant());
            assertTrue(lot.count() <= 64);
            perVariant.merge(lot.variant(), (long) lot.count(), Long::sum);
        }
        assertTrue(perVariant.get(a.variant) <= 60);
        assertTrue(perVariant.get(b.variant) <= 40);
        assertEquals(100, perVariant.values().stream().mapToLong(Long::longValue).sum());
    }

    private static StockPlanner.Survey withShulkers(long loose, long inside) {
        Map<String, List<StockPlanner.Var>> lv = loose > 0 ? Map.of("minecraft:diamond", List.of(new StockPlanner.Var("v", loose))) : Map.of();
        Map<String, List<StockPlanner.Var>> sv = inside > 0 ? Map.of("minecraft:diamond", List.of(new StockPlanner.Var("v", inside))) : Map.of();
        return new StockPlanner.Survey(loose > 0 ? Map.of("minecraft:diamond", loose) : Map.of(), Map.of("minecraft:diamond", 64), Set.of(), Set.of(), lv, Map.of(),
            inside > 0 ? Map.of("minecraft:diamond", inside) : Map.of(), sv);
    }

    @Test
    void contentsOfShulkersAreASourceWhenNothingElseIsThere() {
        StockPlanner.Plan plan = StockPlanner.plan(order(new Order.Line("item", "minecraft:diamond", 200)), withShulkers(0, 1000), Map.of(), OrderPackingTest::name);
        assertNull(plan.failure());
        assertEquals(200, plan.units().get(0).lots.stream().mapToInt(Order.Lot::count).sum());
        assertTrue(plan.notes().isEmpty(), plan.notes().toString());
    }

    @Test
    void looseStacksAreCountedBeforeShulkerContents() {
        // 100 loose + 1000 inside: asking for 100 leaves the shulkers alone, asking for 164 takes 64 from them
        StockPlanner.Plan a = StockPlanner.plan(order(new Order.Line("item", "minecraft:diamond", 100)), withShulkers(100, 1000), Map.of(), OrderPackingTest::name);
        assertEquals(List.of(64, 36), a.units().get(0).lots.stream().map(Order.Lot::count).toList());
        StockPlanner.Plan b = StockPlanner.plan(order(new Order.Line("item", "minecraft:diamond", 164)), withShulkers(100, 1000), Map.of(), OrderPackingTest::name);
        assertEquals(164, b.units().get(0).lots.stream().mapToInt(Order.Lot::count).sum());
        // 64 + 36 from loose, 64 from a shulker: three loads, not four (partials of one variant are coalesced)
        assertEquals(List.of(64, 64, 36), b.units().get(0).lots.stream().map(Order.Lot::count).toList());
    }

    @Test
    void theTotalOfBothSourcesLimitsTheLine() {
        StockPlanner.Plan plan = StockPlanner.plan(order(new Order.Line("item", "minecraft:diamond", 500)), withShulkers(100, 200), Map.of(), OrderPackingTest::name);
        assertEquals(300, plan.units().get(0).lots.stream().mapToInt(Order.Lot::count).sum());
        assertTrue(plan.notes().get(0).contains("only 300 of 500"), plan.notes().toString());
    }

    @Test
    void coalesceJoinsPartialLoadsOfOneVariantOnly() {
        List<Order.Lot> lots = List.of(new Order.Lot("k", "a", 40), new Order.Lot("k", "a", 40), new Order.Lot("k", "b", 40), new Order.Lot("k", "a", 64));
        List<Order.Lot> out = StockPlanner.coalesce(lots, 64);
        assertEquals(4, out.size());
        assertEquals(64 + 64 + 16 + 40, out.stream().mapToInt(Order.Lot::count).sum() + 0);
        assertEquals(1, out.stream().filter(l -> "a".equals(l.variant()) && l.count() == 16).count());
        assertEquals(1, out.stream().filter(l -> "b".equals(l.variant()) && l.count() == 40).count());
    }

    @Test
    void bestFitPrefersTheSmallestVariantThatHasEnough() {
        StockPlanner.Var big = new StockPlanner.Var("big", 500), small = new StockPlanner.Var("small", 64), tiny = new StockPlanner.Var("tiny", 10);
        List<StockPlanner.Var> vars = List.of(big, small, tiny);
        Map<StockPlanner.Var, Long> used = new IdentityHashMap<>();
        assertEquals(small, StockPlanner.bestFit(vars, used, 64));
        assertEquals(tiny, StockPlanner.bestFit(vars, used, 8));
        // Nothing has enough: the one with the most left
        assertEquals(big, StockPlanner.bestFit(vars, used, 600));
        used.put(big, 500L);
        used.put(small, 64L);
        assertEquals(tiny, StockPlanner.bestFit(vars, used, 64));
        used.put(tiny, 10L);
        assertNull(StockPlanner.bestFit(vars, used, 1));
    }

    @Test
    void whatIsNotThereIsNotPlannedAndIsExplained() {
        Order o = order(new Order.Line("item", "minecraft:diamond", 100), new Order.Line("item", "minecraft:emerald", 5));
        StockPlanner.Survey s = new StockPlanner.Survey(Map.of("minecraft:diamond", 40L), Map.of("minecraft:diamond", 64), Set.of(), Set.of(),
            Map.of("minecraft:diamond", List.of(new StockPlanner.Var("minecraft:diamond", 40))), Map.of("minecraft:emerald", "inside shulkers"));
        StockPlanner.Plan plan = StockPlanner.plan(o, s, Map.of(), OrderPackingTest::name);
        assertNull(plan.failure());
        assertEquals(40, plan.units().get(0).lots.stream().mapToInt(Order.Lot::count).sum());
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("only 40 of 100")));
        assertTrue(plan.notes().stream().anyMatch(n -> n.contains("emerald") && n.contains("inside shulkers")));
    }

    @Test
    void nothingToDeliverIsAFailure() {
        Order o = order(new Order.Line("item", "minecraft:emerald", 5));
        StockPlanner.Plan plan = StockPlanner.plan(o, survey(Map.of(), Map.of(), Map.of()), Map.of(), OrderPackingTest::name);
        assertNotNull(plan.failure());
        assertFalse(plan.failure().isBlank());
    }

    @Test
    void aHugeShulkerCountIsRefusedAtOnce() {
        long start = System.currentTimeMillis();
        StockPlanner.Plan plan = StockPlanner.plan(order(new Order.Line("shulker", "s:abc", Integer.MAX_VALUE)), survey(Map.of(), Map.of(), Map.of()), Map.of(), OrderPackingTest::name);
        assertNotNull(plan.failure());
        StockPlanner.Plan empties = StockPlanner.plan(order(new Order.Line("item", "minecraft:red_shulker_box", 2_000_000_000)), survey(Map.of(), Map.of(), Map.of()), Map.of("minecraft:red_shulker_box", 3), OrderPackingTest::name);
        assertTrue(empties.failure().contains("54"));
        assertTrue(System.currentTimeMillis() - start < 2000);
    }

    @Test
    void emptyShulkerLinesAreLimitedByWhatIsFree() {
        Order o = order(new Order.Line("item", "minecraft:red_shulker_box", 5));
        StockPlanner.Plan plan = StockPlanner.plan(o, survey(Map.of(), Map.of(), Map.of()), Map.of("minecraft:red_shulker_box", 3), OrderPackingTest::name);
        assertNull(plan.failure());
        assertEquals(3, plan.units().size());
        assertTrue(plan.notes().get(0).contains("only 3 of 5"));
    }

    @Test
    void shulkerKeys() {
        assertTrue(StockPlanner.isShulkerKey("minecraft:shulker_box"));
        assertTrue(StockPlanner.isShulkerKey("minecraft:red_shulker_box#abcd1234"));
        assertFalse(StockPlanner.isShulkerKey("minecraft:diamond"));
        assertFalse(StockPlanner.isShulkerKey("minecraft:diamond#shulker_box"));
    }

    @Test
    void orderParsingSkipsBrokenLines() {
        var json = com.google.gson.JsonParser.parseString("{\"id\":\"o-1\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:diamond\",\"count\":3},{\"type\":\"x\",\"key\":\"k\",\"count\":1},{\"type\":\"item\",\"key\":\"\",\"count\":1},{\"type\":\"shulker\",\"key\":\"s:1\",\"count\":0},\"junk\"]}").getAsJsonObject();
        Order o = Order.parse(json);
        assertNotNull(o);
        assertEquals(1, o.lines.size());
        assertNull(Order.parse(com.google.gson.JsonParser.parseString("{\"id\":\"o-2\",\"lines\":[]}").getAsJsonObject()));
        assertNull(Order.parse(com.google.gson.JsonParser.parseString("{\"lines\":[{}]}").getAsJsonObject()));
    }
}
