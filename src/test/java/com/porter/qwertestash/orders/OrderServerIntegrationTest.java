package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.ManagerHttp;
import com.porter.qwertestash.manager.ManagerIndex;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin's order client against a real Stash Manager server: the signing, the heartbeat with delivery and blocker, the
 * order page's API (placing, cancelling, force-cancelling) and the bot side (claim, progress, final status, 409). The bot's
 * world is the fake one of {@link OrderSim}; everything else is the plugin's own code ({@link ManagerHttp},
 * {@link ManagerIndex#heartbeat}, {@link OrderDriver}, {@link OrderFlow}).
 * <p>
 * Runs only when given a server jar: {@code gradlew test -PstashManagerJar=<copy of stash-manager-server.jar> -PstashManagerPort=<free port>
 * -PstashManagerData=<scratch dir>}. It starts the server with its own data directory and stops it afterwards.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderServerIntegrationTest {
    private static final String SERVER = "2b2t.org", STASH = "it-stash", PLAYER = "ItBot", D = "minecraft:diamond", RED = "minecraft:red_shulker_box";

    private static Process process;
    private static int port;
    private static String key;
    private static URI base;
    private static HttpClient http;
    private static String cookie = "";
    private static final List<String> log = new CopyOnWriteArrayList<>();
    private static volatile String setupCode;

    @BeforeAll
    static void start() throws Exception {
        String jar = System.getProperty("stashManagerJar");
        Assumptions.assumeTrue(jar != null && Files.exists(Path.of(jar)), "pass -PstashManagerJar=<server jar> to run the server integration test");
        port = Integer.parseInt(System.getProperty("stashManagerPort", "8791"));
        Path data = System.getProperty("stashManagerData") != null ? Path.of(System.getProperty("stashManagerData")) : Files.createTempDirectory("sm-it");
        Assumptions.assumeFalse(port == 8765, "never the user's port");
        Files.createDirectories(data);
        Files.writeString(data.resolve("config.properties"), "port=" + port + "\nbind=127.0.0.1\n");

        process = new ProcessBuilder("java", "-jar", jar, "--data", data.toString()).redirectErrorStream(true).directory(data.toFile()).start();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                Pattern code = Pattern.compile("setup code:\\s*([A-Z0-9]{4}-[A-Z0-9]{4})");
                while ((line = in.readLine()) != null) {
                    log.add(line);
                    Matcher m = code.matcher(line);
                    if (m.find()) setupCode = m.group(1);
                }
            } catch (IOException ignored) {
                // The server stopped
            }
        }, "server-output");
        reader.setDaemon(true);
        reader.start();

        base = URI.create("http://127.0.0.1:" + port);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        long deadline = System.currentTimeMillis() + 40_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (get("/api/v1/auth/state").statusCode() == 200) break;
            } catch (Exception e) {
                Thread.sleep(300);
            }
        }
        assertEquals(200, get("/api/v1/auth/state").statusCode(), "the server did not start: " + log);

        Properties config = new Properties();
        try (var propsIn = Files.newBufferedReader(data.resolve("config.properties"))) {
            config.load(propsIn);
        }
        key = config.getProperty("apiKey");
        assertNotNull(key, "the server wrote no API key");

        // The website password, set up from this PC with the one-time code
        long until = System.currentTimeMillis() + 10_000;
        while (setupCode == null && System.currentTimeMillis() < until) Thread.sleep(100);
        assertNotNull(setupCode, "no setup code in " + log);
        JsonObject setup = new JsonObject();
        setup.addProperty("password", "correct horse battery staple 42");
        setup.addProperty("code", setupCode);
        HttpResponse<String> r = post("/api/v1/auth/setup", setup.toString());
        assertEquals(200, r.statusCode(), r.body());
    }

    @AfterAll
    static void stop() {
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                process.destroyForcibly();
            }
        }
    }

    // The browser

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10)).GET();
        if (!cookie.isEmpty()) b.header("Cookie", cookie);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json").header("X-Stash-Manager", "1").header("Origin", "http://127.0.0.1:" + port)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (!cookie.isEmpty()) b.header("Cookie", cookie);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        r.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("sm_session=")).findFirst().ifPresent(c -> cookie = c.substring(0, c.indexOf(';')));
        return r;
    }

    private static JsonObject json(HttpResponse<String> r) {
        assertTrue(r.statusCode() / 100 == 2, r.statusCode() + " " + r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static JsonObject placeOrder(String lines) throws Exception {
        return placeOrder(lines, null);
    }

    /** @param names the order's names field as JSON, or null for none. The website sends no note any more. */
    private static JsonObject placeOrder(String lines, String names) throws Exception {
        JsonObject o = json(post("/api/v1/orders", "{\"server\":\"" + SERVER + "\",\"stash\":\"" + STASH + "\",\"lines\":" + lines + (names == null ? "" : ",\"names\":" + names) + "}"));
        return o.getAsJsonObject("order");
    }

    private static JsonObject orderState(String id) throws Exception {
        JsonObject all = json(get("/api/v1/orders?server=" + SERVER + "&stash=" + STASH));
        for (JsonElement e : all.getAsJsonArray("orders")) if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        throw new AssertionError("order " + id + " not listed");
    }

    private static JsonObject stashEntry() throws Exception {
        JsonArray stashes = JsonParser.parseString(get("/api/v1/stashes").body()).getAsJsonArray();
        for (JsonElement e : stashes) if (e.getAsJsonObject().get("stash").getAsString().equals(STASH)) return e.getAsJsonObject();
        throw new AssertionError("stash not listed: " + stashes);
    }

    // The bot

    private static final String INDEX_HASH = "it-index-hash-1";

    private static String indexJson(int diamonds) {
        return "{\"version\":1,\"server\":\"" + SERVER + "\",\"stash\":\"" + STASH + "\",\"player\":\"" + PLAYER + "\",\"mcVersion\":\"1.21.4\",\"dimension\":\"minecraft:overworld\","
            + "\"generatedAt\":" + System.currentTimeMillis() + ",\"indexHash\":\"" + INDEX_HASH + "\",\"containers\":{\"known\":3,\"total\":3,\"stale\":0},"
            + "\"items\":[{\"key\":\"" + D + "\",\"id\":\"" + D + "\",\"name\":\"Diamond\",\"englishName\":\"Diamond\",\"count\":" + diamonds + ",\"maxStack\":64,\"details\":[],\"enchanted\":false,\"tab\":\"other\",\"tabOrder\":1000000},"
            + "{\"key\":\"" + RED + "\",\"id\":\"" + RED + "\",\"name\":\"Red Shulker Box\",\"englishName\":\"Red Shulker Box\",\"count\":20,\"maxStack\":1,\"details\":[],\"enchanted\":false,\"tab\":\"other\",\"tabOrder\":1000000}],"
            + "\"shulkers\":[]}";
    }

    private static ManagerHttp.Response call(String path, byte[] body) {
        return ManagerHttp.call("POST", URI.create(base + path), body, key, null, 5_000, 15_000, 20_000, null);
    }

    /** One bot: a fake world, the plugin's order client, and a few ticks' worth of the manager's loop. */
    private static final class Bot {
        final OrderSim sim = new OrderSim();
        final OrderDriver driver;
        final List<String> events = new CopyOnWriteArrayList<>();
        String blocker;
        boolean online = true;

        Bot() {
            sim.stock(D, "v", 100_000, 64);
            sim.empties.put(RED, 20);
            driver = new OrderDriver(sim.flow, () -> sim.now, (level, text) -> events.add(level + ": " + text));
        }

        ManagerIndex.Built built() {
            return new ManagerIndex.Built(SERVER, STASH, PLAYER, INDEX_HASH, new byte[0], 3, 3, 0, 2);
        }

        /** The real heartbeat body, sent signed; the reply goes to the order client. */
        void heartbeat() {
            ManagerIndex.Delivery delivery = sim.deliverySet ? new ManagerIndex.Delivery(120, 64, -340, "minecraft:overworld") : null;
            ManagerHttp.Response r = call("/api/v1/heartbeat", ManagerIndex.heartbeat(built(), -1, null, blocker, delivery));
            assertEquals(200, r.code(), r.body() + " " + r.error());
            driver.onHeartbeat(r.body());
        }

        /** Sends every request the order client has now. @return how many */
        int pump() {
            int n = 0;
            while (n < 30) {
                OrderDriver.Request req = driver.next(true, blocker, SERVER, STASH, PLAYER, true);
                if (req == null) break;
                ManagerHttp.Response r = call(req.path(), req.body());
                boolean ok = req.reply().done(r.code(), r.body());
                if (!ok) sim.now += 6_000;
                n++;
            }
            return n;
        }

        /** Heartbeat, the order client's requests, a bit of work. */
        void tick() {
            heartbeat();
            pump();
            sim.step();
            pump();
            sim.now += 2_500;
        }

        void tickUntil(java.util.function.BooleanSupplier done, int max) {
            for (int i = 0; i < max && !done.getAsBoolean(); i++) tick();
        }
    }

    @Test
    @Order(1)
    void theBotHeartbeatShowsTheDeliveryContainerAndTheBlocker() throws Exception {
        assertEquals(200, call("/api/v1/index", indexJson(10_000).getBytes(StandardCharsets.UTF_8)).code());
        Bot bot = new Bot();
        bot.blocker = "orders off";
        bot.heartbeat();
        JsonObject entry = stashEntry();
        assertEquals("orders off", entry.get("orderBlocker").getAsString());
        JsonObject delivery = entry.getAsJsonObject("delivery");
        assertEquals(120, delivery.get("x").getAsInt());
        assertEquals(64, delivery.get("y").getAsInt());
        assertEquals(-340, delivery.get("z").getAsInt());
        assertEquals("minecraft:overworld", delivery.get("dimension").getAsString());

        // Ready: the blocker clears; no delivery container: it clears too
        bot.blocker = null;
        bot.heartbeat();
        assertTrue(stashEntry().get("orderBlocker").isJsonNull());
        bot.sim.deliverySet = false;
        bot.blocker = "no delivery container set";
        bot.heartbeat();
        entry = stashEntry();
        assertTrue(entry.get("delivery").isJsonNull());
        assertEquals("no delivery container set", entry.get("orderBlocker").getAsString());
        bot.sim.deliverySet = true;
        bot.blocker = null;
        bot.heartbeat();
    }

    @Test
    @Order(2)
    void anOrderPlacedWhileTheBotIsOfflineWaitsThenIsClaimedAndDelivered() throws Exception {
        Bot bot = new Bot();
        // Nobody is heartbeating: the order is placed and waits
        JsonObject placed = placeOrder("[{\"type\":\"item\",\"key\":\"" + D + "\",\"count\":100}]");
        String id = placed.get("id").getAsString();
        assertEquals("pending", placed.get("status").getAsString());
        assertEquals("pending", orderState(id).get("status").getAsString());

        // The bot comes back: its heartbeat reply says one is waiting, it claims it and works
        bot.blocker = null;
        bot.heartbeat();
        assertEquals(1, bot.driver.pending());
        bot.pump();
        assertTrue(bot.sim.flow.running(), "the order was not claimed: " + bot.events);
        JsonObject claimed = orderState(id);
        assertTrue(claimed.get("status").getAsString().equals("accepted") || claimed.get("status").getAsString().equals("in_progress"), claimed.toString());

        bot.tickUntil(() -> !bot.sim.flow.holdsOrder(), 30);
        assertFalse(bot.sim.flow.holdsOrder(), "the bot never finished: " + bot.events);

        JsonObject done = orderState(id);
        assertEquals("delivered", done.get("status").getAsString(), done.toString());
        assertEquals(1, done.getAsJsonObject("result").get("shulkers").getAsInt());
        JsonObject line = done.getAsJsonObject("result").getAsJsonArray("lines").get(0).getAsJsonObject();
        assertEquals(D, line.get("key").getAsString());
        assertEquals(100, line.get("delivered").getAsInt());
        assertEquals(1, done.getAsJsonObject("progress").get("done").getAsInt());
        assertEquals(1, bot.sim.delivery.size());
        assertTrue(bot.events.stream().anyMatch(e -> e.startsWith("info: Order " + id.substring(0, Math.min(24, id.length())) + " claimed")), bot.events.toString());
        assertTrue(bot.events.stream().anyMatch(e -> e.contains("delivered")), bot.events.toString());
    }

    @Test
    @Order(3)
    void progressIsReportedWhileItRuns() throws Exception {
        Bot bot = new Bot();
        String id = placeOrder("[{\"type\":\"item\",\"key\":\"" + D + "\",\"count\":" + 64 * 30 + "}]").get("id").getAsString();
        bot.blocker = null;
        bot.heartbeat();
        bot.pump();
        // Claimed and planned: two shulkers
        bot.sim.step();
        bot.sim.step();
        bot.sim.now += 2_500;
        bot.pump();
        JsonObject mid = orderState(id);
        assertEquals("in_progress", mid.get("status").getAsString(), mid.toString());
        assertEquals(2, mid.getAsJsonObject("progress").get("total").getAsInt());
        assertEquals(1, mid.getAsJsonObject("progress").get("done").getAsInt());
        assertTrue(mid.get("phase").getAsString().length() > 0);

        bot.tickUntil(() -> !bot.sim.flow.holdsOrder(), 30);
        JsonObject done = orderState(id);
        assertEquals("delivered", done.get("status").getAsString());
        assertEquals(2, done.getAsJsonObject("result").get("shulkers").getAsInt());
        assertEquals(64 * 30, done.getAsJsonObject("result").getAsJsonArray("lines").get(0).getAsJsonObject().get("delivered").getAsInt());
    }

    @Test
    @Order(4)
    void aRunningOrderCanBeCancelledFromTheWebsite() throws Exception {
        Bot bot = new Bot();
        String id = placeOrder("[{\"type\":\"item\",\"key\":\"" + D + "\",\"count\":" + 64 * 30 + "}]").get("id").getAsString();
        bot.blocker = null;
        bot.heartbeat();
        bot.pump();
        bot.sim.step();
        bot.sim.step();
        bot.pump();
        assertEquals(1, bot.sim.delivery.size());

        JsonObject cancel = json(post("/api/v1/orders/" + id + "/cancel", "{}"));
        assertTrue(cancel.getAsJsonObject("order").get("cancelRequested").getAsBoolean());

        // The next heartbeat carries the cancel; the bot stops at the next safe point and says so
        bot.tickUntil(() -> !bot.sim.flow.holdsOrder(), 20);
        JsonObject state = orderState(id);
        assertEquals("cancelled", state.get("status").getAsString(), state.toString());
        assertEquals(1, bot.sim.delivery.size(), "nothing more was delivered after the cancel");
        assertTrue(bot.events.stream().anyMatch(e -> e.startsWith("warn: Order") && e.endsWith("cancelled")), bot.events.toString());
        // And the bot is free for the next one
        assertFalse(bot.sim.flow.holdsOrder());
    }

    @Test
    @Order(5)
    void aForceCancelledOrdersLaterStatusIs409AndTheBotLetsGo() throws Exception {
        Bot bot = new Bot();
        String id = placeOrder("[{\"type\":\"item\",\"key\":\"" + D + "\",\"count\":" + 64 * 30 + "}]").get("id").getAsString();
        bot.blocker = null;
        bot.heartbeat();
        bot.pump();
        assertTrue(bot.sim.flow.running());

        // Cancel asked for, the bot never answers, the website gives up on it
        json(post("/api/v1/orders/" + id + "/cancel", "{}"));
        JsonObject forced = json(post("/api/v1/orders/" + id + "/force-cancel", "{}"));
        assertEquals("cancelled", forced.getAsJsonObject("order").get("status").getAsString());

        // The bot, which didn't hear, reports its progress: 409, taken as done
        bot.sim.step();
        bot.sim.step();
        bot.sim.now += 2_500;
        int sent = bot.pump();
        assertTrue(sent >= 1);
        // The 409 stopped the run at the next safe point; its end is told (and 409'd) too
        assertTrue(bot.sim.flow.cancelRequested() || !bot.sim.flow.running());
        bot.sim.run(20);
        bot.sim.now += 2_500;
        bot.pump();
        assertEquals("cancelled", orderState(id).get("status").getAsString());
        assertFalse(bot.sim.flow.holdsOrder(), "the bot kept working on a finished order");
    }

    @Test
    @Order(6)
    void longTextIsCutNotRefused() throws Exception {
        Bot bot = new Bot();
        String id = placeOrder("[{\"type\":\"item\",\"key\":\"" + D + "\",\"count\":10}]").get("id").getAsString();
        bot.blocker = null;
        bot.heartbeat();
        bot.pump();
        // A report with a 500 character phase and a 900 character message, as the driver would build it
        JsonObject o = new JsonObject();
        o.addProperty("status", "in_progress");
        o.addProperty("phase", OrderDriver.clamp("p".repeat(500), OrderDriver.PHASE_MAX));
        o.addProperty("message", OrderDriver.clamp("m".repeat(900), OrderDriver.MESSAGE_MAX));
        o.addProperty("player", PLAYER);
        ManagerHttp.Response r = call("/api/v1/orders/" + id + "/status", o.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(200, r.code(), r.body());
        JsonObject state = orderState(id);
        assertEquals(100, state.get("phase").getAsString().length());
        assertEquals(300, state.get("message").getAsString().length());
        bot.tickUntil(() -> !bot.sim.flow.holdsOrder(), 20);
        assertEquals("delivered", orderState(id).get("status").getAsString());
    }

    @Test
    @Order(7)
    void aBotCannotSkipAheadOrReopenAFinishedOrder() throws Exception {
        Bot bot = new Bot();
        String id = placeOrder("[{\"type\":\"item\",\"key\":\"" + D + "\",\"count\":10}]").get("id").getAsString();
        bot.blocker = null;
        bot.heartbeat();
        bot.pump();
        bot.tickUntil(() -> !bot.sim.flow.holdsOrder(), 20);
        assertEquals("delivered", orderState(id).get("status").getAsString());
        JsonObject again = new JsonObject();
        again.addProperty("status", "in_progress");
        again.addProperty("player", PLAYER);
        assertEquals(409, call("/api/v1/orders/" + id + "/status", again.toString().getBytes(StandardCharsets.UTF_8)).code());
    }

    @Test
    @Order(8)
    void aRestartedBotGetsItsOrderBackAndFinishesTheSamePlan() throws Exception {
        Bot first = new Bot();
        String id = placeOrder("[{\"type\":\"item\",\"key\":\"" + D + "\",\"count\":" + 64 * 30 + "}]").get("id").getAsString();
        first.blocker = null;
        first.heartbeat();
        first.pump();
        first.sim.step();
        first.sim.step();
        first.pump();
        assertEquals(1, first.sim.delivery.size());
        // The proxy restarts: the order lives on in the stash file (saved plan), in the container, and on the server
        first.sim.flow.reset();
        first.driver.reset();

        // Same world and saved plan, a new session
        first.sim.now += 30_000;
        first.heartbeat();
        first.pump();
        assertTrue(first.sim.flow.running(), "the server did not hand the held order back");
        assertEquals(id, first.sim.flow.orderId());
        assertEquals(1, first.sim.flow.index(), "it started over instead of carrying on");
        first.tickUntil(() -> !first.sim.flow.holdsOrder(), 30);
        JsonObject done = orderState(id);
        assertEquals("delivered", done.get("status").getAsString(), done.toString());
        assertEquals(2, done.getAsJsonObject("result").get("shulkers").getAsInt());
        assertEquals(2, first.sim.delivery.size());
    }

    /**
     * Shulker names (contract of 2026-11-05): the claim hands the bot the order's names, the bot names its shulkers in plan
     * order, and the heartbeat's naming field shows on the stash list. A server from before names (it leaves names out of the
     * order it returns) skips the naming checks instead of failing.
     */
    @Test
    @Order(9)
    void theClaimCarriesTheNamesAndTheBotNamesItsShulkers() throws Exception {
        Bot bot = new Bot();
        bot.blocker = null;
        // The naming field of the heartbeat
        ManagerHttp.Response beat = call("/api/v1/heartbeat", ManagerIndex.heartbeat(bot.built(), -1, null, null, new ManagerIndex.Delivery(120, 64, -340, "minecraft:overworld"),
            new ManagerIndex.Naming(true, 12)));
        assertEquals(200, beat.code(), beat.body());
        JsonObject entry = stashEntry();
        if (entry.has("naming") && !entry.get("naming").isJsonNull()) {
            assertTrue(entry.getAsJsonObject("naming").get("anvil").getAsBoolean());
            assertEquals(12, entry.getAsJsonObject("naming").get("xpLevels").getAsInt());
            assertFalse(entry.getAsJsonObject("naming").toString().contains("120"), "no position in naming");
        }

        JsonObject placed = placeOrder("[{\"type\":\"item\",\"key\":\"" + D + "\",\"count\":" + 64 * 28 + "}]", "{\"mode\":\"each\",\"names\":[\"  First  \",\"Second\"]}");
        String id = placed.get("id").getAsString();
        Assumptions.assumeTrue(placed.has("names") && !placed.get("names").isJsonNull(), "this server does not keep names yet: " + placed);
        assertFalse(placed.has("note"), "orders carry no note any more: " + placed);
        // The server trims them
        assertEquals("First", placed.getAsJsonObject("names").getAsJsonArray("names").get(0).getAsString());

        bot.heartbeat();
        bot.pump();
        assertTrue(bot.sim.flow.running(), "the order was not claimed: " + bot.events);
        assertEquals("First", bot.sim.flow.units().get(0).name);
        assertEquals("Second", bot.sim.flow.units().get(1).name);
        bot.tickUntil(() -> !bot.sim.flow.holdsOrder(), 40);
        JsonObject done = orderState(id);
        assertEquals("delivered", done.get("status").getAsString(), done.toString());
        assertEquals(List.of("First", "Second"), bot.sim.named);
        assertEquals("First", bot.sim.delivery.get(0).name());

        // Without an anvil: delivered all the same, with the one note
        bot.sim.anvil = false;
        String plain = placeOrder("[{\"type\":\"item\",\"key\":\"" + D + "\",\"count\":10}]", "{\"mode\":\"all\",\"name\":\"Kit\"}").get("id").getAsString();
        bot.heartbeat();
        bot.pump();
        bot.tickUntil(() -> !bot.sim.flow.holdsOrder(), 30);
        JsonObject unnamed = orderState(plain);
        assertEquals("delivered", unnamed.get("status").getAsString(), unnamed.toString());
        assertTrue(unnamed.toString().contains("1 shulker(s) delivered unnamed: no anvil set"), unnamed.toString());
    }
}
