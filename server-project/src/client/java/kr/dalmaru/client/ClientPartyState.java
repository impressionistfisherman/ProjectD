package kr.dalmaru.client;

import kr.dalmaru.network.DalmaruPayloads;

final class ClientPartyState {
	private static long next = 1;
	private static long current;
	private static String status = "로딩";

	private ClientPartyState() { }

	static void resetForConnection() { next = 1; current = 0; status = "로딩"; }
	static long nextId() { current = next++; return current; }
	static void loading() { status = "로딩"; }

	static void reject(String reason) {
		status = reason != null && reason.startsWith("거절") ? reason : "거절: 성공으로 처리할 수 없습니다.";
	}

	static void accept(DalmaruPayloads.P4State payload) {
		if (payload.requestId() != current) return;
		if (!"REJECT".equals(payload.status())) {
			status = "거절: 성공으로 처리할 수 없습니다.";
			return;
		}
		reject(payload.reason());
	}

	static String status() { return status; }
}
