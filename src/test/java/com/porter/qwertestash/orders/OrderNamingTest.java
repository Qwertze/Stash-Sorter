package com.porter.qwertestash.orders;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shulker names on the order machine: the i-th shulker of the plan gets the i-th name, a shulker that can't be named is
 * delivered unnamed with one combined note, and the names survive the saved state and a resume.
 */
class OrderNamingTest {
    private static final String D = "minecraft:diamond", S = "minecraft:stone", RED = "minecraft:red_shulker_box", BLUE = "minecraft:blue_shulker_box";
    private static final String KIT = "s:0123456789";

    private static Order.Line item(String key, int count) {
        return new Order.Line("item", key, count);
    }

    private static Order.Line shulker(String key, int count) {
        return new Order.Line("shulker", key, count);
    }

    private static OrderSim world() {
        OrderSim w = new OrderSim().stock(D, "v", 100_000, 64).stock(S, "w", 100_000, 64);
        w.empties.put(RED, 10);
        w.empties.put(BLUE, 2);
        w.packed.put(KIT, 3);
        return w;
    }

    @Test
    void eachNameGoesToTheShulkerAtItsIndexInThePlannedLayout() {
        OrderSim w = world();
        // Planned layout: 2 packed shulkers, then 1 empty shulker, then the bins (27 stacks of diamonds, then 1 of stone)
        Order o = new Order("o-each", List.of(item(D, 64 * 28), shulker(KIT, 2), item(BLUE, 1)),
            ShulkerNames.each(List.of("Kit A", "", "Empty", "Diamonds 1", "Diamonds 2")));
        assertNull(w.flow.offer(o));
        List<Unit> units = w.flow.units();
        assertEquals(5, units.size());
        assertEquals(Unit.Kind.Shulker, units.get(0).kind);
        assertEquals(Unit.Kind.Shulker, units.get(1).kind);
        assertEquals(Unit.Kind.Empty, units.get(2).kind);
        assertEquals(Unit.Kind.Bin, units.get(3).kind);
        assertEquals(Unit.Kind.Bin, units.get(4).kind);
        assertEquals("Kit A", units.get(0).name);
        assertNull(units.get(1).name);
        assertEquals("Empty", units.get(2).name);
        assertEquals("Diamonds 2", units.get(4).name);

        w.run(200);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(5, r.shulkers());
        assertEquals(List.of("Kit A", "Empty", "Diamonds 1", "Diamonds 2"), w.named);
        // Delivered in that order, each with its name (the blank one unnamed)
        assertEquals("Kit A", w.delivery.get(0).name());
        assertNull(w.delivery.get(1).name());
        assertEquals("Empty", w.delivery.get(2).name());
        assertEquals("Diamonds 1", w.delivery.get(3).name());
        assertFalse(r.message().contains("unnamed"), r.message());
    }

    @Test
    void allNamesEveryShulkerAndExtraEachNamesGoUnused() {
        OrderSim w = world();
        assertNull(w.flow.offer(new Order("o-all", List.of(item(D, 100), shulker(KIT, 1)), ShulkerNames.all("Base"))));
        w.run(50);
        assertEquals("delivered", w.flow.report().status());
        assertEquals(List.of("Base", "Base"), w.named);

        OrderSim v = world();
        assertNull(v.flow.offer(new Order("o-more", List.of(item(D, 10)), ShulkerNames.each(List.of("One", "Two", "Three")))));
        v.run(50);
        assertEquals(List.of("One"), v.named);
        assertEquals(1, v.delivery.size());
    }

    @Test
    void withNoAnvilEveryShulkerIsDeliveredUnnamedWithOneNote() {
        OrderSim w = world();
        w.anvil = false;
        assertNull(w.flow.offer(new Order("o-noanvil", List.of(item(D, 100), shulker(KIT, 2)), ShulkerNames.all("Base"))));
        w.run(80);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertEquals(3, r.shulkers());
        assertEquals(0, w.anvilTrips);
        assertEquals("3 shulker(s) delivered unnamed: no anvil set", r.message());
    }

