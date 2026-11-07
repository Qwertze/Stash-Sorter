package com.porter.qwertestash.orders;

import com.porter.qwertestash.orders.DeliveryBook.Pos;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where the next chests of the delivery storage go (1.9.0), and where the bot can stand to put them there. Pure: the world
 * comes in through {@link View}, so the rules are tested without the game.
 * <p>
 * The rule: the next row is the top row moved one block up. A double chest gets a double chest above it (above both halves,
 * the same way round), a single chest or a barrel a single chest. It goes up for as long as the new chests could be opened:
 * the build height limit, something in the cells, something solid above them (a lid does not open under a solid block), and
 * nowhere to stand that can reach them all stop it, each with its reason.
 * <p>
 * Placing, as the game does it (ChestBlock.getStateForPlacement), and why it is done this way:
 * <ul>
 *     <li>A chest is not placed against a chest by a plain click, that opens it. The bot sneaks, and a sneaking click on the top
 *     face of the chest below puts a single chest up there, facing away from where the bot looks: the bot stands on the side
 *     of the chest it wants the front on (facing = the opposite of the way the bot looks).</li>
 *     <li>A sneaking click on the side face of that chest, from outside it, puts the second chest next to it as the other half
 *     of the same double chest, with the facing of the first. So the second half does not depend on where the bot looks.</li>
 *     <li>The top face of a chest is only in sight from an eye above it. Standing on the floor (sneaking eyes 1.27 above the
 *     feet) that is the first row above the base only; higher rows need a stand that is higher (a step, a ledge): no stand,
 *     no row, and the reason says so.</li>
 * </ul>
 */
public final class ChestStack {
    private ChestStack() {
    }

    /** Eyes of a sneaking player above its feet. */
    public static final double SNEAK_EYE = 1.27;
    /** How far a click may reach (the game allows 4.5 from the eyes; this keeps clear of the edge). */
    public static final double REACH = 4.0;
    /** The top of a chest's hit box above its block's floor, and the inset of its sides from the block's edge. */
    public static final double CHEST_TOP = 0.875, CHEST_SIDE = 0.4375;
    /** How far from the column a stand is looked for, and how far below and above the chest below it. */
    public static final int STAND_RADIUS = 3, STAND_DOWN = 1, STAND_UP = 5;

    public interface View {
        /** The block's chunk is loaded. */
        boolean loaded(int x, int z);

        /** Empty (or replaceable) space a chest can be put in. */
        boolean air(int x, int y, int z);

        /** A block that keeps the lid of a chest under it from opening (a redstone conductor). */
        boolean conductor(int x, int y, int z);

        /** What block is there, for the reason given (null if not known). */
        @Nullable String name(int x, int y, int z);

        /** The bot can stand with its feet in this cell. */
        boolean standable(int x, int y, int z);

        /** The lowest and the highest-plus-one y a block can be at. */
        int minY();

        int maxY();
    }

    /** Where a stand is, and the way the front of the first chest then faces (a unit step: x and z). */
    public record Stand(Pos cell, int facingX, int facingZ) {
    }

    /**
     * The next row.
     * @param first       where the first chest goes (above the first half of the top chest)
     * @param second      where the second half goes (a double chest), or null
     * @param firstStands where to stand to put the first chest up (top face of the chest below), nearest first
     * @param secondStands where to stand for the second half (side face of the first), nearest first; empty for a single
     */
    public record Plan(Pos first, @Nullable Pos second, List<Stand> firstStands, List<Pos> secondStands) {
    }

    /** The plan, or why there is none ("blocked": the reason, without the "can't place higher: " lead). */
    public record Result(@Nullable Plan plan, @Nullable String blocked) {
    }

