package com.porter.qwertestash.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.Stash.Box;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds what the plugin sends to the Stash Manager server (see stash-manager-server/PROTOCOL.md): the index of the saved
 * stash with its hash, and the heartbeat. Works from a snapshot of the stash and plain data only (the contents arrays are
 * replaced and never changed in place), so it can run on any thread. Every string is cut to the protocol's limit.
 */
public final class ManagerIndex {
    private ManagerIndex() {
    }

    /** A finished index: the JSON body and the hash of its items, plus what identifies the stash. */
    public record Built(String server, String stash, String player, String hash, byte[] json, int known, int total, int stale, int itemTypes) {
    }

    /** What is worked out once per key. */
    private record Info(String key, String id, String name, String englishName, int maxStack, List<String> details, boolean enchanted) {
    }

    private static final class Agg {
        final Info info;
        long count;

        Agg(Info info) {
            this.info = info;
        }
    }

    private static final class Shulker {
        final Info info;
        final String key;
        final List<int[]> slots = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        int count;

        Shulker(Info info, String key) {
            this.info = info;
            this.key = key;
        }
    }

    private static final Gson GSON = new Gson();
    private static final Gson GSON_NULLS = new GsonBuilder().serializeNulls().create();
    private static final int MAX_DEPTH = 4;
    private static final int MAX_TEXT = 256;
    private static final int OTHER_ORDER = 1_000_000;

    /**
     * @param englishName the item's English name for a registry id like {@code diamond_sword}, or null if unknown
     */
    public static Built build(Stash stash, boolean includeExcluded, String player, String mcVersion, @Nullable String dimension,
                              Function<String, @Nullable String> englishName) {
        Map<String, Info> infos = new LinkedHashMap<>();
        Map<String, Agg> groups = new LinkedHashMap<>();
        Map<String, Shulker> shulkers = new LinkedHashMap<>();
        int known = 0, total = 0, stale = 0;
        for (Box box : stash.boxes) {
            // The delivery container is where orders are put, not part of the stash being indexed
            if (box.delivery || (box.excluded && !includeExcluded)) continue;
            total++;
            if (box.stale) stale++;
            Item[] contents = box.contents;
            if (contents == null) continue;
            known++;
            for (Item item : contents) {
                if (item == null) continue;
                add(groups, infos, item, 0, englishName);
                addShulker(shulkers, infos, item, englishName);
            }
        }

        List<Agg> sorted = new ArrayList<>(groups.values());
        sorted.sort(Comparator.comparing((Agg a) -> a.info.key()));

        JsonArray items = new JsonArray();
        for (Agg a : sorted) {
            Info i = a.info;
            JsonObject o = new JsonObject();
            o.addProperty("key", i.key());
            o.addProperty("id", i.id());
            o.addProperty("name", i.name());
            o.addProperty("englishName", i.englishName());
            o.addProperty("count", a.count);
            o.addProperty("maxStack", i.maxStack());
            JsonArray details = new JsonArray();
            for (String d : i.details()) details.add(d);
            o.add("details", details);
            o.addProperty("enchanted", i.enchanted());
            // The plugin has no creative tabs: every item goes in "other"
            o.addProperty("tab", "other");
            o.addProperty("tabOrder", OTHER_ORDER);
            items.add(o);
        }

        List<Shulker> sortedShulkers = new ArrayList<>(shulkers.values());
        sortedShulkers.sort(Comparator.comparing((Shulker s) -> s.key));
        JsonArray shulkerArray = new JsonArray();
        for (Shulker sh : sortedShulkers) {
            JsonObject o = new JsonObject();
            o.addProperty("key", sh.key);
            o.addProperty("itemKey", sh.info.key());
            o.addProperty("id", sh.info.id());
            o.addProperty("name", sh.info.name());
            o.addProperty("count", sh.count);
            JsonArray contents = new JsonArray();
            for (int n = 0; n < sh.keys.size(); n++) {
                JsonObject c = new JsonObject();
                c.addProperty("slot", sh.slots.get(n)[0]);
                c.addProperty("key", sh.keys.get(n));
                c.addProperty("count", sh.slots.get(n)[1]);
                contents.add(c);
            }
            o.add("contents", contents);
            shulkerArray.add(o);
        }
        String hash = sha1(GSON.toJson(items) + GSON.toJson(shulkerArray));

        String who = clamp(player, 256);
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("server", clamp(stash.server, 256));
        root.addProperty("stash", clamp(stash.name, 256));
        root.addProperty("player", who);
        root.addProperty("mcVersion", mcVersion);
        root.addProperty("dimension", namespaced(dimension));
        root.addProperty("generatedAt", System.currentTimeMillis());
        root.addProperty("indexHash", hash);
        JsonObject containers = new JsonObject();
        containers.addProperty("known", known);
        containers.addProperty("total", total);
        containers.addProperty("stale", stale);
        root.add("containers", containers);
        root.add("items", items);
        root.add("shulkers", shulkerArray);

        return new Built(clamp(stash.server, 256), clamp(stash.name, 256), who, hash, GSON.toJson(root).getBytes(StandardCharsets.UTF_8),
            known, total, stale, sorted.size());
    }

