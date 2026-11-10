package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repairing items with experience bottles before they are delivered (REPAIR-CONTRACT.md, plugin 1.13.0): the contract's
 * numbers and reasons, and the result the order reports. Plain Java, no game types, any thread.
 */
public final class Repair {
    private Repair() {
    }

    /** One bottle is 3-11 XP, about 7, and Mending repairs 2 durability per XP point: one bottle is about 14 durability. */
    public static final int BOTTLE_DURABILITY = 14;
    public static final String BOTTLE = "minecraft:experience_bottle";
    /** Hard cap on throwing time per order (ticks): about 2 minutes. */
    public static final int MAX_THROW_TICKS = 2 * 60 * 20;
    /** Another player this close (blocks) stops the throwing. */
    public static final double PLAYER_RADIUS = 8.0;
    /** Interrupted throwing is tried once more, after this many attempts in all. */
    public static final int MAX_ATTEMPTS = 2;
    /** The contract's limits on the result. */
    public static final int MAX_ENTRIES = 400, MAX_KEY = 256, MAX_REASON = 60;

    public static final String NO_MENDING = "no Mending";
    public static final String NOT_DAMAGED = "not damaged";
    public static final String NOT_SINGLE = "not a single item";
    public static final String NO_BOTTLES = "no bottles";
    public static final String INTERRUPTED = "interrupted";

    /** Bottles to fully repair damage: {@code ceil(damage / 14)}; 0 for nothing to repair. */
    public static int bottlesFor(int damage) {
        return damage <= 0 ? 0 : (damage + BOTTLE_DURABILITY - 1) / BOTTLE_DURABILITY;
    }

    /** A few bottles over the estimate, for the ones that give little: 3, and one more per 4 items. */
    public static int spare(int items) {
        return items <= 0 ? 0 : 3 + items / 4;
    }

    /** Bottles to take from the stash for items with this much damage each (estimate plus spare); 0 for no damage at all. */
    public static int bottlesToTake(Collection<Integer> damages) {
        int sum = 0, items = 0;
        for (int d : damages) {
            int b = bottlesFor(d);
            if (b > 0) {
                sum += b;
                items++;
            }
        }
        return sum == 0 ? 0 : sum + spare(items);
    }

    /**
     * Why an item cannot be repaired, or null when it can (Mending, damaged, a single item). The contract's order: Mending is
     * asked first, then damage, then the stack size.
     */
    public static @Nullable String skipReason(boolean mending, int damage, int maxStack) {
        return skipReason(false, mending, damage, maxStack);
    }

    /**
     * As above for a stack that may be a shulker box: that is "not a single item" before anything else (contract v1.2), then
     * "no Mending", "not damaged", "not a single item".
     */
    public static @Nullable String skipReason(boolean shulkerBox, boolean mending, int damage, int maxStack) {
        if (shulkerBox) return NOT_SINGLE;
        if (!mending) return NO_MENDING;
        if (damage <= 0) return NOT_DAMAGED;
        if (maxStack != 1) return NOT_SINGLE;
        return null;
    }

    /**
     * Whether a carried line counts as wanted for repair at all (contract v1.2): a line the order marked itself always does; under
     * "repair all" only a damaged one does, so undamaged tools and stackable items get no entry.
     */
    public static boolean wanted(boolean explicitLine, int damage) {
        return explicitLine || damage > 0;
    }

    /** Whether this item may sit in a window slot of the player inventory: the armour slots 5-8 take only their own armour. */
    public static boolean fitsSlot(int window, @Nullable String id) {
        if (id == null) return false;
        String n = id.startsWith("minecraft:") ? id.substring(10) : id;
        return switch (window) {
            case 5 -> n.endsWith("_helmet");
            case 6 -> n.endsWith("_chestplate") || n.equals("elytra");
            case 7 -> n.endsWith("_leggings");
            case 8 -> n.endsWith("_boots");
            default -> true;
        };
    }

    /** Free slots the trip needs for what it stows: damaged Mending armour, the off hand and main hand occupants, the bottle stacks. */
    public static int slotsNeeded(int damagedArmour, boolean offhandOccupied, int bottles) {
        return damagedArmour + (offhandOccupied ? 1 : 0) + 1 + (Math.max(0, bottles) + 63) / 64;
    }

