package kr.dalmaru.client;

import kr.dalmaru.network.DalmaruPayloads;

final class ClientCombatState {
	private static DalmaruPayloads.CombatStateResponse current;
	private ClientCombatState() { }
	static void resetForConnection() { current = null; }
	static void accept(DalmaruPayloads.CombatStateResponse response) { current = response; }
	static DalmaruPayloads.CombatStateResponse current() { return current; }
}
