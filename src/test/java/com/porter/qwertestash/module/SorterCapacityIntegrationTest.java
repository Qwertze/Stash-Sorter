package com.porter.qwertestash.module;

import com.aquarius.cache.data.inventory.Container;
import com.aquarius.mc.item.ItemRegistry;
import com.porter.qwertestash.QwerteStashPlugin;
import com.porter.qwertestash.StashConfig;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.aquarius.Globals.CACHE;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("integration")
class SorterCapacityIntegrationTest {
    @Test
    void publicOrderCapacityUsesLiveHotbarAndMainInventoryAndHonorsUpdatedSettings() {
        var player = CACHE.getPlayerCache();
        var inventory = player.getInventoryCache().getPlayerInventory();
        var oldConfig = QwerteStashPlugin.CONFIG;
        var oldHealth = player.getThePlayer().getHealth();
        var hotbar = inventory.getItemStack(37);
        var main = inventory.getItemStack(9);
        var config = new StashConfig();
        try {
            QwerteStashPlugin.CONFIG = config;
            player.getThePlayer().setHealth(20f);
            config.reservePearlSlot = true;
            config.carrySlots = "1,2,10";
            config.keepSlots = "";
            var sorter = new StashSorter();
            inventory.setItemStack(37, new ItemStack(ItemRegistry.STONE.id(), 1));
            inventory.setItemStack(9, Container.EMPTY_STACK);
            assertEquals(1, sorter.freeOrderSlots());
            config.keepSlots = "10";
            assertEquals(0, sorter.freeOrderSlots());
            inventory.setItemStack(37, Container.EMPTY_STACK);
            assertEquals(1, sorter.freeOrderSlots());
            config.carrySlots = "-,bad";
            assertEquals(0, sorter.freeOrderSlots());
            config.carrySlots = "2";
            player.getThePlayer().setHealth(0f);
            assertEquals(0, sorter.freeOrderSlots());
        } finally {
            inventory.setItemStack(37, hotbar);
            inventory.setItemStack(9, main);
            player.getThePlayer().setHealth(oldHealth);
            QwerteStashPlugin.CONFIG = oldConfig;
        }
    }
}
