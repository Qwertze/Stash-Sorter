package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.porter.qwertestash.orders.SpecialFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trips to the crafting table for tipped arrows, stew, copies and shields on a fake game (CRAFT-CONTRACT-v1.2.md 15 and the B4 clarifications): the exact grid of
 * the step is placed (a patterned banner is no blank banner), the result's spec is derived and compared with the step's before it is taken, a copy of a banner
 * or book leaves its source in the grid and the trip takes it back out, a map copy's source is used up and the next craft takes a map from the result. The fake
 * follows the rules the contract assumes [UNVERIFIED in game].
 */
class SpecialTripTest {
    private static final String ARROW = "minecraft:arrow", POTION = "minecraft:lingering_potion", MAP_BLANK = "minecraft:map", WRITABLE = "minecraft:writable_book";

    /** A crafting table whose result is worked out from the grid by the rules of the B4 recipes. */
    static final class Table implements CraftTrip.Port {
        final Map<String, Integer> inv = new LinkedHashMap<>();
        final Map<String, String> specOfKind = new HashMap<>();
        final String[] grid = new String[10];
        final int[] counts = new int[10];
        boolean open, closedWithItems;
        /** The result of a copy has this key instead of the source's (a game that copies wrongly). */
        boolean wrongCopy;
        int walkTicks = 2, walks, takes, lag = 2, pendingIn = -1, serial, placed;
        /** The slot whose stack the pending take gives back (a copy's source), or -1. */
        int stays = -1;

        record Out(String kind, String spec, int count, int stays) {
        }

        String kind(String id, String spec) {
            for (var e : specOfKind.entrySet()) if (e.getValue().equals(spec) && e.getKey().startsWith(id + "|")) return e.getKey();
            String kind = id + "|v" + (++serial);
            specOfKind.put(kind, spec);
            return kind;
        }

        /** A stack of a variant: a kind with a component hash, as a patched stack has. */
        String carry(String id, String spec, int n) {
            String kind = spec == null ? id : kind(id, spec);
            inv.merge(kind, n, Integer::sum);
            return kind;
        }

        int held(String id, String spec) {
            int n = 0;
            for (var e : inv.entrySet()) if (CraftSlots.baseOf(e.getKey()).equals(id) && (spec == null ? !e.getKey().contains("|") : spec.equals(specOfKind.get(e.getKey())))) n += e.getValue();
            return n;
        }

        void tick() {
            if (pendingIn > 0 && --pendingIn == 0) {
                for (int s = 1; s <= 9; s++) {
                    if (grid[s] == null || s == stays) continue;
                    if (--counts[s] == 0) grid[s] = null;
                }
                // The source of a banner or book copy: one is taken out of the stack and one of the same kind is put back: the stack stays as it was
                stays = -1;
            }
        }

        private static String base(String kind) {
            return CraftSlots.baseOf(kind);
        }

        @Nullable Out result() {
            if (pendingIn > 0) return null;
            int n = 0;
            for (int s = 1; s <= 9; s++) if (grid[s] != null) n++;
            // Tipped arrows: eight arrows around a lingering potion
            if (n == 9 && base(grid[5]).equals(POTION) && specOfKind.getOrDefault(grid[5], "").startsWith("tipped:")) {
                for (int s = 1; s <= 9; s++) if (s != 5 && !grid[s].equals(ARROW)) return null;
                String spec = specOfKind.get(grid[5]);
                return new Out(kind("minecraft:tipped_arrow", spec), spec, 8, -1);
            }
            // Stew
            if (n == 4 && "minecraft:bowl".equals(grid[1]) && "minecraft:brown_mushroom".equals(grid[2]) && "minecraft:red_mushroom".equals(grid[3]) && "minecraft:poppy".equals(grid[4])) {
                return new Out(kind("minecraft:suspicious_stew", NV), NV, 1, -1);
            }
            // A shield with a banner of a key
            if (n == 2 && "minecraft:shield".equals(grid[1]) && specOfKind.getOrDefault(grid[2], "").startsWith("copy:")) {
                String spec = "shield:" + specOfKind.get(grid[2]).substring("copy:".length());
                return new Out(kind("minecraft:shield", spec), spec, 1, -1);
            }
            // Copies: the source is the stack with a copy spec (or the original of a book), the others are blanks
            int src = -1;
            for (int s = 1; s <= 9; s++) {
                String sp = grid[s] == null ? null : specOfKind.get(grid[s]);
                if (sp != null && (sp.startsWith("copy:") || sp.startsWith("src:"))) src = s;
            }
            if (src < 0 || n < 2) return null;
            String id = base(grid[src]), spec = specOfKind.get(grid[src]);
            int blanks = 0;
            for (int s = 1; s <= 9; s++) {
                if (s == src || grid[s] == null) continue;
                String want = id.endsWith("_banner") ? id : id.equals("minecraft:filled_map") ? MAP_BLANK : WRITABLE;
                if (!grid[s].equals(want)) return null;
                blanks++;
            }
            String key = wrongCopy ? "minecraft:other#ffff" : spec.substring(spec.indexOf(':') + 1);
            if (id.endsWith("_banner")) return blanks == 1 ? new Out(grid[src], "copy:" + key, 1, src) : null;
            if (id.equals("minecraft:filled_map")) return new Out(grid[src], "copy:" + key, blanks + 1, -1);
            return new Out(kind("minecraft:written_book", "gen1:" + key), "copy:" + key, blanks, src);
        }

