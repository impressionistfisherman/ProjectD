package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruLifeSpecScreen extends Screen {
	private final Screen parent;
	private Button statusButton;

	DalmaruLifeSpecScreen(Screen parent) {
		super(Component.literal("달마루 전문화"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		String[] jobs = {"blacksmith", "bowyer", "carpenter", "tailor", "lapidary", "cook", "alchemist", "apothecary", "charm_maker"};
		String[] labels = {"대장장이", "궁장", "목수", "재봉사", "세공사", "요리사", "연금술사", "약사", "부적사"};
		String[] ranks = {"expert", "master", "grandmaster"};
		String[] rankLabels = {"전문 500전", "장인 500전", "명장 500전"};
		int count = jobs.length + ranks.length + 2;
		int left = Math.max(8, (width - 300) / 2), buttonWidth = Math.min(300, width - 16), step = Math.min(22, (height - 16) / count), buttonHeight = Math.min(20, step - 2), top = (height - ((count - 1) * step + buttonHeight)) / 2;
		statusButton = addRenderableWidget(Button.builder(Component.literal(""), button -> { }).bounds(left, top, buttonWidth, buttonHeight).build());
		statusButton.active = false;
		for (int i = 0; i < jobs.length; i++) {
			String job = jobs[i];
			addRenderableWidget(Button.builder(Component.literal(labels[i] + " 첫 선택"), button -> DalmaruClient.requestLifeSpecialize(job, job + ".journeyman")).bounds(left, top + (i + 1) * step, buttonWidth, buttonHeight).build());
		}
		for (int i = 0; i < ranks.length; i++) {
			String rank = ranks[i];
			addRenderableWidget(Button.builder(Component.literal("대장장이 " + rankLabels[i]), button -> DalmaruClient.requestLifeSpecialize("blacksmith", "blacksmith." + rank)).bounds(left, top + (jobs.length + 1 + i) * step, buttonWidth, buttonHeight).build());
		}
		addRenderableWidget(Button.builder(Component.literal("명장 과제"), button -> DalmaruClient.requestLifeTask("blacksmith")).bounds(left, top + (jobs.length + ranks.length + 1) * step, buttonWidth, buttonHeight).build());
	}

	@Override
	public void tick() { statusButton.setMessage(Component.literal(ClientLifeState.text())); }

	@Override
	public void onClose() { minecraft.setScreen(parent); }
}
