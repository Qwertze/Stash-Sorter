package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 1.8.0, from order o-16d278f3d6b8 on 1.7.2: a 12-stack shulker went out with 1 totem because the first load it looked for
 * had no source left and the whole shulker was given up. A load with no source is now left out and the rest still packed;
 * a container the sorter itself gave up on for now is looked at again once per order plan; an emptied source shulker is not
 * put back where it no longer belongs.
 */
class BinSkipTest {
    private static final String TOTEM = "minecraft:totem_of_undying#433fcaf4";

    /** A 12-load bin: the totem first, then 11 other loads that are all in stock. */
    private static List<Order.Lot> twelve() {
        List<Order.Lot> lots = new ArrayList<>();
        lots.add(new Order.Lot(TOTEM, 1));
        for (String k : List.of("netherite_helmet", "netherite_chestplate", "netherite_leggings", "netherite_boots", "netherite_pickaxe", "netherite_sword")) lots.add(new Order.Lot("minecraft:" + k + "#aa", 1));
        lots.add(new Order.Lot("minecraft:netherite_pickaxe#bb", 1));
        for (int i = 0; i < 4; i++) lots.add(new Order.Lot("minecraft:enchanted_golden_apple", 16));
        return lots;
    }

    /** Runs the bin the way continueCompaction now does: fetch what has a source, leave out what has none. */
    private static List<BinTracker.Held> build(List<Order.Lot> lots, Set<String> noSource, Set<String> skip) {
        List<BinTracker.Held> packed = new ArrayList<>();
        for (int guard = 0; guard < 100; guard++) {
            BinTracker.Need need = BinTracker.nextNeed(lots, packed, List.of(), skip);
            if (need == null) break;
            if (noSource.contains(need.lot().key())) {
                skip.add(need.lot().key());
                continue;
            }
            packed.add(new BinTracker.Held(packed.size(), need.lot().key(), need.lot().variant(), need.lot().count()));
        }
        return packed;
    }

    @Test
    void aLoadWithNoSourceIsLeftOutAndTheRestIsPacked() {
        List<Order.Lot> lots = twelve();
        Set<String> skip = new LinkedHashSet<>();
        List<BinTracker.Held> packed = build(lots, Set.of(TOTEM), skip);
        assertEquals(11, packed.size(), "11 of 12 packed");
        assertEquals(Set.of(TOTEM), skip, "short by just the totem");
        assertEquals(1, BinTracker.skipped(lots, packed, List.of(), skip));
        // Everything that can be had is in: the bin is done short (have + skipped = target)
        assertEquals(lots.size(), lots.size() - BinTracker.notPacked(lots, packed).size() + BinTracker.skipped(lots, packed, List.of(), skip));
        assertNull(BinTracker.nextNeed(lots, packed, List.of(), skip));
    }

    @Test
    void withNothingLeftOutNextNeedIsAsBefore() {
        List<Order.Lot> lots = twelve();
        assertEquals(BinTracker.nextNeed(lots, List.of(), List.of()), BinTracker.nextNeed(lots, List.of(), List.of(), Set.of()));
        assertEquals(0, BinTracker.skipped(lots, List.of(), List.of(), Set.of()));
        // A carried stack of a load left out still counts as in hand, not as going without
        List<BinTracker.Held> carried = List.of(new BinTracker.Held(3, TOTEM, null, 1));
        assertEquals(0, BinTracker.skipped(lots, List.of(), carried, Set.of(TOTEM)));
    }

    @Test
    void aSelfInflictedBlockIsLookedAtAgainOncePerPlan() {
        Map<SourceRules.Reject, Integer> blocked = new EnumMap<>(SourceRules.Reject.class);
        blocked.put(SourceRules.Reject.BLOCKED, 1);
        blocked.put(SourceRules.Reject.BAD_UNPACK, 1);
        assertEquals(BinStep.Missing.LOOK_AGAIN, BinStep.missing(blocked, false));
        assertEquals(BinStep.Missing.LEAVE_OUT, BinStep.missing(blocked, true), "only once per plan");
        // Turned down for good (an excluded container, a kit chest) or not at all: left out at once
        Map<SourceRules.Reject, Integer> other = new EnumMap<>(SourceRules.Reject.class);
        other.put(SourceRules.Reject.OTHER, 1);
        other.put(SourceRules.Reject.BLOCKED, 1);
        assertEquals(BinStep.Missing.LEAVE_OUT, BinStep.missing(other, false));
        assertEquals(BinStep.Missing.LEAVE_OUT, BinStep.missing(new EnumMap<>(SourceRules.Reject.class), false));
    }

    @Test
    void anEmptiedSourceShulkerIsNotPutBackWhereItIsMisplaced() {
        // Emptied in a chest signed for totems: it goes where shulkers belong, not back there
        assertEquals("Shulkers", BinStep.spentHome("Totems", true, true, "Shulkers", "Misc"));
        assertEquals("Misc", BinStep.spentHome("Totems", true, true, null, "Misc"));
        // Still belongs where it came from: back there
        assertEquals("Totems", BinStep.spentHome("Totems", true, false, "Shulkers", "Misc"));
        // Its container is full or gone
        assertEquals("Shulkers", BinStep.spentHome("Totems", false, false, "Shulkers", "Misc"));
        assertNull(BinStep.spentHome(null, false, false, null, null));
    }
}
