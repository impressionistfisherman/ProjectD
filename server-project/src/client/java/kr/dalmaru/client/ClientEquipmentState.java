package kr.dalmaru.client;

import java.util.List;
import kr.dalmaru.network.DalmaruPayloads;

final class ClientEquipmentState {
    private static List<DalmaruPayloads.EquipmentData> equipment = List.of();
    private static String status = "장비를 불러오는 중입니다.";
    private static long requestId = 1;
    private static long latestQuery,lastListReply,lastEnhancementReply,statusUntil,minimumRequest=1;
    private static String listStatus=status;
    private ClientEquipmentState() { }
    static void resetForConnection() { equipment = List.of(); status = "장비를 불러오는 중입니다.";listStatus=status;requestId = 1;latestQuery=0;lastListReply=0;lastEnhancementReply=0;statusUntil=0;minimumRequest=1; }
    static void selected(){equipment=List.of();minimumRequest=requestId;statusUntil=0;}
    static long nextRequestId() { return requestId++; }
    static void queried(long request){latestQuery=request;}
    static void accept(DalmaruPayloads.EquipmentListResponse response) { if(response.requestId()<minimumRequest||response.requestId()<latestQuery||response.requestId()<lastListReply)return;lastListReply=response.requestId();equipment = response.equipment();listStatus=response.status(); }
    static void accept(DalmaruPayloads.EquipmentEnhanceResponse response) {
        if(response.requestId()<minimumRequest||response.requestId()<lastEnhancementReply)return;
        lastEnhancementReply=response.requestId();status=response.status();statusUntil=System.nanoTime()+2_000_000_000L;
    }
    static List<DalmaruPayloads.EquipmentData> equipment() { return equipment; }
    static String status() { return System.nanoTime()<statusUntil?status:listStatus; }
}
