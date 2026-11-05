package com.porter.qwertestash.manager;

import com.porter.qwertestash.StashConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The order settings on the dashboard's Settings tab: shown with the right defaults, and the delivery position never. */
class OrderSettingsTest {
    @Test
    void defaultsKeepOldSettingsFilesWorking() {
        StashConfig c = new StashConfig();
        assertFalse(c.acceptOrders);
        assertEquals("", c.deliveryDimension);
        assertTrue(c.useNamedShulkers);
        assertEquals(5, c.freshMinutes);
        // A settings file from 1.5.0 has none of the new keys: they load as the defaults
        StashConfig old = new com.google.gson.Gson().fromJson("{\"sorting\":true,\"stashName\":\"x\"}", StashConfig.class);
        assertFalse(old.acceptOrders);
        assertEquals("", old.deliveryDimension);
        assertEquals(5, old.freshMinutes);
    }

    @Test
    void theOrderSettingsAreOnTheSettingsTabButNotTheDeliveryPosition() {
        StashConfig c = new StashConfig();
        c.deliveryDimension = "minecraft:the_nether";
        c.deliveryX = 123456;
        c.deliveryY = 77;
        c.deliveryZ = -98765;
        ConfigSchema.Built built = ConfigSchema.build(c, ConfigSchema.PLUGIN);
        String schema = built.schema().toString(), values = built.values().toString();
        assertTrue(values.contains("\"acceptOrders\":false"), values);
        assertTrue(values.contains("\"useNamedShulkers\":true"), values);
        assertTrue(values.contains("\"freshMinutes\":5"), values);
        assertTrue(schema.contains("acceptOrders"));
        for (String secret : new String[] {"123456", "-98765", "the_nether", "deliveryX", "deliveryY", "deliveryZ", "deliveryDimension"}) {
            assertFalse(values.contains(secret), "values leak " + secret + ": " + values);
            assertFalse(schema.contains(secret), "schema leaks " + secret);
        }
    }

    @Test
    void acceptOrdersIsChangedWithTheOrdersCommand() {
        assertEquals("orders {v}", SettingsSpec.PLUGIN.get("acceptOrders").command);
        assertTrue(SettingsSpec.PLUGIN.get("deliveryX").hidden);
        assertTrue(SettingsSpec.PLUGIN.get("deliveryDimension").hidden);
        assertEquals(30.0, SettingsSpec.PLUGIN.get("freshMinutes").max);
    }
}
