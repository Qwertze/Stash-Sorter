package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

/**
 * The planner's name for a variant of an item (CRAFT-CONTRACT-v1.2.md 3 and 15, as the server writes it): {@code <item id>@<spec>}, for example
 * {@code minecraft:firework_star@star:small_ball;red;;}. The sub-plan, the ledger and the order's loads reason in these; a plain item id has no
 * {@code @}. A real item id never holds one, and a spec never starts with one. Pure.
 */
public final class Pseudo {
    private Pseudo() {
    }

    public static String of(String id, @Nullable String spec) {
        return spec == null || spec.isEmpty() ? id : id + "@" + spec;
    }

    public static boolean is(String id) {
        return id.indexOf('@') > 0;
    }

    /** The item id of a pseudo id (the id itself for a plain one). */
    public static String base(String id) {
        int at = id.indexOf('@');
        return at > 0 ? id.substring(0, at) : id;
    }

    /** The spec of a pseudo id, or null for a plain id. */
    public static @Nullable String spec(String id) {
        int at = id.indexOf('@');
        return at > 0 ? id.substring(at + 1) : null;
    }
}
