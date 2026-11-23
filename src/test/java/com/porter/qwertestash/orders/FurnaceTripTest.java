package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.Extras;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The trip to the furnace (CRAFT-CONTRACT-v1.2.md 14, B2) against a fake furnace: batches, fuel, timeouts, a busy furnace, a closed window and a stopped run taken up. */
public class FurnaceTripTest {
    static final String SAND = "minecraft:sand", RED = "minecraft:red_sand", GLASS = "minecraft:glass", COAL = "minecraft:coal";

    public static Extras extras() {
        try (InputStream in = FurnaceTripTest.class.getResourceAsStream("/extras-b2-1.21.4.json")) {
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return Extras.parse(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final Smelting SM = Smelting.of(extras());

    /** A furnace step as the planner makes it: {@code items} of these inputs, with this fuel. */
    static CraftPlanner.Step step(String recipe, Map<String, Long> uses, Map<String, Long> fuel) {
        Recipes.Recipe r = SM.asRecipe(recipe);
        assertNotNull(r, recipe);
        long n = 0;
        for (long c : uses.values()) n += c;
        return new CraftPlanner.Step(r, (int) n, 0, uses, fuel);
    }

    /** One furnace step per (items, coal) pair: a step is one input kind and at most 64 items (CRAFT-CONTRACT-v1.2.md, B2 review). */
    static List<FurnaceTrip.Batch> glass(long... itemsAndCoal) {
        List<CraftPlanner.Step> steps = new java.util.ArrayList<>();
        for (int i = 0; i < itemsAndCoal.length; i += 2) steps.add(step("qwerte:smelt/glass", Map.of(SAND, itemsAndCoal[i]), Map.of(COAL, itemsAndCoal[i + 1])));
        return FurnaceTrip.batches(steps, SM, id -> 64);
    }

    static FurnaceTrip trip(FakeFurnace f, List<FurnaceTrip.Batch> b, SmeltJournal j, boolean own) {
        return new FurnaceTrip(f, b, j, () -> { }, SM.cookTicks(), own, Set.of(SAND, GLASS, COAL), false);
    }

    static FurnaceTrip.Outcome run(FurnaceTrip t, FakeFurnace f, int max) {
        for (int i = 0; i < max && t.outcome() == null; i++) {
            f.tick();
            t.tick();
        }
        assertNotNull(t.outcome(), "the trip did not end in " + max + " ticks (step " + t.step() + ")");
        return t.outcome();
    }

    @Test
    void theExtrasOfTheServerGiveTheSmeltingTables() {
        assertNotNull(SM);
        assertEquals(200, SM.cookTicks());
        assertEquals(1600, SM.ticks(COAL));
        assertEquals(List.of("minecraft:red_sand", "minecraft:sand"), SM.byId("qwerte:smelt/glass").in());
        // 70 items in batches of 64 and 6: ceil(64*200/1600) = 8 and ceil(6*200/1600) = 1
        assertEquals(9, SM.batchFuel(COAL, 70));
        assertEquals(2, SM.batchFuel(COAL, 10));
        assertEquals(0, SM.batchFuel("minecraft:stone", 10));
    }

    @Test
    void theBatchesAreExactlyTheStepsWithTheirOwnWholeFuel() {
        // Two steps (64 and 6 items): two batches, each its step's own fuel, whole, never merged or re-cut
        List<FurnaceTrip.Batch> b = glass(64, 8, 6, 1);
        assertEquals(2, b.size());
        assertEquals(64, b.get(0).count());
        assertEquals(8, b.get(0).fuelInc());
        assertEquals(6, b.get(1).count());
        assertEquals(1, b.get(1).fuelInc());
        // The same two steps from the planner: never merged, one input kind each
        List<FurnaceTrip.Batch> two = FurnaceTrip.batches(List.of(step("qwerte:smelt/glass", Map.of(SAND, 3L), Map.of(COAL, 1L)),
            step("qwerte:smelt/glass", Map.of(RED, 4L), Map.of(COAL, 1L))), SM, id -> 64);
        assertEquals(2, two.size());
        assertEquals(SAND, two.get(0).in());
        assertEquals(RED, two.get(1).in());
        assertEquals(1, two.get(0).fuelInc());
        assertEquals(1, two.get(1).fuelInc());
    }

    @Test
    void anExactTotalSplitDoesNotStall() {
        // 64 items need 8 coal in all; the server's split is 41 items with 6 coal and 23 items with 2 (6 coal burn 9600 ticks, 41 items need 8200: what is left
        // burning is the second step's). A furnace that burns only while it cooks (the idealised one) must not stall on it
        FakeFurnace f = new FakeFurnace().carry(SAND, 64).carry(COAL, 8);
        f.burnOnlyWhileSmelting = true;
        SmeltJournal j = new SmeltJournal();
        j.open();
        FurnaceTrip.Outcome o = run(trip(f, glass(41, 6, 23, 2), j, false), f, 100_000);
        assertNull(o.failure(), o.detail());
        assertEquals(64, f.inventory.get(GLASS));
        assertEquals(0, f.inventory.getOrDefault(COAL, 0), "all 8 coal were used");
        assertEquals(41L + 23L, j.placed().get(SAND));
        assertEquals(8L, j.placed().get(COAL));
    }

    @Test
    void aSplitWithAFewTicksOfSlackRunsInARealFurnaceToo() {
        // The same 64 items with the fuel each step needs on its own (ceil per step: 6 and 3 coal)
        FakeFurnace f = new FakeFurnace().carry(SAND, 64).carry(COAL, 9);
        SmeltJournal j = new SmeltJournal();
        j.open();
        FurnaceTrip.Outcome o = run(trip(f, glass(41, 6, 23, 3), j, false), f, 100_000);
        assertNull(o.failure(), o.detail());
        assertEquals(64, f.inventory.get(GLASS));
    }

    @Test
    void aBatchIsSmeltedAndTheLeftoverFuelComesBack() {
        FakeFurnace f = new FakeFurnace().carry(SAND, 10).carry(COAL, 3);
        SmeltJournal j = new SmeltJournal();
        j.open();
        FurnaceTrip t = trip(f, glass(10, 3), j, false);
        FurnaceTrip.Outcome o = run(t, f, 20_000);
        assertNull(o.failure(), o.detail());
        assertEquals(10, f.inventory.get(GLASS));
        assertEquals(10, o.made(GLASS));
        assertEquals(10, o.smelted());
        // ceil(10 * 200 / 1600) = 2 coal were put in; 10 items need 2000 ticks, so both burn (1 and a bit); the third was never taken out of the inventory
        assertEquals(1, f.inventory.get(COAL), "two coal were burnt, the third never left the inventory");
        assertTrue(f.furnaceEmpty());
        assertEquals(10L, j.produced().get(GLASS));
        assertNull(j.active(), "the batch is done");
        assertTrue(o.xpGained() >= 10, "the xp of the furnace was seen: " + o.xpGained());
    }

    @Test
    void severalBatchesRunOneAfterTheOther() {
        FakeFurnace f = new FakeFurnace().carry(SAND, 70).carry(COAL, 9);
        SmeltJournal j = new SmeltJournal();
        j.open();
        FurnaceTrip.Outcome o = run(trip(f, glass(64, 8, 6, 1), j, false), f, 100_000);
        assertNull(o.failure(), o.detail());
        assertEquals(70, f.inventory.get(GLASS));
        assertEquals(0, f.inventory.getOrDefault(SAND, 0));
        assertEquals(70L, j.produced().get(GLASS));
        // Everything put in is counted: 70 sand and 9 coal; what was taken back out is in the outcome
        assertEquals(70L, j.placed().get(SAND));
        assertEquals(9L, j.placed().get(COAL));
    }

    @Test
    void aFurnaceThatMakesNothingTimesOutAndWhatIsOursComesBack() {
        FakeFurnace f = new FakeFurnace().carry(SAND, 5).carry(COAL, 1);
        f.frozen = true;
        SmeltJournal j = new SmeltJournal();
        j.open();
        FurnaceTrip.Outcome o = run(trip(f, glass(5, 1), j, false), f, 20_000);
        assertEquals(CraftOrder.SMELT_TIMEOUT, o.failure());
        assertEquals(5, f.inventory.get(SAND), "the input is taken back");
        assertEquals(1, f.inventory.get(COAL));
        assertEquals(0, o.smelted());
        assertEquals(5L, o.returned().get(SAND));
        assertTrue(f.furnaceEmpty());
    }

    @Test
    void aBusyFurnaceIsLeftAloneAndReported() {
        FakeFurnace f = new FakeFurnace().carry(SAND, 5).carry(COAL, 1).put(0, "minecraft:iron_ore", 20);
        SmeltJournal j = new SmeltJournal();
        j.open();
        FurnaceTrip.Outcome o = run(trip(f, glass(5, 1), j, false), f, 2000);
        assertEquals(CraftOrder.FURNACE_BUSY, o.failure());
        assertEquals(20, f.count[0], "somebody else's items are not touched");
        assertEquals(5, f.inventory.get(SAND));
        assertTrue(j.placed().isEmpty());
        // Even the bot's own furnace does not take what is not smelting's
        FakeFurnace own = new FakeFurnace().carry(SAND, 5).carry(COAL, 1).put(0, "minecraft:iron_ore", 20);
        FurnaceTrip.Outcome o2 = run(trip(own, glass(5, 1), new SmeltJournal(), true), own, 2000);
        assertEquals(CraftOrder.FURNACE_BUSY, o2.failure());
        assertEquals(20, own.count[0]);
    }

    @Test
    void leftoversOfSmeltingInTheBotsOwnFurnaceAreTakenOutFirst() {
        FakeFurnace f = new FakeFurnace().carry(SAND, 5).carry(COAL, 2).put(1, COAL, 3).put(2, GLASS, 4);
        SmeltJournal j = new SmeltJournal();
        j.open();
        // What the journal says the bot put in or took out is its own (a stopped run's leftovers); nothing else is
        j.placed(COAL, 3);
        j.collected(GLASS, 4);
        FurnaceTrip.Outcome o = run(trip(f, glass(5, 1), j, true), f, 20_000);
        assertNull(o.failure(), o.detail());
        assertEquals(5 + 4, f.inventory.get(GLASS), "the old output is in the inventory too");
        assertEquals(9L, j.produced().get(GLASS));
    }

    @Test
    void leftoversOfSmeltingAreTheBotsInItsOwnFurnaceEvenWithNoJournalEntryButNeverInAFoundOne() {
        // A crash between a click and its journal entry leaves what smelting uses in the furnace: in the bot's own furnace that is its own
        FakeFurnace f = new FakeFurnace().carry(SAND, 5).carry(COAL, 2).put(1, COAL, 3);
        FurnaceTrip.Outcome o = run(trip(f, glass(5, 1), new SmeltJournal(), true), f, 20_000);
        assertNull(o.failure(), o.detail());
        assertEquals(5, f.inventory.get(GLASS));
        // In a furnace found standing it is somebody else's
        FakeFurnace found = new FakeFurnace().carry(SAND, 5).carry(COAL, 2).put(1, COAL, 3);
        assertEquals(CraftOrder.FURNACE_BUSY, run(trip(found, glass(5, 1), new SmeltJournal(), false), found, 2000).failure());
        assertEquals(3, found.count[1], "not taken");
        // And what smelting does not use is nobody's of ours even in an own furnace (the iron ore case above)
        FakeFurnace odd = new FakeFurnace().carry(SAND, 5).carry(COAL, 2).put(1, "minecraft:diamond", 3);
        assertEquals(CraftOrder.FURNACE_BUSY, run(trip(odd, glass(5, 1), new SmeltJournal(), true), odd, 2000).failure());
    }

    @Test
    void leftoversTheFurnaceDebtNamesAreTheBotsOwnInItsOwnFurnace() {
        FakeFurnace f = new FakeFurnace().carry(SAND, 5).carry(COAL, 2).put(1, COAL, 3);
        FurnaceTrip t = trip(f, glass(5, 1), new SmeltJournal(), true).withDebtIds(Set.of(COAL));
        FurnaceTrip.Outcome o = run(t, f, 20_000);
        assertNull(o.failure(), o.detail());
        assertEquals(5, f.inventory.get(GLASS));
        // A found furnace is never the bot's, whatever the debt says
        FakeFurnace found = new FakeFurnace().carry(SAND, 5).carry(COAL, 2).put(1, COAL, 3);
        FurnaceTrip.Outcome b = run(trip(found, glass(5, 1), new SmeltJournal(), false).withDebtIds(Set.of(COAL)), found, 2000);
        assertEquals(CraftOrder.FURNACE_BUSY, b.failure());
    }

    @Test
    void aFurnaceThatIsNoLongerThereIsNoFurnaceNotBlocked() {
        SmeltJournal j = new SmeltJournal();
        j.open();
        // Gone when the bot arrives
        FakeFurnace f = new FakeFurnace().carry(SAND, 5).carry(COAL, 1);
        f.gone = true;
        FurnaceTrip.Outcome o = run(trip(f, glass(5, 1), j, false), f, 2000);
        assertEquals(CraftOrder.NO_FURNACE, o.failure());
        assertEquals(FurnaceTrip.GONE, o.detail());
        assertFalse(o.retry());
        assertEquals(0, f.opens, "never opened");
        assertEquals(5, f.inventory.get(SAND));
        // Gone while it is being opened (it never opens, then the block is found missing)
        FakeFurnace g = new FakeFurnace().carry(SAND, 5).carry(COAL, 1);
        g.refuseOpen = true;
        FurnaceTrip t = trip(g, glass(5, 1), j, false);
        for (int i = 0; i < 200; i++) {
            g.tick();
            t.tick();
        }
        g.gone = true;
        FurnaceTrip.Outcome o2 = run(t, g, 2000);
        assertEquals(CraftOrder.NO_FURNACE, o2.failure());
        // A recovery trip says the same, so the host drops the record
        FakeFurnace r = new FakeFurnace();
        r.gone = true;
        FurnaceTrip.Outcome o3 = run(FurnaceTrip.recover(r, j, () -> { }, 200, true, Set.of(SAND)), r, 2000);
        assertEquals(CraftOrder.NO_FURNACE, o3.failure());
    }

    @Test
    void aSmeltGoingOnInTheBotsOwnFurnaceIsLeftAloneAndAClearOnKnownIdsIsSaid() {
        // Input and fuel both there, nothing in the journal: somebody else's cook (or a run that is still going): not touched
        FakeFurnace cooking = new FakeFurnace().carry(SAND, 5).carry(COAL, 2).put(0, SAND, 8).put(1, COAL, 3);
        FurnaceTrip.Outcome busy = run(trip(cooking, glass(5, 1), new SmeltJournal(), true), cooking, 2000);
        assertEquals(CraftOrder.FURNACE_BUSY, busy.failure());
        assertTrue(cooking.count[0] >= 7 && cooking.count[1] >= 2, "still cooking, nothing taken");
        assertEquals(5, cooking.inventory.get(SAND));
        // Only leftover fuel and output: cleared on the known-id basis, and the trip says so
        FakeFurnace left = new FakeFurnace().carry(SAND, 5).carry(COAL, 2).put(1, COAL, 3).put(2, GLASS, 4);
        FurnaceTrip t = trip(left, glass(5, 1), new SmeltJournal(), true);
        FurnaceTrip.Outcome o = run(t, left, 20_000);
        assertNull(o.failure(), o.detail());
        assertTrue(t.clearedOnKnownIds());
        // Leftovers the journal accounts for are not "on that basis"
        FakeFurnace own = new FakeFurnace().carry(SAND, 5).carry(COAL, 2).put(1, COAL, 3);
        SmeltJournal j = new SmeltJournal();
        j.open();
        j.placed(COAL, 3);
        FurnaceTrip t2 = trip(own, glass(5, 1), j, true);
        assertNull(run(t2, own, 20_000).failure());
        assertFalse(t2.clearedOnKnownIds());
    }

    @Test
    void aFurnaceThatCannotBeReachedOrOpenedIsBlocked() {
        FakeFurnace far = new FakeFurnace();
        far.arrived = false;
        FurnaceTrip.Outcome o = run(trip(far, glass(5, 1), new SmeltJournal(), false), far, FurnaceTrip.WALK_TICKS + 500);
        assertEquals(CraftOrder.FURNACE_BLOCKED, o.failure());
        assertEquals(FurnaceTrip.NOT_REACHED, o.detail());
    }

    @Test
    void aWindowClosedUnderTheTripIsOpenedAgainAndTheTripGoesOn() {
        FakeFurnace f = new FakeFurnace().carry(SAND, 8).carry(COAL, 1);
        f.closeAt = 600;
        SmeltJournal j = new SmeltJournal();
        j.open();
        FurnaceTrip.Outcome o = run(trip(f, glass(8, 1), j, false), f, 30_000);
        assertNull(o.failure(), o.detail());
        assertEquals(8, f.inventory.get(GLASS));
        assertEquals(2, f.opens);
        assertEquals(8L, j.placed().get(SAND), "the input was not put in twice");
    }

    @Test
    void aWindowThatCannotBeOpenedAgainLeavesTheFurnaceStuckNotClosedOver() {
        FakeFurnace f = new FakeFurnace().carry(SAND, 8).carry(COAL, 1);
        f.closeAt = 600;
        f.refuseOpenAfterClose = true;
        SmeltJournal j = new SmeltJournal();
        j.open();
        FurnaceTrip.Outcome o = run(trip(f, glass(8, 1), j, false), f, 30_000);
        assertEquals(CraftOrder.INTERRUPTED, o.failure());
        assertTrue(o.stuck(), "something of ours is still inside");
        assertFalse(o.retry());
        assertTrue(f.count[0] > 0 || f.count[2] > 0, "the furnace still holds the batch");
        assertTrue(j.isOpen(), "the journal is not closed while items may be in the furnace");
    }

    @Test
    void anAbortWithTheWindowClosedOpensItAgainAndEmptiesTheFurnace() {
        FakeFurnace f = new FakeFurnace().carry(SAND, 20).carry(COAL, 2);
        SmeltJournal j = new SmeltJournal();
        j.open();
        FurnaceTrip t = trip(f, glass(20, 2), j, false);
        for (int i = 0; i < 700; i++) {
            f.tick();
            t.tick();
        }
        f.open = false;
        t.abort("the order ended");
        FurnaceTrip.Outcome o = run(t, f, 5000);
        assertEquals(CraftOrder.INTERRUPTED, o.failure());
        assertFalse(o.stuck());
        assertTrue(f.furnaceEmpty(), "nothing was left inside");
        assertEquals(2, f.opens);
        assertEquals(20, f.inventory.getOrDefault(GLASS, 0) + f.inventory.getOrDefault(SAND, 0));
    }

    @Test
    void aFurnaceSeenEmptyIsReported() {
        FakeFurnace f = new FakeFurnace().carry(SAND, 3).carry(COAL, 1);
        FurnaceTrip t = trip(f, glass(3, 1), new SmeltJournal(), false);
        run(t, f, 20_000);
        assertTrue(t.wasEmpty());
        FakeFurnace busy = new FakeFurnace().put(0, "minecraft:iron_ore", 2);
        FurnaceTrip b = trip(busy, glass(3, 1), new SmeltJournal(), false);
        run(b, busy, 2000);
        assertFalse(b.wasEmpty());
    }

    @Test
    void anOrderThatEndsMakesTheTripTakeWhatItPutInBack() {
        FakeFurnace f = new FakeFurnace().carry(SAND, 20).carry(COAL, 2);
        SmeltJournal j = new SmeltJournal();
        j.open();
        FurnaceTrip t = trip(f, glass(20, 2), j, false);
        for (int i = 0; i < 700; i++) {
            f.tick();
            t.tick();
        }
        assertTrue(f.count[0] > 0, "still smelting");
        t.abort("the order ended");
        FurnaceTrip.Outcome o = run(t, f, 5000);
        assertEquals(CraftOrder.INTERRUPTED, o.failure());
        assertTrue(o.retry());
        assertTrue(f.furnaceEmpty());
        // What was made and what was not: 20 sand = what was smelted + what came back
        assertEquals(20, f.inventory.getOrDefault(GLASS, 0) + f.inventory.getOrDefault(SAND, 0));
        assertEquals(o.smelted(), f.inventory.getOrDefault(GLASS, 0));
    }

    @Test
    void aFullInventoryLeavesTheFurnaceStuckAfterAMinute() {
        FakeFurnace f = new FakeFurnace().carry(SAND, 3).carry(COAL, 1);
        f.noRoom = true;
        SmeltJournal j = new SmeltJournal();
        j.open();
        FurnaceTrip.Outcome o = run(trip(f, glass(3, 1), j, false), f, 30_000);
        assertEquals(CraftOrder.INVENTORY_FULL, o.failure());
        assertTrue(o.stuck());
        assertTrue(j.isOpen(), "the journal stays open: the furnace still holds ours");
    }

    @Test
    void aStoppedRunIsTakenUpAndSettledWithoutLosingOrCountingTwice() {
        // The bot stopped with 20 sand loaded: 6 glass were taken before (recorded, in the inventory), 6 wait in the output, 8 sand cook on, 1 coal is in
        SmeltJournal j = new SmeltJournal();
        j.open();
        j.placed(SAND, 20);
        j.placed(COAL, 3);
        j.loaded(new SmeltJournal.Active("qwerte:smelt/glass", SAND, 20, GLASS, 20, 6, COAL));
        j.collected(GLASS, 6);
        // It went through the saved ledger form and back
        SmeltJournal back = SmeltJournal.fromJson(j.toJson());
        assertTrue(back.isOpen());
        assertEquals(j.placed(), back.placed());
        assertEquals(j.produced(), back.produced());
        assertEquals(j.active(), back.active());

        FakeFurnace f = new FakeFurnace().put(0, SAND, 8).put(1, COAL, 1).put(2, GLASS, 6).carry(GLASS, 6);
        f.burnLeft = 1000;
        FurnaceTrip.Outcome o = run(FurnaceTrip.recover(f, back, () -> { }, SM.cookTicks(), true, Set.of(SAND, GLASS, COAL)), f, 30_000);
        assertNull(o.failure(), o.detail());
        // 6 in the output + the 8 that cooked on = 14 taken now; with the 6 from before: all 20
        assertEquals(20, f.inventory.get(GLASS));
        assertEquals(20L, back.produced().get(GLASS));
        SmeltJournal.Settlement s = back.settle(o.returned(), Map.of(GLASS, 20L));
        assertEquals(20L, s.charge().get(SAND));
        assertEquals(20L, s.surplus().get(GLASS), "all the glass carried is the order's, counted once");
        assertTrue(f.furnaceEmpty());
    }

    @Test
    void settlingNeverClaimsMoreThanIsCarriedNorChargesWhatCameBack() {
        SmeltJournal j = new SmeltJournal();
        j.open();
        j.placed(SAND, 64);
        j.placed(COAL, 8);
        j.collected(GLASS, 20);
        SmeltJournal.Settlement s = j.settle(Map.of(SAND, 44L, COAL, 6L), Map.of(GLASS, 15L));
        assertEquals(Map.of(SAND, 20L, COAL, 2L), s.charge());
        assertEquals(Map.of(GLASS, 15L), s.surplus(), "five were lost from the inventory since: not claimed");
        // A journal that was closed says nothing
        SmeltJournal none = SmeltJournal.fromJson(new SmeltJournal().toJson());
        assertFalse(none.isOpen());
        assertTrue(none.settle(Map.of(), Map.of(GLASS, 9L)).surplus().isEmpty());
    }
}