        int gridTotal() {
            int n = 0;
            for (int s = 1; s <= 9; s++) n += counts[s];
            return n;
        }

        @Override
        public boolean arrived() {
            return walks > 0 && --walkTicks <= 0;
        }

        @Override
        public boolean walk() {
            walks++;
            return true;
        }

        @Override
        public boolean pathing() {
            return false;
        }

        @Override
        public boolean tableOpen() {
            return open;
        }

        @Override
        public boolean otherWindowOpen() {
            return false;
        }

        @Override
        public boolean open() {
            open = true;
            return true;
        }

        @Override
        public boolean busy() {
            return false;
        }

        @Override
        public Map<String, Integer> carried() {
            Map<String, Integer> out = new HashMap<>();
            inv.forEach((k, v) -> {
                if (v > 0) out.put(k, v);
            });
            return out;
        }

        @Override
        public @Nullable String specOf(String kind) {
            return specOfKind.get(kind);
        }

        @Override
        public @Nullable String specAt(int slot) {
            if (slot == 0) {
                Out r = result();
                return r == null ? null : r.spec();
            }
            return grid[slot] == null ? null : specOfKind.get(grid[slot]);
        }

        @Override
        public @Nullable String itemAt(int slot) {
            if (slot == 0) {
                Out r = result();
                return r == null ? null : r.kind();
            }
            return grid[slot];
        }

        @Override
        public int countAt(int slot) {
            if (slot == 0) {
                Out r = result();
                return r == null ? 0 : r.count();
            }
            return counts[slot];
        }

        @Override
        public boolean place(int gridSlot, String item, int goal) {
            if (!open) return false;
            int add = Math.min(goal - counts[gridSlot], inv.getOrDefault(item, 0));
            if (add <= 0) return false;
            inv.merge(item, -add, Integer::sum);
            grid[gridSlot] = item;
            counts[gridSlot] += add;
            placed++;
            return true;
        }

        @Override
        public int take(String item, int count) {
            takes++;
            Out r = result();
            if (r == null) return 7;
            assertEquals(base(r.kind()), item, "the trip names the item's id, not its pseudo id");
            inv.merge(r.kind(), r.count(), Integer::sum);
            stays = r.stays();
            pendingIn = lag;
            return 7;
        }

        @Override
        public int retrieve(int gridSlot) {
            if (grid[gridSlot] == null) return -1;
            inv.merge(grid[gridSlot], counts[gridSlot], Integer::sum);
            grid[gridSlot] = null;
            counts[gridSlot] = 0;
            return 8;
        }

        @Override
        public boolean holdingCursor() {
            return false;
        }

        @Override
        public int stowCursor() {
            return -1;
        }

        @Override
        public boolean close() {
            if (!open) return true;
            if (gridTotal() > 0) closedWithItems = true;
            open = false;
            return true;
        }
    }

    private static CraftPlanner.Plan plan(CraftLedger l, String ref, int n) {
        CraftSub.Outcome o = l.session().plan(ref, n);
        assertTrue(o.ok(), o.plan().refusal());
        return o.plan();
    }

