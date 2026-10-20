package kr.dalmaru.client;

import com.google.gson.JsonParser;

final class ClientMarketState {
	private static String text = "판매대에 올리고 판매금을 받습니다.";
	private static String material = "material.iron_ingot";
	private static int quantity = 1;
	private static int price = 100;
	private static String stashId;
	private static String listingId;
	private static long nextId = 1;

	private ClientMarketState() { }

	static long nextId() { return nextId++; }

	static String text() { return text; }

	static String material() { return material; }

	static int quantity() { return quantity; }

	static int price() { return price; }

	static String stashId() { return stashId; }

	static String listingId() { return listingId; }

	static void accept(String result, boolean rejected) {
		try {
			var json = JsonParser.parseString(result).getAsJsonObject();
			if (json.has("stashId") && !json.get("stashId").isJsonNull()) stashId = json.get("stashId").getAsString();
			if (json.has("listingId") && !json.get("listingId").isJsonNull()) listingId = json.get("listingId").getAsString();
			String status = json.has("status") ? json.get("status").getAsString() : "";
			text = status.isEmpty() ? "판매대" : status;
		} catch (RuntimeException exception) {
			text = rejected ? "판매 요청이 거절되었습니다." : "판매 상태를 확인하는 중입니다.";
		}
	}
}
