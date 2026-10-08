package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruRunScreen extends Screen {
    private Button status;
    DalmaruRunScreen() { super(Component.literal("폐봉수대")); }
    @Override protected void init() {
        status = addRenderableWidget(Button.builder(Component.literal(ClientRunState.status()), ignored -> { }).bounds(width / 2 - 180, height / 2 - 45, 360, 20).build()); status.active = false;
        addRenderableWidget(Button.builder(Component.literal("초행 입장 · Lv.15 · 주 3회"), ignored -> DalmaruClient.requestRun("FIRST_STEP")).bounds(width / 2 - 180, height / 2 - 15, 360, 20).build());
        addRenderableWidget(Button.builder(Component.literal("수련 입장 · Lv.20 · 초행 완료 필요"), ignored -> DalmaruClient.requestRun("TRAINING")).bounds(width / 2 - 180, height / 2 + 10, 360, 20).build());
        addRenderableWidget(Button.builder(Component.literal("닫기"), ignored -> onClose()).bounds(width / 2 - 180, height / 2 + 45, 360, 20).build());
    }
    @Override public void tick() { status.setMessage(Component.literal(ClientRunState.status())); }
}
