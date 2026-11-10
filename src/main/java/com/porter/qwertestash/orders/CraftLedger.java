package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a craft order has done so far (CRAFT-CONTRACT.md "Bot execution", points 3, 4, 7 and 8): the budget left of the order's
 * {@code take} (never more of an id is taken from the stash than it says), the crafted surplus the bot carries, the grid fills made,
 * what was made and what came up short and why, and what is given back. It makes the {@code result.craft} object of the status
 * report and the order's one note ("<n> item(s) not crafted: <reasons>"), and is saved with the order's plan as JSON so a restart
 * carries on. Pure; the bot tick thread.
 * <p>
 * Crafting never fails or blocks an order by itself: a line that could not be made is short with a reason, and the rest goes on.
 */
public final class CraftLedger {
    /** At most this many entries per list in the status report (CRAFT-CONTRACT.md). */
    public static final int MAX_ENTRIES = 200;
    public static final int MAX_KEY = 256;

    private final Recipes table;
    private final @Nullable Set<String> allowed;
    private final Map<String, Long> take = new LinkedHashMap<>(), budget = new LinkedHashMap<>(), surplus = new LinkedHashMap<>(), used = new LinkedHashMap<>(),
        returned = new LinkedHashMap<>();
    private final Map<String, Long> wanted = new LinkedHashMap<>(), made = new LinkedHashMap<>();
    /** Per key: how many are short and the first reason (a later one for the same key is added to the count only). */
    private final Map<String, Long> shortCount = new LinkedHashMap<>();
    private final Map<String, String> shortReason = new LinkedHashMap<>();
    private final int plannedFills;
    private boolean refused;
    /** Crafted items per key that did not reach a delivered shulker (set when the order ends), and the surplus the saved ledger said was carried. */
    private final Map<String, Long> lost = new LinkedHashMap<>(), savedSurplus = new LinkedHashMap<>();
    private long fills;
    private final int maxFills;

    /**
     * @param table     the bot's recipe table, pinned for the order
     * @param maxFills  the most grid fills the bot makes for this order
     */
    public CraftLedger(Order.Craft craft, List<Order.Line> craftLines, Recipes table, int maxFills) {
        this(craft, craftLines, table, maxFills, true);
    }

    private CraftLedger(Order.Craft craft, List<Order.Line> craftLines, Recipes table, int maxFills, boolean restrict) {
        this.table = table;
        this.maxFills = maxFills;
        if (restrict) {
            this.allowed = new java.util.LinkedHashSet<>();
            for (Order.CraftStep s : craft.steps()) allowed.add(s.recipe());
        }
        else this.allowed = null;
        for (Order.Qty t : craft.take()) {
            take.merge(Recipes.ns(t.id()), (long) t.count(), Long::sum);
            budget.merge(Recipes.ns(t.id()), (long) t.count(), Long::sum);
        }
        for (Order.Line l : craftLines) wanted.merge(l.key(), (long) l.count(), Long::sum);
        this.plannedFills = (int) Math.min(Integer.MAX_VALUE, craft.stepFills());
    }

    /** A ledger for an order whose craft block could not be used: every craft line is short with this reason. */
    public static CraftLedger refused(List<Order.Line> craftLines, Recipes table, int maxFills, String reason) {
        CraftLedger l = new CraftLedger(new Order.Craft(1, "", "", List.of(), List.of(), List.of(), List.of(), 0, false), craftLines, table, maxFills);
        for (var e : new ArrayList<>(l.wanted.entrySet())) l.addShort(e.getKey(), e.getValue(), reason);
        l.refused = true;
        return l;
    }

    /** The ledger was made for an order that can't be carried out here (no table, the check failed). */
    public boolean refusedAtStart() {
        return refused;
    }

    public Recipes table() {
        return table;
    }

    /** The recipe ids the sub-plan may use (the order's craft.steps), or null for every recipe in the table (tests only). */
    public @Nullable Set<String> allowed() {
        return allowed;
    }

    /** A ledger whose sub-plans may use any recipe of the table. For tests of the planner. */
    static CraftLedger unrestricted(Order.Craft craft, List<Order.Line> craftLines, Recipes table, int maxFills) {
        return new CraftLedger(craft, craftLines, table, maxFills, false);
    }

    public int maxFills() {
        return maxFills;
    }

    public long fills() {
        return fills;
    }

    /** Grid fills the server planned for the whole order (for the phase text). */
    public int plannedFills() {
        return plannedFills;
    }

    /** The order's take as it was at the start (the estimates are made from it). */
    public Map<String, Long> take() {
        return take;
    }

    /** What may still be taken from the stash, by id. */
    public Map<String, Long> budget() {
        return budget;
    }

    /** The crafted surplus carried now, by id (set by the host from what is really in the inventory). */
    public Map<String, Long> surplus() {
        return surplus;
    }

