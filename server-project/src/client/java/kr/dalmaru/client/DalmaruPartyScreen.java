package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruPartyScreen extends Screen {
	private final Screen parent;
	private Button status;
	private Button invite;
	private Button accept;

	DalmaruPartyScreen(Screen parent) {
		super(Component.literal("파티"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int contentWidth = Math.min(360, width - 20), left = (width - contentWidth) / 2;
		int rows = 10;
		int step = Math.max(12, Math.min(22, (height - 8) / rows));
		int buttonHeight = Math.max(10, step - 2);
		int top = Math.max(4, (height - rows * step) / 2);
		status = addRenderableWidget(Button.builder(Component.literal("로딩"), ignored -> { }).bounds(left, top, contentWidth, buttonHeight).build());
		status.active = false;
		var target = new EditBox(font, left, top + step, contentWidth, buttonHeight, Component.literal("대상"));
		target.setMaxLength(36);
		addRenderableWidget(target);
		addRenderableWidget(Button.builder(Component.literal("명단"), ignored -> DalmaruClient.requestParty("party_menu")).bounds(left, top + 2 * step, contentWidth, buttonHeight).build());
		invite = addRenderableWidget(Button.builder(Component.literal("초대"), ignored -> DalmaruClient.requestParty("invite", "{\"characterId\":\"" + target.getValue().trim() + "\"}")).bounds(left, top + 3 * step, contentWidth, buttonHeight).build());
		accept = addRenderableWidget(Button.builder(Component.literal("수락"), ignored -> DalmaruClient.requestParty("accept")).bounds(left, top + 4 * step, contentWidth, buttonHeight).build());
		invite.visible = false;
		accept.visible = false;
		addRenderableWidget(Button.builder(Component.literal("추방 투표"), ignored -> DalmaruClient.requestParty("kick_vote", "{\"characterId\":\"" + target.getValue().trim() + "\"}")).bounds(left, top + 5 * step, contentWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("찬성"), ignored -> DalmaruClient.requestParty("kick_yes")).bounds(left, top + 6 * step, contentWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("반대"), ignored -> DalmaruClient.requestParty("kick_no")).bounds(left, top + 7 * step, contentWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("자진 이탈"), ignored -> DalmaruClient.requestParty("leave")).bounds(left, top + 8 * step, contentWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("닫기"), ignored -> onClose()).bounds(left, top + 9 * step, contentWidth, buttonHeight).build());
		DalmaruClient.requestParty("party_menu");
	}

	@Override
	public void tick() {
		if (status != null) status.setMessage(Component.literal(ClientPartyState.status()));
		boolean ready = ClientPartyState.status().startsWith("임시 명단") && ClientPartyState.status().contains("준비");
		if (invite != null) invite.visible = ready;
		if (accept != null) accept.visible = ready;
	}

	@Override
	public void onClose() { minecraft.setScreen(parent); }
}
