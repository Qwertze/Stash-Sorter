package com.porter.qwerte.core.enchant;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * An item or enchanted book as the anvil sees it: its (stored) enchantments and the stored repair-cost value
 * (REPAIR_COST, 0 for a fresh item).
 */
public record Stack(boolean book, Map<String, Integer> enchants, int repairCost) {
    public Stack {
        TreeMap<String, Integer> m = new TreeMap<>();
        if (enchants != null) {
            for (Map.Entry<String, Integer> e : enchants.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    m.put(McData.norm(e.getKey()), e.getValue());
                }
            }
        }
        enchants = Collections.unmodifiableMap(m);
        repairCost = Math.max(0, repairCost);
    }

    public static Stack item(Map<String, Integer> enchants, int repairCost) {
        return new Stack(false, enchants, repairCost);
    }

    public static Stack book(Map<String, Integer> enchants, int repairCost) {
        return new Stack(true, enchants, repairCost);
    }

    /** A new item with no enchantments and no prior work. */
    public static Stack fresh() {
        return new Stack(false, Map.of(), 0);
    }
}
