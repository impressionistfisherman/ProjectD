package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.party.PartyRunService;
import kr.dalmaru.party.PartyVoteRules;

public final class RunKickVoteCheck {
	private static final UUID ACCOUNT_A = UUID.fromString("00000000-0000-4000-8000-00000000b701");
	private static final UUID ACCOUNT_B = UUID.fromString("00000000-0000-4000-8000-00000000b702");
	private static final UUID ACCOUNT_C = UUID.fromString("00000000-0000-4000-8000-00000000b703");
	private static final UUID RUN4 = UUID.fromString("00000000-0000-4000-8000-00000000b711");
	private static final UUID RUN3 = UUID.fromString("00000000-0000-4000-8000-00000000b712");
	private static final UUID RUN2 = UUID.fromString("00000000-0000-4000-8000-00000000b713");

	private RunKickVoteCheck() { }

	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		migrate();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		cleanup();
		try {
			accounts.openSession(ACCOUNT_A);
			accounts.openSession(ACCOUNT_B);
			accounts.openSession(ACCOUNT_C);
			UUID leader = characters.create(ACCOUNT_A, 1, "p4s08_leader").characterId();
			UUID a2 = characters.create(ACCOUNT_A, 2, "p4s08_a2").characterId();
			UUID a3 = characters.create(ACCOUNT_A, 3, "p4s08_a3").characterId();
			UUID a4 = characters.create(ACCOUNT_A, 4, "p4s08_a4").characterId();
			UUID b1 = characters.create(ACCOUNT_B, 1, "p4s08_b1").characterId();
			UUID b2 = characters.create(ACCOUNT_B, 2, "p4s08_b2").characterId();
			UUID b3 = characters.create(ACCOUNT_B, 3, "p4s08_b3").characterId();
			UUID c1 = characters.create(ACCOUNT_C, 1, "p4s08_c1").characterId();
			UUID c2 = characters.create(ACCOUNT_C, 2, "p4s08_c2").characterId();
			insertRun(RUN4, leader);
			addMember(RUN4, a2);
			addMember(RUN4, b1);
			addMember(RUN4, b2);
			insertRun(RUN3, a3);
			addMember(RUN3, a4);
			addMember(RUN3, b3);
			insertRun(RUN2, c1);
			addMember(RUN2, c2);
			Instant start = Instant.parse("2026-10-18T00:00:00Z");
			try (var connection = EnhancementReplayCheck.connection()) {
				connection.setAutoCommit(false);
				require(PartyVoteRules.requiredYes(3) == 2 && PartyVoteRules.requiredYes(4) == 2, "Quorum math");
				require("ROSTER".equals(PartyRunService.open(connection, RUN4, leader, "ACTIVE").status()), "Run of 4 did not open");
				require("ROSTER".equals(PartyRunService.open(connection, RUN3, a3, "ACTIVE").status()), "Run of 3 did not open");
				require("ROSTER".equals(PartyRunService.open(connection, RUN2, c1, "ACTIVE").status()), "Run of 2 did not open");
				require(reject(PartyRunService.openKickVote(connection, ACCOUNT_C, c1, RUN2, c2, start, false), "3명"), "Two members were allowed to vote");
				require(reject(PartyRunService.openKickVote(connection, ACCOUNT_A, leader, RUN4, b2, start, true), "보스"), "Boss fight opened a vote");
				require(voteCount(connection, RUN4) == 0, "Rejected open left a vote");
				require(reject(PartyRunService.openKickVote(connection, ACCOUNT_A, leader, RUN4, leader, start, false), "자기"), "Self target was allowed");
				var opened = PartyRunService.openKickVote(connection, ACCOUNT_A, leader, RUN4, b2, start, false);
				require("OPEN".equals(opened.status()) && opened.reason().contains("찬성 1/3") && opened.reason().contains("필요 2") && opened.reason().contains("남은 30") && !opened.reason().contains("성공"), opened.reason());
				UUID vote = voteId(connection, RUN4);
				require(reject(PartyRunService.castKickVote(connection, ACCOUNT_C, c1, RUN4, vote, true, start, false), "투표할 수 없습니다"), "Outsider voted");
				require(reject(PartyRunService.castKickVote(connection, ACCOUNT_B, b2, RUN4, vote, true, start, false), "대상"), "Target voted");
				require(reject(PartyRunService.castKickVote(connection, ACCOUNT_A, leader, RUN4, vote, true, start, false), "이미"), "Duplicate vote was stored");
				require("VOTED".equals(PartyRunService.castKickVote(connection, ACCOUNT_B, b1, RUN4, vote, false, start, false).status()), "No vote was not recorded");
				var passed = PartyRunService.castKickVote(connection, ACCOUNT_A, a2, RUN4, vote, true, start, false);
				require("WAITING".equals(passed.status()) && passed.reason().contains("준비되지") && !passed.reason().contains("성공") && !passed.reason().contains("추방 확정"), passed.reason());
				require(left(connection, RUN4) == 0 && "WAITING_REMOVAL".equals(outcome(connection, vote)), "Kick was applied before removal exists");
				require(reject(PartyRunService.castKickVote(connection, ACCOUNT_A, a2, RUN4, vote, true, start, false), "끝난"), "Closed vote was replayed");
				require(reject(PartyRunService.openKickVote(connection, ACCOUNT_A, leader, RUN4, b2, start.plusSeconds(59), false), "60초"), "Revote wait ignored the 59 second boundary");
				var again = PartyRunService.openKickVote(connection, ACCOUNT_A, leader, RUN4, b2, start.plusSeconds(60), false);
				require("OPEN".equals(again.status()), again.reason());
				UUID second = voteId(connection, RUN4);
				require(reject(PartyRunService.castKickVote(connection, ACCOUNT_B, b1, RUN4, second, true, start.plusSeconds(60), true), "보스"), "Boss start confirmed a kick");
				require(outcome(connection, second) == null && left(connection, RUN4) == 0, "Boss start closed or removed a member");
				var expired = PartyRunService.settle(connection, RUN4, start.plusSeconds(90));
				require("EXPIRED".equals(expired.status()) && left(connection, RUN4) == 0, expired.reason());
				var quiet = PartyRunService.openKickVote(connection, ACCOUNT_A, a3, RUN3, b3, start, false);
				require("OPEN".equals(quiet.status()) && quiet.reason().contains("찬성 1/2") && quiet.reason().contains("필요 2"), quiet.reason());
				require(electorate(connection, voteId(connection, RUN3)) == 2, "Abstain changed the denominator");
				var abstained = PartyRunService.settle(connection, RUN3, start.plusSeconds(30));
				require("EXPIRED".equals(abstained.status()) && left(connection, RUN3) == 0, abstained.reason());
				var leaderVote = PartyRunService.openKickVote(connection, ACCOUNT_B, b3, RUN3, a3, start.plusSeconds(30), false);
				require("OPEN".equals(leaderVote.status()), leaderVote.reason());
				UUID leaderBallot = voteId(connection, RUN3);
				require(reject(PartyRunService.castKickVote(connection, ACCOUNT_A, a3, RUN3, leaderBallot, true, start.plusSeconds(30), false), "대상"), "Leader target used a different rule");
				var leaderPassed = PartyRunService.castKickVote(connection, ACCOUNT_A, a4, RUN3, leaderBallot, true, start.plusSeconds(30), false);
				require("WAITING".equals(leaderPassed.status()) && left(connection, RUN3) == 0, leaderPassed.reason());
				var finished = PartyRunService.openKickVote(connection, ACCOUNT_B, b3, RUN3, a4, start.plusSeconds(90), false);
				require("OPEN".equals(finished.status()), finished.reason());
				try (var phase = connection.prepareStatement("UPDATE dalmaru.run_party_control SET phase='COMPLETED' WHERE run_id=?")) {
					phase.setObject(1, RUN3);
					phase.executeUpdate();
				}
				var blocked = PartyRunService.castKickVote(connection, ACCOUNT_A, a3, RUN3, voteId(connection, RUN3), true, start.plusSeconds(90), false);
				require("REJECT".equals(blocked.status()) && blocked.reason().contains("진행") && left(connection, RUN3) == 0, blocked.reason());
				var early = PartyRunService.openKickVote(connection, ACCOUNT_A, leader, RUN4, b2, start.plusSeconds(119), false);
				require(reject(early, "60초") && !early.reason().contains("열린 투표"), early.reason());
				var fromOpen = PartyRunService.openKickVote(connection, ACCOUNT_A, leader, RUN4, b2, start.plusSeconds(120), false);
				require("OPEN".equals(fromOpen.status()), fromOpen.reason());
				UUID quietVote = voteId(connection, RUN4);
				var sameTarget = PartyRunService.openKickVote(connection, ACCOUNT_A, leader, RUN4, b2, start.plusSeconds(151), false);
				require(reject(sameTarget, "60초") && !sameTarget.reason().contains("열린 투표"), sameTarget.reason());
				require("EXPIRED".equals(outcome(connection, quietVote)), "Expired vote stayed open");
				var otherTarget = PartyRunService.openKickVote(connection, ACCOUNT_A, leader, RUN4, a2, start.plusSeconds(151), false);
				require("OPEN".equals(otherTarget.status()), otherTarget.reason());
				UUID lookedUp = voteId(connection, RUN4);
				require("ROSTER".equals(PartyRunService.rosterAt(connection, RUN4, start.plusSeconds(182)).status()), "Roster did not load");
				require("EXPIRED".equals(outcome(connection, lookedUp)), "Roster left an expired vote open");
				String networking = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
				require(networking.contains("Phase.GATEKEEPER") && networking.contains("Phase.BEACON"), "Gatekeeper boss fight is not rejected");
				connection.commit();
			}
			System.out.println("Run kick vote PASS");
		} finally { cleanup(); }
	}

	private static boolean reject(PartyRunService.Outcome outcome, String fragment) { return "REJECT".equals(outcome.status()) && outcome.reason().contains(fragment); }
	private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

	private static UUID voteId(java.sql.Connection connection, UUID runId) throws Exception {
		try (var query = connection.prepareStatement("SELECT vote_id FROM dalmaru.run_kick_votes WHERE run_id=? AND closed_at IS NULL")) {
			query.setObject(1, runId);
			try (var result = query.executeQuery()) { if (!result.next()) throw new AssertionError("Open vote missing"); return result.getObject(1, UUID.class); }
		}
	}

	private static int voteCount(java.sql.Connection connection, UUID runId) throws Exception {
		try (var query = connection.prepareStatement("SELECT COUNT(*) FROM dalmaru.run_kick_votes WHERE run_id=?")) {
			query.setObject(1, runId);
			try (var result = query.executeQuery()) { result.next(); return result.getInt(1); }
		}
	}

	private static int electorate(java.sql.Connection connection, UUID voteId) throws Exception {
		try (var query = connection.prepareStatement("SELECT COUNT(*) FROM dalmaru.run_kick_electorate WHERE vote_id=?")) {
			query.setObject(1, voteId);
			try (var result = query.executeQuery()) { result.next(); return result.getInt(1); }
		}
	}

	private static String outcome(java.sql.Connection connection, UUID voteId) throws Exception {
		try (var query = connection.prepareStatement("SELECT outcome FROM dalmaru.run_kick_votes WHERE vote_id=?")) {
			query.setObject(1, voteId);
			try (var result = query.executeQuery()) { result.next(); return result.getString(1); }
		}
	}

	private static int left(java.sql.Connection connection, UUID runId) throws Exception {
		try (var query = connection.prepareStatement("SELECT COUNT(*) FROM dalmaru.run_member_lifecycle WHERE run_id=? AND left_at IS NOT NULL")) {
			query.setObject(1, runId);
			try (var result = query.executeQuery()) { result.next(); return result.getInt(1); }
		}
	}

	private static void insertRun(UUID runId, UUID characterId) throws Exception {
		try (var connection = EnhancementReplayCheck.connection()) {
			try (var run = connection.prepareStatement("INSERT INTO dalmaru.run_instances(run_id,content_id,difficulty,content_version,week_id,party_size,scaling_tier,state) VALUES(?,'run.bongsudae','FIRST_STEP','pilot-1',CURRENT_DATE,1,1,'ACTIVE')")) {
				run.setObject(1, runId);
				run.executeUpdate();
			}
			addMember(connection, runId, characterId);
		}
	}

	private static void addMember(UUID runId, UUID characterId) throws Exception {
		try (var connection = EnhancementReplayCheck.connection()) { addMember(connection, runId, characterId); }
	}

	private static void addMember(java.sql.Connection connection, UUID runId, UUID characterId) throws Exception {
		try (var member = connection.prepareStatement("INSERT INTO dalmaru.run_participants(run_id,character_id) VALUES(?,?)")) {
			member.setObject(1, runId);
			member.setObject(2, characterId);
			member.executeUpdate();
		}
	}

	private static void migrate() throws Exception {
		apply(40, "database/migrations/V040__run_member_lifecycle.sql");
		apply(41, "database/migrations/V041__run_kick_votes.sql");
	}

	private static void apply(int version, String path) throws Exception {
		try (var connection = EnhancementReplayCheck.connection(); var check = connection.prepareStatement("SELECT 1 FROM dalmaru.schema_migrations WHERE version=?")) {
			check.setInt(1, version);
			try (var result = check.executeQuery()) { if (result.next()) return; }
		}
		for (String statement : Files.readString(Path.of(path)).split(";")) {
			String trimmed = statement.trim();
			if (trimmed.isEmpty()) continue;
			try (var connection = EnhancementReplayCheck.connection(); var command = connection.createStatement()) { command.execute(trimmed); }
		}
	}

	private static void cleanup() throws Exception {
		if (System.getenv("DALMARU_DB_PASSWORD") == null || System.getenv("DALMARU_DB_PASSWORD").isBlank()) return;
		try (var connection = EnhancementReplayCheck.connection()) {
			for (String sql : new String[] {
				"DELETE FROM dalmaru.run_kick_ballots WHERE vote_id IN (SELECT vote_id FROM dalmaru.run_kick_votes WHERE run_id IN (?, ?, ?))",
				"DELETE FROM dalmaru.run_kick_electorate WHERE vote_id IN (SELECT vote_id FROM dalmaru.run_kick_votes WHERE run_id IN (?, ?, ?))",
				"DELETE FROM dalmaru.run_kick_votes WHERE run_id IN (?, ?, ?)",
				"DELETE FROM dalmaru.run_party_invites WHERE run_id IN (?, ?, ?)",
				"DELETE FROM dalmaru.run_member_lifecycle WHERE run_id IN (?, ?, ?)",
				"DELETE FROM dalmaru.run_party_control WHERE run_id IN (?, ?, ?)",
				"DELETE FROM dalmaru.run_participants WHERE run_id IN (?, ?, ?)",
				"DELETE FROM dalmaru.run_instances WHERE run_id IN (?, ?, ?)",
				"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id IN (?, ?, ?)",
				"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id IN (?, ?, ?))",
				"DELETE FROM dalmaru.characters WHERE account_id IN (?, ?, ?)",
				"DELETE FROM dalmaru.accounts WHERE account_id IN (?, ?, ?)"
			}) {
				try (var statement = connection.prepareStatement(sql)) {
					boolean account = sql.contains("account_id");
					statement.setObject(1, account ? ACCOUNT_A : RUN4);
					statement.setObject(2, account ? ACCOUNT_B : RUN3);
					statement.setObject(3, account ? ACCOUNT_C : RUN2);
					statement.executeUpdate();
				}
			}
		}
	}
}
