package com.porter.qwertestash.orders;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.ManagerHttp;
import com.porter.qwertestash.manager.ManagerIndex;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin's enchant orders against a real Stash Manager server (1.4.0 or newer): the index with the enchant fields the plugin writes, the heartbeat's
 * enchant block, the claim with {@code enchantOrders} and {@code player}, the order block the server writes read by {@link EnchantOrder#parse}, the preflight
 * agreeing with the server's plan, {@code result.enchant} accepted (and a reason outside the closed set refused), and the force-cancel acknowledgement. Only
 * the world is fake. Runs only when given a server jar: {@code gradlew test -PstashManagerJar=<copy of stash-manager-server.jar> -PstashManagerPort=8793
 * -PstashManagerData=<scratch dir>} (never the user's port 8765 or data).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EnchantServerIntegrationTest {
    private static final String SERVER = "2b2t.org", STASH = "enchant-it", PLAYER = "EnchBot", SWORD = "minecraft:diamond_sword";

    private static Process process;
    private static int port;
    private static String key;
    private static URI base;
    private static HttpClient http;
    private static String cookie = "";
    private static final List<String> log = new CopyOnWriteArrayList<>();
    private static volatile String setupCode;
    private static ManagerIndex.Built built;
    private static String bookKey;

    @BeforeAll
    static void start() throws Exception {
        String jar = System.getProperty("stashManagerJar");
        Assumptions.assumeTrue(jar != null && Files.exists(Path.of(jar)), "pass -PstashManagerJar=<server jar> to run the server integration test");
        port = Integer.parseInt(System.getProperty("stashManagerPort", "8793"));
        Assumptions.assumeFalse(port == 8765, "never the user's port");
        Path data = System.getProperty("stashManagerData") != null ? Path.of(System.getProperty("stashManagerData")) : Files.createTempDirectory("sm-enchant-it");
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
        }, "enchant-server-output");
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
        try (var in = Files.newBufferedReader(data.resolve("config.properties"))) {
            config.load(in);
        }
        key = config.getProperty("apiKey");
        assertNotNull(key, "the server wrote no API key");
        long until = System.currentTimeMillis() + 10_000;
        while (setupCode == null && System.currentTimeMillis() < until) Thread.sleep(100);
        assertNotNull(setupCode, "no setup code in " + log);
        JsonObject setup = new JsonObject();
        setup.addProperty("password", "correct horse battery staple 42");
        setup.addProperty("code", setupCode);
        assertEquals(200, post("/api/v1/auth/setup", setup.toString()).statusCode());
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

    private static JsonObject orderState(String id) throws Exception {
        JsonObject all = json(get("/api/v1/orders?server=" + SERVER + "&stash=" + STASH));
        for (JsonElement e : all.getAsJsonArray("orders")) if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        throw new AssertionError("order " + id + " not listed");
    }

    // The bot

    private static Item item(String id, int count, int maxStack, int maxDamage, int rc, String... levels) {
        Item it = new Item();
        it.id = id;
        it.name = Item.prettyName(id);
        it.count = count;
        it.maxStack = maxStack;
        it.maxDamage = maxDamage;
        it.repairCost = rc;
        it.key = "minecraft:" + id + (levels.length > 0 ? "#a1b2c3d4" : "");
        it.variant = it.key;
        for (String l : levels) {
            it.enchantLevels.add(l);
            it.enchants.add(l.substring(0, l.lastIndexOf(':')));
        }
        return it;
    }

    private static ManagerHttp.Response call(String path, byte[] body) {
        return ManagerHttp.call("POST", URI.create(base + path), body, key, null, 5_000, 15_000, 20_000, null);
    }

    /** The index the plugin would send for a stash with these items (built by the plugin's own ManagerIndex, so its enchant fields are what the server checks). */
    private static void postIndex() {
        Stash stash = new Stash(STASH, SERVER);
        Stash.Box box = new Stash.Box(new BlockPos(1, 2, 3), new ArrayList<>(List.of(new BlockPos(1, 2, 3))));
        box.contents = new Item[27];
        box.contents[0] = item("diamond_sword", 2, 1, 1561, 0);
        box.contents[1] = item("enchanted_book", 1, 1, 0, 0, "sharpness:5");
        box.contents[2] = item("enchanted_book", 1, 1, 0, 0, "sharpness:5");
        box.contents[3] = item("experience_bottle", 64, 64, 0, 0);
        box.contents[4] = item("anvil", 3, 64, 0, 0);
        stash.boxes.add(box);
        Stash.Box bottles = new Stash.Box(new BlockPos(2, 2, 3), new ArrayList<>(List.of(new BlockPos(2, 2, 3))));
        bottles.contents = new Item[27];
        for (int i = 0; i < 27; i++) bottles.contents[i] = item("experience_bottle", 64, 64, 0, 0);
        stash.boxes.add(bottles);
        Stash.Box more = new Stash.Box(new BlockPos(3, 2, 3), new ArrayList<>(List.of(new BlockPos(3, 2, 3))));
        more.contents = new Item[27];
        for (int i = 0; i < 27; i++) more.contents[i] = item("experience_bottle", 64, 64, 0, 0);
        stash.boxes.add(more);
        Stash.Box evenMore = new Stash.Box(new BlockPos(4, 2, 3), new ArrayList<>(List.of(new BlockPos(4, 2, 3))));
        evenMore.contents = new Item[27];
        for (int i = 0; i < 27; i++) evenMore.contents[i] = item("experience_bottle", 64, 64, 0, 0);
        stash.boxes.add(evenMore);
        built = ManagerIndex.build(stash, false, PLAYER, "1.21.4", "overworld", id -> null);
        ManagerHttp.Response r = call("/api/v1/index", built.json());
        assertEquals(200, r.code(), r.body() + " " + r.error());
        bookKey = null;
        for (JsonElement e : JsonParser.parseString(new String(built.json(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("items")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("id").getAsString().equals("minecraft:enchanted_book")) bookKey = o.get("key").getAsString();
        }
        assertNotNull(bookKey);
    }

    /** The plugin's own heartbeat with the enchant block; returns the reply. */
    private static ManagerHttp.Response heartbeat(ManagerIndex.EnchantHb hb) {
        ManagerHttp.Response r = call("/api/v1/heartbeat", ManagerIndex.heartbeat(built, -1, null, null, null, null, null, null, null, null, null, null, null, null, hb));
        assertEquals(200, r.code(), r.body() + " " + r.error());
        return r;
    }

    private static JsonObject statusBody(String status, String phase, JsonObject result) {
        JsonObject o = new JsonObject();
        o.addProperty("status", status);
        o.addProperty("phase", phase);
        JsonObject progress = new JsonObject();
        progress.addProperty("done", 0);
        progress.addProperty("total", 1);
        o.add("progress", progress);
        o.addProperty("message", "");
        if (result != null) o.add("result", result);
        o.addProperty("player", PLAYER);
        return o;
    }

    private static ManagerHttp.Response status(String id, JsonObject body) {
        return call("/api/v1/orders/" + id + "/status", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static final String ENCHANT_LINE = "{\"type\":\"enchant\",\"key\":\"" + SWORD + "\",\"count\":1,\"enchants\":[{\"id\":\"minecraft:sharpness\",\"lvl\":5}]}";

    private static JsonObject placeEnchantOrder() throws Exception {
        HttpResponse<String> r = post("/api/v1/orders", "{\"server\":\"" + SERVER + "\",\"stash\":\"" + STASH + "\",\"lines\":[" + ENCHANT_LINE + "]}");
        assertEquals(200, r.statusCode(), r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("order");
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void theIndexAndHeartbeatTheServerChecksAreWhatThePluginWrites() throws Exception {
        postIndex();
        // no enchant block yet: no enchant-capable bot, the order is refused as no_bot
        HttpResponse<String> none = post("/api/v1/orders", "{\"server\":\"" + SERVER + "\",\"stash\":\"" + STASH + "\",\"lines\":[" + ENCHANT_LINE + "]}");
        assertEquals(409, none.statusCode(), none.body());
        assertEquals("no_bot", JsonParser.parseString(none.body()).getAsJsonObject().get("enchant").getAsString());
        // the plugin's heartbeat block makes the bot capable
        ManagerHttp.Response r = heartbeat(new ManagerIndex.EnchantHb(true, "1.21.4", 20, false));
        assertEquals(0, EnchantState.parse(r.body()), "nothing reserved yet: " + r.body());
        JsonObject stashes = null;
        for (JsonElement e : JsonParser.parseString(get("/api/v1/stashes").body()).getAsJsonArray()) if (e.getAsJsonObject().get("stash").getAsString().equals(STASH)) stashes = e.getAsJsonObject();
        assertNotNull(stashes);
        assertTrue(stashes.getAsJsonObject("enchant").get("supports").getAsBoolean(), stashes.toString());
        assertTrue(stashes.getAsJsonObject("enchant").get("canOrder").getAsBoolean(), stashes.toString());
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void theOrderBlockIsReadAndThePreflightAgreesWithTheServersPlan() throws Exception {
        JsonObject placed = placeEnchantOrder();
        String id = placed.get("id").getAsString();
        assertEquals("pending", placed.get("status").getAsString());
        // the reply to the next heartbeat counts the bottles the order reserves
        ManagerHttp.Response beat = heartbeat(new ManagerIndex.EnchantHb(true, "1.21.4", 20, false));
        long reserved = EnchantState.parse(beat.body());
        assertTrue(reserved > 0, beat.body());

        // the claim, as the plugin sends it
        EnchantFlowTest.Host host = new EnchantFlowTest.Host();
        OrderFlow flow = new OrderFlow(host, System::currentTimeMillis);
        OrderDriver driver = new OrderDriver(flow, System::currentTimeMillis, (level, text) -> {
        });
        driver.enchantOrdersWhen(() -> true);
        driver.onHeartbeat(beat.body());
        OrderDriver.Request claim = driver.next(true, null, SERVER, STASH, PLAYER, true);
        assertNotNull(claim);
        JsonObject claimBody = JsonParser.parseString(new String(claim.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, claimBody.get("enchantOrders").getAsInt());
        assertEquals(PLAYER, claimBody.get("player").getAsString());
        ManagerHttp.Response answer = call(claim.path(), claim.body());
        assertEquals(200, answer.code(), answer.body());
        assertTrue(claim.reply().done(answer.code(), answer.body()));
        assertTrue(flow.running(), "the order was not taken: " + host.said);

        // the block of the real server, read by the plugin
        EnchantLedger ledger = flow.enchantLedger();
        assertNotNull(ledger, host.said.toString());
        assertFalse(ledger.block().malformed());
        assertEquals("1.21.4", ledger.block().mc());
        assertEquals(1, ledger.items().size());
        EnchantOrder.ItemRef ref = ledger.block().items().get(0);
        assertEquals(SWORD, ref.key());
        assertEquals(reserved, ledger.block().takeMap().get(EnchantOrder.BOTTLE_KEY).longValue());
        assertEquals(reserved, ref.plan().bottles());
        assertEquals(SWORD, ref.plan().baseKey());
        assertEquals(bookKey, ref.plan().bookKeys().keySet().iterator().next());
        assertEquals(1, ref.plan().steps().size());

        // the preflight with the stacks the take keys name: the plan made again with the real items is the server's
        List<EnchantItems.Held> held = new ArrayList<>();
        held.add(new EnchantItems.Held(9, SWORD, "diamond_sword", 1, false, Map.of(), 0, false));
        held.add(new EnchantItems.Held(10, bookKey, "enchanted_book", 1, true, Map.of("sharpness", 5), 0, false));
        int slot = 11;
        for (int left = (int) reserved; left > 0; left -= 64) held.add(new EnchantItems.Held(slot++, EnchantOrder.BOTTLE_KEY, "experience_bottle", Math.min(64, left), false, Map.of(), 0, false));
        held.add(new EnchantItems.Held(slot, "minecraft:anvil", "anvil", 1, false, Map.of(), 0, false));
        EnchantPreflight pre = new EnchantPreflight(ref, "1.21.4", Set.of(), ledger, System::currentTimeMillis);
        EnchantPreflight.Ready ready = assertInstanceOf(EnchantPreflight.Ready.class, pre.decide(held, 30), ledger.block().takeMap().toString());
        assertEquals(ref.plan().points(), ready.real().points());

        // the first status, then the result: result.enchant is accepted
        ledger.begin(0, ready.real());
        ledger.stepDone(0, 0);
        assertEquals(200, status(id, statusBody("in_progress", "enchanting 1/1: step 1/1", null)).code());
        Unit u = flow.units().get(0);
        flow.enchantItemDone(u, 0, "minecraft:diamond_sword#feedbeef", "minecraft:diamond_sword#feedbeef00112233", Map.of("sharpness", 5));
        JsonObject result = new JsonObject();
        result.addProperty("shulkers", 0);
        JsonArray lines = new JsonArray();
        JsonObject line = new JsonObject();
        line.addProperty("key", SWORD);
        line.addProperty("delivered", 0);
        lines.add(line);
        result.add("lines", lines);
        ledger.addBottles(12);
        result.add("enchant", ledger.toResult());
        ManagerHttp.Response ok = status(id, statusBody("in_progress", "delivering", result));
        assertEquals(200, ok.code(), ok.body());
        JsonObject stored = orderState(id).getAsJsonObject("result").getAsJsonObject("enchant");
        assertEquals(1, stored.getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        assertEquals(12, stored.get("bottlesUsed").getAsInt());
        // a reason outside the closed set is refused (the plugin never sends one: Codes.safe)
        JsonObject bad = result.deepCopy();
        JsonObject shortOne = new JsonObject();
        shortOne.addProperty("key", SWORD);
        shortOne.addProperty("reason", "interrupted");
        bad.getAsJsonObject("enchant").getAsJsonArray("short").add(shortOne);
        assertEquals(400, status(id, statusBody("in_progress", "delivering", bad)).code());
        // the end
        assertEquals(200, status(id, statusBody("delivered", "done", result)).code());
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void aForceCancelIsAcknowledgedByTheNextReportAndThePluginStopsAtTheBoundary() throws Exception {
        String id = placeEnchantOrder().get("id").getAsString();
        EnchantFlowTest.Host host = new EnchantFlowTest.Host();
        OrderFlow flow = new OrderFlow(host, System::currentTimeMillis);
        OrderDriver driver = new OrderDriver(flow, System::currentTimeMillis, (level, text) -> {
        });
        driver.enchantOrdersWhen(() -> true);
        driver.onHeartbeat(heartbeat(new ManagerIndex.EnchantHb(true, "1.21.4", 20, false)).body());
        OrderDriver.Request claim = driver.next(true, null, SERVER, STASH, PLAYER, true);
        assertNotNull(claim);
        ManagerHttp.Response answer = call(claim.path(), claim.body());
        assertTrue(claim.reply().done(answer.code(), answer.body()));
        assertTrue(flow.running(), host.said.toString());
        EnchantLedger ledger = flow.enchantLedger();
        ledger.begin(0, ledger.block().items().get(0).plan());
        ledger.stepDone(0, 0);
        assertEquals(200, status(id, statusBody("in_progress", "enchanting", null)).code());

        // the orderer cancels (queued), the bot hears it in a heartbeat reply, and later force-cancels (confirm needed)
        assertEquals(200, post("/api/v1/orders/" + id + "/cancel", "{}").statusCode());
        ManagerHttp.Response beat = heartbeat(new ManagerIndex.EnchantHb(true, "1.21.4", 20, false));
        driver.onHeartbeat(beat.body());
        assertTrue(flow.cancelQueued(), "the cancel waits for the item: " + beat.body());
        assertFalse(flow.cancelRequested());
        assertEquals(400, post("/api/v1/orders/" + id + "/force-cancel", "{}").statusCode());
        assertEquals(200, post("/api/v1/orders/" + id + "/force-cancel", "{\"confirm\":true}").statusCode());
        assertEquals("cancelled", orderState(id).get("status").getAsString());
        // the reservations are held until the bot's next report; that report is answered 409 and the plugin stops at the next step boundary
        ManagerHttp.Response ack = status(id, statusBody("in_progress", "enchanting", null));
        assertEquals(409, ack.code());
        assertTrue(flow.cancelHard(id));
        assertTrue(flow.forceCancelled());
        assertTrue(flow.cancelRequested());
        // the item is returned (short with cancelled), the order ends cancelled, and its final report is a 409 the plugin accepts
        flow.enchantItemShort(flow.units().get(0), 0, Codes.CANCELLED);
        flow.next();
        assertEquals(OrderFlow.State.Cancelled, flow.state());
        assertTrue(flow.report().terminal());
    }

    // The missing-base craft (ENCHANT-CONTRACT draft 3.4, item 1.9)

    /** An index for a stash with books, bottles, anvils, diamonds and sticks, and no sword at all. */
    private static void postIndexWithoutSword() {
        Stash stash = new Stash(STASH, SERVER);
        Stash.Box box = new Stash.Box(new BlockPos(1, 2, 3), new ArrayList<>(List.of(new BlockPos(1, 2, 3))));
        box.contents = new Item[27];
        for (int i = 0; i < 4; i++) box.contents[i] = item("enchanted_book", 1, 1, 0, 0, "sharpness:5");
        box.contents[4] = item("anvil", 3, 64, 0, 0);
        box.contents[5] = item("diamond", 64, 64, 0, 0);
        box.contents[6] = item("stick", 64, 64, 0, 0);
        stash.boxes.add(box);
        for (int b = 0; b < 3; b++) {
            Stash.Box bottles = new Stash.Box(new BlockPos(2 + b, 2, 3), new ArrayList<>(List.of(new BlockPos(2 + b, 2, 3))));
            bottles.contents = new Item[27];
            for (int i = 0; i < 27; i++) bottles.contents[i] = item("experience_bottle", 64, 64, 0, 0);
            stash.boxes.add(bottles);
        }
        built = ManagerIndex.build(stash, false, PLAYER, "1.21.4", "overworld", id -> null);
        ManagerHttp.Response r = call("/api/v1/index", built.json());
        assertEquals(200, r.code(), r.body() + " " + r.error());
        bookKey = null;
        for (JsonElement e : JsonParser.parseString(new String(built.json(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("items")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("id").getAsString().equals("minecraft:enchanted_book")) bookKey = o.get("key").getAsString();
        }
        assertNotNull(bookKey);
    }

    /** The plugin's own heartbeat with the enchant block and this craft block; returns the reply. */
    private static ManagerHttp.Response heartbeatWithCraft(ManagerIndex.Craft craft) {
        ManagerHttp.Response r = call("/api/v1/heartbeat", ManagerIndex.heartbeat(built, -1, null, null, null, null, null, null, null, null, craft, null, null, null,
            new ManagerIndex.EnchantHb(true, "1.21.4", 20, false)));
        assertEquals(200, r.code(), r.body() + " " + r.error());
        return r;
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void aMissingBaseIsCraftedFromTheServersCraftBlockAndTheResultIsAccepted() throws Exception {
        postIndexWithoutSword();
        // the server's recipes for 1.21.4 (built from a client jar on this machine); without them there is no craft to test
        String recipes = null, why = "no answer";
        long until = System.currentTimeMillis() + 240_000;
        while (System.currentTimeMillis() < until) {
            ManagerHttp.Response r = ManagerHttp.call("GET", URI.create(base + "/api/v1/bot/recipes?mc=1.21.4"), null, key, null, 5_000, 15_000, 20_000, null);
            if (r.code() == 200) {
                recipes = r.body();
                break;
            }
            why = r.code() + " " + (r.body() == null ? r.error() : r.body().substring(0, Math.min(120, r.body().length())));
            if (r.code() != 503 && r.code() != 404 && r.code() != 429) break;
            // the route allows 6 requests a minute per bot: a built table is ready a few seconds after a fresh data folder starts
            Thread.sleep(11_000);
        }
        Assumptions.assumeTrue(recipes != null, "the server gave no 1.21.4 recipes (" + why + "): the craft part is skipped");
        Recipes table = Recipes.parse(new java.io.StringReader(recipes));
        ManagerIndex.CraftOrders capability = new ManagerIndex.CraftOrders(true, table.hash(), "1.21.4", "auto", 2048, List.of());
        // the first heartbeat is answered with the craft settings, the second says the bot has them (the server wants that before a craft order)
        ManagerHttp.Response first = heartbeatWithCraft(new ManagerIndex.Craft(capability, null));
        String craftHash = ManagerIndex.craftReply(first.body()).hash();
        assertNotNull(craftHash, first.body());
        ManagerHttp.Response beat = heartbeatWithCraft(new ManagerIndex.Craft(capability, craftHash));

        // a sword is wanted and there is none: the server plans a crafted base
        JsonObject placed = placeEnchantOrder();
        String id = placed.get("id").getAsString();
        assertEquals("pending", placed.get("status").getAsString());

        // the claim needs both capabilities, which the plugin's driver sends
        OrderSim w = new OrderSim().craftOrders(table);
        w.craftStack.put(SWORD, 1);
        w.empties.put("minecraft:red_shulker_box", 5);
        OrderFlow flow = w.flow;
        OrderDriver driver = new OrderDriver(flow, () -> w.now, (level, text) -> {
        });
        driver.enchantOrdersWhen(() -> true);
        driver.craftOrdersWhen(() -> true);
        driver.onHeartbeat(beat.body());
        w.now += 70_000;
        OrderDriver.Request claim = driver.next(true, null, SERVER, STASH, PLAYER, true);
        assertNotNull(claim);
        JsonObject claimBody = JsonParser.parseString(new String(claim.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(1, claimBody.get("enchantOrders").getAsInt());
        assertEquals(1, claimBody.get("craftOrders").getAsInt());
        ManagerHttp.Response answer = call(claim.path(), claim.body());
        assertEquals(200, answer.code(), answer.body());
        assertTrue(claim.reply().done(answer.code(), answer.body()));
        assertTrue(flow.running(), "the order was not taken: " + w.said);

        // the block of the real server, read by the plugin: the base is crafted, not in the take, and the craft block passed the bot's own check against the table
        EnchantLedger ledger = flow.enchantLedger();
        assertNotNull(ledger, w.said.toString());
        assertFalse(ledger.block().malformed());
        EnchantOrder.ItemRef ref = ledger.block().items().get(0);
        assertTrue(ref.plan().baseCrafted(), "the server marked the base crafted");
        assertEquals(SWORD, ref.plan().baseKey());
        assertFalse(ledger.block().takeMap().containsKey(SWORD), ledger.block().takeMap().toString());
        CraftLedger craft = flow.craftLedger();
        assertNotNull(craft, "the order has a craft stage: " + w.said);
        assertFalse(craft.refusedAtStart(), "the bot's craft check refused the server's block: " + w.said);
        assertEquals(1, craft.wanted(SWORD));
        Unit bin = flow.units().get(0);
        assertEquals(1, bin.lots.stream().filter(l -> l.base() && l.craft()).count(), "the base is a load of the bin, to be crafted");
        // the ingredients are the craft block's take, in the stash
        craft.take().forEach((k, v) -> w.craftStash.put(k, v));

        // crafted first, then enchanted with it (the real preflight and trip over the fake anvil), then delivered without the base
        EnchantCraftBaseTest.Anvil anvil = new EnchantCraftBaseTest.Anvil(w, 1, bookKey, ref.plan().bottles());
        w.enchantHandler = anvil::handle;
        w.run(300);
        OrderFlow.Report report = flow.report();
        assertEquals("delivered", report.status(), report.message() + " " + w.said);
        assertEquals(List.of(1), anvil.basesInHand);
        assertEquals(List.of("FINISHED"), anvil.outcomes);
        assertEquals(1, report.craft().getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        assertEquals(0, report.craft().getAsJsonArray("short").size());
        assertTrue(w.basesReleased.isEmpty());

        // the status the plugin sends is accepted by the server, result.craft and result.enchant stored
        JsonObject body = driver.statusJson(report);
        body.addProperty("player", PLAYER);
        ManagerHttp.Response ok = status(id, body);
        assertEquals(200, ok.code(), ok.body() + " " + body);
        JsonObject stored = orderState(id).getAsJsonObject("result");
        assertEquals(1, stored.getAsJsonObject("craft").getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        assertEquals(1, stored.getAsJsonObject("enchant").getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        assertEquals("delivered", orderState(id).get("status").getAsString());

        // a bot that can't craft (the capability is withdrawn) gets the old refusal: no_base
        heartbeatWithCraft(new ManagerIndex.Craft(null, null));
        HttpResponse<String> refused = post("/api/v1/orders", "{\"server\":\"" + SERVER + "\",\"stash\":\"" + STASH + "\",\"lines\":[" + ENCHANT_LINE + "]}");
        assertEquals(400, refused.statusCode(), refused.body());
        assertEquals("no_base", JsonParser.parseString(refused.body()).getAsJsonObject().get("enchant").getAsString());
    }
}
