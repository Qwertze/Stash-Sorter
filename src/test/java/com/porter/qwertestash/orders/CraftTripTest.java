package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One trip to the crafting table on a fake world: the whole shulker box from logs, and every way it ends short. Whatever
 * happens, the window is never closed by the bot with something in its grid.
 */
class CraftTripTest {
    private static final Recipes R = Recipes.shipped();

    /** A crafting table next to the bot, and what the bot carries (by item id). */
    static class FakeTable implements CraftTrip.Port {
        int walkTicks = 3;
        boolean reachable = true, opens = true, gives = true, takeWorks = true, full;
        /** Ticks the server takes to show the grid used up after a take. */
        int lag = 2;
        /** The server closes the window this many ticks after it opened (-1: never). */
        int closeAfter = -1;
        boolean open, closedWithItems;
        int walks, opensTried, places, takes, retrieves, openTicks;
        final String[] grid = new String[10];
        final int[] counts = new int[10];
        final Map<String, Integer> inv = new HashMap<>();
        final List<Recipes.Recipe> recipes;
        int pendingIn = -1;

        FakeTable(List<Recipes.Recipe> recipes) {
            this.recipes = recipes;
        }

        FakeTable carry(String id, int n) {
            inv.merge(id, n, Integer::sum);
            return this;
        }

        void tick() {
            if (open) {
                openTicks++;
                if (closeAfter >= 0 && openTicks >= closeAfter) {
                    // The server closes it, and gives what was in the grid back to the inventory
                    open = false;
                    for (int s = 1; s <= 9; s++) giveBack(s);
                }
            }
            if (pendingIn > 0 && --pendingIn == 0) {
                for (int s = 1; s <= 9; s++) {
                    if (grid[s] == null) continue;
                    if (--counts[s] == 0) grid[s] = null;
                }
            }
        }

        private void giveBack(int s) {
            if (grid[s] == null) return;
            inv.merge(grid[s], counts[s], Integer::sum);
            grid[s] = null;
            counts[s] = 0;
        }

        /** What the grid makes now, or null. */
        Recipes.@Nullable Recipe result() {
            if (!gives || pendingIn > 0) return null;
            for (Recipes.Recipe r : recipes) {
                int[] at = CraftGrid.slots(r);
                boolean ok = true;
                boolean[] used = new boolean[10];
                for (int i = 0; i < at.length && ok; i++) {
                    used[at[i]] = true;
                    ok = grid[at[i]] != null && r.ingredients().get(i).contains(grid[at[i]]);
                }
                for (int s = 1; s <= 9 && ok; s++) if (!used[s] && grid[s] != null) ok = false;
                if (ok) return r;
            }
            return null;
        }

        int gridTotal() {
            int n = 0;
            for (int s = 1; s <= 9; s++) n += counts[s];
            return n;
        }

        @Override
        public boolean arrived() {
            return reachable && walks > 0 && --walkTicks <= 0;
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
            opensTried++;
            if (opens) open = true;
            return true;
        }

        @Override
        public boolean busy() {
            return busyForever && open;
        }

        /** Clicks that never finish once the window is open (the proxy threw on one, say). */
        boolean busyForever;

        @Override
        public Map<String, Integer> carried() {
            Map<String, Integer> out = new HashMap<>();
            inv.forEach((k, v) -> {
                if (v > 0) out.put(k, v);
            });
            return out;
        }

        @Override
        public @Nullable String itemAt(int slot) {
            if (slot == 0) {
                Recipes.Recipe r = result();
                return r == null ? null : r.result();
            }
            return grid[slot];
        }

        @Override
        public int countAt(int slot) {
            if (slot == 0) {
                Recipes.Recipe r = result();
                return r == null ? 0 : r.count();
            }
            return counts[slot];
        }

        @Override
        public boolean place(int gridSlot, String item, int goal) {
            if (!open) return false;
            places++;
            int have = inv.getOrDefault(item, 0);
            int add = Math.min(goal - counts[gridSlot], have);
            if (add <= 0) return false;
            inv.merge(item, -add, Integer::sum);
            grid[gridSlot] = item;
            counts[gridSlot] += add;
            return true;
        }

        @Override
        public int take(String item, int count) {
            if (full || !open) return -1;
            takes++;
            Recipes.Recipe r = result();
            if (r == null) return 7;
            if (takeWorks) {
                inv.merge(r.result(), r.count(), Integer::sum);
                pendingIn = lag;
            }
            return 7;
        }

