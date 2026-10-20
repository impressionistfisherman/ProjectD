package kr.dalmaru.client;

import com.google.gson.JsonParser;

final class ClientLifeState {
	private static String text = "생활 직업을 불러오는 중입니다.";
	private static long nextId = 1;

	private ClientLifeState() { }

	static long nextId() { return nextId++; }

	static String text() { return text; }

	static void acceptProfile(String status, String snapshot) {
		if (!"OK".equals(status)) { text = "생활 직업을 열 수 없습니다."; return; }
		text = labels(snapshot);
	}

	static void acceptLearn(String result, boolean rejected) {
		if (rejected) { text = "생활 직업을 배울 수 없습니다."; return; }
		text = labels(result);
	}

	private static String labels(String json) {
		try {
			var body = JsonParser.parseString(json).getAsJsonObject();
			var line = new StringBuilder();
			for (var entry : body.getAsJsonArray("jobs")) {
				var job = entry.getAsJsonObject();
				line.append(DalmaruLifeScreen.label(job.get("job").getAsString())).append(' ').append(job.get("proficiency").getAsInt()).append(' ');
			}
			String shown = line.toString().trim();
			return shown.isEmpty() ? "배운 생활 직업이 없습니다." : shown;
		} catch (RuntimeException exception) {
			return "생활 직업을 확인하는 중입니다.";
		}
	}
}
