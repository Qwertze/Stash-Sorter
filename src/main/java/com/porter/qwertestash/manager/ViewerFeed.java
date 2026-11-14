package com.porter.qwertestash.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * The live viewer's bot side (see the viewer contract in the Stash Manager server). While a browser watches, one daemon thread
 * ("dashboard-viewer") builds a frame five times a second and posts it: the tiles that changed, the player, the entities, the
 * HUD and new chat lines. When nobody watches there is no thread, no request and no chunk scanning; the only thing kept is the
 * chat ring (50 lines).
 * <p>
 * One request at a time: the thread posts and waits, and the state is read again for the next frame, so nothing queues. State
 * is only counted as sent when the server accepted the frame; after a failure the next frame is a full one. Nothing here logs
 * chat, positions or names.
 */
public final class ViewerFeed {
    public static final int MAX_RADIUS = 12;
    public static final int MAX_ENTITIES = 256;
    public static final int MAX_CHAT_PER_FRAME = 20;
    public static final int CHAT_RING = 50;
    /** A frame stays well under the server's 512 KiB: at about 1.1 KB a tile, this many fit. */
    static final int MAX_TILES_PER_FRAME = 300;
    static final long KEEPALIVE_MS = 2_000, HUD_MS = 5_000, SWEEP_MS = 2_000, SETTLE_MS = 40;
    /** No 200 reply for this long (while there was something to send): the feed stops by itself. */
    static final long GIVE_UP_MS = 30_000;

    /** What the server asked for: {@code viewer} in the bot/next reply. */
    public record Params(int radius, int hz, String session) {
    }

    /** The server's answer to a frame; code &lt;= 0 means it could not be reached. */
    public record Reply(int code, boolean watching, boolean resync, long retryMs) {
    }

    /** Posts a frame (signed, by the dashboard link). Returns null when nothing could be sent. */
    public interface Poster {
        @Nullable Reply post(JsonObject body);
    }

    public record Self(double x, double y, double z, float yaw) {
    }

    public record World(String dim, int minY, int height) {
    }

    /** An entity as the game reader sees it; {@code kind} is player, mob, item or other. Rounded when a frame is built. */
    public record Ent(int id, String kind, @Nullable String name, @Nullable String type, int count, double x, double y, double z, int yaw) {
    }

    /** What a frame reads from the game. Called only from the viewer thread; may throw (the frame is skipped then). */
    public interface Game {
        /** The world now, or null when the bot is not in one. */
        @Nullable World world();

        @Nullable Self self();

        /**
         * The entities in these chunks and the radius around them (the feed picks and caps), the bot itself left out. Entities
         * outside are left out before anything else about them is looked up.
         */
        List<Ent> entities(int chunkX, int chunkZ, int radius);

        /** The HUD (hp, food, hotbar...), or null. */
        @Nullable JsonObject hud();

        MapTiles.ColumnSource columns();
    }

    // Chat

    public record ChatLine(long id, long t, String k, String s, @Nullable String to, String m) {
    }

    /** The last 50 chat lines, kept whether or not anyone watches. Text is cut to the wire limits when it comes in. */
    public static final class ChatRing {
        private final ArrayDeque<ChatLine> lines = new ArrayDeque<>();
        private long next = 1;
        private final LongSupplier clock;

        public ChatRing(LongSupplier clock) {
            this.clock = clock;
        }

        public synchronized void add(String kind, @Nullable String sender, @Nullable String to, @Nullable String message) {
            if (message == null) return;
            lines.addLast(new ChatLine(next++, clock.getAsLong(), kind, cut(sender == null ? "" : sender, 16), to == null ? null : cut(to, 16), cut(message, 256)));
            while (lines.size() > CHAT_RING) lines.removeFirst();
        }

        /** Up to {@code max} lines newer than {@code afterId}, oldest first. */
        synchronized List<ChatLine> after(long afterId, int max) {
            List<ChatLine> out = new ArrayList<>();
            for (ChatLine l : lines) if (l.id() > afterId && out.size() < max) out.add(l);
            return out;
        }

        /** The newest {@code max} lines, oldest first. */
        synchronized List<ChatLine> last(int max) {
            List<ChatLine> all = new ArrayList<>(lines);
            return all.subList(Math.max(0, all.size() - max), all.size());
        }

