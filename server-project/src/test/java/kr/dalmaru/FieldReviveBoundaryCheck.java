package kr.dalmaru;
import java.nio.file.*;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;
public final class FieldReviveBoundaryCheck {
 public static void main(String[]args)throws Exception{
 String source=Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));StringBuilder methods=new StringBuilder();for(String signature:new String[]{"private static void fieldDeath(","private static void fieldRespawn(","private static void clearFieldRevive("})methods.append(method(source,signature));
 Path dir=Files.createTempDirectory("field-revive-boundary-");Path file=dir.resolve("FieldHarness.java");Files.writeString(file,HARNESS.replace("/* METHODS */",methods.toString()).replace("net.minecraft.world.level.GameType.SPECTATOR","GameType.SPECTATOR"));
 if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",dir.toString(),file.toString())!=0)throw new AssertionError("Field fixture compile failed");try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},ClassLoader.getPlatformClassLoader())){loader.loadClass("FieldHarness").getMethod("run").invoke(null);}
 System.out.println("C06 death checkpoint snapshot/early-respawn wait/deadline/selection/disconnect mode restoration PASS");
 }
 static String method(String source,String signature){int start=source.indexOf(signature),open=source.indexOf('{',start),depth=1,end=open+1;for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}return source.substring(start,end);}
 static final String HARNESS="""
 import java.util.*;
 enum GameType{SURVIVAL,SPECTATOR}
 record AccountSession(UUID activeCharacterId){}
 record Point(String id){}
 record FieldRevive(AccountSession session,long due,Point checkpoint,GameType mode){}
 class ConnectionState{AccountSession accountSession;Point checkpoint;boolean manualCombat=true;long manualUntil=100;}
 class ServerPlayer{UUID id=UUID.randomUUID();GameMode gameMode=new GameMode();UUID getUUID(){return id;}void setGameMode(GameType mode){gameMode.mode=mode;}}
 class GameMode{GameType mode=GameType.SURVIVAL;GameType getGameModeForPlayer(){return mode;}}
 public class FieldHarness{
 static Map<UUID,ConnectionState>CONNECTIONS=new HashMap<>();static Map<UUID,FieldRevive>FIELD_REVIVES=new HashMap<>();static long currentTick=200;
 /* METHODS */
 static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void run(){var p=new ServerPlayer();var state=new ConnectionState();state.accountSession=new AccountSession(UUID.randomUUID());state.checkpoint=new Point("prior_town");CONNECTIONS.put(p.id,state);fieldDeath(p);var pending=FIELD_REVIVES.get(p.id);require(pending.checkpoint()==state.checkpoint&&pending.due()==260,"Death state not frozen");state.checkpoint=new Point("vanilla_spawn");currentTick=210;fieldRespawn(p);require(FIELD_REVIVES.get(p.id)==pending&&pending.checkpoint().id().equals("prior_town")&&pending.due()==260,"Early respawn changed point/deadline");require(p.gameMode.mode==GameType.SPECTATOR&&!state.manualCombat&&state.manualUntil==0,"Pending actions enabled");clearFieldRevive(p);require(p.gameMode.mode==GameType.SURVIVAL&&FIELD_REVIVES.isEmpty(),"Disconnect leaked spectator");clearFieldRevive(p);require(p.gameMode.mode==GameType.SURVIVAL,"Repeated cleanup changed mode");var unselected=new ServerPlayer();var empty=new ConnectionState();empty.accountSession=new AccountSession(null);CONNECTIONS.put(unselected.id,empty);fieldDeath(unselected);fieldRespawn(unselected);require(!FIELD_REVIVES.containsKey(unselected.id)&&unselected.gameMode.mode==GameType.SURVIVAL,"Unselected vanilla player intercepted");}
 }
 """;
}
