package com.porter.qwertestash.orders;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwertestash.manager.Extras;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dyed leather armour, leather horse armour and wolf armour as the order contract names them (CRAFT-CONTRACT-v1.2.md sections 3, 4, 9, 13 and 15, milestone B5,
 * and the stash manager server's PROTOCOL.md "Dyed leather armour and wolf armour"): the spec {@code dyed:<rrggbb>}, the one grid every dyeing craft fills, the
 * colour arithmetic of that craft, and the check of a block's dyed steps against the bot's own extras. Written by hand from the contract and the server's own
 * description of the arithmetic; nothing is read from game files. Plain data and functions, no game types; any thread.
 * <ul>
 *     <li>A dyeing step ({@code qwerte:special/dyed}) makes {@code count 1} item of the colour of its {@code spec}. Its grid has the dyeable item in cell 0 and
 *     then 1 to 8 dyes in the contract's dye order, repeats kept. Cell 0 is the plain item (undyed: a dyed item is never the base of another colour) or, for the
 *     second coat of a two-coat colour, the item with the spec {@code dyed:<mid>} and no key: the result of an earlier step of the same block, never stock.</li>
 *     <li>The bot works the colour out itself from its own armour colours ({@link #mix}) and refuses the block when the colour it gets is not the step's spec.</li>
 * </ul>
 */
public final class DyedSpecs {
    private DyedSpecs() {
    }

    public static final String RECIPE_DYED = "qwerte:special/dyed";
    /** The most dyes one craft takes, and the most of a first and of a second coat of a two-coat colour (the server's planner never goes past them). */
    public static final int MAX_DYES = 8, FIRST_COAT = 3, SECOND_COAT = 4;
    public static final int GRID = 9;
    /**
     * The items the bot itself knows an armour-dye craft may colour (hard-coded, so the server's list can only narrow it, as for the firework ingredients): the
     * four leather armour pieces, leather horse armour and wolf armour, without a namespace.
     */
    private static final Set<String> ALLOWED = Set.of("leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots", "leather_horse_armor", "wolf_armor");

    /** The item (with or without {@code minecraft:}) is one of the six the bot allows to be dyed. */
    public static boolean allowed(@Nullable String id) {
        if (id == null) return false;
        return ALLOWED.contains(id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id);
    }

    /** The special kind of the extras and the feature of the heartbeat. */
    public static final String KIND = "dyed", FEATURE = "dyed";

    private static final Pattern SPEC = Pattern.compile("^dyed:[0-9a-f]{6}$"), COLOR = Pattern.compile("^[0-9a-f]{6}$");
    /** The dye names in the contract's order (the order the grid lists them in). */
    private static final List<String> DYES = FireworkSpecs.DYES;

    // ---------- the spec ----------

    /** The colour of a canonical {@code dyed:<rrggbb>} spec, or -1 when it is not one. */
    public static int rgbOf(@Nullable String spec) {
        if (spec == null || !SPEC.matcher(spec).matches()) return -1;
        return Integer.parseInt(spec.substring(5), 16);
    }

    /** {@code dyed:<rrggbb>} (lowercase hex) of a colour. */
    public static String specOf(int rgb) {
        return "dyed:" + String.format(Locale.ROOT, "%06x", rgb & 0xFFFFFF);
    }

    /** The recipe (or a plan id, {@link FireworkSpecs#planId}) is the dyeing recipe. */
    public static boolean isRecipe(String recipeOrPlanId) {
        int hash = recipeOrPlanId.indexOf('#');
        return (hash < 0 ? recipeOrPlanId : recipeOrPlanId.substring(0, hash)).equals(RECIPE_DYED);
    }

    /** A short description for a log line: {@code colour b02e26}. Never a key. */
    public static String describe(String spec) {
        return rgbOf(spec) < 0 ? spec : "colour " + spec.substring(5);
    }

    // ---------- the colour arithmetic ----------

    /**
     * The colour of an item with {@code start} (an rgb int, or -1 for an undyed one) after the dyes {@code counts} (per dye, in the contract's dye order) are
     * added: the item's own colour (when it has one) counts as one more dye; each channel is summed and divided (whole numbers) by the number of dyes and
     * items; then every channel is multiplied by (the average of the largest channel of each dye or dyed item) over (the largest averaged channel), in 32-bit
     * float arithmetic, and cut off, not rounded. {@code palette} holds each dye's armour colour as an rgb int. [UNVERIFIED in game, like the server's copy.]
     */
    public static int mix(int start, int[] counts, int[] palette) {
        int r = 0, g = 0, b = 0, big = 0, n = 0;
        if (start >= 0) {
            int sr = (start >> 16) & 255, sg = (start >> 8) & 255, sb = start & 255;
            big += Math.max(sr, Math.max(sg, sb));
            r += sr;
            g += sg;
            b += sb;
            n++;
        }
        for (int i = 0; i < counts.length; i++) {
            int c = counts[i];
            if (c == 0) continue;
            int pr = (palette[i] >> 16) & 255, pg = (palette[i] >> 8) & 255, pb = palette[i] & 255;
            big += c * Math.max(pr, Math.max(pg, pb));
            r += c * pr;
            g += c * pg;
            b += c * pb;
            n += c;
        }
        if (n == 0) return 0;
        int ar = r / n, ag = g / n, ab = b / n;
        float avgMax = (float) big / (float) n;
        float maxOfAvg = (float) Math.max(ar, Math.max(ag, ab));
        // A black mix (every channel 0) would divide by zero: no dye has an armour colour of 0, but a table is the server's to write
        if (maxOfAvg == 0f) return 0;
        ar = (int) ((float) ar * avgMax / maxOfAvg);
        ag = (int) ((float) ag * avgMax / maxOfAvg);
        ab = (int) ((float) ab * avgMax / maxOfAvg);
        return (ar << 16) | (ag << 8) | ab;
    }

    // ---------- the extras' tables ----------

    /**
     * The B5 part of the extras (13.1): the 16 armour colours (each dye's {@code armor}, in the contract's dye order), the items a dye craft may colour and the
     * special kinds listed. Null from {@link #of} when the extras can't be read.
     */
    public record Tables(int[] palette, Set<String> dyeable, Set<String> special) {
        private static Extras cachedFor;
        private static Tables cached;

        public static synchronized @Nullable Tables of(@Nullable Extras extras) {
            if (extras == null) return null;
            if (extras != cachedFor) {
                cached = of(extras.json());
                cachedFor = extras;
            }
            return cached;
        }

        public static @Nullable Tables of(@Nullable JsonObject json) {
            if (json == null) return null;
            try {
                Set<String> special = new LinkedHashSet<>();
                if (json.has("special") && json.get("special").isJsonArray()) for (JsonElement e : json.getAsJsonArray("special")) special.add(e.getAsString());
                int[] palette = new int[DYES.size()];
                boolean whole = json.has("dyes") && json.get("dyes").isJsonObject();
                if (whole) {
                    JsonObject dyes = json.getAsJsonObject("dyes");
                    for (int i = 0; i < palette.length; i++) {
                        JsonElement d = dyes.get(DYES.get(i));
                        JsonElement armor = d != null && d.isJsonObject() ? d.getAsJsonObject().get("armor") : null;
                        if (armor == null || armor.isJsonNull() || !COLOR.matcher(armor.getAsString()).matches()) {
                            whole = false;
                            break;
                        }
                        palette[i] = Integer.parseInt(armor.getAsString(), 16);
                    }
                }
                Set<String> dyeable = new LinkedHashSet<>();
                // Only the six items the bot allows itself, whatever the server lists
                if (json.has("dyeable") && json.get("dyeable").isJsonArray()) for (JsonElement e : json.getAsJsonArray("dyeable")) if (e.getAsString().startsWith("minecraft:") && allowed(e.getAsString())) dyeable.add(e.getAsString());
                return new Tables(whole ? palette : new int[0], Collections.unmodifiableSet(dyeable), Collections.unmodifiableSet(special));
            } catch (RuntimeException ex) {
                return null;
            }
        }

        /**
         * The bot can dye by these extras: the server lists {@code dyed}, gives all 16 armour colours and at least one dyeable item, and every dyeable item is
         * a plain item id (the list is written by hand on the server, so a table that holds anything else is not trusted).
         */
        public boolean can() {
            return special.contains(KIND) && palette.length == DYES.size() && !dyeable.isEmpty();
        }

        public boolean dyes(String itemId) {
            return dyeable.contains(itemId);
        }
    }

    /** Whether a spec on an item of this id is a dyed spec of a dyeable item. */
    public static boolean fitsItem(@Nullable String spec, String id, @Nullable Tables t) {
        return rgbOf(spec) >= 0 && t != null && t.dyes(id);
    }

    // ---------- a dye cell ----------

    /** The index of a dye in the contract's dye order from its item id ({@code minecraft:red_dye}), or -1. */
    static int dyeIndex(@Nullable String id) {
        if (id == null || !id.startsWith("minecraft:") || !id.endsWith("_dye")) return -1;
        return DYES.indexOf(id.substring("minecraft:".length(), id.length() - "_dye".length()));
    }

    /** The grid of a dyeing step for these dyes: the item in cell 0 (with its first-coat spec, or none), then the dyes in palette order, repeats kept. */
    public static List<Order.Cell> grid(String itemId, @Nullable String midSpec, int[] counts) {
        List<Order.Cell> out = new ArrayList<>();
        out.add(new Order.Cell(itemId, midSpec));
        for (int i = 0; i < counts.length; i++) for (int k = 0; k < counts[i]; k++) out.add(new Order.Cell("minecraft:" + DYES.get(i) + "_dye", null));
        while (out.size() < GRID) out.add(null);
        return out;
    }

    // ---------- the check of a step ----------

    /**
     * Checks a dyeing step against the bot's own extras (CRAFT-CONTRACT-v1.2.md 11.1 and 15, B5): the bot's {@code can} has {@code dyed} and its extras list
     * it; {@code spec} is a canonical {@code dyed:} spec; the result is a dyeable item of the extras and one is made a fill; the grid has nine cells, the
     * item in cell 0 (undyed, or with a canonical {@code dyed:} first-coat spec and no key), then 1 to 8 dyes in the contract's dye order, then empty cells;
     * the inputs are the grid's cells times the fills (an input with a {@code dyed:} spec names no key: it is the result of an earlier step, never stock);
     * and the colour the bot works out for the grid ({@link #mix}, from its own armour colours and the first coat's colour) is the colour of the spec. A
     * second coat takes at most 4 dyes.
     * @return null when it passes, else why not
     */
    public static @Nullable String checkStep(Order.CraftStep s, @Nullable Tables t, Set<String> can) {
        if (!s.recipe().equals(RECIPE_DYED)) return "recipe " + s.recipe() + " is not the dyeing recipe";
        if (t == null || !t.can()) return "recipe " + s.recipe() + " dyes, and this bot's extras don't list it";
        if (!can.contains(FEATURE)) return "recipe " + s.recipe() + " needs dyed, which this bot can't do";
        if (s.furnace()) return "recipe " + s.recipe() + " is not a furnace recipe";
        int rgb = rgbOf(s.spec());
        if (rgb < 0) return "recipe " + s.recipe() + " has no usable dyed spec";
        if (!t.dyes(s.result())) return "recipe " + s.recipe() + " dyes " + s.result() + ", which is not dyeable by this bot's extras";
        if (s.count() != 1) return "recipe " + s.recipe() + " makes " + s.count() + " a fill here, not 1";
        if (s.fills() < 1) return "recipe " + s.recipe() + " has no fills";
        List<Order.Cell> grid = s.grid();
        if (grid == null || grid.size() != GRID) return "recipe " + s.recipe() + " has a grid of " + (grid == null ? 0 : grid.size()) + " cells, not " + GRID;
        Order.Cell base = grid.get(0);
        if (base == null || !base.id().equals(s.result())) return "recipe " + s.recipe() + " has the wrong item in slot 1";
        int start = -1;
        if (base.spec() != null) {
            start = rgbOf(base.spec());
            if (start < 0) return "recipe " + s.recipe() + " has a first coat that is not a canonical dyed spec";
        }
        int[] counts = new int[DYES.size()];
        int n = 0, last = -1;
        boolean gap = false;
        for (int i = 1; i < GRID; i++) {
            Order.Cell c = grid.get(i);
            if (c == null) {
                gap = true;
                continue;
            }
            if (gap) return "recipe " + s.recipe() + " has a gap before slot " + (i + 1);
            int dye = dyeIndex(c.id());
            if (dye < 0 || c.spec() != null) return "recipe " + s.recipe() + " has no dye in slot " + (i + 1);
            if (dye < last) return "recipe " + s.recipe() + " has its dyes out of the contract's order in slot " + (i + 1);
            last = dye;
            counts[dye]++;
            n++;
        }
        if (n < 1 || n > MAX_DYES) return "recipe " + s.recipe() + " has " + n + " dyes in one craft (1 to " + MAX_DYES + ")";
        if (start >= 0 && n > SECOND_COAT) return "recipe " + s.recipe() + " puts " + n + " dyes on a dyed item (at most " + SECOND_COAT + ")";
        int got = mix(start, counts, t.palette());
        if (got != rgb) return "recipe " + s.recipe() + " mixes " + specOf(got) + " here, not " + s.spec();
        // What goes in is what the fills take: every cell once per fill; the first coat is no stock, so its input names no key
        Map<String, Long> cells = new LinkedHashMap<>();
        for (Order.Cell c : grid) if (c != null) cells.merge(c.ref(), (long) s.fills(), Long::sum);
        Map<String, Long> in = new LinkedHashMap<>();
        for (Order.Qty q : s.inputs()) {
            if (q.key() != null) return "recipe " + s.recipe() + " takes an input by a key (a dyed item is never a base, and no stock is named)";
            in.merge(q.stockRef(), (long) q.count(), Long::sum);
        }
        if (!cells.equals(in)) return "recipe " + s.recipe() + " has inputs that are not its grid times its fills";
        if (!s.remainder().isEmpty()) return "recipe " + s.recipe() + " has a remainder, which it doesn't leave";
        return null;
    }

    // ---------- the chain of coats ----------

    /** The step is a dyeing step whose first cell is the first coat of an earlier step (a second coat). */
    static boolean isSecondCoat(Order.CraftStep s) {
        return isRecipe(s.recipe()) && s.grid() != null && !s.grid().isEmpty() && s.grid().get(0) != null && s.grid().get(0).spec() != null;
    }

    /** The number of dyes in a dyeing step's grid (cells 1 to 8). */
    static int dyesOf(Order.CraftStep s) {
        int n = 0;
        if (s.grid() != null) for (int i = 1; i < s.grid().size(); i++) if (s.grid().get(i) != null) n++;
        return n;
    }

    /** How many of a pseudo id a dyeing step takes as the first coat: its fills. */
    static long coatsTaken(Order.CraftStep s) {
        return isSecondCoat(s) ? s.fills() : 0;
    }

    /**
     * The chain of coats of a block (CRAFT-CONTRACT-v1.2.md B5): the item in cell 0 of a second coat is the result of an earlier step of the same block, never
     * stock and never a take. The steps are walked in order; a dyeing step adds {@code fills} items of its colour to what the earlier steps made, and a second
     * coat uses {@code fills} of them. A second coat that finds fewer than it needs is refused, and so is a first coat of more than 3 dyes. What is left
     * after the last step is the order's own lines (a colour made and delivered): the coverage of the lines is judged by {@link CraftOrder#check}.
     * @return null when every coat has its item, else why not
     */
    public static @Nullable String checkChain(Order.Craft craft) {
        // Per pseudo id: the items made so far by dyeing steps, the ones from at most 3 dyes first (what a first coat may be)
        Map<String, ArrayDeque<long[]>> made = new HashMap<>();
        int n = 0;
        for (Order.CraftStep s : craft.steps()) {
            n++;
            if (!isRecipe(s.recipe())) continue;
            if (isSecondCoat(s)) {
                String ref = s.grid().get(0).ref();
                long need = s.fills();
                ArrayDeque<long[]> have = made.get(ref);
                // Smaller first coats first: a colour that was made both ways uses the first coat that is one
                if (have != null) {
                    List<long[]> sorted = new ArrayList<>(have);
                    sorted.sort((a, b) -> Long.compare(a[1], b[1]));
                    for (long[] batch : sorted) {
                        if (need <= 0) break;
                        long use = Math.min(need, batch[0]);
                        if (use <= 0) continue;
                        if (batch[1] > FIRST_COAT) continue;
                        batch[0] -= use;
                        need -= use;
                    }
                }
                if (need > 0) return "step " + n + " has no first coat (at most " + FIRST_COAT + " dyes) made before it for " + s.fills() + " " + Recipes.pretty(ref);
            }
            // A first coat is put on an undyed item: what a second coat made is no first coat (three coats don't exist)
            made.computeIfAbsent(s.resultRef(), k -> new ArrayDeque<>()).add(new long[] {s.fills(), isSecondCoat(s) ? Integer.MAX_VALUE : dyesOf(s)});
        }
        return null;
    }

    /**
     * How many of each pseudo id the second coats of a block use up (per ref): they are made by dyeing steps but are not what the order delivers, so the lines
     * are covered by what the steps made less this.
     */
    public static Map<String, Long> coatsUsed(Order.Craft craft) {
        Map<String, Long> out = new HashMap<>();
        for (Order.CraftStep s : craft.steps()) {
            long n = coatsTaken(s);
            if (n > 0) out.merge(s.grid().get(0).ref(), n, Long::sum);
        }
        return out;
    }
}
