package kr.dalmaru.quest;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import kr.dalmaru.account.CharacterProfile;
import kr.dalmaru.account.CharacterRepository;

/** First persisted pilot quest: accept, kill three zombies, receive 100 XP once. */
public final class QuestRepository {
	public static final String QUEST_ID = "pilot1.zombie_hunt";
	private static final String VERSION = "pilot-1";
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public String accept(UUID characterId) throws SQLException {
		try (var connection = connection(); var statement = connection.prepareStatement("INSERT INTO dalmaru.character_quest_progress (character_id, quest_id, content_version, state) VALUES (?, ?, ?, 'ACTIVE') ON CONFLICT (character_id, quest_id, content_version) DO NOTHING")) {
			statement.setObject(1, characterId); statement.setString(2, QUEST_ID); statement.setString(3, VERSION);
			return statement.executeUpdate() == 1 ? "좀비 토벌 의뢰를 수락했습니다. 좀비 3마리를 처치하세요." : status(characterId);
		}
	}

	public QuestReward recordZombieKill(UUID characterId) throws SQLException {
		try (var connection = connection()) {
			connection.setAutoCommit(false);
			try {
				int count;
				try (var statement = connection.prepareStatement("SELECT state, progress_count FROM dalmaru.character_quest_progress WHERE character_id = ? AND quest_id = ? AND content_version = ? FOR UPDATE")) {
					statement.setObject(1, characterId); statement.setString(2, QUEST_ID); statement.setString(3, VERSION);
					try (var result = statement.executeQuery()) { if (!result.next() || !"ACTIVE".equals(result.getString(1))) { connection.commit(); return null; } count = result.getInt(2) + 1; }
				}
				if (count < 3) { update(connection, characterId, count, "ACTIVE"); connection.commit(); return new QuestReward(null, "좀비 토벌 진행: " + count + "/3"); }
				update(connection, characterId, count, "REWARDED");
				CharacterProfile profile = grantExperience(connection, characterId, 100);
				connection.commit();
				return new QuestReward(profile, "좀비 토벌 완료! 경험치 100을 획득했습니다.");
			} catch (Exception exception) { connection.rollback(); throw exception; }
		}
	}

	public String status(UUID characterId) throws SQLException {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT state, progress_count FROM dalmaru.character_quest_progress WHERE character_id = ? AND quest_id = ? AND content_version = ?")) {
			statement.setObject(1, characterId); statement.setString(2, QUEST_ID); statement.setString(3, VERSION);
			try (var result = statement.executeQuery()) { return result.next() ? "좀비 토벌: " + result.getInt(2) + "/3 (" + result.getString(1) + ")" : "수락 가능한 의뢰: 좀비 토벌"; }
		}
	}

	private void update(java.sql.Connection connection, UUID id, int count, String state) throws SQLException { try (var statement = connection.prepareStatement("UPDATE dalmaru.character_quest_progress SET progress_count = ?, state = ?, claimed_at = CASE WHEN ? = 'REWARDED' THEN CURRENT_TIMESTAMP ELSE claimed_at END, updated_at = CURRENT_TIMESTAMP WHERE character_id = ? AND quest_id = ? AND content_version = ?")) { statement.setInt(1, count); statement.setString(2, state); statement.setString(3, state); statement.setObject(4, id); statement.setString(5, QUEST_ID); statement.setString(6, VERSION); statement.executeUpdate(); } }
	private CharacterProfile grantExperience(java.sql.Connection connection, UUID id, int amount) throws SQLException { return CharacterRepository.grantExperience(connection, id, amount); }
	private java.sql.Connection connection() throws SQLException { if(password==null||password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured."); return DriverManager.getConnection(url, username, password); }
	public record QuestReward(CharacterProfile profile, String message) { }
}
