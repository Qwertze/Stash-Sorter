package com.porter.qwertestash.module;

import com.aquarius.cache.data.entity.Entity;
import com.aquarius.cache.data.entity.EntityPlayer;
import com.aquarius.event.client.ClientBotTick;
import com.aquarius.event.client.ClientDisconnectEvent;
import com.aquarius.feature.inventory.InventoryActionRequest;
import com.aquarius.feature.inventory.actions.DropItem;
import com.aquarius.feature.inventory.actions.InventoryAction;
import com.aquarius.feature.player.Input;
import com.aquarius.feature.player.InputRequest;
import com.aquarius.feature.player.InputRequestFuture;
import com.aquarius.mc.block.BlockPos;
import com.aquarius.module.api.Module;
import com.aquarius.network.codec.PacketHandlerCodec;
import com.aquarius.network.codec.PacketHandlerStateCodec;
import com.aquarius.util.RequestFuture;
import com.github.rfresh2.EventConsumer;
import com.porter.qwertestash.stash.Item;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTakeItemEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.spawn.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.aquarius.Globals.BARITONE;
import static com.aquarius.Globals.CACHE;
import static com.aquarius.Globals.INPUTS;
import static com.aquarius.Globals.INVENTORY;
import static com.aquarius.Globals.MODULE;
import static com.github.rfresh2.EventConsumer.of;
import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;
import static com.porter.qwertestash.QwerteStashPlugin.LOG;

/**
 * Gives back what a player dropped near the bot. The server never says who dropped an item, so it is worked out from
 * what is seen when the item appears: an item that appears at a player's position, at about the height their hand
 * throws from, was thrown by them; one that appears in the cell of a block that has just been removed, with a player in
 * reach of that block and no dig of the bot's own at it, was left by that player breaking it. Anything else (mob and
 * death drops, dispensers, items the bot or its owner threw or broke) is nobody's, and is kept.
 * <p>
 * When the bot then picks such an item up, it turns to look at the player and drops exactly what it picked up. It
 * never does this over what else is going on: it waits for inventory actions, an open container and pathing to be free.
 */
public class AutoDrop extends Module {
    // An item and the removal of the block it came out of arrive within a tick or two of each other, either way round
    private static final long BLOCK_WINDOW_MS = 400;
    private static final long ATTRIBUTION_TTL_MS = 5 * 60_000;
    // The bot's own digs, kept this long to tell its blocks from a player's
    private static final long DIG_TTL_MS = 8_000;
    // A return not done by now is given up on
    private static final long RETURN_TTL_MS = 30_000;
    private static final long RETURN_GAP_MS = 1_500;
    private static final int MAX_QUEUE = 20;
    private static final int MAX_RECENT = 512;
    private static final double EYE_HEIGHT = 1.62;
    // Where a thrown item appears: at the player's position, eye height less 0.3 above their feet
    private static final double THROW_HORIZONTAL = 0.7;
    private static final double THROW_LOW = 0.8, THROW_HIGH = 1.8;
    private static final double BREAK_REACH = 6.0;
    private static final float AIM_TOLERANCE = 6f;

    private record Spawn(int id, double x, double y, double z, long at) {
    }

    private record Air(BlockPos pos, long at) {
    }

    private record Dig(BlockPos pos, long at) {
    }

    private static final class Attribution {
        final UUID player;
        final String name, why;
        final long at;
        // What the item is, read from the item entity once its data has arrived, so it is known even if the cache
        // has already forgotten the entity when it is picked up
        volatile @Nullable ItemStack stack;

        Attribution(UUID player, String name, String why, long at) {
            this.player = player;
            this.name = name;
            this.why = why;
            this.at = at;
        }
    }

    static @Nullable ItemStack stackOf(@Nullable Entity entity) {
        if (entity == null) return null;
        try {
            ItemStack stack = null;
            for (var metadata : new ArrayList<>(entity.getMetadata().values())) {
                if (metadata.getValue() instanceof ItemStack found) stack = found;
            }
            return stack;
        } catch (RuntimeException e) {
            // The network thread is changing it: next time
            return null;
        }
    }

