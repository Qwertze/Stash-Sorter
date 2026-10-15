package com.porter.qwertestash.module;

import com.aquarius.cache.data.inventory.Container;
import com.aquarius.event.chat.WhisperChatEvent;
import com.aquarius.event.client.ClientDisconnectEvent;
import com.aquarius.event.client.ClientTickEvent;
import com.aquarius.feature.player.World;
import com.aquarius.mc.block.BlockPos;
import com.aquarius.mc.block.properties.api.BlockStateProperties;
import com.aquarius.module.api.Module;
import com.aquarius.network.codec.PacketHandlerCodec;
import com.aquarius.network.codec.PacketHandlerStateCodec;
import com.aquarius.util.ChatUtil;
import com.github.rfresh2.EventConsumer;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.Stash.Box;
import com.porter.qwertestash.stash.Stashes;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEntityDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundOpenSignEditorPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aquarius.Globals.CACHE;
import static com.aquarius.Globals.MODULE;
import static com.github.rfresh2.EventConsumer.of;
import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;
import static com.porter.qwertestash.QwerteStashPlugin.LOG;

/**
 * Keeps the saved stash up to date and answers whisper searches, whether or not sorting is running. When a container
 * in the stash is opened through the proxy, by you while connected or by the bot, what's inside is saved.
 */
public class StashTracker extends Module {
    // Whispers back are spaced out so the server doesn't kick us for spam
    private static final int REPLY_GAP_TICKS = 20 * 3;
    // A click this long before a container opens is the click that opened it
    private static final long CLICK_WINDOW_MS = 3000;
    // A container shown open for this long was never seen closing
    private static final long OPEN_FORGET_TICKS = 20 * 60 * 10;

    private final Queue<String[]> replies = new ArrayDeque<>();
    private int replyCooldown;

    /** A block that was right-clicked, and when. */
    private record Click(BlockPos pos, long at) {}

    private volatile @Nullable Click click;
    // The click a container has been filed under, so one click is only ever used for one container
    private @Nullable Click consumed;

    private int trackedContainer;
    private @Nullable Box trackedBox;

    // Other players using containers. The server tells everyone nearby when a chest lid moves (how many are looking in
    // it) and when a barrel opens or closes. That arrives on the network thread, so it's only queued there
    private record Access(BlockPos pos, int viewers) {}

    private final Queue<Access> accesses = new ConcurrentLinkedQueue<>();

    /** One container of the stash being looked into: per block (a double chest reports both halves), and since when it's closed. */
    private static final class Watch {
        final Map<BlockPos, Integer> viewers = new HashMap<>();
        boolean foreign;
        long closedAt = -1;
        long openedAt;

        int total() {
            int max = 0;
            for (int count : viewers.values()) max = Math.max(max, count);
            return max;
        }
    }

    private final Map<Box, Watch> watches = new HashMap<>();
    private long accessTicks;

