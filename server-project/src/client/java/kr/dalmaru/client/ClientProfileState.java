package kr.dalmaru.client;

import kr.dalmaru.network.DalmaruPayloads;

final class ClientProfileState {
	private static DalmaruPayloads.CharacterProfileResponse current;
	private static long nextInvestmentRequestId = 1;
	private static long currentInvestmentRequestId;
	private static boolean investmentWaiting;
    private static boolean investmentDelegated;
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
	private static java.util.UUID character;
	private static int resetDeadline,professionDeadline,skillDeadline;
	private static DalmaruPayloads.CharacterStatResetRequest resetRequest;
	private static DalmaruPayloads.CharacterProfessionRequest professionRequest;
	private static DalmaruPayloads.SkillLearnRequest skillRequest;

	private ClientProfileState() { }

	static void resetForConnection() {
		current = null;
		character=null;resetRequest=null;professionRequest=null;skillRequest=null;
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
	static void selected(java.util.UUID selected){character=selected;current=null;investmentWaiting=false;resetWaiting=false;professionWaiting=false;skillWaiting=false;status="";}
	static void accept(DalmaruPayloads.CharacterProfileResponse response) { if(character!=null&&!character.equals(response.characterId()))return;character=response.characterId();current = response; }
	static boolean beginInvestment() {
		if (current == null || current.profile().unspentStatPoints() < 1 || investmentWaiting) return false;
		currentInvestmentRequestId = nextInvestmentRequestId++;
		investmentWaiting = true;investmentDelegated=false;
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
		if (current == null || resetWaiting || investmentWaiting) return false;
		currentResetRequestId = nextResetRequestId++;
		resetWaiting = true;
		resetDeadline=tick+100;resetRequest=new DalmaruPayloads.CharacterStatResetRequest(currentResetRequestId,current.characterId());
		status = current.profile().freeResetAvailable() ? "무료 초기화를 처리하는 중입니다." : "유료 초기화를 처리하는 중입니다.";
		return true;
	}
	static void acceptReset(DalmaruPayloads.CharacterStatResetResponse response) {
		if (!resetWaiting || response.requestId() != currentResetRequestId) return;
		resetWaiting = false;
		status = response.status();
		if (response.accepted()) current = new DalmaruPayloads.CharacterProfileResponse(response.characterId(), response.profile());
	}
	static boolean beginProfession(String profession) {
		if (current == null || !current.profile().profession().isBlank() || professionWaiting || investmentWaiting || resetWaiting) return false;
		currentProfessionRequestId = nextProfessionRequestId++;
		professionWaiting = true;
		professionDeadline=tick+100;professionRequest=new DalmaruPayloads.CharacterProfessionRequest(currentProfessionRequestId,profession);
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
	static boolean beginSkill(String skillId) {
		if (current == null || current.profile().profession().isBlank() || current.profile().unspentSkillPoints() < 1 || skillWaiting) return false;
		currentSkillRequestId = nextSkillRequestId++;
		skillWaiting = true;
		skillDeadline=tick+100;skillRequest=new DalmaruPayloads.SkillLearnRequest(currentSkillRequestId,skillId);
		status = "기술을 습득하는 중입니다.";
		return true;
	}
    static void delegateProfessionRetry(){professionRequest=null;}
    static void completeProfession(long correlation,String message){if(!professionWaiting||correlation!=currentProfessionRequestId)return;professionWaiting=false;professionRequest=null;status=message;}
    static void delegateResetRetry(){resetRequest=null;}
    static void completeReset(long correlation,String message){if(!resetWaiting||correlation!=currentResetRequestId)return;resetWaiting=false;resetRequest=null;status=message;}
    static void delegateInvestmentRetry(){investmentDelegated=true;}
    static void completeInvestment(long correlation,String message){if(!investmentWaiting||correlation!=currentInvestmentRequestId)return;investmentWaiting=false;investmentDelegated=false;status=message;}
    static void delegateSkillRetry(){skillRequest=null;}
    static void completeSkill(long correlation,String message){if(!skillWaiting||correlation!=currentSkillRequestId)return;skillWaiting=false;skillRequest=null;status=message;}
	static long currentSkillRequestId() { return currentSkillRequestId; }
	static void acceptSkill(DalmaruPayloads.SkillLearnResponse response) {
		if (!skillWaiting || response.requestId() != currentSkillRequestId) return;
		skillWaiting = false;
		status = response.status();
		if (response.accepted() && current != null) current = new DalmaruPayloads.CharacterProfileResponse(current.characterId(), response.profile());
	}
	static void tick() {
		tick++;
		if (investmentWaiting && !investmentDelegated && tick >= investmentDeadlineTick) {
			investmentWaiting = false;
			status = "능력치 투자 응답 시간이 초과되었습니다. 다시 시도하세요.";
		}
	}
	static DalmaruPayloads.CharacterStatResetRequest retryReset(){if(!resetWaiting||tick<resetDeadline)return null;resetDeadline=tick+100;return resetRequest;}
	static DalmaruPayloads.CharacterProfessionRequest retryProfession(){if(!professionWaiting||tick<professionDeadline)return null;professionDeadline=tick+100;return professionRequest;}
	static DalmaruPayloads.SkillLearnRequest retrySkill(){if(!skillWaiting||tick<skillDeadline)return null;skillDeadline=tick+100;return skillRequest;}
	static boolean canLearn(){return current!=null&&current.profile().unspentSkillPoints()>0&&!skillWaiting;}
	static DalmaruPayloads.CharacterProfileResponse current() { return current; }
	static long currentInvestmentRequestId() { return currentInvestmentRequestId; }
	static long currentResetRequestId() { return currentResetRequestId; }
	static boolean canInvest() { return current != null && current.profile().unspentStatPoints() > 0 && !investmentWaiting && !resetWaiting && !professionWaiting; }
	static boolean canReset() { return current != null && !investmentWaiting && !resetWaiting && !professionWaiting; }
	static String status() { return status; }
}
