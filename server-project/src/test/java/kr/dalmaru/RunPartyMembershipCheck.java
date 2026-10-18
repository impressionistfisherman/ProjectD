package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.party.PartyRunService;

public final class RunPartyMembershipCheck {
	private static final UUID LEADER_ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000b601");
	private static final UUID OTHER_ACCOUNT = UUID.fromString("00000000-0000-4000-8000-00000000b602");
	private static final UUID RUN = UUID.fromString("00000000-0000-4000-8000-00000000b611");
	private static final UUID OTHER_RUN = UUID.fromString("00000000-0000-4000-8000-00000000b612");

	private RunPartyMembershipCheck() { }

	public static void main(String[] args) throws Exception {
		FixtureDatabase.databaseName();
		migrate();
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		cleanup();
		try {
			accounts.openSession(LEADER_ACCOUNT);
			accounts.openSession(OTHER_ACCOUNT);
			UUID leader = characters.create(LEADER_ACCOUNT, 1, "p4s06_leader").characterId();
			UUID member = characters.create(LEADER_ACCOUNT, 2, "p4s06_member").characterId();
			UUID recruit = characters.create(OTHER_ACCOUNT, 1, "p4s06_recruit").characterId();
			UUID outsider = characters.create(OTHER_ACCOUNT, 2, "p4s06_out").characterId();
			insertRun(RUN, leader);
			insertRun(OTHER_RUN, outsider);
			try (var connection = EnhancementReplayCheck.connection()) {
				connection.setAutoCommit(false);
				var opened = PartyRunService.open(connection, RUN, leader, "READY");
				require("ROSTER".equals(opened.status()) && opened.reason().contains("준비"), opened.reason());
				require("ROSTER".equals(PartyRunService.open(connection, OTHER_RUN, outsider, "READY").status()), "Other run did not open");
				require(disconnects(connection, RUN) == 0, "Historical disconnect time was invented");
				var otherRun = PartyRunService.invite(connection, LEADER_ACCOUNT, leader, OTHER_RUN, recruit, Instant.parse("2026-10-18T00:00:00Z"));
				require("REJECT".equals(otherRun.status()) && otherRun.reason().contains("파티장"), otherRun.reason());
				var otherAccount = PartyRunService.invite(connection, OTHER_ACCOUNT, leader, RUN, recruit, Instant.parse("2026-10-18T00:00:00Z"));
				require("REJECT".equals(otherAccount.status()) && otherAccount.reason().contains("다른 계정"), otherAccount.reason());
				var notLeader = PartyRunService.invite(connection, LEADER_ACCOUNT, member, RUN, recruit, Instant.parse("2026-10-18T00:00:00Z"));
				require("REJECT".equals(notLeader.status()) && notLeader.reason().contains("파티장"), notLeader.reason());
				var invited = PartyRunService.invite(connection, LEADER_ACCOUNT, leader, RUN, recruit, Instant.parse("2026-10-18T00:00:00Z"));
				require("INVITED".equals(invited.status()), invited.reason());
				PartyRunService.advanceRoster(connection, RUN);
				var stale = PartyRunService.accept(connection, OTHER_ACCOUNT, recruit, RUN);
				require("REJECT".equals(stale.status()) && stale.reason().contains("오래된 초대"), stale.reason());
				var again = PartyRunService.invite(connection, LEADER_ACCOUNT, leader, RUN, recruit, Instant.parse("2026-10-18T00:01:00Z"));
				require("INVITED".equals(again.status()), again.reason());
				var accepted = PartyRunService.accept(connection, OTHER_ACCOUNT, recruit, RUN);
				require("ACCEPTED".equals(accepted.status()) && !accepted.reason().contains("성공"), accepted.reason());
				PartyRunService.activate(connection, RUN);
				var mid = PartyRunService.invite(connection, LEADER_ACCOUNT, leader, RUN, outsider, Instant.parse("2026-10-18T00:02:00Z"));
				require("REJECT".equals(mid.status()) && mid.reason().contains("준비 중"), mid.reason());
				PartyRunService.markLeft(connection, RUN, recruit, Instant.parse("2026-10-18T00:03:00Z"));
				var rejoin = PartyRunService.invite(connection, LEADER_ACCOUNT, leader, RUN, recruit, Instant.parse("2026-10-18T00:04:00Z"));
				require("REJECT".equals(rejoin.status()) && rejoin.reason().contains("이탈자"), rejoin.reason());
				connection.commit();
			}
			System.out.println("Run party membership PASS");
		} finally { cleanup(); }
	}

	private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

	private static int disconnects(java.sql.Connection connection, UUID runId) throws Exception {
		try (var query = connection.prepareStatement("SELECT COUNT(*) FROM dalmaru.run_member_lifecycle WHERE run_id=? AND disconnect_at IS NOT NULL")) {
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
			try (var member = connection.prepareStatement("INSERT INTO dalmaru.run_participants(run_id,character_id) VALUES(?,?)")) {
				member.setObject(1, runId);
				member.setObject(2, characterId);
				member.executeUpdate();
			}
		}
	}

	private static void migrate() throws Exception {
		try (var connection = EnhancementReplayCheck.connection(); var check = connection.prepareStatement("SELECT 1 FROM dalmaru.schema_migrations WHERE version=40")) {
			try (var result = check.executeQuery()) { if (result.next()) return; }
		}
		String sql = Files.readString(Path.of("database", "migrations", "V040__run_member_lifecycle.sql"));
		for (String statement : sql.split(";")) {
			String trimmed = statement.trim();
			if (trimmed.isEmpty()) continue;
			try (var connection = EnhancementReplayCheck.connection(); var command = connection.createStatement()) { command.execute(trimmed); }
		}
	}

	private static void cleanup() throws Exception {
		if (System.getenv("DALMARU_DB_PASSWORD") == null || System.getenv("DALMARU_DB_PASSWORD").isBlank()) return;
		try (var connection = EnhancementReplayCheck.connection()) {
			for (String sql : new String[] {
				"DELETE FROM dalmaru.run_party_invites WHERE run_id IN (?, ?)",
				"DELETE FROM dalmaru.run_member_lifecycle WHERE run_id IN (?, ?)",
				"DELETE FROM dalmaru.run_party_control WHERE run_id IN (?, ?)",
				"DELETE FROM dalmaru.run_participants WHERE run_id IN (?, ?)",
				"DELETE FROM dalmaru.run_instances WHERE run_id IN (?, ?)",
				"UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id IN (?, ?)",
				"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id IN (?, ?))",
				"DELETE FROM dalmaru.characters WHERE account_id IN (?, ?)",
				"DELETE FROM dalmaru.accounts WHERE account_id IN (?, ?)"
			}) {
				try (var statement = connection.prepareStatement(sql)) {
					statement.setObject(1, sql.contains("account_id") ? LEADER_ACCOUNT : RUN);
					statement.setObject(2, sql.contains("account_id") ? OTHER_ACCOUNT : OTHER_RUN);
					statement.executeUpdate();
				}
			}
		}
	}
}
