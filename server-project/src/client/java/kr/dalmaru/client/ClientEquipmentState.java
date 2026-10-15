package kr.dalmaru.client;

import java.util.List;
import kr.dalmaru.network.DalmaruPayloads;

final class ClientEquipmentState {
    private static List<DalmaruPayloads.EquipmentData> equipment = List.of();
    private static String status = "장비를 불러오는 중입니다.";
    private static long requestId = 1;
    private static long latestQuery,lastListReply,lastEnhancementReply,statusUntil,minimumRequest=1;
    private static String listStatus=status;
    private static final java.util.Map<java.util.UUID,DalmaruPayloads.RefinementStateResponse> refinements=new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID,Long> refinementQueries=new java.util.HashMap<>();
    static void queriedRefinement(long correlation,java.util.UUID item){refinementQueries.put(item,correlation);}
    static void acceptRefinement(DalmaruPayloads.RefinementStateResponse response){
        if(response.requestId()<minimumRequest||response.requestId()<refinementQueries.getOrDefault(response.equipmentId(),0L)||response.grade()<0||response.grade()>5||response.failures()<0||equipment.stream().noneMatch(item->item.equipmentId().equals(response.equipmentId())))return;
        var current=refinements.get(response.equipmentId());if(current!=null&&response.requestId()<current.requestId())return;
        refinements.put(response.equipmentId(),response);
    }
    static String refinement(java.util.UUID item){var current=refinements.get(item);return current==null?"":" · 재련 "+current.grade()+" (실패 "+current.failures()+")";}
    private ClientEquipmentState() { }
    static void resetForConnection() { refinements.clear();refinementQueries.clear(); equipment = List.of(); status = "장비를 불러오는 중입니다.";listStatus=status;requestId = 1;latestQuery=0;lastListReply=0;lastEnhancementReply=0;statusUntil=0;minimumRequest=1; }
    static void selected(){refinements.clear();refinementQueries.clear();equipment=List.of();minimumRequest=requestId;statusUntil=0;}
    static long nextRequestId() { return requestId++; }
    static void queried(long request){latestQuery=request;}
    static void accept(DalmaruPayloads.EquipmentListResponse response) { if(response.requestId()<minimumRequest||response.requestId()<latestQuery||response.requestId()<lastListReply)return;lastListReply=response.requestId();equipment = response.equipment();listStatus=response.status(); }
    static void accept(DalmaruPayloads.EquipmentEnhanceResponse response) {
        if(response.requestId()<minimumRequest||response.requestId()<lastEnhancementReply)return;
        lastEnhancementReply=response.requestId();status=response.status();statusUntil=System.nanoTime()+2_000_000_000L;
    }
    static void movementStatus(String message){status=message;statusUntil=System.nanoTime()+2_000_000_000L;}
    static List<DalmaruPayloads.EquipmentData> equipment() { return equipment; }
    static String status() { return System.nanoTime()<statusUntil?status:listStatus; }
}
