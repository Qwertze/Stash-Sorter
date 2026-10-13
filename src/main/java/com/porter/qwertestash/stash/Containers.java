package com.porter.qwertestash.stash;

import com.aquarius.feature.player.World;
import com.aquarius.mc.block.BlockPos;
import com.aquarius.mc.block.Direction;
import com.aquarius.mc.block.properties.ChestType;
import com.aquarius.mc.block.properties.api.BlockStateProperties;
import com.aquarius.util.ComponentSerializer;
import com.porter.qwertestash.stash.Stash.Box;
import com.viaversion.nbt.io.MNBTIO;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.nbt.tag.Tag;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Finding chests and barrels in the proxy's cached world, joining double chests, and reading the signs on them.
 */
public final class Containers {
    private Containers() {
    }

    public static boolean isContainer(BlockPos pos, boolean barrels) {
        if (!World.isChunkLoadedBlockPos(pos.x(), pos.z())) return false;
        String name = World.getBlock(pos.x(), pos.y(), pos.z()).name();
        return name.equals("chest") || name.equals("trapped_chest") || (barrels && name.equals("barrel"));
    }

    public static boolean isLoaded(BlockPos pos) {
        return World.isChunkLoadedBlockPos(pos.x(), pos.z());
    }

    /**
     * A container, with the other half when it's a double chest.
     */
    public static Box makeBox(BlockPos pos) {
        List<BlockPos> parts = new ArrayList<>(List.of(pos));
        BlockPos other = otherHalf(pos);
        if (other != null) parts.add(other);
        return new Box(pos, parts);
    }

    private static @Nullable BlockPos otherHalf(BlockPos pos) {
        int state = World.getBlockStateId(pos.x(), pos.y(), pos.z());
        if (!World.hasBlockStateProperty(state, BlockStateProperties.CHEST_TYPE)) return null;

        ChestType type = World.getBlockStateProperty(state, BlockStateProperties.CHEST_TYPE);
        Direction facing = World.getBlockStateProperty(state, BlockStateProperties.HORIZONTAL_FACING);
        if (type == null || type == ChestType.SINGLE || facing == null) return null;

        // Same as vanilla: the left half's partner is clockwise of where it faces, the right half's counter-clockwise
        Direction toward = type == ChestType.LEFT ? clockwise(facing) : clockwise(facing).invert();
        return pos.add(toward.x(), 0, toward.z());
    }

    private static Direction clockwise(Direction dir) {
        return switch (dir) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> dir;
        };
    }

    // Signs

    /**
     * Signs on the side of a container facing away from it, or standing on top of it. Each line is a label, and so
     * are all the lines of a sign read together, for labels split across lines.
     */
    public static void readSigns(Box box) {
        box.labels.clear();
        if (!isLoaded(box.pos)) return;

        for (BlockPos part : box.parts) {
            for (Direction dir : Direction.HORIZONTALS) {
                BlockPos signPos = part.add(dir.x(), 0, dir.z());
                String name = World.getBlock(signPos.x(), signPos.y(), signPos.z()).name();
                if (!name.endsWith("_wall_sign") || name.endsWith("hanging_sign")) continue;

                int state = World.getBlockStateId(signPos.x(), signPos.y(), signPos.z());
                Direction facing = World.getBlockStateProperty(state, BlockStateProperties.HORIZONTAL_FACING);
                if (facing == dir) addLines(box, signPos);
            }

            BlockPos above = part.above();
            String name = World.getBlock(above.x(), above.y(), above.z()).name();
            if (name.endsWith("_sign") && !name.endsWith("_wall_sign") && !name.contains("hanging")) addLines(box, above);
        }
    }

    private static void addLines(Box box, BlockPos signPos) {
        CompoundTag nbt = blockEntityNbt(signPos);
        if (nbt == null) return;

        for (String side : List.of("front_text", "back_text")) {
            if (!(nbt.get(side) instanceof CompoundTag text) || !(text.get("messages") instanceof ListTag<?> messages)) continue;

            StringBuilder joined = new StringBuilder();
            int lines = 0;
            for (Tag message : messages) {
                String line = plain(message).trim();
                if (line.isEmpty()) continue;
                box.labels.add(line);
                if (!joined.isEmpty()) joined.append(' ');
                joined.append(line);
                lines++;
            }
            if (lines > 1) box.labels.add(joined.toString());
        }
    }

    /**
     * A sign line as plain text. Lines are text components, sent as JSON in 1.21.4.
     */
    private static String plain(Tag message) {
        if (!(message instanceof StringTag string)) return "";
        String value = string.getValue();
        if (value == null || value.isBlank()) return "";
        try {
            return ComponentSerializer.serializePlain(ComponentSerializer.deserialize(value));
        } catch (RuntimeException e) {
            // Not JSON, just text
            return value.replaceAll("^\"|\"$", "");
        }
    }

    private static @Nullable CompoundTag blockEntityNbt(BlockPos pos) {
        var chunk = World.getChunk(pos.x() >> 4, pos.z() >> 4);
        if (chunk == null) return null;

        int rx = pos.x() & 15, rz = pos.z() & 15;
        List<BlockEntityInfo> entities = chunk.getBlockEntities();
        synchronized (entities) {
            for (BlockEntityInfo info : entities) {
                if (info.getX() != rx || info.getY() != pos.y() || info.getZ() != rz || info.getNbt() == null) continue;
                try {
                    return MNBTIO.read(info.getNbt()) instanceof CompoundTag tag ? tag : null;
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }
}
