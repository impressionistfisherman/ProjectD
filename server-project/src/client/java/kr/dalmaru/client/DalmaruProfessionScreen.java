package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruProfessionScreen extends Screen {
	private final Screen previous;
	DalmaruProfessionScreen(Screen previous) { super(Component.literal("달마루 전직")); this.previous = previous; }
	@Override protected void init() {
		addRenderableWidget(Button.builder(Component.literal("전사 · 주력 힘"), b -> choose("warrior")).bounds(width / 2 - 160, height / 2 - 55, 320, 20).build());
		addRenderableWidget(Button.builder(Component.literal("궁수 · 주력 재주"), b -> choose("archer")).bounds(width / 2 - 160, height / 2 - 30, 320, 20).build());
		addRenderableWidget(Button.builder(Component.literal("법사 · 주력 도력"), b -> choose("mage")).bounds(width / 2 - 160, height / 2 - 5, 320, 20).build());
		addRenderableWidget(Button.builder(Component.literal("도적 · 주력 힘 또는 재주"), b -> choose("rogue")).bounds(width / 2 - 160, height / 2 + 20, 320, 20).build());
		addRenderableWidget(Button.builder(Component.literal("돌아가기"), b -> minecraft.setScreen(previous)).bounds(width / 2 - 160, height / 2 + 55, 320, 20).build());
	}
	private void choose(String job) { DalmaruClient.requestProfession(job); minecraft.setScreen(previous); }
}
