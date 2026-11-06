package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import com.porter.qwertestash.orders.OrderBlocker;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * When a container that looks broken may really be forgotten. Boxes store no dimension, and the proxy's world is whatever
 * it was sent last: after a login or a dimension change its chunk cache is cleared (CacheResetType.LOGIN / RESPAWN) and
 * filled again from the new world, and with no world at all it says "overworld" (World.getCurrentDimension's fallback). A
 * queue or limbo world, or the Nether at the stash's x/z, is all air where the stash stands. So a box is only dropped when:
 * <ol>
 * <li>the stash's dimension is known (recorded on a container read) and is the one the bot is in;</li>
 * <li>this world (since the last login or dimension change) has shown it is the stash's: a container of it was read, or one
 * of its containers is seen standing;</li>
 * <li>this world has been in for {@link #SETTLE_MS}, and the box looked gone on two checks {@link #CONFIRM_MS} apart.</li>
 * </ol>
 * Plain logic, fed the time and the dimension, so it can be tested without a world.
 */
public final class GoneWatch {
    public static final long SETTLE_MS = 30_000, CONFIRM_MS = 5_000;

    private @Nullable String world;
    private long worldSince = Long.MIN_VALUE / 2;
    private boolean readHere;
    private final Map<BlockPos, Long> seenGone = new HashMap<>();

    /** A login (or the sorter starting over): nothing seen before counts. */
    public void reset(long now) {
        world = null;
        worldSince = now;
        readHere = false;
        seenGone.clear();
    }

    /** The dimension the bot is in now; a change starts the world over. */
    public void observe(@Nullable String dimension, long now) {
        if (world != null && OrderBlocker.sameDimension(world, dimension)) return;
        if (world == null && dimension == null) return;
        world = dimension;
        worldSince = now;
        readHere = false;
        seenGone.clear();
    }

    /** A container of the stash was read in this world. */
    public void read(@Nullable String dimension, long now) {
        observe(dimension, now);
        if (world != null) readHere = true;
    }

    /**
     * Why nothing may be dropped now, or null when boxes may be.
     * @param stashDimension the dimension recorded for the stash (null: not known yet)
     * @param stillThere     how many of its containers (not the delivery container) are seen standing now
     */
    public @Nullable String blocker(@Nullable String stashDimension, @Nullable String current, int stillThere, long now) {
        observe(current, now);
        if (stashDimension == null) return "the stash's dimension isn't known yet";
        if (current == null || !OrderBlocker.sameDimension(stashDimension, current)) return "the bot isn't in the stash's dimension";
        if (now - worldSince < SETTLE_MS) return "this world was only just joined";
        if (!readHere && stillThere == 0) return "nothing shows this is the stash's world yet";
        return null;
    }

    /**
     * The two-check rule: of the boxes that look gone now, the ones that already looked gone at least {@link #CONFIRM_MS}
     * ago. The others are remembered for the next check; boxes no longer looking gone are forgotten.
     */
    public List<Stash.Box> confirmed(List<Stash.Box> lookGone, long now) {
        List<Stash.Box> out = new ArrayList<>();
        Set<BlockPos> still = new HashSet<>();
        for (Stash.Box b : lookGone) {
            still.add(b.pos);
            Long first = seenGone.putIfAbsent(b.pos, now);
            if (first != null && now - first >= CONFIRM_MS) out.add(b);
        }
        seenGone.keySet().retainAll(still);
        return out;
    }

    /** Forgets a box that was dropped or rebuilt. */
    public void forget(BlockPos pos) {
        seenGone.remove(pos);
    }

    /** One box's two-check rule (a job's own target), without forgetting the others. */
    public boolean confirmedOne(Stash.Box b, long now) {
        Long first = seenGone.putIfAbsent(b.pos, now);
        return first != null && now - first >= CONFIRM_MS;
    }
}
