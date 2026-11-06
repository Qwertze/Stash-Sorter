package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.8.1 final review fixes on the pure parts: the table hold backs off and lets go of its own inputs; place requests counted. */
class FinalReviewTest {
    @Test
    void theAlertsAboutAHeldTableBackOff() {
        assertEquals(1, CraftSlots.alertMinutes(0));
        assertEquals(5, CraftSlots.alertMinutes(1));
        assertEquals(15, CraftSlots.alertMinutes(2));
        assertEquals(75, CraftSlots.alertMinutes(3));
        assertEquals(135, CraftSlots.alertMinutes(4));
        // Over a night (10 hours) that is 12 alerts, not 600
        int n = 0;
        while (CraftSlots.alertMinutes(n) <= 600) n++;
        assertEquals(12, n);
        assertTrue(CraftSlots.STUCK_ESCAPE_MINUTES <= 20);
    }

    @Test
    void onlyTheCraftsOwnInputsAreLetGoOf() {
        Set<String> craftIds = Recipes.shipped().inputsOf("minecraft:shulker_box", CraftPlanner.MAX_DEPTH);
        assertTrue(CraftSlots.ownInputsOnly(List.of("minecraft:oak_planks", "minecraft:chest", "minecraft:shulker_shell"), craftIds));
        assertTrue(CraftSlots.ownInputsOnly(List.of("minecraft:oak_log|abcd"), craftIds), "a named log too");
        assertFalse(CraftSlots.ownInputsOnly(List.of("minecraft:oak_planks", "minecraft:diamond"), craftIds));
    }

    @Test
    void placeRequestsThatDidNotGoOutAreNotCounted() {
        CraftPlanner.Plan p = CraftTripTest.fromLogs(1);
        CraftTripTest.FakeTable f = new CraftTripTest.FakeTable(p.steps().stream().map(CraftPlanner.Step::recipe).toList()) {
            int refusals = 50;

            @Override
            public boolean place(int gridSlot, String item, int goal) {
                // The proxy turns the first 50 requests down (another module is ahead): none of them counts
                if (refusals-- > 0) return false;
                return super.place(gridSlot, item, goal);
            }
        };
        f.carry("minecraft:shulker_shell", 2).carry("minecraft:oak_log", 2);
        CraftTrip.Outcome o = CraftTripTest.run(new CraftTrip(f, p), f, 4000);
        assertEquals(null, o.failure());
        assertEquals(1, o.made());
    }
}
