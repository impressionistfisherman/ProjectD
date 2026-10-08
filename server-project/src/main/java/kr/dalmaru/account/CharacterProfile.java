package kr.dalmaru.account;

import java.util.UUID;

public record CharacterProfile(UUID characterId, int level, long experience, int unspentStatPoints, int strength, int dexterity, int arcana, int vitality, int spirit, boolean freeResetAvailable, int unspentSkillPoints, String profession) {
	public CharacterProfile(UUID characterId, int level, long experience, int unspentStatPoints, int strength, int dexterity, int arcana, int vitality, int spirit, boolean freeResetAvailable) { this(characterId, level, experience, unspentStatPoints, strength, dexterity, arcana, vitality, spirit, freeResetAvailable, 0, ""); }
}
