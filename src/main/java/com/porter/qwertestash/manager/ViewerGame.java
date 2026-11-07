package com.porter.qwertestash.manager;

import com.aquarius.Proxy;
import com.aquarius.cache.data.chunk.Chunk;
import com.aquarius.cache.data.entity.Entity;
import com.aquarius.cache.data.entity.EntityLiving;
import com.aquarius.cache.data.entity.EntityPlayer;
import com.aquarius.feature.map.Brightness;
import com.aquarius.mc.block.Block;
import com.aquarius.mc.dimension.DimensionData;
import com.aquarius.mc.item.ItemData;
import com.aquarius.mc.item.ItemRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.porter.qwertestash.module.GroundItems;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.Attribute;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeModifier;
import org.geysermc.mcprotocollib.protocol.data.game.entity.attribute.AttributeType;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.aquarius.Globals.CACHE;

/**
 * The viewer's window onto the proxy: reads the caches from the viewer thread. The network thread keeps writing them, so
 * every read is made to fail soft (the feed catches what gets through).
 */
public final class ViewerGame implements ViewerFeed.Game {
    private final Columns columns = new Columns();

    @Override
    public ViewerFeed.@Nullable World world() {
        Proxy proxy = Proxy.getInstance();
        if (proxy == null || !proxy.isConnected() || proxy.isInQueue()) return null;
        DimensionData dim = CACHE.getChunkCache().getCurrentDimension();
        if (dim == null || dim.name() == null) return null;
        return new ViewerFeed.World(ManagerIndex.namespaced(dim.name()).toLowerCase(Locale.ROOT), dim.minY(), dim.height());
    }

    @Override
    public ViewerFeed.@Nullable Self self() {
        var p = CACHE.getPlayerCache();
        return new ViewerFeed.Self(p.getX(), p.getY(), p.getZ(), p.getYaw());
    }

    @Override
    public List<ViewerFeed.Ent> entities(int chunkX, int chunkZ, int radius) {
        List<ViewerFeed.Ent> out = new ArrayList<>();
        int own = CACHE.getPlayerCache().getEntityId();
        for (Entity e : CACHE.getEntityCache().snapshot()) {
            try {
                if (e == null || e.isRemoved() || e.getEntityId() == own) continue;
                // Outside the radius before anything else about it is looked up (tab list, item registry)
                if (Math.abs(((int) Math.floor(e.getX()) >> 4) - chunkX) > radius || Math.abs(((int) Math.floor(e.getZ()) >> 4) - chunkZ) > radius) continue;
                if (e instanceof EntityPlayer pl && pl.isSelfPlayer()) continue;
                EntityType type = e.getEntityType();
                if (type == null) continue;
                String kind;
                String name = null, typeName = null;
                int count = 0;
                if (e instanceof EntityPlayer pl) {
                    kind = "player";
                    name = CACHE.getTabListCache().get(pl.getUuid()).map(PlayerListEntry::getName).orElse("?");
                }
                else if (type == EntityType.ITEM) {
                    kind = "item";
                    ItemStack stack = GroundItems.stackOf(e);
                    ItemData data = stack == null || stack.getAmount() <= 0 ? null : ItemRegistry.REGISTRY.get(stack.getId());
                    if (data != null) {
                        typeName = ManagerIndex.namespaced(data.name()).toLowerCase(Locale.ROOT);
                        count = stack.getAmount();
                    }
                }
                else if (e instanceof EntityLiving) {
                    kind = "mob";
                    typeName = type.name().toLowerCase(Locale.ROOT);
                }
                else {
                    String n = type.name();
                    // Item frames, displays and the like would be hundreds in a stash and tell nothing
                    if (n.contains("FRAME") || n.contains("DISPLAY") || n.equals("MARKER") || n.equals("INTERACTION") || n.equals("TEXT_DISPLAY")) continue;
                    kind = "other";
                    typeName = n.toLowerCase(Locale.ROOT);
                }
                out.add(new ViewerFeed.Ent(e.getEntityId(), kind, name, typeName, count, e.getX(), e.getY(), e.getZ(), Math.round(e.getYaw())));
            } catch (RuntimeException ex) {
                // The network thread changed it under us: left out of this frame
            }
        }
        return out;
    }

