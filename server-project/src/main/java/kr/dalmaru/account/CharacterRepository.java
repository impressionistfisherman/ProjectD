package kr.dalmaru.account;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

public final class CharacterRepository {
	static {
		try {
			Class.forName("org.postgresql.Driver");
		} catch (ClassNotFoundException exception) {
			throw new ExceptionInInitializerError(exception);
		}
	}

	private static final Pattern ALLOWED_NAME = Pattern.compile("[가-힣A-Za-z0-9_]+");
	private final String url;
	private final String username;
	private final String password;

	public CharacterRepository() {
		String host = System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost");
		String port = System.getenv().getOrDefault("DALMARU_DB_PORT", "15432");
		String database = System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
		url = "jdbc:postgresql://" + host + ":" + port + "/" + database;
		username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
		password = System.getenv("DALMARU_DB_PASSWORD");
	}

	public CharacterRecord create(UUID accountId, int slot, String displayName) throws SQLException {
		if (slot < 1 || slot > 4) throw new IllegalArgumentException("Character slot must be between 1 and 4.");
		String normalizedName = Normalizer.normalize(displayName, Normalizer.Form.NFKC);
		if (!ALLOWED_NAME.matcher(normalizedName).matches()) throw new IllegalArgumentException("Invalid character name.");
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		UUID characterId = UUID.randomUUID();
		String sql = "INSERT INTO dalmaru.characters (character_id, account_id, slot, display_name, normalized_name) VALUES (?, ?, ?, ?, ?)";
		try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.prepareStatement(sql)) {
			statement.setObject(1, characterId);
			statement.setObject(2, accountId);
			statement.setInt(3, slot);
			statement.setString(4, displayName);
			statement.setString(5, normalizedName);
			statement.executeUpdate();
			insertStartingProfile(connection, characterId);
			return new CharacterRecord(characterId, accountId, slot, displayName, normalizedName);
		}
	}

	public CharacterProfile profile(UUID characterId) throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		String sql = "SELECT p.character_id, p.level, p.experience, p.unspent_stat_points, p.strength, p.dexterity, p.arcana, p.vitality, p.spirit, p.free_reset_available, p.unspent_skill_points, COALESCE(j.base_job, '') FROM dalmaru.character_profiles p LEFT JOIN dalmaru.character_professions j ON j.character_id = p.character_id WHERE p.character_id = ?";
		try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.prepareStatement(sql)) {
			statement.setObject(1, characterId);
			try (var result = statement.executeQuery()) {
				if (!result.next()) throw new IllegalArgumentException("Character profile was not found.");
				return new CharacterProfile(result.getObject(1, UUID.class), result.getInt(2), result.getLong(3), result.getInt(4), result.getInt(5), result.getInt(6), result.getInt(7), result.getInt(8), result.getInt(9), result.getBoolean(10), result.getInt(11), result.getString(12));
			}
		}
	}

	public CharacterStatInvestmentResult investStat(UUID accountId, long connectionGeneration, long requestId, UUID characterId, CharacterStat stat, int amount) throws SQLException {
		if (connectionGeneration < 1 || requestId < 1 || amount < 1) throw new IllegalArgumentException("Invalid stat investment request.");
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try {
				String accountSql = "SELECT active_character_id FROM dalmaru.accounts WHERE account_id = ? AND connection_generation = ? FOR UPDATE";
				try (var statement = connection.prepareStatement(accountSql)) {
					statement.setObject(1, accountId); statement.setLong(2, connectionGeneration);
					try (var result = statement.executeQuery()) {
						if (!result.next() || !characterId.equals(result.getObject(1, UUID.class))) throw new IllegalArgumentException("Active character does not match this request.");
					}
				}
				String existingSql = "SELECT character_id, stat_id, amount, level, experience, unspent_stat_points, strength, dexterity, arcana, vitality, spirit, free_reset_available FROM dalmaru.character_stat_investment_requests WHERE account_id = ? AND connection_generation = ? AND request_id = ?";
				try (var statement = connection.prepareStatement(existingSql)) {
					statement.setObject(1, accountId); statement.setLong(2, connectionGeneration); statement.setLong(3, requestId);
					try (var result = statement.executeQuery()) {
						if (result.next()) {
							if (!characterId.equals(result.getObject(1, UUID.class)) || result.getInt(2) != stat.id() || result.getInt(3) != amount) throw new IllegalArgumentException("Request ID was reused for a different stat investment.");
							CharacterProfile profile = new CharacterProfile(characterId, result.getInt(4), result.getLong(5), result.getInt(6), result.getInt(7), result.getInt(8), result.getInt(9), result.getInt(10), result.getInt(11), result.getBoolean(12));
							connection.commit();
							return new CharacterStatInvestmentResult(profile, true);
						}
					}
				}
				CharacterProfile before = profileForUpdate(connection, characterId);
				if (amount > before.unspentStatPoints()) throw new IllegalArgumentException("Not enough unspent stat points.");
				String column = switch (stat) {
					case STRENGTH -> "strength";
					case DEXTERITY -> "dexterity";
					case ARCANA -> "arcana";
					case VITALITY -> "vitality";
					case SPIRIT -> "spirit";
				};
				try (var statement = connection.prepareStatement("UPDATE dalmaru.character_profiles SET " + column + " = " + column + " + ?, unspent_stat_points = unspent_stat_points - ? WHERE character_id = ?")) {
					statement.setInt(1, amount); statement.setInt(2, amount); statement.setObject(3, characterId);
					if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Character profile was not found.");
				}
				CharacterProfile after = profileForUpdate(connection, characterId);
				String requestSql = "INSERT INTO dalmaru.character_stat_investment_requests (account_id, connection_generation, request_id, character_id, stat_id, amount, level, experience, unspent_stat_points, strength, dexterity, arcana, vitality, spirit, free_reset_available) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
				try (var statement = connection.prepareStatement(requestSql)) {
					statement.setObject(1, accountId); statement.setLong(2, connectionGeneration); statement.setLong(3, requestId); statement.setObject(4, characterId); statement.setInt(5, stat.id()); statement.setInt(6, amount);
					statement.setInt(7, after.level()); statement.setLong(8, after.experience()); statement.setInt(9, after.unspentStatPoints()); statement.setInt(10, after.strength()); statement.setInt(11, after.dexterity()); statement.setInt(12, after.arcana()); statement.setInt(13, after.vitality()); statement.setInt(14, after.spirit()); statement.setBoolean(15, after.freeResetAvailable());
					statement.executeUpdate();
				}
				connection.commit();
				return new CharacterStatInvestmentResult(after, false);
			} catch (Exception exception) {
				connection.rollback();
				throw exception;
			}
		}
	}

	public CharacterStatResetResult resetStats(UUID accountId, long connectionGeneration, long requestId, UUID characterId) throws SQLException {
		if (connectionGeneration < 1 || requestId < 1) throw new IllegalArgumentException("Invalid stat reset request.");
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try {
				String accountSql = "SELECT active_character_id FROM dalmaru.accounts WHERE account_id = ? AND connection_generation = ? FOR UPDATE";
				try (var statement = connection.prepareStatement(accountSql)) {
					statement.setObject(1, accountId); statement.setLong(2, connectionGeneration);
					try (var result = statement.executeQuery()) {
						if (!result.next() || !characterId.equals(result.getObject(1, UUID.class))) throw new IllegalArgumentException("Active character does not match this request.");
					}
				}
				String existingSql = "SELECT character_id, level, experience, unspent_stat_points, strength, dexterity, arcana, vitality, spirit, free_reset_available FROM dalmaru.character_stat_reset_requests WHERE account_id = ? AND connection_generation = ? AND request_id = ?";
				try (var statement = connection.prepareStatement(existingSql)) {
					statement.setObject(1, accountId); statement.setLong(2, connectionGeneration); statement.setLong(3, requestId);
					try (var result = statement.executeQuery()) {
						if (result.next()) {
							if (!characterId.equals(result.getObject(1, UUID.class))) throw new IllegalArgumentException("Request ID was reused for a different stat reset.");
							CharacterProfile profile = new CharacterProfile(characterId, result.getInt(2), result.getLong(3), result.getInt(4), result.getInt(5), result.getInt(6), result.getInt(7), result.getInt(8), result.getInt(9), result.getBoolean(10));
							connection.commit();
							return new CharacterStatResetResult(profile, true);
						}
					}
				}
				CharacterProfile before = profileForUpdate(connection, characterId);
				if (!before.freeResetAvailable()) throw new IllegalArgumentException("Free stat reset was already used.");
				int refunded = before.strength() + before.dexterity() + before.arcana() + before.vitality() + before.spirit();
				try (var statement = connection.prepareStatement("UPDATE dalmaru.character_profiles SET unspent_stat_points = unspent_stat_points + ?, strength = 0, dexterity = 0, arcana = 0, vitality = 0, spirit = 0, free_reset_available = FALSE WHERE character_id = ?")) {
					statement.setInt(1, refunded); statement.setObject(2, characterId);
					if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Character profile was not found.");
				}
				CharacterProfile after = profileForUpdate(connection, characterId);
				String requestSql = "INSERT INTO dalmaru.character_stat_reset_requests (account_id, connection_generation, request_id, character_id, level, experience, unspent_stat_points, strength, dexterity, arcana, vitality, spirit, free_reset_available) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
				try (var statement = connection.prepareStatement(requestSql)) {
					statement.setObject(1, accountId); statement.setLong(2, connectionGeneration); statement.setLong(3, requestId); statement.setObject(4, characterId); statement.setInt(5, after.level()); statement.setLong(6, after.experience()); statement.setInt(7, after.unspentStatPoints()); statement.setInt(8, after.strength()); statement.setInt(9, after.dexterity()); statement.setInt(10, after.arcana()); statement.setInt(11, after.vitality()); statement.setInt(12, after.spirit()); statement.setBoolean(13, after.freeResetAvailable());
					statement.executeUpdate();
				}
				connection.commit();
				return new CharacterStatResetResult(after, false);
			} catch (Exception exception) {
				connection.rollback();
				throw exception;
			}
		}
	}

	public CharacterProfile grantExperience(UUID characterId, long amount) throws SQLException {
		if (amount < 1) throw new IllegalArgumentException("Experience amount must be positive.");
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try {
				CharacterProfile before = profileForUpdate(connection, characterId);
				int level = before.level();
				long experience = Math.addExact(before.experience(), amount);
				int points = before.unspentStatPoints();
				while (level < 60 && experience >= experienceNeededForNextLevel(level)) {
					experience -= experienceNeededForNextLevel(level);
					level++;
					points += 5;
				}
				if (level == 60) experience = 0;
				try (var statement = connection.prepareStatement("UPDATE dalmaru.character_profiles SET level = ?, experience = ?, unspent_stat_points = ? WHERE character_id = ?")) {
					statement.setInt(1, level); statement.setLong(2, experience); statement.setInt(3, points); statement.setObject(4, characterId);
					statement.executeUpdate();
				}
				connection.commit();
				return profile(characterId);
			} catch (Exception exception) {
				connection.rollback();
				throw exception;
			}
		}
	}

	public static long experienceNeededForNextLevel(int level) {
		if (level < 1 || level >= 60) throw new IllegalArgumentException("Level does not have a next level.");
		return 100L + 35L * (level - 1);
	}

	public List<CharacterRecord> list(UUID accountId) throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		String sql = "SELECT character_id, account_id, slot, display_name, normalized_name FROM dalmaru.characters WHERE account_id = ? ORDER BY slot";
		try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.prepareStatement(sql)) {
			statement.setObject(1, accountId);
			try (var result = statement.executeQuery()) {
				List<CharacterRecord> characters = new ArrayList<>();
				while (result.next()) characters.add(new CharacterRecord((UUID) result.getObject(1), (UUID) result.getObject(2), result.getInt(3), result.getString(4), result.getString(5)));
				return characters;
			}
		}
	}

	public void selectActive(UUID accountId, UUID characterId) throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		String sql = "UPDATE dalmaru.accounts SET active_character_id = ? WHERE account_id = ? AND EXISTS (SELECT 1 FROM dalmaru.characters WHERE character_id = ? AND account_id = ?)";
		try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.prepareStatement(sql)) {
			statement.setObject(1, characterId); statement.setObject(2, accountId); statement.setObject(3, characterId); statement.setObject(4, accountId);
			if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Character does not belong to this account.");
		}
	}

	public CharacterSelectionResult selectActive(UUID accountId, long connectionGeneration, long requestId, UUID characterId) throws SQLException {
		if (connectionGeneration < 1 || requestId < 1) throw new IllegalArgumentException("Invalid character selection request.");
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try {
				String insert = "INSERT INTO dalmaru.character_selection_requests (account_id, connection_generation, request_id, character_id) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING character_id";
				try (var statement = connection.prepareStatement(insert)) {
					statement.setObject(1, accountId); statement.setLong(2, connectionGeneration); statement.setLong(3, requestId); statement.setObject(4, characterId);
					try (var result = statement.executeQuery()) {
						if (!result.next()) {
							String existing = "SELECT character_id FROM dalmaru.character_selection_requests WHERE account_id = ? AND connection_generation = ? AND request_id = ?";
							try (var existingStatement = connection.prepareStatement(existing)) {
								existingStatement.setObject(1, accountId); existingStatement.setLong(2, connectionGeneration); existingStatement.setLong(3, requestId);
								try (var existingResult = existingStatement.executeQuery()) {
									existingResult.next();
									if (!characterId.equals(existingResult.getObject(1, UUID.class))) throw new IllegalArgumentException("Request ID was reused for a different character.");
								}
							}
							connection.commit();
							return new CharacterSelectionResult(characterId, true);
						}
					}
				}
				String update = "UPDATE dalmaru.accounts SET active_character_id = ? WHERE account_id = ? AND connection_generation = ? AND EXISTS (SELECT 1 FROM dalmaru.characters WHERE character_id = ? AND account_id = ?)";
				try (var statement = connection.prepareStatement(update)) {
					statement.setObject(1, characterId); statement.setObject(2, accountId); statement.setLong(3, connectionGeneration); statement.setObject(4, characterId); statement.setObject(5, accountId);
					if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Character selection was rejected.");
				}
				connection.commit();
				return new CharacterSelectionResult(characterId, false);
			} catch (Exception exception) {
				connection.rollback();
				throw exception;
			}
		}
	}

	public CharacterCreationResult create(UUID accountId, long connectionGeneration, long requestId, int slot, String displayName) throws SQLException {
		if (connectionGeneration < 1 || requestId < 1 || slot < 1 || slot > 4) throw new IllegalArgumentException("Invalid character creation request.");
		String normalizedName = Normalizer.normalize(displayName, Normalizer.Form.NFKC);
		if (!ALLOWED_NAME.matcher(normalizedName).matches()) throw new IllegalArgumentException("Invalid character name.");
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		UUID characterId = UUID.randomUUID();
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try {
				String requestSql = "INSERT INTO dalmaru.character_creation_requests (account_id, connection_generation, request_id, slot, display_name, normalized_name, character_id) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING character_id";
				try (var statement = connection.prepareStatement(requestSql)) {
					statement.setObject(1, accountId); statement.setLong(2, connectionGeneration); statement.setLong(3, requestId); statement.setInt(4, slot); statement.setString(5, displayName); statement.setString(6, normalizedName); statement.setObject(7, characterId);
					try (var result = statement.executeQuery()) {
						if (!result.next()) {
							String existingSql = "SELECT slot, display_name, normalized_name, character_id FROM dalmaru.character_creation_requests WHERE account_id = ? AND connection_generation = ? AND request_id = ?";
							try (var existing = connection.prepareStatement(existingSql)) {
								existing.setObject(1, accountId); existing.setLong(2, connectionGeneration); existing.setLong(3, requestId);
								try (var existingResult = existing.executeQuery()) {
									existingResult.next();
									UUID existingId = existingResult.getObject(4, UUID.class);
									if (existingResult.getInt(1) != slot || !existingResult.getString(2).equals(displayName) || !existingResult.getString(3).equals(normalizedName)) throw new IllegalArgumentException("Request ID was reused for a different character.");
									connection.commit();
									return new CharacterCreationResult(new CharacterRecord(existingId, accountId, slot, displayName, normalizedName), true);
								}
							}
						}
					}
				}
				String characterSql = "INSERT INTO dalmaru.characters (character_id, account_id, slot, display_name, normalized_name) VALUES (?, ?, ?, ?, ?)";
				try (var statement = connection.prepareStatement(characterSql)) {
					statement.setObject(1, characterId); statement.setObject(2, accountId); statement.setInt(3, slot); statement.setString(4, displayName); statement.setString(5, normalizedName);
					statement.executeUpdate();
				}
				insertStartingProfile(connection, characterId);
				connection.commit();
				return new CharacterCreationResult(new CharacterRecord(characterId, accountId, slot, displayName, normalizedName), false);
			} catch (Exception exception) {
				connection.rollback();
				throw exception;
			}
		}
	}

	public CharacterProfession selectProfession(UUID accountId, long connectionGeneration, long requestId, UUID characterId, CharacterProfession profession) throws SQLException {
		if (connectionGeneration < 1 || requestId < 1) throw new IllegalArgumentException("Invalid profession request.");
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try {
				try (var account = connection.prepareStatement("SELECT active_character_id FROM dalmaru.accounts WHERE account_id = ? AND connection_generation = ? FOR UPDATE")) { account.setObject(1, accountId); account.setLong(2, connectionGeneration); try (var result = account.executeQuery()) { if (!result.next() || !characterId.equals(result.getObject(1, UUID.class))) throw new IllegalArgumentException("Active character does not match this request."); } }
				try (var profile = connection.prepareStatement("SELECT level FROM dalmaru.character_profiles WHERE character_id = ? FOR UPDATE")) { profile.setObject(1, characterId); try (var result = profile.executeQuery()) { if (!result.next() || result.getInt(1) < 10) throw new IllegalArgumentException("Level 10 is required for a profession."); } }
				try (var quest = connection.prepareStatement("SELECT 1 FROM dalmaru.character_quest_progress WHERE character_id = ? AND quest_id = 'pilot1.zombie_hunt' AND content_version = 'pilot-1' AND state = 'REWARDED'")) { quest.setObject(1, characterId); try (var result = quest.executeQuery()) { if (!result.next()) throw new IllegalArgumentException("Profession quest is not complete."); } }
				try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_professions (character_id, base_job) VALUES (?, ?) ON CONFLICT (character_id) DO NOTHING")) { insert.setObject(1, characterId); insert.setString(2, profession.name()); if (insert.executeUpdate() == 1) try (var reward = connection.prepareStatement("UPDATE dalmaru.character_profiles SET unspent_skill_points = unspent_skill_points + 6 WHERE character_id = ?")) { reward.setObject(1, characterId); reward.executeUpdate(); } }
				try (var selected = connection.prepareStatement("SELECT base_job FROM dalmaru.character_professions WHERE character_id = ?")) { selected.setObject(1, characterId); try (var result = selected.executeQuery()) { result.next(); CharacterProfession saved = CharacterProfession.valueOf(result.getString(1)); if (saved != profession) throw new IllegalArgumentException("Profession was already selected."); connection.commit(); return saved; } }
			} catch (Exception exception) { connection.rollback(); throw exception; }
		}
	}

	public CharacterProfession profession(UUID characterId) throws SQLException {
		try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.prepareStatement("SELECT base_job FROM dalmaru.character_professions WHERE character_id = ?")) {
			statement.setObject(1, characterId);
			try (var result = statement.executeQuery()) { return result.next() ? CharacterProfession.valueOf(result.getString(1)) : null; }
		}
	}

	private static void insertStartingProfile(java.sql.Connection connection, UUID characterId) throws SQLException {
		try (var statement = connection.prepareStatement("INSERT INTO dalmaru.character_profiles (character_id) VALUES (?)")) {
			statement.setObject(1, characterId);
			statement.executeUpdate();
		}
	}

	private static CharacterProfile profileForUpdate(java.sql.Connection connection, UUID characterId) throws SQLException {
		String sql = "SELECT character_id, level, experience, unspent_stat_points, strength, dexterity, arcana, vitality, spirit, free_reset_available FROM dalmaru.character_profiles WHERE character_id = ? FOR UPDATE";
		try (var statement = connection.prepareStatement(sql)) {
			statement.setObject(1, characterId);
			try (var result = statement.executeQuery()) {
				if (!result.next()) throw new IllegalArgumentException("Character profile was not found.");
				return new CharacterProfile(result.getObject(1, UUID.class), result.getInt(2), result.getLong(3), result.getInt(4), result.getInt(5), result.getInt(6), result.getInt(7), result.getInt(8), result.getInt(9), result.getBoolean(10));
			}
		}
	}
}
