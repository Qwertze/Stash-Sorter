package com.porter.qwertestash.module;

import com.aquarius.cache.data.entity.Entity;
import com.aquarius.cache.data.entity.EntityPlayer;
import com.aquarius.event.client.ClientBotTick;
import com.aquarius.event.client.ClientDisconnectEvent;
import com.aquarius.mc.block.BlockPos;
import com.aquarius.module.api.Module;
import com.aquarius.network.codec.PacketHandlerCodec;
import com.aquarius.network.codec.PacketHandlerStateCodec;
import com.github.rfresh2.EventConsumer;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTakeItemEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.spawn.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static com.aquarius.Globals.CACHE;
import static com.github.rfresh2.EventConsumer.of;
import static com.porter.qwertestash.QwerteStashPlugin.LOG;

/**
 * Knows about the item entities lying around for {@link StashSorter}, which picks them up: which of them the bot threw
 * or dropped itself or left by breaking a block (never picked up again), how long each has lain still, and whether
 * another player is loaded. The server never says who dropped an item, so it is worked out when the item appears: one
 * that appears at the bot's eye position just after an inventory drop of its own, or next to a block the bot has just
 * dug, is the bot's. Own ids stay excluded for the whole session (ids are not reused within a server run), capped.
 * <p>
 * Always on, whatever the collect setting, and cheap; it only watches, it never sends anything. The drops are seen on
 * the proxy's outgoing client codec, which every send path of the proxy's own modules (the inventory queue's
 * {@code sendAwait}, Baritone's {@code sendAsync}) goes through.
 */
public class GroundItems extends Module {
    // The bot's own digs, kept this long to tell its blocks from other people's
    private static final long DIG_TTL_MS = 8_000;
    // A drop of the bot's own is matched to the spawn it causes within this long, laggy servers included
    private static final long DROP_TTL_MS = 5_000;
    // After the first spawn a drop caused, it may still cause others (a stack as several entities) for this long
    private static final long DROP_FOLLOW_MS = 500;
    private static final int MAX_RECENT = 512;
    private static final int MAX_EXCLUDED = 4096;
    // Where a thrown item appears: at the player's position, eye height less 0.3 above their feet (lower if sneaking or swimming)
    private static final double DROP_HORIZONTAL = 2.0;
    private static final double DROP_LOW = 0.3, DROP_HIGH = 2.1;
    // Items that appear this close to the centre of a block the bot dug, or in a cell next to it, are its own
    private static final double DIG_DISTANCE = 1.5;
    private static final int DIG_CELLS = 1;
    private static final double STILL_EPSILON = 0.01;
    private static final long SCAN_GAP_MS = 200;
    // The scan did not run for this long (the bot was not being ticked, e.g. a player was connected): stillness starts over
    private static final long PAUSE_MS = 1_500;

    private record Dig(BlockPos pos, long at) {
    }

    private static final class Drop {
        final double x, y, z;
        final long at;
        // When the first spawn was matched to it, 0 until then
        volatile long matchedAt;

