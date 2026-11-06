package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A dead anvil is given up on for the rest of the order, so a big order doesn't spend a full trip per shulker on it. */
class AnvilHealthTest {
    private static final String A = "anvil-1", B = "anvil-2";

    @Test
    void noResultAndNotTakenAreRememberedForTheOrderAndAnvil() {
        for (String why : List.of(AnvilNamer.NO_RESULT, AnvilNamer.NOT_TAKEN)) {
            AnvilHealth h = new AnvilHealth();
            assertNull(h.problem("o", A));
            h.note("o", A, new AnvilNamer.Outcome(why, false, false));
            assertEquals(why, h.problem("o", A));
            assertNull(h.problem("o", B), "another anvil starts afresh");
            assertNull(h.problem("p", A), "another order starts afresh");
        }
    }

    @Test
    void unreachableAndUnopenableAreRemembered() {
        AnvilHealth h = new AnvilHealth();
        h.note("o", A, new AnvilNamer.Outcome(AnvilNamer.NOT_OPENED, false, true));
        assertEquals(AnvilNamer.NOT_OPENED, h.problem("o", A));
    }

    @Test
    void aSecondCloseInARowIsTroubleButOneIsNot() {
        AnvilHealth h = new AnvilHealth();
        AnvilNamer.Outcome closed = new AnvilNamer.Outcome(AnvilNamer.CLOSED, true, false);
        h.note("o", A, closed);
        assertNull(h.problem("o", A));
        h.note("o", A, new AnvilNamer.Outcome(null, false, false));
        h.note("o", A, closed);
        assertNull(h.problem("o", A), "a success in between resets the count");
        h.note("o", A, closed);
        assertEquals(AnvilNamer.CLOSED, h.problem("o", A));
    }

    @Test
    void problemsOfTheBotNotTheAnvilAreNotRemembered() {
        AnvilHealth h = new AnvilHealth();
        h.note("o", A, new AnvilNamer.Outcome(AnvilNamer.TOO_FEW_LEVELS, false, false));
        h.note("o", A, new AnvilNamer.Outcome(AnvilNamer.TOO_DEAR, false, false));
        h.note("o", A, new AnvilNamer.Outcome("gave way to another module", true, false));
        assertNull(h.problem("o", A));
    }
}
