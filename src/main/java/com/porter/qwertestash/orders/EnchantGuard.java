package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * The module guard of enchant orders (ENCHANT-CONTRACT v1.0, "Bot rules" 5, and the owner's decision of 2026-11-12): the proxy's own {@code Enchanter},
 * {@code KitMaker} and {@code OrderFiller} drive an anvil and the inventory themselves, and {@code AutoMend} and {@code AutoArmor} move the gear the bot puts
 * aside and take XP, so while one of them is on the order is refused with {@code module_conflict}: at planning, at every item's preflight, and in the
 * heartbeat ({@code enabled:false}, so the claim is not even offered). {@code AutoTotem} may stay on: the bottles are thrown from the main hand and the
 * off hand is left alone. Pure: names come from {@code ProxySources}.
 */
public final class EnchantGuard {
    private EnchantGuard() {
    }

    /** Modules that refuse the order while on. */
    public static final Set<String> BLOCKING = Set.of("enchanter", "kitmaker", "orderfiller", "automend", "autoarmor");

    /** A module name as one lower-case word ("Kit Maker", "KitMaker" and "kit_maker" are one). */
    static String norm(String name) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; name != null && i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) b.append(Character.toLowerCase(c));
        }
        return b.toString().toLowerCase(Locale.ROOT);
    }

    /** The name (as given) of the first enabled module that blocks enchant orders, or null. */
    public static @Nullable String conflict(Collection<String> enabledModules) {
        for (String n : enabledModules) if (BLOCKING.contains(norm(n))) return n;
        return null;
    }

    /**
     * A running enchant item (past its first step) that meets this refusal after a restart is never failed: with {@code module_conflict} it waits (the status
     * says which module to turn off) until the module is off, unless a force-cancel was heard (then the trip ends at once at its boundary). Other refusals
     * (the option off, another Minecraft version) let the running item finish.
     */
    public static boolean pausesRunningItem(@Nullable String problem, boolean forceCancelled) {
        return Codes.MODULE_CONFLICT.equals(problem) && !forceCancelled;
    }
}
