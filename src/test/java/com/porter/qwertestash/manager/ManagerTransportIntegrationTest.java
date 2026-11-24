package com.porter.qwertestash.manager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class ManagerTransportIntegrationTest {
    private HttpServer server;
    private URI base;
    private static final String KEY = "local-test-fixture";
    private static final byte[] REPLY = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static byte[] mac(byte[] key, String message) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] value) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static void send(HttpExchange exchange, int code, byte[] bytes) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Test
    void gzipReplyAuthenticatesTheDecodedBodyAndTheServerCanVerifyTheRequest() throws Exception {
        var seen = new AtomicReference<String>();
        byte[] payload = "{\"test\":1}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/index", exchange -> {
            try {
                String[] fields = exchange.getRequestHeaders().getFirst("X-SM-Auth").split(" ");
                byte[] key = mac(KEY.getBytes(StandardCharsets.UTF_8), "stash-manager v1");
                String signed = String.join("\n", exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    fields[2], fields[3], digest(exchange.getRequestBody().readAllBytes()));
                if (HexFormat.of().formatHex(mac(key, signed)).equals(fields[4])) seen.set(fields[1]);
                String time = Long.toString(System.currentTimeMillis());
                String response = String.join("\n", "resp", fields[3], "200", time, digest(REPLY));
                exchange.getResponseHeaders().set("X-SM-Sig", "v1 " + time + " " + HexFormat.of().formatHex(mac(key, response)));
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                var wire = new ByteArrayOutputStream();
                try (var gzip = new GZIPOutputStream(wire)) { gzip.write(REPLY); }
                send(exchange, 200, wire.toByteArray());
            } catch (GeneralSecurityException e) {
                throw new IOException(e);
            }
        });
        var response = ManagerHttp.call("POST", base.resolve("/index?v=a%20b"), payload, KEY, "bot",
            2000, 2000, 4000, null);
        assertEquals("bot", seen.get());
        assertEquals(200, response.code(), response.error());
        assertEquals(new String(REPLY, StandardCharsets.UTF_8), response.body());
    }

    @Test
    void unsignedSuccessAndFailureNeverExposeResponseContent() {
        server.createContext("/ok", ex -> send(ex, 200, REPLY));
        server.createContext("/busy", ex -> send(ex, 503, REPLY));
        var ok = get("/ok");
        assertEquals(ManagerHttp.BAD_SIGNATURE, ok.code());
        assertEquals("", ok.body());
        var busy = get("/busy");
        assertEquals(503, busy.code());
        assertEquals(ManagerHttp.UNSIGNED, busy.error());
        assertEquals("", busy.body());
    }

    @Test
    void redirectsAreNotFollowed() {
        var visits = new AtomicInteger();
        server.createContext("/other", ex -> { visits.incrementAndGet(); send(ex, 200, REPLY); });
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().set("Location", base.resolve("/other").toString());
            send(ex, 302, REPLY);
        });
        assertEquals(ManagerHttp.BAD_SIGNATURE, get("/redirect").code());
        assertEquals(0, visits.get());
    }

    @Test
    void aStalledReplyTimesOutAndTheCallerReleasesItsConnection() {
        var release = new CountDownLatch(1);
        server.createContext("/slow", ex -> {
            try (ex) {
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        var changes = new java.util.ArrayList<Boolean>();
        try {
            var result = ManagerHttp.call("GET", base.resolve("/slow"), null, KEY, null, 2000, 200, 4000,
                conn -> changes.add(conn != null));
            assertEquals(-1, result.code());
            assertTrue(result.error().contains("Timeout"), result.error());
            assertEquals(java.util.List.of(true, false), changes);
        } finally {
            release.countDown();
        }
    }

    private ManagerHttp.Response get(String path) {
        return ManagerHttp.call("GET", base.resolve(path), null, KEY, null, 2000, 2000, 4000, null);
    }
}
