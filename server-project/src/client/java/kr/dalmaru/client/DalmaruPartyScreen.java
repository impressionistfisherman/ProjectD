package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruPartyScreen extends Screen {
	private final Screen parent;
	private Button status;

	DalmaruPartyScreen(Screen parent) {
		super(Component.literal("임시 파티"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int contentWidth = Math.min(360, width - 20), left = (width - contentWidth) / 2;
		int rows = 5;
		int step = Math.max(12, Math.min(22, (height - 8) / rows));
		int buttonHeight = Math.max(10, step - 2);
		int top = Math.max(4, (height - rows * step) / 2);
		status = addRenderableWidget(Button.builder(Component.literal("로딩"), ignored -> { }).bounds(left, top, contentWidth, buttonHeight).build());
		status.active = false;
		var target = new net.minecraft.client.gui.components.EditBox(font, left, top + step, contentWidth, buttonHeight, Component.literal("대상"));
		target.setMaxLength(36);
		addRenderableWidget(target);
		addRenderableWidget(Button.builder(Component.literal("임시 초대"), ignored -> DalmaruClient.requestParty("invite", "{\"characterId\":\"" + target.getValue().trim() + "\"}")).bounds(left, top + 2 * step, contentWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("임시 수락"), ignored -> DalmaruClient.requestParty("accept")).bounds(left, top + 3 * step, contentWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("닫기"), ignored -> onClose()).bounds(left, top + 4 * step, contentWidth, buttonHeight).build());
		DalmaruClient.requestParty("party_menu");
	}

	@Override
	public void tick() { if (status != null) status.setMessage(Component.literal(ClientPartyState.status())); }

	@Override
	public void onClose() { minecraft.setScreen(parent); }
}
