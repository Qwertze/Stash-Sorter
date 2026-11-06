package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The names field of an order: how it is read from the claim, which shulker gets which name, and the anvil's rules. */
class ShulkerNamesTest {
    private static Order order(String names) {
        String json = "{\"id\":\"o-1\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:stone\",\"count\":64}]" + (names == null ? "" : ",\"names\":" + names) + "}";
        return Order.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void noNamesFieldOrNullIsNoNaming() {
        assertNull(order(null).names);
        assertNull(order("null").names);
    }

    @Test
    void allGivesEveryShulkerTheTrimmedName() {
        ShulkerNames n = order("{\"mode\":\"all\",\"name\":\"  Kit 1  \"}").names;
        assertNotNull(n);
        assertEquals("all", n.mode);
        assertEquals("Kit 1", n.nameFor(0));
        assertEquals("Kit 1", n.nameFor(53));
        assertNull(n.nameFor(-1));
    }

    @Test
    void eachMapsByIndexAndBlanksOrMissingIndexesStayUnnamed() {
        ShulkerNames n = order("{\"mode\":\"each\",\"names\":[\"A\",\"\",\"  \",\" C \",5]}").names;
        assertNotNull(n);
        assertEquals("A", n.nameFor(0));
        assertNull(n.nameFor(1));
        assertNull(n.nameFor(2));
        assertEquals("C", n.nameFor(3));
        // A wrong type is one unnamed shulker, not a broken order
        assertNull(n.nameFor(4));
        assertNull(n.nameFor(5));
    }

    @Test
    void blankOrBrokenNamesAreNoNamesAndTheOrderStillReads() {
        assertNull(order("{\"mode\":\"all\",\"name\":\"   \"}").names);
        assertNull(order("{\"mode\":\"each\",\"names\":[\"\",\" \"]}").names);
        assertNull(order("{\"mode\":\"some\",\"name\":\"x\"}").names);
        assertNull(order("{\"mode\":\"all\",\"name\":5}").names);
        assertNull(order("{\"mode\":\"each\",\"names\":\"x\"}").names);
        assertNull(order("\"all\"").names);
        assertNotNull(order("{\"mode\":\"all\",\"name\":{}}"));
        assertEquals(1, order("{\"mode\":\"each\",\"names\":{}}").lines.size());
    }

    @Test
    void eachKeepsAtMostFiftyFourEntries() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < 60; i++) b.append(i == 0 ? "" : ",").append("\"n").append(i).append('"');
        ShulkerNames n = order("{\"mode\":\"each\",\"names\":" + b.append(']') + "}").names;
        assertEquals("n53", n.nameFor(53));
        assertNull(n.nameFor(54));
    }

    @Test
    void theAnvilRules() {
        assertNull(ShulkerNames.problem("Kit"));
        assertNull(ShulkerNames.problem("x".repeat(50)));
        assertNotNull(ShulkerNames.problem("x".repeat(51)));
        assertNotNull(ShulkerNames.problem("  "));
        assertNotNull(ShulkerNames.problem(null));
        assertNotNull(ShulkerNames.problem("§cRed"));
        assertNotNull(ShulkerNames.problem("a\u0007b"));
        assertNotNull(ShulkerNames.problem("a\nb"));
        assertEquals("Kit", ShulkerNames.normalise("  Kit "));
        // Fifty characters once trimmed
        assertEquals("x".repeat(50), ShulkerNames.normalise(" " + "x".repeat(50) + " "));
        assertNull(ShulkerNames.normalise("x".repeat(51)));
        assertNull(ShulkerNames.normalise("§"));
    }

    @Test
    void anOverlongNameFromTheServerIsKeptButFailsTheRules() {
        ShulkerNames n = order("{\"mode\":\"all\",\"name\":\"" + "y".repeat(60) + "\"}").names;
        assertEquals(60, n.nameFor(0).length());
        assertNotNull(ShulkerNames.problem(n.nameFor(0)));
    }

    @Test
    void theOneUnnamedNote() {
        assertNull(ShulkerNames.unnamedNote(0, List.of("x")));
        assertEquals("1 shulker(s) delivered unnamed: no anvil set", ShulkerNames.unnamedNote(1, List.of("no anvil set")));
        assertEquals("3 shulker(s) delivered unnamed: no anvil set, too few XP levels", ShulkerNames.unnamedNote(3, List.of("no anvil set", "too few XP levels")));
    }

    @Test
    void anOrderWithoutNamesParsesAsBefore() {
        JsonObject o = JsonParser.parseString("{\"id\":\"o-2\",\"lines\":[{\"type\":\"shulker\",\"key\":\"s:abc\",\"count\":2}]}").getAsJsonObject();
        Order parsed = Order.parse(o);
        assertEquals(1, parsed.lines.size());
        assertNull(parsed.names);
    }
}
