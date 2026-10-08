package kr.dalmaru.equipment;

import java.util.UUID;

public record InheritanceResult(UUID targetEquipmentId, int credit, boolean replayed) { }
