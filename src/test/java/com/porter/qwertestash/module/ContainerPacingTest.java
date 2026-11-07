package com.porter.qwertestash.module;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerPacingTest {
    @Test
    void zeroIsTheOldBehaviour() {
        assertFalse(ContainerPacing.hold(0, 100, 100), "never holds, even right after a close");
        assertFalse(ContainerPacing.hold(0, 100, ContainerPacing.NEVER));
        assertEquals(3, ContainerPacing.openWait(3, 0));
    }

    @Test
    void anOpenWaitsUntilDTicksAfterTheLastClose() {
        assertTrue(ContainerPacing.hold(5, 100, 100), "same tick as the close");
        assertTrue(ContainerPacing.hold(5, 104, 100));
        assertFalse(ContainerPacing.hold(5, 105, 100), "D ticks have passed");
        assertFalse(ContainerPacing.hold(5, 100, ContainerPacing.NEVER), "no close yet");
    }

    @Test
    void theFirstClickInANewWindowWaitsClickDelayPlusD() {
        assertEquals(3 + 7, ContainerPacing.openWait(3, 7));
        assertEquals(3 + 100, ContainerPacing.openWait(3, 500), "capped at 100");
        assertEquals(3, ContainerPacing.openWait(3, -4));
    }

    @Test
    void heldTicksAreHandedBackToTheOpenCounters() {
        // The sorter takes one off its open step counter on every held tick, so the timeout and retry cadence stand still
        int stepTicks = 0, held = 0;
        long lastClose = 50;
        for (long now = 50; now < 60; now++) {
            stepTicks++;
            if (ContainerPacing.hold(4, now, lastClose)) {
                stepTicks--;
                held++;
            }
        }
        assertEquals(4, held);
        assertEquals(6, stepTicks, "only the ticks that could click count");
    }
}
