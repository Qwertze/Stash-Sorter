package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What an enchant order has done so far (ENCHANT-CONTRACT v1.0, "Result and failure rules", "Bot rules" 1): the budget left of the order's
 * {@code take} (never more of a key is taken from the stash than the order reserved, as {@link CraftLedger} does for craft orders), every item's
 * progress and the journal of its steps, what was made and what came up short and why, and what was given back. It makes the {@code result.enchant}
 * object of the status report and is saved with the order's plan as JSON, so a restart carries on. Pure; the bot tick thread.
 *
 * <p>An item is {@code PENDING} until its preflight passes, {@code RUNNING} from then on (the real plan and the steps done are the journal), and
 * {@code DONE} or {@code SHORT} when it ends. A half-done item is never {@code DONE}.
 */
public final class EnchantLedger {
    public static final int MAX_ENTRIES = 200;
    public static final int MAX_KEY = 256;

    public enum Status { PENDING, RUNNING, DONE, SHORT }

    /** One item of the order. */
    public static final class Item {
        public final EnchantOrder.ItemRef ref;
        public Status status = Status.PENDING;
        public @Nullable String reason;
        /** The plan made with the real items, once the preflight passed (the journal), and the steps done of it. */
        public EnchantOrder.@Nullable ItemPlan real;
        public int stepsDone;
        /** Bottles fetched on top of the held ones (top-ups) for this item. */
        public int topped;
        /** What the finished item carries (plain id to level), when done. */
        public Map<String, Integer> levels = new TreeMap<>();

        Item(EnchantOrder.ItemRef ref) {
            this.ref = ref;
        }
    }

    private final EnchantOrder.Block block;
    private final List<Item> items = new ArrayList<>();
    private final Map<String, Long> take = new LinkedHashMap<>(), budget = new LinkedHashMap<>(), returned = new LinkedHashMap<>();
    private final Map<Integer, Integer> wantedPerLine = new LinkedHashMap<>();
    private int bottlesUsed, anvilsBroken, topUps;
    private Map<String, Integer> lastLevels = new TreeMap<>();
    private boolean refusedAtStart;

    public EnchantLedger(EnchantOrder.Block block) {
        this.block = block;
        for (EnchantOrder.ItemRef r : block.items()) items.add(new Item(r));
        for (EnchantOrder.Take t : block.take()) {
            take.merge(t.key(), (long) t.count(), Long::sum);
            budget.merge(t.key(), (long) t.count(), Long::sum);
        }
        for (int l = 0; l < block.lines().size(); l++) wantedPerLine.put(l, block.lines().get(l).count());
    }

    /** A ledger for an order that cannot be carried out here (module conflict, version mismatch, unreadable block): every item is short with this reason. */
    public static EnchantLedger refused(EnchantOrder.Block block, String reason) {
        EnchantLedger l = new EnchantLedger(block);
        for (Item i : l.items) {
            i.status = Status.SHORT;
            i.reason = Codes.safe(reason);
        }
        l.refusedAtStart = true;
        return l;
    }

    public boolean refusedAtStart() {
        return refusedAtStart;
    }

    public EnchantOrder.Block block() {
        return block;
    }

    public List<Item> items() {
        return items;
    }

    public Item item(int n) {
        return items.get(n);
    }

    /** The index of the first item not done or short, or -1. */
    public int nextOpen() {
        for (int i = 0; i < items.size(); i++) if (items.get(i).status == Status.PENDING || items.get(i).status == Status.RUNNING) return i;
        return -1;
    }

    public boolean anyOpen() {
        return nextOpen() >= 0;
    }

    /** True while an item is past its first step: pauses have no time limit then, a queued cancel waits, and a force-cancel stops at the next boundary. */
    public boolean pastStepOne() {
        for (Item i : items) if (i.status == Status.RUNNING && i.stepsDone >= 1) return true;
        return false;
    }

