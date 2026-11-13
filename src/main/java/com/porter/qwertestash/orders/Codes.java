package com.porter.qwertestash.orders;

import java.util.Set;

/**
 * The closed set of reasons an enchant item can be short with (ENCHANT-CONTRACT v1.0, "Reason set", SR2-N2). Nothing outside this set is ever
 * sent to the server; {@code interrupted} is not used by enchant.
 */
public final class Codes {
    private Codes() {
    }

    public static final String TOO_EXPENSIVE = "too_expensive", BOOKS_SHORT = "books_short", XP_SHORT = "xp_short", NO_ANVIL = "no_anvil",
        INVENTORY_FULL = "inventory_full", COST_MISMATCH = "cost_mismatch", MC_UNSUPPORTED = "mc_unsupported", MC_MISMATCH = "mc_mismatch", BUSY = "busy",
        TOO_COMPLEX = "too_complex", NO_BASE = "no_base", ANVIL_BROKE = "anvil_broke", WRONG_RESULT = "wrong_result", MODULE_CONFLICT = "module_conflict",
        CAP = "cap", CANCELLED = "cancelled", TIMEOUT = "timeout";

    public static final Set<String> ALL = Set.of(TOO_EXPENSIVE, BOOKS_SHORT, XP_SHORT, NO_ANVIL, INVENTORY_FULL, COST_MISMATCH, MC_UNSUPPORTED, MC_MISMATCH,
        BUSY, TOO_COMPLEX, NO_BASE, ANVIL_BROKE, WRONG_RESULT, MODULE_CONFLICT, CAP, CANCELLED, TIMEOUT);

    /** The codes that may end a run only at the preflight, before step 1. */
    public static final Set<String> PREFLIGHT = Set.of(NO_BASE, BOOKS_SHORT, XP_SHORT, NO_ANVIL, INVENTORY_FULL, COST_MISMATCH, MC_MISMATCH, MODULE_CONFLICT,
        TIMEOUT);

    /** A code that is in the set, else {@link #WRONG_RESULT}'s neighbour {@link #CAP}: nothing outside the set leaves the bot. */
    public static String safe(String code) {
        return code != null && ALL.contains(code) ? code : CAP;
    }
}
