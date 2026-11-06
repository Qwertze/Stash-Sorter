package com.porter.qwertestash.manager;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponent;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The keys the Stash Manager server knows items by (see stash-manager-server/PROTOCOL.md): the item id, and for a stack
 * with a component patch '#' and the first 8 hex digits of a SHA-1 of that patch. They only have to be stable for this
 * bot and stash: they are computed from the proxy's own wire encoding of each component, in the order of the
 * component's name, so two reads of the same stack give the same key whatever order the server sent the components in.
 * What a container or bundle holds, custom_data (added to most items by ViaVersion on some servers) and, for shulkers,
 * block_entity_data are not part of the key.
 */
public final class ItemKeys {
    private ItemKeys() {
    }

    /** The key and the variant (the same hash over the whole patch, contents included) of a stack. */
    public record Keys(String key, String variant) {
    }

    /**
     * @param id      the item id without a namespace, like {@code diamond_sword}
     * @param shulker a shulker box: block_entity_data is left out too
     */
    public static Keys of(String id, DataComponents components, boolean shulker) {
        return of(id, components, shulker, false);
    }

    /**
     * The key the stack would have without its custom name: an order shulker renamed at the anvil is still known by the key
     * it was planned with.
     */
    public static String keyWithoutName(String id, DataComponents components, boolean shulker) {
        return of(id, components, shulker, true).key();
    }

    private static Keys of(String id, DataComponents components, boolean shulker, boolean withoutName) {
        String base = "minecraft:" + id;
        if (components == null) return new Keys(base, base);
        Map<DataComponentType<?>, DataComponent<?, ?>> all = components.getDataComponents();
        if (all == null || all.isEmpty()) return new Keys(base, base);

        List<DataComponent<?, ?>> shown = new ArrayList<>();
        List<DataComponent<?, ?>> full = new ArrayList<>();
        for (DataComponent<?, ?> c : all.values()) {
            if (c == null || (withoutName && c.getType() == DataComponentTypes.CUSTOM_NAME)) continue;
            full.add(c);
            if (!left(c.getType(), shulker)) shown.add(c);
        }
        return new Keys(with(base, shown, 8), with(base, full, 16));
    }

    private static boolean left(DataComponentType<?> type, boolean shulker) {
        return type == DataComponentTypes.CUSTOM_DATA || type == DataComponentTypes.CONTAINER || type == DataComponentTypes.BUNDLE_CONTENTS
            || (shulker && type == DataComponentTypes.BLOCK_ENTITY_DATA);
    }

    private static String with(String base, List<DataComponent<?, ?>> parts, int digits) {
        if (parts.isEmpty()) return base;
        parts.sort(Comparator.comparing((DataComponent<?, ?> c) -> c.getType().getKey().asString()));
        MessageDigest sha = sha1();
        for (DataComponent<?, ?> c : parts) {
            sha.update(c.getType().getKey().asString().getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(bytes(c));
            sha.update((byte) 0xff);
        }
        return base + "#" + HexFormat.of().formatHex(sha.digest()).substring(0, digits);
    }

    /** The value as the protocol writes it, or a marker for a removed component (a null value). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static byte[] bytes(DataComponent<?, ?> c) {
        Object value = c.getValue();
        if (value == null) return new byte[] {'!'};
        ByteBuf buf = Unpooled.buffer();
        try {
            ((DataComponentType) c.getType()).writeDataComponent(buf, value);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } catch (RuntimeException | LinkageError e) {
            // A component this build cannot write: its text is the best that is left
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        } finally {
            buf.release();
        }
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
