package com.porter.qwertestash.pearl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.ManagerHttp;
import com.porter.qwertestash.manager.ManagerIndex;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pearl travel against a real Stash Manager server 1.3.0: the traveller's heartbeat body and the plugin's own reading of the replies, the loader's signed
 * {@code bot/pearls} report and its {@code allow} answer, the owner's approval, the pin and the allowed list, and a whole one-way trip (go, whisper, pull
 * confirmed from both sides, pickup, re-arm, completed). The bot's world is a fake one; every other piece is the plugin's own code.
 * <p>
 * Runs only when given a server jar: {@code gradlew test --tests *PearlServerIntegrationTest -PstashManagerJar=<copy of the jar> -PstashManagerPort=<free port>
 * -PstashManagerData=<scratch dir>}. It starts the server with its own data directory and stops it afterwards (never port 8765).
 */
class PearlServerIntegrationTest {
    private static final String SERVER = "2b2t.org", STASH = "pearl-it", TRAVELLER = "ItTravel", LOADER = "ItLoader";
    private static final UUID TRAVELLER_UUID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final String INDEX_HASH = "pearl-it-index-hash";

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
        port = Integer.parseInt(System.getProperty("stashManagerPort", "8792"));
        Path data = System.getProperty("stashManagerData") != null ? Path.of(System.getProperty("stashManagerData")) : Files.createTempDirectory("sm-pearl-it");
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
        }, "pearl-server-output");
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
        assertNotNull(key);
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

    private static JsonObject ok(HttpResponse<String> r) {
        assertTrue(r.statusCode() / 100 == 2, r.statusCode() + " " + r.body());
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static JsonObject pearlPage() throws Exception {
        return ok(get("/api/v1/pearl"));
    }

    private static JsonObject station(String id) throws Exception {
        for (JsonElement e : pearlPage().getAsJsonArray("stations")) if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        throw new AssertionError("station not listed");
    }

    // The bots

    private static ManagerHttp.Response call(String path, JsonObject body, @Nullable String botId) {
        return ManagerHttp.call("POST", URI.create(base + path), body.toString().getBytes(StandardCharsets.UTF_8), key, botId, 5_000, 15_000, 20_000, null);
    }

    private static final class Clock {
        long offset;

        long now() {
            return System.currentTimeMillis() + offset;
        }
    }

    private static PearlTripDriverTest.FakeWorld world(Clock clock) {
        return new PearlTripDriverTest.FakeWorld() {
            @Override
            public long now() {
                return clock.now();
            }

            @Override
            public @Nullable String uuid() {
                return TRAVELLER_UUID.toString();
            }
        };
    }

    private static PearlTripDriver.ManagerBody beat(PearlTripDriver driver) {
        PearlTripDriver.ManagerBody b = driver.body();
        ManagerIndex.Built built = new ManagerIndex.Built(SERVER, STASH, TRAVELLER, INDEX_HASH, new byte[0], 3, 3, 0, 2);
        byte[] body = ManagerIndex.heartbeat(built, -1, null, AwayGuard.blocker(), null, null, null, null, null, null, null, null, null, new ManagerIndex.PearlHb(b.object(), b.clear()));
        ManagerHttp.Response r = ManagerHttp.call("POST", URI.create(base + "/api/v1/heartbeat"), body, key, null, 5_000, 15_000, 20_000, null);
        assertEquals(200, r.code(), r.body() + " " + r.error());
        driver.onReply(r.body());
        return b;
    }

    /** The loader as the plugin runs it: the real reporter and the real transport, a fake PearlPlus. */
    private static final class Loader {
        final String botId = UUID.randomUUID().toString();
        final PearlLoaderTestSource source = new PearlLoaderTestSource();
        final List<String> bodies = new ArrayList<>();
        final PearlReporter reporter = new PearlReporter(source, new PearlReporter.Transport() {
            @Override
            public PearlReporter.Result send(JsonObject body) {
                JsonObject o = body.deepCopy();
                o.addProperty("botId", botId);
                bodies.add(String.valueOf(body.get("load")) + " " + body.get("busy"));
                ManagerHttp.Response r = call("/api/v1/bot/pearls", o, botId);
                return new PearlReporter.Result(r.code(), r.body());
            }

            @Override
            public void refused(int code) {
                log.add("the loader was refused (" + code + ")");
            }
        });

        void hello() {
            JsonObject o = new JsonObject();
            o.addProperty("botId", botId);
            o.addProperty("player", LOADER);
            JsonObject plugin = new JsonObject();
            plugin.addProperty("id", "qwerte-stash");
            plugin.addProperty("version", "1.18.0");
            o.add("plugin", plugin);
            JsonObject proxy = new JsonObject();
            proxy.addProperty("version", "5.13.0");
            proxy.addProperty("mc", "1.21.4");
            o.add("proxy", proxy);
            JsonArray caps = new JsonArray();
            caps.add("settings");
            o.add("caps", caps);
            JsonArray scopes = new JsonArray();
            scopes.add("plugin");
            o.add("scopes", scopes);
            ManagerHttp.Response r = call("/api/v1/bot/hello", o, botId);
            assertEquals(200, r.code(), r.body() + " " + r.error());
        }

        void report() {
            source.now = System.currentTimeMillis();
            reporter.sendSoon();
            reporter.tick();
        }
    }

    /** A PearlPlus with one registered pearl of the traveller. */
    static final class PearlLoaderTestSource implements PearlReporter.Source {
        long now = System.currentTimeMillis();
        boolean pearlPresent = true, ownerNear;
        LoadTracker.@Nullable Target active;

        @Override public boolean enabled() { return true; }
        @Override public String player() { return LOADER; }
        @Override public long now() { return now; }

        @Override
        public PearlReporter.Reading read(List<LoadAuth.Allowed> allow, LoadTracker.@Nullable Target watching, long graceMs) {
            PearlReport.Pearl p = new PearlReport.Pearl(TRAVELLER_UUID, TRAVELLER, "pearl-1", pearlPresent, true, true, true, now - 600_000L);
            PearlReport.Snapshot s = new PearlReport.Snapshot(LOADER, now, true, false, true, false, now - 3_600_000L, graceMs, false, false, List.of(p));
            boolean asked = watching != null || active != null;
            return new PearlReporter.Reading(s, active, asked && pearlPresent, true, asked && ownerNear, 0);
        }
    }

    @Test
    void aWholeOneWayTripThroughTheRealServer() throws Exception {
        // The owner registers a station for the loader, and the loader reports: it is pending until approved
        JsonObject created = ok(post("/api/v1/pearl/stations", "{\"name\":\"IT hub\",\"server\":\"" + SERVER + "\",\"dim\":\"minecraft:the_nether\",\"loader\":{\"player\":\"" + LOADER + "\"}}"));
        String stationId = null;
        for (JsonElement e : created.getAsJsonArray("stations")) if (e.getAsJsonObject().get("name").getAsString().equals("IT hub")) stationId = e.getAsJsonObject().get("id").getAsString();
        assertNotNull(stationId, created.toString());

        Loader loader = new Loader();
        loader.hello();
        loader.report();
        assertTrue(loader.reporter.allow().isEmpty(), "an unapproved loader gets no list");
        JsonObject pending = station(stationId).getAsJsonObject("loaderState");
        assertFalse(pending.get("approved").getAsBoolean());
        ok(post("/api/v1/pearl/stations/approve", "{\"id\":\"" + stationId + "\",\"source\":\"bot:" + loader.botId + "\"}"));

        // The traveller's first heartbeat: the server learns it can travel and which uuid it reports
        Clock clock = new Clock();
        PearlTripDriverTest.FakeWorld w = world(clock);
        w.joinedAt = clock.now() - 300_000L;
        AwayGuard.off();
        PearlTripDriver driver = new PearlTripDriver(w, Files.createTempFile("pearl-it", ".json"), () -> new PearlTripDriver.Config(true, 45));
        // The index the heartbeat needs a stash for
        JsonObject index = JsonParser.parseString("{\"version\":1,\"server\":\"" + SERVER + "\",\"stash\":\"" + STASH + "\",\"player\":\"" + TRAVELLER
            + "\",\"mcVersion\":\"1.21.4\",\"dimension\":\"minecraft:overworld\",\"generatedAt\":" + System.currentTimeMillis() + ",\"indexHash\":\"" + INDEX_HASH
            + "\",\"containers\":{\"known\":3,\"total\":3,\"stale\":0},\"items\":[],\"shulkers\":[]}").getAsJsonObject();
        assertEquals(200, call("/api/v1/index", index, null).code());
        driver.tick();
        beat(driver);

        // The owner approves the traveller for the station, pins its uuid (the uuid the bot reported is only a suggestion) and assigns the chamber
        ok(post("/api/v1/pearl/allowed", "{\"station\":\"" + stationId + "\",\"player\":\"" + TRAVELLER + "\",\"allowed\":true}"));
        ok(post("/api/v1/pearl/pins/reset", "{\"player\":\"" + TRAVELLER + "\",\"uuid\":\"" + TRAVELLER_UUID + "\"}"));
        loader.report();
        assertEquals(1, loader.reporter.allow().size());
        assertEquals(TRAVELLER_UUID, loader.reporter.allow().get(0).uuid());
        loader.report();
        JsonObject st = station(stationId);
        assertEquals(1, st.getAsJsonArray("chambers").size(), st.toString());
        assertEquals("armed", st.getAsJsonArray("chambers").get(0).getAsJsonObject().get("state").getAsString());
        ok(post("/api/v1/pearl/assign", "{\"station\":\"" + stationId + "\",\"player\":\"" + TRAVELLER + "\",\"chamber\":\"pearl-1\"}"));

        // A one-way trip from the website
        ok(post("/api/v1/pearl/trips", "{\"server\":\"" + SERVER + "\",\"stash\":\"" + STASH + "\",\"station\":\"" + stationId + "\",\"action\":\"go\",\"oneWay\":true}"));
        beat(driver);
        assertTrue(driver.hasTrip(), "the plugin read the offer");
        assertEquals("ready", driver.machine().step());
        loader.report();
        beat(driver);
        assertEquals("awaiting_pull", driver.machine().step(), "the server said go and the bot whispered: " + w.whispers);
        assertEquals(List.of(LOADER + ": load pearl-1"), w.whispers);
        assertTrue(AwayGuard.away());

        // The loader runs the load: loading, then the pearl is gone; the traveller is seen near the chamber as it teleports
        loader.source.active = new LoadTracker.Target(TRAVELLER_UUID, TRAVELLER, "pearl-1");
        loader.report();
        loader.source.active = null;
        loader.source.pearlPresent = false;
        loader.report();
        w.x += 3000;
        w.dim = "minecraft:the_nether";
        driver.tick();
        assertEquals("arrived", driver.machine().step());
        loader.source.ownerNear = true;
        loader.report();
        beat(driver);
        beat(driver);
        assertEquals("pickup", driver.machine().step(), "the server confirmed the arrival from both sides: " + pearlPage().getAsJsonArray("trips").get(0) + " loads " + loader.bodies);

        // Pickup, settle, throw, done; the loader sees the pearl again
        clock.offset += 6_000;
        driver.tick();
        clock.offset += 2_100;
        driver.tick();
        w.rearmRunning = false;
        w.rearmResult = "SUCCESS";
        clock.offset += 500;
        driver.tick();
        assertEquals("done", driver.machine().step());
        beat(driver); // the re-arm result reaches the server first, then the loader sees the pearl armed again
        loader.source.pearlPresent = true;
        loader.report();
        beat(driver);
        beat(driver);
        JsonObject page = pearlPage();
        JsonObject trip = page.getAsJsonArray("trips").get(0).getAsJsonObject();
        assertEquals("completed", trip.get("state").getAsString(), trip.toString());
        assertFalse(driver.hasTrip(), "the plugin let go of the trip once the server showed its end");
        // Still away from the stash after a trip there
        assertTrue(AwayGuard.away());
        AwayGuard.off();
    }
}
