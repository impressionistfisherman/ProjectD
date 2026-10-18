package kr.dalmaru.party;

import java.sql.Connection;
import java.util.UUID;

/** Call shape for S07. S01 does not cancel rewards or move a player. */
public final class PartyReturnAdapter {
	private PartyReturnAdapter() { }

	public static P4Contract.Result cancelReward(Connection connection, UUID runId, UUID characterId) {
		if (connection == null || runId == null || characterId == null) return P4Contract.reject("거절: 반환 정보가 없습니다.");
		return P4Contract.reject("거절: 참가자 반환은 아직 열리지 않았습니다.");
	}
}
