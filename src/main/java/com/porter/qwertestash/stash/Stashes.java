package com.porter.qwertestash.stash;

import com.aquarius.cache.data.inventory.Container;
import com.porter.qwertestash.stash.Stash.Box;
import org.jspecify.annotations.Nullable;

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

    /** Goes up whenever a container's contents or marks change, so stay-on sorting knows to look again. */
    public static volatile int version;

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
            stash = Stash.load(name);
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
        triedKey = Stash.currentServer() + "/" + CONFIG.stashName;
        return stash;
    }

    public static synchronized void forget() {
        Stash.delete(CONFIG.stashName);
        stash = null;
        triedKey = null;
        version++;
    }

    public static synchronized void reset() {
        if (stash != null && stash.isDirty()) stash.save();
        stash = null;
        triedKey = null;
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
