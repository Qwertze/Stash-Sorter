package com.porter.qwertestash.farm;

import com.aquarius.feature.inventory.InventoryActionRequest;
import com.aquarius.feature.inventory.actions.SetHeldItem;
import com.aquarius.feature.pathfinder.PathingRequestFuture;
import com.aquarius.feature.pathfinder.goals.Goal;
import com.aquarius.feature.pathfinder.goals.GoalBlock;
import com.aquarius.feature.player.ClickResult;
import com.aquarius.feature.player.ClickTarget;
import com.aquarius.feature.player.Input;
import com.aquarius.feature.player.InputRequest;
import com.aquarius.feature.player.InputRequestFuture;
import com.aquarius.feature.player.World;
import com.aquarius.mc.item.ItemData;
import com.aquarius.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.function.BiConsumer;

import static com.aquarius.Globals.BARITONE;
import static com.aquarius.Globals.CACHE;
import static com.aquarius.Globals.INPUTS;
import static com.aquarius.Globals.INVENTORY;
import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;

/**
 * {@link FarmPlayer.Port} on the real proxy: the cache, the per-tick {@code Input} queue at {@code farm.inputPriority}, Baritone and the
 * inventory queue. Bot tick thread only. The click result of an input is handed to {@code clickResult(hit, expectedTarget)} when the
 * proxy executes it; nothing here logs a position.
 */
public final class ProxyFarmPort implements FarmPlayer.Port {
    private final Object owner;
    private final BiConsumer<Boolean, Boolean> clickResult;
    private Goal ourGoal;

    /** @param clickResult (hit, aimed): called when a click ran; {@code aimed} is true when the click had a block or entity target */
    public ProxyFarmPort(Object owner, BiConsumer<Boolean, Boolean> clickResult) {
        this.owner = owner;
        this.clickResult = clickResult;
    }

    @Override
    public double[] feet() {
        var p = CACHE.getPlayerCache();
        return new double[] {p.getX(), p.getY(), p.getZ()};
    }

    @Override
    public String dimension() {
        try {
            String d = World.getCurrentDimension().name();
            return d.contains(":") ? d : "minecraft:" + d;
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public String blockAt(int x, int y, int z) {
        try {
            if (!World.isChunkLoadedBlockPos(x, z)) return null;
            String n = World.getBlock(x, y, z).name();
            return n.contains(":") ? n : "minecraft:" + n;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean chunkLoaded(int blockX, int blockZ) {
        return World.isChunkLoadedBlockPos(blockX, blockZ);
    }

    private static ClickTarget target(FarmPlayer.Aim a, boolean left) {
        switch (a.kind()) {
            case 1:
                return new ClickTarget.BlockPosition(a.x(), a.y(), a.z());
            case 2:
                try {
                    String id = a.entity();
                    String path = id.substring(id.indexOf(':') + 1);
                    return new ClickTarget.EntityTypeTarget(EntityType.valueOf(path.toUpperCase(java.util.Locale.ROOT)));
                } catch (RuntimeException e) {
                    return ClickTarget.None.INSTANCE;
                }
            default:
                return ClickTarget.None.INSTANCE;
        }
    }

    @Override
    public void submit(FarmPlayer.Cmd c) {
        Input.Builder b = Input.builder()
            .pressingForward((c.keys() & 1) != 0).pressingBack((c.keys() & 2) != 0).pressingLeft((c.keys() & 4) != 0).pressingRight((c.keys() & 8) != 0)
            .jumping((c.keys() & 16) != 0).sneaking((c.keys() & 32) != 0).sprinting((c.keys() & 64) != 0);
        boolean click = c.left() || c.right();
        if (click) {
            b.leftClick(c.left()).rightClick(c.right()).hand(c.offHand() ? Hand.OFF_HAND : Hand.MAIN_HAND).clickTarget(target(c.aim(), c.left()));
        }
        InputRequest.Builder r = InputRequest.builder().owner(owner).priority(CONFIG.farmInputPriority).input(b.build());
        if (c.look()) r.yaw(c.yaw()).pitch(c.pitch());
        InputRequestFuture f = INPUTS.submit(r.build());
        if (click && f != InputRequestFuture.rejected) {
            final boolean aimed = c.aim().kind() != 0;
            final boolean left = c.left();
            f.addInputExecutedListener(fut -> {
                ClickResult res = fut.getClickResult();
                boolean hit = res != null;
                if (res instanceof ClickResult.LeftClickResult l && l.getType().name().contains("SWING")) hit = false;
                clickResult.accept(hit, aimed);
            });
        }
    }

    private static String idOf(ItemStack s) {
        if (s == null || s.getAmount() <= 0) return null;
        ItemData d = ItemRegistry.REGISTRY.get(s.getId());
        if (d == null || s.getId() == ItemRegistry.AIR.id()) return null;
        return "minecraft:" + d.name();
    }

    @Override
    public boolean select(String itemId) {
        var inv = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        int held = CACHE.getPlayerCache().getHeldItemSlot();
        for (int i = 0; i < 9; i++) {
            if (itemId.equals(idOf(inv.getItemStack(36 + i)))) {
                if (i != held) INVENTORY.submit(InventoryActionRequest.builder().owner(owner).actions(new SetHeldItem(i)).priority(CONFIG.farmInputPriority).build());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean holding(String itemId) {
        try {
            var inv = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
            int held = CACHE.getPlayerCache().getHeldItemSlot();
            return held >= 0 && held < 9 && itemId.equals(idOf(inv.getItemStack(36 + held)));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean pathfinderBusy() {
        try {
            return BARITONE.isActive();
        } catch (RuntimeException e) {
            return true;
        }
    }

    @Override
    public FarmPlayer.Path startPath(int x, int y, int z) {
        try {
            Goal goal = new GoalBlock(x, y, z);
            PathingRequestFuture f = BARITONE.pathTo(goal);
            if (f == null || f == PathingRequestFuture.rejected) return null;
            ourGoal = goal;
            return new FarmPlayer.Path() {
                @Override
                public S state() {
                    if (!f.isDone()) return S.RUNNING;
                    return f.isCompleted() ? S.DONE : S.FAILED;
                }

                @Override
                public void cancel() {
                    // only our own request: a newer one of someone else is never cancelled
                    if (ourGoal != null && BARITONE.getCustomGoalProcess().getGoal() == ourGoal) BARITONE.getCustomGoalProcess().stop();
                    ourGoal = null;
                }
            };
        } catch (RuntimeException e) {
            return null;
        }
    }
}
