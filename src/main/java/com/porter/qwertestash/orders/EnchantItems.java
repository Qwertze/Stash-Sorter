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

        /** The spec alone (no item id): package-private on purpose, a caller that picks a stack uses {@link #matches(Held, String)}. */
        boolean matches(Held h) {
            return h.book() == book && h.rc() == rc && h.ench().equals(ench);
        }

        /**
         * The spec AND the plain item id: a book must be {@code enchanted_book}, anything else must be {@code baseId}. The spec alone is not enough: a stack of
         * bottles or a spare anvil (no enchantments, repair cost 0, not a book) has the same spec as a fresh base. A null {@code baseId} skips the id check of a non-book.
         */
        public boolean matches(Held h, @org.jspecify.annotations.Nullable String baseId) {
            if (!matches(h)) return false;
            String id = com.porter.qwerte.core.enchant.McData.norm(h.id());
            return book ? BOOK_ID.equals(id) : baseId == null || com.porter.qwerte.core.enchant.McData.norm(baseId).equals(id);
        }
    }

    /** The plain item id of an index key ({@code minecraft:diamond_sword#ab12} as {@code diamond_sword}). */
    public static String idOf(String key) {
        int hash = key.indexOf('#');
        return com.porter.qwerte.core.enchant.McData.norm(hash >= 0 ? key.substring(0, hash) : key);
    }

    /**
     * The finished item among the carried stacks: the last one that has the spec and the line's item id and is not a kept slot (a finished item waiting for its
     * bin), or null. Pure: the caller maps the slot back to its inventory item.
     */
    public static @org.jspecify.annotations.Nullable Held finished(java.util.List<Held> carried, Spec spec, String lineKey, java.util.Set<Integer> keptSlots) {
        String id = idOf(lineKey);
        Held got = null;
        for (Held h : carried) if (spec.matches(h, id) && !keptSlots.contains(h.slot())) got = h;
        return got;
    }

    /** The plain id of an enchanted book. */
    public static final String BOOK_ID = "enchanted_book";
}
