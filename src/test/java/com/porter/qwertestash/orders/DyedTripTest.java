package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.porter.qwertestash.orders.DyedFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trips to the crafting table for dyed armour on a fake game (CRAFT-CONTRACT-v1.2.md 15, B5): the item goes in cell 0 and the dyes after it, one fill at a time
 * (armour doesn't stack), the colour of the result is worked out from the grid by the bot's arithmetic and compared with the step's before it is taken (the
 * first coat too), a dyed piece is never put in the base cell, and a first coat made in the same trip is the item of the second. The fake follows the rules
 * the contract assumes [UNVERIFIED in game].
 */
class DyedTripTest {
    /** A crafting table whose result is worked out from the grid by the armour-dye recipe. */
    static final class Table implements CraftTrip.Port {
        final Map<String, Integer> inv = new LinkedHashMap<>();
        final Map<String, String> specOfKind = new HashMap<>();
        final String[] grid = new String[10];
        final int[] counts = new int[10];
        boolean open, closedWithItems;
        /** The result's colour is this one instead of the mix's (a game that mixes another way). */
        @Nullable Integer wrongColour;
        int walkTicks = 2, walks, takes, lag = 2, pendingIn = -1, serial, placed;
        int maxGridCount;

        record Out(String kind, String spec) {
        }

        String kind(String id, String spec) {
            for (var e : specOfKind.entrySet()) if (e.getValue().equals(spec) && e.getKey().startsWith(id + "|")) return e.getKey();
            String kind = id + "|v" + (++serial);
            specOfKind.put(kind, spec);
            return kind;
        }