    public void setSurplus(Map<String, Long> now) {
        surplus.clear();
        for (var e : now.entrySet()) if (e.getValue() != null && e.getValue() > 0) surplus.put(Recipes.ns(e.getKey()), e.getValue());
    }

    /** A new sub-plan session from the budget left and the surplus carried. */
    public CraftSub.Session session() {
        return new CraftSub.Session(table, allowed, take, budget, surplus);
    }

    /** Where the key's craft line comes in the order (0 first): the server plans the lines in this order, so the bot crafts them in it. */
    public int rank(String key) {
        int i = 0;
        for (String k : wanted.keySet()) {
            if (k.equals(key)) return i;
            i++;
        }
        return Integer.MAX_VALUE;
    }

    /** How many of a key were asked for in all, and made. */
    public long wanted(String key) {
        return wanted.getOrDefault(key, 0L);
    }

    public long made(String key) {
        return made.getOrDefault(key, 0L);
    }

    /** A chunk was crafted: {@code count} of {@code key} are in hand, it took {@code fillsMade} grid fills and these items from the stash. */
    public void madeChunk(String key, long count, long fillsMade, Map<String, Long> fromStock) {
        if (count > 0) made.merge(key, count, Long::sum);
        fills += Math.max(0, fillsMade);
        for (var e : fromStock.entrySet()) {
            String id = Recipes.ns(e.getKey());
            long n = Math.max(0, e.getValue());
            used.merge(id, n, Long::sum);
            budget.merge(id, -n, Long::sum);
            if (budget.get(id) <= 0) budget.remove(id);
        }
    }

    /** {@code count} of {@code key} could not be made. */
    public void addShort(String key, long count, String reason) {
        // Never more short than was asked for and not made
        if (wanted.containsKey(key)) count = Math.min(count, wanted.get(key) - made.getOrDefault(key, 0L) - shortCount.getOrDefault(key, 0L));
        if (count <= 0) return;
        shortCount.merge(key, count, Long::sum);
        shortReason.putIfAbsent(key, reason);
    }

    /** What the bot gives back at the end, by id (set when the order ends). */
    public void setReturned(Map<String, Long> back) {
        returned.clear();
        for (var e : back.entrySet()) if (e.getValue() != null && e.getValue() > 0) returned.put(Recipes.ns(e.getKey()), e.getValue());
    }

    /** Anything asked for and neither made nor already short is short with this reason (the order ended before it was crafted). */
    public void closeOpen(String reason) {
        for (var e : wanted.entrySet()) {
            long open = e.getValue() - made.getOrDefault(e.getKey(), 0L) - shortCount.getOrDefault(e.getKey(), 0L);
            if (open > 0) addShort(e.getKey(), open, reason);
        }
    }

    /**
     * The order ended: crafted items that did not reach a delivered shulker are counted (a bin that was skipped or came out short). The
     * craft part of a key's delivery is what was delivered beyond its item lines.
     */
    public void setDelivered(Map<String, Integer> delivered, Map<String, Integer> requested) {
        lost.clear();
        for (var e : wanted.entrySet()) {
            long m = Math.min(e.getValue(), made.getOrDefault(e.getKey(), 0L));
            long itemPart = Math.max(0, requested.getOrDefault(e.getKey(), 0) - e.getValue());
            long got = Math.min(m, Math.max(0, delivered.getOrDefault(e.getKey(), 0) - itemPart));
            if (m - got > 0) lost.put(e.getKey(), m - got);
        }
    }

    /** Budget given back: items counted as gathered that are not carried any more (a restart lost them). */
    public void refund(Map<String, Long> back) {
        for (var e : back.entrySet()) {
            String id = Recipes.ns(e.getKey());
            long n = Math.max(0, e.getValue());
            long cap = take.getOrDefault(id, 0L);
            budget.merge(id, n, Long::sum);
            if (budget.get(id) > cap) budget.put(id, cap);
            used.merge(id, -n, Long::sum);
            if (used.get(id) <= 0) used.remove(id);
        }
    }

    /**
     * A chunk begins: it may consume surplus, so the surplus is no longer listed (and saved) until the chunk is over and what is carried is
     * known again. A restart in between then refunds nothing for items the chunk used.
     */
    public void chunkStarted() {
        surplus.clear();
    }

    /** The surplus the saved ledger said was carried, by id (read back by {@link #restore}; empty for a fresh ledger). */
    public Map<String, Long> savedSurplus() {
        return savedSurplus;
    }

    /** Items not crafted, in all. */
    public long shortTotal() {
        long n = 0;
        for (long c : shortCount.values()) n += c;
        return n;
    }

    /** Whether the order has crafted or tried anything worth a report (a craft block is there). */
    public boolean any() {
        return !wanted.isEmpty();
    }

