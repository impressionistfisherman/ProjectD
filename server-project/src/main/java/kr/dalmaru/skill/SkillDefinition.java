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

	public static SkillDefinition find(CharacterProfession profession, String id) {
		return ALL.stream().filter(skill -> skill.profession == profession && skill.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Skill is not available for this profession."));
	}

	public static List<SkillDefinition> forProfession(CharacterProfession profession) { return ALL.stream().filter(skill -> skill.profession == profession).toList(); }
}
