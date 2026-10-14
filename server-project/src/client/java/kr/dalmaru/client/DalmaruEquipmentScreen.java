package kr.dalmaru.client;

import java.util.ArrayList;
import java.util.List;
import kr.dalmaru.network.DalmaruPayloads;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruEquipmentScreen extends Screen {
    private final List<Button> buttons = new ArrayList<>();
    private List<java.util.UUID> displayedIds = List.of();
    private int page,displayedPage=-1;
    DalmaruEquipmentScreen() { super(Component.literal("달마루 장비")); }
    @Override protected void init() { displayedPage=-1;DalmaruClient.requestEquipment(); rebuild(); }
    @Override public void tick() { rebuild(); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta){
        super.extractRenderState(graphics,mouseX,mouseY,delta);
        String status=font.plainSubstrByWidth(ClientEquipmentState.status(),width-20);
        graphics.text(font,status,(width-font.width(status))/2,6,0xFFFFFFFF,false);
    }
    private void rebuild() {
        List<DalmaruPayloads.EquipmentData> equipment = ClientEquipmentState.equipment();
        List<java.util.UUID> ids = equipment.stream().map(DalmaruPayloads.EquipmentData::equipmentId).toList();
        int rows=Math.max(1,(height-70)/22),pages=Math.max(1,(equipment.size()+rows-1)/rows);
        page=Math.min(page,pages-1);int start=page*rows,end=Math.min(equipment.size(),start+rows);
        if (displayedPage==page&&displayedIds.equals(ids)) { for (int i = 0; i < buttons.size(); i++) buttons.get(i).setMessage(message(equipment.get(start+i))); return; }
        clearWidgets(); buttons.clear(); displayedIds=ids;displayedPage=page;
        int contentWidth=Math.min(360,width-20),left=(width-contentWidth)/2,enhanceWidth=Math.min(80,contentWidth/3),itemWidth=contentWidth-enhanceWidth-10;
        for (int i = start; i < end; i++) {
            var item=equipment.get(i);int y=20+(i-start)*22;
            buttons.add(addRenderableWidget(Button.builder(message(item),ignored->DalmaruClient.requestEquipmentMove(item.equipmentId())).bounds(left,y,itemWidth,20).build()));
            addRenderableWidget(Button.builder(Component.literal("강화"),ignored->DalmaruClient.requestEnhancement(item.equipmentId())).bounds(left+itemWidth+10,y,enhanceWidth,20).build());
        }
        int half=(contentWidth-10)/2;
        var previous=addRenderableWidget(Button.builder(Component.literal("이전 · "+(page+1)+"/"+pages),ignored->{page--;rebuild();}).bounds(left,height-48,half,20).build());previous.active=page>0;
        var next=addRenderableWidget(Button.builder(Component.literal("다음"),ignored->{page++;rebuild();}).bounds(left+half+10,height-48,half,20).build());next.active=page<pages-1;
        addRenderableWidget(Button.builder(Component.literal("닫기"), ignored -> onClose()).bounds(left,height-24,contentWidth,20).build());
    }
    private static Component message(DalmaruPayloads.EquipmentData item) { return Component.literal((item.slotId()<0 ? "가방 [장착]" : "슬롯 " + (item.slotId()+1) + " [해제]") + " · " + item.itemId() + "  +" + item.enhancement() + "  (실패 " + item.consecutiveFailures() + ")"); }
}
