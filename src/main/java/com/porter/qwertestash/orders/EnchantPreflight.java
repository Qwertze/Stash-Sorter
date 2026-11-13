package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The preflight of one enchant item (ENCHANT-CONTRACT v1.0, "Result and failure rules" and "Bot rules" 1 and 2): what has to be in the
 * inventory before step 1, and whether it is. The base, the books and the anvil spare are taken by the keys of the order's {@code take} only,
 * charged to the ledger's per-key budget; all the bottles the plan calls for (its margin is part of the figure) are held; the carry formula is
 * checked; the plan is made again with the real items ({@link EnchantReplan}). The preflight gives up with {@code timeout} after
 * {@link #STALL_MS} without headway (a stack taken) or more than {@code 12 + 4 x leaves} trips. Pure: the host gathers what
 * {@link Gather} lists, then asks again.
 */
public final class EnchantPreflight {
    /** No headway (a stack taken) for this long ends the preflight with {@code timeout}. */
    public static final long STALL_MS = 10 * 60 * 1000L;

    /** An index key and how many of it to take. */
    public record Lot(String key, int count) {
    }

    public sealed interface Decision permits Gather, Refuse, Ready {
    }

    /** Take these from the stash (a lot is at most what the budget allows). */
    public record Gather(List<Lot> lots) implements Decision {
    }

    /** The item is refused before step 1: everything goes back. {@code code} is in {@link Codes}. */
    public record Refuse(String code, String why) implements Decision {
    }

    /** Everything is held and the real plan passed. */
    public record Ready(EnchantOrder.ItemPlan real) implements Decision {
    }

    public static int maxTrips(int leaves) {
        return 12 + 4 * Math.max(1, leaves);
    }

    private final EnchantOrder.ItemRef ref;
    private final String mc;
    private final Set<String> consent;
    private final EnchantLedger ledger;
    private final LongSupplier clock;
    private final Map<String, Long> charged = new HashMap<>();
    private int trips;
    private long headwayAt;

    public EnchantPreflight(EnchantOrder.ItemRef ref, String mc, Set<String> consent, EnchantLedger ledger, LongSupplier clock) {
        this.ref = ref;
        this.mc = mc;
        this.consent = consent;
        this.ledger = ledger;
        this.clock = clock;
        this.headwayAt = clock.getAsLong();
    }

    public int trips() {
        return trips;
    }

    /** A gather trip was made: counted, and headway when it took anything. */
    public void tripMade(boolean tookSomething) {
        trips++;
        if (tookSomething) headwayAt = clock.getAsLong();
    }

    /** {@code timeout} when the preflight has stalled or made too many trips, else null. */
    public @Nullable String stalled() {
        if (trips > maxTrips(ref.plan().leaves())) return Codes.TIMEOUT;
        if (clock.getAsLong() - headwayAt > STALL_MS) return Codes.TIMEOUT;
        return null;
    }

    /** The anvil slots the carry check counts: 0 with no spare reserved, else 1 (fresh anvils stack; the contract's figure of 2 is an upper bound). */
    private int anvilSlots() {
        return spareKey() == null ? 0 : 1;
    }

    /** The anvil key a spare is taken under: one the order reserved, preferring what is already held; null when the order reserved none. */
    private @Nullable String spareKey() {
        for (String k : ledger.anvilKeys()) if (ledger.block().takeMap().getOrDefault(k, 0L) > 0) return k;
        return null;
    }

    /**
     * @param owned     the stacks this order carries (taken for it: its own gear and other items are never listed)
     * @param liveCarry the free carry slots after the gear is stowed, plus the slots the owned stacks take now
     */
    public Decision decide(List<EnchantItems.Held> owned, int liveCarry) {
        return decide(owned, List.of(), liveCarry);
    }

    /**
     * As {@link #decide(List, int)}, for an order that crafts bases (ENCHANT-CONTRACT draft 3.4). {@code craftedBases} are the carried stacks the order's craft
     * made as bases for its enchant plans (never in {@code owned}: they were not taken from the stash and are not charged to the ledger). A plan whose base is
     * {@code crafted} takes nothing from the stash for it and is planned again with one of those stacks as the base; with none carried (the craft came up short)
     * it is refused with {@code no_base}. The base counts in the carry formula as always: {@code liveCarry} includes the slot of the crafted base the item uses.
     */
    public Decision decide(List<EnchantItems.Held> owned, List<EnchantItems.Held> craftedBases, int liveCarry) {
        EnchantOrder.ItemPlan plan = ref.plan();
        // What the plan calls for, by index key
        Map<String, Long> need = new LinkedHashMap<>();
        String baseKey = plan.baseKey();
        if (baseKey == null) return new Refuse(Codes.NO_BASE, "the plan has no base item");
        boolean crafted = plan.baseCrafted();
        List<EnchantItems.Held> madeBases = new ArrayList<>();
        if (crafted) {
            // The base is made by the order's craft: a fresh stack of the plain id, nothing is taken for it
            for (EnchantItems.Held h : craftedBases) {
                if (!h.book() && !h.named() && h.rc() == 0 && h.ench().isEmpty() && h.id().equals(EnchantOrder.plain(baseKey))) madeBases.add(h);
            }
            if (madeBases.isEmpty()) return new Refuse(Codes.NO_BASE, "the base this item needs was not crafted");
        }
        else need.put(baseKey, 1L);
        for (var e : plan.bookKeys().entrySet()) need.merge(e.getKey(), (long) e.getValue(), Long::sum);
        long bottles = plan.bottles();
        if (bottles > 0) need.merge(EnchantOrder.BOTTLE_KEY, bottles, Long::sum);
        String spare = spareKey();
        // An anvil that broke earlier in this order used up the reserved spare: the next item goes on without one (an anvil stands) rather than being refused
        if (spare != null && ledger.anvilsBroken() > 0 && ledger.remaining(spare) == 0) spare = null;
        if (spare != null) need.merge(spare, 1L, Long::sum);

        if (!EnchantCarry.fits(plan.books(), anvilSlots(), plan.bottles(), liveCarry)) {
            return new Refuse(Codes.INVENTORY_FULL, "the materials need " + EnchantCarry.slotsNeeded(plan.books(), anvilSlots(), plan.bottles()) + " carry slots, " + liveCarry + " are free");
        }

        Map<String, Long> have = new HashMap<>();
        for (EnchantItems.Held h : owned) have.merge(h.key(), (long) Math.max(1, h.count()), Long::sum);
        // Charge what was taken since the last look; more than the order reserved is a refusal (never a take beyond the budget)
        for (var e : have.entrySet()) {
            if (!ledgerKnows(e.getKey())) continue;
            long c = charged.getOrDefault(e.getKey(), 0L);
            if (e.getValue() > c) {
                if (!ledger.charge(e.getKey(), e.getValue() - c)) return new Refuse(codeFor(e.getKey(), spare), "more of an item was taken than the order reserved");
                charged.put(e.getKey(), e.getValue());
            }
        }

        List<Lot> lots = new ArrayList<>();
        for (var e : need.entrySet()) {
            long missing = e.getValue() - have.getOrDefault(e.getKey(), 0L);
            if (missing <= 0) continue;
            if (ledger.remaining(e.getKey()) < missing) return new Refuse(codeFor(e.getKey(), spare), "the stash has no more of what the order reserved");
            lots.add(new Lot(e.getKey(), (int) Math.min(Integer.MAX_VALUE, missing)));
        }
        if (!lots.isEmpty()) return new Gather(lots);

        // Everything is held: the plan is made again with what is really there
        List<EnchantItems.Held> bases = new ArrayList<>(), books = new ArrayList<>();
        Set<String> bookKeys = plan.bookKeys().keySet();
        for (EnchantItems.Held h : owned) {
            if (!crafted && h.key().equals(baseKey) && !h.book()) bases.add(h);
            else if (h.book() && bookKeys.contains(h.key())) books.add(h);
        }
        if (crafted) bases.addAll(madeBases);
        EnchantReplan.Result r = EnchantReplan.replan(mc, ref.key(), ref.enchants(), plan, limitTo(bases, 1), books, consent);
        if (!r.ok()) return new Refuse(Codes.safe(r.code()), r.message());
        return new Ready(r.plan());
    }

    /** The stacks to plan with: at most {@code n} of them. */
    private static List<EnchantItems.Held> limitTo(List<EnchantItems.Held> list, int n) {
        return list.size() <= n ? list : new ArrayList<>(list.subList(0, n));
    }

    private boolean ledgerKnows(String key) {
        return ledger.block().takeMap().containsKey(key);
    }

    private String codeFor(String key, @Nullable String spare) {
        if (key.equals(ref.plan().baseKey())) return Codes.NO_BASE;
        if (key.equals(EnchantOrder.BOTTLE_KEY)) return Codes.XP_SHORT;
        if (key.equals(spare)) return Codes.NO_ANVIL;
        return Codes.BOOKS_SHORT;
    }
}
