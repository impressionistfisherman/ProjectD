package kr.dalmaru.client;

import kr.dalmaru.network.DalmaruPayloads;

final class ClientProfileState {
	private static DalmaruPayloads.CharacterProfileResponse current;
	private static long nextInvestmentRequestId = 1;
	private static long currentInvestmentRequestId;
	private static boolean investmentWaiting;
	private static int investmentDeadlineTick;
	private static int tick;
	private static String status = "";
	private static long nextResetRequestId = 1;
	private static long currentResetRequestId;
	private static boolean resetWaiting;
	private static long nextProfessionRequestId = 1;
	private static long currentProfessionRequestId;
	private static boolean professionWaiting;
	private static long nextSkillRequestId = 1;
	private static long currentSkillRequestId;
	private static boolean skillWaiting;

	private ClientProfileState() { }

	static void resetForConnection() {
		current = null;
		nextInvestmentRequestId = 1;
		currentInvestmentRequestId = 0;
		investmentWaiting = false;
		investmentDeadlineTick = 0;
		tick = 0;
		status = "";
		nextResetRequestId = 1;
		currentResetRequestId = 0;
		resetWaiting = false;
		nextProfessionRequestId = 1;
		currentProfessionRequestId = 0;
		professionWaiting = false;
		nextSkillRequestId = 1;
		currentSkillRequestId = 0;
		skillWaiting = false;
	}
	static void accept(DalmaruPayloads.CharacterProfileResponse response) { current = response; }
	static boolean beginInvestment() {
		if (current == null || current.profile().unspentStatPoints() < 1 || investmentWaiting) return false;
		currentInvestmentRequestId = nextInvestmentRequestId++;
		investmentWaiting = true;
		investmentDeadlineTick = tick + 100;
		status = "능력치를 투자하는 중입니다.";
		return true;
	}
	static void acceptInvestment(DalmaruPayloads.CharacterStatInvestmentResponse response) {
		if (!investmentWaiting || response.requestId() != currentInvestmentRequestId) return;
		investmentWaiting = false;
		status = response.status();
		if (response.accepted()) current = new DalmaruPayloads.CharacterProfileResponse(response.characterId(), response.profile());
	}
	static boolean beginReset() {
		if (current == null || !current.profile().freeResetAvailable() || resetWaiting || investmentWaiting) return false;
		currentResetRequestId = nextResetRequestId++;
		resetWaiting = true;
		status = "무료 초기화를 처리하는 중입니다.";
		return true;
	}
	static void acceptReset(DalmaruPayloads.CharacterStatResetResponse response) {
		if (!resetWaiting || response.requestId() != currentResetRequestId) return;
		resetWaiting = false;
		status = response.status();
		if (response.accepted()) current = new DalmaruPayloads.CharacterProfileResponse(response.characterId(), response.profile());
	}
	static boolean beginProfession() {
		if (current == null || !current.profile().profession().isBlank() || professionWaiting || investmentWaiting || resetWaiting) return false;
		currentProfessionRequestId = nextProfessionRequestId++;
		professionWaiting = true;
		status = "전직을 선택하는 중입니다.";
		return true;
	}
	static void acceptProfession(DalmaruPayloads.CharacterProfessionResponse response) {
		if (!professionWaiting || response.requestId() != currentProfessionRequestId) return;
		professionWaiting = false;
		status = response.status();
		if (response.accepted() && current != null) current = new DalmaruPayloads.CharacterProfileResponse(current.characterId(), response.profile());
	}
	static long currentProfessionRequestId() { return currentProfessionRequestId; }
	static boolean beginSkill() {
		if (current == null || current.profile().profession().isBlank() || current.profile().unspentSkillPoints() < 1 || skillWaiting) return false;
		currentSkillRequestId = nextSkillRequestId++;
		skillWaiting = true;
		status = "기술을 습득하는 중입니다.";
		return true;
	}
	static long currentSkillRequestId() { return currentSkillRequestId; }
	static void acceptSkill(DalmaruPayloads.SkillLearnResponse response) {
		if (!skillWaiting || response.requestId() != currentSkillRequestId) return;
		skillWaiting = false;
		status = response.status();
		if (response.accepted() && current != null) current = new DalmaruPayloads.CharacterProfileResponse(current.characterId(), response.profile());
	}
	static void tick() {
		tick++;
		if (investmentWaiting && tick >= investmentDeadlineTick) {
			investmentWaiting = false;
			status = "능력치 투자 응답 시간이 초과되었습니다. 다시 시도하세요.";
		}
	}
	static DalmaruPayloads.CharacterProfileResponse current() { return current; }
	static long currentInvestmentRequestId() { return currentInvestmentRequestId; }
	static long currentResetRequestId() { return currentResetRequestId; }
	static boolean canInvest() { return current != null && current.profile().unspentStatPoints() > 0 && !investmentWaiting && !resetWaiting && !professionWaiting; }
	static boolean canReset() { return current != null && current.profile().freeResetAvailable() && !investmentWaiting && !resetWaiting && !professionWaiting; }
	static String status() { return status; }
}
