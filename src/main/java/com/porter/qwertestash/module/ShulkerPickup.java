package com.porter.qwertestash.module;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * The decisions about getting a shulker of ours back off the ground once it has been broken, kept apart from the world
 * so they can be tested: which item is looked for, when the shulker count says it was picked up, what a pickup whose
 * slot can't be told means, and which shulkers AutoDrop must never give away. Bot tick thread only.
 */
public final class ShulkerPickup {
    /** Ticks a grown shulker count with no slot to show for it has to last, with nothing of ours in sight, to count as picked up. */
    public static final int CONFIRM_TICKS = 10;
    /** Stands for "a shulker of any colour" among ours, when the colour of one is not known. */
    public static final String ANY = "*";

    private ShulkerPickup() {
    }

    /**
     * What the shulker count is measured against: the count the pickup brings it to, the slots that held shulkers
     * before, and the count the last tick the shulker in the world was still seen standing (-1 if it never was).
     */
    public static final class Watch {
        private int before;
        private final Set<Integer> slotsBefore = new HashSet<>();
        private int atBreak = -1;

        /** The shulker in hand is about to be put down: @param heldNow counts it. Put down once the count drops below this. */
        public void placing(int heldNow) {
            before = heldNow;
            atBreak = -1;
        }

        /**
         * A shulker of ours stands in the world (or lies on the ground) and is not in the inventory: picking it up brings
         * the count to one more than now. Said every time the bot comes back to one, not only when it is put down: what
         * was put down and picked up in between (another shulker unpacked meanwhile) must not look like this one.
         */
        public void standing(int heldNow, Collection<Integer> shulkerSlots) {
            before = heldNow + 1;
            atBreak = -1;
            slots(shulkerSlots);
        }

        /** The slots that hold shulkers now, which the picked up one will not be in. */
        public void slots(Collection<Integer> shulkerSlots) {
            slotsBefore.clear();
            slotsBefore.addAll(shulkerSlots);
        }

        /** The shulker in the world was seen standing with this many held: picking it up adds to this. */
        public void sawStanding(int heldNow) {
            atBreak = heldNow;
        }

        /** Breaking and collecting start over: the count at the break is not known yet. */
        public void forgetBreak() {
            atBreak = -1;
        }

        /** The place went through: the count dropped. */
        public boolean putDown(int now) {
            return now < before;
        }

        /** The count says one more shulker was picked up. */
        public boolean grew(int now) {
            return now >= before || (atBreak >= 0 && now > atBreak);
        }

        /** A shulker in this slot was there before: it is not the one picked up. */
        public boolean heldBefore(int slot) {
            return slotsBefore.contains(slot);
        }
    }

    /**
     * The item to look for on the ground, and to ask the pathfinder to pick up, for a shulker of a plan: the colour the
     * plan's shulker was put down as, else the block standing at its spot, else null (any shulker). Never just the last
     * shulker put down, which may be another one (unpacked between batches) of another colour.
     */
    public static @Nullable String lookFor(@Nullable String planShulker, @Nullable String blockThere) {
        if (planShulker != null && !planShulker.isBlank()) return planShulker;
        if (blockThere != null && blockThere.endsWith("shulker_box")) return blockThere;
        return null;
    }

    /** What a pickup whose slot can't be told comes to. */
    public enum Unknown {
        /** Not yet known: looked at again next tick. */
        Wait,
        /** Picked up: the shulker is in the inventory, only its slot is not known. */
        PickedUp,
        /** Not picked up: it is still to be collected. */
        NotPickedUp
    }

    /**
     * The shulker count grew but no slot shows the shulker. @param grownTicks how long the count has stayed up;
     * @param ourItemInSight a dropped shulker like ours is in sight. Only a count that stays up, with nothing like ours
     * left on the ground, is a pickup: a count that grew for another reason must not end the collecting with the shulker
     * still lying there. But a shulker like ours in sight may be someone else's: once the count has stayed up for
     * MAX_DEFER_TICKS it is taken as picked up anyway, rather than chasing the other one and giving ours up as lost.
     */
    public static Unknown unknownSlot(int grownTicks, boolean ourItemInSight) {
        if (grownTicks >= MAX_DEFER_TICKS) return Unknown.PickedUp;
        if (ourItemInSight) return Unknown.NotPickedUp;
        return grownTicks < CONFIRM_TICKS ? Unknown.Wait : Unknown.PickedUp;
    }

    /** The longest a grown count with no slot to show for it is doubted because a shulker like ours is in sight. */
    public static final int MAX_DEFER_TICKS = 20 * 60;

    /** How near the spot of a shulker we just broke, and how soon after the break, an item must appear to be its drop. */
    public static final int BREAK_RADIUS = 4;
    public static final long BREAK_WINDOW_MS = 5_000;
    /** An item can be seen a moment before the spot is: the break counts from a little before it was noticed. */
    public static final long BREAK_EARLY_MS = 1_000;

    /** A shulker of ours just broken: its colour (null if not known), where it stood, and when (ms) the spot went clear. */
    public record Break(@Nullable String id, int x, int y, int z, long atMs) {
    }

    /**
     * AutoDrop: whether a picked up item must be kept, not given back to the player it seems to come from. Only what is
     * very likely ours: a shulker of the colour being collected right now (any shulker while collecting one of unknown
     * colour), or a shulker of the colour of one just broken that appeared next to its spot right after the break.
     * Standing shulkers, ones to be resumed and ones given up on keep nothing: a player's shulker must not end up
     * treated as stash cargo.
     * @param collecting the colours being collected (or recovered) now, ANY for one of unknown colour; empty when not
     * @param recent the shulker of ours broken last, or null
     * @param spawnKnown whether where and when (ms) the item appeared is known
     */
    public static boolean keepFromReturn(@Nullable String itemId, boolean isShulker, Collection<String> collecting, @Nullable Break recent,
                                         boolean spawnKnown, int sx, int sy, int sz, long spawnAtMs) {
        if (!isShulker || itemId == null) return false;
        if (collecting.contains(itemId) || collecting.contains(ANY)) return true;
        if (recent == null || recent.id() == null || !recent.id().equals(itemId) || !spawnKnown) return false;
        if (spawnAtMs < recent.atMs() - BREAK_EARLY_MS || spawnAtMs > recent.atMs() + BREAK_WINDOW_MS) return false;
        long dx = sx - recent.x(), dy = sy - recent.y(), dz = sz - recent.z();
        return dx * dx + dy * dy + dz * dz <= (long) BREAK_RADIUS * BREAK_RADIUS;
    }
}
