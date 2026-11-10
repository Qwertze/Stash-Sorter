package com.porter.qwertestash.orders;

import com.porter.qwertestash.stash.KitTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The restock job as the order machine runs it (KIT-CONTRACT v2, revision 4): a restock order needs no delivery container, delivers nothing, names the
 * kit and leaves it carried for the sorter to file; a kit that falls short is kept as an incomplete copy; it is never reported to the server; a cancel
 * stops it and ends it. Also the budget (bottle reserve) and the persisted hourly cap.
 */
class KitRestockJobTest {
    private static final String D = "minecraft:diamond", P = "minecraft:ender_pearl", T = "t-0000aaaa";

    private static KitTemplate template() {
        List<KitTemplate.Slot> slots = new ArrayList<>();
        slots.add(new KitTemplate.Slot(D, D, 4));
        slots.add(new KitTemplate.Slot(P, P, 2));
        while (slots.size() < 27) slots.add(null);
        return new KitTemplate(T, "Pack", "pvp", "1,64,1", null, 2, slots);
    }

    private static OrderSim world() {
        OrderSim w = new OrderSim().stock(D, "v", 100, 64).stock(P, "p", 16, 16);
        w.empties.put("minecraft:red_shulker_box", 2);
        w.deliverySet = false;
        return w;
    }

    private static Order restock(OrderSim w, boolean kitShort) {
        Unit u = new Unit(Unit.Kind.Bin, "", new ArrayList<>(List.of(new Order.Lot(D, "v", 4), new Order.Lot(P, "p", 2))));
        u.kitTemplate = T;
        u.name = "Pack";
        u.kitShort = kitShort;
        w.kitPlan = new KitPlanner.Plan(List.of(u), List.of(), List.of());
        KitOrder.Snapshot snap = new KitOrder.Snapshot(1, List.of(new KitOrder.Line(template(), 1, 0, 1)), KitRestock.takeFor(template(), 0), false);
        Order o = new Order("restock-1", List.of(new Order.Line(Order.KIT, T, 1)), null, false, null, snap);
        o.restock = true;
        return o;
    }

    @Test
    void aRestockOrderNeedsNoDeliveryContainerAndDeliversNothing() {
        OrderSim w = world();
        assertNull(w.flow.offer(restock(w, false)));
        assertTrue(w.flow.isRestock());
        w.run(200);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertTrue(w.delivery.isEmpty(), "nothing is put in the delivery container");
        assertEquals(1, w.carried.size(), "the named kit stays carried for the sorter to file");
        assertEquals(List.of("Pack"), w.named);
        assertEquals(0, r.shulkers());
    }

    @Test
    void aKitThatFallsShortIsKeptAsAnIncompleteCopyNotDelivered() {
        OrderSim w = world();
        assertNull(w.flow.offer(restock(w, true)));
        w.run(200);
        OrderFlow.Report r = w.flow.report();
        assertTrue(w.delivery.isEmpty());
        assertNotNull(r.kit());
        assertEquals("incomplete", r.kit().getAsJsonArray("short").get(0).getAsJsonObject().get("stored").getAsString());
        assertEquals(List.of("Pack"), w.named);
    }

    @Test
    void theDriverNeverReportsARestockJobAndAcksItWhenDone() {
        OrderSim w = world();
        OrderDriver d = new OrderDriver(w.flow, () -> 1_000_000L, (level, text) -> { });
        assertNull(w.flow.offer(restock(w, false)));
        assertNull(d.next(true, null, "2b2t.org", "stash", "Bot", true), "no status, and no claim while the job runs");
        w.run(200);
        assertNull(d.next(true, null, "2b2t.org", "stash", "Bot", true));
        assertFalse(w.flow.holdsOrder(), "the finished job is released, so a claim can follow");
        d.onHeartbeat("{\"ok\":true,\"orders\":{\"pending\":1,\"cancel\":[]}}");
        assertEquals("/api/v1/orders/claim", d.next(true, null, "2b2t.org", "stash", "Bot", true).path());
    }

    @Test
    void aCancelStopsTheJob() {
        OrderSim w = world();
        assertNull(w.flow.offer(restock(w, false)));
        assertTrue(w.flow.cancel("restock-1"));
        w.run(50);
        assertEquals("cancelled", w.flow.report().status());
        assertTrue(w.delivery.isEmpty());
    }

    @Test
    void theBudgetKeepsTheBottleReserve() {
        List<KitTemplate.Slot> slots = new ArrayList<>();
        slots.add(new KitTemplate.Slot("minecraft:experience_bottle", "minecraft:experience_bottle", 64));
        slots.add(new KitTemplate.Slot(D, D, 1));
        slots.add(new KitTemplate.Slot("minecraft:totem_of_undying#*", "minecraft:totem_of_undying#*", 1));
        while (slots.size() < 27) slots.add(null);
        KitTemplate t = new KitTemplate(T, "Pack", "other", null, null, 1, slots);
        assertEquals(0, KitRestock.bottlesUsable(1000));
        assertEquals(72, KitRestock.bottlesUsable(1800));
        List<KitOrder.Take> none = KitRestock.takeFor(t, 0);
        assertEquals(List.of(D, "minecraft:totem_of_undying#*"), none.stream().map(KitOrder.Take::match).toList(), "no bottles may be taken");
        List<KitOrder.Take> some = KitRestock.takeFor(t, 30);
        assertEquals(30, some.stream().filter(x -> x.match().equals("minecraft:experience_bottle")).findFirst().get().count());
        assertEquals(64, KitRestock.takeFor(t, 5000).get(0).count());
    }

    @Test
    void theHourlyStampsSurviveARestartAndExpire(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kit-restock.json");
        KitRestockStamps a = new KitRestockStamps(file);
        long now = 10 * KitRestockStamps.HOUR_MS;
        a.add(now - 2 * KitRestockStamps.HOUR_MS);
        a.add(now - 1000);
        a.add(now);
        assertEquals(2, a.lastHour(now));
        assertTrue(Files.isRegularFile(file));
        KitRestockStamps again = new KitRestockStamps(file);
        assertEquals(2, again.lastHour(now), "the cap is not reset by a restart");
        assertEquals(0, again.lastHour(now + 2 * KitRestockStamps.HOUR_MS));
        // A broken file is an empty record
        Files.writeString(file, "not json");
        assertEquals(0, new KitRestockStamps(file).lastHour(now));
    }

    @Test
    void theReportCarriesTheRestockState() {
        com.porter.qwertestash.stash.Stash stash = new com.porter.qwertestash.stash.Stash("s", "srv");
        KitRestock.Decision d = new KitRestock.Decision(KitRestock.State.WAITING, T, KitRestock.ITEMS_SHORT, false);
        var built = com.porter.qwertestash.manager.KitReport.buildV2(stash, false, "Bot", List.of(), com.porter.qwertestash.stash.KitScore.Scoring.DEFAULT,
            com.porter.qwertestash.stash.KitItems.shared(), null, d);
        var restock = com.google.gson.JsonParser.parseString(new String(built.json(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("restock");
        assertEquals("waiting", restock.get("state").getAsString());
        assertEquals(T, restock.get("template").getAsString());
        assertEquals("items_short", restock.get("reason").getAsString());
        var off = com.porter.qwertestash.manager.KitReport.buildV2(stash, false, "Bot", List.of(), com.porter.qwertestash.stash.KitScore.Scoring.DEFAULT,
            com.porter.qwertestash.stash.KitItems.shared(), null);
        assertEquals("off", com.google.gson.JsonParser.parseString(new String(off.json(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("restock").get("state").getAsString());
    }
}