        /** A stack of a colour (a kind with a component hash) or a plain one. */
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
                    if (grid[s] == null) continue;
                    if (--counts[s] == 0) grid[s] = null;
                }
            }
        }

        /** The armour-dye recipe: one dyeable item and one or more dyes, nothing else, one of each in a cell. */
        @Nullable Out result() {
            if (pendingIn > 0) return null;
            String item = null;
            int[] dyes = new int[16];
            int n = 0;
            for (int s = 1; s <= 9; s++) {
                if (grid[s] == null) continue;
                String id = CraftSlots.baseOf(grid[s]);
                int dye = DyedSpecs.dyeIndex(id);
                if (dye >= 0) {
                    dyes[dye]++;
                    n++;
                }
                else if (DYED.dyes(id) && item == null) item = grid[s];
                else return null;
            }
            if (item == null || n == 0) return null;
            String spec = specOfKind.get(item);
            int start = spec == null ? -1 : DyedSpecs.rgbOf(spec);
            int rgb = wrongColour != null ? wrongColour : DyedSpecs.mix(start, dyes, PALETTE);
            String made = DyedSpecs.specOf(rgb);
            return new Out(kind(CraftSlots.baseOf(item), made), made);
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
        public int maxStack(String kind) {
            return DYED.dyes(CraftSlots.baseOf(kind)) ? 1 : 64;
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
            if (slot == 0) return result() == null ? 0 : 1;
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
            maxGridCount = Math.max(maxGridCount, counts[gridSlot]);
            placed++;
            return true;
        }

        @Override
        public int take(String item, int count) {
            takes++;
            Out r = result();
            if (r == null) return 7;
            assertEquals(CraftSlots.baseOf(r.kind()), item, "the trip names the item's id, not its pseudo id");
            inv.merge(r.kind(), 1, Integer::sum);
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

    private static CraftTrip.Outcome go(Table f, CraftPlanner.Plan p) {
        return run(new CraftTrip(f, p), f);
    }

    private static void dyes(Table f, String name, int n) {
        f.carry("minecraft:" + name + "_dye", null, n);
    }

    @Test
    void aChestplateIsDyedWithTheItemFirstAndTheDyeAfterOneFillAtATime() {
        CraftLedger l = ledger(red(0, 3), CHEST, RED, 3);
        Table f = new Table();
        // The fake keeps the three plain pieces as one count of one kind: the trip still places one at a time, as armour has a stack of 1
        f.inv.put(CHEST, 3);
        dyes(f, "red", 3);
        CraftTrip.Outcome o = go(f, plan(l, CHEST + "@" + RED, 3));
        assertNull(o.failure());
        assertEquals(3, f.held(CHEST, RED));
        assertEquals(0, f.held(CHEST, null));
        assertEquals(0, f.held("minecraft:red_dye", null));
        assertEquals(3, f.takes, "three crafts, one fill each");
        assertEquals(1, f.maxGridCount, "a grid slot never held more than one: armour has a stack of 1");
        assertEquals(0, f.gridTotal());
        assertFalse(f.closedWithItems);
        assertEquals(3, o.made());
    }

    @Test
    void aMixOfSeveralDyesIsPlacedInPaletteOrderAndMakesTheServersColour() {
        CraftLedger l = ledger(orange(2), CHEST, ORANGE, 2);
        Table f = new Table();
        f.inv.put(CHEST, 2);
        dyes(f, "red", 2);
        dyes(f, "yellow", 2);
        CraftTrip.Outcome o = go(f, plan(l, CHEST + "@" + ORANGE, 2));
        assertNull(o.failure());
        assertEquals(2, f.held(CHEST, ORANGE));
        assertEquals(0, f.held("minecraft:red_dye", null) + f.held("minecraft:yellow_dye", null));
    }

    @Test
    void aTwoCoatColourMakesTheFirstCoatAndPutsTheSecondOnIt() {
        Two two = twoCoats().get(0);
        String result = DyedSpecs.specOf(two.result()), mid = DyedSpecs.specOf(two.mid());
        CraftLedger l = ledger(twoCoat(CHEST, two, 1), CHEST, result, 1);
        CraftPlanner.Plan p = plan(l, CHEST + "@" + result, 1);
        assertEquals(2, p.steps().size());
        assertEquals(CHEST + "@" + mid, p.steps().get(0).recipe().result(), "the first coat first");
        Table f = new Table();
        f.inv.put(CHEST, 1);
        for (int i = 0; i < 16; i++) if (two.first()[i] + two.second()[i] > 0) dyes(f, NAMES.get(i), two.first()[i] + two.second()[i]);
        CraftTrip.Outcome o = go(f, p);
        assertNull(o.failure());
        assertEquals(1, f.held(CHEST, result));
        assertEquals(0, f.held(CHEST, mid), "the first coat is used up by the second");
        assertEquals(0, f.held(CHEST, null));
        assertEquals(2, f.takes);
        assertEquals(0, f.gridTotal());
        assertFalse(f.closedWithItems);
    }

    @Test
    void severalPiecesOfATwoCoatColourInOneTripKeepTheirFirstCoatsApartFromStock() {
        Two two = twoCoats().get(1);
        String result = DyedSpecs.specOf(two.result()), mid = DyedSpecs.specOf(two.mid());
        CraftLedger l = ledger(twoCoat(CHEST, two, 3), CHEST, result, 3);
        Table f = new Table();
        f.inv.put(CHEST, 3);
        for (int i = 0; i < 16; i++) if (two.first()[i] + two.second()[i] > 0) dyes(f, NAMES.get(i), 3 * (two.first()[i] + two.second()[i]));
        CraftTrip.Outcome o = go(f, plan(l, CHEST + "@" + result, 3));
        assertNull(o.failure());
        assertEquals(3, f.held(CHEST, result));
        assertEquals(0, f.held(CHEST, mid));
        assertEquals(6, f.takes);
    }

    @Test
    void aDyedPieceIsNeverPutInTheBaseCellEvenWhenItIsTheOnlyOneCarried() {
        CraftLedger l = ledger(orange(1), CHEST, ORANGE, 1);
        Table f = new Table();
        String redOne = f.carry(CHEST, RED, 1);
        dyes(f, "red", 1);
        dyes(f, "yellow", 1);
        CraftTrip.Outcome o = go(f, plan(l, CHEST + "@" + ORANGE, 1));
        assertNotNull(o.failure());
        assertTrue(o.failure().startsWith(CraftTrip.MISSING), o.failure());
        assertEquals(0, f.gridTotal(), "nothing was put in the grid");
        assertEquals(1, f.inv.get(redOne), "the red piece is as it was");
        assertEquals(0, f.takes);
        // With a plain one beside it the plain one is used and the red one stays
        Table g = new Table();
        String keep = g.carry(CHEST, RED, 1);
        g.carry(CHEST, null, 1);
        dyes(g, "red", 1);
        dyes(g, "yellow", 1);
        assertNull(go(g, plan(l, CHEST + "@" + ORANGE, 1)).failure());
        assertEquals(1, g.inv.get(keep));
        assertEquals(1, g.held(CHEST, ORANGE));
        assertEquals(0, g.held(CHEST, null));
    }

    @Test
    void aResultOfAnotherColourIsNotTakenAndTheTripEndsWithTheSpecMismatch() {
        CraftLedger l = ledger(red(0, 1), CHEST, RED, 1);
        Table f = new Table();
        f.inv.put(CHEST, 1);
        dyes(f, "red", 1);
        f.wrongColour = 0x123456;
        CraftTrip.Outcome o = go(f, plan(l, CHEST + "@" + RED, 1));
        assertEquals(CraftTrip.SPEC_MISMATCH, o.failure());
        assertEquals(0, f.takes, "nothing is taken");
        assertEquals(0, f.gridTotal(), "the grid is taken back");
        assertEquals(1, f.held(CHEST, null), "the piece and the dye are back in hand, not lost");
        assertEquals(1, f.held("minecraft:red_dye", null));
        assertEquals(CraftOrder.RECIPE_MISMATCH, CraftOrder.reasonOf(o.failure(), false, false));
    }

    @Test
    void aFirstCoatOfAnotherColourThanTheStepSaysIsNotTakenEither() {
        Two two = twoCoats().get(0);
        String result = DyedSpecs.specOf(two.result());
        CraftLedger l = ledger(twoCoat(CHEST, two, 1), CHEST, result, 1);
        Table f = new Table();
        f.inv.put(CHEST, 1);
        for (int i = 0; i < 16; i++) if (two.first()[i] + two.second()[i] > 0) dyes(f, NAMES.get(i), two.first()[i] + two.second()[i]);
        f.wrongColour = 0x654321;
        CraftTrip.Outcome o = go(f, plan(l, CHEST + "@" + result, 1));
        assertEquals(CraftTrip.SPEC_MISMATCH, o.failure());
        assertEquals(0, f.takes);
        assertEquals(0, f.gridTotal());
    }

    @Test
    void horseAndWolfArmourAreDyedLikeAnyOther() {
        for (String item : new String[] {HORSE, WOLF, BOOTS}) {
            JsonObject b = block("[" + line(item, RED, 2) + "]", "[" + step(item, RED, null, mix("red"), 2) + "]", "[ " + q(item, 2) + ", " + q("minecraft:red_dye", 2) + " ]", "[]", 2);
            CraftLedger l = ledger(b, item, RED, 2);
            Table f = new Table();
            f.inv.put(item, 2);
            dyes(f, "red", 2);
            assertNull(go(f, plan(l, item + "@" + RED, 2)).failure(), item);
            assertEquals(2, f.held(item, RED), item);
        }
    }
}
