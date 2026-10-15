package kr.dalmaru.account;

import java.sql.Connection;
import java.sql.SQLException;

/** Vault, trade, and mail must call this inside the caller's transaction before a row moves. */
public final class SupplyStay {
	private SupplyStay() { }

	public static void refuseMove(Connection connection, String itemId, String binding) throws SQLException {
		if (connection == null || connection.getAutoCommit()) throw new IllegalArgumentException("Supply stay must be judged in the caller's transaction.");
		if (blocked(itemId, binding)) throw new IllegalArgumentException("Bound supply cannot move.");
	}

	public static boolean blocked(String itemId, String binding) {
		if (itemId == null || itemId.isBlank() || binding == null || (!binding.equals("FREE") && !binding.equals("ACCOUNT") && !binding.equals("CHARACTER"))) return true;
		return "CHARACTER".equals(binding) || !StarterSupply.tradable(itemId);
	}
}
