package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/**
 * The bot's side of the order protocol (PROTOCOL.md, "Orders"): which request to send next (a claim, or the status of the
 * order in hand), what a reply means, and what a heartbeat reply says about orders (how many wait, which of ours were
 * cancelled). Pull style and free of I/O: the caller sends the request however it likes (the plugin on its worker thread,
 * a test synchronously) and hands the answer back, so one request is in flight at a time and the caller owns the retry
 * back-off. Everything is for one thread.
 * <p>
 * A bad status can never wedge the runner: strings are cut to the server's limits, a status the server refuses with a 4xx
 * is sent once more in short form and then given up on, and 409 (already finished) counts as done.
 */
public final class OrderDriver {
    public static final long CLAIM_MS = 5_000, STATUS_MS = 2_000;
    /** With nothing pending, a claim is still made this often: the server hands back an order the bot holds whose claim reply was lost. */
    public static final long IDLE_CLAIM_MS = 60_000;
    public static final int PHASE_MAX = 100, MESSAGE_MAX = 300, MINIMAL_MESSAGE_MAX = 100;

    /** What to do with the answer. Returns false when the request failed and should be tried again later. */
    public interface Reply {
        boolean done(int code, String body);
    }

    public record Request(String path, byte[] body, Reply reply) {
    }

    /** Where the plugin shows what happens (the activity feed). Never with coordinates. */
    public interface Listener {
        void event(String level, String text);
    }

    private final OrderFlow flow;
    private final LongSupplier clock;
    private final Listener listener;

    private int pendingOrders;
    private long lastClaim = Long.MIN_VALUE / 2, lastStatusAt = Long.MIN_VALUE / 2, idleSince;
    private boolean parkNoted;
    private String lastStatusSig = "";
    private @Nullable String rejectedId, rejectedWhy;
    private String rejectedStatus = "failed";
    /** The order whose full status report was refused, so its short form is sent. */
    private @Nullable String minimalFor;
    private @Nullable String announced;

    public OrderDriver(OrderFlow flow, LongSupplier clock, Listener listener) {
        this.flow = flow;
        this.clock = clock;
        this.listener = listener;
        this.idleSince = clock.getAsLong();
    }

    /**
     * A refusal the same request could get through after: the server busy or slow, a wrong key about to be fixed, the network.
     * Only a plain "no" (400, 404, 422...) is given up on; a terminal report is never dropped for anything else.
     */
    static boolean retryable(int code) {
        return code < 0 || code == 401 || code == 408 || code == 425 || code == 429 || code >= 500;
    }

    public int pending() {
        return pendingOrders;
    }

    /** A new session (the bot reconnected): the order is parked, what the server said before is stale. */
    public void reset() {
        pendingOrders = 0;
        lastStatusSig = "";
        lastClaim = Long.MIN_VALUE / 2;
        idleSince = clock.getAsLong();
        lastStatusAt = Long.MIN_VALUE / 2;
        minimalFor = null;
        parkNoted = false;
    }

    /** Sends the status again at the next chance (a finished order whose report never got through). */
    public void resend() {
        lastStatusSig = "";
    }

