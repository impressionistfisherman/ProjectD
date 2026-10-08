package kr.dalmaru.skill;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import kr.dalmaru.account.CharacterProfession;

public final class SkillRepository {
	private final String url;
	private final String username;
	private final String password;

	public SkillRepository() {
		String host = System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost");
		String port = System.getenv().getOrDefault("DALMARU_DB_PORT", "15432");
		url = "jdbc:postgresql://" + host + ":" + port + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
		username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
		password = System.getenv("DALMARU_DB_PASSWORD");
	}

	public int learn(UUID accountId, long connectionGeneration, long requestId, UUID characterId, CharacterProfession profession, String skillId) throws SQLException {
		if (connectionGeneration < 1 || requestId < 1 || password == null || password.isBlank()) throw new IllegalArgumentException("Invalid skill learning request.");
		SkillDefinition skill = SkillDefinition.find(profession, skillId);
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try {
				try (var account = connection.prepareStatement("SELECT active_character_id FROM dalmaru.accounts WHERE account_id = ? AND connection_generation = ? FOR UPDATE")) {
					account.setObject(1, accountId); account.setLong(2, connectionGeneration);
					try (var result = account.executeQuery()) { if (!result.next() || !characterId.equals(result.getObject(1, UUID.class))) throw new IllegalArgumentException("Active character does not match this request."); }
				}
				try (var existing = connection.prepareStatement("SELECT character_id, skill_id, rank FROM dalmaru.character_skill_learning_requests WHERE account_id = ? AND connection_generation = ? AND request_id = ?")) {
					existing.setObject(1, accountId); existing.setLong(2, connectionGeneration); existing.setLong(3, requestId);
					try (var result = existing.executeQuery()) { if (result.next()) { if (!characterId.equals(result.getObject(1, UUID.class)) || !skill.id().equals(result.getString(2))) throw new IllegalArgumentException("Request ID was reused for a different skill."); connection.commit(); return result.getInt(3); } }
				}
				try (var profile = connection.prepareStatement("SELECT unspent_skill_points FROM dalmaru.character_profiles WHERE character_id = ? FOR UPDATE")) {
					profile.setObject(1, characterId);
					try (var result = profile.executeQuery()) { if (!result.next() || result.getInt(1) < 1) throw new IllegalArgumentException("Not enough skill points."); }
				}
				int rank;
				try (var current = connection.prepareStatement("SELECT rank FROM dalmaru.character_skill_ranks WHERE character_id = ? AND skill_id = ? FOR UPDATE")) {
					current.setObject(1, characterId); current.setString(2, skill.id());
					try (var result = current.executeQuery()) { rank = result.next() ? result.getInt(1) : 0; }
				}
				if (rank >= 5) throw new IllegalArgumentException("Skill is already at maximum rank.");
				rank++;
				try (var update = connection.prepareStatement("UPDATE dalmaru.character_profiles SET unspent_skill_points = unspent_skill_points - 1 WHERE character_id = ?")) { update.setObject(1, characterId); update.executeUpdate(); }
				try (var update = connection.prepareStatement("INSERT INTO dalmaru.character_skill_ranks (character_id, skill_id, rank) VALUES (?, ?, ?) ON CONFLICT (character_id, skill_id) DO UPDATE SET rank = EXCLUDED.rank")) { update.setObject(1, characterId); update.setString(2, skill.id()); update.setInt(3, rank); update.executeUpdate(); }
				try (var request = connection.prepareStatement("INSERT INTO dalmaru.character_skill_learning_requests (account_id, connection_generation, request_id, character_id, skill_id, rank) VALUES (?, ?, ?, ?, ?, ?)")) { request.setObject(1, accountId); request.setLong(2, connectionGeneration); request.setLong(3, requestId); request.setObject(4, characterId); request.setString(5, skill.id()); request.setInt(6, rank); request.executeUpdate(); }
				connection.commit();
				return rank;
			} catch (Exception exception) { connection.rollback(); throw exception; }
		}
	}

	public Map<String, Integer> ranks(UUID characterId) throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.prepareStatement("SELECT skill_id, rank FROM dalmaru.character_skill_ranks WHERE character_id = ?")) {
			statement.setObject(1, characterId);
			try (var result = statement.executeQuery()) { Map<String, Integer> ranks = new HashMap<>(); while (result.next()) ranks.put(result.getString(1), result.getInt(2)); return ranks; }
		}
	}
}
