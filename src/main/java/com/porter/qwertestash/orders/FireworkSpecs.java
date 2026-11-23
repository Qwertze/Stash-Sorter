package com.porter.qwertestash.orders;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwertestash.manager.Extras;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Firework rockets and stars as the order contract names them (CRAFT-CONTRACT-v1.2.md sections 3, 4, 13 and 15, milestone B3): the canonical spec of an
 * item's components, the grid a special recipe fills, and the check of a step's grid against the bot's own extras. Written by hand from the contract;
 * nothing is read from game files. Plain data and functions, no game types (the stack's components are turned into an {@link Explosion} list by
 * {@code stash.FireworkItems}); any thread.
 * <ul>
 *     <li>A spec is derived from the item's <em>effective</em> components: the colours are the firework colour ints of the extras' {@code dyes} table,
 *     and a value outside the grammar (a colour not in the table, a flight of 0 or 4, more than 8 explosions' worth of stars, more than 8 colours) has no
 *     spec.</li>
 *     <li>A special step ({@code qwerte:special/rocket}, {@code .../star}, {@code .../star_fade}) carries the nine cells of the crafting grid, row
 *     major. The bot builds the grid it expects from the spec and its own extras, compares it cell by cell and refuses the block ({@code recipe_mismatch})
 *     when it differs.</li>
 * </ul>
 */
public final class FireworkSpecs {
    private FireworkSpecs() {
    }

    public static final String ROCKET = "minecraft:firework_rocket", STAR = "minecraft:firework_star", PAPER = "minecraft:paper", GUNPOWDER = "minecraft:gunpowder";
    public static final String RECIPE_ROCKET = "qwerte:special/rocket", RECIPE_STAR = "qwerte:special/star", RECIPE_FADE = "qwerte:special/star_fade";
    /** Rockets one rocket craft makes. [UNVERIFIED in game] */
    public static final int ROCKET_COUNT = 3;
    public static final int GRID = 9;
    /** The most colours (and fades) one explosion has, and the most spec length (CRAFT-CONTRACT-v1.2.md 3). */
    public static final int MAX_COLORS = 8, MAX_SPEC = 2048;

    /** The shape names by the game's shape id (0 to 4). */
    public static final List<String> SHAPES = List.of("small_ball", "large_ball", "star", "creeper", "burst");
    /** The dye names of the grammar. */
    public static final List<String> DYES = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown",
        "green", "red", "black");

    private static final Set<String> HEADS = Set.of("minecraft:creeper_head", "minecraft:dragon_head", "minecraft:piglin_head", "minecraft:player_head",
        "minecraft:skeleton_skull", "minecraft:wither_skeleton_skull", "minecraft:zombie_head");
    /** The ingredients the game takes for each role of a star (hard-coded here, so the server's list can only narrow it). */
    private static final Map<String, Set<String>> ACCEPTED = Map.of("large_ball", Set.of("minecraft:fire_charge"), "star", Set.of("minecraft:gold_nugget"),
        "burst", Set.of("minecraft:feather"), "creeper", HEADS, "trail", Set.of("minecraft:diamond"), "twinkle", Set.of("minecraft:glowstone_dust"));

    /** One firework explosion as the game stores it: the shape id, the colours and fade colours (rgb ints), and the two flags. */
    public record Explosion(int shape, List<Integer> colors, List<Integer> fades, boolean trail, boolean twinkle) {
        public static Explosion of(int shape, int[] colors, int[] fades, boolean trail, boolean twinkle) {
            List<Integer> c = new ArrayList<>(), f = new ArrayList<>();
            for (int x : colors == null ? new int[0] : colors) c.add(x & 0xFFFFFF);
            for (int x : fades == null ? new int[0] : fades) f.add(x & 0xFFFFFF);
            return new Explosion(shape, Collections.unmodifiableList(c), Collections.unmodifiableList(f), trail, twinkle);
        }
    }

    // ---------- the extras' tables ----------

    /**
     * The firework tables of the extras (13.1): each dye's firework colour, the items that give a star its shape, trail and twinkle, and the special
     * recipes the server plans. Null from {@link #of} unless the extras hold all three tables.
     */
    public record Tables(Map<String, Integer> dyeColor, Map<Integer, String> dyeByColor, Map<String, List<String>> starItems, Set<String> special) {
        /** The tables of these extras, or null when they lack the {@code special} (with {@code rocket} and {@code star}), {@code dyes} or {@code starItems} table. */
        private static Extras cachedFor;
        private static Tables cached;

        public static synchronized @Nullable Tables of(@Nullable Extras extras) {
            if (extras == null) return null;
            // Cached by the identity of the extras block: the heartbeat and the index ask on every tick
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
                Map<String, Integer> byName = new LinkedHashMap<>();
                Map<Integer, String> byColor = new HashMap<>();
                if (json.has("dyes") && json.get("dyes").isJsonObject()) {
                    for (var d : json.getAsJsonObject("dyes").entrySet()) {
                        JsonObject v = d.getValue().getAsJsonObject();
                        if (!DYES.contains(d.getKey()) || !v.has("firework") || v.get("firework").isJsonNull()) continue;
                        int rgb = Integer.parseInt(v.get("firework").getAsString(), 16);
                        byName.put(d.getKey(), rgb);
                        byColor.put(rgb, d.getKey());
                    }
                }
                Map<String, List<String>> items = new LinkedHashMap<>();
                if (json.has("starItems") && json.get("starItems").isJsonObject()) {
                    for (var r : json.getAsJsonObject("starItems").entrySet()) {
                        // Only the ingredients the game accepts for the role are kept, whatever the server lists (it is written by hand)
                        Set<String> ok = ACCEPTED.get(r.getKey());
                        if (ok == null) continue;
                        List<String> ids = new ArrayList<>();
                        for (JsonElement i : r.getValue().getAsJsonArray()) if (ok.contains(i.getAsString())) ids.add(i.getAsString());
                        if (!ids.isEmpty()) items.put(r.getKey(), Collections.unmodifiableList(ids));
                    }
                }
                if (!special.contains("rocket") || !special.contains("star") || byName.isEmpty() || items.isEmpty()) return null;
                return new Tables(Collections.unmodifiableMap(byName), Collections.unmodifiableMap(byColor), Collections.unmodifiableMap(items), Collections.unmodifiableSet(special));
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }

    // ---------- deriving a spec (section 3) ----------

    /** The canonical spec of a rocket with this flight and these explosions ({@code rocket:2|small_ball;red;;}), or null when it has none (S9, S11, S12). */
    public static @Nullable String rocket(int flight, List<Explosion> explosions, Tables t) {
        if (flight < 1 || flight > 3 || explosions.size() > 8 - flight) return null;
        StringBuilder sb = new StringBuilder("rocket:").append(flight);
        for (Explosion e : explosions) {
            String body = body(e, t);
            if (body == null) return null;
            sb.append('|').append(body);
        }
        String spec = sb.toString();
        return spec.length() > MAX_SPEC ? null : spec;
    }

    /** The canonical spec of a star ({@code star:large_ball;red,blue;white;tw}), or null when it has none (S10, S11). */
    public static @Nullable String star(Explosion e, Tables t) {
        String body = body(e, t);
        return body == null ? null : "star:" + body;
    }

    private static @Nullable String body(Explosion e, Tables t) {
        if (e.shape() < 0 || e.shape() >= SHAPES.size()) return null;
        if (e.colors().isEmpty() || e.colors().size() > MAX_COLORS || e.fades().size() > MAX_COLORS) return null;
        String colors = names(e.colors(), t), fades = names(e.fades(), t);
        if (colors == null || fades == null) return null;
        return SHAPES.get(e.shape()) + ";" + colors + ";" + fades + ";" + (e.trail() ? "t" : "") + (e.twinkle() ? "w" : "");
    }

    private static @Nullable String names(List<Integer> colors, Tables t) {
        List<String> out = new ArrayList<>();
        for (int c : colors) {
            String name = t.dyeByColor().get(c & 0xFFFFFF);
            if (name == null) return null;
            out.add(name);
        }
        return String.join(",", out);
    }

    // ---------- the stack's data, as the stash saves it ----------

    /**
     * What the stash keeps of a rocket or star so its spec can be derived later, whatever the extras say then (the colour table may change): {@code R<flight>}
     * and one {@code |<explosion>} per explosion for a rocket, {@code S|<explosion>} for a star; an explosion is {@code shape:colours:fades:flags} with the
     * colours as comma-separated rgb ints and the flags {@code t} and {@code w}.
     */
    public static String encodeRocket(int flight, List<Explosion> explosions) {
        StringBuilder sb = new StringBuilder("R").append(flight);
        for (Explosion e : explosions) sb.append('|').append(encode(e));
        return sb.toString();
    }

    public static String encodeStar(Explosion e) {
        return "S|" + encode(e);
    }

    private static String encode(Explosion e) {
        return e.shape() + ":" + ints(e.colors()) + ":" + ints(e.fades()) + ":" + (e.trail() ? "t" : "") + (e.twinkle() ? "w" : "");
    }

    private static String ints(List<Integer> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) sb.append(i == 0 ? "" : ",").append(l.get(i));
        return sb.toString();
    }

    /** The spec of a stack from the data the stash saved with it ({@link #encodeRocket}, {@link #encodeStar}), or null (none saved, unreadable, or outside the grammar). */
    public static @Nullable String specOf(@Nullable String data, @Nullable Tables t) {
        if (data == null || t == null || data.isEmpty()) return null;
        try {
            String[] parts = data.split("\\|", -1);
            if (data.charAt(0) == 'R') {
                List<Explosion> ex = new ArrayList<>();
                for (int i = 1; i < parts.length; i++) ex.add(decode(parts[i]));
                return rocket(Integer.parseInt(parts[0].substring(1)), ex, t);
            }
            if (data.charAt(0) == 'S' && parts.length == 2) return star(decode(parts[1]), t);
        } catch (RuntimeException ex) {
            // Not what the stash writes: no spec
        }
        return null;
    }

    private static Explosion decode(String s) {
        String[] f = s.split(":", -1);
        if (f.length != 4) throw new IllegalArgumentException("explosion");
        return new Explosion(Integer.parseInt(f[0]), intList(f[1]), intList(f[2]), f[3].contains("t"), f[3].contains("w"));
    }

    private static List<Integer> intList(String s) {
        List<Integer> out = new ArrayList<>();
        if (!s.isEmpty()) for (String x : s.split(",", -1)) out.add(Integer.parseInt(x));
        return Collections.unmodifiableList(out);
    }

    // ---------- reading a spec ----------

    /** One star body of a spec: shape name, colour names, fade names, flags. */
    public record Body(String shape, List<String> colors, List<String> fades, boolean trail, boolean twinkle) {
        public String canonical() {
            return shape + ";" + String.join(",", colors) + ";" + String.join(",", fades) + ";" + (trail ? "t" : "") + (twinkle ? "w" : "");
        }

        /** The same star without its fade colours: what the first craft of a faded star makes. */
        public Body withoutFade() {
            return new Body(shape, colors, List.of(), trail, twinkle);
        }
    }

    /** A rocket (flight and stars) or a star (one body) spec, read back. */
    public record Parsed(boolean rocket, int flight, List<Body> stars) {
        public String canonical() {
            if (!rocket) return "star:" + stars.get(0).canonical();
            StringBuilder sb = new StringBuilder("rocket:").append(flight);
            for (Body b : stars) sb.append('|').append(b.canonical());
            return sb.toString();
        }
    }

    /** The spec as a value, or null when it is not a canonical rocket or star spec of the grammar (section 3). */
    public static @Nullable Parsed parse(@Nullable String spec) {
        if (spec == null || spec.isEmpty() || spec.length() > MAX_SPEC) return null;
        for (int i = 0; i < spec.length(); i++) if (spec.charAt(i) <= 0x20 || spec.charAt(i) >= 0x7f) return null;
        Parsed out;
        if (spec.startsWith("rocket:")) {
            String[] parts = spec.substring("rocket:".length()).split("\\|", -1);
            if (!(parts[0].equals("1") || parts[0].equals("2") || parts[0].equals("3"))) return null;
            int flight = parts[0].charAt(0) - '0';
            if (parts.length - 1 > 8 - flight) return null;
            List<Body> stars = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                Body b = body(parts[i]);
                if (b == null) return null;
                stars.add(b);
            }
            out = new Parsed(true, flight, Collections.unmodifiableList(stars));
        }
        else if (spec.startsWith("star:")) {
            Body b = body(spec.substring("star:".length()));
            if (b == null) return null;
            out = new Parsed(false, 0, List.of(b));
        }
        else return null;
        return out.canonical().equals(spec) ? out : null;
    }

    private static @Nullable Body body(String s) {
        String[] f = s.split(";", -1);
        if (f.length != 4 || !SHAPES.contains(f[0])) return null;
        List<String> colors = dyes(f[1], 1), fades = dyes(f[2], 0);
        if (colors == null || fades == null) return null;
        boolean trail, twinkle;
        switch (f[3]) {
            case "" -> {
                trail = false;
                twinkle = false;
            }
            case "t" -> {
                trail = true;
                twinkle = false;
            }
            case "w" -> {
                trail = false;
                twinkle = true;
            }
            case "tw" -> {
                trail = true;
                twinkle = true;
            }
            default -> {
                return null;
            }
        }
        return new Body(f[0], colors, fades, trail, twinkle);
    }

    private static @Nullable List<String> dyes(String s, int min) {
        if (s.isEmpty()) return min == 0 ? List.of() : null;
        String[] names = s.split(",", -1);
        if (names.length > MAX_COLORS) return null;
        for (String n : names) if (!DYES.contains(n)) return null;
        return List.of(names);
    }

    /** A short description for a log line: "rocket, flight 2, 1 star" or "star, large ball, red + blue, fade white, trail". */
    public static String describe(@Nullable String spec) {
        Parsed p = parse(spec);
        if (p == null) return String.valueOf(spec);
        if (p.rocket()) return "rocket, flight " + p.flight() + ", " + (p.stars().isEmpty() ? "no stars" : p.stars().size() + (p.stars().size() == 1 ? " star" : " stars"));
        Body b = p.stars().get(0);
        StringBuilder sb = new StringBuilder("star, ").append(b.shape().replace('_', ' ')).append(", ").append(String.join(" + ", b.colors()).replace('_', ' '));
        if (!b.fades().isEmpty()) sb.append(", fade ").append(String.join(" + ", b.fades()).replace('_', ' '));
        if (b.trail()) sb.append(", trail");
        if (b.twinkle()) sb.append(", twinkle");
        return sb.toString();
    }

    // ---------- the grid of a special step (section 15) ----------

    private static Order.Cell cell(String id) {
        return new Order.Cell(id, null);
    }

    private static Order.Cell dye(String name) {
        return cell("minecraft:" + name + "_dye");
    }

    /**
     * The cells the grid of a step of this recipe has for this spec, in order, each as the alternatives it may be (the shape item of a star has one per
     * accepted item; one grid holds one). Null when the recipe doesn't make the spec's kind, or the tables don't have what it needs.
     * <ul>
     *     <li>star: gunpowder, the shape item (none for a small ball), the trail item, the twinkle item, then the dyes in spec order;</li>
     *     <li>fade: the star without its fade, then the fade dyes in spec order;</li>
     *     <li>rocket: paper, gunpowder once per flight level, then the stars in spec order.</li>
     * </ul>
     */
    public static @Nullable List<List<Order.Cell>> expected(String recipe, Parsed p, Tables t) {
        List<List<Order.Cell>> out = new ArrayList<>();
        switch (recipe) {
            case RECIPE_ROCKET -> {
                if (!p.rocket() || !t.special().contains("rocket")) return null;
                out.add(List.of(cell(PAPER)));
                for (int i = 0; i < p.flight(); i++) out.add(List.of(cell(GUNPOWDER)));
                for (Body b : p.stars()) out.add(List.of(new Order.Cell(STAR, "star:" + b.canonical())));
            }
            case RECIPE_STAR -> {
                if (p.rocket() || !t.special().contains("star")) return null;
                Body b = p.stars().get(0);
                if (!b.fades().isEmpty()) return null;
                out.add(List.of(cell(GUNPOWDER)));
                if (!b.shape().equals("small_ball")) {
                    List<Order.Cell> alts = items(t, b.shape());
                    if (alts == null) return null;
                    out.add(alts);
                }
                if (b.trail()) {
                    List<Order.Cell> alts = items(t, "trail");
                    if (alts == null) return null;
                    out.add(alts);
                }
                if (b.twinkle()) {
                    List<Order.Cell> alts = items(t, "twinkle");
                    if (alts == null) return null;
                    out.add(alts);
                }
                for (String c : b.colors()) {
                    if (!t.dyeColor().containsKey(c)) return null;
                    out.add(List.of(dye(c)));
                }
            }
            case RECIPE_FADE -> {
                if (p.rocket() || !t.special().contains("star_fade")) return null;
                Body b = p.stars().get(0);
                if (b.fades().isEmpty()) return null;
                out.add(List.of(new Order.Cell(STAR, "star:" + b.withoutFade().canonical())));
                for (String c : b.fades()) {
                    if (!t.dyeColor().containsKey(c)) return null;
                    out.add(List.of(dye(c)));
                }
            }
            default -> {
                return null;
            }
        }
        return out.size() > GRID ? null : out;
    }

    private static @Nullable List<Order.Cell> items(Tables t, String role) {
        List<String> ids = t.starItems().get(role);
        if (ids == null || ids.isEmpty()) return null;
        List<Order.Cell> out = new ArrayList<>();
        for (String id : ids) out.add(cell(id));
        return out;
    }

    /** The recipe ids of the special steps. */
    public static boolean isSpecialRecipe(String recipe) {
        return recipe.equals(RECIPE_ROCKET) || recipe.equals(RECIPE_STAR) || recipe.equals(RECIPE_FADE);
    }

    /**
     * Checks a special step against the bot's own tables (CRAFT-CONTRACT-v1.2.md 11.1 and 15): the recipe is one of the three and the bot's extras list it;
     * {@code spec} is a canonical spec of the recipe's kind for the step's result item; the count is what the recipe makes (3 for a rocket, else 1); the
     * grid has nine entries and its cells are exactly the ones the spec asks for, in order, then empty; the inputs are the grid's cells times the fills.
     * @return null when it passes, else why not
     */
    public static @Nullable String checkStep(Order.CraftStep s, @Nullable Tables t) {
        if (t == null) return "recipe " + s.recipe() + " is a special recipe, and this bot has no firework tables";
        if (s.furnace()) return "recipe " + s.recipe() + " is not a furnace recipe";
        if (!isSpecialRecipe(s.recipe())) return "recipe " + s.recipe() + " is not a special recipe but has a grid";
        Parsed p = parse(s.spec());
        if (p == null) return "recipe " + s.recipe() + " has no usable spec";
        String item = p.rocket() ? ROCKET : STAR;
        if (!s.result().equals(item)) return "recipe " + s.recipe() + " makes " + s.result() + ", not " + item;
        int count = p.rocket() ? ROCKET_COUNT : 1;
        if (s.count() != count) return "recipe " + s.recipe() + " makes " + s.count() + " a fill here, not " + count;
        if (s.fills() < 1) return "recipe " + s.recipe() + " has no fills";
        List<Order.Cell> grid = s.grid();
        if (grid == null || grid.size() != GRID) return "recipe " + s.recipe() + " has a grid of " + (grid == null ? 0 : grid.size()) + " cells, not " + GRID;
        List<List<Order.Cell>> want = expected(s.recipe(), p, t);
        if (want == null) return "recipe " + s.recipe() + " can't make " + s.spec() + " by this bot's extras";
        for (int i = 0; i < GRID; i++) {
            Order.Cell c = grid.get(i);
            if (i >= want.size()) {
                if (c != null) return "recipe " + s.recipe() + " has a cell in slot " + (i + 1) + " that its spec doesn't call for";
                continue;
            }
            if (c == null || !want.get(i).contains(c)) return "recipe " + s.recipe() + " has the wrong item in slot " + (i + 1);
        }
        // What goes in is what the fills take: every cell once per fill (one grid holds one shape item, a single cell, so the creeper heads are never mixed)
        Map<String, Long> cells = new LinkedHashMap<>();
        for (Order.Cell c : grid) if (c != null) cells.merge(c.ref(), (long) s.fills(), Long::sum);
        Map<String, Long> in = new LinkedHashMap<>();
        for (Order.Qty q : s.inputs()) in.merge(q.ref(), (long) q.count(), Long::sum);
        if (!cells.equals(in)) return "recipe " + s.recipe() + " has inputs that are not its grid times its fills";
        return null;
    }

    /** The id the sub-plan knows a special step's recipe by: the recipe id and a short hash of its spec and grid (two steps differ in either). */
    public static String planId(Order.CraftStep s) {
        if (s.grid() == null) return s.recipe();
        StringBuilder sb = new StringBuilder(String.valueOf(s.spec()));
        for (Order.Cell c : s.grid()) sb.append('|').append(c == null ? "-" : c.ref());
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return s.recipe() + "#" + HexFormat.of().formatHex(h).substring(0, 10);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * The step as the sub-plan's recipe: shapeless, one cell per ingredient in grid order (a spec'd cell is the pseudo id of its star), making the pseudo
     * id of the step's spec, {@code count} per fill. Its id is {@link #planId}. The grid is the recipe: the trip fills slots 1, 2, 3, ... with these cells
     * (a shapeless recipe's slots), which is the grid row-major with the empty cells last.
     */
    public static Recipes.Recipe recipeOf(Order.CraftStep s) {
        List<@Nullable List<String>> cells = new ArrayList<>();
        if (s.grid() != null) for (Order.Cell c : s.grid()) if (c != null) cells.add(List.of(c.ref()));
        return new Recipes.Recipe(planId(s), false, 0, 0, Collections.unmodifiableList(cells), s.resultRef(), Math.max(1, s.count()));
    }

    /** The special steps of a block as the sub-plan's recipes, by plan id; empty when it has none. */
    public static Map<String, Recipes.Recipe> recipesOf(Order.Craft craft) {
        Map<String, Recipes.Recipe> out = new LinkedHashMap<>();
        for (Order.CraftStep s : craft.steps()) if (s.special()) out.put(planId(s), recipeOf(s));
        return out;
    }

    /** Whether the recipe is a special step's (its id has the special prefix). */
    public static boolean isSpecial(Recipes.Recipe r) {
        return r.id().startsWith("qwerte:special/");
    }

}
