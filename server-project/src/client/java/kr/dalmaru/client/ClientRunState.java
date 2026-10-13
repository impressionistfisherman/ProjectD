package kr.dalmaru.client;

import kr.dalmaru.network.DalmaruPayloads;

final class ClientRunState {
    private static long requestId = 1;
    private static long pendingRequestId, pendingUntil;
    private static boolean waiting;
    private static String status = "폐봉수대 입장 난이도를 선택하세요.";
    private ClientRunState() { }
    static void resetForConnection() { requestId = 1; waiting = false; status = "폐봉수대 입장 난이도를 선택하세요."; }
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
        if (!waiting || response.requestId() != pendingRequestId) return false;
        waiting = false;
        status = response.status();
        return response.accepted();
    }
    static String status() { return status; }
}
