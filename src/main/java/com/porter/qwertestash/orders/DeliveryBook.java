package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The delivery storage (1.9.0): the delivery container set with {@code stash delivery} and the double chests the bot put up
 * above it when "auto expand delivery storage" is on. Pure bookkeeping, no game types: which chests there are, which are
 * full, which a player may have emptied (a lid seen open and closed again), and what the order should do about it.
 * <ul>
 *     <li>Chests are filled lowest first; a full one is passed over until it is known to have room again.</li>
 *     <li>A full chest that another player opened (the lid event says more viewers than the bot) and closed again is flagged
 *     {@code check}: the bot opens it once, and if it has room it is usable again. Where the lid could not be seen (the bot
 *     was too far away) a full chest is looked at again every {@link #RECHECK_MS} while an order waits for space.</li>
 *     <li>The entries are saved with the stash (plain fields, nothing transient but the lid counters).</li>
 * </ul>
 * All of it is for the bot tick thread, except {@link #onViewers}, which is called from the network thread and is safe to
 * (it only sets flags and counters).
 */
public final class DeliveryBook {
    /** A full chest is looked at again this often while an order waits for space (lids out of sight are never seen). */
    public static final long RECHECK_MS = 2 * 60 * 1000L;

    public record Pos(int x, int y, int z) {
    }

    /** One chest (a single, or a double with its two halves). Plain fields so the stash file can hold it. */
    public static final class Entry {
        /** x, y, z of each half, flattened: 3 numbers for a single chest, 6 for a double. */
        public int[] at = new int[0];
        /** The delivery container itself, the one set with `stash delivery`. */
        public boolean primary;
        /** The last look found no room for a shulker. */
        public volatile boolean full;
        /** A player opened it and closed it again since it was found full: to be looked at. */
        public volatile boolean check;
        /** When it was last looked at (ms since 1970), 0 if never. */
        public long checkedAt;
        /** A player has been seen with it open (cleared when they close it). */
        public transient volatile boolean opened;

        public Entry() {
        }

        public Entry(List<Pos> parts, boolean primary) {
            this.primary = primary;
            this.at = flatten(parts);
        }

        public List<Pos> parts() {
            List<Pos> out = new ArrayList<>();
            for (int i = 0; i + 2 < at.length; i += 3) out.add(new Pos(at[i], at[i + 1], at[i + 2]));
            return out;
        }

        public boolean has(Pos p) {
            for (int i = 0; i + 2 < at.length; i += 3) if (at[i] == p.x() && at[i + 1] == p.y() && at[i + 2] == p.z()) return true;
            return false;
        }

        /** The lowest block of it. */
        public int y() {
            int y = Integer.MAX_VALUE;
            for (int i = 1; i < at.length; i += 3) y = Math.min(y, at[i]);
            return y == Integer.MAX_VALUE ? 0 : y;
        }

        public Pos first() {
            return new Pos(at[0], at[1], at[2]);
        }
    }

    private static int[] flatten(List<Pos> parts) {
        int[] out = new int[parts.size() * 3];
        for (int i = 0; i < parts.size(); i++) {
            out[i * 3] = parts.get(i).x();
            out[i * 3 + 1] = parts.get(i).y();
            out[i * 3 + 2] = parts.get(i).z();
        }
        return out;
    }

    private final List<Entry> entries;

    public DeliveryBook() {
        this(new CopyOnWriteArrayList<>());
    }

    /** Over a list that lives elsewhere (the stash's), changed in place. */
    public DeliveryBook(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> raw() {
        return entries;
    }

    private static final Comparator<Entry> LOW_FIRST = Comparator.<Entry>comparingInt(Entry::y)
        .thenComparingInt(e -> e.at.length > 0 ? e.at[0] : 0).thenComparingInt(e -> e.at.length > 2 ? e.at[2] : 0);

    /** Lowest first. */
    public List<Entry> sorted() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e != null && e.at != null && e.at.length >= 3) out.add(e);
        out.sort(LOW_FIRST);
        return out;
    }

    public int chests() {
        return sorted().size();
    }

    public int fullCount() {
        int n = 0;
        for (Entry e : sorted()) if (e.full) n++;
        return n;
    }

    public @Nullable Entry find(Pos p) {
        for (Entry e : entries) if (e != null && e.has(p)) return e;
        return null;
    }

    public @Nullable Entry primary() {
        for (Entry e : sorted()) if (e.primary) return e;
        return null;
    }

    /**
     * Keeps the book in step with the delivery container setting. @param parts the halves of the container now (null: none is
     * set, the book empties). A different container than the one remembered forgets the chests put up above the old one; the
     * same container (perhaps grown into a double chest, or just read again) keeps them and what is known about them.
     */
    public void syncPrimary(@Nullable List<Pos> parts) {
        if (parts == null || parts.isEmpty()) {
            entries.clear();
            return;
        }
        Entry old = primary();
        if (old != null) {
            boolean same = false;
            for (Pos p : parts) if (old.has(p)) same = true;
            if (same) {
                if (old.parts().size() != parts.size() || !old.parts().containsAll(parts)) old.at = flatten(parts);
                return;
            }
            entries.clear();
        }
        entries.add(new Entry(parts, true));
    }

    /** A chest put up for the storage. */
    public Entry add(List<Pos> parts) {
        Entry e = new Entry(parts, false);
        entries.add(e);
        return e;
    }

    /**
     * A chest that stands now joins the storage. When any block of it is already in an entry (the first half of a double chest was
     * added alone, and the second half made it a double), it is merged into that entry; so a double chest is never two entries.
     */
    public Entry addOrMerge(List<Pos> parts) {
        for (Entry e : sorted()) {
            boolean overlap = false;
            for (Pos p : parts) if (e.has(p)) overlap = true;
            if (!overlap) continue;
            java.util.LinkedHashSet<Pos> all = new java.util.LinkedHashSet<>(e.parts());
            all.addAll(parts);
            if (all.size() != e.parts().size()) e.at = flatten(new ArrayList<>(all));
            dedupe();
            return e;
        }
        return add(parts);
    }

    /** Entries that share a block are one chest: merged (full if either was, flagged if either was). @return whether anything changed */
    public boolean dedupe() {
        boolean changed = false;
        List<Entry> all = sorted();
        for (int i = 0; i < all.size(); i++) {
            Entry a = all.get(i);
            if (!entries.contains(a)) continue;
            for (int j = i + 1; j < all.size(); j++) {
                Entry b = all.get(j);
                if (!entries.contains(b)) continue;
                boolean overlap = false;
                for (Pos p : b.parts()) if (a.has(p)) overlap = true;
                if (!overlap) continue;
                java.util.LinkedHashSet<Pos> union = new java.util.LinkedHashSet<>(a.parts());
                union.addAll(b.parts());
                a.at = flatten(new ArrayList<>(union));
                a.full |= b.full;
                a.check |= b.check;
                a.primary |= b.primary;
                a.checkedAt = Math.max(a.checkedAt, b.checkedAt);
                entries.remove(b);
                changed = true;
            }
        }
        return changed;
    }

    public void remove(Entry e) {
        entries.remove(e);
    }

    /** The row on top: every chest as high as the highest. */
    public List<Entry> topRow() {
        List<Entry> all = sorted();
        if (all.isEmpty()) return List.of();
        int top = all.get(all.size() - 1).y();
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) if (e.y() == top) out.add(e);
        return out;
    }

    // What is known about each chest

    public void markFull(Entry e, long now) {
        e.full = true;
        e.check = false;
        e.checkedAt = now;
    }

    public void markRoom(Entry e, long now) {
        e.full = false;
        e.check = false;
        e.opened = false;
        e.checkedAt = now;
    }

    /** The lowest chest not known to be full, or null. */
    public @Nullable Entry firstUsable() {
        for (Entry e : sorted()) if (!e.full) return e;
        return null;
    }

    /**
     * A lid event for a block of a chest: its viewer count changed. {@code botViewing}: the bot has this chest open (or just
     * closed it), so one of the viewers is the bot. A viewer that is not the bot marks the chest as opened by a player; once
     * nobody has it open any more, a full chest is flagged to be looked at.
     * @return whether the chest is one of the storage's
     */
    public boolean onViewers(Pos at, int viewers, boolean botViewing) {
        Entry e = find(at);
        if (e == null) return false;
        int others = viewers - (botViewing ? 1 : 0);
        if (others > 0) e.opened = true;
        else if (viewers <= 0 && e.opened) {
            e.opened = false;
            if (e.full) e.check = true;
        }
        return true;
    }

    /** Lowest chest flagged for a look, or null. */
    public @Nullable Entry flagged() {
        for (Entry e : sorted()) if (e.full && e.check) return e;
        return null;
    }

    /**
     * The lowest full chest not looked at for {@code every} ms, or null. At most one look per {@code every} across the whole storage:
     * nothing is due while any chest was looked at (or found full) within that time, so the looks do not grow with the chest count.
     */
    public @Nullable Entry stale(long now, long every) {
        long latest = 0;
        for (Entry e : sorted()) latest = Math.max(latest, e.checkedAt);
        if (now - latest < every) return null;
        for (Entry e : sorted()) if (e.full && now - e.checkedAt >= every) return e;
        return null;
    }

    /**
     * What the storage can take now.
     * @param autoExpand     the setting
     * @param expandBlocker  why no chest can be put up above the top row now (null: one can)
     * @param recheckEvery   how often a full chest is looked at while waiting (ms; 0 or less: never)
     */
    public DeliveryRoom room(boolean autoExpand, @Nullable String expandBlocker, long now, long recheckEvery) {
        List<Entry> all = sorted();
        if (all.isEmpty()) return DeliveryRoom.ROOM;
        Entry usable = firstUsable();
        Entry flagged = flagged();
        // A chest a player emptied, lower than the one in use, is looked at first
        if (flagged != null && (usable == null || LOW_FIRST.compare(flagged, usable) < 0)) return DeliveryRoom.recheck(flagged.first());
        if (usable != null) return DeliveryRoom.ROOM;
        if (autoExpand && expandBlocker == null) return DeliveryRoom.EXPAND;
        if (recheckEvery > 0) {
            Entry due = stale(now, recheckEvery);
            if (due != null) return DeliveryRoom.recheck(due.first());
        }
        return DeliveryRoom.waitFor(waitReason(autoExpand, expandBlocker, all.size()));
    }

    /** "delivery storage full, auto expand is off" or "delivery storage full: can't place higher: ...". */
    public static String waitReason(boolean autoExpand, @Nullable String expandBlocker, int chests) {
        if (!autoExpand) return "delivery storage full (" + chests + (chests == 1 ? " chest" : " chests") + "), auto expand is off";
        return "delivery storage full: can't place higher: " + (expandBlocker == null ? "not known yet" : expandBlocker);
    }
}
