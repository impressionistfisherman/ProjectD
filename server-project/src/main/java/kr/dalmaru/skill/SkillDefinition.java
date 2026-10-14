package kr.dalmaru.skill;

import java.util.List;
import java.util.Map;
import kr.dalmaru.account.CharacterProfession;
import kr.dalmaru.content.ContentCatalog;

public record SkillDefinition(String id,CharacterProfession profession,String koreanName) {
    public enum Kind { ACTIVE,PASSIVE,ULTIMATE }
    // IDs identify executable server capabilities; tuning and display live in content.
    private static final Map<String,CharacterProfession> CAPABILITIES=Map.ofEntries(
        Map.entry("skill.warrior.sweep",CharacterProfession.WARRIOR),
        Map.entry("skill.warrior.advance",CharacterProfession.WARRIOR),
        Map.entry("skill.warrior.rising_strike",CharacterProfession.WARRIOR),
        Map.entry("skill.warrior.battle_cry",CharacterProfession.WARRIOR),
        Map.entry("skill.warrior.body_training",CharacterProfession.WARRIOR),
        Map.entry("skill.warrior.martial_mastery",CharacterProfession.WARRIOR),
        Map.entry("skill.archer.piercing_shot",CharacterProfession.ARCHER),
        Map.entry("skill.archer.retreat_shot",CharacterProfession.ARCHER),
        Map.entry("skill.archer.tracking_mark",CharacterProfession.ARCHER),
        Map.entry("skill.archer.binding_shot",CharacterProfession.ARCHER),
        Map.entry("skill.archer.breath_control",CharacterProfession.ARCHER),
        Map.entry("skill.archer.bow_crossbow_mastery",CharacterProfession.ARCHER),
        Map.entry("skill.mage.spirit_talisman",CharacterProfession.MAGE),
        Map.entry("skill.mage.binding_talisman",CharacterProfession.MAGE),
        Map.entry("skill.mage.protective_talisman",CharacterProfession.MAGE),
        Map.entry("skill.mage.short_step",CharacterProfession.MAGE),
        Map.entry("skill.mage.breath_meditation",CharacterProfession.MAGE),
        Map.entry("skill.mage.talisman_mastery",CharacterProfession.MAGE),
        Map.entry("skill.rogue.ambush",CharacterProfession.ROGUE),
        Map.entry("skill.rogue.smoke_bomb",CharacterProfession.ROGUE),
        Map.entry("skill.rogue.shackle_strike",CharacterProfession.ROGUE),
        Map.entry("skill.rogue.escape",CharacterProfession.ROGUE),
        Map.entry("skill.rogue.evasive_motion",CharacterProfession.ROGUE),
        Map.entry("skill.rogue.expose_weakness",CharacterProfession.ROGUE));
    public static Map<String,CharacterProfession> capabilities(){return CAPABILITIES;}
    public static Kind supportedKind(String id){
        return switch(id){
            case "skill.warrior.body_training","skill.warrior.martial_mastery","skill.archer.breath_control","skill.archer.bow_crossbow_mastery",
                 "skill.mage.breath_meditation","skill.mage.talisman_mastery","skill.rogue.evasive_motion","skill.rogue.expose_weakness" -> Kind.PASSIVE;
            default -> Kind.ACTIVE;
        };
    }
    public Kind kind(){return ContentCatalog.current().skill(id).kind();}
    public String description(){return ContentCatalog.current().skill(id).description();}
    public static SkillDefinition find(CharacterProfession profession,String id){
        var skill=ContentCatalog.current().skill(id);
        if(skill.profession()!=profession)throw new IllegalArgumentException("Skill is not available for this profession.");
        return new SkillDefinition(id,profession,skill.name());
    }
    public static List<SkillDefinition> forProfession(CharacterProfession profession){
        return ContentCatalog.current().skills().stream().filter(skill->skill.profession()==profession).map(skill->new SkillDefinition(skill.id(),profession,skill.name())).toList();
    }
}
