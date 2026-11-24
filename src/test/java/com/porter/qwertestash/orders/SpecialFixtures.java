package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.Extras;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * The B4 test data. The extras are the body the real server (stash-manager-server ac87d80, {@code HandRecipes}) serves for 1.21.4: the nine special kinds and the
 * hand-written stew table (16 flowers). The blocks are written as the server's planner writes them (PROTOCOL.md "Block v2 fields", and the shapes
 * {@code SpecialOrdersTest} of the server asserts): the grids, the inputs that list a copy's source once per craft, the remainder, the exact keys of takes.
 * Nothing here comes from game files.
 */
public final class SpecialFixtures {
    private SpecialFixtures() {
    }

    /** The extras hash the real B4 server gives its 1.21.4 extras (the same for 1.21.11 today). */
    public static final String SERVER_HASH = "6ab104db90f5d927f6cbab41419848cb545fa043";

    public static final Extras EXTRAS;
    public static final VariantSpecs.Tables TABLES;
    public static final FireworkSpecs.Tables FIREWORKS;
    public static final Recipes R = TestRecipes.table();

    static {
        try (InputStream in = SpecialFixtures.class.getResourceAsStream("/extras-b4-1.21.4.json")) {
            EXTRAS = Extras.parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        TABLES = VariantSpecs.Tables.of(EXTRAS);
        FIREWORKS = FireworkSpecs.Tables.of(EXTRAS);
    }

    public static final Set<String> CAN = Set.of("firework", "tipped", "stew", "copy");

    public static final String POISON = "tipped:minecraft:long_poison", NV = "stew:minecraft:night_vision/100";
    public static final String K_ARROW = "minecraft:tipped_arrow#a0a0a0a0", K_POTION = "minecraft:lingering_potion#b0b0b0b0", K_STEW = "minecraft:suspicious_stew#c0c0c0c0";
    public static final String K_BANNER = "minecraft:white_banner#a1b2c3d4", K_MAP = "minecraft:filled_map#0000abcd", K_BOOK = "minecraft:written_book#bb00bb00";
    public static final String BANNER = "minecraft:white_banner", MAP = "minecraft:filled_map", BOOK = "minecraft:written_book";

    /** A block as the server writes it, from the JSON of its lines, steps, take and leftovers. */
    public static JsonObject block(String needs, String lines, String steps, String take, String leftovers, int fills) {
        return JsonParser.parseString("""
            { "v": 2, "mc": "1.21.4", "recipes": "%s", "extras": "%s", "maxDepth": 12, "needs": %s,
              "lines": %s, "steps": %s, "take": %s, "leftovers": %s, "fills": %d, "smelt": 0, "forEnchant": [] }
            """.formatted(R.hash(), SERVER_HASH, needs, lines, steps, take, leftovers, fills)).getAsJsonObject();
    }

    public static String q(String id, int count) {
        return "{ \"id\": \"" + id + "\", \"count\": " + count + " }";
    }

    public static String q(String id, String key, String spec, int count) {
        return "{ \"id\": \"" + id + "\", \"key\": \"" + key + "\", \"spec\": \"" + spec + "\", \"count\": " + count + " }";
    }

    public static String cell(String id) {
        return "{ \"id\": \"" + id + "\" }";
    }

    public static String cell(String id, String spec) {
        return "{ \"id\": \"" + id + "\", \"spec\": \"" + spec + "\" }";
    }

    private static String grid(String... cells) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 9; i++) sb.append(i == 0 ? "" : ",").append(i < cells.length ? cells[i] : "null");
        return sb.append("]").toString();
    }

    /** 10 tipped arrows of {@link #POISON} with 5 in stock: one craft of 8 around a lingering potion taken by key, 3 arrows left over. */
    public static JsonObject tipped() {
        String arrow = cell("minecraft:arrow"), potion = cell("minecraft:lingering_potion", POISON);
        String step = "{ \"recipe\": \"qwerte:special/tipped\", \"result\": \"minecraft:tipped_arrow\", \"spec\": \"" + POISON + "\", \"count\": 8, \"fills\": 1, \"grid\": "
            + grid(arrow, arrow, arrow, arrow, potion, arrow, arrow, arrow, arrow) + ", \"inputs\": [ " + q("minecraft:arrow", 8) + ", " + q("minecraft:lingering_potion", K_POTION, POISON, 1) + " ] }";
        return block("[\"tipped\"]", "[ { \"key\": \"minecraft:tipped_arrow\", \"count\": 10, \"spec\": \"" + POISON + "\", \"orCraft\": false } ]", "[" + step + "]",
            "[ " + q("minecraft:lingering_potion", K_POTION, POISON, 1) + ", " + q("minecraft:tipped_arrow", K_ARROW, POISON, 5) + ", " + q("minecraft:arrow", 8) + " ]",
            "[ { \"id\": \"minecraft:tipped_arrow\", \"spec\": \"" + POISON + "\", \"count\": 3 } ]", 1);
    }

    /** 5 night vision stews with 3 in stock: 2 crafts of a bowl, both mushrooms and a poppy. */
    public static JsonObject stew() {
        String step = "{ \"recipe\": \"qwerte:special/stew\", \"result\": \"minecraft:suspicious_stew\", \"spec\": \"" + NV + "\", \"count\": 1, \"fills\": 2, \"grid\": "
            + grid(cell("minecraft:bowl"), cell("minecraft:brown_mushroom"), cell("minecraft:red_mushroom"), cell("minecraft:poppy"))
            + ", \"inputs\": [ " + q("minecraft:bowl", 2) + ", " + q("minecraft:brown_mushroom", 2) + ", " + q("minecraft:red_mushroom", 2) + ", " + q("minecraft:poppy", 2) + " ] }";
        return block("[\"stew\"]", "[ { \"key\": \"minecraft:suspicious_stew\", \"count\": 5, \"spec\": \"" + NV + "\", \"orCraft\": false } ]", "[" + step + "]",
            "[ " + q("minecraft:suspicious_stew", K_STEW, NV, 3) + ", " + q("minecraft:bowl", 2) + ", " + q("minecraft:brown_mushroom", 2) + ", " + q("minecraft:red_mushroom", 2)
                + ", " + q("minecraft:poppy", 2) + " ]", "[]", 2);
    }

    /** 3 banner copies of {@link #K_BANNER} with 2 of it in stock: one is kept as the source, one delivered, 2 copied. */
    public static JsonObject bannerCopy() {
        String spec = "copy:" + K_BANNER;
        String step = "{ \"recipe\": \"qwerte:special/banner_copy\", \"result\": \"" + BANNER + "\", \"spec\": \"" + spec + "\", \"count\": 1, \"fills\": 2, \"grid\": "
            + grid(cell(BANNER, spec), cell(BANNER)) + ", \"inputs\": [ " + q(BANNER, K_BANNER, spec, 2) + ", " + q(BANNER, 2) + " ], \"remainder\": [ " + q(BANNER, K_BANNER, spec, 2) + " ] }";
        return block("[\"copy\"]", "[ { \"key\": \"" + BANNER + "\", \"count\": 3, \"spec\": \"" + spec + "\", \"orCraft\": false } ]", "[" + step + "]",
            "[ " + q(BANNER, K_BANNER, spec, 1) + ", " + q(BANNER, K_BANNER, spec, 1) + ", " + q(BANNER, 2) + " ]", "[]", 2);
    }

    /** 12 copies of {@link #K_MAP} with 1 in stock: a craft of 8 blanks (9 maps), then one of 3 whose source map is a result of the first. */
    public static JsonObject mapCopy() {
        String spec = "copy:" + K_MAP;
        String a = "{ \"recipe\": \"qwerte:special/map_copy\", \"result\": \"" + MAP + "\", \"spec\": \"" + spec + "\", \"count\": 9, \"fills\": 1, \"grid\": "
            + grid(cell(MAP, spec), cell("minecraft:map"), cell("minecraft:map"), cell("minecraft:map"), cell("minecraft:map"), cell("minecraft:map"), cell("minecraft:map"),
                cell("minecraft:map"), cell("minecraft:map")) + ", \"inputs\": [ " + q(MAP, K_MAP, spec, 1) + ", " + q("minecraft:map", 8) + " ] }";
        String b = "{ \"recipe\": \"qwerte:special/map_copy\", \"result\": \"" + MAP + "\", \"spec\": \"" + spec + "\", \"count\": 4, \"fills\": 1, \"grid\": "
            + grid(cell(MAP, spec), cell("minecraft:map"), cell("minecraft:map"), cell("minecraft:map")) + ", \"inputs\": [ " + q(MAP, K_MAP, spec, 1) + ", " + q("minecraft:map", 3) + " ] }";
        return block("[\"copy\"]", "[ { \"key\": \"" + MAP + "\", \"count\": 12, \"spec\": \"" + spec + "\", \"orCraft\": false } ]", "[" + a + "," + b + "]",
            "[ " + q(MAP, K_MAP, spec, 1) + ", " + q("minecraft:map", 11) + " ]", "[]", 2);
    }

    /** 17 copies of {@link #K_MAP} with 1 in stock: one step of 2 crafts of 8 blanks, the second craft's source is the first's result. */
    public static JsonObject mapCopyInOneStep() {
        String spec = "copy:" + K_MAP;
        String grid8 = grid(cell(MAP, spec), cell("minecraft:map"), cell("minecraft:map"), cell("minecraft:map"), cell("minecraft:map"), cell("minecraft:map"),
            cell("minecraft:map"), cell("minecraft:map"), cell("minecraft:map"));
        String a = "{ \"recipe\": \"qwerte:special/map_copy\", \"result\": \"" + MAP + "\", \"spec\": \"" + spec + "\", \"count\": 9, \"fills\": 2, \"grid\": " + grid8
            + ", \"inputs\": [ " + q(MAP, K_MAP, spec, 2) + ", " + q("minecraft:map", 16) + " ] }";
        return block("[\"copy\"]", "[ { \"key\": \"" + MAP + "\", \"count\": 17, \"spec\": \"" + spec + "\", \"orCraft\": false } ]", "[" + a + "]",
            "[ " + q(MAP, K_MAP, spec, 1) + ", " + q("minecraft:map", 16) + " ]", "[]", 2);
    }

    /** 6 copies of {@link #K_BOOK}: 6 writable books and the original, which stays (the remainder). */
    public static JsonObject bookCopy() {
        String spec = "copy:" + K_BOOK;
        String step = "{ \"recipe\": \"qwerte:special/book_copy\", \"result\": \"" + BOOK + "\", \"spec\": \"" + spec + "\", \"count\": 6, \"fills\": 1, \"grid\": "
            + grid(cell(BOOK, spec), cell("minecraft:writable_book"), cell("minecraft:writable_book"), cell("minecraft:writable_book"), cell("minecraft:writable_book"),
                cell("minecraft:writable_book"), cell("minecraft:writable_book"))
            + ", \"inputs\": [ " + q(BOOK, K_BOOK, spec, 1) + ", " + q("minecraft:writable_book", 6) + " ], \"remainder\": [ " + q(BOOK, K_BOOK, spec, 1) + " ] }";
        return block("[\"copy\"]", "[ { \"key\": \"" + BOOK + "\", \"count\": 6, \"spec\": \"" + spec + "\", \"orCraft\": false } ]", "[" + step + "]",
            "[ " + q(BOOK, K_BOOK, spec, 1) + ", " + q("minecraft:writable_book", 6) + " ]", "[]", 1);
    }

    /** 2 shields with the banner of {@link #K_BANNER} (consumed): the shields come from the stash. */
    public static JsonObject shield() {
        String spec = "shield:" + K_BANNER;
        String step = "{ \"recipe\": \"qwerte:special/shield\", \"result\": \"minecraft:shield\", \"spec\": \"" + spec + "\", \"count\": 1, \"fills\": 2, \"grid\": "
            + grid(cell("minecraft:shield"), cell(BANNER, "copy:" + K_BANNER)) + ", \"inputs\": [ " + q("minecraft:shield", 2) + ", " + q(BANNER, K_BANNER, "copy:" + K_BANNER, 2) + " ] }";
        return block("[\"copy\"]", "[ { \"key\": \"minecraft:shield\", \"count\": 2, \"spec\": \"" + spec + "\", \"orCraft\": false } ]", "[" + step + "]",
            "[ " + q(BANNER, K_BANNER, "copy:" + K_BANNER, 2) + ", " + q("minecraft:shield", 2) + " ]", "[]", 2);
    }

    /** {@code q} copies of {@link #K_BANNER} with {@code stock} of it in the stash: one is kept as the source for the whole order, the rest delivered, the others copied. */
    public static JsonObject bannerCopy(int stock, int q) {
        String spec = "copy:" + K_BANNER;
        int delivered = Math.min(stock - 1, q), crafts = q - delivered;
        String step = "{ \"recipe\": \"qwerte:special/banner_copy\", \"result\": \"" + BANNER + "\", \"spec\": \"" + spec + "\", \"count\": 1, \"fills\": " + crafts + ", \"grid\": "
            + grid(cell(BANNER, spec), cell(BANNER)) + ", \"inputs\": [ " + q(BANNER, K_BANNER, spec, crafts) + ", " + q(BANNER, crafts) + " ], \"remainder\": [ " + q(BANNER, K_BANNER, spec, crafts) + " ] }";
        return block("[\"copy\"]", "[ { \"key\": \"" + BANNER + "\", \"count\": " + q + ", \"spec\": \"" + spec + "\" } ]", "[" + step + "]",
            "[ " + q(BANNER, K_BANNER, spec, delivered + 1) + ", " + q(BANNER, crafts) + " ]", "[]", crafts);
    }

    /** {@code q} copies of {@link #K_MAP} with {@code stock} of it (all of it delivered, one used as the source): crafts of 8 blanks and one shorter, chained. */
    public static JsonObject mapCopy(int stock, int q) {
        String spec = "copy:" + K_MAP;
        int net = q - stock, full = net / 8, rest = net % 8;
        StringBuilder steps = new StringBuilder();
        String[] eight = new String[9];
        eight[0] = cell(MAP, spec);
        for (int i = 1; i < 9; i++) eight[i] = cell("minecraft:map");
        if (full > 0) {
            steps.append("{ \"recipe\": \"qwerte:special/map_copy\", \"result\": \"" + MAP + "\", \"spec\": \"" + spec + "\", \"count\": 9, \"fills\": " + full + ", \"grid\": " + grid(eight)
                + ", \"inputs\": [ " + q(MAP, K_MAP, spec, full) + ", " + q("minecraft:map", 8 * full) + " ] }");
        }
        if (rest > 0) {
            String[] cells = new String[rest + 1];
            System.arraycopy(eight, 0, cells, 0, rest + 1);
            if (full > 0) steps.append(",");
            steps.append("{ \"recipe\": \"qwerte:special/map_copy\", \"result\": \"" + MAP + "\", \"spec\": \"" + spec + "\", \"count\": " + (rest + 1) + ", \"fills\": 1, \"grid\": " + grid(cells)
                + ", \"inputs\": [ " + q(MAP, K_MAP, spec, 1) + ", " + q("minecraft:map", rest) + " ] }");
        }
        return block("[\"copy\"]", "[ { \"key\": \"" + MAP + "\", \"count\": " + q + ", \"spec\": \"" + spec + "\" } ]", "[" + steps + "]",
            "[ " + q(MAP, K_MAP, spec, stock) + ", " + q("minecraft:map", net) + " ]", "[]", full + (rest > 0 ? 1 : 0));
    }

    /** One shield with the banner of the key and 2 copies of it, with 2 of the key in stock: one is the source, one goes into the shield. */
    public static JsonObject shieldAndCopies(boolean shieldFirst) {
        String spec = "copy:" + K_BANNER;
        String copy = "{ \"recipe\": \"qwerte:special/banner_copy\", \"result\": \"" + BANNER + "\", \"spec\": \"" + spec + "\", \"count\": 1, \"fills\": 2, \"grid\": "
            + grid(cell(BANNER, spec), cell(BANNER)) + ", \"inputs\": [ " + q(BANNER, K_BANNER, spec, 2) + ", " + q(BANNER, 2) + " ], \"remainder\": [ " + q(BANNER, K_BANNER, spec, 2) + " ] }";
        String shield = "{ \"recipe\": \"qwerte:special/shield\", \"result\": \"minecraft:shield\", \"spec\": \"shield:" + K_BANNER + "\", \"count\": 1, \"fills\": 1, \"grid\": "
            + grid(cell("minecraft:shield"), cell(BANNER, spec)) + ", \"inputs\": [ " + q("minecraft:shield", 1) + ", " + q(BANNER, K_BANNER, spec, 1) + " ] }";
        String lineCopy = "{ \"key\": \"" + BANNER + "\", \"count\": 2, \"spec\": \"" + spec + "\" }", lineShield = "{ \"key\": \"minecraft:shield\", \"count\": 1, \"spec\": \"shield:" + K_BANNER + "\" }";
        return block("[\"copy\"]", shieldFirst ? "[" + lineShield + "," + lineCopy + "]" : "[" + lineCopy + "," + lineShield + "]", "[" + copy + "," + shield + "]",
            "[ " + q(BANNER, K_BANNER, spec, 2) + ", " + q(BANNER, 2) + ", " + q("minecraft:shield", 1) + " ]", "[]", 3);
    }

    public static Order.Craft parse(JsonObject block) {
        Order.Craft c = Order.parseCraft(block);
        if (c == null || c.malformed()) throw new AssertionError("the block can't be read: " + block);
        return c;
    }

    /** The check of a block by a bot that can do everything in B3 and B4, with the extras above. */
    public static String check(JsonObject block) {
        return check(parse(block), CAN);
    }

    public static String check(Order.Craft c, Set<String> can) {
        return CraftOrder.check(c, R, 2048, SERVER_HASH, can, null, 0, FIREWORKS, TABLES);
    }

    public static List<String> specials() {
        return List.of("tipped", "stew", "banner_copy", "map_copy", "book_copy", "shield");
    }
}
