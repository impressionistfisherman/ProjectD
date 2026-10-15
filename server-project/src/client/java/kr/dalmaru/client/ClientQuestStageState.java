package kr.dalmaru.client;

import com.google.gson.JsonParser;

final class ClientQuestStageState {
	private static String text = "조사 단계를 고르세요.";
	private static long nextId = 1;
	private static String stage = "field";

	private ClientQuestStageState() { }

	static long nextId() { return nextId++; }

	static String stage() { return stage; }

	static void stage(String value) { stage = value; }

	static String text() { return text; }

	static void accept(String result, boolean rejected) {
		if (rejected) { text = "이 조사 단계는 진행할 수 없습니다."; return; }
		try {
			var json = JsonParser.parseString(result).getAsJsonObject();
			text = json.get("stage").getAsString() + (json.get("confirmed").getAsBoolean() ? " 확정" : " 다시 시작") + (json.get("rewarded").getAsBoolean() ? " 보고 보상" : "");
		} catch (RuntimeException exception) {
			text = "조사 단계를 확인하는 중입니다.";
		}
	}
}
