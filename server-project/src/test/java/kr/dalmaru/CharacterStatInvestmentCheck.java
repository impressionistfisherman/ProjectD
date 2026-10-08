package kr.dalmaru;

import java.util.UUID;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.account.CharacterStat;

public final class CharacterStatInvestmentCheck {
	private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
	private CharacterStatInvestmentCheck() { }

	public static void main(String[] args) throws Exception {
		var accounts = new AccountRepository();
		var characters = new CharacterRepository();
		try {
			var session = accounts.openSession(ACCOUNT_ID);
			var character = characters.create(ACCOUNT_ID, 1, "달마루_능력치시험6");
			characters.selectActive(ACCOUNT_ID, session.connectionGeneration(), 301, character.characterId());
			setUnspentPoints(character.characterId());
			var invested = characters.investStat(ACCOUNT_ID, session.connectionGeneration(), 401, character.characterId(), CharacterStat.STRENGTH, 2);
			if (invested.replayed() || invested.profile().strength() != 2 || invested.profile().unspentStatPoints() != 1) throw new IllegalStateException("Initial stat investment was not saved.");
			var replayed = characters.investStat(ACCOUNT_ID, session.connectionGeneration(), 401, character.characterId(), CharacterStat.STRENGTH, 2);
			if (!replayed.replayed() || replayed.profile().strength() != 2 || replayed.profile().unspentStatPoints() != 1) throw new IllegalStateException("Repeated stat investment did not return its saved outcome.");
			try {
				characters.investStat(ACCOUNT_ID, session.connectionGeneration(), 401, character.characterId(), CharacterStat.DEXTERITY, 1);
				throw new IllegalStateException("Changed stat investment request was accepted.");
			} catch (IllegalArgumentException expected) { }
			try {
				characters.investStat(ACCOUNT_ID, session.connectionGeneration(), 402, character.characterId(), CharacterStat.VITALITY, 2);
				throw new IllegalStateException("Investment beyond unspent points was accepted.");
			} catch (IllegalArgumentException expected) { }
			System.out.println("Character stat investment idempotency verified.");
		} finally {
			cleanup();
		}
	}

	private static void setUnspentPoints(UUID characterId) throws Exception {
		try (var connection = connection(); var statement = connection.prepareStatement("UPDATE dalmaru.character_profiles SET unspent_stat_points = 3 WHERE character_id = ?")) {
			statement.setObject(1, characterId);
			statement.executeUpdate();
		}
	}

	private static void cleanup() throws Exception {
		if (System.getenv("DALMARU_DB_PASSWORD") == null || System.getenv("DALMARU_DB_PASSWORD").isBlank()) return;
		try (var connection = connection()) {
			for (String sql : new String[] { "UPDATE dalmaru.accounts SET active_character_id = NULL WHERE account_id = ?", "DELETE FROM dalmaru.character_stat_investment_requests WHERE account_id = ?", "DELETE FROM dalmaru.character_selection_requests WHERE account_id = ?", "DELETE FROM dalmaru.character_profiles WHERE character_id IN (SELECT character_id FROM dalmaru.characters WHERE account_id = ?)", "DELETE FROM dalmaru.characters WHERE account_id = ?", "DELETE FROM dalmaru.accounts WHERE account_id = ?" }) {
				try (var statement = connection.prepareStatement(sql)) { statement.setObject(1, ACCOUNT_ID); statement.executeUpdate(); }
			}
		}
	}

	private static java.sql.Connection connection() throws Exception {
		Class.forName("org.postgresql.Driver");
		return java.sql.DriverManager.getConnection("jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD"), System.getenv().getOrDefault("DALMARU_DB_USER", "postgres"), System.getenv("DALMARU_DB_PASSWORD"));
	}
}
