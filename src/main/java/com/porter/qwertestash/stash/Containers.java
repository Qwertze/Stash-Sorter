package com.porter.qwertestash.stash;

import com.aquarius.feature.player.World;
import com.aquarius.feature.player.raycast.RaycastHelper;
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
     * What a read of a container's signs found: the labels, and whether there is any sign block at all, blank or not
     * (a sign just placed has no text yet).
     */
    public record Signs(List<String> labels, boolean anyBlock) {
    }

    /**
     * Signs on the side of a container facing away from it, or standing on top of it. Each line is a label, and so
     * are all the lines of a sign read together, for labels split across lines. If a part or a sign position is in a
     * chunk that isn't loaded the labels are left as they are, a container out of range is not a container without
     * signs.
     */
    public static void readSigns(Box box) {
        Signs signs = readSignsIfLoaded(box);
        if (signs == null) return;
        box.labels.clear();
        box.labels.addAll(signs.labels());
    }

    /**
     * The signs of a container as they are now, or null if any of the chunks they could be in isn't loaded.
     */
    public static @Nullable Signs readSignsIfLoaded(Box box) {
        List<String> labels = new ArrayList<>();
        boolean anyBlock = false;

        for (BlockPos part : box.parts) {
            if (!isLoaded(part)) return null;

            for (Direction dir : Direction.HORIZONTALS) {
                BlockPos signPos = part.add(dir.x(), 0, dir.z());
                if (!isLoaded(signPos)) return null;

                String name = World.getBlock(signPos.x(), signPos.y(), signPos.z()).name();
                boolean hanging = name.endsWith("_wall_hanging_sign");
                if (!name.endsWith("_wall_sign") && !hanging) continue;
                // A wall hanging sign only counts when it can only be this container's
                if (hanging && sharedWithAnother(box, signPos)) continue;

                int state = World.getBlockStateId(signPos.x(), signPos.y(), signPos.z());
                Direction facing = World.getBlockStateProperty(state, BlockStateProperties.HORIZONTAL_FACING);
                if (facing == dir) {
                    anyBlock = true;
                    addLines(labels, signPos);
                }
            }

            BlockPos above = part.above();
            if (!isLoaded(above)) return null;
            String name = World.getBlock(above.x(), above.y(), above.z()).name();
            if (name.endsWith("_sign") && !name.endsWith("_wall_sign") && !name.contains("hanging")) {
                anyBlock = true;
                addLines(labels, above);
            }
        }
        return new Signs(labels, anyBlock);
    }

    /**
     * Every block a sign for this container can be at: beside each part, and on top of it. A change at any of them
     * can be a sign being put up, changed or taken down.
     */
    public static List<BlockPos> signSpots(Box box) {
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos part : box.parts) {
            for (Direction dir : Direction.HORIZONTALS) spots.add(part.add(dir.x(), 0, dir.z()));
            spots.add(part.above());
        }
        return spots;
    }

    private static void addLines(List<String> labels, BlockPos signPos) {
        CompoundTag nbt = blockEntityNbt(signPos);
        if (nbt == null) return;

        for (String side : List.of("front_text", "back_text")) {
            if (!(nbt.get(side) instanceof CompoundTag text) || !(text.get("messages") instanceof ListTag<?> messages)) continue;

            List<String> shown = new ArrayList<>();
            for (Tag message : messages) shown.add(plain(message));
            labels.addAll(labelsOf(shown));
        }
    }

    /**
     * The labels a sign face gives: every line that has text, and all of them joined when there is more than one. The
     * one place that decides it, for reading signs and for working out what a sign about to be written will say.
     */
    public static List<String> labelsOf(List<String> lines) {
        List<String> labels = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        int count = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            labels.add(line);
            if (!joined.isEmpty()) joined.append(' ');
            joined.append(line);
            count++;
        }
        if (count > 1) labels.add(joined.toString());
        return labels;
    }

    /**
     * A wall hanging sign at this place that could just as well belong to another container of the stash (one with a
     * part right beside it) is nobody's.
     */
    private static boolean sharedWithAnother(Box box, BlockPos signPos) {
        Stash stash = Stashes.current();
        if (stash == null) return true;
        for (Box other : stash.boxes) {
            if (other == box) continue;
            for (BlockPos part : other.parts) {
                if (part.y() == signPos.y() && Math.abs(part.x() - signPos.x()) + Math.abs(part.z() - signPos.z()) == 1) return true;
            }
        }
        return false;
    }

    /**
     * Whether an item is a sign the bot can put up on a container: a plain sign, never a hanging one.
     */
    public static boolean isSignItem(String id) {
        return id.endsWith("_sign") && !id.contains("hanging");
    }

    /**
     * A place for a sign on a container: the block the sign stands in, which side of the container it is on, and the
     * part of the container it is against.
     */
    public record Face(BlockPos sign, Direction dir, BlockPos part, BlockPos stand) {
    }

    // How far from the eyes to the middle of the chest's side a sign can be put up from, inside the reach of 4.5
    private static final double SIGN_REACH = 4.0;
    private static final double SNEAKING_EYE = 1.27;

    /**
     * A place to stand to put a sign at {@code sign} against {@code part}: on the side the sign is on, with a solid floor and
     * room for the head, clear of every container, close, and from where a ray from the sneaking eyes to the middle of the
     * side hits that part on that side. The closest to the sign first. Null if there is none: such a face is not used.
     */
    private static @Nullable BlockPos standFor(BlockPos sign, Direction dir, BlockPos part, Stash stash) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 1; dy++) {
                    BlockPos feet = sign.add(dx, dy, dz);
                    BlockPos head = feet.above();
                    BlockPos floor = feet.add(0, -1, 0);
                    if (!isLoaded(feet)) continue;
                    // On the side of the sign
                    if ((feet.x() - part.x()) * dir.x() + (feet.z() - part.z()) * dir.z() < 1) continue;
                    if (stash.boxAt(feet) != null || stash.boxAt(head) != null) continue;
                    if (!free(feet) || !free(head) || !standable(floor)) continue;
                    if (!seesSide(feet, dir, part)) continue;

                    double distance = dx * dx + dy * dy + dz * dz;
                    if (distance < bestDistance) {
                        best = feet;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static boolean free(BlockPos pos) {
        var block = World.getBlock(pos.x(), pos.y(), pos.z());
        if (block.isAir()) return true;
        // Water, lava, fire and the like count as replaceable too, but they're no place to stand
        String name = block.name();
        return block.replaceable() && !name.equals("water") && !name.equals("lava") && !name.endsWith("fire")
            && !name.equals("bubble_column");
    }

    /**
     * Something to stand on. Signs, cobwebs, plates and banners are solid but can't be stood on.
     */
    private static boolean standable(BlockPos pos) {
        var block = World.getBlock(pos.x(), pos.y(), pos.z());
        if (!block.solidBlock()) return false;
        String name = block.name();
        return !name.endsWith("_sign") && !name.equals("cobweb") && !name.endsWith("pressure_plate") && !name.endsWith("_banner");
    }

    /**
     * From the sneaking eyes standing at {@code feet}, the ray to a point just inside the middle of the side of the part
     * hits that part, on that side, within reach.
     */
    private static boolean seesSide(BlockPos feet, Direction dir, BlockPos part) {
        double ex = feet.x() + 0.5, ey = feet.y() + SNEAKING_EYE, ez = feet.z() + 0.5;
        double tx = part.x() + 0.5 + dir.x() * 0.4, ty = part.y() + 0.5, tz = part.z() + 0.5 + dir.z() * 0.4;
        double length = Math.sqrt((tx - ex) * (tx - ex) + (ty - ey) * (ty - ey) + (tz - ez) * (tz - ez));
        if (length > SIGN_REACH) return false;

        double scale = SIGN_REACH / length;
        var hit = RaycastHelper.blockRaycast(ex, ey, ez, ex + (tx - ex) * scale, ey + (ty - ey) * scale, ez + (tz - ez) * scale, false);
        return hit.hit() && hit.x() == part.x() && hit.y() == part.y() && hit.z() == part.z() && hit.direction() == dir;
    }

    /**
     * Where a sign can go on this container, best first: the side it faces (the front) of each part, then the other
     * sides. A place needs air to put the sign in, and not to be inside any container of the stash. Null if a chunk
     * isn't loaded.
     */
    public static @Nullable List<Face> signFaces(Box box, Stash stash) {
        List<Face> faces = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (BlockPos part : box.parts) {
                if (!isLoaded(part)) return null;
                int state = World.getBlockStateId(part.x(), part.y(), part.z());
                Direction front = World.hasBlockStateProperty(state, BlockStateProperties.HORIZONTAL_FACING)
                    ? World.getBlockStateProperty(state, BlockStateProperties.HORIZONTAL_FACING) : null;
                if (front == null && World.hasBlockStateProperty(state, BlockStateProperties.FACING)) {
                    Direction facing = World.getBlockStateProperty(state, BlockStateProperties.FACING);
                    if (facing != null && facing.x() * facing.x() + facing.z() * facing.z() == 1) front = facing;
                }

                for (Direction dir : Direction.HORIZONTALS) {
                    if ((pass == 0) != (dir == front)) continue;
                    BlockPos cell = part.add(dir.x(), 0, dir.z());
                    if (!isLoaded(cell)) return null;
                    if (!World.getBlock(cell.x(), cell.y(), cell.z()).isAir() || stash.boxAt(cell) != null) continue;
                    BlockPos stand = standFor(cell, dir, part, stash);
                    if (stand != null) faces.add(new Face(cell, dir, part, stand));
                }
            }
        }
        return faces;
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
