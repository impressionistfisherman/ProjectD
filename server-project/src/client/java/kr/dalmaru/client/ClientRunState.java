package kr.dalmaru.client;

import kr.dalmaru.network.DalmaruPayloads;

final class ClientRunState {
    private static long requestId = 1;
    private static long pendingRequestId, pendingUntil;
    private static boolean waiting,preparing;
    private static java.util.UUID preparationId;
    private static String status = "폐봉수대 입장 난이도를 선택하세요.";
    private ClientRunState() { }
    static void resetForConnection() { requestId = 1; waiting = false;preparing=false;preparationId=null; status = "폐봉수대 입장 난이도를 선택하세요."; }
    static boolean canRequest() {
        if (waiting && System.nanoTime() >= pendingUntil) { waiting = false; status = "입장 응답 시간이 초과되었습니다. 다시 시도하세요."; }
        return !waiting;
    }
    static long nextRequestId() {
        if (!canRequest()) return 0;
        waiting = true;
        pendingUntil = System.nanoTime() + 10_000_000_000L;
        status = "폐봉수대 입장을 준비하고 있습니다.";
        return pendingRequestId = requestId++;
    }
    static boolean accept(DalmaruPayloads.RunResponse response) {
        if(preparing&&"CANCELLED".equals(response.state())&&response.runId().equals(preparationId)){preparing=false;waiting=false;preparationId=null;status=shown(response);return false;}
        if(preparing&&response.requestId()==pendingRequestId&&response.accepted()&&"ACTIVE".equals(response.state())){preparing=false;waiting=false;status=shown(response);return true;}
        if (!waiting || response.requestId() != pendingRequestId) return false;
        waiting = false;preparing=false;
        status = shown(response);
        return response.accepted();
    }
    static boolean prepare(DalmaruPayloads.RunResponse response){if(!"READY".equals(response.state()))return false;preparing=true;waiting=false;pendingRequestId=response.requestId();preparationId=response.runId();status=shown(response);return true;}
    private static String shown(DalmaruPayloads.RunResponse response){if(response.difficulty()==null||response.difficulty().isEmpty())return response.status();String name=switch(response.difficulty()){case "FIRST_STEP"->"초행";case "TRAINING"->"수련";case "TRIAL"->"시련";case "EXTREME"->"극한";default->response.difficulty();};return response.status()+" · "+name;}
    static boolean preparing(){return preparing;}
    static java.util.UUID preparationId(){return preparationId;}
    static String status() { return status; }
}
