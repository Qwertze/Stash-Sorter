package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The exact-count click plans: replayed under the game's click rules they must leave exactly the goal and an empty cursor. */
class ClickPlannerTest {
    /** Replays the plan on a source slot, a destination slot and a cursor; returns {cursor, source, destination}. */
    private static int[] replay(ClickPlanner.Clicks plan, int max, int p0, int q0) {
        int cur = 0, p = p0, q = q0;
        for (int op : plan.ops()) {
            boolean right = op % 2 == 1;
            if (op < 2) {
                int[] r = ClickPlanner.click(cur, p, max, right);
                assertNotNull(r, "a click that does nothing");
                cur = r[0];
                p = r[1];
            }
            else {
                int[] r = ClickPlanner.click(cur, q, max, right);
                assertNotNull(r, "a click that does nothing");
                cur = r[0];
                q = r[1];
            }
        }
        return new int[] {cur, p, q};
    }

    @Test
    void everyReachableGoalLeavesExactlyThatManyAndAnEmptyCursor() {
        for (int max : new int[] {1, 16, 64}) {
            for (int p0 = 0; p0 <= max; p0 += Math.max(1, max / 8)) {
                for (int q0 = 0; q0 <= max; q0 += Math.max(1, max / 4)) {
                    for (int goal = q0; goal <= Math.min(max, q0 + p0); goal++) {
                        ClickPlanner.Clicks plan = ClickPlanner.plan(max, p0, q0, goal);
                        assertNotNull(plan, "max " + max + " p0 " + p0 + " q0 " + q0 + " goal " + goal);
                        int[] end = replay(plan, max, p0, q0);
                        assertEquals(0, end[0], "cursor");
                        assertEquals(goal, end[2], "destination");
                        assertEquals(p0 - (goal - q0), end[1], "source keeps the rest");
                    }
                }
            }
        }
    }

    @Test
    void aWholeStackIsTwoClicks() {
        ClickPlanner.Clicks plan = ClickPlanner.plan(64, 64, 0, 64);
        assertEquals(2, plan.ops().length);
    }

    @Test
    void halfAStackIsTwoClicks() {
        assertEquals(2, ClickPlanner.plan(64, 64, 0, 32).ops().length);
    }

    @Test
    void aSmallNumberIsFewClicks() {
        ClickPlanner.Clicks plan = ClickPlanner.plan(64, 64, 0, 3);
        assertNotNull(plan);
        assertTrue(plan.ops().length <= 6, "clicks " + plan.ops().length);
    }

    @Test
    void nothingToDoIsNoClicks() {
        assertEquals(0, ClickPlanner.plan(64, 10, 5, 5).ops().length);
    }

    @Test
    void impossibleGoalsAreRefused() {
        assertNull(ClickPlanner.plan(64, 10, 0, 11));
        assertNull(ClickPlanner.plan(64, 10, 0, 65));
        assertNull(ClickPlanner.plan(0, 1, 0, 1));
        assertNull(ClickPlanner.plan(64, 10, 70, 70));
    }

    @Test
    void topUpAddsToWhatIsThere() {
        ClickPlanner.Clicks plan = ClickPlanner.plan(64, 40, 30, 64);
        assertNotNull(plan);
        int[] end = replay(plan, 64, 40, 30);
        assertEquals(64, end[2]);
        assertEquals(6, end[1]);
    }
}