    /**
     * The next request, or null when there is nothing to send now.
     * @param acceptOrders the setting
     * @param blocker      why orders cannot be taken ({@link OrderBlocker}), null when they can
     * @param player       the bot's account name, sent with every order request (the server only takes them from the stash's owner)
     */
    public @Nullable Request next(boolean acceptOrders, @Nullable String blocker, String server, String stash, @Nullable String player, boolean stashLoaded) {
        long now = clock.getAsLong();

        // An order the bot could not take
        if (rejectedId != null) {
            String id = rejectedId, why = rejectedWhy == null ? "" : rejectedWhy, end = rejectedStatus;
            JsonObject o = new JsonObject();
            o.addProperty("status", end);
            o.addProperty("message", clamp(why, MESSAGE_MAX));
            return new Request("/api/v1/orders/" + id + "/status", bytes(o, player), (code, body) -> {
                // 409: the server already has it finished; another refusal is not tried again
                if (code != 200 && code != 409 && !(code >= 400 && code < 500 && !retryable(code))) return false;
                if (id.equals(rejectedId)) rejectedId = rejectedWhy = null;
                if (end.equals("cancelled")) flow.dropSaved(null);
                else listener.event("error", "Order " + shortId(id) + " couldn't be taken: " + clamp(why, 120));
                return true;
            });
        }

        // Taking orders was switched off with one in hand: it is parked, and resumed when it is switched on again
        if (!acceptOrders && flow.running()) {
            // The server is told it is parked (one report), then it is
            if (!parkNoted) {
                parkNoted = true;
                flow.setPhase("parked: orders off");
                lastStatusSig = "";
            }
            else {
                parkNoted = false;
                flow.park();
            }
        }
        else parkNoted = false;

        OrderFlow.Report report = flow.report();
        if (report != null) {
            String sig = report.id() + "|" + report.status() + "|" + report.phase() + "|" + report.done() + "|" + report.message();
            if (!sig.equals(lastStatusSig) && (report.terminal() || now - lastStatusAt >= STATUS_MS)) {
                lastStatusAt = now;
                boolean minimal = report.id().equals(minimalFor);
                return new Request("/api/v1/orders/" + report.id() + "/status", bytes(minimal ? minimalJson(report) : statusJson(report), player), (code, body) -> {
                    // A report the server refuses (4xx other than a wrong key or "already finished"): said again in short once,
                    // then given up on, so the bot is never stuck on one it can't send
                    if (code >= 400 && code < 500 && !retryable(code) && code != 409) {
                        if (!minimal) {
                            minimalFor = report.id();
                            return false;
                        }
                        minimalFor = null;
                        if (report.terminal()) {
                            announce(report);
                            flow.ack(report.id());
                            lastStatusSig = "";
                        }
                        else lastStatusSig = sig;
                        return true;
                    }
                    // 409: the order is already finished or moved on, nothing more to say about it. One still being worked on is stopped
                    // at the next safe point (the website gave up on it, say) instead of being carried out for nobody
                    if (code != 200 && code != 409) return false;
                    if (code == 409 && !report.terminal() && flow.cancel(report.id())) {
                        listener.event("warn", "Order " + shortId(report.id()) + " was finished on the website, stopping");
                    }
                    minimalFor = null;
                    lastStatusSig = sig;
                    if (report.terminal()) {
                        announce(report);
                        flow.ack(report.id());
                        lastStatusSig = "";
                    }
                    return true;
                });
            }
            return null;
        }

        // Nothing in hand: take the oldest pending order, or the one still held from before a restart
        if (acceptOrders && blocker == null && stashLoaded && now - lastClaim >= CLAIM_MS && (pendingOrders > 0 || flow.resumeId() != null || now - Math.max(lastClaim, idleSince) >= IDLE_CLAIM_MS)) {
            lastClaim = now;
            JsonObject o = new JsonObject();
            o.addProperty("server", server);
            o.addProperty("stash", stash);
            return new Request("/api/v1/orders/claim", bytes(o, player), (code, body) -> {
                if (code == 204) {
                    pendingOrders = 0;
                    flow.dropSaved(null);
                    return true;
                }
                if (code != 200) return false;
                takeOrder(body);
                return true;
            });
        }
        return null;
    }

    private void announce(OrderFlow.Report r) {
        if (r.id().equals(announced + r.status())) return;
        announced = r.id() + r.status();
        String id = shortId(r.id());
        switch (r.status()) {
            case "delivered" -> listener.event("info", "Order " + id + " delivered: " + r.shulkers() + " shulkers" + (r.message().isEmpty() ? "" : " (" + clamp(r.message(), 160) + ")"));
            case "cancelled" -> listener.event("warn", "Order " + id + " cancelled");
            default -> listener.event("error", "Order " + id + " failed: " + clamp(r.message(), 160));
        }
    }

    private void takeOrder(String body) {
        Order order = null;
        String id = null;
        try {
            JsonElement e = JsonParser.parseString(body);
            if (e.isJsonObject() && e.getAsJsonObject().has("order") && e.getAsJsonObject().get("order").isJsonObject()) {
                JsonObject o = e.getAsJsonObject().getAsJsonObject("order");
                if (o.has("id") && o.get("id").isJsonPrimitive()) id = o.get("id").getAsString();
                order = Order.parse(o);
            }
        } catch (RuntimeException e) {
            // Treated as no usable order below
        }
        if (order == null) {
            // Left alone it would be handed to us again forever: say it failed, if there is an id to say it with
            if (id != null && !id.isEmpty()) {
                rejectedId = id;
                rejectedWhy = "couldn't read order";
                rejectedStatus = "failed";
            }
            return;
        }

        flow.dropSaved(order.id);
        String why = flow.offer(order);
        if (why == null) {
            pendingOrders = Math.max(0, pendingOrders - 1);
            listener.event("info", "Order " + shortId(order.id) + " claimed: " + order.lines.size() + (order.lines.size() == 1 ? " line" : " lines"));
        }
        else {
            rejectedId = order.id;
            rejectedWhy = why;
            rejectedStatus = "failed";
        }
    }

