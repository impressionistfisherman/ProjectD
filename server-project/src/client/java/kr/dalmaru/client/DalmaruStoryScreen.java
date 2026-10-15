package kr.dalmaru.client;

import java.util.*;
import kr.dalmaru.content.VillageCatalog;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruStoryScreen extends Screen {
    private final Screen previous;
    private Button npc,interact,report;
    private List<String> ids=List.of();private int index,ticks;
    DalmaruStoryScreen(Screen previous){super(Component.literal("1~20 이야기"));this.previous=previous;}
    @Override protected void init(){
        int w=Math.min(300,width-16),x=(width-w)/2,y=height/2-30;
        npc=addRenderableWidget(Button.builder(Component.literal("이야기 NPC 확인 중"),b->{if(!ids.isEmpty())index=(index+1)%ids.size();}).bounds(x,y,w,20).build());
        interact=addRenderableWidget(Button.builder(Component.literal("NPC 상호작용"),b->{if(!ids.isEmpty())DalmaruClient.storyOperation("story_interact",ids.get(index));}).bounds(x,y+24,w,20).build());
        report=addRenderableWidget(Button.builder(Component.literal("개인 보고 · 완료 보상"),b->{if(!ids.isEmpty())DalmaruClient.storyOperation("story_report",ids.get(index));}).bounds(x,y+48,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("새로고침"),b->DalmaruClient.requestStoryState()).bounds(x,y+72,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("돌아가기"),b->minecraft.setScreen(previous)).bounds(x,y+96,w,20).build());
        DalmaruClient.requestStoryState();
    }
    @Override public void tick(){
        if(++ticks%100==0)DalmaruClient.requestStoryState();var state=ClientStoryState.current();
        var next=new ArrayList<String>();if(state!=null)for(var entry:state.getAsJsonArray("npcs"))next.add(entry.getAsString());
        if(!next.equals(ids)){ids=List.copyOf(next);index=0;}
        String label=ids.isEmpty()?"현재 이야기 NPC 없음":npcLabel(ids.get(index));npc.setMessage(Component.literal(font.plainSubstrByWidth(label,npc.getWidth()-12)));
        npc.active=!ids.isEmpty();interact.active=!ids.isEmpty()&&!ClientStoryState.waiting();report.active=interact.active&&state!=null&&state.get("ready").getAsBoolean();
    }
    private static String npcLabel(String id){for(var village:VillageCatalog.current())for(var npc:village.npcs())if(npc.id().equals(id))return village.name()+" · "+npc.name()+" ("+village.zone().x()+", "+village.zone().y()+", "+village.zone().z()+")";return id;}
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta){
        super.extractRenderState(graphics,mouseX,mouseY,delta);var state=ClientStoryState.current();var lines=new ArrayList<String>();
        lines.add(state==null?"1~20 이야기":state.get("title").getAsString());
        lines.add(state==null?ClientStoryState.message():"필수 전투 경험치 "+state.get("combatXP").getAsInt()+"/"+state.get("combatGoal").getAsInt());
        lines.add(state!=null&&!state.getAsJsonArray("missing").isEmpty()?state.getAsJsonArray("missing").get(0).getAsString():ClientStoryState.message());
        int y=Math.max(2,height/2-74);for(String line:lines){String text=font.plainSubstrByWidth(line,Math.max(0,width-16));graphics.text(font,text,(width-font.width(text))/2,y,0xFFFFFFFF,false);y+=12;}
    }
}
