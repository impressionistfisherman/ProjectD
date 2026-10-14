package kr.dalmaru.equipment;

public record EnhancementResult(EquipmentRecord equipment, boolean success, boolean replayed, int resultEnhancement, int resultFailures) {
    public EnhancementResult(EquipmentRecord equipment,boolean success,boolean replayed){this(equipment,success,replayed,equipment.enhancement(),equipment.consecutiveFailures());}
}
