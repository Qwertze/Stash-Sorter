package com.porter.qwertestash.module;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.21.0: being pinned, and what is done about it. */
class PinWatchTest {
    @Test
    void aFloodOfCorrectionsPins() {
        PinWatch w = new PinWatch();
        long t = 0;
        for (int i = 0; i < 19; i++) {
            w.noteSetback(t += 100);
            assertFalse(w.observe(t, 5, 64, 5, true, false), "19 is not yet");
        }
        w.noteSetback(t += 100);
        assertTrue(w.observe(t, 5, 64, 5, true, false));
        assertTrue(w.why().contains("keeps moving it back"));
    }

    @Test
    void aSingleTeleportOrSlowTricklesDoNotPin() {
        PinWatch w = new PinWatch();
        w.noteSetback(0);
        assertFalse(w.observe(10, 0, 64, 0, true, false));
        for (long t = 1000; t < 60_000; t += 1000) {
            w.noteSetback(t);
            assertFalse(w.observe(t, 0, 64, 0, true, false));
        }
    }

    @Test
    void standingStillInABlockOfOursPinsAfterTwentySecondsOfWalking() {
        PinWatch w = new PinWatch();
        for (long t = 0; t < 20_000; t += 50) assertFalse(w.observe(t, 5, 64, 5, true, true), "at " + t);
        assertTrue(w.observe(20_000, 5, 64, 5, true, true));
        assertTrue(w.why().contains("has not moved"));
    }

    @Test
    void notWalkingOrMovingOrNotObstructedDoesNotPin() {
        PinWatch w = new PinWatch();
        for (long t = 0; t < 60_000; t += 50) assertFalse(w.observe(t, 5, 64, 5, false, true), "idle beside our own block");
        PinWatch m = new PinWatch();
        for (long t = 0; t < 60_000; t += 50) assertFalse(m.observe(t, 5 + t / 1000.0, 64, 5, true, true), "moving");
        PinWatch o = new PinWatch();
        for (long t = 0; t < 60_000; t += 50) assertFalse(o.observe(t, 5, 64, 5, true, false), "walking slowly, nothing in the way");
    }

    @Test
    void endsWhenTheCorrectionsStopAndNothingIsInTheWay() {
        PinWatch w = new PinWatch();
        long t = 0;
        for (int i = 0; i < 25; i++) w.noteSetback(t += 100);
        assertTrue(w.observe(t, 5, 64, 5, true, false));
        assertTrue(w.observe(t + 2_000, 5, 64, 5, true, false), "still within the quiet time");
        assertFalse(w.observe(t + 3_000, 5, 64, 5, true, false));
        PinWatch b = new PinWatch();
        for (long u = 0; u <= 20_000; u += 100) b.observe(u, 5, 64, 5, true, true);
        assertTrue(b.pinned());
        assertTrue(b.observe(60_000, 5, 64, 5, true, true));
        assertFalse(b.observe(60_100, 5, 64, 5, true, false), "mined back");
    }

    @Test
    void aWalkCutShortByAPinDoesNotCount() {
        assertFalse(PinPolicy.walkFailureCounts(true, 1000, -1));
        assertFalse(PinPolicy.walkFailureCounts(false, 1000, 5000), "a pin ended after the job began");
        assertTrue(PinPolicy.walkFailureCounts(false, 6000, 5000), "the job began after the pin");
        assertTrue(PinPolicy.walkFailureCounts(false, 1000, -1), "no pin at all");
    }

    @Test
    void aWalkFailureOfAPinIsForgottenOtherOnesStaySticky() {
        PinPolicy.DeliveryFailure d = new PinPolicy.DeliveryFailure();
        d.fail("reach", true, 10_000);
        assertFalse(d.forgetWalkSince(20_000), "failed long before the pin: stays, as before");
        assertEquals("reach", d.reason());
        assertTrue(d.forgetWalkSince(5_000), "failed around the pin: forgotten");
        assertNull(d.reason());
        d.fail("open", false, 10_000);
        assertFalse(d.forgetWalkSince(0), "a failure to open is not a walk");
        assertEquals("open", d.reason());
    }

    @Test
    void containersMarkedAroundAPinAreGivenBack() {
        PinPolicy.RecentMarks<String> m = new PinPolicy.RecentMarks<>();
        m.mark("old", 1_000);
        m.mark("a", 50_000);
        m.mark("b", 60_000);
        assertEquals(java.util.List.of("a", "b"), m.takeSince(40_000));
        assertTrue(m.takeSince(40_000).isEmpty());
        assertEquals(java.util.List.of("old"), m.takeSince(0));
    }
}
