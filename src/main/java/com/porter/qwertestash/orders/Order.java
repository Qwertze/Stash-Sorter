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
 * An order from the Stash Manager server: what to take from the stash, as lines, and the packer that turns item lines
 * into shulker loads. Plain Java (no game types), a port of the Meteor addon's {@code Order}; the packing is the one
 * PROTOCOL.md specifies, so the website's preview and the bot agree.
 */
public final class Order {
    public static final String ITEM = "item", SHULKER = "shulker", CRAFT = "craft", KIT = "kit", ENCHANT = "enchant";
    /** The most steps and take entries a craft block may have (CRAFT-CONTRACT.md "Orders"). */
    public static final int MAX_CRAFT_STEPS = 64, MAX_CRAFT_TAKE = 64;
    /** A line asking for more than this is not read (it shows up as undelivered); the server caps them as well. */
    public static final int MAX_LINE_COUNT = 1_000_000;
    /** The most shulkers one order may need (one double chest). */
    public static final int MAX_BINS = 54;
    public static final int SLOTS = 27;

    /**
     * One line: {@code type} is "item" (key is an items[] key), "shulker" (key is a shulkers[] group key) or "craft" (1.15.0: key is a
     * plain item id the stash can make; the order's {@link Craft} block has the plan).
     */
    public record Line(String type, String key, int count, boolean repair) {
        public Line(String type, String key, int count) {
            this(type, key, count, false);
        }
    }

    /**
     * The stacks to put in one slot: an item key, how many, and (once the stash was looked at) the exact variant, the hash
     * over every component, so the stack is taken from a stack that can really merge with it. Null: any variant.
     */
    public record Lot(String key, @Nullable String variant, int count, int craftState) {
        /** An ordinary load, taken from the stash; a load still to be crafted (not in the stash); a load that was crafted (in hand, not in the stash). */
        public static final int NONE = 0, PENDING = 1, CRAFTED = 2;
        /**
         * An item still to be enchanted (1.19.0, ENCHANT-CONTRACT v1.0): not in the stash as such, made at the anvil before its bin is built. Its variant is
         * {@link #enchantVariant}, the number of the item in the order's enchant ledger; once it is finished the lot is the finished stack (state CRAFTED).
         */
        public static final int ENCHANT = 3;

        /** The variant text of an enchant lot: {@code enchant:<item number>}. */
        public static String enchantVariant(int n) {
            return "enchant:" + n;
        }

