package kr.dalmaru.account;

import java.util.UUID;

public record AccountSession(UUID accountId, UUID activeCharacterId, long connectionGeneration) { }
