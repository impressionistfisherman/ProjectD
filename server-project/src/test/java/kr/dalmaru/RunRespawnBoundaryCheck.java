package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;
import kr.dalmaru.run.RunEncounterRules;

public final class RunRespawnBoundaryCheck {
    public static void main(String[] args)throws Exception{
        for(long ticks:new long[]{-1,0,1,99,100,101,299,300,301,Long.MAX_VALUE}){
            if(RunEncounterRules.clickRevive(ticks)!=(ticks>=100)||RunEncounterRules.autoRevive(ticks)!=(ticks>=300))throw new AssertionError("Revive cutoff differs");
        }
        String source=Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        String methods=method(source,"public boolean canRespawn(")+method(source,"private boolean autoRespawn(")+method(source,"public void afterRespawn(");
        methods=methods.replace("net.minecraft.network.protocol.game.ServerboundClientCommandPacket","Packet").replace("net.minecraft.world.level.GameType","GameType");
        Path dir=Files.createTempDirectory("run-respawn-");
        try{
            Path file=dir.resolve("RespawnHarness.java");Files.writeString(file,HARNESS.replace("/* METHODS */",methods));
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",dir.toString(),file.toString())!=0)throw new AssertionError("Fixture compile failed");
            try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},RunRespawnBoundaryCheck.class.getClassLoader())){loader.loadClass("RespawnHarness").getMethod("run").invoke(null);}
        }finally{try(var files=Files.walk(dir)){for(var path:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("Run revive 5s/15s boundaries, actual gate/auto/consume/spectator lifecycle PASS");
    }
    private static String method(String source,String signature){int start=source.indexOf(signature),open=source.indexOf('{',start),depth=1,end=open+1;for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}return source.substring(start,end);}
    private static final String HARNESS="""
        import java.util.*;
        import kr.dalmaru.run.RunEncounterRules;
        enum GameType{SPECTATOR}
        class Position{double x,y,z;}
        record Packet(Action action){enum Action{PERFORM_RESPAWN}}
        class ServerPlayer{
            UUID id=UUID.randomUUID();boolean dead=true,spectator;float health;Connection connection=new Connection();
            class Connection{int calls;Runnable respawn;void handleClientCommand(Packet packet){calls++;if(respawn!=null)respawn.run();}}
            UUID getUUID(){return id;}boolean isDeadOrDying(){return dead;}float getMaxHealth(){return 100;}
            float getYRot(){return 0;}float getXRot(){return 0;}void setHealth(float value){health=value;dead=false;}
            void setGameMode(GameType mode){spectator=true;}void teleportTo(Object level,double x,double y,double z,Set<?> set,float yaw,float pitch,boolean b){}
        }
        class State{UUID runId=UUID.randomUUID();boolean dead=true,connected=true;int revivesRemaining=2;long deathTick=10;Position lastSafe;}
        class Encounter{boolean failed;Object level;}
        public class RespawnHarness{
            Map<UUID,State>playerRuns=new HashMap<>();Map<UUID,Encounter>encounters=new HashMap<>();long tickCount;
            Position revivePosition(ServerPlayer player,Encounter encounter,State state){return new Position();}
            /* METHODS */
            static void check(boolean value){if(!value)throw new AssertionError("Run revive lifecycle differs");}
            public static void run(){
                var h=new RespawnHarness();var p=new ServerPlayer();check(h.canRespawn(p));check(!h.autoRespawn(p));
                var s=new State();var e=new Encounter();h.playerRuns.put(p.id,s);h.encounters.put(s.runId,e);
                h.tickCount=109;check(!h.canRespawn(p));h.tickCount=110;check(h.canRespawn(p));
                h.tickCount=309;check(!h.autoRespawn(p));check(s.revivesRemaining==2);
                p.connection.respawn=()->h.afterRespawn(p,p);h.tickCount=310;check(h.autoRespawn(p));
                check(p.connection.calls==1&&!s.dead&&s.revivesRemaining==1&&p.health==35);check(!h.autoRespawn(p));h.afterRespawn(p,p);check(s.revivesRemaining==1);
                s.dead=true;p.dead=true;s.connected=false;check(!h.autoRespawn(p));s.connected=true;
                e.failed=true;check(!h.autoRespawn(p));check(h.canRespawn(p));h.afterRespawn(p,p);check(p.spectator&&s.revivesRemaining==1);
                e.failed=false;s.revivesRemaining=0;p.spectator=false;check(!h.autoRespawn(p));check(h.canRespawn(p));h.afterRespawn(p,p);check(p.spectator&&s.revivesRemaining==0);
                h.encounters.clear();check(!h.autoRespawn(p));check(h.canRespawn(p));
            }
        }
        """;
}
