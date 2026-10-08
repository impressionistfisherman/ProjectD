package kr.dalmaru.equipment;

import java.util.UUID;

public record RefinementResult(UUID equipmentId, int grade, int failures, boolean success, boolean replayed) { }
