package com.porter.qwertestash.stash;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The repair settings of a stash (REPAIR-CONTRACT v2, "Repair settings", with the "opening unnamed shulkers" revision): repair mode and automatic passes, the
 * threshold in percent, the bottle reserve, the needs-repair chest and whether unnamed shulker boxes are opened. Stored by the server; the bot caches them.
 * The hash is the SHA-1 of {@code "repair-v1\n" + enabled + "\n" + auto + "\n" + threshold + "\n" + reserve + "\n" + (chest or "") + "\n" + shulkers + "\n"}.
 * Everything read from the server or the cache file is checked here. Immutable.
 */
public record RepairSettings(long rev, String hash, boolean enabled, boolean auto, int threshold, int reserve, @Nullable String needsRepairChest, boolean shulkers) {
    public static final int DEFAULT_THRESHOLD = 50, DEFAULT_RESERVE = 1728, MAX_RESERVE = 1_000_000;

    /** What a local run uses with nothing cached (threshold 50, reserve 1728, no chest); never a source of automatic passes. */
    public static final RepairSettings DEFAULTS = of(0, true, true, DEFAULT_THRESHOLD, DEFAULT_RESERVE, null, true);

    /** Why an object was refused, for the log. */
    public static final class Invalid extends Exception {
        public Invalid(String why) {
            super(why);
        }
    }

    public static RepairSettings of(long rev, boolean enabled, boolean auto, int threshold, int reserve, @Nullable String chest, boolean shulkers) {
        return new RepairSettings(rev, hashOf(enabled, auto, threshold, reserve, chest, shulkers), enabled, auto, threshold, reserve, chest, shulkers);
    }

    public static String hashOf(boolean enabled, boolean auto, int threshold, int reserve, @Nullable String chest, boolean shulkers) {
        String text = "repair-v1\n" + enabled + "\n" + auto + "\n" + threshold + "\n" + reserve + "\n" + (chest == null ? "" : chest) + "\n" + shulkers + "\n";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The hash of a server before the shulkers setting: {@code "repair-v1\n" + enabled + auto + threshold + reserve + chest} with no shulkers line. */
    static String legacyHashOf(boolean enabled, boolean auto, int threshold, int reserve, @Nullable String chest) {
        String text = "repair-v1\n" + enabled + "\n" + auto + "\n" + threshold + "\n" + reserve + "\n" + (chest == null ? "" : chest) + "\n";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean bool(JsonObject o, String name) throws Invalid {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) throw new Invalid(name + " is not true or false");
        return e.getAsBoolean();
    }

    private static int whole(JsonObject o, String name, int min, int max) throws Invalid {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || !e.getAsString().matches("-?[0-9]{1,9}")) throw new Invalid(name + " is not a whole number");
        int n = Integer.parseInt(e.getAsString());
        if (n < min || n > max) throw new Invalid(name + " is out of range");
        return n;
    }

    /**
     * Checks and reads a settings object ({@code rev, hash, enabled, auto, threshold, reserve, needsRepairChest, shulkers}). {@code shulkers} may be missing
     * (a server before the revision): it is then true, and the hash is the one of that reading. The hash must be the one of the fields.
     */
    public static RepairSettings parse(@Nullable JsonElement element) throws Invalid {
        if (element == null || !element.isJsonObject()) throw new Invalid("repair settings are not an object");
        JsonObject o = element.getAsJsonObject();
        JsonElement r = o.get("rev");
        if (r == null || !r.isJsonPrimitive() || !r.getAsJsonPrimitive().isNumber() || !r.getAsString().matches("[0-9]{1,15}")) throw new Invalid("rev is not a whole number");
        long rev = Long.parseLong(r.getAsString());
        boolean enabled = bool(o, "enabled"), auto = bool(o, "auto");
        int threshold = whole(o, "threshold", 1, 100), reserve = whole(o, "reserve", 0, MAX_RESERVE);
        String chest = null;
        JsonElement c = o.get("needsRepairChest");
        if (c != null && !c.isJsonNull()) {
            if (!c.isJsonPrimitive() || !c.getAsJsonPrimitive().isString() || !KitSettings.validId(c.getAsString())) throw new Invalid("needsRepairChest is not a container id");
            chest = c.getAsString();
        }
        // An older server's settings have no shulkers field: boxes are then not opened (the safe side); its hash is the one without it
        boolean shulkers = o.has("shulkers") && !o.get("shulkers").isJsonNull() && bool(o, "shulkers");
        JsonElement h = o.get("hash");
        if (h == null || !h.isJsonPrimitive() || !h.getAsJsonPrimitive().isString() || !h.getAsString().matches("[0-9a-f]{40}")) throw new Invalid("hash is not 40 hex digits");
        String given = h.getAsString();
        if (!given.equals(hashOf(enabled, auto, threshold, reserve, chest, shulkers)) && !(!shulkers && given.equals(legacyHashOf(enabled, auto, threshold, reserve, chest)))) {
            throw new Invalid("hash does not match the settings");
        }
        // The hash is kept as the server gave it: it is what the heartbeat sends back
        return new RepairSettings(rev, given, enabled, auto, threshold, reserve, chest, shulkers);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("rev", rev);
        o.addProperty("hash", hash);
        o.addProperty("enabled", enabled);
        o.addProperty("auto", auto);
        o.addProperty("threshold", threshold);
        o.addProperty("reserve", reserve);
        if (needsRepairChest == null) o.add("needsRepairChest", com.google.gson.JsonNull.INSTANCE);
        else o.addProperty("needsRepairChest", needsRepairChest);
        o.addProperty("shulkers", shulkers);
        return o;
    }
}
