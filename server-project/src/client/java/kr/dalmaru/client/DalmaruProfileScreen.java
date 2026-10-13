package kr.dalmaru.client;

import kr.dalmaru.network.DalmaruPayloads;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruProfileScreen extends Screen {
	private Button statusButton;
	private Button strengthButton;
	private Button dexterityButton;
	private Button arcanaButton;
	private Button vitalityButton;
	private Button spiritButton;
	private Button resetButton;
	private Button professionButton;
	private Button skillButton;

	DalmaruProfileScreen() { super(Component.literal("달마루 캐릭터 정보")); }

	@Override
	protected void init() {
		int buttonWidth = Math.min(360, width - 16), left = (width - buttonWidth) / 2, top = (height - 220) / 2;
		statusButton = addRenderableWidget(Button.builder(Component.literal("프로필을 불러오는 중입니다."), button -> { }).bounds(left, top, buttonWidth, 20).build());
		strengthButton = statButton("힘", 0, 25);
		dexterityButton = statButton("재주", 1, 50);
		arcanaButton = statButton("도력", 2, 75);
		vitalityButton = statButton("체력", 3, 100);
		spiritButton = statButton("정신", 4, 125);
		resetButton = addRenderableWidget(Button.builder(Component.literal(""), button -> DalmaruClient.requestCharacterStatReset()).bounds(left, top + 150, buttonWidth, 20).build());
		professionButton = addRenderableWidget(Button.builder(Component.literal("전직 선택"), button -> minecraft.setScreen(new DalmaruProfessionScreen(this))).bounds(left, top + 175, buttonWidth, 20).build());
		skillButton = addRenderableWidget(Button.builder(Component.literal("기술 습득"), button -> minecraft.setScreen(new DalmaruSkillScreen(this))).bounds(left, top + 200, buttonWidth, 20).build());
		statusButton.active = false;
		resetButton.active = false;
	}

	private Button statButton(String name, int statId, int yOffset) {
		int buttonWidth = Math.min(360, width - 16);
		return addRenderableWidget(Button.builder(Component.literal(name), button -> DalmaruClient.requestCharacterStatInvestment(statId))
			.bounds((width - buttonWidth) / 2, (height - 220) / 2 + yOffset, buttonWidth, 20).build());
	}

	@Override
	public void tick() {
		DalmaruPayloads.CharacterProfileResponse response = ClientProfileState.current();
		if (response == null) {
			statusButton.setMessage(Component.literal("캐릭터를 선택한 뒤 확인할 수 있습니다."));
			setStatButtons(false);
			resetButton.setMessage(Component.empty());
			return;
		}
		DalmaruPayloads.CharacterProfileData profile = response.profile();
		String status = ClientProfileState.status();
		String profession = profile.profession().isBlank() ? "미전직" : profile.profession();
		statusButton.setMessage(Component.literal(status.isBlank() ? "직업 " + profession + "  |  레벨 " + profile.level() + "  |  경험치 " + profile.experience() + "  |  미배분점 " + profile.unspentStatPoints() + "  |  기술점 " + profile.unspentSkillPoints() : status));
		strengthButton.setMessage(Component.literal("힘 " + profile.strength() + "  [+1]"));
		dexterityButton.setMessage(Component.literal("재주 " + profile.dexterity() + "  [+1]"));
		arcanaButton.setMessage(Component.literal("도력 " + profile.arcana() + "  [+1]"));
		vitalityButton.setMessage(Component.literal("체력 " + profile.vitality() + "  [+1]"));
		spiritButton.setMessage(Component.literal("정신 " + profile.spirit() + "  [+1]"));
		setStatButtons(ClientProfileState.canInvest());
		resetButton.setMessage(Component.literal(profile.freeResetAvailable() ? "무료 초기화" : "무료 초기화: 사용 완료"));
		resetButton.active = ClientProfileState.canReset();
		skillButton.active = !profile.profession().isBlank();
	}

	private void setStatButtons(boolean active) {
		strengthButton.active = active;
		dexterityButton.active = active;
		arcanaButton.active = active;
		vitalityButton.active = active;
		spiritButton.active = active;
	}
}
