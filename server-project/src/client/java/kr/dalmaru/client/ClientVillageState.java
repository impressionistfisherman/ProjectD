package kr.dalmaru.client;

import com.google.gson.JsonParser;

final class ClientVillageState {
	private static String text = "마을 목록을 불러오는 중입니다.";
	private static long nextId = 1;

	private ClientVillageState() { }

	static long nextId() { return nextId++; }

	static String text() { return text; }

	static void accept(String operation, String result, boolean rejected) {
		if (rejected) { text = "마을 안내를 열 수 없습니다."; return; }
		try {
			var json = JsonParser.parseString(result).getAsJsonObject();
			if ("list_villages".equals(operation)) {
				var names = new StringBuilder();
				for (var village : json.getAsJsonArray("villages")) names.append(village.getAsJsonObject().get("name").getAsString()).append(' ');
				text = names.toString().trim();
			} else {
				text = json.get("name").getAsString() + " " + json.get("x").getAsInt() + "," + json.get("y").getAsInt() + "," + json.get("z").getAsInt() + " " + json.get("npc").getAsString() + ": " + json.get("line").getAsString();
			}
		} catch (RuntimeException exception) {
			text = "마을 안내를 확인하는 중입니다.";
		}
	}
}
