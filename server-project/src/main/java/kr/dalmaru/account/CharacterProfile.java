package kr.dalmaru.account;

import java.util.UUID;

public record CharacterProfile(UUID characterId, int level, long experience, int unspentStatPoints, int strength, int dexterity, int arcana, int vitality, int spirit, boolean freeResetAvailable, int unspentSkillPoints, String profession, long revision) {
	public CharacterProfile(UUID characterId, int level, long experience, int unspentStatPoints, int strength, int dexterity, int arcana, int vitality, int spirit, boolean freeResetAvailable, int unspentSkillPoints, String profession) { this(characterId, level, experience, unspentStatPoints, strength, dexterity, arcana, vitality, spirit, freeResetAvailable, unspentSkillPoints, profession, 0); }
	public boolean supersedes(CharacterProfile current) { return current == null || !characterId.equals(current.characterId()) || revision >= current.revision(); }
	public CharacterProfile(UUID characterId, int level, long experience, int unspentStatPoints, int strength, int dexterity, int arcana, int vitality, int spirit, boolean freeResetAvailable) { this(characterId, level, experience, unspentStatPoints, strength, dexterity, arcana, vitality, spirit, freeResetAvailable, 0, ""); }
}
