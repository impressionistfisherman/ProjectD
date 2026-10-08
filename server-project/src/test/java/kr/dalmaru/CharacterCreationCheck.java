package kr.dalmaru;

import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;

public final class CharacterCreationCheck {
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
	private CharacterCreationCheck() { }

	public static void main(String[] args) throws Exception {
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		try {
			var session = accounts.openSession(ACCOUNT_ID);
			var created = characters.create(ACCOUNT_ID, session.connectionGeneration(), 201, 1, "달마루_생성시험5");
			if (created.replayed()) throw new IllegalStateException("Initial creation was treated as replay.");
			var profile = characters.profile(created.character().characterId());
			if (profile.level() != 1 || profile.experience() != 0 || profile.unspentStatPoints() != 0 || profile.strength() != 0 || profile.dexterity() != 0 || profile.arcana() != 0 || profile.vitality() != 0 || profile.spirit() != 0 || !profile.freeResetAvailable()) throw new IllegalStateException("Starting profile was not initialized.");
			var replayed = characters.create(ACCOUNT_ID, session.connectionGeneration(), 201, 1, "달마루_생성시험5");
			if (!replayed.replayed() || !created.character().characterId().equals(replayed.character().characterId())) throw new IllegalStateException("Repeated creation did not return its original result.");
			try {
				characters.create(ACCOUNT_ID, session.connectionGeneration(), 201, 2, "달마루_생성시험5B");
				throw new IllegalStateException("Changed creation request was accepted.");
			} catch (IllegalArgumentException expected) { }
			try {
				characters.create(ACCOUNT_ID, session.connectionGeneration() - 1, 202, 2, "달마루_생성시험5B");
				throw new IllegalStateException("Stale creation generation was accepted.");
			} catch (IllegalArgumentException expected) { }
			System.out.println("Character creation idempotency verified.");
		} finally {
			cleanup();
		}
	}

	private static void cleanup() throws Exception {
		String password = System.getenv("DALMARU_DB_PASSWORD");
		if (password == null || password.isBlank()) return;
		Class.forName("org.postgresql.Driver");
		try (var connection = java.sql.DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), password)) {
			for (String sql : new String[] { "UPDATE dalmaru.accounts SET active_character_id = NULL WHERE account_id = ?", "DELETE FROM dalmaru.character_creation_requests WHERE account_id = ?", "DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id = ?)", "DELETE FROM dalmaru.characters WHERE account_id = ?", "DELETE FROM dalmaru.accounts WHERE account_id = ?" }) {
				try (var statement = connection.prepareStatement(sql)) { statement.setObject(1, ACCOUNT_ID); statement.executeUpdate(); }
			}
		}
	}
}