    /**
     * @param topParts  the blocks of the top chest: one (single, or a barrel) or two (a double chest), the same y
     * @param facingX   which way the chest's front faces (unit step; 0, 0 if it has none: a barrel or an unknown)
     * @param belowIsChest the chest below is a chest (top 0.875) and not a barrel (top 1.0)
     * @param lowestY   y of the lowest chest of the storage (for the reason)
     * @param near      the bot's feet: stands are ordered by how far they are from it
     */
    public static Result next(List<Pos> topParts, int facingX, int facingZ, boolean belowIsChest, int lowestY, Pos near, View v) {
        if (topParts.isEmpty() || topParts.size() > 2) return new Result(null, "the top chest is not a single or a double chest");
        int y = topParts.get(0).y();
        int ny = y + 1;
        if (ny >= v.maxY()) return new Result(null, "the build height limit is reached");

        Pos first = new Pos(topParts.get(0).x(), ny, topParts.get(0).z());
        Pos second = topParts.size() == 2 ? new Pos(topParts.get(1).x(), ny, topParts.get(1).z()) : null;
        for (Pos p : second == null ? List.of(first) : List.of(first, second)) {
            if (!v.loaded(p.x(), p.z())) return new Result(null, "the chunk above the top chest is not loaded");
            if (!v.air(p.x(), p.y(), p.z())) {
                String n = v.name(p.x(), p.y(), p.z());
                return new Result(null, (n == null ? "a block" : n) + " is in the way above the top chest");
            }
            // The new chest's lid: a solid block right above it keeps it shut
            if (p.y() + 1 < v.maxY() && v.conductor(p.x(), p.y() + 1, p.z())) {
                String n = v.name(p.x(), p.y() + 1, p.z());
                return new Result(null, (n == null ? "a solid block" : n) + " above the next chest would keep its lid from opening");
            }
        }

        double top = y + (belowIsChest ? CHEST_TOP : 1.0);
        double aimX = first.x() + 0.5, aimZ = first.z() + 0.5;
        List<Stand> stands = new ArrayList<>();
        for (int dx = -STAND_RADIUS; dx <= STAND_RADIUS; dx++) {
            for (int dz = -STAND_RADIUS; dz <= STAND_RADIUS; dz++) {
                for (int dy = -STAND_DOWN; dy <= STAND_UP; dy++) {
                    int cx = first.x() + dx, cz = first.z() + dz, cy = y + dy;
                    if (cy < v.minY() || !v.loaded(cx, cz) || occupied(cx, cy, cz, topParts, first, second)) continue;
                    if (!v.standable(cx, cy, cz)) continue;
                    double ex = cx + 0.5, ey = cy + SNEAK_EYE, ez = cz + 0.5;
                    // The top face is in sight from an eye above it, near enough to click, not from straight above the column
                    if (ey < top + 0.1) continue;
                    double horizontal = Math.hypot(aimX - ex, aimZ - ez);
                    if (horizontal < 0.9 || Math.sqrt(horizontal * horizontal + (ey - top) * (ey - top)) > REACH) continue;
                    // The way the bot looks decides the way the chest faces: the opposite of it. Diagonals are ambiguous
                    double lx = aimX - ex, lz = aimZ - ez;
                    if (Math.abs(Math.abs(lx) - Math.abs(lz)) < 0.2) continue;
                    int fx = 0, fz = 0;
                    if (Math.abs(lx) > Math.abs(lz)) fx = lx > 0 ? -1 : 1;
                    else fz = lz > 0 ? -1 : 1;
                    // A double chest goes up facing the same or the opposite way as the one below, never across it
                    if (second != null && (facingX != 0 || facingZ != 0) && fx * facingX + fz * facingZ == 0) continue;
                    stands.add(new Stand(new Pos(cx, cy, cz), fx, fz));
                }
            }
        }
        if (stands.isEmpty()) {
            return new Result(null, "out of reach: nowhere to stand where the top of the chest below can be clicked");
        }
        stands.sort(Comparator.comparingDouble(s -> dist2(s.cell(), near)));

        List<Pos> secondStands = new ArrayList<>();
        if (second != null) {
            int ddx = Integer.signum(second.x() - first.x()), ddz = Integer.signum(second.z() - first.z());
            for (int dx = -STAND_RADIUS - 1; dx <= STAND_RADIUS + 1; dx++) {
                for (int dz = -STAND_RADIUS - 1; dz <= STAND_RADIUS + 1; dz++) {
                    for (int dy = -STAND_DOWN; dy <= STAND_UP; dy++) {
                        int cx = first.x() + dx, cz = first.z() + dz, cy = y + dy;
                        if (cy < v.minY() || !v.loaded(cx, cz) || occupied(cx, cy, cz, topParts, first, second)) continue;
                        if (!v.standable(cx, cy, cz)) continue;
                        if (sideClickable(cx, cy, cz, first, second, ddx, ddz)) secondStands.add(new Pos(cx, cy, cz));
                    }
                }
            }
            if (secondStands.isEmpty()) {
                return new Result(null, "out of reach: nowhere to stand where the side of the first chest can be clicked for the second half");
            }
            secondStands.sort(Comparator.comparingDouble(s -> dist2(s, near)));
        }
        return new Result(new Plan(first, second, stands, secondStands), null);
    }

    private static boolean occupied(int x, int y, int z, List<Pos> top, Pos first, @Nullable Pos second) {
        // The chests, the cells above them where the new ones go, and the cell the bot's head would be in above them
        for (Pos p : top) if (p.x() == x && p.z() == z && y >= p.y() && y <= p.y() + 2) return true;
        if (first.x() == x && first.z() == z && y >= first.y() - 1 && y <= first.y() + 1) return true;
        return second != null && second.x() == x && second.z() == z && y >= second.y() - 1 && y <= second.y() + 1;
    }

    /**
     * Whether a bot with its feet in this cell sees the side face of the first chest that looks at the second one, from outside
     * it, in reach, and through the cell the second chest goes in.
     */
    static boolean sideClickable(int cx, int cy, int cz, Pos first, Pos second, int ddx, int ddz) {
        double ex = cx + 0.5, ey = cy + SNEAK_EYE, ez = cz + 0.5;
        // The middle of that face
        double px = first.x() + 0.5 + ddx * CHEST_SIDE, py = first.y() + 0.5, pz = first.z() + 0.5 + ddz * CHEST_SIDE;
        double vx = ex - px, vy = ey - py, vz = ez - pz;
        // From outside the face: on the side the second chest is
        double along = vx * ddx + vz * ddz;
        if (along <= 0.15) return false;
        if (Math.sqrt(vx * vx + vy * vy + vz * vz) > REACH) return false;
        // Through the middle of the second chest's cell: where the sight line crosses it, it must be inside the cell
        double t = 0.5625 / along;
        double sx = px + vx * t, sy = py + vy * t, sz = pz + vz * t;
        if (sy < second.y() + 0.02 || sy > second.y() + 0.98) return false;
        return Math.abs(sx - (second.x() + 0.5)) <= 0.48 && Math.abs(sz - (second.z() + 0.5)) <= 0.48;
    }

    private static double dist2(Pos a, Pos b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    // Aim: the look that clicks a point, from the eyes of a sneaking bot with its feet at (px, py, pz)

    /** Yaw in the game's degrees (0 south, 90 west) from the eyes toward the point. */
    public static float yawTo(double fromX, double fromZ, double toX, double toZ) {
        return (float) (Math.toDegrees(Math.atan2(toZ - fromZ, toX - fromX)) - 90.0);
    }

    /** Pitch in degrees (positive looks down) from the eyes toward the point. */
    public static float pitchTo(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double horizontal = Math.hypot(toX - fromX, toZ - fromZ);
        return (float) -Math.toDegrees(Math.atan2(toY - fromY, horizontal));
    }
}
