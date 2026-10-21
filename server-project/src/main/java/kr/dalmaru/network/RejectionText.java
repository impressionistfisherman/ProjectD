package kr.dalmaru.network;

import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RejectionText {
	private static final Logger LOGGER = LoggerFactory.getLogger("dalmaru");
	private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z]{3,}");
	private static final java.util.Set<String> GAME_TERMS = java.util.Set.of("NPC", "HP", "XP");
	private RejectionText() { }

	public static String growth(String status, String reason) {
		return switch ((status == null ? "" : status) + " " + (reason == null ? "" : reason)) {
			case "STALE_SESSION inactive_character" -> "다른 캐릭터가 선택되어 있습니다.";
			case "FORBIDDEN in_run" -> "공략 중에는 할 수 없습니다.";
			case "FIELD_LIMIT invalid_payload" -> "요청 내용이 올바르지 않습니다.";
			case "FORBIDDEN profession_mismatch" -> "이 직업으로는 할 수 없습니다.";
			case "NOT_READY quest_required" -> "필요한 이야기를 마치지 않았습니다.";
			case "DUPLICATE rogue_reset_used" -> "도적 재배분은 이미 사용했습니다.";
			case "CONFLICT stale_revision" -> "화면 정보가 오래되었습니다. 다시 여세요.";
			case "NOT_RELEASED level_cap_closed" -> "아직 그 레벨 상한이 열리지 않았습니다.";
			case "DUPLICATE already_advanced" -> "이미 승급한 단계입니다.";
			case "NOT_READY level_required" -> "승급에 필요한 레벨이 아닙니다.";
			case "FORBIDDEN branch_mismatch" -> "선택한 갈래와 맞지 않습니다.";
			case "NOT_READY advancement_confirmation_required" -> "승급 확인이 필요합니다.";
			default -> hiddenOriginal((status == null ? "" : status) + " " + (reason == null ? "" : reason));
		};
	}

	public static String player(String message) {
		if (message == null || message.isEmpty()) return hiddenOriginal(message);
		var cooldown = Pattern.compile("Gather node is not ready\\. (\\d+) seconds remain\\.").matcher(message);
		if (cooldown.matches()) return "채집 지점이 아직 준비되지 않았습니다. " + cooldown.group(1) + "초 남았습니다.";
		if (message.startsWith("Unknown recipe: ")) return "알 수 없는 제작식입니다.";
		if (message.startsWith("Unknown craft clue: ")) return "알 수 없는 제작 단서입니다.";
		if (message.startsWith("Unknown craft station: ")) return "알 수 없는 작업대입니다.";
		return switch (message) {
			case "Unknown skill.", "Invalid skill body", "Invalid skill learning request.", "Skill envelope does not match session/body" -> "알 수 없는 기술입니다.";
			case "Select a character before learning a skill." -> "전직한 캐릭터를 먼저 선택하세요.";
			case "Skill profession/level does not match current profile" -> "직업이나 레벨이 이 기술과 맞지 않습니다.";
			case "Not enough skill points." -> "기술 점수가 부족합니다.";
			case "Skill is already at maximum rank." -> "이미 최고 단계입니다.";
			case "Request ID was reused for another skill" -> "같은 요청이 다른 기술에 쓰였습니다.";
			case "Unknown story NPC" -> "알 수 없는 이야기 인물입니다.";
			case "Invalid story operation body", "Invalid story position" -> "이야기 요청이 올바르지 않습니다.";
			case "Not at a gather node." -> "채집 지점에서 진행하세요.";
			case "Material binding does not match the node." -> "재료 귀속이 채집 지점과 맞지 않습니다.";
			case "Not at the craft station." -> "제작대 위치에서 진행하세요.";
			case "Invalid production request." -> "생산 예약 요청이 올바르지 않습니다.";
			case "Invalid crop request." -> "농지 요청이 올바르지 않습니다.";
			case "Invalid home request." -> "거처 요청이 올바르지 않습니다.";
			case "Invalid market request." -> "시장 요청이 올바르지 않습니다.";
			case "Unknown life job." -> "알 수 없는 생활 직업입니다.";
			case "Life job is already learned." -> "이미 배운 생활 직업입니다.";
			case "Life job is not learned." -> "아직 배우지 않은 생활 직업입니다.";
			case "Life proficiency is already at the cap." -> "생활 숙련이 이미 상한입니다.";
			case "Life specialization is not ready." -> "전문화 숙련이 아직 부족합니다.";
			case "Life specialization is already chosen." -> "이미 고른 전문화입니다.";
			case "Unknown life specialization." -> "알 수 없는 전문화입니다.";
			case "Life task is not ready." -> "생활 과제가 아직 준비되지 않았습니다.";
			case "Life task is already done." -> "생활 과제를 이미 마쳤습니다.";
			case "Invalid life request.", "Invalid life field.", "Invalid life gain.", "Stale life session." -> "생활 요청이 올바르지 않습니다.";
			case "Insufficient copper." -> "전이 부족합니다.";
			case "Level 10 is required for a profession." -> "10레벨부터 직업을 고를 수 있습니다.";
			case "Profession was already selected." -> "직업을 이미 골랐습니다.";
			case "Invalid profession request.", "Invalid profession body", "Profession envelope does not match session/body" -> "직업 요청이 올바르지 않습니다.";
			case "Craft is not ready.", "Precision order is not ready.", "Craft order is not ready." -> "제작 조건이 아직 맞지 않습니다.";
			case "Precision prerequisite is not learned." -> "정밀 제작법 또는 선행 제작법을 먼저 배워야 합니다.";
			case "Insufficient material." -> "재료가 부족합니다.";
			case "Craft binding was rejected.", "Material binding does not match the recipe." -> "재료 귀속이 제작과 맞지 않습니다.";
			case "Unknown craft attribute." -> "알 수 없는 제작 속성입니다.";
			case "Craft research did not narrow." -> "제작 연구가 좁혀지지 않았습니다.";
			case "Invalid craft request.", "Invalid learned craft.", "Invalid craft research.", "Invalid craft body.", "Invalid craft order.", "Invalid precision request.", "Invalid precision body." -> "제작 요청이 올바르지 않습니다.";
			case "Unknown recipe." -> "알 수 없는 제작식입니다.";
			case "Craft order is already accepted." -> "이미 받은 제작 의뢰입니다.";
			case "Craft order conflicts.", "Precision order conflicts.", "Precision step conflicts." -> "제작 상태가 바뀌었습니다.";
			case "Unknown craft order.", "Unknown precision order.", "Unknown equipment.", "Unknown craft order account." -> "없는 제작 대상을 요청했습니다.";
			default -> foreignWord(message) ? hiddenOriginal(message) : message;
		};
	}

	private static boolean foreignWord(String message) {
		var matcher = LATIN_WORD.matcher(message);
		while (matcher.find()) if (!GAME_TERMS.contains(matcher.group())) return true;
		return false;
	}

	private static String hiddenOriginal(String original) {
		LOGGER.warn("Unmapped rejection text: {}", original);
		return "요청을 처리할 수 없습니다.";
	}
}
