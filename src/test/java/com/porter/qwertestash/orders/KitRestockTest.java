package com.porter.qwertestash.orders;

import com.porter.qwertestash.orders.KitRestock.Candidate;
import com.porter.qwertestash.orders.KitRestock.Decision;
import com.porter.qwertestash.orders.KitRestock.Inputs;
import com.porter.qwertestash.orders.KitRestock.State;
import com.porter.qwertestash.stash.KitTemplate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The restock decision (KIT-CONTRACT v2, revision 4, "Restock"). */
class KitRestockTest {
    private static KitTemplate template(String id, int keep) {
        List<KitTemplate.Slot> slots = new ArrayList<>();
        slots.add(new KitTemplate.Slot("minecraft:totem_of_undying", "minecraft:totem_of_undying", 1));
        slots.add(new KitTemplate.Slot("minecraft:end_crystal", "minecraft:end_crystal", 2));
        while (slots.size() < 27) slots.add(null);
        return new KitTemplate(id, "Kit " + id, "pvp", "1,64,1", null, keep, slots);
    }

    private static Candidate cand(String id, int keep, int complete, int incomplete, boolean topUp, boolean build) {
        return new Candidate(template(id, keep), "1,64,1", true, true, complete, incomplete, topUp, build, build, false);
    }

    private static Inputs in(Candidate... c) {
        return new Inputs(true, false, 0, 12, 5, 2, true, List.of(c));
    }

    @Test
    void offWhenDisabledAndIdleWhenNothingIsBelowKeep() {
        assertEquals(State.OFF, KitRestock.decide(new Inputs(false, false, 0, 12, 5, 2, true, List.of(cand("t-0000aaaa", 2, 0, 0, false, true)))).state());
        assertEquals(State.IDLE, KitRestock.decide(in(cand("t-0000aaaa", 2, 2, 0, false, true), cand("t-0000bbbb", 0, 0, 0, false, true))).state());
    }

    @Test
    void ordersAndTheCapComeFirst() {
        Candidate c = cand("t-0000aaaa", 2, 0, 0, false, true);
        Decision orders = KitRestock.decide(new Inputs(true, true, 0, 12, 5, 2, true, List.of(c)));
        assertEquals(State.WAITING, orders.state());
        assertEquals(KitRestock.ORDERS, orders.reason());
        Decision cap = KitRestock.decide(new Inputs(true, false, 12, 12, 5, 2, true, List.of(c)));
        assertEquals(KitRestock.CAP, cap.reason());
    }

    @Test
    void topUpsComeBeforeNewBuildsAcrossTemplates() {
        // The first template (most missing) could be built new; the second has an incomplete copy to top up: the top-up goes first
        Decision d = KitRestock.decide(in(cand("t-0000aaaa", 5, 0, 0, false, true), cand("t-0000bbbb", 2, 0, 1, true, true)));
        assertEquals(State.BUILDING, d.state());
        assertEquals("t-0000bbbb", d.template());
        assertTrue(d.topUp());
        assertEquals(KitRestock.TOPPING_UP, d.reason());
    }

    @Test
    void mostMissingFirstThenById() {
        Decision d = KitRestock.decide(in(cand("t-0000bbbb", 3, 1, 0, false, true), cand("t-0000aaaa", 3, 1, 0, false, true), cand("t-0000cccc", 5, 0, 0, false, true)));
        assertEquals("t-0000cccc", d.template());
        assertFalse(d.topUp());
        Decision tie = KitRestock.decide(in(cand("t-0000bbbb", 3, 1, 0, false, true), cand("t-0000aaaa", 3, 1, 0, false, true)));
        assertEquals("t-0000aaaa", tie.template());
    }

    @Test
    void anIncompleteCopyCountsNotTowardsKeepButBlocksANewBuildWhenTheSumReachesIt() {
        // keep 2, complete 1, incomplete 1: no new copy; nothing to top it up with: waiting for items
        Decision d = KitRestock.decide(in(cand("t-0000aaaa", 2, 1, 1, false, true)));
        assertEquals(State.WAITING, d.state());
        assertEquals(KitRestock.ITEMS_SHORT, d.reason());
    }

    @Test
    void reasonsForWhyItCannotBuild() {
        assertEquals(KitRestock.NO_SHULKER, KitRestock.decide(new Inputs(true, false, 0, 12, 1, 2, true, List.of(cand("t-0000aaaa", 2, 0, 0, false, true)))).reason());
        assertEquals(KitRestock.ITEMS_SHORT, KitRestock.decide(in(cand("t-0000aaaa", 2, 0, 0, false, false))).reason());
        Candidate bottles = new Candidate(template("t-0000aaaa", 2), "1,64,1", true, true, 0, 0, false, false, false, true);
        assertEquals(KitRestock.BOTTLE_RESERVE, KitRestock.decide(in(bottles)).reason());
        Candidate full = new Candidate(template("t-0000aaaa", 2), "1,64,1", true, false, 0, 0, false, true, true, false);
        Decision fd = KitRestock.decide(in(full));
        assertEquals(State.BLOCKED, fd.state());
        assertEquals(KitRestock.CHEST_FULL, fd.reason());
        Candidate none = new Candidate(template("t-0000aaaa", 2), null, false, false, 0, 0, false, true, true, false);
        assertEquals(KitRestock.CHEST_MISSING, KitRestock.decide(in(none)).reason());
        // Without an anvil it still builds, and says so
        Decision unnamed = KitRestock.decide(new Inputs(true, false, 0, 12, 5, 2, false, List.of(cand("t-0000aaaa", 2, 0, 0, false, true))));
        assertEquals(State.BUILDING, unnamed.state());
        assertEquals(KitRestock.NAMING, unnamed.reason());
        assertNull(KitRestock.decide(in(cand("t-0000aaaa", 2, 0, 0, false, true))).reason());
    }
}
