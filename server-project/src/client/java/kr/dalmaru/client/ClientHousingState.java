package kr.dalmaru.client;

import com.google.gson.JsonParser;

final class ClientHousingState {
	private static String text = "거처를 만들면 주택, 농지 4칸, 기초 공방이 열립니다.";
	private static String housingId;
	private static long nextId = 1;

	private ClientHousingState() { }

	static long nextId() { return nextId++; }

	static String text() { return text; }

	static String housingId() { return housingId; }

	static void accept(String result, boolean rejected) {
		try {
			var json = JsonParser.parseString(result).getAsJsonObject();
			if (json.has("housingId") && !json.get("housingId").isJsonNull()) housingId = json.get("housingId").getAsString();
			String status = json.has("status") ? json.get("status").getAsString() : "";
			if ("ABSENT".equals(status) && !json.has("dimension")) text = "거처를 먼저 만드세요.";
			else if (json.has("dimension")) text = status + " " + json.get("dimension").getAsString() + " " + json.get("x").getAsInt() + "," + json.get("y").getAsInt() + "," + json.get("z").getAsInt();
			else text = (status.isEmpty() ? "거처" : status) + (housingId == null ? "" : " " + housingId);
		} catch (RuntimeException exception) {
			text = rejected ? "거처 요청이 거절되었습니다." : "거처 상태를 확인하는 중입니다.";
		}
	}
}
