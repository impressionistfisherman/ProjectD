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

	public record Outcome(String status, String reason, kr.dalmaru.run.RunMechanicAccess.MemberExit exit) {
		public Outcome(String status, String reason) { this(status, reason, null); }
	}

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

	public static Outcome roster(Connection connection, UUID runId) throws SQLException { return rosterAt(connection, runId, Instant.now()); }

	public static Outcome rosterAt(Connection connection, UUID runId, Instant now) throws SQLException {
		if (runId == null || lock(connection, runId) == null) return reject("거절: 파티 명단은 아직 열리지 않았습니다.");
		closeExpired(connection, runId, now);
		return new Outcome("ROSTER", rosterText(connection, runId));
	}

	public static Outcome openKickVote(Connection connection, UUID actorAccount, UUID actorCharacter, UUID runId, UUID targetCharacter, Instant now, boolean bossFight) throws SQLException {
		var rules = PartyRules.current();
		lockAccounts(connection, actorAccount);
		var row = lock(connection, runId);
		if (row == null) return reject("거절: 파티 명단은 아직 열리지 않았습니다.");
		if (!actorAccount.equals(accountOf(connection, actorCharacter))) return reject("거절: 다른 계정입니다.");
		if (!"ACTIVE".equals(row.phase)) return reject("거절: 진행 중인 공략에서만 투표할 수 있습니다.");
		if (bossFight) return reject("거절: 보스 전투 중에는 추방 투표를 열 수 없습니다.");
		if (actorCharacter.equals(targetCharacter)) return reject("거절: 자기 자신은 투표 대상이 아닙니다.");
		if (!present(connection, runId, actorCharacter) || !present(connection, runId, targetCharacter)) return reject("거절: 대상이 명단에 없습니다.");
		int present = presentCount(connection, runId);
		if (present < rules.voteMinPresent()) return reject("거절: 3명 미만은 추방 투표를 열 수 없습니다.");
		closeExpired(connection, runId, now);
		if (openVote(connection, runId) != null) return reject("거절: 열린 투표가 있습니다.");
		Instant blockedUntil = revoteBlockedUntil(connection, runId, targetCharacter, rules.revoteWaitSeconds());
		if (blockedUntil != null && now.isBefore(blockedUntil)) return reject("거절: 같은 대상은 60초 뒤에 다시 투표할 수 있습니다.");
		UUID voteId = UUID.randomUUID();
		Instant expires = now.plusSeconds(rules.voteExpireSeconds());
		try (var vote = connection.prepareStatement("INSERT INTO dalmaru.run_kick_votes(vote_id,run_id,target_character_id,opener_character_id,opened_at,expires_at,roster_revision) VALUES(?,?,?,?,?,?,?)")) {
			vote.setObject(1, voteId);
			vote.setObject(2, runId);
			vote.setObject(3, targetCharacter);
			vote.setObject(4, actorCharacter);
			vote.setTimestamp(5, Timestamp.from(now));
			vote.setTimestamp(6, Timestamp.from(expires));
			vote.setLong(7, row.revision);
			vote.executeUpdate();
		}
		try (var electorate = connection.prepareStatement("INSERT INTO dalmaru.run_kick_electorate(vote_id,character_id) SELECT ?, character_id FROM dalmaru.run_member_lifecycle WHERE run_id=? AND left_at IS NULL AND character_id<>?")) {
			electorate.setObject(1, voteId);
			electorate.setObject(2, runId);
			electorate.setObject(3, targetCharacter);
			electorate.executeUpdate();
		}
		insertBallot(connection, voteId, actorCharacter, true, now);
		return new Outcome("OPEN", voteText(connection, voteId, rules.voteExpireSeconds()));
	}

	public static Outcome castKickVote(Connection connection, UUID actorAccount, UUID actorCharacter, UUID runId, UUID voteId, boolean yes, Instant now, boolean bossFight) throws SQLException {
		lockAccounts(connection, actorAccount);
		var row = lock(connection, runId);
		if (row == null) return reject("거절: 파티 명단은 아직 열리지 않았습니다.");
		if (!actorAccount.equals(accountOf(connection, actorCharacter))) return reject("거절: 다른 계정입니다.");
		if (voteId == null) voteId = openVote(connection, runId);
		if (voteId == null) return reject("거절: 열린 투표가 없습니다.");
		var vote = lockVote(connection, voteId);
		if (vote == null || !vote.runId.equals(runId)) return reject("거절: 다른 공략의 투표입니다.");
		if (vote.closed) return reject("거절: 끝난 투표입니다.");
		if (!now.isBefore(vote.expiresAt)) return expire(connection, voteId, now);
		if (actorCharacter.equals(vote.target)) return reject("거절: 대상은 투표할 수 없습니다.");
		if (!elector(connection, voteId, actorCharacter)) return reject("거절: 투표할 수 없습니다.");
		if (ballotExists(connection, voteId, actorCharacter)) return reject("거절: 이미 투표했습니다.");
		insertBallot(connection, voteId, actorCharacter, yes, now);
		int eligible = electorateCount(connection, voteId);
		int yesCount = yesCount(connection, voteId);
		if (yesCount >= PartyVoteRules.requiredYes(eligible + 1)) {
			if (bossFight) return reject("거절: 보스 전투 중에는 추방을 확정하지 않습니다.");
			if (!"ACTIVE".equals(row.phase)) return reject("거절: 진행 중인 공략에서만 추방을 확정할 수 없습니다.");
			return removeKicked(connection, voteId, runId, vote.target, row.revision, now);
		}
		long remaining = Math.max(0, vote.expiresAt.getEpochSecond() - now.getEpochSecond());
		return new Outcome("VOTED", "찬성 " + yesCount + "/" + eligible + " · 필요 " + PartyVoteRules.requiredYes(eligible + 1) + " · 남은 " + remaining + "초");
	}

	public static Outcome settle(Connection connection, UUID runId, Instant now) throws SQLException {
		var row = lock(connection, runId);
		if (row == null) return reject("거절: 파티 명단은 아직 열리지 않았습니다.");
		UUID voteId = openVote(connection, runId);
		if (voteId == null) return new Outcome("ROSTER", rosterText(connection, runId));
		var vote = lockVote(connection, voteId);
		if (vote == null) return new Outcome("ROSTER", rosterText(connection, runId));
		if (!now.isBefore(vote.expiresAt)) return expire(connection, voteId, now);
		return new Outcome("OPEN", voteText(connection, voteId, Math.max(0, vote.expiresAt.getEpochSecond() - now.getEpochSecond())));
	}

	private static final kr.dalmaru.run.RunMechanicAccess.MemberExitAdapter EXIT_REMOVAL = new kr.dalmaru.run.RunMemberExit();

	private static Outcome removeKicked(Connection connection, UUID voteId, UUID runId, UUID characterId, long revision, Instant now) throws SQLException {
		var exit = EXIT_REMOVAL.removeMember(connection, runId, characterId, revision, kr.dalmaru.run.RunMechanicAccess.ExitReason.KICKED);
		if (exit.status() == kr.dalmaru.run.RunMechanicAccess.ExitStatus.REMOVED || exit.status() == kr.dalmaru.run.RunMechanicAccess.ExitStatus.ALREADY_REMOVED) {
			close(connection, voteId, now, "REMOVED");
			return new Outcome("REMOVED", "추방 확정", exit);
		}
		String reason = switch (exit.status()) {
			case STALE_REVISION -> "거절: 명단이 바뀌었습니다.";
			case NOT_PARTICIPANT -> "거절: 대상이 명단에 없습니다.";
			default -> "거절: 지금은 추방할 수 없습니다.";
		};
		return reject(reason);
	}

	private static void closeExpired(Connection connection, UUID runId, Instant now) throws SQLException {
		UUID voteId = openVote(connection, runId);
		if (voteId == null) return;
		var vote = lockVote(connection, voteId);
		if (vote != null && !vote.closed && !now.isBefore(vote.expiresAt)) expire(connection, voteId, now);
	}

	private static Outcome expire(Connection connection, UUID voteId, Instant now) throws SQLException {
		close(connection, voteId, now, "EXPIRED");
		return new Outcome("EXPIRED", "거절: 투표 시간이 끝났습니다.");
	}

	private static void close(Connection connection, UUID voteId, Instant now, String outcome) throws SQLException {
		try (var update = connection.prepareStatement("UPDATE dalmaru.run_kick_votes SET closed_at=?, outcome=? WHERE vote_id=? AND closed_at IS NULL")) {
			update.setTimestamp(1, Timestamp.from(now));
			update.setString(2, outcome);
			update.setObject(3, voteId);
			update.executeUpdate();
		}
	}

	private static void insertBallot(Connection connection, UUID voteId, UUID voter, boolean yes, Instant now) throws SQLException {
		try (var ballot = connection.prepareStatement("INSERT INTO dalmaru.run_kick_ballots(vote_id,voter_character_id,choice,cast_at) VALUES(?,?,?,?)")) {
			ballot.setObject(1, voteId);
			ballot.setObject(2, voter);
			ballot.setString(3, yes ? "YES" : "NO");
			ballot.setTimestamp(4, Timestamp.from(now));
			ballot.executeUpdate();
		}
	}

	private static String voteText(Connection connection, UUID voteId, long remainingSeconds) throws SQLException {
		int eligible = electorateCount(connection, voteId);
		return "투표가 열렸습니다. 찬성 " + yesCount(connection, voteId) + "/" + eligible + " · 필요 " + PartyVoteRules.requiredYes(eligible + 1) + " · 남은 " + remainingSeconds + "초";
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

	private static boolean present(Connection connection, UUID runId, UUID characterId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.run_member_lifecycle WHERE run_id=? AND character_id=? AND left_at IS NULL")) {
			query.setObject(1, runId);
			query.setObject(2, characterId);
			try (var result = query.executeQuery()) { return result.next(); }
		}
	}

	private static int presentCount(Connection connection, UUID runId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT COUNT(*) FROM dalmaru.run_member_lifecycle WHERE run_id=? AND left_at IS NULL")) {
			query.setObject(1, runId);
			try (var result = query.executeQuery()) { result.next(); return result.getInt(1); }
		}
	}

	private static UUID openVote(Connection connection, UUID runId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT vote_id FROM dalmaru.run_kick_votes WHERE run_id=? AND closed_at IS NULL")) {
			query.setObject(1, runId);
			try (var result = query.executeQuery()) { return result.next() ? result.getObject(1, UUID.class) : null; }
		}
	}

	private static Instant revoteBlockedUntil(Connection connection, UUID runId, UUID target, int waitSeconds) throws SQLException {
		try (var query = connection.prepareStatement("SELECT opened_at FROM dalmaru.run_kick_votes WHERE run_id=? AND target_character_id=? ORDER BY opened_at DESC LIMIT 1")) {
			query.setObject(1, runId);
			query.setObject(2, target);
			try (var result = query.executeQuery()) {
				if (!result.next()) return null;
				return result.getTimestamp(1).toInstant().plusSeconds(waitSeconds);
			}
		}
	}

	private record Vote(UUID runId, UUID target, Instant expiresAt, boolean closed) { }

	private static Vote lockVote(Connection connection, UUID voteId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT run_id, target_character_id, expires_at, closed_at FROM dalmaru.run_kick_votes WHERE vote_id=? FOR UPDATE")) {
			query.setObject(1, voteId);
			try (var result = query.executeQuery()) {
				if (!result.next()) return null;
				return new Vote(result.getObject(1, UUID.class), result.getObject(2, UUID.class), result.getTimestamp(3).toInstant(), result.getTimestamp(4) != null);
			}
		}
	}

	private static boolean elector(Connection connection, UUID voteId, UUID characterId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.run_kick_electorate e JOIN dalmaru.run_kick_votes v ON v.vote_id=e.vote_id JOIN dalmaru.run_member_lifecycle l ON l.run_id=v.run_id AND l.character_id=e.character_id AND l.left_at IS NULL WHERE e.vote_id=? AND e.character_id=?")) {
			query.setObject(1, voteId);
			query.setObject(2, characterId);
			try (var result = query.executeQuery()) { return result.next(); }
		}
	}

	private static boolean ballotExists(Connection connection, UUID voteId, UUID characterId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.run_kick_ballots WHERE vote_id=? AND voter_character_id=?")) {
			query.setObject(1, voteId);
			query.setObject(2, characterId);
			try (var result = query.executeQuery()) { return result.next(); }
		}
	}

	private static int electorateCount(Connection connection, UUID voteId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT COUNT(*) FROM dalmaru.run_kick_electorate e JOIN dalmaru.run_kick_votes v ON v.vote_id=e.vote_id JOIN dalmaru.run_member_lifecycle l ON l.run_id=v.run_id AND l.character_id=e.character_id AND l.left_at IS NULL WHERE e.vote_id=?")) {
			query.setObject(1, voteId);
			try (var result = query.executeQuery()) { result.next(); return result.getInt(1); }
		}
	}

	private static int yesCount(Connection connection, UUID voteId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT COUNT(*) FROM dalmaru.run_kick_ballots b JOIN dalmaru.run_kick_electorate e ON e.vote_id=b.vote_id AND e.character_id=b.voter_character_id JOIN dalmaru.run_kick_votes v ON v.vote_id=b.vote_id JOIN dalmaru.run_member_lifecycle l ON l.run_id=v.run_id AND l.character_id=b.voter_character_id AND l.left_at IS NULL WHERE b.vote_id=? AND b.choice='YES'")) {
			query.setObject(1, voteId);
			try (var result = query.executeQuery()) { result.next(); return result.getInt(1); }
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
