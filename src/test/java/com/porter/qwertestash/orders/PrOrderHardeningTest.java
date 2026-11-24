package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Order parsing and planning limits (ideas from PR 1). */
class PrOrderHardeningTest {
    private static JsonObject order(String... counts) {
        StringBuilder lines = new StringBuilder();
        for (String c : counts) {
            if (lines.length() > 0) lines.append(",");
            lines.append("{\"type\":\"item\",\"key\":\"minecraft:stone\",\"count\":").append(c).append("}");
        }
        return JsonParser.parseString("{\"id\":\"o1\",\"lines\":[" + lines + "]}").getAsJsonObject();
    }

    @Test
    void exactPositiveIntegersAreKept() {
        Order o = Order.parse(order("5", "2.0", "9"));
        assertNotNull(o);
        assertEquals(3, o.lines.size());
        assertEquals(16, o.requested().get("minecraft:stone"));
    }

    @Test
    void inexactOrBrokenCountsLeaveTheLineOut() {
        Order o = Order.parse(order("1.5", "1e30", "0", "-3", "true", "null", "3"));
        assertNotNull(o);
        assertEquals(1, o.lines.size());
        assertEquals(3, o.lines.get(0).count());
        assertNull(Order.parse(order("2147483648")));
    }

    @Test
    void mergedTotalsThatOverflowRefuseTheOrder() {
        // each line is within the per-line limit, the sum for one key is not an int
        String[] many = new String[2200];
        Arrays.fill(many, "1000000");
        assertNull(Order.parse(order(many)));
        String[] fine = new String[100];
        Arrays.fill(fine, "1000000");
        assertNotNull(Order.parse(order(fine)));
    }

    @Test
    void allocateRefusesAStackSizeBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> StockPlanner.allocate("k", 10, 0, List.of(), new IdentityHashMap<>()));
    }

    @Test
    void anOrderBeyondTheSlotBudgetIsTooBig() {
        long budget = (long) Order.MAX_BINS * Order.SLOTS * 64;
        assertTrue(Order.MAX_LINE_COUNT > budget);
        Order o = new Order("o", List.of(new Order.Line(Order.ITEM, "minecraft:stone", Order.MAX_LINE_COUNT)));
        StockPlanner.Survey survey = new StockPlanner.Survey(Map.of("minecraft:stone", budget * 20), Map.of("minecraft:stone", 64), Set.of(), Set.of(), Map.of(), Map.of());
        StockPlanner.Plan plan = StockPlanner.plan(o, survey, Map.of(), k -> k);
        assertNotNull(plan.failure());
        assertTrue(plan.failure().startsWith("too big"));
        assertTrue(plan.units().isEmpty());
    }

    @Test
    void anOrderWithinTheBudgetStillPlans() {
        Order o = new Order("o", List.of(new Order.Line(Order.ITEM, "minecraft:stone", 640)));
        Map<String, List<StockPlanner.Var>> vars = Map.of("minecraft:stone", List.of(new StockPlanner.Var("minecraft:stone", 10_000L)));
        StockPlanner.Survey survey = new StockPlanner.Survey(Map.of("minecraft:stone", 10_000L), Map.of("minecraft:stone", 64), Set.of(), Set.of(), vars, Map.of());
        StockPlanner.Plan plan = StockPlanner.plan(o, survey, Map.of(), k -> k);
        assertNull(plan.failure());
        assertEquals(1, plan.units().size());
    }
}
