package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.Comparator;
import javax.tools.ToolProvider;

final class WaypointApplicationCheck {
    static void run()throws Exception {
        String source=Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        StringBuilder methods=new StringBuilder();
        for(String signature:new String[]{"private static void applyWaypoint(","private static void waypointFailure(","private static void interruptWaypoint(","public static void attackStarted("})methods.append(FieldReviveBoundaryCheck.method(source,signature));
        int start=source.indexOf("ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {");
        int end=source.indexOf("});",start);
        if(start<0||end<start)throw new AssertionError("Damage hook missing");
        String damage=source.substring(source.indexOf('{',start)+1,end);
        String program=HARNESS.replace("/* METHODS */",methods).replace("/* DAMAGE */",damage)
            .replace("net.minecraft.resources.Identifier","Identifier").replace("net.minecraft.resources.ResourceKey","ResourceKey").replace("net.minecraft.core.registries.Registries.DIMENSION","\"dimension\"");
        Path dir=Files.createTempDirectory("waypoint-application-");
        try {
            Path file=dir.resolve("WaypointHarness.java");Files.writeString(file,program);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",dir.toString(),file.toString())!=0)throw new AssertionError("Waypoint application fixture compile");
            try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},WaypointApplicationCheck.class.getClassLoader())){loader.loadClass("WaypointHarness").getMethod("run").invoke(null);}
        }finally{try(var paths=Files.walk(dir)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static final String HARNESS="""
        import java.util.*;
        import kr.dalmaru.account.*;
        public class WaypointHarness {
            record Vec(double x,double y,double z){double distanceToSqr(Vec other){return Math.pow(x-other.x,2)+Math.pow(y-other.y,2)+Math.pow(z-other.z,2);}}
            record Identifier(String id){static Identifier tryParse(String id){return id.contains(" ")?null:new Identifier(id);}}
            record ResourceKey(Identifier id){static ResourceKey create(Object registry,Identifier id){return new ResourceKey(id);}}
            static class Level{}
            static class Server{Level getLevel(ResourceKey key){return key.id.id.equals("world")?level:null;}}
            static class Link{boolean closed;void disconnect(Object text){closed=true;}}
            static class Component{static String literal(String text){return text;}}
            static class ServerPlayer{UUID id=UUID.randomUUID();Link connection=new Link();Vec position=new Vec(0,0,0);int mode;UUID getUUID(){return id;}void sendSystemMessage(Object text){}Level level(){return level;}Vec position(){return position;}float getYRot(){return 0;}float getXRot(){return 0;}void teleportTo(Level target,double x,double y,double z,Set<?>flags,float yaw,float pitch,boolean relative){if(mode==1)throw new IllegalStateException("teleport");if(mode!=2)position=new Vec(x,y,z);}}
            static class ConnectionState{AccountSession accountSession=new AccountSession(UUID.randomUUID(),UUID.randomUUID(),1);String accountFailure;}
            static class Repo{boolean fail,applied;int calls;void finish(UUID a,long g,UUID c,UUID r,boolean ok)throws java.sql.SQLException{calls++;if(fail)throw new java.sql.SQLException("ACK");applied=ok;}}
            static class Run{UUID id;UUID runId(ServerPlayer p){return id;}}
            static class Combat{int remaining;int starts;int hits;int remainingCombatTicks(UUID p,long tick){return remaining;}void attackStarted(ServerPlayer p){starts++;}void recordValidHit(ServerPlayer p,long tick){hits++;}}
            static class Source{Object getEntity(){return null;}}
            static class Logger{void warn(String s,Object...args){}void error(String s,Object...args){}}
            static Level level=new Level();static Server server=new Server();static Repo WAYPOINTS=new Repo();static Run RUN_RUNTIME=new Run();static Combat COMBAT=new Combat();static Logger LOGGER=new Logger();
            static long currentTick;static boolean eligible=true,safe=true;static Map<UUID,Object>WAYPOINT_CASTS=new HashMap<>(),PREPARATIONS=new HashMap<>();
            static boolean waypointEligible(ServerPlayer p,ConnectionState state){return eligible;}
            static Server serverFor(ServerPlayer p){return server;}
            static Vec safeFieldPosition(ServerPlayer p,Level l,SafeCheckpoint point){return safe?new Vec(point.x(),point.y(),point.z()):null;}
            static boolean hostileCombatEntity(ServerPlayer player,Object other){return false;}
            /* METHODS */
            static void damage(Object entity,Source source,float baseDamageTaken,float damageTaken,boolean blocked){/* DAMAGE */}
            static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
            static void reset(){eligible=true;safe=true;RUN_RUNTIME.id=null;COMBAT.remaining=0;WAYPOINTS=new Repo();}
            public static void run(){
                var point=new SafeCheckpoint("town","world",10.5,64,20.5);var result=new WaypointRepository.Return(UUID.randomUUID(),point,true);
                for(int scenario=0;scenario<9;scenario++){
                    reset();var p=new ServerPlayer();var state=new ConnectionState();
                    if(scenario==1)safe=false;if(scenario==2)p.mode=1;if(scenario==3)p.mode=2;if(scenario==4)eligible=false;if(scenario==5)RUN_RUNTIME.id=UUID.randomUUID();if(scenario==6)COMBAT.remaining=1;if(scenario==7)WAYPOINTS.fail=true;
                    var input=scenario==8?new WaypointRepository.Return(result.request(),new SafeCheckpoint("town","missing",0,0,0),true):result;
                    applyWaypoint(p,state,input);
                    check(WAYPOINTS.calls==1,"Missing application ACK/cancel");
                    check(WAYPOINTS.applied==(scenario==0),"Unsafe/failed teleport consumed cooldown");
                    check(p.connection.closed==(scenario==7),"Uncertain ACK must disconnect");
                    if(scenario==7)check(state.accountFailure!=null,"Uncertain ACK did not poison connection");
                }
                reset();var p=new ServerPlayer();applyWaypoint(p,new ConnectionState(),new WaypointRepository.Return(result.request(),point,false));check(WAYPOINTS.calls==0,"Completed replay was applied twice");
                var other=new ServerPlayer();WAYPOINT_CASTS.put(p.id,new Object());WAYPOINT_CASTS.put(other.id,new Object());attackStarted(p);check(!WAYPOINT_CASTS.containsKey(p.id)&&WAYPOINT_CASTS.containsKey(other.id),"Attack cancelled another player");
                WAYPOINT_CASTS.put(p.id,new Object());damage(p,new Source(),4,0,true);check(!WAYPOINT_CASTS.containsKey(p.id),"Blocked hit failed to cancel");
                WAYPOINT_CASTS.put(p.id,new Object());damage(p,new Source(),0,0,false);check(WAYPOINT_CASTS.containsKey(p.id),"Zero hit unexpectedly cancelled");
            }
        }
        """;
}
