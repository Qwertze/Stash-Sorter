package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bot's side of the order protocol, against an in-memory server. */
class OrderDriverTest {
    private static final String D = "minecraft:diamond", RED = "minecraft:red_shulker_box";

    /** What the server does with a request. */
    private static final class Server {
        final ArrayDeque<String> pending = new ArrayDeque<>();
        final List<String> paths = new ArrayList<>();
        final List<JsonObject> statuses = new ArrayList<>();
        final List<JsonObject> claims = new ArrayList<>();
        /** Code to answer status requests with (200 normally). */
        int statusCode = 200;
        int statusRefusals = Integer.MAX_VALUE;

        int[] code = new int[1];
        String body = "";

        void handle(OrderDriver.Request r) {
            paths.add(r.path());
            JsonObject json = JsonParser.parseString(new String(r.body(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (r.path().equals("/api/v1/orders/claim")) {
                claims.add(json);
                if (pending.isEmpty()) {
                    code[0] = 204;
                    body = "";
                }
                else {
                    code[0] = 200;
                    body = pending.poll();
                }
                return;
            }
            statuses.add(json);
            if (statusCode != 200 && statusRefusals-- > 0) {
                code[0] = statusCode;
                body = "{\"ok\":false,\"error\":\"nope\"}";
            }
            else {
                code[0] = 200;
                body = "{\"ok\":true}";
            }
        }
    }

    private static String orderJson(String id, String lines) {
        return "{\"ok\":true,\"order\":{\"id\":\"" + id + "\",\"lines\":" + lines + "}}";
    }

    private static final String DIAMONDS = "[{\"type\":\"item\",\"key\":\"minecraft:diamond\",\"count\":100}]";

    private final List<String> events = new ArrayList<>();

    private OrderDriver driver(OrderSim sim) {
        return new OrderDriver(sim.flow, () -> sim.now, (level, text) -> events.add(level + ": " + text));
    }

    /** Sends requests until the driver has none. @return how many */
    private int pump(OrderDriver d, Server s, OrderSim sim, boolean accept, String blocker) {
        int n = 0;
        while (n < 20) {
            OrderDriver.Request r = d.next(accept, blocker, "2b2t.org", "stash", "Bot", true);
            if (r == null) break;
            s.handle(r);
            boolean ok = r.reply().done(s.code[0], s.body);
            if (!ok) sim.now += 6_000;
            n++;
        }
        return n;
    }

    private static OrderSim world() {
        OrderSim w = new OrderSim().stock(D, "v", 1000, 64);
        w.empties.put(RED, 5);
        return w;
    }

    @Test
    void claimWorkReportAndFinish() {
        OrderSim w = world();
        Server s = new Server();
        OrderDriver d = driver(w);
        d.onHeartbeat("{\"ok\":true,\"orders\":{\"pending\":1,\"cancel\":[]}}");
        assertEquals(1, d.pending());
        s.pending.add(orderJson("o-aaaaaaaaaaaa", DIAMONDS));

        // The claim names the stash and the player
        OrderDriver.Request claim = d.next(true, null, "2b2t.org", "stash", "Bot", true);
        assertEquals("/api/v1/orders/claim", claim.path());
        JsonObject body = JsonParser.parseString(new String(claim.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("2b2t.org", body.get("server").getAsString());
        assertEquals("stash", body.get("stash").getAsString());
        assertEquals("Bot", body.get("player").getAsString());
        s.handle(claim);
        assertTrue(claim.reply().done(s.code[0], s.body));
        assertTrue(w.flow.running());
        assertEquals(0, d.pending());
        assertTrue(events.get(0).startsWith("info: Order o-aaaaaaaaaaaa claimed"));

        // The first status says in_progress with the plan
        pump(d, s, w, true, null);
        JsonObject first = s.statuses.get(0);
        assertEquals("in_progress", first.get("status").getAsString());
        assertEquals("Bot", first.get("player").getAsString());
        assertEquals(1, first.getAsJsonObject("progress").get("total").getAsInt());
        assertFalse(first.has("result"));

        // Work, then the end is reported with the result and the order is forgotten
        w.run(10);
        pump(d, s, w, true, null);
        JsonObject last = s.statuses.get(s.statuses.size() - 1);
        assertEquals("delivered", last.get("status").getAsString());
        assertEquals(1, last.getAsJsonObject("result").get("shulkers").getAsInt());
        JsonObject line = last.getAsJsonObject("result").getAsJsonArray("lines").get(0).getAsJsonObject();
        assertEquals(D, line.get("key").getAsString());
        assertEquals(100, line.get("delivered").getAsInt());
        assertFalse(w.flow.holdsOrder());
        assertNull(w.saved);
        assertTrue(events.stream().anyMatch(e -> e.startsWith("info: Order o-aaaaaaaaaaaa delivered")));
    }

    @Test
    void noClaimWhileBlockedOrNothingPending() {
        OrderSim w = world();
        Server s = new Server();
        OrderDriver d = driver(w);
        assertNull(d.next(true, null, "2b2t.org", "stash", "Bot", true));
        d.onHeartbeat("{\"orders\":{\"pending\":2}}");
        assertNull(d.next(true, "no delivery container set", "2b2t.org", "stash", "Bot", true));
        assertNull(d.next(false, null, "2b2t.org", "stash", "Bot", true));
        assertNotNull(d.next(true, null, "2b2t.org", "stash", "Bot", true));
        // A claim is not repeated at once
        assertNull(d.next(true, null, "2b2t.org", "stash", "Bot", true));
        w.now += OrderDriver.CLAIM_MS;
        assertNotNull(d.next(true, null, "2b2t.org", "stash", "Bot", true));
    }

    @Test
    void a204ClearsThePendingCount() {
        OrderSim w = world();
        Server s = new Server();
        OrderDriver d = driver(w);
        d.onHeartbeat("{\"orders\":{\"pending\":3}}");
        assertEquals(1, pump(d, s, w, true, null));
        assertEquals(0, d.pending());
        assertEquals(204, s.code[0]);
    }

    @Test
    void anOrderHeldFromBeforeARestartIsClaimedAgain() {
        OrderSim w = world();
        w.flow.offer(new Order("o-bbbbbbbbbbbb", List.of(new Order.Line("item", D, 10))));
        w.flow.reset();
        Server s = new Server();
        s.pending.add(orderJson("o-bbbbbbbbbbbb", "[{\"type\":\"item\",\"key\":\"minecraft:diamond\",\"count\":10}]"));
        OrderDriver d = driver(w);
        // Nothing pending on the website's count, but a saved order is asked about
        assertEquals(1, pump(d, s, w, true, null) > 0 ? 1 : 0);
        assertEquals("/api/v1/orders/claim", s.paths.get(0));
        assertTrue(w.flow.running());
        assertEquals("o-bbbbbbbbbbbb", w.flow.orderId());
    }

    @Test
    void aSavedOrderTheServerNoLongerHoldsIsForgotten() {
        OrderSim w = world();
        w.flow.offer(new Order("o-cccccccccccc", List.of(new Order.Line("item", D, 10))));
        w.flow.reset();
        Server s = new Server();
        OrderDriver d = driver(w);
        pump(d, s, w, true, null);
        assertEquals(204, s.code[0]);
        assertNull(w.saved);
    }

    @Test
    void anOrderThatCannotBeDoneIsReportedFailedWithTheReason() {
        OrderSim w = new OrderSim().stock(D, "v", 1000, 64);
        w.empties.clear();
        Server s = new Server();
        OrderDriver d = driver(w);
        d.onHeartbeat("{\"orders\":{\"pending\":1}}");
        s.pending.add(orderJson("o-dddddddddddd", DIAMONDS));
        pump(d, s, w, true, null);
        JsonObject status = s.statuses.get(0);
        assertEquals("failed", status.get("status").getAsString());
        assertTrue(status.get("message").getAsString().startsWith("need 1 empty shulkers"));
        assertTrue(s.paths.contains("/api/v1/orders/o-dddddddddddd/status"));
        assertFalse(w.flow.holdsOrder());
        assertTrue(events.stream().anyMatch(e -> e.startsWith("error: Order o-dddddddddddd couldn't be taken")));
    }

    @Test
    void anOrderThatCannotBeReadIsReportedFailedSoItIsNotHandedBackForever() {
        OrderSim w = world();
        Server s = new Server();
        OrderDriver d = driver(w);
        d.onHeartbeat("{\"orders\":{\"pending\":1}}");
        s.pending.add("{\"ok\":true,\"order\":{\"id\":\"o-eeeeeeeeeeee\",\"lines\":[{\"type\":\"x\"}]}}");
        pump(d, s, w, true, null);
        assertEquals("failed", s.statuses.get(0).get("status").getAsString());
        assertEquals("couldn't read order", s.statuses.get(0).get("message").getAsString());
    }

    @Test
    void aStatusTheServerCallsFinishedIs409AndCountsAsDone() {
        OrderSim w = world();
        Server s = new Server();
        OrderDriver d = driver(w);
        d.onHeartbeat("{\"orders\":{\"pending\":1}}");
        s.pending.add(orderJson("o-ffffffffffff", DIAMONDS));
        pump(d, s, w, true, null);
        w.run(10);
        // The website force-cancelled it meanwhile: every status gets 409
        s.statusCode = 409;
        int n = pump(d, s, w, true, null);
        assertTrue(n >= 1);
        assertFalse(w.flow.holdsOrder());
        assertNull(w.saved);
        // And nothing is sent for it again
        assertEquals(0, pump(d, s, w, true, "orders off"));
    }

    @Test
    void aProgressReportThatGets409StopsTheRunAtASafePoint() {
        OrderSim w = world();
        Server s = new Server();
        OrderDriver d = driver(w);
        d.onHeartbeat("{\"orders\":{\"pending\":1}}");
        s.pending.add(orderJson("o-llllllllllll", "[{\"type\":\"item\",\"key\":\"minecraft:diamond\",\"count\":" + 64 * 30 + "}]"));
        pump(d, s, w, true, null);
        w.step();
        w.step();
        w.now += 3_000;
        s.statusCode = 409;
        pump(d, s, w, true, null);
        assertTrue(w.flow.cancelRequested());
        assertTrue(events.stream().anyMatch(e -> e.contains("was finished on the website")));
        w.run(10);
        pump(d, s, w, true, null);
        assertFalse(w.flow.holdsOrder());
        assertEquals(1, w.delivery.size());
    }

    @Test
    void aTerminalReportRefusedAsBusyIsKeptAndRetriedNotAcked() {
        for (int code : new int[] {408, 429, 500, 503, -1, 401}) {
            OrderSim w = world();
            Server s = new Server();
            OrderDriver d = driver(w);
            d.onHeartbeat("{\"orders\":{\"pending\":1}}");
            s.pending.add(orderJson("o-mmmmmmmmmmmm", DIAMONDS));
            pump(d, s, w, true, null);
            w.run(10);
            OrderDriver.Request r = d.next(true, null, "2b2t.org", "stash", "Bot", true);
            assertNotNull(r, "code " + code);
            assertFalse(r.reply().done(code, ""), "code " + code);
            assertTrue(w.flow.holdsOrder(), "code " + code + ": the order was let go");
            assertNotNull(w.saved, "code " + code + ": the plan was dropped");
            // The same terminal report is made again, and now it goes through
            OrderDriver.Request again = d.next(true, null, "2b2t.org", "stash", "Bot", true);
            assertNotNull(again, "code " + code);
            assertTrue(again.reply().done(200, "{\"ok\":true}"));
            assertFalse(w.flow.holdsOrder());
            assertNull(w.saved);
        }
    }

    @Test
    void aPlainNoOnATerminalReportIsGivenUpOnAfterTheShortForm() {
        for (int code : new int[] {400, 404, 422}) {
            OrderSim w = world();
            Server s = new Server();
            OrderDriver d = driver(w);
            d.onHeartbeat("{\"orders\":{\"pending\":1}}");
            s.pending.add(orderJson("o-nnnnnnnnnnnn", DIAMONDS));
            pump(d, s, w, true, null);
            w.run(10);
            s.statusCode = code;
            pump(d, s, w, true, null);
            assertFalse(w.flow.holdsOrder(), "code " + code);
        }
    }

    @Test
    void anOrderHeldAfterALostClaimReplyIsClaimedAgainEvenWithNothingPending() {
        OrderSim w = world();
        Server s = new Server();
        OrderDriver d = driver(w);
        d.onHeartbeat("{\"orders\":{\"pending\":0}}");
        assertNull(d.next(true, null, "2b2t.org", "stash", "Bot", true));
        w.now += OrderDriver.IDLE_CLAIM_MS - 1000;
        assertNull(d.next(true, null, "2b2t.org", "stash", "Bot", true));
        w.now += 1000;
        // The server's held (accepted) order comes back in the answer
        s.pending.add(orderJson("o-oooooooooooo", DIAMONDS));
        pump(d, s, w, true, null);
        assertEquals("/api/v1/orders/claim", s.paths.get(0));
        assertTrue(w.flow.running());
    }

    @Test
    void turningOrdersOffTellsTheServerItIsParkedThenParks() {
        OrderSim w = world();
        Server s = new Server();
        OrderDriver d = driver(w);
        d.onHeartbeat("{\"orders\":{\"pending\":1}}");
        s.pending.add(orderJson("o-pppppppppppp", DIAMONDS));
        pump(d, s, w, true, null);
        s.statuses.clear();
        w.active = false;
        w.now += 3000;
        pump(d, s, w, false, "orders off");
        assertEquals("parked: orders off", s.statuses.get(0).get("phase").getAsString());
        assertFalse(w.flow.holdsOrder());
        assertNotNull(w.saved);
    }

    @Test
    void aStatusRefusedWith400IsSentOnceMoreInShortFormThenGivenUp() {
        OrderSim w = world();
        Server s = new Server();
        OrderDriver d = driver(w);
        d.onHeartbeat("{\"orders\":{\"pending\":1}}");
        s.pending.add(orderJson("o-gggggggggggg", DIAMONDS));
        pump(d, s, w, true, null);
        w.run(10);
        s.statusCode = 400;
        pump(d, s, w, true, null);

        // The full report was refused, the short one too: the order is let go instead of wedging the runner
        JsonObject full = s.statuses.get(s.statuses.size() - 2);
        JsonObject minimal = s.statuses.get(s.statuses.size() - 1);
        assertTrue(full.has("progress"));
        assertTrue(minimal.has("status") && !minimal.has("progress") && !minimal.has("result"));
        assertFalse(w.flow.holdsOrder());
        assertNull(w.saved);
    }

    @Test
    void aFailedTransportRetriesAndNothingIsLost() {
        OrderSim w = world();
        Server s = new Server();
        OrderDriver d = driver(w);
        d.onHeartbeat("{\"orders\":{\"pending\":1}}");
        s.pending.add(orderJson("o-hhhhhhhhhhhh", DIAMONDS));
        pump(d, s, w, true, null);
        w.run(10);
        OrderDriver.Request r = d.next(true, null, "2b2t.org", "stash", "Bot", true);
        assertNotNull(r);
        assertFalse(r.reply().done(-1, ""));
        assertTrue(w.flow.holdsOrder());
        // The same report is made again
        OrderDriver.Request again = d.next(true, null, "2b2t.org", "stash", "Bot", true);
        assertNotNull(again);
        assertEquals(r.path(), again.path());
    }

    @Test
    void phaseAndMessageAreCutToTheServersLimits() {
        OrderFlow.Report r = new OrderFlow.Report("o-x", "failed", "p".repeat(500), 1, 2, "m".repeat(900), 1, java.util.Map.of());
        OrderSim w = world();
        JsonObject o = driver(w).statusJson(r);
        assertEquals(OrderDriver.PHASE_MAX, o.get("phase").getAsString().length());
        assertEquals(OrderDriver.MESSAGE_MAX, o.get("message").getAsString().length());
        assertEquals(100, OrderDriver.minimalJson(r).get("message").getAsString().length());
        // Control characters and half a surrogate pair don't get through
        assertEquals("a b", OrderDriver.clamp("a\nb", 10));
        String emoji = "ab" + new String(Character.toChars(0x1F600));
        assertEquals("ab", OrderDriver.clamp(emoji, 3));
    }

    @Test
    void aLongResultIsCutTo400Lines() {
        OrderSim w = world();
        List<Order.Line> lines = new ArrayList<>();
        for (int i = 0; i < 450; i++) lines.add(new Order.Line("item", "minecraft:item_" + i, 1));
        w.flow.offer(new Order("o-many", lines));
        // Nothing of it exists: it fails at planning, so build the report by hand
        OrderFlow.Report r = new OrderFlow.Report("o-many", "delivered", "delivered", 1, 1, "", 1, java.util.Map.of());
        // Not planned, so the flow holds no requested keys: the result is empty and valid
        JsonObject o = driver(w).statusJson(r);
        assertTrue(o.getAsJsonObject("result").getAsJsonArray("lines").size() <= 400);
    }

    @Test
    void aCancelFromTheHeartbeatStopsTheOrderAtASafePoint() {
        OrderSim w = world();
        Server s = new Server();
        OrderDriver d = driver(w);
        d.onHeartbeat("{\"orders\":{\"pending\":1}}");
        s.pending.add(orderJson("o-iiiiiiiiiiii", "[{\"type\":\"item\",\"key\":\"minecraft:diamond\",\"count\":" + 64 * 30 + "}]"));
        pump(d, s, w, true, null);
        w.step();
        w.step();
        d.onHeartbeat("{\"orders\":{\"pending\":0,\"cancel\":[\"o-iiiiiiiiiiii\"]}}");
        w.run(5);
        pump(d, s, w, true, null);
        JsonObject last = s.statuses.get(s.statuses.size() - 1);
        assertEquals("cancelled", last.get("status").getAsString());
        assertFalse(last.has("result"));
        assertEquals(1, w.delivery.size());
        assertTrue(events.stream().anyMatch(e -> e.equals("warn: Order o-iiiiiiiiiiii cancelled")));
    }

    @Test
    void aCancelForASavedOrderTheBotIsNotHoldingIsAnswered() {
        OrderSim w = world();
        w.flow.offer(new Order("o-jjjjjjjjjjjj", List.of(new Order.Line("item", D, 10))));
        w.flow.reset();
        Server s = new Server();
        OrderDriver d = driver(w);
        d.onHeartbeat("{\"orders\":{\"pending\":0,\"cancel\":[\"o-jjjjjjjjjjjj\"]}}");
        pump(d, s, w, false, "orders off");
        JsonObject status = s.statuses.get(0);
        assertEquals("cancelled", status.get("status").getAsString());
        assertEquals("cancelled", status.get("message").getAsString());
        assertNull(w.saved);
    }

    @Test
    void turningOrdersOffParksTheOrderInHand() {
        OrderSim w = world();
        Server s = new Server();
        OrderDriver d = driver(w);
        d.onHeartbeat("{\"orders\":{\"pending\":1}}");
        s.pending.add(orderJson("o-kkkkkkkkkkkk", DIAMONDS));
        pump(d, s, w, true, null);
        assertTrue(w.flow.running());
        d.next(false, "orders off", "2b2t.org", "stash", "Bot", true);
        d.next(false, "orders off", "2b2t.org", "stash", "Bot", true);
        w.flow.next();
        assertFalse(w.flow.holdsOrder());
        assertNotNull(w.saved);
    }

    @Test
    void reconnectingResetsWhatTheServerSaid() {
        OrderSim w = world();
        OrderDriver d = driver(w);
        d.onHeartbeat("{\"orders\":{\"pending\":4}}");
        d.reset();
        assertEquals(0, d.pending());
    }

    @Test
    void junkInTheHeartbeatReplyIsHarmless() {
        OrderSim w = world();
        OrderDriver d = driver(w);
        d.onHeartbeat("not json");
        d.onHeartbeat("[]");
        d.onHeartbeat("{\"orders\":\"x\"}");
        d.onHeartbeat("{\"orders\":{\"pending\":\"many\",\"cancel\":5}}");
        assertEquals(0, d.pending());
    }
}