    /** An item is running (its preflight passed, it has not ended): the cancel of the orderer waits for it only once it is past step 1. */
    public boolean running() {
        for (Item i : items) if (i.status == Status.RUNNING) return true;
        return false;
    }

    // Budget

    /** How many of this key the order may still take from the stash. */
    public long remaining(String key) {
        return Math.max(0, budget.getOrDefault(key, 0L));
    }

    public long taken(String key) {
        return take.getOrDefault(key, 0L) - remaining(key);
    }

    /** {@code n} of this key were taken from the stash: charged to the budget. @return false when that is more than the order may take */
    public boolean charge(String key, long n) {
        if (n <= 0) return true;
        long left = remaining(key);
        if (n > left) return false;
        budget.put(key, left - n);
        return true;
    }

    /** {@code n} of this key went back to the stash: given back to the budget (never above what the order reserved). */
    public void refund(String key, long n) {
        if (n <= 0 || !take.containsKey(key)) return;
        budget.put(key, Math.min(take.get(key), remaining(key) + n));
    }

    /** All keys of the order's take that are anvils (any damage state). */
    public List<String> anvilKeys() {
        List<String> out = new ArrayList<>();
        for (String k : take.keySet()) if (k.startsWith("minecraft:") && (k.endsWith("anvil") || k.contains("anvil#"))) out.add(k);
        return out;
    }

    /**
     * Bottles the bot may fetch to top up beyond what it holds (D3): what the stash has, less the bottles other orders (and this order's later items) still
     * reserve. {@code reservedTotal} is the heartbeat reply's {@code bottlesReserved}; what this order already took from the stash is not in the stash any more,
     * so it is taken off the reservation. The repair reserve is not counted: it may be dipped into.
     */
    public static long topUpAllowance(long stashBottles, long reservedTotal, long takenByThisOrder) {
        long others = Math.max(0, reservedTotal - Math.max(0, takenByThisOrder));
        return Math.max(0, stashBottles - others);
    }

    // Progress

    public void begin(int n, EnchantOrder.ItemPlan real) {
        Item i = items.get(n);
        i.status = Status.RUNNING;
        i.real = real;
        i.stepsDone = 0;
    }

    public void stepDone(int n, int step) {
        Item i = items.get(n);
        if (i.status == Status.RUNNING) i.stepsDone = Math.max(i.stepsDone, step + 1);
    }

    public void done(int n, Map<String, Integer> levels) {
        Item i = items.get(n);
        i.status = Status.DONE;
        i.reason = null;
        i.levels = new TreeMap<>(levels);
        lastLevels = new TreeMap<>(levels);
    }

    public void fail(int n, String reason) {
        Item i = items.get(n);
        if (i.status == Status.DONE) return;
        i.status = Status.SHORT;
        i.reason = Codes.safe(reason);
        i.real = null;
        i.stepsDone = 0;
    }

    /** Every item not done is short with this reason (the order ends, is cancelled, or a refusal applies to what is left). */
    public void failRest(String reason) {
        for (int n = 0; n < items.size(); n++) {
            Item i = items.get(n);
            if (i.status == Status.PENDING || i.status == Status.RUNNING) fail(n, reason);
        }
    }

    public void addBottles(int n) {
        bottlesUsed += Math.max(0, n);
    }

    public void anvilBroke(int n) {
        anvilsBroken += Math.max(0, n);
    }

    public void toppedUp() {
        topUps++;
    }

    public int topUps() {
        return topUps;
    }

    public int bottlesUsed() {
        return bottlesUsed;
    }

    public int anvilsBroken() {
        return anvilsBroken;
    }

    /** What went back to the stash, by index key: replaces what was noted. */
    public void setReturned(Map<String, Long> back) {
        returned.clear();
        addReturned(back);
    }

