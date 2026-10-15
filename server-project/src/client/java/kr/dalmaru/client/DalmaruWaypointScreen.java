package kr.dalmaru.client;

import java.util.List;
import java.util.UUID;
import kr.dalmaru.network.DalmaruPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruWaypointScreen extends Screen {
    private List<String> ids=List.of();
    private int index;
    private long pending;
    private UUID character;
    private String status="직접 방문한 마을만 해금됩니다. 8초 준비 · 15분 재사용";
    private Button destination;
    DalmaruWaypointScreen(){super(Component.literal("역참 귀환"));}
    @Override protected void init(){
        int w=Math.min(280,width-16),x=(width-w)/2,y=height/2-38;
        destination=addRenderableWidget(Button.builder(Component.literal(label()),b->{if(!ids.isEmpty()){index=(index+1)%ids.size();destination.setMessage(Component.literal(label()));}}).bounds(x,y,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("목록 새로고침"),b->request("list","")).bounds(x,y+24,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("선택 역참으로 귀환"),b->{if(!ids.isEmpty())request("return",ids.get(index));}).bounds(x,y+48,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("귀환 취소"),b->request("cancel","")).bounds(x,y+72,w,20).build());
        request("list","");
    }
    private String label(){return ids.isEmpty()?"해금된 역참 없음":ids.get(index)+" (눌러 다음 역참)";}
    private void request(String action,String id){
        var loadout=ClientLoadoutState.current();if(!ClientConnectionState.isCompatible()||loadout==null)return;
        character=loadout.characterId();pending=ClientLoadoutState.next();
        ClientPlayNetworking.send(new DalmaruPayloads.WaypointRequest(pending,character,action,id));
    }
    void accept(DalmaruPayloads.WaypointResponse response){
        var loadout=ClientLoadoutState.current();
        if(response.requestId()!=pending||!response.characterId().equals(character)||loadout==null||!character.equals(loadout.characterId()))return;
        status=response.status();if(status.equals("해금 역참 목록")){ids=response.waypoints();index=0;destination.setMessage(Component.literal(label()));}
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta){
        super.extractRenderState(graphics,mouseX,mouseY,delta);
        String text=font.plainSubstrByWidth(status,Math.max(0,width-16));
        graphics.text(font,text,(width-font.width(text))/2,Math.max(2,height/2-56),0xFFFFFFFF,false);
    }
}
