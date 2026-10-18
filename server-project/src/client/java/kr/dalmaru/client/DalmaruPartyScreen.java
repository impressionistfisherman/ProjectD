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
		int step = Math.max(12, Math.min(22, (height - 8) / 2));
		int buttonHeight = Math.max(10, step - 2);
		int top = Math.max(4, (height - 2 * step) / 2);
		status = addRenderableWidget(Button.builder(Component.literal("로딩"), ignored -> { }).bounds(left, top, contentWidth, buttonHeight).build());
		status.active = false;
		addRenderableWidget(Button.builder(Component.literal("닫기"), ignored -> onClose()).bounds(left, top + step, contentWidth, buttonHeight).build());
		DalmaruClient.requestParty("party_menu");
	}

	@Override
	public void tick() { if (status != null) status.setMessage(Component.literal(ClientPartyState.status())); }

	@Override
	public void onClose() { minecraft.setScreen(parent); }
}
