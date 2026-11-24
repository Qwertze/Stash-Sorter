package com.porter.qwertestash.orders;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwertestash.manager.Extras;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Tipped arrows, suspicious stew, copies of banners, maps and written books, and shields with a banner, as the order contract names them
 * (CRAFT-CONTRACT-v1.2.md sections 3, 4, 9, 13 and 15 and the B4 clarifications, milestone B4): the specs {@code tipped:}, {@code stew:}, {@code copy:} and
 * {@code shield:}, the grid each special recipe fills, and the check of a step against the bot's own extras. Written by hand from the contract; nothing is
 * read from game files. Plain data and functions, no game types; any thread.
 * <ul>
 *     <li>A step ({@code qwerte:special/tipped}, {@code .../stew}, {@code .../banner_copy}, {@code .../map_copy}, {@code .../book_copy},
 *     {@code .../shield}) carries the nine cells of the crafting grid, row major. The bot builds the grid it expects from the step's spec and its own
 *     extras, compares it cell by cell and refuses the block ({@code recipe_mismatch}) when it differs.</li>
 *     <li>The source of a copy (a banner or a book that stays, a map that is used up) is a cell with the spec {@code copy:<key>}. A written book's
 *     source is a different stack from the copies it makes (generation 0 against 1): internally it is the pseudo id {@code written_book@src:<key>}
 *     ({@link #sourceRef}), the copies are {@code written_book@copy:<key>}. A banner's or map's copy is the very item of the key.</li>
 * </ul>
 */
public final class VariantSpecs {
    private VariantSpecs() {
    }

    public static final String ARROW = "minecraft:arrow", TIPPED_ARROW = "minecraft:tipped_arrow", LINGERING_POTION = "minecraft:lingering_potion",
        STEW = "minecraft:suspicious_stew", BOWL = "minecraft:bowl", BROWN_MUSHROOM = "minecraft:brown_mushroom", RED_MUSHROOM = "minecraft:red_mushroom",
        SHIELD = "minecraft:shield", EMPTY_MAP = "minecraft:map", FILLED_MAP = "minecraft:filled_map", WRITTEN_BOOK = "minecraft:written_book",
        WRITABLE_BOOK = "minecraft:writable_book";
    public static final String RECIPE_TIPPED = "qwerte:special/tipped", RECIPE_STEW = "qwerte:special/stew", RECIPE_BANNER_COPY = "qwerte:special/banner_copy",
        RECIPE_MAP_COPY = "qwerte:special/map_copy", RECIPE_BOOK_COPY = "qwerte:special/book_copy", RECIPE_SHIELD = "qwerte:special/shield";
    /** Arrows one tipped-arrow craft makes (8 arrows around the potion). [UNVERIFIED in game] */
    public static final int TIPPED_COUNT = 8;
    /** The most blank maps or writable books one copy craft takes. */
    public static final int COPY_BATCH = 8;
    public static final int GRID = 9, MAX_SPEC = 2048, MAX_TICKS = 1_000_000;

    private static final String ID = "[a-z0-9_.-]+:[a-z0-9_./-]{1,128}";
    private static final Pattern ID_P = Pattern.compile("^" + ID + "$"), KEY_P = Pattern.compile("^(" + ID + ")#[0-9a-f]{1,16}$"), TICKS_P = Pattern.compile("^[1-9][0-9]{0,6}$");

    // ---------- the extras' tables ----------

    /** The small flowers of Minecraft 1.21.4 (what a suspicious stew takes), written by hand. */
    public static final Set<String> SMALL_FLOWERS = Set.of("minecraft:allium", "minecraft:azure_bluet", "minecraft:blue_orchid", "minecraft:cornflower", "minecraft:dandelion",
        "minecraft:lily_of_the_valley", "minecraft:oxeye_daisy", "minecraft:poppy", "minecraft:torchflower", "minecraft:orange_tulip", "minecraft:pink_tulip",
        "minecraft:red_tulip", "minecraft:white_tulip", "minecraft:wither_rose", "minecraft:open_eyeblossom", "minecraft:closed_eyeblossom");

    /** The mob effect ids of Minecraft 1.21.4, written by hand. */
    public static final Set<String> EFFECTS;

    static {
        Set<String> e = new LinkedHashSet<>();
        for (String n : new String[] {"speed", "slowness", "haste", "mining_fatigue", "strength", "instant_health", "instant_damage", "jump_boost", "nausea", "regeneration",
            "resistance", "fire_resistance", "water_breathing", "invisibility", "blindness", "night_vision", "hunger", "weakness", "poison", "wither", "health_boost",
            "absorption", "saturation", "glowing", "levitation", "luck", "unluck", "slow_falling", "conduit_power", "dolphins_grace", "bad_omen", "hero_of_the_village",
            "darkness", "trial_omen", "raid_omen", "wind_charged", "weaving", "oozing", "infested"}) e.add("minecraft:" + n);
        EFFECTS = Collections.unmodifiableSet(e);
    }

    /** What one flower gives a suspicious stew: the effect id and its duration in game ticks. */
    public record Stew(String effect, int ticks) {
    }

    /**
     * The B4 part of the extras (13.1): which special kinds the server plans and the stew table (flower to effect and duration). Never null for a block of
     * extras; empty when the server lists none.
     */
    public record Tables(Set<String> special, Map<String, Stew> stew) {
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
                Map<String, Stew> stew = new TreeMap<>();
                if (json.has("stew") && json.get("stew").isJsonObject()) {
                    for (var f : json.getAsJsonObject("stew").entrySet()) {
                        JsonObject v = f.getValue().getAsJsonObject();
                        long ticks = v.get("ticks").getAsLong();
                        if (ticks < 1 || ticks > MAX_TICKS) continue;
                        stew.put(f.getKey(), new Stew(v.get("effect").getAsString(), (int) ticks));
                    }
                }
                return new Tables(Collections.unmodifiableSet(special), Collections.unmodifiableMap(stew));
            } catch (RuntimeException ex) {
                return null;
            }
        }

        /** The bot can make tipped arrows by these extras: the server lists {@code tipped}. */
        public boolean canTipped() {
            return special.contains("tipped");
        }

        /**
         * The server lists {@code stew} and gives at least one flower, and every key of its table is one of the small flowers and every effect one of the game's
         * (the two lists written here by hand): a table with anything else is not trusted.
         */
        public boolean canStew() {
            if (!special.contains("stew") || stew.isEmpty()) return false;
            for (var e : stew.entrySet()) if (!SMALL_FLOWERS.contains(e.getKey()) || !EFFECTS.contains(e.getValue().effect())) return false;
            return true;
        }

        /** The server lists all four copy kinds: {@code banner_copy}, {@code map_copy}, {@code book_copy} and {@code shield}. */
        public boolean canCopy() {
            return special.contains("banner_copy") && special.contains("map_copy") && special.contains("book_copy") && special.contains("shield");
        }

        /** The flowers that give this effect and duration, sorted. */
        public List<String> flowersOf(String effect, int ticks) {
            List<String> out = new ArrayList<>();
            for (var e : stew.entrySet()) if (e.getValue().effect().equals(effect) && e.getValue().ticks() == ticks) out.add(e.getKey());
            return out;
        }
    }

    // ---------- the grammar (section 3) ----------

    /** A tipped, stew, copy or shield spec read back: {@code id} is the potion or the effect, {@code ticks} the stew's duration, {@code key} a copy's or shield's index key. */
    public record Spec(String kind, @Nullable String id, int ticks, @Nullable String key) {
        public String canonical() {
            return switch (kind) {
                case "tipped" -> "tipped:" + id;
                case "stew" -> "stew:" + id + "/" + ticks;
                default -> kind + ":" + key;
            };
        }

        /** The item id of a copy's or shield's key (the part before the {@code #}). */
        public @Nullable String keyId() {
            return key == null ? null : key.substring(0, key.indexOf('#'));
        }

        /** The bot feature (craftOrders.can) the kind needs: tipped, stew or copy (a shield is a copy feature too). */
        public String feature() {
            return switch (kind) {
                case "tipped" -> "tipped";
                case "stew" -> "stew";
                default -> "copy";
            };
        }
    }

    /** The spec as a value, or null when it is not a canonical tipped, stew, copy or shield spec of the grammar. */
    public static @Nullable Spec parse(@Nullable String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_SPEC) return null;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) <= 0x20 || s.charAt(i) >= 0x7f) return null;
        if (s.startsWith("tipped:")) {
            String id = s.substring("tipped:".length());
            return ID_P.matcher(id).matches() ? new Spec("tipped", id, 0, null) : null;
        }
        if (s.startsWith("stew:")) {
            String rest = s.substring("stew:".length());
            int slash = rest.lastIndexOf('/');
            if (slash < 1) return null;
            String id = rest.substring(0, slash), ticks = rest.substring(slash + 1);
            if (!ID_P.matcher(id).matches() || !TICKS_P.matcher(ticks).matches() || Integer.parseInt(ticks) > MAX_TICKS) return null;
            return new Spec("stew", id, Integer.parseInt(ticks), null);
        }
        for (String kind : new String[] {"copy", "shield"}) {
            if (!s.startsWith(kind + ":")) continue;
            String key = s.substring(kind.length() + 1);
            return KEY_P.matcher(key).matches() ? new Spec(kind, null, 0, key) : null;
        }
        return null;
    }

    /** The feature a spec needs of the bot ({@code firework}, {@code tipped}, {@code stew} or {@code copy}), or null for a spec that is of no known kind. */
    public static @Nullable String featureOf(@Nullable String spec) {
        if (spec == null) return null;
        if (spec.startsWith("rocket:") || spec.startsWith("star:")) return "firework";
        if (spec.startsWith("tipped:")) return "tipped";
        if (spec.startsWith("stew:")) return "stew";
        if (spec.startsWith("copy:") || spec.startsWith("shield:")) return "copy";
        return null;
    }

    /** Whether a spec of this kind may name this item as the variant itself (a line, a step's result): the kind and the item must match (section 3). */
    public static boolean fitsItem(Spec s, String id) {
        return switch (s.kind()) {
            case "tipped" -> id.equals(TIPPED_ARROW);
            case "stew" -> id.equals(STEW);
            case "shield" -> id.equals(SHIELD) && isBanner(s.keyId());
            default -> id.equals(s.keyId()) && copyable(id);
        };
    }

    /** Whether a take, input or leftover entry of this item may carry this spec: a lingering potion with the potion's spec is fine too. */
    public static boolean fitsEntry(Spec s, String id) {
        if (s.kind().equals("tipped")) return id.equals(TIPPED_ARROW) || id.equals(LINGERING_POTION);
        // A shield is made, never taken or used with a shield spec; a banner or map or book of a key carries copy:<key>
        if (s.kind().equals("shield")) return id.equals(SHIELD);
        return fitsItem(s, id);
    }

    public static boolean isBanner(@Nullable String id) {
        return id != null && id.startsWith("minecraft:") && id.endsWith("_banner");
    }

    /** A banner, a filled map or a written book. */
    public static boolean copyable(@Nullable String id) {
        return FILLED_MAP.equals(id) || WRITTEN_BOOK.equals(id) || isBanner(id);
    }

    // ---------- specs derived from items ----------

    /** {@code tipped:minecraft:<potion>} for a potion registry name such as {@code long_poison}, or null. */
    public static @Nullable String tippedSpec(@Nullable String potion) {
        if (potion == null) return null;
        String id = potion.indexOf(':') < 0 ? "minecraft:" + potion : potion;
        return ID_P.matcher(id).matches() ? "tipped:" + id : null;
    }

    /** {@code stew:<effect>/<ticks>} for an effect id and a duration in ticks, or null when either is outside the grammar. */
    public static @Nullable String stewSpec(@Nullable String effect, long ticks) {
        if (effect == null || ticks < 1 || ticks > MAX_TICKS) return null;
        String id = effect.indexOf(':') < 0 ? "minecraft:" + effect : effect;
        return ID_P.matcher(id).matches() ? "stew:" + id + "/" + ticks : null;
    }

    /** A short description for a log line: "long poison", "night vision 5 s", "a copy", "the source", "with a banner". Never a key. */
    public static String describe(String spec) {
        if (spec.startsWith("src:")) return "the original";
        Spec sp = parse(spec);
        if (sp == null) return spec;
        return switch (sp.kind()) {
            case "tipped" -> name(sp.id());
            case "stew" -> name(sp.id()) + " " + (sp.ticks() % 20 == 0 ? Integer.toString(sp.ticks() / 20) : String.format(java.util.Locale.ROOT, "%.2f", sp.ticks() / 20.0)) + " s";
            case "shield" -> "with a banner";
            default -> "a copy";
        };
    }

    private static String name(@Nullable String id) {
        if (id == null) return "";
        return (id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id).replace('_', ' ');
    }

    private static final List<String> BANNER_COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
        "purple", "blue", "brown", "green", "red", "black");

    /** The colour id of a banner's item id ({@code minecraft:red_banner} is 14), by the game's colour order (written by hand); -1 for anything else. */
    public static int bannerColor(String id) {
        String c = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        if (!c.endsWith("_banner")) return -1;
        return BANNER_COLORS.indexOf(c.substring(0, c.length() - "_banner".length()));
    }

    // ---------- pseudo ids ----------

    /** A written book held as the source of a copy (generation 0), as the planner calls it: {@code minecraft:written_book@src:<key>}. */
    public static String sourceRef(String id, @Nullable String spec) {
        if (WRITTEN_BOOK.equals(id) && spec != null && spec.startsWith("copy:")) return Pseudo.of(id, "src:" + spec.substring("copy:".length()));
        return Pseudo.of(id, spec);
    }

    /** The key a pseudo id of a copy source or a copy names ({@code @copy:<key>} or {@code @src:<key>}), or null. */
    public static @Nullable String keyOfRef(String ref) {
        String spec = Pseudo.spec(ref);
        if (spec == null) return null;
        if (spec.startsWith("copy:")) return spec.substring("copy:".length());
        if (spec.startsWith("src:")) return spec.substring("src:".length());
        return null;
    }

    // ---------- the recipes and their grids (section 15) ----------

    public static boolean isRecipe(String recipe) {
        return recipe.equals(RECIPE_TIPPED) || recipe.equals(RECIPE_STEW) || recipe.equals(RECIPE_BANNER_COPY) || recipe.equals(RECIPE_MAP_COPY)
            || recipe.equals(RECIPE_BOOK_COPY) || recipe.equals(RECIPE_SHIELD);
    }

    /** The recipe (or a plan id, {@link FireworkSpecs#planId}) is one of milestone B4's: its cells that are plain items take plain stacks only. */
    public static boolean isB4(String recipeOrPlanId) {
        int hash = recipeOrPlanId.indexOf('#');
        return isRecipe(hash < 0 ? recipeOrPlanId : recipeOrPlanId.substring(0, hash));
    }

    private static String baseRecipe(String recipeOrPlanId) {
        int hash = recipeOrPlanId.indexOf('#');
        return hash < 0 ? recipeOrPlanId : recipeOrPlanId.substring(0, hash);
    }

    /** How many stacks of a copy stay in the grid after each craft: the source banner or book (a map is used up). */
    public static int remainderCells(String recipeOrPlanId) {
        String r = baseRecipe(recipeOrPlanId);
        return r.equals(RECIPE_BANNER_COPY) || r.equals(RECIPE_BOOK_COPY) ? 1 : 0;
    }

    /** The recipe copies something and its source stays or is used up: a banner, map or book copy. */
    public static boolean isCopy(String recipeOrPlanId) {
        String r = baseRecipe(recipeOrPlanId);
        return r.equals(RECIPE_BANNER_COPY) || r.equals(RECIPE_MAP_COPY) || r.equals(RECIPE_BOOK_COPY);
    }

    /**
     * Items one craft of this step really adds to what the bot carries: what it makes, less the source map it uses up (a map copy makes {@code k + 1} from
     * the source and {@code k} blanks, so it adds {@code k}). Other recipes add what they make.
     */
    public static int netCount(String recipeOrPlanId, int count) {
        return baseRecipe(recipeOrPlanId).equals(RECIPE_MAP_COPY) ? Math.max(0, count - 1) : count;
    }

    private static Order.Cell cell(String id) {
        return new Order.Cell(id, null);
    }

    /**
     * The cells the grid of a step of this recipe has for this spec, in order, each as the alternatives it may be (a stew's flower has one per flower that
     * gives the effect; one grid holds one). Null when the recipe doesn't make the spec's kind, or the tables don't have what it needs.
     * <ul>
     *     <li>tipped: 8 arrows around the lingering potion of the potion in cell 4;</li>
     *     <li>stew: a bowl, a brown mushroom, a red mushroom, then the flower;</li>
     *     <li>shield: a plain shield, then the banner of the key (consumed);</li>
     *     <li>banner copy: the source banner of the key, then a blank banner of the same colour;</li>
     *     <li>map copy: the source map, then {@code blanks} empty maps; book copy: the original, then {@code blanks} writable books.</li>
     * </ul>
     * @param blanks the number of blank maps or writable books (1 to 8) of a map or book copy; ignored by the other recipes
     */
    public static @Nullable List<List<Order.Cell>> expected(String recipe, Spec sp, Tables t, int blanks) {
        List<List<Order.Cell>> out = new ArrayList<>();
        switch (recipe) {
            case RECIPE_TIPPED -> {
                if (!sp.kind().equals("tipped") || !t.canTipped()) return null;
                for (int i = 0; i < GRID; i++) out.add(List.of(i == 4 ? new Order.Cell(LINGERING_POTION, sp.canonical()) : cell(ARROW)));
            }
            case RECIPE_STEW -> {
                if (!sp.kind().equals("stew") || !t.canStew()) return null;
                List<String> flowers = t.flowersOf(sp.id(), sp.ticks());
                if (flowers.isEmpty()) return null;
                out.add(List.of(cell(BOWL)));
                out.add(List.of(cell(BROWN_MUSHROOM)));
                out.add(List.of(cell(RED_MUSHROOM)));
                List<Order.Cell> alts = new ArrayList<>();
                for (String f : flowers) alts.add(cell(f));
                out.add(alts);
            }
            case RECIPE_SHIELD -> {
                if (!sp.kind().equals("shield") || !t.special().contains("shield") || !isBanner(sp.keyId())) return null;
                out.add(List.of(cell(SHIELD)));
                out.add(List.of(new Order.Cell(sp.keyId(), "copy:" + sp.key())));
            }
            case RECIPE_BANNER_COPY -> {
                if (!sp.kind().equals("copy") || !t.special().contains("banner_copy") || !isBanner(sp.keyId()) || blanks != 1) return null;
                out.add(List.of(new Order.Cell(sp.keyId(), sp.canonical())));
                out.add(List.of(cell(sp.keyId())));
            }
            case RECIPE_MAP_COPY, RECIPE_BOOK_COPY -> {
                boolean map = recipe.equals(RECIPE_MAP_COPY);
                if (!sp.kind().equals("copy") || !t.special().contains(map ? "map_copy" : "book_copy") || !sp.keyId().equals(map ? FILLED_MAP : WRITTEN_BOOK)) return null;
                if (blanks < 1 || blanks > COPY_BATCH) return null;
                out.add(List.of(new Order.Cell(sp.keyId(), sp.canonical())));
                for (int i = 0; i < blanks; i++) out.add(List.of(cell(map ? EMPTY_MAP : WRITABLE_BOOK)));
            }
            default -> {
                return null;
            }
        }
        return out;
    }

    /** What one craft of the recipe makes: 8 tipped arrows, 1 stew, 1 shield, 1 banner copy, {@code k + 1} maps or {@code k} books for {@code k} blanks. */
    public static int countOf(String recipe, int blanks) {
        return switch (recipe) {
            case RECIPE_TIPPED -> TIPPED_COUNT;
            case RECIPE_MAP_COPY -> blanks + 1;
            case RECIPE_BOOK_COPY -> blanks;
            default -> 1;
        };
    }

    /** The item a step of this recipe makes, for the spec. */
    private static @Nullable String resultOf(String recipe, Spec sp) {
        return switch (recipe) {
            case RECIPE_TIPPED -> TIPPED_ARROW;
            case RECIPE_STEW -> STEW;
            case RECIPE_SHIELD -> SHIELD;
            default -> sp.keyId();
        };
    }

    /** What a step's recipe asks of the bot: the feature in {@code can} and the {@code special} kind of the extras. */
    private static String specialKind(String recipe) {
        return switch (recipe) {
            case RECIPE_TIPPED -> "tipped";
            case RECIPE_STEW -> "stew";
            case RECIPE_SHIELD -> "shield";
            case RECIPE_BANNER_COPY -> "banner_copy";
            case RECIPE_MAP_COPY -> "map_copy";
            default -> "book_copy";
        };
    }

    /**
     * Checks a B4 step against the bot's own tables (CRAFT-CONTRACT-v1.2.md 11.1 and 15): the recipe is one of the six and the bot's extras list it; {@code spec}
     * is a canonical spec of the recipe's kind for the step's result item; the count is what the recipe makes; the grid has nine entries and its cells are
     * exactly the ones the spec asks for, in order, then empty; the inputs are the grid's cells times the fills, and the remainder is the source banner or
     * book once per fill (none for any other recipe).
     * @return null when it passes, else why not
     */
    public static @Nullable String checkStep(Order.CraftStep s, @Nullable Tables t, Set<String> can) {
        if (!isRecipe(s.recipe())) return "recipe " + s.recipe() + " is not a special recipe of this bot";
        if (t == null) return "recipe " + s.recipe() + " is a special recipe, and this bot has no extras for it";
        if (s.furnace()) return "recipe " + s.recipe() + " is not a furnace recipe";
        Spec sp = parse(s.spec());
        if (sp == null) return "recipe " + s.recipe() + " has no usable spec";
        if (!can.contains(sp.feature())) return "recipe " + s.recipe() + " needs " + sp.feature() + ", which this bot can't do";
        if (!t.special().contains(specialKind(s.recipe()))) return "recipe " + s.recipe() + " is not listed by this bot's extras";
        String result = resultOf(s.recipe(), sp);
        if (result == null || !s.result().equals(result) || !fitsItem(sp, result)) return "recipe " + s.recipe() + " makes " + s.result() + ", not " + result;
        if (s.fills() < 1) return "recipe " + s.recipe() + " has no fills";
        List<Order.Cell> grid = s.grid();
        if (grid == null || grid.size() != GRID) return "recipe " + s.recipe() + " has a grid of " + (grid == null ? 0 : grid.size()) + " cells, not " + GRID;
        int blanks = 0;
        for (int i = 1; i < GRID; i++) if (grid.get(i) != null) blanks++;
        boolean copy = s.recipe().equals(RECIPE_MAP_COPY) || s.recipe().equals(RECIPE_BOOK_COPY);
        if (copy && (blanks < 1 || blanks > COPY_BATCH)) return "recipe " + s.recipe() + " has " + blanks + " blanks in one craft (1 to " + COPY_BATCH + ")";
        int count = countOf(s.recipe(), blanks);
        if (s.count() != count) return "recipe " + s.recipe() + " makes " + s.count() + " a craft here, not " + count;
        List<List<Order.Cell>> want = expected(s.recipe(), sp, t, copy ? blanks : 1);
        if (want == null) return "recipe " + s.recipe() + " can't make " + s.spec() + " by this bot's extras";
        for (int i = 0; i < GRID; i++) {
            Order.Cell c = grid.get(i);
            if (i >= want.size()) {
                if (c != null) return "recipe " + s.recipe() + " has a cell in slot " + (i + 1) + " that its spec doesn't call for";
                continue;
            }
            if (c == null || !want.get(i).contains(c)) return "recipe " + s.recipe() + " has the wrong item in slot " + (i + 1);
        }
        // What goes in is what the fills take: every cell once per fill (a copy lists its source once per craft)
        Map<String, Long> cells = new LinkedHashMap<>();
        for (Order.Cell c : grid) if (c != null) cells.merge(c.ref(), (long) s.fills(), Long::sum);
        Map<String, Long> in = new LinkedHashMap<>();
        for (Order.Qty q : s.inputs()) in.merge(q.stockRef(), (long) q.count(), Long::sum);
        if (!cells.equals(in)) return "recipe " + s.recipe() + " has inputs that are not its grid times its fills";
        // What stays in the grid and is handed back: the source of a banner or book copy, once per craft
        Map<String, Long> rem = new LinkedHashMap<>();
        for (Order.Qty q : s.remainder()) {
            if (q.count() < 1) return "recipe " + s.recipe() + " has an empty remainder";
            rem.merge(q.stockRef(), (long) q.count(), Long::sum);
        }
        Map<String, Long> wantRem = new LinkedHashMap<>();
        if (remainderCells(s.recipe()) > 0) wantRem.put(grid.get(0).ref(), (long) s.fills());
        if (!rem.equals(wantRem)) return "recipe " + s.recipe() + " has a remainder that is not its source once per craft";
        return null;
    }

    // ---------- copy families: how the sub-plan reasons about a copy ----------

    /** The kinds of copy. */
    public enum CopyKind { BANNER, MAP, BOOK }

    /**
     * A banner, map or book copy of one key as the block's steps name it: what is delivered ({@code product}), what is held while copying ({@code source}:
     * the same ref for a banner or map, {@code written_book@src:<key>} for a book), the blank item, the most blanks one craft takes, and the recipe and spec.
     * The sub-plan makes the crafts itself from this (the number of blanks per craft is its own to cut), with the same grids the block's steps have.
     */
    public record CopyFamily(CopyKind kind, String product, String source, String blank, int batch, String recipe, String spec, String keyId, String key) {
        /** The source stays in the grid (banner, book); a map's source is used up. */
        public boolean sourceStays() {
            return kind != CopyKind.MAP;
        }

        /** What a craft with {@code k} blanks makes. */
        public int count(int k) {
            return countOf(recipe, k);
        }

        /** The grid of a craft with {@code k} blanks: the source, then the blanks, then empty cells. */
        public List<Order.Cell> grid(int k) {
            List<Order.Cell> g = new ArrayList<>();
            g.add(new Order.Cell(keyId, spec));
            for (int i = 0; i < k; i++) g.add(cell(blank));
            while (g.size() < GRID) g.add(null);
            return g;
        }

        /** The step of one such craft, as the block writes it: it names the recipe the sub-plan knows it by ({@link FireworkSpecs#planId}). */
        public Order.CraftStep step(int k, int fills) {
            List<Order.Cell> g = grid(k);
            Map<String, Long> in = new LinkedHashMap<>();
            for (Order.Cell c : g) if (c != null) in.merge(c.ref(), (long) fills, Long::sum);
            List<Order.Qty> inputs = new ArrayList<>();
            for (var e : in.entrySet()) inputs.add(new Order.Qty(Pseudo.base(e.getKey()), (int) (long) e.getValue(), null, Pseudo.spec(e.getKey())));
            return new Order.CraftStep(recipe, keyId, count(k), fills, inputs, false, List.of(), spec, g, List.of());
        }

        public Recipes.Recipe recipeFor(int k) {
            return FireworkSpecs.recipeOf(step(k, 1));
        }
    }

    /**
     * The copy families of a block: one per copy spec its steps name, keyed by the product's pseudo id. Empty for a block with no copy step; a step that
     * is not well formed is left to {@link #checkStep} (the block is refused before anything is planned).
     */
    public static Map<String, CopyFamily> families(Order.Craft craft) {
        Map<String, CopyFamily> out = new LinkedHashMap<>();
        for (Order.CraftStep s : craft.steps()) {
            if (!isCopy(s.recipe()) || s.grid() == null || s.grid().size() != GRID) continue;
            Spec sp = parse(s.spec());
            if (sp == null || !sp.kind().equals("copy") || s.grid().get(0) == null || s.grid().get(1) == null) continue;
            CopyKind kind = s.recipe().equals(RECIPE_BANNER_COPY) ? CopyKind.BANNER : s.recipe().equals(RECIPE_MAP_COPY) ? CopyKind.MAP : CopyKind.BOOK;
            String id = sp.keyId();
            String product = Pseudo.of(id, sp.canonical());
            String source = kind == CopyKind.BOOK ? sourceRef(id, sp.canonical()) : product;
            out.putIfAbsent(product, new CopyFamily(kind, product, source, s.grid().get(1).id(), kind == CopyKind.BANNER ? 1 : COPY_BATCH, s.recipe(), sp.canonical(), id, sp.key()));
        }
        return out;
    }
}
