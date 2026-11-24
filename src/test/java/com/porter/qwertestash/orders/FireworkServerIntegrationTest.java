package com.porter.qwertestash.orders;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.Extras;
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
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rockets and stars against a real Stash Manager server (1.7.0 with B3, CRAFT-CONTRACT-v1.2.md): the extras body it serves with the recipes (hash and
 * tables), the index with the {@code spec} the plugin writes (accepted from the stash's owner), the heartbeat with {@code can: firework}, an order for
 * rockets placed on the website, claimed as the plugin claims it, and the block the server's planner wrote read, checked and planned by the plugin's own
 * code, with the preflight and the {@code made[].spec} result accepted. Only the world is fake. Runs only when given a server jar:
 * {@code gradlew test -PstashManagerJar=<copy of stash-manager-server.jar> -PstashManagerPort=18766 -PstashManagerData=<scratch dir>} (never the user's
 * port 8765 or data); the server needs a client jar for the 1.21.4 recipes (found on the machine, or fetched once), else the craft part is skipped.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FireworkServerIntegrationTest {
    private static final String SERVER = "2b2t.org", STASH = "fw-it", PLAYER = "FwBot";
    private static final String ROCKET_KEY = "minecraft:firework_rocket#ab12cd34", STAR_KEY = "minecraft:firework_star#aaaabbbb";
    private static final String ROCKET_SPEC = "rocket:2|small_ball;red;;", STAR_SPEC = "star:small_ball;red;;";

    private static Process process;
    private static int port;
    private static String key;
    private static URI base;
    private static HttpClient http;
    private static String cookie = "";
    private static final List<String> log = new CopyOnWriteArrayList<>();
    private static volatile String setupCode;
    private static ManagerIndex.Built built;
    private static Recipes table;
    private static Extras extras;

    @BeforeAll
    static void start() throws Exception {
        String jar = System.getProperty("stashManagerJar");
        Assumptions.assumeTrue(jar != null && Files.exists(Path.of(jar)), "pass -PstashManagerJar=<server jar> to run the server integration test");
        port = Integer.parseInt(System.getProperty("stashManagerPort", "18766"));
        // Safety: a scratch server only. Never the user's port, jar or data
        Assumptions.assumeTrue(port >= 18000 && port != 8765, "the port must be 18000 or more and never 8765");
        String jarPath = Path.of(jar).toAbsolutePath().normalize().toString().replace((char) 92, '/').toLowerCase();
        Assumptions.assumeFalse(jarPath.contains("/desktop/qwertze/stash-manager-server/build") || jarPath.matches(".*/desktop/qwertze/[^/]*[.]jar"),
            "never the user's running jar: pass a copy of it");
        try (java.net.ServerSocket probe = new java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            // Free: the probe is closed again before the server starts
        } catch (IOException busy) {
            Assumptions.abort("port " + port + " is in use");
        }
        Path data = System.getProperty("stashManagerData") != null ? Path.of(System.getProperty("stashManagerData")) : Files.createTempDirectory("sm-fw-it");
        Assumptions.assumeFalse(Files.exists(data.resolve("config.properties")), "the data directory already holds a server's config.properties: pass an empty scratch directory");
        Files.createDirectories(data);
        // The port is written before the server starts (it must never come up on the default one)
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
        }, "firework-server-output");
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

    private static JsonObject orderState(String id) throws Exception {
        JsonObject all = JsonParser.parseString(get("/api/v1/orders?server=" + SERVER + "&stash=" + STASH).body()).getAsJsonObject();
        for (JsonElement e : all.getAsJsonArray("orders")) if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        throw new AssertionError("order " + id + " not listed");
    }

    // The bot

    private static Item stack(String id, String keyHash, String fw, int count) {
        Item it = new Item();
        it.id = id;
        it.name = Item.prettyName(id);
        it.count = count;
        it.maxStack = 64;
        it.key = "minecraft:" + id + (keyHash == null ? "" : "#" + keyHash);
        it.variant = it.key;
        it.fw = fw;
        return it;
    }

    private static ManagerHttp.Response call(String path, byte[] body) {
        return ManagerHttp.call("POST", URI.create(base + path), body, key, null, 5_000, 15_000, 20_000, null);
    }

    private static final int RED = FireworkFixtures.fw("red");

    /** The index the plugin sends for a stash with these rockets, stars and materials, built by its own ManagerIndex with the extras' firework tables. */
    private static void postIndex() {
        Stash stash = new Stash(STASH, SERVER);
        Stash.Box a = new Stash.Box(new BlockPos(1, 2, 3), new ArrayList<>(List.of(new BlockPos(1, 2, 3))));
        a.contents = new Item[27];
        a.contents[0] = stack("firework_rocket", "ab12cd34", FireworkSpecs.encodeRocket(2, List.of(FireworkSpecs.Explosion.of(0, new int[] {RED}, new int[0], false, false))), 2);
        a.contents[1] = stack("firework_star", "aaaabbbb", FireworkSpecs.encodeStar(FireworkSpecs.Explosion.of(0, new int[] {RED}, new int[0], false, false)), 4);
        a.contents[2] = stack("paper", null, null, 64);
        a.contents[3] = stack("gunpowder", null, null, 64);
        a.contents[4] = stack("gold_nugget", null, null, 8);
        a.contents[5] = stack("lime_dye", null, null, 8);
        // A plain rocket (no patch): rocket:1, and a rocket with a custom name (no spec)
        a.contents[6] = stack("firework_rocket", null, FireworkSpecs.encodeRocket(1, List.of()), 16);
        a.contents[7] = stack("firework_rocket", "55556666", null, 1);
        stash.boxes.add(a);
        built = ManagerIndex.build(stash, false, PLAYER, "1.21.4", "overworld", id -> null, null, FireworkFixtures.TABLES);
        ManagerHttp.Response r = call("/api/v1/index", built.json());
        assertEquals(200, r.code(), r.body() + " " + r.error());
    }

    private static ManagerHttp.Response heartbeat(ManagerIndex.Craft craft) {
        ManagerHttp.Response r = call("/api/v1/heartbeat", ManagerIndex.heartbeat(built, -1, null, null, null, null, null, null, null, null, craft));
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

    private static JsonObject place(String lines) throws Exception {
        HttpResponse<String> r = post("/api/v1/orders", "{\"server\":\"" + SERVER + "\",\"stash\":\"" + STASH + "\",\"lines\":" + lines + "}");
        assertEquals(200, r.statusCode(), r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("order");
    }

    /** The server's recipes with extras for 1.21.4; the test is skipped when it has none. */
    private static void fetchRecipes() throws Exception {
        if (table != null) return;
        // The server builds a version's recipes once an index of that version is there
        postIndex();
        String recipes = null, why = "no answer";
        long until = System.currentTimeMillis() + 240_000;
        while (System.currentTimeMillis() < until) {
            ManagerHttp.Response r = ManagerHttp.call("GET", URI.create(base + "/api/v1/bot/recipes?mc=1.21.4&extras=1"), null, key, null, 5_000, 15_000, 20_000, null);
            if (r.code() == 200) {
                recipes = r.body();
                break;
            }
            why = r.code() + " " + (r.body() == null ? r.error() : r.body().substring(0, Math.min(120, r.body().length())));
            if (r.code() != 503 && r.code() != 404 && r.code() != 429) break;
            Thread.sleep(11_000);
        }
        Assumptions.assumeTrue(recipes != null, "the server gave no 1.21.4 recipes (" + why + "): the craft part is skipped");
        JsonObject root = JsonParser.parseString(recipes).getAsJsonObject();
        table = Recipes.fromJson(root);
        extras = Extras.parse(root.getAsJsonObject("extras"));
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void theServersExtrasBodyIsTheOneThePluginHashesAndTheTablesAreTheFireworkTables() throws Exception {
        fetchRecipes();
        // The B3 server (1d99fa1) and the B4 server (ac87d80, which adds the B4 kinds and the stew table) both serve the firework tables: either hash is a live server's
        assertTrue(extras.hash().equals(FireworkFixtures.SERVER_HASH) || extras.hash().equals(SpecialFixtures.SERVER_HASH), "the live server's extras hash is the hash of HandRecipes in 1d99fa1 or ac87d80: " + extras.hash());
        assertNotNull(FireworkSpecs.Tables.of(extras));
        assertEquals(List.of("smelt", "firework"), ManagerIndex.craftCan(extras, true, true));
        // The ETag is tableHash + extrasHash
        ManagerHttp.Response r = ManagerHttp.call("GET", URI.create(base + "/api/v1/bot/recipes?mc=1.21.4&extras=1"), null, key, null, 5_000, 15_000, 20_000, null);
        if (r.code() == 200 && r.etag() != null) assertEquals(extras.hash(), Extras.hashOfEtag(r.etag()));
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void theIndexWithSpecsIsAcceptedFromTheOwnerAndTheHeartbeatSaysFirework() throws Exception {
        fetchRecipes();
        postIndex();
        // The same index as another player is refused: a spec comes from the stash's owner only
        Stash other = new Stash(STASH, SERVER);
        Stash.Box a = new Stash.Box(new BlockPos(1, 2, 3), new ArrayList<>(List.of(new BlockPos(1, 2, 3))));
        a.contents = new Item[27];
        a.contents[0] = stack("firework_star", "aaaabbbb", FireworkSpecs.encodeStar(FireworkSpecs.Explosion.of(0, new int[] {RED}, new int[0], false, false)), 4);
        other.boxes.add(a);
        ManagerIndex.Built intruder = ManagerIndex.build(other, false, "SomeoneElse", "1.21.4", "overworld", id -> null, null, FireworkFixtures.TABLES);
        ManagerHttp.Response refused = call("/api/v1/index", intruder.json());
        assertTrue(refused.code() == 409 || refused.code() == 400, refused.code() + " " + refused.body());
        postIndex();

        ManagerIndex.CraftOrders capability = new ManagerIndex.CraftOrders(true, table.hash(), "1.21.4", "auto", 2048, List.of())
            .withV2(extras.hash(), ManagerIndex.craftCan(extras, true, true)).withSmelt(256, null);
        ManagerHttp.Response first = heartbeat(new ManagerIndex.Craft(capability, null));
        String craftHash = ManagerIndex.craftReply(first.body()).hash();
        assertNotNull(craftHash, first.body());
        ManagerHttp.Response beat = heartbeat(new ManagerIndex.Craft(capability, craftHash));
        JsonObject reply = JsonParser.parseString(beat.body()).getAsJsonObject();
        assertEquals(extras.hash(), reply.get("extrasHash").getAsString(), "the server's extras hash is the bot's: no refetch is asked for");
        // The server lists the stash as able to take craft orders for fireworks
        JsonObject orders = JsonParser.parseString(get("/api/v1/orders?server=" + SERVER + "&stash=" + STASH).body()).getAsJsonObject();
        JsonObject cart = orders.getAsJsonObject("craftCart");
        assertNotNull(cart, orders.toString());
        assertTrue(cart.get("canOrder").getAsBoolean(), cart.toString());
        assertTrue(cart.getAsJsonArray("can").toString().contains("firework"), cart.toString());
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void anOrderForRocketsIsPlannedClaimedCheckedAndPlannedAgainByThePlugin() throws Exception {
        fetchRecipes();
        // 6 rockets of the spec asked for by the key of the 2 in stock: 2 from stock and 4 made (two fills make 6, with 2 left over)
        JsonObject placed = place("[{\"type\":\"item\",\"key\":\"" + ROCKET_KEY + "\",\"count\":6,\"orCraft\":true}]");
        String id = placed.get("id").getAsString();
        assertEquals("pending", placed.get("status").getAsString());
        JsonObject block = placed.getAsJsonObject("craft");
        assertNotNull(block, placed.toString());
        assertEquals(2, block.get("v").getAsInt());
        assertTrue(block.getAsJsonArray("needs").toString().contains("firework"), block.toString());
        assertEquals(extras.hash(), block.get("extras").getAsString());

        // The claim, as the plugin sends it, with the bot's extras, features and firework tables
        ManagerIndex.CraftOrders capability = new ManagerIndex.CraftOrders(true, table.hash(), "1.21.4", "auto", 2048, List.of())
            .withV2(extras.hash(), ManagerIndex.craftCan(extras, true, true)).withSmelt(256, null);
        ManagerHttp.Response beat = heartbeat(new ManagerIndex.Craft(capability, ManagerIndex.craftReply(heartbeat(new ManagerIndex.Craft(capability, null)).body()).hash()));
        OrderSim w = new OrderSim().craftOrders(table);
        w.extrasHash = extras.hash();
        w.can = Set.of("smelt", "firework");
        w.fireworks = FireworkSpecs.Tables.of(extras);
        w.preflight = true;
        w.empties.put("minecraft:red_shulker_box", 5);
        // The stash as the preflight counts it: by key
        w.craftStash.put(ROCKET_KEY, 2L);
        w.craftStash.put(STAR_KEY, 4L);
        w.craftStash.put("minecraft:paper", 64L);
        w.craftStash.put("minecraft:gunpowder", 64L);
        OrderFlow flow = w.flow;
        OrderDriver driver = new OrderDriver(flow, () -> w.now, (level, text) -> {
        });
        driver.craftOrdersWhen(() -> true);
        driver.onHeartbeat(beat.body());
        w.now += 70_000;
        OrderDriver.Request claim = driver.next(true, null, SERVER, STASH, PLAYER, true);
        assertNotNull(claim);
        assertEquals(2, JsonParser.parseString(new String(claim.body(), StandardCharsets.UTF_8)).getAsJsonObject().get("craftOrders").getAsInt());
        ManagerHttp.Response answer = call(claim.path(), claim.body());
        assertEquals(200, answer.code(), "the server handed the order out: " + answer.body());
        assertTrue(claim.reply().done(answer.code(), answer.body()));
        assertTrue(flow.running(), "the order was not taken: " + w.said);

        // The block of the real server, read by the plugin and passed by its own check (grid, specs, keys, extras)
        CraftLedger ledger = flow.craftLedger();
        assertNotNull(ledger, w.said.toString());
        assertFalse(ledger.refusedAtStart(), "the bot's craft check refused the server's block: " + w.said);
        assertTrue(ledger.hasSpecs());
        String ref = "minecraft:firework_rocket@" + ROCKET_SPEC;
        assertEquals(4L, ledger.wanted(ref), "the craft part of the line");
        assertEquals("ok", ledger.preflight(), "the preflight counted the take by key: " + w.said);
        Order.Craft craft = Order.parseCraft(block);
        assertNotNull(craft);
        assertTrue(craft.usesSpecs());
        assertNull(craft.reserved());
        assertNull(CraftOrder.check(craft, table, 2048, extras.hash(), Set.of("smelt", "firework"), null, 0, FireworkSpecs.Tables.of(extras)));
        Order.CraftStep rocketStep = craft.steps().stream().filter(Order.CraftStep::special).findFirst().orElseThrow();
        assertEquals(FireworkSpecs.RECIPE_ROCKET, rocketStep.recipe());
        assertEquals(ROCKET_SPEC, rocketStep.spec());
        assertEquals(9, rocketStep.grid().size());
        assertEquals(3, rocketStep.count(), "3 rockets a craft (UNVERIFIED in game): the server and the bot agree on the number");

        // The sub-plan from the block alone makes what the server planned
        CraftSub.Outcome plan = ledger.session().plan(ref, 4);
        assertTrue(plan.ok(), plan.plan().refusal());
        assertEquals(1, plan.plan().steps().size());
        assertEquals(2, plan.plan().steps().get(0).fills(), "4 rockets are two fills of the 3-rocket recipe");
        assertTrue(plan.plan().fromStock().containsKey("minecraft:firework_star@" + STAR_SPEC), plan.plan().fromStock().toString());
        assertEquals(java.util.Map.of(STAR_KEY, 2L), ledger.keysFor(plan.plan().fromStock()));

        // The result the plugin would send: made[] with the spec; the server stores it
        assertEquals(200, status(id, statusBody("in_progress", "crafting", null)).code());
        ledger.madeChunk(ref, 4, 2, plan.plan().fromStock());
        JsonObject result = new JsonObject();
        result.addProperty("shulkers", 0);
        result.add("lines", new JsonArray());
        result.add("craft", ledger.toResult());
        ManagerHttp.Response ok = status(id, statusBody("delivered", "done", result));
        assertEquals(200, ok.code(), ok.body() + " " + result);
        JsonObject stored = orderState(id).getAsJsonObject("result").getAsJsonObject("craft");
        JsonObject made = stored.getAsJsonArray("made").get(0).getAsJsonObject();
        assertEquals("minecraft:firework_rocket", made.get("key").getAsString());
        assertEquals(ROCKET_SPEC, made.get("spec").getAsString(), "the server keeps the spec of made[]: " + stored);
        assertEquals(4, made.get("made").getAsInt());
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void aStarThatHasToBeMadeIsAStepBeforeTheRocketAndTheBlockPasses() throws Exception {
        fetchRecipes();
        // A lime star rocket: no such star in stock, so the server plans a star step and then the rocket step that uses it
        JsonObject placed = place("[{\"type\":\"craft\",\"key\":\"minecraft:firework_rocket\",\"count\":3,\"spec\":\"rocket:1|star;lime;;\"}]");
        String id = placed.get("id").getAsString();
        JsonObject block = placed.getAsJsonObject("craft");
        assertNotNull(block, placed.toString());
        Order.Craft craft = Order.parseCraft(block);
        assertFalse(craft.malformed());
        assertTrue(craft.steps().stream().filter(Order.CraftStep::special).count() >= 2, block.toString());
        assertNull(CraftOrder.check(craft, table, 2048, extras.hash(), Set.of("smelt", "firework"), null, 0, FireworkSpecs.Tables.of(extras)), block.toString());
        CraftLedger ledger = new CraftLedger(craft, List.of(new Order.Line(Order.CRAFT, "minecraft:firework_rocket", 3, false, "rocket:1|star;lime;;")), table, 2048);
        CraftSub.Outcome plan = ledger.session().plan("minecraft:firework_rocket@rocket:1|star;lime;;", 3);
        assertTrue(plan.ok(), plan.plan().refusal());
        assertEquals("minecraft:firework_star@star:star;lime;;", plan.plan().steps().get(0).recipe().result(), "the star is made first");
        // The order is cancelled: nothing stays reserved on the scratch server
        assertEquals(200, post("/api/v1/orders/" + id + "/cancel", "{}").statusCode());
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    void aBotThatDoesNotSayFireworkIsNeverGivenARocketOrder() throws Exception {
        fetchRecipes();
        ManagerIndex.CraftOrders without = new ManagerIndex.CraftOrders(true, table.hash(), "1.21.4", "auto", 2048, List.of())
            .withV2(extras.hash(), List.of("smelt")).withSmelt(256, null);
        heartbeat(new ManagerIndex.Craft(without, ManagerIndex.craftReply(heartbeat(new ManagerIndex.Craft(without, null)).body()).hash()));
        HttpResponse<String> r = post("/api/v1/orders", "{\"server\":\"" + SERVER + "\",\"stash\":\"" + STASH + "\",\"lines\":[{\"type\":\"craft\",\"key\":\"minecraft:firework_rocket\",\"count\":3,\"spec\":\"rocket:1\"}]}");
        assertEquals(409, r.statusCode(), r.body());
        assertEquals("bot_outdated", JsonParser.parseString(r.body()).getAsJsonObject().get("craft").getAsString());
    }
}
