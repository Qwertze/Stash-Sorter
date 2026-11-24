package com.porter.qwertestash.module;

import org.junit.jupiter.api.Test;

import static com.porter.qwertestash.module.WaypointProgress.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

class WaypointProgressTest {
    private static final class Pathing implements WaypointProgress.Pathing {
        boolean available = true;
        boolean failed;
        int failureChecks;
        int availabilityChecks;

        @Override
        public boolean ownFailureSettled() {
            failureChecks++;
            return failed;
        }

        @Override
        public boolean mayRequest() {
            availabilityChecks++;
            return available;
        }
    }

    @Test
    void ignoresStaleFailuresUntilThisWaypointOwnsARequest() {
        var path = new Pathing();
        path.failed = true;
        path.available = false;
        var attempt = new WaypointProgress();
        assertEquals(WAIT, attempt.advance(50, 20, path));
        path.available = true;
        assertEquals(REQUEST_PATH, attempt.advance(51, 19, path));
        assertEquals(0, path.failureChecks);
        assertEquals(NO_PATH, attempt.advance(52, 1, path));
        assertEquals(1, path.failureChecks);
        assertEquals(2, path.availabilityChecks);
        assertEquals(REQUEST_PATH, new WaypointProgress().advance(53, 12, path));
    }

    @Test
    void requestsAreSpacedByBotTicksAndOnlySuccessfulAdmissionConsumesTheInterval() {
        var attempt = new WaypointProgress();
        var path = new Pathing();
        assertEquals(REQUEST_PATH, attempt.advance(300, 10, path));
        assertEquals(WAIT, attempt.advance(360, 10, path));
        path.available = false;
        assertEquals(WAIT, attempt.advance(361, 10, path));
        path.available = true;
        assertEquals(REQUEST_PATH, attempt.advance(900, 10, path));
        assertEquals(WAIT, attempt.advance(960, 10, path));
        assertEquals(REQUEST_PATH, attempt.advance(961, 10, path));
    }

    @Test
    void mustStayWithinTwoBlocksForFortyOneConsecutiveActiveTicks() {
        var attempt = new WaypointProgress();
        var path = new Pathing();
        for (int i = 1; i <= 40; i++) assertEquals(WAIT, attempt.advance(i, 2, path));
        assertEquals(0, path.availabilityChecks);
        assertEquals(REQUEST_PATH, attempt.advance(41, 2.01, path));
        for (int i = 42; i <= 81; i++) assertEquals(WAIT, attempt.advance(i, 1.99, path));
        assertEquals(UNLOADED, attempt.advance(82, 2.0, path));
        assertEquals(1, path.availabilityChecks);
    }

    @Test
    void exactlyOneBlockOfImprovementDoesNotResetTheStallClock() {
        var attempt = new WaypointProgress();
        var path = new Pathing();
        path.available = false;
        assertEquals(WAIT, attempt.advance(1, 12, path));
        for (int i = 2; i <= 301; i++) assertEquals(WAIT, attempt.advance(i, 11, path));
        assertEquals(STALLED, attempt.advance(302, 11, path));
    }

    @Test
    void greaterImprovementRestartsTheStallClockAndSkippedTicksDoNotAgeIt() {
        var attempt = new WaypointProgress();
        var path = new Pathing();
        path.available = false;
        for (int i = 0; i <= 300; i++) assertEquals(WAIT, attempt.advance(10_000L * i, 12, path));
        assertEquals(WAIT, attempt.advance(3_010_000, 10.99, path));
        for (int i = 1; i <= 300; i++) assertEquals(WAIT, attempt.advance(3_010_000L + i, 10, path));
        assertEquals(STALLED, attempt.advance(3_010_301, 10, path));
    }

    @Test
    void overallTimeoutWinsBeforeASettledFailureOrAnotherRequest() {
        var attempt = new WaypointProgress();
        var path = new Pathing();
        for (int tick = 1; tick <= 800; tick++) {
            var decision = attempt.advance(tick, 5000 - tick * 2, path);
            assertEquals((tick - 1) % 61 == 0 ? REQUEST_PATH : WAIT, decision);
        }
        path.failed = true;
        int checked = path.failureChecks;
        assertEquals(TIMED_OUT, attempt.advance(801, 0, path));
        assertEquals(checked, path.failureChecks);
    }
}
