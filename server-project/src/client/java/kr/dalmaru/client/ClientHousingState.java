package kr.dalmaru.client;

import com.google.gson.JsonParser;

final class ClientHousingState {
	private static String text = "거처를 만들면 주택, 농지 4칸, 기초 공방이 열립니다.";
	private static String housingId;
	private static String targetAccount;
	private static String permission = "VISIT";
	private static String targetId;
	private static String grantId;
	private static String changeId;
	private static int plot;
	private static String crop = "grain";
	private static int cropGeneration = 1;
	private static long nextId = 1;

	private ClientHousingState() { }

	static long nextId() { return nextId++; }

	static String text() { return text; }

	static String housingId() { return housingId; }
	static String targetAccount() { return targetAccount; }
	static String permission() { return permission; }
	static String targetId() { return targetId; }
	static String grantId() { return grantId; }
	static String changeId() { return changeId; }
	static int plot() { return plot; }
	static String crop() { return crop; }
	static int cropGeneration() { return cropGeneration; }

	static void cyclePlot() {
		plot = (plot + 1) % 4;
		text = "농지 " + plot + " " + crop;
	}

	static void cycleCrop() {
		String[] crops = {"grain", "vegetable", "herb", "fiber", "flower"};
		int index = 0;
		for (int i = 0; i < crops.length; i++) if (crops[i].equals(crop)) index = i;
		crop = crops[(index + 1) % crops.length];
		text = "농지 " + plot + " " + crop;
	}

	static void accept(String result, boolean rejected) {
		try {
			var json = JsonParser.parseString(result).getAsJsonObject();
			if (json.has("housingId") && !json.get("housingId").isJsonNull()) housingId = json.get("housingId").getAsString();
			if (json.has("grantId") && !json.get("grantId").isJsonNull()) grantId = json.get("grantId").getAsString();
			if (json.has("generation") && !json.get("generation").isJsonNull()) cropGeneration = json.get("generation").getAsInt();
			String status = json.has("status") ? json.get("status").getAsString() : "";
			if ("ABSENT".equals(status) && !json.has("dimension")) text = "거처를 먼저 만드세요.";
			else if (json.has("dimension")) text = status + " " + json.get("dimension").getAsString() + " " + json.get("x").getAsInt() + "," + json.get("y").getAsInt() + "," + json.get("z").getAsInt();
			else text = (status.isEmpty() ? "거처" : status) + (housingId == null ? "" : " " + housingId);
		} catch (RuntimeException exception) {
			text = rejected ? "거처 요청이 거절되었습니다." : "거처 상태를 확인하는 중입니다.";
		}
	}
}
