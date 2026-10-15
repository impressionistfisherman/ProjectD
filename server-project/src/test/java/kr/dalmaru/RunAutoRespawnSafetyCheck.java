package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;

/**
 * Checklist item 3 (S04a Creed §3.3): automatic handleClientCommand must not
 * double-revive, double-charge, or touch another player's run.
 * A real closing ServerGamePacketListenerImpl and a fake/test player need a
 * running server, so they stay a human check. This harness uses the product
 * method bodies with a stub connection.
 */
public final class RunAutoRespawnSafetyCheck {
    public static void main(String[] args)throws Exception{
        String source=Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        int call=source.indexOf("if(autoRespawn(player))continue;");
        int loop=source.lastIndexOf("for(var entry:playerRuns.entrySet()){",call);
        require(call>0&&loop>0&&source.lastIndexOf("getPlayer(entry.getKey())",call)>loop,"Tick must resolve the online player before autoRespawn");
        String auto=method(source,"private boolean autoRespawn(");
        require(auto.contains("if(player==null)return false")&&auto.contains("!state.connected"),"autoRespawn lost the null or disconnected guard");
        require(method(source,"public void disconnect(").contains("state.connected=false"),"disconnect must clear connected before linger");
        require(method(source,"public void afterRespawn(").contains("if(!state.dead)return;"),"afterRespawn lost its dead-state guard");
        String methods=method(source,"public boolean canRespawn(")+auto+method(source,"public void afterRespawn(");
        methods=methods.replace("net.minecraft.network.protocol.game.ServerboundClientCommandPacket","Packet").replace("net.minecraft.world.level.GameType","GameType");
        Path dir=Files.createTempDirectory("auto-respawn-");
        try{
            Path file=dir.resolve("AutoHarness.java");Files.writeString(file,HARNESS.replace("/* METHODS */",methods));
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",dir.toString(),file.toString())!=0)throw new AssertionError("Fixture compile failed");
            try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},RunAutoRespawnSafetyCheck.class.getClassLoader())){loader.loadClass("AutoHarness").getMethod("run").invoke(null);}
        }finally{try(var files=Files.walk(dir)){for(var path:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("PASS: throwing connection stays inside autoRespawn; tick continues, revive counts unchanged, fatal Error propagates");
        System.out.println("HUMAN: a real closing ServerGamePacketListenerImpl, and a fake/test player connection, need a running server. Not run: no live DB, no PID 14160, no dungeon entry.");
        System.out.println("Auto revive: null and disconnected skip, no charge without respawn, one charge on replay, other player untouched PASS");
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static String method(String source,String signature){int start=source.indexOf(signature);require(start>=0,"Missing method "+signature);int open=source.indexOf('{',start),depth=1,end=open+1;for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}return source.substring(start,end);}
    private static final String HARNESS="""
        import java.util.*;
        import kr.dalmaru.run.RunEncounterRules;
        enum GameType{SPECTATOR}
        class Position{double x,y,z;}
        class WarningLog{int calls;Object player,failure;void warn(String message,Object player,Object failure){calls++;this.player=player;this.failure=failure;}}
        record Packet(Action action){enum Action{PERFORM_RESPAWN}}
        class ServerPlayer{
            UUID id;boolean dead=true,spectator;float health;Connection connection=new Connection();
            ServerPlayer(UUID id){this.id=id;}
            class Connection{int calls;RuntimeException failure;Error fatal;Runnable respawn;void handleClientCommand(Packet packet){calls++;if(packet.action()!=Packet.Action.PERFORM_RESPAWN)throw new AssertionError("Wrong command");if(failure!=null)throw failure;if(fatal!=null)throw fatal;if(respawn!=null)respawn.run();}}
            UUID getUUID(){return id;}boolean isDeadOrDying(){return dead;}float getMaxHealth(){return 100;}
            float getYRot(){return 0;}float getXRot(){return 0;}void setHealth(float value){health=value;dead=false;}
            void setGameMode(GameType mode){spectator=true;}void teleportTo(Object level,double x,double y,double z,Set<?> set,float yaw,float pitch,boolean b){}
        }
        class State{UUID runId;boolean dead=true,connected=true;int revivesRemaining=2;long deathTick=10;Position lastSafe;State(UUID runId){this.runId=runId;}}
        class Encounter{boolean failed;Object level;}
        public class AutoHarness{
            static final WarningLog LOGGER=new WarningLog();
            Map<UUID,State>playerRuns=new HashMap<>();Map<UUID,Encounter>encounters=new HashMap<>();long tickCount;
            Position revivePosition(ServerPlayer player,Encounter encounter,State state){return new Position();}
            /* METHODS */
            static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
            public static void run(){
                var h=new AutoHarness();var run=UUID.randomUUID();var e=new Encounter();h.encounters.put(run,e);
                var a=new ServerPlayer(UUID.randomUUID());var b=new ServerPlayer(UUID.randomUUID());
                var sa=new State(run);var sb=new State(run);h.playerRuns.put(a.id,sa);h.playerRuns.put(b.id,sb);
                h.tickCount=310;
                check(h.canRespawn(a),"Mixin gate would cancel the automatic respawn once it is due");
                check(!h.autoRespawn(null)&&a.connection.calls==0&&sa.revivesRemaining==2,"Null player (already off the list) was revived");
                sa.connected=false;check(!h.autoRespawn(a)&&a.connection.calls==0,"Disconnected player was revived");sa.connected=true;
                for(int i=0;i<3;i++){check(h.autoRespawn(a),"Swallowed packet should stay eligible");h.tickCount++;}
                check(a.connection.calls==3&&sa.revivesRemaining==2&&sa.dead&&b.connection.calls==0&&sb.revivesRemaining==2,"Charge or other player changed without a respawn");
                a.connection.failure=new IllegalStateException("closed connection");
                int visited=0;
                for(var player:List.of(a,b)){boolean revived=h.autoRespawn(player);if(player==a)check(!revived,"Throwing connection reported success");visited++;}
                check(visited==2&&a.connection.calls==4&&b.connection.calls==1&&sa.revivesRemaining==2&&sa.dead&&sb.revivesRemaining==2&&sb.dead,"Throwing connection escaped the tick or changed revive state");
                check(LOGGER.calls==1&&a.id.equals(LOGGER.player)&&IllegalStateException.class.getName().equals(LOGGER.failure),"Failure warning lost player UUID or exception class");
                a.connection.failure=null;
                var fatal=new AssertionError("fatal connection failure");a.connection.fatal=fatal;
                try{h.autoRespawn(a);throw new AssertionError("Fatal Error swallowed");}catch(AssertionError error){check(error==fatal,"Fatal Error must escape unchanged");}
                check(sa.revivesRemaining==2&&sa.dead&&LOGGER.calls==1,"Fatal failure changed revive state or was caught as an ordinary warning");
                a.connection.fatal=null;a.connection.respawn=()->h.afterRespawn(a,a);
                check(h.autoRespawn(a)&&sa.revivesRemaining==1&&!sa.dead,"Completed respawn was not charged once");
                var replay=new ServerPlayer(a.id);replay.dead=false;
                h.afterRespawn(a,replay);h.afterRespawn(a,a);h.afterRespawn(replay,replay);
                check(!h.autoRespawn(a)&&sa.revivesRemaining==1&&sb.revivesRemaining==2&&sb.dead,"Replay double-charged or touched the other player");
                var stranger=new ServerPlayer(UUID.randomUUID());h.afterRespawn(stranger,stranger);
                check(sa.revivesRemaining==1&&sb.revivesRemaining==2&&!stranger.spectator,"Player outside the run changed run state");
            }
        }
        """;
}
