package kr.dalmaru;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;

/** Executes the actual lifecycle and movement methods with deterministic world/container doubles. */
public final class PremergeRuntimeCheck {
    public static void main(String[] args)throws Exception {
        String source=Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        StringBuilder methods=new StringBuilder();
        for(String signature:new String[]{"public void enter(","public DeathResult recordDeath(","public void afterRespawn(","public void disconnect(","public void tick(","public void close(","public UUID runId(","public int revivesRemaining(","private UUID region(","public boolean mayTarget(","public boolean mayReceive(","public void loadedEntity(","public void move(","private static net.minecraft.world.phys.AABB bounds(","private static boolean inside(","private static BlockPos floor(","private static void teleport(ServerPlayer player, Encounter"})methods.append(method(source,signature)).append('\n');
        String combat=Files.readString(Path.of("src/main/java/kr/dalmaru/combat/CombatRuntime.java"));
        StringBuilder combatMethods=new StringBuilder();
        for(String signature:new String[]{"public void tick(long", "public void disconnect(UUID", "public void reconnect(UUID", "public void shiftCooldowns(UUID"})combatMethods.append(method(combat,signature)).append('\n');
        var matcher=java.util.regex.Pattern.compile("private final Map<UUID, Long> (\\w+)").matcher(combat);
        StringBuilder maps=new StringBuilder();java.util.List<String> names=new java.util.ArrayList<>();
        while(matcher.find()){String name=matcher.group(1);names.add(name);maps.append("final Map<UUID,Long> ").append(name).append("=new HashMap<>();\n");}
        String proof="class RuntimeState{int maximumStamina=100;double stamina=12,staminaRegenerationPerSecond=5;long lastCombatTick=0,nextBasicAttackTick=30;}class CombatProof{static final long COMBAT_TIMEOUT_TICKS=200;final Map<UUID,RuntimeState>states=new HashMap<>();final Set<UUID>disconnected=new HashSet<>();"+maps+combatMethods+"static void run(){var c=new CombatProof();UUID id=UUID.randomUUID();var state=new RuntimeState();c.states.put(id,state);for(var map:List.of("+String.join(",",names.stream().map(n->"c."+n).toList())+"))map.put(id,200L);c.disconnect(id);c.tick(400);SafetyHarness.require(state.stamina==12,\"J19 offline stamina changed\");c.reconnect(id);c.shiftCooldowns(id,100);for(var map:List.of("+String.join(",",names.stream().map(n->"c."+n).toList())+"))SafetyHarness.require(map.get(id)==300,\"J19 cooldown reset or elapsed while paused\");SafetyHarness.require(state.lastCombatTick==100&&state.nextBasicAttackTick==130,\"J19 attack timer shifted incorrectly\");}}";
        String code=HARNESS.replace("/* COMBAT */",proof).replace("/* METHODS */",methods.toString()).replace("net.minecraft.world.entity.LivingEntity","Entity").replace("net.minecraft.world.entity.Entity","Entity").replace("net.minecraft.world.level.block.state.BlockState","BlockState").replace("net.minecraft.world.phys.Vec3","Vec3").replace("net.minecraft.world.phys.AABB","Box").replace("net.minecraft.world.level.GameType.SPECTATOR","GameType.SPECTATOR").replace("GameType.SURVIVAL","GameType.SURVIVAL");
        Path directory=Files.createTempDirectory("dalmaru-premerge-runtime-");
        try {
            Path java=directory.resolve("SafetyHarness.java");Files.writeString(java,code);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",directory.toString(),java.toString())!=0)throw new IllegalStateException("Runtime harness compilation failed.");
            try(var loader=new URLClassLoader(new java.net.URL[]{directory.toUri().toURL()},ClassLoader.getPlatformClassLoader())){loader.loadClass("SafetyHarness").getMethod("main",String[].class).invoke(null,(Object)new String[0]);}
        } finally {try(var paths=Files.walk(directory)){for(Path file:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(file);}}
    }
    private static String method(String source,String signature){int start=source.indexOf(signature);if(start<0)throw new IllegalStateException(signature);int open=source.indexOf('{',start),depth=1,end=open+1;for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}return source.substring(start,end);}
    private static final String HARNESS="""
        import java.util.*;
        class Vec3 { final double x,y,z; static final Vec3 ZERO=new Vec3(0,0,0);Vec3(double x,double y,double z){this.x=x;this.y=y;this.z=z;}Vec3 add(double a,double b,double c){return new Vec3(x+a,y+b,z+c);}Vec3 add(Vec3 b){return add(b.x,b.y,b.z);}Vec3 subtract(Vec3 b){return add(-b.x,-b.y,-b.z);}Vec3 scale(double s){return new Vec3(x*s,y*s,z*s);}Vec3 normalize(){double n=Math.sqrt(x*x+y*y+z*z);return n==0?ZERO:scale(1/n);} }
        record BlockPos(int x,int y,int z){BlockPos offset(int x,int y,int z){return new BlockPos(this.x+x,this.y+y,this.z+z);}Vec3 getCenter(){return new Vec3(x+.5,y+.5,z+.5);}int getX(){return x;}int getY(){return y;}int getZ(){return z;}}
        class Box {double a,b,c,d,e,f;Box(double a,double b,double c,double d,double e,double f){this.a=a;this.b=b;this.c=c;this.d=d;this.e=e;this.f=f;}boolean contains(Vec3 p){return p.x>=a&&p.x<d&&p.y>=b&&p.y<e&&p.z>=c&&p.z<f;}Box deflate(double n){return new Box(a+n,b+n,c+n,d-n,e-n,f-n);}Box move(Vec3 v){return new Box(a+v.x,b+v.y,c+v.z,d+v.x,e+v.y,f+v.z);}}
        class Component{static String literal(String value){return value;}}
        class Connection{boolean disconnected;void disconnect(String value){disconnected=true;}}
        class BlockState{}
        enum RunDifficulty{FIRST_STEP,TRAINING}
        enum GameType{SURVIVAL,SPECTATOR}
        enum Stage{GATEKEEPER,BRAZIER,BEACON,COMPLETED}
        class Entity {Vec3 position=new Vec3(0,64,0);ServerLevel level;boolean discarded;Entity(ServerLevel l){level=l;}ServerLevel level(){return level;}Vec3 position(){return position;}Set<String>tags=new HashSet<>();Set<String>entityTags(){return tags;}boolean addTag(String tag){return tags.add(tag);}void discard(){discarded=true;}}
        class Mob extends Entity {boolean noAi;Mob(ServerLevel l){super(l);}void setNoAi(boolean value){noAi=value;}}
        class PlayerGameMode{ServerPlayer player;PlayerGameMode(ServerPlayer p){player=p;}GameType getGameModeForPlayer(){return player.mode;}}
        class ServerPlayer extends Entity {final Connection connection=new Connection();final PlayerGameMode gameMode=new PlayerGameMode(this);UUID id=UUID.randomUUID();boolean alive=true,hurtMarked;GameType mode=GameType.SURVIVAL;Vec3 look=new Vec3(1,0,0);float health=100;ServerPlayer(ServerLevel l){super(l);}UUID getUUID(){return id;}boolean isAlive(){return alive;}boolean isSpectator(){return mode==GameType.SPECTATOR;}void setGameMode(GameType m){mode=m;}boolean onGround(){return true;}Vec3 getLookAngle(){return look;}Box getBoundingBox(){return new Box(position.x-.3,position.y,position.z-.3,position.x+.3,position.y+1.8,position.z+.3);}void teleportTo(double x,double y,double z){position=new Vec3(x,y,z);}void teleportTo(ServerLevel l,double x,double y,double z,Set<?> relatives,float yaw,float pitch,boolean flag){level=l;teleportTo(x,y,z);}float getYRot(){return 0;}float getXRot(){return 0;}void setDeltaMovement(Vec3 v){}void setHealth(float v){health=v;}float getMaxHealth(){return 100;} }
        class ServerLevel {Map<UUID,Mob> mobs=new HashMap<>();double wall=Double.POSITIVE_INFINITY;Object getEntity(UUID id){return mobs.get(id);}boolean noCollision(ServerPlayer p,Box box){return box.d<wall;}<T extends Entity>List<T> getEntitiesOfClass(Class<T> type,Box box,java.util.function.Predicate<T> predicate){List<T> result=new ArrayList<>();for(Mob m:mobs.values())if(type.isInstance(m)&&box.contains(m.position)&&predicate.test(type.cast(m)))result.add(type.cast(m));return result;}}
        class MinecraftServer {Map<UUID,ServerPlayer> players=new HashMap<>();MinecraftServer getPlayerList(){return this;}ServerPlayer getPlayer(UUID id){return players.get(id);}List<ServerPlayer> getPlayers(){return new ArrayList<>(players.values());}}
        class BeaconTowerMap {static int restored;static Map<BlockPos,BlockState> capture(ServerLevel l,BlockPos p){return new HashMap<>();}static void build(ServerLevel l,BlockPos p){}static void restore(ServerLevel l,Map<BlockPos,BlockState> data){restored++;}}
        class RunTerrainRecovery{static void prepare(ServerLevel l,UUID run,BlockPos floor,Map<BlockPos,BlockState>terrain){}static void complete(ServerLevel l,UUID run){}}
        class BeaconTowerBosses{static void style(ServerLevel l,UUID id,boolean magic){}}
        class State {final UUID runId;int revivesRemaining;boolean dead,connected=true;long disconnectedAt;ServerLevel returnLevel;Vec3 returnPosition,lastSafe;GameType returnMode;State(UUID id,int lives){runId=id;revivesRemaining=lives;}}
        class Encounter {UUID runId,bossId;RunDifficulty difficulty;BlockPos floor;ServerLevel level;int partySize;long pausedAt,pausedTick,deadline=System.currentTimeMillis()+2700000;boolean failed;Stage stage=Stage.GATEKEEPER;Map<UUID,Integer>brazierTicks=new HashMap<>();Map<BlockPos,BlockState>terrain;Encounter(UUID id,RunDifficulty d,BlockPos f,ServerLevel l,int size){runId=id;difficulty=d;floor=f;level=l;partySize=size;}}
        /* COMBAT */
        public class SafetyHarness {
            int released;final java.util.function.Consumer<UUID> releaseDisconnected=id->released++;
            long tickCount;final Map<UUID,State> playerRuns=new HashMap<>();final Map<UUID,Encounter> encounters=new HashMap<>();int bossSteps;
            record DeathResult(UUID runId,boolean canRespawn,boolean partyDefeated){}
            static void teleport(ServerPlayer p,BlockPos f){p.teleportTo(f.x()+.5,f.y()+1,f.z()+.5);}
            static UUID spawnBoss(ServerLevel l,BlockPos p,String name,double health){UUID id=UUID.randomUUID();Mob mob=new Mob(l);mob.position=p.getCenter();l.mobs.put(id,mob);return id;}
            static double health(RunDifficulty d,int base){return base;}
            static boolean nearBrazier(ServerPlayer p,BlockPos pos){return false;}
            void activateFinalBoss(Encounter e){}void tickBoss(MinecraftServer server,Encounter e){bossSteps++;}
            /* METHODS */
            public static void main(String[] args){
                CombatProof.run();
                ServerLevel level=new ServerLevel();MinecraftServer server=new MinecraftServer();SafetyHarness runtime=new SafetyHarness();
                ServerPlayer a=new ServerPlayer(level),b=new ServerPlayer(level);server.players.put(a.id,a);server.players.put(b.id,b);UUID party=UUID.randomUUID();runtime.enter(a,party,RunDifficulty.FIRST_STEP,2);runtime.enter(b,party,RunDifficulty.FIRST_STEP,2);
                require(runtime.recordDeath(a).canRespawn()&&runtime.revivesRemaining(a)==5,"J18 charged life on death");runtime.afterRespawn(a,a);require(runtime.revivesRemaining(a)==4,"J18 did not charge actual revival");
                runtime.recordDeath(a);DeathResult wipe=runtime.recordDeath(b);require(wipe.partyDefeated()&&!wipe.canRespawn(),"J18 all-dead party survived");
                runtime.close(server,party);require(runtime.playerRuns.isEmpty()&&runtime.encounters.isEmpty()&&BeaconTowerMap.restored==1,"J21 did not restore/release region");
                UUID solo=UUID.randomUUID();runtime.enter(a,solo,RunDifficulty.FIRST_STEP,1);require(runtime.recordDeath(a).canRespawn(),"J18 solo exception broken");runtime.afterRespawn(a,a);int lives=runtime.revivesRemaining(a);
                runtime.disconnect(a);require(runtime.playerRuns.containsKey(a.id)&&runtime.encounters.get(solo).pausedAt>0,"J19/J20 lost disconnected state");int ticks=runtime.bossSteps;runtime.tick(server);require(runtime.bossSteps==ticks,"J20 paused boss kept advancing");runtime.enter(a,solo,RunDifficulty.FIRST_STEP,1);require(runtime.revivesRemaining(a)==lives&&runtime.encounters.get(solo).pausedAt==0,"J19 reset lives on reconnection");
                Encounter e=runtime.encounters.get(solo);a.teleportTo(e.floor.x()+8,e.floor.y()+1,e.floor.z()+2);runtime.move(a,5,false);require(a.position.x<e.floor.x()+9,"C17 crossed run wall");
                a.teleportTo(e.floor.x(),e.floor.y()+1,e.floor.z()+2);level.wall=e.floor.x()+2;runtime.move(a,5,false);require(a.position.x<=e.floor.x()+1.5,"C17 crossed solid obstruction");level.wall=Double.POSITIVE_INFINITY;
                Mob enemy=new Mob(level);enemy.position=a.position;require(runtime.mayTarget(a,enemy),"C21 denied own run target");enemy.position=new Vec3(0,64,0);require(!runtime.mayTarget(a,enemy)&&!runtime.mayTarget(a,b),"C21 allowed another run/player target");
                b.position=a.position;runtime.tick(server);require(b.connection.disconnected,"J21 outsider remained in private region");
                e.deadline=System.currentTimeMillis()-1;runtime.tick(server);require(runtime.encounters.isEmpty()&&runtime.runId(a)==null,"J20 timeout did not clean world state");
                UUID abandoned=UUID.randomUUID();runtime.enter(a,abandoned,RunDifficulty.FIRST_STEP,1);runtime.disconnect(a);server.players.remove(a.id);runtime.close(server,abandoned);require(runtime.released==1,"J19 disconnected combat state leaked after closure");
                Mob late=new Mob(level);late.tags.add("dalmaru.run."+abandoned);runtime.loadedEntity(late);require(late.discarded,"R6 delayed loading kept cancelled run entity");
                System.out.println("C17/C21/J21/J18/J19/J20 production-method regressions PASS");
            }
            static void require(boolean result,String message){if(!result)throw new IllegalStateException(message);}
        }
        """;
}
