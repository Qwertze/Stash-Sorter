package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The checks a bot makes before it clicks anything for a craft order (CRAFT-CONTRACT.md "Bot execution", step 1), and the failure
 * reasons the bots report. Pure.
 * <p>
 * The check: {@code craft.v == 1}; {@code craft.recipes} is the hash of the bot's own recipe table; every step's recipe is in that table
 * with the same result and count; every input id is accepted by some cell of that recipe; the total grid fills are within the bot's cap;
 * every {@code take} id (and every craft line key) is a plain item id, never one with a component patch ({@code id#hash}), because the
 * server counts plain keys. Any failure makes every craft line short with {@link #RECIPE_MISMATCH} and nothing is crafted; the rest of
 * the order goes on.
 */
public final class CraftOrder {
    private CraftOrder() {
    }

    /** The failure reasons (CRAFT-CONTRACT.md "Result from the bot"). */
    public static final String NO_TABLE = "no_table", TABLE_MISSING = "table_missing", TABLE_BLOCKED = "table_blocked",
        INGREDIENTS_SHORT = "ingredients_short", INVENTORY_FULL = "inventory_full", RECIPE_MISMATCH = "recipe_mismatch", GRID_STUCK = "grid_stuck",
        TIMEOUT = "timeout", INTERRUPTED = "interrupted", CAP = "cap", CANCELLED = "cancelled";

    /** B2 (CRAFT-CONTRACT-v1.2.md 12): no furnace could be had, it could not be reached or opened, something of someone else's is in it, the output took too long. */
    public static final String NO_FURNACE = "no_furnace", FURNACE_BLOCKED = "furnace_blocked", FURNACE_BUSY = "furnace_busy", SMELT_TIMEOUT = "smelt_timeout";

    public static final Set<String> REASONS = Set.of(NO_TABLE, TABLE_MISSING, TABLE_BLOCKED, INGREDIENTS_SHORT, INVENTORY_FULL, RECIPE_MISMATCH,
        GRID_STUCK, TIMEOUT, INTERRUPTED, CAP, CANCELLED, NO_FURNACE, FURNACE_BLOCKED, FURNACE_BUSY, SMELT_TIMEOUT);

    /** A plain item id: namespaced, no component patch. */
    private static final Pattern PLAIN = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,128}$");

    /** The most one craft line, and all of them together, may ask for; and the most loads they are cut into. */
    public static final int MAX_LINE = 100_000, MAX_TOTAL = 1_000_000, MAX_LOTS = 4096;

    public static boolean plain(@Nullable String id) {
        return id != null && PLAIN.matcher(id).matches();
    }

    /** What a reason means, for the order's note and the log. */
    public static String describe(String reason) {
        return switch (reason) {
            case NO_TABLE -> "no crafting table is set for this stash";
            case TABLE_MISSING -> "the block at the crafting table is not a crafting table";
            case TABLE_BLOCKED -> "the crafting table could not be reached or opened";
            case INGREDIENTS_SHORT -> "not enough ingredients";
            case INVENTORY_FULL -> "no room to carry what is needed";
            case RECIPE_MISMATCH -> "the order's recipes are not the bot's";
            case GRID_STUCK -> "the crafting grid would not clear";
            case TIMEOUT -> "crafting took too long";
            case INTERRUPTED -> "interrupted";
            case CAP -> "a crafting cap was reached";
            case CANCELLED -> "cancelled";
            case NO_FURNACE -> "no furnace is available for smelting";
            case FURNACE_BLOCKED -> "the furnace could not be reached or opened";
            case FURNACE_BUSY -> "the furnace is in use by something else";
            case SMELT_TIMEOUT -> "smelting took too long";
            default -> reason;
        };
    }

    /**
     * @param table   the bot's own recipe table (whose hash the order must name)
     * @param maxFills the most grid fills the bot makes for one order
     * @return null when the order can be carried out, else a short description of what is wrong (the reason is {@link #RECIPE_MISMATCH})
     */
    public static @Nullable String check(Order.@Nullable Craft craft, Recipes table, int maxFills) {
        return check(craft, table, maxFills, null, Set.of());
    }

    /** The deepest craft chain a v2 block may name (CRAFT-CONTRACT-v1.2.md 7 and 9). */
    public static final int V2_MAX_DEPTH = 12;

    /**
     * As above, for a bot that also runs v2 blocks (CRAFT-CONTRACT-v1.2.md 11.1). A v1 block is checked by the v1 rules, nothing changed. A v2 block
     * passes when, besides the v1 checks of its steps: {@code maxDepth} is 1 to 12; every entry of {@code needs} is one of the bot's {@code can}; its
     * {@code extras} is null or the hash of the bot's own extras (and the latter when {@code needs} is not empty); and it has no field reserved for a
     * later milestone that this bot doesn't carry out ({@link Order#reservedField}). Any failure is {@link #RECIPE_MISMATCH} for every craft line.
     * @param extrasHash the hash of the extras the bot holds, or null
     * @param can        the features beyond B1 the bot does (empty in B1)
     */
    public static @Nullable String check(Order.@Nullable Craft craft, Recipes table, int maxFills, @Nullable String extrasHash, Set<String> can) {
        return check(craft, table, maxFills, extrasHash, can, null, 0);
    }

    /**
     * As above, for a bot that can smelt (B2, CRAFT-CONTRACT-v1.2.md 9, 11.1 and 14). A furnace step ({@code station: "furnace"}) passes when the bot's
     * {@code can} has {@code smelt}, {@code smelting} is the bot's own smelt and fuel tables, its recipe is one of the smelt recipes with the same result
     * and count, every input is an alternative of the recipe's input slot and they add up to its {@code fills} (items smelted), and its {@code fuel}
     * names only fuels whose burn time covers {@code fills * cookTicks}. The items smelted in all are at most {@code min(256, maxSmelt)} and within the
     * block's {@code smelt}. A furnace step's {@code fills} are items, not grid fills: only the table steps count against {@code maxFills}.
     * @param smelting the bot's smelting (from its extras), or null when it holds none
     * @param maxSmelt the most items the bot smelts for one order (what its heartbeat says)
     */
    public static @Nullable String check(Order.@Nullable Craft craft, Recipes table, int maxFills, @Nullable String extrasHash, Set<String> can, @Nullable Smelting smelting,
                                         int maxSmelt) {
        return check(craft, table, maxFills, extrasHash, can, smelting, maxSmelt, null);
    }

    /**
     * As above, for a bot that can make fireworks (B3, CRAFT-CONTRACT-v1.2.md 3, 9, 11.1 and 15). A block that uses specs, keys or grids passes only when the
     * bot's {@code can} has {@code firework}, {@code firework} (its tables from its own extras) is not null, the block names the bot's extras and
     * {@code needs} lists {@code firework}; then: every special step's grid is the one the bot builds itself from the step's spec ({@link FireworkSpecs#checkStep});
     * every spec on a line, take or leftover is a canonical rocket or star spec of its item; a take with a spec has the index key it is taken by (a key
     * of that item); and the lines are covered by what the steps make and the take holds, counted per spec.
     * @param firework the bot's firework tables (from its extras), or null when it holds none
     */
    public static @Nullable String check(Order.@Nullable Craft craft, Recipes table, int maxFills, @Nullable String extrasHash, Set<String> can, @Nullable Smelting smelting,
                                         int maxSmelt, FireworkSpecs.@Nullable Tables firework) {
        if (craft == null) return "the order has craft lines but no craft block";
        if (craft.malformed()) return "the craft block can't be read";
        if (craft.v() != 1 && craft.v() != 2) return "craft version " + craft.v() + " is not 1 or 2";
        if (craft.v() == 2) {
            if (craft.maxDepth() < 1 || craft.maxDepth() > V2_MAX_DEPTH) return "craft depth " + craft.maxDepth() + " is not 1 to " + V2_MAX_DEPTH;
            for (String need : craft.needs()) if (!can.contains(need)) return "the order needs " + need + ", which this bot can't do";
            if (craft.extras() != null && !craft.extras().equals(extrasHash)) return "the order was planned with other extras";
            if (!craft.needs().isEmpty() && craft.extras() == null) return "the order needs extras it doesn't name";
            if (craft.reserved() != null) return "the craft block uses " + craft.reserved() + ", which this bot doesn't carry out";
            if (craft.usesSpecs()) {
                if (!can.contains("firework") || firework == null) return "the order uses rockets or stars, which this bot can't make";
                if (!craft.needs().contains("firework")) return "the order uses rockets or stars but doesn't say it needs fireworks";
                if (craft.extras() == null) return "the order needs extras it doesn't name";
            }
        }
        if (!craft.recipes().equals(table.hash())) return "the order was planned with another recipe table";
        if (craft.steps().size() > Order.MAX_CRAFT_STEPS || craft.take().size() > Order.MAX_CRAFT_TAKE) return "too many steps or ingredients";
        long fills = 0, smelted = 0;
        for (Order.CraftStep s : craft.steps()) {
            if (s.special()) {
                // B3: a special step carries the exact grid of a rocket, star or fade; the bot builds it again from the spec and its own extras
                String bad = FireworkSpecs.checkStep(s, firework);
                if (bad != null) return bad;
                fills += s.fills();
                continue;
            }
            if (s.spec() != null) return "recipe " + s.recipe() + " has a spec but no grid";
            if (s.furnace()) {
                // Only a v2 block has furnace steps (parseCraft reads the station of a v2 block only)
                String bad = checkFurnaceStep(s, can, smelting);
                if (bad != null) return bad;
                smelted += s.fills();
                continue;
            }
            Recipes.Recipe r = table.byId(s.recipe());
            if (r == null) return "recipe " + s.recipe() + " is not in the table";
            if (!r.result().equals(s.result()) || r.count() != s.count()) return "recipe " + s.recipe() + " makes something else here";
            if (s.fills() < 1) return "recipe " + s.recipe() + " has no fills";
            fills += s.fills();
            for (Order.Qty in : s.inputs()) {
                if (in.count() < 1 || !plain(in.id()) || in.spec() != null) return "recipe " + s.recipe() + " has a bad input";
                if (!accepts(r, in.id())) return "recipe " + s.recipe() + " takes no " + in.id();
            }
        }
        if (fills > maxFills) return fills + " grid fills are more than the " + maxFills + " this bot makes";
        if (smelted > 0 || craft.smelt() > 0) {
            if (smelted > Math.min(Smelting.MAX_SMELT, maxSmelt)) return smelted + " items to smelt are more than the " + Math.min(Smelting.MAX_SMELT, maxSmelt) + " this bot smelts";
            if (craft.smelt() < smelted) return "the block says " + craft.smelt() + " items smelted but its furnace steps smelt " + smelted;
            if (smelted == 0) return "the block says " + craft.smelt() + " items smelted but has no furnace step";
        }
        for (Order.Qty t : craft.take()) {
            if (t.count() < 1 || !plain(t.id())) return "a take id is not a plain item id";
            String bad = checkSpecified(t.id(), t.spec(), "take");
            if (bad != null) return bad;
            if (t.spec() != null && !keyOf(t.id(), t.key())) return "a take of " + t.id() + " with a spec has no index key of that item";
            if (t.spec() == null && t.key() != null) return "a take of " + t.id() + " names a key but no spec";
        }
        for (Order.Qty l : craft.leftovers()) {
            String bad = checkSpecified(l.id(), l.spec(), "leftover");
            if (bad != null) return bad;
        }
        long total = 0;
        java.util.Map<String, Long> made = new java.util.HashMap<>(), taken = new java.util.HashMap<>();
        for (Order.CraftStep s : craft.steps()) made.merge(s.resultRef(), (long) s.count() * s.fills(), Long::sum);
        for (Order.Qty t : craft.take()) taken.merge(t.ref(), (long) t.count(), Long::sum);
        for (Order.Qty l : craft.lines()) {
            if (l.count() < 1 || !plain(l.id())) return "a craft line key is not a plain item id";
            String bad = checkSpecified(l.id(), l.spec(), "craft line");
            if (bad != null) return bad;
            if (l.count() > MAX_LINE) return "a craft line asks for " + l.count() + ", more than " + MAX_LINE;
            total += l.count();
            // Every line is covered by the steps that make it (or by what the order takes of it from the stash)
            if (made.getOrDefault(l.ref(), 0L) + taken.getOrDefault(l.ref(), 0L) < l.count()) return "the steps don't make " + l.count() + " of " + l.ref();
        }
        if (total > MAX_TOTAL) return "the craft lines ask for " + total + " in all, more than " + MAX_TOTAL;
        // ENCHANT-CONTRACT draft 3.4: the bases made for enchant plans are part of the craft lines (merged with an ordered line of the same item)
        java.util.Map<String, Long> lineCount = new java.util.HashMap<>();
        for (Order.Qty l : craft.lines()) lineCount.merge(l.id(), (long) l.count(), Long::sum);
        for (Order.Qty b : craft.forEnchant()) {
            if (b.count() < 1 || !plain(b.id())) return "a base for the enchant plans is not a plain item id";
            if (b.count() > lineCount.getOrDefault(b.id(), 0L)) return "the craft lines don't include " + b.count() + " of " + b.id() + " for the enchant plans";
        }
        return null;
    }

    /** A spec on a line, take or leftover (B3) is a canonical rocket or star spec of the item it sits on; null when there is none or it is fine. */
    private static @Nullable String checkSpecified(String id, @Nullable String spec, String what) {
        if (spec == null) return null;
        FireworkSpecs.Parsed p = FireworkSpecs.parse(spec);
        if (p == null) return "a " + what + " has a spec that is not a canonical rocket or star spec";
        if (!id.equals(p.rocket() ? FireworkSpecs.ROCKET : FireworkSpecs.STAR)) return "a " + what + " of " + id + " has the spec of another item";
        return null;
    }

    private static final Pattern KEY = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,128}(#[0-9a-f]{1,16})?$");

    /** The key is an index key of this item: its id, then optionally {@code #} and a hash. */
    private static boolean keyOf(String id, @Nullable String key) {
        return key != null && KEY.matcher(key).matches() && (key.equals(id) || key.startsWith(id + "#"));
    }

    /**
     * The reason code for a trip or a gather that ended with this failure text ({@link CraftTrip} and the host's own words).
     * @param stuck     items are left in the grid with nowhere to go
     * @param cancelled the order was cancelled
     */
    public static String reasonOf(@Nullable String failure, boolean stuck, boolean cancelled) {
        if (cancelled) return CANCELLED;
        if (stuck) return GRID_STUCK;
        if (failure == null) return INTERRUPTED;
        if (failure.equals(CraftTrip.SPEC_MISMATCH)) return RECIPE_MISMATCH;
        if (failure.equals(CraftTrip.NOT_REACHED) || failure.equals(CraftTrip.NOT_OPENED) || failure.contains("another dimension")) return TABLE_BLOCKED;
        if (failure.contains("website's crafting table isn't one")) return TABLE_MISSING;
        if (failure.contains("no crafting table")) return NO_TABLE;
        if (failure.equals(CraftTrip.NO_ROOM) || failure.contains("carry room")) return INVENTORY_FULL;
        if (failure.startsWith(CraftTrip.MISSING) || failure.contains("not enough") || failure.contains("left (")) return INGREDIENTS_SHORT;
        if (failure.equals(CraftTrip.GRID)) return GRID_STUCK;
        if (failure.equals(CraftTrip.NO_RESULT) || failure.equals(CraftTrip.NOT_TAKEN) || failure.equals(CraftTrip.TOO_LONG) || failure.contains("too many trips")) return TIMEOUT;
        return INTERRUPTED;
    }

    /** The check of one furnace step (see the {@code check} above); null when it passes. */
    private static @Nullable String checkFurnaceStep(Order.CraftStep s, Set<String> can, @Nullable Smelting smelting) {
        if (!can.contains("smelt") || smelting == null) return "recipe " + s.recipe() + " smelts, which this bot can't do";
        Smelting.Recipe r = smelting.byId(s.recipe());
        if (r == null) return "smelt recipe " + s.recipe() + " is not in the extras";
        if (!r.out().equals(s.result()) || r.count() != s.count()) return "smelt recipe " + s.recipe() + " makes something else here";
        if (s.fills() < 1 || s.fills() > Smelting.MAX_BATCH) return "smelt recipe " + s.recipe() + " smelts " + s.fills() + " items in one step (at most " + Smelting.MAX_BATCH + ")";
        // One input kind and one fuel kind per step (mixed kinds are separate steps): a bot loads a step's whole input in one go after its fuel
        if (s.inputs().size() != 1) return "smelt recipe " + s.recipe() + " has " + s.inputs().size() + " input kinds in one step";
        if (s.fuel().size() > 1) return "smelt recipe " + s.recipe() + " names more than one fuel kind";
        long in = 0;
        for (Order.Qty q : s.inputs()) {
            if (q.count() < 1 || !plain(q.id())) return "smelt recipe " + s.recipe() + " has a bad input";
            if (!r.in().contains(q.id())) return "smelt recipe " + s.recipe() + " takes no " + q.id();
            in += q.count();
        }
        if (in != s.fills()) return "smelt recipe " + s.recipe() + " has " + in + " inputs for " + s.fills() + " items";
        if (s.fuel().isEmpty()) return "smelt recipe " + s.recipe() + " names no fuel";
        long ticks = 0;
        for (Order.Qty f : s.fuel()) {
            if (f.count() < 1 || !plain(f.id())) return "smelt recipe " + s.recipe() + " has a bad fuel";
            int t = smelting.ticks(f.id());
            if (t <= 0) return f.id() + " is not a fuel";
            if (f.count() > smelting.fuelStack(f.id())) return "the fuel of smelt recipe " + s.recipe() + " is more than a stack of " + f.id();
            ticks += (long) t * f.count();
        }
        if (ticks < smelting.ticksFor(s.fills())) return "the fuel of smelt recipe " + s.recipe() + " doesn't burn long enough";
        return null;
    }

    /** Some cell of the recipe accepts this item id. */
    public static boolean accepts(Recipes.Recipe r, String id) {
        for (List<String> cell : r.ingredients()) if (cell.contains(id)) return true;
        return false;
    }
}
