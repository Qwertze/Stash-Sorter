package com.porter.qwertestash.stash;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared kit-items table is the contract's file: its fingerprint must be the one the contract names. */
class KitItemsTest {
    @Test
    void theResourceHasTheContractFingerprint() {
        assertNotNull(KitItems.class.getResource(KitItems.RESOURCE), "the resource is in the jar");
        assertEquals("ed47369c84e5a477e55669f4a9be7f47217a50499d7955d2da7c1959d07e1bf7", KitItems.FINGERPRINT_V1);
        assertEquals(KitItems.FINGERPRINT_V1, KitItems.shared().fingerprint());
    }

    @Test
    void theTableHasWhatTheContractSays() {
        KitItems t = KitItems.shared();
        assertTrue(t.op().contains("minecraft:totem_of_undying"));
        assertTrue(t.op().contains("minecraft:elytra"));
        assertEquals(15, t.groups().size());
        assertTrue(t.groups().stream().anyMatch(g -> g.id().equals("gear") && g.min() == 3));
        assertTrue(t.groups().stream().anyMatch(g -> g.id().equals("concrete") && g.min() == 5));
    }

    @Test
    void theFingerprintIgnoresOrderButNotContent() {
        KitItems a = new KitItems(Set.of("b", "a"), List.of(new KitItems.Group("z", 2, Set.of("y", "x")), new KitItems.Group("c", 1, Set.of("q"))));
        KitItems b = new KitItems(Set.of("a", "b"), List.of(new KitItems.Group("c", 1, Set.of("q")), new KitItems.Group("z", 2, Set.of("x", "y"))));
        assertEquals(a.fingerprint(), b.fingerprint());
        assertNotEquals(a.fingerprint(), new KitItems(Set.of("a", "b"), List.of(new KitItems.Group("c", 1, Set.of("q")), new KitItems.Group("z", 3, Set.of("x", "y")))).fingerprint());
    }
}
