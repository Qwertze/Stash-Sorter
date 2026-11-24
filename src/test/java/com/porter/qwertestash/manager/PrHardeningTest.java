package com.porter.qwertestash.manager;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hardening ideas of PR 1: ManagerHttp, SafeJson, ConfigApplier and the manager URL policy. */
class PrHardeningTest {
    @Test
    void aReplyAtTheLimitIsReadAndOneByteMoreIsRefused() throws IOException {
        byte[] ok = new byte[4 << 20];
        assertEquals(ok.length, ManagerHttp.read(new ByteArrayInputStream(ok)).length);
        byte[] big = new byte[(4 << 20) + 1];
        assertThrows(IOException.class, () -> ManagerHttp.read(new ByteArrayInputStream(big)));
    }

    @Test
    void aNegativeTimestampNeverVerifies() {
        byte[] k = ManagerHttp.signingKey("key");
        byte[] body = "{}".getBytes();
        assertTrue(ManagerHttp.verify(k, ManagerHttp.responseHeader(k, "n", 200, 1_000, body), "n", 200, body, 1_000));
        assertFalse(ManagerHttp.verify(k, ManagerHttp.responseHeader(k, "n", 200, -5, body), "n", 200, body, 10));
        assertFalse(ManagerHttp.verify(k, ManagerHttp.responseHeader(k, "n", 200, 5, body), "n", 200, body, -10));
    }

    @Test
    void safeJsonIsStrict() {
        assertTrue(SafeJson.parse(" {\"a\":[1,2]} \n").isJsonObject());
        for (String bad : new String[] {"", "  ", "{} {}", "{}x", "{'a':1}", "{a:1}", "{\"a\":1,}", "[1,]", "// c\n{}", "{\"a\":1} // c"}) {
            assertThrows(JsonParseException.class, () -> SafeJson.parse(bad), bad);
        }
        assertThrows(JsonParseException.class, () -> SafeJson.parse((String) null));
    }

    @Test
    void safeJsonStillAcceptsTheShapesTheServerSends() {
        assertTrue(SafeJson.parse("{\"jobs\":[],\"need\":[{\"scope\":\"x\",\"what\":\"schema\"}],\"watching\":true,\"n\":1.5e3}").isJsonObject());
        assertTrue(SafeJson.parse("[]").isJsonArray());
        assertTrue(SafeJson.parse("\"s\"").isJsonPrimitive());
    }

    @SuppressWarnings("unused")
    static class Target {
        byte b;
        short s;
        int i;
        long l;
        float f;
        double d;
    }

    private static Object validate(String field, JsonElement value) throws NoSuchFieldException {
        Field f = Target.class.getDeclaredField(field);
        return ConfigApplier.validate(f, new SettingsSpec.Spec("l", "h", "s"), value);
    }

    private static JsonElement num(String text) {
        return new JsonPrimitive(new BigDecimal(text));
    }

    @Test
    void wholeNumbersAreConvertedExactly() throws Exception {
        assertEquals((byte) 100, validate("b", num("100")));
        assertEquals((short) 300, validate("s", num("300.0")));
        assertEquals(7, validate("i", num("7")));
        assertEquals(Long.MAX_VALUE, validate("l", num("9223372036854775807")));
        assertEquals(Long.MIN_VALUE, validate("l", num("-9223372036854775808")));
    }

    @Test
    void nothingIsTruncatedOrWrapped() {
        String[][] cases = {{"b", "128"}, {"b", "-129"}, {"s", "40000"}, {"i", "2147483648"}, {"i", "1.5"}, {"i", "1e999999999"},
            {"l", "9223372036854775808"}, {"l", "9223372036854775807.5"}, {"l", "1e30"}};
        for (String[] c : cases) {
            assertThrows(IllegalArgumentException.class, () -> validate(c[0], num(c[1])), c[0] + " " + c[1]);
        }
    }

    @Test
    void floatsAndDoublesMustBeFinite() throws Exception {
        assertEquals(1.5, validate("d", num("1.5")));
        assertEquals(2.5f, validate("f", num("2.5")));
        assertThrows(IllegalArgumentException.class, () -> validate("d", num("1e400")));
        assertThrows(IllegalArgumentException.class, () -> validate("f", num("1e40")));
        assertThrows(IllegalArgumentException.class, () -> validate("d", new JsonPrimitive(Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> validate("i", JsonNull.INSTANCE));
    }

    @Test
    void httpsIsAcceptedForAnyHost() {
        assertEquals("https://stash.example.com", ManagerUrl.parse("https://stash.example.com/").toString());
        assertNotNull(ManagerUrl.parse("https://203.0.113.9:8765"));
    }

    @Test
    void plainHttpIsAcceptedOnlyForLoopbackAndPrivateAddresses() {
        String[] good = {"http://127.0.0.1:8765", "http://localhost:8765", "http://[::1]:8765", "http://10.1.2.3:8765", "http://10.1.2.4:8765",
            "http://172.16.0.5:8765", "http://172.31.255.255:8765", "http://192.168.1.20:8765", "http://169.254.1.1:8765", "http://[fd12:3456::1]:8765",
            "http://[fe80::1]:8765", "http://[::ffff:192.168.0.4]:8765", "http://[fd00::1]:8765", "http://[::ffff:10.1.2.3]:8765",
            "HTTP://LOCALHOST:8765", "http://[FD00::1]:8765", "http://LocalHost"};
        for (String ok : good) {
            URI u = ManagerUrl.parse(ok);
            assertNotNull(u, ok);
            assertTrue(ManagerUrl.isPlain(u));
        }
        String[] bad = {"http://203.0.113.9:8765", "http://8.8.8.8", "http://172.15.0.1", "http://172.32.0.1", "http://192.169.0.1", "http://11.0.0.1",
            "http://stash.example.com", "http://example.com:8765", "http://10.1.2.3.example.com", "http://[2001:db8::1]:8765", "http://010.1.2.3",
            "http://256.1.1.1", "http://100.64.0.1:8765", "http://100.127.255.254", "http://a.localhost:8765", "http://localhost.example.com:8765", "http://nas.example.com:8765",
            "http://[::ffff:8.8.8.8]:8765", "HTTP://EXAMPLE.COM", "http://[fe00::1]:8765", "ftp://10.1.2.3", "http://user@10.1.2.3", "http://10.1.2.3?x=1", ""};
        for (String no : bad) assertNull(ManagerUrl.parse(no), no);
    }

    @Test
    void trailingSlashesAreStripped() {
        assertEquals("http://10.1.2.4:8765", ManagerUrl.parse(" http://10.1.2.4:8765// ").toString());
    }
}
