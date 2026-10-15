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
    public static final int TRIAL_LEVEL_CAP=20;
    public static final String PROFESSION_GATE_VERSION="pilot-2";
    public static List<String> professionGateFlags(CharacterProfession profession) {
        String prefix="profession."+profession.name().toLowerCase(java.util.Locale.ROOT);
        return List.of(prefix+".village_visit",prefix+".trial","story.four_paths");
    }
	static {
		try {
			Class.forName("org.postgresql.Driver");
		} catch (ClassNotFoundException exception) {
			throw new ExceptionInInitializerError(exception);
		}
	}

	private static final Pattern ALLOWED_NAME = Pattern.compile("[가-힣A-Za-z0-9_]+");
	public static final int PAID_RESET_COPPER_PER_LEVEL = StatResetPrice.COPPER_PER_LEVEL;
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
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try {
				releaseExpiredHold(connection, accountId, slot, normalizedName);
				rejectHeld(connection, accountId, slot, normalizedName);
				try (var statement = connection.prepareStatement(sql)) {
					statement.setObject(1, characterId);
					statement.setObject(2, accountId);
					statement.setInt(3, slot);
					statement.setString(4, displayName);
					statement.setString(5, normalizedName);
					statement.executeUpdate();
				}
				insertStartingProfile(connection, characterId);
				connection.commit();
				return new CharacterRecord(characterId, accountId, slot, displayName, normalizedName);
			} catch (Exception exception) {
				connection.rollback();
				throw exception;
			}
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
				int cost = before.freeResetAvailable() ? 0 : StatResetPrice.copper(before.level());
				if (cost > 0) {
					try (var wallet = connection.prepareStatement("UPDATE dalmaru.character_wallet SET copper=copper-? WHERE character_id=? AND copper>=?")) {
						wallet.setInt(1, cost); wallet.setObject(2, characterId); wallet.setInt(3, cost);
						if (wallet.executeUpdate() != 1) throw new IllegalArgumentException("Insufficient copper.");
					}
					try (var ledger = connection.prepareStatement("INSERT INTO dalmaru.character_currency_ledger(event_id,character_id,source_run_id,reason,copper_delta) VALUES(?,?,NULL,'stat_reset',?)")) {
						ledger.setObject(1, stored.receipt().eventId()); ledger.setObject(2, characterId); ledger.setInt(3, -cost); ledger.executeUpdate();
					}
				}
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
                var outcome=profileOutcome(after);outcome.addProperty("copperCost",cost);var receipt=RequestJournal.confirm(connection,stored,outcome);
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
        return applyExperience(connection,characterId,amount,TRIAL_LEVEL_CAP);
    }

    /** Explicit release transition; trial gameplay never calls this method. */
    public static CharacterProfile recalculateLevelForRelease(java.sql.Connection connection,UUID characterId)throws SQLException {
        if(connection.getAutoCommit())throw new IllegalArgumentException("Release recalculation requires a transaction.");
        return applyExperience(connection,characterId,0,60);
    }

    private static CharacterProfile applyExperience(java.sql.Connection connection,UUID characterId,long amount,int levelCap)throws SQLException {
        CharacterProfile before = profileForUpdate(connection, characterId);
        int level = before.level(), points = before.unspentStatPoints();
        long experience = Math.addExact(before.experience(), amount);
        while (level < levelCap && experience >= experienceNeededForNextLevel(level)) {
            experience -= experienceNeededForNextLevel(level); level++; points += 5;
        }
        int skillPoints=Math.max(0,level-10)-Math.max(0,before.level()-10);
        if(skillPoints>0)try(var profession=connection.prepareStatement("SELECT 1 FROM dalmaru.character_professions WHERE character_id=?")) {
            // Read after the profile lock so a concurrent profession selection is visible.
            profession.setObject(1,characterId);try(var row=profession.executeQuery()){if(!row.next())skillPoints=0;}
        }
        try (var update = connection.prepareStatement("UPDATE dalmaru.character_profiles SET level=?, experience=?, unspent_stat_points=?, unspent_skill_points=unspent_skill_points+? WHERE character_id=?")) {
            update.setInt(1, level); update.setLong(2, experience); update.setInt(3, points); update.setInt(4,skillPoints);update.setObject(5, characterId);
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
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try {
				releaseExpiredAccount(connection, accountId);
				connection.commit();
			} catch (Exception exception) {
				connection.rollback();
				throw exception;
			}
		}
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

	public record Journaled(JsonObject result, RequestJournal.Receipt receipt, boolean replayed) { }

	public java.util.Set<UUID> pendingDeletions(UUID accountId) throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		try (var connection = DriverManager.getConnection(url, username, password); var query = connection.prepareStatement("SELECT c.character_id FROM dalmaru.characters c JOIN dalmaru.character_deletions d ON d.character_id=c.character_id WHERE c.account_id=? AND d.deleted_at > clock_timestamp() - interval '7 days'")) {
			query.setObject(1, accountId);
			try (var rows = query.executeQuery()) {
				var ids = new java.util.HashSet<UUID>();
				while (rows.next()) ids.add(rows.getObject(1, UUID.class));
				return ids;
			}
		}
	}

	public void delete(UUID accountId, UUID characterId) throws SQLException { inTransaction(connection -> { applyDelete(connection, accountId, characterId); return null; }); }

	public void restore(UUID accountId, UUID characterId) throws SQLException { inTransaction(connection -> { applyRestore(connection, accountId, characterId); return null; }); }

	public Journaled deleteJournaled(UUID accountId, long generation, long requestId, UUID characterId, RequestEnvelope envelope) throws SQLException {
		return journal(accountId, generation, requestId, envelope, connection -> { applyDelete(connection, accountId, characterId); return lifeOutcome(characterId, true); });
	}

	public Journaled restoreJournaled(UUID accountId, long generation, long requestId, UUID characterId, RequestEnvelope envelope) throws SQLException {
		return journal(accountId, generation, requestId, envelope, connection -> { applyRestore(connection, accountId, characterId); return lifeOutcome(characterId, false); });
	}

	public Journaled note(UUID accountId, long generation, long requestId, RequestEnvelope envelope, JsonObject fresh) throws SQLException {
		return journal(accountId, generation, requestId, envelope, connection -> fresh);
	}

	public void selectActive(UUID accountId, UUID characterId) throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		try (var connection = DriverManager.getConnection(url, username, password)) {
			if (pendingDeletion(connection, accountId, characterId)) throw new IllegalArgumentException("Character is pending deletion.");
		}
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
                if (pendingDeletion(connection, accountId, characterId)) throw new IllegalArgumentException("Character is pending deletion.");
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
        return towns().stream().anyMatch(zone->zone.contains(dimension,x,y,z));
    }
    public java.util.List<TownZone> towns()throws SQLException{
        return kr.dalmaru.content.VillageCatalog.current().stream().map(kr.dalmaru.content.VillageCatalog.Village::zone).toList();
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
				releaseExpiredHold(connection, accountId, slot, normalizedName);
				rejectHeld(connection, accountId, slot, normalizedName);
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
                var requiredFlags=professionGateFlags(profession);
                var labels=List.of("직업 마을 방문","전직 시험","이야기");
                for(int i=0;i<requiredFlags.size();i++)try(var flag=connection.prepareStatement("SELECT 1 FROM dalmaru.character_quest_progress WHERE character_id=? AND quest_id=? AND content_version=? AND state IN ('COMPLETED','REWARDED')")) {
                    flag.setObject(1,characterId);flag.setString(2,requiredFlags.get(i));flag.setString(3,PROFESSION_GATE_VERSION);
                    try(var row=flag.executeQuery()){if(!row.next())throw new IllegalArgumentException(labels.get(i)+" 완료가 필요합니다.");}
                }
				try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_professions (character_id, base_job) VALUES (?, ?) ON CONFLICT (character_id) DO NOTHING")) { insert.setObject(1, characterId); insert.setString(2, profession.name()); if (insert.executeUpdate() == 1) try (var reward = connection.prepareStatement("UPDATE dalmaru.character_profiles SET unspent_skill_points = unspent_skill_points + 6 + GREATEST(LEAST(level,60)-10,0) WHERE character_id = ?")) { reward.setObject(1, characterId); reward.executeUpdate(); } }
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
		StarterSupply.grant(connection, characterId);
	}

	private interface Work { JsonObject apply(java.sql.Connection connection) throws SQLException; }

	private void inTransaction(Work work) throws SQLException {
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try { work.apply(connection); connection.commit(); }
			catch (Exception exception) { connection.rollback(); throw exception; }
		}
	}

	private Journaled journal(UUID accountId, long generation, long requestId, RequestEnvelope envelope, Work work) throws SQLException {
		if (generation < 1 || requestId < 1 || envelope == null) throw new IllegalArgumentException("Invalid character request.");
		if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD is not configured.");
		try (var connection = DriverManager.getConnection(url, username, password)) {
			connection.setAutoCommit(false);
			try {
				var stored = RequestJournal.begin(connection, accountId, requestId, envelope);
				if (stored.result() != null) { connection.commit(); return new Journaled(stored.result(), stored.receipt(), true); }
				var outcome = work.apply(connection);
				var receipt = RequestJournal.confirm(connection, stored, outcome);
				connection.commit();
				return new Journaled(outcome, receipt, false);
			} catch (Exception exception) { connection.rollback(); throw exception; }
		}
	}

	private static JsonObject lifeOutcome(UUID characterId, boolean pending) {
		var outcome = new JsonObject();
		outcome.addProperty("characterId", characterId.toString());
		outcome.addProperty("pending", pending);
		return outcome;
	}

	private void applyDelete(java.sql.Connection connection, UUID accountId, UUID characterId) throws SQLException {
		own(connection, accountId, characterId);
		try (var mark = connection.prepareStatement("INSERT INTO dalmaru.character_deletions(character_id, deleted_at) VALUES(?, clock_timestamp()) ON CONFLICT DO NOTHING")) {
			mark.setObject(1, characterId);
			mark.executeUpdate();
		}
		try (var clear = connection.prepareStatement("UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=? AND active_character_id=?")) {
			clear.setObject(1, accountId);
			clear.setObject(2, characterId);
			clear.executeUpdate();
		}
	}

	private void applyRestore(java.sql.Connection connection, UUID accountId, UUID characterId) throws SQLException {
		own(connection, accountId, characterId);
		try (var restore = connection.prepareStatement("DELETE FROM dalmaru.character_deletions WHERE character_id=? AND deleted_at > clock_timestamp() - interval '7 days'")) {
			restore.setObject(1, characterId);
			if (restore.executeUpdate() != 1) {
				try (var expired = connection.prepareStatement("SELECT 1 FROM dalmaru.character_deletions WHERE character_id=?")) {
					expired.setObject(1, characterId);
					try (var row = expired.executeQuery()) {
						throw new IllegalArgumentException(row.next() ? "Recovery window has closed." : "Character is not deleted.");
					}
				}
			}
		}
	}

	private void own(java.sql.Connection connection, UUID accountId, UUID characterId) throws SQLException {
		try (var own = connection.prepareStatement("SELECT 1 FROM dalmaru.characters WHERE character_id=? AND account_id=? FOR UPDATE")) {
			own.setObject(1, characterId);
			own.setObject(2, accountId);
			try (var row = own.executeQuery()) {
				if (!row.next()) throw new IllegalArgumentException("Character does not belong to this account.");
			}
		}
	}

	private boolean pendingDeletion(java.sql.Connection connection, UUID accountId, UUID characterId) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.characters c JOIN dalmaru.character_deletions d ON d.character_id=c.character_id WHERE c.character_id=? AND c.account_id=? AND d.deleted_at > clock_timestamp() - interval '7 days'")) {
			query.setObject(1, characterId);
			query.setObject(2, accountId);
			try (var row = query.executeQuery()) { return row.next(); }
		}
	}

	private void rejectHeld(java.sql.Connection connection, UUID accountId, int slot, String normalizedName) throws SQLException {
		try (var query = connection.prepareStatement("SELECT 1 FROM dalmaru.characters c JOIN dalmaru.character_deletions d ON d.character_id=c.character_id WHERE ((c.account_id=? AND c.slot=?) OR c.normalized_name=?) AND d.deleted_at > clock_timestamp() - interval '7 days'")) {
			query.setObject(1, accountId);
			query.setInt(2, slot);
			query.setString(3, normalizedName);
			try (var row = query.executeQuery()) {
				if (row.next()) throw new IllegalArgumentException("Name or slot is held for 7 days.");
			}
		}
	}

	private void releaseExpiredHold(java.sql.Connection connection, UUID accountId, int slot, String normalizedName) throws SQLException {
		String match = "((c.account_id=? AND c.slot=?) OR c.normalized_name=?) AND d.deleted_at <= clock_timestamp() - interval '7 days'";
		clearExpired(connection, match, accountId, slot, normalizedName);
	}

	private void releaseExpiredAccount(java.sql.Connection connection, UUID accountId) throws SQLException {
		clearExpired(connection, "c.account_id=? AND d.deleted_at <= clock_timestamp() - interval '7 days'", accountId, null, null);
	}

	private void clearExpired(java.sql.Connection connection, String match, UUID accountId, Integer slot, String normalizedName) throws SQLException {
		try (var clear = connection.prepareStatement("UPDATE dalmaru.accounts a SET active_character_id=NULL FROM dalmaru.characters c JOIN dalmaru.character_deletions d ON d.character_id=c.character_id WHERE a.active_character_id=c.character_id AND " + match)) {
			bindHold(clear, accountId, slot, normalizedName);
			clear.executeUpdate();
		}
		for (String table : List.of("dalmaru.character_profiles", "dalmaru.character_selection_requests", "dalmaru.character_stat_investment_requests", "dalmaru.character_stat_reset_requests", "dalmaru.skill_loadout_requests")) {
			try (var child = connection.prepareStatement("DELETE FROM " + table + " t USING dalmaru.characters c JOIN dalmaru.character_deletions d ON d.character_id=c.character_id WHERE t.character_id=c.character_id AND " + match)) {
				bindHold(child, accountId, slot, normalizedName);
				child.executeUpdate();
			}
		}
		try (var drop = connection.prepareStatement("DELETE FROM dalmaru.characters c USING dalmaru.character_deletions d WHERE c.character_id=d.character_id AND " + match)) {
			bindHold(drop, accountId, slot, normalizedName);
			drop.executeUpdate();
		}
	}

	private static void bindHold(java.sql.PreparedStatement statement, UUID accountId, Integer slot, String normalizedName) throws SQLException {
		statement.setObject(1, accountId);
		if (slot != null) {
			statement.setInt(2, slot);
			statement.setString(3, normalizedName);
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
