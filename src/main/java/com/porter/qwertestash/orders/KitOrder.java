package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwertestash.stash.KitTemplate;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;

/**
 * The kit block of a stored order (KIT-CONTRACT v2, revisions 3 and 4, "Kit orders"): a snapshot of the templates ordered, how many copies were ready
 * and how many are to be built, and the budget of items that may be taken ({@code take}, by match key). Plain data; nothing here touches the world.
 */
public final class KitOrder {
    private KitOrder() {
    }

    /** The most lines and take entries a kit block may have. */
    public static final int MAX_LINES = 200, MAX_TAKE = 2000, MAX_COUNT = 54;

    /** The reason codes of {@code result.kit.short[].reason}. */
    public static final String ITEMS_SHORT = "items_short", NO_SHULKER = "no_shulker", INVENTORY_FULL = "inventory_full", TEMPLATE_MISMATCH = "template_mismatch",
        INTERRUPTED = "interrupted", TIMEOUT = "timeout", CANCELLED = "cancelled", CAP = "cap";

    private static final Pattern MATCH = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,128}(#([0-9a-f]{8,40}|\\*))?$");

    /** One ordered template: {@code count} copies wanted, {@code ready} complete copies the server counted in the stash, {@code build} to make. */
    public record Line(KitTemplate template, int count, int ready, int build) {
    }

    /** An item the order may take: a match key (an exact one, or {@code <id>#*}) and how many. */
    public record Take(String match, int count) {
    }

    /** @param malformed the block is there but not readable (every kit is then short: template_mismatch) */
    public record Snapshot(int v, List<Line> lines, List<Take> take, boolean malformed) {
        public static Snapshot unreadable() {
            return new Snapshot(0, List.of(), List.of(), true);
        }

        /** The line for a template id, or null. */
        public @Nullable Line line(String templateId) {
            for (Line l : lines) if (l.template().id().equals(templateId)) return l;
            return null;
        }

        /** Kits to deliver in all. */
        public int kits() {
            int n = 0;
            for (Line l : lines) n += l.count();
            return n;
        }
    }

    /** The block: null for none (absent or null), {@link Snapshot#unreadable()} for one that is there but not what the contract says. */
    public static @Nullable Snapshot parse(@Nullable JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        try {
            if (!e.isJsonObject()) return Snapshot.unreadable();
            JsonObject o = e.getAsJsonObject();
            int v = o.get("v").getAsInt();
            JsonArray la = o.getAsJsonArray("lines"), ta = o.getAsJsonArray("take");
            if (la.size() > MAX_LINES || ta.size() > MAX_TAKE) return Snapshot.unreadable();
            List<Line> lines = new ArrayList<>();
            for (JsonElement x : la) {
                JsonObject lo = x.getAsJsonObject();
                int count = lo.get("count").getAsInt(), ready = lo.get("ready").getAsInt(), build = lo.get("build").getAsInt();
                if (count < 1 || count > MAX_COUNT || ready < 0 || build < 0 || ready + build < count) return Snapshot.unreadable();
                lines.add(new Line(KitTemplate.parse(lo.get("template")), count, ready, build));
            }
            List<Take> take = new ArrayList<>();
            for (JsonElement x : ta) {
                JsonObject to = x.getAsJsonObject();
                String match = to.get("match").getAsString();
                int count = to.get("count").getAsInt();
                if (!MATCH.matcher(match).matches() || count < 1) return Snapshot.unreadable();
                take.add(new Take(match, count));
            }
            // A take entry is a match key of some slot of the templates in the block (an exact one, or <id>#*); anything else is not a kit order's
            java.util.Set<String> known = new java.util.HashSet<>();
            for (Line l : lines) for (KitTemplate.Slot sl : l.template().slots()) if (sl != null) known.add(sl.match());
            for (Take t : take) if (!known.contains(t.match())) return Snapshot.unreadable();
            return new Snapshot(v, lines, take, false);
        } catch (RuntimeException | KitTemplate.Invalid ex) {
            return Snapshot.unreadable();
        }
    }

    /**
     * The check before any click (step 1): a readable snapshot of version 1 whose templates have 27 slots, at least two kinds and counts within the
     * stack size of their item. @return null when it is fine, else why not (every kit is then short with {@link #TEMPLATE_MISMATCH})
     */
    public static @Nullable String check(@Nullable Snapshot s, ToIntFunction<String> maxStack) {
        if (s == null) return null;
        if (s.malformed() || s.v() != 1) return "the kit block is not readable";
        for (Line l : s.lines()) {
            if (l.template().slots().size() != KitTemplate.SLOTS) return "a template has no 27 slots";
            for (KitTemplate.Slot slot : l.template().slots()) {
                if (slot == null) continue;
                if (slot.count() > Math.max(1, maxStack.applyAsInt(slot.base()))) return "a slot holds more than a stack";
            }
        }
        return null;
    }
}
