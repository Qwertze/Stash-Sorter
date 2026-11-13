package com.porter.qwerte.core.enchant;

import java.util.List;

/**
 * What to plan: one item type, the ordered enchantments, and what the stash holds.
 *
 * <p>Books may carry extra enchantments (always allowed); base items may not carry unwanted ones.
 *
 * @param item   item id, for example {@code diamond_sword}
 * @param wanted the ordered enchantments with levels
 * @param bases  candidate base items in stock (one is used); an empty list gives the {@code no_base} refusal
 * @param books  enchanted books in stock with their stored repair cost
 */
public record PlanRequest(String item, List<Wanted> wanted, List<Stock> bases, List<Stock> books) {
    public record Wanted(String id, int level) {
    }

    /** A stack type in stock: enchantments and repair cost, and how many of them exist. */
    public record Stock(Stack stack, int count) {
    }
}
