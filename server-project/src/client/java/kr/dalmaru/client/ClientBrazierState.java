package kr.dalmaru.client;

import kr.dalmaru.network.DalmaruPayloads;

final class ClientBrazierState {
	private static long next = 1;
	private static int lit;
	private static int total;
	private static int remaining;
	private static String result = "";
	private static String neglect = "IDLE";
	private static int neglectTicks;

	private ClientBrazierState() { }

	static void resetForConnection() { next = 1; lit = 0; total = 0; remaining = 0; result = ""; neglect = "IDLE"; neglectTicks = 0; }
	static long nextId() { return next++; }
	static boolean channeling() { return remaining > 0; }

	static void accept(DalmaruPayloads.P4BrazierHud hud) {
		lit = hud.litCount();
		total = hud.total();
		remaining = hud.remainingTicks();
		if (total < 1) { result = ""; neglect = "IDLE"; neglectTicks = 0; return; }
		if (!hud.result().isEmpty()) result = hud.result();
		neglect = hud.neglect() == null ? "IDLE" : hud.neglect();
		neglectTicks = Math.max(0, hud.neglectTicks());
	}

	static String line() {
		if (total < 1) return "";
		String text = "임시 화로 " + lit + "/" + total;
		if (remaining > 0) text += " · 남은 " + remaining + "틱";
		if (!result.isEmpty()) text += " · " + label(result);
		return text;
	}

	static String neglectLine() {
		if (total < 1) return "";
		int seconds = (Math.max(0, neglectTicks) + 19) / 20;
		return switch (neglect) {
			case "IDLE" -> neglectTicks > 0 ? "임시 방치 남은 " + seconds + "초" : "";
			case "WARNING" -> "임시 경고 남은 " + seconds + "초";
			case "DAMAGING" -> "임시 피해 중 · 다음 " + seconds + "초";
			default -> "";
		};
	}

	private static String label(String value) {
		return switch (value) {
			case "STARTED" -> "채널 진행";
			case "CANCELLED" -> "취소됨";
			case "ALREADY_CHANNELING" -> "이미 채널 중";
			case "NOT_CHANNELING" -> "거절: 채널 중이 아님";
			case "STALE_REVISION" -> "거절: 단계가 바뀌었습니다";
			case "NOT_PARTICIPANT" -> "거절: 참가자가 아닙니다";
			case "WRONG_PHASE" -> "거절: 화로 단계가 아닙니다";
			case "DEAD" -> "거절: 쓰러진 상태입니다";
			case "OUT_OF_RANGE" -> "거절: 거리가 멉니다";
			case "NO_LINE_OF_SIGHT" -> "거절: 시야가 없습니다";
			case "BUSY" -> "거절: 다른 사람이 조작 중입니다";
			case "ALREADY_LIT" -> "거절: 이미 점화되었습니다";
			case "MISSING" -> "거절: 화로 위치가 없습니다";
			default -> "거절: 지금은 조작할 수 없습니다";
		};
	}
}
