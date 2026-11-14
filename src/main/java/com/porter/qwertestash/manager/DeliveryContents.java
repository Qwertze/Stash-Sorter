package com.porter.qwertestash.manager;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.porter.qwertestash.orders.DeliveryBook;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.Stash;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What is in the delivery storage's chests (1.11.0, PROTOCOL.md "delivery"): the body of {@code POST /api/v1/delivery} and its
 * hash. The contents are what the sorter last read through an open delivery chest window ({@link Stash.Box#contents} and
 * {@link Stash.Box#readAt}, saved with the stash). They are never part of the index, the search or the stock counts, and
 * there are no positions anywhere in what is built. Pure data in, JSON out: it can run on any thread.
 */
public final class DeliveryContents {
    private DeliveryContents() {
    }

    public static final int MAX_CHESTS = 16;
    public static final int MAX_CONTENTS = 27;
    public static final int MAX_NAME = 64;
    public static final int MAX_COUNT = 10_000;

    /** One chest as the bot knows it, in fill order. {@code contents} null (or {@code readAt} 0) means never read. */
    public record Chest(int size, boolean full, long readAt, Item @Nullable [] contents) {
    }

    /** A finished report: the JSON body, the hash of the chests (readAt left out) and the newest read. */
    public record Built(String server, String stash, String player, String hash, long readAt, int chests, byte[] json) {
    }

    private static final Gson GSON = new Gson();
    private static final Gson GSON_NULLS = new GsonBuilder().serializeNulls().create();

    /**
     * The chests of the stash's delivery storage in fill order: the ones not known to be full first (lowest first, so
     * chest 0 is where the next shulker goes), then the full ones (lowest first). At most {@link #MAX_CHESTS}.
     */
    public static List<Chest> collect(Stash stash) {
        List<Chest> open = new ArrayList<>(), full = new ArrayList<>();
        if (stash.deliveryStorage == null) return open;
        for (DeliveryBook.Entry e : new DeliveryBook(stash.deliveryStorage).sorted()) {
            int size = Math.max(1, e.parts().size()) * 27 >= 54 ? 54 : 27;
            Stash.Box box = null;
            for (DeliveryBook.Pos p : e.parts()) {
                box = stash.boxAt(new BlockPos(p.x(), p.y(), p.z()));
                if (box != null) break;
            }
            Item[] contents = box == null ? null : box.contents;
            long readAt = box == null ? 0 : box.readAt;
            if (contents != null) size = contents.length >= 54 ? 54 : 27;
            (e.full ? full : open).add(new Chest(size, e.full, readAt, contents));
        }
        open.addAll(full);
        return open.size() > MAX_CHESTS ? new ArrayList<>(open.subList(0, MAX_CHESTS)) : open;
    }

    /** The body the server accepts is at most 256 KiB; the report is degraded to stay under this. */
    public static final int MAX_BYTES = 230_000;

    /**
     * Builds the report, or null when there are no chests. When it would be over {@link #MAX_BYTES} it is degraded: first the
     * shulkers lose their {@code contents} (they keep {@code shulker:true}), then the last chests are sent as unknown
     * ({@code items} null, {@code readAt} 0) one by one until it fits.
     */
    public static @Nullable Built build(String server, String stash, String player, List<Chest> chests) {
        if (!com.porter.qwertestash.farm.FarmerGuard.allowDeliveryContents()) return null; // the farmer role
        if (chests.isEmpty()) return null;
        int count = Math.min(MAX_CHESTS, chests.size());
        Built b = build(server, stash, player, chests, true, count);
        if (b.json().length <= MAX_BYTES) return b;
        b = build(server, stash, player, chests, false, count);
        for (int known = count - 1; b.json().length > MAX_BYTES && known >= 0; known--) b = build(server, stash, player, chests, false, known);
        return b;
    }

    /** @param withContents shulker summaries included; @param knownChests only the first this many chests keep their items */
    private static Built build(String server, String stash, String player, List<Chest> chests, boolean withContents, int knownChests) {
        JsonArray array = new JsonArray();
        long newest = 0;
        List<Long> reads = new ArrayList<>();
        int n = 0;
        for (Chest c : chests) {
            if (n >= MAX_CHESTS) break;
            boolean read = c.contents() != null && c.readAt() > 0 && n < knownChests;
            int size = c.size() >= 54 ? 54 : 27;
            JsonObject o = new JsonObject();
            o.addProperty("n", n++);
            o.addProperty("size", size);
            o.addProperty("full", c.full());
            if (!read) o.add("items", com.google.gson.JsonNull.INSTANCE);
            else o.add("items", items(c.contents(), size, withContents));
            array.add(o);
            long at = read ? c.readAt() : 0;
            reads.add(at);
            newest = Math.max(newest, at);
        }
        // The hash leaves every readAt out, so a read that found nothing changed is not a change
        String hash = ManagerIndex.sha1(GSON_NULLS.toJson(array));
        for (int i = 0; i < array.size(); i++) array.get(i).getAsJsonObject().addProperty("readAt", reads.get(i));

        String who = ManagerIndex.clamp(player, 256);
        String srv = ManagerIndex.clamp(server, 256), st = ManagerIndex.clamp(stash, 256);
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("server", srv);
        root.addProperty("stash", st);
        root.addProperty("player", who);
        root.addProperty("hash", hash);
        root.addProperty("readAt", newest);
        root.add("chests", array);
        return new Built(srv, st, who, hash, newest, array.size(), GSON_NULLS.toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    private static JsonArray items(Item[] contents, int size, boolean withContents) {
        JsonArray out = new JsonArray();
        for (int slot = 0; slot < contents.length && slot < size; slot++) {
            Item item = contents[slot];
            if (item == null || item.id == null || item.count <= 0) continue;
            JsonObject o = new JsonObject();
            o.addProperty("slot", slot);
            o.addProperty("id", ManagerIndex.clamp("minecraft:" + item.id, 256));
            o.addProperty("name", name(item));
            o.addProperty("count", Math.min(MAX_COUNT, item.count));
            o.addProperty("maxStack", Math.max(1, item.maxStack));
            int[] dur = ManagerIndex.durability(item.damage, item.maxDamage);
            if (dur[0] > 0) {
                o.addProperty("damage", dur[0]);
                o.addProperty("maxDamage", dur[1]);
            }
            if (item.isShulker()) {
                o.addProperty("shulker", true);
                if (withContents) o.add("contents", summary(item));
            }
            out.add(o);
        }
        return out;
    }

    /** The hover name, cut to {@link #MAX_NAME} characters, control characters removed. */
    static String name(Item item) {
        String text = item.customName && item.customText != null && !item.customText.isBlank() ? item.customText : Item.prettyName(item.id);
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isISOControl(ch)) sb.append(ch);
        }
        return ManagerIndex.clamp(sb.toString().strip(), MAX_NAME);
    }

    /** What is in a shulker: the stacks grouped by id (counts added), in the order the ids first appear, at most {@link #MAX_CONTENTS}. */
    static JsonArray summary(Item shulker) {
        Map<String, Long> byId = new LinkedHashMap<>();
        if (shulker.contents != null) {
            for (Item inner : shulker.contents) {
                if (inner == null || inner.id == null || inner.count <= 0) continue;
                byId.merge(ManagerIndex.clamp("minecraft:" + inner.id, 256), (long) inner.count, Long::sum);
            }
        }
        JsonArray out = new JsonArray();
        for (Map.Entry<String, Long> e : byId.entrySet()) {
            if (out.size() >= MAX_CONTENTS) break;
            JsonObject o = new JsonObject();
            o.addProperty("id", e.getKey());
            o.addProperty("count", (int) Math.min(MAX_COUNT, e.getValue()));
            out.add(o);
        }
        return out;
    }

    /** Whether a heartbeat reply asks for the delivery contents. Missing or broken means no. */
    public static boolean needsDelivery(String body) {
        try {
            var e = SafeJson.parse(body);
            return e.isJsonObject() && e.getAsJsonObject().has("needDelivery") && e.getAsJsonObject().get("needDelivery").getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Whether a heartbeat reply shows the server knows delivery reports: it carries a {@code needDelivery} key, true or false. */
    public static boolean supportsDelivery(String body) {
        try {
            var e = SafeJson.parse(body);
            return e.isJsonObject() && e.getAsJsonObject().has("needDelivery");
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * When to send the report. Only once a heartbeat reply has shown the server supports it; at most once every
     * {@link #DEBOUNCE_MS}; only when the key is not the one last sent or the server asked (unless that key was rejected, then not
     * until it changes); and after a failure not before a retry time of its own (60 s, doubling to 10 min), which has nothing to
     * do with the manager's backoff. Tick thread only.
     */
    /**
     * When "Delivery report sent: N chests" goes to the log (the same line as the Qwerte addon's): the first success, the first
     * success after a failure, and otherwise at most once every {@link #INTERVAL_MS}. No positions or names are ever logged.
     */
    public static final class SentLog {
        public static final long INTERVAL_MS = 60_000;
        private long lastLogged = Long.MIN_VALUE;
        private boolean failedSince = true;

        /** A report was taken by the server: true when the line should be logged now. */
        public synchronized boolean sent(long now) {
            if (failedSince || now - lastLogged >= INTERVAL_MS) {
                failedSince = false;
                lastLogged = now;
                return true;
            }
            return false;
        }

        /** A send failed (offline, refused, or an error reply). */
        public synchronized void failed() {
            failedSince = true;
        }

        public static String line(int chests) {
            return "Delivery report sent: " + chests + (chests == 1 ? " chest" : " chests");
        }
    }

    public static final class Schedule {
        public static final long DEBOUNCE_MS = 5_000;
        public static final long RETRY_MIN_MS = 60_000, RETRY_MAX_MS = 600_000;

        private @Nullable String sentKey, rejectedKey;
        private boolean asked, supported;
        private long lastAttempt = Long.MIN_VALUE / 2, retryAt = Long.MIN_VALUE / 2, retryDelay = RETRY_MIN_MS;

        /** What the last heartbeat reply showed: whether the server knows delivery reports. */
        public void supported(boolean yes) {
            supported = yes;
        }

        /** The server asked for it (heartbeat reply). */
        public void ask() {
            asked = true;
        }

        public boolean due(@Nullable String key, long now) {
            if (key == null || !supported) return false;
            if (!(asked && !key.equals(rejectedKey)) && key.equals(sentKey)) return false;
            return now - lastAttempt >= DEBOUNCE_MS && now >= retryAt;
        }

        /** A request is going out now. */
        public void attempt(long now) {
            lastAttempt = now;
        }

        /** The server took this key. */
        public void sent(String key) {
            sentKey = key;
            rejectedKey = null;
            asked = false;
            retryDelay = RETRY_MIN_MS;
            retryAt = Long.MIN_VALUE / 2;
        }

        /** The server refused this key (400, 413): not sent again, and asking does nothing for it, until the contents change. */
        public void rejected(String key) {
            sentKey = key;
            rejectedKey = key;
            asked = false;
        }

        /** Any other failure (unreachable, unsigned, 404, 5xx): the next try is a while off, longer each time. */
        public void failed(long now) {
            retryAt = now + retryDelay;
            retryDelay = Math.min(RETRY_MAX_MS, retryDelay * 2);
        }

        /** Forget what was sent (the owner changed, the session was reset): the next key is sent again. */
        public void forget() {
            sentKey = null;
            rejectedKey = null;
            asked = false;
        }

        /** A new session: nothing is known about the server either. */
        public void reset() {
            forget();
            supported = false;
            retryAt = Long.MIN_VALUE / 2;
            retryDelay = RETRY_MIN_MS;
        }
    }
}