    @Test
    void tooFewLevelsNamesWhatItCanAndDeliversTheRest() {
        OrderSim w = world();
        w.xp = 1;
        assertNull(w.flow.offer(new Order("o-xp", List.of(shulker(KIT, 3)), ShulkerNames.all("Kit"))));
        w.run(80);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertEquals(3, r.shulkers());
        assertEquals(List.of("Kit"), w.named);
        assertEquals("2 shulker(s) delivered unnamed: " + AnvilNamer.TOO_FEW_LEVELS, r.message());
    }

    @Test
    void aTimeoutAtTheAnvilDeliversUnnamed() {
        OrderSim w = world();
        w.anvilTrip = "timeout";
        assertNull(w.flow.offer(new Order("o-slow", List.of(item(D, 10)), ShulkerNames.all("Slow"))));
        w.run(40);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertEquals(1, w.anvilTrips);
        assertEquals("1 shulker(s) delivered unnamed: " + AnvilNamer.NO_RESULT, r.message());
    }

    @Test
    void aTripCutShortIsMadeOnceMoreThenItGoesUnnamed() {
        OrderSim w = world();
        w.anvilTrip = "closed";
        assertNull(w.flow.offer(new Order("o-closed", List.of(item(D, 10)), ShulkerNames.all("X"))));
        w.run(40);
        assertEquals(OrderFlow.MAX_NAME_TRIES, w.anvilTrips);
        assertEquals("1 shulker(s) delivered unnamed: " + AnvilNamer.CLOSED, w.flow.report().message());
    }

    @Test
    void aNameThatBreaksTheRulesIsNotTakenToTheAnvil() {
        OrderSim w = world();
        assertNull(w.flow.offer(new Order("o-long", List.of(item(D, 10), shulker(KIT, 1)),
            ShulkerNames.each(List.of("k".repeat(51), "§cRed")))));
        w.run(40);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertEquals(0, w.anvilTrips);
        assertEquals("2 shulker(s) delivered unnamed: a name that breaks the rules", r.message());
    }

    @Test
    void differentReasonsAreListedOnceEach() {
        OrderSim w = world();
        w.xp = 0;
        assertNull(w.flow.offer(new Order("o-mixed", List.of(shulker(KIT, 2), item(D, 10)),
            ShulkerNames.each(List.of("A", "B", "c".repeat(60))))));
        w.run(80);
        assertEquals("3 shulker(s) delivered unnamed: " + AnvilNamer.TOO_FEW_LEVELS + ", a name that breaks the rules", w.flow.report().message());
    }

    @Test
    void aShulkerThatAlreadyHasTheNameIsNotTakenToTheAnvilAgain() {
        OrderSim w = world();
        assertNull(w.flow.offer(new Order("o-has", List.of(item(D, 10)), ShulkerNames.all("Done"))));
        // Built, then (say) named before a restart: it already carries the name
        assertTrue(w.step());
        w.carried.set(0, w.carried.get(0).named("Done"));
        w.run(20);
        assertEquals(0, w.anvilTrips);
        assertEquals("delivered", w.flow.report().status());
        assertFalse(w.flow.report().message().contains("unnamed"));
    }

    @Test
    void anOrderThatEndsEarlyStillCarriesTheNote() {
        OrderSim w = world();
        w.anvil = false;
        w.deliveryCapacity = 1;
        assertNull(w.flow.offer(new Order("o-full", List.of(shulker(KIT, 3)), ShulkerNames.all("K"))));
        w.run(80);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertTrue(r.message().startsWith("delivery container full"), r.message());
        assertTrue(r.message().endsWith("; 1 shulker(s) delivered unnamed: no anvil set"), r.message());
    }

