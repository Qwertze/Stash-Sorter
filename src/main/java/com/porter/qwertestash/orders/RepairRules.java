package com.porter.qwertestash.orders;

import com.porter.qwertestash.manager.ManagerIndex;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.KitRouting;
import com.porter.qwertestash.stash.Stash.Box;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The pure rules of repair passes (REPAIR-CONTRACT v2): what is low, what the bottle reserve leaves usable, which items of the saved stash are candidates (loose
 * or inside unnamed boxes), and how a pass cuts them into a batch (route order, reserve, room). Nothing here touches the world. Any thread.
 */
public final class RepairRules {
    private RepairRules() {
    }

    /** The reasons a pass leaves an item alone (REPAIR-CONTRACT v2: report {@code items[].reason}). */
    public static final String NO_BOTTLES = "no bottles", RESERVE = "reserve", INTERRUPTED = "interrupted", IN_A_SHULKER = "in a shulker", NO_ROOM = "no room",
        STOPPED = "stopped", ORDER = "order", COOLDOWN = "cooldown", IN_A_NAMED_SHULKER = "in a named shulker", IN_A_KIT = "in a kit", BOX_CAP = "box cap",
        BOX_FAILED = "box failed", PLAYER_NEAR = "player near";

    /** The most items one batch holds. */
    public static final int MAX_BATCH = 27;

    /** {@code (maxDamage - damage) * 100 < T * maxDamage}, in integers; a damageable item with damage only. */
    public static boolean low(int damage, int maxDamage, int threshold) {
        return maxDamage > 0 && damage > 0 && (long) (maxDamage - damage) * 100 < (long) threshold * maxDamage;
    }

    /** {@code max(0, stashBottles - heldOrderBottles - reserve)}. */
    public static long usable(long stashBottles, long heldOrderBottles, int reserve) {
        return Math.max(0, stashBottles - heldOrderBottles - reserve);
    }

    /** A low item with no Mending (it goes to the needs-repair chest); never a shulker box, a book or an undamaged item. */
    public static boolean needsRepair(Item it, int threshold) {
        return damageable(it) && !it.hasMending() && low(it.damage, it.maxDamage, threshold);
    }

    /** A low item with Mending (a pass repairs it). */
    public static boolean mend(Item it, int threshold) {
        return damageable(it) && it.hasMending() && low(it.damage, it.maxDamage, threshold);
    }

    /** A damageable single item that is not a shulker box. */
    public static boolean damageable(Item it) {
        return it != null && it.id != null && !it.isShulker() && it.maxStack == 1 && it.maxDamage > 0 && it.damage > 0 && !it.isBundle();
    }

    /**
     * One low item in a container's slot ({@code slot} of the container) or inside a box ({@code box}: the shulker's group key; {@code boxSlot} where the box lies).
     * @param variant the variant hash of the stack (null in older saves), for the failure memory
     */
    public record Cand(String container, int slot, @Nullable String box, String key, @Nullable String variant, int damage, int maxDamage, boolean mending) {
        public int bottles() {
            return Repair.bottlesFor(damage);
        }

        /** The failure memory's key for this item. */
        public String memoryKey() {
            return variant != null ? variant : key;
        }
    }

    /** An unnamed, non-kit box that holds at least one mend candidate and may be opened: its place and its candidates. */
    public record BoxStop(String container, int slot, String key, List<Cand> mend, List<Cand> needs) {
    }

    /**
     * @param mend          loose mend candidates; {@code needs} loose needs-repair items
     * @param boxes         the boxes a pass may open
     * @param inShulkers    low items that will not be reached, with the reason: {@code in a shulker}, {@code in a named shulker} or {@code in a kit}
     */
    public record Scan(List<Cand> mend, List<Cand> needs, List<BoxStop> boxes, int inShulkers, Map<String, Integer> inShulkerReasons) {
        public int mendBottles() {
            int n = 0;
            for (Cand c : mend) n += c.bottles();
            return n;
        }

        public int inBoxes() {
            int n = 0;
            for (BoxStop b : boxes) n += b.mend().size();
            return n;
        }
    }

