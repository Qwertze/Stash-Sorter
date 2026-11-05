package com.porter.qwertestash.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What the proxy is doing, for the dashboard's Activity tab (PROTOCOL.md, "Additions: activity"): a snapshot built on
 * request and a bounded ring of events. Every source is read on its own inside a try/catch (LinkageError included), so a
 * source that fails or is missing in this proxy version leaves a gap and never breaks the snapshot. Nothing secret is read:
 * no tokens, no keys, no permissions. Reads are of cheap, thread-safe state; the snapshot is built off the bot tick.
 */
public final class ActivityFeed {
    /** Where the plugin's own code reports to. */
    public static final ActivityFeed INSTANCE = new ActivityFeed();

    private static final int RING = 200, MAX_PER_PUSH = 100, MAX_MODULES = 300, MAX_TEXT = 300;

    private record Event(long seq, long at, String source, String level, String text) {
    }

    /** One thing that is working now. */
    public record Item(String source, String kind, String title, @Nullable String detail, int @Nullable [] progress, String level) {
    }

    /** A snapshot and where its events end. */
    public record Snap(JsonObject json, long lastEvent, String signature) {
    }

    private final ArrayDeque<Event> ring = new ArrayDeque<>();
    private long seq;
    private long acked;
    private final Map<String, Long> since = new HashMap<>();
    private final Map<String, Boolean> moduleState = new HashMap<>();

    /** The orders hook: the active stash order (phase and progress) once the order runner exists. Null item: none. */
    private volatile @Nullable Supplier<Item> orders;
    /** The plugin's own items (stash sorter, stash manager link). */
    private volatile @Nullable Supplier<List<Item>> own;
    /** The proxy side, so the feed can be used and tested without one. */
    private volatile Sources sources = new Sources() {
    };

    private ActivityFeed() {
    }

    /** What the feed reads from the proxy. The default reads the real one; a test gives its own. */
    public interface Sources {
        default JsonObject connection() {
            return ProxySources.connection();
        }

        default List<Item> processes() {
            return ProxySources.processes();
        }

        default List<ProxySources.ModuleInfo> modules() {
            return ProxySources.modules();
        }

        default JsonArray plugins() {
            return ProxySources.plugins();
        }
    }

    public void setSources(Sources s) {
        this.sources = s;
    }

    public void setOrders(@Nullable Supplier<Item> orders) {
        this.orders = orders;
    }

    public void setOwn(@Nullable Supplier<List<Item>> own) {
        this.own = own;
    }

    // Events

    public void event(String source, String level, String text) {
        if (text == null || text.isBlank()) return;
        synchronized (ring) {
            ring.addLast(new Event(++seq, System.currentTimeMillis(), cap(source, 80), level(level), cap(text, MAX_TEXT)));
            while (ring.size() > RING) ring.pollFirst();
        }
    }

    /** The server took the events up to this one. */
    public void ack(long lastEvent) {
        synchronized (ring) {
            acked = Math.max(acked, lastEvent);
            while (!ring.isEmpty() && ring.peekFirst().seq() <= acked) ring.pollFirst();
        }
    }

    private static String level(String level) {
        return "warn".equals(level) || "error".equals(level) ? level : "info";
    }

