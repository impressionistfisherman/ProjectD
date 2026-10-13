package kr.dalmaru.equipment;

public enum EquipmentBinding {
    FREE, ACCOUNT, CHARACTER;
    public EquipmentBinding merge(EquipmentBinding other) { return ordinal() >= other.ordinal() ? this : other; }
}
