package kr.dalmaru.quest;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import kr.dalmaru.account.CharacterProfile;
import kr.dalmaru.account.CharacterRepository;

/** First persisted pilot quest: accept, kill three zombies, receive 100 XP once. */
public final class QuestRepository {
	public static final String QUEST_ID = "pilot1.zombie_hunt";
	private static final kr.dalmaru.content.ContentCatalog.Quest DEFINITION=kr.dalmaru.content.ContentCatalog.current().quest(QUEST_ID);
    private static final String VERSION=DEFINITION.version();
	private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
	private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
	private final String password = System.getenv("DALMARU_DB_PASSWORD");

	public String accept(UUID characterId) throws SQLException {
        if(!DEFINITION.enabled())return "\uc758\ub8b0\uac00 \ube44\ud65c\uc131\ud654\ub418\uc5c8\uc2b5\ub2c8\ub2e4.";
        requirePrerequisites(characterId);
		try (var connection = connection(); var statement = connection.prepareStatement("INSERT INTO dalmaru.character_quest_progress (character_id, quest_id, content_version, state) VALUES (?, ?, ?, 'ACTIVE') ON CONFLICT (character_id, quest_id, content_version) DO NOTHING")) {
			statement.setObject(1, characterId); statement.setString(2, QUEST_ID); statement.setString(3, VERSION);
			return statement.executeUpdate() == 1 ? "\uc880\ube44 \ud1a0\ubc8c \uc758\ub8b0\ub97c \uc218\ub77d\ud588\uc2b5\ub2c8\ub2e4. \uc880\ube44 "+DEFINITION.goal()+"\ub9c8\ub9ac\ub97c \ucc98\uce58\ud558\uc138\uc694." : status(characterId);
		}
	}

	public QuestReward recordZombieKill(UUID characterId) throws SQLException {
        if(!DEFINITION.enabled())return null;
		try (var connection = connection()) {
			connection.setAutoCommit(false);
			try {
				int count;
				try (var statement = connection.prepareStatement("SELECT state, progress_count FROM dalmaru.character_quest_progress WHERE character_id = ? AND quest_id = ? AND content_version = ? FOR UPDATE")) {
					statement.setObject(1, characterId); statement.setString(2, QUEST_ID); statement.setString(3, VERSION);
					try (var result = statement.executeQuery()) { if (!result.next() || !"ACTIVE".equals(result.getString(1))) { connection.commit(); return null; } count = result.getInt(2) + 1; }
				}
				if (count < DEFINITION.goal()) { update(connection, characterId, count, "ACTIVE"); connection.commit(); return new QuestReward(null, "좀비 토벌 진행: " + count + "/" + DEFINITION.goal()); }
				update(connection, characterId, count, "REWARDED");
				CharacterProfile profile = DEFINITION.experience()==0?null:grantExperience(connection, characterId, DEFINITION.experience());
				connection.commit();
				return new QuestReward(profile, "\uc880\ube44 \ud1a0\ubc8c \uc644\ub8cc! \uacbd\ud5d8\uce58 "+DEFINITION.experience()+"\uc744 \ud68d\ub4dd\ud588\uc2b5\ub2c8\ub2e4.");
			} catch (Exception exception) { connection.rollback(); throw exception; }
		}
	}

	public String status(UUID characterId) throws SQLException {
		try (var connection = connection(); var statement = connection.prepareStatement("SELECT state, progress_count FROM dalmaru.character_quest_progress WHERE character_id = ? AND quest_id = ? AND content_version = ?")) {
			statement.setObject(1, characterId); statement.setString(2, QUEST_ID); statement.setString(3, VERSION);
            try (var result = statement.executeQuery()) { return result.next() ? "좀비 토벌: " + result.getInt(2) + "/" + DEFINITION.goal() + " (" + result.getString(1) + ")" : "수락 가능한 의뢰: 좀비 토벌"; }
		}
	}

	private void update(java.sql.Connection connection, UUID id, int count, String state) throws SQLException { try (var statement = connection.prepareStatement("UPDATE dalmaru.character_quest_progress SET progress_count = ?, state = ?, claimed_at = CASE WHEN ? = 'REWARDED' THEN CURRENT_TIMESTAMP ELSE claimed_at END, updated_at = CURRENT_TIMESTAMP WHERE character_id = ? AND quest_id = ? AND content_version = ?")) { statement.setInt(1, count); statement.setString(2, state); statement.setString(3, state); statement.setObject(4, id); statement.setString(5, QUEST_ID); statement.setString(6, VERSION); statement.executeUpdate(); } }
	private void requirePrerequisites(UUID character)throws SQLException{
        if(DEFINITION.requires().isEmpty())return;
        try(var c=connection()){
            for(String prerequisite:DEFINITION.requires()){
                var definition=kr.dalmaru.content.ContentCatalog.current().quest(prerequisite);
                try(var query=c.prepareStatement("SELECT 1 FROM dalmaru.character_quest_progress WHERE character_id=? AND quest_id=? AND content_version=? AND state='REWARDED'")){
                    query.setObject(1,character);query.setString(2,prerequisite);query.setString(3,definition.version());try(var row=query.executeQuery()){if(!row.next())throw new IllegalArgumentException("Quest prerequisite incomplete");}
                }
            }
        }
    }
    private CharacterProfile grantExperience(java.sql.Connection connection, UUID id, int amount) throws SQLException { return CharacterRepository.grantExperience(connection, id, amount); }
	private java.sql.Connection connection() throws SQLException { if(password==null||password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured."); return DriverManager.getConnection(url, username, password); }
	public record QuestReward(CharacterProfile profile, String message) { }
}
