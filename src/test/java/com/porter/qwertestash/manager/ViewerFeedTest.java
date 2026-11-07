package com.porter.qwertestash.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerFeedTest {
    long now;
    final MapTilesTest.Fake columns = new MapTilesTest.Fake();
    final List<ViewerFeed.Ent> ents = new ArrayList<>();
    ViewerFeed.World world = new ViewerFeed.World("minecraft:overworld", -64, 384);
    ViewerFeed.Self self = new ViewerFeed.Self(8.5, 64, 8.5, 90);
    JsonObject hud = new JsonObject();
    final List<JsonObject> posted = new ArrayList<>();
    ViewerFeed.Reply reply = new ViewerFeed.Reply(200, true, false, 0);
    ViewerFeed.ChatRing chat;
    final AtomicInteger stopped = new AtomicInteger();
    ViewerFeed feed;
    ViewerFeed.Game game;

    @BeforeEach
    void setUp() {
        now = 1_000_000;
        chat = new ViewerFeed.ChatRing(() -> now);
        hud.addProperty("hp", 20.0);
        game = new ViewerFeed.Game() {
            @Override
            public ViewerFeed.@Nullable World world() {
                return world;
            }

            @Override
            public ViewerFeed.@Nullable Self self() {
                return self;
            }

            @Override
            public List<ViewerFeed.Ent> entities(int chunkX, int chunkZ, int radius) {
                return ents;
            }

            @Override
            public @Nullable JsonObject hud() {
                return hud;
            }

            @Override
            public MapTiles.ColumnSource columns() {
                return columns;
            }
        };
        feed = new ViewerFeed(game, body -> {
            posted.add(body);
            return reply;
        }, chat, () -> now, false, "bot1", stopped::incrementAndGet);
        feed.update(true, new ViewerFeed.Params(1, 5, "abc"));
    }

    /** One 200 ms tick; the frame posted, or null. */
    private @Nullable JsonObject tickAndTake() {
        int before = posted.size();
        feed.tick(now);
        now += 200;
        return posted.size() > before ? posted.get(posted.size() - 1) : null;
    }

    private static ViewerFeed.Ent mob(int id, double x, double z) {
        return new ViewerFeed.Ent(id, "mob", null, "zombie", 0, x, 64, z, 0);
    }

    private static ViewerFeed.Ent player(int id, double x, double z) {
        return new ViewerFeed.Ent(id, "player", "P" + id, null, 0, x, 64, z, 0);
    }

    @Test
    void firstFrameIsFullAndComplete() {
        chat.add("public", "Steve", null, "hello");
        ents.add(mob(1, 9, 9));
        JsonObject f = tickAndTake();
        assertNotNull(f);
        assertTrue(f.get("full").getAsBoolean());
        assertEquals("abc", f.get("session").getAsString());
        assertEquals(1, f.get("seq").getAsLong());
        assertEquals("minecraft:overworld", f.getAsJsonObject("world").get("dim").getAsString());
        assertEquals(9, f.getAsJsonArray("tiles").size());
        assertTrue(f.has("self") && f.has("ents") && f.has("hud") && f.has("chat") && f.has("drop"));
        JsonObject tile = f.getAsJsonArray("tiles").get(0).getAsJsonObject();
        assertEquals(344, tile.get("c").getAsString().length());
        assertEquals(684, tile.get("h").getAsString().length());
        assertEquals("hello", f.getAsJsonArray("chat").get(0).getAsJsonObject().get("m").getAsString());
    }

    @Test
    void quietStateSendsNothingUntilTheKeepAlive() {
        tickAndTake();
        assertNull(tickAndTake());
        JsonObject f = null;
        for (int i = 0; i < 12 && f == null; i++) f = tickAndTake();
        assertNotNull(f);
        assertFalse(f.get("full").getAsBoolean());
        assertTrue(f.has("self"));
        assertFalse(f.has("tiles") || f.has("ents") || f.has("chat"));
    }

    @Test
    void entitiesArePlayersFirstThenNearestAndCapped() {
        for (int i = 0; i < 300; i++) ents.add(mob(100 + i, 8.5 + (i % 20) * 0.1, 8.5 + (i / 20) * 0.1));
        ents.add(player(7, 20, 20));
        ents.add(player(8, 10, 10));
        ents.add(mob(9999, 500, 500));
        JsonArray out = tickAndTake().getAsJsonArray("ents");
        assertEquals(256, out.size());
        assertEquals(8, out.get(0).getAsJsonObject().get("id").getAsInt());
        assertEquals(7, out.get(1).getAsJsonObject().get("id").getAsInt());
        assertEquals("player", out.get(1).getAsJsonObject().get("k").getAsString());
        assertEquals("P8", out.get(0).getAsJsonObject().get("n").getAsString());
        assertEquals(100, out.get(2).getAsJsonObject().get("id").getAsInt());
        for (int i = 0; i < out.size(); i++) assertTrue(out.get(i).getAsJsonObject().get("id").getAsInt() != 9999);
    }

    @Test
    void positionsAreRoundedAndLongNamesCut() {
        ents.add(new ViewerFeed.Ent(5, "player", "AVeryLongPlayerNameIndeed", null, 0, 9.123456, 64.98, 9.55, 33));
        JsonObject e = tickAndTake().getAsJsonArray("ents").get(0).getAsJsonObject();
        assertEquals(9.1, e.get("x").getAsDouble());
        assertEquals(65.0, e.get("y").getAsDouble());
        assertEquals(16, e.get("n").getAsString().length());
    }

    @Test
    void chatIsCutAndLimitedPerFrame() {
        chat.add("public", "S".repeat(40), "T".repeat(40), "m".repeat(500));
        JsonObject line = tickAndTake().getAsJsonArray("chat").get(0).getAsJsonObject();
        assertEquals(256, line.get("m").getAsString().length());
        assertEquals(16, line.get("s").getAsString().length());
        assertEquals(16, line.get("to").getAsString().length());

        // 70 lines come in at once: the ring keeps the newest 50 (line 20 on), 20 go out per frame
        for (int i = 0; i < 70; i++) chat.add("system", "", null, "line " + i);
        JsonArray first = tickAndTake().getAsJsonArray("chat");
        assertEquals(20, first.size());
        assertEquals("line 20", first.get(0).getAsJsonObject().get("m").getAsString());
        assertTrue(first.get(0).getAsJsonObject().get("to").isJsonNull());
        JsonArray second = tickAndTake().getAsJsonArray("chat");
        assertEquals("line 40", second.get(0).getAsJsonObject().get("m").getAsString());
    }

    @Test
    void ringHoldsFiftyLines() {
        for (int i = 0; i < 80; i++) chat.add("system", "", null, "x" + i);
        assertEquals(50, chat.last(100).size());
        assertEquals(20, chat.last(20).size());
    }

    @Test
    void watchingFalseStopsAtOnce() {
        reply = new ViewerFeed.Reply(200, false, false, 0);
        assertNotNull(tickAndTake());
        assertFalse(feed.isActive());
        assertEquals(1, stopped.get());
        int n = posted.size();
        chat.add("public", "a", null, "x");
        assertNull(tickAndTake());
        assertEquals(n, posted.size());
    }

    @Test
    void updateFalseStops() {
        feed.update(false, null);
        assertFalse(feed.isActive());
        assertNull(tickAndTake());
    }

    @Test
    void resyncGivesAFullFrameNext() {
        tickAndTake();
        chat.add("public", "a", null, "x");
        JsonObject second = tickAndTake();
        assertFalse(second.get("full").getAsBoolean());
        assertEquals(2, second.get("seq").getAsLong());

        reply = new ViewerFeed.Reply(200, true, true, 0);
        chat.add("public", "a", null, "y");
        tickAndTake();
        reply = new ViewerFeed.Reply(200, true, false, 0);
        JsonObject full = tickAndTake();
        assertTrue(full.get("full").getAsBoolean());
        assertEquals(9, full.getAsJsonArray("tiles").size());
        // And only once
        chat.add("public", "a", null, "z");
        assertFalse(tickAndTake().get("full").getAsBoolean());
    }

    @Test
    void newSessionAndDimensionChangeAreFull() {
        tickAndTake();
        feed.update(true, new ViewerFeed.Params(1, 5, "def"));
        JsonObject f = tickAndTake();
        assertTrue(f.get("full").getAsBoolean());
        assertEquals("def", f.get("session").getAsString());
        assertEquals(1, f.get("seq").getAsLong());

        world = new ViewerFeed.World("minecraft:the_nether", 0, 256);
        assertTrue(tickAndTake().get("full").getAsBoolean());
    }

    @Test
    void failedPostIsFollowedByABareProbeThenAFullFrame() {
        tickAndTake();
        reply = new ViewerFeed.Reply(500, true, false, 0);
        chat.add("public", "a", null, "x");
        tickAndTake();
        reply = new ViewerFeed.Reply(200, true, false, 0);
        // Backed off first; then the server is asked with a frame that carries no tiles
        JsonObject probe = null;
        for (int i = 0; i < 20 && probe == null; i++) probe = tickAndTake();
        assertNotNull(probe);
        assertFalse(probe.get("full").getAsBoolean());
        assertFalse(probe.has("tiles") || probe.has("ents") || probe.has("chat"));
        // Answered: now the whole state
        JsonObject full = tickAndTake();
        assertTrue(full.get("full").getAsBoolean());
        assertEquals(9, full.getAsJsonArray("tiles").size());
    }

    @Test
    void noAnswerBacksOffGrowingAndNeverRebuildsTheTiles() {
        tickAndTake();
        reply = null;
        long start = now;
        int posts = posted.size();
        List<Long> at = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            long before = now;
            int n = posted.size();
            tickAndTake();
            if (posted.size() > n) at.add(before);
        }
        assertTrue(at.size() >= 3);
        // The first attempt after the failure is a probe, and every later one too: no frame with tiles goes out
        for (int i = posts; i < posted.size(); i++) {
            JsonObject b = posted.get(i);
            assertFalse(b.has("tiles"), "no tile set rebuilt while the server doesn't answer");
        }
        // The gaps grow
        long first = at.get(1) - at.get(0), later = at.get(at.size() - 1) - at.get(at.size() - 2);
        assertTrue(later >= first, first + " then " + later);
        assertTrue(start > 0);
    }

    @Test
    void itGivesUpByItselfWhenTheServerNeverAnswers() {
        tickAndTake();
        reply = null;
        for (int i = 0; i < 400 && feed.isActive(); i++) tickAndTake();
        assertFalse(feed.isActive());
        // Not "the server said nobody watches": the link may start it again when the server is back
        assertEquals(0, stopped.get());
    }

    @Test
    void aStopAndStartDuringARequestGivesAFullFrameAndNoCrash() {
        ViewerFeed.Params params = new ViewerFeed.Params(1, 5, "abc");
        feed = new ViewerFeed(game, body -> {
            posted.add(body);
            // Stopped and started again while the request was out
            feed.stop();
            feed.update(true, params);
            return reply;
        }, chat, () -> now, false, "bot1", stopped::incrementAndGet);
        feed.update(true, params);
        tickAndTake();
        JsonObject next = tickAndTake();
        assertNotNull(next);
        assertTrue(next.get("full").getAsBoolean(), "nothing of the stale frame was counted as sent");
        assertTrue(feed.isActive());
    }

    @Test
    void yawIsKeptWithinAHalfTurn() {
        assertEquals(-90, ViewerFeed.yaw(270));
        assertEquals(180, ViewerFeed.yaw(540));
        assertEquals(180, ViewerFeed.yaw(-180));
        assertEquals(10, ViewerFeed.yaw(370));
        assertEquals(-179, ViewerFeed.yaw(181));
        self = new ViewerFeed.Self(8.5, 64, 8.5, 450);
        ents.add(new ViewerFeed.Ent(1, "mob", null, "zombie", 0, 9, 64, 9, 720 + 45));
        JsonObject f = tickAndTake();
        assertEquals(90, f.getAsJsonObject("self").get("yaw").getAsInt());
        assertEquals(45, f.getAsJsonArray("ents").get(0).getAsJsonObject().get("yaw").getAsInt());
    }

    @Test
    void cutNeverSplitsASurrogatePair() {
        String smile = new String(Character.toChars(0x1F600));
        assertEquals("ab", ViewerFeed.cut("ab" + smile, 3));
        assertEquals("ab" + smile, ViewerFeed.cut("ab" + smile, 4));
        assertEquals("abc", ViewerFeed.cut("abcdef", 3));
    }

    @Test
    void aChunkTheServerForgotIsDropped() {
        tickAndTake();
        columns.unloaded.add(MapTilesTest.Fake.k(1, 1));
        feed.markUnloaded(1, 1);
        now += 100;
        JsonObject f = tickAndTake();
        JsonArray drop = f.getAsJsonArray("drop");
        assertEquals(1, drop.size());
        assertEquals(1, drop.get(0).getAsJsonArray().get(0).getAsInt());
        assertEquals(1, drop.get(0).getAsJsonArray().get(1).getAsInt());
        // Back again: sent once more as a new tile
        columns.unloaded.clear();
        feed.markDirty(1, 1);
        now += 100;
        assertEquals(1, tickAndTake().getAsJsonArray("tiles").size());
    }

    @Test
    void aChunkThatIsLoadedAgainIsNotDropped() {
        tickAndTake();
        feed.markUnloaded(1, 1);
        now += 100;
        JsonObject f = tickAndTake();
        assertTrue(f == null || !f.has("drop"));
    }

    @Test
    void tooManyRequestsWaitTheTimeAsked() {
        tickAndTake();
        reply = new ViewerFeed.Reply(429, true, false, 1500);
        chat.add("public", "a", null, "x");
        tickAndTake();
        int n = posted.size();
        reply = new ViewerFeed.Reply(200, true, false, 0);
        for (int i = 0; i < 5; i++) tickAndTake();
        assertEquals(n, posted.size());
        for (int i = 0; i < 5; i++) tickAndTake();
        assertTrue(posted.size() > n);
    }

    @Test
    void dirtyTileIsResentOnlyWhenItChanged() {
        tickAndTake();
        feed.markDirty(0, 0);
        now += 100;
        assertNull(tickAndTake());

        columns.col(8, 8, 90, 3);
        feed.markDirty(0, 0);
        now += 100;
        JsonArray tiles = tickAndTake().getAsJsonArray("tiles");
        assertEquals(1, tiles.size());
        assertEquals(0, tiles.get(0).getAsJsonObject().get("x").getAsInt());
        assertEquals(0, tiles.get(0).getAsJsonObject().get("z").getAsInt());
    }

    @Test
    void changeInATileReshadesTheOneToItsSouth() {
        tickAndTake();
        // Row 15 of chunk (0,0) changes: row 0 of chunk (0,1) is shaded against it
        columns.col(2, 15, 80, 1);
        feed.markDirty(0, 0);
        now += 100;
        JsonArray tiles = tickAndTake().getAsJsonArray("tiles");
        assertEquals(2, tiles.size());
    }

    @Test
    void sweepFindsAChangeNobodyReported() {
        tickAndTake();
        columns.col(1, 1, 99, 5);
        boolean found = false;
        for (int i = 0; i < 11 && !found; i++) {
            JsonObject f = tickAndTake();
            found = f != null && f.has("tiles");
        }
        assertTrue(found);
    }

    @Test
    void movingSendsEnteringTilesAndDropsLeavingOnes() {
        tickAndTake();
        self = new ViewerFeed.Self(8.5 + 16, 64, 8.5, 90);
        JsonObject f = tickAndTake();
        assertEquals(3, f.getAsJsonArray("tiles").size());
        assertEquals(3, f.getAsJsonArray("drop").size());
        JsonArray pair = f.getAsJsonArray("drop").get(0).getAsJsonArray();
        assertEquals(-1, pair.get(0).getAsInt());
    }

    @Test
    void unloadedChunksAreNotSentUntilTheyLoad() {
        columns.unloaded.add(MapTilesTest.Fake.k(1, 1));
        JsonObject f = tickAndTake();
        assertEquals(8, f.getAsJsonArray("tiles").size());
        columns.unloaded.clear();
        JsonObject g = tickAndTake();
        assertEquals(1, g.getAsJsonArray("tiles").size());
    }

    @Test
    void radiusIsCappedAndBigFullFramesAreSplit() {
        feed.update(true, new ViewerFeed.Params(40, 5, "big"));
        JsonObject f = tickAndTake();
        assertTrue(f.get("full").getAsBoolean());
        assertEquals(300, f.getAsJsonArray("tiles").size());
        JsonObject g = tickAndTake();
        assertFalse(g.get("full").getAsBoolean());
        assertEquals(300, g.getAsJsonArray("tiles").size());
        JsonObject h = tickAndTake();
        assertEquals(25 * 25 - 600, h.getAsJsonArray("tiles").size());
    }

    @Test
    void noGameMeansNoFrame() {
        self = null;
        world = null;
        assertNull(tickAndTake());
    }

    @Test
    void theThreadRunsOnlyWhileWatchedAndEndsOnStop() throws Exception {
        feed.update(false, null);
        ViewerFeed.Game game = new ViewerFeed.Game() {
            @Override
            public ViewerFeed.@Nullable World world() {
                return world;
            }

            @Override
            public ViewerFeed.@Nullable Self self() {
                return self;
            }

            @Override
            public List<ViewerFeed.Ent> entities(int chunkX, int chunkZ, int radius) {
                return ents;
            }

            @Override
            public @Nullable JsonObject hud() {
                return null;
            }

            @Override
            public MapTiles.ColumnSource columns() {
                return columns;
            }
        };
        AtomicInteger posts = new AtomicInteger();
        ViewerFeed live = new ViewerFeed(game, body -> {
            posts.incrementAndGet();
            return reply;
        }, chat, System::currentTimeMillis, true, "bot1", null);
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("dashboard-viewer")));
        live.update(true, new ViewerFeed.Params(1, 5, "t1"));
        long end = System.currentTimeMillis() + 3000;
        while (posts.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(20);
        assertTrue(posts.get() > 0);
        live.stop();
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("dashboard-viewer") && t.isAlive()));
        int n = posts.get();
        Thread.sleep(500);
        assertEquals(n, posts.get());
    }

    @Test
    void parsesTheBotNextReply() {
        JsonObject r = JsonParser.parseString("{\"watchingViewer\":true,\"viewer\":{\"radius\":30,\"hz\":5,\"session\":\"9f9f\"}}").getAsJsonObject();
        ViewerFeed.Params p = ViewerFeed.parse(r);
        assertNotNull(p);
        assertEquals(12, p.radius());
        assertEquals("9f9f", p.session());
        assertNull(ViewerFeed.parse(JsonParser.parseString("{\"watchingViewer\":false}").getAsJsonObject()));
        assertNull(ViewerFeed.parse(JsonParser.parseString("{\"watchingViewer\":true}").getAsJsonObject()));
    }

    @Test
    void hudItemsCarryDurabilityOnlyForDamageableItems() {
        JsonObject pick = ViewerFeed.hudItem("minecraft:diamond_pickaxe", 1, 120, 1561);
        assertEquals(120, pick.get("d").getAsInt());
        assertEquals(1561, pick.get("md").getAsInt());
        assertEquals(1, pick.get("c").getAsInt());
        JsonObject fresh = ViewerFeed.hudItem("minecraft:diamond_sword", 1, 0, 1561);
        assertEquals(0, fresh.get("d").getAsInt(), "damageable but undamaged: d 0");
        JsonObject dirt = ViewerFeed.hudItem("minecraft:dirt", 64, 0, 0);
        assertFalse(dirt.has("d") || dirt.has("md"));
        assertEquals(java.util.Set.of("id", "c"), dirt.keySet(), "nothing else, never a name");
        assertEquals(1561, ViewerFeed.hudItem("minecraft:x", 1, 9999, 1561).get("d").getAsInt(), "d never above md");
        assertEquals(0, ViewerFeed.hudItem("minecraft:x", 1, -5, 10).get("d").getAsInt());
        assertFalse(ViewerFeed.hudItem("minecraft:x", 1, 5, ViewerFeed.MAX_DAMAGE + 1).has("md"), "over the server's cap: no bar");
    }

    @Test
    void hudSlotsCoverTheMainInventory() {
        JsonArray inv = ViewerFeed.hudSlots(i -> i == 9 || i == 35 ? ViewerFeed.hudItem("minecraft:slot" + i, i, 0, 0)
            : com.google.gson.JsonNull.INSTANCE, ViewerFeed.INV_FIRST, ViewerFeed.INV_LAST);
        assertEquals(27, inv.size());
        assertEquals("minecraft:slot9", inv.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("minecraft:slot35", inv.get(26).getAsJsonObject().get("id").getAsString());
        assertTrue(inv.get(1).isJsonNull());
    }

    @Test
    void anInventoryChangeSendsTheHudOnTheNextTick() {
        JsonArray inv = ViewerFeed.hudSlots(i -> com.google.gson.JsonNull.INSTANCE, ViewerFeed.INV_FIRST, ViewerFeed.INV_LAST);
        hud.add("inv", inv);
        JsonObject first = tickAndTake();
        assertNotNull(first);
        assertEquals(27, first.getAsJsonObject("hud").getAsJsonArray("inv").size());
        JsonObject quiet = tickAndTake();
        assertTrue(quiet == null || !quiet.has("hud"), "unchanged: no hud");
        JsonObject next = new JsonObject();
        next.addProperty("hp", 20.0);
        JsonArray changed = inv.deepCopy();
        changed.set(4, ViewerFeed.hudItem("minecraft:dirt", 3, 0, 0));
        next.add("inv", changed);
        hud = next;
        JsonObject f = tickAndTake();
        assertNotNull(f);
        assertEquals(3, f.getAsJsonObject("hud").getAsJsonArray("inv").get(4).getAsJsonObject().get("c").getAsInt());
        JsonObject after = tickAndTake();
        assertTrue(after == null || !after.has("hud"), "sent once, not every tick");
    }
}
