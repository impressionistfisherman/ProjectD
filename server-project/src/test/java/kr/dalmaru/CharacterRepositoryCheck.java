package kr.dalmaru;

import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;

public final class CharacterRepositoryCheck {
	private CharacterRepositoryCheck() { }

	public static void main(String[] args) throws Exception {
		UUID accountId = UUID.fromString("00000000-0000-0000-0000-000000000003");
		cleanup(accountId);
		try {
		new AccountRepository().openSession(accountId);
		var character = new CharacterRepository().create(accountId, 1, "달마루_시험3");
		if (!character.normalizedName().equals("달마루_시험3")) throw new IllegalStateException("Name was not preserved.");
		try {
			new CharacterRepository().create(accountId, 5, "잘못된슬롯");
			throw new IllegalStateException("Invalid slot was accepted.");
		} catch (IllegalArgumentException expected) { }
		var repository = new CharacterRepository();
		if (repository.list(accountId).size() != 1) throw new IllegalStateException("Character list was not persisted.");
		repository.selectActive(accountId, character.characterId());
		System.out.println("Character creation verified for slot " + character.slot());
		} finally { cleanup(accountId); }
	}

	private static void cleanup(UUID accountId) throws Exception {
		if (System.getenv("DALMARU_DB_PASSWORD") == null || System.getenv("DALMARU_DB_PASSWORD").isBlank()) return;
		try (var connection = java.sql.DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"))) {
			for (String sql : new String[] { "UPDATE dalmaru.accounts SET active_character_id = NULL WHERE account_id = ?", "DELETE FROM dalmaru.character_quest_progress WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id = ?)", "DELETE FROM dalmaru.character_professions WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id = ?)", "DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id = ?)", "DELETE FROM dalmaru.characters WHERE account_id = ?", "DELETE FROM dalmaru.accounts WHERE account_id = ?" }) {
				try (var statement = connection.prepareStatement(sql)) { statement.setObject(1, accountId); statement.executeUpdate(); }
			}
		}
	}
}