    /** "overworld" as "minecraft:overworld"; a name with a namespace is left alone. Empty when unknown. */
    public static String namespaced(@Nullable String dimension) {
        if (dimension == null || dimension.isBlank()) return "";
        String d = dimension.trim();
        return d.indexOf(':') >= 0 ? d : "minecraft:" + d;
    }

    /**
     * The heartbeat body. {@code rescanDone} is the website rescan id handled and not yet acknowledged, or -1;
     * {@code rescanBlocked} why a rescan can't start, or null. In this version no orders are taken and there is no
     * delivery container, so delivery is null and the order blocker says so.
     */
    public static byte[] heartbeat(Built b, long rescanDone, @Nullable String rescanBlocked, @Nullable String orderBlocker) {
        return heartbeat(b, rescanDone, rescanBlocked, orderBlocker, null);
    }

    /** The delivery container the bot will fill: x, y, z and dimension (whole numbers, dimension cut to 100 characters). */
    public record Delivery(int x, int y, int z, String dimension) {
    }

    /**
     * @param delivery the delivery container, or null when none is set (sent as an explicit null, which clears it)
     * @param orderBlocker why orders cannot be taken right now, or null when they can (PROTOCOL.md "delivery status")
     */
    public static byte[] heartbeat(Built b, long rescanDone, @Nullable String rescanBlocked, @Nullable String orderBlocker, @Nullable Delivery delivery) {
        JsonObject o = new JsonObject();
        o.addProperty("server", b.server());
        o.addProperty("stash", b.stash());
        o.addProperty("player", b.player());
        o.addProperty("indexHash", b.hash());
        if (rescanDone >= 0) o.addProperty("rescanDone", rescanDone);
        if (rescanBlocked == null || rescanBlocked.isEmpty()) o.add("rescanBlocked", JsonNull.INSTANCE);
        else o.addProperty("rescanBlocked", clamp(rescanBlocked, 100));
        if (delivery == null) o.add("delivery", JsonNull.INSTANCE);
        else {
            JsonObject d = new JsonObject();
            d.addProperty("x", delivery.x());
            d.addProperty("y", delivery.y());
            d.addProperty("z", delivery.z());
            d.addProperty("dimension", clamp(delivery.dimension(), 100));
            o.add("delivery", d);
        }
        if (orderBlocker == null || orderBlocker.isEmpty()) o.add("orderBlocker", JsonNull.INSTANCE);
        else o.addProperty("orderBlocker", clamp(orderBlocker, 100));
        return GSON_NULLS.toJson(o).getBytes(StandardCharsets.UTF_8);
    }

