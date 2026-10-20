package kr.dalmaru.account;

import java.util.Map;

public enum AdvancementStage {
    BRANCH_30(30, 5, "story.branch_30"),
    ADVANCE_50(50, 10, "story.advance_50"),
    MASTER_60(60, 10, "story.master_60");

    private final int level, bonus;
    private final String questId;
    AdvancementStage(int level, int bonus, String questId) {
        this.level = level; this.bonus = bonus; this.questId = questId;
    }
    public int requiredLevel() { return level; }
    public int bonus() { return bonus; }
    public String questId() { return questId; }
    public static AdvancementStage forQuest(String id) {
        for (var stage : values()) if (stage.questId.equals(id)) return stage;
        return null;
    }
    private static final Map<String, CharacterProfession> BRANCHES = Map.of(
        "warrior.twohand", CharacterProfession.WARRIOR, "warrior.shield", CharacterProfession.WARRIOR,
        "archer.bow", CharacterProfession.ARCHER, "archer.crossbow", CharacterProfession.ARCHER,
        "mage.fan", CharacterProfession.MAGE, "mage.ritual", CharacterProfession.MAGE,
        "rogue.bandit", CharacterProfession.ROGUE, "rogue.pirate", CharacterProfession.ROGUE);
    public static CharacterProfession profession(String branch) {
        var profession = BRANCHES.get(branch);
        if (profession == null) throw new CharacterRepository.GrowthException("UNKNOWN_ID", "unknown_id");
        return profession;
    }
}
