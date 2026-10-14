package kr.dalmaru.equipment;

public record EnhancementResult(EquipmentRecord equipment, boolean success, boolean replayed, int resultEnhancement, int resultFailures, kr.dalmaru.request.RequestJournal.Receipt receipt) {
    public EnhancementResult(EquipmentRecord equipment,boolean success,boolean replayed,int enhancement,int failures){this(equipment,success,replayed,enhancement,failures,null);}
    public EnhancementResult(EquipmentRecord equipment,boolean success,boolean replayed){this(equipment,success,replayed,equipment.enhancement(),equipment.consecutiveFailures());}
}