    /** More went back to the stash (an item ended short and its stacks were put back, or the order ended): added to what was noted. */
    public void addReturned(Map<String, Long> back) {
        for (var e : back.entrySet()) if (e.getValue() > 0) returned.merge(e.getKey(), e.getValue(), Long::sum);
    }

    public int madeOnLine(int line) {
        int n = 0;
        for (Item i : items) if (i.ref.line() == line && i.status == Status.DONE) n++;
        return n;
    }

    public int doneCount() {
        int n = 0;
        for (Item i : items) if (i.status == Status.DONE) n++;
        return n;
    }

    public int shortCount() {
        int n = 0;
        for (Item i : items) if (i.status == Status.SHORT) n++;
        return n;
    }

    /** True when anything was asked for and nothing could be made. */
    public boolean any() {
        return !items.isEmpty();
    }

    /** The first reason an item of this line is short with, or null. */
    public @Nullable String shortReasonOfLine(int line) {
        for (Item i : items) if (i.ref.line() == line && i.status == Status.SHORT) return i.reason;
        return null;
    }

    /** The order's one note: "<n> item(s) not enchanted: <reasons>" (each reason once), or null when none came up short. */
    public @Nullable String note() {
        int n = shortCount();
        if (n == 0) return null;
        List<String> reasons = new ArrayList<>();
        for (Item i : items) if (i.status == Status.SHORT && i.reason != null && !reasons.contains(i.reason)) reasons.add(i.reason);
        return n + " item" + (n == 1 ? "" : "s") + " not enchanted: " + String.join(", ", reasons);
    }

    // result.enchant

    /** {@code result.enchant} (ENCHANT-CONTRACT v1.0): numbers, item keys, enchantment ids and reason codes only. No positions. */
    public JsonObject toResult() {
        JsonObject o = new JsonObject();
        JsonArray made = new JsonArray(), shorts = new JsonArray();
        for (int l = 0; l < block.lines().size() && made.size() < MAX_ENTRIES; l++) {
            EnchantOrder.Line line = block.lines().get(l);
            if (line.key().length() > MAX_KEY) continue;
            JsonObject m = new JsonObject();
            m.addProperty("key", line.key());
            m.addProperty("wanted", line.count());
            m.addProperty("made", Math.min(line.count(), madeOnLine(l)));
            made.add(m);
            String why = shortReasonOfLine(l);
            if (why != null && shorts.size() < MAX_ENTRIES) {
                JsonObject s = new JsonObject();
                s.addProperty("key", line.key());
                s.addProperty("reason", Codes.safe(why));
                shorts.add(s);
            }
        }
        o.add("made", made);
        o.add("short", shorts);
        o.add("levels", EnchantOrder.levelsJson(lastLevels));
        o.addProperty("bottlesUsed", bottlesUsed);
        o.addProperty("anvilsBroken", anvilsBroken);
        JsonArray back = new JsonArray();
        for (var e : returned.entrySet()) {
            if (back.size() >= MAX_ENTRIES || e.getKey().length() > MAX_KEY) continue;
            JsonObject r = new JsonObject();
            r.addProperty("key", e.getKey());
            r.addProperty("count", (int) Math.min(Integer.MAX_VALUE, e.getValue()));
            back.add(r);
        }
        o.add("returned", back);
        return o;
    }

    // The journal

