package kr.dalmaru.client;

import kr.dalmaru.account.CharacterProfession;
import kr.dalmaru.network.DalmaruPayloads;
import kr.dalmaru.skill.SkillDefinition;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruSkillScreen extends Screen {
	private final Screen previous;
	DalmaruSkillScreen(Screen previous) { super(Component.literal("달마루 기술 습득")); this.previous = previous; }
	@Override protected void init() {
		DalmaruPayloads.CharacterProfileResponse response = ClientProfileState.current();
		if (response == null || response.profile().profession().isBlank()) return;
		CharacterProfession profession = CharacterProfession.valueOf(response.profile().profession());
		for (int index = 0; index < SkillDefinition.forProfession(profession).size(); index++) {
			SkillDefinition skill = SkillDefinition.forProfession(profession).get(index);
			addRenderableWidget(Button.builder(Component.literal(skill.koreanName() + " " + ClientSkillState.rank(skill.id()) + "/5 [+1]"), button -> DalmaruClient.requestSkill(skill.id())).bounds(width / 2 - 160, height / 2 - 75 + index * 25, 320, 20).build());
		}
		addRenderableWidget(Button.builder(Component.literal("돌아가기"), button -> minecraft.setScreen(previous)).bounds(width / 2 - 160, height / 2 + 85, 320, 20).build());
		DalmaruClient.requestSkillRanks();
	}
}
