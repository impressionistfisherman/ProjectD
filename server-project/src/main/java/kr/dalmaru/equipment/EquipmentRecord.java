package kr.dalmaru.equipment;

import java.util.UUID;

public record EquipmentRecord(UUID equipmentId, UUID characterId, int slotId, String itemId, int requiredLevel, String profession, int enhancement, int consecutiveFailures, boolean bound) { }
