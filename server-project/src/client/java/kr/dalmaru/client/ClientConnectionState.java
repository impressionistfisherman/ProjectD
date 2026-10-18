package kr.dalmaru.client;

import kr.dalmaru.network.DalmaruNetworking;

final class ClientConnectionState {
	private static final int PROBE_TIMEOUT_TICKS = 100;
	private static long nextRequestId = 1;
	private static long currentRequestId;
	private static int deadlineTick;
	private static int tick;
	private static boolean compatible;
	private static boolean waiting;
	private static String status = "서버 호환성 확인 중";

	private ClientConnectionState() { }

	static void resetForConnection() {
		nextRequestId = 1;
		currentRequestId = 0;
		deadlineTick = 0;
		tick = 0;
		compatible = false;
		waiting = false;
		status = "서버 호환성 확인 중";
	}

	static void acceptWelcome(int protocolVersion) {
		if (DalmaruNetworking.acceptsProtocol(protocolVersion)) {
			compatible = true;
			status = "서버 연결 준비됨";
		}
	}

	static boolean beginProbe() {
		if (!compatible || waiting || nextRequestId <= 0) {
			return false;
		}
		currentRequestId = nextRequestId++;
		waiting = true;
		deadlineTick = tick + PROBE_TIMEOUT_TICKS;
		status = "서버 응답 대기 중";
		return true;
	}

	static void acceptProbeResponse(long requestId, boolean rateLimited) {
		if (!waiting || requestId != currentRequestId) {
			return;
		}
		waiting = false;
		status = rateLimited ? "요청이 너무 빠릅니다. 잠시 후 다시 시도하세요." : "서버 연결이 확인되었습니다.";
	}

	static void tick() {
		tick++;
		if (waiting && tick >= deadlineTick) {
			waiting = false;
			status = "서버 응답 시간이 초과되었습니다. 다시 시도하세요.";
		}
	}

	static boolean canRequestProbe() { return compatible && !waiting; }
	static boolean isCompatible() { return compatible; }
	static long currentRequestId() { return currentRequestId; }
	static String status() { return status; }
	static String probeButtonLabel() {
		if (waiting || status.startsWith("서버 응답 시간이") || status.startsWith("요청이 너무 빠릅니다")) {
			return status;
		}
		return "연결 확인";
	}
}