        @Override
        public int retrieve(int gridSlot) {
            if (full || !open) return -1;
            retrieves++;
            giveBack(gridSlot);
            return 8;
        }

        /** A stack on the cursor (a take the server turned down halfway). */
        boolean cursor;
        int stowed;

        @Override
        public boolean holdingCursor() {
            return cursor;
        }

        @Override
        public int stowCursor() {
            if (full) return -1;
            cursor = false;
            stowed++;
            return 9;
        }

        @Override
        public boolean close() {
            if (!open) return true;
            if (gridTotal() > 0) closedWithItems = true;
            for (int s = 1; s <= 9; s++) giveBack(s);
            open = false;
            return true;
        }
    }

    static CraftPlanner.Plan fromLogs(int n) {
        CraftPlanner.Plan p = CraftPlanner.plan(R, "minecraft:shulker_box", n, CraftPlannerTest.pool("minecraft:shulker_shell", 2 * n, "minecraft:oak_log", 2 * n), CraftPlanner.Limits.open());
        assertTrue(p.ok(), p.refusal());
        return p;
    }

    static FakeTable table(CraftPlanner.Plan p) {
        return new FakeTable(p.steps().stream().map(CraftPlanner.Step::recipe).toList());
    }

    /** Ticks the trip until it is done (or the budget runs out). */
    static CraftTrip.Outcome run(CraftTrip t, FakeTable f, int budget) {
        for (int i = 0; i < budget && t.step() != CraftTrip.Step.Done; i++) {
            f.tick();
            t.tick();
        }
        assertEquals(CraftTrip.Step.Done, t.step(), "the trip ended");
        return t.outcome();
    }

    @Test
    void aShulkerBoxFromLogs() {
        CraftPlanner.Plan p = fromLogs(1);
        FakeTable f = table(p).carry("minecraft:shulker_shell", 2).carry("minecraft:oak_log", 2);
        CraftTrip t = new CraftTrip(f, p);
        CraftTrip.Outcome o = run(t, f, 2000);
        assertNull(o.failure());
        assertEquals(1, o.made());
        assertEquals(1, f.inv.get("minecraft:shulker_box"));
        assertEquals(0, f.inv.getOrDefault("minecraft:oak_log", 0));
        assertEquals(0, f.inv.getOrDefault("minecraft:oak_planks", 0));
        assertEquals(0, f.inv.getOrDefault("minecraft:chest", 0));
        assertEquals(0, f.inv.getOrDefault("minecraft:shulker_shell", 0));
        assertFalse(f.open);
        assertFalse(f.closedWithItems);
        // Planks twice (one log each), a chest, the box: one take each
        assertEquals(4, f.takes);
    }

    @Test
    void twoAtOnce() {
        CraftPlanner.Plan p = fromLogs(2);
        FakeTable f = table(p).carry("minecraft:shulker_shell", 4).carry("minecraft:oak_log", 4);
        CraftTrip.Outcome o = run(new CraftTrip(f, p), f, 4000);
        assertNull(o.failure());
        assertEquals(2, o.made());
        assertEquals(2, f.inv.get("minecraft:shulker_box"));
        assertFalse(f.closedWithItems);
    }

    @Test
    void aTableOutOfReachOrThatWontOpenIsBad() {
        CraftPlanner.Plan p = fromLogs(1);
        FakeTable far = table(p);
        far.reachable = false;
        CraftTrip.Outcome o = run(new CraftTrip(far, p), far, CraftTrip.WALK_TICKS + 100);
        assertEquals(CraftTrip.NOT_REACHED, o.failure());
        assertTrue(o.tableBad());
        FakeTable shut = table(p);
        shut.opens = false;
        CraftTrip.Outcome o2 = run(new CraftTrip(shut, p), shut, CraftTrip.OPEN_TICKS + 200);
        assertEquals(CraftTrip.NOT_OPENED, o2.failure());
        assertTrue(o2.tableBad());
    }

