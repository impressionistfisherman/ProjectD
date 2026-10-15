package kr.dalmaru.equipment;

import java.util.UUID;

public record EquipmentRecord(UUID equipmentId, UUID characterId, int slotId, String itemId, int requiredLevel, String profession, int enhancement, int consecutiveFailures, boolean bound, EquipmentBinding binding, int refinementGrade) {
    public EquipmentRecord { if(refinementGrade<0||refinementGrade>5)throw new IllegalArgumentException("Invalid refinement grade."); }
    public EquipmentRecord(UUID equipmentId, UUID characterId, int slotId, String itemId, int requiredLevel, String profession, int enhancement, int failures, boolean bound, EquipmentBinding binding) {
        this(equipmentId,characterId,slotId,itemId,requiredLevel,profession,enhancement,failures,bound,binding,0);
    }
    public EquipmentRecord(UUID equipmentId, UUID characterId, int slotId, String itemId, int requiredLevel, String profession, int enhancement, int failures, boolean bound) {
        this(equipmentId, characterId, slotId, itemId, requiredLevel, profession, enhancement, failures, bound, bound ? EquipmentBinding.CHARACTER : EquipmentBinding.FREE);
    }
}