    /**
     * The candidates of a saved stash.
     * @param eligible      containers a pass may take from (not delivery, excluded, drop-off, unread, stale, kit- or repair-reserved)
     * @param shulkersOn    the website's "open unnamed boxes"
     * @param isKit         the shulker is a kit by the kit rules
     * @param onCooldown    a box key opened by a pass in the last 24 h (the key changes with its contents)
     * @param chestSet      a needs-repair chest is set (needs-repair items in openable boxes are taken too)
     */
    public static Scan scan(List<Box> boxes, Predicate<Box> eligible, int threshold, boolean shulkersOn, Predicate<Item> isKit, Predicate<String> onCooldown, boolean chestSet) {
        List<Cand> mend = new ArrayList<>(), needs = new ArrayList<>();
        List<BoxStop> stops = new ArrayList<>();
        Map<String, Integer> reasons = new LinkedHashMap<>();
        int inShulkers = 0;
        for (Box box : boxes) {
            if (box.contents == null || !eligible.test(box)) continue;
            String id = KitRouting.canonicalId(box);
            for (int slot = 0; slot < box.contents.length; slot++) {
                Item it = box.contents[slot];
                if (it == null) continue;
                if (!it.isShulker()) {
                    Cand c = cand(id, slot, null, it, threshold);
                    if (c != null) (c.mending() ? mend : needs).add(c);
                    continue;
                }
                if (it.contents.isEmpty()) continue;
                String boxKey = ManagerIndex.shulkerKey(it);
                List<Cand> innerMend = new ArrayList<>(), innerNeeds = new ArrayList<>();
                for (Item in : it.contents) {
                    Cand c = cand(id, slot, boxKey, in, threshold);
                    if (c != null) (c.mending() ? innerMend : innerNeeds).add(c);
                }
                if (innerMend.isEmpty() && innerNeeds.isEmpty()) continue;
                String why;
                if (it.customName) why = IN_A_NAMED_SHULKER;
                else if (isKit.test(it)) why = IN_A_KIT;
                else if (!shulkersOn || boxKey == null || onCooldown.test(boxKey) || innerMend.isEmpty()) why = IN_A_SHULKER;
                else why = null;
                if (why == null) stops.add(new BoxStop(id, slot, boxKey, innerMend, chestSet ? innerNeeds : List.of()));
                else {
                    int n = innerMend.size() + innerNeeds.size();
                    inShulkers += n;
                    reasons.merge(why, n, Integer::sum);
                }
            }
        }
        return new Scan(mend, needs, stops, inShulkers, reasons);
    }

    private static @Nullable Cand cand(String container, int slot, @Nullable String box, @Nullable Item it, int threshold) {
        if (it == null || !damageable(it) || !low(it.damage, it.maxDamage, threshold)) return null;
        return new Cand(container, slot, box, it.indexKey(), it.variant, it.damage, it.maxDamage, it.hasMending());
    }

    /** The scan without the items whose memory key is in {@code keys} (items tried with no headway in this pass); boxes left with no mend item are dropped. */
    public static Scan without(Scan scan, java.util.Set<String> keys) {
        if (keys.isEmpty()) return scan;
        List<Cand> mend = new ArrayList<>();
        for (Cand c : scan.mend()) if (!keys.contains(c.memoryKey())) mend.add(c);
        List<BoxStop> boxes = new ArrayList<>();
        for (BoxStop b : scan.boxes()) {
            List<Cand> m = new ArrayList<>();
            for (Cand c : b.mend()) if (!keys.contains(c.memoryKey())) m.add(c);
            if (!m.isEmpty()) boxes.add(new BoxStop(b.container(), b.slot(), b.key(), m, b.needs()));
        }
        return new Scan(mend, scan.needs(), boxes, scan.inShulkers(), scan.inShulkerReasons());
    }

    /** One item a batch leaves alone and why. */
    public record Skipped(Cand cand, String reason) {
    }

    /** What the batch takes: loose items, boxes to open (their mend items and, with a chest, needs-repair items), and the bottles. */
    public record Batch(List<Cand> loose, List<BoxStop> boxes, List<Cand> extraNeeds, int bottles) {
        public int items() {
            int n = loose.size();
            for (BoxStop b : boxes) n += b.mend().size();
            return n;
        }

        public boolean empty() {
            return loose.isEmpty() && boxes.isEmpty();
        }
    }

