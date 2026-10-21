package kr.dalmaru.client;

import com.google.gson.JsonParser;

final class ClientPlayAccessState {
	private static String text = "임시. 상점은 마을 안, 제작은 대장간 안. 거래 받기는 제안 번호, 경매는 마지막 공략.";
	private static String offerId;
	private static int version;
	private static String auctionId;
	private static int minimumBid;
	private static long nextId = 1;

	private ClientPlayAccessState() { }

	static long nextId() { return nextId++; }

	static String text() { return text; }

	static String offerId() { return offerId; }

	static int version() { return version; }

	static String auctionId() { return auctionId; }

	static int minimumBid() { return minimumBid; }

	static void accept(String result, boolean rejected) {
		if (rejected) {
			try {
				var json = JsonParser.parseString(result).getAsJsonObject();
				if (json.has("message") && json.get("message").isJsonPrimitive()) { text = "임시 " + json.get("message").getAsString(); return; }
				if (json.has("reason") || json.has("status")) { text = "임시 " + DalmaruClient.rejectionText(result, "임시 요청이 거절되었습니다."); return; }
			} catch (RuntimeException ignored) { }
			text = "임시 요청이 거절되었습니다.";
			return;
		}
		try {
			var json = JsonParser.parseString(result).getAsJsonObject();
			if (json.has("offerId") && json.get("offerId").isJsonPrimitive()) offerId = json.get("offerId").getAsString();
			if (json.has("version") && json.get("version").isJsonPrimitive() && json.get("version").getAsJsonPrimitive().isNumber()) version = json.get("version").getAsInt();
			if (json.has("auctionId") && json.get("auctionId").isJsonPrimitive()) auctionId = json.get("auctionId").getAsString();
			if (json.has("minimumBid") && json.get("minimumBid").isJsonPrimitive() && json.get("minimumBid").getAsJsonPrimitive().isNumber()) {
				try { minimumBid = json.get("minimumBid").getAsBigDecimal().intValueExact(); }
				catch (ArithmeticException error) { minimumBid = 0; }
			}
			String shown = json.toString();
			text = "임시 " + (shown.length() > 72 ? shown.substring(0, 72) : shown);
		} catch (RuntimeException exception) {
			text = "임시 결과를 확인하는 중입니다.";
		}
	}
}
