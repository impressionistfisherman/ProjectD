package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruRunScreen extends Screen {
    private Button status;
    private Button firstStep, training,ready;
    DalmaruRunScreen() { super(Component.literal("폐봉수대")); }
    @Override protected void init() {
        int contentWidth=Math.min(360,width-20),left=(width-contentWidth)/2;
        status = addRenderableWidget(Button.builder(Component.literal(ClientRunState.status()), ignored -> { }).bounds(left, height / 2 - 45, contentWidth, 20).build()); status.active = false;
        firstStep = addRenderableWidget(Button.builder(Component.literal("초행 입장 · Lv.15 · 주 3회"), ignored -> DalmaruClient.requestRun("FIRST_STEP")).bounds(left, height / 2 - 15, contentWidth, 20).build());
        training = addRenderableWidget(Button.builder(Component.literal("수련 입장 · Lv.20 · 초행 완료 필요"), ignored -> DalmaruClient.requestRun("TRAINING")).bounds(left, height / 2 + 10, contentWidth, 20).build());
        ready=addRenderableWidget(Button.builder(Component.literal("준비 완료"),ignored->DalmaruClient.readyRun()).bounds(left,height/2+35,contentWidth,20).build());
        addRenderableWidget(Button.builder(Component.literal("닫기"), ignored -> onClose()).bounds(left, height / 2 + 65, contentWidth, 20).build());
    }
    @Override public void tick() { boolean available = ClientRunState.canRequest()&&!ClientRunState.preparing();if(ready!=null)ready.active=ClientRunState.preparing(); firstStep.active = available; training.active = available; status.setMessage(Component.literal(ClientRunState.status())); }
}