    private static CraftLedger ledger(JsonObject block, String id, String spec, int count) {
        Order.Craft c = parse(block);
        return new CraftLedger(c, List.of(new Order.Line(Order.CRAFT, id, count, false, spec)), R, 2048);
    }

    private static CraftTrip.Outcome run(CraftTrip t, Table f) {
        for (int i = 0; i < 6000 && t.step() != CraftTrip.Step.Done; i++) {
            f.tick();
            t.tick();
        }
        assertEquals(CraftTrip.Step.Done, t.step(), "the trip ended");
        return t.outcome();
    }

    private static final String B = BANNER + "@copy:" + K_BANNER, M = MAP + "@copy:" + K_MAP, BC = BOOK + "@copy:" + K_BOOK;

    private static CraftTrip.Outcome go(Table f, CraftPlanner.Plan p) {
        return run(new CraftTrip(f, p), f);
    }

    @Test
    void tippedArrowsAreMadeFromEightArrowsAroundThePotionInTheMiddle() {
        CraftLedger l = ledger(SpecialFixtures.tipped(), "minecraft:tipped_arrow", POISON, 10);
        CraftPlanner.Plan p = plan(l, "minecraft:tipped_arrow@" + POISON, 10);
        Table f = new Table();
        f.carry(ARROW, null, 8);
        f.carry(POTION, POISON, 1);
        f.carry("minecraft:tipped_arrow", POISON, 5);
        CraftTrip.Outcome o = go(f, p);
        assertNull(o.failure());
        assertEquals(0, f.gridTotal());
        assertFalse(f.closedWithItems);
        assertEquals(1, f.takes);
        assertEquals(13, f.held("minecraft:tipped_arrow", POISON), "5 in stock and 8 made, one stack kind");
        assertEquals(0, f.held(POTION, POISON));
        assertEquals(9, f.placed, "eight arrows and the potion went in, one slot each");
    }

    @Test
    void aStewIsMadeFromItsFourCells() {
        CraftLedger l = ledger(SpecialFixtures.stew(), "minecraft:suspicious_stew", NV, 5);
        Table f = new Table();
        for (String id : new String[] {"minecraft:bowl", "minecraft:brown_mushroom", "minecraft:red_mushroom", "minecraft:poppy"}) f.carry(id, null, 2);
        // The fake rules need a stack of each; the plan's fills are 2 of 1 stew: one batch of 2, taken twice
        CraftTrip.Outcome o = go(f, plan(l, "minecraft:suspicious_stew@" + NV, 5));
        assertNull(o.failure());
        assertEquals(2, f.held("minecraft:suspicious_stew", NV));
        assertEquals(0, f.gridTotal());
    }

    @Test
    void aBannerCopyPlacesTheSourceAndAPlainBlankTakesTheResultAndGetsTheSourceBack() {
        CraftLedger l = ledger(SpecialFixtures.bannerCopy(), BANNER, "copy:" + K_BANNER, 3);
        Table f = new Table();
        String source = f.carry(BANNER, "copy:" + K_BANNER, 2);
        f.carry(BANNER, null, 2);
        CraftTrip.Outcome o = go(f, plan(l, B, 3));
        assertNull(o.failure());
        assertEquals(0, f.gridTotal(), "the source that stayed in the grid is taken back out");
        assertFalse(f.closedWithItems);
        // The patterned banners: 2 carried, 2 made, none lost; the plain blanks are used up
        assertEquals(4, f.inv.getOrDefault(source, 0), "the 2 taken from the stash and the 2 copies, the source among them");
        assertEquals(0, f.held(BANNER, null), "no blank is left, and a patterned banner was never used as one");
        assertEquals(2, f.takes);
    }

    @Test
    void aPatternedBannerIsNeverPutInTheBlankCellEvenWhenItIsTheMostPlentiful() {
        CraftLedger l = ledger(SpecialFixtures.bannerCopy(), BANNER, "copy:" + K_BANNER, 3);
        Table f = new Table();
        String patterned = f.carry(BANNER, "copy:" + K_BANNER, 10);
        f.carry(BANNER, null, 2);
        CraftTrip.Outcome o = go(f, plan(l, B, 3));
        assertNull(o.failure());
        assertEquals(12, f.inv.get(patterned), "10 and the 2 copies");
        // With no plain blank at all the craft is missing its input and nothing is put in the grid
        Table none = new Table();
        none.carry(BANNER, "copy:" + K_BANNER, 10);
        CraftTrip.Outcome miss = go(none, plan(l, B, 3));
        assertNotNull(miss.failure());
        assertTrue(miss.failure().startsWith(CraftTrip.MISSING), miss.failure());
        assertEquals(0, none.gridTotal());
    }

