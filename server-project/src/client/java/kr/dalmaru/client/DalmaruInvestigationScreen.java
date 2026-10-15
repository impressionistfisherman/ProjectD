package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruInvestigationScreen extends Screen {
	private final Screen parent;
	private Button statusButton;

	DalmaruInvestigationScreen(Screen parent) {
		super(Component.literal("달마루 조사"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int left = Math.max(8, (width - 300) / 2), buttonWidth = Math.min(300, width - 16), step = Math.min(22, (height - 16) / 6), buttonHeight = Math.min(20, step - 2), top = (height - (5 * step + buttonHeight)) / 2;
		statusButton = addRenderableWidget(Button.builder(Component.literal(""), button -> { }).bounds(left, top, buttonWidth, buttonHeight).build());
		statusButton.active = false;
		addRenderableWidget(Button.builder(Component.literal("현장"), button -> ClientQuestStageState.stage("field")).bounds(left, top + step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("증거"), button -> ClientQuestStageState.stage("evidence")).bounds(left, top + 2 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("최종 보고"), button -> ClientQuestStageState.stage("report")).bounds(left, top + 3 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("확정"), button -> DalmaruClient.requestQuestStage(ClientQuestStageState.stage(), "confirm")).bounds(left, top + 4 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("이 구간 실패"), button -> DalmaruClient.requestQuestStage(ClientQuestStageState.stage(), "fail")).bounds(left, top + 5 * step, buttonWidth, buttonHeight).build());
	}

	@Override
	public void tick() { statusButton.setMessage(Component.literal(ClientQuestStageState.text())); }

	@Override
	public void onClose() { minecraft.setScreen(parent); }
}
