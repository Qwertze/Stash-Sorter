package com.porter.qwertestash.module;

import com.aquarius.cache.data.chunk.Chunk;
import com.aquarius.event.client.ClientBotTick;
import com.aquarius.feature.pathfinder.goals.GoalXZ;
import com.aquarius.mc.block.BlockPos;
import com.github.rfresh2.EventConsumer;
import com.porter.qwertestash.QwerteStashPlugin;
import com.porter.qwertestash.StashConfig;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.Stashes;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;

import static com.aquarius.Globals.BARITONE;
import static com.aquarius.Globals.CACHE;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class SorterLifecycleIntegrationTest {
    private final StashConfig config = new StashConfig();
    private final StashConfig oldConfig = QwerteStashPlugin.CONFIG;
    private final ComponentLogger oldLogger = QwerteStashPlugin.LOG;
    private final Float oldHealth = CACHE.getPlayerCache().getThePlayer().getHealth();

    /** The real host cache has no network connection. Supply only readiness and the simulated player's block position. */
    private static final class Sorter extends StashSorter {
        private BlockPos feet;

        @Override
        protected boolean notReady() {
            return false;
        }

        @Override
        protected BlockPos playerFeet() {
            return feet;
        }
    }

    @BeforeEach
    void setUp() {
        config.stashName = "lifecycle-test-" + UUID.randomUUID();
        config.carrySlots = "2";
        config.reservePearlSlot = false;
        QwerteStashPlugin.CONFIG = config;
        QwerteStashPlugin.LOG = ComponentLogger.logger("sorter-test");
        CACHE.getPlayerCache().getThePlayer().setHealth(20f);
    }

    @AfterEach
    void tearDown() {
        try {
            Stashes.forget();
        } finally {
            CACHE.getPlayerCache().getThePlayer().setHealth(oldHealth);
            QwerteStashPlugin.CONFIG = oldConfig;
            QwerteStashPlugin.LOG = oldLogger;
        }
    }

    @Test
    void startupExplainsWhyDesignatedCarrySlotsAreUnavailable() {
        config.keepSlots = "2";
        var sorter = new StashSorter();
        sorter.onEnable();
        assertTrue(sorter.problem().contains("The carry slots are all full or kept."));
        assertTrue(sorter.problem().contains("1 are keep slots"));
    }

    @Test
    void registeredTicksWaitForUnloadedColumnThenFinishExploration() {
        Sorter sorter = startWithOffset(0);
        Runnable tick = ticks(sorter);
        for (int i = 0; i < 40; i++) tick.run();
        assertNull(sorter.problem());
        tick.run();
        tick.run();
        assertEquals("Found 0 container(s) to sort in the stash area, need at least 2.", sorter.problem());
    }

    @Test
    void leavingAnAreaStartedInsideAbandonsAnUnloadedWaypointImmediately() {
        Sorter sorter = startWithOffset(0);
        Runnable tick = ticks(sorter);
        tick.run();
        assertNull(sorter.problem());
        sorter.feet = new BlockPos(sorter.feet.x() + 16, sorter.feet.y(), sorter.feet.z());
        tick.run();
        tick.run();
        assertEquals("Found 0 container(s) to sort in the stash area, need at least 2.", sorter.problem());
    }

    @Test
    void requestedPathIsReleasedOnceTheTargetColumnLoads() {
        var player = CACHE.getPlayerCache();
        int x = (int) Math.floor(player.getX()) + 16, z = (int) Math.floor(player.getZ());
        var column = new Chunk(x >> 4, z >> 4, new ChunkSection[0], 0, 0, new ArrayList<>(), null);
        var cache = CACHE.getChunkCache().getCache();
        long key = column.getChunkPos();
        var previous = cache.remove(key);
        Sorter sorter = null;
        try {
            sorter = startWithOffset(16);
            Runnable tick = ticks(sorter);
            tick.run();
            assertEquals(new GoalXZ(x, z), BARITONE.getCustomGoalProcess().getGoal());
            cache.put(key, column);
            tick.run();
            assertNull(sorter.problem());
            tick.run();
            assertNull(BARITONE.getCustomGoalProcess().getGoal());
            assertEquals("Found 0 container(s) to sort in the stash area, need at least 2.", sorter.problem());
        } finally {
            if (previous == null) cache.remove(key);
            else cache.put(key, previous);
            if (sorter != null) {
                // A disconnected restart releases any outstanding path and restores borrowed pathfinder settings.
                player.getThePlayer().setHealth(0f);
                sorter.onEnable();
            }
        }
    }

    private Sorter startWithOffset(int offsetX) {
        var player = CACHE.getPlayerCache();
        int[] pos = {(int) Math.floor(player.getX()), (int) Math.floor(player.getY()), (int) Math.floor(player.getZ())};
        int[] target = {pos[0] + offsetX, pos[1], pos[2]};
        Stashes.currentOrNew().area = new Stash.Area(target, target, null);
        var sorter = new Sorter();
        sorter.feet = new BlockPos(pos[0], pos[1], pos[2]);
        sorter.onEnable();
        assertNull(sorter.problem());
        return sorter;
    }

    private static Runnable ticks(StashSorter sorter) {
        var event = sorter.registerEvents().stream().filter(e -> e.eventClass() == ClientBotTick.class)
            .findFirst().orElseThrow();
        return () -> dispatch(event, ClientBotTick.INSTANCE);
    }

    private static <T> void dispatch(EventConsumer<T> event, Object value) {
        event.handler().accept(event.eventClass().cast(value));
    }
}
