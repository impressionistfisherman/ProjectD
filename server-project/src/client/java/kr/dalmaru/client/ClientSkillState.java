package kr.dalmaru.client;

import java.util.HashMap;
import java.util.Map;
import kr.dalmaru.network.DalmaruPayloads;

final class ClientSkillState {
	private static final Map<String, Integer> RANKS = new HashMap<>();
	private static long nextRequestId = 1;
	private static int ticks,deadline;
	private static boolean waiting;
	private ClientSkillState() { }
	static void resetForConnection() { RANKS.clear(); nextRequestId = 1;ticks=0;deadline=0;waiting=false; }
	static void selected(){RANKS.clear();waiting=false;nextRequestId++;}
	static long nextRequestId() { waiting=true;deadline=ticks+100;return nextRequestId++; }
	static void accept(DalmaruPayloads.SkillRanksResponse response) { if(response.requestId()!=nextRequestId-1)return;waiting=false;RANKS.clear(); response.ranks().forEach(rank -> RANKS.put(rank.skillId(), rank.rank())); }
	static boolean tick(){ticks++;return waiting&&ticks>=deadline;}
	static int rank(String skillId) { return RANKS.getOrDefault(skillId, 0); }
}