        /** The item number of an enchant lot, or -1. */
        public int enchantItem() {
            if (craftState != ENCHANT || variant == null || !variant.startsWith("enchant:")) return -1;
            try {
                return Integer.parseInt(variant.substring("enchant:".length()));
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        /** A load that is an item still to be enchanted. */
        public boolean enchant() {
            return craftState == ENCHANT;
        }

        /** The load of the enchanted item number {@code n} of a line (one item, one slot). */
        public static Lot toEnchant(String key, int n) {
            return new Lot(key, enchantVariant(n), 1, ENCHANT);
        }

        public Lot(String key, @Nullable String variant, int count) {
            this(key, variant, count, NONE);
        }

        public Lot(String key, int count) {
            this(key, null, count, NONE);
        }

        /** A load of a craft line, still to be made before its bin is built. */
        public static Lot toCraft(String key, int count) {
            return new Lot(key, null, count, PENDING);
        }

        /**
         * The variant text of a load that is a base for an enchant plan (1.19.0, ENCHANT-CONTRACT draft 3.4): made by the order's craft block, kept in hand for the enchant
         * run and never put in a shulker. It lives in the last bin that has enchant loads until that bin is built (then {@link OrderFlow} lets it go).
         */
        public static final String BASE_VARIANT = "enchantbase";

        /** A load of a craft line that is a base for the enchant plans, still to be made. */
        public static Lot toBase(String key, int count) {
            return new Lot(key, BASE_VARIANT, count, PENDING);
        }

        /** A base for the enchant plans (still to be made, or made and waiting in hand): never delivered. */
        public boolean base() {
            return BASE_VARIANT.equals(variant) && (craftState == PENDING || craftState == CRAFTED);
        }

        /** The load of a craft line is to be made before its bin is built. */
        public boolean craft() {
            return craftState == PENDING;
        }

        /** Made by crafting: it is not in the stash to be taken or counted again (a replan leaves it alone). */
        public boolean crafted() {
            return craftState == CRAFTED;
        }

        public Lot withVariant(@Nullable String v) {
            return new Lot(key, v, count, craftState);
        }

        /** The same load with another count (a craft load that came out smaller). */
        public Lot withCount(int n) {
            return new Lot(key, variant, n, craftState);
        }

        /** The same load, made: it is a stack in hand now, not something to craft. */
        public Lot made() {
            return new Lot(key, variant, count, CRAFTED);
        }
    }

    /** An item id and a count: a craft block's lines, inputs, take and leftovers. */
    public record Qty(String id, int count) {
    }

    /** One recipe made {@code fills} times, as the server planned it: what it makes per fill ({@code count}) and what goes in, by id. */
    public record CraftStep(String recipe, String result, int count, int fills, List<Qty> inputs) {
    }

    /**
     * The craft block of an order (CRAFT-CONTRACT.md "Stored order"): the plan the server made at placement. {@code take} is the budget:
     * never more of an id is taken from the stash than it says. {@code malformed} is a block that is there but could not be read: every
     * craft line is then short ({@code recipe_mismatch}).
     */
    public record Craft(int v, String mc, String recipes, List<Qty> lines, List<CraftStep> steps, List<Qty> take, List<Qty> leftovers, int fills, boolean malformed,
                        List<Qty> forEnchant) {
        /** A block without bases for enchant plans (every block before ENCHANT-CONTRACT draft 3.4). */
        public Craft(int v, String mc, String recipes, List<Qty> lines, List<CraftStep> steps, List<Qty> take, List<Qty> leftovers, int fills, boolean malformed) {
            this(v, mc, recipes, lines, steps, take, leftovers, fills, malformed, List.of());
        }

        public static Craft unreadable() {
            return new Craft(0, "", "", List.of(), List.of(), List.of(), List.of(), 0, true);
        }

        /** Grid fills of all the steps. */
        public long stepFills() {
            long n = 0;
            for (CraftStep s : steps) n += s.fills();
            return n;
        }
    }

    public final String id;
    public final List<Line> lines;
    /** The names the order's shulkers get, or null for none. */
    public final @Nullable ShulkerNames names;
    /** The cart-wide "Repair all items" switch (REPAIR-CONTRACT.md); false from an older server. */
    public final boolean repairAll;
    /** The craft block (1.15.0), or null when the order has no craft lines (or an older server sent none). */
    public final @Nullable Craft craft;
    /** The kit block (1.16.0, KIT-CONTRACT v2), or null when the order has no kit lines. */
    public final KitOrder.@Nullable Snapshot kit;
    /** The enchant block (1.19.0, ENCHANT-CONTRACT v1.0), or null when the order has no enchant lines; {@link EnchantOrder.Block#unreadable()} when it is there but wrong. */
    public final EnchantOrder.@Nullable Block enchant;
    /** A kit restock job (1.16.0): never from the server, never reported; its kit is filed by the sorter instead of delivered. */
    public boolean restock;
    /** For a restock job: it tops up an incomplete copy in the chain target (else it builds a new copy from an empty shulker). */
    public boolean restockTopUp;

    public Order(String id, List<Line> lines) {
        this(id, lines, null);
    }

    public Order(String id, List<Line> lines, @Nullable ShulkerNames names) {
        this(id, lines, names, false);
    }

    public Order(String id, List<Line> lines, @Nullable ShulkerNames names, boolean repairAll) {
        this(id, lines, names, repairAll, null);
    }

    public Order(String id, List<Line> lines, @Nullable ShulkerNames names, boolean repairAll, @Nullable Craft craft) {
        this(id, lines, names, repairAll, craft, null);
    }

    public Order(String id, List<Line> lines, @Nullable ShulkerNames names, boolean repairAll, @Nullable Craft craft, KitOrder.@Nullable Snapshot kit) {
        this(id, lines, names, repairAll, craft, kit, null);
    }

    public Order(String id, List<Line> lines, @Nullable ShulkerNames names, boolean repairAll, @Nullable Craft craft, KitOrder.@Nullable Snapshot kit,
                 EnchantOrder.@Nullable Block enchant) {
        this.enchant = enchant;
        this.kit = kit;
        this.id = id;
        this.lines = lines;
        this.names = names;
        this.repairAll = repairAll;
        this.craft = craft;
    }

    /** The craft lines, in line order. */
    public List<Line> craftLines() {
        List<Line> out = new ArrayList<>();
        for (Line l : lines) if (l.type().equals(CRAFT)) out.add(l);
        return out;
    }

    /** The kit lines, in line order. */
    public List<Line> kitLines() {
        List<Line> out = new ArrayList<>();
        for (Line l : lines) if (l.type().equals(KIT)) out.add(l);
        return out;
    }

    /** The enchant lines, in line order. */
    public List<Line> enchantLines() {
        List<Line> out = new ArrayList<>();
        for (Line l : lines) if (l.type().equals(ENCHANT)) out.add(l);
        return out;
    }

    public boolean hasEnchant() {
        for (Line l : lines) if (l.type().equals(ENCHANT)) return true;
        return false;
    }

    public boolean hasKit() {
        for (Line l : lines) if (l.type().equals(KIT)) return true;
        return false;
    }

    public boolean hasCraft() {
        for (Line l : lines) if (l.type().equals(CRAFT)) return true;
        return hasEnchantBases();
    }

    /** The craft block makes bases for enchant plans (ENCHANT-CONTRACT draft 3.4 {@code craft.forEnchant}): made, used by the enchant run, never delivered. */
    public boolean hasEnchantBases() {
        return craft != null && !craft.malformed() && !craft.forEnchant().isEmpty();
    }

    /**
     * What the craft engine makes for this order: the craft lines (delivered), then the bases for the enchant plans (not delivered), as the server's
     * {@code craft.lines} merge them per key. The craft ledger counts what was wanted and made from this.
     */
    public List<Line> craftWanted() {
        List<Line> out = new ArrayList<>(craftLines());
        if (hasEnchantBases()) for (Qty q : craft.forEnchant()) if (q.count() > 0) out.add(new Line(CRAFT, q.id(), q.count()));
        return out;
    }

    /** An item line is wanted for repair when the order says repair all or the line asks for it. Shulker lines never are. */
    public boolean wantsRepair(Line l) {
        return l.type().equals(ITEM) && (repairAll || l.repair());
    }

    /** The keys of the item lines wanted for repair, in line order. */
    public java.util.Set<String> repairKeys() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Line l : lines) if (wantsRepair(l)) out.add(l.key());
        return out;
    }

