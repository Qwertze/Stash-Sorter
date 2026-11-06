package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.7.2: "Kit #1" on 1.7.1 ended "nothing could be delivered: not enough left to pack; replanned a shulker: ..." although
 * its shulker held part of the kit. A shulker given up on with something in it now goes out short.
 */
class ShortDeliveryTest {
    private static final String D = "minecraft:diamond", ARROW = "minecraft:arrow", P = "minecraft:ender_pearl", RED = "minecraft:red_shulker_box";

    private static Order.Line item(String key, int count) {
        return new Order.Line("item", key, count);
    }

    private static OrderSim world() {
        OrderSim w = new OrderSim().stock(D, "v", 1000, 64).stock(ARROW, "a", 64, 64).stock(P, "p", 64, 16);
        w.empties.put(RED, 2);
        return w;
    }

    @Test
    void aPlannerRunnerDisagreementGivesAShortDeliveryNotNothing() {
        OrderSim w = world();
        // The plan counts the arrows; the run won't take them (1.7.1: the container was stalled)
        w.runnerRefuses.add(ARROW);
        assertNull(w.flow.offer(new Order("kit-1", List.of(item(D, 128), item(ARROW, 64), item(P, 16)))));
        w.run(100);

        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertFalse(r.message().startsWith("nothing could be delivered"), r.message());
        assertTrue(r.message().contains("shulker 1 delivered short: not enough left to pack"), r.message());
        assertTrue(r.message().contains("delivered less than asked: " + ARROW + " 0/64"), r.message());
        assertEquals(1, r.shulkers());
        assertEquals(128, r.delivered().get(D));
        assertEquals(16, r.delivered().get(P));
        assertNull(r.delivered().get(ARROW));
    }

    @Test
    void aShortShulkerIsStillNamedAndDelivered() {
        OrderSim w = world();
        w.runnerRefuses.add(ARROW);
        assertNull(w.flow.offer(new Order("kit-2", List.of(item(D, 64), item(ARROW, 64)), ShulkerNames.all("Kit"))));
        w.run(100);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(List.of("Kit"), w.named);
        assertEquals("Kit", w.delivery.get(0).name());
        assertFalse(r.message().contains("unnamed"), r.message());
    }

    @Test
    void theShortContentsAreSavedSoARestartKnowsTheShulker() {
        OrderSim w = world();
        w.runnerRefuses.add(ARROW);
        Order o = new Order("kit-3", List.of(item(D, 64), item(ARROW, 64)));
        assertNull(w.flow.offer(o));
        // One step: the shulker is built short and carried, not delivered yet
        w.step();
        assertEquals(1, w.carried.size());
        assertEquals(List.of(D), w.saved.units.get(0).lotKeys);
        assertEquals(List.of(64), w.saved.units.get(0).lotCounts);

        // The bot goes offline and comes back: the saved plan is resumed, the carried shulker is the unit's and is delivered
        w.flow.reset();
        assertNull(w.flow.offer(o));
        int diamondsBefore = w.loose.get(D).get("v");
        w.run(100);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(64, r.delivered().get(D));
        assertEquals(diamondsBefore, (int) w.loose.get(D).get("v"), "not built a second time");
        assertEquals(0, w.carried.size());
    }

    @Test
    void aShulkerGivenUpOnEmptyIsStillReplannedThenSkipped() {
        OrderSim w = world();
        // Nothing of the bin can be taken: as before, one replan, then the unit is skipped
        w.runnerRefuses.add(D);
        assertNull(w.flow.offer(new Order("kit-4", List.of(item(D, 64)))));
        w.run(100);
        OrderFlow.Report r = w.flow.report();
        assertEquals("failed", r.status());
        assertTrue(r.message().startsWith("nothing could be delivered"), r.message());
    }

    @Test
    void theHostIsToldBeforeEveryPlan() {
        OrderSim w = world();
        assertNull(w.flow.offer(new Order("kit-5", List.of(item(D, 64)))));
        assertEquals(1, w.plannings, "claimed: the sorter forgets what it gave up on for now before the plan");
    }
}
