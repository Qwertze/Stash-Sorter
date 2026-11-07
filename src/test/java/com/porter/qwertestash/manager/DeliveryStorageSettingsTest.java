package com.porter.qwertestash.manager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.StashConfig;
import com.porter.qwertestash.orders.DeliveryBook;
import com.porter.qwertestash.orders.OrderFlow;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The delivery storage (1.9.0) in the settings, on the heartbeat and in the saved stash. */
class DeliveryStorageSettingsTest {
    private static final Gson GSON = new Gson();

    @Test
    void autoExpandIsOffByDefaultAndAnOldFileLoadsWithItOff() {
        assertFalse(new StashConfig().autoExpandDelivery);
        assertFalse(GSON.fromJson("{\"sorting\":true,\"deliveryDimension\":\"overworld\"}", StashConfig.class).autoExpandDelivery);
    }

    @Test
    void theSettingIsOnTheWebsiteWithItsCommand() {
        ConfigSchema.Built built = ConfigSchema.build(new StashConfig(), ConfigSchema.PLUGIN);
        assertTrue(built.schema().toString().contains("autoExpandDelivery"));
        assertFalse(SettingsSpec.PLUGIN.get("autoExpandDelivery").hidden);
        assertEquals("delivery expand {v}", SettingsSpec.PLUGIN.get("autoExpandDelivery").command);
    }

    @Test
    void theHeartbeatCarriesTheStorageWithoutPositions() {
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        byte[] body = ManagerIndex.heartbeat(b, -1, null, null, null, null, null, new ManagerIndex.DeliveryStorage(true, 3, 2, false, "stone is in the way above the top chest"));
        JsonObject o = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject d = o.getAsJsonObject("deliveryStorage");
        assertTrue(d.get("autoExpand").getAsBoolean());
        assertEquals(3, d.get("chests").getAsInt());
        assertEquals(2, d.get("full").getAsInt());
        assertFalse(d.get("canExpand").getAsBoolean());
        assertEquals("stone is in the way above the top chest", d.get("blocked").getAsString());
        assertEquals(5, d.size(), "no position: " + d);

        // Can expand: blocked is an explicit null; the existing fields are as they were
        JsonObject ok = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, null, null,
            new ManagerIndex.DeliveryStorage(true, 1, 1, true, null)), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(ok.getAsJsonObject("deliveryStorage").get("blocked").isJsonNull());
        assertTrue(ok.has("delivery") && ok.get("delivery").isJsonNull());
        assertTrue(ok.has("orderBlocker") && ok.has("indexHash"));

        // A bot without it leaves the field out
        JsonObject none = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null), StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(none.has("deliveryStorage"));
    }

    @Test
    void theStorageIsSavedWithTheStashAndAnOldStashHasNone() {
        Stash s = new Stash("s", "2b2t.org");
        DeliveryBook book = new DeliveryBook(s.deliveryStorage);
        book.syncPrimary(List.of(new DeliveryBook.Pos(1, 64, 2), new DeliveryBook.Pos(2, 64, 2)));
        book.add(List.of(new DeliveryBook.Pos(1, 65, 2), new DeliveryBook.Pos(2, 65, 2)));
        book.markFull(book.primary(), 5);
        // A lid seen open stays out of the file
        book.onViewers(new DeliveryBook.Pos(1, 64, 2), 1, false);

        Stash back = GSON.fromJson(GSON.toJson(s), Stash.class);
        DeliveryBook again = new DeliveryBook(back.deliveryStorage);
        assertEquals(2, again.chests());
        assertTrue(again.primary().full);
        assertEquals(List.of(new DeliveryBook.Pos(1, 64, 2), new DeliveryBook.Pos(2, 64, 2)), again.primary().parts());
        assertFalse(again.sorted().get(1).full);
        assertFalse(again.primary().opened);

        Stash old = GSON.fromJson("{\"name\":\"x\",\"server\":\"y\"}", Stash.class);
        assertNull(old.deliveryStorage);
    }

    @Test
    void aSavedOrderWithoutTheDeliveryChestLoadsAsBefore() {
        OrderFlow.Saved saved = GSON.fromJson("{\"id\":\"o-1\",\"done\":1,\"deliveringIndex\":0,\"deliveringBase\":2}", OrderFlow.Saved.class);
        assertNull(saved.deliveringAt);
        assertEquals(2, saved.deliveringBase);
    }
}
