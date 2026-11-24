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
            default -> safe(reason);
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
        return check(craft, table, maxFills, extrasHash, can, smelting, maxSmelt, firework, null);
    }

    /**
     * As above, for a bot that can also make tipped arrows, suspicious stew, copies of banners, maps and written books, and shields with a banner (B4,
     * CRAFT-CONTRACT-v1.2.md 3, 9, 11.1 and 15). A block that uses a spec of one of these kinds passes only when the bot's {@code can} has the kind's feature
     * ({@code tipped}, {@code stew} or {@code copy}), {@code special} (its tables from its own extras) is not null, {@code needs} lists the feature and the block
     * names the bot's extras; then every B4 step's grid is the one the bot builds itself from the step's spec ({@link VariantSpecs#checkStep}, the remainder
     * included), every spec on a line, take, leftover or remainder is a canonical spec of its item, and, for a block with a step of B4, every step's inputs are in
     * hand when it is made: what the take holds, what the steps before it made, and (for a copy) what its own earlier crafts made, less what earlier steps used.
     * @param special the bot's B4 tables (from its extras), or null when it holds none
     */
    public static @Nullable String check(Order.@Nullable Craft craft, Recipes table, int maxFills, @Nullable String extrasHash, Set<String> can, @Nullable Smelting smelting,
                                         int maxSmelt, FireworkSpecs.@Nullable Tables firework, VariantSpecs.@Nullable Tables special) {
        return check(craft, table, maxFills, extrasHash, can, smelting, maxSmelt, firework, special, null);
    }

    /**
     * As above, for a bot that can also dye leather armour, leather horse armour and wolf armour (B5, CRAFT-CONTRACT-v1.2.md 3, 9, 11.1 and 15). A block that
     * uses a {@code dyed:} spec passes only when the bot's {@code can} has {@code dyed}, {@code dyed} (its armour colours and dyeable items, from its own extras)
     * can dye, {@code needs} lists {@code dyed} and the block names the bot's extras; then every dyeing step is checked by {@link DyedSpecs#checkStep} (its grid is
     * rebuilt from the spec and the bot's own armour colours, and the colour it mixes must be the step's), the second coat of a two-coat colour must come from
     * an earlier step ({@link DyedSpecs#checkChain}), a dyed spec on a line, take or leftover is canonical and on a dyeable item, and the lines are covered by
     * what the steps make less the first coats the later steps use up.
     * @param dyed the bot's B5 tables (from its extras), or null when it holds none
     */
    public static @Nullable String check(Order.@Nullable Craft craft, Recipes table, int maxFills, @Nullable String extrasHash, Set<String> can, @Nullable Smelting smelting,
                                         int maxSmelt, FireworkSpecs.@Nullable Tables firework, VariantSpecs.@Nullable Tables special, DyedSpecs.@Nullable Tables dyed) {
        String bad = checkRaw(craft, table, maxFills, extrasHash, can, smelting, maxSmelt, firework, special, dyed);
        // The words of a refusal are logged and said: they carry strings of the server's block, so they are cut to printable ASCII and a short length
        return bad == null ? null : safe(bad);
    }

    /** At most this many characters of a server's string are said or logged. */
    public static final int MAX_SAID = 80;

    /** The text with anything but printable ASCII turned into {@code ?} and cut to {@link #MAX_SAID} characters (server strings reach logs and chat). */
    public static String safe(@Nullable String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(text.length(), MAX_SAID + 3));
        for (int i = 0; i < text.length() && sb.length() < MAX_SAID; i++) {
            char c = text.charAt(i);
            sb.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        return text.length() > MAX_SAID ? sb + "..." : sb.toString();
    }

    private static @Nullable String checkRaw(Order.@Nullable Craft craft, Recipes table, int maxFills, @Nullable String extrasHash, Set<String> can, @Nullable Smelting smelting,
                                             int maxSmelt, FireworkSpecs.@Nullable Tables firework, VariantSpecs.@Nullable Tables special, DyedSpecs.@Nullable Tables dyed) {
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
                // The kinds the block's specs are of; a spec, key or grid of no known kind is B3's (it needs firework, as it always did)
                java.util.Set<String> used = craft.features();
                if (used.isEmpty()) used = java.util.Set.of("firework");
                for (String f : used) {
                    if (f.equals("firework")) {
                        if (!can.contains("firework") || firework == null) return "the order uses rockets or stars, which this bot can't make";
                        if (!craft.needs().contains("firework")) return "the order uses rockets or stars but doesn't say it needs fireworks";
                    }
                    else if (f.equals(DyedSpecs.FEATURE)) {
                        if (!can.contains(f) || dyed == null || !dyed.can()) return "the order uses " + describeFeature(f) + ", which this bot can't make";
                        if (!craft.needs().contains(f)) return "the order uses " + describeFeature(f) + " but doesn't say it needs " + f;
                    }
                    else {
                        if (!can.contains(f) || special == null) return "the order uses " + describeFeature(f) + ", which this bot can't make";
                        if (!craft.needs().contains(f)) return "the order uses " + describeFeature(f) + " but doesn't say it needs " + f;
                    }
                }
                if (craft.extras() == null) return "the order needs extras it doesn't name";
            }
        }
        if (!craft.recipes().equals(table.hash())) return "the order was planned with another recipe table";
        if (craft.steps().size() > Order.MAX_CRAFT_STEPS || craft.take().size() > Order.MAX_CRAFT_TAKE) return "too many steps or ingredients";
        long fills = 0, smelted = 0;
        for (Order.CraftStep s : craft.steps()) {
            if (s.special()) {
                // B3: a special step carries the exact grid of a rocket, star or fade; B4: of tipped arrows, stew, a copy or a shield; the bot builds it
                // again from the spec and its own extras
                boolean b4 = VariantSpecs.isRecipe(s.recipe());
                boolean b5 = DyedSpecs.isRecipe(s.recipe());
                String bad = b5 ? DyedSpecs.checkStep(s, dyed, can) : b4 ? VariantSpecs.checkStep(s, special, can) : FireworkSpecs.checkStep(s, firework);
                if (bad != null) return bad;
                if (!b4 && !b5 && !s.remainder().isEmpty()) return "recipe " + s.recipe() + " has a remainder, which it doesn't leave";
                fills += s.fills();
                continue;
            }
            if (s.spec() != null) return "recipe " + s.recipe() + " has a spec but no grid";
            if (!s.remainder().isEmpty()) return "recipe " + s.recipe() + " has a remainder but no grid";
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
            String bad = checkSpecified(t.id(), t.spec(), "take", dyed);
            if (bad != null) return bad;
            if (t.spec() != null && !keyOf(t.id(), t.key())) return "a take of " + t.id() + " with a spec has no index key of that item";
            if (t.spec() == null && t.key() != null) return "a take of " + t.id() + " names a key but no spec";
            // B4: a banner, map or book taken to be copied (or a banner for a shield) is taken by the exact key its spec names
            VariantSpecs.Spec v = VariantSpecs.parse(t.spec());
            if (v != null && v.kind().equals("copy") && !v.key().equals(t.key())) return "a take of " + t.id() + " names a key other than its copy spec's";
        }
        for (Order.Qty l : craft.leftovers()) {
            // B5: a first coat is never left over (after an interruption it is only handed back, in the result's returned list)
            if (l.spec() != null && l.spec().startsWith("dyed:")) return "a leftover has a dyed spec (a dyed piece is never left over)";
            String bad = checkSpecified(l.id(), l.spec(), "leftover", dyed);
            if (bad != null) return bad;
        }
        for (Order.CraftStep s : craft.steps()) {
            for (Order.Qty q : s.remainder()) {
                String bad = checkSpecified(q.id(), q.spec(), "remainder", dyed);
                if (bad != null) return bad;
            }
        }
        if (craft.hasB4Steps()) {
            String bad = DyedSpecs.checkChain(craft);
            if (bad == null) bad = checkInputsInHand(craft);
            if (bad == null) bad = checkTakeBound(craft);
            if (bad != null) return bad;
        }
        long total = 0;
        java.util.Map<String, Long> made = new java.util.HashMap<>(), taken = new java.util.HashMap<>();
        for (Order.CraftStep s : craft.steps()) made.merge(s.resultRef(), (long) s.count() * s.fills(), Long::sum);
        // B5: the items a second coat is put on were made by an earlier step and are not what the order delivers
        for (var e : DyedSpecs.coatsUsed(craft).entrySet()) made.merge(e.getKey(), -e.getValue(), Long::sum);
        for (Order.Qty t : craft.take()) taken.merge(t.stockRef(), (long) t.count(), Long::sum);
        for (Order.Qty l : craft.lines()) {
            if (l.count() < 1 || !plain(l.id())) return "a craft line key is not a plain item id";
            String bad = checkSpecified(l.id(), l.spec(), "craft line", dyed);
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

    /**
     * A spec on a line, take, leftover or remainder (B3, B4) is a canonical spec of a kind the bot knows (rocket, star, tipped, stew, copy or shield) that fits the
     * item it sits on: a line is of the variant itself, a take, leftover or remainder may also be a lingering potion with a tipped spec or a banner, map or book
     * with the copy spec of its own key. Null when there is none or it is fine.
     */
    private static @Nullable String checkSpecified(String id, @Nullable String spec, String what, DyedSpecs.@Nullable Tables dyed) {
        if (spec == null) return null;
        if (spec.startsWith("dyed:")) {
            // B5: a canonical colour on an item the bot's own extras say is dyeable (a first coat is never a line, a take or a leftover: only the steps name it)
            if (DyedSpecs.rgbOf(spec) < 0) return "a " + what + " has a spec that is not a canonical dyed spec";
            return DyedSpecs.fitsItem(spec, id, dyed) ? null : "a " + what + " of " + id + " has a dyed spec but is not dyeable by this bot's extras";
        }
        if (spec.startsWith("rocket:") || spec.startsWith("star:")) {
            FireworkSpecs.Parsed p = FireworkSpecs.parse(spec);
            if (p == null) return "a " + what + " has a spec that is not a canonical rocket or star spec";
            if (!id.equals(p.rocket() ? FireworkSpecs.ROCKET : FireworkSpecs.STAR)) return "a " + what + " of " + id + " has the spec of another item";
            return null;
        }
        VariantSpecs.Spec v = VariantSpecs.parse(spec);
        if (v == null) return "a " + what + " has a spec that is not a canonical rocket, star, tipped, stew, copy or shield spec";
        boolean fits = what.equals("craft line") ? VariantSpecs.fitsItem(v, id) : VariantSpecs.fitsEntry(v, id);
        return fits ? null : "a " + what + " of " + id + " has the spec of another item";
    }

    private static String describeFeature(String feature) {
        return switch (feature) {
            case "tipped" -> "tipped arrows";
            case "stew" -> "suspicious stew";
            case "copy" -> "copies of banners, maps or books, or shields";
            case "dyed" -> "dyed leather or wolf armour";
            default -> feature;
        };
    }

    /**
     * A block with a step of B4 takes nothing from the stash that its steps and lines can't use (T3 of the B4 review): of each item (by key for a spec'd one) the
     * take is at most what the steps use (inputs and fuel), what the craft lines ask for and what the block says is left over. Judged for B4 blocks only: B1 to
     * B3 blocks keep the rules they always had.
     */
    static @Nullable String checkTakeBound(Order.Craft craft) {
        java.util.Map<String, Long> use = new java.util.HashMap<>(), take = new java.util.LinkedHashMap<>();
        for (Order.CraftStep s : craft.steps()) {
            // B5: a dyed input is the first coat made by an earlier step, never stock: it justifies no take
            for (Order.Qty q : s.inputs()) if (!isCoat(q)) use.merge(q.stockRef(), (long) q.count(), Long::sum);
            for (Order.Qty q : s.fuel()) use.merge(q.stockRef(), (long) q.count(), Long::sum);
        }
        for (Order.Qty l : craft.lines()) use.merge(l.ref(), (long) l.count(), Long::sum);
        for (Order.Qty l : craft.leftovers()) {
            use.merge(l.ref(), (long) l.count(), Long::sum);
            if (!l.stockRef().equals(l.ref())) use.merge(l.stockRef(), (long) l.count(), Long::sum);
        }
        for (Order.Qty f : craft.forEnchant()) use.merge(f.ref(), (long) f.count(), Long::sum);
        for (Order.Qty t : craft.take()) take.merge(t.stockRef(), (long) t.count(), Long::sum);
        for (var e : take.entrySet()) {
            if (e.getValue() > use.getOrDefault(e.getKey(), 0L)) return "the block takes " + e.getValue() + " " + e.getKey() + " from the stash and uses at most " + use.getOrDefault(e.getKey(), 0L);
        }
        return null;
    }

    /**
     * The inputs of a block with a step of B4 are in hand when each step is made (CRAFT-CONTRACT-v1.2.md B4, "Chained map crafts"): the balance starts as the take, a
     * step's crafts are made one after the other, each takes its inputs (the step's inputs over its fills) from the balance, hands back its remainder (the
     * source banner or book) and adds what it makes (a map copy's source is a result of an earlier craft, of this step or of an earlier one). A craft that
     * finds an input missing makes the block unusable. Pure; the steps are in the order they are made.
     * @return null when every craft has what it needs, else why not
     */
    static @Nullable String checkInputsInHand(Order.Craft craft) {
        java.util.Map<String, Long> bal = new java.util.HashMap<>();
        for (Order.Qty t : craft.take()) bal.merge(t.stockRef(), (long) t.count(), Long::sum);
        int n = 0;
        for (Order.CraftStep s : craft.steps()) {
            n++;
            boolean copy = VariantSpecs.isCopy(s.recipe());
            int crafts = copy ? Math.max(1, s.fills()) : 1;
            long result = copy ? s.count() : (long) s.count() * s.fills();
            java.util.Map<String, Long> in = new java.util.LinkedHashMap<>(), back = new java.util.LinkedHashMap<>();
            for (Order.Qty q : s.inputs()) in.merge(isCoat(q) ? COAT + q.stockRef() : q.stockRef(), (long) q.count(), Long::sum);
            for (Order.Qty q : s.fuel()) in.merge(q.stockRef(), (long) q.count(), Long::sum);
            for (Order.Qty q : s.remainder()) back.merge(q.stockRef(), (long) q.count(), Long::sum);
            for (var e : in.entrySet()) if (e.getValue() % crafts != 0) return "step " + n + " (" + s.recipe() + ") has " + e.getValue() + " " + e.getKey() + " for " + crafts + " crafts";
            for (var e : back.entrySet()) if (e.getValue() % crafts != 0) return "step " + n + " (" + s.recipe() + ") hands back " + e.getValue() + " " + e.getKey() + " from " + crafts + " crafts";
            for (int i = 0; i < crafts; i++) {
                for (var e : in.entrySet()) {
                    long need = e.getValue() / crafts;
                    long have = bal.getOrDefault(e.getKey(), 0L);
                    if (have < need) return "step " + n + " (" + s.recipe() + ") has no " + e.getKey() + " for " + (copy ? "craft " + (i + 1) : "its crafts") + " (" + need + " wanted, " + have + " in hand)";
                    bal.put(e.getKey(), have - need);
                }
                for (var e : back.entrySet()) bal.merge(e.getKey(), e.getValue() / crafts, Long::sum);
                // B5: what a dyeing step makes is kept apart from stock of the same colour: only it can be the first coat of a later step
                bal.merge(DyedSpecs.isRecipe(s.recipe()) ? COAT + s.resultRef() : s.resultRef(), result, Long::sum);
            }
        }
        return null;
    }

    /** The balance key of the first coats (B5): items made by dyeing steps, never stock. */
    private static final String COAT = "coat:";

    /** An input with a dyed spec is the first coat of a second coat: the result of an earlier step. */
    static boolean isCoat(Order.Qty q) {
        return q.spec() != null && q.spec().startsWith("dyed:");
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
