package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One trip to the anvil on a fake world: the happy path, and every way it ends with the shulker unnamed but back in its slot. */
class AnvilNamerTest {
    /** An anvil next to the bot. The shulker starts in carry slot 5. */
    static final class FakeAnvil implements AnvilNamer.Port {
        int walkTicks = 3;
        boolean reachable = true, opens = true, gives = true, takeWorks = true, sendsCost = true;
        int cost = 1, level = 30;
        boolean open, otherOpen;
        String slotName;
        boolean slotHasShulker = true;
        boolean input;
        @Nullable String result;
        boolean costSeen;
        int walks, opensTried, places, takes, retrieves, closes;
        int ticksToClose = -1;
        /** No free slot anywhere: nothing can be taken out of the anvil. */
        boolean full;
        /** Something was picked up into the shulker's own slot while it was in the anvil: it goes to this slot instead. */
        int otherSlot = -1;
        int landedIn = 5;

        @Override
        public boolean arrived() {
            return reachable && walks > 0 && --walkTicks <= 0;
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
        public boolean anvilOpen() {
            if (ticksToClose == 0 && open) {
                // The server closes it, and gives what was in it back to the inventory
                open = false;
                if (input) {
                    input = false;
                    result = null;
                    slotHasShulker = true;
                }
            }
            if (ticksToClose > 0) ticksToClose--;
            return open;
        }

        @Override
        public boolean otherWindowOpen() {
            return otherOpen;
        }

        @Override
        public boolean open() {
            opensTried++;
            if (opens) open = true;
            return true;
        }

        @Override
        public boolean busy() {
            return false;
        }

        @Override
        public boolean place(int slot, String name) {
            places++;
            slotHasShulker = false;
            input = true;
            if (gives) result = name;
            costSeen = sendsCost;
            return true;
        }

        @Override
        public @Nullable String resultName() {
            return result;
        }

        @Override
        public boolean inputPresent() {
            return input;
        }

        @Override
        public int cost() {
            return costSeen ? cost : -1;
        }

        @Override
        public int xpLevel() {
            return level;
        }

        @Override
        public int take(int slot) {
            if (full) return -1;
            takes++;
            int into = otherSlot >= 0 ? otherSlot : slot;
            if (takeWorks && level >= cost) {
                slotHasShulker = true;
                landedIn = into;
                slotName = result;
                input = false;
                result = null;
                level -= cost;
            }
            return into;
        }

        @Override
        public int retrieve(int slot) {
            if (full) return -1;
            retrieves++;
            input = false;
            result = null;
            slotHasShulker = true;
            landedIn = otherSlot >= 0 ? otherSlot : slot;
            return landedIn;
        }

        @Override
        public @Nullable String nameAt(int slot) {
            return slotHasShulker && slot == landedIn ? slotName : null;
        }

        @Override
        public boolean close() {
            closes++;
            if (open && input && full) throw new AssertionError("closed with the shulker in the anvil and no room for it");
            otherOpen = false;
            if (open && input) {
                // The server gives it back to the inventory
                input = false;
                slotHasShulker = true;
            }
            open = false;
            return true;
        }
    }

    private static AnvilNamer.Outcome run(FakeAnvil a, String name) {
        AnvilNamer n = new AnvilNamer(a, 5, name);
        for (int i = 0; i < 20 * 300 && n.step() != AnvilNamer.Step.Done; i++) n.tick();
        assertEquals(AnvilNamer.Step.Done, n.step());
        assertFalse(a.open, "the anvil was left open");
        return n.outcome();
    }

    @Test
    void walksOpensRenamesTakesItBackAndCloses() {
        FakeAnvil a = new FakeAnvil();
        AnvilNamer.Outcome o = run(a, "Kit 1");
        assertTrue(o.named(), String.valueOf(o));
        assertEquals("Kit 1", a.slotName);
        assertTrue(a.slotHasShulker);
        assertEquals(1, a.places);
        assertEquals(1, a.takes);
        assertEquals(0, a.retrieves);
        assertEquals(29, a.level);
    }

    @Test
    void tooFewLevelsForTheShownCostTakesItBackUnnamed() {
        FakeAnvil a = new FakeAnvil();
        a.cost = 3;
        a.level = 2;
        AnvilNamer.Outcome o = run(a, "Kit");
        assertEquals(AnvilNamer.TOO_FEW_LEVELS, o.failure());
        assertFalse(o.retry());
        assertEquals(0, a.takes);
        assertEquals(1, a.retrieves);
        assertTrue(a.slotHasShulker);
        assertNull(a.slotName);
    }

    @Test
    void tooExpensiveIsRefusedLikeTheAnvilDoes() {
        FakeAnvil a = new FakeAnvil();
        a.cost = 40;
        a.level = 100;
        assertEquals(AnvilNamer.TOO_DEAR, run(a, "Kit").failure());
        assertEquals(0, a.takes);
    }

    @Test
    void noResultWithinAboutThirtySecondsGivesUp() {
        FakeAnvil a = new FakeAnvil();
        a.gives = false;
        AnvilNamer n = new AnvilNamer(a, 5, "Kit");
        int ticks = 0;
        while (n.step() != AnvilNamer.Step.Done && ticks < 20 * 300) {
            n.tick();
            ticks++;
        }
        assertEquals(AnvilNamer.NO_RESULT, n.outcome().failure());
        assertTrue(ticks >= AnvilNamer.RESULT_TICKS && ticks < AnvilNamer.RESULT_TICKS + 200, "took " + ticks);
        assertEquals(1, a.retrieves);
        assertTrue(a.slotHasShulker);
    }