    @Test
    void aCopyThatTheTableMakesOfAnotherKeyIsNotTakenAndTheTripEndsWithTheSpecMismatch() {
        CraftLedger l = ledger(SpecialFixtures.bannerCopy(), BANNER, "copy:" + K_BANNER, 3);
        Table f = new Table();
        f.carry(BANNER, "copy:" + K_BANNER, 2);
        f.carry(BANNER, null, 2);
        f.wrongCopy = true;
        CraftTrip.Outcome o = go(f, plan(l, B, 3));
        assertEquals(CraftTrip.SPEC_MISMATCH, o.failure());
        assertEquals(0, f.takes, "nothing is taken");
        assertEquals(0, f.gridTotal(), "the grid is taken back");
        assertEquals(CraftOrder.RECIPE_MISMATCH, CraftOrder.reasonOf(o.failure(), false, false));
    }

    @Test
    void aMapCopyChainsItsCraftsAndUsesTheSourceUp() {
        CraftLedger l = ledger(SpecialFixtures.mapCopy(), MAP, "copy:" + K_MAP, 12);
        CraftPlanner.Plan p = plan(l, M, 12);
        Table f = new Table();
        String map = f.carry(MAP, "copy:" + K_MAP, 1);
        f.carry(MAP_BLANK, null, 11);
        CraftTrip.Outcome o = go(f, p);
        assertNull(o.failure());
        assertEquals(12, f.inv.get(map), "12 maps of the key in all: the source was used up and made again, twice");
        assertEquals(0, f.held(MAP_BLANK, null));
        assertEquals(0, f.gridTotal());
        assertEquals(2, f.takes, "two crafts: 8 blanks and 3");
        assertEquals(3, o.made(), "the trip counts what the last step adds: 4 made by the craft of 3 blanks, less the source it used up");
    }

    @Test
    void aMapCopyOfSeveralFillsInOneStepTakesTheSecondSourceFromTheFirstResult() {
        CraftLedger l = ledger(SpecialFixtures.mapCopyInOneStep(), MAP, "copy:" + K_MAP, 17);
        Table f = new Table();
        String map = f.carry(MAP, "copy:" + K_MAP, 1);
        f.carry(MAP_BLANK, null, 16);
        CraftTrip.Outcome o = go(f, plan(l, M, 17));
        assertNull(o.failure());
        assertEquals(17, f.inv.get(map));
        assertEquals(2, f.takes);
        assertEquals(0, f.gridTotal());
    }

    @Test
    void aBookCopyLeavesTheOriginalInTheGridAndTheTripTakesItBack() {
        CraftLedger l = ledger(SpecialFixtures.bookCopy(), BOOK, "copy:" + K_BOOK, 6);
        Table f = new Table();
        String original = f.carry(BOOK, "src:" + K_BOOK, 1);
        f.carry(WRITABLE, null, 6);
        CraftTrip.Outcome o = go(f, plan(l, BC, 6));
        assertNull(o.failure());
        assertEquals(1, f.inv.get(original), "the original is back among the carried stacks");
        assertEquals(6, f.held(BOOK, "gen1:" + K_BOOK), "6 copies, one stack kind");
        assertEquals(0, f.gridTotal());
        assertFalse(f.closedWithItems);
    }

    @Test
    void aShieldTakesAPlainShieldAndTheBannerOfTheKeyAndConsumesBoth() {
        CraftLedger l = ledger(SpecialFixtures.shield(), "minecraft:shield", "shield:" + K_BANNER, 2);
        Table f = new Table();
        f.carry("minecraft:shield", null, 2);
        f.carry(BANNER, "copy:" + K_BANNER, 2);
        CraftTrip.Outcome o = go(f, plan(l, "minecraft:shield@shield:" + K_BANNER, 2));
        assertNull(o.failure());
        assertEquals(2, f.held("minecraft:shield", "shield:" + K_BANNER));
        assertEquals(0, f.held(BANNER, "copy:" + K_BANNER));
        assertEquals(0, f.gridTotal());
    }
}
