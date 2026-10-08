package kr.dalmaru.client;

import java.util.HashMap;
import java.util.Map;
import kr.dalmaru.network.DalmaruPayloads;

final class ClientSkillState {
	private static final Map<String, Integer> RANKS = new HashMap<>();
	private static long nextRequestId = 1;
	private ClientSkillState() { }
	static void resetForConnection() { RANKS.clear(); nextRequestId = 1; }
	static long nextRequestId() { return nextRequestId++; }
	static void accept(DalmaruPayloads.SkillRanksResponse response) { RANKS.clear(); response.ranks().forEach(rank -> RANKS.put(rank.skillId(), rank.rank())); }
	static int rank(String skillId) { return RANKS.getOrDefault(skillId, 0); }
}
