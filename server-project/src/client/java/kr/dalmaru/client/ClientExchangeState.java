package kr.dalmaru.client;

import com.google.gson.JsonParser;

final class ClientExchangeState {
	private static String text = "거래소에서 검색하고 구매합니다.";
	private static String query = "iron_ingot";
	private static int offset;
	private static int price = 100;
	private static String stashId;
	private static String listingId;
	private static long nextId = 1;

	private ClientExchangeState() { }

	static long nextId() { return nextId++; }

	static String text() { return text; }

	static String query() { return query; }

	static int offset() { return offset; }

	static int price() { return price; }

	static String stashId() { return stashId; }

	static String listingId() { return listingId; }

	static void accept(String result, boolean rejected) {
		try {
			var json = JsonParser.parseString(result).getAsJsonObject();
			if (json.has("stashId") && !json.get("stashId").isJsonNull()) stashId = json.get("stashId").getAsString();
			if (json.has("listingId") && !json.get("listingId").isJsonNull()) listingId = json.get("listingId").getAsString();
			if (json.has("rows") && json.get("rows").isJsonArray() && !json.getAsJsonArray("rows").isEmpty()) {
				var first = json.getAsJsonArray("rows").get(0).getAsJsonObject();
				if (first.has("listingId")) listingId = first.get("listingId").getAsString();
				if (first.has("price")) price = first.get("price").getAsInt();
			}
			String status = json.has("status") ? json.get("status").getAsString() : "";
			text = status.isEmpty() ? "거래소" : status;
		} catch (RuntimeException exception) {
			text = rejected ? "거래소 요청이 거절되었습니다." : "거래소 상태를 확인하는 중입니다.";
		}
	}
}
