package kr.dalmaru.account;

import java.util.UUID;

public record CharacterSelectionResult(UUID characterId, boolean replayed) { }
