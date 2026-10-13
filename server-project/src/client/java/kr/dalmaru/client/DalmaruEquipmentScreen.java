package kr.dalmaru.client;

import java.util.ArrayList;
import java.util.List;
import kr.dalmaru.network.DalmaruPayloads;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruEquipmentScreen extends Screen {
    private final List<Button> buttons = new ArrayList<>();
    private List<java.util.UUID> displayedIds = List.of();
    DalmaruEquipmentScreen() { super(Component.literal("달마루 장비")); }
    @Override protected void init() { DalmaruClient.requestEquipment(); rebuild(); }
    @Override public void tick() { rebuild(); }
    private void rebuild() {
        List<DalmaruPayloads.EquipmentData> equipment = ClientEquipmentState.equipment();
        List<java.util.UUID> ids = equipment.stream().map(DalmaruPayloads.EquipmentData::equipmentId).toList();
        if (buttons.size() == equipment.size() && displayedIds.equals(ids)) { for (int i = 0; i < buttons.size(); i++) buttons.get(i).setMessage(message(equipment.get(i))); return; }
        clearWidgets(); buttons.clear(); displayedIds=ids;
        for (int i = 0; i < equipment.size(); i++) { DalmaruPayloads.EquipmentData item = equipment.get(i); Button button = addRenderableWidget(Button.builder(message(item), ignored -> DalmaruClient.requestEquipmentMove(item.equipmentId())).bounds(width / 2 - 180, height / 2 - 100 + i * 22, 270, 20).build()); buttons.add(button); addRenderableWidget(Button.builder(Component.literal("강화"),ignored->DalmaruClient.requestEnhancement(item.equipmentId())).bounds(width/2+100,height/2-100+i*22,80,20).build()); }
        addRenderableWidget(Button.builder(Component.literal("닫기"), ignored -> onClose()).bounds(width / 2 - 180, height / 2 + 105, 360, 20).build());
    }
    private static Component message(DalmaruPayloads.EquipmentData item) { return Component.literal((item.slotId()<0 ? "가방 [장착]" : "슬롯 " + (item.slotId()+1) + " [해제]") + " · " + item.itemId() + "  +" + item.enhancement() + "  (실패 " + item.consecutiveFailures() + ")"); }
}
