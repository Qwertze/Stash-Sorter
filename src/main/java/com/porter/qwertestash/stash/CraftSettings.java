package com.porter.qwertestash.stash;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * The craft settings of one stash (CRAFT-CONTRACT.md "Craft settings"): the crafting table the website set for it, or none. Immutable.
 * Everything that comes from the server or from the cache file is checked here, and an object that fails any check is refused whole.
 * {@code neverUse} is server-side only and not part of this object.
 *
 * @param table where craft orders craft, or null when none is set
 */
public record CraftSettings(long rev, String hash, @Nullable Table table) {
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern DIMENSION = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,100}$");

    /** The crafting table: block position and dimension ("minecraft:overworld"). No position is ever logged above debug. */
    public record Table(int x, int y, int z, String dimension) {
        public com.aquarius.mc.block.BlockPos pos() {
            return new com.aquarius.mc.block.BlockPos(x, y, z);
        }

        /** The id the heartbeat's {@code seen} list uses: "x,y,z". */
        public String id() {
            return x + "," + y + "," + z;
        }
    }

    /** What a stash has when nothing is set. */
    public static final CraftSettings EMPTY = new CraftSettings(0, hashOf(null), null);

    /** Why an object was refused, for the log. */
    public static final class Invalid extends Exception {
        public Invalid(String why) {
            super(why);
        }
    }

    /** Whether this is a position the contract allows: |x|, |z| at most 30,000,000, y from -64 to 320, a namespaced dimension. */
    public static boolean validTable(long x, long y, long z, @Nullable String dimension) {
        return Math.abs(x) <= 30_000_000 && Math.abs(z) <= 30_000_000 && y >= -64 && y <= 320 && dimension != null && DIMENSION.matcher(dimension).matches();
    }

    /** SHA-1 (lowercase hex) of {@code "craft-v1\n" + (table ? x + "," + y + "," + z + "," + dimension : "") + "\n"}. */
    public static String hashOf(@Nullable Table table) {
        String text = "craft-v1\n" + (table == null ? "" : table.x() + "," + table.y() + "," + table.z() + "," + table.dimension()) + "\n";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Checks and reads a settings object ({@code rev}, {@code hash}, {@code table}). Unknown fields (such as {@code neverUse}) are ignored.
     * @throws Invalid when anything is wrong: a type, a position out of range, a bad dimension, or a hash that is not the content's
     */
    public static CraftSettings parse(@Nullable JsonElement element) throws Invalid {
        if (element == null || !element.isJsonObject()) throw new Invalid("not an object");
        JsonObject o = element.getAsJsonObject();
        long rev = wholeNumber(o.get("rev"), "rev");
        if (rev < 0) throw new Invalid("rev is negative");

        JsonElement h = o.get("hash");
        if (h == null || !h.isJsonPrimitive() || !h.getAsJsonPrimitive().isString() || !HASH.matcher(h.getAsString()).matches()) throw new Invalid("hash is not 40 lowercase hex digits");

        Table table = null;
        JsonElement t = o.get("table");
        if (t != null && !t.isJsonNull()) {
            if (!t.isJsonObject()) throw new Invalid("table is not an object");
            JsonObject to = t.getAsJsonObject();
            long x = wholeNumber(to.get("x"), "x"), y = wholeNumber(to.get("y"), "y"), z = wholeNumber(to.get("z"), "z");
            JsonElement d = to.get("dimension");
            if (d == null || !d.isJsonPrimitive() || !d.getAsJsonPrimitive().isString()) throw new Invalid("dimension is not text");
            if (!validTable(x, y, z, d.getAsString())) throw new Invalid("the table position or dimension is out of range");
            table = new Table((int) x, (int) y, (int) z, d.getAsString());
        }
        // The content has to hash to the hash it states: a mismatch is a damaged or tampered object
        if (!hashOf(table).equals(h.getAsString())) throw new Invalid("hash does not match the content");
        return new CraftSettings(rev, h.getAsString(), table);
    }

    private static long wholeNumber(@Nullable JsonElement e, String what) throws Invalid {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new Invalid(what + " is not a number");
        JsonPrimitive p = e.getAsJsonPrimitive();
        String text = p.getAsString();
        if (text.length() > 15 || !text.matches("-?[0-9]+")) throw new Invalid(what + " is not a whole number");
        return Long.parseLong(text);
    }

    /** The object the server sends, and the cache file keeps. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("rev", rev);
        o.addProperty("hash", hash);
        if (table == null) o.add("table", JsonNull.INSTANCE);
        else {
            JsonObject t = new JsonObject();
            t.addProperty("x", table.x());
            t.addProperty("y", table.y());
            t.addProperty("z", table.z());
            t.addProperty("dimension", table.dimension());
            o.add("table", t);
        }
        return o;
    }
}
