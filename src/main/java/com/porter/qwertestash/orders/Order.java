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
    public record Line(String type, String key, int count, boolean repair, @Nullable String spec) {
        public Line(String type, String key, int count, boolean repair) {
            this(type, key, count, repair, null);
        }

        public Line(String type, String key, int count) {
            this(type, key, count, false, null);
        }

        /**
         * What the line is called in the craft ledger and in a craft load: the key, or for a craft line with a spec (B3, a rocket or star variant) the
         * pseudo id {@code <key>@<spec>} ({@link Pseudo}).
         */
        public String ref() {
            return Pseudo.of(key, spec);
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
         * An item still to be enchanted (1.20.0, ENCHANT-CONTRACT v1.0): not in the stash as such, made at the anvil before its bin is built. Its variant is
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
         * The variant text of a load that is a base for an enchant plan (1.20.0, ENCHANT-CONTRACT draft 3.4): made by the order's craft block, kept in hand for the enchant
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

        /**
         * The same load, made, under the index key of the stack that was made (B3: a load of a rocket or star spec is planned under its pseudo id; what is made
         * has the key of its own components, which is what the bin and the delivery know it by). A null key leaves it as it was.
         */
        public Lot madeAs(@Nullable String madeKey) {
            return new Lot(madeKey == null ? key : madeKey, variant, count, CRAFTED);
        }
    }

    /**
     * An item id and a count: a craft block's lines, inputs, take and leftovers. B3 (CRAFT-CONTRACT-v1.2.md 9): {@code spec} names a variant of the item
     * (a rocket or star) and {@code key} the index key it is taken by (a {@code take} entry's key; an input's key only when one key carries the spec).
     */
    public record Qty(String id, int count, @Nullable String key, @Nullable String spec) {
        public Qty(String id, int count) {
            this(id, count, null, null);
        }

        /** The planner's name for it: the id, or the pseudo id of its spec. */
        public String ref() {
            return Pseudo.of(id, spec);
        }
    }

    /** One cell of a special step's grid (B3): an item id and, for a star, its spec. */
    public record Cell(String id, @Nullable String spec) {
        public String ref() {
            return Pseudo.of(id, spec);
        }
    }

    /**
     * One recipe made {@code fills} times, as the server planned it: what it makes per fill ({@code count}) and what goes in, by id. A furnace step
     * ({@code station: "furnace"}, B2) names an extras smelt recipe; its {@code fills} are the items smelted and {@code fuel} what burns for them.
     */
    public record CraftStep(String recipe, String result, int count, int fills, List<Qty> inputs, boolean furnace, List<Qty> fuel, @Nullable String spec,
                            @Nullable List<@Nullable Cell> grid) {
        /** A step at the crafting table (CRAFT-CONTRACT-v1.2.md 9: no {@code station} means {@code table}). */
        public CraftStep(String recipe, String result, int count, int fills, List<Qty> inputs) {
            this(recipe, result, count, fills, inputs, false, List.of(), null, null);
        }

        public CraftStep(String recipe, String result, int count, int fills, List<Qty> inputs, boolean furnace, List<Qty> fuel) {
            this(recipe, result, count, fills, inputs, furnace, fuel, null, null);
        }

        /** A special step (B3): it makes a variant of {@code result}, from the exact nine-cell {@code grid}. */
        public boolean special() {
            return grid != null;
        }

        /** What the step makes, as the planner names it: the result id, or the pseudo id of its spec. */
        public String resultRef() {
            return Pseudo.of(result, spec);
        }
    }

    /**
     * The craft block of an order (CRAFT-CONTRACT.md "Stored order"): the plan the server made at placement. {@code take} is the budget:
     * never more of an id is taken from the stash than it says. {@code malformed} is a block that is there but could not be read: every
     * craft line is then short ({@code recipe_mismatch}).
     */
    public record Craft(int v, String mc, String recipes, List<Qty> lines, List<CraftStep> steps, List<Qty> take, List<Qty> leftovers, int fills, boolean malformed,
                        List<Qty> forEnchant, int maxDepth, @Nullable String extras, List<String> needs, @Nullable String reserved, int smelt) {
        /** A v2 block of milestone B1: no items smelted. */
        public Craft(int v, String mc, String recipes, List<Qty> lines, List<CraftStep> steps, List<Qty> take, List<Qty> leftovers, int fills, boolean malformed,
                     List<Qty> forEnchant, int maxDepth, @Nullable String extras, List<String> needs, @Nullable String reserved) {
            this(v, mc, recipes, lines, steps, take, leftovers, fills, malformed, forEnchant, maxDepth, extras, needs, reserved, 0);
        }

        /**
         * A v1 block, with bases for enchant plans. {@code maxDepth} is 0 (a v1 block has none: the planner's own 4 levels), no extras, no needs and
         * nothing reserved.
         */
        public Craft(int v, String mc, String recipes, List<Qty> lines, List<CraftStep> steps, List<Qty> take, List<Qty> leftovers, int fills, boolean malformed,
                     List<Qty> forEnchant) {
            this(v, mc, recipes, lines, steps, take, leftovers, fills, malformed, forEnchant, 0, null, List.of(), null);
        }

        /** A block without bases for enchant plans (every block before ENCHANT-CONTRACT draft 3.4). */
        public Craft(int v, String mc, String recipes, List<Qty> lines, List<CraftStep> steps, List<Qty> take, List<Qty> leftovers, int fills, boolean malformed) {
            this(v, mc, recipes, lines, steps, take, leftovers, fills, malformed, List.of());
        }

        public static Craft unreadable() {
            return new Craft(0, "", "", List.of(), List.of(), List.of(), List.of(), 0, true);
        }

        /** Grid fills of all the steps at the crafting table (a furnace step's fills are items: {@link #smeltItems}). */
        public long stepFills() {
            long n = 0;
            for (CraftStep s : steps) if (!s.furnace()) n += s.fills();
            return n;
        }

        /** Items the furnace steps smelt in all. */
        public long smeltItems() {
            long n = 0;
            for (CraftStep s : steps) if (s.furnace()) n += s.fills();
            return n;
        }

        /** The block has a furnace step (it needs the {@code smelt} feature). */
        public boolean hasFurnace() {
            for (CraftStep s : steps) if (s.furnace()) return true;
            return false;
        }

        /**
         * The block uses a field of milestone B3 (CRAFT-CONTRACT-v1.2.md 9): a spec on a line, step, input, take or leftover, a take or input key or a grid.
         * Such a block needs the {@code firework} feature and the bot's own extras.
         */
        public boolean usesSpecs() {
            for (Qty q : lines) if (q.spec() != null) return true;
            for (Qty q : take) if (q.spec() != null || q.key() != null) return true;
            for (Qty q : leftovers) if (q.spec() != null) return true;
            for (CraftStep s : steps) {
                if (s.spec() != null || s.grid() != null) return true;
                for (Qty q : s.inputs()) if (q.spec() != null || q.key() != null) return true;
            }
            return false;
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
    /** The enchant block (1.20.0, ENCHANT-CONTRACT v1.0), or null when the order has no enchant lines; {@link EnchantOrder.Block#unreadable()} when it is there but wrong. */
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
                // B3: a craft line may name a variant of its item (a rocket or star spec); no other line has one
                JsonElement sp = l.get("spec");
                String spec = type.equals(CRAFT) && sp != null && sp.isJsonPrimitive() && sp.getAsJsonPrimitive().isString() && !sp.getAsString().isEmpty() ? sp.getAsString() : null;
                if ((type.equals(ITEM) || type.equals(SHULKER) || type.equals(CRAFT) || type.equals(KIT) || type.equals(ENCHANT)) && !key.isEmpty() && count >= 1 && count <= MAX_LINE_COUNT) lines.add(new Line(type, key, count, type.equals(ITEM) && flag(l, "repair"), spec));
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
            List<Qty> lines = qtys(c.get("lines"), "key", 200, v == 2);
            List<Qty> take = qtys(c.get("take"), "id", MAX_CRAFT_TAKE, v == 2);
            List<Qty> leftovers = qtys(c.get("leftovers"), "id", 4096, v == 2);
            List<CraftStep> steps = new ArrayList<>();
            JsonArray sa = c.getAsJsonArray("steps");
            if (sa.size() > MAX_CRAFT_STEPS) return Craft.unreadable();
            for (JsonElement s : sa) {
                JsonObject so = s.getAsJsonObject();
                // B2: station "furnace" and fuel (a v2 block only; a v1 block carries neither, and an unknown station is unreadable)
                boolean furnace = false;
                List<Qty> fuel = List.of();
                if (v == 2) {
                    JsonElement st = so.get("station");
                    if (st != null && !st.isJsonNull()) {
                        if (!st.isJsonPrimitive() || !st.getAsJsonPrimitive().isString()) return Craft.unreadable();
                        String station = st.getAsString();
                        if (station.equals("furnace")) furnace = true;
                        else if (!station.equals("table")) return Craft.unreadable();
                    }
                    JsonElement fu = so.get("fuel");
                    if (fu != null && !fu.isJsonNull()) fuel = qtys(fu, "id", 8);
                }
                // B3: the variant a special step makes and its exact nine-cell grid (a v2 block only)
                String stepSpec = null;
                List<Cell> grid = null;
                if (v == 2) {
                    stepSpec = optionalString(so.get("spec"));
                    grid = grid(so.get("grid"));
                }
                steps.add(new CraftStep(so.get("recipe").getAsString(), so.get("result").getAsString(), so.get("count").getAsInt(), so.get("fills").getAsInt(),
                    qtys(so.get("inputs"), "id", 64, v == 2), furnace, fuel, stepSpec, grid));
            }
            int fills = c.get("fills").getAsInt();
            // v 2 (CRAFT-CONTRACT-v1.2.md 9): the depth, the extras hash and the features the plan needs are required; anything this bot does not carry out (a
            // furnace step, a spec, a grid, a remainder, ...) is noted in `reserved` and makes the block fail its check with recipe_mismatch
            int maxDepth = 0, smelt = 0;
            String extras = null, reserved = null;
            List<String> needs = List.of();
            if (v == 2) {
                JsonElement md = c.get("maxDepth");
                if (md == null || !md.isJsonPrimitive() || !md.getAsJsonPrimitive().isNumber()) return Craft.unreadable();
                maxDepth = md.getAsInt();
                if (!c.has("extras")) return Craft.unreadable();
                JsonElement ex = c.get("extras");
                if (!ex.isJsonNull()) {
                    if (!ex.isJsonPrimitive() || !ex.getAsJsonPrimitive().isString()) return Craft.unreadable();
                    extras = ex.getAsString();
                }
                JsonElement ne = c.get("needs");
                if (ne == null || !ne.isJsonArray() || ne.getAsJsonArray().size() > 16) return Craft.unreadable();
                needs = new ArrayList<>();
                for (JsonElement n : ne.getAsJsonArray()) {
                    if (!n.isJsonPrimitive() || !n.getAsJsonPrimitive().isString()) return Craft.unreadable();
                    needs.add(n.getAsString());
                }
                reserved = reservedField(c);
                // B2: the items the furnace steps smelt in all (absent or null is 0); a negative or non-numeric value is unreadable
                JsonElement sm = c.get("smelt");
                if (sm != null && !sm.isJsonNull()) {
                    if (!sm.isJsonPrimitive() || !sm.getAsJsonPrimitive().isNumber()) return Craft.unreadable();
                    long n = sm.getAsLong();
                    if (n < 0 || n > 1_000_000) return Craft.unreadable();
                    smelt = (int) n;
                }
            }
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
            return new Craft(v, mc, recipes, lines, steps, take, leftovers, fills, false, forEnchant, maxDepth, extras, needs, reserved, smelt);
        } catch (RuntimeException ex) {
            return Craft.unreadable();
        }
    }

    /**
     * The first field of a v2 craft block that is reserved for a later milestone (CRAFT-CONTRACT-v1.2.md 9), as text, or null. B4: a {@code remainder}.
     * A field that is absent, null or (for a list) empty is not there. The fields of B2 (furnace stations, {@code fuel}, {@code smelt}) and of B3 (a
     * {@code spec} on a line, step, input, take or leftover, a {@code grid}, a {@code key} on an input or a take entry) are carried out: they are read
     * ({@link Craft#usesSpecs}) and judged by {@link CraftOrder#check}, which refuses them for a bot that doesn't say {@code firework}.
     */
    static @Nullable String reservedField(JsonObject c) {
        for (JsonElement s : arrayOf(c, "steps")) {
            if (set(s, "remainder")) return "steps[].remainder";
        }
        return null;
    }

    /** A string field that is there (not null), or null; a value of another type makes the block unreadable. */
    private static @Nullable String optionalString(@Nullable JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("not a string");
        String s = e.getAsString();
        return s.isEmpty() ? null : s;
    }

    /**
     * A special step's grid (B3): null when the field is absent or null, else its cells, each null (empty) or {@code {id, spec?}}. The number of cells is
     * not judged here ({@link CraftOrder#check} wants nine); a cell that is not null or an object with an id is unreadable.
     */
    private static @Nullable List<@Nullable Cell> grid(@Nullable JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (!e.isJsonArray() || e.getAsJsonArray().size() > 16) throw new IllegalArgumentException("bad grid");
        List<Cell> out = new ArrayList<>();
        for (JsonElement x : e.getAsJsonArray()) {
            if (x.isJsonNull()) {
                out.add(null);
                continue;
            }
            JsonObject o = x.getAsJsonObject();
            JsonElement id = o.get("id");
            if (id == null || !id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("bad grid cell");
            out.add(new Cell(id.getAsString(), optionalString(o.get("spec"))));
        }
        return java.util.Collections.unmodifiableList(out);
    }

    private static List<JsonElement> arrayOf(JsonObject o, String name) {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonArray()) return List.of();
        List<JsonElement> out = new ArrayList<>();
        for (JsonElement x : e.getAsJsonArray()) if (x.isJsonObject()) out.add(x);
        return out;
    }

    private static @Nullable JsonElement field(JsonElement o, String name) {
        return o.isJsonObject() ? o.getAsJsonObject().get(name) : null;
    }

    /** A field that is present and not null, and not an empty list or an empty string. */
    private static boolean set(JsonElement o, String name) {
        JsonElement f = field(o, name);
        if (f == null || f.isJsonNull()) return false;
        if (f.isJsonArray()) return !f.getAsJsonArray().isEmpty();
        if (f.isJsonPrimitive() && f.getAsJsonPrimitive().isString()) return !f.getAsString().isEmpty();
        return true;
    }

    private static List<Qty> qtys(@Nullable JsonElement e, String idName, int max) {
        return qtys(e, idName, max, false);
    }

    /** As above; {@code specs}: an entry's optional {@code spec} (and, when the id is not itself called key, {@code key}) is read too (B3, a v2 block only). */
    private static List<Qty> qtys(@Nullable JsonElement e, String idName, int max, boolean specs) {
        if (e == null || !e.isJsonArray()) throw new IllegalArgumentException("not a list");
        JsonArray a = e.getAsJsonArray();
        if (a.size() > max) throw new IllegalArgumentException("too many");
        List<Qty> out = new ArrayList<>();
        for (JsonElement x : a) {
            JsonObject o = x.getAsJsonObject();
            int count = o.get("count").getAsInt();
            if (count < 0) throw new IllegalArgumentException("negative");
            if (!specs) {
                out.add(new Qty(o.get(idName).getAsString(), count));
                continue;
            }
            out.add(new Qty(o.get(idName).getAsString(), count, idName.equals("key") ? null : optionalString(o.get("key")), optionalString(o.get("spec"))));
        }
        return out;
    }

    /** The total asked for per line key, lines with the same key merged. */
    public Map<String, Integer> requested() {
        Map<String, Integer> out = new LinkedHashMap<>();
        // A craft line with a spec (B3) is delivered under the key of the stack that was made, which the plan can not know: the craft ledger accounts for it
        for (Line l : lines) if (l.spec() == null) out.merge(l.key(), l.count(), Integer::sum);
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
