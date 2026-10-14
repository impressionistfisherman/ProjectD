package kr.dalmaru.client;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.gui.components.AbstractWidget;
import com.mojang.blaze3d.platform.InputConstants;
import kr.dalmaru.network.DalmaruPayloads;
import java.util.*;
import java.io.File;
import org.lwjgl.glfw.GLFW;
public final class ClientLiveCheck implements ClientModInitializer {
 int tick,phase;UUID character=UUID.randomUUID();List<String> slots=List.of("skill.warrior.sweep","skill.warrior.advance","skill.warrior.rising_strike","skill.warrior.battle_cry","");
 public void onInitializeClient(){ClientLifecycleEvents.CLIENT_STARTED.register(client->ConnectScreen.startConnecting(new TitleScreen(),client,ServerAddress.parseString("127.0.0.1:57702"),new ServerData("Oscar isolated QA","127.0.0.1:57702",ServerData.Type.OTHER),false,null));ClientTickEvents.END_CLIENT_TICK.register(client->{try{step(client);}catch(Throwable error){System.out.println("CLIENT_LIVE_FAIL");error.printStackTrace();client.stop();}});}
 void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
 void step(Minecraft client)throws Exception {
  tick++;if(tick>1200)throw new AssertionError("Client QA timeout");if(client.player==null)return;
  if(phase==0){
   if(tick<100)return;var selected=ClientCharacterState.class.getDeclaredField("selectionConfirmed");selected.setAccessible(true);selected.setBoolean(null,true);
   client.options.pauseOnLostFocus=false;client.getWindow().setWindowed(640,480);client.options.guiScale().set(2);client.resizeGui();
   ClientProfileState.accept(new DalmaruPayloads.CharacterProfileResponse(character,new DalmaruPayloads.CharacterProfileData(15,0,0,10,10,10,10,10,true,0,"WARRIOR")));
   ClientLoadoutState.selected(character);ClientLoadoutState.accept(new DalmaruPayloads.LoadoutResponse(1,character,0,slots,true,""));
   ClientSkillState.accept(new DalmaruPayloads.SkillRanksResponse(1,slots.stream().filter(s->!s.isEmpty()).map(s->new DalmaruPayloads.SkillRank(s,1)).toList()));
   client.setScreen(new DalmaruSkillScreen(null));phase=1;tick=0;return;
  }
  if(phase==1&&tick==120){checkScreen(client);Screenshot.grab(new File("C:/Users/gusdl/AppData/Local/Temp/oscar-mdf16-qa"),"skill-320x240.png",client.getMainRenderTarget(),1,c->{});client.setScreen(new DalmaruRunScreen());phase=2;tick=0;return;}
  if(phase==2&&tick==120){checkScreen(client);Screenshot.grab(new File("C:/Users/gusdl/AppData/Local/Temp/oscar-mdf16-qa"),"run-320x240.png",client.getMainRenderTarget(),1,c->{});client.setScreen(null);ClientLoadoutState.reset();ClientCombatState.accept(new DalmaruPayloads.CombatStateResponse(100,100,false,false,0,List.of(0,0,0,0,0),slots,2,100));client.player.getInventory().setSelectedSlot(5);KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_1));handleKeys(client);phase=3;tick=0;return;}
  if(phase==3&&tick==5){require(client.player.getInventory().getSelectedSlot()==0,"Life mode numeric key lost vanilla selection");ClientCombatState.accept(new DalmaruPayloads.CombatStateResponse(100,100,false,true,0,List.of(0,0,0,0,0),slots,2,100));client.player.getInventory().setSelectedSlot(5);KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_2));handleKeys(client);phase=4;tick=0;return;}
  if(phase==4&&tick==5){require(client.player.getInventory().getSelectedSlot()==5,"Combat number changed vanilla slot");client.player.getInventory().setSelectedSlot(0);KeyMapping.click(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_6));handleKeys(client);phase=5;tick=0;return;}
  if(phase==5&&tick==5){require(client.player.getInventory().getSelectedSlot()==5,"Combat 6 lost vanilla routing");var mappings=Arrays.asList(client.options.keyMappings);require(mappings.stream().noneMatch(k->k.getName().equals("key.dalmaru.sweep")),"Legacy skill keys still registered");require(mappings.stream().anyMatch(k->k.getName().equals("key.dalmaru.toggle_mode")&&k.getDefaultKey().getValue()==GLFW.GLFW_KEY_GRAVE_ACCENT),"Mode key default wrong");require(mappings.stream().anyMatch(k->k.getName().equals("key.dalmaru.dodge")&&k.getDefaultKey().getValue()==GLFW.GLFW_KEY_LEFT_ALT),"Dodge key default wrong");phase=6;tick=0;return;}
  if(phase==6&&tick==30){Screenshot.grab(new File("C:/Users/gusdl/AppData/Local/Temp/oscar-mdf16-qa"),"combat-hud.png",client.getMainRenderTarget(),1,c->{});System.out.println("CLIENT_LIVE_PASS actual render/bounds and injected numeric key routing, defaults/legacy cleanup; profile/combat were fixture snapshots");phase=7;tick=0;}
  if(phase==7&&tick==30)client.stop();
 }
 void handleKeys(Minecraft client)throws Exception{var m=Minecraft.class.getDeclaredMethod("handleKeybinds");m.setAccessible(true);m.invoke(client);}
 void checkScreen(Minecraft client){var screen=client.screen;var widgets=screen.children().stream().filter(w->w instanceof AbstractWidget).map(w->(AbstractWidget)w).toList();require(widgets.size()==(screen instanceof DalmaruSkillScreen?kr.dalmaru.skill.SkillDefinition.forProfession(kr.dalmaru.account.CharacterProfession.WARRIOR).size()+6:5),"Missing widgets: "+screen.getClass().getSimpleName()+" count="+widgets.size());for(var w:widgets)require(w.getX()>=0&&w.getY()>=0&&w.getX()+w.getWidth()<=screen.width&&w.getY()+w.getHeight()<=screen.height,"Clipped live widget");for(int i=0;i<widgets.size();i++)for(int j=i+1;j<widgets.size();j++){var a=widgets.get(i);var b=widgets.get(j);require(a.getX()+a.getWidth()<=b.getX()||b.getX()+b.getWidth()<=a.getX()||a.getY()+a.getHeight()<=b.getY()||b.getY()+b.getHeight()<=a.getY(),"Live widgets overlap");}}
}
