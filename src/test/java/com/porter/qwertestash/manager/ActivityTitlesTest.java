package com.porter.qwertestash.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Titles on the website are not blurred, details are: no title may carry a position. */
class ActivityTitlesTest {
    private static ActivityFeed.Item item(String title, String detail) {
        return new ActivityFeed.Item("Stash Sorter", "plugin", title, detail, null, "info");
    }

    @Test
    void aPositionInATitleMovesToTheDetail() {
        ActivityFeed.Item out = ActivityFeed.scrub(item("reading BlockPos[x=10, y=64, z=-300] (4 moved)", null));
        assertFalse(out.title().contains("-300"));
        assertTrue(out.title().contains("(position)"));
        assertTrue(out.detail().contains("-300"));

        ActivityFeed.Item xyz = ActivityFeed.scrub(item("walking to 120 64 -340", "goal"));
        assertEquals("walking to (position)", xyz.title());
        assertTrue(xyz.detail().contains("120 64 -340") && xyz.detail().endsWith("goal"));
    }

    @Test
    void titlesWithoutOneAreUntouched() {
        ActivityFeed.Item plain = item("Queue position 123", null);
        assertSame(plain, ActivityFeed.scrub(plain));
        ActivityFeed.Item times = item("Waiting, 12s quiet left (3 moved)", null);
        assertSame(times, ActivityFeed.scrub(times));
    }
}