    @Test
    void namesAndTheUnnamedCountSurviveTheSavedStateAndAResume() {
        OrderSim w = world();
        w.anvil = false;
        Order o = new Order("o-resume", List.of(shulker(KIT, 3)), ShulkerNames.each(List.of("First", "Second", "Third")));
        assertNull(w.flow.offer(o));
        // The first one goes unnamed (no anvil yet), then the bot goes offline
        while (w.delivery.isEmpty()) w.step();
        w.step();
        assertEquals(1, w.saved.done);
        w.flow.reset();

        // The saved state goes through the stash file (Gson) and back
        Gson gson = new Gson();
        w.saved = gson.fromJson(gson.toJson(w.saved), OrderFlow.Saved.class);
        assertEquals("Second", w.saved.units.get(1).name);
        assertEquals(1, w.saved.unnamed);

        // The anvil is set now; the server hands the order back (without names, say): the saved names are used
        w.anvil = true;
        assertNull(w.flow.offer(new Order("o-resume", o.lines)));
        assertEquals("Second", w.flow.units().get(1).name);
        assertEquals("Third", w.flow.units().get(2).name);
        w.run(80);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertEquals(List.of("Second", "Third"), w.named);
        assertEquals("1 shulker(s) delivered unnamed: no anvil set", r.message());
    }

    @Test
    void aPlanSavedBeforeNamesExistedTakesTheNamesFromTheOrder() {
        OrderSim w = world();
        Order plain = new Order("o-old", List.of(shulker(KIT, 2)));
        assertNull(w.flow.offer(plain));
        w.flow.reset();
        // What a 1.6 plugin wrote: no name fields at all
        String json = new Gson().toJson(w.saved).replaceAll(",\"name\":\"\"", "").replaceAll(",\"nameDone\":false", "")
            .replaceAll(",\"nameFailure\":\"\"", "").replaceAll(",\"namedKey\":\"\"", "").replaceAll(",\"unnamed\":0", "").replaceAll(",\"unnamedReasons\":\\[]", "");
        assertFalse(json.contains("name"), json);
        w.saved = new Gson().fromJson(json, OrderFlow.Saved.class);
        assertNull(w.flow.offer(new Order("o-old", plain.lines, ShulkerNames.all("Late"))));
        w.run(60);
        assertEquals(List.of("Late", "Late"), w.named);
        assertEquals("delivered", w.flow.report().status());
    }

    @Test
    void aShulkerInTheDeliveryContainerBeforeARestartCountsItsNamingRight() {
        OrderSim w = world();
        w.anvil = false;
        w.deferConfirm = true;
        assertNull(w.flow.offer(new Order("o-inflight", List.of(shulker(KIT, 1)), ShulkerNames.all("N"))));
        // Fetched, then put in: the bot goes offline before it saw it arrive
        while (w.delivery.isEmpty()) w.step();
        w.flow.reset();
        w.deferConfirm = false;
        assertNull(w.flow.offer(new Order("o-inflight", List.of(shulker(KIT, 1)))));
        w.run(20);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(1, r.shulkers());
        assertEquals("1 shulker(s) delivered unnamed: no anvil set", r.message());
    }

    @Test
    void theNamingStateIsSavedRightAfterTheAnvilNotOnlyAtDelivery() {
        OrderSim w = world();
        assertNull(w.flow.offer(new Order("o-m3", List.of(shulker(KIT, 1)), ShulkerNames.all("New"))));
        assertTrue(w.step());
        assertEquals("fetch", w.last);
        assertTrue(w.step());
        assertEquals("name", w.last);
        // The bot goes offline right after the rename, before it starts to deliver
        w.flow.reset();
        Gson gson = new Gson();
        w.saved = gson.fromJson(gson.toJson(w.saved), OrderFlow.Saved.class);
        assertTrue(w.saved.units.get(0).nameDone);
        assertEquals(KIT + "~named", w.saved.units.get(0).namedKey);
        assertNull(w.flow.offer(new Order("o-m3", List.of(shulker(KIT, 1)))));
        Unit u = w.flow.units().get(0);
        assertTrue(u.nameDone);
        assertEquals(KIT + "~named", u.namedKey);
        w.run(20);
        assertEquals(1, w.anvilTrips, "not taken to the anvil again");
        assertEquals("delivered", w.flow.report().status());
        assertFalse(w.flow.report().message().contains("unnamed"));
    }

