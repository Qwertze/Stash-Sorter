package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EnchantPreflight: what is taken by the take keys and charged to the budget, the refusals before step 1, the plan made again, and the timeout. */
class EnchantPreflightTest {
    private final AtomicLong clock = new AtomicLong(1_000_000);

    private EnchantPreflight pre(EnchantKit.Case c, EnchantLedger ledger) {
        return new EnchantPreflight(c.ref(), EnchantKit.MC, Set.of(), ledger, clock::get);
    }

    /** Everything the plan calls for, as carried stacks: the materials, the bottles and the anvil spare. */
    private List<EnchantItems.Held> all(EnchantKit.Case c, int bottles, boolean spare) {
        List<EnchantItems.Held> out = new ArrayList<>(c.held());
        int slot = 20;
        for (int left = bottles; left > 0; left -= 64) out.add(new EnchantItems.Held(slot++, EnchantOrder.BOTTLE_KEY, "experience_bottle", Math.min(64, left), false, Map.of(), 0, false));
        if (spare) out.add(new EnchantItems.Held(slot, EnchantKit.ANVIL, "anvil", 1, false, Map.of(), 0, false));
        return out;
    }

    @Test
    void nothingCarriedGathersTheBaseTheBooksTheBottlesAndTheSpare() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantLedger ledger = new EnchantLedger(c.block());
        EnchantPreflight.Decision d = pre(c, ledger).decide(List.of(), 30);
        EnchantPreflight.Gather g = assertInstanceOf(EnchantPreflight.Gather.class, d);
        Map<String, Integer> lots = new java.util.HashMap<>();
        for (EnchantPreflight.Lot l : g.lots()) lots.put(l.key(), l.count());
        assertEquals(1, lots.get(EnchantKit.SWORD));
        assertEquals(2, lots.get(EnchantKit.BOOK + "#bk0"));
        assertEquals(c.ref().plan().bottles(), lots.get(EnchantOrder.BOTTLE_KEY));
        assertEquals(1, lots.get(EnchantKit.ANVIL));
    }

    @Test
    void whatIsCarriedIsChargedToTheBudgetAndOnlyTheRestIsGathered() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantLedger ledger = new EnchantLedger(c.block());
        EnchantPreflight p = pre(c, ledger);
        List<EnchantItems.Held> some = new ArrayList<>(c.held());
        EnchantPreflight.Gather g = assertInstanceOf(EnchantPreflight.Gather.class, p.decide(some, 30));
        for (EnchantPreflight.Lot l : g.lots()) assertTrue(!l.key().equals(EnchantKit.SWORD) && !l.key().startsWith(EnchantKit.BOOK), "gathered again: " + l);
        assertEquals(0, ledger.remaining(EnchantKit.SWORD));
        assertEquals(0, ledger.remaining(EnchantKit.BOOK + "#bk0"));
        // asking again does not charge twice
        p.decide(some, 30);
        assertEquals(0, ledger.remaining(EnchantKit.SWORD));
        assertEquals(c.ref().plan().bottles(), ledger.remaining(EnchantOrder.BOTTLE_KEY));
    }

    @Test
    void everythingHeldGivesTheRealPlan() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantLedger ledger = new EnchantLedger(c.block());
        EnchantPreflight.Decision d = pre(c, ledger).decide(all(c, c.ref().plan().bottles(), true), 30);
        EnchantPreflight.Ready r = assertInstanceOf(EnchantPreflight.Ready.class, d);
        assertEquals(c.ref().plan().steps().size(), r.real().steps().size());
        assertEquals(c.ref().plan().points(), r.real().points());
        // the materials carry the key of the stack they are
        assertEquals(EnchantKit.SWORD, r.real().materials().get(0).key());
        assertEquals(EnchantKit.BOOK + "#bk0", r.real().materials().get(1).key());
    }

    @Test
    void aMissingBaseWithNothingLeftInTheBudgetIsNoBase() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantLedger ledger = new EnchantLedger(c.block());
        ledger.charge(EnchantKit.SWORD, 1);
        EnchantPreflight.Decision d = pre(c, ledger).decide(List.of(), 30);
        assertEquals(Codes.NO_BASE, assertInstanceOf(EnchantPreflight.Refuse.class, d).code());
    }

    @Test
    void missingBooksAreBooksShort() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantLedger ledger = new EnchantLedger(c.block());
        ledger.charge(EnchantKit.BOOK + "#bk0", 1);
        EnchantPreflight.Decision d = pre(c, ledger).decide(List.of(), 30);
        assertEquals(Codes.BOOKS_SHORT, assertInstanceOf(EnchantPreflight.Refuse.class, d).code());
    }

    @Test
    void bottlesTheOrderDoesNotHaveAreXpShort() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantLedger ledger = new EnchantLedger(c.block());
        ledger.charge(EnchantOrder.BOTTLE_KEY, c.ref().plan().bottles() - 3);
        EnchantPreflight.Decision d = pre(c, ledger).decide(List.of(), 30);
        assertEquals(Codes.XP_SHORT, assertInstanceOf(EnchantPreflight.Refuse.class, d).code());
    }

    @Test
    void noSpareAnvilLeftIsNoAnvil() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantLedger ledger = new EnchantLedger(c.block());
        ledger.charge(EnchantKit.ANVIL, 2);
        EnchantPreflight.Decision d = pre(c, ledger).decide(List.of(), 30);
        assertEquals(Codes.NO_ANVIL, assertInstanceOf(EnchantPreflight.Refuse.class, d).code());
    }

    @Test
    void anAnvilThatBrokeEarlierUsedUpTheSpareAndTheNextItemGoesOnWithoutOne() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantLedger ledger = new EnchantLedger(c.block());
        ledger.charge(EnchantKit.ANVIL, 2);
        ledger.anvilBroke(1);
        EnchantPreflight.Decision d = pre(c, ledger).decide(all(c, c.ref().plan().bottles(), false), 30);
        assertInstanceOf(EnchantPreflight.Ready.class, d);
    }

    @Test
    void notEnoughCarrySlotsIsInventoryFullBeforeAnythingIsGathered() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantLedger ledger = new EnchantLedger(c.block());
        int need = EnchantCarry.slotsNeeded(c.ref().plan().books(), 1, c.ref().plan().bottles());
        EnchantPreflight.Decision d = pre(c, ledger).decide(List.of(), need - 1);
        assertEquals(Codes.INVENTORY_FULL, assertInstanceOf(EnchantPreflight.Refuse.class, d).code());
        assertInstanceOf(EnchantPreflight.Gather.class, pre(c, new EnchantLedger(c.block())).decide(List.of(), need));
        // nothing was charged by the refusal
        assertEquals(1, ledger.remaining(EnchantKit.SWORD));
    }

    @Test
    void aBookWhoseRealRepairCostIsDearerIsACostMismatch() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        // the stack in the inventory is the one the key says, but its stored repair cost is higher than the index claimed
        List<EnchantItems.Held> held = all(c, c.ref().plan().bottles(), true);
        for (int i = 0; i < held.size(); i++) {
            EnchantItems.Held h = held.get(i);
            if (h.book()) held.set(i, new EnchantItems.Held(h.slot(), h.key(), h.id(), 1, true, h.ench(), 15, false));
        }
        EnchantLedger ledger = new EnchantLedger(c.block());
        EnchantPreflight.Decision d = pre(c, ledger).decide(held, 30);
        EnchantPreflight.Refuse r = assertInstanceOf(EnchantPreflight.Refuse.class, d);
        assertTrue(r.code().equals(Codes.COST_MISMATCH) || r.code().equals(Codes.TOO_EXPENSIVE), r.code());
    }

    @Test
    void aCheaperRealPlanIsAccepted() {
        // the server planned with a book of repair cost 3, the real one has 0
        EnchantKit.Case dear = EnchantKit.order(EnchantKit.SWORD, EnchantKit.ench("sharpness", 4), List.of(new EnchantKit.Bk(EnchantKit.ench("sharpness", 4), 3)), 2);
        List<EnchantItems.Held> held = all(dear, dear.ref().plan().bottles(), true);
        for (int i = 0; i < held.size(); i++) {
            EnchantItems.Held h = held.get(i);
            if (h.book()) held.set(i, new EnchantItems.Held(h.slot(), h.key(), h.id(), 1, true, h.ench(), 0, false));
        }
        EnchantPreflight.Decision d = pre(dear, new EnchantLedger(dear.block())).decide(held, 30);
        EnchantPreflight.Ready r = assertInstanceOf(EnchantPreflight.Ready.class, d);
        assertTrue(r.real().points() <= dear.ref().plan().points());
    }

    @Test
    void aBookWithoutTheEnchantmentsTheIndexClaimedIsRefused() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        List<EnchantItems.Held> held = all(c, c.ref().plan().bottles(), true);
        for (int i = 0; i < held.size(); i++) {
            EnchantItems.Held h = held.get(i);
            if (h.book()) held.set(i, new EnchantItems.Held(h.slot(), h.key(), h.id(), 1, true, Map.of("sharpness", 1), 0, false));
        }
        EnchantPreflight.Decision d = pre(c, new EnchantLedger(c.block())).decide(held, 30);
        assertInstanceOf(EnchantPreflight.Refuse.class, d);
    }

    @Test
    void aCustomNamedBaseIsNoBase() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        List<EnchantItems.Held> held = all(c, c.ref().plan().bottles(), true);
        EnchantItems.Held b = held.get(0);
        held.set(0, new EnchantItems.Held(b.slot(), b.key(), b.id(), 1, false, b.ench(), b.rc(), true));
        EnchantPreflight.Decision d = pre(c, new EnchantLedger(c.block())).decide(held, 30);
        assertEquals(Codes.NO_BASE, assertInstanceOf(EnchantPreflight.Refuse.class, d).code());
    }

    @Test
    void booksOfAKeyThatIsNotInTheTakeAreNeverUsed() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        List<EnchantItems.Held> held = all(c, c.ref().plan().bottles(), true);
        // the plan's books are not carried, a better book of another key is: it is not swapped in
        held.removeIf(EnchantItems.Held::book);
        held.add(new EnchantItems.Held(5, EnchantKit.BOOK + "#zz", "enchanted_book", 1, true, EnchantKit.ench("sharpness", 5), 0, false));
        EnchantPreflight.Decision d = pre(c, new EnchantLedger(c.block())).decide(held, 30);
        EnchantPreflight.Gather g = assertInstanceOf(EnchantPreflight.Gather.class, d);
        assertTrue(g.lots().stream().anyMatch(l -> l.key().equals(EnchantKit.BOOK + "#bk0") && l.count() == 2));
    }

    @Test
    void tenMinutesWithoutHeadwayIsATimeout() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantPreflight p = pre(c, new EnchantLedger(c.block()));
        assertNull(p.stalled());
        clock.addAndGet(EnchantPreflight.STALL_MS - 1);
        assertNull(p.stalled());
        // a stack taken is headway
        p.tripMade(true);
        clock.addAndGet(EnchantPreflight.STALL_MS - 1);
        assertNull(p.stalled());
        clock.addAndGet(2);
        assertEquals(Codes.TIMEOUT, p.stalled());
    }

    @Test
    void moreThanTwelvePlusFourTimesTheLeavesTripsIsATimeout() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantPreflight p = pre(c, new EnchantLedger(c.block()));
        int max = EnchantPreflight.maxTrips(c.ref().plan().leaves());
        assertEquals(12 + 4 * 3, max);
        for (int i = 0; i < max; i++) {
            p.tripMade(true);
            assertNull(p.stalled(), "trip " + i);
        }
        p.tripMade(true);
        assertEquals(Codes.TIMEOUT, p.stalled());
    }

    @Test
    void theRefusalCodesAreAllInTheClosedSet() {
        for (String code : List.of(Codes.NO_BASE, Codes.BOOKS_SHORT, Codes.XP_SHORT, Codes.NO_ANVIL, Codes.INVENTORY_FULL, Codes.COST_MISMATCH, Codes.MC_MISMATCH,
            Codes.MODULE_CONFLICT, Codes.TIMEOUT)) {
            assertTrue(Codes.ALL.contains(code));
            assertTrue(Codes.PREFLIGHT.contains(code));
        }
        assertEquals(Codes.CAP, Codes.safe("interrupted"));
        assertEquals(Codes.CAP, Codes.safe(null));
        assertEquals(17, Codes.ALL.size());
    }
}
