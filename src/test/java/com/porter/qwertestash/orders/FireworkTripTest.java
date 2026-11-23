package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A trip to the crafting table for rockets and stars on a fake game (CRAFT-CONTRACT-v1.2.md 15): the exact grid of the step is placed (the stars of the right
 * spec, picked among the carried ones), the result's spec is derived and compared with the step's before it is taken, and on a mismatch the grid is
 * taken back and the trip ends with {@code recipe_mismatch}. The fake follows the rule the contract assumes [UNVERIFIED in game]: colours and explosions
 * are appended in ascending grid-slot order; {@code reverse} makes it do the opposite, to play the mismatch.
 */
class FireworkTripTest {
    private static final FireworkSpecs.Tables T = FireworkFixtures.TABLES;
    private static final String PAPER = "minecraft:paper", GP = "minecraft:gunpowder", STAR = "minecraft:firework_star", ROCKET = "minecraft:firework_rocket";

    /** A crafting table whose result is worked out from the grid by the rules of fireworks. */
    static final class FireworkTable implements CraftTrip.Port {
        final Map<String, Integer> inv = new LinkedHashMap<>();
        final Map<String, String> specOfKind = new HashMap<>();
        final String[] grid = new String[10];
        final int[] counts = new int[10];
        boolean open, reverse, closedWithItems;
        int walkTicks = 2, walks, takes, lag = 2, pendingIn = -1, serial;
        /** The result slot shows another spec for this many ticks (a late update from the server). */
        int staleTicks;

        record Out(String kind, String spec, int count) {
        }

        FireworkTable carry(String id, int n) {
            inv.merge(id, n, Integer::sum);
            return this;
        }

        /** A stack of stars (or rockets) of a spec: a kind with a component hash, as a patched stack has. */
        String carrySpec(String id, String spec, int n) {
            String kind = kindFor(id, spec);
            inv.merge(kind, n, Integer::sum);
            return kind;
        }

        String kindFor(String id, String spec) {
            for (var e : specOfKind.entrySet()) if (e.getValue().equals(spec) && e.getKey().startsWith(id + "|")) return e.getKey();
            String kind = id + "|v" + (++serial);
            specOfKind.put(kind, spec);
            return kind;
        }

        void tick() {
            if (staleTicks > 0) staleTicks--;
            if (pendingIn > 0 && --pendingIn == 0) {
                for (int s = 1; s <= 9; s++) {
                    if (grid[s] == null) continue;
                    if (--counts[s] == 0) grid[s] = null;
                }
            }
        }

        private static String base(String kind) {
            return CraftSlots.baseOf(kind);
        }

        private static String dye(String id) {
            return id.endsWith("_dye") ? id.substring("minecraft:".length(), id.length() - "_dye".length()) : null;
        }