    @Test
    void aFailureIsSavedRightAwayToo() {
        OrderSim w = world();
        w.anvilTrip = "timeout";
        assertNull(w.flow.offer(new Order("o-m3b", List.of(shulker(KIT, 1)), ShulkerNames.all("X"))));
        w.step();
        w.step();
        assertEquals("name", w.last);
        assertEquals(AnvilNamer.NO_RESULT, w.saved.units.get(0).nameFailure);
    }

    @Test
    void aTripTheHostCouldNotStartIsNotCounted() {
        OrderSim w = world();
        assertNull(w.flow.offer(new Order("o-l1", List.of(shulker(KIT, 1)), ShulkerNames.all("K"))));
        w.step();
        // The host turns the Name step down a few times (busy with something else) without starting a trip
        for (int i = 0; i < OrderFlow.MAX_NAME_TRIES + 3; i++) assertTrue(w.flow.next() instanceof OrderFlow.Name);
        assertEquals(0, w.flow.units().get(0).nameTries);
        w.run(20);
        assertEquals(List.of("K"), w.named);
    }

    @Test
    void aShulkerThatCarriesItsNameWhenItGoesInIsNotCountedUnnamed() {
        OrderSim w = world();
        w.anvil = false;
        w.deliveryCapacity = 0;
        assertNull(w.flow.offer(new Order("o-l2", List.of(shulker(KIT, 1)), ShulkerNames.all("Mine"))));
        w.step();
        // Naming is given up (no anvil); the delivery container is full, so the shulker stays carried
        w.step();
        assertEquals("deliver", w.last);
        assertEquals("no anvil set", w.flow.units().get(0).nameFailure);
        // Someone named it by hand meanwhile
        w.carried.set(0, w.carried.get(0).named("Mine"));
        w.deliveryCapacity = 54;
        w.run(20);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertFalse(r.message().contains("unnamed"), r.message());
    }

    @Test
    void theUnnamedNoteIsAddedOnceWholeAndTheMessageFitsTheServer() {
        String note = "2 shulker(s) delivered unnamed: no anvil set";
        String longText = "delivered less than asked: " + "minecraft:some_long_item_name 0/640, ".repeat(12);
        String out = OrderFlow.withUnnamedNote(longText, note);
        assertTrue(out.length() <= OrderFlow.MESSAGE_MAX, out.length() + ": " + out);
        assertTrue(out.endsWith("; " + note), out);
        assertEquals(1, out.split(java.util.regex.Pattern.quote(ShulkerNames.UNNAMED_MARK), -1).length - 1, out);

        // Already there, even cut short: not added again
        String cutShort = "nothing much; 2 shulker(s) delivered unnamed: no an ...";
        assertEquals(cutShort, OrderFlow.withUnnamedNote(cutShort, note));
        assertEquals(note, OrderFlow.withUnnamedNote("", note));
        assertEquals("cancelled; " + note, OrderFlow.withUnnamedNote("cancelled", note));
        assertEquals("cancelled", OrderFlow.withUnnamedNote("cancelled", null));
    }

    @Test
    void aParkWhileTheMachineRunsIsSeenAsRequested() {
        OrderSim w = world();
        assertNull(w.flow.offer(new Order("o-park", List.of(shulker(KIT, 1)), ShulkerNames.all("P"))));
        assertFalse(w.flow.parkRequested());
        w.flow.park();
        assertTrue(w.flow.parkRequested());
        w.flow.next();
        assertFalse(w.flow.parkRequested());
    }
}
