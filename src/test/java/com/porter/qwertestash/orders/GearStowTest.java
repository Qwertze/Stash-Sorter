package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GearStow: damaged Mending armour goes out of the way, the bottles go into a selected hotbar slot (the MAIN hand: the off hand is never touched), and
 * everything is put back from the ledger, the held slot selected again.
 */
class GearStowTest {
    private static final class Inv implements RepairTrip.Port {
        final TreeMap<Integer, RepairTrip.Stack> slots = new TreeMap<>();
        final Set<Integer> keep = new HashSet<>();
        List<int[]> ledger = new ArrayList<>();
        boolean busy;
        int refuse, held;
        final List<int[]> moves = new ArrayList<>();

        @Override
        public boolean busy() {
            return busy;
        }

        @Override
        public RepairTrip.Stack at(int window) {
            return slots.get(window);
        }

        @Override
        public boolean move(int from, int to) {
            if (refuse-- > 0) return false;
            RepairTrip.Stack s = slots.remove(from);
            if (s == null || slots.containsKey(to)) throw new IllegalStateException("bad move " + from + " to " + to);
            slots.put(to, s);
            moves.add(new int[] {from, to});
            return true;
        }

        @Override
        public boolean select(int hotbar) {
            held = hotbar;
            return true;
        }

        @Override
        public int held() {
            return held;
        }

        @Override
        public boolean throwBottle() {
            return true;
        }

        @Override
        public boolean playerNear() {
            return false;
        }

        @Override
        public int orbsNear() {
            return 0;
        }

        @Override
        public int bottles() {
            return 0;
        }

        @Override
        public List<Integer> freeSlots() {
            List<Integer> out = new ArrayList<>();
            for (int w = 9; w <= 44; w++) if (!slots.containsKey(w) && !keep.contains(w)) out.add(w);
            return out;
        }

        @Override
        public boolean keep(int window) {
            return keep.contains(window);
        }

        @Override
        public boolean abort() {
            return false;
        }

        @Override
        public void ledger(List<int[]> moves) {
            ledger = new ArrayList<>();
            for (int[] m : moves) ledger.add(m.clone());
        }

        @Override
        public void afterClick() {
        }
    }

    private static RepairTrip.Stack bottles(int n) {
        return new RepairTrip.Stack("experience_bottle", n, 0, 0, false);
    }

    private static RepairTrip.Stack chest(int damage) {
        return new RepairTrip.Stack("diamond_chestplate", 1, damage, 528, true);
    }

    private static int stow(GearStow g, int limit) {
        int r = 0;
        for (int i = 0; i < limit && r == 0; i++) r = g.stow();
        return r;
    }

    @Test
    void damagedMendingArmourIsStoredAndTheBottlesGoIntoTheSelectedHotbarSlotNeverTheOffHand() {
        Inv inv = new Inv();
        inv.slots.put(6, chest(100));
        inv.slots.put(7, new RepairTrip.Stack("diamond_leggings", 1, 0, 495, true));
        inv.slots.put(45, new RepairTrip.Stack("totem_of_undying", 1, 0, 0, false));
        inv.slots.put(10, bottles(64));
        inv.held = 3;
        GearStow g = new GearStow(inv);
        assertEquals(1, g.damagedMendingArmour());
        assertEquals(1, stow(g, 100));
        assertNull(inv.slots.get(6));
        assertNotNull(inv.slots.get(7));
        // the off hand was never touched
        assertEquals("totem_of_undying", inv.slots.get(45).id());
        for (int[] m : inv.moves) assertTrue(m[0] != 45 && m[1] != 45, "moved the off hand");
        // the bottles are in the held hotbar slot (the held one was empty)
        assertTrue(g.bottleReady());
        assertEquals(36 + 3, g.handSlot());
        assertEquals(64, inv.slots.get(39).count());
        assertEquals(2, inv.ledger.size());
        // put back, last move first, and the slot held before is selected
        inv.held = 5;
        int ticks = 0;
        while (!g.restore() && ticks++ < 100) {
        }
        assertEquals("diamond_chestplate", inv.slots.get(6).id());
        assertEquals(64, inv.slots.get(10).count());
        assertEquals(3, inv.held);
        assertTrue(inv.ledger.isEmpty());
    }