    @Test
    void noResultInTimeTakesEverythingBackOutBeforeClosing() {
        CraftPlanner.Plan p = fromLogs(1);
        FakeTable f = table(p).carry("minecraft:shulker_shell", 2).carry("minecraft:oak_log", 2);
        f.gives = false;
        CraftTrip.Outcome o = run(new CraftTrip(f, p), f, 2000);
        assertEquals(CraftTrip.NO_RESULT, o.failure());
        assertTrue(o.tableBad());
        assertFalse(f.closedWithItems);
        assertTrue(f.retrieves > 0);
        assertEquals(2, f.inv.get("minecraft:oak_log"));
        assertEquals(0, o.made());
    }

    @Test
    void aTakeTheServerRefusesEndsIt() {
        CraftPlanner.Plan p = fromLogs(1);
        FakeTable f = table(p).carry("minecraft:shulker_shell", 2).carry("minecraft:oak_log", 2);
        f.takeWorks = false;
        CraftTrip.Outcome o = run(new CraftTrip(f, p), f, 2000);
        assertEquals(CraftTrip.NOT_TAKEN, o.failure());
        assertFalse(f.closedWithItems);
    }

    @Test
    void withNoRoomTheGridIsNeverClosedOnItIsLeftOpen() {
        CraftPlanner.Plan p = fromLogs(1);
        FakeTable f = table(p).carry("minecraft:shulker_shell", 2).carry("minecraft:oak_log", 2);
        f.gives = false;
        f.full = true;
        CraftTrip.Outcome o = run(new CraftTrip(f, p), f, CraftTrip.RESULT_TICKS + CraftTrip.RETRIEVE_TICKS + 500);
        assertTrue(o.stuck());
        assertTrue(f.open, "left open for a person");
        assertFalse(f.closedWithItems);
        assertTrue(f.gridTotal() > 0);
    }

    @Test
    void anAbortEmptiesTheGridAndMayBeTriedAgain() {
        CraftPlanner.Plan p = fromLogs(1);
        FakeTable f = table(p).carry("minecraft:shulker_shell", 2).carry("minecraft:oak_log", 2);
        f.gives = false;
        CraftTrip t = new CraftTrip(f, p);
        for (int i = 0; i < 200 && f.gridTotal() == 0; i++) {
            f.tick();
            t.tick();
        }
        assertTrue(f.gridTotal() > 0, "something is in the grid");
        t.abort("gave way");
        CraftTrip.Outcome o = run(t, f, 500);
        assertEquals("gave way", o.failure());
        assertTrue(o.retry());
        assertFalse(f.closedWithItems);
        assertEquals(2, f.inv.get("minecraft:oak_log"));
    }

    @Test
    void theServerClosingItIsCutShort() {
        CraftPlanner.Plan p = fromLogs(1);
        FakeTable f = table(p).carry("minecraft:shulker_shell", 2).carry("minecraft:oak_log", 2);
        f.gives = false;
        f.closeAfter = 30;
        CraftTrip.Outcome o = run(new CraftTrip(f, p), f, 500);
        assertEquals(CraftTrip.CLOSED, o.failure());
        assertTrue(o.retry());
        // The server gave the grid back
        assertEquals(2, f.inv.get("minecraft:oak_log"));
    }

    @Test
    void missingInputsAreSaid() {
        CraftPlanner.Plan p = fromLogs(1);
        FakeTable f = table(p).carry("minecraft:shulker_shell", 2);
        CraftTrip.Outcome o = run(new CraftTrip(f, p), f, 500);
        assertTrue(o.failure().startsWith(CraftTrip.MISSING), o.failure());
        assertFalse(o.retry());
    }

    @Test
    void tableHealthRemembersTroubleForTheTask() {
        CraftHealth h = new CraftHealth();
        h.note("o1", "t", new CraftTrip.Outcome(CraftTrip.NOT_OPENED, false, true, false, 0));
        assertEquals(CraftTrip.NOT_OPENED, h.problem("o1", "t"));
        assertNull(h.problem("o2", "t"));
        h.note("o3", "t", new CraftTrip.Outcome(CraftTrip.CLOSED, true, false, false, 0));
        assertNull(h.problem("o3", "t"));
        h.note("o3", "t", new CraftTrip.Outcome(CraftTrip.CLOSED, true, false, false, 0));
        assertEquals(CraftTrip.CLOSED, h.problem("o3", "t"));
        h.crafted(1000, 3);
        h.crafted(2000, 2);
        assertEquals(5, h.lastHour(3000));
        assertEquals(2, h.lastHour(1000 + CraftHealth.HOUR_MS));
    }
}
