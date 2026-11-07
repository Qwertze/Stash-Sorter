package com.porter.qwertestash.module;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendsTest {
    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void emptyListMatchesNobody() {
        assertFalse(Friends.matches(List.of(), ID, "Alt"));
    }

    @Test
    void matchesByNameIgnoringCaseOrByUuid() {
        assertTrue(Friends.matches(List.of("dustinfoes"), null, "DustinFoes"));
        assertTrue(Friends.matches(List.of(ID.toString().toUpperCase()), ID, null));
        assertTrue(Friends.matches(List.of(ID.toString()), ID, "Renamed"), "uuid wins over a changed name");
        assertFalse(Friends.matches(List.of("Other"), ID, "DustinFoes"));
    }
}
