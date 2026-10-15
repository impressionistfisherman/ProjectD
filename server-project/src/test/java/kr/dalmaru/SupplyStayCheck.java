package kr.dalmaru;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import kr.dalmaru.account.SupplyStay;

public final class SupplyStayCheck {
	private SupplyStayCheck() { }

	public static void main(String[] args) throws Exception {
		require(SupplyStay.blocked("supply.training_gear", "FREE"), "Training gear could move.");
		require(SupplyStay.blocked("supply.recovery", "ACCOUNT"), "Recovery supply could move.");
		require(SupplyStay.blocked("pilot.weapon.two_handed_sword.10", "FREE"), "Level 10 kit could move.");
		require(SupplyStay.blocked("pilot.weapon.two_handed_sword.15", "ACCOUNT"), "Level 15 kit could move.");
		require(SupplyStay.blocked("material.ironstone", "CHARACTER"), "Character binding could move.");
		require(!SupplyStay.blocked("material.ironstone", "FREE") && !SupplyStay.blocked("material.ironstone", "ACCOUNT"), "A free material was blocked.");
		require(SupplyStay.blocked(null, "FREE") && SupplyStay.blocked("material.ironstone", "BOUND"), "A missing id or binding was allowed.");
		var open = connection(false);
		SupplyStay.refuseMove(open, "material.ironstone", "FREE");
		reject(() -> SupplyStay.refuseMove(open, "supply.recovery", "FREE"));
		reject(() -> SupplyStay.refuseMove(open, "material.ironstone", "CHARACTER"));
		reject(() -> SupplyStay.refuseMove(connection(true), "material.ironstone", "FREE"));
		reject(() -> SupplyStay.refuseMove(null, "material.ironstone", "FREE"));
		System.out.println("Supply stay blocks granted and character-bound items PASS");
	}

	private static Connection connection(boolean autoCommit) {
		return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[] { Connection.class }, (proxy, method, args) -> {
			if ("getAutoCommit".equals(method.getName())) return autoCommit;
			throw new UnsupportedOperationException(method.getName());
		});
	}

	private static void reject(Attempt attempt) {
		try { attempt.run(); }
		catch (IllegalArgumentException expected) { return; }
		catch (Exception exception) { throw new IllegalStateException("Unexpected supply stay failure.", exception); }
		throw new IllegalStateException("A blocked move was accepted.");
	}

	private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

	private interface Attempt { void run() throws Exception; }
}
