package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.8.1: the fixes from the review of 1.8.0's crafting. */
class CraftReviewFixesTest {
    private static final Recipes R = TestRecipes.table();

    @Test
    void onlyTheSlotsTheResultWentIntoAreKeptNeverASourceShulker() {
        // Slot 12 holds an emptied plain source shulker, 14 the crafted box; both look like empty plain boxes
        Set<Integer> boxes = Set.of(12, 14);
        List<Integer> kept = CraftSlots.keep(List.of(14), Set.of(12), boxes::contains, 1);
        assertEquals(List.of(14), kept);
        // A result slot that turned out to be a source shulker's, or no longer holds the box, is never kept
        assertEquals(List.of(), CraftSlots.keep(List.of(12), Set.of(12), boxes::contains, 1));
        assertEquals(List.of(), CraftSlots.keep(List.of(20), Set.of(), boxes::contains, 1));
        // No more than were made
        assertEquals(List.of(14), CraftSlots.keep(List.of(14, 15), Set.of(), s -> true, 1));
    }

    @Test
    void aRetryKeepsWhatTheCutShortTripMade() {
        // The first trip made planks (slot 20) and a chest (slot 21), then gave way; the logs were used up (slot 9 empty)
        Set<Integer> owned = Set.of(9, 20, 21);
        Set<Integer> next = CraftSlots.retained(owned, s -> s != 9, List.of(10, 11));
        assertEquals(Set.of(20, 21, 10, 11), next);
    }

    @Test
    void namedAndPlainStacksAreDifferentKindsAndEachFillsItsOwnSlots() {
        String plain = CraftSlots.kind("oak_planks", "minecraft:oak_planks", "minecraft:oak_planks");
        String named = CraftSlots.kind("oak_planks", "minecraft:oak_planks#12345678", "abcdef01");
        assertEquals("minecraft:oak_planks", plain);
        assertFalse(plain.equals(named));
        assertEquals("minecraft:oak_planks", CraftSlots.baseOf(named));
        // 5 plain and 3 named planks make a chest: each slot gets one kind
        Recipes.Recipe chest = R.forResult("minecraft:chest").get(0);
        CraftGrid.Batch b = CraftGrid.batch(chest, Map.of(plain, 5, named, 3), 1, 64);
        assertNotNull(b);
        assertEquals(Map.of(plain, 5, named, 3), b.uses());
    }

    @Test
    void theWholeTripIsBounded() {
        CraftPlanner.Plan p = CraftTripTest.fromLogs(1);
        CraftTripTest.FakeTable f = CraftTripTest.table(p).carry("minecraft:shulker_shell", 2).carry("minecraft:oak_log", 2);
        // The result shows, every take is turned down by the server and the slow lag never ends: the trip still ends
        f.lag = Integer.MAX_VALUE;
        CraftTrip t = new CraftTrip(f, p);
        assertEquals(CraftTrip.TRIP_BASE_TICKS + CraftTrip.TRIP_FILL_TICKS * p.fills(), t.limit());
        CraftTrip.Outcome o = CraftTripTest.run(t, f, t.limit() + CraftTrip.RETRIEVE_TICKS + CraftTrip.CLOSE_TICKS + 100);
        assertNotNull(o.failure());
        assertFalse(f.closedWithItems);
    }

    @Test
    void clicksThatNeverFinishStillEndTheTrip() {
        CraftPlanner.Plan p = CraftTripTest.fromLogs(1);
        CraftTripTest.FakeTable f = CraftTripTest.table(p).carry("minecraft:shulker_shell", 2).carry("minecraft:oak_log", 2);
        f.busyForever = true;
        CraftTrip t = new CraftTrip(f, p);
        CraftTrip.Outcome o = CraftTripTest.run(t, f, t.limit() + CraftTrip.RETRIEVE_TICKS + CraftTrip.CLOSE_TICKS + 10);
        assertEquals(CraftTrip.TOO_LONG, o.failure());
    }

    @Test
    void aStackOnTheCursorIsPutAwayBeforeTheGridIsTouched() {
        CraftPlanner.Plan p = CraftTripTest.fromLogs(1);
        CraftTripTest.FakeTable f = CraftTripTest.table(p).carry("minecraft:shulker_shell", 2).carry("minecraft:oak_log", 2);
        f.gives = false;
        CraftTrip t = new CraftTrip(f, p);
        for (int i = 0; i < 200 && f.gridTotal() == 0; i++) {
            f.tick();
            t.tick();
        }
        // A take's second click was turned down: the result is on the cursor
        f.cursor = true;
        t.abort("gave way");
        CraftTrip.Outcome o = CraftTripTest.run(t, f, 500);
        assertEquals(1, f.stowed);
        assertFalse(f.closedWithItems);
        assertTrue(o.retry());
    }

    @Test
    void withNoRoomForTheCursorTheTableIsLeftOpen() {
        CraftPlanner.Plan p = CraftTripTest.fromLogs(1);
        CraftTripTest.FakeTable f = CraftTripTest.table(p).carry("minecraft:shulker_shell", 2).carry("minecraft:oak_log", 2);
        f.gives = false;
        CraftTrip t = new CraftTrip(f, p);
        for (int i = 0; i < 200 && f.gridTotal() == 0; i++) {
            f.tick();
            t.tick();
        }
        f.cursor = true;
        f.full = true;
        t.abort("gave way");
        CraftTrip.Outcome o = CraftTripTest.run(t, f, CraftTrip.RETRIEVE_TICKS + 500);
        assertTrue(o.stuck());
        assertTrue(f.open);
    }

    @Test
    void theHourlyCountIsSafeToReadFromAnotherThread() throws Exception {
        CraftHealth h = new CraftHealth();
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 20_000; i++) h.crafted(i, 1);
        });
        writer.start();
        for (int i = 0; i < 20_000; i++) h.lastHour(i);
        writer.join();
        assertEquals(20_000, h.lastHour(20_000));
    }
}
