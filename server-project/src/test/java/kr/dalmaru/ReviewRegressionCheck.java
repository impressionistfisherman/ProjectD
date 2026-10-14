package kr.dalmaru;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.tools.ToolProvider;

/** Runs the callbacks and durable terrain journal from the supplied production source tree. */
public final class ReviewRegressionCheck {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(System.getProperty("reviewSource","src"));
        String network=Files.readString(root.resolve("main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        String profile=method(network,"private static void sendCharacterProfile(");
        if(network.contains("private static CharacterProfile applyProfile("))profile+=method(network,"private static CharacterProfile applyProfile(");
        String restore=method(network,"private static void restorePendingReturn(")
            .replace("RunRepository.ReturnPosition","Runs.ReturnPosition")
            .replace("serverFor(player).getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.Identifier.parse(position.dimension())))","serverFor(player).level")
            .replace("net.minecraft.world.level.GameType","GameType");
        int disconnect=network.indexOf("ServerPlayConnectionEvents.DISCONNECT.register");
        String pause=body(network,network.indexOf("{",disconnect));
        String screen=Files.readString(root.resolve("client/java/kr/dalmaru/client/DalmaruEquipmentScreen.java"))
            .replaceAll("(?m)^package .*;\\s*","").replaceAll("(?m)^import .*;\\s*","");
        String harness=HARNESS;
        if(!network.contains("AccountSession session,RunRepository.ReturnPosition position"))harness=harness.replace("restorePendingReturn(player,state.accountSession,RUNS.pendingReturn(character));","restorePendingReturn(player,state.accountSession);");
        String code=harness.replace("/* RESUME */",network.contains("LIFECYCLE_DB")?"CompletableFuture.runAsync(()->RUNS.active(character),LIFECYCLE_DB);":"RUNS.active(character);").replace("/* PROFILE */",profile).replace("/* RETURN */",restore).replace("/* DISCONNECT */",pause).replace("/* SCREEN */",screen);
        String journal="";
        Path journalFile=root.resolve("main/java/kr/dalmaru/run/RunTerrainRecovery.java");
        if(Files.exists(journalFile)) {
            journal=Files.readString(journalFile).replaceAll("(?m)^package .*;\\s*","").replaceAll("(?m)^import net\\..*;\\s*","").replace("public final class RunTerrainRecovery","final class RunTerrainRecovery").replace("net.minecraft.server.level.ServerPlayer","ServerPlayer");
            int start=journal.indexOf("/**");String imports=journal.substring(0,start);journal=journal.substring(start);code=imports+code;
        } else journal="class RunTerrainRecovery {static void prepare(ServerLevel l,UUID id,BlockPos pos,Map<BlockPos,BlockState> data){}static void recover(MinecraftServer server){}}";
        code+=journal;
        Path fixture=Files.createTempDirectory("dalmaru-review-methods-");
        try {
            Path file=fixture.resolve("ReviewHarness.java");Files.writeString(file,code);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",fixture.toString(),file.toString())!=0)throw new IllegalStateException("Review harness compilation failed.");
            try(var loader=new URLClassLoader(new java.net.URL[]{fixture.toUri().toURL()},ReviewRegressionCheck.class.getClassLoader())) {
                loader.loadClass("ReviewHarness").getMethod("main",String[].class).invoke(null,(Object)new String[0]);
            }
        }finally{try(var paths=Files.walk(fixture)){for(Path file:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(file);}}
    }
    private static String method(String source,String signature){int start=source.indexOf(signature);if(start<0)throw new IllegalStateException(signature);int open=source.indexOf('{',start);return source.substring(start,open)+body(source,open);}
    private static String body(String source,int open){int depth=1,end=open+1;for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}return source.substring(open,end);}
    private static final String HARNESS="""
        import java.util.*;
        import java.nio.file.*;
        class Component {String text;Component(String s){text=s;}static Component literal(String s){return new Component(s);}}
        class Button {Component message;java.util.function.Consumer<Button> action;Button(Component m,java.util.function.Consumer<Button>a){message=m;action=a;}void setMessage(Component m){message=m;}void click(){action.accept(this);}static Builder builder(Component m,java.util.function.Consumer<Button>a){return new Builder(m,a);}static class Builder{Component m;java.util.function.Consumer<Button>a;Builder(Component m,java.util.function.Consumer<Button>a){this.m=m;this.a=a;}Builder bounds(int x,int y,int w,int h){return this;}Button build(){return new Button(m,a);}}}
        class Screen {int width=800,height=600;List<Button> widgets=new ArrayList<>();Screen(Component title){}protected void init(){}public void tick(){}void clearWidgets(){widgets.clear();}Button addRenderableWidget(Button b){widgets.add(b);return b;}void onClose(){}}
        class DalmaruPayloads {record EquipmentData(UUID equipmentId,int slotId,String itemId,int enhancement,int consecutiveFailures){}record CharacterProfileResponse(UUID id,CharacterProfile data){}}
        class ClientEquipmentState {static List<DalmaruPayloads.EquipmentData> items;static List<DalmaruPayloads.EquipmentData> equipment(){return items;}}
        class DalmaruClient {static UUID moved,enhanced;static void requestEquipment(){}static void requestEquipmentMove(UUID id){moved=id;}static void requestEnhancement(UUID id){enhanced=id;}}
        /* SCREEN */
        record CharacterProfile(UUID characterId,long revision){boolean supersedes(CharacterProfile p){return p==null||!characterId.equals(p.characterId)||revision>=p.revision;}}
        record AccountSession(UUID accountId,UUID activeCharacterId,long connectionGeneration){}
        class ConnectionState {volatile AccountSession openedSession;Object connection;String accountFailure;AccountSession accountSession;CharacterProfile activeProfile;}
        enum GameType{SURVIVAL,SPECTATOR;static GameType byName(String name){return valueOf(name.toUpperCase());}}
        class Connection{void disconnect(Component reason){throw new IllegalStateException(reason.text);}}
        class ServerPlayer extends Entity {UUID id=UUID.randomUUID();GameType mode=GameType.SPECTATOR;Connection connection=new Connection();ServerPlayer(ServerLevel l){super(l);}UUID getUUID(){return id;}void setGameMode(GameType m){mode=m;}float getYRot(){return 0;}float getXRot(){return 0;}void teleportTo(ServerLevel l,double x,double y,double z,Set<?> set,float yaw,float pitch,boolean flag){level=l;}}
        class Handler {ServerPlayer p;Handler(ServerPlayer p){this.p=p;}ServerPlayer getPlayer(){return p;}}
        class Log{void error(String s,Exception e){throw new IllegalStateException(s,e);}void warn(String s,Exception e){throw new IllegalStateException(s,e);}}
        class Combat {void remove(UUID id){}void disconnect(UUID id){}}
        class Runtime {void disconnect(ServerPlayer p){}UUID runId(ServerPlayer p){return null;}}
        class CompletableFuture {static List<Runnable> tasks=new ArrayList<>();static void runAsync(Runnable r){tasks.add(r);}static void runAsync(Runnable r,java.util.concurrent.Executor ignored){tasks.add(r);}static void drain(){for(var r:tasks)r.run();tasks.clear();}}
        class Runs {String state="ACTIVE",returnMode="survival";boolean acknowledged;GameType modeAtAck;record ReturnPosition(UUID runId,String dimension,double x,double y,double z,String mode){}ReturnPosition pendingReturn(UUID id){return new ReturnPosition(UUID.randomUUID(),"minecraft:overworld",1,64,1,returnMode);}void acknowledgeReturn(UUID id){acknowledged=true;modeAtAck=ReviewHarness.savedMode;}void acknowledgeReturn(UUID account,long generation,UUID character,UUID run){acknowledgeReturn(character);}void pauseIfUnattended(UUID id,List<UUID> ids){if(ids.isEmpty())state="PAUSED";}void active(UUID id){state="ACTIVE";}}
        class ServerPlayNetworking {static CharacterProfile sent;static void send(ServerPlayer p,DalmaruPayloads.CharacterProfileResponse r){sent=r.data();}}
        record BlockPos(int x,int y,int z){int getX(){return x;}int getY(){return y;}int getZ(){return z;}}
        record Dimension(String name){Identifier identifier(){return new Identifier(name);}}
        record Identifier(String name){static Identifier parse(String s){return new Identifier(s);}public String toString(){return name;}}
        class Registries{static String DIMENSION="dimension";}
        record ResourceKey(Identifier id){static ResourceKey create(String registry,Identifier id){return new ResourceKey(id);}}
        class LevelResource{static String ROOT="root";}
        record BlockState(String name){static Codec CODEC=new Codec();}
        class Codec{Result encodeStart(Object ops,BlockState s){return new Result(new com.google.gson.JsonPrimitive(s.name()));}Result parse(Object ops,com.google.gson.JsonElement json){return new Result(new BlockState(json.getAsString()));}}
        record Result(Object value){@SuppressWarnings("unchecked")<T>T getOrThrow(){return (T)value;}}
        class Registry {Object createSerializationContext(Object ops){return ops;}}
        class AABB{AABB(int a,int b,int c,int d,int e,int f){}}
        class Entity{ServerLevel level;boolean discarded;Entity(ServerLevel l){level=l;}void discard(){discarded=true;}}
        class Chunks{ServerLevel level;Chunks(ServerLevel l){level=l;}void save(boolean flush){level.saved=new HashMap<>(level.blocks);}}
        class ServerLevel{MinecraftServer server;Map<BlockPos,BlockState> blocks=new HashMap<>(),saved=new HashMap<>();List<Entity>entities=new ArrayList<>();Dimension dimension(){return new Dimension("minecraft:overworld");}Registry registryAccess(){return new Registry();}MinecraftServer getServer(){return server;}Chunks getChunkSource(){return new Chunks(this);} <T extends Entity>List<T>getEntitiesOfClass(Class<T> type,AABB box,java.util.function.Predicate<T>predicate){return entities.stream().filter(type::isInstance).map(type::cast).filter(predicate).toList();}}
        class MinecraftServer{Path path;ServerLevel level;MinecraftServer(Path p){path=p;level=new ServerLevel();level.server=this;}Path getWorldPath(String ignored){return path;}ServerLevel getLevel(ResourceKey key){return level;}MinecraftServer getPlayerList(){return this;}void saveAll(){ReviewHarness.savedMode=ReviewHarness.player.mode;}}
        class BeaconTowerMap{static void restore(ServerLevel l,Map<BlockPos,BlockState> terrain){l.blocks.putAll(terrain);}}
        public class ReviewHarness {
            static void clearFieldRevive(ServerPlayer player){}
            static void cancelPreparation(UUID id,String reason){}
            static Map<UUID,ConnectionState>CONNECTIONS=new HashMap<>();static CharacterProfile combat;static GameType savedMode;
            static ServerPlayer player;static MinecraftServer server;static java.util.concurrent.Executor LIFECYCLE_DB=Runnable::run;static Runs RUNS=new Runs();static Combat COMBAT=new Combat();static Runtime RUN_RUNTIME=new Runtime();static Log LOGGER=new Log();
            static ConnectionState stateFor(UUID id){return CONNECTIONS.computeIfAbsent(id,k->new ConnectionState());}static void refreshCombat(ServerPlayer p,CharacterProfile profile){combat=profile;}static CharacterProfile profileData(CharacterProfile p){return p;}static MinecraftServer serverFor(ServerPlayer p){return server;}
            /* PROFILE */
            /* RETURN */
            static void disconnected(Handler handler,MinecraftServer server) /* DISCONNECT */
            static Map<BlockPos,BlockState>terrain(){Map<BlockPos,BlockState>data=new HashMap<>();for(int x=-9;x<=9;x++)for(int z=-6;z<=74;z++)for(int y=0;y<=5;y++)data.put(new BlockPos(x,100+y,z),new BlockState("stone"));return data;}
            static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
            static void check(String id,Runnable test,List<String>failures){try{test.run();System.out.println(id+" PASS");}catch(Throwable error){failures.add(id);System.out.println(id+" FAIL: "+error.getMessage());}}
            public static void main(String[] args)throws Exception {
                if(args.length==3&&args[0].equals("crash")) {
                    Path world=Path.of(args[1]);server=new MinecraftServer(world);UUID run=UUID.fromString(args[2]);Map<BlockPos,BlockState>terrain=terrain();
                    server.level.blocks.putAll(terrain);RunTerrainRecovery.prepare(server.level,run,new BlockPos(0,100,0),terrain);
                    Files.writeString(world.resolve("saved-world.txt"),"map-and-boss");
                    java.lang.Runtime.getRuntime().halt(23);
                }
                Path path=Files.createTempDirectory("dalmaru-journal-world-");server=new MinecraftServer(path);player=new ServerPlayer(server.level);UUID character=UUID.randomUUID();var state=stateFor(player.id);state.accountSession=new AccountSession(UUID.randomUUID(),character,1);List<String>failures=new ArrayList<>();
                check("R1",()->{var newer=new CharacterProfile(character,2);sendCharacterProfile(player,newer);sendCharacterProfile(player,new CharacterProfile(character,1));require(state.activeProfile==newer&&combat==newer&&ServerPlayNetworking.sent==newer,"reverse callback regressed profile/combat/UI");},failures);
                check("R3",()->{UUID a=UUID.randomUUID(),b=UUID.randomUUID();var first=new DalmaruPayloads.EquipmentData(a,0,"A",0,0);var second=new DalmaruPayloads.EquipmentData(b,-1,"B",0,0);ClientEquipmentState.items=List.of(first,second);var screen=new DalmaruEquipmentScreen();screen.init();ClientEquipmentState.items=List.of(new DalmaruPayloads.EquipmentData(b,0,"B",0,0),new DalmaruPayloads.EquipmentData(a,-1,"A",0,0));screen.tick();require(screen.widgets.get(0).message.text.contains("B"),"row not refreshed");screen.widgets.get(0).click();screen.widgets.get(1).click();require(b.equals(DalmaruClient.moved)&&b.equals(DalmaruClient.enhanced),"displayed B sent A id");},failures);
                check("R4",()->{disconnected(new Handler(player),server);CONNECTIONS.put(player.id,state);/* RESUME */CompletableFuture.drain();require(RUNS.state.equals("ACTIVE"),"late disconnect paused reconnected run");},failures);
                check("R5",()->{player.mode=GameType.SPECTATOR;restorePendingReturn(player,state.accountSession,RUNS.pendingReturn(character));CompletableFuture.drain();require(player.mode==GameType.SURVIVAL&&RUNS.acknowledged&&RUNS.modeAtAck==GameType.SURVIVAL,"offline spectator mode not durably restored before ack");},failures);
                check("V022 legacy return",()->{RUNS.returnMode=null;RUNS.acknowledged=false;player.mode=GameType.SPECTATOR;boolean rejected=false;try{restorePendingReturn(player,state.accountSession,RUNS.pendingReturn(character));}catch(IllegalStateException expected){rejected=true;}CompletableFuture.drain();require(rejected&&!RUNS.acknowledged&&player.mode==GameType.SPECTATOR,"NULL legacy mode was guessed or acknowledged");},failures);
                check("R6",()->{try {
                    UUID run=UUID.randomUUID();Map<BlockPos,BlockState>terrain=terrain();
                    String fixture=Path.of(ReviewHarness.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
                    var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",fixture+java.io.File.pathSeparator+System.getProperty("java.class.path"),"ReviewHarness","crash",path.toString(),run.toString()).inheritIO().start();
                    require(child.waitFor()==23,"crash writer did not halt after journal/world save");require(Files.readString(path.resolve("saved-world.txt")).equals("map-and-boss"),"world was not persisted before process exit");
                    var reloaded=new MinecraftServer(path);terrain.forEach((pos,block)->reloaded.level.blocks.put(pos,new BlockState("map")));Entity boss=new Entity(reloaded.level);reloaded.level.entities.add(boss);
                    RunTerrainRecovery.recover(reloaded);require(reloaded.level.blocks.equals(terrain)&&reloaded.level.saved.equals(terrain)&&boss.discarded,"fresh runtime retained map or boss after crash");
                    require(!Files.exists(path.resolve("dalmaru-run-recovery").resolve(run+".json")),"journal not acknowledged after restoration");
                }catch(Exception error){throw new RuntimeException(error);}},failures);
                try(var paths=Files.walk(path)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}
                if(!failures.isEmpty())throw new AssertionError("Review regressions failed: "+failures);
            }
        }
        """;
}
