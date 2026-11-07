package com.porter.qwertestash.module;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.12.1: a shulker problem (a refused break, a passer-by) must not stop shulker handling for good. */
class ShulkerRecoveryTest {
    @Test
    void timeSpentGivingWayDoesNotCountAgainstTheDeadline() {
        assertEquals(1000 + 300, ShulkerRecovery.deadlineAfterWait(1000, 200, 500));
        assertEquals(1000, ShulkerRecovery.deadlineAfterWait(1000, -1, 500), "no wait: unchanged");
        assertEquals(1000, ShulkerRecovery.deadlineAfterWait(1000, 600, 500), "never moved back");
    }

    @Test
    void lostEndsWhenTheShulkerIsPickedUp() {
        var lost = new ShulkerRecovery.LostMarker<String>();
        lost.mark("a");
        assertTrue(lost.lost());
        assertFalse(lost.cleared("b"), "another shulker's record");
        assertTrue(lost.lost());
        assertTrue(lost.cleared("a"));
        assertFalse(lost.lost());
    }

    @Test
    void lostEndsWhenItsOrphanRecordIsGone() {
        var lost = new ShulkerRecovery.LostMarker<String>();
        lost.mark("a");
        assertFalse(lost.sync(List.of("a", "b")), "still listed: still stopped");
        assertTrue(lost.lost());
        assertTrue(lost.sync(List.of("b")), "forgotten because it is gone from the world");
        assertFalse(lost.lost());
        assertFalse(lost.sync(List.of()), "nothing to end");
    }

    @Test
    void aShulkerIsGoneBackToThreeTimesThenGivenUp() {
        var retries = new ShulkerRecovery.Retries<String>();
        for (int i = 0; i < ShulkerRecovery.MAX_RECOVERIES; i++) assertTrue(retries.allow("a"));
        assertFalse(retries.allow("a"));
        assertTrue(retries.allow("b"), "per spot");
        retries.forget("a");
        assertTrue(retries.allow("a"), "picked up: counted afresh");
    }

    @Test
    void goingBackWaitsForTheBackoffAndForQuiet() {
        var gate = new ShulkerRecovery.QuietGate(100, 50);
        gate.arm(0);
        assertFalse(gate.ready(10, false), "back-off");
        assertFalse(gate.ready(99, false));
        assertTrue(gate.ready(100, false), "back-off over and quiet since 10");
        // A player walks by: the quiet time starts over
        assertFalse(gate.ready(110, true));
        assertFalse(gate.ready(120, false));
        assertFalse(gate.ready(169, false));
        assertTrue(gate.ready(170, false));
    }

    @Test
    void nearIsFlat() {
        assertTrue(ShulkerRecovery.within(0, 0, 3, 4, 5));
        assertFalse(ShulkerRecovery.within(0, 0, 3, 4.1, 5));
    }

    @Test
    void goingBackAfterTheWaitRunsOutEvenWithSomeoneNear() {
        var gate = new ShulkerRecovery.QuietGate(10, 50, 1000);
        gate.arm(0);
        assertFalse(gate.ready(500, true));
        assertFalse(gate.forced());
        assertTrue(gate.ready(1000, true), "the break is our own action: not held back for ever");
        assertTrue(gate.forced());
        assertTrue(gate.ready(1100, false));
        assertFalse(gate.forced());
    }

    @Test
    void theCountSurvivesARestart() {
        var retries = new ShulkerRecovery.Retries<String>();
        retries.seed("a", 2);
        assertTrue(retries.allow("a"));
        assertFalse(retries.allow("a"), "3 in all, counting the 2 before the restart");
        retries.seed("b", 99);
        assertFalse(retries.allow("b"), "capped");
    }
}
