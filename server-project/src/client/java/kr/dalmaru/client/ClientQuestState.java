package kr.dalmaru.client;

import kr.dalmaru.network.DalmaruPayloads;

final class ClientQuestState {
	private static long nextRequestId = 1;
	private static long requestId;
	private static String status = "퀘스트 정보를 불러오세요.";
	private ClientQuestState() { }
	static void resetForConnection() { nextRequestId = 1; requestId = 0; status = "퀘스트 정보를 불러오세요."; }
    static void selected(){requestId=0;status="퀘스트 정보를 불러오세요.";}
	static long beginRequest() { return requestId = nextRequestId++; }
	static void accept(DalmaruPayloads.QuestResponse response) { if (response.requestId() == requestId) status = response.status(); }
	static String status() { return status; }
}