    /** The orders part of a heartbeat reply: how many wait, and which of ours were cancelled. */
    public void onHeartbeat(String body) {
        try {
            JsonElement e = JsonParser.parseString(body);
            if (!e.isJsonObject() || !e.getAsJsonObject().has("orders") || !e.getAsJsonObject().get("orders").isJsonObject()) {
                pendingOrders = 0;
                return;
            }
            JsonObject orders = e.getAsJsonObject().getAsJsonObject("orders");
            pendingOrders = orders.has("pending") && orders.get("pending").isJsonPrimitive() ? orders.get("pending").getAsInt() : 0;
            if (orders.has("cancel") && orders.get("cancel").isJsonArray()) {
                JsonArray cancel = orders.getAsJsonArray("cancel");
                for (JsonElement id : cancel) {
                    if (!id.isJsonPrimitive()) continue;
                    String cancelled = id.getAsString();
                    // An order finished here whose report never got through: it is sent again now
                    if (!flow.running() && flow.holdsOrder()) lastStatusSig = "";
                    // Not held here, but saved from before a restart: say it is cancelled and forget it
                    if (!flow.cancel(cancelled) && cancelled.equals(flow.resumeId()) && rejectedId == null) {
                        rejectedId = cancelled;
                        rejectedWhy = "cancelled";
                        rejectedStatus = "cancelled";
                    }
                }
            }
        } catch (RuntimeException ex) {
            pendingOrders = 0;
        }
    }

    // Bodies

    JsonObject statusJson(OrderFlow.Report r) {
        JsonObject o = new JsonObject();
        o.addProperty("status", r.status());
        o.addProperty("phase", clamp(r.phase(), PHASE_MAX));
        JsonObject progress = new JsonObject();
        progress.addProperty("done", Math.max(0, r.done()));
        progress.addProperty("total", Math.max(0, r.total()));
        o.add("progress", progress);
        o.addProperty("message", clamp(r.message(), MESSAGE_MAX));
        if (r.terminal() && !r.status().equals("cancelled")) {
            JsonObject result = new JsonObject();
            result.addProperty("shulkers", Math.max(0, r.shulkers()));
            JsonArray lines = new JsonArray();
            for (String key : flow.requested().keySet()) {
                // The server takes at most 400 lines with keys up to 256 characters
                if (lines.size() >= 400 || key.length() > 256) continue;
                JsonObject line = new JsonObject();
                line.addProperty("key", key);
                line.addProperty("delivered", Math.max(0, r.delivered().getOrDefault(key, 0)));
                lines.add(line);
            }
            result.add("lines", lines);
            o.add("result", result);
        }
        return o;
    }

    private byte[] bytes(JsonObject o, @Nullable String player) {
        if (!o.has("player") && player != null && !player.isEmpty()) o.addProperty("player", player);
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Only the status and a short message, for a report the server would not take in full. */
    static JsonObject minimalJson(OrderFlow.Report r) {
        JsonObject o = new JsonObject();
        o.addProperty("status", r.status());
        o.addProperty("message", clamp(r.message(), MINIMAL_MESSAGE_MAX));
        return o;
    }

    public static String shortId(String id) {
        return id.length() > 24 ? id.substring(0, 24) : id;
    }

    /** At most {@code max} characters, never ending in half of a surrogate pair; control characters become spaces. */
    public static String clamp(@Nullable String s, int max) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && out.length() < max; i++) {
            char c = s.charAt(i);
            out.append(c < 32 ? ' ' : c);
        }
        if (out.length() > 0 && Character.isHighSurrogate(out.charAt(out.length() - 1))) out.setLength(out.length() - 1);
        return out.toString();
    }
}
