package kr.dalmaru.client;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import kr.dalmaru.network.DalmaruPayloads;

/** Executes the production entry validation and start method with a small world boundary. */
public final class P5BrazierRejectionCheck {
    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        String methods = method(source, "public Result startBrazier(") + method(source, "private Result validateMechanic(");
        String code = """
            import java.util.*;
            import kr.dalmaru.run.*;
            import kr.dalmaru.run.RunMechanicAccess.Result;
            import kr.dalmaru.content.ContentCatalog;
            import net.minecraft.core.BlockPos;
            import net.minecraft.world.phys.Vec3;
            public class BrazierAccessHarness {
                enum Stage { GATEKEEPER, BRAZIER }
                static class ServerPlayer {
                    UUID id=UUID.randomUUID(); Object level;
                    UUID getUUID(){return id;} boolean isAlive(){return true;} boolean isSpectator(){return false;}
                    Object level(){return level;} Vec3 position(){return BlockPos.ZERO.getCenter();}
                    Vec3 getEyePosition(){return position();} double distanceToSqr(Vec3 p){return position().distanceToSqr(p);}
                }
                static class ClipContext {
                    enum Block { COLLIDER } enum Fluid { NONE }
                    ClipContext(Vec3 a,Vec3 b,Block c,Fluid d,ServerPlayer p){}
                }
                static class Level {
                    net.minecraft.world.phys.BlockHitResult clip(ClipContext c){
                        return net.minecraft.world.phys.BlockHitResult.miss(Vec3.ZERO,net.minecraft.core.Direction.UP,BlockPos.ZERO);
                    }
                }
                static class State { UUID runId; boolean connected=true,dead; }
                static class Encounter {
                    Level level=new Level(); Stage stage=Stage.BRAZIER; long revision=1,pausedAt; boolean failed;
                    Map<UUID,Object> mobs=new HashMap<>(); ContentCatalog content=ContentCatalog.current();
                    BrazierMechanic brazier=new BrazierMechanic(content.mechanics().brazier(),1);
                }
                Map<UUID,State> playerRuns=new HashMap<>(); Map<UUID,Encounter> encounters=new HashMap<>();
                BlockPos brazierPosition(Encounter e,int i){return BlockPos.ZERO;}
                void contributionInput(ServerPlayer p){}
                /* METHODS */
                public static void main(String[] args){
                    var h=new BrazierAccessHarness(); var p=new ServerPlayer(); var e=new Encounter(); var s=new State();
                    s.runId=UUID.randomUUID(); p.level=e.level; h.playerRuns.put(p.id,s); h.encounters.put(s.runId,e);
                    e.mobs.put(UUID.randomUUID(),new Object());
                    check(h.startBrazier(p,s.runId,1,BlockPos.ZERO).name().equals("MOBS_REMAINING"),"Remaining mobs reason");
                    check(e.brazier.remaining(p.id)==0,"Rejected input began channel");
                    e.stage=Stage.GATEKEEPER;
                    check(h.startBrazier(p,s.runId,1,BlockPos.ZERO)==Result.WRONG_PHASE,"Wrong stage precedence");
                    e.mobs.clear();
                    check(h.startBrazier(p,s.runId,1,BlockPos.ZERO)==Result.WRONG_PHASE,"Empty wrong stage");
                    e.stage=Stage.BRAZIER;
                    check(h.startBrazier(p,s.runId,1,BlockPos.ZERO)==Result.STARTED,"Empty correct stage starts");
                    for(int i=0;i<80;i++)e.brazier.tick(Map.of(p.id,p.position()));
                    check(e.brazier.complete(),"Channel completes after clearing mobs");
                }
                static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
            }
            """.replace("/* METHODS */", methods.replace("net.minecraft.world.level.ClipContext", "ClipContext"));
        Path directory = Files.createTempDirectory("dalmaru-brazier-access-");
        try {
            Path java = directory.resolve("BrazierAccessHarness.java");
            Files.writeString(java, code);
            if (ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",directory.toString(),java.toString()) != 0)
                throw new AssertionError("Brazier production methods did not compile");
            try (var loader = new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, P5BrazierRejectionCheck.class.getClassLoader())) {
                loader.loadClass("BrazierAccessHarness").getMethod("main",String[].class).invoke(null,(Object)new String[0]);
            }
        } finally {
            try(var paths=Files.walk(directory)){for(Path file:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(file);}
        }
        ClientBrazierState.resetForConnection();
        ClientBrazierState.accept(new DalmaruPayloads.P4BrazierHud(1,0,1,0,"MOBS_REMAINING","IDLE",0,""));
        String text=String.join(" ",ClientBrazierState.lines());
        if(!text.contains("남은 적을 모두 처치해야 합니다.") || text.matches(".*[A-Za-z]{3,}.*"))
            throw new AssertionError("Remaining mobs must display Korean without English: "+text);
        System.out.println("Brazier remaining mobs, phase precedence, successful ignition and Korean HUD PASS");
    }

    private static String method(String source,String signature){
        int start=source.indexOf(signature);
        if(start<0)throw new AssertionError("Missing production method: "+signature);
        int open=source.indexOf('{',start),depth=1,end=open+1;
        for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}
        return source.substring(start,end)+"\n";
    }
}