    @Override
    public boolean enabledSetting() {
        return CONFIG.tracking;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientTickEvent.class, this::onTick),
            of(WhisperChatEvent.class, this::onWhisper),
            of(ClientDisconnectEvent.class, e -> onDisconnect())
        );
    }

    // Which block you right-clicked while connected, to know which container you opened
    @Override
    public PacketHandlerCodec registerServerPacketHandlerCodec() {
        return PacketHandlerCodec.serverBuilder()
            .setId("qwerte-stash-tracker")
            .setPriority(-1000)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.serverBuilder()
                .inbound(ServerboundUseItemOnPacket.class, (packet, session) -> {
                    clicked(new BlockPos(packet.getX(), packet.getY(), packet.getZ()));
                    return packet;
                })
                .build())
            .build();
    }

    // What the server sends to everyone near a chest or barrel that someone opens or closes. Seen, never changed.
    // Whatever goes wrong in here must not reach the packet pipeline: the packet is passed on regardless
    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId("qwerte-stash-watch")
            .setPriority(-1000)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                .inbound(ClientboundBlockEventPacket.class, (packet, session) -> {
                    try {
                        // Chests, trapped chests and shulker boxes: event 1 is "this many are looking in it"
                        if (CONFIG.watchAccess && packet.getRawType() == 1) {
                            accesses.add(new Access(new BlockPos(packet.getX(), packet.getY(), packet.getZ()), packet.getRawValue()));
                        }
                    } catch (RuntimeException e) {
                        watchFailed(e);
                    }
                    return packet;
                })
                .inbound(ClientboundBlockUpdatePacket.class, (packet, session) -> {
                    try {
                        if (CONFIG.watchAccess) barrelChanged(packet.getEntry());
                        if (CONFIG.watchSigns) signSpotChanged(packet.getEntry().getX(), packet.getEntry().getY(), packet.getEntry().getZ());
                    } catch (RuntimeException e) {
                        watchFailed(e);
                    }
                    return packet;
                })
                .inbound(ClientboundSectionBlocksUpdatePacket.class, (packet, session) -> {
                    try {
                        for (BlockChangeEntry entry : packet.getEntries()) {
                            if (CONFIG.watchAccess) barrelChanged(entry);
                            if (CONFIG.watchSigns) signSpotChanged(entry.getX(), entry.getY(), entry.getZ());
                        }
                    } catch (RuntimeException e) {
                        watchFailed(e);
                    }
                    return packet;
                })
                // The server tells us to write on a sign we just put up
                .inbound(ClientboundOpenSignEditorPacket.class, (packet, session) -> {
                    try {
                        BlockPos awaited = awaitedEditor;
                        if (awaited != null) {
                            // Wherever it opened: the sorter decides whether that is a sign of its own
                            editorAt = new BlockPos(packet.getX(), packet.getY(), packet.getZ());
                            editorFront = packet.isFrontText();
                        }
                        if (awaited != null && packet.isFrontText() && packet.getX() == awaited.x() && packet.getY() == awaited.y() && packet.getZ() == awaited.z()) {
                            editorOpened = true;
                        }
                        else if (awaited != null) {
                            LOG.info("Qwerte Stash: a sign editor opened at {} {} {} (front: {}), not the one awaited at {}.",
                                packet.getX(), packet.getY(), packet.getZ(), packet.isFrontText(), awaited);
                        }
                    } catch (RuntimeException e) {
                        watchFailed(e);
                    }
                    return packet;
                })
                // A sign's text arrives on its own, after the block: the text of a new sign, or an edit of one
                .inbound(ClientboundBlockEntityDataPacket.class, (packet, session) -> {
                    try {
                        BlockEntityType type = packet.getType();
                        if (CONFIG.watchSigns && (type == BlockEntityType.SIGN || type == BlockEntityType.HANGING_SIGN)) {
                            signSpotChanged(packet.getX(), packet.getY(), packet.getZ());
                        }
                    } catch (RuntimeException e) {
                        watchFailed(e);
                    }
                    return packet;
                })
                .build())
            .build();
    }

    // Signs: where one can be for a container of the stash, set by the sorter (a fresh immutable set each time), and
    // the places in it that something happened at. The cache is only updated after this handler, so it's just queued
    private volatile Set<BlockPos> watchedSigns = Set.of();
    private final Queue<BlockPos> signEvents = new ConcurrentLinkedQueue<>();
    private final AtomicInteger signQueued = new AtomicInteger();
    private static final int MAX_SIGN_EVENTS = 4096;

    // A sign the sorter has just put up and is waiting for the server to open the editor of
    private volatile BlockPos awaitedEditor;
    private volatile boolean editorOpened;

    // Where the last editor opened while one was awaited, and for which side of the sign
    private volatile BlockPos editorAt;
    private volatile boolean editorFront = true;

    public void awaitEditor(BlockPos pos) {
        editorOpened = false;
        editorAt = null;
        awaitedEditor = pos;
    }

    public BlockPos editorAt() {
        return editorAt;
    }

    public boolean editorFront() {
        return editorFront;
    }

    public boolean editorOpened() {
        return editorOpened;
    }

    public void stopAwaitingEditor() {
        awaitedEditor = null;
        editorOpened = false;
    }

    public void watchSigns(Set<BlockPos> spots) {
        watchedSigns = spots;
    }

    /**
     * Hands over, once each, the sign places something happened at since the last call.
     */
    public void drainSignEvents(Consumer<BlockPos> then) {
        BlockPos pos;
        while ((pos = signEvents.poll()) != null) {
            signQueued.decrementAndGet();
            then.accept(pos);
        }
    }

    private void signSpotChanged(int x, int y, int z) {
        Set<BlockPos> watched = watchedSigns;
        if (watched.isEmpty()) return;
        BlockPos pos = new BlockPos(x, y, z);
        if (watched.contains(pos) && signQueued.get() < MAX_SIGN_EVENTS) {
            signQueued.incrementAndGet();
            signEvents.add(pos);
        }
    }

    private volatile boolean watchErrorLogged;

    private void watchFailed(RuntimeException e) {
        if (watchErrorLogged) return;
        watchErrorLogged = true;
        LOG.debug("Qwerte Stash: couldn't read a block packet for the chest watch", e);
    }

    /**
     * Barrels have no lid event, their open state is part of the block.
     */
    private void barrelChanged(BlockChangeEntry entry) {
        int state = entry.getBlock();
        if (!World.getBlock(state).name().equals("barrel") || !World.hasBlockStateProperty(state, BlockStateProperties.OPEN)) return;

        Boolean open = World.getBlockStateProperty(state, BlockStateProperties.OPEN);
        accesses.add(new Access(new BlockPos(entry.getX(), entry.getY(), entry.getZ()), open != null && open ? 1 : 0));
    }

    /**
     * Sorts what was queued into who is looking in which container of the stash. One that somebody other than the bot
     * or you looked into and that has stayed closed for a while is handed to the sorter to be read again.
     */
    private void tickAccess(@Nullable Stash stash) {
        accessTicks++;

        Access access;
        while ((access = accesses.poll()) != null) {
            Box box = stash == null || !CONFIG.watchAccess ? null : stash.boxAt(access.pos());
            if (box == null || box.excluded) continue;

            Watch watch = watches.computeIfAbsent(box, b -> new Watch());
            int before = watch.total();
            watch.viewers.put(access.pos(), access.viewers());
            int after = watch.total();

            if (before == 0 && after > 0) watch.openedAt = accessTicks;
            // Somebody new looking in: the bot's own click, or the connected player's, opened it themselves
            if (after > before && !openedByUs(box)) watch.foreign = true;
            watch.closedAt = after > 0 ? -1 : watch.closedAt < 0 ? accessTicks : watch.closedAt;
        }

        Iterator<Map.Entry<Box, Watch>> it = watches.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Box, Watch> entry = it.next();
            Watch watch = entry.getValue();
            Box box = entry.getKey();
            if (stash == null || !stash.boxes.contains(box)) {
                it.remove();
            }
            else if (watch.total() > 0) {
                // Still shown as open after ten minutes: the close was missed, most likely out of range. Forgotten
                if (accessTicks - watch.openedAt > OPEN_FORGET_TICKS) it.remove();
            }
            else if (!watch.foreign) {
                it.remove();
            }
            else if (accessTicks - watch.closedAt >= CONFIG.accessRescanSeconds * 20L) {
                it.remove();
                MODULE.get(StashSorter.class).accessed(box.pos);
            }
        }
    }

    /**
     * The bot opened it just now, or you did through the proxy, in which case it's read as you close it.
     */
    private boolean openedByUs(Box box) {
        Click clicked = click;
        if (clicked != null && System.currentTimeMillis() - clicked.at() < CLICK_WINDOW_MS && box.has(clicked.pos())) return true;
        return MODULE.get(StashSorter.class).openedByUs(box);
    }

    /**
     * Remembers a container about to be opened, by you or by the sorter.
     */
    public void clicked(BlockPos pos) {
        click = new Click(pos, System.currentTimeMillis());
    }

    public @Nullable BlockPos lastClicked() {
        Click c = click;
        return c == null ? null : c.pos();
    }

    private void onDisconnect() {
        Stashes.reset();
        trackedContainer = 0;
        trackedBox = null;
        replies.clear();
        accesses.clear();
        watches.clear();
        signEvents.clear();
        signQueued.set(0);
        awaitedEditor = null;
        watchedSigns = Set.of();
    }

    private void onTick(ClientTickEvent event) {
        if (replyCooldown > 0) replyCooldown--;
        else if (!replies.isEmpty()) {
            String[] reply = replies.poll();
            sendClientPacketAsync(new ServerboundChatPacket(ChatUtil.sanitizeChatMessage("/whisper " + reply[0] + " " + reply[1])));
            replyCooldown = REPLY_GAP_TICKS;
        }

        Stash stash = Stashes.current();
        tickAccess(stash);

        // The sorter reads the containers it opens itself
        if (MODULE.get(StashSorter.class).isWorking()) return;

        if (stash == null) return;

        var inventory = CACHE.getPlayerCache().getInventoryCache();
        int id = inventory.getOpenContainerId();
        Container container = id == 0 ? null : inventory.getOpenContainer();

        if (container != null && Stashes.slots(container) > 0) {
            if (id != trackedContainer) {
                trackedContainer = id;
                trackedBox = null;

                // Only a window that opened after the click, soon after it, that no earlier window was filed under,
                // and of the size the clicked box has
                Click clicked = click;
                long openedAt = inventory.getContainerOpenedAt();
                if (clicked != null && clicked != consumed && openedAt >= clicked.at()) {
                    consumed = clicked;
                    Box box = openedAt - clicked.at() < CLICK_WINDOW_MS ? stash.boxAt(clicked.pos()) : null;
                    if (box != null && Stashes.slots(container) == box.parts.size() * 27) trackedBox = box;
                }
            }

            if (trackedBox != null && Stashes.changed(trackedBox, container)) Stashes.read(stash, trackedBox, container);
        }
        else if (trackedContainer != 0) {
            trackedContainer = 0;
            trackedBox = null;
            if (stash.isDirty()) stash.save();
        }
    }

    // Whisper search

    private void onWhisper(WhisperChatEvent event) {
        if (event.outgoing() || !CONFIG.whisperSearch || event.sender() == null) return;

        String sender = event.sender().getName();
        if (sender == null || CONFIG.whitelist.stream().noneMatch(name -> name.equalsIgnoreCase(sender))) return;

        String message = event.message().trim();
        String query = message;
        String p = CONFIG.prefix.trim();
        if (!p.isEmpty()) {
            if (!message.toLowerCase(Locale.ROOT).startsWith(p.toLowerCase(Locale.ROOT) + " ")) return;
            query = message.substring(p.length()).trim();
        }
        if (query.isEmpty()) return;

        Stash stash = Stashes.current();
        if (stash == null) return;

        replies.add(new String[] {sender, answer(stash, query)});
        info("{} searched the stash for \"{}\".", sender, query);
    }

    /**
     * The whisper reply for a search: where the best match is.
     */
    public static String answer(Stash stash, String query) {
        List<Stash.Result> results = stash.search(query);
        if (results.isEmpty()) return String.format("Couldn't find %s @ %s", query, stash.name);

        Stash.Result result = results.getFirst();
        Stash.Spot spot = result.best();
        BlockPos pos = spot.box().pos;
        String where = spot.inShulker() ? " (in a shulker)" : "";
        return String.format("%s Can be Found At %d, %d, %d @ %s%s", result.sample.label(), pos.x(), pos.y(), pos.z(), stash.name, where);
    }
}