    /** Whether a heartbeat reply says the server wants the index. Missing or broken means no. */
    public static boolean needsIndex(String body) {
        try {
            JsonElement e = SafeJson.parse(body);
            return e.isJsonObject() && e.getAsJsonObject().has("needIndex") && e.getAsJsonObject().get("needIndex").getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** The rescan id of a heartbeat reply, or -1 when there is none. */
    public static long rescanId(String body) {
        try {
            JsonElement e = SafeJson.parse(body);
            if (!e.isJsonObject()) return -1;
            JsonObject o = e.getAsJsonObject();
            if (!o.has("rescan") || o.get("rescan").isJsonNull()) return -1;
            return o.get("rescan").getAsLong();
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    /** Whether a 409 body is the stash ownership refusal; the owner's name if so, "?" if it names none, else null. */
    public static @Nullable String ownerConflict(String body) {
        try {
            JsonElement e = SafeJson.parse(body);
            if (!e.isJsonObject()) return null;
            JsonObject o = e.getAsJsonObject();
            if (!o.has("error") || !o.get("error").isJsonPrimitive() || !"stash name in use".equals(o.get("error").getAsString())) return null;
            if (o.has("owner") && o.get("owner").isJsonPrimitive()) return clamp(o.get("owner").getAsString(), 64);
            return "?";
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** At most {@code max} characters, cut at a word boundary and ending in an ellipsis when it was longer. */
    public static String clamp(@Nullable String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int cut = s.lastIndexOf(' ', max - 1);
        if (cut < max / 2) cut = max - 1;
        return s.substring(0, cut).stripTrailing() + "…";
    }

    // Aggregating

    private static void add(Map<String, Agg> groups, Map<String, Info> infos, Item item, int depth, Function<String, @Nullable String> english) {
        Info found = infoOf(infos, item, english);
        groups.computeIfAbsent(found.key(), k -> new Agg(found)).count += item.count;
        if (depth >= MAX_DEPTH || item.contents == null) return;
        for (Item inner : item.contents) if (inner != null) add(groups, infos, inner, depth + 1, english);
    }

    private static Info infoOf(Map<String, Info> infos, Item item, Function<String, @Nullable String> english) {
        String key = clamp(item.indexKey(), MAX_TEXT);
        Info info = infos.get(key);
        if (info != null) return info;

        String en = english.apply(item.id);
        if (en == null || en.isBlank()) en = Item.prettyName(item.id);
        String name = item.customName && item.customText != null && !item.customText.isBlank() ? item.customText : en;
        List<String> details = new ArrayList<>();
        if (item.enchantLevels != null) {
            List<String> levels = new ArrayList<>(item.enchantLevels);
            levels.sort(null);
            for (String entry : levels) details.add(enchantment(entry));
        }
        if (item.potion != null) details.add(title(item.potion));
        boolean enchanted = item.enchants != null && !item.enchants.isEmpty();
        info = new Info(key, clamp("minecraft:" + item.id, MAX_TEXT), clamp(name, MAX_TEXT), clamp(en, MAX_TEXT), Math.max(1, item.maxStack), details, enchanted);
        infos.put(key, info);
        return info;
    }

    /**
     * The shulkers[] group key of a shulker with something in it ("s:" and 10 hex digits of the SHA-1 of its item key and its
     * contents), or null for anything else. The same key addShulker files it under, so an order's {@code shulker} line finds it.
     */
    public static @Nullable String shulkerKey(Item item) {
        if (!item.isShulker() || item.contents == null || item.contents.isEmpty()) return null;
        StringBuilder canon = new StringBuilder();
        int at = 0;
        boolean any = false;
        for (Item inner : item.contents) {
            int fallback = at++;
            if (inner == null) continue;
            int slot = inner.slot >= 0 ? inner.slot : fallback;
            if (slot > 26) continue;
            if (canon.length() > 0) canon.append(',');
            canon.append(slot).append(':').append(clamp(inner.indexKey(), MAX_TEXT)).append(':').append(inner.count);
            any = true;
        }
        if (!any) return null;
        return "s:" + sha1(clamp(item.indexKey(), MAX_TEXT) + "|" + canon).substring(0, 10);
    }

    /** A top-level shulker with something in it, grouped with identical ones. */
    private static void addShulker(Map<String, Shulker> shulkers, Map<String, Info> infos, Item item, Function<String, @Nullable String> english) {
        if (!item.isShulker() || item.contents == null || item.contents.isEmpty()) return;
        List<int[]> slots = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        StringBuilder canon = new StringBuilder();
        int at = 0;
        for (Item inner : item.contents) {
            int fallback = at++;
            if (inner == null) continue;
            int slot = inner.slot >= 0 ? inner.slot : fallback;
            if (slot > 26) continue;
            String k = infoOf(infos, inner, english).key();
            slots.add(new int[] {slot, inner.count});
            keys.add(k);
            if (canon.length() > 0) canon.append(',');
            canon.append(slot).append(':').append(k).append(':').append(inner.count);
        }
        if (slots.isEmpty()) return;

        Info info = infoOf(infos, item, english);
        String key = "s:" + sha1(info.key() + "|" + canon).substring(0, 10);
        Shulker sh = shulkers.get(key);
        if (sh == null) {
            sh = new Shulker(info, key);
            sh.slots.addAll(slots);
            sh.keys.addAll(keys);
            shulkers.put(key, sh);
        }
        sh.count++;
    }

    // Names

    /** "sharpness:5" or "minecraft:sharpness:5" as "Sharpness V". */
    private static String enchantment(String entry) {
        int colon = entry.lastIndexOf(':');
        if (colon < 0) return title(entry);
        String name = entry.substring(0, colon);
        int sep = name.indexOf(':');
        if (sep >= 0) name = name.substring(sep + 1);
        try {
            return title(name) + level(Integer.parseInt(entry.substring(colon + 1)));
        } catch (NumberFormatException e) {
            return title(name);
        }
    }

    private static String level(int n) {
        String[] roman = {"", " I", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"};
        return n >= 1 && n < roman.length ? roman[n] : " " + n;
    }

    static String title(String path) {
        StringBuilder sb = new StringBuilder();
        for (String word : path.replace('/', '_').split("_")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return sb.toString();
    }

    public static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