    @Test
    void anUnreachableAnvilIsNotWorthTryingAgain() {
        FakeAnvil a = new FakeAnvil();
        a.reachable = false;
        AnvilNamer.Outcome o = run(a, "Kit");
        assertEquals(AnvilNamer.NOT_REACHED, o.failure());
        assertTrue(o.anvilBad());
        assertEquals(0, a.places);
    }

    @Test
    void anAnvilThatNeverOpensIsGivenUpOnAfterAFewClicks() {
        FakeAnvil a = new FakeAnvil();
        a.opens = false;
        AnvilNamer.Outcome o = run(a, "Kit");
        assertEquals(AnvilNamer.NOT_OPENED, o.failure());
        assertTrue(o.anvilBad());
        assertEquals(AnvilNamer.OPEN_TRIES, a.opensTried);
    }

    @Test
    void aWindowClosedByTheServerMayBeTriedAgain() {
        FakeAnvil a = new FakeAnvil();
        a.gives = false;
        a.ticksToClose = 8;
        AnvilNamer.Outcome o = run(a, "Kit");
        assertEquals(AnvilNamer.CLOSED, o.failure());
        assertTrue(o.retry());
        assertTrue(a.slotHasShulker);
    }

    @Test
    void aTakeTheServerRefusedEndsUnnamedWithTheShulkerBack() {
        FakeAnvil a = new FakeAnvil();
        a.takeWorks = false;
        AnvilNamer.Outcome o = run(a, "Kit");
        assertEquals(AnvilNamer.NOT_TAKEN, o.failure());
        assertEquals(1, a.retrieves);
        assertTrue(a.slotHasShulker);
    }

    @Test
    void withoutACostTheResultIsTakenAtOneLevel() {
        FakeAnvil a = new FakeAnvil();
        a.sendsCost = false;
        assertTrue(run(a, "Kit").named());
        FakeAnvil b = new FakeAnvil();
        b.sendsCost = false;
        b.level = 0;
        assertEquals(AnvilNamer.TOO_FEW_LEVELS, run(b, "Kit").failure());
    }

    @Test
    void aStrayWindowIsClosedBeforeTheAnvilIsOpened() {
        FakeAnvil a = new FakeAnvil();
        a.otherOpen = true;
        assertTrue(run(a, "Kit").named());
        assertTrue(a.closes >= 2);
    }

    @Test
    void anAbortClosesTheWindowAndLeavesTheShulkerInItsSlot() {
        FakeAnvil a = new FakeAnvil();
        a.gives = false;
        AnvilNamer n = new AnvilNamer(a, 5, "Kit");
        for (int i = 0; i < 40; i++) n.tick();
        assertTrue(n.shulkerInAnvil());
        n.abort("the order ended");
        for (int i = 0; i < 40 && n.step() != AnvilNamer.Step.Done; i++) n.tick();
        AnvilNamer.Outcome o = n.outcome();
        assertNotNull(o);
        assertTrue(o.retry());
        assertEquals("the order ended", o.failure());
        assertTrue(a.slotHasShulker);
        assertFalse(a.open);
    }

    @Test
    void withNoRoomTheAnvilIsNeverClosedOnTheShulker() {
        FakeAnvil a = new FakeAnvil();
        a.gives = false;
        AnvilNamer n = new AnvilNamer(a, 5, "Kit");
        for (int i = 0; i < 40; i++) n.tick();
        assertTrue(n.shulkerInAnvil());
        // The inventory fills up (items picked up), then the result never comes
        a.full = true;
        int ticks = 0;
        while (n.step() != AnvilNamer.Step.Done && ticks++ < 20 * 300) n.tick();
        AnvilNamer.Outcome o = n.outcome();
        assertTrue(o.stuck(), String.valueOf(o));
        assertEquals(AnvilNamer.NO_RESULT, o.failure());
        assertFalse(o.retry());
        assertTrue(a.open, "left open for a person");
        assertEquals(0, a.closes);
        assertTrue(ticks > AnvilNamer.RETRIEVE_TICKS, "tried for a while first: " + ticks);
    }

    @Test
    void roomThatTurnsUpWhileWaitingIsUsed() {
        FakeAnvil a = new FakeAnvil();
        a.gives = false;
        AnvilNamer n = new AnvilNamer(a, 5, "Kit");
        for (int i = 0; i < 40; i++) n.tick();
        a.full = true;
        for (int i = 0; i < AnvilNamer.RESULT_TICKS + 200; i++) n.tick();
        assertEquals(AnvilNamer.Step.Retrieve, n.step());
        a.full = false;
        for (int i = 0; i < 100 && n.step() != AnvilNamer.Step.Done; i++) n.tick();
        assertFalse(n.outcome().stuck());
        assertTrue(a.slotHasShulker);
        assertFalse(a.open);
    }

    @Test
    void aResultWhoseOwnSlotFilledUpGoesToAnotherFreeSlot() {
        FakeAnvil a = new FakeAnvil();
        a.otherSlot = 7;
        AnvilNamer n = new AnvilNamer(a, 5, "Kit");
        for (int i = 0; i < 400 && n.step() != AnvilNamer.Step.Done; i++) n.tick();
        assertTrue(n.outcome().named(), String.valueOf(n.outcome()));
        assertEquals(7, n.landedSlot());
    }
}
