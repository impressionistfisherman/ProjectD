package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.phys.AABB;

public final class DungeonNaturalSpawnCheck {
    public static void main(String[] args) throws Exception {
        AABB box = new AABB(-9, 100, -6, 10, 106, 75);
        check(box.contains(0.5, 104, 54), "Natural spawn inside the dungeon must be denied");
        check(!box.contains(0.5, 64, 0), "Natural spawn outside the dungeon must stay allowed");
        check(!box.contains(0.5, 100, 80), "Natural spawn past the dungeon end must stay allowed");
        String runtime = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        String mixin = Files.readString(Path.of("src/main/java/kr/dalmaru/mixin/DungeonNaturalSpawnMixin.java"));
        check(runtime.contains("new net.minecraft.world.phys.AABB(e.floor.getX()-9,e.floor.getY(),e.floor.getZ()-6,e.floor.getX()+10,e.floor.getY()+6,e.floor.getZ()+75)"), "Run bounds must stay the instance box");
        check(runtime.contains("deniesNaturalSpawn") && runtime.contains("bounds(encounter).contains"), "Natural spawn uses the run box");
        check(runtime.contains("EntitySpawnReason.EVENT"), "Scripted dungeon spawns stay on the event path");
        check(mixin.contains("spawnCategoryForPosition") && mixin.contains("deniesNaturalSpawn") && !mixin.contains("doMobSpawning"), "Only the natural spawn attempt is cancelled");
        check(Files.readString(Path.of("src/main/resources/dalmaru.mixins.json")).contains("\"DungeonNaturalSpawnMixin\""), "Spawn mixin is not registered");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
