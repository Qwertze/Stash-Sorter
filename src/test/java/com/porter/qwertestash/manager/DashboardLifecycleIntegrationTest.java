package com.porter.qwertestash.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.QwerteStashPlugin;
import com.porter.qwertestash.StashConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class DashboardLifecycleIntegrationTest {
    @Test
    void signedBatchDrainsInOrderAndAdvertisesOnlySupportedActions() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var wanted = new AtomicBoolean(true);
        var first = new AtomicBoolean(true);
        var caps = new AtomicReference<JsonArray>();
        var updates = new CopyOnWriteArrayList<Integer>();
        var results = new CopyOnWriteArrayList<JsonObject>();
        var finished = new CountDownLatch(42);
        var stopPoll = new CountDownLatch(1);
        var config = new StashConfig();
        var previousLogger = QwerteStashPlugin.LOG;
        QwerteStashPlugin.LOG = ComponentLogger.logger("dashboard-test");
        var env = new ConfigApplier.Env() {
            public ConsoleRunner.Outcome command(String command) { throw new AssertionError("unexpected console command"); }
            public void changed(String path) { updates.add(config.settleTicks); }
        };
        var link = new DashboardLink(new DashboardLink.Host() {
            public URI base() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
            public String key() { return "test-only"; }
            public String botId() { return "bot"; }
            public String player() { return "fixture"; }
            public List<DashboardLink.Scope> scopes() {
                return List.of(new DashboardLink.Scope("plugin", config, ConfigSchema.PLUGIN, env, "stash "));
            }
            public String pluginId() { return "qwerte-stash"; }
            public String pluginVersion() { return "test"; }
            public String proxyVersion() { return "test"; }
            public String mcVersion() { return "1.21.4"; }
            public boolean wanted() { return wanted.get(); }
            public boolean consoleAllowed() { return true; }
            public boolean proxyAllowed() { return true; }
        });
        try (var workers = Executors.newCachedThreadPool()) {
            server.setExecutor(workers);
            server.createContext("/api/v1/bot/hello", ex -> {
                caps.set(request(ex).getAsJsonArray("caps"));
                reply(ex, "{}");
            });
            server.createContext("/api/v1/bot/next", ex -> {
                if (first.getAndSet(false)) {
                    var jobs = new JsonArray();
                    for (int i = 1; i <= 42; i++) {
                        var job = new JsonObject();
                        job.addProperty("id", "j" + i);
                        job.addProperty("type", i == 42 ? "command" : "set");
                        job.addProperty("scope", i == 41 ? "proxy" : "plugin");
                        job.addProperty("path", "settleTicks");
                        job.addProperty("value", i);
                        jobs.add(job);
                    }
                    var batch = new JsonObject();
                    batch.add("jobs", jobs);
                    reply(ex, batch.toString());
                } else {
                    try { stopPoll.await(15, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    reply(ex, "{}");
                }
            });
            server.createContext("/api/v1/bot/results", ex -> {
                results.add(request(ex).getAsJsonArray("results").get(0).getAsJsonObject());
                reply(ex, "{}");
                finished.countDown();
            });
            server.createContext("/api/v1/bot/", ex -> reply(ex, "{}"));
            server.start();
            try {
                link.start();
                assertTrue(finished.await(15, TimeUnit.SECONDS), "dashboard batch did not drain");
                assertEquals(java.util.stream.IntStream.rangeClosed(1, 40).boxed().toList(), updates);
                assertEquals(42, results.size());
                for (int i = 0; i < 42; i++) {
                    assertEquals("j" + (i + 1), results.get(i).get("id").getAsString());
                    assertEquals(i < 40, results.get(i).get("ok").getAsBoolean());
                }
                assertEquals(JsonParser.parseString("[\"settings\",\"activity\"]"), caps.get());
            } finally {
                wanted.set(false);
                stopPoll.countDown();
                link.stop();
                server.stop(0);
            }
            assertFalse(link.isRunning());
        } finally {
            QwerteStashPlugin.LOG = previousLogger;
        }
    }

    private static JsonObject request(HttpExchange exchange) throws IOException {
        return JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void reply(HttpExchange exchange, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String nonce = exchange.getRequestHeaders().getFirst("X-SM-Auth").split(" ")[3];
            exchange.getResponseHeaders().set("X-SM-Sig", ManagerHttp.responseHeader(
                ManagerHttp.signingKey("test-only"), nonce, 200, System.currentTimeMillis(), bytes));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
