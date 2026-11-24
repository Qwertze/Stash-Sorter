package com.porter.qwertestash.stash;

import com.porter.qwertestash.manager.SafeJson;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bundled tables are read through the strict SafeJson: they must be strict-valid JSON, and the loaders must really get them (not a fallback). */
class BundledResourcesStrictTest {
    private static Reader open(String resource) {
        InputStream in = BundledResourcesStrictTest.class.getResourceAsStream(resource);
        assertNotNull(in, resource);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    @Test
    void kitItemsParsesStrictAndLoads() throws Exception {
        try (Reader r = open(KitItems.RESOURCE)) {
            assertTrue(SafeJson.parse(r).isJsonObject());
        }
        try (Reader r = open(KitItems.RESOURCE)) {
            assertNotNull(KitItems.parse(SafeJson.parse(r)));
        }
        assertNotNull(KitItems.shared());
    }

    @Test
    void kitTypesParsesStrictAndLoads() throws Exception {
        try (Reader r = open(KitTypes.RESOURCE)) {
            assertNotNull(KitTypes.parse(r));
        }
    }

    @Test
    void theEnchantTablesAreStrictValidToo() throws Exception {
        for (String v : new String[] {"1.21.4", "1.21.11"}) {
            try (Reader r = open("/com/porter/qwerte/core/enchant/enchant-" + v + ".json")) {
                assertTrue(SafeJson.parse(r).isJsonObject());
            }
        }
    }
}
