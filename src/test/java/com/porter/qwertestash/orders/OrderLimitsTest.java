package com.porter.qwertestash.orders;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OrderLimitsTest {
    private static Order read(String... counts) {
        var entries = new java.util.ArrayList<String>();
        for (String count : counts) entries.add("{\"type\":\"item\",\"key\":\"stone\",\"count\":" + count + "}");
        return Order.parse(JsonParser.parseString("{\"id\":\"o-1\",\"lines\":[" + String.join(",", entries) + "]}").getAsJsonObject());
    }

    @Test
    void onlyPositiveExactlyRepresentableCountsReachThePlanner() {
        for (String invalid : List.of("0", "-1", "1.5", "4294967297", "true", "null", "[2]")) {
            assertNull(read(invalid), invalid);
        }
        assertEquals(Map.of("stone", 15), read("10", "\"3\"", "2.0").requested());
    }

    @Test
    void duplicateLinesCannotWrapTheRequestedTotal() {
        assertEquals(Map.of("stone", Integer.MAX_VALUE), read("2147483646", "1").requested());
        assertNull(read("2147483647", "1"));
    }

    @Test
    void anImpossibleQuantityIsRejectedBeforeInspectingStackVariants() {
        // This survey can answer availability, but expansion must be unnecessary once the slot limit is exceeded.
        List<StockPlanner.Var> unused = new java.util.AbstractList<>() {
            public StockPlanner.Var get(int index) { throw new AssertionError("expanded an impossible order"); }
            public int size() { return 1; }
        };
        var survey = new StockPlanner.Survey(Map.of("stone", 100000L), Map.of("stone", 64),
            Set.of(), Set.of(), Map.of("stone", unused), Map.of());
        assertTrue(StockPlanner.plan(read("100000"), survey, Map.of(), k -> k).failure().contains("too big"));
    }
}
