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
    /** Per key: how many of the wanted are bases for enchant plans (ENCHANT-CONTRACT draft 3.4): made and used, never delivered. */
    private final Map<String, Long> forEnchant = new LinkedHashMap<>();
    /** Per key: how many are short and the first reason (a later one for the same key is added to the count only). */
    private final Map<String, Long> shortCount = new LinkedHashMap<>();
    private final Map<String, String> shortReason = new LinkedHashMap<>();
    private final int plannedFills;
    private boolean refused;
    /**
     * B3 (CRAFT-CONTRACT-v1.2.md 9): the block's special steps as the sub-plan's recipes (a rocket or star is a pseudo id, see {@link Pseudo}); per spec'd id
     * the index keys it is taken by and how many each (the take's entries, in the server's key order); how many of each key were taken so far.
     */
    private final Map<String, Recipes.Recipe> special = new LinkedHashMap<>();
    private final Map<String, LinkedHashMap<String, Long>> keyCap = new LinkedHashMap<>();
    private final Map<String, Long> keyUsed = new LinkedHashMap<>();
    private final Map<String, String> keySpec = new LinkedHashMap<>();
    /** How a v2 block was planned: the depth, the estimate rounds and the calls the sub-plans may use (CRAFT-CONTRACT-v1.2.md 7). */
    private final CraftSub.Limits limits;
    /** The preflight of a v2 order (CRAFT-CONTRACT-v1.2.md 11.2): null for any other order, {@code "ok"} or {@code "short"}, and the restock list of a short one. */
    private @Nullable String preflight;
    private JsonArray restock = new JsonArray();
    /** Crafted items per key that did not reach a delivered shulker (set when the order ends), and the surplus the saved ledger said was carried. */
    private final Map<String, Long> lost = new LinkedHashMap<>(), savedSurplus = new LinkedHashMap<>();
    private long fills;
    private final int maxFills;
    /** Smelting (B2): the bot's smelt and fuel tables for a block with furnace steps (null for any other), the most items it smelts for the order, and what it did. */
    private @Nullable Smelting smelting;
    private int maxSmelt;
    private boolean hasFurnace;
    /** Per out item: how many the block's furnace steps make, and how many were taken out of the furnace (the report's {@code smelted}). */
    private final Map<String, Long> smeltWanted = new LinkedHashMap<>(), smeltMade = new LinkedHashMap<>();
    /** How the furnace came to be used ({@code found}, {@code placed}, {@code crafted}), or null when none was used. */
    private @Nullable String furnaceSource;
    /** What the bot has put into the furnace and taken out of it during the chunk under way (CRAFT-CONTRACT-v1.2.md 14): saved with the ledger. */
    private final SmeltJournal journal = new SmeltJournal();

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
        this.limits = CraftSub.Limits.of(craft);
        if (restrict) {
            this.allowed = new java.util.LinkedHashSet<>();
            for (Order.CraftStep s : craft.steps()) allowed.add(FireworkSpecs.planId(s));
        }
        else this.allowed = null;
        special.putAll(FireworkSpecs.recipesOf(craft));
        for (Order.Qty t : craft.take()) {
            String ref = Pseudo.of(Recipes.ns(t.id()), t.spec());
            take.merge(ref, (long) t.count(), Long::sum);
            budget.merge(ref, (long) t.count(), Long::sum);
            if (t.spec() != null && t.key() != null) {
                keyCap.computeIfAbsent(ref, k -> new LinkedHashMap<>()).merge(t.key(), (long) t.count(), Long::sum);
                keySpec.put(t.key(), ref);
            }
        }
        for (Order.Line l : craftLines) wanted.merge(l.ref(), (long) l.count(), Long::sum);
        for (Order.Qty q : craft.forEnchant()) if (q.count() > 0) forEnchant.merge(q.id(), (long) q.count(), Long::sum);
        this.plannedFills = (int) Math.min(Integer.MAX_VALUE, craft.stepFills());
        this.hasFurnace = craft.hasFurnace();
        for (Order.CraftStep s : craft.steps()) if (s.furnace()) smeltWanted.merge(Recipes.ns(s.result()), (long) s.fills() * s.count(), Long::sum);
    }

    /**
     * The smelting this order's sub-plans use (B2): the bot's smelt and fuel tables, and the most items it smelts for the order (what its heartbeat says).
     * Set when the order starts, before any plan.
     */
    public void useSmelting(@Nullable Smelting smelting, int maxSmelt) {
        this.smelting = smelting;
        this.maxSmelt = Math.max(0, maxSmelt);
    }

    public @Nullable Smelting smelting() {
        return smelting;
    }

    /** The block has furnace steps: the report carries {@code smelted} and {@code furnace}. */
    public boolean hasFurnace() {
        return hasFurnace;
    }

    public SmeltJournal journal() {
        return journal;
    }

    /** How many more items the order may smelt (the cap of CRAFT-CONTRACT-v1.2.md 14, less what was taken out of the furnace so far). */
    public long smeltLeft() {
        return Math.max(0, Math.min(Smelting.MAX_SMELT, maxSmelt) - smeltedTotal());
    }

    public long smeltedTotal() {
        long n = 0;
        for (long c : smeltMade.values()) n += c;
        return n;
    }

    /** Items were taken out of the furnace's output: counted in the report's {@code smelted}. */
    public void addSmelted(Map<String, Long> out) {
        for (var e : out.entrySet()) if (e.getValue() != null && e.getValue() > 0) smeltMade.merge(Recipes.ns(e.getKey()), e.getValue(), Long::sum);
    }

    public long smeltMade(String id) {
        return smeltMade.getOrDefault(Recipes.ns(id), 0L);
    }

    /** The furnace the order used came to be this way: {@code found}, {@code placed} or {@code crafted} (the first one used is the one reported). */
    public void setFurnaceSource(@Nullable String source) {
        if (furnaceSource == null && source != null) furnaceSource = source;
    }

    public @Nullable String furnaceSource() {
        return furnaceSource;
    }

    /** What was put into the furnace and did not come back out is charged to the budget (a run that was cut short, see {@link SmeltJournal#settle}). */
    public void charge(Map<String, Long> fromStock) {
        madeChunk("", 0, 0, fromStock);
    }

    /** A ledger for an order whose craft block could not be used: every craft line is short with this reason. */
    public static CraftLedger refused(List<Order.Line> craftLines, Recipes table, int maxFills, String reason) {
        CraftLedger l = new CraftLedger(new Order.Craft(1, "", "", List.of(), List.of(), List.of(), List.of(), 0, false), craftLines, table, maxFills);
        for (var e : new ArrayList<>(l.wanted.entrySet())) l.addShort(e.getKey(), e.getValue(), reason);
        l.refused = true;
        return l;
    }

    /**
     * The ledger of a v2 order whose preflight found the stash short (CRAFT-CONTRACT-v1.2.md 11.2): nothing is withdrawn, every craft line is short with
     * {@code ingredients_short}, and the report carries {@code preflight: "short"} and the {@code restock} list.
     */
    public static CraftLedger preflightShort(List<Order.Line> craftLines, Recipes table, int maxFills, JsonArray restock) {
        CraftLedger l = refused(craftLines, table, maxFills, CraftOrder.INGREDIENTS_SHORT);
        l.refused = false;
        l.preflight = PREFLIGHT_SHORT;
        l.restock = restock;
        return l;
    }

    public static final String PREFLIGHT_OK = "ok", PREFLIGHT_SHORT = "short";

    /** The preflight of a v2 order found the stash covers it. */
    public void preflightOk() {
        preflight = PREFLIGHT_OK;
    }

    public @Nullable String preflight() {
        return preflight;
    }

    /** The limits this order's sub-plans use. */
    public CraftSub.Limits limits() {
        return limits;
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
        for (var e : now.entrySet()) if (e.getValue() != null && e.getValue() > 0) surplus.put(ns(e.getKey()), e.getValue());
    }

    /** An id or pseudo id made namespaced: {@code chest} as {@code minecraft:chest}, {@code firework_star@star:...} as {@code minecraft:firework_star@star:...}. */
    public static String ns(String id) {
        return Pseudo.of(Recipes.ns(Pseudo.base(id)), Pseudo.spec(id));
    }

    /** A new sub-plan session from the budget left and the surplus carried. */
    public CraftSub.Session session() {
        return new CraftSub.Session(table, allowed, take, budget, surplus, limits, smelting, special);
    }

    /** As {@link #session()} from this pool and surplus (a chunk planned again from what is carried): the order's recipes only, its special steps too. */
    public CraftSub.Session sessionWith(Map<String, Long> estPool, Map<String, Long> pool, Map<String, Long> surplus) {
        return new CraftSub.Session(table, allowed, estPool, pool, surplus, limits, smelting, special);
    }

    /** The block has a step or a take of a rocket or star (B3). */
    public boolean hasSpecs() {
        return !special.isEmpty() || !keyCap.isEmpty();
    }

    /** The item ids (namespaced) the order's take is of, spec'd entries under their item id: what the stash is surveyed for. */
    public java.util.Set<String> takeItemIds() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String ref : take.keySet()) out.add(Pseudo.base(ref));
        return out;
    }

    /** The index keys that take entries name for a spec'd id (B3), for the stash survey. */
    public java.util.Set<String> specKeys() {
        return java.util.Collections.unmodifiableSet(keySpec.keySet());
    }

    /** The pseudo id (item id and spec) the index key stands for in the order's take, or null when it is not one of its spec'd keys. */
    public @Nullable String refOfKey(String key) {
        return keySpec.get(key);
    }

    /**
     * The index keys a plan's stock of spec'd items is taken by (B3), with how many of each: for each pseudo id of {@code fromStock} the keys of the take's
     * entries in their order, each up to what is left of its entry. Plain ids are left out (they are gathered by item id). Never more than the take.
     */
    public Map<String, Long> keysFor(Map<String, Long> fromStock) {
        Map<String, Long> out = new LinkedHashMap<>(), used = new LinkedHashMap<>(keyUsed);
        walkKeys(fromStock, used, out);
        return out;
    }

    private void walkKeys(Map<String, Long> stock, Map<String, Long> used, @Nullable Map<String, Long> out) {
        for (var e : stock.entrySet()) {
            if (!Pseudo.is(e.getKey())) continue;
            long left = e.getValue() == null ? 0 : e.getValue();
            for (var k : keyCap.getOrDefault(e.getKey(), new LinkedHashMap<>()).entrySet()) {
                if (left <= 0) break;
                long n = Math.min(left, k.getValue() - used.getOrDefault(k.getKey(), 0L));
                if (n <= 0) continue;
                used.merge(k.getKey(), n, Long::sum);
                if (out != null) out.merge(k.getKey(), n, Long::sum);
                left -= n;
            }
        }
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
        walkKeys(fromStock, keyUsed, null);
        for (var e : fromStock.entrySet()) {
            String id = ns(e.getKey());
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
        for (var e : back.entrySet()) if (e.getValue() != null && e.getValue() > 0) returned.put(ns(e.getKey()), e.getValue());
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
            // A rocket or star line (B3) is delivered under the key of the stack that was made, which this ledger does not know: it is not counted here
            if (Pseudo.is(e.getKey())) continue;
            // The bases made for enchant plans are never delivered: only the ordered part of a line can be lost on the way to a shulker
            long ordered = Math.max(0, e.getValue() - forEnchant.getOrDefault(e.getKey(), 0L));
            long m = Math.min(ordered, made.getOrDefault(e.getKey(), 0L));
            long itemPart = Math.max(0, requested.getOrDefault(e.getKey(), 0) - ordered);
            long got = Math.min(m, Math.max(0, delivered.getOrDefault(e.getKey(), 0) - itemPart));
            if (m - got > 0) lost.put(e.getKey(), m - got);
        }
    }

    /** Budget given back: items counted as gathered that are not carried any more (a restart lost them). */
    public void refund(Map<String, Long> back) {
        // B3: a spec'd id's refund goes back to its keys, the last used first
        for (var e : back.entrySet()) {
            if (!Pseudo.is(e.getKey())) continue;
            long left = Math.max(0, e.getValue());
            List<Map.Entry<String, Long>> keys = new ArrayList<>(keyCap.getOrDefault(e.getKey(), new LinkedHashMap<>()).entrySet());
            for (int i = keys.size() - 1; i >= 0 && left > 0; i--) {
                long n = Math.min(left, keyUsed.getOrDefault(keys.get(i).getKey(), 0L));
                if (n <= 0) continue;
                keyUsed.merge(keys.get(i).getKey(), -n, Long::sum);
                left -= n;
            }
        }
        for (var e : back.entrySet()) {
            String id = ns(e.getKey());
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
        // A failed preflight says it all in the order's message
        if (PREFLIGHT_SHORT.equals(preflight)) return null;
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
        if (preflight != null) o.addProperty("preflight", preflight);
        JsonArray m = new JsonArray();
        // Nothing was made when the preflight came out short: the list is empty (the contract's shape)
        for (var e : PREFLIGHT_SHORT.equals(preflight) ? Map.<String, Long>of().entrySet() : wanted.entrySet()) {
            if (m.size() >= MAX_ENTRIES) break;
            JsonObject x = new JsonObject();
            ref(x, "key", e.getKey());
            x.addProperty("wanted", e.getValue());
            x.addProperty("made", Math.min(e.getValue(), made.getOrDefault(e.getKey(), 0L)));
            m.add(x);
        }
        o.add("made", m);
        JsonArray s = new JsonArray();
        for (var e : shortCount.entrySet()) {
            if (s.size() >= MAX_ENTRIES) break;
            JsonObject x = new JsonObject();
            ref(x, "key", e.getKey());
            x.addProperty("count", e.getValue());
            x.addProperty("reason", shortReason.getOrDefault(e.getKey(), CraftOrder.INTERRUPTED));
            s.add(x);
        }
        o.add("short", s);
        o.add("used", qtys(used));
        o.add("returned", qtys(returned));
        if (PREFLIGHT_SHORT.equals(preflight)) o.add("restock", restock);
        // B2 (CRAFT-CONTRACT-v1.2.md 12): what the furnace steps were to make and what came out, and how the furnace was had (no position); only for a block with furnace steps
        if (hasFurnace) {
            JsonArray sm = new JsonArray();
            for (var e : smeltWanted.entrySet()) {
                if (sm.size() >= MAX_ENTRIES) break;
                JsonObject x = new JsonObject();
                x.addProperty("id", cut(e.getKey()));
                x.addProperty("wanted", e.getValue());
                x.addProperty("made", smeltMade.getOrDefault(e.getKey(), 0L));
                sm.add(x);
            }
            o.add("smelted", sm);
            if (furnaceSource == null) o.add("furnace", com.google.gson.JsonNull.INSTANCE);
            else {
                JsonObject f = new JsonObject();
                f.addProperty("source", furnaceSource);
                o.add("furnace", f);
            }
        }
        return o;
    }

    /** The item id under {@code name}, and for a variant (B3) its {@code spec} beside it (CRAFT-CONTRACT-v1.2.md 12: {@code made[].spec}, {@code short[].spec}). */
    private static void ref(JsonObject x, String name, String ref) {
        x.addProperty(name, cut(Pseudo.base(ref)));
        if (Pseudo.is(ref)) x.addProperty("spec", Pseudo.spec(ref));
    }

    private static JsonArray qtys(Map<String, Long> m) {
        JsonArray a = new JsonArray();
        for (var e : m.entrySet()) {
            if (a.size() >= MAX_ENTRIES) break;
            if (e.getValue() <= 0) continue;
            JsonObject x = new JsonObject();
            ref(x, "id", e.getKey());
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
        if (PREFLIGHT_OK.equals(preflight)) o.addProperty("preflight", PREFLIGHT_OK);
        if (!keyUsed.isEmpty()) o.add("keyUsed", map(keyUsed));
        if (hasFurnace) {
            o.add("smeltMade", map(smeltMade));
            if (furnaceSource != null) o.addProperty("furnace", furnaceSource);
            if (journal.isOpen()) o.add("smelt", journal.toJson());
        }
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
            keyUsed.clear();
            if (o.has("keyUsed") && o.get("keyUsed").isJsonObject()) {
                for (var e : read(o.getAsJsonObject("keyUsed")).entrySet()) if (keySpec.containsKey(e.getKey())) keyUsed.put(e.getKey(), e.getValue());
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
            if (o.has("preflight") && o.get("preflight").isJsonPrimitive() && PREFLIGHT_OK.equals(o.get("preflight").getAsString())) preflight = PREFLIGHT_OK;
            // The smelting (B2): what came out of the furnace, how it was had, and the journal of a run that was cut short
            smeltMade.clear();
            if (o.has("smeltMade") && o.get("smeltMade").isJsonObject()) smeltMade.putAll(read(o.getAsJsonObject("smeltMade")));
            if (o.has("furnace") && o.get("furnace").isJsonPrimitive()) furnaceSource = o.get("furnace").getAsString();
            journal.close();
            if (o.has("smelt")) {
                SmeltJournal saved = SmeltJournal.fromJson(o.get("smelt"));
                if (saved.isOpen()) {
                    journal.open();
                    journal.placed().putAll(saved.placed());
                    journal.produced().putAll(saved.produced());
                    if (saved.active() != null) journal.loaded(saved.active());
                }
            }
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
