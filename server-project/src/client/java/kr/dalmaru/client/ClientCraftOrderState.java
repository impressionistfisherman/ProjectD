package kr.dalmaru.client;

final class ClientCraftOrderState {
	private static String text = "주문판을 기다리는 중입니다.";
	private static long nextId = 1;

	private ClientCraftOrderState() { }

	static long nextId() { return nextId++; }

	static String text() { return text; }

	static void accept(String result, boolean rejected) {
		text = rejected ? "의뢰를 진행할 수 없습니다." : "의뢰 결과를 받았습니다.";
	}
}
