package kr.dalmaru;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;

/** Executes the production terrain/once-only methods against a deterministic block world. */
public final class P5VillagePlazaCheck {
    public static void main(String[] args) throws Exception {
        String source=Files.readString(Path.of("src/main/java/kr/dalmaru/content/VillagePlazas.java"));
        String methods="";
        for(String signature:new String[]{"public static void prepare(","static boolean buildIfUnsafe(","private static boolean dry(",
                "private static boolean enclosed(","private static BlockPos nearestLand(","private static boolean buildExit(",
                "private static BlockPos stairTower(","private static void shoreDoor(","private static void landing(","private static void bridge("}) methods+=method(source,signature);
        var dir=Files.createTempDirectory("village-plaza-check-");
        try {
            var file=dir.resolve("PlazaHarness.java");
            Files.writeString(file,HARNESS.replace("/* METHODS */",methods));
            int code=ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",dir.toString(),file.toString());
            if(code!=0)throw new AssertionError("Village plaza harness compilation failed");
            try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},P5VillagePlazaCheck.class.getClassLoader())) {
                loader.loadClass("PlazaHarness").getMethod("run").invoke(null);
            }
        } finally {
            try(var paths=Files.walk(dir)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
        System.out.println("P5 village plaza/nearest-land/three-wide-walking-exit/no-land/legacy-once/restart PASS");
    }

    private static String method(String source,String signature) {
        int start=source.indexOf(signature);
        if(start<0)throw new AssertionError("Missing production method: "+signature);
        int open=source.indexOf('{',start),depth=1,end=open+1;
        for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}
        return source.substring(start,end)+"\n";
    }

    private static final String HARNESS="""
        import java.util.*;
        import kr.dalmaru.account.TownZone;
        public class PlazaHarness {
            static Object PREPARED=new Object();
            static class Log {int warnings,exits;void warn(String message,Object... args){warnings++;}void info(String message,Object... args){exits++;}}
            static Log LOGGER=new Log();
            enum Direction { UP,EAST,WEST,SOUTH,NORTH }
            static class Heightmap {enum Types { MOTION_BLOCKING_NO_LEAVES }}
            static class StairBlock {static Object FACING=new Object();}
            record BlockPos(int x,int y,int z) {
                BlockPos offset(int a,int b,int c){return new BlockPos(x+a,y+b,z+c);}
                BlockPos below(){return offset(0,-1,0);}
                BlockPos above(){return offset(0,1,0);}
                int getX(){return x;}int getY(){return y;}int getZ(){return z;}
            }
            record Shape(boolean isEmpty) {}
            record Fluid(boolean isEmpty) {}
            record State(String name,boolean solid,boolean fluid,Direction facing) {
                State(String name,boolean solid,boolean fluid){this(name,solid,fluid,null);}
                boolean is(State other){return name.equals(other.name);}
                boolean isFaceSturdy(ServerLevel level,BlockPos pos,Direction face){return solid;}
                Shape getCollisionShape(ServerLevel level,BlockPos pos){return new Shape(!solid);}
                State defaultBlockState(){return this;}
                State setValue(Object property,Direction value){return new State(name,solid,fluid,value);}
            }
            static class Blocks {
                static State STONE=new State("stone",true,false),STONE_STAIRS=new State("stairs",true,false),GLASS=new State("glass",true,false),AIR=new State("air",false,false),
                    WATER=new State("water",false,true),MAGMA_BLOCK=new State("magma",true,false),CAMPFIRE=new State("campfire",true,false),
                    SOUL_CAMPFIRE=new State("soul_campfire",true,false),FIRE=new State("fire",false,false),SOUL_FIRE=new State("soul_fire",false,false);
            }
            static class ServerLevel {
                final Map<BlockPos,State> blocks=new HashMap<>();final int water;final boolean land;
                List<String> attached;int writes,saves;
                ServerLevel(int water,boolean land){this.water=water;this.land=land;}
                State getBlockState(BlockPos p){return blocks.getOrDefault(p,p.y()<=60||(land&&p.y()<=63)?Blocks.STONE:p.y()<=water?Blocks.WATER:Blocks.AIR);}
                Fluid getFluidState(BlockPos p){return new Fluid(!getBlockState(p).fluid());}
                void setBlock(BlockPos p,State state,int flags){blocks.put(p,state);writes++;}
                List<String> getAttached(Object key){return attached;}
                void setAttached(Object key,List<String> value){attached=value;}
                ServerLevel getChunkSource(){return this;}
                void save(boolean flush){saves++;}
                int getHeight(Heightmap.Types type,int x,int z){for(int y=160;y>=0;y--)if(getBlockState(new BlockPos(x,y,z))!=Blocks.AIR)return y+1;return 0;}
                void land(int x,int z,int floor){for(int y=61;y<=Math.max(water,floor);y++)blocks.put(new BlockPos(x,y,z),y<=floor?Blocks.STONE:Blocks.AIR);}
            }
            static class Identifier {static String parse(String value){return value;}}
            static class ResourceKey {static String create(Object registry,String value){return value;}}
            static class Registries {static Object DIMENSION=new Object();}
            record MinecraftServer(ServerLevel level) {ServerLevel getLevel(String key){return key.equals("minecraft:overworld")?level:null;}}
            record Village(String id,TownZone zone) {}
            static class VillageCatalog {static List<Village> villages;static List<Village> current(){return villages;}}
            /* METHODS */
            static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
            static boolean walkable(ServerLevel level,BlockPos start,BlockPos target){
                Set<BlockPos> visited=new HashSet<>();ArrayDeque<BlockPos> queue=new ArrayDeque<>();queue.add(start);visited.add(start);
                while(!queue.isEmpty()){
                    var feet=queue.remove();if(feet.equals(target))return true;
                    for(var direction:new Direction[]{Direction.EAST,Direction.WEST,Direction.SOUTH,Direction.NORTH}){
                        int dx=direction==Direction.EAST?1:direction==Direction.WEST?-1:0;
                        int dz=direction==Direction.SOUTH?1:direction==Direction.NORTH?-1:0;
                        for(int dy=-1;dy<=1;dy++){
                            var next=feet.offset(dx,dy,dz);if(Math.abs(next.x())>70||Math.abs(next.z())>70||next.y()<63||next.y()>155)continue;
                            var floor=level.getBlockState(next.below());
                            if(!floor.solid()||!level.getFluidState(next).isEmpty()||!level.getFluidState(next.above()).isEmpty()
                                ||level.getBlockState(next).solid()||level.getBlockState(next.above()).solid())continue;
                            if(dy>0&&(!floor.is(Blocks.STONE_STAIRS)||floor.facing()!=direction))continue;
                            if(visited.add(next))queue.add(next);
                        }
                    }
                }
                return false;
            }
            public static void run() {
                var town=new TownZone("minecraft:overworld",0,64,0,32,"taepyeong");
                var center=new BlockPos(0,64,0);
                VillageCatalog.villages=List.of(new Village("taepyeong",town));
                var ocean=new ServerLevel(62,false);
                prepare(new MinecraftServer(ocean));
                require(dry(ocean,center)&&ocean.getBlockState(center.below())==Blocks.STONE,"Ocean arrival needs a dry stone floor");
                require(ocean.writes==9*9*4&&ocean.saves==1,"Ocean plaza must be 9 by 9 with three clear head blocks");
                require(ocean.blocks.keySet().stream().allMatch(p->Math.abs(p.x())<=4&&Math.abs(p.z())<=4&&p.y()>=63&&p.y()<=66),"Plaza escaped its bounded footprint");
                ocean.blocks.put(center.below(),Blocks.AIR);
                var restarted=new ServerLevel(62,false);
                restarted.blocks.putAll(ocean.blocks);restarted.attached=List.copyOf(ocean.attached);
                prepare(new MinecraftServer(restarted));
                require(restarted.writes==0&&restarted.getBlockState(center.below())==Blocks.AIR,"Restart must preserve subsequent player edits");
                var lake=new ServerLevel(70,false);
                prepare(new MinecraftServer(lake));
                require(dry(lake,center)&&lake.writes==9*9*5,"Deep lake needs a dry, sealed plaza");
                for(int y=64;y<=66;y++)for(int n=-4;n<=4;n++) {
                    require(lake.getBlockState(new BlockPos(4,y,n))==Blocks.GLASS&&lake.getBlockState(new BlockPos(-4,y,n))==Blocks.GLASS,"Lake side leak");
                    require(lake.getBlockState(new BlockPos(n,y,4))==Blocks.GLASS&&lake.getBlockState(new BlockPos(n,y,-4))==Blocks.GLASS,"Lake end leak");
                }
                require(lake.getBlockState(center.offset(0,3,0))==Blocks.GLASS,"Lake roof leak");
                require(LOGGER.warnings==1,"No-land fallback must log once");
                int lakeWrites=lake.writes;prepare(new MinecraftServer(lake));
                require(lake.writes==lakeWrites&&LOGGER.warnings==1,"No-land fallback must not rebuild or retry every startup");
                var connected=new ServerLevel(70,false);connected.land(12,0,71);connected.land(20,0,71);
                require(nearestLand(connected,town).equals(new BlockPos(12,72,0)),"Exit must choose nearest natural land");
                prepare(new MinecraftServer(connected));
                require(walkable(connected,center,new BlockPos(12,72,0)),"Deep lake exit must be walkable without jumping or swimming");
                for(int x=4;x<=11;x++)for(int width=-1;width<=1;width++){
                    int z=2+(int)Math.round(-2.0*(x-2)/10)+width;
                    require(connected.getBlockState(new BlockPos(x,71,z))==Blocks.STONE,"Bridge must have three stone floor columns");
                }
                require(connected.getBlockState(new BlockPos(-4,64,0))==Blocks.GLASS,"Non-entry wall must stay sealed");
                require(connected.getBlockState(new BlockPos(4,72,2))==Blocks.AIR,"Only the raised bridge entry must open");
                int connectedWrites=connected.writes;prepare(new MinecraftServer(connected));
                require(connected.writes==connectedWrites,"Bridge must share once-only completion state");
                var cliff=new ServerLevel(70,false);cliff.land(-8,-7,90);prepare(new MinecraftServer(cliff));
                require(walkable(cliff,center,new BlockPos(-8,91,-7)),"Nearby high shore needs a full walking stair route");
                var lowBank=new ServerLevel(70,false);
                for(int x=20;x<=40;x++)for(int z=-8;z<=8;z++)lowBank.land(x,z,65);
                prepare(new MinecraftServer(lowBank));
                require(walkable(lowBank,center,new BlockPos(20,66,0)),"Bridge above higher water needs stairs down to the lower bank");
                require(walkable(lowBank,center,new BlockPos(25,66,2)),"Lower-bank stairs must open a dry ground-level doorway");
                var legacy=new ServerLevel(70,false);buildIfUnsafe(legacy,town);legacy.attached=List.of("taepyeong:0:64:0");legacy.land(12,0,71);
                prepare(new MinecraftServer(legacy));
                require(walkable(legacy,center,new BlockPos(12,72,0))&&legacy.attached.contains("taepyeong:0:64:0:exit-v1"),"Old completed plaza must receive exactly one exit upgrade");
                var boundary=new ServerLevel(70,false);boundary.land(65,0,71);boundary.land(64,64,71);
                require(nearestLand(boundary,town)==null,"Land search must use a 64-block circle");
                boundary.land(64,0,71);require(nearestLand(boundary,town).equals(new BlockPos(64,72,0)),"Exact 64-block boundary must be included");
                var dryLand=new ServerLevel(-100,true);
                prepare(new MinecraftServer(dryLand));
                require(dryLand.writes==0&&dryLand.saves==0,"Existing dry land must remain unchanged");
                var danger=new ServerLevel(-100,true);danger.blocks.put(center.below(),Blocks.MAGMA_BLOCK);
                require(buildIfUnsafe(danger,town)&&dry(danger,center),"Hazardous floor must be replaced");
                var tiny=new ServerLevel(70,false);
                require(buildIfUnsafe(tiny,new TownZone("minecraft:overworld",0,64,0,1,"tiny"))&&dry(tiny,center),"Small village still needs one safe cell");
                require(tiny.blocks.keySet().stream().allMatch(p->Math.abs(p.x())<=1&&Math.abs(p.z())<=1),"Small village radius exceeded");
                VillageCatalog.villages=List.of(new Village("missing",new TownZone("minecraft:missing",0,64,0,32,"missing")));
                try{prepare(new MinecraftServer(ocean));throw new AssertionError("Missing dimension silently ignored");}catch(IllegalStateException expected){}
            }
        }
        """;
}
