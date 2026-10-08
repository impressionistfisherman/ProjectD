package kr.dalmaru.client;

import java.util.ArrayList;
import java.util.List;
import kr.dalmaru.network.DalmaruPayloads;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruEquipmentScreen extends Screen {
    private final List<Button> buttons = new ArrayList<>();
    DalmaruEquipmentScreen() { super(Component.literal("달마루 장비")); }
    @Override protected void init() { DalmaruClient.requestEquipment(); rebuild(); }
    @Override public void tick() { rebuild(); }
    private void rebuild() {
        List<DalmaruPayloads.EquipmentData> equipment = ClientEquipmentState.equipment();
        if (buttons.size() == equipment.size()) { for (int i = 0; i < buttons.size(); i++) buttons.get(i).setMessage(message(equipment.get(i))); return; }
        clearWidgets(); buttons.clear();
        for (int i = 0; i < equipment.size(); i++) { DalmaruPayloads.EquipmentData item = equipment.get(i); Button button = addRenderableWidget(Button.builder(message(item), ignored -> DalmaruClient.requestEnhancement(item.equipmentId())).bounds(width / 2 - 180, height / 2 - 100 + i * 22, 360, 20).build()); buttons.add(button); }
        addRenderableWidget(Button.builder(Component.literal("닫기"), ignored -> onClose()).bounds(width / 2 - 180, height / 2 + 105, 360, 20).build());
    }
    private static Component message(DalmaruPayloads.EquipmentData item) { return Component.literal("슬롯 " + (item.slotId() + 1) + " · " + item.itemId() + "  +" + item.enhancement() + "  (실패 " + item.consecutiveFailures() + ")"); }
}
