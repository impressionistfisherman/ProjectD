package kr.dalmaru.account;

import java.util.UUID;

public record CharacterRecord(UUID characterId, UUID accountId, int slot, String displayName, String normalizedName) { }
