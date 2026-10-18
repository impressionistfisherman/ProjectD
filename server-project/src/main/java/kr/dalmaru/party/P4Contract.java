package kr.dalmaru.party;

import java.util.Set;

public final class P4Contract {
	public static final Set<String> ACTIONS = Set.of("party_menu", "start_brazier", "cancel_brazier", "skill_used", "hit", "boss_snapshot", "participant_removed", "return_ack", "s12_cancel");

	private P4Contract() { }

	public static Result judge(String action) {
		if (action == null || !ACTIONS.contains(action)) return reject("거절: 알 수 없는 요청입니다.");
		return switch (action) {
			case "party_menu" -> reject("거절: 파티 명단은 아직 열리지 않았습니다.");
			case "start_brazier", "cancel_brazier" -> reject("거절: 화로 조작은 아직 열리지 않았습니다.");
			case "skill_used", "hit", "boss_snapshot" -> reject("거절: 보스 상태는 아직 열리지 않았습니다.");
			default -> reject("거절: 참가자 반환은 아직 열리지 않았습니다.");
		};
	}

	public static Result reject(String reason) { return new Result("REJECT", reason); }

	public record Result(String status, String reason) { }
}
