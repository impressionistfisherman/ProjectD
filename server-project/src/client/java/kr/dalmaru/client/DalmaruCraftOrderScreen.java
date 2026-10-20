package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruCraftOrderScreen extends Screen {
	private final Screen parent;
	private Button statusButton;

	DalmaruCraftOrderScreen(Screen parent) {
		super(Component.literal("달마루 주문판"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int count = 5, left = Math.max(8, (width - 300) / 2), buttonWidth = Math.min(300, width - 16), step = Math.min(22, (height - 16) / count), buttonHeight = Math.min(20, step - 2), top = (height - ((count - 1) * step + buttonHeight)) / 2;
		statusButton = addRenderableWidget(Button.builder(Component.literal(""), button -> { }).bounds(left, top, buttonWidth, buttonHeight).build());
		statusButton.active = false;
		addRenderableWidget(Button.builder(Component.literal("의뢰"), button -> DalmaruClient.requestCraftOrderPlace()).bounds(left, top + step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("수락"), button -> DalmaruClient.requestCraftOrderAccept()).bounds(left, top + 2 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("정산"), button -> DalmaruClient.requestCraftOrderSettle()).bounds(left, top + 3 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("취소"), button -> DalmaruClient.requestCraftOrderCancel()).bounds(left, top + 4 * step, buttonWidth, buttonHeight).build());
	}

	@Override
	public void tick() { statusButton.setMessage(Component.literal(ClientCraftOrderState.text())); }

	@Override
	public void onClose() { minecraft.setScreen(parent); }
}
