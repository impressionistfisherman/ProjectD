package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruHousingScreen extends Screen {
	private final Screen parent;
	private Button statusButton;

	DalmaruHousingScreen(Screen parent) {
		super(Component.literal("내 거처"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int count = 8, left = Math.max(8, (width - 300) / 2), buttonWidth = Math.min(300, width - 16), step = Math.min(22, (height - 16) / count), buttonHeight = Math.min(20, step - 2), top = (height - ((count - 1) * step + buttonHeight)) / 2;
		statusButton = addRenderableWidget(Button.builder(Component.literal(""), button -> { }).bounds(left, top, buttonWidth, buttonHeight).build());
		statusButton.active = false;
		addRenderableWidget(Button.builder(Component.literal("거처 만들기"), button -> DalmaruClient.requestHome("create_home")).bounds(left, top + step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("입장"), button -> DalmaruClient.requestHome("enter_home")).bounds(left, top + 2 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("마을로 복귀"), button -> DalmaruClient.requestHome("leave_home")).bounds(left, top + 3 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("방문 허용"), button -> DalmaruClient.requestHome("grant_home")).bounds(left, top + 4 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("권한 회수"), button -> DalmaruClient.requestHome("revoke_home")).bounds(left, top + 5 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("배치"), button -> DalmaruClient.requestHome("place_home")).bounds(left, top + 6 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("확장"), button -> DalmaruClient.requestHome("expand_home")).bounds(left, top + 7 * step, buttonWidth, buttonHeight).build());
	}

	@Override
	public void tick() { statusButton.setMessage(Component.literal(ClientHousingState.text())); }

	@Override
	public void onClose() { minecraft.setScreen(parent); }
}
