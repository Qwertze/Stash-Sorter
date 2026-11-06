package com.porter.qwertestash.orders;

import com.porter.qwertestash.manager.ManagerIndex;
import com.porter.qwertestash.stash.Item;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The checks that find an order's shulker in the carry slots, the stash and the delivery container (delivery confirmation,
 * the look after a restart): a shulker renamed for the order is still the unit's, a shulker with another name is not.
 */
class ShulkerMatchTest {
    private static final String D = "minecraft:diamond", RED = "minecraft:red_shulker_box";

    private static Item stack(String id, int count, String key) {
        Item it = new Item();
        it.id = id;
        it.name = Item.prettyName(id);
        it.count = count;
        it.maxStack = id.endsWith("shulker_box") ? 1 : 64;
        it.key = key;
        it.variant = key;
        return it;
    }

    private static Item shulker(Item... inside) {
        Item s = stack("red_shulker_box", 1, RED);
        s.contents = new ArrayList<>();
        for (int i = 0; i < inside.length; i++) {
            inside[i].slot = i;
            s.contents.add(inside[i]);
        }
        return s;
    }

    /** The same shulker after the anvil: a custom name, so a key with a hash, and the key it had without the name. */
    private static Item renamed(Item s, String name) {
        s.customName = true;
        s.customText = name;
        s.unnamedKey = s.key;
        s.key = RED + "#1a2b3c4d";
        s.variant = s.key;
        return s;
    }

    private static Unit bin(String name) {
        Unit u = new Unit(Unit.Kind.Bin, "", new ArrayList<>(List.of(new Order.Lot(D, D, 64), new Order.Lot(D, D, 10))));
        u.name = name;
        return u;
    }

    @Test
    void aBinRenamedForTheOrderIsStillItsShulker() {
        Item done = renamed(shulker(stack("diamond", 64, D), stack("diamond", 10, D)), "Diamonds");
        assertTrue(ShulkerMatch.matches(bin("Diamonds"), done, false));
        assertTrue(ShulkerMatch.matches(bin("Diamonds"), done, true));
        // Not before it was named, nor a shulker named something else, nor a named one for an unnamed unit
        assertFalse(ShulkerMatch.matches(bin("Other"), done, true));
        assertFalse(ShulkerMatch.matches(bin(null), done, true));
        assertTrue(ShulkerMatch.matches(bin("Diamonds"), shulker(stack("diamond", 64, D), stack("diamond", 10, D)), false));
        // Contents still decide
        assertFalse(ShulkerMatch.matches(bin("Diamonds"), renamed(shulker(stack("diamond", 64, D)), "Diamonds"), true));
    }

    @Test
    void aRenamedEmptyShulkerIsKnownByItsKeyWithoutTheName() {
        Unit u = new Unit(Unit.Kind.Empty, RED, List.of());
        u.name = "Empty";
        Item empty = renamed(shulker(), "Empty");
        assertNotEquals(RED, empty.indexKey());
        assertEquals(RED, empty.keyWithoutName());
        assertTrue(ShulkerMatch.matches(u, empty, false));
        assertFalse(ShulkerMatch.matches(u, renamed(shulker(), "Someone's"), true));

        // Read from a save (no key without the name): the key seen when the anvil gave it back still finds it
        Item saved = renamed(shulker(), "Empty");
        String namedKey = saved.indexKey();
        saved.unnamedKey = null;
        assertFalse(ShulkerMatch.matches(u, saved, false));
        u.namedKey = namedKey;
        assertTrue(ShulkerMatch.matches(u, saved, false));
    }

    @Test
    void aRenamedPackedShulkerKeepsItsGroupKey() {
        Item packed = shulker(stack("diamond", 64, D));
        String group = ManagerIndex.shulkerKey(packed);
        Unit u = new Unit(Unit.Kind.Shulker, group, List.of());
        u.name = "Kit";
        assertTrue(ShulkerMatch.matches(u, packed, false));

        Item named = renamed(shulker(stack("diamond", 64, D)), "Kit");
        assertNotEquals(group, ManagerIndex.shulkerKey(named));
        assertEquals(group, ManagerIndex.shulkerKey(named, true));
        // Found even with use-named-shulkers off: it carries the order's own name
        assertTrue(ShulkerMatch.matches(u, named, false));
        // Somebody else's named shulker is not taken for it with named shulkers off
        Item theirs = renamed(shulker(stack("diamond", 64, D)), "Theirs");
        assertFalse(ShulkerMatch.matches(u, theirs, false));
    }

    @Test
    void aPackedShulkerThatHadAnotherNameIsFoundByTheKeySeenAfterTheAnvil() {
        // Ordered by the group key it had with its old name; renamed, both keys change
        Item before = renamed(shulker(stack("diamond", 64, D)), "Old");
        before.key = RED + "#oldname1";
        Unit u = new Unit(Unit.Kind.Shulker, ManagerIndex.shulkerKey(before), List.of());
        u.name = "New";
        Item after = renamed(shulker(stack("diamond", 64, D)), "New");
        after.key = RED + "#newname1";
        assertFalse(ShulkerMatch.matches(u, after, true));
        u.namedKey = ManagerIndex.shulkerKey(after);
        assertTrue(ShulkerMatch.matches(u, after, true));
    }

    @Test
    void aShulkerBackFromTheAnvilInANonCarrySlotIsStillFound() {
        Unit u = bin("Diamonds");
        Item[] inv = new Item[36];
        inv[20] = renamed(shulker(stack("diamond", 64, D), stack("diamond", 10, D)), "Diamonds");
        List<Integer> carried = List.of(3, 4);
        assertEquals(-1, ShulkerMatch.findSlot(u, carried, false, i -> inv[i], false), "only carry slots unless just named");
        assertEquals(20, ShulkerMatch.findSlot(u, carried, true, i -> inv[i], false));
        // A carry slot comes first
        inv[4] = renamed(shulker(stack("diamond", 64, D), stack("diamond", 10, D)), "Diamonds");
        assertEquals(4, ShulkerMatch.findSlot(u, carried, true, i -> inv[i], false));
    }
}