    /** "<n> item(s) not crafted: <reasons>", or null when everything asked for was made. */
    public @Nullable String note() {
        long n = shortTotal(), gone = 0;
        for (long l : lost.values()) gone += l;
        if (n <= 0 && gone <= 0) return null;
        List<String> parts = new ArrayList<>();
        if (n > 0) {
            List<String> reasons = new ArrayList<>();
            for (String r : shortReason.values()) if (!reasons.contains(r)) reasons.add(r);
            parts.add(n + " item(s) not crafted: " + String.join(", ", reasons));
        }
        if (gone > 0) parts.add(gone + " crafted item(s) not delivered");
        return String.join("; ", parts);
    }

    /** The {@code result.craft} object of the status report. */
    public JsonObject toResult() {
        JsonObject o = new JsonObject();
        o.addProperty("fills", fills);
        JsonArray m = new JsonArray();
        for (var e : wanted.entrySet()) {
            if (m.size() >= MAX_ENTRIES) break;
            JsonObject x = new JsonObject();
            x.addProperty("key", cut(e.getKey()));
            x.addProperty("wanted", e.getValue());
            x.addProperty("made", Math.min(e.getValue(), made.getOrDefault(e.getKey(), 0L)));
            m.add(x);
        }
        o.add("made", m);
        JsonArray s = new JsonArray();
        for (var e : shortCount.entrySet()) {
            if (s.size() >= MAX_ENTRIES) break;
            JsonObject x = new JsonObject();
            x.addProperty("key", cut(e.getKey()));
            x.addProperty("count", e.getValue());
            x.addProperty("reason", shortReason.getOrDefault(e.getKey(), CraftOrder.INTERRUPTED));
            s.add(x);
        }
        o.add("short", s);
        o.add("used", qtys(used));
        o.add("returned", qtys(returned));
        return o;
    }

    private static JsonArray qtys(Map<String, Long> m) {
        JsonArray a = new JsonArray();
        for (var e : m.entrySet()) {
            if (a.size() >= MAX_ENTRIES) break;
            if (e.getValue() <= 0) continue;
            JsonObject x = new JsonObject();
            x.addProperty("id", cut(e.getKey()));
            x.addProperty("count", e.getValue());
            a.add(x);
        }
        return a;
    }

    private static String cut(String s) {
        return s.length() <= MAX_KEY ? s : s.substring(0, MAX_KEY);
    }

    /** The ledger as saved with the order's plan (a restart reads it back with {@link #restore}). */
    public JsonObject toSaved() {
        JsonObject o = new JsonObject();
        o.addProperty("fills", fills);
        o.add("budget", map(budget));
        o.add("made", map(made));
        o.add("used", map(used));
        o.add("short", map(shortCount));
        o.add("surplus", map(surplus));
        JsonObject r = new JsonObject();
        for (var e : shortReason.entrySet()) r.addProperty(e.getKey(), e.getValue());
        o.add("reasons", r);
        return o;
    }

    private static JsonObject map(Map<String, Long> m) {
        JsonObject o = new JsonObject();
        for (var e : m.entrySet()) o.addProperty(e.getKey(), e.getValue());
        return o;
    }

    /** Reads a saved ledger back into this one (a broken record leaves it as it is). */
    public void restore(JsonObject o) {
        try {
            long f = o.get("fills").getAsLong();
            Map<String, Long> b = read(o.getAsJsonObject("budget")), m = read(o.getAsJsonObject("made")), u = read(o.getAsJsonObject("used")), s = read(o.getAsJsonObject("short"));
            Map<String, String> reasons = new LinkedHashMap<>();
            for (var e : o.getAsJsonObject("reasons").entrySet()) reasons.put(e.getKey(), e.getValue().getAsString());
            fills = f;
            // Never more budget than the order's take, whatever the record says
            for (var e : new ArrayList<>(b.entrySet())) {
                long cap = take.getOrDefault(e.getKey(), 0L);
                if (cap <= 0) b.remove(e.getKey());
                else if (e.getValue() > cap) b.put(e.getKey(), cap);
            }
            savedSurplus.clear();
            if (o.has("surplus") && o.get("surplus").isJsonObject()) savedSurplus.putAll(read(o.getAsJsonObject("surplus")));
            budget.clear();
            budget.putAll(b);
            made.clear();
            made.putAll(m);
            used.clear();
            used.putAll(u);
            shortCount.clear();
            shortCount.putAll(s);
            shortReason.clear();
            shortReason.putAll(reasons);
        } catch (RuntimeException e) {
            // A broken record is a fresh ledger: the order goes on with its full budget
        }
    }

    private static Map<String, Long> read(JsonObject o) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            long n = e.getValue().getAsLong();
            if (n < 0) throw new IllegalArgumentException("negative");
            out.put(e.getKey(), n);
        }
        return out;
    }
}
