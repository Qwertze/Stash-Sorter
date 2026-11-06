package com.porter.qwertestash.orders;

import com.porter.qwertestash.stash.AnvilFinder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 1.8.1 on the order machine: without an anvil set, one found or put down names the shulkers; with none, they go unnamed. */
class AnvilAutoFindTest {
    private static final String D = "minecraft:diamond", RED = "minecraft:red_shulker_box";

    private static OrderSim world() {
        OrderSim w = new OrderSim().stock(D, "v", 100_000, 64);
        w.empties.put(RED, 5);
        w.anvil = false;
        w.noAnvilReason = AnvilFinder.NO_ANVILS;
        return w;
    }

    private static Order named(String id) {
        return new Order(id, List.of(new Order.Line("item", D, 64 * 30)), ShulkerNames.all("Gems"));
    }

    @Test
    void oneStandingInTheStashAreaIsFoundAndUsed() {
        OrderSim w = world();
        w.anvilStanding = true;
        assertNull(w.flow.offer(named("o-found")));
        w.run(100);
        assertEquals("delivered", w.flow.report().status());
        assertEquals("found", w.anvilHow);
        assertEquals(List.of("Gems", "Gems"), w.named);
    }

    @Test
    void withNoneStandingOneFromTheStashIsPutDown() {
        OrderSim w = world();
        w.anvilInStock = true;
        assertNull(w.flow.offer(named("o-placed")));
        w.run(100);
        assertEquals("placed", w.anvilHow);
        assertEquals(2, w.named.size());
    }

    @Test
    void withNoAnvilsAtAllTheOrderIsStillDeliveredUnnamedWithTheReason() {
        OrderSim w = world();
        assertNull(w.flow.offer(named("o-none")));
        w.run(100);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertEquals(2, r.shulkers());
        assertEquals(0, w.anvilTrips);
        assertEquals("2 shulker(s) delivered unnamed: no anvils available to rename with", r.message());
    }
}
