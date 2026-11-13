package com.porter.qwertestash.orders;

import com.porter.qwerte.core.enchant.Stack;

import java.util.Map;
import java.util.TreeMap;

/**
 * Carried stacks as an enchant order sees them: what the anvil cares about (book or item, enchantments, stored repair cost) and the index key
 * the stash knew it by. Plain data; the bot builds these from its inventory.
 */
public final class EnchantItems {
    private EnchantItems() {
    }

    /**
     * A carried stack.
     *
     * @param slot  where it is (the caller's numbering)
     * @param key   the index key ({@code minecraft:enchanted_book#1a2b3c4d})
     * @param id    the plain item id ({@code enchanted_book})
     * @param ench  stored enchantments of a book, enchantments of an item, by plain id
     * @param rc    the stored repair cost (REPAIR_COST), 0 when none
     * @param named a custom name is on it
     */
    public record Held(int slot, String key, String id, int count, boolean book, Map<String, Integer> ench, int rc, boolean named) {
        public Held {
            ench = new TreeMap<>(ench);
        }

        public Spec spec() {
            return new Spec(book, ench, rc);
        }
    }

    /** What an anvil operand is: book or item, its enchantments and its stored repair cost. */
    public record Spec(boolean book, Map<String, Integer> ench, int rc) {
        public Spec {
            ench = new TreeMap<>(ench);
        }

        public Stack stack() {
            return new Stack(book, ench, rc);
        }

        public static Spec of(Stack s) {
            return new Spec(s.book(), s.enchants(), s.repairCost());
        }

        public boolean matches(Held h) {
            return h.book() == book && h.rc() == rc && h.ench().equals(ench);
        }
    }

    /** The plain id of an enchanted book. */
    public static final String BOOK_ID = "enchanted_book";
}
