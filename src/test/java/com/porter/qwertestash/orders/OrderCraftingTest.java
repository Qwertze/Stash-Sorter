package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.8.0: crafting a plain shulker box for an order whose bin, or empty-shulker line, has none in the stash. The flow asks
 * for a Craft before the Build and before the Fetch; the plan, the check of the empties and the run count craftables from
 * the same pool; a craft that fails gives the unit up with the reason; crafting off changes nothing.
 */
class OrderCraftingTest {
    private static final String D = "minecraft:diamond", BOX = OrderFlow.PLAIN_SHULKER, SHELL = "minecraft:shulker_shell", LOG = "minecraft:oak_log";

    private static Order order(String id, Order.Line... lines) {
        return new Order(id, List.of(lines));
    }

    private static Order.Line item(String key, int count) {
        return new Order.Line("item", key, count);
    }

    private static OrderSim world() {
        OrderSim w = new OrderSim().stock(D, "v", 10_000, 64);
        w.craftOn = true;
        return w;
    }

    private static List<String> intents(OrderSim w, int budget) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < budget && w.flow.running(); i++) {
            w.last = "";
            w.step();
            if (!w.last.isEmpty()) out.add(w.last);
            w.now += 50;
        }
        return out;
    }

    @Test
    void aBinWithNoEmptyShulkerCraftsOneFirst() {
        OrderSim w = world().craftStock(SHELL, 2).craftStock(LOG, 2);
        assertNull(w.flow.offer(order("o-1", item(D, 64))));
        List<String> did = intents(w, 30);
        assertEquals(List.of("craft", "build", "deliver"), did);
        assertEquals(OrderFlow.State.Delivered, w.flow.state());
        assertEquals(1, w.craftedTotal);
        assertTrue(w.flow.report().message().contains("crafted 1 Shulker Box"), w.flow.report().message());
        assertEquals(1, w.saved.crafted, "counted in the saved state");
    }

    @Test
    void anEmptyLineTheStashCantGiveIsCrafted() {
        OrderSim w = world().craftStock(SHELL, 4).craftStock(LOG, 4);
        assertNull(w.flow.offer(order("o-2", item(BOX, 2))));
        assertEquals(2, w.flow.units().size(), "the plan counts what can be crafted");
        assertEquals(2, w.planCrafted);
        List<String> did = intents(w, 30);
        assertEquals(List.of("craft", "deliver", "craft", "deliver"), did);
        assertEquals(2, w.deliveredTotals().get(BOX));
        assertTrue(w.flow.report().message().contains("crafted 2 Shulker Box"));
    }

    @Test
    void thePlanCountsOnlyWhatCanBeCrafted() {
        // Shells for one box only: one of three is planned, the run crafts that one and no more
        OrderSim w = world().craftStock(SHELL, 3).craftStock(LOG, 10);
        assertNull(w.flow.offer(order("o-3", item(BOX, 3))));
        assertEquals(1, w.flow.units().size());
        intents(w, 30);
        assertEquals(1, w.craftTrips);
        assertEquals(1, w.deliveredTotals().get(BOX));
        assertTrue(w.flow.report().message().contains("delivered less than asked"));
    }

    @Test
    void whatTheBinsNeedIsNotCraftedWith() {
        // The bin wants the logs: crafting may not take them, so there is no empty shulker for the bin
        OrderSim w = world().craftStock(SHELL, 2).craftStock(LOG, 2);
        w.stock(LOG, "l", 2, 64);
        String why = w.flow.offer(order("o-4", item(LOG, 2)));
        assertEquals("need 1 empty shulkers, have 0", why);
        assertEquals(0, w.craftTrips);
    }

    @Test
    void aCraftThatFailsGivesTheUnitUpWithTheReason() {
        OrderSim w = world().craftStock(SHELL, 4).craftStock(LOG, 4);
        assertNull(w.flow.offer(order("o-5", item(BOX, 1))));
        w.craftTrip = "fail";
        intents(w, 30);
        assertEquals(1, w.craftTrips, "not retried: the table gave no result");
        assertEquals(OrderFlow.State.Failed, w.flow.state());
        assertTrue(w.flow.report().message().contains(OrderFlow.NO_EMPTY_CRAFT + CraftTrip.NO_RESULT), w.flow.report().message());
        assertEquals(1, w.putBack, "what was gathered went back");
    }

    @Test
    void aCraftCutShortIsTriedOnceMore() {
        OrderSim w = world().craftStock(SHELL, 4).craftStock(LOG, 4);
        assertNull(w.flow.offer(order("o-6", item(BOX, 1))));
        w.craftTrip = "closed";
        intents(w, 30);
        assertEquals(OrderFlow.MAX_CRAFT_TRIES, w.craftTrips);
        assertTrue(w.flow.report().message().contains(OrderFlow.NO_EMPTY_CRAFT), w.flow.report().message());
    }

    @Test
    void noTableNoCraft() {
        OrderSim w = world().craftStock(SHELL, 4).craftStock(LOG, 4);
        w.tableSet = false;
        String why = w.flow.offer(order("o-7", item(D, 64)));
        assertEquals("need 1 empty shulkers, have 0", why);
        // An empty line with a table that goes away after the plan: skipped with the reason
        OrderSim v = world().craftStock(SHELL, 4).craftStock(LOG, 4);
        assertNull(v.flow.offer(order("o-8", item(BOX, 1))));
        v.tableSet = false;
        intents(v, 10);
        assertEquals(0, v.craftTrips);
        assertTrue(v.flow.report().message().contains(OrderFlow.NO_EMPTY_CRAFT + CraftPlanner.NO_TABLE), v.flow.report().message());
    }

    @Test
    void aCancelDuringACraftEndsTheOrderAfterThePutBack() {
        OrderSim w = world().craftStock(SHELL, 4).craftStock(LOG, 4);
        assertNull(w.flow.offer(order("o-9", item(BOX, 2))));
        // The host is mid-craft: the server cancels, the craft is cut short (what was gathered goes back), then the order ends
        assertTrue(w.flow.next() instanceof OrderFlow.Craft c && c.unit() == w.flow.current());
        w.flow.craftStarted(w.flow.current());
        assertTrue(w.flow.cancel("o-9"));
        assertTrue(w.flow.cancelRequested());
        w.flow.craftFailed(w.flow.current(), "the order ended", true);
        assertTrue(w.flow.next() instanceof OrderFlow.Idle);
        assertEquals(OrderFlow.State.Cancelled, w.flow.state());
        assertEquals(0, w.craftedTotal);
    }

    @Test
    void aParkDuringACraftKeepsTheOrderForLater() {
        OrderSim w = world().craftStock(SHELL, 4).craftStock(LOG, 4);
        assertNull(w.flow.offer(order("o-10", item(BOX, 1))));
        assertTrue(w.flow.next() instanceof OrderFlow.Craft);
        w.flow.park();
        assertTrue(w.flow.parkRequested());
        assertTrue(w.flow.next() instanceof OrderFlow.Idle);
        assertFalse(w.flow.holdsOrder());
        assertEquals("o-10", w.flow.resumeId(), "the plan stays saved");
    }

    @Test
    void theCraftedCountSurvivesARestart() {
        OrderSim w = world().craftStock(SHELL, 4).craftStock(LOG, 4);
        Order o = order("o-11", item(BOX, 2));
        assertNull(w.flow.offer(o));
        intents(w, 2);
        assertEquals(1, w.flow.craftedCount());
        w.flow.reset();
        assertNull(w.flow.offer(o));
        assertEquals(1, w.flow.craftedCount(), "resumed from the saved state");
    }

    @Test
    void craftingOffChangesNothing() {
        OrderSim on = world().craftStock(SHELL, 4).craftStock(LOG, 4);
        on.craftOn = false;
        OrderSim plain = new OrderSim().stock(D, "v", 10_000, 64);
        // Exactly what a world without crafting says: no empty shulker for the bin
        assertEquals(plain.flow.offer(order("o-12", item(BOX, 2), item(D, 10))), on.flow.offer(order("o-12", item(BOX, 2), item(D, 10))));
        assertEquals(0, on.craftTrips);
        assertEquals(0, on.planCrafted);
        // An empty line alone: the plan has nothing to deliver either way
        assertEquals(plain.flow.offer(order("o-13", item(BOX, 1))), on.flow.offer(order("o-13", item(BOX, 1))));
        assertFalse(on.flow.holdsOrder());
    }

    @Test
    void theEndMessageKeepsBothNotesWithinTheLimit() {
        String text = "x ".repeat(200);
        String out = OrderFlow.withNotes(text, "2 shulker(s) delivered unnamed: no anvil set", "crafted 3 Shulker Box");
        assertTrue(out.length() <= OrderFlow.MESSAGE_MAX, out.length() + "");
        assertTrue(out.endsWith("2 shulker(s) delivered unnamed: no anvil set; crafted 3 Shulker Box"), out);
        assertEquals("crafted 1 Shulker Box", OrderFlow.withNotes("", null, "crafted 1 Shulker Box"));
        assertEquals("done; crafted 1 Shulker Box", OrderFlow.withNotes("done; crafted 1 Shulker Box", null, "crafted 1 Shulker Box"));
    }
}
