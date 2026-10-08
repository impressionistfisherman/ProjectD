package kr.dalmaru;

import java.sql.DriverManager;
import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterProfession;
import kr.dalmaru.account.CharacterRepository;

public final class CharacterProfessionCheck {
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000009");
	private CharacterProfessionCheck() { }

	public static void main(String[] args) throws Exception {
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		try {
			var session = accounts.openSession(ACCOUNT_ID);
			var character = characters.create(ACCOUNT_ID, 1, "달마루_전직시험9");
			characters.selectActive(ACCOUNT_ID, session.connectionGeneration(), 901, character.characterId());
			characters.grantExperience(character.characterId(), 2160);
			completeProfessionQuest(character.characterId());
			characters.selectProfession(ACCOUNT_ID, session.connectionGeneration(), 902, character.characterId(), CharacterProfession.WARRIOR);
			if (characters.profile(character.characterId()).unspentSkillPoints() != 6) throw new IllegalStateException("Initial profession reward was not saved.");
			characters.selectProfession(ACCOUNT_ID, session.connectionGeneration(), 902, character.characterId(), CharacterProfession.WARRIOR);
			if (characters.profile(character.characterId()).unspentSkillPoints() != 6) throw new IllegalStateException("Profession reward was granted more than once.");
			System.out.println("Initial profession skill-point reward verified.");
		} finally {
			cleanup();
		}
	}

	private static void completeProfessionQuest(UUID characterId) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("INSERT INTO dalmaru.character_quest_progress (character_id, quest_id, content_version, state, progress_count, claimed_at) VALUES (?, 'pilot1.zombie_hunt', 'pilot-1', 'REWARDED', 3, CURRENT_TIMESTAMP)")) {
			statement.setObject(1, characterId);
			statement.executeUpdate();
		}
	}

	private static void cleanup() throws Exception {
		if (System.getenv("DALMARU_DB_PASSWORD") == null || System.getenv("DALMARU_DB_PASSWORD").isBlank()) return;
		try (var connection = connection()) {
			for (String sql : new String[] {
				"UPDATE dalmaru.accounts SET active_character_id = NULL WHERE account_id = ?",
				"DELETE FROM dalmaru.character_selection_requests WHERE account_id = ?",
				"DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id = ?)",
				"DELETE FROM dalmaru.characters WHERE account_id = ?",
				"DELETE FROM dalmaru.accounts WHERE account_id = ?"
			}) try (var statement = connection.prepareStatement(sql)) {
				statement.setObject(1, ACCOUNT_ID);
				statement.executeUpdate();
			}
		}
	}

	private static java.sql.Connection connection() throws Exception {
		Class.forName("org.postgresql.Driver");
		return DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}
}
