package com.porter.qwertestash.orders;

/**
 * The carry-slot arithmetic of enchant orders (ENCHANT-CONTRACT v1.0, "inventory_full uses carry slots"). One formula at plan time (the
 * server, with the heartbeat's {@code enchant.carry}) and at preflight (the bot, with its live free carry count after its gear is stowed):
 * {@code 1 (base) + books + anvil slots + 1 (source shulker) + ceil(bottles / 64) <= carry}. Pure.
 */
public final class EnchantCarry {
    private EnchantCarry() {
    }

    /** Slots a bottle stack takes. */
    public static final int BOTTLE_STACK = 64;
    /** What the server assumes when the heartbeat sent no carry. */
    public static final int DEFAULT_CARRY = 20;
    /** The most the heartbeat may say (the main inventory and hotbar). */
    public static final int MAX_CARRY = 36;

    /** Slots the item's materials take at once. {@code anvilSlots} is an upper bound (fresh anvils stack, so 2 is the contract's figure). */
    public static int slotsNeeded(int books, int anvilSlots, int bottles) {
        // long math: a bottle count near Integer.MAX_VALUE must not wrap into a small (or negative) number of slots
        long n = 1L + Math.max(0, books) + Math.max(0, anvilSlots) + 1 + (Math.max(0L, bottles) + BOTTLE_STACK - 1) / BOTTLE_STACK;
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    /** Whether the item's materials fit the free carry slots (after the gear is stowed). */
    public static boolean fits(int books, int anvilSlots, int bottles, int carry) {
        return slotsNeeded(books, anvilSlots, bottles) <= carry;
    }

    /**
     * Crafted bases (ENCHANT-CONTRACT draft 3.4), the one slot formula on both sides. The server checks, for every item in plan order across all lines,
     * {@code slotsNeeded + L <= carry}, where {@code L} is the number of LATER items whose base is crafted (1 slot each, the bases are carried from the start).
     * The bot's live carry is the free slots after the gear is stowed plus the slots its owned stacks take now: the later bases are already in the inventory, so
     * they are not free (that is {@code + L}), and this item's own crafted base is added back with {@link #ownBaseSlot}. Both give the same answer.
     */
    public static int ownBaseSlot(boolean baseCrafted, boolean baseInHand) {
        return baseCrafted && baseInHand ? 1 : 0;
    }

    /**
     * The {@code enchant.carry} of the heartbeat: the free carry slots after the bot's gear is stowed
     * ({@link Repair#slotsNeeded(int, boolean, int)} with no bottles), at least 0 and at most 36.
     */
    public static int heartbeatCarry(int freeCarrySlots, int damagedMendingArmour, boolean offhandOccupied) {
        int gear = Repair.slotsNeeded(Math.max(0, damagedMendingArmour), offhandOccupied, 0);
        return Math.max(0, Math.min(MAX_CARRY, freeCarrySlots - gear));
    }

    /**
     * The most bottles one item may hold at carry {@code carry}: {@code (carry - 4) x 64} (4 = base, two anvils, source shulker) as the server's
     * ceiling, at most 2,048. Only for messages and tests: the check is {@link #fits}.
     */
    public static int bottleCeiling(int carry) {
        return Math.max(0, Math.min(MAX_CARRY - 4, carry - 4)) * BOTTLE_STACK;
    }
}
