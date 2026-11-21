package com.porter.qwertestash.orders;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The smelt journal of a craft order (CRAFT-CONTRACT-v1.2.md 14, "Restarts"): what the bot has put into the furnace and taken out of it during the
 * furnace work of one chunk, kept in the order's ledger and saved with it, so that after a restart the bot knows that the furnace holds something of
 * its own, and what.
 * <p>
 * Every entry is made after the game confirmed the move (the stack is seen in the furnace's slot, or is gone from it and seen in the inventory), never
 * before. A crash between a click and its entry therefore loses a count (items put into the furnace that are then taken out and put back to the stash;
 * a few products that are put back too) and can never make one up: nothing is counted twice and nothing is charged that did not leave the stash.
 * <ul>
 *     <li>{@code placed}: items put into the furnace's input and fuel slots in this chunk, by item id (what the chunk's budget is charged for, less what
 *     is taken out again).</li>
 *     <li>{@code produced}: items taken out of the output slot into the inventory in this chunk, by item id.</li>
 *     <li>{@code active}: the batch in the furnace now, if any.</li>
 * </ul>
 * The journal is {@link #open} while the chunk's furnace work is under way and cleared ({@link #close}) when the chunk has accounted for it (its normal
 * end), so a saved journal that is open means the bot stopped in the middle of it. Pure; the bot tick thread.
 */
public final class SmeltJournal {
    /** The batch that is in the furnace now: the input kind and count loaded, what it makes, how many items it should make and how many were taken so far. */
    public record Active(String recipe, String in, int inCount, String out, int items, int collected, String fuel) {
    }

    private boolean open;
    private final Map<String, Long> placed = new LinkedHashMap<>(), produced = new LinkedHashMap<>();
    private @Nullable Active active;

    public boolean isOpen() {
        return open;
    }

    /** The chunk's furnace work begins (nothing is in the furnace yet). */
    public void open() {
        open = true;
        placed.clear();
        produced.clear();
        active = null;
    }

    /** The chunk's furnace work is accounted for (or given up on): the journal is empty again. */
    public void close() {
        open = false;
        placed.clear();
        produced.clear();
        active = null;
    }

    public Map<String, Long> placed() {
        return placed;
    }

    public Map<String, Long> produced() {
        return produced;
    }

    public @Nullable Active active() {
        return active;
    }

    /** {@code n} of this item were seen in the furnace's fuel or input slot, put there by the bot. */
    public void placed(String id, long n) {
        if (n > 0) placed.merge(Recipes.ns(id), n, Long::sum);
    }

    /** A batch is loaded: its input counted in {@link #placed} by the caller. */
    public void loaded(Active a) {
        active = a;
    }

    /** {@code n} of this item were taken out of the output slot into the inventory. */
    public void collected(String id, long n) {
        if (n <= 0) return;
        produced.merge(Recipes.ns(id), n, Long::sum);
        Active a = active;
        if (a != null && Recipes.ns(a.out()).equals(Recipes.ns(id))) active = new Active(a.recipe(), a.in(), a.inCount(), a.out(), a.items(), a.collected() + (int) Math.min(Integer.MAX_VALUE, n), a.fuel());
    }

    /** The batch in the furnace is done and emptied. */
    public void batchDone() {
        active = null;
    }

    /** What taking the furnace's contents back out settles for the order's ledger (see {@link #settle}). */
    public record Settlement(Map<String, Long> charge, Map<String, Long> surplus) {
    }

    /**
     * The accounting of a furnace run that ended without its chunk being finished (a restart, a failed trip): the budget is charged for what went into
     * the furnace and did not come back out (the inputs the furnace turned into products, and the fuel it burnt), and the products that were taken out
     * into the inventory are the order's surplus, as many as are really carried now.
     * @param returned what was taken out of the input and fuel slots again, by id (it is put back to the stash with the leftovers)
     * @param carried  what the inventory holds of items nobody has claimed (stacks the ledger does not already count), by id
     */
    public Settlement settle(Map<String, Long> returned, Map<String, Long> carried) {
        Map<String, Long> charge = new LinkedHashMap<>(), surplus = new LinkedHashMap<>();
        for (var e : placed.entrySet()) {
            long n = e.getValue() - Math.max(0, returned.getOrDefault(e.getKey(), 0L));
            if (n > 0) charge.put(e.getKey(), n);
        }
        for (var e : produced.entrySet()) {
            long n = Math.min(e.getValue(), Math.max(0, carried.getOrDefault(e.getKey(), 0L)));
            if (n > 0) surplus.put(e.getKey(), n);
        }
        return new Settlement(charge, surplus);
    }

    // The saved form

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("open", open);
        o.add("placed", map(placed));
        o.add("produced", map(produced));
        Active a = active;
        if (a != null) {
            JsonObject x = new JsonObject();
            x.addProperty("recipe", a.recipe());
            x.addProperty("in", a.in());
            x.addProperty("inCount", a.inCount());
            x.addProperty("out", a.out());
            x.addProperty("items", a.items());
            x.addProperty("collected", a.collected());
            x.addProperty("fuel", a.fuel());
            o.add("active", x);
        }
        return o;
    }

    /** Reads a saved journal back (a broken record is an empty, closed journal: the furnace is then only found busy if something is in it). */
    public static SmeltJournal fromJson(@Nullable JsonElement e) {
        SmeltJournal j = new SmeltJournal();
        try {
            if (e == null || !e.isJsonObject()) return j;
            JsonObject o = e.getAsJsonObject();
            if (!o.get("open").getAsBoolean()) return j;
            Map<String, Long> p = read(o.getAsJsonObject("placed")), q = read(o.getAsJsonObject("produced"));
            Active a = null;
            if (o.has("active") && o.get("active").isJsonObject()) {
                JsonObject x = o.getAsJsonObject("active");
                a = new Active(x.get("recipe").getAsString(), x.get("in").getAsString(), x.get("inCount").getAsInt(), x.get("out").getAsString(), x.get("items").getAsInt(),
                    x.get("collected").getAsInt(), x.get("fuel").getAsString());
            }
            j.open = true;
            j.placed.putAll(p);
            j.produced.putAll(q);
            j.active = a;
        } catch (RuntimeException ex) {
            return new SmeltJournal();
        }
        return j;
    }

    private static JsonObject map(Map<String, Long> m) {
        JsonObject o = new JsonObject();
        for (var e : m.entrySet()) o.addProperty(e.getKey(), e.getValue());
        return o;
    }

    private static Map<String, Long> read(JsonObject o) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (var e : o.entrySet()) {
            long n = e.getValue().getAsLong();
            if (n < 0) throw new IllegalArgumentException("negative");
            out.put(e.getKey(), n);
        }
        return out;
    }
}
