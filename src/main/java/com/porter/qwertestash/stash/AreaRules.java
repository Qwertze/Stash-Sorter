package com.porter.qwertestash.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.porter.qwertestash.orders.OrderBlocker;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The stash area editor's rules (AREA-CONTRACT.md): the hash of an area, how an area from the website is checked, the derived area the bot
 * reports when none is set, whether an area may be applied now and why not (busy, far, dimension). Pure.
 */
public final class AreaRules {
    private AreaRules() {
    }

    public static final int MAX_W = 256, MAX_H = 128, MAX_D = 256;
    /** A website area further than this (horizontally) from the bot is not applied. */
    public static final int FAR_BLOCKS = 128;
    public static final String BUSY = "busy", FAR = "far", DIMENSION = "dimension";
    private static final Pattern DIM = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,100}$");

    /** Why an area from the server was refused, for the log. */
    public static final class Invalid extends Exception {
        public Invalid(String why) {
            super(why);
        }
    }

    /** minX, minY, minZ, maxX, maxY, maxZ and the dimension. */
    public record Parsed(int[] box, String dimension) {
    }

    /** SHA-1 (lowercase hex) of {@code "area-v1\n" + (area ? minX,minY,minZ,maxX,maxY,maxZ,dimension : "") + "\n"}. */
    public static String hashOf(int @Nullable [] box, @Nullable String dimension) {
        String text = "area-v1\n";
        if (box != null && dimension != null) {
            text += box[0] + "," + box[1] + "," + box[2] + "," + box[3] + "," + box[4] + "," + box[5] + "," + dimension;
        }
        text += "\n";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The area object of a reply or a heartbeat, read: {@code { box: [6 whole numbers], dimension }}. */
    public static Parsed parse(@Nullable JsonElement e) throws Invalid {
        if (e == null || !e.isJsonObject()) throw new Invalid("not an object");
        JsonObject o = e.getAsJsonObject();
        JsonElement b = o.get("box"), d = o.get("dimension");
        if (b == null || !b.isJsonArray() || b.getAsJsonArray().size() != 6) throw new Invalid("box is not 6 numbers");
        JsonArray a = b.getAsJsonArray();
        int[] box = new int[6];
        for (int i = 0; i < 6; i++) {
            JsonElement x = a.get(i);
            if (!x.isJsonPrimitive() || !x.getAsJsonPrimitive().isNumber()) throw new Invalid("box has a non-number");
            JsonPrimitive p = x.getAsJsonPrimitive();
            String text = p.getAsString();
            if (!text.matches("-?[0-9]{1,9}")) throw new Invalid("box has a number that is not whole");
            box[i] = Integer.parseInt(text);
        }
        if (d == null || !d.isJsonPrimitive() || !d.getAsJsonPrimitive().isString()) throw new Invalid("dimension is not text");
        return new Parsed(box, d.getAsString());
    }

    /** The Y limits of a dimension: the nether and the end 0..255, any other -64..319. */
    public static int[] yLimits(String dimension) {
        String d = dimension.indexOf(':') >= 0 ? dimension.substring(dimension.indexOf(':') + 1) : dimension;
        return d.equals("the_nether") || d.equals("the_end") ? new int[] {0, 255} : new int[] {-64, 319};
    }

    /**
     * Why an area from the website can't be used, or null: the shape and range of the box, the size caps, the Y limits of the dimension, the dimension
     * itself, which must be the stash's (with or without the "minecraft:" prefix); a stash with no known dimension accepts none.
     */
    public static @Nullable String validate(int[] box, String dimension, @Nullable String stashDimension) {
        if (box.length != 6) return "box is not 6 numbers";
        for (int i = 0; i < 3; i++) if (box[i] > box[i + 3]) return "a minimum is above its maximum";
        if (Math.abs((long) box[0]) > 30_000_000 || Math.abs((long) box[3]) > 30_000_000 || Math.abs((long) box[2]) > 30_000_000 || Math.abs((long) box[5]) > 30_000_000) return "x or z out of range";
        long w = (long) box[3] - box[0] + 1, h = (long) box[4] - box[1] + 1, d = (long) box[5] - box[2] + 1;
        if (w > MAX_W || h > MAX_H || d > MAX_D) return "that area is " + w + "x" + h + "x" + d + "; the largest is " + MAX_W + "x" + MAX_H + "x" + MAX_D;
        if (!DIM.matcher(dimension).matches()) return "bad dimension";
        int[] y = yLimits(dimension);
        if (box[1] < y[0] || box[4] > y[1]) return "y must be " + y[0] + ".." + y[1] + " in " + dimension;
        if (stashDimension == null) return "the stash's dimension isn't known";
        if (!OrderBlocker.sameDimension(stashDimension, dimension)) return "not the stash's dimension";
        return null;
    }

    /**
     * The area the bot reports when none is set (its working box): the bounding box of the read, in-scope, non-delivery container blocks, widened
     * by 2 blocks on X and Z and 1 on Y; when bigger than the caps, clamped to them around its centre; null with no container.
     * @param blocks x, y, z of every block of those containers
     */
    public static int @Nullable [] derived(List<int[]> blocks) {
        if (blocks.isEmpty()) return null;
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int[] p : blocks) {
            b[0] = Math.min(b[0], p[0]);
            b[1] = Math.min(b[1], p[1]);
            b[2] = Math.min(b[2], p[2]);
            b[3] = Math.max(b[3], p[0]);
            b[4] = Math.max(b[4], p[1]);
            b[5] = Math.max(b[5], p[2]);
        }
        b[0] -= 2;
        b[3] += 2;
        b[2] -= 2;
        b[5] += 2;
        b[1] -= 1;
        b[4] += 1;
        clamp(b, 0, 3, MAX_W);
        clamp(b, 1, 4, MAX_H);
        clamp(b, 2, 5, MAX_D);
        return b;
    }

    private static void clamp(int[] b, int lo, int hi, int cap) {
        if ((long) b[hi] - b[lo] + 1 <= cap) return;
        int centre = (int) (((long) b[lo] + b[hi]) / 2);
        b[lo] = centre - cap / 2;
        b[hi] = b[lo] + cap - 1;
    }

    /** Blocks from the bot's feet to the nearest point of the box, horizontally. */
    public static double horizontalDistance(int[] box, double feetX, double feetZ) {
        double dx = Math.max(Math.max(box[0] - feetX, 0), feetX - (box[3] + 1));
        double dz = Math.max(Math.max(box[2] - feetZ, 0), feetZ - (box[5] + 1));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Whether the bot is between tasks (the plugin's conditions of AREA-CONTRACT.md): no order held or resumable, no job, no shulker handling, no
     * shulker placed or pending, no repair pass or craft running, nothing carried for a task. Null when it is, else "busy".
     */
    public static @Nullable String busyWhy(boolean orderHeldOrResumable, boolean jobRunning, boolean shulkerHandling, boolean boxPlacedOrPending, boolean repairOrCraft,
                                           boolean carryingForTask) {
        return orderHeldOrResumable || jobRunning || shulkerHandling || boxPlacedOrPending || repairOrCraft || carryingForTask ? BUSY : null;
    }

    /**
     * Why a queued area is not applied yet, or null when it can be applied now: the bot is in another dimension (dimension), the area is more than
     * 128 blocks from the bot (far; website areas only: someone who types or stands at a corner knows where it is), or the bot is busy.
     * @param box null: the area is cleared
     */
    public static @Nullable String pendingWhy(int @Nullable [] box, @Nullable String areaDimension, @Nullable String botDimension, boolean website, double feetX, double feetZ,
                                              @Nullable String busy) {
        if (areaDimension != null && botDimension != null && !OrderBlocker.sameDimension(areaDimension, botDimension)) return DIMENSION;
        if (website && box != null && horizontalDistance(box, feetX, feetZ) > FAR_BLOCKS) return FAR;
        return busy;
    }
}
