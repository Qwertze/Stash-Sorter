package com.porter.qwertestash.manager;

import org.jspecify.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * One blocking, signed request to the Stash Manager server (PROTOCOL.md, "Signed requests (HMAC)"). The API key itself is
 * never sent: the request carries {@code X-SM-Auth: v1 <botId|-> <ts> <nonce> <sig>}, and the response is only believed
 * if its {@code X-SM-Sig} proves the server knows the key and answers this very request (nonce, status, body, time).
 * An answer that doesn't prove it is dropped: the caller gets code {@value #BAD_SIGNATURE} and no body. There are timeouts
 * on connect and read and a total deadline for the whole exchange, enforced by a watchdog that disconnects. No redirects.
 * Works on any thread. {@code code} is -1 when the server could not be reached.
 */
public final class ManagerHttp {
    private ManagerHttp() {
    }

    /** The code of a response whose signature did not verify (or was missing). */
    public static final int BAD_SIGNATURE = -2;

    /** The error text of a 401, 403 or 5xx that came without a signature: only the code is believable. */
    public static final String UNSIGNED = "unsigned";

    /** @param etag the ETag header of the answer (not covered by the signature: only a hint), or null */
    public record Response(int code, String body, @Nullable String error, @Nullable String etag) {
        public Response(int code, String body, @Nullable String error) {
            this(code, body, error, null);
        }
    }

    private static final int MAX_REPLY = 4 << 20;
    private static final long MAX_SKEW_MS = 60_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ScheduledThreadPoolExecutor WATCHDOG = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "stash-manager-watchdog");
        t.setDaemon(true);
        return t;
    });

    static {
        WATCHDOG.setKeepAliveTime(10, TimeUnit.SECONDS);
        WATCHDOG.allowCoreThreadTimeOut(true);
        WATCHDOG.setRemoveOnCancelPolicy(true);
    }

    /** K = HMAC-SHA256(key = the API key, msg = "stash-manager v1"). */
    static byte[] signingKey(String apiKey) {
        return hmac(apiKey.getBytes(StandardCharsets.UTF_8), "stash-manager v1".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The value of the X-SM-Auth header. */
    static String authHeader(byte[] k, String method, String pathWithQuery, @Nullable String botId, long ts, String nonce, byte[] body) {
        String input = method + "\n" + pathWithQuery + "\n" + ts + "\n" + nonce + "\n" + sha256Hex(body);
        String sig = hex(hmac(k, input.getBytes(StandardCharsets.UTF_8)));
        return "v1 " + (botId == null || botId.isEmpty() ? "-" : botId) + " " + ts + " " + nonce + " " + sig;
    }

    /** The X-SM-Sig a server would send for this response (body is the uncompressed bytes). For tests and the like. */
    static String responseHeader(byte[] k, String nonce, int status, long ts, byte[] body) {
        String input = "resp\n" + nonce + "\n" + status + "\n" + ts + "\n" + sha256Hex(body);
        return "v1 " + ts + " " + hex(hmac(k, input.getBytes(StandardCharsets.UTF_8)));
    }

    /** Whether an X-SM-Sig header proves this response (body is the uncompressed bytes). */
    static boolean verify(byte[] k, @Nullable String header, String nonce, int status, byte[] body, long now) {
        if (header == null) return false;
        String[] parts = header.trim().split(" +");
        if (parts.length != 3 || !parts[0].equals("v1")) return false;
        long ts;
        try {
            ts = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(now - ts) > MAX_SKEW_MS) return false;
        String input = "resp\n" + nonce + "\n" + status + "\n" + ts + "\n" + sha256Hex(body);
        byte[] expected = hex(hmac(k, input.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, parts[2].toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param body    the JSON to send with POST, null for GET
     * @param botId   the bot id for bot/* requests, null for the others
     * @param totalMs the whole request, connect to last byte, may take at most this long
     * @param active  told the connection while it is in use, and null again afterwards, so a stop can disconnect it
     */
    public static Response call(String method, URI url, byte @Nullable [] body, String key, @Nullable String botId, int connectMs, int readMs, int totalMs, @Nullable Consumer<HttpURLConnection> active) {
        return call(method, url, body, key, botId, connectMs, readMs, totalMs, active, null);
    }

    /** As above; {@code ifNoneMatch} is sent as the If-None-Match header (a GET of something cached; a 304 answer has no body). */
    public static Response call(String method, URI url, byte @Nullable [] body, String key, @Nullable String botId, int connectMs, int readMs, int totalMs, @Nullable Consumer<HttpURLConnection> active,
                                @Nullable String ifNoneMatch) {
        if (key.isEmpty() || key.chars().anyMatch(c -> c < 33 || c > 126) || (botId != null && botId.chars().anyMatch(c -> c < 33 || c > 126))) {
            return new Response(-1, "", "bad key");
        }
        byte[] payload = body == null ? new byte[0] : body;
        byte[] k = signingKey(key);
        byte[] nonceBytes = new byte[16];
        RANDOM.nextBytes(nonceBytes);
        String nonce = hex(nonceBytes);
        long ts = System.currentTimeMillis();
        String path = url.getRawPath() + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery());

        HttpURLConnection conn = null;
        ScheduledFuture<?> deadline = null;
        try {
            conn = (HttpURLConnection) url.toURL().openConnection();
            final HttpURLConnection watched = conn;
            deadline = WATCHDOG.schedule(() -> {
                try {
                    watched.disconnect();
                } catch (RuntimeException ignored) {
                    // The request ends with an error anyway
                }
            }, totalMs, TimeUnit.MILLISECONDS);
            if (active != null) active.accept(conn);
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod(method);
            conn.setRequestProperty("X-SM-Auth", authHeader(k, method, path, botId, ts, nonce, payload));
            if (ifNoneMatch != null && !ifNoneMatch.isEmpty()) conn.setRequestProperty("If-None-Match", ifNoneMatch);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(payload.length);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }
            }
            int code = conn.getResponseCode();
            String sigHeader = conn.getHeaderField("X-SM-Sig");
            String encoding = conn.getHeaderField("Content-Encoding");
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] raw = in == null ? new byte[0] : read(in);
            byte[] bytes = encoding != null && encoding.equalsIgnoreCase("gzip") && raw.length > 0 ? gunzip(raw) : raw;

            if (verify(k, sigHeader, nonce, code, bytes, System.currentTimeMillis())) {
                return new Response(code, new String(bytes, StandardCharsets.UTF_8), null, conn.getHeaderField("ETag"));
            }
            // Refusals (401, 403) and server errors (5xx) may come unsigned: the code is shown but nothing in the body is
            // ever acted on
            if (sigHeader == null && (code == 401 || code == 403 || code >= 500)) return new Response(code, "", UNSIGNED);
            return new Response(BAD_SIGNATURE, "", "server signature invalid");
        } catch (IOException | RuntimeException e) {
            return new Response(-1, "", e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } finally {
            if (deadline != null) deadline.cancel(false);
            if (active != null) active.accept(null);
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_REPLY) throw new IOException("reply too large");
            }
            return out.toByteArray();
        }
    }

    private static byte[] gunzip(byte[] raw) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return read(in);
        }
    }
}
