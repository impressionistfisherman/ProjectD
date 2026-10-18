package kr.dalmaru.client;

import com.google.gson.JsonParser;

final class ClientPlayAccessState {
	private static String text = "임시. 상점은 마을 안, 제작은 대장간 안. 재련은 첫 장비, 계승은 첫 장비와 둘째 장비.";
	private static String offerId;
	private static int version;
	private static long nextId = 1;

	private ClientPlayAccessState() { }

	static long nextId() { return nextId++; }

	static String text() { return text; }

	static String offerId() { return offerId; }

	static int version() { return version; }

	static void accept(String result, boolean rejected) {
		if (rejected) {
			try {
				var json = JsonParser.parseString(result).getAsJsonObject();
				if (json.has("message") && json.get("message").isJsonPrimitive()) { text = "임시 " + json.get("message").getAsString(); return; }
			} catch (RuntimeException ignored) { }
			text = "임시 요청이 거절되었습니다.";
			return;
		}
		try {
			var json = JsonParser.parseString(result).getAsJsonObject();
			if (json.has("offerId") && json.get("offerId").isJsonPrimitive()) offerId = json.get("offerId").getAsString();
			if (json.has("version") && json.get("version").isJsonPrimitive() && json.get("version").getAsJsonPrimitive().isNumber()) version = json.get("version").getAsInt();
			String shown = json.toString();
			text = "임시 " + (shown.length() > 72 ? shown.substring(0, 72) : shown);
		} catch (RuntimeException exception) {
			text = "임시 결과를 확인하는 중입니다.";
		}
	}
}
