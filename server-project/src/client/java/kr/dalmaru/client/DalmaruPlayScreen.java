package kr.dalmaru.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruPlayScreen extends Screen {
	private final Screen parent;
	private Button statusButton;
	private EditBox partner;
	private EditBox mail;

	DalmaruPlayScreen(Screen parent) {
		super(Component.literal("임시 생활"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int rows = 16;
		int left = Math.max(8, (width - 300) / 2);
		int buttonWidth = Math.min(300, width - 16);
		int step = Math.max(12, Math.min(20, (height - 16) / rows));
		int buttonHeight = Math.max(10, step - 2);
		int top = Math.max(4, (height - rows * step) / 2);
		statusButton = addRenderableWidget(Button.builder(Component.literal(""), button -> { }).bounds(left, top, buttonWidth, buttonHeight).build());
		statusButton.active = false;
		partner = new EditBox(font, left, top + step, buttonWidth, buttonHeight, Component.literal("상대 계정"));
		partner.setMaxLength(36);
		addRenderableWidget(partner);
		addRenderableWidget(Button.builder(Component.literal("임시 금고 입금"), button -> send("vault_move", vault("deposit"))).bounds(left, top + 2 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("임시 금고 출금"), button -> send("vault_move", vault("withdraw"))).bounds(left, top + 3 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("임시 상점 구매"), button -> send("shop_trade", shop("buy"))).bounds(left, top + 4 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("임시 상점 판매"), button -> send("shop_trade", shop("sell"))).bounds(left, top + 5 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("임시 채집"), button -> { var body = new JsonObject(); body.addProperty("nodeId", "gather.ironstone"); send("gather_node", body); }).bounds(left, top + 6 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("임시 주괴"), button -> craft("craft.iron_ingot")).bounds(left, top + 7 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("임시 대장간 제작"), button -> craft("craft.basic_fitting")).bounds(left, top + 8 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("임시 거래 제안"), button -> { var body = new JsonObject(); body.addProperty("action", "open"); body.addProperty("partnerAccountId", partner.getValue().trim()); body.addProperty("itemId", "copper"); body.addProperty("quantity", 1); send("direct_trade", body); }).bounds(left, top + 9 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("임시 거래 맡기기"), button -> trade("deposit", true)).bounds(left, top + 10 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("임시 거래 확인"), button -> trade("confirm", false)).bounds(left, top + 11 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("임시 거래 취소"), button -> trade("cancel", false)).bounds(left, top + 12 * step, buttonWidth, buttonHeight).build());
		addRenderableWidget(Button.builder(Component.literal("임시 거래 회수"), button -> trade("withdraw", false)).bounds(left, top + 13 * step, buttonWidth, buttonHeight).build());
		mail = new EditBox(font, left, top + 14 * step, buttonWidth, buttonHeight, Component.literal("우편"));
		mail.setMaxLength(36);
		addRenderableWidget(mail);
		addRenderableWidget(Button.builder(Component.literal("임시 우편 수령"), button -> { var body = new JsonObject(); body.addProperty("eventId", mail.getValue().trim()); send("claim_mail", body); }).bounds(left, top + 15 * step, buttonWidth, buttonHeight).build());
	}

	private static JsonObject vault(String direction) {
		var body = new JsonObject();
		body.addProperty("assetId", "copper");
		body.addProperty("quantity", 1);
		body.addProperty("direction", direction);
		return body;
	}

	private static JsonObject shop(String direction) {
		var body = new JsonObject();
		body.addProperty("itemId", "supply.recovery");
		body.addProperty("quantity", 1);
		body.addProperty("direction", direction);
		return body;
	}

	private static void craft(String recipeId) {
		var body = new JsonObject();
		body.addProperty("recipeId", recipeId);
		send("craft_recipe", body);
	}

	private static void trade(String action, boolean deposit) {
		String offer = ClientPlayAccessState.offerId();
		if (offer == null) return;
		var body = new JsonObject();
		body.addProperty("action", action);
		body.addProperty("offerId", offer);
		if (deposit) { body.addProperty("itemId", "copper"); body.addProperty("quantity", 1); }
		if ("confirm".equals(action)) body.addProperty("version", ClientPlayAccessState.version());
		send("direct_trade", body);
	}

	private static void send(String operation, JsonObject body) { DalmaruClient.requestPlay(operation, body); }

	@Override
	public void tick() { statusButton.setMessage(Component.literal(ClientPlayAccessState.text())); }

	@Override
	public void onClose() { minecraft.setScreen(parent); }
}
