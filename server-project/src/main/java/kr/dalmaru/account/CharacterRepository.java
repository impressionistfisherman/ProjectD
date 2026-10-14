package kr.dalmaru.account;

import com.google.gson.JsonObject;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;
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
		url = "jdbc:postgresql://" + host + ":" + port + "/" + database + "?connectTimeout=1&loginTimeout=1&socketTimeout=2&options=-c%20statement_timeout%3D250ms%20-c%20lock_timeout%3D200ms";
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
		String sql = "SELECT p.character_id, p.level, p.experience, p.unspent_stat_points, p.strength, p.dexterity, p.arcana, p.vitality, p.spirit, p.free_reset_available, p.unspent_skill_points, COALESCE(j.base_job, ''), p.revision FROM dalmaru.character_profiles p LEFT JOIN dalmaru.character_professions j ON j.character_id = p.character_id WHERE p.character_id = ?";
		try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.prepareStatement(sql)) {
			statement.setObject(1, characterId);
			try (var result = statement.executeQuery()) {
				if (!result.next()) throw new IllegalArgumentException("Character profile was not found.");
				return new CharacterProfile(result.getObject(1, UUID.class), result.getInt(2), result.getLong(3), result.getInt(4), result.getInt(5), result.getInt(6), result.getInt(7), result.getInt(8), result.getInt(9), result.getBoolean(10), result.getInt(11), result.getString(12), result.getLong(13));
			}
		}
	}

	public record InvestmentResult(CharacterProfile profile, boolean replayed, JsonObject originalResult, RequestJournal.Receipt receipt) { public InvestmentResult { originalResult=originalResult.deepCopy(); } @Override public JsonObject originalResult(){return originalResult.deepCopy();} }
	public CharacterStatInvestmentResult investStat(UUID accountId, long connectionGeneration, long requestId, UUID characterId, CharacterStat stat, int amount) throws SQLException {
        var result=investStatJournaled(accountId,connectionGeneration,requestId,characterId,stat,amount,null);
        return new CharacterStatInvestmentResult(result.profile(),result.replayed());
    }

    public InvestmentResult investStatJournaled(UUID accountId,long connectionGeneration,long requestId,UUID characterId,CharacterStat stat,int amount,RequestEnvelope envelope)throws SQLException {
		if (connectionGeneration < 1 || requestId < 1 || amount < 1) throw new IllegalArgumentException("Invalid stat investment request.");
        if(characterId==null||stat==null)throw new IllegalArgumentException("Invalid stat investment request.");
        var body=new JsonObject();body.addProperty("statId",stat.id());body.addProperty("amount",amount);
        if(envelope==null)envelope=RequestEnvelope.legacy(accountId,connectionGeneration,characterId,"invest_stat",requestId,body);
        if(envelope.connectionGeneration()!=connectionGeneration||!envelope.activeCharacterId().equals(characterId)||!envelope.operation().equals("invest_stat")||!envelope.payload().equals(body))throw new IllegalArgumentException("Stat envelope does not match session/body");
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try {
                var stored=RequestJournal.begin(connection,accountId,requestId,envelope);requestId=stored.legacyRequestId();
                if(stored.result()!=null){var profile=profileForUpdate(connection,characterId);connection.commit();return new InvestmentResult(profile,true,stored.result(),stored.receipt());}
				String existingSql = "SELECT character_id, stat_id, amount, level, experience, unspent_stat_points, strength, dexterity, arcana, vitality, spirit, free_reset_available FROM dalmaru.character_stat_investment_requests WHERE account_id = ? AND connection_generation = ? AND request_id = ?";
				try (var statement = connection.prepareStatement(existingSql)) {
					statement.setObject(1, accountId); statement.setLong(2, connectionGeneration); statement.setLong(3, requestId);
					try (var result = statement.executeQuery()) {
						if (result.next()) {
							if (!characterId.equals(result.getObject(1, UUID.class)) || result.getInt(2) != stat.id() || result.getInt(3) != amount) throw new IllegalArgumentException("Request ID was reused for a different stat investment.");
                            var original=new CharacterProfile(characterId,result.getInt(4),result.getLong(5),result.getInt(6),result.getInt(7),result.getInt(8),result.getInt(9),result.getInt(10),result.getInt(11),result.getBoolean(12));
                            var receipt=RequestJournal.confirm(connection,stored,investmentOutcome(original,stat,amount));
                            var profile=profileForUpdate(connection,characterId);connection.commit();
                            return new InvestmentResult(profile,true,investmentOutcome(original,stat,amount),receipt);
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
                var receipt=RequestJournal.confirm(connection,stored,investmentOutcome(after,stat,amount));
                connection.commit();return new InvestmentResult(after,false,investmentOutcome(after,stat,amount),receipt);
			} catch (Exception exception) {
				connection.rollback();
				throw exception;
			}
		}
	}

    private static JsonObject investmentOutcome(CharacterProfile profile,CharacterStat stat,int amount) {
        var result=profileOutcome(profile);result.addProperty("statId",stat.id());result.addProperty("amount",amount);return result;
    }

    private static JsonObject profileOutcome(CharacterProfile profile) {
        var result=new JsonObject();result.addProperty("level",profile.level());result.addProperty("experience",profile.experience());
        result.addProperty("unspentStatPoints",profile.unspentStatPoints());result.addProperty("strength",profile.strength());
        result.addProperty("dexterity",profile.dexterity());result.addProperty("arcana",profile.arcana());
        result.addProperty("vitality",profile.vitality());result.addProperty("spirit",profile.spirit());
        result.addProperty("freeResetAvailable",profile.freeResetAvailable());return result;
    }

    public record ResetResult(CharacterProfile profile,boolean replayed,JsonObject originalResult,RequestJournal.Receipt receipt) {
        public ResetResult{originalResult=originalResult.deepCopy();}
        @Override public JsonObject originalResult(){return originalResult.deepCopy();}
    }
	public CharacterStatResetResult resetStats(UUID accountId, long connectionGeneration, long requestId, UUID characterId) throws SQLException {
        var result=resetStatsJournaled(accountId,connectionGeneration,requestId,characterId,null);
        return new CharacterStatResetResult(result.profile(),result.replayed());
    }

    public ResetResult resetStatsJournaled(UUID accountId,long connectionGeneration,long requestId,UUID characterId,RequestEnvelope envelope)throws SQLException {
		if (connectionGeneration < 1 || requestId < 1) throw new IllegalArgumentException("Invalid stat reset request.");
        if(characterId==null)throw new IllegalArgumentException("Invalid stat reset request.");
        var body=new JsonObject();
        if(envelope==null)envelope=RequestEnvelope.legacy(accountId,connectionGeneration,characterId,"reset_stats",requestId,body);
        if(envelope.connectionGeneration()!=connectionGeneration||!envelope.activeCharacterId().equals(characterId)||!envelope.operation().equals("reset_stats")||!envelope.payload().equals(body))throw new IllegalArgumentException("Reset envelope does not match session/body");
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try {
                var stored=RequestJournal.begin(connection,accountId,requestId,envelope);requestId=stored.legacyRequestId();
                if(stored.result()!=null){var profile=profileForUpdate(connection,characterId);connection.commit();return new ResetResult(profile,true,stored.result(),stored.receipt());}
				String existingSql = "SELECT character_id, level, experience, unspent_stat_points, strength, dexterity, arcana, vitality, spirit, free_reset_available FROM dalmaru.character_stat_reset_requests WHERE account_id = ? AND connection_generation = ? AND request_id = ?";
				try (var statement = connection.prepareStatement(existingSql)) {
					statement.setObject(1, accountId); statement.setLong(2, connectionGeneration); statement.setLong(3, requestId);
					try (var result = statement.executeQuery()) {
						if (result.next()) {
							if (!characterId.equals(result.getObject(1, UUID.class))) throw new IllegalArgumentException("Request ID was reused for a different stat reset.");
                            var original=new CharacterProfile(characterId,result.getInt(2),result.getLong(3),result.getInt(4),result.getInt(5),result.getInt(6),result.getInt(7),result.getInt(8),result.getInt(9),result.getBoolean(10));
                            var outcome=profileOutcome(original);var receipt=RequestJournal.confirm(connection,stored,outcome);
                            var profile=profileForUpdate(connection,characterId);connection.commit();return new ResetResult(profile,true,outcome,receipt);
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
                var outcome=profileOutcome(after);var receipt=RequestJournal.confirm(connection,stored,outcome);
                connection.commit();return new ResetResult(after,false,outcome,receipt);
			} catch (Exception exception) {
				connection.rollback();
				throw exception;
			}
		}
	}

	public CharacterProfile grantExperience(UUID characterId, long amount) throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            try { CharacterProfile result = grantExperience(connection, characterId, amount); connection.commit(); return result; }
            catch (Exception exception) { connection.rollback(); throw exception; }
        }
    }
    public static CharacterProfile grantExperience(java.sql.Connection connection, UUID characterId, long amount) throws SQLException {
        if (amount < 1) throw new IllegalArgumentException("Experience amount must be positive.");
        CharacterProfile before = profileForUpdate(connection, characterId);
        int level = before.level(), points = before.unspentStatPoints();
        long experience = Math.addExact(before.experience(), amount);
        while (level < 60 && experience >= experienceNeededForNextLevel(level)) {
            experience -= experienceNeededForNextLevel(level); level++; points += 5;
        }
        if (level == 60) experience = 0;
        try (var update = connection.prepareStatement("UPDATE dalmaru.character_profiles SET level=?, experience=?, unspent_stat_points=? WHERE character_id=?")) {
            update.setInt(1, level); update.setLong(2, experience); update.setInt(3, points); update.setObject(4, characterId);
            if (update.executeUpdate()!=1) throw new SQLException("Character profile disappeared.");
        }
        return profileForUpdate(connection, characterId);
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

    public CharacterSelectionResult selectActive(UUID accountId, long generation, long requestId, UUID characterId) throws SQLException {
        return selectActive(accountId, generation, requestId, characterId, null);
    }
    public CharacterSelectionResult selectActive(UUID accountId, long generation, long requestId, UUID characterId, String vanillaSnapshot) throws SQLException {
        return selectActive(accountId,generation,requestId,characterId,vanillaSnapshot,null);
    }
    public CharacterSelectionResult selectActive(UUID accountId,long generation,long requestId,UUID characterId,String vanillaSnapshot,java.util.function.Consumer<String> install) throws SQLException {
        if (generation < 1 || requestId < 1) throw new IllegalArgumentException("Invalid character selection request.");
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
        if(install!=null&&vanillaSnapshot!=null)checkpointVanilla(accountId,generation,vanillaSnapshot);
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            boolean commitAttempted=false;
            try {
                UUID previous;
                try (var account = connection.prepareStatement("SELECT active_character_id FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? FOR UPDATE")) {
                    account.setObject(1, accountId); account.setLong(2, generation);
                    try (var row=account.executeQuery()) { if (!row.next()) throw new IllegalArgumentException("Stale selection session."); previous=row.getObject(1, UUID.class); }
                }
                boolean replayed=false;
                try(var replay=connection.prepareStatement("SELECT character_id FROM dalmaru.character_selection_requests WHERE account_id=? AND connection_generation=? AND request_id=?")) {
                    replay.setObject(1, accountId); replay.setLong(2, generation); replay.setLong(3, requestId);
                    try(var row=replay.executeQuery()) { if(row.next()) { if(!characterId.equals(row.getObject(1,UUID.class)) || !characterId.equals(previous)) throw new IllegalArgumentException("Selection request is no longer current."); replayed=true; } }
                }
                if (!replayed && !characterId.equals(previous)) {
                    try(var run=connection.prepareStatement("SELECT 1 FROM dalmaru.run_participants p JOIN dalmaru.run_instances r ON r.run_id=p.run_id WHERE p.character_id IN (?,?) AND r.state IN ('ACTIVE','PAUSED') FOR UPDATE OF r")) {
                        run.setObject(1, previous); run.setObject(2, characterId);
                        try(var row=run.executeQuery()) { if(row.next()) throw new IllegalArgumentException("Cannot switch during a run."); }
                    }
                    if (previous!=null && vanillaSnapshot!=null) try(var save=connection.prepareStatement("INSERT INTO dalmaru.character_vanilla_state(character_id,state) VALUES(?,?::jsonb) ON CONFLICT(character_id) DO UPDATE SET state=EXCLUDED.state,updated_at=CURRENT_TIMESTAMP")) {
                        save.setObject(1,previous); save.setString(2,vanillaSnapshot); save.executeUpdate();
                    }
                }
                if (!replayed) {
                    try(var update=connection.prepareStatement("UPDATE dalmaru.accounts SET active_character_id=? WHERE account_id=? AND connection_generation=? AND EXISTS(SELECT 1 FROM dalmaru.characters WHERE character_id=? AND account_id=?)")) {
                        update.setObject(1,characterId); update.setObject(2,accountId); update.setLong(3,generation); update.setObject(4,characterId); update.setObject(5,accountId);
                        if(update.executeUpdate()!=1) throw new IllegalArgumentException("Character selection was rejected.");
                    }
                    try(var request=connection.prepareStatement("INSERT INTO dalmaru.character_selection_requests VALUES(?,?,?,?)")) {
                        request.setObject(1,accountId); request.setLong(2,generation); request.setLong(3,requestId); request.setObject(4,characterId); request.executeUpdate();
                    }
                }
                String inventory=null;
                try(var stored=connection.prepareStatement("SELECT state::text FROM dalmaru.character_vanilla_state WHERE character_id=?")) {
                    stored.setObject(1,characterId); try(var row=stored.executeQuery()) { if(row.next()) inventory=row.getString(1); }
                }
                // The initial selection adopts existing vanilla possessions; subsequent new characters start empty.
                if ((previous==null || characterId.equals(previous)) && inventory==null) inventory=vanillaSnapshot;
                if(!characterId.equals(previous)) {
                    inventory=withOwner(inventory,characterId+":"+UUID.randomUUID());
                    saveVanilla(connection,characterId,inventory);
                    try(var checkpoint=connection.prepareStatement("UPDATE dalmaru.accounts SET vanilla_owner=?,vanilla_checkpoint=?::jsonb WHERE account_id=?")) {
                        checkpoint.setString(1,vanillaOwner(inventory));checkpoint.setString(2,inventory);checkpoint.setObject(3,accountId);checkpoint.executeUpdate();
                    }
                    if(install!=null)install.accept(inventory);
                }
                commitAttempted=true;connection.commit(); return new CharacterSelectionResult(characterId,replayed,inventory);
            } catch(Exception exception) {
                try{connection.rollback();}catch(Exception rollback){exception.addSuppressed(rollback);throw new SelectionCommitUncertainException(exception);}
                if(commitAttempted)throw new SelectionCommitUncertainException(exception);
                throw exception;
            }
        }
    }
    private static String vanillaOwner(String snapshot) {
        var json=com.google.gson.JsonParser.parseString(snapshot).getAsJsonObject();
        return json.has("owner")?json.get("owner").getAsString():null;
    }
    private static String withOwner(String snapshot,String owner) {
        var json=snapshot==null?new com.google.gson.JsonObject():com.google.gson.JsonParser.parseString(snapshot).getAsJsonObject();
        if(snapshot==null)json.addProperty("empty",true);
        json.addProperty("owner",owner);return json.toString();
    }
    private static void saveVanilla(java.sql.Connection connection,UUID character,String snapshot)throws SQLException {
        try(var save=connection.prepareStatement("INSERT INTO dalmaru.character_vanilla_state(character_id,state) VALUES(?,?::jsonb) ON CONFLICT(character_id) DO UPDATE SET state=EXCLUDED.state,updated_at=CURRENT_TIMESTAMP")) {
            save.setObject(1,character);save.setString(2,snapshot);save.executeUpdate();
        }
    }
    public void checkpointVanilla(UUID account,long generation,String snapshot)throws SQLException {
        try(var connection=DriverManager.getConnection(url,username,password)) {
            connection.setAutoCommit(false);
            try {
                try(var lock=connection.prepareStatement("SELECT active_character_id,vanilla_owner FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? FOR UPDATE")) {
                    lock.setObject(1,account);lock.setLong(2,generation);
                    try(var row=lock.executeQuery()) {
                        if(!row.next()||!java.util.Objects.equals(row.getString(2),vanillaOwner(snapshot)))throw new IllegalArgumentException("Inventory ownership is not recovered.");
                        UUID character=row.getObject(1,UUID.class);if(character!=null)saveVanilla(connection,character,snapshot);
                    }
                }
                try(var update=connection.prepareStatement("UPDATE dalmaru.accounts SET vanilla_checkpoint=?::jsonb WHERE account_id=?")){update.setString(1,snapshot);update.setObject(2,account);update.executeUpdate();}
                connection.commit();
            }catch(Exception error){connection.rollback();throw error;}
        }
    }
    public void recoverVanilla(UUID account,long generation,String snapshot,java.util.function.Consumer<String> install)throws SQLException {
        String restored;
        try(var connection=DriverManager.getConnection(url,username,password)) {
            connection.setAutoCommit(false);
            try {
                try(var lock=connection.prepareStatement("SELECT active_character_id,vanilla_owner,vanilla_checkpoint::text FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? FOR UPDATE")) {
                    lock.setObject(1,account);lock.setLong(2,generation);
                    try(var row=lock.executeQuery()) {
                        if(!row.next())throw new IllegalArgumentException("Stale inventory recovery.");
                        String owner=row.getString(2);
                        if(owner!=null&&owner.equals(vanillaOwner(snapshot))){connection.commit();return;}
                        if(owner==null) {
                            if(vanillaOwner(snapshot)!=null)throw new IllegalStateException("Unrecognized inventory owner; manual recovery required.");
                            try(var legacy=connection.prepareStatement("SELECT state::text FROM dalmaru.character_vanilla_state WHERE character_id=?")) {
                                legacy.setObject(1,row.getObject(1,UUID.class));
                                try(var stored=legacy.executeQuery()) {
                                    if(stored.next()&&!com.google.gson.JsonParser.parseString(stored.getString(1)).equals(com.google.gson.JsonParser.parseString(snapshot)))throw new IllegalStateException("Legacy inventory mismatch; manual recovery required.");
                                }
                            }
                            owner=String.valueOf(row.getObject(1))+":"+UUID.randomUUID();restored=withOwner(snapshot,owner);
                            try(var update=connection.prepareStatement("UPDATE dalmaru.accounts SET vanilla_owner=?,vanilla_checkpoint=?::jsonb WHERE account_id=?")){update.setString(1,owner);update.setString(2,restored);update.setObject(3,account);update.executeUpdate();}
                            UUID character=row.getObject(1,UUID.class);if(character!=null)saveVanilla(connection,character,restored);
                        }else {
                            restored=row.getString(3);
                            if(restored==null||!owner.equals(vanillaOwner(restored)))throw new IllegalStateException("Inventory checkpoint is missing or inconsistent.");
                        }
                    }
                }
                // Durable recovery precedes installation, so a second crash repeats the same owned snapshot.
                connection.commit();
            }catch(Exception error){connection.rollback();throw error;}
        }
        install.accept(restored);
    }

    public boolean inTown(String dimension, int x, int y, int z) throws SQLException {
        try(var connection=DriverManager.getConnection(url,username,password);var query=connection.prepareStatement("SELECT 1 FROM dalmaru.town_safe_zones WHERE dimension=? AND abs(x-?)<=radius AND abs(y-?)<=radius AND abs(z-?)<=radius")) {
            query.setString(1,dimension);query.setInt(2,x);query.setInt(3,y);query.setInt(4,z);try(var row=query.executeQuery()){return row.next();}
        }
    }
    public java.util.List<TownZone> towns()throws SQLException{
        try(var c=DriverManager.getConnection(url,username,password);var q=c.prepareStatement("SELECT dimension,x,y,z,radius FROM dalmaru.town_safe_zones");var rows=q.executeQuery()){
            var zones=new java.util.ArrayList<TownZone>();while(rows.next())zones.add(new TownZone(rows.getString(1),rows.getInt(2),rows.getInt(3),rows.getInt(4),rows.getInt(5)));return java.util.List.copyOf(zones);
        }
    }
    public void registerTown(String name,String dimension,int x,int y,int z,int radius) throws SQLException {
        if(radius<1||radius>128)throw new IllegalArgumentException("Invalid town radius.");
        try(var connection=DriverManager.getConnection(url,username,password);var insert=connection.prepareStatement("INSERT INTO dalmaru.town_safe_zones VALUES(?,?,?,?,?,?) ON CONFLICT(name) DO UPDATE SET dimension=EXCLUDED.dimension,x=EXCLUDED.x,y=EXCLUDED.y,z=EXCLUDED.z,radius=EXCLUDED.radius")) {
            insert.setString(1,name);insert.setString(2,dimension);insert.setInt(3,x);insert.setInt(4,y);insert.setInt(5,z);insert.setInt(6,radius);insert.executeUpdate();
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
				try (var account = connection.prepareStatement("SELECT 1 FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? FOR UPDATE")) {
                    account.setObject(1, accountId); account.setLong(2, connectionGeneration);
                    try (var row = account.executeQuery()) { if (!row.next()) throw new IllegalArgumentException("Stale character creation session."); }
                }
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

    public record ProfessionResult(CharacterProfession profession,CharacterProfile profile,boolean replayed,RequestJournal.Receipt receipt) {}
	public CharacterProfession selectProfession(UUID accountId, long connectionGeneration, long requestId, UUID characterId, CharacterProfession profession) throws SQLException {
        return selectProfessionJournaled(accountId,connectionGeneration,requestId,characterId,profession,null).profession();
    }

    public ProfessionResult selectProfessionJournaled(UUID accountId,long connectionGeneration,long requestId,UUID characterId,CharacterProfession profession,RequestEnvelope envelope)throws SQLException {
		if (connectionGeneration < 1 || requestId < 1) throw new IllegalArgumentException("Invalid profession request.");
        if(characterId==null||profession==null)throw new IllegalArgumentException("Invalid profession request.");
        var body=new JsonObject();body.addProperty("profession",profession.name());
        if(envelope==null)envelope=RequestEnvelope.legacy(accountId,connectionGeneration,characterId,"select_profession",requestId,body);
        if(envelope.connectionGeneration()!=connectionGeneration||!envelope.activeCharacterId().equals(characterId)||!envelope.operation().equals("select_profession")||!envelope.payload().equals(body))throw new IllegalArgumentException("Profession envelope does not match session/body");
        if(password==null||password.isBlank())throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try {
                var stored=RequestJournal.begin(connection,accountId,requestId,envelope);requestId=stored.legacyRequestId();
                if(stored.result()!=null){var selected=CharacterProfession.valueOf(stored.result().get("profession").getAsString());var current=profileForUpdate(connection,characterId);connection.commit();return new ProfessionResult(selected,current,true,stored.receipt());}
				try (var profile = connection.prepareStatement("SELECT level FROM dalmaru.character_profiles WHERE character_id = ? FOR UPDATE")) { profile.setObject(1, characterId); try (var result = profile.executeQuery()) { if (!result.next() || result.getInt(1) < 10) throw new IllegalArgumentException("Level 10 is required for a profession."); } }
				try (var quest = connection.prepareStatement("SELECT 1 FROM dalmaru.character_quest_progress WHERE character_id = ? AND quest_id = 'pilot1.zombie_hunt' AND content_version = 'pilot-1' AND state = 'REWARDED'")) { quest.setObject(1, characterId); try (var result = quest.executeQuery()) { if (!result.next()) throw new IllegalArgumentException("Profession quest is not complete."); } }
				try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_professions (character_id, base_job) VALUES (?, ?) ON CONFLICT (character_id) DO NOTHING")) { insert.setObject(1, characterId); insert.setString(2, profession.name()); if (insert.executeUpdate() == 1) try (var reward = connection.prepareStatement("UPDATE dalmaru.character_profiles SET unspent_skill_points = unspent_skill_points + 6 WHERE character_id = ?")) { reward.setObject(1, characterId); reward.executeUpdate(); } }
				try (var selected = connection.prepareStatement("SELECT base_job FROM dalmaru.character_professions WHERE character_id = ?")) { selected.setObject(1, characterId); try (var result = selected.executeQuery()) { result.next(); CharacterProfession saved = CharacterProfession.valueOf(result.getString(1)); if (saved != profession) throw new IllegalArgumentException("Profession was already selected."); var outcome=new JsonObject();outcome.addProperty("profession",saved.name());var receipt=RequestJournal.confirm(connection,stored,outcome);var current=profileForUpdate(connection,characterId);connection.commit();return new ProfessionResult(saved,current,false,receipt); } }
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

	public static CharacterProfile profileForUpdate(java.sql.Connection connection, UUID characterId) throws SQLException {
		String sql = "SELECT p.character_id, p.level, p.experience, p.unspent_stat_points, p.strength, p.dexterity, p.arcana, p.vitality, p.spirit, p.free_reset_available, p.unspent_skill_points, COALESCE(j.base_job, ''), p.revision FROM dalmaru.character_profiles p LEFT JOIN dalmaru.character_professions j ON j.character_id=p.character_id WHERE p.character_id = ? FOR UPDATE OF p";
		try (var statement = connection.prepareStatement(sql)) {
			statement.setObject(1, characterId);
			try (var result = statement.executeQuery()) {
				if (!result.next()) throw new IllegalArgumentException("Character profile was not found.");
				return new CharacterProfile(result.getObject(1, UUID.class), result.getInt(2), result.getLong(3), result.getInt(4), result.getInt(5), result.getInt(6), result.getInt(7), result.getInt(8), result.getInt(9), result.getBoolean(10), result.getInt(11), result.getString(12), result.getLong(13));
			}
		}
	}
}
