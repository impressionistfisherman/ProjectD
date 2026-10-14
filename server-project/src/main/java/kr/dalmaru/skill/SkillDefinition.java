package kr.dalmaru.skill;

import java.util.List;
import kr.dalmaru.account.CharacterProfession;

public record SkillDefinition(String id, CharacterProfession profession, String koreanName) {
    public enum Kind { ACTIVE,PASSIVE,ULTIMATE }
    public Kind kind() {
        return switch(id){
            case "skill.warrior.body_training","skill.warrior.martial_mastery","skill.archer.breath_control","skill.archer.bow_crossbow_mastery",
                 "skill.mage.breath_meditation","skill.mage.talisman_mastery","skill.rogue.evasive_motion","skill.rogue.expose_weakness" -> Kind.PASSIVE;
            default -> Kind.ACTIVE;
        };
    }
	private static final List<SkillDefinition> ALL = List.of(
		new SkillDefinition("skill.warrior.sweep", CharacterProfession.WARRIOR, "횡참"),
		new SkillDefinition("skill.warrior.advance", CharacterProfession.WARRIOR, "진격"),
		new SkillDefinition("skill.warrior.rising_strike", CharacterProfession.WARRIOR, "승룡격"),
		new SkillDefinition("skill.warrior.battle_cry", CharacterProfession.WARRIOR, "기합"),
		new SkillDefinition("skill.warrior.body_training", CharacterProfession.WARRIOR, "신체 단련"),
		new SkillDefinition("skill.warrior.martial_mastery", CharacterProfession.WARRIOR, "무예 숙련"),
		new SkillDefinition("skill.archer.piercing_shot", CharacterProfession.ARCHER, "관통 사격"),
		new SkillDefinition("skill.archer.retreat_shot", CharacterProfession.ARCHER, "후퇴 사격"),
		new SkillDefinition("skill.archer.tracking_mark", CharacterProfession.ARCHER, "추적 표식"),
		new SkillDefinition("skill.archer.binding_shot", CharacterProfession.ARCHER, "속박 사격"),
		new SkillDefinition("skill.archer.breath_control", CharacterProfession.ARCHER, "호흡 조절"),
		new SkillDefinition("skill.archer.bow_crossbow_mastery", CharacterProfession.ARCHER, "궁노 숙련"),
		new SkillDefinition("skill.mage.spirit_talisman", CharacterProfession.MAGE, "영부"),
		new SkillDefinition("skill.mage.binding_talisman", CharacterProfession.MAGE, "속박부"),
		new SkillDefinition("skill.mage.protective_talisman", CharacterProfession.MAGE, "호신부"),
		new SkillDefinition("skill.mage.short_step", CharacterProfession.MAGE, "축지"),
		new SkillDefinition("skill.mage.breath_meditation", CharacterProfession.MAGE, "운기조식"),
		new SkillDefinition("skill.mage.talisman_mastery", CharacterProfession.MAGE, "부적 숙련"),
		new SkillDefinition("skill.rogue.ambush", CharacterProfession.ROGUE, "급습"),
		new SkillDefinition("skill.rogue.smoke_bomb", CharacterProfession.ROGUE, "연막탄"),
		new SkillDefinition("skill.rogue.shackle_strike", CharacterProfession.ROGUE, "족쇄격"),
		new SkillDefinition("skill.rogue.escape", CharacterProfession.ROGUE, "긴급 이탈"),
		new SkillDefinition("skill.rogue.evasive_motion", CharacterProfession.ROGUE, "회피 기동"),
		new SkillDefinition("skill.rogue.expose_weakness", CharacterProfession.ROGUE, "허점 간파")
	);

