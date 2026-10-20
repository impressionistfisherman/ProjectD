package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruLifeScreen extends Screen {
	private static final String[] JOBS = {"blacksmith", "bowyer", "carpenter", "tailor", "lapidary", "cook", "alchemist", "apothecary", "charm_maker"};
	private static final String[] LABELS = {"대장장이", "궁장", "목수", "재봉사", "세공사", "요리사", "연금술사", "약사", "부적사"};
	private final Screen parent;
	private Button statusButton;

	DalmaruLifeScreen(Screen parent) {
		super(Component.literal("달마루 생활"));
		this.parent = parent;
	}

	static String label(String job) {
		for (int i = 0; i < JOBS.length; i++) if (JOBS[i].equals(job)) return LABELS[i];
		return job;
	}

	@Override
	protected void init() {
		String[] jobs = {"blacksmith", "bowyer", "carpenter", "tailor", "lapidary", "cook", "alchemist", "apothecary", "charm_maker"};
		String[] labels = {"대장장이", "궁장", "목수", "재봉사", "세공사", "요리사", "연금술사", "약사", "부적사"};
		int count = jobs.length + 2;
		int left = Math.max(8, (width - 300) / 2), buttonWidth = Math.min(300, width - 16), step = Math.min(22, (height - 16) / count), buttonHeight = Math.min(20, step - 2), top = (height - ((count - 1) * step + buttonHeight)) / 2;
		statusButton = addRenderableWidget(Button.builder(Component.literal(""), button -> { }).bounds(left, top, buttonWidth, buttonHeight).build());
		statusButton.active = false;
		for (int i = 0; i < jobs.length; i++) {
			String job = jobs[i];
			addRenderableWidget(Button.builder(Component.literal(labels[i]), button -> DalmaruClient.requestLifeLearn(job)).bounds(left, top + (i + 1) * step, buttonWidth, buttonHeight).build());
		}
		addRenderableWidget(Button.builder(Component.literal("전문화"), button -> minecraft.setScreen(new DalmaruLifeSpecScreen(this))).bounds(left, top + (jobs.length + 1) * step, buttonWidth, buttonHeight).build());
		DalmaruClient.requestLifeProfile();
	}

	@Override
	public void tick() { statusButton.setMessage(Component.literal(ClientLifeState.text())); }

	@Override
	public void onClose() { minecraft.setScreen(parent); }
}
