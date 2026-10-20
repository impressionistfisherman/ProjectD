package kr.dalmaru.client;

final class ClientCraftState {
	private static String text = "제작식 연구를 기다리는 중입니다.";
	private static long nextId = 1;

	private ClientCraftState() { }

	static long nextId() { return nextId++; }

	static String text() { return text; }

	static void accept(String result, boolean rejected) {
		text = rejected ? DalmaruClient.rejectionText(result, "제작을 진행할 수 없습니다.") : "제작 결과를 받았습니다.";
	}
}
