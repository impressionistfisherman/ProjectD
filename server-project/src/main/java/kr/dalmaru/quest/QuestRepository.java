package kr.dalmaru.quest;

import java.sql.Connection;
import java.sql.DriverManager;
import com.google.gson.JsonObject;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;
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

    public static String contentVersion(){return VERSION;}
    public record AcceptanceResult(boolean created,boolean replayed,String currentStatus,RequestJournal.Receipt receipt) {}
    public AcceptanceResult acceptJournaled(UUID account,long generation,long request,UUID character,RequestEnvelope envelope)throws SQLException {
        if(generation<1||request<1||character==null)throw new IllegalArgumentException("Invalid quest acceptance request.");
        if(!DEFINITION.enabled())throw new IllegalArgumentException("Quest is disabled.");
        var body=new JsonObject();body.addProperty("questId",QUEST_ID);body.addProperty("contentVersion",VERSION);
        if(envelope==null)envelope=RequestEnvelope.legacy(account,generation,character,"accept_quest",request,body);
        if(envelope.connectionGeneration()!=generation||!envelope.activeCharacterId().equals(character)||!envelope.operation().equals("accept_quest")||!envelope.payload().equals(body))throw new IllegalArgumentException("Quest envelope does not match session/body");
        try(var connection=connection()){connection.setAutoCommit(false);try{
            var stored=RequestJournal.begin(connection,account,request,envelope);
            if(stored.result()!=null){var result=new AcceptanceResult(stored.result().get("created").getAsBoolean(),true,status(connection,character),stored.receipt());connection.commit();return result;}
            requirePrerequisites(connection,character);
            boolean created;try(var insert=connection.prepareStatement("INSERT INTO dalmaru.character_quest_progress(character_id,quest_id,content_version,state) VALUES(?,?,?,'ACTIVE') ON CONFLICT(character_id,quest_id,content_version) DO NOTHING")){
                insert.setObject(1,character);insert.setString(2,QUEST_ID);insert.setString(3,VERSION);created=insert.executeUpdate()==1;
            }
            var outcome=new JsonObject();outcome.addProperty("questId",QUEST_ID);outcome.addProperty("contentVersion",VERSION);outcome.addProperty("created",created);
            var receipt=RequestJournal.confirm(connection,stored,outcome);var result=new AcceptanceResult(created,false,status(connection,character),receipt);connection.commit();return result;
        }catch(Exception error){connection.rollback();throw error;}}
    }
    // Compatibility for trusted setup fixtures; session requests use acceptJournaled.
	public String accept(UUID characterId) throws SQLException {
        if(!DEFINITION.enabled())return "\uc758\ub8b0\uac00 \ube44\ud65c\uc131\ud654\ub418\uc5c8\uc2b5\ub2c8\ub2e4.";
        requirePrerequisites(characterId);
		try (var connection = connection(); var statement = connection.prepareStatement("INSERT INTO dalmaru.character_quest_progress (character_id, quest_id, content_version, state) VALUES (?, ?, ?, 'ACTIVE') ON CONFLICT (character_id, quest_id, content_version) DO NOTHING")) {
			statement.setObject(1, characterId); statement.setString(2, QUEST_ID); statement.setString(3, VERSION);
			return statement.executeUpdate() == 1 ? "\uc880\ube44 \ud1a0\ubc8c \uc758\ub8b0\ub97c \uc218\ub77d\ud588\uc2b5\ub2c8\ub2e4. \uc880\ube44 "+DEFINITION.goal()+"\ub9c8\ub9ac\ub97c \ucc98\uce58\ud558\uc138\uc694." : status(characterId);
		}
	}

    public QuestReward recordZombieKillJournaled(UUID account,long generation,UUID character,UUID killedEntity)throws SQLException {
        java.util.Objects.requireNonNull(killedEntity);
        if(killedEntity.equals(new UUID(0,0)))throw new IllegalArgumentException("Invalid killed entity");
        var body=new JsonObject();body.addProperty("questId",QUEST_ID);body.addProperty("contentVersion",VERSION);body.addProperty("killedEntityId",killedEntity.toString());
        UUID id=UUID.nameUUIDFromBytes(("quest_kill:"+character+":"+QUEST_ID+":"+VERSION+":"+killedEntity).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var envelope=new RequestEnvelope(id,generation,character,"quest_kill",body);
        long correlation=id.getLeastSignificantBits()&Long.MAX_VALUE;
        try(var connection=connection()){
            connection.setAutoCommit(false);
            try {
                var stored=RequestJournal.begin(connection,account,correlation==0?1:correlation,envelope);
                if(stored.result()!=null){connection.commit();return null;}
                QuestReward reward=DEFINITION.enabled()?recordZombieKill(connection,character):null;
                var result=body.deepCopy();result.addProperty("progressApplied",reward!=null);
                RequestJournal.confirm(connection,stored,result);
                // The event only changes database state; no world effect needs a client acknowledgment.
                RequestJournal.applied(connection,account,generation,id);
                connection.commit();return reward;
            }catch(SQLException|RuntimeException error){connection.rollback();throw error;}
        }
    }

    /** Trusted fixture compatibility; live kills must supply the entity identity. */
    public QuestReward recordZombieKill(UUID characterId)throws SQLException {
        if(!DEFINITION.enabled())return null;
        try(var connection=connection()){
            connection.setAutoCommit(false);
            try {var reward=recordZombieKill(connection,characterId);connection.commit();return reward;}
            catch(SQLException|RuntimeException error){connection.rollback();throw error;}
        }
    }

    private QuestReward recordZombieKill(Connection connection,UUID characterId)throws SQLException {
				int count;
				try (var statement = connection.prepareStatement("SELECT state, progress_count FROM dalmaru.character_quest_progress WHERE character_id = ? AND quest_id = ? AND content_version = ? FOR UPDATE")) {
					statement.setObject(1, characterId); statement.setString(2, QUEST_ID); statement.setString(3, VERSION);
					try (var result = statement.executeQuery()) { if (!result.next() || !"ACTIVE".equals(result.getString(1))) { return null; } count = result.getInt(2) + 1; }
				}
				if (count < DEFINITION.goal()) { update(connection, characterId, count, "ACTIVE"); return new QuestReward(null, "좀비 토벌 진행: " + count + "/" + DEFINITION.goal()); }
				update(connection, characterId, count, "REWARDED");
				CharacterProfile profile = DEFINITION.experience()==0?null:grantExperience(connection, characterId, DEFINITION.experience());
				return new QuestReward(profile, "\uc880\ube44 \ud1a0\ubc8c \uc644\ub8cc! \uacbd\ud5d8\uce58 "+DEFINITION.experience()+"\uc744 \ud68d\ub4dd\ud588\uc2b5\ub2c8\ub2e4.");
    }

    public String status(UUID characterId)throws SQLException {try(var connection=connection()){return status(connection,characterId);}}
    private static String status(Connection connection,UUID character)throws SQLException {
        try(var query=connection.prepareStatement("SELECT state,progress_count FROM dalmaru.character_quest_progress WHERE character_id=? AND quest_id=? AND content_version=?")){
            query.setObject(1,character);query.setString(2,QUEST_ID);query.setString(3,VERSION);try(var row=query.executeQuery()){return row.next()?"좀비 토벌: "+row.getInt(2)+"/"+DEFINITION.goal()+" ("+row.getString(1)+")":"수락 가능한 의뢰: 좀비 토벌";}
        }
    }
	private void update(java.sql.Connection connection, UUID id, int count, String state) throws SQLException { try (var statement = connection.prepareStatement("UPDATE dalmaru.character_quest_progress SET progress_count = ?, state = ?, claimed_at = CASE WHEN ? = 'REWARDED' THEN CURRENT_TIMESTAMP ELSE claimed_at END, updated_at = CURRENT_TIMESTAMP WHERE character_id = ? AND quest_id = ? AND content_version = ?")) { statement.setInt(1, count); statement.setString(2, state); statement.setString(3, state); statement.setObject(4, id); statement.setString(5, QUEST_ID); statement.setString(6, VERSION); statement.executeUpdate(); } }
    private void requirePrerequisites(UUID character)throws SQLException{try(var connection=connection()){requirePrerequisites(connection,character);}}
    private static void requirePrerequisites(Connection connection,UUID character)throws SQLException{
        for(String prerequisite:DEFINITION.requires()){
            var definition=kr.dalmaru.content.ContentCatalog.current().quest(prerequisite);
            try(var query=connection.prepareStatement("SELECT 1 FROM dalmaru.character_quest_progress WHERE character_id=? AND quest_id=? AND content_version=? AND state='REWARDED'")){
                query.setObject(1,character);query.setString(2,prerequisite);query.setString(3,definition.version());try(var row=query.executeQuery()){if(!row.next())throw new IllegalArgumentException("Quest prerequisite incomplete");}
            }
        }
    }
    private CharacterProfile grantExperience(java.sql.Connection connection, UUID id, int amount) throws SQLException { return CharacterRepository.grantExperience(connection, id, amount); }
	private java.sql.Connection connection() throws SQLException { if(password==null||password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured."); return DriverManager.getConnection(url, username, password); }
	public record QuestReward(CharacterProfile profile, String message) { }
}
