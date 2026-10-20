package kr.dalmaru.client;

import com.google.gson.JsonParser;

final class ClientProductionState {
	private static String text = "보유한 재료로 생산을 예약합니다.";
	private static String jobId;
	private static long nextId = 1;

	private ClientProductionState() { }

	static long nextId() { return nextId++; }

	static String text() { return text; }

	static String jobId() { return jobId; }

	static void accept(String result, boolean rejected) {
		try {
			var json = JsonParser.parseString(result).getAsJsonObject();
			if (json.has("jobId") && !json.get("jobId").isJsonNull()) jobId = json.get("jobId").getAsString();
			String status = json.has("status") ? json.get("status").getAsString() : "";
			text = status.isEmpty() ? "생산" : status + (jobId == null ? "" : " " + jobId);
		} catch (RuntimeException exception) {
			text = rejected ? "생산 요청이 거절되었습니다." : "생산 상태를 확인하는 중입니다.";
		}
	}
}
