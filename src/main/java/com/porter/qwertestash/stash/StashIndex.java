package com.porter.qwertestash.stash;

import com.porter.qwertestash.QwerteStashPlugin;
import com.porter.qwertestash.stash.Stash.Box;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A readable list of what is in the stash and where, kept next to the stash file as {@code <server>_<stash>.index.txt}.
 * Written off the sorter's thread, at most every 30 seconds per stash, whenever the stash is saved; written to a
 * temporary file and moved into place, so a reader never sees half of it. Everything is worked out from a snapshot
 * taken when the stash is saved, never from the live stash, which the sorter changes under any other thread.
 */
public final class StashIndex {
    private static final long MIN_GAP_MS = 30_000;
    // The thread goes away when idle, so nothing of this plugin is kept alive after it is unloaded
    private static final ScheduledThreadPoolExecutor POOL = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "qwerte-stash-index");
        thread.setDaemon(true);
        return thread;
    });
    static {
        POOL.setKeepAliveTime(10, TimeUnit.SECONDS);
        POOL.allowCoreThreadTimeOut(true);
        POOL.setRemoveOnCancelPolicy(true);
    }

    // Access under the StashIndex class lock. An old writer cannot publish after a forget.
    private static final Map<Path, Pending> pending = new HashMap<>();
    private static final class Pending {
        Snapshot latest;
        ScheduledFuture<?> task;
        long lastStarted;
        long sequence;
    }

    private StashIndex() {
    }

    private record BoxSnap(int x, int y, int z, List<String> labels, Item @Nullable [] contents, boolean excluded, boolean dropOff) {
    }

    private record Snapshot(String name, String server, Path path, List<BoxSnap> boxes) {
    }

    /**
     * What the stash holds right now, copied: the contents arrays are only ever replaced and never changed in place, so
     * keeping the array is enough.
     */
    private static Snapshot snapshot(Stash stash) {
        List<BoxSnap> boxes = new ArrayList<>();
        for (Box box : stash.boxes) {
            if (box.delivery || !stash.inScope(box)) continue;
            List<String> labels;
            try {
                labels = List.copyOf(new ArrayList<>(box.labels));
            } catch (RuntimeException e) {
                labels = List.of();
            }
            boxes.add(new BoxSnap(box.pos.x(), box.pos.y(), box.pos.z(), labels, box.contents, box.excluded, box.dropOff));
        }
        return new Snapshot(stash.name, stash.server, stash.indexPath(), boxes);
    }

    /**
     * The stash changed: the index is rewritten soon, but not more than once in half a minute.
     */
    public static void touch(Stash stash) {
        Snapshot snap;
        try {
            snap = snapshot(stash);
        } catch (RuntimeException e) {
            QwerteStashPlugin.LOG.warn("Could not snapshot stash index for {}", stash.name, e);
            return;
        }
        schedule(snap, false);
    }

    public static void writeNow(Stash stash) {
        schedule(snapshot(stash), true);
    }

    private static synchronized void schedule(Snapshot snap, boolean immediate) {
        Path path = snap.path();
        Pending state = pending.computeIfAbsent(path, ignored -> new Pending());
        state.latest = snap;
        if (state.task != null) {
            if (!immediate || !state.task.cancel(false)) return;
        }
        long delay = immediate ? 0 : Math.max(0, MIN_GAP_MS - (System.currentTimeMillis() - state.lastStarted));
        long sequence = ++state.sequence;
        state.task = POOL.schedule(() -> write(path, state, sequence), delay, TimeUnit.MILLISECONDS);
    }

    static synchronized void forget(Path path) throws IOException {
        Files.deleteIfExists(path);
        Pending state = pending.remove(path);
        if (state != null && state.task != null) state.task.cancel(false);
    }

    private static void write(Path path, Pending state, long sequence) {
        Snapshot snap;
        synchronized (StashIndex.class) {
            if (pending.get(path) != state || state.sequence != sequence || state.latest == null) return;
            snap = state.latest;
            state.latest = null;
            state.task = null;
            state.lastStarted = System.currentTimeMillis();
        }
        try {
            Path temp = SnapshotFiles.stage(path, render(snap, build(snap)));
            try {
                synchronized (StashIndex.class) {
                    if (pending.get(path) == state) SnapshotFiles.replace(temp, path);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException | RuntimeException e) {
            QwerteStashPlugin.LOG.warn("Couldn't write the stash index for {}", snap.name(), e);
        }
    }

    // What is in the stash

    private static final class Agg {
        final Item sample;
        long items;
        int stacks;
        long inShulkers;
        // Where, as "chest" or "in shulkers in chest": stacks and items
        final Map<String, long[]> where = new LinkedHashMap<>();

        Agg(Item sample) {
            this.sample = sample;
        }
    }

    private record Built(List<String> summary, List<Agg> items) {
    }

    private static String key(Item item) {
        return item.id + (item.typeData != null ? "|" + item.typeData : "");
    }

    private static String detail(Item item) {
        if (item.typeData == null) return "";
        if (item.potion != null) return " [" + item.potion + "]";
        if (item.id.equals("enchanted_book") && !item.enchants.isEmpty()) return " [" + String.join(", ", item.enchants) + "]";
        String text = item.typeData;
        return " [" + (text.length() > 48 ? text.substring(0, 48) + "..." : text) + "]";
    }

    private static String where(BoxSnap box) {
        String sign = box.labels().isEmpty() ? "" : " \"" + box.labels().getFirst() + "\"";
        String flag = box.dropOff() ? " (drop-off)" : box.excluded() ? " (excluded)" : "";
        return "[" + box.x() + ", " + box.y() + ", " + box.z() + "]" + sign + flag;
    }

    private static void add(Map<String, Agg> all, Item item, String location, boolean inShulker) {
        Agg agg = all.computeIfAbsent(key(item), k -> new Agg(item));
        agg.items += item.count;
        agg.stacks++;
        if (inShulker) agg.inShulkers += item.count;
        long[] there = agg.where.computeIfAbsent(location, l -> new long[2]);
        there[0]++;
        there[1] += item.count;
    }

    private static Built build(Snapshot snap) {
        Map<String, Agg> all = new LinkedHashMap<>();
        int known = 0, excluded = 0, dropOffs = 0, slots = 0, used = 0;
        int filled = 0, empty = 0, named = 0;
        // Full stacks by kind, for the estimate of what compacting would free
        Map<String, int[]> fulls = new LinkedHashMap<>();

        for (BoxSnap box : snap.boxes()) {
            if (box.excluded()) excluded++;
            if (box.dropOff()) dropOffs++;
            Item[] contents = box.contents();
            if (contents == null) continue;
            known++;
            slots += contents.length;
            String here = where(box);

            for (Item item : contents) {
                if (item == null) continue;
                used++;
                add(all, item, here, false);
                if (item.isShulker()) {
                    if (item.customName) named++;
                    else if (item.contents.isEmpty()) empty++;
                    else filled++;
                    for (Item inside : item.contents) add(all, inside, "in shulkers in " + here, true);
                    continue;
                }
                String bulk = item.bulkKey();
                if (bulk != null && item.isFullStack() && !box.excluded() && !box.dropOff()) {
                    fulls.computeIfAbsent(bulk, k -> new int[] {0, item.maxStack == 1 ? 1 : 0})[0]++;
                }
            }
        }

        int freeable = 0;
        for (int[] full : fulls.values()) {
            boolean single = full[1] == 1;
            if (single) freeable += full[0] >= 2 ? full[0] - (full[0] + 26) / 27 : 0;
            else freeable += full[0] / 27 * 26;
        }

        List<String> summary = new ArrayList<>();
        summary.add(snap.boxes().size() + " containers (" + known + " read, " + excluded + " excluded, " + dropOffs + " drop-off)");
        summary.add(used + " of " + slots + " slots used, " + (slots - used) + " free");
        summary.add("Shulkers: " + filled + " filled, " + empty + " empty, " + named + " named");
        summary.add("About " + freeable + " slots could be freed by compacting");

        List<Agg> items = new ArrayList<>(all.values());
        items.sort(Comparator.<Agg>comparingLong(a -> -a.items).thenComparing(a -> a.sample.name));
        return new Built(summary, items);
    }

    private static String title(Agg agg) {
        return agg.sample.name + " (" + agg.sample.id + ")" + detail(agg.sample);
    }

    private static String render(Snapshot snap, Built built) {
        StringBuilder out = new StringBuilder();
        out.append("Stash \"").append(snap.name()).append("\" on ").append(snap.server()).append(", updated ")
            .append(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now())).append('\n');
        for (String line : built.summary()) out.append(line).append('\n');
        out.append('\n').append(built.items().size()).append(" kinds of item, most first. Items inside shulkers are counted too.\n\n");

        for (Agg agg : built.items()) {
            out.append(title(agg)).append(": ").append(agg.items).append(" in ").append(agg.stacks).append(" stacks");
            if (agg.inShulkers > 0) out.append(", ").append(agg.inShulkers).append(" inside shulkers");
            out.append('\n');
            agg.where.entrySet().stream()
                .sorted(Map.Entry.<String, long[]>comparingByValue(Comparator.comparingLong(v -> -v[1])))
                .forEach(e -> out.append("    ").append(e.getKey()).append(": ").append(e.getValue()[1]).append(" in ")
                    .append(e.getValue()[0]).append(e.getValue()[0] == 1 ? " stack\n" : " stacks\n"));
        }
        return out.toString();
    }

    /**
     * For the command: the summary, where the index file is, and the {@code top} kinds with the most items.
     */
    public static String report(Stash stash, int top) {
        try {
            Snapshot snap = snapshot(stash);
            Built built = build(snap);
            StringBuilder out = new StringBuilder();
            for (String line : built.summary()) out.append(line).append('\n');
            out.append("Index file: ").append(snap.path()).append("\n\nMost items:\n");
            int n = 0;
            for (Agg agg : built.items()) {
                if (n++ >= top) break;
                out.append(title(agg)).append(": ").append(agg.items).append(" in ").append(agg.stacks).append(" stacks\n");
            }
            String text = out.toString().stripTrailing();
            return text.length() > 1800 ? text.substring(0, 1800) + "..." : text;
        } catch (RuntimeException e) {
            QwerteStashPlugin.LOG.warn("Couldn't work out the stash index for {}", stash.name, e);
            return "Couldn't read the stash just now, try again in a moment.";
        }
    }
}
