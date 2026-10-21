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

/** Fixed arrival village. Every new center is leveled once and given the same layout. */
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
            boolean built = (prepared == null || !prepared.contains(key)) && buildIfUnsafe(level, town);
            boolean changed = built;
            boolean isolated = false;
            boolean shelled = pad(town) > 4 && glassBox(level, new BlockPos(town.x(), town.y(), town.z()), pad(town));
            if (enclosed(level, town)) {
                boolean exitBuilt = buildExit(level, town);
                changed |= exitBuilt;
                isolated = !exitBuilt;
            }
            if (built) furnish(level, town);
            if (built && !shelled) roadRamps(level, town);
            if (shelled) openRingDoors(level, town);
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

    private static int pad(TownZone town) { return Math.min(10, town.radius()); }

    private static boolean glassBox(ServerLevel level, BlockPos center, int radius) {
        if (!level.getBlockState(center.offset(0, 3, 0)).is(Blocks.GLASS)) return false;
        for (int offset = -radius; offset <= radius; offset++) for (int y = 0; y < 3; y++) {
            if (!level.getBlockState(center.offset(radius, y, offset)).is(Blocks.GLASS)
                    || !level.getBlockState(center.offset(-radius, y, offset)).is(Blocks.GLASS)
                    || !level.getBlockState(center.offset(offset, y, radius)).is(Blocks.GLASS)
                    || !level.getBlockState(center.offset(offset, y, -radius)).is(Blocks.GLASS)) return false;
        }
        return true;
    }

    private static boolean enclosed(ServerLevel level, TownZone town) {
        var center = new BlockPos(town.x(), town.y(), town.z());
        int legacy = Math.min(4, town.radius());
        if (glassBox(level, center, legacy)) return true;
        int radius = pad(town);
        return radius != legacy && glassBox(level, center, radius);
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
        int radius = pad(town);
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

    private static void openRingDoors(ServerLevel level, TownZone town) {
        var center = new BlockPos(town.x(), town.y(), town.z());
        int[][] doors = {{4, 0}, {-4, 0}, {0, 4}, {0, -4}};
        for (int[] door : doors) for (int y = 0; y <= 1; y++) {
            var pos = center.offset(door[0], y, door[1]);
            if (level.getBlockState(pos).is(Blocks.GLASS)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static void furnish(ServerLevel level, TownZone town) {
        int pad = pad(town);
        if (pad <= 4) return;
        var center = new BlockPos(town.x(), town.y(), town.z());
        for (int x = 1 - pad; x < pad; x++) for (int z = 1 - pad; z < pad; z++) {
            if (Math.max(Math.abs(x), Math.abs(z)) <= 4) continue;
            if (x == 0 || z == 0) put(level, center.offset(x, -1, z), "smooth_stone");
        }
        var root = readLayout();
        for (var entry : root.getAsJsonArray("buildings")) {
            var building = entry.getAsJsonObject();
            placeBuilding(level, center, pad, building.get("x").getAsInt(), building.get("z").getAsInt(), building.get("w").getAsInt(), building.get("d").getAsInt(), building.get("wall").getAsString(), building.get("marker").getAsString());
        }
        var npc = root.getAsJsonObject("npc");
        int nx = npc.get("x").getAsInt(), nz = npc.get("z").getAsInt();
        if (Math.max(Math.abs(nx), Math.abs(nz)) > 4 && Math.max(Math.abs(nx), Math.abs(nz)) < pad) put(level, center.offset(nx, 0, nz), npc.get("marker").getAsString());
    }

    private static void roadRamps(ServerLevel level, TownZone town) {
        int pad = pad(town);
        if (pad <= 4) return;
        int floor = town.y() - 1;
        int[][] ends = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] end : ends) {
            int sx = end[0], sz = end[1];
            int outside = ground(level, town.x() + sx * (pad + 1), town.z() + sz * (pad + 1));
            int diff = outside - floor;
            if (Math.abs(diff) <= 1 || Math.abs(diff) > 24) continue;
            Direction facing = sx > 0 ? (diff > 0 ? Direction.EAST : Direction.WEST)
                    : sx < 0 ? (diff > 0 ? Direction.WEST : Direction.EAST)
                    : sz > 0 ? (diff > 0 ? Direction.SOUTH : Direction.NORTH)
                    : (diff > 0 ? Direction.NORTH : Direction.SOUTH);
            if (diff < 0) for (int width = -1; width <= 1; width++) {
                var lip = new BlockPos(town.x() + sx * pad + (sx == 0 ? width : 0), floor, town.z() + sz * pad + (sz == 0 ? width : 0));
                level.setBlock(lip, Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing), 3);
                level.setBlock(lip.above(), Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(lip.above().above(), Blocks.AIR.defaultBlockState(), 3);
            }
            for (int step = 1; step <= Math.abs(diff); step++) {
                int y = floor + (diff > 0 ? step : -step);
                int x = town.x() + sx * (pad + step);
                int z = town.z() + sz * (pad + step);
                for (int width = -1; width <= 1; width++) {
                    var pos = new BlockPos(x + (sx == 0 ? width : 0), y, z + (sz == 0 ? width : 0));
                    level.setBlock(pos, Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing), 3);
                    level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(pos.above().above(), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    private static int ground(ServerLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        for (int y = top + 2; y >= Math.max(0, top - 24); y--) {
            if (dry(level, new BlockPos(x, y, z))) return y - 1;
        }
        return top - 1;
    }

    private static void placeBuilding(ServerLevel level, BlockPos center, int pad, int x0, int z0, int w, int d, String wall, String marker) {
        int faceX = Math.abs(x0) <= Math.abs(x0 + w - 1) ? x0 : x0 + w - 1;
        int faceZ = Math.abs(z0) <= Math.abs(z0 + d - 1) ? z0 : z0 + d - 1;
        int midX = x0 + (w - 1) / 2, midZ = z0 + (d - 1) / 2;
        int doorX = faceX, doorZ = midZ;
        if (Math.abs(faceZ) < Math.abs(faceX)) { doorX = midX; doorZ = faceZ; }
        int doorRing = Math.max(Math.abs(doorX), Math.abs(doorZ));
        boolean foundDoor = doorRing > 4 && doorRing < pad && doorX != 0 && doorZ != 0;
        int markerX = x0 + w / 2, markerZ = z0 + d / 2;
        for (int x = x0; x < x0 + w; x++) for (int z = z0; z < z0 + d; z++) {
            int ring = Math.max(Math.abs(x), Math.abs(z));
            if (ring <= 4 || ring >= pad || x == 0 || z == 0) continue;
            boolean edge = x == x0 || x == x0 + w - 1 || z == z0 || z == z0 + d - 1;
            boolean door = foundDoor && x == doorX && z == doorZ;
            if (!edge && x == markerX && z == markerZ) {
                put(level, center.offset(x, 0, z), marker);
                put(level, center.offset(x, 1, z), "air");
            } else if (edge && !door) {
                put(level, center.offset(x, 0, z), wall);
                put(level, center.offset(x, 1, z), wall);
            } else {
                put(level, center.offset(x, 0, z), "air");
                put(level, center.offset(x, 1, z), "air");
            }
            put(level, center.offset(x, 2, z), wall);
        }
    }

    private static com.google.gson.JsonObject readLayout() {
        try (var stream = kr.dalmaru.content.VillagePlazas.class.getResourceAsStream("/content/village-layout.json")) {
            if (stream == null) throw new IllegalStateException("Missing village layout");
            var root = com.google.gson.JsonParser.parseString(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("radius").getAsInt() != 10) throw new IllegalStateException("Village layout radius drifted");
            return root;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot load village layout", exception);
        }
    }

    private static void put(ServerLevel level, BlockPos pos, String id) {
        level.setBlock(pos, switch (id) {
            case "smooth_stone" -> Blocks.SMOOTH_STONE.defaultBlockState();
            case "bricks" -> Blocks.BRICKS.defaultBlockState();
            case "lodestone" -> Blocks.LODESTONE.defaultBlockState();
            case "blast_furnace" -> Blocks.BLAST_FURNACE.defaultBlockState();
            case "barrel" -> Blocks.BARREL.defaultBlockState();
            case "oak_planks" -> Blocks.OAK_PLANKS.defaultBlockState();
            case "lectern" -> Blocks.LECTERN.defaultBlockState();
            case "air" -> Blocks.AIR.defaultBlockState();
            default -> throw new IllegalArgumentException("Unknown village block: " + id);
        }, 3);
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