    /**
     * One item to give back: who to, what (id and components, which is how it is told from other stacks), and how many.
     */
    private static final class Return {
        final UUID player;
        final String name, why;
        final ItemStack template;
        final int amount;
        // The inventory as it was when the pickup happened, to see which slot it changed
        final int[] beforeId = new int[46], beforeAmount = new int[46];
        int gained;
        // Whether the slot found holds the very same components, and when looking for it began
        boolean exact = true;
        long findStart;
        final long at = System.currentTimeMillis();
        Stage stage = Stage.Find;
        int slot = -1, countBefore, ticks, aims;
        float yaw, pitch;
        @Nullable RequestFuture future;

        Return(UUID player, String name, String why, ItemStack template, int amount) {
            this.player = player;
            this.name = name;
            this.why = why;
            this.template = template;
            this.amount = amount;
        }
    }

    private enum Stage { Find, Aim, Turn, Drop, Done }

    private final Map<Integer, Attribution> attributions = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Spawn> spawns = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Air> airs = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Dig> digs = new ConcurrentLinkedDeque<>();
    private final Queue<Return> returns = new ConcurrentLinkedQueue<>();
    private long nextReturnAt;
    private @Nullable StashSorter sorter;

    @Override
    public boolean enabledSetting() {
        return CONFIG.autoDrop;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, -500, this::onTick),
            // A player taking the account over, or giving it back, leaves what was picked up meanwhile to them
            of(ClientBotTick.Starting.class, e -> returns.clear()),
            of(ClientBotTick.Stopped.class, e -> returns.clear()),
            of(ClientDisconnectEvent.class, e -> clear())
        );
    }

    @Override
    public void onDisable() {
        clear();
    }

    private void clear() {
        attributions.clear();
        spawns.clear();
        airs.clear();
        digs.clear();
        returns.clear();
    }

    // Seeing what happens

    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId("qwerte-stash-autodrop")
            .setPriority(-1000)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                .inbound(ClientboundAddEntityPacket.class, (packet, session) -> {
                    try {
                        if (isEnabled() && packet.getType() == EntityType.ITEM) itemAppeared(packet);
                    } catch (RuntimeException e) {
                        LOG.debug("Qwerte Stash: couldn't look at a dropped item", e);
                    }
                    return packet;
                })
                .inbound(ClientboundBlockUpdatePacket.class, (packet, session) -> {
                    try {
                        if (isEnabled()) blocksChanged(List.of(packet.getEntry()));
                    } catch (RuntimeException e) {
                        LOG.debug("Qwerte Stash: couldn't look at a block change", e);
                    }
                    return packet;
                })
                .inbound(ClientboundSectionBlocksUpdatePacket.class, (packet, session) -> {
                    try {
                        if (isEnabled()) blocksChanged(java.util.Arrays.asList(packet.getEntries()));
                    } catch (RuntimeException e) {
                        LOG.debug("Qwerte Stash: couldn't look at block changes", e);
                    }
                    return packet;
                })
                .inbound(ClientboundTakeItemEntityPacket.class, (packet, session) -> {
                    try {
                        if (isEnabled()) taken(packet);
                    } catch (RuntimeException e) {
                        LOG.debug("Qwerte Stash: couldn't look at an item pickup", e);
                    }
                    return packet;
                })
                // Every dig the bot (or the owner through it) makes, so its own blocks are never taken for a player's
                .outbound(ServerboundPlayerActionPacket.class, (packet, session) -> {
                    try {
                        if (!isEnabled()) return packet;
                        PlayerAction action = packet.getAction();
                        if (action == PlayerAction.START_DESTROY_BLOCK || action == PlayerAction.STOP_DESTROY_BLOCK || action == PlayerAction.ABORT_DESTROY_BLOCK) {
                            long now = System.currentTimeMillis();
                            digs.removeIf(d -> now - d.at() > DIG_TTL_MS);
                            digs.addLast(new Dig(new BlockPos(packet.getX(), packet.getY(), packet.getZ()), now));
                            while (digs.size() > MAX_RECENT) digs.pollFirst();
                        }
                    } catch (RuntimeException e) {
                        LOG.debug("Qwerte Stash: couldn't note a dig", e);
                    }
                    return packet;
                })
                .build())
            .build();
    }

    private static BlockPos cell(double x, double y, double z) {
        return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    private int botId() {
        return CACHE.getPlayerCache().getEntityId();
    }

    private void itemAppeared(ClientboundAddEntityPacket packet) {
        long now = System.currentTimeMillis();
        Spawn spawn = new Spawn(packet.getEntityId(), packet.getX(), packet.getY(), packet.getZ(), now);
        prune(now);
        if (!attribute(spawn)) {
            spawns.addLast(spawn);
            while (spawns.size() > MAX_RECENT / 4) spawns.pollFirst();
        }
    }

    private void blocksChanged(Iterable<BlockChangeEntry> entries) {
        long now = System.currentTimeMillis();
        // Block state 0 is air: something was removed
        Set<BlockPos> removed = new HashSet<>();
        for (BlockChangeEntry entry : entries) {
            if (entry.getBlock() == 0 && removed.size() < MAX_RECENT) removed.add(new BlockPos(entry.getX(), entry.getY(), entry.getZ()));
        }
        if (removed.isEmpty()) return;

        prune(now);
        for (BlockPos pos : removed) airs.addLast(new Air(pos, now));
        while (airs.size() > MAX_RECENT) airs.pollFirst();

        // The item may have come first
        if (spawns.isEmpty()) return;
        for (Spawn spawn : spawns) {
            if (now - spawn.at() <= BLOCK_WINDOW_MS && removed.contains(cell(spawn.x(), spawn.y(), spawn.z())) && attribute(spawn)) spawns.remove(spawn);
        }
    }

    private void prune(long now) {
        airs.removeIf(a -> now - a.at() > 2 * BLOCK_WINDOW_MS);
        spawns.removeIf(s -> now - s.at() > 2 * BLOCK_WINDOW_MS);
        digs.removeIf(d -> now - d.at() > DIG_TTL_MS);
        attributions.values().removeIf(a -> now - a.at > ATTRIBUTION_TTL_MS);
    }

    /**
     * Whose item this is, if it can be told. Returns true if it was settled, one way or the other.
     */
    private boolean attribute(Spawn spawn) {
        double bx = CACHE.getPlayerCache().getX(), bz = CACHE.getPlayerCache().getZ();
        double botDistance = Math.hypot(spawn.x() - bx, spawn.z() - bz);
        int botId = botId();

        // A removed block in this very cell
        BlockPos at = cell(spawn.x(), spawn.y(), spawn.z());
        for (Air air : airs) {
            if (!air.pos().equals(at) || Math.abs(air.at() - spawn.at()) > BLOCK_WINDOW_MS) continue;

            // The bot's own block, however it was broken
            for (Dig dig : digs) if (dig.pos().equals(at)) return true;

            EntityPlayer breaker = null;
            double best = BREAK_REACH;
            for (EntityPlayer player : CACHE.getEntityCache().getPlayers().values()) {
                if (player.getEntityId() == botId) continue;
                double d = Math.sqrt(sq(player.getX() - (at.x() + 0.5)) + sq(player.getY() + 1.0 - (at.y() + 0.5)) + sq(player.getZ() - (at.z() + 0.5)));
                if (d <= best) {
                    best = d;
                    breaker = player;
                }
            }
            if (breaker != null) mark(spawn, breaker, "broken block");
            return true;
        }

        // Thrown: right where a player stands, at the height a hand throws from, and nearer to them than to the bot
        EntityPlayer thrower = null;
        double best = THROW_HORIZONTAL;
        for (EntityPlayer player : CACHE.getEntityCache().getPlayers().values()) {
            if (player.getEntityId() == botId) continue;
            double horizontal = Math.hypot(spawn.x() - player.getX(), spawn.z() - player.getZ());
            double up = spawn.y() - player.getY();
            if (horizontal <= best && up >= THROW_LOW && up <= THROW_HIGH && horizontal < botDistance) {
                best = horizontal;
                thrower = player;
            }
        }
        if (thrower != null) {
            mark(spawn, thrower, "thrown");
            return true;
        }
        return false;
    }

    private static double sq(double v) {
        return v * v;
    }

    private void mark(Spawn spawn, EntityPlayer player, String why) {
        String name = CACHE.getTabListCache().get(player.getUuid()).map(PlayerListEntry::getName).orElse(player.getUuid().toString());
        attributions.put(spawn.id(), new Attribution(player.getUuid(), name, why, System.currentTimeMillis()));
    }

    /**
     * The bot picked something up. If it was a player's, it is queued to go back. Seen before the cache forgets the item.
     */
    private void taken(ClientboundTakeItemEntityPacket packet) {
        if (packet.getCollectorEntityId() != botId()) return;
        Attribution who = attributions.remove(packet.getCollectedEntityId());
        if (who == null) return;

        // Someone is playing the account: what it picks up is theirs to decide
        if (System.currentTimeMillis() - lastBotTick > 500) return;

        // Picked up on purpose: a shulker being collected, or anything Baritone was sent to fetch
        StashSorter sorter = sorter();
        if (sorter.isCollecting() || BARITONE.getFollowProcess().isActive()) return;

        ItemStack stack = stackOf(CACHE.getEntityCache().get(packet.getCollectedEntityId()));
        if (stack == null) stack = who.stack;
        if (stack == null) return;
        Item item = Item.of(stack);
        if (item == null) return;
        // PearlPlus loads and drops pearls itself
        if (item.id.equals("ender_pearl")) return;

        if (returns.size() >= MAX_QUEUE) return;
        Return ret = new Return(who.player, who.name, who.why, stack, Math.max(1, packet.getItemCount()));
        List<ItemStack> inventory = CACHE.getPlayerCache().getPlayerInventory();
        for (int i = 0; i < 46 && i < inventory.size(); i++) {
            ItemStack there = inventory.get(i);
            if (there == null || there.getAmount() <= 0) continue;
            ret.beforeId[i] = there.getId() + 1;
            ret.beforeAmount[i] = there.getAmount();
        }
        returns.add(ret);
        LOG.debug("Qwerte Stash: picked up {} x{} that {} {}, giving it back", item.label(), packet.getItemCount(), who.name, who.why);
    }

    private StashSorter sorter() {
        StashSorter found = sorter;
        if (found == null) sorter = found = MODULE.get(StashSorter.class);
        return found;
    }

    /**
     * Whether this stack is one that is about to be given back, so the sorter doesn't take it for stash cargo.
     */
    public boolean isReturning(ItemStack stack) {
        for (Return r : returns) {
            if (r.template.getId() == stack.getId()
                && Objects.equals(r.template.getDataComponentsOrEmpty(), stack.getDataComponentsOrEmpty())) return true;
        }
        return false;
    }

    // Giving it back

    private volatile long lastBotTick;

    private void onTick(ClientBotTick event) {
        lastBotTick = System.currentTimeMillis();
        // Learn what each attributed item is while it is still around
        for (Map.Entry<Integer, Attribution> entry : attributions.entrySet()) {
            if (entry.getValue().stack == null) entry.getValue().stack = stackOf(CACHE.getEntityCache().get(entry.getKey()));
        }

        Return r = returns.peek();
        if (r == null || !isEnabled() || !CACHE.getPlayerCache().isAlive()) return;

        long now = System.currentTimeMillis();
        Item label = Item.of(r.template);
        String what = label == null ? "item" : label.label();
        if (now - r.at > RETURN_TTL_MS) {
            returns.poll();
            LOG.info("Qwerte Stash: couldn't give {} back to {} in time, keeping it.", what, r.name);
            return;
        }
        if (now < nextReturnAt) return;

        Entity entity = CACHE.getEntityCache().get(r.player);
        if (!(entity instanceof EntityPlayer player)
            || Math.sqrt(sq(player.getX() - CACHE.getPlayerCache().getX()) + sq(player.getZ() - CACHE.getPlayerCache().getZ())) > CONFIG.autoDropRange) {
            returns.poll();
            LOG.info("Qwerte Stash: {} is out of range or gone, keeping the {}.", r.name, what);
            return;
        }

        // Never over the sorter's job, anyone's clicks, inputs or pathing, or an open window
        if (r.stage != Stage.Done && (sorter().busyWithJob() || INVENTORY.hasActiveRequest() || INPUTS.hasActiveRequest()
            || CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() != 0 || BARITONE.isActive())) {
            return;
        }

        switch (r.stage) {
            case Find -> {
                if (r.findStart == 0) r.findStart = now;
                r.slot = find(r);
                if (r.slot < 0) {
                    if (now - r.findStart > 4_000) {
                        returns.poll();
                        LOG.info("Qwerte Stash: couldn't find the {} to give back to {}.", what, r.name);
                        LOG.debug("Qwerte Stash: looked for {} x{} among: {}", what, r.amount, describeSlots(r));
                    }
                    return;
                }
                r.countBefore = CACHE.getPlayerCache().getPlayerInventory().get(r.slot).getAmount();
                r.stage = Stage.Aim;
                return;
            }
            case Aim -> {
                double px = CACHE.getPlayerCache().getX(), py = CACHE.getPlayerCache().getY(), pz = CACHE.getPlayerCache().getZ();
                double tx = player.getX(), ty = player.getY() + EYE_HEIGHT, tz = player.getZ();
                double distance = Math.sqrt(sq(tx - px) + sq(tz - pz));
                r.yaw = (float) (Math.toDegrees(Math.atan2(tz - pz, tx - px)) - 90.0);
                r.pitch = (float) -Math.toDegrees(Math.atan2(ty - (py + EYE_HEIGHT), Math.max(distance, 0.01)));
                InputRequestFuture future = INPUTS.submit(InputRequest.builder().owner(this)
                    .input(Input.builder().build()).yaw(r.yaw).pitch(r.pitch).priority(10).build());
                if (future == InputRequestFuture.rejected) return;
                r.ticks = 0;
                r.stage = Stage.Turn;
            }
            case Turn -> {
                // The turn takes a tick or two to reach the server
                if (++r.ticks < 2) return;
                if (angle(CACHE.getPlayerCache().getYaw(), r.yaw) > AIM_TOLERANCE || Math.abs(CACHE.getPlayerCache().getPitch() - r.pitch) > AIM_TOLERANCE) {
                    if (++r.aims > 5) {
                        returns.poll();
                        LOG.info("Qwerte Stash: couldn't turn to {} to give the {} back, keeping it.", r.name, what);
                        return;
                    }
                    r.stage = Stage.Aim;
                    return;
                }
                r.stage = Stage.Drop;
            }
            case Drop -> {
                // Still what the pickup left in that very slot, or nothing is thrown
                ItemStack there = CACHE.getPlayerCache().getPlayerInventory().get(r.slot);
                if (there == null || there.getAmount() != r.countBefore || there.getId() != r.template.getId()
                    || (r.exact && !Objects.equals(r.template.getDataComponentsOrEmpty(), there.getDataComponentsOrEmpty()))) {
                    returns.poll();
                    LOG.info("Qwerte Stash: the {} for {} moved before it could be given back, keeping it.", what, r.name);
                    return;
                }
                int count = Math.min(Math.min(r.amount, r.gained), there.getAmount());
                List<InventoryAction> actions = new ArrayList<>();
                if (count >= there.getAmount()) actions.add(new DropItem(r.slot, DropItemAction.DROP_SELECTED_STACK));
                else for (int i = 0; i < count; i++) actions.add(new DropItem(r.slot, DropItemAction.DROP_FROM_SELECTED));
                r.future = INVENTORY.submit(InventoryActionRequest.builder().owner(this).actions(actions).priority(10).build());
                if (r.future == RequestFuture.rejected) {
                    r.future = null;
                    return;
                }
                r.stage = Stage.Done;
            }
            case Done -> {
                RequestFuture future = r.future;
                if (future == null || !future.isDone()) return;
                returns.poll();
                nextReturnAt = now + RETURN_GAP_MS;
                ItemStack after = CACHE.getPlayerCache().getPlayerInventory().get(r.slot);
                int left = after == null || after.getAmount() <= 0 || after.getId() != r.template.getId() ? 0 : after.getAmount();
                int gave = r.countBefore - left;
                if (gave > 0) LOG.info("Qwerte Stash: returned {} {} to {} ({}).", gave, what, r.name, r.why);
                else LOG.info("Qwerte Stash: the {} for {} did not leave the inventory, keeping it.", what, r.name);
            }
        }
    }

    private static float angle(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    /**
     * The inventory slot (window numbering, main inventory and hotbar) holding what was picked up, the one with the
     * most of it if there are several.
     */
    private int find(Return r) {
        List<ItemStack> inventory = CACHE.getPlayerCache().getPlayerInventory();
        int id = r.template.getId();

        // The slot the pickup changed: the same item, with the same components, that has more in it than before. The
        // main inventory, the hotbar (the hand included) and the offhand
        int best = -1, bestGain = 0;
        List<Integer> sameId = new ArrayList<>();
        for (int i = 9; i <= 45 && i < inventory.size(); i++) {
            ItemStack stack = inventory.get(i);
            if (stack == null || stack.getAmount() <= 0 || stack.getId() != id) continue;
            int gain = stack.getAmount() - (r.beforeId[i] == id + 1 ? r.beforeAmount[i] : 0);
            if (gain > 0) sameId.add(i);
            if (gain <= 0 || !Objects.equals(r.template.getDataComponentsOrEmpty(), stack.getDataComponentsOrEmpty())) continue;
            if (best == -1 || (gain == r.amount && bestGain != r.amount)) {
                best = i;
                bestGain = gain;
            }
        }
        if (best >= 0) {
            r.exact = true;
            r.gained = bestGain;
            return best;
        }

        // The components may read differently on the ground and in a slot: then the one slot of that item that grew
        if (sameId.size() == 1) {
            int i = sameId.get(0);
            ItemStack stack = inventory.get(i);
            r.exact = false;
            r.gained = stack.getAmount() - (r.beforeId[i] == id + 1 ? r.beforeAmount[i] : 0);
            return i;
        }

        // The inventory may already have been updated when the pickup was seen: if the bot had none of this item before,
        // the one slot that holds it is the pickup
        int before = 0;
        for (int i = 0; i < 46; i++) if (r.beforeId[i] == id + 1) before += r.beforeAmount[i];
        if (before == 0) {
            int only = -1, count = 0;
            for (int i = 9; i <= 45 && i < inventory.size(); i++) {
                ItemStack stack = inventory.get(i);
                if (stack != null && stack.getAmount() > 0 && stack.getId() == id) {
                    only = i;
                    count++;
                }
            }
            if (count == 1) {
                r.exact = Objects.equals(r.template.getDataComponentsOrEmpty(), inventory.get(only).getDataComponentsOrEmpty());
                r.gained = Math.min(r.amount, inventory.get(only).getAmount());
                return only;
            }
        }
        r.gained = 0;
        return -1;
    }

    /**
     * For the log when nothing was found: every slot holding the item, now and before the pickup.
     */
    private String describeSlots(Return r) {
        List<ItemStack> inventory = CACHE.getPlayerCache().getPlayerInventory();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < inventory.size() && i < 46; i++) {
            ItemStack stack = inventory.get(i);
            boolean there = stack != null && stack.getAmount() > 0 && stack.getId() == r.template.getId();
            boolean was = r.beforeId[i] == r.template.getId() + 1;
            if (!there && !was) continue;
            out.append("slot ").append(i).append(": now ").append(there ? stack.getAmount() : 0).append(", before ").append(was ? r.beforeAmount[i] : 0)
                .append(there ? (Objects.equals(r.template.getDataComponentsOrEmpty(), stack.getDataComponentsOrEmpty()) ? ", same components" : ", different components") : "")
                .append("; ");
        }
        return out.isEmpty() ? "no slot holds it" : out.toString();
    }
}
