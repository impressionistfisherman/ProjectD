package kr.dalmaru;

import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;

public final class CharacterExperienceCheck {
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
	private CharacterExperienceCheck() { }

	public static void main(String[] args) throws Exception {
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		try {
			var session = accounts.openSession(ACCOUNT_ID);
			var character = characters.create(ACCOUNT_ID, 1, "달마루_경험치시험7");
			characters.selectActive(ACCOUNT_ID, session.connectionGeneration(), 701, character.characterId());

			var firstLevel = characters.grantExperience(character.characterId(), 100);
			if (firstLevel.level() != 2 || firstLevel.experience() != 0 || firstLevel.unspentStatPoints() != 5) {
				throw new IllegalStateException("Level 1 experience reward was not saved correctly.");
			}

			var secondLevel = characters.grantExperience(character.characterId(), 170);
			if (secondLevel.level() != 3 || secondLevel.experience() != 35 || secondLevel.unspentStatPoints() != 10) {
				throw new IllegalStateException("Experience carry-over or level reward was not saved correctly.");
			}
			System.out.println("Character experience and level-up rewards verified.");
		} finally {
			cleanup();
		}
	}

	private static void cleanup() throws Exception {
		if (System.getenv("DALMARU_DB_PASSWORD") == null || System.getenv("DALMARU_DB_PASSWORD").isBlank()) return;
		try (var connection = connection()) {
			for (String sql : new String[] {
				"UPDATE dalmaru.accounts SET active_character_id = NULL WHERE account_id = ?",
				"DELETE FROM dalmaru.character_stat_investment_requests WHERE account_id = ?",
				"DELETE FROM dalmaru.character_stat_reset_requests WHERE account_id = ?",
				"DELETE FROM dalmaru.character_selection_requests WHERE account_id = ?",
				"DELETE FROM dalmaru.character_creation_requests WHERE account_id = ?",
				"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id = ?)",
				"DELETE FROM dalmaru.characters WHERE account_id = ?",
				"DELETE FROM dalmaru.accounts WHERE account_id = ?"
			}) {
				try (var statement = connection.prepareStatement(sql)) {
					statement.setObject(1, ACCOUNT_ID);
					statement.executeUpdate();
				}
			}
		}
	}

	private static java.sql.Connection connection() throws Exception {
		Class.forName("org.postgresql.Driver");
		return java.sql.DriverManager.getConnection(
			"jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD"),
			System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"),
			System.getenv("DALMARU_DB_PASSWORD"));
	}
}
