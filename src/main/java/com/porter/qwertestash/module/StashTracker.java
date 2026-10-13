package com.porter.qwertestash.module;

import com.aquarius.cache.data.inventory.Container;
import com.aquarius.event.chat.WhisperChatEvent;
import com.aquarius.event.client.ClientDisconnectEvent;
import com.aquarius.event.client.ClientTickEvent;
import com.aquarius.mc.block.BlockPos;
import com.aquarius.module.api.Module;
import com.aquarius.network.codec.PacketHandlerCodec;
import com.aquarius.network.codec.PacketHandlerStateCodec;
import com.aquarius.util.ChatUtil;
import com.github.rfresh2.EventConsumer;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.Stash.Box;
import com.porter.qwertestash.stash.Stashes;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Queue;

import static com.aquarius.Globals.CACHE;
import static com.aquarius.Globals.MODULE;
import static com.github.rfresh2.EventConsumer.of;
import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;

/**
 * Keeps the saved stash up to date and answers whisper searches, whether or not sorting is running. When a container
 * in the stash is opened through the proxy, by you while connected or by the bot, what's inside is saved.
 */
public class StashTracker extends Module {
    // Whispers back are spaced out so the server doesn't kick us for spam
    private static final int REPLY_GAP_TICKS = 20 * 3;
    // A click this long before a container opens is the click that opened it
    private static final long CLICK_WINDOW_MS = 3000;

    private final Queue<String[]> replies = new ArrayDeque<>();
    private int replyCooldown;

    private volatile @Nullable BlockPos lastClicked;
    private volatile long lastClickedAt;

    private int trackedContainer;
    private @Nullable Box trackedBox;

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

    /**
     * Remembers a container about to be opened, by you or by the sorter.
     */
    public void clicked(BlockPos pos) {
        lastClicked = pos;
        lastClickedAt = System.currentTimeMillis();
    }

    public @Nullable BlockPos lastClicked() {
        return lastClicked;
    }

    private void onDisconnect() {
        Stashes.reset();
        trackedContainer = 0;
        trackedBox = null;
        replies.clear();
    }

    private void onTick(ClientTickEvent event) {
        if (replyCooldown > 0) replyCooldown--;
        else if (!replies.isEmpty()) {
            String[] reply = replies.poll();
            sendClientPacketAsync(new ServerboundChatPacket(ChatUtil.sanitizeChatMessage("/whisper " + reply[0] + " " + reply[1])));
            replyCooldown = REPLY_GAP_TICKS;
        }

        // The sorter reads the containers it opens itself
        if (MODULE.get(StashSorter.class).isWorking()) return;

        Stash stash = Stashes.current();
        if (stash == null) return;

        var inventory = CACHE.getPlayerCache().getInventoryCache();
        int id = inventory.getOpenContainerId();
        Container container = id == 0 ? null : inventory.getOpenContainer();

        if (container != null && Stashes.slots(container) > 0) {
            if (id != trackedContainer) {
                trackedContainer = id;
                BlockPos clicked = lastClicked;
                boolean recent = clicked != null && System.currentTimeMillis() - lastClickedAt < CLICK_WINDOW_MS;
                trackedBox = recent ? stash.boxAt(clicked) : null;
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
        return String.format("%s Can be Found At %d, %d, %d @ %s%s", result.sample.name, pos.x(), pos.y(), pos.z(), stash.name, where);
    }
}
