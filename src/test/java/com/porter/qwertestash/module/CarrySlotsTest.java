package com.porter.qwertestash.module;

import com.porter.qwertestash.stash.Item;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CarrySlotsTest {
    @Test
    void separatorOnlyEntriesDoNotDiscardNeighborsOrCrashThePublicParser() {
        assertEquals(Set.of(0, 1, 35), StashSorter.parseSlots("1-2, -, ---, junk, 36"));
        assertTrue(StashSorter.parseSlots("--").isEmpty());
    }

    @Test
    void parserRetainsLegacyRangesClippingAndDuplicateHandling() {
        assertEquals(Set.of(0, 1, 2, 33, 34, 35),
            CarrySlots.parse(" 3-1, 34-2147483647,0,34,3-,2147483648, 0-1,1-2-extra "));
        assertTrue(CarrySlots.parse(null).isEmpty());
    }

    @Test
    void missingOrBlankCarryUsesAllUnreservedSlotsInInventoryOrder() {
        var slots = new CarrySlots(" \t", " 36 ", true);
        assertFalse(slots.isDesignated());
        assertEquals(0, slots.designatedCount());
        assertEquals(2, slots.keptCount());
        assertEquals(IntStream.range(1, 35).boxed().toList(), slots.forSorting(i -> null, Map.of()));
        assertEquals(36, new CarrySlots(null, null, false).freeCount(i -> null));
    }

    @Test
    void malformedExplicitConfigurationNeverWidensToEverySlot() {
        var slots = new CarrySlots("-,bad", "", false);
        assertTrue(slots.isDesignated());
        assertEquals(0, slots.designatedCount());
        assertTrue(slots.forSorting(i -> { throw new AssertionError(i); }, Map.of()).isEmpty());
        assertEquals(0, slots.freeCount(i -> { throw new AssertionError(i); }));
    }

    @Test
    void sortingReclaimsOnlyMatchingHeldStacksAndOrdersCountOnlyEmptySlots() {
        var slots = new CarrySlots("1-6", "3", true);
        var stone = new Item();
        stone.id = "minecraft:stone";
        Map<Integer, String> held = Map.of(0, stone.id, 1, stone.id, 3, "minecraft:dirt");
        List<Integer> consulted = new ArrayList<>();
        var sorting = slots.forSorting(slot -> {
            consulted.add(slot);
            return slot == 4 ? null : stone;
        }, held);
        assertEquals(List.of(1, 3, 4, 5), consulted);
        assertEquals(List.of(1, 4), sorting);
        consulted.clear();
        assertEquals(1, slots.freeCount(slot -> {
            consulted.add(slot);
            return slot == 4 ? null : stone;
        }));
        assertEquals(List.of(1, 3, 4, 5), consulted);
        assertEquals(6, slots.designatedCount());
    }
}
