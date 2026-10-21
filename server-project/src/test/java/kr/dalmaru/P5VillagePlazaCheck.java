package kr.dalmaru;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;

/** Executes the production terrain/once-only methods against a deterministic block world. */
public final class P5VillagePlazaCheck {
    public static void main(String[] args) throws Exception {
        String source=Files.readString(Path.of("src/main/java/kr/dalmaru/content/VillagePlazas.java"));
        String methods=method(source,"public static void prepare(")+method(source,"static boolean buildIfUnsafe(")+method(source,"private static boolean dry(");
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
        System.out.println("P5 village ocean/lake/dry-land/bounds/restart/missing-dimension PASS");
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
            enum Direction { UP }
            record BlockPos(int x,int y,int z) {
                BlockPos offset(int a,int b,int c){return new BlockPos(x+a,y+b,z+c);}
                BlockPos below(){return offset(0,-1,0);}
                BlockPos above(){return offset(0,1,0);}
            }
            record Shape(boolean isEmpty) {}
            record Fluid(boolean isEmpty) {}
            record State(String name,boolean solid,boolean fluid) {
                boolean is(State other){return this==other;}
                boolean isFaceSturdy(ServerLevel level,BlockPos pos,Direction face){return solid;}
                Shape getCollisionShape(ServerLevel level,BlockPos pos){return new Shape(!solid);}
                State defaultBlockState(){return this;}
            }
            static class Blocks {
                static State STONE=new State("stone",true,false),GLASS=new State("glass",true,false),AIR=new State("air",false,false),
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
            }
            static class Identifier {static String parse(String value){return value;}}
            static class ResourceKey {static String create(Object registry,String value){return value;}}
            static class Registries {static Object DIMENSION=new Object();}
            record MinecraftServer(ServerLevel level) {ServerLevel getLevel(String key){return key.equals("minecraft:overworld")?level:null;}}
            record Village(String id,TownZone zone) {}
            static class VillageCatalog {static List<Village> villages;static List<Village> current(){return villages;}}
            /* METHODS */
            static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
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
