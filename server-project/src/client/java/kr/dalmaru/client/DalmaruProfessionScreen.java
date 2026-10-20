package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruProfessionScreen extends Screen {
	private final Screen previous;
	DalmaruProfessionScreen(Screen previous) { super(Component.literal("달마루 전직")); this.previous = previous; }
	@Override protected void init() {
		int buttonWidth = Math.min(320, width - 20), left = (width - buttonWidth) / 2, top = Math.max(24, height / 2 - 90);
		var response = ClientProfileState.current();
		var profile = response == null ? null : response.profile();
		if (profile == null || profile.profession() == null || profile.profession().isBlank()) {
			addRenderableWidget(Button.builder(Component.literal("전사 · 주력 힘"), b -> choose("warrior")).bounds(left, top, buttonWidth, 20).build());
			addRenderableWidget(Button.builder(Component.literal("궁수 · 주력 재주"), b -> choose("archer")).bounds(left, top + 24, buttonWidth, 20).build());
			addRenderableWidget(Button.builder(Component.literal("법사 · 주력 도력"), b -> choose("mage")).bounds(left, top + 48, buttonWidth, 20).build());
			addRenderableWidget(Button.builder(Component.literal("도적 · 주력 힘 또는 재주"), b -> choose("rogue")).bounds(left, top + 72, buttonWidth, 20).build());
			top += 96;
		} else {
			if (!profile.advance30()) for (String branch : branches(profile.profession())) {
				String chosen = branch;
				addRenderableWidget(Button.builder(Component.literal(branchLabel(chosen)), b -> advance("BRANCH_30", chosen)).bounds(left, top, buttonWidth, 20).build());
				top += 24;
			} else if (!profile.advance50()) {
				addRenderableWidget(Button.builder(Component.literal("50 후속 확정"), b -> advance("ADVANCE_50", profile.branch())).bounds(left, top, buttonWidth, 20).build());
				top += 24;
			} else if (!profile.advance60()) {
				addRenderableWidget(Button.builder(Component.literal("60 마스터 확정"), b -> advance("MASTER_60", profile.branch())).bounds(left, top, buttonWidth, 20).build());
				top += 24;
			}
			if (profile.rogueResetAvailable()) {
				int sum = profile.strength() + profile.dexterity();
				addRenderableWidget(Button.builder(Component.literal("힘을 모두 재주로"), b -> reset(0, sum)).bounds(left, top, buttonWidth, 20).build());
				addRenderableWidget(Button.builder(Component.literal("재주를 모두 힘으로"), b -> reset(sum, 0)).bounds(left, top + 24, buttonWidth, 20).build());
				top += 48;
			}
		}
		addRenderableWidget(Button.builder(Component.literal("돌아가기"), b -> minecraft.setScreen(previous)).bounds(left, top, buttonWidth, 20).build());
	}
	private void choose(String job) { DalmaruClient.requestProfession(job); minecraft.setScreen(previous); }
	private void advance(String stage, String branch) { DalmaruClient.requestAdvancement(stage, branch); minecraft.setScreen(previous); }
	private void reset(int strength, int dexterity) { DalmaruClient.requestRogueReset(strength, dexterity); minecraft.setScreen(previous); }
	private static java.util.List<String> branches(String profession) {
		return switch (profession) {
			case "WARRIOR" -> java.util.List.of("warrior.twohand", "warrior.shield");
			case "ARCHER" -> java.util.List.of("archer.bow", "archer.crossbow");
			case "MAGE" -> java.util.List.of("mage.fan", "mage.ritual");
			case "ROGUE" -> java.util.List.of("rogue.bandit", "rogue.pirate");
			default -> java.util.List.of();
		};
	}
	private static String branchLabel(String branch) {
		return switch (branch) {
			case "warrior.twohand" -> "양손 검 · 30분기";
			case "warrior.shield" -> "검과 방패 · 30분기";
			case "archer.bow" -> "활 · 30분기";
			case "archer.crossbow" -> "석궁 · 30분기";
			case "mage.fan" -> "부채 · 30분기";
			case "mage.ritual" -> "의식검 · 30분기";
			case "rogue.bandit" -> "쌍도끼 · 30분기";
			case "rogue.pirate" -> "해적검 · 30분기";
			default -> branch;
		};
	}
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta) {
        super.extractRenderState(graphics,mouseX,mouseY,delta);
        int y=Math.max(2,height/2-80);
        for(String line:java.util.List.of("전직 조건: 10레벨 · 직업 마을 방문","해당 직업 시험 · 이야기 완료")) {
            String text=font.plainSubstrByWidth(line,Math.max(0,width-16));
            graphics.text(font,text,(width-font.width(text))/2,y,0xFFFFFFFF,false);y+=12;
        }
    }
}