    /** The ledger as JSON text, saved with the order's plan after every step. */
    public JsonObject toSaved() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Item i : items) {
            JsonObject j = new JsonObject();
            j.addProperty("s", i.status.ordinal());
            if (i.reason != null) j.addProperty("why", i.reason);
            j.addProperty("done", i.stepsDone);
            if (i.topped > 0) j.addProperty("top", i.topped);
            if (i.real != null) j.add("plan", planJson(i.real));
            if (!i.levels.isEmpty()) j.add("levels", EnchantOrder.levelsJson(i.levels));
            arr.add(j);
        }
        o.add("items", arr);
        JsonObject b = new JsonObject();
        for (var e : budget.entrySet()) b.addProperty(e.getKey(), e.getValue());
        o.add("budget", b);
        JsonObject r = new JsonObject();
        for (var e : returned.entrySet()) r.addProperty(e.getKey(), e.getValue());
        o.add("returned", r);
        o.addProperty("bottles", bottlesUsed);
        o.addProperty("broken", anvilsBroken);
        o.addProperty("topUps", topUps);
        o.add("last", EnchantOrder.levelsJson(lastLevels));
        return o;
    }

    /** A cap on what a saved journal may say about counts (it is read back from a file the bot wrote, but never trusted past what an order can hold). */
    private static final long MAX_SAVED = 100_000_000L;

    /**
     * Takes a saved record up again. The whole record is parsed into locals first and applied only when all of it parsed: a record that does not fit the
     * order (another item count) or is broken anywhere leaves the ledger as it is (fresh, with its full budget). A running item whose saved plan is not sound
     * (indices, costs, or it does not replay with the library) can't be resumed: it is short with {@code wrong_result} and what is carried goes back.
     */
    public void restore(JsonObject o) {
        try {
            JsonArray arr = o.getAsJsonArray("items");
            if (arr.size() != items.size()) return;
            int count = arr.size();
            Status[] status = new Status[count];
            String[] reason = new String[count];
            int[] done = new int[count], topped = new int[count];
            EnchantOrder.ItemPlan[] real = new EnchantOrder.ItemPlan[count];
            boolean[] unsound = new boolean[count];
            List<Map<String, Integer>> levels = new ArrayList<>();
            for (int n = 0; n < count; n++) {
                JsonObject j = arr.get(n).getAsJsonObject();
                int s = j.get("s").getAsInt();
                if (s < 0 || s >= Status.values().length) return;
                status[n] = Status.values()[s];
                reason[n] = j.has("why") ? Codes.safe(j.get("why").getAsString()) : null;
                done[n] = (int) Math.max(0, Math.min(MAX_SAVED, j.get("done").getAsLong()));
                topped[n] = j.has("top") ? (int) Math.max(0, Math.min(MAX_SAVED, j.get("top").getAsLong())) : 0;
                levels.add(j.has("levels") ? EnchantOrder.levels(j.get("levels")) : new TreeMap<>());
                if (j.has("plan")) {
                    try {
                        real[n] = planOf(j.getAsJsonObject("plan"));
                        String problem = EnchantReplan.validate(block.mc(), items.get(n).ref.key(), real[n]);
                        if (problem != null) unsound[n] = true;
                    } catch (RuntimeException e) {
                        unsound[n] = true;
                    }
                }
            }
            Map<String, Long> newBudget = new LinkedHashMap<>(budget), newReturned = new LinkedHashMap<>();
            for (var e : o.getAsJsonObject("budget").entrySet()) {
                if (take.containsKey(e.getKey())) newBudget.put(e.getKey(), Math.max(0, Math.min(take.get(e.getKey()), e.getValue().getAsLong())));
            }
            for (var e : o.getAsJsonObject("returned").entrySet()) newReturned.put(e.getKey(), Math.max(0, Math.min(MAX_SAVED, e.getValue().getAsLong())));
            int bottles = (int) Math.max(0, Math.min(MAX_SAVED, o.get("bottles").getAsLong()));
            int broken = (int) Math.max(0, Math.min(MAX_SAVED, o.get("broken").getAsLong()));
            int ups = o.has("topUps") ? (int) Math.max(0, Math.min(MAX_SAVED, o.get("topUps").getAsLong())) : 0;
            Map<String, Integer> last = o.has("last") ? EnchantOrder.levels(o.get("last")) : new TreeMap<>();

            // All of it parsed: applied
            for (int n = 0; n < count; n++) {
                Item i = items.get(n);
                i.status = status[n];
                i.reason = reason[n];
                i.levels = levels.get(n);
                i.topped = topped[n];
                i.real = unsound[n] ? null : real[n];
                i.stepsDone = i.real == null ? 0 : Math.min(done[n], i.real.steps().size());
                if (unsound[n] && (i.status == Status.RUNNING || i.status == Status.PENDING)) {
                    i.status = Status.SHORT;
                    i.reason = Codes.WRONG_RESULT;
                }
                if (i.status == Status.RUNNING && i.real == null) i.status = Status.PENDING;
            }
            budget.putAll(newBudget);
            returned.clear();
            returned.putAll(newReturned);
            bottlesUsed = bottles;
            anvilsBroken = broken;
            topUps = ups;
            lastLevels = last;
        } catch (RuntimeException e) {
            // A broken record is a fresh ledger: nothing was applied and the order goes on with its full budget
        }
    }

    /**
     * Every item that has not started is short with this reason. An item that is past its first step is left alone: it can't fail once started (the host
     * finishes it, or waits for what it needs).
     */
    public void failNotStarted(String reason) {
        for (int n = 0; n < items.size(); n++) {
            Item i = items.get(n);
            if (i.status == Status.PENDING || (i.status == Status.RUNNING && i.stepsDone < 1)) fail(n, reason);
        }
    }

    /** Bottles fetched on top of the held ones for this item (the top-up cap counts them). */
    public int topped(int n) {
        return items.get(n).topped;
    }

    public void addTopped(int n, int bottles) {
        items.get(n).topped += Math.max(0, bottles);
    }

    static JsonObject planJson(EnchantOrder.ItemPlan p) {
        JsonObject o = new JsonObject();
        JsonArray mats = new JsonArray();
        for (EnchantOrder.Mat m : p.materials()) {
            JsonObject j = new JsonObject();
            j.addProperty("kind", m.kind());
            j.addProperty("key", m.key());
            j.add("ench", EnchantOrder.levelsJson(m.ench()));
            j.addProperty("rc", m.rc());
            if (m.crafted()) j.addProperty("crafted", true);
            mats.add(j);
        }
        o.add("materials", mats);
        JsonArray steps = new JsonArray();
        for (EnchantOrder.Step s : p.steps()) {
            JsonObject j = new JsonObject();
            j.addProperty("left", s.left());
            j.addProperty("right", s.right());
            j.addProperty("cost", s.cost());
            j.addProperty("rc", s.rc());
            steps.add(j);
        }
        o.add("steps", steps);
        o.add("levels", EnchantOrder.levelsJson(p.levels()));
        o.add("extras", EnchantOrder.levelsJson(p.extras()));
        JsonArray curse = new JsonArray();
        for (String c : p.curse()) curse.add(EnchantOrder.ns(c));
        o.add("curse", curse);
        o.addProperty("points", p.points());
        o.addProperty("bottles", p.bottles());
        return o;
    }

    static EnchantOrder.ItemPlan planOf(JsonObject o) {
        JsonElement wrapped = o;
        EnchantOrder.Block b = EnchantOrder.parse(wrapBlock(o));
        if (b == null || b.malformed() || b.lines().isEmpty() || b.lines().get(0).plans().isEmpty()) throw new IllegalArgumentException("plan");
        return b.lines().get(0).plans().get(0);
    }

    /** A one-item block around a plan, so the plan has one reader. */
    private static JsonObject wrapBlock(JsonObject plan) {
        JsonObject line = new JsonObject();
        line.addProperty("key", "minecraft:stick");
        line.addProperty("count", 1);
        line.add("enchants", new JsonArray());
        JsonArray plans = new JsonArray();
        plans.add(plan);
        line.add("plans", plans);
        JsonArray lines = new JsonArray();
        lines.add(line);
        JsonObject b = new JsonObject();
        b.addProperty("v", 1);
        b.addProperty("mc", "1.21.4");
        b.add("lines", lines);
        b.add("take", new JsonArray());
        return b;
    }
}
