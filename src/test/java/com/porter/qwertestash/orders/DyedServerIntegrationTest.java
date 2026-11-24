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
 * Dyed leather armour, leather horse armour and wolf armour against a real Stash Manager server (1.7.0 with B5, stash-manager-server f672392,
 * CRAFT-CONTRACT-v1.2.md): the extras body it serves with the recipes (hash, armour colours, dyeable list), the index with the {@code dyed:} specs the plugin
 * writes (accepted from the stash's owner), the heartbeat with {@code can: dyed}, orders placed on the website for one coat, a mix and two coats in chunks, and the
 * blocks the server's planner wrote read and checked by the plugin's own code (its own colour arithmetic against the server's) and planned again by its sub-plan,
 * with the result ({@code made[].spec}) accepted. Only the world is fake. Runs only when given a server jar: {@code gradlew test -PstashManagerJar=<copy of
 * stash-manager-server.jar> -PstashManagerPort=18766 -PstashManagerData=<scratch dir>} (never the user's port 8765 or data); the server needs a client jar for
 * the 1.21.4 recipes (found on the machine, or fetched once), else the craft part is skipped.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DyedServerIntegrationTest {
    private static final String SERVER = "2b2t.org", STASH = "b5-it", PLAYER = "B5Bot";
    private static final String CHEST = DyedFixtures.CHEST, RED = DyedFixtures.RED, K_RED = DyedFixtures.K_RED1, K_BLACK = DyedFixtures.K_BLACK, K_BLUE = "minecraft:wolf_armor#f0f0f0f0";
    private static final String BLUE = "dyed:3c44aa";

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
        Path data = System.getProperty("stashManagerData") != null ? Path.of(System.getProperty("stashManagerData")) : Files.createTempDirectory("sm-b5-it");
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
        }, "b5-server-output");
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

    private static Item stack(String id, String keyHash, String fw, int count, int maxStack) {
        Item it = new Item();
        it.id = id;
        it.name = Item.prettyName(id);
        it.count = count;
        it.maxStack = maxStack;
        it.key = "minecraft:" + id + (keyHash == null ? "" : "#" + keyHash);
        it.variant = it.key;
        it.fw = fw;
        return it;
    }

    private static ManagerHttp.Response call(String path, byte[] body) {
        return ManagerHttp.call("POST", URI.create(base + path), body, key, null, 5_000, 15_000, 20_000, null);
    }

    private static String hashOf(String k) {
        return k.substring(k.indexOf('#') + 1);
    }

    /** The kinds the bot's index is built with: its can says dyed, and the extras' dyeable list is the server's. */
    private static Variants.Kinds kinds() {
        return new Variants.Kinds(null, false, false).withDyeable(DyedFixtures.DYED.dyeable());
    }

    /**
     * The index the plugin sends for a stash with these stacks, built by its own ManagerIndex with the kinds its can says: 3 red chestplates and a black pair of
     * boots and a blue wolf armour (dyed, each its own key), undyed pieces, a wool that has a colour (S16), and all 16 dyes.
     */
    private static void postIndex() {
        Stash stash = new Stash(STASH, SERVER);
        Stash.Box a = new Stash.Box(new BlockPos(1, 2, 3), new ArrayList<>(List.of(new BlockPos(1, 2, 3))));
        a.contents = new Item[27];
        int i = 0;
        for (int k = 0; k < 3; k++) a.contents[i++] = stack("leather_chestplate", hashOf(K_RED), "D|b02e26", 1, 1);
        a.contents[i++] = stack("leather_boots", hashOf(K_BLACK), "D|000000", 1, 1);
        a.contents[i++] = stack("wolf_armor", hashOf(K_BLUE), "D|3c44aa", 1, 1);
        a.contents[i++] = stack("white_wool", "e0e0e0e0", "D|b02e26", 4, 64);
        for (int k = 0; k < 8; k++) a.contents[i++] = stack("leather_chestplate", null, null, 1, 1);
        a.contents[i++] = stack("leather_horse_armor", null, null, 1, 1);
        a.contents[i++] = stack("leather", null, null, 64, 64);
        Stash.Box b = new Stash.Box(new BlockPos(2, 2, 3), new ArrayList<>(List.of(new BlockPos(2, 2, 3))));
        b.contents = new Item[27];
        int j = 0;
        for (String d : DyedFixtures.NAMES) b.contents[j++] = stack(d + "_dye", null, null, 64, 64);
        stash.boxes.add(a);
        stash.boxes.add(b);
        built = ManagerIndex.buildKinds(stash, false, PLAYER, "1.21.4", "overworld", id -> null, null, kinds());
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

    private static HttpResponse<String> tryPlace(String lines) throws Exception {
        return post("/api/v1/orders", "{\"server\":\"" + SERVER + "\",\"stash\":\"" + STASH + "\",\"lines\":" + lines + "}");
    }

    private static JsonObject place(String lines) throws Exception {
        HttpResponse<String> r = tryPlace(lines);
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

    private static List<String> everything() {
        return ManagerIndex.craftCan(extras, true, true, true, true, true, true);
    }

    /** The block of an order is read and passes the bot's own check against the real table and extras (its colour arithmetic against the server's). */
    private static Order.Craft checked(JsonObject placed, Set<String> can) {
        JsonObject block = placed.getAsJsonObject("craft");
        assertNotNull(block, placed.toString());
        assertEquals(2, block.get("v").getAsInt());
        assertEquals(extras.hash(), block.get("extras").getAsString());
        assertTrue(block.getAsJsonArray("needs").toString().contains("dyed"), block.toString());
        Order.Craft craft = Order.parseCraft(block);
        assertNotNull(craft);
        assertFalse(craft.malformed(), block.toString());
        assertNull(craft.reserved());
        assertNull(CraftOrder.check(craft, table, 2048, extras.hash(), can, null, 0, FireworkSpecs.Tables.of(extras), VariantSpecs.Tables.of(extras), DyedSpecs.Tables.of(extras)), block.toString());
        return craft;
    }

    private static void cancel(JsonObject placed) throws Exception {
        assertEquals(200, post("/api/v1/orders/" + placed.get("id").getAsString() + "/cancel", "{}").statusCode(), "cancelled, so nothing stays reserved on the scratch server");
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void theServersExtrasBodyIsTheOneThePluginHashesAndHoldsTheArmourColoursAndTheDyeableItems() throws Exception {
        fetchRecipes();
        assertEquals(DyedFixtures.SERVER_HASH, extras.hash(), "the live server's extras hash is the hash of HandRecipes in f672392");
        DyedSpecs.Tables t = DyedSpecs.Tables.of(extras);
        assertNotNull(t);
        assertTrue(t.can());
        assertEquals(Set.copyOf(DyedFixtures.DYED.dyeable()), t.dyeable());
        assertEquals(6, t.dyeable().size());
        for (int i = 0; i < 16; i++) assertEquals(DyedFixtures.PALETTE[i], t.palette()[i], DyedFixtures.NAMES.get(i));
        assertEquals(List.of("smelt", "firework", "tipped", "stew", "copy", "dyed"), everything());
        ManagerHttp.Response r = ManagerHttp.call("GET", URI.create(base + "/api/v1/bot/recipes?mc=1.21.4&extras=1"), null, key, null, 5_000, 15_000, 20_000, null);
        if (r.code() == 200 && r.etag() != null) assertEquals(extras.hash(), Extras.hashOfEtag(r.etag()));
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void theIndexWithDyedSpecsIsAcceptedFromTheOwnerAndTheHeartbeatSaysDyed() throws Exception {
        fetchRecipes();
        postIndex();
        // The same index as another player is refused: a spec comes from the stash's owner only
        Stash other = new Stash(STASH, SERVER);
        Stash.Box a = new Stash.Box(new BlockPos(1, 2, 3), new ArrayList<>(List.of(new BlockPos(1, 2, 3))));
        a.contents = new Item[27];
        a.contents[0] = stack("leather_chestplate", hashOf(K_RED), "D|b02e26", 1, 1);
        other.boxes.add(a);
        ManagerIndex.Built intruder = ManagerIndex.buildKinds(other, false, "SomeoneElse", "1.21.4", "overworld", id -> null, null, kinds());
        ManagerHttp.Response refused = call("/api/v1/index", intruder.json());
        assertTrue(refused.code() == 409 || refused.code() == 400, refused.code() + " " + refused.body());
        postIndex();
        beat(everything());
        JsonObject orders = JsonParser.parseString(get("/api/v1/orders?server=" + SERVER + "&stash=" + STASH).body()).getAsJsonObject();
        JsonObject cart = orders.getAsJsonObject("craftCart");
        assertNotNull(cart, orders.toString());
        assertTrue(cart.get("canOrder").getAsBoolean(), cart.toString());
        assertTrue(cart.getAsJsonArray("can").toString().contains("dyed"), cart.toString());
        assertTrue(cart.get("dyedReady").getAsBoolean(), "the extras match and the bot says dyed: " + cart);
        // The index the server holds has the specs of S14 and S15 on the dyed keys (the page's quote sees them as stock), none on the wool or the plain pieces
        // An item line of the red chestplate's key, 5 wanted, "or craft": the 3 in stock, then 2 more made (the page's quote counts the index's dyed keys as stock)
        JsonObject quote = JsonParser.parseString(post("/api/v1/craft/cart", "{\"server\":\"" + SERVER + "\",\"stash\":\"" + STASH + "\",\"lines\":[{\"type\":\"item\",\"key\":\"" + K_RED
            + "\",\"count\":5,\"orCraft\":true}]}").body()).getAsJsonObject();
        assertTrue(quote.get("ok").getAsBoolean(), quote.toString());
        JsonObject line = quote.getAsJsonArray("lines").get(0).getAsJsonObject();
        assertEquals(3, line.get("stock").getAsInt(), "the three red chestplates the index says: " + quote);
        assertEquals(2, line.get("craft").getAsInt(), quote.toString());
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void theBlocksOfTheServersPlannerPassTheBotsCheckAndArePlannedAgainBySubPlan() throws Exception {
        fetchRecipes();
        beat(everything());
        Set<String> can = Set.copyOf(everything());
        // 8 red chestplates (3 in stock): 5 plain ones and 5 red dyes, one craft each
        JsonObject red = place("[" + specLine(CHEST, RED, 8) + "]");
        Order.Craft craft = checked(red, can);
        CraftLedger ledger = new CraftLedger(craft, List.of(new Order.Line(Order.CRAFT, CHEST, 8, false, RED)), table, 2048);
        String ref = CHEST + "@" + RED;
        CraftSub.Outcome plan = ledger.session().plan(ref, 8);
        assertTrue(plan.ok(), plan.plan().refusal());
        assertEquals(java.util.Map.of(ref, 3L, CHEST, 5L, "minecraft:red_dye", 5L), plan.plan().fromStock());
        assertEquals(5, plan.plan().fills());
        assertEquals(java.util.Map.of(K_RED, 3L), ledger.keysFor(java.util.Map.of(ref, 3L)));
        cancel(red);
        // A mix of two dyes: the server writes the yellow dye first (the contract's order), and the bot's arithmetic gives the server's colour
        JsonObject orange = place("[" + specLine(CHEST, DyedFixtures.ORANGE, 2) + "]");
        craft = checked(orange, can);
        assertEquals("minecraft:yellow_dye", craft.steps().get(craft.steps().size() - 1).grid().get(1).id());
        cancel(orange);
        // Horse armour and wolf armour dyed from stock (the plain pieces) and a colour in stock by key
        JsonObject horse = place("[" + specLine("minecraft:leather_horse_armor", BLUE, 1) + "]");
        checked(horse, can);
        cancel(horse);
        JsonObject wolfStock = place("[" + specLine("minecraft:wolf_armor", BLUE, 1) + "]");
        craft = checked(wolfStock, can);
        assertTrue(craft.steps().isEmpty(), "one blue wolf armour is in stock: nothing to make");
        cancel(wolfStock);
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    void aTwoCoatColourInChunksIsWrittenAsInterleavedStepsAndPassesTheBotsCheck() throws Exception {
        fetchRecipes();
        beat(everything());
        Set<String> can = Set.copyOf(everything());
        int tried = 0, passed = 0, interleaved = 0;
        for (DyedFixtures.Two two : DyedFixtures.twoCoats()) {
            if (tried++ >= 6) break;
            String result = DyedSpecs.specOf(two.result());
            // 10 pieces: a chunk of 8 and one of 2 (the plain pieces in stock are 8, the other 2 are crafted from leather)
            HttpResponse<String> r = tryPlace("[" + specLine(CHEST, result, 10) + "]");
            if (r.statusCode() != 200) continue; // the stash's dyes may not cover that colour's best path: not every colour is placed
            JsonObject placed = JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonObject("order");
            Order.Craft craft = checked(placed, can);
            int dyed = 0;
            for (Order.CraftStep s : craft.steps()) if (DyedSpecs.isRecipe(s.recipe())) dyed++;
            assertTrue(dyed >= 2 && dyed <= 4, "one pair for 10 pieces in chunks of 8 and 2, or a one-coat colour: " + dyed + " dyeing steps");
            if (dyed == 4) {
                interleaved++;
                // Interleaved: first coat of 8, second coat of those 8, first coat of 2, second coat of those 2
                List<Order.CraftStep> d = new ArrayList<>();
                for (Order.CraftStep s : craft.steps()) if (DyedSpecs.isRecipe(s.recipe())) d.add(s);
                assertFalse(DyedSpecs.isSecondCoat(d.get(0)));
                assertTrue(DyedSpecs.isSecondCoat(d.get(1)));
                assertFalse(DyedSpecs.isSecondCoat(d.get(2)));
                assertTrue(DyedSpecs.isSecondCoat(d.get(3)));
                assertEquals(8, d.get(0).fills());
                assertEquals(8, d.get(1).fills());
                assertEquals(2, d.get(2).fills());
                assertEquals(2, d.get(3).fills());
                CraftLedger ledger = new CraftLedger(craft, List.of(new Order.Line(Order.CRAFT, CHEST, 10, false, result)), table, 2048);
                CraftSub.Outcome plan = ledger.session().plan(CHEST + "@" + result, 10);
                assertTrue(plan.ok(), plan.plan().refusal());
                for (var e : plan.plan().fromStock().entrySet()) assertFalse(e.getKey().contains("@dyed:"), "no dyed piece is taken from stock for this colour: " + e);
            }
            passed++;
            cancel(placed);
        }
        assertTrue(passed > 0, "at least one two-coat colour was placed and checked");
        assertTrue(interleaved > 0, "at least one of them was written as two pairs of interleaved steps (chunks of 8 and 2)");
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    void aDyedOrderIsClaimedCheckedPreflightedAndItsResultWithTheSpecAccepted() throws Exception {
        fetchRecipes();
        postIndex();
        List<String> canList = everything();
        ManagerHttp.Response beat = heartbeat(new ManagerIndex.Craft(capability(canList), ManagerIndex.craftReply(heartbeat(new ManagerIndex.Craft(capability(canList), null)).body()).hash()));
        JsonObject placed = place("[" + specLine(CHEST, RED, 8) + "]");
        String id = placed.get("id").getAsString();
        OrderSim w = new OrderSim().craftOrders(table);
        w.extrasHash = extras.hash();
        w.can = Set.copyOf(canList);
        w.fireworks = FireworkSpecs.Tables.of(extras);
        w.specials = VariantSpecs.Tables.of(extras);
        w.dyes = DyedSpecs.Tables.of(extras);
        w.preflight = true;
        w.empties.put("minecraft:red_shulker_box", 5);
        w.craftStash.put(K_RED, 3L);
        w.craftStash.put(CHEST, 8L);
        w.craftStash.put("minecraft:red_dye", 64L);
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
        assertEquals("ok", ledger.preflight(), "the preflight counted the take by key (the dyed stock) and by id: " + w.said);
        String ref = CHEST + "@" + RED;
        assertEquals(8L, ledger.wanted(ref));
        CraftSub.Outcome plan = ledger.session().plan(ref, 8);
        assertTrue(plan.ok(), plan.plan().refusal());
        assertEquals(200, status(id, statusBody("in_progress", "crafting", null)).code());
        ledger.madeChunk(ref, 8, plan.plan().fills(), plan.plan().fromStock());
        JsonObject result = new JsonObject();
        result.addProperty("shulkers", 0);
        result.add("lines", new JsonArray());
        result.add("craft", ledger.toResult());
        ManagerHttp.Response ok = status(id, statusBody("delivered", "done", result));
        assertEquals(200, ok.code(), ok.body() + " " + result);
        JsonObject stored = orderState(id).getAsJsonObject("result").getAsJsonObject("craft");
        JsonObject made = stored.getAsJsonArray("made").get(0).getAsJsonObject();
        assertEquals(CHEST, made.get("key").getAsString());
        assertEquals(RED, made.get("spec").getAsString(), "the server keeps the dyed spec of made[]: " + stored);
        assertEquals(8, made.get("made").getAsInt());
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    void aBotThatDoesNotSayDyedIsNeverGivenADyedOrder() throws Exception {
        fetchRecipes();
        beat(List.of("smelt", "firework", "tipped", "stew", "copy"));
        HttpResponse<String> r = tryPlace("[" + specLine(CHEST, RED, 1) + "]");
        assertEquals(409, r.statusCode(), r.body());
        assertEquals("bot_outdated", JsonParser.parseString(r.body()).getAsJsonObject().get("craft").getAsString());
        // The order of a kind it can make is placed as before
        assertEquals(200, tryPlace("[{\"type\":\"item\",\"key\":\"minecraft:leather\",\"count\":2}]").statusCode());
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    void aServerThatLacksTheB5ExtrasMakesTheBotSayNoDyed() throws Exception {
        fetchRecipes();
        // The B4 server's extras (the plugin's own fixture): the bot says its B4 kinds and never dyed, and an index built for it carries no dyed spec
        assertEquals(List.of("tipped", "stew", "copy"), ManagerIndex.craftCan(SpecialFixtures.EXTRAS, false, false, true, true, true, true));
        assertEquals(List.of("smelt", "firework", "tipped", "stew", "copy"), ManagerIndex.craftCan(extras, true, true, true, true, true, false));
    }
}