    /**
     * The moves that put a saved ledger back, checked against what is there now: an entry whose item is gone, whose place is
     * taken, or whose item does not fit its place (an armour slot) is dropped. Each result is {from, to} of one move. Last move first.
     */
    public static List<int[]> undoPlan(List<int[]> ledger, java.util.function.IntFunction<@Nullable String> idAt) {
        Map<Integer, String> now = new LinkedHashMap<>();
        java.util.function.IntFunction<@Nullable String> at = w -> now.containsKey(w) ? now.get(w) : idAt.apply(w);
        List<int[]> out = new ArrayList<>();
        for (int i = ledger.size() - 1; i >= 0; i--) {
            int[] m = ledger.get(i);
            String id = at.apply(m[1]);
            if (id == null || at.apply(m[0]) != null || !fitsSlot(m[0], id)) continue;
            now.put(m[0], id);
            now.put(m[1], null);
            out.add(new int[] {m[1], m[0]});
        }
        return out;
    }

    /** Whether an enchantment name from the registry ("mending" or "minecraft:mending") is Mending. */
    public static boolean isMending(@Nullable String enchantment) {
        if (enchantment == null) return false;
        String e = enchantment.toLowerCase(java.util.Locale.ROOT);
        int colon = e.indexOf(':');
        // "name:level" entries and "namespace:name" ids both occur
        if (colon >= 0 && e.substring(0, colon).equals("minecraft")) e = e.substring(colon + 1);
        colon = e.indexOf(':');
        if (colon >= 0) e = e.substring(0, colon);
        return e.equals("mending");
    }

