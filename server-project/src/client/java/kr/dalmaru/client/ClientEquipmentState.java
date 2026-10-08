package kr.dalmaru.client;

import java.util.List;
import kr.dalmaru.network.DalmaruPayloads;

final class ClientEquipmentState {
    private static List<DalmaruPayloads.EquipmentData> equipment = List.of();
    private static String status = "장비를 불러오는 중입니다.";
    private static long requestId = 1;
    private ClientEquipmentState() { }
    static void resetForConnection() { equipment = List.of(); status = "장비를 불러오는 중입니다."; requestId = 1; }
    static long nextRequestId() { return requestId++; }
    static void accept(DalmaruPayloads.EquipmentListResponse response) { equipment = response.equipment(); status = response.status(); }
    static void accept(DalmaruPayloads.EquipmentEnhanceResponse response) { if (!response.accepted()) { status = response.status(); return; } equipment = equipment.stream().map(item -> item.equipmentId().equals(current) ? new DalmaruPayloads.EquipmentData(item.equipmentId(), item.slotId(), item.itemId(), response.enhancement(), response.consecutiveFailures(), item.bound()) : item).toList(); status = response.status(); }
    private static java.util.UUID current;
    static void enhance(java.util.UUID equipmentId) { current = equipmentId; }
    static List<DalmaruPayloads.EquipmentData> equipment() { return equipment; }
    static String status() { return status; }
}