    @Test
    void aHotbarSlotThatAlreadyHoldsBottlesIsUsedAsItIs() {
        Inv inv = new Inv();
        inv.slots.put(41, bottles(20));
        inv.slots.put(12, bottles(64));
        GearStow g = new GearStow(inv);
        assertEquals(1, stow(g, 50));
        assertEquals(41, g.handSlot());
        assertEquals(5, inv.held);
        assertEquals(20, inv.slots.get(41).count());
        assertTrue(inv.ledger.isEmpty());
    }

    @Test
    void anOccupiedHotbarSlotIsStoredFirstWhenNoneIsFree() {
        Inv inv = new Inv();
        for (int w = 36; w <= 44; w++) inv.slots.put(w, new RepairTrip.Stack("cobblestone", 64, 0, 0, false));
        inv.keep.add(36);
        inv.slots.put(10, bottles(30));
        GearStow g = new GearStow(inv);
        assertEquals(1, stow(g, 100));
        assertTrue(g.handSlot() >= 37);
        assertEquals(30, inv.slots.get(g.handSlot()).count());
        assertEquals("cobblestone", inv.slots.get(36).id());
        int ticks = 0;
        while (!g.restore() && ticks++ < 100) {
        }
        assertEquals("cobblestone", inv.slots.get(37).id());
        assertEquals(30, inv.slots.get(10).count());
    }

    @Test
    void noRoomIsReportedBeforeAnythingIsMoved() {
        Inv inv = new Inv();
        inv.slots.put(5, new RepairTrip.Stack("diamond_helmet", 1, 50, 363, true));
        for (int w = 9; w <= 44; w++) inv.slots.put(w, new RepairTrip.Stack("cobblestone", 64, 0, 0, false));
        GearStow g = new GearStow(inv);
        assertEquals(-1, g.stow());
        assertNotNull(inv.slots.get(5));
        assertTrue(inv.ledger.isEmpty());
    }

    @Test
    void aRefusedClickIsAskedAgain() {
        Inv inv = new Inv();
        inv.slots.put(8, new RepairTrip.Stack("diamond_boots", 1, 20, 429, true));
        inv.slots.put(11, bottles(10));
        inv.refuse = 5;
        GearStow g = new GearStow(inv);
        assertEquals(1, stow(g, 200));
        assertNull(inv.slots.get(8));
    }

    @Test
    void aStackOfBottlesUsedUpIsReplacedFromTheInventory() {
        Inv inv = new Inv();
        inv.slots.put(10, bottles(2));
        inv.slots.put(12, bottles(64));
        GearStow g = new GearStow(inv);
        assertEquals(1, stow(g, 50));
        int hand = g.handSlot();
        assertEquals(64, inv.slots.get(hand).count());
        inv.slots.remove(hand);
        assertFalse(g.bottleReady());
        for (int i = 0; i < 5 && !g.bottleReady(); i++) g.refill();
        assertTrue(g.bottleReady());
        assertEquals(2, inv.slots.get(hand).count());
        int ticks = 0;
        while (!g.restore() && ticks++ < 100) {
        }
        assertTrue(inv.ledger.isEmpty());
        assertEquals(2, inv.slots.get(10).count());
        assertNull(inv.slots.get(hand));
    }

    @Test
    void noBottlesAtAllIsNothingToPutInTheHand() {
        Inv inv = new Inv();
        GearStow g = new GearStow(inv);
        assertEquals(1, stow(g, 20));
        assertTrue(g.refill());
        assertFalse(g.bottleReady());
    }

    @Test
    void aBusyPortHoldsItBack() {
        Inv inv = new Inv();
        inv.slots.put(9, bottles(5));
        inv.busy = true;
        GearStow g = new GearStow(inv);
        assertEquals(0, g.stow());
        assertTrue(inv.moves.isEmpty());
        assertFalse(g.restore());
        inv.busy = false;
        assertEquals(1, stow(g, 20));
        assertTrue(g.bottleReady());
    }

    @Test
    void aStaleLedgerEntryIsDroppedNotMovedBlindly() {
        Inv inv = new Inv();
        inv.slots.put(20, new RepairTrip.Stack("cobblestone", 64, 0, 0, false));
        GearStow g = new GearStow(inv).seed(List.of(new int[] {6, 20}));
        int ticks = 0;
        while (!g.restore() && ticks++ < 20) {
        }
        assertEquals("cobblestone", inv.slots.get(20).id());
        assertNull(inv.slots.get(6));
        assertTrue(g.pending().isEmpty());
    }
}