    /** The keys of the item lines that ask for repair themselves (under "repair all" the other lines count only when damaged). */
    public java.util.Set<String> repairExplicitKeys() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Line l : lines) if (l.type().equals(ITEM) && l.repair()) out.add(l.key());
        return out;
    }

    /** Any line is wanted for repair. */
    public boolean anyRepair() {
        for (Line l : lines) if (wantsRepair(l)) return true;
        return false;
    }

    private static boolean flag(JsonObject o, String name) {
        try {
            JsonElement e = o.get(name);
            return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Reads an order object from the server. Null when it has no id or no usable line. */
    public static @Nullable Order parse(@Nullable JsonObject o) {
        if (o == null || !o.has("id") || !o.has("lines") || !o.get("lines").isJsonArray()) return null;
        List<Line> lines = new ArrayList<>();
        JsonArray array = o.getAsJsonArray("lines");
        for (JsonElement e : array) {
            if (!e.isJsonObject()) continue;
            JsonObject l = e.getAsJsonObject();
            try {
                String type = l.get("type").getAsString();
                String key = l.get("key").getAsString();
                int count = l.get("count").getAsInt();
                if ((type.equals(ITEM) || type.equals(SHULKER) || type.equals(CRAFT) || type.equals(KIT) || type.equals(ENCHANT)) && !key.isEmpty() && count >= 1 && count <= MAX_LINE_COUNT) lines.add(new Line(type, key, count, type.equals(ITEM) && flag(l, "repair")));
            } catch (RuntimeException ex) {
                // A broken line is left out and shows up as undelivered
            }
        }
        String id;
        try {
            id = o.get("id").getAsString();
        } catch (RuntimeException ex) {
            return null;
        }
        // A broken names field is read as no names: naming never fails an order
        return id.isEmpty() || lines.isEmpty() ? null : new Order(id, lines, ShulkerNames.parse(o.get("names")), flag(o, "repairAll"), parseCraft(o.get("craft")), kitOf(o, lines), enchantOf(o, lines));
    }

    /** The enchant block; an order with enchant lines and no readable block gets the unreadable marker (every enchant item is then short). */
    private static EnchantOrder.@Nullable Block enchantOf(JsonObject o, List<Line> lines) {
        EnchantOrder.Block b = EnchantOrder.parse(o.get("enchant"));
        if (b == null) {
            for (Line l : lines) if (l.type().equals(ENCHANT)) return EnchantOrder.Block.unreadable();
        }
        return b;
    }

    /** The kit block; an order with kit lines and no readable block gets the unreadable marker (every kit is then short). */
    private static KitOrder.@Nullable Snapshot kitOf(JsonObject o, List<Line> lines) {
        KitOrder.Snapshot s = KitOrder.parse(o.get("kit"));
        if (s == null) {
            for (Line l : lines) if (l.type().equals(KIT)) return KitOrder.Snapshot.unreadable();
        }
        return s;
    }

    /** The craft block: null for none (absent or null), {@link Craft#unreadable()} for one that is there but not what the contract says. */
    static @Nullable Craft parseCraft(@Nullable JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        try {
            if (!e.isJsonObject()) return Craft.unreadable();
            JsonObject c = e.getAsJsonObject();
            int v = c.get("v").getAsInt();
            String mc = c.get("mc").getAsString(), recipes = c.get("recipes").getAsString();
            List<Qty> lines = qtys(c.get("lines"), "key", 200);
            List<Qty> take = qtys(c.get("take"), "id", MAX_CRAFT_TAKE);
            List<Qty> leftovers = qtys(c.get("leftovers"), "id", 4096);
            List<CraftStep> steps = new ArrayList<>();
            JsonArray sa = c.getAsJsonArray("steps");
            if (sa.size() > MAX_CRAFT_STEPS) return Craft.unreadable();
            for (JsonElement s : sa) {
                JsonObject so = s.getAsJsonObject();
                steps.add(new CraftStep(so.get("recipe").getAsString(), so.get("result").getAsString(), so.get("count").getAsInt(), so.get("fills").getAsInt(),
                    qtys(so.get("inputs"), "id", 64)));
            }
            int fills = c.get("fills").getAsInt();
            // ENCHANT-CONTRACT draft 3.4: how many of each craft line are bases for enchant plans. Optional; there but wrong makes the whole block unreadable
            List<Qty> forEnchant = List.of();
            JsonElement fe = c.get("forEnchant");
            if (fe != null && !fe.isJsonNull()) {
                forEnchant = qtys(fe, "key", EnchantOrder.MAX_ITEMS);
                long total = 0;
                for (Qty q : forEnchant) {
                    if (q.count() < 1 || !CraftOrder.plain(q.id())) return Craft.unreadable();
                    total += q.count();
                }
                if (total > EnchantOrder.MAX_ITEMS) return Craft.unreadable();
            }
            return new Craft(v, mc, recipes, lines, steps, take, leftovers, fills, false, forEnchant);
        } catch (RuntimeException ex) {
            return Craft.unreadable();
        }
    }

    private static List<Qty> qtys(@Nullable JsonElement e, String idName, int max) {
        if (e == null || !e.isJsonArray()) throw new IllegalArgumentException("not a list");
        JsonArray a = e.getAsJsonArray();
        if (a.size() > max) throw new IllegalArgumentException("too many");
        List<Qty> out = new ArrayList<>();
        for (JsonElement x : a) {
            JsonObject o = x.getAsJsonObject();
            int count = o.get("count").getAsInt();
            if (count < 0) throw new IllegalArgumentException("negative");
            out.add(new Qty(o.get(idName).getAsString(), count));
        }
        return out;
    }

    /** The total asked for per line key, lines with the same key merged. */
    public Map<String, Integer> requested() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Line l : lines) out.merge(l.key(), l.count(), Integer::sum);
        return out;
    }

    /** Splits a total of one item into slot loads: full stacks, the remainder last. */
    public static List<Lot> expand(String key, int total, int maxStack) {
        List<Lot> lots = new ArrayList<>();
        int max = Math.max(1, maxStack);
        for (int left = total; left > 0; left -= max) lots.add(new Lot(key, Math.min(max, left)));
        return lots;
    }

    /**
     * Packs slot loads into shulkers of 27 slots, any mix of items allowed. Loads of one item are kept together (keys in
     * ascending order), the items with the most loads first, the largest load first within one, then they fill the
     * shulkers in sequence: the fewest shulkers is ceil(loads / 27).
     */
    public static List<List<Lot>> pack(List<Lot> lots) {
        Map<String, List<Lot>> byKey = new TreeMap<>();
        for (Lot lot : lots) byKey.computeIfAbsent(lot.key(), k -> new ArrayList<>()).add(lot);

        List<List<Lot>> groups = new ArrayList<>(byKey.values());
        groups.sort((a, b) -> b.size() - a.size());

        List<Lot> ordered = new ArrayList<>();
        for (List<Lot> g : groups) {
            g.sort((a, b) -> b.count() - a.count());
            ordered.addAll(g);
        }

        List<List<Lot>> bins = new ArrayList<>();
        for (Lot lot : ordered) {
            if (bins.isEmpty() || bins.get(bins.size() - 1).size() >= SLOTS) bins.add(new ArrayList<>());
            bins.get(bins.size() - 1).add(lot);
        }
        return bins;
    }
}
