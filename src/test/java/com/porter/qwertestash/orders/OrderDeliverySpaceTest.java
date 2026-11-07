package com.porter.qwertestash.orders;

import com.porter.qwertestash.orders.DeliveryBook.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An order and the delivery storage (1.9.0): fills the lowest chest, grows when full, waits when it can't, resumes. */
class OrderDeliverySpaceTest {
    private static final String RED = "minecraft:red_shulker_box";
    private static final Pos BASE = new Pos(0, 64, 0);

    private static Order order(String id, int shulkers) {
        return new Order(id, List.of(new Order.Line("item", RED, shulkers)));
    }

    private static OrderSim world(int cap, int shulkers) {
        OrderSim w = new OrderSim().storage(cap);
        w.empties.put(RED, shulkers);
        return w;
    }

    @Test
    void fillsTheLowestChestFirstThenPutsAnotherUp() {
        OrderSim w = world(2, 5);
        w.autoExpand = true;
        assertNull(w.flow.offer(order("o-1", 5)));
        w.run(300);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(5, r.shulkers());
        // 5 shulkers in chests of 2: three chests, two of them put up by the bot, the base filled first
        assertEquals(3, w.book.chests());
        assertEquals(2, w.expansions);
        assertEquals(2, w.inChest.get(BASE));
        assertEquals(2, w.inChest.get(new Pos(0, 65, 0)));
        assertEquals(1, w.inChest.get(new Pos(0, 66, 0)));
    }

    @Test
    void withAutoExpandOffAFullStoragePausesInsteadOfEnding() {
        OrderSim w = world(1, 3);
        w.flow.offer(order("o-2", 3));
        w.run(120);
        OrderFlow.Report r = w.flow.report();
        assertEquals("in_progress", r.status());
        assertEquals("paused: waiting for delivery space: delivery storage full (1 chest), auto expand is off", r.phase());
        assertEquals(1, r.shulkers());
        // The finished shulker is still in the bot's inventory
        assertEquals(1, w.carried.size());
    }

    @Test
    void aBlockedExpansionPausesWithTheReasonAndResumesWhenAPlayerClaimsTheChest() {
        OrderSim w = world(1, 2);
        w.autoExpand = true;
        w.expandBlocker = "stone is in the way above the top chest";
        w.flow.offer(order("o-3", 2));
        w.run(120);
        OrderFlow.Report r = w.flow.report();
        assertEquals("in_progress", r.status());
        assertEquals("paused: waiting for delivery space: delivery storage full: can't place higher: stone is in the way above the top chest", r.phase());

        // A player opens the chest, takes the shulker and closes it: the bot looks, finds room, delivers
        w.claim(BASE);
        w.run(120);
        r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(2, r.shulkers());
        assertEquals(0, w.expansions);
        assertTrue(w.rechecks >= 1);
    }

    @Test
    void aClaimThePlayerMadeOutOfSightIsFoundByTheNowAndThenLook() {
        OrderSim w = world(1, 2);
        w.flow.offer(order("o-4", 2));
        w.run(120);
        assertEquals("in_progress", w.flow.report().status());
        // The chest is emptied but the bot saw no lid
        w.inChest.put(BASE, 0);
        w.now += DeliveryBook.RECHECK_MS;
        w.run(120);
        assertEquals("delivered", w.flow.report().status(), w.flow.report().message());
    }

    @Test
    void theWaitIsBounded() {
        OrderSim w = world(1, 2);
        w.flow.offer(order("o-5", 2));
        w.run(120);
        assertEquals("in_progress", w.flow.report().status());
        w.now += OrderFlow.SPACE_WAIT_TIMEOUT_MS + 1;
        w.run(10);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertTrue(r.message().startsWith("delivery container full (1/2)"), r.message());
        assertTrue(r.message().contains("waited 120 min"), r.message());
        // Still in the inventory for the sorter to put back
        assertEquals(1, w.carried.size());
    }

    @Test
    void theTimerRestartsAfterRoomWasFound() {
        OrderSim w = world(1, 3);
        w.autoExpand = true;
        w.expandBlocker = "x";
        w.flow.offer(order("o-6", 3));
        w.run(100);
        assertEquals("in_progress", w.flow.report().status());
        w.claim(BASE);
        w.run(60);
        // The second went in; the third waits again from now, not from the first wait
        w.now += OrderFlow.SPACE_WAIT_TIMEOUT_MS - 60_000;
        w.run(20);
        assertEquals("in_progress", w.flow.report().status());
    }

    @Test
    void aHostThatCannotStartAnExpansionGetsAWaitNotASpin() {
        OrderSim w = world(1, 2);
        w.autoExpand = true;
        w.refuseStarts = true;
        w.flow.offer(order("o-8", 2));
        w.run(200);
        OrderFlow.Report r = w.flow.report();
        assertEquals("in_progress", r.status());
        assertTrue(r.phase().startsWith("paused: waiting for delivery space"), r.phase());
        assertTrue(w.expansions + w.rechecks < OrderFlow.MAX_SPINS + 5, "steps handed over: " + (w.expansions + w.rechecks));
        // A minute on, the host can: the storage grows
        w.refuseStarts = false;
        w.now += OrderFlow.SPIN_HOLD_MS + 1000;
        w.run(200);
        assertEquals("delivered", w.flow.report().status(), w.flow.report().message());
        assertTrue(w.expansions >= 1);
    }

    @Test
    void anotherReasonIsAnotherChanceToExpand() {
        OrderSim w = world(1, 3);
        w.autoExpand = true;
        w.expandBlocker = "a";
        w.flow.offer(order("o-9", 3));
        w.run(60);
        assertEquals("in_progress", w.flow.report().status());
        w.expandBlocker = null;
        w.run(200);
        assertEquals("delivered", w.flow.report().status(), w.flow.report().message());
    }

    @Test
    void legacySingleContainerStillEndsFull() {
        OrderSim w = new OrderSim();
        w.empties.put(RED, 3);
        w.deliveryCapacity = 1;
        w.flow.offer(order("o-7", 3));
        w.run(120);
        assertEquals("delivered", w.flow.report().status());
        assertTrue(w.flow.report().message().contains("delivery container full"));
    }
}
