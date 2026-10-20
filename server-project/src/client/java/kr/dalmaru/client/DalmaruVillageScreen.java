package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruVillageScreen extends Screen {
	private final Screen parent;
	private Button statusButton;

	DalmaruVillageScreen(Screen parent) {
		super(Component.literal("달마루 마을"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int count = 10, left = Math.max(8, (width - 300) / 2), buttonWidth = Math.min(300, width - 16), step = Math.min(22, (height - 16) / count), buttonHeight = Math.min(20, step - 2), top = (height - ((count - 1) * step + buttonHeight)) / 2;
		statusButton = addRenderableWidget(Button.builder(Component.literal(""), button -> { }).bounds(left, top, buttonWidth, buttonHeight).build());
		statusButton.active = false;
		addRenderableWidget(Button.builder(Component.literal("마을 목록"), button -> DalmaruClient.requestVillages()).bounds(left, top + step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("태평현"), button -> DalmaruClient.requestVillage("taepyeong")).bounds(left, top + 2 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("철위성"), button -> DalmaruClient.requestVillage("cheolwi")).bounds(left, top + 3 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("청림촌"), button -> DalmaruClient.requestVillage("cheongnim")).bounds(left, top + 4 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("운학촌"), button -> DalmaruClient.requestVillage("unhak")).bounds(left, top + 5 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("흑조포"), button -> DalmaruClient.requestVillage("heukjo")).bounds(left, top + 6 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("생활"), button -> minecraft.setScreen(new DalmaruLifeScreen(this))).bounds(left, top + 7 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("제작"), button -> minecraft.setScreen(new DalmaruCraftScreen(this))).bounds(left, top + 8 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("주문판"), button -> minecraft.setScreen(new DalmaruCraftOrderScreen(this))).bounds(left, top + 9 * step, buttonWidth, buttonHeight).build());
		DalmaruClient.requestVillages();
	}

	@Override
	public void tick() { statusButton.setMessage(Component.literal(ClientVillageState.text())); }

	@Override
	public void onClose() { minecraft.setScreen(parent); }
}
