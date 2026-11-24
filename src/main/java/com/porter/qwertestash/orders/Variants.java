package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

/**
 * The spec of a stack from what the stash keeps of it ({@code Item#fw}, see {@code VariantItems}): rockets and stars (B3), tipped arrows and lingering
 * potions, suspicious stew (B4, the index specs), and what the sub-plan calls a banner, map, book or shield of the order. Pure; any thread.
 */
public final class Variants {
    private Variants() {
    }

    /**
     * The kinds of spec the bot writes in the index: those its {@code can} says (CRAFT-CONTRACT-v1.2.md 4, "a bot sends only the kinds in its can").
     * @param fireworks the firework tables (rockets and stars) of its extras, or null for none
     */
    public record Kinds(FireworkSpecs.@Nullable Tables fireworks, boolean tipped, boolean stew) {
        public static final Kinds NONE = new Kinds(null, false, false);

        public static Kinds fireworks(FireworkSpecs.@Nullable Tables fireworks) {
            return fireworks == null ? NONE : new Kinds(fireworks, false, false);
        }

        public boolean any() {
            return fireworks != null || tipped || stew;
        }
    }

    /**
     * The spec the index carries for a stack: a rocket's or star's, a tipped arrow's or lingering potion's, a suspicious stew's, when the bot's kinds say so.
     * Banners, maps, books and shields have none (CRAFT-CONTRACT-v1.2.md 4: copy and shield are never index specs).
     */
    public static @Nullable String indexSpec(@Nullable String data, Kinds kinds) {
        if (data == null || data.isEmpty()) return null;
        switch (data.charAt(0)) {
            case 'R', 'S' -> {
                return kinds.fireworks() == null ? null : FireworkSpecs.specOf(data, kinds.fireworks());
            }
            case 'T' -> {
                return kinds.tipped() ? tipped(data) : null;
            }
            case 'W' -> {
                return kinds.stew() ? stew(data) : null;
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * What the sub-plan calls the variant of a carried or gathered stack (the spec half of its pseudo id), or null for a stack that is only its item id.
     * @param id       the item id without a namespace
     * @param data     {@code Item#fw}
     * @param indexKey the stack's index key
     * @param fireworks the bot's firework tables (the colours of a rocket or star), or null
     */
    public static @Nullable String specOf(String id, @Nullable String data, String indexKey, FireworkSpecs.@Nullable Tables fireworks) {
        if (data == null || data.isEmpty()) return null;
        switch (data.charAt(0)) {
            case 'R', 'S' -> {
                return FireworkSpecs.specOf(data, fireworks);
            }
            case 'T' -> {
                return tipped(data);
            }
            case 'W' -> {
                return stew(data);
            }
            case 'C' -> {
                // A banner or map is its own copy: the spec names its index key. A decorated shield is the shield of some banner: which one is not kept
                if (id.equals("shield")) return "shield:carried";
                return indexKey.indexOf('#') > 0 ? "copy:" + indexKey : null;
            }
            case 'G' -> {
                // A written book of generation 0 is an original (a source for copies); a copy of one is only a written book
                return data.equals("G0") && indexKey.indexOf('#') > 0 ? "src:" + indexKey : null;
            }
            default -> {
                return null;
            }
        }
    }

    /** The pseudo id of a stack: its item id and its spec when it has one. */
    public static String refOf(String id, @Nullable String data, String indexKey, FireworkSpecs.@Nullable Tables fireworks) {
        return Pseudo.of("minecraft:" + id, specOf(id, data, indexKey, fireworks));
    }

    private static @Nullable String tipped(String data) {
        return data.startsWith("T|") ? VariantSpecs.tippedSpec(data.substring(2)) : null;
    }

    private static @Nullable String stew(String data) {
        if (!data.startsWith("W|")) return null;
        String[] p = data.split("\\|", -1);
        if (p.length != 3) return null;
        try {
            return VariantSpecs.stewSpec(p[1], Long.parseLong(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The generation of a written book from its saved data ({@code G<n>}), or -1 when it is not a book's. */
    public static int generationOf(@Nullable String data) {
        if (data == null || data.length() < 2 || data.charAt(0) != 'G') return -1;
        try {
            return Integer.parseInt(data.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
