package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruCraftScreen extends Screen {
	private final Screen parent;
	private Button statusButton;

	DalmaruCraftScreen(Screen parent) {
		super(Component.literal("달마루 제작"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int count = 4, left = Math.max(8, (width - 300) / 2), buttonWidth = Math.min(300, width - 16), step = Math.min(22, (height - 16) / count), buttonHeight = Math.min(20, step - 2), top = (height - ((count - 1) * step + buttonHeight)) / 2;
		statusButton = addRenderableWidget(Button.builder(Component.literal(""), button -> { }).bounds(left, top, buttonWidth, buttonHeight).build());
		statusButton.active = false;
		addRenderableWidget(Button.builder(Component.literal("연구"), button -> DalmaruClient.requestCraftResearch()).bounds(left, top + step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("제작"), button -> DalmaruClient.requestLearnedCraft()).bounds(left, top + 2 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("정밀 주문"), button -> minecraft.setScreen(new DalmaruPrecisionScreen(this))).bounds(left, top + 3 * step, buttonWidth, buttonHeight).build());
	}

	@Override
	public void tick() { statusButton.setMessage(Component.literal(ClientCraftState.text())); }

	@Override
	public void onClose() { minecraft.setScreen(parent); }
}
