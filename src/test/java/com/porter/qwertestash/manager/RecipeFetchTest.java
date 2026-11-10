package com.porter.qwertestash.manager;

import com.google.gson.JsonParser;
import com.porter.qwertestash.orders.OrderDriver;
import com.porter.qwertestash.orders.OrderFlow;
import com.porter.qwertestash.orders.Recipes;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recipes request over a real socket (a signed GET with If-None-Match, the ETag read whatever its casing), and the claim flag asked at claim time. */
class RecipeFetchTest {
    private static final String KEY = "k".repeat(32);

    @Test
    void theEtagIsReadWhateverItsCasingAndIfNoneMatchIsSent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> sentMatch = new AtomicReference<>();
        String body = "{\"version\":\"1.21.4\",\"recipes\":[]}";
        String hash = "ab".repeat(20);
        server.createContext("/api/v1/bot/recipes", ex -> {
            sentMatch.set(ex.getRequestHeaders().getFirst("If-None-Match"));
            String[] auth = ex.getRequestHeaders().getFirst("X-SM-Auth").split(" ");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            // "Etag", the casing the server really sends
            ex.getResponseHeaders().add("Etag", "\"" + hash + "\"");
            ex.getResponseHeaders().add("X-SM-Sig", ManagerHttp.responseHeader(ManagerHttp.signingKey(KEY), auth[3], 200, System.currentTimeMillis(), bytes));
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        try {
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/bot/recipes?mc=1.21.4");
            ManagerHttp.Response r = ManagerHttp.call("GET", url, null, KEY, "bot-1", 2000, 2000, 5000, null, RecipeStore.quote("cd".repeat(20)));
            assertEquals(200, r.code());
            assertEquals(body, r.body());
            assertEquals(hash, RecipeStore.hashOfEtag(r.etag()));
            assertEquals(RecipeStore.quote("cd".repeat(20)), sentMatch.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aMissingCountIsOneInTheHashAndInTheParse() throws Exception {
        String with = "{\"version\":\"1.21.4\",\"recipes\":[{\"id\":\"minecraft:a\",\"type\":\"shapeless\",\"ingredients\":[[\"minecraft:b\"]],\"result\":\"minecraft:a\",\"count\":1}]}";
        String without = with.replace(",\"count\":1", "");
        Recipes a = Recipes.parse(new java.io.StringReader(with)), b = Recipes.parse(new java.io.StringReader(without));
        assertEquals(a.hash(), b.hash());
        assertEquals(1, b.forResult("minecraft:a").get(0).count());
    }

    @Test
    void aTableNestedFarTooDeepIsRefusedNotACrash() {
        String deep = "[".repeat(100_000) + "]".repeat(100_000);
        RecipeStore store = new RecipeStore(java.nio.file.Path.of("build", "tmp-recipe-test"));
        assertThrows(RecipeStore.Invalid.class, () -> store.accept("1.21.4", deep, null));
    }

    @Test
    void theClaimFlagIsAskedWhenTheClaimIsMadeNotBefore() {
        long[] now = {0};
        OrderFlow flow = new OrderFlow(new com.porter.qwertestash.orders.OrderFlow.Host() {
            public boolean gate() { return true; }
            public boolean rereading() { return false; }
            public int staleCount() { return 0; }
            public boolean deliverySet() { return true; }
            public String deliveryProblem() { return null; }
            public int finishedSlot(com.porter.qwertestash.orders.Unit u) { return -1; }
            public java.util.Map<String, Integer> contentOf(com.porter.qwertestash.orders.Unit u, int slot) { return java.util.Map.of(); }
            public boolean canFetch(com.porter.qwertestash.orders.Unit u) { return false; }
            public com.porter.qwertestash.orders.StockPlanner.Plan plan(com.porter.qwertestash.orders.Order o) { return null; }
            public String checkEmpties(java.util.List<com.porter.qwertestash.orders.Unit> r) { return null; }
            public void save(OrderFlow.Saved s) { }
            public void clearSaved(String id) { }
            public OrderFlow.Saved saved(String id) { return null; }
            public String savedId() { return "o-held"; }
            public void say(String t) { }
        }, () -> now[0]);
        OrderDriver d = new OrderDriver(flow, () -> now[0], (a, b) -> { });
        boolean[] capable = {false};
        d.craftOrdersWhen(() -> capable[0]);
        now[0] += 70_000;
        assertFalse(JsonParser.parseString(new String(d.next(true, null, "s", "t", "Bot", true).body())).getAsJsonObject().has("craftOrders"));
        // The cached table loaded after the driver was made: the next claim says so
        capable[0] = true;
        now[0] += 70_000;
        assertEquals(1, JsonParser.parseString(new String(d.next(true, null, "s", "t", "Bot", true).body())).getAsJsonObject().get("craftOrders").getAsInt());
    }

    @Test
    void theWebsiteTablesHeightIsBetweenMinus64And320() {
        for (int y : new int[] {-65, 321}) {
            assertFalse(com.porter.qwertestash.stash.CraftSettings.validTable(0, y, 0, "minecraft:overworld"), "y " + y);
        }
        for (int y : new int[] {-64, 0, 320}) {
            assertTrue(com.porter.qwertestash.stash.CraftSettings.validTable(0, y, 0, "minecraft:overworld"), "y " + y);
        }
        assertNull(null);
        assertNotNull(KEY);
    }
}
