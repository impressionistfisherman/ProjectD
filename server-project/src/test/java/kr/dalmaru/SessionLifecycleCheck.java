package kr.dalmaru;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;

/** Actual JOIN callback and connection predicate, with controllable DB waits and server scheduling. */
public final class SessionLifecycleCheck {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(System.getProperty("reviewSource","src"));
        String source=Files.readString(root.resolve("main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        int join=source.indexOf("ServerPlayConnectionEvents.JOIN.register");String body=body(source,source.indexOf('{',join));
        String predicate="";if(source.contains("private static boolean currentJoin(")){int start=source.indexOf("private static boolean currentJoin(");int open=source.indexOf('{',start);predicate=source.substring(start,open)+body(source,open);}
        int disconnect=source.indexOf("ServerPlayConnectionEvents.DISCONNECT.register");
        String disconnected=body(source,source.indexOf('{',disconnect));
        String code=HARNESS.replace("/* DISCONNECT */",disconnected).replace("/* JOIN */",body).replace("/* PREDICATE */",predicate).replace("kr.dalmaru.account.VanillaCharacterState","VanillaCharacterState");
        Path fixture=Files.createTempDirectory("dalmaru-session-check-");
        try {
            Path file=fixture.resolve("SessionHarness.java");Files.writeString(file,code);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",fixture.toString(),file.toString())!=0)throw new IllegalStateException("Session harness compilation failed.");
            try(var loader=new URLClassLoader(new java.net.URL[]{fixture.toUri().toURL()},ClassLoader.getPlatformClassLoader())){loader.loadClass("SessionHarness").getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
        }finally{try(var files=Files.walk(fixture)){for(Path file:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(file);}}
    }
    private static String body(String source,int open){int depth=1,end=open+1;for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}return source.substring(open,end);}
    private static final String HARNESS="""
        import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
        record AccountSession(UUID accountId,UUID activeCharacterId,long connectionGeneration){}
        record RunRecord(){}
        class RunRepository{AtomicInteger pauses=new AtomicInteger();record ReturnPosition(){}ReturnPosition pendingReturn(UUID id){return null;}RunRecord active(UUID id){return null;}void pauseIfUnattended(UUID id,List<UUID>connected){if(connected.isEmpty())pauses.incrementAndGet();}}
        record JoinedSession(AccountSession session,String vanillaState,RunRepository.ReturnPosition position,RunRecord run){}
        class Component{static String literal(String s){return s;}}
        class Handler{ServerPlayer player;boolean disconnected;Handler(ServerPlayer p){player=p;}ServerPlayer getPlayer(){return player;}void disconnect(String message){disconnected=true;}}
        class ServerPlayer{UUID id;Handler connection;String inventory="original";ServerPlayer(UUID id){this.id=id;connection=new Handler(this);}UUID getUUID(){return id;}}
        class ConnectionState{volatile AccountSession openedSession;Handler connection;AccountSession accountSession;String accountFailure;ConnectionState(long tick){}}
        class MinecraftServer{Map<UUID,ServerPlayer>players=new ConcurrentHashMap<>();List<Runnable>callbacks=Collections.synchronizedList(new ArrayList<>());ExecutorService main=Executors.newSingleThreadExecutor();boolean live;MinecraftServer getPlayerList(){return this;}ServerPlayer getPlayer(UUID id){return players.get(id);}void execute(Runnable r){if(live)main.execute(r);else callbacks.add(r);}void drain(){while(!callbacks.isEmpty())callbacks.removeFirst().run();}}
        class Log{void error(String message,Object...args){}void warn(String message,Object...args){}}
        class Combat{void reconnect(UUID id){}void remove(UUID id){}void disconnect(UUID id){}}class RuntimeState{void disconnect(ServerPlayer p){}UUID runId(ServerPlayer p){return null;}}
        class Accounts{AtomicLong generation=new AtomicLong();volatile boolean failFirst,blockFirst;CountDownLatch opened=new CountDownLatch(1),releaseOpen=new CountDownLatch(1);AccountSession openSession(UUID id){long number=generation.incrementAndGet();opened.countDown();if(blockFirst&&number==1)try{releaseOpen.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){throw new RuntimeException(e);}if(failFirst&&number==1)throw new IllegalStateException("old connection failed");return new AccountSession(id,id,number);}}
        class Characters{CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);volatile boolean block;void recoverVanilla(UUID id,long generation,String state,java.util.function.Consumer<String>install){entered.countDown();if(block)try{if(!release.await(3,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}catch(InterruptedException e){throw new RuntimeException(e);}if(generation!=SessionHarness.ACCOUNTS.generation.get())throw new IllegalStateException("stale generation");install.accept("recovered-"+generation);}}
        class VanillaCharacterState{static String capture(ServerPlayer p){return p.inventory;}static void restore(ServerPlayer p,String state){p.inventory=state;}}
        public class SessionHarness {
            static void clearFieldRevive(ServerPlayer player){}
            static void cancelPreparation(UUID id,String reason){}
            static Map<UUID,ConnectionState>CONNECTIONS=new HashMap<>();static ExecutorService LIFECYCLE_DB;static Accounts ACCOUNTS;static Characters CHARACTERS;static RuntimeState RUN_RUNTIME=new RuntimeState();static RunRepository RUNS=new RunRepository();static Combat COMBAT=new Combat();static Log LOGGER=new Log();static long currentTick;
            /* PREDICATE */
            static void joined(Handler handler,Object sender,MinecraftServer server) /* JOIN */
            static void disconnected(Handler handler,MinecraftServer server) /* DISCONNECT */
            static void restorePendingReturn(ServerPlayer p,AccountSession session){}static void restorePendingReturn(ServerPlayer p,AccountSession session,RunRepository.ReturnPosition pos){}static void sendCharacterSelectionReady(ServerPlayer p,ConnectionState s){}static void resumeRuntime(ServerPlayer p,RunRecord run){}
            static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
            static void waitFor(java.util.function.BooleanSupplier ready)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!ready.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);require(ready.getAsBoolean(),"fixture did not settle");}
            static void reset(){CONNECTIONS.clear();ACCOUNTS=new Accounts();CHARACTERS=new Characters();RUNS=new RunRepository();LIFECYCLE_DB=Executors.newSingleThreadExecutor();}
            static void stop(MinecraftServer server)throws Exception {CHARACTERS.release.countDown();ACCOUNTS.releaseOpen.countDown();LIFECYCLE_DB.shutdownNow();server.main.shutdownNow();require(LIFECYCLE_DB.awaitTermination(4,TimeUnit.SECONDS)&&server.main.awaitTermination(4,TimeUnit.SECONDS),"fixture threads leaked");}
            public static void main(String[]args)throws Exception {
                List<String>failures=new ArrayList<>();
                reset();var server=new MinecraftServer();server.live=true;UUID id=UUID.randomUUID();var player=new ServerPlayer(id);server.players.put(id,player);CHARACTERS.block=true;
                try {
                    joined(player.connection,null,server);require(CHARACTERS.entered.await(2,TimeUnit.SECONDS),"recovery never entered");
                    CountDownLatch tick=new CountDownLatch(1);server.execute(tick::countDown);require(tick.await(250,TimeUnit.MILLISECONDS),"DB recovery blocked other server ticks");System.out.println("N1 server tick PASS");
                }catch(AssertionError error){failures.add("N1");System.out.println("N1 FAIL: "+error.getMessage());}finally{stop(server);}
                for(boolean failure:new boolean[]{false,true}) {
                    reset();server=new MinecraftServer();id=UUID.randomUUID();var a=new ServerPlayer(id);server.players.put(id,a);ACCOUNTS.failFirst=failure;
                    try {
                        joined(a.connection,null,server);MinecraftServer current=server;waitFor(()->!current.callbacks.isEmpty());
                        var b=new ServerPlayer(id);server.players.put(id,b);joined(b.connection,null,server);waitFor(()->current.callbacks.size()>=2);
                        // Apply B first, then the delayed success/failure callback from A.
                        server.callbacks.remove(1).run();AccountSession newest=CONNECTIONS.get(id).accountSession;String inventory=b.inventory;server.drain();
                        require(newest!=null&&newest.connectionGeneration()==2&&CONNECTIONS.get(id).accountSession==newest&&CONNECTIONS.get(id).accountFailure==null&&!b.connection.disconnected&&b.inventory.equals(inventory),"old JOIN overwrote or disconnected B");System.out.println("N2 old "+(failure?"failure":"success")+" PASS");
                    }catch(AssertionError error){failures.add("N2");System.out.println("N2 FAIL: "+error.getMessage());}finally{stop(server);}
                }
                reset();server=new MinecraftServer();id=UUID.randomUUID();
                try {
                    ACCOUNTS.blockFirst=true;var a=new ServerPlayer(id);server.players.put(id,a);joined(a.connection,null,server);require(ACCOUNTS.opened.await(2,TimeUnit.SECONDS),"first generation did not enter");var b=new ServerPlayer(id);server.players.put(id,b);joined(b.connection,null,server);Thread.sleep(150);
                    require(ACCOUNTS.generation.get()==1,"new generation issued before previous session work completed");ACCOUNTS.blockFirst=false;ACCOUNTS.releaseOpen.countDown();MinecraftServer current=server;waitFor(()->current.callbacks.size()>=2);server.drain();require(CONNECTIONS.get(id).accountSession.connectionGeneration()==2&&!b.connection.disconnected,"FIFO generation did not reach current B");System.out.println("N2 generation order PASS");
                }catch(AssertionError error){failures.add("N2 order");System.out.println("N2 FAIL: "+error.getMessage());}finally{stop(server);}
                reset();server=new MinecraftServer();id=UUID.randomUUID();
                try {
                    ACCOUNTS.blockFirst=true;var a=new ServerPlayer(id);server.players.put(id,a);joined(a.connection,null,server);require(ACCOUNTS.opened.await(2,TimeUnit.SECONDS),"opening session never entered");
                    disconnected(a.connection,server);ACCOUNTS.blockFirst=false;ACCOUNTS.releaseOpen.countDown();MinecraftServer current=server;waitFor(()->!current.callbacks.isEmpty());LIFECYCLE_DB.submit(()->{}).get(3,TimeUnit.SECONDS);
                    require(RUNS.pauses.get()==1,"disconnect before JOIN completion lost DB pause");server.drain();require(CONNECTIONS.get(id)==null,"old JOIN resurrected disconnected state");System.out.println("N1/N2 pending JOIN disconnect PASS");
                }catch(AssertionError error){failures.add("pending disconnect");System.out.println("N1/N2 FAIL: "+error.getMessage());}finally{stop(server);}
                reset();server=new MinecraftServer();id=UUID.randomUUID();CHARACTERS.block=true;
                try {
                    var old=new ServerPlayer(id);server.players.put(id,old);joined(old.connection,null,server);require(CHARACTERS.entered.await(2,TimeUnit.SECONDS),"respawn recovery never entered");
                    var replacement=new ServerPlayer(id);replacement.connection=old.connection;replacement.connection.player=replacement;server.players.put(id,replacement);
                    CHARACTERS.release.countDown();MinecraftServer current=server;waitFor(()->!current.callbacks.isEmpty());server.drain();
                    require(CONNECTIONS.get(id).accountSession!=null&&!replacement.connection.disconnected&&replacement.inventory.equals("recovered-1")&&old.inventory.equals("original"),"same-connection respawn lost JOIN recovery");System.out.println("N3 respawn during JOIN PASS");
                }catch(AssertionError error){failures.add("N3");System.out.println("N3 FAIL: "+error.getMessage());}finally{stop(server);}
                if(!failures.isEmpty())throw new AssertionError("Session regressions: "+failures);
            }
        }
        """;
}
