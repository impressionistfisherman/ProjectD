package kr.dalmaru.party;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

/** Run roster after a run row exists. The in-memory waiting party stays where it is. */
public final class PartyRunService {
	private PartyRunService() { }

	public record Outcome(String status, String reason) { }

	public static Connection connect() throws SQLException {
		String password = System.getenv("DALMARU_DB_PASSWORD");
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		String host = System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost");
		String port = System.getenv().getOrDefault("DALMARU_DB_PORT", "15432");
		String database = System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
		String url = "jdbc:postgresql://" + host + ":" + port + "/" + database + "?connectTimeout=1&loginTimeout=1&socketTimeout=2&options=-c%20statement_timeout%3D250ms%20-c%20lock_timeout%3D200ms";
		return java.sql.DriverManager.getConnection(url, System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), password);
	}

	public static Outcome open(Connection connection, UUID runId, UUID leaderCharacter, String phase) throws SQLException {
		if (!"READY".equals(phase) && !"ACTIVE".equals(phase)) return reject("거절: 단계가 없습니다.");
		PartyRules.current();
		lockAccounts(connection, accountOf(connection, leaderCharacter));
		try (var insert = connection.prepareStatement("INSERT INTO dalmaru.run_party_control(run_id,rules_snapshot,leader_character_id,phase) VALUES(?,?::jsonb,?,?) ON CONFLICT (run_id) DO NOTHING")) {
			insert.setObject(1, runId);
			insert.setString(2, PartyRules.snapshot());
			insert.setObject(3, leaderCharacter);
			insert.setString(4, phase);
			insert.executeUpdate();
		}
		try (var members = connection.prepareStatement("INSERT INTO dalmaru.run_member_lifecycle(run_id,character_id) SELECT run_id,character_id FROM dalmaru.run_participants WHERE run_id=? ON CONFLICT DO NOTHING")) {
			members.setObject(1, runId);
			members.executeUpdate();
		}
		return new Outcome("ROSTER", rosterText(connection, runId));
	}

	public static Outcome invite(Connection connection, UUID actorAccount, UUID actorCharacter, UUID runId, UUID targetCharacter, Instant invitedAt) throws SQLException {
		UUID targetAccount = accountOf(connection, targetCharacter);
		lockAccounts(connection, actorAccount, targetAccount);
		var row = lock(connection, runId);
		if (row == null) return reject("거절: 파티 명단은 아직 열리지 않았습니다.");
		if (!actorAccount.equals(accountOf(connection, actorCharacter))) return reject("거절: 다른 계정입니다.");
		if (!row.leader.equals(actorCharacter)) return reject("거절: 파티장만 초대할 수 있습니다.");
		if (left(connection, runId, targetCharacter)) return reject("거절: 이탈자는 다시 들어올 수 없습니다.");
		if (!"READY".equals(row.phase)) return reject("거절: 준비 중에만 초대할 수 있습니다.");
		if (participant(connection, runId, targetCharacter)) return reject("거절: 이미 명단에 있습니다.");
		try (var invite = connection.prepareStatement("INSERT INTO dalmaru.run_party_invites(run_id,character_id,roster_revision,invited_at) VALUES(?,?,?,?) ON CONFLICT (run_id,character_id) DO UPDATE SET roster_revision=EXCLUDED.roster_revision, invited_at=EXCLUDED.invited_at")) {
			invite.setObject(1, runId);
			invite.setObject(2, targetCharacter);
			invite.setLong(3, row.revision);
			invite.setTimestamp(4, Timestamp.from(invitedAt));
			invite.executeUpdate();
		}
		return new Outcome("INVITED", "초대를 기록했습니다.");
	}

	public static Outcome accept(Connection connection, UUID actorAccount, UUID actorCharacter, UUID runId) throws SQLException {
		lockAccounts(connection, actorAccount);
		var row = lock(connection, runId);
		if (row == null) return reject("거절: 파티 명단은 아직 열리지 않았습니다.");
		if (!actorAccount.equals(accountOf(connection, actorCharacter))) return reject("거절: 다른 계정입니다.");
		if (!"READY".equals(row.phase)) return reject("거절: 준비 중에만 수락할 수 있습니다.");
		Long invitedRevision = inviteRevision(connection, runId, actorCharacter);
		if (invitedRevision == null) return reject("거절: 초대가 없습니다.");
		if (invitedRevision != row.revision) return reject("거절: 오래된 초대입니다.");
		if (left(connection, runId, actorCharacter)) return reject("거절: 이탈자는 다시 들어올 수 없습니다.");
		try (var member = connection.prepareStatement("INSERT INTO dalmaru.run_participants(run_id,character_id) VALUES(?,?)")) {
			member.setObject(1, runId);
			member.setObject(2, actorCharacter);
			member.executeUpdate();
		}
		try (var life = connection.prepareStatement("INSERT INTO dalmaru.run_member_lifecycle(run_id,character_id) VALUES(?,?)")) {
			life.setObject(1, runId);
			life.setObject(2, actorCharacter);
			life.executeUpdate();
		}
		try (var drop = connection.prepareStatement("DELETE FROM dalmaru.run_party_invites WHERE run_id=? AND character_id=?")) {
			drop.setObject(1, runId);
			drop.setObject(2, actorCharacter);
			drop.executeUpdate();
		}
		try (var bump = connection.prepareStatement("UPDATE dalmaru.run_party_control SET roster_revision=roster_revision+1 WHERE run_id=?")) {
			bump.setObject(1, runId);
			bump.executeUpdate();
		}
		return new Outcome("ACCEPTED", "명단에 넣었습니다.");
	}

	public static Outcome activate(Connection connection, UUID runId) throws SQLException {
		var row = lock(connection, runId);
		if (row == null) return reject("거절: 파티 명단은 아직 열리지 않았습니다.");
		try (var update = connection.prepareStatement("UPDATE dalmaru.run_party_control SET phase='ACTIVE', roster_revision=roster_revision+1 WHERE run_id=?")) {
			update.setObject(1, runId);
			update.executeUpdate();
		}
		try (var drop = connection.prepareStatement("DELETE FROM dalmaru.run_party_invites WHERE run_id=?")) {
			drop.setObject(1, runId);
			drop.executeUpdate();
		}
		return new Outcome("ROSTER", rosterText(connection, runId));
	}

	public static Outcome advanceRoster(Connection connection, UUID runId) throws SQLException {
		var row = lock(connection, runId);
		if (row == null) return reject("거절: 파티 명단은 아직 열리지 않았습니다.");
		try (var update = connection.prepareStatement("UPDATE dalmaru.run_party_control SET roster_revision=roster_revision+1 WHERE run_id=?")) {
			update.setObject(1, runId);
			update.executeUpdate();
		}
		return new Outcome("ROSTER", rosterText(connection, runId));
	}

	public static Outcome markLeft(Connection connection, UUID runId, UUID characterId, Instant leftAt) throws SQLException {
		var row = lock(connection, runId);
		if (row == null) return reject("거절: 파티 명단은 아직 열리지 않았습니다.");
		try (var update = connection.prepareStatement("UPDATE dalmaru.run_member_lifecycle SET leave_reason='VOLUNTARY', left_at=? WHERE run_id=? AND character_id=? AND left_at IS NULL")) {
			update.setTimestamp(1, Timestamp.from(leftAt));
			update.setObject(2, runId);
			update.setObject(3, characterId);
			if (update.executeUpdate() != 1) return reject("거절: 명단에 없습니다.");
		}
		return new Outcome("ROSTER", rosterText(connection, runId));
	}

	public static Outcome roster(Connection connection, UUID runId) throws SQLException {
		if (runId == null || lock(connection, runId) == null) return reject("거절: 파티 명단은 아직 열리지 않았습니다.");
		return new Outcome("ROSTER", rosterText(connection, runId));
	}

	private static Outcome reject(String reason) { return new Outcome("REJECT", reason); }

	private static String rosterText(Connection connection, UUID runId) throws SQLException {
		var row = lock(connection, runId);
		int count = 0;
		try (var query = connection.prepareStatement("SELECT COUNT(*) FROM dalmaru.run_member_lifecycle WHERE run_id=? AND left_at IS NULL")) {
			query.setObject(1, runId);
			try (var result = query.executeQuery()) { if (result.next()) count = result.getInt(1); }
		}
		String phase = row == null ? "" : "READY".equals(row.phase) ? "준비" : "진행";
		return "임시 명단 " + count + "명 · " + phase;
	}

	private record Row(String phase, long revision, UUID leader) { }

	private static Row lock(Connection connection, UUID runId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT phase, roster_revision, leader_character_id FROM dalmaru.run_party_control WHERE run_id=? FOR UPDATE")) {
			query.setObject(1, runId);
			try (var result = query.executeQuery()) {
				if (!result.next()) return null;
				return new Row(result.getString(1), result.getLong(2), result.getObject(3, UUID.class));
			}
		}
	}

	private static void lockAccounts(Connection connection, UUID... accounts) throws SQLException {
		var ordered = Arrays.stream(accounts).distinct().sorted(Comparator.comparing(UUID::toString)).toList();
		for (UUID account : ordered) {
			try (var query = connection.prepareStatement("SELECT account_id FROM dalmaru.accounts WHERE account_id=? FOR UPDATE")) {
				query.setObject(1, account);
				try (var result = query.executeQuery()) { if (!result.next()) throw new SQLException("Missing account"); }
			}
		}
	}

	private static UUID accountOf(Connection connection, UUID characterId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT account_id FROM dalmaru.characters WHERE character_id=?")) {
			query.setObject(1, characterId);
			try (var result = query.executeQuery()) {
				if (!result.next()) throw new SQLException("Missing character");
				return result.getObject(1, UUID.class);
			}
		}
	}

	private static boolean participant(Connection connection, UUID runId, UUID characterId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.run_participants WHERE run_id=? AND character_id=?")) {
			query.setObject(1, runId);
			query.setObject(2, characterId);
			try (var result = query.executeQuery()) { return result.next(); }
		}
	}

	private static boolean left(Connection connection, UUID runId, UUID characterId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.run_member_lifecycle WHERE run_id=? AND character_id=? AND left_at IS NOT NULL")) {
			query.setObject(1, runId);
			query.setObject(2, characterId);
			try (var result = query.executeQuery()) { return result.next(); }
		}
	}

	private static Long inviteRevision(Connection connection, UUID runId, UUID characterId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT roster_revision FROM dalmaru.run_party_invites WHERE run_id=? AND character_id=?")) {
			query.setObject(1, runId);
			query.setObject(2, characterId);
			try (var result = query.executeQuery()) { return result.next() ? result.getLong(1) : null; }
		}
	}
}
