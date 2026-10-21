package kr.dalmaru.content;

import java.util.ArrayList;
import java.util.List;
import kr.dalmaru.account.TownZone;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.levelgen.Heightmap;

/** Small temporary arrival floors until authored village terrain replaces the pilot map. */
public final class VillagePlazas {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(VillagePlazas.class);
    private static final AttachmentType<List<String>> PREPARED = AttachmentRegistry.<List<String>>create(
            Identifier.parse("dalmaru:village_plazas"), builder -> builder.persistent(com.mojang.serialization.Codec.STRING.listOf()));

    private VillagePlazas() { }

    public static void initialize() { }

    public static void prepare(MinecraftServer server) {
        for (var village : VillageCatalog.current()) {
            var town = village.zone();
            var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(town.dimension())));
            if (level == null) throw new IllegalStateException("Missing village dimension: " + town.dimension());
            String key = village.id() + ":" + town.x() + ":" + town.y() + ":" + town.z();
            List<String> prepared = level.getAttached(PREPARED);
            String exitKey = key + ":exit-v3";
            if (prepared != null && prepared.contains(exitKey)) continue;
            boolean changed = (prepared == null || !prepared.contains(key)) && buildIfUnsafe(level, town);
            boolean isolated = false;
            if (enclosed(level, town)) {
                boolean exitBuilt = buildExit(level, town);
                changed |= exitBuilt;
                isolated = !exitBuilt;
            }
            if (changed) level.getChunkSource().save(true);
            var next = prepared == null ? new ArrayList<String>() : new ArrayList<>(prepared);
            if (!next.contains(key)) next.add(key);
            if (!next.contains(key + ":exit-v1")) next.add(key + ":exit-v1");
            if (!next.contains(key + ":exit-v2")) next.add(key + ":exit-v2");
            if (!isolated) next.remove(key + ":waypoint-exit-v1");
            if (isolated && !next.contains(key + ":waypoint-exit-v1")) next.add(key + ":waypoint-exit-v1");
            next.add(exitKey);
            level.setAttached(PREPARED, List.copyOf(next));
        }
    }

    public static String waypointExit(ServerLevel level, double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        List<String> prepared = level.getAttached(PREPARED);
        if (prepared == null) return null;
        for (var village : VillageCatalog.current()) {
            var town = village.zone(); int radius = Math.min(4, town.radius());
            String key = village.id() + ":" + town.x() + ":" + town.y() + ":" + town.z() + ":waypoint-exit-v1";
            if (prepared.contains(key) && x >= town.x() - radius + 1 && x < town.x() + radius
                    && z >= town.z() - radius + 1 && z < town.z() + radius && y >= town.y() && y < town.y() + 3) return village.id();
        }
        return null;
    }

    private static boolean enclosed(ServerLevel level, TownZone town) {
        var center = new BlockPos(town.x(), town.y(), town.z());
        int radius = Math.min(4, town.radius());
        if (!level.getBlockState(center.offset(0, 3, 0)).is(Blocks.GLASS)) return false;
        for (int offset = -radius; offset <= radius; offset++) for (int y = 0; y < 3; y++) {
            if (!level.getBlockState(center.offset(radius, y, offset)).is(Blocks.GLASS)
                    || !level.getBlockState(center.offset(-radius, y, offset)).is(Blocks.GLASS)
                    || !level.getBlockState(center.offset(offset, y, radius)).is(Blocks.GLASS)
                    || !level.getBlockState(center.offset(offset, y, -radius)).is(Blocks.GLASS)) return false;
        }
        return true;
    }

    private static BlockPos nearestLand(ServerLevel level, TownZone town) {
        BlockPos best = null;
        int distance = 256 * 256 + 1, plaza = Math.min(4, town.radius());
        for (int limit = 64; limit <= 256; limit *= 2) {
            for (int ring = 1; ring <= limit && ring * ring < distance; ring++)
            for (int x = -ring; x <= ring; x++) for (int z = -ring; z <= ring; z++) {
                if (Math.abs(x) != ring && Math.abs(z) != ring) continue;
                int squared = x * x + z * z;
                if (squared >= distance || squared > limit * limit || (limit > 64 && squared <= limit * limit / 4)
                        || (Math.abs(x) <= plaza && Math.abs(z) <= plaza)) continue;
                int wx = town.x() + x, wz = town.z() + z;
                var feet = new BlockPos(wx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz), wz);
                if (dry(level, feet)) { best = feet; distance = squared; }
            }
            if (best != null) return best;
        }
        return best;
    }

    private static boolean buildExit(ServerLevel level, TownZone town) {
        var land = nearestLand(level, town);
        if (land == null) {
            LOGGER.warn("Village {} has no dry land within 256 blocks; retaining sealed plaza with outbound waypoint exit", town.waypointId());
            return false;
        }
        int dx = land.getX() - town.x(), dz = land.getZ() - town.z();
        int length = Math.max(Math.abs(dx), Math.abs(dz));
        int floor = Math.max(town.y() - 1, land.getY() - 1);
        // Include the approach width and stair-tower offset when measuring the water surface.
        for (int i = 0; i <= length; i++) for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            int wx = town.x() + (int)Math.round((double)dx * i / length) + x;
            int wz = town.z() + (int)Math.round((double)dz * i / length) + z;
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz) - 1;
            if (!level.getFluidState(new BlockPos(wx, surface, wz)).isEmpty()) floor = Math.max(floor, surface);
        }
        var start = stairTower(level, new BlockPos(town.x(), town.y(), town.z()), floor, dx, dz, true);
        var end = land.getY() - 1 == floor ? land : stairTower(level, land, floor, -dx, -dz, false);
        bridge(level, start, end);
        LOGGER.info("Village {} walking exit reaches dry land at {},{},{}", town.waypointId(), land.getX(), land.getY(), land.getZ());
        return true;
    }

    private static BlockPos stairTower(ServerLevel level, BlockPos base, int top, int dx, int dz, boolean plaza) {
        int bottom = base.getY() - 1;
        boolean sealed = plaza;
        if (!sealed) for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) for (int y = base.getY(); y < top + 3; y++) {
            if (!level.getFluidState(new BlockPos(base.getX() + x, y, base.getZ() + z)).isEmpty()) sealed = true;
        }
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            level.setBlock(base.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = base.getY(); y < top + 3; y++) {
                boolean edge = Math.abs(x) == 4 || Math.abs(z) == 4;
                level.setBlock(new BlockPos(base.getX() + x, y, base.getZ() + z),
                        (sealed && edge ? Blocks.GLASS : Blocks.AIR).defaultBlockState(), 3);
            }
            if (sealed) level.setBlock(new BlockPos(base.getX() + x, top + 3, base.getZ() + z), Blocks.GLASS.defaultBlockState(), 3);
        }
        int[][] corners = {{2,2},{-2,2},{-2,-2},{2,-2}};
        int end = dx >= 0 ? (dz >= 0 ? 0 : 3) : (dz >= 0 ? 1 : 2);
        int first = Math.floorMod(end - (top - bottom), 4);
        landing(level, base, corners[first], bottom);
        for (int y = bottom; y < top; y++) {
            int side = Math.floorMod(first + y - bottom, 4);
            var from = corners[side]; var to = corners[(side + 1) % 4];
            int stepX = Integer.signum(to[0] - from[0]), stepZ = Integer.signum(to[1] - from[1]);
            var facing = stepX > 0 ? Direction.EAST : stepX < 0 ? Direction.WEST : stepZ > 0 ? Direction.SOUTH : Direction.NORTH;
            for (int width = -1; width <= 1; width++) {
                var pos = new BlockPos(base.getX() + (from[0] + to[0]) / 2 + stepZ * width, y + 1,
                        base.getZ() + (from[1] + to[1]) / 2 + stepX * width);
                level.setBlock(pos, Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing), 3);
            }
            landing(level, base, to, y + 1);
        }
        if (!plaza && sealed) shoreDoor(level, base, corners[first]);
        return new BlockPos(base.getX() + corners[end][0], top + 1, base.getZ() + corners[end][1]);
    }

    private static void shoreDoor(ServerLevel level, BlockPos base, int[] corner) {
        for (int axis = 0; axis < 2; axis++) {
            int sign = Integer.signum(corner[axis]);
            boolean safe = true;
            for (int width = -1; width <= 1; width++) {
                var outside = base.offset(axis == 0 ? sign * 5 : corner[0] + width, 0,
                        axis == 1 ? sign * 5 : corner[1] + width);
                if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, outside.getX(), outside.getZ()) != outside.getY()
                        || !dry(level, outside)) safe = false;
            }
            if (!safe) continue;
            for (int width = -1; width <= 1; width++) {
                var door = base.offset(axis == 0 ? sign * 4 : corner[0] + width, 0,
                        axis == 1 ? sign * 4 : corner[1] + width);
                level.setBlock(door, Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(door.above(), Blocks.AIR.defaultBlockState(), 3);
            }
            return;
        }
        LOGGER.warn("Shore stair at {},{},{} has no three-wide dry doorway; retaining protective walls", base.getX(), base.getY(), base.getZ());
    }

    private static void landing(ServerLevel level, BlockPos base, int[] corner, int floor) {
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            level.setBlock(new BlockPos(base.getX() + corner[0] + x, floor, base.getZ() + corner[1] + z), Blocks.STONE.defaultBlockState(), 3);
        }
    }

    private static void bridge(ServerLevel level, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        int length = Math.max(Math.abs(dx), Math.abs(dz));
        for (int i = 0; i <= length; i++) {
            int x = from.getX() + (length == 0 ? 0 : (int)Math.round((double)dx * i / length));
            int z = from.getZ() + (length == 0 ? 0 : (int)Math.round((double)dz * i / length));
            for (int width = -1; width <= 1; width++) {
                var pos = new BlockPos(x + (Math.abs(dx) < Math.abs(dz) ? width : 0), from.getY() - 1,
                        z + (Math.abs(dx) >= Math.abs(dz) ? width : 0));
                level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(pos.above().above(), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    static boolean buildIfUnsafe(ServerLevel level, TownZone town) {
        var center = new BlockPos(town.x(), town.y(), town.z());
        if (dry(level, center)) return false;
        int radius = Math.min(4, town.radius());
        boolean submerged = false;
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            for (int y = 0; y <= 3; y++) {
                if (!level.getFluidState(center.offset(x, y, z)).isEmpty()) submerged = true;
            }
        }
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            level.setBlock(center.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 0; y < 3; y++) {
                boolean rim = submerged && (Math.abs(x) == radius || Math.abs(z) == radius);
                level.setBlock(center.offset(x, y, z), (rim ? Blocks.GLASS : Blocks.AIR).defaultBlockState(), 3);
            }
            // Lakes above the arrival height need a sealed roof to keep flowing water out.
            if (submerged) level.setBlock(center.offset(x, 3, z), Blocks.GLASS.defaultBlockState(), 3);
        }
        if (!dry(level, center)) throw new IllegalStateException("Cannot prepare village arrival: " + town.waypointId());
        return true;
    }

    private static boolean dry(ServerLevel level, BlockPos feet) {
        var floor = level.getBlockState(feet.below());
        return floor.isFaceSturdy(level, feet.below(), Direction.UP)
                && !floor.is(Blocks.MAGMA_BLOCK) && !floor.is(Blocks.CAMPFIRE) && !floor.is(Blocks.SOUL_CAMPFIRE)
                && level.getFluidState(feet.below()).isEmpty()
                && level.getFluidState(feet).isEmpty() && level.getFluidState(feet.above()).isEmpty()
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && !level.getBlockState(feet).is(Blocks.FIRE) && !level.getBlockState(feet).is(Blocks.SOUL_FIRE);
    }
}