    /** @param left mend candidates the plan did not reach (over the item cap or the room) */
    public record Plan(Batch batch, List<Skipped> skipped, int left) {
    }

    private record Stop(double x, double z, List<Cand> loose, @Nullable BoxStop box) {
    }

    /**
     * The next batch: stops (containers with loose candidates, and openable boxes) ordered nearest-neighbour from the bot, items added in route order while each
     * one's bottlesFor fits what is left of {@code usable}, up to the room (min(freeCarry - 1 - slotsNeeded, 27)), the item cap of the pass and the box cap.
     * @param itemsLeft the items the pass may still take; {@code boxesLeft} the boxes it may still open (0: none, their items are skipped with {@code box cap})
     * @param pos       the container id's position (x, z), or null when unknown
     */
    public static Plan plan(Scan scan, long usable, int freeCarry, int armour, boolean offhandOccupied, double fromX, double fromZ, Function<String, double[]> pos, int itemsLeft,
                            int boxesLeft) {
        Map<String, List<Cand>> byContainer = new LinkedHashMap<>();
        for (Cand c : scan.mend()) byContainer.computeIfAbsent(c.container(), k -> new ArrayList<>()).add(c);
        List<Stop> stops = new ArrayList<>();
        for (var e : byContainer.entrySet()) {
            double[] p = pos.apply(e.getKey());
            stops.add(new Stop(p == null ? 0 : p[0], p == null ? 0 : p[1], e.getValue(), null));
        }
        for (BoxStop b : scan.boxes()) {
            double[] p = pos.apply(b.container());
            stops.add(new Stop(p == null ? 0 : p[0], p == null ? 0 : p[1], List.of(), b));
        }
        List<Stop> route = new ArrayList<>();
        double x = fromX, z = fromZ;
        while (!stops.isEmpty()) {
            int best = 0;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < stops.size(); i++) {
                double dx = stops.get(i).x() - x, dz = stops.get(i).z() - z, d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            Stop s = stops.remove(best);
            route.add(s);
            x = s.x();
            z = s.z();
        }

        List<Skipped> skipped = new ArrayList<>();
        List<Cand> loose = new ArrayList<>(), extra = new ArrayList<>();
        List<BoxStop> boxes = new ArrayList<>();
        List<Integer> damages = new ArrayList<>();
        long remaining = usable;
        int taken = 0, boxesUsed = 0, left = 0;
        boolean full = false;
        for (Stop s : route) {
            List<Cand> items = s.box() == null ? s.loose() : s.box().mend();
            if (s.box() != null && boxesUsed >= boxesLeft) {
                for (Cand c : items) skipped.add(new Skipped(c, BOX_CAP));
                continue;
            }
            List<Cand> picked = new ArrayList<>();
            for (Cand c : items) {
                if (full || taken + picked.size() >= itemsLeft) {
                    left++;
                    continue;
                }
                if (c.bottles() > remaining) {
                    skipped.add(new Skipped(c, RESERVE));
                    continue;
                }
                // Room for what is carried: the items, the gear stowed and the bottles
                List<Integer> trial = new ArrayList<>(damages);
                trial.add(c.damage());
                int room = Math.min(freeCarry - 1 - Repair.slotsNeeded(armour, offhandOccupied, Math.min((int) Math.min(usable, Integer.MAX_VALUE), Repair.bottlesToTake(trial))), MAX_BATCH);
                int extraHere = s.box() == null ? 0 : s.box().needs().size();
                if (taken + picked.size() + 1 + (picked.isEmpty() && s.box() != null ? extraHere : 0) + extra.size() > room) {
                    full = true;
                    left++;
                    continue;
                }
                picked.add(c);
                damages.add(c.damage());
                remaining -= c.bottles();
            }
            if (picked.isEmpty()) continue;
            taken += picked.size();
            if (s.box() == null) loose.addAll(picked);
            else {
                boxes.add(new BoxStop(s.box().container(), s.box().slot(), s.box().key(), picked, s.box().needs()));
                extra.addAll(s.box().needs());
                boxesUsed++;
            }
        }
        int bottles = damages.isEmpty() ? 0 : (int) Math.min(usable, Repair.bottlesToTake(damages));
        return new Plan(new Batch(loose, boxes, extra, bottles), skipped, left);
    }
}