    /** At most {@code max} characters. */
    public static String cut(@Nullable String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    // The ledger of moves (gear stored, items and bottles taken in hand), kept in the stash file while a trip runs

    /** "from>to" for each move, in the order they were made. */
    public static List<String> formatLedger(List<int[]> moves) {
        List<String> out = new ArrayList<>();
        for (int[] m : moves) out.add(m[0] + ">" + m[1]);
        return out;
    }

    /** The moves in a saved ledger; entries that do not read are left out. */
    public static List<int[]> parseLedger(@Nullable List<String> saved) {
        List<int[]> out = new ArrayList<>();
        if (saved == null) return out;
        for (String s : saved) {
            if (s == null) continue;
            int gt = s.indexOf('>');
            if (gt <= 0) continue;
            try {
                int from = Integer.parseInt(s.substring(0, gt).trim());
                int to = Integer.parseInt(s.substring(gt + 1).trim());
                if (from >= 0 && from <= 45 && to >= 0 && to <= 45 && from != to) out.add(new int[] {from, to});
            } catch (NumberFormatException e) {
                // Left out
            }
        }
        return out;
    }

    /**
     * The result of the repairs of one order, as the contract's {@code result.repair}: bottles thrown, items fully repaired,
     * items delivered not fully repaired, items wanted but not tried. Bot tick thread.
     */
    public static final class Report {
        private int bottlesUsed, repaired, partialCount, skippedCount, ticks, retries;
        private @Nullable JsonObject cached;
        private final List<Partial> partial = new ArrayList<>();
        private final List<Skipped> skipped = new ArrayList<>();
        private final List<String> reasons = new ArrayList<>();

        public record Partial(String key, int damage, int maxDamage) {
        }

        public record Skipped(String key, String reason) {
        }

        public int bottlesUsed() {
            return bottlesUsed;
        }

        public int repaired() {
            return repaired;
        }

        /** Items not fully repaired in all (partial and skipped), counted past the entry limit too. */
        public int notRepaired() {
            return partialCount + skippedCount;
        }

        public List<Partial> partial() {
            return partial;
        }

        public List<Skipped> skipped() {
            return skipped;
        }

        public void thrown(int bottles) {
            if (bottles > 0) bottlesUsed += bottles;
            cached = null;
        }

        public void repaired(int n) {
            if (n > 0) repaired += n;
            cached = null;
        }

        /** Throwing ticks the order has used so far (kept across a restart: the cap is per order). */
        public int ticks() {
            return ticks;
        }

        public void addTicks(int n) {
            if (n > 0) ticks += n;
        }

        /** Retries after an interruption already made for the order. */
        public int retries() {
            return retries;
        }

        public void addRetry() {
            retries++;
        }

        /** An item delivered with this much damage left; {@code why} says what stopped the repair. */
        public void partial(String key, int damage, int maxDamage, String why) {
            partialCount++;
            cached = null;
            if (partial.size() < MAX_ENTRIES) partial.add(new Partial(cut(key, MAX_KEY), Math.max(0, damage), Math.max(0, maxDamage)));
            reason(why);
        }

        /** An item wanted for repair that was not tried (or not worked on). */
        public void skip(String key, String why) {
            skippedCount++;
            cached = null;
            String r = cut(why, MAX_REASON);
            if (skipped.size() < MAX_ENTRIES) skipped.add(new Skipped(cut(key, MAX_KEY), r));
            reason(r);
        }

        private void reason(String why) {
            String r = cut(why, MAX_REASON);
            if (!r.isEmpty() && !reasons.contains(r)) reasons.add(r);
        }

        /** Nothing happened at all (no item was looked at). */
        public boolean untouched() {
            return bottlesUsed == 0 && repaired == 0 && partialCount == 0 && skippedCount == 0;
        }

        /** The order message's note, or null when everything wanted was repaired: "2 item(s) not fully repaired: no Mending, no bottles". */
        public @Nullable String note() {
            int n = notRepaired();
            if (n == 0) return null;
            return n + " item(s) not fully repaired" + (reasons.isEmpty() ? "" : ": " + String.join(", ", reasons));
        }

        /** The contract's {@code result.repair} object. */
        public JsonObject toJson() {
            // Worked out again only after a change: the status is asked for every few seconds
            JsonObject c = cached;
            if (c != null) return c;
            JsonObject o = new JsonObject();
            o.addProperty("bottlesUsed", Math.max(0, bottlesUsed));
            o.addProperty("repaired", Math.max(0, repaired));
            JsonArray p = new JsonArray();
            for (Partial e : partial) {
                JsonObject x = new JsonObject();
                x.addProperty("key", e.key());
                x.addProperty("damage", e.damage());
                x.addProperty("maxDamage", e.maxDamage());
                p.add(x);
            }
            o.add("partial", p);
            JsonArray s = new JsonArray();
            for (Skipped e : skipped) {
                JsonObject x = new JsonObject();
                x.addProperty("key", e.key());
                x.addProperty("reason", e.reason());
                s.add(x);
            }
            o.add("skipped", s);
            cached = o;
            return o;
        }

        /** The same plus what is needed to carry on after a restart: the counts past the limit and the reasons. */
        public JsonObject toSaved() {
            JsonObject o = toJson().deepCopy();
            o.addProperty("partialCount", partialCount);
            o.addProperty("skippedCount", skippedCount);
            o.addProperty("ticks", ticks);
            o.addProperty("retries", retries);
            JsonArray r = new JsonArray();
            for (String s : reasons) r.add(s);
            o.add("reasons", r);
            return o;
        }

        public static Report fromSaved(@Nullable JsonObject o) {
            Report r = new Report();
            if (o == null) return r;
            try {
                r.bottlesUsed = Math.max(0, intOf(o, "bottlesUsed"));
                r.repaired = Math.max(0, intOf(o, "repaired"));
                if (o.has("partial") && o.get("partial").isJsonArray()) {
                    for (JsonElement e : o.getAsJsonArray("partial")) {
                        if (!e.isJsonObject() || r.partial.size() >= MAX_ENTRIES) continue;
                        JsonObject x = e.getAsJsonObject();
                        r.partial.add(new Partial(cut(str(x, "key"), MAX_KEY), Math.max(0, intOf(x, "damage")), Math.max(0, intOf(x, "maxDamage"))));
                    }
                }
                if (o.has("skipped") && o.get("skipped").isJsonArray()) {
                    for (JsonElement e : o.getAsJsonArray("skipped")) {
                        if (!e.isJsonObject() || r.skipped.size() >= MAX_ENTRIES) continue;
                        JsonObject x = e.getAsJsonObject();
                        r.skipped.add(new Skipped(cut(str(x, "key"), MAX_KEY), cut(str(x, "reason"), MAX_REASON)));
                    }
                }
                r.partialCount = Math.max(r.partial.size(), intOf(o, "partialCount"));
                r.skippedCount = Math.max(r.skipped.size(), intOf(o, "skippedCount"));
                r.ticks = Math.max(0, intOf(o, "ticks"));
                r.retries = Math.max(0, intOf(o, "retries"));
                if (o.has("reasons") && o.get("reasons").isJsonArray()) {
                    for (JsonElement e : o.getAsJsonArray("reasons")) if (e.isJsonPrimitive()) r.reason(e.getAsString());
                }
            } catch (RuntimeException e) {
                // What did not read is lost; the repair itself never blocks an order
            }
            return r;
        }

        private static int intOf(JsonObject o, String k) {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : 0;
        }

        private static String str(JsonObject o, String k) {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
        }
    }

    /**
     * What the shulker holds after repairs, by the keys the order asked for. A repaired item has another key (its damage is part
     * of it): {@code renames} lists {@code {key now, key asked for}} for each repaired item of the shulker, and each takes one
     * item of the first key back to the second. Pure.
     */
    public static Map<String, Integer> translate(Map<String, Integer> content, List<String[]> renames) {
        if (renames == null || renames.isEmpty()) return content;
        Map<String, Integer> out = new LinkedHashMap<>(content);
        for (String[] r : renames) {
            int have = out.getOrDefault(r[0], 0);
            if (have <= 0 || r[0].equals(r[1])) continue;
            if (have == 1) out.remove(r[0]);
            else out.put(r[0], have - 1);
            out.merge(r[1], 1, Integer::sum);
        }
        return out;
    }
}