    @Override
    public @Nullable JsonObject hud() {
        var cache = CACHE.getPlayerCache();
        EntityPlayer me = cache.getThePlayer();
        // The player is not there yet: still a hud (empty slots, no numbers), a full frame always has one
        if (me == null) {
            JsonObject empty = new JsonObject();
            empty.addProperty("held", 0);
            JsonArray hotbar = new JsonArray(), armorSlots = new JsonArray();
            for (int i = 0; i < 9; i++) hotbar.add(JsonNull.INSTANCE);
            for (int i = 0; i < 4; i++) armorSlots.add(JsonNull.INSTANCE);
            empty.add("hotbar", hotbar);
            empty.add("armorSlots", armorSlots);
            empty.add("offhand", JsonNull.INSTANCE);
            empty.add("inv", ViewerFeed.hudSlots(i -> JsonNull.INSTANCE, ViewerFeed.INV_FIRST, ViewerFeed.INV_LAST));
            return empty;
        }
        JsonObject o = new JsonObject();
        if (me.getHealth() != null) o.addProperty("hp", Math.round(me.getHealth() * 10) / 10.0);
        o.addProperty("food", me.getFood());
        o.addProperty("sat", Math.round(me.getSaturation() * 10) / 10.0);
        o.addProperty("xp", me.getLevel());
        o.addProperty("xpP", Math.round(me.getExperience() * 100) / 100.0);
        Double armor = armor(me);
        if (armor != null) o.addProperty("armor", Math.round(armor));
        int held = cache.getHeldItemSlot();
        if (held >= 0 && held <= 8) o.addProperty("held", held);
        List<ItemStack> inv = cache.getPlayerInventory();
        JsonArray hotbar = new JsonArray();
        for (int i = 36; i <= 44; i++) hotbar.add(stack(inv, i));
        o.add("hotbar", hotbar);
        JsonArray armorSlots = new JsonArray();
        for (int i = 5; i <= 8; i++) armorSlots.add(stack(inv, i));
        o.add("armorSlots", armorSlots);
        o.add("offhand", stack(inv, 45));
        // The main inventory, slots 9-35; part of the hud, so a change there is a hud change (sent on the next tick)
        o.add("inv", ViewerFeed.hudSlots(i -> stack(inv, i), ViewerFeed.INV_FIRST, ViewerFeed.INV_LAST));
        return o;
    }

    /** The ARMOR attribute with its modifiers applied (the way the game works it out), or null when the proxy has none. */
    private static @Nullable Double armor(EntityPlayer me) {
        Attribute a = me.getAttributes().get(AttributeType.Builtin.ARMOR);
        if (a == null) return null;
        double base = a.getValue(), add = 0, mulBase = 0, mulTotal = 1;
        for (AttributeModifier m : new ArrayList<>(a.getModifiers())) {
            switch (m.getOperation()) {
                case ADD -> add += m.getAmount();
                case ADD_MULTIPLIED_BASE -> mulBase += m.getAmount();
                case ADD_MULTIPLIED_TOTAL -> mulTotal *= 1 + m.getAmount();
            }
        }
        double v = (base + add) + base * mulBase;
        return Math.max(0, Math.min(30, v * mulTotal));
    }

    private static JsonElement stack(List<ItemStack> inv, int slot) {
        if (slot >= inv.size()) return JsonNull.INSTANCE;
        ItemStack s = inv.get(slot);
        if (s == null || s.getAmount() <= 0) return JsonNull.INSTANCE;
        ItemData data = ItemRegistry.REGISTRY.get(s.getId());
        if (data == null || s.getId() == ItemRegistry.AIR.id()) return JsonNull.INSTANCE;
        // Durability read the way the index reads it (Item.durability)
        int[] dur = com.porter.qwertestash.stash.Item.durability(s, data);
        return ViewerFeed.hudItem(ViewerFeed.cut(ManagerIndex.namespaced(data.name()).toLowerCase(Locale.ROOT), 64), s.getAmount(), dur[0], dur[1]);
    }

    @Override
    public MapTiles.ColumnSource columns() {
        return columns;
    }

    /** Reads columns straight from the chunk cache (the cached heightmap goes stale, so the columns are scanned). */
    private static final class Columns implements MapTiles.ColumnSource {
        private static Chunk chunk(int x, int z) {
            return CACHE.getChunkCache().get(x >> 4, z >> 4);
        }

        @Override
        public boolean loaded(int chunkX, int chunkZ) {
            return CACHE.getChunkCache().get(chunkX, chunkZ) != null;
        }

        @Override
        public int minY() {
            return CACHE.getChunkCache().getCurrentDimension().minY();
        }

        @Override
        public int topY(int x, int z) {
            Chunk c = chunk(x, z);
            if (c == null) return MapTiles.NONE;
            ChunkSection[] sections = c.getSections();
            int minSection = c.getMinSection();
            int lx = x & 15, lz = z & 15;
            for (int i = sections.length - 1; i >= 0; i--) {
                ChunkSection s = sections[i];
                if (s == null || s.isBlockCountEmpty()) continue;
                int base = (minSection + i) << 4;
                for (int y = base + 15; y >= base; y--) {
                    if (colorOf(c.getBlockStateId(lx, y, lz)) != 0) return y;
                }
            }
            return MapTiles.NONE;
        }

        private static int colorOf(int stateId) {
            Block b = com.aquarius.Globals.BLOCK_DATA.getBlockDataFromBlockStateId(stateId);
            return b == null ? 0 : b.mapColorId();
        }

        @Override
        public int mapColor(int x, int y, int z) {
            Chunk c = chunk(x, z);
            return c == null ? 0 : colorOf(c.getBlockStateId(x & 15, y, z & 15));
        }

        @Override
        public boolean water(int x, int y, int z) {
            Chunk c = chunk(x, z);
            if (c == null) return false;
            Block b = com.aquarius.Globals.BLOCK_DATA.getBlockDataFromBlockStateId(c.getBlockStateId(x & 15, y, z & 15));
            return b != null && com.aquarius.feature.player.World.isWater(b);
        }

        @Override
        public byte packed(int mapColor, MapTiles.Shade shade) {
            Brightness b = switch (shade) {
                case HIGH -> Brightness.HIGH;
                case LOW -> Brightness.LOW;
                case NORMAL -> Brightness.NORMAL;
            };
            return com.aquarius.Globals.MAP_BLOCK_COLOR.getPackedId(mapColor, b);
        }
    }
}
