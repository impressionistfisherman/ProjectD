package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruRunScreen extends Screen {
    private Button status;
    private Button firstStep, training,ready;
    DalmaruRunScreen() { super(Component.literal(kr.dalmaru.content.ContentCatalog.current().display("run.bongsudae").name())); }
    @Override protected void init() {
        int contentWidth=Math.min(360,width-20),left=(width-contentWidth)/2;
        int rows=6,step=Math.max(12,Math.min(22,(height-8)/rows)),buttonHeight=Math.max(10,step-2),top=Math.max(4,(height-rows*step)/2);
        status = addRenderableWidget(Button.builder(Component.literal(ClientRunState.status()), ignored -> { }).bounds(left, top, contentWidth, buttonHeight).build()); status.active = false;
        firstStep = addRenderableWidget(Button.builder(Component.literal("초행 입장 · Lv.15 · 주 3회"), ignored -> DalmaruClient.requestRun("FIRST_STEP")).bounds(left, top+step, contentWidth, buttonHeight).build());
        training = addRenderableWidget(Button.builder(Component.literal("수련 입장 · Lv.20 · 초행 완료 필요"), ignored -> DalmaruClient.requestRun("TRAINING")).bounds(left, top+2*step, contentWidth, buttonHeight).build());
        ready=addRenderableWidget(Button.builder(Component.literal("준비 완료"),ignored->DalmaruClient.readyRun()).bounds(left,top+3*step,contentWidth,buttonHeight).build());
        addRenderableWidget(Button.builder(Component.literal("파티"), ignored -> DalmaruClient.openParty()).bounds(left, top+4*step, contentWidth, buttonHeight).build());
        addRenderableWidget(Button.builder(Component.literal("닫기"), ignored -> onClose()).bounds(left, top+5*step, contentWidth, buttonHeight).build());
    }
    @Override public void tick() { boolean available = ClientRunState.canRequest()&&!ClientRunState.preparing();if(ready!=null)ready.active=ClientRunState.preparing(); firstStep.active = available; training.active = available; status.setMessage(Component.literal(ClientRunState.status())); }
}
