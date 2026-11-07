package com.porter.qwertestash.module;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurveyRetryTest {
    @Test
    void retriesAfterOneThenTwoThenFiveMinutes() {
        var retry = new SurveyRetry<String>();
        assertEquals(1200, retry.failed("a", 0));
        assertEquals(1200 + 2400, retry.failed("a", 1200));
        assertEquals(3600 + 6000, retry.failed("a", 3600));
        assertEquals(9600 + 6000, retry.failed("a", 9600), "stays at five minutes");
        retry.succeeded("a");
        assertEquals(100 + 1200, retry.failed("a", 100), "a good read starts over");
    }

    @Test
    void eachContainerOnItsOwnScheduleAndNeverMissedByATick() {
        var retry = new SurveyRetry<String>();
        Set<String> failed = new HashSet<>(Set.of("a", "b"));
        retry.failed("a", 0);
        retry.failed("b", 500);
        assertEquals(0, retry.release(failed, 1199));
        assertEquals(1, retry.release(failed, 1203), "1203 is not a multiple of 1200 and still counts");
        assertTrue(failed.contains("b"));
        assertEquals(1, retry.release(failed, 1700));
        assertTrue(failed.isEmpty());
    }
}
