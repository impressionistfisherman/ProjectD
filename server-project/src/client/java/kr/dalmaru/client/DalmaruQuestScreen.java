package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruQuestScreen extends Screen {
	private final Screen previous;
	private Button status;
	DalmaruQuestScreen(Screen previous) { super(Component.literal("달마루 퀘스트")); this.previous = previous; }
	@Override protected void init() {
		status = addRenderableWidget(Button.builder(Component.empty(), b -> DalmaruClient.requestQuest(false)).bounds(width / 2 - 180, height / 2 - 30, 360, 20).build());
		addRenderableWidget(Button.builder(Component.literal("좀비 토벌 의뢰 수락"), b -> DalmaruClient.requestQuest(true)).bounds(width / 2 - 180, height / 2, 360, 20).build());
		addRenderableWidget(Button.builder(Component.literal("돌아가기"), b -> minecraft.setScreen(previous)).bounds(width / 2 - 180, height / 2 + 30, 360, 20).build());
		DalmaruClient.requestQuest(false);
	}
	@Override public void tick() { status.setMessage(Component.literal(ClientQuestState.status())); }
}
