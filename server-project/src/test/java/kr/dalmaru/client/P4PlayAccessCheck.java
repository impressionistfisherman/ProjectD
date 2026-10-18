package kr.dalmaru.client;

import java.nio.file.Files;
import java.nio.file.Path;
import kr.dalmaru.network.DalmaruPayloads;

public final class P4PlayAccessCheck {
	public static void main(String[] args) throws Exception {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
		ClientPartyState.resetForConnection();
		long first = ClientPartyState.nextId();
		ClientPartyState.accept(new DalmaruPayloads.P4State(0, "REJECT", 0, 0, "거절: 늦은 응답"));
		require("로딩".equals(ClientPartyState.status()), "Stale party reply changed the screen");
		ClientPartyState.accept(new DalmaruPayloads.P4State(first, "REJECT", 0, 0, "거절: 파티 명단은 아직 열리지 않았습니다."));
		require(ClientPartyState.status().contains("아직 열리지"), "Rejected roster is not shown");
		long retry = ClientPartyState.nextId();
		ClientPartyState.accept(new DalmaruPayloads.P4State(retry, "ROSTER", 0, 0, "명단 1명"));
		require("명단 1명".equals(ClientPartyState.status()), "Retried roster is not shown");
		ClientPartyState.accept(new DalmaruPayloads.P4State(retry, "WAITING", 0, 0, "추방 확정"));
		require(ClientPartyState.status().contains("성공으로 처리할 수 없습니다"), "Removal confirmation reached the screen");

		ClientBrazierState.resetForConnection();
		ClientBrazierState.accept(new DalmaruPayloads.P4BrazierHud(1, 0, 0, 0, "", "IDLE", 0, "120.0/400.0/30.0/100.0/COLLAPSED/80"));
		require(ClientBrazierState.lines().size() == 1 && ClientBrazierState.lines().get(0).contains("보스"), "Reconnect boss snapshot did not return");
		ClientBrazierState.accept(new DalmaruPayloads.P4BrazierHud(2, 1, 2, 40, "STARTED", "WARNING", 200, "120.0/400.0/30.0/100.0/COLLAPSED/80"));
		var lines = ClientBrazierState.lines();
		require(lines.size() == 3 && lines.get(0).contains("보스") && lines.get(1).contains("경고") && lines.get(2).contains("화로"), "HUD order is not boss, neglect, channel");

		String root = System.getProperty("user.dir");
		String party = Files.readString(Path.of(root, "src/client/java/kr/dalmaru/client/DalmaruPartyScreen.java"));
		String server = Files.readString(Path.of(root, "src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
		require(party.contains("requestParty(\"party_menu\")") && party.contains("requestParty(\"invite\"") && party.contains("requestParty(\"accept\")") && party.contains("requestParty(\"kick_vote\"") && party.contains("requestParty(\"kick_yes\")") && party.contains("requestParty(\"kick_no\")"), "Party buttons do not call their requests");
		require(!party.contains("자진 이탈") && !party.contains("추방 확정"), "Unassigned exit screen is present");
		int hello = server.indexOf("DalmaruPayloads.Hello.TYPE");
		int compatible = server.indexOf("state.compatible = true;", hello);
		int resend = server.indexOf("sendBrazierHud(context.player(), \"\");", hello);
		int welcome = server.indexOf("new DalmaruPayloads.Welcome(PROTOCOL_VERSION)", hello);
		require(compatible >= 0 && resend > compatible && welcome > resend, "Reconnect does not resend the snapshot");
		System.out.println("P4 play access PASS");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
