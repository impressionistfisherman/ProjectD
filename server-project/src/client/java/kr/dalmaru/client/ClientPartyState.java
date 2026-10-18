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
		if ("REJECT".equals(payload.status())) { reject(payload.reason()); return; }
		if ("REMOVED".equals(payload.status()) && payload.reason() != null && payload.reason().contains("추방 확정") && !payload.reason().contains("성공")) { status = payload.reason(); return; }
		if (("INVITED".equals(payload.status()) || "ACCEPTED".equals(payload.status()) || "ROSTER".equals(payload.status()) || "OPEN".equals(payload.status()) || "VOTED".equals(payload.status()) || "WAITING".equals(payload.status()) || "EXPIRED".equals(payload.status()) || "EXITING".equals(payload.status())) && payload.reason() != null && !payload.reason().contains("성공") && !payload.reason().contains("추방 확정")) {
			status = payload.reason();
			return;
		}
		status = "거절: 성공으로 처리할 수 없습니다.";
	}

	static String status() { return status; }
}