	public String description() {
		return switch (id) {
			case "skill.warrior.sweep" -> "기력 20 / 재사용 4.5초 / 1~5랭크: 물리 110% → 150%";
			case "skill.warrior.advance" -> "기력 20 / 재사용 7초 / 1~5랭크: 물리 85% → 115%, 3블록 접근";
			case "skill.warrior.rising_strike" -> "기력 30 / 재사용 9초 / 1~5랭크: 물리 125% → 170%, 자세 피해 18 → 30";
			case "skill.warrior.battle_cry" -> "기력 25 / 재사용 18초 / 1~5랭크: 둔화 1개 해제, 경직 저항 2 → 4초";
			case "skill.warrior.body_training" -> "기력 - / 재사용 지속 / 1~5랭크: 직접 생명력 +30 → +130";
			case "skill.warrior.martial_mastery" -> "기력 - / 재사용 지속 / 1~5랭크: 기본 공격 피해 +4% → +12%";
			case "skill.archer.piercing_shot" -> "기력 22 / 재사용 5초 / 1~5랭크: 물리 115% → 155%";
			case "skill.archer.retreat_shot" -> "기력 18 / 재사용 7초 / 1~5랭크: 물리 85% → 115%, 3블록 후퇴";
			case "skill.archer.tracking_mark" -> "기력 20 / 재사용 12초 / 1~5랭크: 물리 60% → 90%, 표식 대상 피해 +4% → +12%";
			case "skill.archer.binding_shot" -> "기력 25 / 재사용 14초 / 1~5랭크: 물리 70% → 100%, 일반 적 둔화 1.5 → 2.5초";
			case "skill.archer.breath_control" -> "기력 - / 재사용 지속 / 1~5랭크: 완전 조준 시간 -5% → -20%";
			case "skill.archer.bow_crossbow_mastery" -> "기력 - / 재사용 지속 / 1~5랭크: 기본 공격 피해 +4% → +12%";
			case "skill.mage.spirit_talisman" -> "기력 20 / 재사용 4초 / 1~5랭크: 술법 115% → 155%";
			case "skill.mage.binding_talisman" -> "기력 30 / 재사용 12초 / 1~5랭크: 술법 70% → 105%, 일반 적 구속 2 → 3초";
			case "skill.mage.protective_talisman" -> "기력 35 / 재사용 14초 / 1~5랭크: 보호막 70 + 술법 공격력×0.8 → 130 + 술법 공격력×1.4";
			case "skill.mage.short_step" -> "기력 25 / 재사용 12초 / 1~5랭크: 5블록 이동, 벽·봉인·공략 경계 통과 불가";
			case "skill.mage.breath_meditation" -> "기력 - / 재사용 지속 / 1~5랭크: 초당 기력 회복 +0.4 → +2.0";
			case "skill.mage.talisman_mastery" -> "기력 - / 재사용 지속 / 1~5랭크: 부적 직접 피해·보호막 +4% → +12%";
			case "skill.rogue.ambush" -> "기력 22 / 재사용 5초 / 1~5랭크: 물리 115% → 155%, 측후방 적중 시 추가 20%";
			case "skill.rogue.smoke_bomb" -> "기력 25 / 재사용 16초 / 1~5랭크: 일반 원거리 적의 새 조준 차단 2.5 → 4초";
			case "skill.rogue.shackle_strike" -> "기력 20 / 재사용 7초 / 1~5랭크: 물리 90% → 130%, 일반 적 둔화 2 → 3초";
			case "skill.rogue.escape" -> "기력 20 / 재사용 12초 / 1~5랭크: 3블록 후퇴, 해제 가능한 이동 방해 1개 해제";
			case "skill.rogue.evasive_motion" -> "기력 - / 재사용 지속 / 1~5랭크: 회피 성공 뒤 이동 속도 +10% → +25%, 2초";
			case "skill.rogue.expose_weakness" -> "기력 - / 재사용 지속 / 1~5랭크: 제어·측후방 직접 피해 +4% → +12%";
			default -> throw new IllegalArgumentException("Missing skill description: " + id);
		};
	}

	public static SkillDefinition find(CharacterProfession profession, String id) {
		return ALL.stream().filter(skill -> skill.profession == profession && skill.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Skill is not available for this profession."));
	}

	public static List<SkillDefinition> forProfession(CharacterProfession profession) { return ALL.stream().filter(skill -> skill.profession == profession).toList(); }
}
