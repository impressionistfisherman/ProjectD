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

/** Small temporary arrival floors until authored village terrain replaces the pilot map. */
public final class VillagePlazas {
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
            if (prepared != null && prepared.contains(key)) continue;
            if (buildIfUnsafe(level, town)) level.getChunkSource().save(true);
            var next = prepared == null ? new ArrayList<String>() : new ArrayList<>(prepared);
            next.add(key);
            level.setAttached(PREPARED, List.copyOf(next));
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
