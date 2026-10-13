package kr.dalmaru.account;

import java.util.UUID;

public record CharacterSelectionResult(UUID characterId, boolean replayed, String vanillaState) {
    public CharacterSelectionResult(UUID characterId, boolean replayed) { this(characterId, replayed, null); }
}
