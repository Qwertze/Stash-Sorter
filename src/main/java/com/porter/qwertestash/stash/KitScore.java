package com.porter.qwertestash.stash;

import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The kit score of one shulker box (KIT-CONTRACT.md v1.1, "Kit score"): points for the word kit in its name, an item mix, all 27 slots
 * occupied and an OP item, against a threshold. Pure: slot contents as item ids in, breakdown out. The server and the pages only show it.
 */
public final class KitScore {
    private KitScore() {
    }

    public static final int SLOTS = 27;

    /**
     * Point values, the threshold and the OP count. Points are 0..10, the threshold 1..40. The op points need {@code opMin} OP items in the
     * box (1..1728, counted per item: the sum of the stack counts).
     */
    public record Scoring(int threshold, int name, int mix, int full, int op, int opMin) {
        public static final int DEFAULT_OP_MIN = 3;
        public static final Scoring DEFAULT = new Scoring(4, 2, 3, 1, 4, DEFAULT_OP_MIN);

        public Scoring(int threshold, int name, int mix, int full, int op) {
            this(threshold, name, mix, full, op, DEFAULT_OP_MIN);
        }

        public static boolean valid(int threshold, int name, int mix, int full, int op) {
            return valid(threshold, name, mix, full, op, DEFAULT_OP_MIN);
        }

        public static boolean valid(int threshold, int name, int mix, int full, int op, int opMin) {
            return threshold >= 1 && threshold <= 40 && points(name) && points(mix) && points(full) && points(op) && opMin >= 1 && opMin <= 1728;
        }

        private static boolean points(int p) {
            return p >= 0 && p <= 10;
        }

        /** The sum of the four point values. */
        public int max() {
            return name + mix + full + op;
        }
    }

    /** The parts that apply (points, not flags), their sum, and whether the box is a kit. */
    public record Result(int name, int mix, int full, int op, int total, boolean kit) {
        public static final Result NONE = new Result(0, 0, 0, 0, 0, false);

        /** The same parts without regard to whether it is a kit (for the report's breakdown, which is only ever for kits). */
        public boolean sameParts(Result other) {
            return name == other.name && mix == other.mix && full == other.full && op == other.op;
        }
    }

    /** One occupied slot: the item registry id and the stack count. */
    public record Stack(String id, int count) {
    }

    /**
     * @param occupiedIds the registry id ("minecraft:..." or without the namespace) of every occupied slot, one entry per slot, each a stack of one
     * @param customName  the box plain custom name, or null
     */
    public static Result score(List<String> occupiedIds, @Nullable String customName, Scoring s, KitItems table) {
        return scoreNormalized(ones(occupiedIds), KitNames.normalize(customName), s, table);
    }

    /** The same with the stack counts, which the OP count needs. */
    public static Result scoreStacks(List<Stack> occupied, @Nullable String customName, Scoring s, KitItems table) {
        return scoreNormalized(occupied, KitNames.normalize(customName), s, table);
    }

    private static List<Stack> ones(List<String> ids) {
        List<Stack> out = new java.util.ArrayList<>(ids.size());
        for (String id : ids) out.add(new Stack(id, 1));
        return out;
    }

    /** The same with the name already normalised (callers that need the key too normalise the name once). */
    public static Result scoreNormalized(List<Stack> occupiedStacks, @Nullable String normalizedName, Scoring s, KitItems table) {
        int occupied = occupiedStacks.size();
        int name = KitNames.isKit(normalizedName) ? s.name() : 0;

        Set<String> distinct = new HashSet<>();
        long opItems = 0;
        for (Stack st : occupiedStacks) {
            String id = full(st.id());
            distinct.add(id);
            if (table.op().contains(id)) opItems += Math.max(1, st.count());
        }

        int mix = 0;
        for (KitItems.Group g : table.groups()) {
            int have = 0;
            for (String id : distinct) if (g.items().contains(id)) have++;
            if (have >= g.min()) {
                mix = s.mix();
                break;
            }
        }
        int fullPoints = occupied >= SLOTS ? s.full() : 0;
        // OP points need opMin OP items in the box, counted per item (the stack counts added up)
        int op = opItems >= s.opMin() ? s.op() : 0;
        int total = name + mix + fullPoints + op;

        // A box of one item is a kit only when it is full; an empty box never is
        boolean single = distinct.size() == 1;
        boolean kit = occupied > 0 && total >= s.threshold() && !(single && occupied < SLOTS);
        return new Result(name, mix, fullPoints, op, total, kit);
    }

    private static String full(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    /** The score of a shulker box stack (what the plugin reads of it); not a shulker: never a kit. */
    public static Result of(Item shulker, Scoring s, KitItems table) {
        return shulker == null ? Result.NONE : of(shulker, shulker.customName ? KitNames.normalize(shulker.customText) : null, s, table);
    }

    /** @param normalizedName the stack's custom name already normalised (null when unnamed) */
    public static Result of(Item shulker, @Nullable String normalizedName, Scoring s, KitItems table) {
        if (shulker == null || !shulker.isShulker()) return Result.NONE;
        List<Stack> stacks = new java.util.ArrayList<>(shulker.contents == null ? 0 : shulker.contents.size());
        if (shulker.contents != null) for (Item inner : shulker.contents) if (inner != null && inner.id != null) stacks.add(new Stack(inner.id, inner.count));
        return scoreNormalized(stacks, normalizedName, s, table);
    }
}