        /** What the grid makes now by the rules of fireworks, or null. */
        @Nullable Out result() {
            if (pendingIn > 0) return null;
            int paper = 0, gunpowder = 0;
            List<String> dyes = new ArrayList<>(), stars = new ArrayList<>(), shapes = new ArrayList<>();
            boolean trail = false, twinkle = false, other = false;
            for (int s = 1; s <= 9; s++) {
                if (grid[s] == null) continue;
                String id = base(grid[s]);
                if (id.equals(PAPER)) paper++;
                else if (id.equals(GP)) gunpowder++;
                else if (id.equals(STAR)) stars.add(specOfKind.getOrDefault(grid[s], "?"));
                else if (dye(id) != null) dyes.add(dye(id));
                else if (id.equals("minecraft:diamond")) trail = true;
                else if (id.equals("minecraft:glowstone_dust")) twinkle = true;
                else {
                    String role = null;
                    for (var r : T.starItems().entrySet()) if (!r.getKey().equals("trail") && !r.getKey().equals("twinkle") && r.getValue().contains(id)) role = r.getKey();
                    if (role == null) other = true;
                    else shapes.add(role);
                }
            }
            if (other) return null;
            List<String> order = new ArrayList<>(dyes);
            if (reverse) java.util.Collections.reverse(order);
            if (paper == 1 && gunpowder >= 1 && gunpowder <= 3 && dyes.isEmpty() && shapes.isEmpty() && !trail && !twinkle && stars.size() <= 8 - gunpowder) {
                List<String> bodies = new ArrayList<>();
                for (String st : stars) bodies.add(st.substring("star:".length()));
                if (reverse) java.util.Collections.reverse(bodies);
                String spec = "rocket:" + gunpowder + (bodies.isEmpty() ? "" : "|" + String.join("|", bodies));
                return new Out(spec.equals("rocket:1") ? ROCKET : kindFor(ROCKET, spec), spec, 3);
            }
            if (paper == 0 && gunpowder == 1 && stars.isEmpty() && !order.isEmpty() && shapes.size() <= 1) {
                String shape = shapes.isEmpty() ? "small_ball" : shapes.get(0);
                String spec = "star:" + shape + ";" + String.join(",", order) + ";;" + (trail ? "t" : "") + (twinkle ? "w" : "");
                return new Out(kindFor(STAR, spec), spec, 1);
            }
            if (paper == 0 && gunpowder == 0 && stars.size() == 1 && !order.isEmpty() && shapes.isEmpty() && !trail && !twinkle) {
                FireworkSpecs.Parsed p = FireworkSpecs.parse(stars.get(0));
                if (p == null) return null;
                FireworkSpecs.Body b = p.stars().get(0);
                String spec = "star:" + b.shape() + ";" + String.join(",", b.colors()) + ";" + String.join(",", order) + ";" + (b.trail() ? "t" : "") + (b.twinkle() ? "w" : "");
                return new Out(kindFor(STAR, spec), spec, 1);
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
                return r == null ? null : staleTicks > 0 ? "star:stale;white;;" : r.spec();
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
            return true;
        }

        @Override
        public int take(String item, int count) {
            takes++;
            Out r = result();
            if (r == null) return 7;
            assertEquals(base(r.kind()), item, "the trip names the item's id, not its pseudo id");
            inv.merge(r.kind(), r.count(), Integer::sum);
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

    private static CraftPlanner.Step step(FireworkFixtures.Grid g, int fills) {
        Order.CraftStep s = g.step(fills);
        Map<String, Long> uses = new LinkedHashMap<>();
        for (Order.Qty q : s.inputs()) uses.put(q.ref(), (long) q.count());
        return new CraftPlanner.Step(FireworkSpecs.recipeOf(s), fills, 0, uses);
    }

    private static CraftPlanner.Plan plan(String itemRef, int count, CraftPlanner.Step... steps) {
        int fills = 0;
        for (CraftPlanner.Step s : steps) fills += s.fills();
        return new CraftPlanner.Plan(itemRef, count, List.of(steps), Map.of(), fills, null);
    }

    private static CraftTrip.Outcome run(CraftTrip t, FireworkTable f) {
        for (int i = 0; i < 4000 && t.step() != CraftTrip.Step.Done; i++) {
            f.tick();
            t.tick();
        }
        assertEquals(CraftTrip.Step.Done, t.step(), "the trip ended");
        return t.outcome();
    }

    private static int count(FireworkTable f, String id, String spec) {
        int n = 0;
        for (var e : f.inv.entrySet()) if (e.getKey().startsWith(id) && (spec == null || spec.equals(f.specOfKind.get(e.getKey())))) n += e.getValue();
        return n;
    }

    @Test
    void aStarIsMadeFromItsExactGrid() {
        FireworkFixtures.Grid g = FireworkFixtures.grid(FireworkSpecs.RECIPE_STAR, "star:large_ball;red,blue;;t");
        FireworkTable f = new FireworkTable().carry(GP, 2).carry("minecraft:fire_charge", 2).carry("minecraft:diamond", 2).carry("minecraft:red_dye", 2).carry("minecraft:blue_dye", 2);
        String ref = STAR + "@star:large_ball;red,blue;;t";
        CraftTrip.Outcome o = run(new CraftTrip(f, plan(ref, 2, step(g, 2))), f);
        assertNull(o.failure());
        assertEquals(2, o.made());
        assertEquals(2, count(f, STAR, "star:large_ball;red,blue;;t"));
        assertEquals(0, f.gridTotal());
        assertFalse(f.closedWithItems);
        assertEquals(2, f.takes);
    }

    @Test
    void aResultThatShowsTheRightSpecAfterAFewTicksIsTaken_oneThatNeverDoesIsAMismatch() {
        FireworkFixtures.Grid g = FireworkFixtures.grid(FireworkSpecs.RECIPE_STAR, "star:large_ball;red,blue;;t");
        String ref = STAR + "@star:large_ball;red,blue;;t";
        FireworkTable late = new FireworkTable().carry(GP, 1).carry("minecraft:fire_charge", 1).carry("minecraft:diamond", 1).carry("minecraft:red_dye", 1).carry("minecraft:blue_dye", 1);
        late.staleTicks = 8;
        CraftTrip.Outcome ok = run(new CraftTrip(late, plan(ref, 1, step(g, 1))), late);
        assertNull(ok.failure());
        assertEquals(1, ok.made());
        FireworkTable never = new FireworkTable().carry(GP, 1).carry("minecraft:fire_charge", 1).carry("minecraft:diamond", 1).carry("minecraft:red_dye", 1).carry("minecraft:blue_dye", 1);
        never.staleTicks = 100_000;
        CraftTrip.Outcome bad = run(new CraftTrip(never, plan(ref, 1, step(g, 1))), never);
        assertEquals(CraftTrip.SPEC_MISMATCH, bad.failure());
        assertEquals(0, never.takes);
        assertEquals(0, never.gridTotal());
    }

    @Test
    void aRocketTakesTheStarOfItsSpecAmongTheCarriedOnes() {
        FireworkFixtures.Grid g = FireworkFixtures.grid(FireworkSpecs.RECIPE_ROCKET, "rocket:2|small_ball;red;;");
        FireworkTable f = new FireworkTable().carry(PAPER, 3).carry(GP, 6);
        String blue = f.carrySpec(STAR, "star:small_ball;blue;;", 3);
        String red = f.carrySpec(STAR, "star:small_ball;red;;", 3);
        String ref = ROCKET + "@rocket:2|small_ball;red;;";
        CraftTrip.Outcome o = run(new CraftTrip(f, plan(ref, 6, step(g, 2))), f);
        assertNull(o.failure());
        assertEquals(6, o.made(), "3 rockets a fill");
        assertEquals(6, count(f, ROCKET, "rocket:2|small_ball;red;;"));
        assertEquals(1, f.inv.get(red), "two red stars went in");
        assertEquals(3, f.inv.get(blue), "the blue ones were never touched");
        assertEquals(1, f.inv.get(PAPER));
        assertEquals(2, f.inv.get(GP));
        assertEquals(0, f.gridTotal());
    }

    @Test
    void aStarIsMadeAndThenUsedByTheRocketInTheSameTrip() {
        FireworkFixtures.Grid star = FireworkFixtures.grid(FireworkSpecs.RECIPE_STAR, "star:star;lime;;");
        FireworkFixtures.Grid rocket = FireworkFixtures.grid(FireworkSpecs.RECIPE_ROCKET, "rocket:1|star;lime;;");
        FireworkTable f = new FireworkTable().carry(PAPER, 1).carry(GP, 2).carry("minecraft:gold_nugget", 1).carry("minecraft:lime_dye", 1);
        String ref = ROCKET + "@rocket:1|star;lime;;";
        CraftTrip.Outcome o = run(new CraftTrip(f, plan(ref, 3, step(star, 1), step(rocket, 1))), f);
        assertNull(o.failure());
        assertEquals(3, o.made());
        assertEquals(3, count(f, ROCKET, "rocket:1|star;lime;;"));
        assertEquals(0, count(f, STAR, null), "the star was used up");
        assertEquals(2, f.takes);
    }

    @Test
    void aPlainRocketIsTheItemWithNoComponents() {
        FireworkFixtures.Grid g = FireworkFixtures.grid(FireworkSpecs.RECIPE_ROCKET, "rocket:1");
        FireworkTable f = new FireworkTable().carry(PAPER, 2).carry(GP, 2);
        CraftTrip.Outcome o = run(new CraftTrip(f, plan(ROCKET + "@rocket:1", 6, step(g, 2))), f);
        assertNull(o.failure());
        assertEquals(6, o.made());
        assertEquals(6, f.inv.get(ROCKET), "the game's rocket with the default flight has no patch (UNVERIFIED in game): the plain kind");
    }

    @Test
    void aResultOfAnotherSpecIsNotTakenTheGridGoesBackAndTheLineIsRecipeMismatch() {
        // The game appends the colours the other way round (the contract's assumption is wrong): red,blue is made as blue,red
        FireworkFixtures.Grid g = FireworkFixtures.grid(FireworkSpecs.RECIPE_STAR, "star:large_ball;red,blue;;t");
        FireworkTable f = new FireworkTable().carry(GP, 2).carry("minecraft:fire_charge", 2).carry("minecraft:diamond", 2).carry("minecraft:red_dye", 2).carry("minecraft:blue_dye", 2);
        f.reverse = true;
        CraftTrip t = new CraftTrip(f, plan(STAR + "@star:large_ball;red,blue;;t", 2, step(g, 2)));
        CraftTrip.Outcome o = run(t, f);
        assertEquals(CraftTrip.SPEC_MISMATCH, o.failure());
        assertEquals(CraftOrder.RECIPE_MISMATCH, CraftOrder.reasonOf(o.failure(), o.stuck(), false));
        assertFalse(o.retry());
        assertFalse(o.tableBad());
        assertFalse(o.stuck());
        assertEquals(0, o.made());
        assertEquals(0, f.takes, "nothing was taken from the result slot");
        assertEquals(0, f.gridTotal(), "the grid is back in the inventory");
        assertFalse(f.closedWithItems);
        assertFalse(f.open);
        assertEquals(2, f.inv.get(GP));
        assertEquals(2, f.inv.get("minecraft:red_dye"));
        assertEquals(2, f.inv.get("minecraft:blue_dye"));
        assertEquals(2, f.inv.get("minecraft:fire_charge"));
        assertEquals(0, count(f, STAR, null));
    }

    @Test
    void aRocketWhoseStarsComeOutInAnotherOrderIsAMismatchToo() {
        FireworkFixtures.Grid g = FireworkFixtures.grid(FireworkSpecs.RECIPE_ROCKET, "rocket:2|small_ball;red;;|large_ball;blue;;");
        FireworkTable f = new FireworkTable().carry(PAPER, 1).carry(GP, 2);
        f.carrySpec(STAR, "star:small_ball;red;;", 1);
        f.carrySpec(STAR, "star:large_ball;blue;;", 1);
        f.reverse = true;
        CraftTrip.Outcome o = run(new CraftTrip(f, plan(ROCKET + "@rocket:2|small_ball;red;;|large_ball;blue;;", 3, step(g, 1))), f);
        assertEquals(CraftTrip.SPEC_MISMATCH, o.failure());
        assertEquals(0, f.gridTotal());
        assertEquals(0, count(f, ROCKET, null));
        assertEquals(1, f.inv.get(PAPER));
        // In the order the contract assumes it is made, and taken
        FireworkTable g2 = new FireworkTable().carry(PAPER, 1).carry(GP, 2);
        g2.carrySpec(STAR, "star:small_ball;red;;", 1);
        g2.carrySpec(STAR, "star:large_ball;blue;;", 1);
        CraftTrip.Outcome ok = run(new CraftTrip(g2, plan(ROCKET + "@rocket:2|small_ball;red;;|large_ball;blue;;", 3, step(g, 1))), g2);
        assertNull(ok.failure());
        assertEquals(3, ok.made());
    }

    @Test
    void aStarOfAnotherSpecIsNotUsedAndTheStepIsMissingItsInput() {
        FireworkFixtures.Grid g = FireworkFixtures.grid(FireworkSpecs.RECIPE_ROCKET, "rocket:2|small_ball;red;;");
        FireworkTable f = new FireworkTable().carry(PAPER, 1).carry(GP, 2);
        f.carrySpec(STAR, "star:small_ball;blue;;", 5);
        CraftTrip.Outcome o = run(new CraftTrip(f, plan(ROCKET + "@rocket:2|small_ball;red;;", 3, step(g, 1))), f);
        assertTrue(o.failure().startsWith(CraftTrip.MISSING), o.failure());
        assertEquals(CraftOrder.INGREDIENTS_SHORT, CraftOrder.reasonOf(o.failure(), false, false));
        assertEquals(0, f.takes);
        assertEquals(0, f.gridTotal());
    }

    @Test
    void theGridKeepsTheStepsOrderWithEmptyCellsLast() {
        FireworkFixtures.Grid g = FireworkFixtures.grid(FireworkSpecs.RECIPE_ROCKET, "rocket:3|star;lime;;|burst;white,black;red;w");
        FireworkTable f = new FireworkTable().carry(PAPER, 1).carry(GP, 3);
        f.carrySpec(STAR, "star:star;lime;;", 1);
        f.carrySpec(STAR, "star:burst;white,black;red;w", 1);
        CraftTrip t = new CraftTrip(f, plan(ROCKET + "@rocket:3|star;lime;;|burst;white,black;red;w", 3, step(g, 1)));
        CraftTrip.Outcome o = run(t, f);
        assertNull(o.failure());
        assertEquals(3, count(f, ROCKET, "rocket:3|star;lime;;|burst;white,black;red;w"));
        assertNotNull(f.specOfKind);
    }
}
