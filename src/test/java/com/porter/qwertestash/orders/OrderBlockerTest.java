package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reason orders can't run, as the heartbeat reports it. */
class OrderBlockerTest {
    /** Everything fine. */
    private static OrderBlocker.Snapshot ready() {
        return new OrderBlocker.Snapshot(true, true, true, false, false, true, "overworld", "minecraft:overworld", 40, null, 5, true, true);
    }

    private static OrderBlocker.Snapshot with(java.util.function.UnaryOperator<Builder> change) {
        return change.apply(new Builder()).build();
    }

    private static final class Builder {
        boolean accept = true, delivery = true, inGame = true, queue, controlled, stash = true, shulkers = true, read = true;
        String deliveryDim = "overworld", dim = "minecraft:overworld", busy;
        double distance = 40;
        int free = 5;

        OrderBlocker.Snapshot build() {
            return new OrderBlocker.Snapshot(accept, delivery, inGame, queue, controlled, stash, deliveryDim, dim, distance, busy, free, shulkers, read);
        }
    }

    @Test
    void readyIsNull() {
        assertNull(OrderBlocker.compute(ready()));
    }

    @Test
    void eachReasonHasItsText() {
        assertEquals("orders off", OrderBlocker.compute(with(b -> {
            b.accept = false;
            return b;
        })));
        assertEquals("no delivery container set", OrderBlocker.compute(with(b -> {
            b.delivery = false;
            return b;
        })));
        assertEquals("bot not in game", OrderBlocker.compute(with(b -> {
            b.inGame = false;
            return b;
        })));
        assertEquals("in the queue", OrderBlocker.compute(with(b -> {
            b.queue = true;
            b.inGame = false;
            return b;
        })));
        assertEquals("a player is controlling the bot", OrderBlocker.compute(with(b -> {
            b.controlled = true;
            return b;
        })));
        assertEquals("no stash loaded", OrderBlocker.compute(with(b -> {
            b.stash = false;
            return b;
        })));
        assertEquals("inventory full", OrderBlocker.compute(with(b -> {
            b.free = 0;
            return b;
        })));
        assertEquals("no shulkers in the stash", OrderBlocker.compute(with(b -> {
            b.shulkers = false;
            return b;
        })));
        assertEquals("busy: working on an order", OrderBlocker.compute(with(b -> {
            b.busy = "working on an order";
            return b;
        })));
        assertEquals("busy: finishing a shulker", OrderBlocker.compute(with(b -> {
            b.busy = "finishing a shulker";
            return b;
        })));
    }

    @Test
    void wrongDimensionAndTooFar() {
        String wrong = OrderBlocker.compute(with(b -> {
            b.dim = "minecraft:the_nether";
            return b;
        }));
        assertTrue(wrong.startsWith("wrong dimension"), wrong);
        String far = OrderBlocker.compute(with(b -> {
            b.distance = 5000;
            return b;
        }));
        assertTrue(far.startsWith("delivery container too far"), far);
        assertNull(OrderBlocker.compute(with(b -> {
            b.distance = OrderBlocker.MAX_DELIVERY_DISTANCE;
            return b;
        })));
        // Not known is not a reason
        assertNull(OrderBlocker.compute(with(b -> {
            b.dim = null;
            b.distance = -1;
            return b;
        })));
    }

    @Test
    void anUnreadStashSaysNothingAboutShulkers() {
        assertNull(OrderBlocker.compute(with(b -> {
            b.shulkers = false;
            b.read = false;
            return b;
        })));
    }

    @Test
    void theMostBasicReasonWins() {
        String text = OrderBlocker.compute(with(b -> {
            b.accept = false;
            b.delivery = false;
            b.free = 0;
            return b;
        }));
        assertEquals("orders off", text);
        assertEquals("no delivery container set", OrderBlocker.compute(with(b -> {
            b.delivery = false;
            b.free = 0;
            b.queue = true;
            return b;
        })));
        // Waiting in the queue is told before "not in game"
        assertEquals("in the queue", OrderBlocker.compute(with(b -> {
            b.queue = true;
            b.inGame = false;
            b.controlled = true;
            return b;
        })));
    }

    @Test
    void textFitsTheHeartbeat() {
        String longest = OrderBlocker.compute(with(b -> {
            b.dim = "minecraft:the_nether";
            b.deliveryDim = "minecraft:a_very_long_custom_dimension_name_that_a_datapack_might_use_for_something";
            return b;
        }));
        // The heartbeat cuts it to 100 characters; it must still say what is wrong first
        assertTrue(longest.startsWith("wrong dimension"));
    }

    @Test
    void dimensionsMatchWithOrWithoutNamespace() {
        assertTrue(OrderBlocker.sameDimension("overworld", "minecraft:overworld"));
        assertTrue(OrderBlocker.sameDimension("minecraft:the_nether", "the_nether"));
        assertTrue(!OrderBlocker.sameDimension("the_end", "the_nether"));
        assertTrue(!OrderBlocker.sameDimension(null, "the_nether"));
    }
}
