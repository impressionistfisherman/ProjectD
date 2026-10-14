package kr.dalmaru;
import java.nio.file.*;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;
public final class FieldReviveBoundaryCheck {
 public static void main(String[]args)throws Exception{
 String source=Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));StringBuilder methods=new StringBuilder();for(String signature:new String[]{"private static kr.dalmaru.account.SafeCheckpoint activeCheckpoint(","private static void fieldDeath(","private static void fieldRespawn(","private static void clearFieldRevive("})methods.append(method(source,signature));
 Path dir=Files.createTempDirectory("field-revive-boundary-");Path file=dir.resolve("FieldHarness.java");Files.writeString(file,HARNESS.replace("/* METHODS */",methods.toString()).replace("net.minecraft.world.level.GameType.SPECTATOR","GameType.SPECTATOR").replace("kr.dalmaru.account.SafeCheckpoint","Point"));
 if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",dir.toString(),file.toString())!=0)throw new AssertionError("Field fixture compile failed");try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},ClassLoader.getPlatformClassLoader())){loader.loadClass("FieldHarness").getMethod("run").invoke(null);}
 fallbackBoundary(source);
 System.out.println("C06 death checkpoint snapshot/early-respawn wait/deadline/selection/disconnect and R7 fallback PASS");
 }
 static String method(String source,String signature){int start=source.indexOf(signature),open=source.indexOf('{',start),depth=1,end=open+1;for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}return source.substring(start,end);}
 static void fallbackBoundary(String source)throws Exception{
  String body=method(source,"private static FieldDestination fieldDestination(")
   .replace("kr.dalmaru.account.SafeCheckpoint","SafeCheckpoint")
   .replace("net.minecraft.resources.Identifier","Identifier")
   .replace("net.minecraft.resources.ResourceKey","ResourceKey")
   .replace("net.minecraft.core.registries.Registries.DIMENSION","\"dimension\"");
  Path dir=Files.createTempDirectory("field-fallback-boundary-");
  try{
   Path file=dir.resolve("FieldFallbackProof.java");Files.writeString(file,FALLBACK_HARNESS.replace("/* METHOD */",body));
   if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",dir.toString(),file.toString())!=0)throw new AssertionError("R7 fallback fixture compile failed");
   try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},FieldReviveBoundaryCheck.class.getClassLoader())){loader.loadClass("FieldFallbackProof").getMethod("run").invoke(null);}
  }finally{try(var paths=Files.walk(dir)){for(Path p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
 }
 static final String FALLBACK_HARNESS="""
 import java.util.*;
 import kr.dalmaru.account.SafeCheckpoint;
 import kr.dalmaru.account.TownZone;
 public class FieldFallbackProof{
  record Vec(double x,double y,double z){}
  static class ServerPlayer{}
  record Identifier(String name){static Identifier tryParse(String value){return value.contains(" ")?null:new Identifier(value);}public String toString(){return name;}}
  record ResourceKey(Identifier id){static ResourceKey create(Object registry,Identifier id){return new ResourceKey(id);}}
  record Dimension(Identifier identifier){}
  record Pos(int x,int y,int z){int getX(){return x;}int getY(){return y;}int getZ(){return z;}}
  record Spawn(Pos pos){}
  static class ServerLevel{String name;ServerLevel(String n){name=n;}Dimension dimension(){return new Dimension(new Identifier(name));}Spawn getRespawnData(){return new Spawn(new Pos(100,70,100));}}
  static class MinecraftServer{Map<String,ServerLevel>levels=Map.of("minecraft:overworld",new ServerLevel("minecraft:overworld"),"minecraft:nether",new ServerLevel("minecraft:nether"));ServerLevel overworld(){return levels.get("minecraft:overworld");}ServerLevel getLevel(ResourceKey key){return levels.get(key.id.name);}}
  record FieldDestination(ServerLevel level,Vec position){}
  static List<TownZone>TOWNS=List.of(new TownZone("minecraft:nether",30,64,0,32),new TownZone("minecraft:overworld",10,64,0,32),new TownZone("minecraft:overworld",20,64,0,32));
  static Set<String>safe=Set.of();static List<String>checked=new ArrayList<>();
  static class Logger{int warnings;void warn(String s,Object id){warnings++;}}static Logger LOGGER=new Logger();
  static Vec safeFieldPosition(ServerPlayer p,ServerLevel level,SafeCheckpoint point){checked.add(point.pointId());return safe.contains(point.pointId())?new Vec(point.x(),point.y(),point.z()):null;}
  /* METHOD */
  static void reset(String...ids){safe=Set.of(ids);checked.clear();LOGGER.warnings=0;}
  static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  public static void run(){
   var p=new ServerPlayer();var server=new MinecraftServer();var active=new SafeCheckpoint("active","minecraft:overworld",.5,64,.5);
   reset("active","town:20:0");var dest=fieldDestination(p,server,active);require(dest.position.x()==.5&&checked.equals(List.of("active")),"R7 active point priority");
   reset("town:20:0","town:30:0");dest=fieldDestination(p,server,active);require(dest.position.x()==20.5&&checked.equals(List.of("active","town:10:0","town:20:0")),"R7 same dimension and unsafe first town");
   reset("town:30:0");dest=fieldDestination(p,server,new SafeCheckpoint("missing","minecraft:missing",0,64,0));require(dest.level.name.equals("minecraft:nether")&&!checked.contains("missing"),"R7 missing dimension fallback");
   reset("town:20:0");dest=fieldDestination(p,server,new SafeCheckpoint("invalid","invalid dimension",0,64,0));require(dest.position.x()==20.5&&LOGGER.warnings==1,"R7 malformed dimension fallback");
   reset("world_entry");dest=fieldDestination(p,server,active);require(dest.position.x()==100.5&&dest.position.y()==70,"R7 world entry after unsafe towns");
   reset();require(fieldDestination(p,server,active)==null,"R7 all invalid must not select unsafe destination");
   TOWNS=List.of();reset("world_entry");require(fieldDestination(p,server,null).position.x()==100.5,"R7 absent active point and towns");
  }
 }
 """;
 static final String HARNESS="""
 import java.util.*;
 enum GameType{SURVIVAL,SPECTATOR}
 record AccountSession(UUID activeCharacterId){}
 record Point(String id){}
 record FieldRevive(AccountSession session,long due,Point checkpoint,GameType mode){}
 class ConnectionState{AccountSession accountSession;Point checkpoint;UUID checkpointCharacter;boolean startPending,startInvalidated,manualCombat=true;long manualUntil=100;}
 class ServerPlayer{UUID id=UUID.randomUUID();GameMode gameMode=new GameMode();UUID getUUID(){return id;}void setGameMode(GameType mode){gameMode.mode=mode;}}
 class GameMode{GameType mode=GameType.SURVIVAL;GameType getGameModeForPlayer(){return mode;}}
 public class FieldHarness{
 static Map<UUID,ConnectionState>CONNECTIONS=new HashMap<>();static Map<UUID,FieldRevive>FIELD_REVIVES=new HashMap<>();static long currentTick=200;
 /* METHODS */
 static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void run(){var p=new ServerPlayer();var state=new ConnectionState();state.accountSession=new AccountSession(UUID.randomUUID());state.checkpoint=new Point("prior_town");state.checkpointCharacter=state.accountSession.activeCharacterId();state.startPending=true;CONNECTIONS.put(p.id,state);fieldDeath(p);require(state.startInvalidated,"Death did not invalidate preparation");var pending=FIELD_REVIVES.get(p.id);require(pending.checkpoint()==state.checkpoint&&pending.due()==260,"Death state not frozen");state.checkpoint=new Point("vanilla_spawn");currentTick=210;fieldRespawn(p);require(FIELD_REVIVES.get(p.id)==pending&&pending.checkpoint().id().equals("prior_town")&&pending.due()==260,"Early respawn changed point/deadline");require(p.gameMode.mode==GameType.SPECTATOR&&!state.manualCombat&&state.manualUntil==0,"Pending actions enabled");clearFieldRevive(p);require(p.gameMode.mode==GameType.SURVIVAL&&FIELD_REVIVES.isEmpty(),"Disconnect leaked spectator");clearFieldRevive(p);require(p.gameMode.mode==GameType.SURVIVAL,"Repeated cleanup changed mode");var foreign=new ServerPlayer();var mismatch=new ConnectionState();mismatch.accountSession=new AccountSession(UUID.randomUUID());mismatch.checkpoint=new Point("other_character");mismatch.checkpointCharacter=UUID.randomUUID();CONNECTIONS.put(foreign.id,mismatch);fieldDeath(foreign);require(FIELD_REVIVES.get(foreign.id).checkpoint()==null,"Other character checkpoint was captured");clearFieldRevive(foreign);var unselected=new ServerPlayer();var empty=new ConnectionState();empty.accountSession=new AccountSession(null);CONNECTIONS.put(unselected.id,empty);fieldDeath(unselected);fieldRespawn(unselected);require(!FIELD_REVIVES.containsKey(unselected.id)&&unselected.gameMode.mode==GameType.SURVIVAL,"Unselected vanilla player intercepted");}
 }
 """;
}