    static String cap(@Nullable String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ');
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    // Snapshot

    public Snap snapshot() {
        long now = System.currentTimeMillis();
        JsonObject root = new JsonObject();
        root.addProperty("at", now);

        JsonObject connection = safe(() -> sources.connection(), new JsonObject());
        root.add("connection", connection);

        List<Item> items = new ArrayList<>();
        addAll(items, () -> sources.processes());
        Supplier<List<Item>> o = own;
        if (o != null) addAll(items, o);
        Supplier<Item> ord = orders;
        if (ord != null) {
            Item item = safe(ord::get, null);
            if (item != null) items.add(item);
        }
        // Queue waiting is an activity of its own
        if ("queue".equals(str(connection, "state"))) {
            String eta = str(connection, "queueEta");
            items.add(new Item("Proxy", "process", "In the 2b2t queue: position " + (connection.has("queuePosition") ? connection.get("queuePosition").getAsString() : "?"),
                eta == null ? null : "ETA " + eta, null, "info"));
        }

        StringBuilder signature = new StringBuilder(str(connection, "state") == null ? "" : str(connection, "state"));
        JsonArray current = new JsonArray();
        synchronized (since) {
            Map<String, Boolean> still = new HashMap<>();
            for (Item raw : items) {
                Item item = scrub(raw);
                String key = item.source() + "|" + item.title();
                since.putIfAbsent(key, now);
                still.put(key, true);
                JsonObject j = new JsonObject();
                j.addProperty("source", cap(item.source(), MAX_TEXT));
                j.addProperty("kind", item.kind());
                j.addProperty("title", cap(item.title(), MAX_TEXT));
                if (item.detail() != null && !item.detail().isBlank()) j.addProperty("detail", cap(item.detail(), MAX_TEXT));
                if (item.progress() != null) {
                    JsonObject p = new JsonObject();
                    p.addProperty("done", Math.max(0, item.progress()[0]));
                    p.addProperty("total", Math.max(0, item.progress()[1]));
                    j.add("progress", p);
                }
                j.addProperty("since", since.get(key));
                j.addProperty("level", level(item.level()));
                current.add(j);
                signature.append('\n').append(key);
            }
            since.keySet().removeIf(k -> !still.containsKey(k));
        }
        root.add("current", current);

        JsonArray modules = new JsonArray();
        List<ProxySources.ModuleInfo> infos = safe(() -> sources.modules(), List.<ProxySources.ModuleInfo>of());
        for (ProxySources.ModuleInfo m : infos) {
            if (modules.size() >= MAX_MODULES) break;
            JsonObject j = new JsonObject();
            j.addProperty("name", cap(m.name(), 80));
            j.addProperty("plugin", cap(m.plugin(), 80));
            j.addProperty("enabled", m.enabled());
            if (m.status() != null && !m.status().isBlank()) j.addProperty("status", cap(m.status(), MAX_TEXT));
            modules.add(j);
            Boolean was = moduleState.put(m.plugin() + "/" + m.name(), m.enabled());
            if (was != null && was != m.enabled()) event(m.name(), "info", m.name() + (m.enabled() ? " enabled" : " disabled"));
        }
        root.add("modules", modules);
        root.add("plugins", safe(() -> sources.plugins(), new JsonArray()));

        JsonArray events = new JsonArray();
        long last;
        synchronized (ring) {
            last = acked;
            int n = 0;
            for (Event e : ring) {
                if (e.seq() <= acked) continue;
                if (n++ >= MAX_PER_PUSH) break;
                JsonObject j = new JsonObject();
                j.addProperty("at", e.at());
                j.addProperty("source", e.source());
                j.addProperty("level", e.level());
                j.addProperty("text", e.text());
                events.add(j);
                last = e.seq();
            }
        }
        root.add("events", events);
        return new Snap(root, last, signature.toString());
    }

    /** Three whole numbers in a row (x y z, "1, 2, 3"): a position. */
    private static final java.util.regex.Pattern POSITION = java.util.regex.Pattern.compile("x=-?\\d+,?\\s*y=-?\\d+,?\\s*z=-?\\d+|(?<![\\w.\\-])-?\\d{1,8}(?:\\s*,\\s*|\\s+)-?\\d{1,4}(?:\\s*,\\s*|\\s+)-?\\d{1,8}(?![\\w.])");

    /**
     * Titles are not blurred on the website, details are. A title that carries a position gets it replaced by "(position)",
     * and the original text goes first in the detail.
     */
    static Item scrub(Item item) {
        String title = item.title();
        if (title == null || !POSITION.matcher(title).find()) return item;
        String clean = POSITION.matcher(title).replaceAll("(position)");
        String detail = item.detail() == null || item.detail().isBlank() ? title : title + "; " + item.detail();
        return new Item(item.source(), item.kind(), clean, detail, item.progress(), item.level());
    }

    private void addAll(List<Item> out, Supplier<List<Item>> source) {
        List<Item> got = safe(source::get, List.<Item>of());
        if (got != null) out.addAll(got);
    }

    private static @Nullable String str(JsonObject o, String field) {
        return o.has(field) && o.get(field).isJsonPrimitive() ? o.get(field).getAsString() : null;
    }

    /** One source, on its own: whatever goes wrong yields the fallback. */
    static <T> T safe(Supplier<T> s, T fallback) {
        try {
            T v = s.get();
            return v == null ? fallback : v;
        } catch (RuntimeException | LinkageError e) {
            return fallback;
        }
    }

}
