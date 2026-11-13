package com.porter.qwertestash.orders;

import com.porter.qwerte.core.enchant.EnchantRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The carry formula of ENCHANT-CONTRACT v1.0 ("Things the user should know" has the worked numbers), the heartbeat's carry, and the module guard. */
class EnchantCarryTest {
    /** The most points that still fit the default 20 carry slots with this many books (the contract's anvil figure of 2). */
    private static int maxPoints(int books, int carry) {
        int best = 0;
        for (int p = 1; p < 20_000; p++) if (EnchantCarry.fits(books, 2, EnchantRules.bottles(p), carry)) best = p;
        return best;
    }

    @Test
    void theFormulaIsOneBasePlusBooksPlusAnvilsPlusAShulkerPlusBottleStacks() {
        assertEquals(1 + 3 + 2 + 1 + 2, EnchantCarry.slotsNeeded(3, 2, 128));
        assertEquals(1 + 3 + 2 + 1 + 3, EnchantCarry.slotsNeeded(3, 2, 129));
        assertEquals(1 + 0 + 0 + 1, EnchantCarry.slotsNeeded(0, 0, 0));
        assertTrue(EnchantCarry.fits(3, 2, 128, 9));
        assertFalse(EnchantCarry.fits(3, 2, 128, 8));
    }

    @Test
    void theContractsWorkedNumbersHoldAtTwentySlots() {
        // "with 3 books up to 5,137 points pass (832 bottles); with 7 books up to 3,534 (576); with 11 books only up to 1,938 (320)"
        assertEquals(5137, maxPoints(3, 20));
        assertEquals(3534, maxPoints(7, 20));
        assertEquals(1938, maxPoints(11, 20));
        assertEquals(832, EnchantRules.bottles(5137));
        assertEquals(576, EnchantRules.bottles(3534));
        assertEquals(320, EnchantRules.bottles(1938));
        // books + ceil(bottles / 64) <= 16 at 20 slots
        assertTrue(EnchantCarry.fits(7, 2, 134, 20));
    }

    @Test
    void theBottleCeilingIsCarryMinusFourStacks() {
        assertEquals((20 - 4) * 64, EnchantCarry.bottleCeiling(20));
        assertEquals(2048, EnchantCarry.bottleCeiling(36));
        assertEquals(2048, EnchantCarry.bottleCeiling(50));
        assertEquals(0, EnchantCarry.bottleCeiling(3));
    }

    @Test
    void theHeartbeatCarryIsTheFreeSlotsAfterTheGearIsStowed() {
        // no gear to stow: the main hand slot of the throw only
        assertEquals(19, EnchantCarry.heartbeatCarry(20, 0, false));
        assertEquals(20 - (2 + 1 + 1), EnchantCarry.heartbeatCarry(20, 2, true));
        assertEquals(0, EnchantCarry.heartbeatCarry(2, 4, true));
        assertEquals(36, EnchantCarry.heartbeatCarry(60, 0, false));
        assertEquals(35, EnchantCarry.heartbeatCarry(36, 0, false));
        assertEquals(0, EnchantCarry.heartbeatCarry(-5, 0, false));
    }

    @Test
    void theGuardRefusesTheProxysOwnEnchantingModulesAndOnlyReportsTheGearOnes() {
        assertEquals("Enchanter", EnchantGuard.conflict(java.util.List.of("AutoEat", "Enchanter")));
        assertEquals("KitMaker", EnchantGuard.conflict(java.util.List.of("KitMaker")));
        assertEquals("Order Filler", EnchantGuard.conflict(java.util.List.of("Order Filler")));
        assertEquals("order_filler", EnchantGuard.conflict(java.util.List.of("order_filler")));
        assertEquals("AutoMend", EnchantGuard.conflict(java.util.List.of("AutoMend")));
        assertEquals("AutoArmor", EnchantGuard.conflict(java.util.List.of("AutoEat", "AutoArmor")));
        assertEquals(null, EnchantGuard.conflict(java.util.List.of("AutoTotem", "AutoEat", "KillAura")));
        assertTrue(EnchantGuard.pausesRunningItem(Codes.MODULE_CONFLICT, false));
        assertFalse(EnchantGuard.pausesRunningItem(Codes.MODULE_CONFLICT, true));
        assertFalse(EnchantGuard.pausesRunningItem(Codes.MC_MISMATCH, false));
        assertFalse(EnchantGuard.pausesRunningItem(null, false));
        assertEquals(null, EnchantGuard.conflict(java.util.List.of()));
    }

    @Test
    void aBottleCountNearIntegerMaxCantWrapTheCarryCheck() {
        assertTrue(EnchantCarry.slotsNeeded(3, 2, Integer.MAX_VALUE) > 36);
        assertFalse(EnchantCarry.fits(3, 2, Integer.MAX_VALUE, 36));
        assertFalse(EnchantCarry.fits(0, 0, Integer.MAX_VALUE - 1, 36));
        assertEquals(Integer.MAX_VALUE, EnchantCarry.slotsNeeded(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }
}