        Drop(double x, double y, double z, long at) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.at = at;
        }
    }

    private record Taken(int count, long at) {
    }

    private static final class Seen {
        double x, y, z;
        // When it last moved (or first appeared)
        long since;
    }

    // Own items, oldest forgotten first once there are too many
    private final Set<Integer> excluded = Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<Integer, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
            return size() > MAX_EXCLUDED;
        }
    }));
    private final Map<Integer, Seen> seen = new ConcurrentHashMap<>();
    private final Map<Integer, Taken> taken = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<Dig> digs = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Drop> drops = new ConcurrentLinkedDeque<>();
    private volatile @Nullable Object world;
    private long lastScan;

    @Override
    public boolean enabledSetting() {
        return true;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, -600, e -> scan()),
            of(ClientDisconnectEvent.class, e -> clear())
        );
    }

    @Override
    public void onDisable() {
        clear();
    }

    private void clear() {
        excluded.clear();
        seen.clear();
        taken.clear();
        digs.clear();
        drops.clear();
        world = null;
        lastScan = 0;
    }

    // Seeing what happens

    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId("qwerte-stash-ground")
            .setPriority(-1000)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                .inbound(ClientboundAddEntityPacket.class, (packet, session) -> {
                    try {
                        if (packet.getType() == EntityType.ITEM) itemAppeared(packet);
                    } catch (RuntimeException e) {
                        LOG.debug("Qwerte Stash: couldn't look at a dropped item", e);
                    }
                    return packet;
                })
                .inbound(ClientboundTakeItemEntityPacket.class, (packet, session) -> {
                    try {
                        if (packet.getCollectorEntityId() == botId()) {
                            taken.put(packet.getCollectedEntityId(), new Taken(packet.getItemCount(), System.currentTimeMillis()));
                        }
                    } catch (RuntimeException e) {
                        LOG.debug("Qwerte Stash: couldn't look at an item pickup", e);
                    }
                    return packet;
                })
                .inbound(ClientboundRemoveEntitiesPacket.class, (packet, session) -> {
                    try {
                        // Own ids stay excluded: leaving tracking range and coming back is the same entity
                        for (int id : packet.getEntityIds()) seen.remove(id);
                    } catch (RuntimeException e) {
                        LOG.debug("Qwerte Stash: couldn't look at a despawn", e);
                    }
                    return packet;
                })
                // Another dimension has other entities under the same ids
                .inbound(ClientboundRespawnPacket.class, (packet, session) -> {
                    try {
                        Object now = packet.getCommonPlayerSpawnInfo().getWorldName();
                        Object was = world;
                        world = now;
                        if (was == null || !was.equals(now)) {
                            excluded.clear();
                            seen.clear();
                            taken.clear();
                            drops.clear();
                        }
                    } catch (RuntimeException e) {
                        LOG.debug("Qwerte Stash: couldn't look at a respawn", e);
                    }
                    return packet;
                })
                // Everything the bot (or the owner through it) digs or drops, so its own are never taken for anyone else's
                .outbound(ServerboundPlayerActionPacket.class, (packet, session) -> {
                    try {
                        PlayerAction action = packet.getAction();
                        if (action == PlayerAction.START_DESTROY_BLOCK || action == PlayerAction.STOP_DESTROY_BLOCK || action == PlayerAction.ABORT_DESTROY_BLOCK) {
                            long now = System.currentTimeMillis();
                            digs.removeIf(d -> now - d.at() > DIG_TTL_MS);
                            digs.addLast(new Dig(new BlockPos(packet.getX(), packet.getY(), packet.getZ()), now));
                            while (digs.size() > MAX_RECENT) digs.pollFirst();
                        }
                        else if (action == PlayerAction.DROP_ITEM || action == PlayerAction.DROP_ALL_ITEMS) noteDrop();
                    } catch (RuntimeException e) {
                        LOG.debug("Qwerte Stash: couldn't note a dig or drop", e);
                    }
                    return packet;
                })
                .outbound(ServerboundContainerClickPacket.class, (packet, session) -> {
                    try {
                        // Drop (Q) on a slot, or a click outside the window with something on the cursor
                        if (packet.getActionType() == ContainerActionType.DROP_ITEM && packet.getSlot() != ServerboundContainerClickPacket.CLICK_OUTSIDE_NOT_HOLDING_SLOT) noteDrop();
                        else if (packet.getActionType() == ContainerActionType.CLICK_ITEM && packet.getSlot() == ServerboundContainerClickPacket.CLICK_OUTSIDE_NOT_HOLDING_SLOT
                            && (packet.getActionParam() == ClickItemAction.LEFT_CLICK || packet.getActionParam() == ClickItemAction.RIGHT_CLICK)) noteDrop();
                    } catch (RuntimeException e) {
                        LOG.debug("Qwerte Stash: couldn't note a drop", e);
                    }
                    return packet;
                })
                .build())
            .build();
    }

    private static int botId() {
        return CACHE.getPlayerCache().getEntityId();
    }

    /**
     * The bot just dropped something: an item entity is about to appear at its eye position.
     */
    private void noteDrop() {
        long now = System.currentTimeMillis();
        drops.removeIf(d -> now - d.at > DROP_TTL_MS);
        drops.addLast(new Drop(CACHE.getPlayerCache().getX(), CACHE.getPlayerCache().getY(), CACHE.getPlayerCache().getZ(), now));
        while (drops.size() > MAX_RECENT) drops.pollFirst();
    }

    private void itemAppeared(ClientboundAddEntityPacket packet) {
        long now = System.currentTimeMillis();
        digs.removeIf(d -> now - d.at() > DIG_TTL_MS);
        drops.removeIf(d -> now - d.at > DROP_TTL_MS);
        taken.values().removeIf(t -> now - t.at() > 60_000);

        int id = packet.getEntityId();
        if (excluded.contains(id)) return;
        double x = packet.getX(), y = packet.getY(), z = packet.getZ();
        if (ownDrop(x, y, z, now) || ownDig(x, y, z)) excluded.add(id);
    }

    /**
     * At the bot's eye position, within a moment of a drop of its own. A drop is matched by the first item it causes and
     * may then match others for a moment (a stack can appear as several entities), but not later ones, so a foreign
     * throw at the same spot a little later is not taken for it.
     */
    private boolean ownDrop(double x, double y, double z, long now) {
        double bx = CACHE.getPlayerCache().getX(), by = CACHE.getPlayerCache().getY(), bz = CACHE.getPlayerCache().getZ();
        for (Drop drop : drops) {
            if (now - drop.at > DROP_TTL_MS || now < drop.at - 50) continue;
            long first = drop.matchedAt;
            if (first != 0 && now - first > DROP_FOLLOW_MS) continue;
            // Where the bot was when it dropped, or where it is now if it has walked on since
            boolean there = Math.hypot(x - drop.x, z - drop.z) <= DROP_HORIZONTAL || Math.hypot(x - bx, z - bz) <= DROP_HORIZONTAL;
            double upThen = y - drop.y, upNow = y - by;
            boolean high = (upThen >= DROP_LOW && upThen <= DROP_HIGH) || (upNow >= DROP_LOW && upNow <= DROP_HIGH);
            if (!there || !high) continue;
            if (first == 0) drop.matchedAt = now;
            return true;
        }
        return false;
    }

    /**
     * Next to a block the bot dug a moment ago: within a block and a half of its centre, or in a cell next to its own.
     * Whether the block is shown as air by then does not matter (water, kelp, torches that went with it).
     */
    private boolean ownDig(double x, double y, double z) {
        int cx = (int) Math.floor(x), cy = (int) Math.floor(y), cz = (int) Math.floor(z);
        for (Dig dig : digs) {
            BlockPos d = dig.pos();
            if (Math.abs(d.x() - cx) <= DIG_CELLS && Math.abs(d.y() - cy) <= DIG_CELLS && Math.abs(d.z() - cz) <= DIG_CELLS) return true;
            double dx = x - (d.x() + 0.5), dy = y - (d.y() + 0.5), dz = z - (d.z() + 0.5);
            if (dx * dx + dy * dy + dz * dz <= DIG_DISTANCE * DIG_DISTANCE) return true;
        }
        return false;
    }

    // Watching how long each lies still

    private void scan() {
        long now = System.currentTimeMillis();
        if (now - lastScan < SCAN_GAP_MS) return;
        boolean paused = lastScan != 0 && now - lastScan > PAUSE_MS;
        lastScan = now;

        for (Entity entity : CACHE.getEntityCache().getEntities().values()) {
            if (entity == null || entity.getEntityType() != EntityType.ITEM) continue;
            Seen was = seen.computeIfAbsent(entity.getEntityId(), id -> {
                Seen fresh = new Seen();
                fresh.x = entity.getX();
                fresh.y = entity.getY();
                fresh.z = entity.getZ();
                fresh.since = now;
                return fresh;
            });
            if (paused || Math.abs(was.x - entity.getX()) > STILL_EPSILON || Math.abs(was.y - entity.getY()) > STILL_EPSILON || Math.abs(was.z - entity.getZ()) > STILL_EPSILON) {
                was.x = entity.getX();
                was.y = entity.getY();
                was.z = entity.getZ();
                was.since = now;
            }
        }
        // Gone from the cache without a despawn packet seen (the world changed)
        seen.keySet().removeIf(id -> CACHE.getEntityCache().get(id) == null);
    }

    // What the sorter asks (from the tick thread)

    /**
     * How long this item has lain where it is, in milliseconds, or -1 if it is not known.
     */
    public long stillFor(int id) {
        Seen s = seen.get(id);
        return s == null ? -1 : System.currentTimeMillis() - s.since;
    }

    /**
     * How many of the item the bot picked up, or -1 if it did not pick it up (as far as has been seen).
     */
    public int takenCount(int id) {
        Taken t = taken.get(id);
        return t == null ? -1 : t.count();
    }

    /**
     * The item entities on the ground that may be picked up: not the bot's own, and lying still for at least this long.
     */
    public List<Entity> candidates(long stillMs) {
        List<Entity> found = new ArrayList<>();
        for (Entity entity : CACHE.getEntityCache().getEntities().values()) {
            if (entity == null || entity.getEntityType() != EntityType.ITEM || entity.isRemoved()) continue;
            int id = entity.getEntityId();
            if (excluded.contains(id) || taken.containsKey(id)) continue;
            long still = stillFor(id);
            if (still >= 0 && still >= stillMs) found.add(entity);
        }
        return found;
    }

    /**
     * The name of a player other than the bot who is loaded (in render distance), or null if there is none.
     */
    public @Nullable String playerNearby() {
        int botId = botId();
        for (EntityPlayer player : CACHE.getEntityCache().getPlayers().values()) {
            if (player == null || player.getEntityId() == botId || player.isSelfPlayer() || player.isRemoved()) continue;
            return CACHE.getTabListCache().get(player.getUuid()).map(PlayerListEntry::getName).orElse(player.getUuid().toString());
        }
        return null;
    }

    /**
     * The stack an item entity holds, once its data has arrived.
     */
    public static @Nullable ItemStack stackOf(@Nullable Entity entity) {
        return AutoDrop.stackOf(entity);
    }
}
