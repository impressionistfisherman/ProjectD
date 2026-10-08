package kr.dalmaru;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import javax.tools.ToolProvider;

/** Runs the production map builder against solid terrain without starting a Minecraft server. */
public final class BeaconTowerTerrainCheck {
    public static void main(String[] args) throws Exception {
        Path source = args.length == 0 ? Path.of("src/main/java/kr/dalmaru/run/BeaconTowerMap.java") : Path.of(args[0]);
        Path fixture = Files.createTempDirectory("dalmaru-terrain-check-");
        try {
            var sources = new ArrayList<String>();
            var files = Map.of(
                "net/minecraft/core/BlockPos.java", """
                    package net.minecraft.core;
                    public record BlockPos(int x, int y, int z) {
                        public BlockPos offset(int dx, int dy, int dz) { return new BlockPos(x+dx,y+dy,z+dz); }
                    }
                    """,
                "net/minecraft/world/level/block/Blocks.java", """
                    package net.minecraft.world.level.block;
                    public enum Blocks {
                        AIR, DEEPSLATE_TILES, DEEPSLATE_BRICKS, IRON_BARS, POLISHED_DEEPSLATE;
                        public String defaultBlockState() { return name(); }
                    }
                    """,
                "net/minecraft/server/level/ServerLevel.java", """
                    package net.minecraft.server.level;
                    import java.util.HashMap;
                    import net.minecraft.core.BlockPos;
                    public class ServerLevel {
                        private final HashMap<BlockPos,String> blocks = new HashMap<>();
                        public boolean setBlock(BlockPos p, String block, int flags) { blocks.put(p,block); return true; }
                        public String getBlockState(BlockPos p) { return blocks.getOrDefault(p,"STONE"); }
                    }
                    """,
                "kr/dalmaru/run/BeaconTowerMap.java", Files.readString(source),
                "kr/dalmaru/run/TerrainHarness.java", HARNESS
            );
            for (var entry : files.entrySet()) {
                Path file = fixture.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue());
                sources.add(file.toString());
            }
            var options = new ArrayList<String>(java.util.List.of("-classpath", fixture.toString(), "-d", fixture.toString()));
            options.addAll(sources);
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null, options.toArray(String[]::new)) != 0)
                throw new IllegalStateException("Terrain harness compilation failed.");
            try (var loader = new URLClassLoader(new java.net.URL[]{fixture.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                loader.loadClass("kr.dalmaru.run.TerrainHarness").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            }
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static final String HARNESS = """
        package kr.dalmaru.run;
        import java.util.Set;
        import net.minecraft.core.BlockPos;
        import net.minecraft.server.level.ServerLevel;
        public class TerrainHarness {
            public static void main(String[] args) {
                var origin = new BlockPos(89520,100,135312);
                var level = new ServerLevel();
                BeaconTowerMap.build(level,origin);
                for (int x=-8;x<=8;x++) for(int z=-6;z<=74;z++) for(int y=1;y<=4;y++) {
                    String expected="AIR";
                    if (Math.abs(x)<=2 && Set.of(27,45,64).contains(z) && y<=3) expected="IRON_BARS";
                    if (Math.abs(x)==6 && Set.of(9,21,48,60).contains(z) && y<=3) expected="POLISHED_DEEPSLATE";
                    require(level.getBlockState(origin.offset(x,y,z)).equals(expected),"Interior terrain not cleared or map obstacle changed.");
                }
                for(int x=-9;x<=9;x++) for(int z=-6;z<=74;z++)
                    require(level.getBlockState(origin.offset(x,0,z)).equals("DEEPSLATE_TILES"),"Floor changed.");
                for(int x:new int[]{-9,9}) for(int z=-6;z<=74;z++) for(int y=1;y<=4;y++)
                    require(level.getBlockState(origin.offset(x,y,z)).equals("DEEPSLATE_BRICKS"),"Wall changed.");
                for(var outside:new BlockPos[]{origin.offset(10,1,0),origin.offset(0,5,0),origin.offset(0,-1,0),origin.offset(0,1,75)})
                    require(level.getBlockState(outside).equals("STONE"),"Terrain outside the map changed.");
                BeaconTowerMap.open(level,origin,27);
                require(level.getBlockState(origin.offset(0,1,27)).equals("AIR"),"Gate opening changed.");
                System.out.println("Solid-terrain spawn/corridor clearing and floor/wall/gate/pillar boundaries PASS (production builder, stubbed blocks).");
            }
            private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
        }
        """;
}