        synchronized long latestId() {
            return next - 1;
        }
    }

    /** The process-wide ring the chat events feed. */
    public static final ChatRing CHAT = new ChatRing(System::currentTimeMillis);

    /** Window 0 (the player's inventory): the main inventory's first and last slot, sent as {@code hud.inv}. */
    static final int INV_FIRST = 9, INV_LAST = 35;
    /** The server's cap on {@code md}: a larger max damage is left out (no bar) rather than refused. */
    static final int MAX_DAMAGE = 100_000;

    /**
     * A HUD item: {@code {id, c}}, plus {@code d} (damage, 0..md) and {@code md} (max damage, 1..100,000) for damageable
     * items only. Nothing else (never a custom name).
     */
    static JsonObject hudItem(String id, int count, int damage, int maxDamage) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("c", count);
        if (maxDamage > 0 && maxDamage <= MAX_DAMAGE) {
            o.addProperty("d", Math.max(0, Math.min(maxDamage, damage)));
            o.addProperty("md", maxDamage);
        }
        return o;
    }

    /** Slots {@code from}..{@code to} (inclusive) as a JSON list, each from {@code slot} (an item or JSON null). */
    static JsonArray hudSlots(java.util.function.IntFunction<JsonElement> slot, int from, int to) {
        JsonArray a = new JsonArray();
        for (int i = from; i <= to; i++) a.add(slot.apply(i));
        return a;
    }

    /** At most {@code max} chars, never ending in the first half of a surrogate pair. */
    static String cut(String s, int max) {
        if (s.length() <= max) return s;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    // State

    private final Game game;
    private final Poster poster;
    private final ChatRing chat;
    private final LongSupplier clock;
    private final boolean threaded;
    private final String botId;
    private final @Nullable Runnable onStopped;

    private volatile boolean active;
    private volatile @Nullable Params params;
    private volatile @Nullable Thread thread;
    private volatile @Nullable String lastError;
    /** Bumped on every stop and start: a thread (or a reply) from an earlier one does nothing any more. */
    private volatile int generation;
    private final AtomicBoolean needFull = new AtomicBoolean(true);
    /** Tiles that may have changed: key to the time they were marked. */
    private final ConcurrentHashMap<Long, Long> dirty = new ConcurrentHashMap<>();
    /** Chunks the server told us to forget: key to the time. */
    private final ConcurrentHashMap<Long, Long> unloaded = new ConcurrentHashMap<>();

    // Guarded by this (the build and the commit of a frame; the post itself is made outside it)
    private String session = "";
    private long seq;
    private @Nullable String lastDim;
    private final Map<Long, Integer> sentCrc = new HashMap<>();
    private @Nullable Self lastSelf;
    private @Nullable List<Ent> lastEnts;
    private @Nullable JsonObject lastHud;
    private long lastHudAt, lastPostAt, chatSent, blockedUntil, ticks, lastOkAt;
    private int failures;
    /** The server wasn't reached (or refused): a bare probe goes first, the full frame only after it was answered. */
    private boolean probing;

    private static final Map<Integer, int[][]> OFFSETS = new ConcurrentHashMap<>();

    public ViewerFeed(Game game, Poster poster, String botId, @Nullable Runnable onStopped) {
        this(farmerChecked(game), poster, CHAT, System::currentTimeMillis, true, botId, onStopped);
    }

    /** The farmer role never has a viewer feed (FARM P5a). */
    private static Game farmerChecked(Game game) {
        if (!com.porter.qwertestash.farm.FarmerGuard.allowViewerFeed()) throw new IllegalStateException("farmer role");
        return game;
    }

    ViewerFeed(Game game, Poster poster, ChatRing chat, LongSupplier clock, boolean threaded, String botId, @Nullable Runnable onStopped) {
        this.game = game;
        this.poster = poster;
        this.chat = chat;
        this.clock = clock;
        this.threaded = threaded;
        this.botId = botId;
        this.onStopped = onStopped;
    }

    public boolean isActive() {
        return active;
    }

    public @Nullable String lastError() {
        return lastError;
    }

    /** From the bot/next reply: nobody (null params or watching false) stops it; otherwise starts it, or follows a new session. */
    public synchronized void update(boolean watching, @Nullable Params p) {
        if (!watching || p == null) {
            stop();
            return;
        }
        if (!active) {
            needFull.set(true);
            probing = false;
            failures = 0;
            blockedUntil = 0;
            lastOkAt = clock.getAsLong();
        }
        params = new Params(Math.max(1, Math.min(MAX_RADIUS, p.radius())), Math.max(1, Math.min(5, p.hz())), p.session());
        active = true;
        if (threaded) {
            Thread t = thread;
            if (t == null || !t.isAlive()) {
                final int gen = ++generation;
                t = new Thread(() -> run(gen), "dashboard-viewer");
                t.setDaemon(true);
                thread = t;
                t.start();
            }
        }
    }

    /**
     * Stops at once: no more frames. The thread ends by itself (a request in flight finishes on its own and its reply is ignored);
     * this neither waits for it nor blocks. Safe to repeat.
     */
    public synchronized void stop() {
        active = false;
        generation++;
        Thread t = thread;
        thread = null;
        dirty.clear();
        unloaded.clear();
        if (t != null && t != Thread.currentThread()) t.interrupt();
    }

    /** The next frame is a full one (a disconnect, a resync). */
    public void resync() {
        needFull.set(true);
    }

    /** A block or chunk changed: that tile and the one to its south (whose top row is shaded against it) are looked at again. */
    public void markDirty(int chunkX, int chunkZ) {
        if (!active) return;
        long now = clock.getAsLong();
        dirty.put(key(chunkX, chunkZ), now);
        dirty.put(key(chunkX, chunkZ + 1), now);
        unloaded.remove(key(chunkX, chunkZ));
    }

    /** The server forgot this chunk: its tile is dropped (unless the chunk is loaded again by then). */
    public void markUnloaded(int chunkX, int chunkZ) {
        if (!active) return;
        unloaded.put(key(chunkX, chunkZ), clock.getAsLong());
    }

    static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    static int keyX(long k) {
        return (int) (k >> 32);
    }

    static int keyZ(long k) {
        return (int) k;
    }

    private void run(int gen) {
        while (active && gen == generation && !Thread.currentThread().isInterrupted()) {
            long start = clock.getAsLong();
            try {
                tick(start, gen);
            } catch (Throwable e) {
                // Whatever it was must not end the thread; only the class is kept, never data
                lastError = e.getClass().getSimpleName();
            }
            Params p = params;
            long period = 1000L / (p == null ? 5 : p.hz());
            long wait = Math.max(10, period - (clock.getAsLong() - start));
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** The server said nobody watches. */
    private void deactivate(boolean told) {
        active = false;
        generation++;
        // The thread running this ends with the generation; an update() meanwhile starts a new one
        thread = null;
        dirty.clear();
        unloaded.clear();
        Runnable r = onStopped;
        if (told && r != null) r.run();
    }

    /** One frame: builds it from the game and posts it, if there is anything to say. Returns whether a frame was posted. */
    boolean tick(long now) {
        return tick(now, generation);
    }

    /** What a frame was built from, kept until the server has answered it. */
    private record Pending(JsonObject body, Map<Long, Integer> tiles, List<Long> dropped, Self self, List<Ent> ents, @Nullable JsonObject hud,
                           boolean hudChanged, List<ChatLine> lines, boolean full, boolean probe) {
    }

    boolean tick(long now, int gen) {
        Pending pending;
        synchronized (this) {
            if (gen != generation) return false;
            pending = prepare(now);
            if (pending == null) return false;
        }
        // The one request in flight: made outside the lock, so a stop or a restart never waits for it
        Reply r = poster.post(pending.body());
        synchronized (this) {
            // Stopped (or restarted) meanwhile: this reply belongs to a frame nobody counts on any more
            if (gen != generation) return true;
            apply(pending, r, now);
        }
        return true;
    }

    private @Nullable Pending prepare(long now) {
        Params p = params;
        if (!active || p == null) return null;
        if (!p.session().equals(session)) {
            session = p.session();
            seq = 0;
            needFull.set(true);
            failures = 0;
            blockedUntil = 0;
            probing = false;
            lastOkAt = now;
        }
        // The server hasn't answered for a long time (down, restarted): stop asking. The link starts it again when the server is back
        if (now - lastOkAt > GIVE_UP_MS) {
            deactivate(false);
            return null;
        }
        if (now < blockedUntil) return null;
        World world = game.world();
        Self self = game.self();
        if (world == null || self == null) {
            // Not in a game (disconnected, loading): nothing to read; the next frame once there is one is a full one. Nothing to
            // send is not the server failing
            needFull.set(true);
            lastOkAt = now;
            return null;
        }
        if (probing) return probe(now, self);
        if (!world.dim().equals(lastDim)) {
            lastDim = world.dim();
            needFull.set(true);
        }
        boolean full = needFull.getAndSet(false);
        if (full) {
            sentCrc.clear();
            lastSelf = null;
            lastEnts = null;
            lastHud = null;
            dirty.clear();
            unloaded.clear();
        }
        int radius = p.radius();
        int pcx = (int) Math.floor(self.x()) >> 4, pcz = (int) Math.floor(self.z()) >> 4;
        try {
            return frame(now, p, world, self, full, radius, pcx, pcz);
        } catch (RuntimeException | LinkageError e) {
            // A race with the network thread: this frame is dropped and the next one is complete
            needFull.set(true);
            lastError = e.getClass().getSimpleName();
            return null;
        }
    }

    /** A bare frame (the player only): after a failure the server is asked once before the whole tile set is rebuilt. */
    private Pending probe(long now, Self self) {
        JsonObject body = new JsonObject();
        body.addProperty("botId", botId);
        body.addProperty("v", 1);
        body.addProperty("session", session);
        body.addProperty("seq", ++seq);
        body.addProperty("t", now);
        body.addProperty("full", false);
        body.add("self", selfJson(self));
        return new Pending(body, Map.of(), List.of(), self, List.of(), null, false, List.of(), false, true);
    }

    /** The tile offsets around the player of this radius, nearest first (worked out once per radius). */
    private static int[][] offsets(int radius) {
        return OFFSETS.computeIfAbsent(radius, r -> {
            List<int[]> list = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) list.add(new int[] {dx, dz, dx * dx + dz * dz});
            list.sort(Comparator.comparingInt(a -> a[2]));
            return list.toArray(new int[0][]);
        });
    }

    private @Nullable Pending frame(long now, Params p, World world, Self self, boolean full, int radius, int pcx, int pcz) {
        final int[][] near = offsets(radius);
        dirty.keySet().removeIf(k -> Math.abs(keyX(k) - pcx) > radius || Math.abs(keyZ(k) - pcz) > radius);
        MapTiles.ColumnSource columns = game.columns();

        // Tiles that left the radius, and those the server forgot
        JsonArray drop = new JsonArray();
        List<Long> dropped = new ArrayList<>();
        for (long k : sentCrc.keySet()) {
            if (Math.abs(keyX(k) - pcx) > radius || Math.abs(keyZ(k) - pcz) > radius) dropped.add(k);
        }
        for (var e : unloaded.entrySet()) {
            long k = e.getKey();
            if (now - e.getValue() < SETTLE_MS) continue;
            unloaded.remove(k, e.getValue());
            if (sentCrc.containsKey(k) && !dropped.contains(k) && !columns.loaded(keyX(k), keyZ(k))) {
                dropped.add(k);
                dirty.remove(k);
            }
        }
        for (long k : dropped) {
            JsonArray pair = new JsonArray();
            pair.add(keyX(k));
            pair.add(keyZ(k));
            drop.add(pair);
        }

        // Tiles to look at: new ones, marked ones, and this tick's slice of the sweep (every tile once per 2 s)
        long sweepPeriod = Math.max(1, SWEEP_MS * p.hz() / 1000);
        long phase = ticks++ % sweepPeriod;
        JsonArray tiles = new JsonArray();
        Map<Long, Integer> pending = new LinkedHashMap<>();
        for (int[] o : near) {
            if (pending.size() >= MAX_TILES_PER_FRAME) break;
            int cx = pcx + o[0], cz = pcz + o[1];
            long k = key(cx, cz);
            if (dropped.contains(k) || !columns.loaded(cx, cz)) continue;
            Integer old = sentCrc.get(k);
            Long marked = dirty.get(k);
            boolean ready = marked != null && now - marked >= SETTLE_MS;
            boolean swept = old != null && Math.floorMod(cx * 31 + cz, sweepPeriod) == phase;
            if (!(full || old == null || ready || swept)) continue;
            if (ready) dirty.remove(k, marked);
            try {
                MapTiles.Tile t = MapTiles.encode(columns, cx, cz);
                if (!full && old != null && old == t.crc()) continue;
                JsonObject tile = new JsonObject();
                tile.addProperty("x", cx);
                tile.addProperty("z", cz);
                tile.addProperty("c", t.colorsBase64());
                tile.addProperty("h", t.heightsBase64());
                tiles.add(tile);
                pending.put(k, t.crc());
                // The row below shades against this tile's last row
                if (old != null) dirty.putIfAbsent(key(cx, cz + 1), now);
            } catch (RuntimeException e) {
                // A race with the network thread: looked at again
                dirty.put(k, now);
                lastError = e.getClass().getSimpleName();
            }
        }

        // The rest of the frame
        boolean selfChanged = lastSelf == null || !same(lastSelf, self);
        List<Ent> ents = entities(self, radius, pcx, pcz);
        boolean entsChanged = full || lastEnts == null || !lastEnts.equals(ents);
        JsonObject hud = game.hud();
        boolean hudChanged = hud != null && (full || lastHud == null || !hud.equals(lastHud) || now - lastHudAt >= HUD_MS);
        List<ChatLine> lines = full ? chat.last(MAX_CHAT_PER_FRAME) : chat.after(chatSent, MAX_CHAT_PER_FRAME);

        boolean other = !tiles.isEmpty() || !drop.isEmpty() || entsChanged || hudChanged || !lines.isEmpty();
        if (!full && !other && !selfChanged && now - lastPostAt < KEEPALIVE_MS) return null;

        JsonObject body = new JsonObject();
        body.addProperty("botId", botId);
        body.addProperty("v", 1);
        body.addProperty("session", session);
        body.addProperty("seq", ++seq);
        body.addProperty("t", now);
        body.addProperty("full", full);
        if (full) {
            JsonObject w = new JsonObject();
            w.addProperty("dim", world.dim());
            w.addProperty("minY", world.minY());
            w.addProperty("height", world.height());
            body.add("world", w);
        }
        if (full || selfChanged || !other) body.add("self", selfJson(self));
        if (full || !tiles.isEmpty()) body.add("tiles", tiles);
        if (full || !drop.isEmpty()) body.add("drop", drop);
        if (entsChanged) body.add("ents", entsJson(ents));
        if (hudChanged) body.add("hud", hud);
        if (full || !lines.isEmpty()) body.add("chat", chatJson(lines));
        return new Pending(body, pending, dropped, self, ents, hud, hudChanged, lines, full, false);
    }

    /** The server's answer (or none) to a frame built by {@link #prepare}. */
    private void apply(Pending f, @Nullable Reply r, long now) {
        if (r == null || r.code() <= 0) {
            fail(now, 500L << Math.min(4, failures));
            return;
        }
        if (r.code() == 200) {
            failures = 0;
            lastOkAt = now;
            lastPostAt = now;
            if (f.probe()) {
                // Answered: the whole state follows next
                probing = false;
                needFull.set(true);
            }
            else {
                sentCrc.putAll(f.tiles());
                for (long k : f.dropped()) sentCrc.remove(k);
                lastSelf = f.self();
                lastEnts = f.ents();
                if (f.hudChanged()) {
                    lastHud = f.hud();
                    lastHudAt = now;
                }
                if (!f.lines().isEmpty()) chatSent = f.lines().get(f.lines().size() - 1).id();
                else if (f.full()) chatSent = chat.latestId();
            }
            if (!r.watching()) {
                deactivate(true);
                return;
            }
            if (r.resync()) needFull.set(true);
            return;
        }
        if (r.code() == 429) {
            // The server is up and busy: no probe, the frame is rebuilt complete after the wait
            lastOkAt = now;
            blockedUntil = now + Math.max(200, Math.min(5_000, r.retryMs()));
            needFull.set(true);
            return;
        }
        fail(now, r.code() == 409 ? 2_000 : 500L << Math.min(4, failures));
    }

    /** No answer, or a refusal: wait (growing), then ask with a bare probe before the tile set is built again. */
    private void fail(long now, long delay) {
        failures++;
        blockedUntil = now + Math.min(10_000, delay);
        probing = true;
        needFull.set(true);
    }

    private static boolean same(Self a, Self b) {
        return r1(a.x()) == r1(b.x()) && r1(a.y()) == r1(b.y()) && r1(a.z()) == r1(b.z()) && yaw(a.yaw()) == yaw(b.yaw());
    }

    static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** Minecraft's yaw is unbounded; the wire has it in (-180, 180]. */
    static int yaw(double degrees) {
        double y = degrees % 360.0;
        if (y > 180.0) y -= 360.0;
        else if (y <= -180.0) y += 360.0;
        return (int) Math.round(y) == -180 ? 180 : (int) Math.round(y);
    }

    private static JsonObject selfJson(Self s) {
        JsonObject o = new JsonObject();
        o.addProperty("x", r1(s.x()));
        o.addProperty("y", r1(s.y()));
        o.addProperty("z", r1(s.z()));
        o.addProperty("yaw", yaw(s.yaw()));
        return o;
    }

    /** Players first, then the nearest others, at most 256, only those in the radius, positions rounded to 0.1. */
    List<Ent> entities(Self self, int radius, int pcx, int pcz) {
        List<Ent> all = new ArrayList<>();
        for (Ent e : game.entities(pcx, pcz, radius)) {
            int ecx = (int) Math.floor(e.x()) >> 4, ecz = (int) Math.floor(e.z()) >> 4;
            if (Math.abs(ecx - pcx) > radius || Math.abs(ecz - pcz) > radius) continue;
            all.add(e);
        }
        all.sort(Comparator.<Ent, Integer>comparing(e -> "player".equals(e.kind()) ? 0 : 1)
            .thenComparingDouble(e -> sq(e.x() - self.x()) + sq(e.y() - self.y()) + sq(e.z() - self.z()))
            .thenComparingInt(Ent::id));
        List<Ent> out = new ArrayList<>();
        for (Ent e : all) {
            if (out.size() >= MAX_ENTITIES) break;
            String k = e.kind();
            if (!k.equals("player") && !k.equals("mob") && !k.equals("item")) k = "other";
            out.add(new Ent(e.id(), k, e.name() == null ? null : cut(e.name(), 16), e.type() == null ? null : cut(e.type(), 64), e.count(), r1(e.x()), r1(e.y()), r1(e.z()), yaw(e.yaw())));
        }
        return out;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static JsonArray entsJson(List<Ent> ents) {
        JsonArray a = new JsonArray();
        for (Ent e : ents) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("k", e.kind());
            if (e.name() != null && "player".equals(e.kind())) o.addProperty("n", e.name());
            if (e.type() != null) o.addProperty("t", e.type());
            if (e.count() > 0 && "item".equals(e.kind())) o.addProperty("c", e.count());
            o.addProperty("x", e.x());
            o.addProperty("y", e.y());
            o.addProperty("z", e.z());
            o.addProperty("yaw", e.yaw());
            a.add(o);
        }
        return a;
    }

    private static JsonArray chatJson(List<ChatLine> lines) {
        JsonArray a = new JsonArray();
        for (ChatLine l : lines) {
            JsonObject o = new JsonObject();
            o.addProperty("t", l.t());
            o.addProperty("k", l.k());
            o.addProperty("s", l.s());
            if (l.to() == null) o.add("to", com.google.gson.JsonNull.INSTANCE);
            else o.addProperty("to", l.to());
            o.addProperty("m", l.m());
            a.add(o);
        }
        return a;
    }

    /** Reads {@code viewer} / {@code watchingViewer} from a bot/next reply; null params when not watched. */
    public static @Nullable Params parse(JsonObject reply) {
        JsonElement w = reply.get("watchingViewer");
        if (w == null || !w.isJsonPrimitive() || !w.getAsBoolean()) return null;
        JsonElement v = reply.get("viewer");
        if (v == null || !v.isJsonObject()) return null;
        JsonObject o = v.getAsJsonObject();
        JsonElement session = o.get("session");
        if (session == null || !session.isJsonPrimitive()) return null;
        int radius = intOf(o, "radius", 8), hz = intOf(o, "hz", 5);
        return new Params(Math.max(1, Math.min(MAX_RADIUS, radius)), Math.max(1, Math.min(5, hz)), cut(session.getAsString(), 64));
    }

    private static int intOf(JsonObject o, String field, int fallback) {
        JsonElement e = o.get(field);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsInt() : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
