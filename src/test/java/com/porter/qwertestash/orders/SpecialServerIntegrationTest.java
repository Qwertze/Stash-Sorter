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
 * Tipped arrows, stew, copies and shields against a real Stash Manager server (1.7.0 with B4, CRAFT-CONTRACT-v1.2.md): the extras body it serves with the
 * recipes (hash and tables), the index with the {@code spec} the plugin writes for tipped arrows, lingering potions and stew (accepted from the stash's owner),
 * the heartbeat with {@code can: tipped, stew, copy}, orders placed on the website for each kind, and the blocks the server's planner wrote read and checked by
 * the plugin's own code and planned again by its sub-plan, with the result ({@code made[].spec}, {@code used[]}, {@code returned[]}) accepted. Only the world is
 * fake. Runs only when given a server jar: {@code gradlew test -PstashManagerJar=<copy of stash-manager-server.jar> -PstashManagerPort=18766
 * -PstashManagerData=<scratch dir>} (never the user's port 8765 or data); the server needs a client jar for the 1.21.4 recipes (found on the machine, or
 * fetched once), else the craft part is skipped.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpecialServerIntegrationTest {
    private static final String SERVER = "2b2t.org", STASH = "b4-it", PLAYER = "B4Bot";

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
        // The folder of the user's own running server: -PstashManagerProtectedDir=<folder>, by default Desktop/Qwertze under the home folder
        String protectedDir = Path.of(System.getProperty("stashManagerProtectedDir", Path.of(System.getProperty("user.home"), "Desktop", "Qwertze").toString()))
            .toAbsolutePath().normalize().toString().replace((char) 92, '/').toLowerCase();
        Assumptions.assumeFalse(jarPath.contains(protectedDir + "/stash-manager-server/build") || jarPath.matches(java.util.regex.Pattern.quote(protectedDir) + "/[^/]*[.]jar"),
            "never the user's running jar: pass a copy of it");
        try (java.net.ServerSocket probe = new java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            // Free: the probe is closed again before the server starts
        } catch (IOException busy) {
            Assumptions.abort("port " + port + " is in use");
        }
        Path data = System.getProperty("stashManagerData") != null ? Path.of(System.getProperty("stashManagerData")) : Files.createTempDirectory("sm-b4-it");
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
        }, "b4-server-output");
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


    private static final String K_ARROW = SpecialFixtures.K_ARROW, K_POTION = SpecialFixtures.K_POTION, K_STEW = SpecialFixtures.K_STEW, K_BANNER = SpecialFixtures.K_BANNER,
        K_MAP = SpecialFixtures.K_MAP, K_BOOK = SpecialFixtures.K_BOOK;
    private static final String POISON = SpecialFixtures.POISON, NV = SpecialFixtures.NV;

    private static String hashOf(String key) {
        return key.substring(key.indexOf('#') + 1);
    }

    /** The index the plugin sends for a stash with these stacks, built by its own ManagerIndex with the kinds its can says. */
    private static void postIndex() {
        Stash stash = new Stash(STASH, SERVER);
        Stash.Box a = new Stash.Box(new BlockPos(1, 2, 3), new ArrayList<>(List.of(new BlockPos(1, 2, 3))));
        a.contents = new Item[27];
        int i = 0;
        a.contents[i++] = stack("tipped_arrow", hashOf(K_ARROW), "T|long_poison", 5);
        a.contents[i++] = stack("lingering_potion", hashOf(K_POTION), "T|long_poison", 2);
        a.contents[i++] = stack("suspicious_stew", hashOf(K_STEW), "W|minecraft:night_vision|100", 3);
        a.contents[i++] = stack("arrow", null, null, 40);
        a.contents[i++] = stack("bowl", null, null, 4);
        a.contents[i++] = stack("brown_mushroom", null, null, 4);
        a.contents[i++] = stack("red_mushroom", null, null, 4);
        a.contents[i++] = stack("poppy", null, null, 3);
        a.contents[i++] = stack("white_banner", hashOf(K_BANNER), "C", 2);
        a.contents[i++] = stack("white_banner", null, null, 5);
        a.contents[i++] = stack("filled_map", hashOf(K_MAP), "C", 1);
        a.contents[i++] = stack("map", null, null, 12);
        a.contents[i++] = stack("written_book", hashOf(K_BOOK), "G0", 1);
        a.contents[i++] = stack("writable_book", null, null, 6);
        a.contents[i++] = stack("shield", null, null, 2);
        // A tipped arrow with a custom colour has no spec (S18)
        a.contents[i++] = stack("tipped_arrow", "d0d0d0d0", null, 1);
        // 16 copies of one banner key and plain blanks of its colour: a line of 20 is two loads of a stack of 16 (H1 of the review)
        a.contents[i++] = stack("red_banner", "e1e1e1e1", "C", 16);
        a.contents[i++] = stack("red_banner", null, null, 8);
        stash.boxes.add(a);
        built = ManagerIndex.buildKinds(stash, false, PLAYER, "1.21.4", "overworld", id -> null, null, new Variants.Kinds(null, true, true));
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

    private static String specLine(String id, String spec, int n) {
        return "{\"type\":\"craft\",\"key\":\"" + id + "\",\"spec\":\"" + spec + "\",\"count\":" + n + "}";
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

    private static ManagerIndex.CraftOrders capability(List<String> can) {
        return new ManagerIndex.CraftOrders(true, table.hash(), "1.21.4", "auto", 2048, List.of()).withV2(extras.hash(), can).withSmelt(256, null);
    }

    private static void beat(List<String> can) {
        ManagerIndex.CraftOrders cap = capability(can);
        heartbeat(new ManagerIndex.Craft(cap, ManagerIndex.craftReply(heartbeat(new ManagerIndex.Craft(cap, null)).body()).hash()));
    }

    /** The block of an order is read and passes the bot's own check against the real table and extras. */
    private static Order.Craft checked(JsonObject placed, Set<String> can) {
        JsonObject block = placed.getAsJsonObject("craft");
        assertNotNull(block, placed.toString());
        assertEquals(2, block.get("v").getAsInt());
        assertEquals(extras.hash(), block.get("extras").getAsString());
        Order.Craft craft = Order.parseCraft(block);
        assertNotNull(craft);
        assertFalse(craft.malformed(), block.toString());
        assertNull(craft.reserved());
        assertNull(CraftOrder.check(craft, table, 2048, extras.hash(), can, null, 0, FireworkSpecs.Tables.of(extras), VariantSpecs.Tables.of(extras)), block.toString());
        return craft;
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void theServersExtrasBodyIsTheOneThePluginHashesAndHoldsTheStewTable() throws Exception {
        fetchRecipes();
        assertEquals(SpecialFixtures.SERVER_HASH, extras.hash(), "the live server's extras hash is the hash of HandRecipes in ac87d80");
        VariantSpecs.Tables t = VariantSpecs.Tables.of(extras);
        assertNotNull(t);
        assertTrue(t.canTipped() && t.canStew() && t.canCopy());
        assertEquals(16, t.stew().size());
        assertEquals(List.of("smelt", "firework", "tipped", "stew", "copy"), ManagerIndex.craftCan(extras, true, true, true, true, true));
        // The ETag is tableHash + extrasHash
        ManagerHttp.Response r = ManagerHttp.call("GET", URI.create(base + "/api/v1/bot/recipes?mc=1.21.4&extras=1"), null, key, null, 5_000, 15_000, 20_000, null);
        if (r.code() == 200 && r.etag() != null) assertEquals(extras.hash(), Extras.hashOfEtag(r.etag()));
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void theIndexWithTippedAndStewSpecsIsAcceptedFromTheOwnerAndTheHeartbeatSaysTheKinds() throws Exception {
        fetchRecipes();
        postIndex();
        // The same index as another player is refused: a spec comes from the stash's owner only
        Stash other = new Stash(STASH, SERVER);
        Stash.Box a = new Stash.Box(new BlockPos(1, 2, 3), new ArrayList<>(List.of(new BlockPos(1, 2, 3))));
        a.contents = new Item[27];
        a.contents[0] = stack("suspicious_stew", hashOf(K_STEW), "W|minecraft:night_vision|100", 3);
        other.boxes.add(a);
        ManagerIndex.Built intruder = ManagerIndex.buildKinds(other, false, "SomeoneElse", "1.21.4", "overworld", id -> null, null, new Variants.Kinds(null, true, true));
        ManagerHttp.Response refused = call("/api/v1/index", intruder.json());
        assertTrue(refused.code() == 409 || refused.code() == 400, refused.code() + " " + refused.body());
        postIndex();
        beat(ManagerIndex.craftCan(extras, true, true, true, true, true));
        JsonObject orders = JsonParser.parseString(get("/api/v1/orders?server=" + SERVER + "&stash=" + STASH).body()).getAsJsonObject();
        JsonObject cart = orders.getAsJsonObject("craftCart");
        assertNotNull(cart, orders.toString());
        assertTrue(cart.get("canOrder").getAsBoolean(), cart.toString());
        String can = cart.getAsJsonArray("can").toString();
        assertTrue(can.contains("tipped") && can.contains("stew") && can.contains("copy"), can);
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void theBlocksOfTheServersPlannerPassTheBotsCheckAndArePlannedAgainBySubPlan() throws Exception {
        fetchRecipes();
        beat(ManagerIndex.craftCan(extras, true, true, true, true, true));
        Set<String> can = Set.of("smelt", "firework", "tipped", "stew", "copy");
        // Each order is cancelled once it is checked, so the next one finds the stash as it was (the banners of the key are one stock for the copy and the shield)
        // 10 tipped arrows (5 in stock): a craft of 8 around the lingering potion
        JsonObject tipped = place("[" + specLine("minecraft:tipped_arrow", POISON, 10) + "]");
        Order.Craft craft = checked(tipped, can);
        CraftLedger ledger = new CraftLedger(craft, List.of(new Order.Line(Order.CRAFT, "minecraft:tipped_arrow", 10, false, POISON)), table, 2048);
        CraftSub.Outcome plan = ledger.session().plan("minecraft:tipped_arrow@" + POISON, 10);
        assertTrue(plan.ok(), plan.plan().refusal());
        assertEquals(1, plan.plan().steps().size());
        assertEquals(java.util.Map.of(K_ARROW, 5L, K_POTION, 1L), ledger.keysFor(plan.plan().fromStock()));
        cancel(tipped);
        // 5 night vision stews (3 in stock)
        JsonObject stew = place("[" + specLine("minecraft:suspicious_stew", NV, 5) + "]");
        craft = checked(stew, can);
        assertTrue(craft.features().contains("stew"));
        cancel(stew);
        // 3 banner copies of the key (2 in stock: one stays the source)
        JsonObject banner = place("[" + specLine("minecraft:white_banner", "copy:" + K_BANNER, 3) + "]");
        craft = checked(banner, can);
        ledger = new CraftLedger(craft, List.of(new Order.Line(Order.CRAFT, "minecraft:white_banner", 3, false, "copy:" + K_BANNER)), table, 2048);
        String b = "minecraft:white_banner@copy:" + K_BANNER;
        plan = ledger.session().plan(b, 3);
        assertTrue(plan.ok(), plan.plan().refusal());
        assertEquals(java.util.Map.of(b, 2L, "minecraft:white_banner", 2L), plan.plan().fromStock());
        assertEquals(2, plan.plan().steps().get(0).fills());
        cancel(banner);
        // 12 map copies (1 in stock): crafts of 8 and 3, the second one's source a result of the first
        JsonObject map = place("[" + specLine("minecraft:filled_map", "copy:" + K_MAP, 12) + "]");
        craft = checked(map, can);
        assertEquals(2, craft.steps().size());
        cancel(map);
        // 6 book copies from the original (the text is never in the block)
        JsonObject book = place("[" + specLine("minecraft:written_book", "copy:" + K_BOOK, 6) + "]");
        craft = checked(book, can);
        assertFalse(book.toString().contains("pages") || book.toString().contains("author") || book.toString().contains("title"));
        assertEquals(1, craft.steps().get(0).remainder().size());
        cancel(book);
        // 2 shields with the banner of the key (2 in stock, all consumed)
        JsonObject shield = place("[" + specLine("minecraft:shield", "shield:" + K_BANNER, 2) + "]");
        checked(shield, can);
        cancel(shield);
    }

    private static void cancel(JsonObject placed) throws Exception {
        assertEquals(200, post("/api/v1/orders/" + placed.get("id").getAsString() + "/cancel", "{}").statusCode(), "cancelled, so nothing stays reserved on the scratch server");
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void aBannerCopyOrderIsClaimedCheckedPreflightedAndItsResultAccepted() throws Exception {
        fetchRecipes();
        postIndex();
        List<String> canList = ManagerIndex.craftCan(extras, true, true, true, true, true);
        ManagerHttp.Response beat = heartbeat(new ManagerIndex.Craft(capability(canList), ManagerIndex.craftReply(heartbeat(new ManagerIndex.Craft(capability(canList), null)).body()).hash()));
        JsonObject placed = place("[" + specLine("minecraft:white_banner", "copy:" + K_BANNER, 3) + "]");
        String id = placed.get("id").getAsString();
        OrderSim w = new OrderSim().craftOrders(table);
        w.extrasHash = extras.hash();
        w.can = Set.copyOf(canList);
        w.fireworks = FireworkSpecs.Tables.of(extras);
        w.specials = VariantSpecs.Tables.of(extras);
        w.preflight = true;
        w.empties.put("minecraft:red_shulker_box", 5);
        w.craftStash.put(K_BANNER, 2L);
        w.craftStash.put("minecraft:white_banner", 5L);
        OrderFlow flow = w.flow;
        OrderDriver driver = new OrderDriver(flow, () -> w.now, (level, text) -> {
        });
        driver.craftOrdersWhen(() -> true);
        driver.onHeartbeat(beat.body());
        w.now += 70_000;
        OrderDriver.Request claim = driver.next(true, null, SERVER, STASH, PLAYER, true);
        assertNotNull(claim);
        ManagerHttp.Response answer = call(claim.path(), claim.body());
        assertEquals(200, answer.code(), "the server handed the order out: " + answer.body());
        assertTrue(claim.reply().done(answer.code(), answer.body()));
        assertTrue(flow.running(), "the order was not taken: " + w.said);
        CraftLedger ledger = flow.craftLedger();
        assertNotNull(ledger, w.said.toString());
        assertFalse(ledger.refusedAtStart(), "the bot's craft check refused the server's block: " + w.said);
        assertEquals("ok", ledger.preflight(), "the preflight counted the take by key (the source included): " + w.said);
        String ref = "minecraft:white_banner@copy:" + K_BANNER;
        assertEquals(3L, ledger.wanted(ref));
        CraftSub.Outcome plan = ledger.session().plan(ref, 3);
        assertTrue(plan.ok(), plan.plan().refusal());
        assertEquals(200, status(id, statusBody("in_progress", "crafting", null)).code());
        ledger.madeChunk(ref, 3, 2, plan.plan().fromStock());
        ledger.setReturned(java.util.Map.of(ref, 1L));
        JsonObject result = new JsonObject();
        result.addProperty("shulkers", 0);
        result.add("lines", new JsonArray());
        result.add("craft", ledger.toResult());
        ManagerHttp.Response ok = status(id, statusBody("delivered", "done", result));
        assertEquals(200, ok.code(), ok.body() + " " + result);
        JsonObject stored = orderState(id).getAsJsonObject("result").getAsJsonObject("craft");
        JsonObject made = stored.getAsJsonArray("made").get(0).getAsJsonObject();
        assertEquals("minecraft:white_banner", made.get("key").getAsString());
        assertEquals("copy:" + K_BANNER, made.get("spec").getAsString(), "the server keeps the spec of made[]: " + stored);
        assertEquals(3, made.get("made").getAsInt());
        assertEquals(1, stored.getAsJsonArray("returned").size(), stored.toString());
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    void aBotThatDoesNotSayTheKindIsNeverGivenAnOrderOfIt() throws Exception {
        fetchRecipes();
        beat(List.of("smelt", "firework"));
        for (String[] c : new String[][] {{"minecraft:tipped_arrow", POISON}, {"minecraft:suspicious_stew", NV}, {"minecraft:white_banner", "copy:" + K_BANNER}, {"minecraft:shield", "shield:" + K_BANNER}}) {
            HttpResponse<String> r = post("/api/v1/orders", "{\"server\":\"" + SERVER + "\",\"stash\":\"" + STASH + "\",\"lines\":[" + specLine(c[0], c[1], 1) + "]}");
            assertEquals(409, r.statusCode(), c[0] + " " + r.body());
            assertEquals("bot_outdated", JsonParser.parseString(r.body()).getAsJsonObject().get("craft").getAsString());
        }
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    void aBannerLineOfTwoLoadsOfTheServersPlanKeepsItsSourceForTheSecondLoad() throws Exception {
        fetchRecipes();
        postIndex();
        beat(ManagerIndex.craftCan(extras, true, true, true, true, true));
        String key = "minecraft:red_banner#e1e1e1e1", spec = "copy:" + key, ref = "minecraft:red_banner@" + spec;
        JsonObject placed = place("[" + specLine("minecraft:red_banner", spec, 20) + "]");
        Order.Craft craft = checked(placed, Set.of("smelt", "firework", "tipped", "stew", "copy"));
        CraftLedger ledger = new CraftLedger(craft, List.of(new Order.Line(Order.CRAFT, "minecraft:red_banner", 20, false, spec)), table, 2048);
        long carried = 0, delivered = 0;
        while (delivered < 20) {
            int n = (int) Math.min(16, 20 - delivered);
            ledger.setSurplus(carried > 0 ? java.util.Map.of(ref, carried) : java.util.Map.of());
            CraftBatch.Choice c = CraftBatch.choose(ledger, ref, n, 27, 2048 - ledger.fills(), id -> 16, need -> need.size());
            assertTrue(c.ok(), "load of " + n + ": " + c.reason() + " " + c.detail());
            long made = 0;
            for (CraftPlanner.Step st : c.plan().steps()) made += (long) st.fills() * st.recipe().count();
            carried += c.plan().fromStock().getOrDefault(ref, 0L) + made - c.count();
            ledger.madeChunk(ref, c.count(), c.plan().fills(), c.plan().fromStock());
            delivered += c.count();
        }
        assertEquals(20, delivered);
        assertEquals(1, carried, "the source is what is left: it goes back");
        cancel(placed);
    }
}
