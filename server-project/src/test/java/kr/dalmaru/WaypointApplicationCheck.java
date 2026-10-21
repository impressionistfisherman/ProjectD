package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.Comparator;
import javax.tools.ToolProvider;

final class WaypointApplicationCheck {
    public static void main(String[] args)throws Exception {run();System.out.println("Waypoint application and isolated-plaza channel/teleport PASS");}
    static void run()throws Exception {
        String source=Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        StringBuilder methods=new StringBuilder();
        for(String signature:new String[]{"private static void applyWaypoint(","private static void waypointFailure(","private static void interruptWaypoint(","public static void attackStarted(","private static String isolatedWaypointExit(","private static void beginWaypoint("})methods.append(FieldReviveBoundaryCheck.method(source,signature));
        String tick=FieldReviveBoundaryCheck.method(source,"private static void tickWaypoints(");
        int visits=tick.indexOf("for(var player:server.getPlayerList().getPlayers())");
        if(visits<0)throw new AssertionError("Waypoint channel/visit boundary missing");
        methods.append(tick.substring(0,visits)).append("}\n");
        int start=source.indexOf("ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {");
        int end=source.indexOf("});",start);
        if(start<0||end<start)throw new AssertionError("Damage hook missing");
        String damage=source.substring(source.indexOf('{',start)+1,end);
        String program=HARNESS.replace("/* METHODS */",methods).replace("/* DAMAGE */",damage)
            .replace("kr.dalmaru.content.VillagePlazas","VillagePlazas").replace("ServerLevel","Level").replace("MinecraftServer","Server").replace("var cast=entry.getValue();","var cast=(WaypointCast)entry.getValue();").replace("net.minecraft.resources.Identifier","Identifier").replace("net.minecraft.resources.ResourceKey","ResourceKey").replace("net.minecraft.core.registries.Registries.DIMENSION","\"dimension\"");
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
            static class Level{Dimension dimension(){return new Dimension(new Identifier("world"));}}
            record Dimension(Identifier identifier){}
            static class VillagePlazas{static boolean isolated;static String waypointExit(Level level,double x,double y,double z){return isolated?"town":null;}}
            record WaypointCast(AccountSession session,WaypointChannel channel,UUID request){}
            static Map<UUID,ConnectionState> CONNECTIONS=new HashMap<>();static Map<UUID,ServerPlayer> players=new HashMap<>();
            static class Server{Server getPlayerList(){return this;}ServerPlayer getPlayer(UUID id){return players.get(id);}Level getLevel(ResourceKey key){return key.id.id.equals("world")?level:null;}}
            static class Link{boolean closed;void disconnect(Object text){closed=true;}}
            static class Component{static String literal(String text){return text;}}
            static class ServerPlayer{UUID id=UUID.randomUUID();Link connection=new Link();Vec position=new Vec(0,0,0);int mode;double getX(){return position.x;}double getY(){return position.y;}double getZ(){return position.z;}UUID getUUID(){return id;}void sendSystemMessage(Object text){}Level level(){return level;}Vec position(){return position;}float getYRot(){return 0;}float getXRot(){return 0;}void teleportTo(Level target,double x,double y,double z,Set<?>flags,float yaw,float pitch,boolean relative){if(mode==1)throw new IllegalStateException("teleport");if(mode!=2)position=new Vec(x,y,z);}}
            static class ConnectionState{AccountSession accountSession=new AccountSession(UUID.randomUUID(),UUID.randomUUID(),1);String accountFailure;}
            static class Repo{boolean fail,applied,cooldown,isolatedCheck,isolatedPrepare;int calls,unlocks,prepares;
                String unlockIsolatedExit(UUID a,long g,UUID c,String origin,String dimension,double x,double y,double z){unlocks++;return "other";}
                WaypointRepository.Return pending(UUID a,long g,UUID c){return null;}
                void check(UUID a,long g,UUID c,String id,boolean isolated){isolatedCheck=isolated;if(cooldown&&!isolated)throw new IllegalArgumentException("cooldown");}
                WaypointRepository.Return prepare(UUID a,long g,UUID c,UUID request,String id,boolean isolated){check(a,g,c,id,isolated);isolatedPrepare=isolated;prepares++;return new WaypointRepository.Return(request,new SafeCheckpoint(id,"world",10.5,64,20.5),true);}
                void finish(UUID a,long g,UUID c,UUID r,boolean ok)throws java.sql.SQLException{calls++;if(fail)throw new java.sql.SQLException("ACK");applied=ok;}}
            static class Run{UUID id;UUID runId(ServerPlayer p){return id;}}
            static class Combat{int remaining;int starts;int hits;int remainingCombatTicks(UUID p,long tick){return remaining;}void attackStarted(ServerPlayer p){starts++;}void recordValidHit(ServerPlayer p,long tick){hits++;}void recordIncomingHit(ServerPlayer p,long tick){hits++;}}
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
            static void reset(){currentTick=0;VillagePlazas.isolated=false;CONNECTIONS.clear();players.clear();WAYPOINT_CASTS.clear();PREPARATIONS.clear();eligible=true;safe=true;RUN_RUNTIME.id=null;COMBAT.remaining=0;WAYPOINTS=new Repo();}
            static void isolatedCases(){
                for(int scenario=0;scenario<10;scenario++){
                    reset();var p=new ServerPlayer();var state=new ConnectionState();CONNECTIONS.put(p.id,state);players.put(p.id,p);
                    VillagePlazas.isolated=scenario!=1;WAYPOINTS.cooldown=true;
                    if(scenario==3)RUN_RUNTIME.id=UUID.randomUUID();if(scenario==4)COMBAT.remaining=1;if(scenario==5)eligible=false;
                    beginWaypoint(p,scenario==2?"town":"other");
                    boolean started=scenario==0||scenario>=6;
                    check(WAYPOINT_CASTS.containsKey(p.id)==started,"Isolated exit start gate "+scenario);
                    if(!started){check(WAYPOINTS.prepares==0,"Rejected exit prepared movement");continue;}
                    currentTick=159;tickWaypoints(server);check(WAYPOINT_CASTS.containsKey(p.id)&&WAYPOINTS.prepares==0,"Isolated exit skipped the eight-second channel");
                    if(scenario==6)p.position=new Vec(1,0,0);
                    if(scenario==7)VillagePlazas.isolated=false;
                    if(scenario==8)state.accountSession=new AccountSession(state.accountSession.accountId(),state.accountSession.activeCharacterId(),2);
                    if(scenario==9)COMBAT.remaining=1;
                    currentTick=160;tickWaypoints(server);
                    check(!WAYPOINT_CASTS.containsKey(p.id),"Completed or cancelled exit stayed pending");
                    check(WAYPOINTS.applied==(scenario==0),"Isolated escape moved after loss of eligibility "+scenario);
                    if(scenario==0)check(p.position.x==10.5&&WAYPOINTS.isolatedPrepare&&WAYPOINTS.prepares==1&&WAYPOINTS.calls==1,"Isolated cooldown exception did not complete a different-village teleport and ACK");
                }
            }
            public static void run(){isolatedCases();
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
