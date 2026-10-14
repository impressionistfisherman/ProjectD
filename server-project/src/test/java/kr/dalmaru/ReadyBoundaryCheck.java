package kr.dalmaru;
import java.nio.file.*;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;
public final class ReadyBoundaryCheck {
    public static void main(String[]args)throws Exception {
        String source=Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        int start=source.indexOf("    private static void readyRun(ServerPlayer player,UUID preparationId,boolean ready){");
        int end=source.indexOf("    private static void enterPreparedRun(",start);
        Path dir=Files.createTempDirectory("ready-boundary-");
        String fixture=HEADER+source.substring(start,end)+TEST;
        Path file=dir.resolve("ReadyHarness.java");Files.writeString(file,fixture);
        if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",dir.toString(),file.toString())!=0)throw new AssertionError("READY fixture compile failed");
        try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},ClassLoader.getPlatformClassLoader())){loader.loadClass("ReadyHarness").getMethod("run").invoke(null);}
        System.out.println("C01 actual READY pending-write/all-ready/stale-session/roster/town PASS; live packets remain unverified.");
    }
    private static final String HEADER="""
        import java.util.*;
        public class ReadyHarness {
        static Map<UUID,ConnectionState> CONNECTIONS=new HashMap<>();
        static Map<UUID,PreparedRun> PREPARATIONS=new HashMap<>();
        static Parties PARTIES=new Parties();static Runtime RUN_RUNTIME=new Runtime();static int started,cancelled;
        static class ConnectionState {Object accountSession=new Object(),loadout=new Object(),activeProfile=new Object();String accountFailure;int loadoutWrites;boolean startPending;}
        static class ServerPlayer {UUID id=UUID.randomUUID();boolean inTown=true;UUID getUUID(){return id;}boolean isAlive(){return true;}boolean isSpectator(){return false;}}
        static class Server {Map<UUID,ServerPlayer> players=new HashMap<>();Server getPlayerList(){return this;}ServerPlayer getPlayer(UUID id){return players.get(id);}}
        static class Context {Server server;Server server(){return server;}}
        static class PreparedRun {UUID preparationId=UUID.randomUUID();List<UUID> players;List<Object> sessions;Set<UUID> ready=new HashSet<>();Context context;}
        static class Parties {List<UUID> roster;List<UUID> members(UUID id){return roster;}}
        static class Runtime {Object runId(ServerPlayer p){return null;}}
        static boolean town(ServerPlayer p){return p.inTown;}
        static void cancelPreparation(UUID id,String reason){cancelled++;PREPARATIONS.clear();}
        static void notifyPreparation(PreparedRun p,String reason){}
        static void enterPreparedRun(PreparedRun p){started++;}
        static ServerPlayer a,b;static PreparedRun prep;
        static void setup(){CONNECTIONS.clear();PREPARATIONS.clear();started=cancelled=0;a=new ServerPlayer();b=new ServerPlayer();prep=new PreparedRun();prep.players=List.of(a.id,b.id);PARTIES.roster=prep.players;CONNECTIONS.put(a.id,new ConnectionState());CONNECTIONS.put(b.id,new ConnectionState());prep.sessions=prep.players.stream().map(id->CONNECTIONS.get(id).accountSession).toList();prep.context=new Context();prep.context.server=new Server();prep.context.server.players.put(a.id,a);prep.context.server.players.put(b.id,b);PREPARATIONS.put(a.id,prep);PREPARATIONS.put(b.id,prep);}
        static void require(boolean condition){if(!condition)throw new AssertionError("READY boundary");}
        """;
    private static final String TEST="""
        public static void run(){
        setup();readyRun(a,UUID.randomUUID(),true);require(prep.ready.isEmpty());readyRun(a,prep.preparationId,true);require(started==0);readyRun(b,prep.preparationId,true);require(started==1&&PREPARATIONS.isEmpty()&&CONNECTIONS.get(a.id).startPending&&CONNECTIONS.get(b.id).startPending);
        setup();CONNECTIONS.get(b.id).loadoutWrites=1;readyRun(a,prep.preparationId,true);require(prep.ready.isEmpty()&&started==0);CONNECTIONS.get(b.id).loadoutWrites=0;readyRun(a,prep.preparationId,true);readyRun(a,prep.preparationId,false);readyRun(b,prep.preparationId,true);require(started==0);readyRun(a,prep.preparationId,true);require(started==1);
        setup();CONNECTIONS.get(b.id).accountSession=new Object();readyRun(a,prep.preparationId,true);require(cancelled==1&&started==0);
        setup();PARTIES.roster=List.of(a.id);readyRun(a,prep.preparationId,true);require(cancelled==1&&started==0);
        setup();CONNECTIONS.get(b.id).activeProfile=null;readyRun(a,prep.preparationId,true);require(cancelled==1&&started==0);
        setup();b.inTown=false;readyRun(a,prep.preparationId,true);require(cancelled==1&&started==0);
        setup();prep.context.server.players.remove(b.id);readyRun(a,prep.preparationId,true);require(cancelled==1&&started==0);
        }
        }
        """;
}
