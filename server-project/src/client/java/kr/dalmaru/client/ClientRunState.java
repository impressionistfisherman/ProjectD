package kr.dalmaru.client;

import kr.dalmaru.network.DalmaruPayloads;

final class ClientRunState {
    private static long requestId = 1;
    private static String status = "폐봉수대 입장 난이도를 선택하세요.";
    private ClientRunState() { }
    static void resetForConnection() { requestId = 1; status = "폐봉수대 입장 난이도를 선택하세요."; }
    static long nextRequestId() { return requestId++; }
    static void accept(DalmaruPayloads.RunResponse response) { status = response.status(); }
    static String status() { return status; }
}
