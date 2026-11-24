package com.porter.qwertestash.stash;

import com.aquarius.cache.data.inventory.Container;
import com.porter.qwertestash.stash.Stash.Box;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;

/**
 * The stash named in the config, shared by the tracker, the sorter and the command. Loaded from disk the first time
 * it's needed on a server.
 */
public final class Stashes {
    private Stashes() {
    }

    private static @Nullable Stash stash;
    private static @Nullable String triedKey;
    /** The file on disk for {@link #triedKey} could not be read and not be copied aside: a new stash made for it must not overwrite it (see {@link Stash#protectExistingFile}). */
    private static boolean protectFile;

    /** Goes up whenever a container's contents or marks change, so stay-on sorting knows to look again. */
    public static volatile int version;

    /** Goes up when the perimeter changes or everything is to be read again, so the sorter explores and looks afresh. */
    public static volatile int exploreVersion;

    /**
     * The stash named in the config, or null if there isn't one saved on this server yet.
     */
    public static synchronized @Nullable Stash current() {
        String server = Stash.currentServer();
        String name = CONFIG.stashName;
        if (stash != null && stash.name.equals(name) && stash.server.equals(server)) return stash;

        // Only try the disk once per name and server, a missing stash isn't looked for again every tick
        String key = server + "/" + name;
        if (!key.equals(triedKey)) {
            triedKey = key;
            Stash.Loaded loaded;
            try {
                loaded = Stash.load(name);
            } catch (RuntimeException e) {
                // Never thrown out of the tick: the stash is left as not loaded, and the file is not to be overwritten
                loaded = new Stash.Loaded(null, true);
            }
            stash = loaded.stash();
            protectFile = loaded.protect();
        }
        else stash = null;

        return stash;
    }

    /**
     * The current stash, starting a new empty one if there isn't one yet.
     */
    public static synchronized Stash currentOrNew() {
        Stash existing = current();
        if (existing != null) return existing;
        stash = new Stash(CONFIG.stashName, Stash.currentServer());
        if (protectFile) {
            // The file on disk is kept: this stash stays dirty and saves nothing until a copy of the file exists (retried with a growing wait)
            stash.protectExistingFile();
            protectFile = false;
        }
        triedKey = Stash.currentServer() + "/" + CONFIG.stashName;
        return stash;
    }

    /**
     * Deletes the saved stash and its index file. Nothing is forgotten in memory when a file could not be deleted: the caller reports it.
     * @throws IOException when the stash file or its index could not be deleted
     */
    public static synchronized void forget() throws IOException {
        Stash.delete(CONFIG.stashName);
        stash = null;
        triedKey = null;
        protectFile = false;
        version++;
    }

    public static synchronized void reset() {
        // The last save before the stash is let go: a protected stash tries to release its file now, whatever the backoff
        if (stash != null && stash.isDirty()) stash.save(true);
        stash = null;
        triedKey = null;
        protectFile = false;
    }

    // Reading open containers

    public static int slots(Container c) {
        return Math.max(0, c.getSize() - 36);
    }

    /**
     * Whether what's in the open container differs from what we have saved for the box.
     */
    public static boolean changed(Box box, Container c) {
        int size = slots(c);
        if (!box.known() || box.contents.length != size) return true;
        for (int i = 0; i < size; i++) {
            Item saved = box.contents[i];
            var now = c.getItemStack(i);
            boolean empty = now == Container.EMPTY_STACK;
            if (saved == null != empty) return true;
            if (saved == null) continue;
            if (saved.raw != null ? !Objects.equals(saved.raw, now) : !saved.sameAs(Objects.requireNonNull(Item.of(now))) || saved.count != now.getAmount()) return true;
        }
        return false;
    }

    /**
     * Saves what's in the open container as the box's contents.
     */
    public static void read(Stash stash, Box box, Container c) {
        box.stale = false;
        box.readAt = System.currentTimeMillis();
        if (changed(box, c)) {
            stash.markDirty();
            version++;
        }

        int size = slots(c);
        Item[] contents = new Item[size];
        for (int i = 0; i < size; i++) contents[i] = Item.of(c.getItemStack(i));
        box.contents = contents;
    }
}
