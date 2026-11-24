package com.porter.qwertestash.manager;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ManagerBoundaryTest {
    @Test
    void onlyOneCompleteJsonValueIsAccepted() {
        for (String invalid : new String[] {"", " ", "null true", "{x:2}", "[1,]", "/* annotation */ {}"}) {
            assertThrows(JsonParseException.class, () -> SafeJson.parse(invalid));
        }
        assertTrue(SafeJson.parse(" null ").isJsonNull());
    }

    @Test
    void nestingLimitIgnoresStructuralCharactersInStrings() {
        String content = "\"[{}\\\"\"";
        String atLimit = "[".repeat(SafeJson.MAX_DEPTH) + content + "]".repeat(SafeJson.MAX_DEPTH);
        assertTrue(SafeJson.parse(atLimit).isJsonArray());
        assertThrows(JsonParseException.class, () -> SafeJson.parse("[" + atLimit + "]"));
    }

    @Test
    void numericSettingsInExternalScopesAreMarkedReadOnlyInTheExportedSchema() {
        class Fixture { public int distance = 6; }
        for (var policy : new ProxyPolicy[] {ProxyPolicy.PROXY, ProxyPolicy.OTHER_PLUGIN}) {
            var result = ConfigSchema.build(new Fixture(), policy);
            assertEquals(6, result.values().get("distance").getAsInt());
            assertTrue(result.schema().get(0).getAsJsonObject().get("readOnly").getAsBoolean());
        }
    }

    @Test
    void signaturesAcceptOnlyTheInclusiveTimeWindowAndMatchingContent() {
        var k = ManagerHttp.signingKey("fixture");
        byte[] bytes = {1, 2};
        long now = 1700000000000L;
        for (long offset : new long[] {-60001, -60000, 0, 60000, 60001}) {
            String header = ManagerHttp.responseHeader(k, "a", 200, now + offset, bytes);
            assertEquals(Math.abs(offset) <= 60000, ManagerHttp.verify(k, header, "a", 200, bytes, now));
            assertFalse(ManagerHttp.verify(k, header, "b", 200, bytes, now));
            assertFalse(ManagerHttp.verify(k, header, "a", 201, bytes, now));
        }
        assertFalse(ManagerHttp.verify(k, null, "a", 200, bytes, now));
    }
}
