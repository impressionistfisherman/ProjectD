package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruMarketScreen extends Screen {
	private final Screen parent;
	private Button statusButton;

	DalmaruMarketScreen(Screen parent) {
		super(Component.literal("판매대"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int count = 6, left = Math.max(8, (width - 300) / 2), buttonWidth = Math.min(300, width - 16), step = Math.min(22, (height - 16) / count), buttonHeight = Math.min(20, step - 2), top = (height - ((count - 1) * step + buttonHeight)) / 2;
		statusButton = addRenderableWidget(Button.builder(Component.literal(""), button -> { }).bounds(left, top, buttonWidth, buttonHeight).build());
		statusButton.active = false;
		addRenderableWidget(Button.builder(Component.literal("보관"), button -> DalmaruClient.requestMarket("stash_market")).bounds(left, top + step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("등록"), button -> DalmaruClient.requestMarket("list_market")).bounds(left, top + 2 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("구매"), button -> DalmaruClient.requestMarket("buy_market")).bounds(left, top + 3 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("취소"), button -> DalmaruClient.requestMarket("cancel_market")).bounds(left, top + 4 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("회수"), button -> DalmaruClient.requestMarket("claim_market")).bounds(left, top + 5 * step, buttonWidth, buttonHeight).build());
	}

	@Override
	public void tick() { statusButton.setMessage(Component.literal(ClientMarketState.text())); }

	@Override
	public void onClose() { minecraft.setScreen(parent); }
}
