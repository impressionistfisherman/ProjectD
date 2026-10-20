package kr.dalmaru.client;

final class ClientPrecisionState {
	private static String text = "정밀 주문을 기다리는 중입니다.";
	private static long nextId = 1;

	private ClientPrecisionState() { }

	static long nextId() { return nextId++; }

	static String text() { return text; }

	static void accept(String result, boolean rejected) {
		text = rejected ? "정밀 주문을 진행할 수 없습니다." : "장비는 그대로 두고 공정 결과를 받았습니다.";
	}
}
