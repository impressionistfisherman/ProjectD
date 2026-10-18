package kr.dalmaru.run;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class RunRepository {
    private static final String CONTENT = "run.bongsudae";
    private final RunRewardRepository rewards = new RunRewardRepository();
    private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD") + "?connectTimeout=1&loginTimeout=1&socketTimeout=2&options=-c%20statement_timeout%3D250ms%20-c%20lock_timeout%3D200ms";
    private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
    private final String password = System.getenv("DALMARU_DB_PASSWORD");

    public RunRecord enter(UUID accountId, long generation, long requestId, RunDifficulty difficulty, List<UUID> participants) throws SQLException {
        if (generation < 1 || requestId < 1 || participants.isEmpty() || participants.size() > 4 || password == null || password.isBlank()) throw new IllegalArgumentException("Invalid run entry.");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            try {
                UUID active = activeCharacter(connection, accountId, generation);
                if (!participants.contains(active) || participants.stream().distinct().count() != participants.size()) throw new IllegalArgumentException("Active character must enter exactly once.");
                var stored = kr.dalmaru.request.RequestJournal.begin(connection, accountId, requestId, kr.dalmaru.request.RequestEnvelope.legacy(accountId, generation, active, "enter_run", requestId, entryBody(difficulty, participants)));
                requestId = stored.legacyRequestId();
                if (stored.result() != null) {
                    if (!difficulty.name().equals(stored.result().get("difficulty").getAsString()) || participants.size() != stored.result().get("partySize").getAsInt()) throw new IllegalArgumentException("Entry replay is no longer active or has different content.");
                    var current = acceptedReplay(connection, UUID.fromString(stored.result().get("runId").getAsString()), difficulty, participants);
                    connection.commit(); return current;
                }
                // Lock pending active runs too, so expiry cannot cross the entry check.
                for(UUID characterId:participants)try(var pending=connection.prepareStatement("SELECT r.state FROM dalmaru.run_participants p JOIN dalmaru.run_instances r ON r.run_id=p.run_id WHERE p.character_id=? AND p.return_pending FOR UPDATE OF p,r")){
                    pending.setObject(1,characterId);try(var rows=pending.executeQuery()){
                        while(rows.next())if("FAILED".equals(rows.getString(1))||"COMPLETED".equals(rows.getString(1)))throw new IllegalArgumentException("Pending run return requires recovery.");
                    }
                }
                RunRecord replay=replay(connection,accountId,generation,requestId,"ENTER");
                if(replay!=null){
                    var current = acceptedReplay(connection, replay.runId(), difficulty, participants);
                    kr.dalmaru.request.RequestJournal.confirm(connection, stored, entryOutcome(current));
                    connection.commit(); return current;
                }
                for (UUID characterId : participants) validateParticipant(connection, characterId, difficulty);
                int tier = participants.size() == 1 ? 1 : participants.size() == 2 ? 2 : 4;
                LocalDate week = weekId();
                for (UUID characterId : participants) reserveAvailable(connection, characterId, difficulty, week);
                var content=kr.dalmaru.content.ContentCatalog.current();
                var definition=content.run(CONTENT);
                if(!definition.enabled()||participants.size()<definition.minPlayers()||participants.size()>definition.maxPlayers())throw new IllegalArgumentException("Run definition does not allow this party.");
                UUID runId = UUID.randomUUID();
                try (var insert = connection.prepareStatement("INSERT INTO dalmaru.run_instances (run_id, content_id, difficulty, week_id, party_size, scaling_tier, state,content_version,content_snapshot) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE',?,?::jsonb)")) { insert.setObject(1, runId); insert.setString(2, CONTENT); insert.setString(3, difficulty.name()); insert.setObject(4, week); insert.setInt(5, participants.size()); insert.setInt(6, tier); insert.setString(7,content.version());insert.setString(8,content.snapshot());insert.executeUpdate(); }
                try (var insert = connection.prepareStatement("INSERT INTO dalmaru.run_participants (run_id, character_id,frozen_loadout,loadout_revision) VALUES (?, ?,?::jsonb,?)")) { for (UUID characterId : participants) { var loadout=kr.dalmaru.skill.LoadoutRepository.load(connection,characterId); insert.setObject(1, runId); insert.setObject(2, characterId); insert.setString(3,kr.dalmaru.skill.LoadoutRepository.encode(loadout.slots()));insert.setLong(4,loadout.revision());insert.addBatch(); } insert.executeBatch(); }
                // Same transaction as the run row: the roster exists exactly when the run does (account lock already held above).
                var roster = kr.dalmaru.party.PartyRunService.open(connection, runId, active, "READY");
                if (!"ROSTER".equals(roster.status())) throw new IllegalStateException("Run party roster did not open: " + roster.reason());
                request(connection, accountId, generation, requestId, "ENTER", runId, "ACTIVE");
                var created = new RunRecord(runId, difficulty, participants.size(), tier, "ACTIVE", false);
                kr.dalmaru.request.RequestJournal.confirm(connection, stored, entryOutcome(created));
                connection.commit(); return created;
            } catch (Exception exception) { connection.rollback(); throw exception; }
        }
    }
    /** READY to ACTIVE for the run roster when the first combat stage starts. Repeat calls and runs without a roster change nothing; failure rolls back. */
    public boolean activateParty(UUID runId) throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            try {
                try (var phase = connection.prepareStatement("SELECT phase FROM dalmaru.run_party_control WHERE run_id=? FOR UPDATE")) {
                    phase.setObject(1, runId);
                    try (var row = phase.executeQuery()) {
                        if (!row.next() || !"READY".equals(row.getString(1))) { connection.commit(); return true; }
                    }
                }
                var outcome = kr.dalmaru.party.PartyRunService.activate(connection, runId);
                if (!"ROSTER".equals(outcome.status())) throw new IllegalStateException("Run party roster did not activate: " + outcome.reason());
                connection.commit();
                return true;
            } catch (Exception error) { connection.rollback(); throw error; }
        }
    }
    public kr.dalmaru.content.ContentCatalog frozenContent(UUID run)throws SQLException{
        try(var c=DriverManager.getConnection(url,username,password);var q=c.prepareStatement("SELECT content_id,content_version,content_snapshot::text FROM dalmaru.run_instances WHERE run_id=?")){
            q.setObject(1,run);
            try(var row=q.executeQuery()){
                if(!row.next())throw new IllegalArgumentException("Run not found.");
                if(row.getString(3)==null)throw new IllegalArgumentException("Legacy run content snapshot is unknown; operator recovery required.");
                var catalog=kr.dalmaru.content.ContentCatalog.parse(row.getString(3));
                if(!CONTENT.equals(row.getString(1))||!catalog.version().equals(row.getString(2))||!catalog.run(CONTENT).version().equals(row.getString(2))||!catalog.run(CONTENT).enabled())throw new IllegalArgumentException("Run content snapshot mismatch.");
                return catalog;
            }
        }
    }

    public kr.dalmaru.skill.SkillLoadout frozenLoadout(UUID run,UUID character)throws SQLException {
        try(var c=DriverManager.getConnection(url,username,password);var q=c.prepareStatement("SELECT frozen_loadout::text,loadout_revision FROM dalmaru.run_participants WHERE run_id=? AND character_id=?")){
            q.setObject(1,run);q.setObject(2,character);try(var row=q.executeQuery()){if(!row.next()||row.getString(1)==null)throw new IllegalArgumentException("Legacy run lacks frozen loadout.");return new kr.dalmaru.skill.SkillLoadout(character,row.getLong(2),kr.dalmaru.skill.LoadoutRepository.decode(row.getString(1)));}
        }
    }
    public boolean failUnattendedWipe(UUID runId) throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try (var connection = DriverManager.getConnection(url, username, password); var update = connection.prepareStatement("UPDATE dalmaru.run_instances SET state='FAILED', finished_at=CURRENT_TIMESTAMP WHERE run_id=? AND state IN ('ACTIVE','PAUSED')")) {
            update.setObject(1, runId);
            return update.executeUpdate() == 1;
        }
    }
    public RunRecord finish(UUID accountId, long generation, long requestId, UUID runId, boolean success) throws SQLException {
        return finish(accountId,generation,requestId,runId,success,java.util.Map.of());
    }
    public RunRecord finish(UUID accountId, long generation, long requestId, UUID runId, boolean success, java.util.Map<UUID,RunContribution.Action> contributions) throws SQLException {
        contributions=java.util.Map.copyOf(contributions);
        if (generation < 1 || requestId < 1 || password == null || password.isBlank()) throw new IllegalArgumentException("Invalid run result.");
        try (var connection = DriverManager.getConnection(url, username, password)) { connection.setAutoCommit(false); try {
            UUID active = activeCharacter(connection, accountId, generation); RunRecord replay = replay(connection, accountId, generation, requestId, success ? "COMPLETE" : "FAIL"); if (replay != null) { connection.commit(); return replay; }
            RunRecord run = lockRun(connection, runId);
            if(!participant(connection,runId,active))throw new IllegalArgumentException("Not a run participant.");
            if((success&&"COMPLETED".equals(run.state()))||(!success&&"FAILED".equals(run.state()))){connection.commit();return new RunRecord(runId,run.difficulty(),run.partySize(),run.scalingTier(),run.state(),true);}
            if(!"ACTIVE".equals(run.state()))throw new IllegalArgumentException("Run cannot be finished.");
            if (success) {
                for(var entry:contributions.entrySet())writeContribution(connection,runId,entry.getKey(),entry.getValue(),false);
                rewards.grant(connection, runId, run.difficulty());
                AuctionRepository.create(connection, runId);
                try (var increment = connection.prepareStatement("UPDATE dalmaru.character_run_weekly_clears SET clear_count=clear_count+1 WHERE character_id IN (SELECT p.character_id FROM dalmaru.run_participants p WHERE p.run_id=? AND NOT EXISTS(SELECT 1 FROM dalmaru.run_contributions c WHERE c.run_id=p.run_id AND c.character_id=p.character_id AND c.revoked)) AND content_id=? AND difficulty=? AND week_id=(SELECT week_id FROM dalmaru.run_instances WHERE run_id=?)")) { increment.setObject(1, runId); increment.setString(2, CONTENT); increment.setString(3, run.difficulty().name()); increment.setObject(4, runId); increment.executeUpdate(); }
            }
            String state = success ? "COMPLETED" : "FAILED"; try (var update = connection.prepareStatement("UPDATE dalmaru.run_instances SET state=?, finished_at=CURRENT_TIMESTAMP WHERE run_id=?")) { update.setString(1, state); update.setObject(2, runId); update.executeUpdate(); }
            request(connection, accountId, generation, requestId, success ? "COMPLETE" : "FAIL", runId, state); connection.commit(); return new RunRecord(runId, run.difficulty(), run.partySize(), run.scalingTier(), state, false);
        } catch (Exception exception) { connection.rollback(); throw exception; } }
    }
    /** Called only with server-verified evidence, never with a client-supplied eligibility flag. */
    public void recordContribution(UUID runId,UUID characterId,RunContribution.Action action) throws SQLException {
        changeContribution(runId,characterId,java.util.Objects.requireNonNull(action),false);
    }
    public void revokeContribution(UUID runId,UUID characterId) throws SQLException {
        changeContribution(runId,characterId,null,true);
    }
    private void changeContribution(UUID runId,UUID characterId,RunContribution.Action action,boolean revoked) throws SQLException {
        try(var connection=DriverManager.getConnection(url,username,password)){connection.setAutoCommit(false);try{
            if(!"ACTIVE".equals(lockRun(connection,runId).state()))throw new IllegalArgumentException("Run is not active.");
            writeContribution(connection,runId,characterId,action,revoked);connection.commit();
        }catch(Exception error){connection.rollback();throw error;}}
    }
    static void writeContribution(Connection connection,UUID runId,UUID characterId,RunContribution.Action action,boolean revoked) throws SQLException {
        try(var statement=connection.prepareStatement("INSERT INTO dalmaru.run_contributions(run_id,character_id,action,revoked) SELECT run_id,character_id,?,? FROM dalmaru.run_participants WHERE run_id=? AND character_id=? ON CONFLICT(run_id,character_id) DO UPDATE SET action=COALESCE(dalmaru.run_contributions.action,EXCLUDED.action),revoked=dalmaru.run_contributions.revoked OR EXCLUDED.revoked")){
            statement.setString(1,action==null?null:action.name());statement.setBoolean(2,revoked);statement.setObject(3,runId);statement.setObject(4,characterId);
            if(statement.executeUpdate()!=1)throw new IllegalArgumentException("Not an entry participant.");
        }
    }
    public RunRecord active(UUID characterId) throws SQLException {
        return resume(characterId);
    }
    public RunRecord pauseIfUnattended(UUID characterId, List<UUID> connectedCharacters) throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try (var connection = DriverManager.getConnection(url, username, password)) { connection.setAutoCommit(false); try {
            RunRecord run = activeForUpdate(connection, characterId); if (run == null || !"ACTIVE".equals(run.state()) || hasConnectedParticipant(connection, run.runId(), connectedCharacters)) { connection.commit(); return null; }
            try (var update = connection.prepareStatement("UPDATE dalmaru.run_instances SET state='PAUSED', paused_at=CURRENT_TIMESTAMP, pause_expires_at=CURRENT_TIMESTAMP + INTERVAL '180 seconds' WHERE run_id=?")) { update.setObject(1, run.runId()); update.executeUpdate(); }
            connection.commit(); return new RunRecord(run.runId(), run.difficulty(), run.partySize(), run.scalingTier(), "PAUSED", false);
        } catch (Exception exception) { connection.rollback(); throw exception; } }
    }
    public RunRecord resume(UUID characterId) throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try (var connection = DriverManager.getConnection(url, username, password)) { connection.setAutoCommit(false); try {
            RunRecord run=activeForUpdate(connection,characterId);if(run==null){connection.commit();return null;}
            // Legacy definitions cannot be reconstructed; keep return checkpoints and earned rewards.
            try(var legacy=connection.prepareStatement("UPDATE dalmaru.run_instances SET state='FAILED',finished_at=CURRENT_TIMESTAMP WHERE run_id=? AND content_snapshot IS NULL AND state IN ('ACTIVE','PAUSED')")){
                legacy.setObject(1,run.runId());if(legacy.executeUpdate()==1){connection.commit();return null;}
            }
            try(var expiry=connection.prepareStatement("SELECT 1 FROM dalmaru.run_instances WHERE run_id=? AND deadline_at>CURRENT_TIMESTAMP AND (state='ACTIVE' OR pause_expires_at>=CURRENT_TIMESTAMP)")){
                expiry.setObject(1,run.runId());try(var row=expiry.executeQuery()){if(!row.next())throw new IllegalArgumentException("Run has expired.");}
            }
            if(!"PAUSED".equals(run.state())){connection.commit();return run;}
            try (var update = connection.prepareStatement("UPDATE dalmaru.run_instances SET state='ACTIVE', paused_at=NULL, pause_expires_at=NULL WHERE run_id=? AND deadline_at>CURRENT_TIMESTAMP AND pause_expires_at>=CURRENT_TIMESTAMP")) { update.setObject(1, run.runId()); if (update.executeUpdate() != 1) throw new IllegalArgumentException("Run has expired."); }
            connection.commit(); return new RunRecord(run.runId(), run.difficulty(), run.partySize(), run.scalingTier(), "ACTIVE", false);
        } catch (Exception exception) { connection.rollback(); throw exception; } }
    }
    public boolean cancelNewEntry(UUID runId,UUID accountId,long generation,long requestId) throws SQLException {
        try(var connection=DriverManager.getConnection(url,username,password);var update=connection.prepareStatement("UPDATE dalmaru.run_instances r SET state='FAILED',finished_at=CURRENT_TIMESTAMP WHERE r.run_id=? AND r.state IN ('ACTIVE','PAUSED') AND EXISTS (SELECT 1 FROM dalmaru.run_requests q WHERE q.run_id=r.run_id AND q.account_id=? AND q.connection_generation=? AND q.request_id=? AND q.action='ENTER')")){
            update.setObject(1,runId);update.setObject(2,accountId);update.setLong(3,generation);update.setLong(4,requestId);return update.executeUpdate()==1;
        }
    }
    public void cancelUnfinished() throws SQLException {
        try(var connection=DriverManager.getConnection(url,username,password);var update=connection.prepareStatement("UPDATE dalmaru.run_instances SET state='FAILED',finished_at=CURRENT_TIMESTAMP WHERE state IN ('ACTIVE','PAUSED')")){update.executeUpdate();}
    }
    public record ReturnPosition(UUID runId,String dimension,double x,double y,double z,String mode) { }
    public void saveReturn(UUID runId,UUID characterId,String dimension,double x,double y,double z) throws SQLException {saveReturn(runId,characterId,dimension,x,y,z,"survival");}
    public void saveReturn(UUID runId,UUID characterId,String dimension,double x,double y,double z,String mode) throws SQLException {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Invalid return position.");
        try(var connection=DriverManager.getConnection(url,username,password);var update=connection.prepareStatement("UPDATE dalmaru.run_participants SET return_dimension=?,return_x=?,return_y=?,return_z=?,return_mode=?,return_pending=TRUE WHERE run_id=? AND character_id=? AND return_dimension IS NULL")){
            update.setString(1,dimension);update.setDouble(2,x);update.setDouble(3,y);update.setDouble(4,z);update.setString(5,mode);update.setObject(6,runId);update.setObject(7,characterId);
            if(update.executeUpdate()==0)try(var check=connection.prepareStatement("SELECT 1 FROM dalmaru.run_participants WHERE run_id=? AND character_id=? AND return_dimension IS NOT NULL")){
                check.setObject(1,runId);check.setObject(2,characterId);try(var row=check.executeQuery()){if(!row.next())throw new IllegalArgumentException("Return owner is not a participant.");}
            }
        }
    }
    public ReturnPosition pendingReturn(UUID characterId) throws SQLException {
        try(var connection=DriverManager.getConnection(url,username,password);var query=connection.prepareStatement("SELECT p.run_id,return_dimension,return_x,return_y,return_z,return_mode FROM dalmaru.run_participants p JOIN dalmaru.run_instances r ON r.run_id=p.run_id WHERE p.character_id=? AND p.return_pending AND (r.state IN ('FAILED','COMPLETED') OR EXISTS (SELECT 1 FROM dalmaru.run_member_lifecycle l WHERE l.run_id=p.run_id AND l.character_id=p.character_id AND l.left_at IS NOT NULL)) ORDER BY r.started_at DESC LIMIT 1")){
            query.setObject(1,characterId);try(var row=query.executeQuery()){return row.next()?new ReturnPosition(row.getObject(1,UUID.class),row.getString(2),row.getDouble(3),row.getDouble(4),row.getDouble(5),row.getString(6)):null;}
        }
    }
    public boolean acknowledgeReturn(UUID accountId,long generation,UUID characterId,UUID runId) throws SQLException {
        try(var connection=DriverManager.getConnection(url,username,password)){connection.setAutoCommit(false);try{
            boolean cleared;
            try(var update=connection.prepareStatement("UPDATE dalmaru.run_participants p SET return_pending=FALSE FROM dalmaru.run_instances r,dalmaru.accounts a WHERE r.run_id=p.run_id AND p.run_id=? AND p.character_id=? AND p.return_pending AND (r.state IN ('FAILED','COMPLETED') OR EXISTS (SELECT 1 FROM dalmaru.run_member_lifecycle l WHERE l.run_id=p.run_id AND l.character_id=p.character_id AND l.left_at IS NOT NULL)) AND a.account_id=? AND a.connection_generation=? AND a.active_character_id=p.character_id")) {
                update.setObject(1,runId);update.setObject(2,characterId);update.setObject(3,accountId);update.setLong(4,generation);cleared=update.executeUpdate()==1;
            }
            if(cleared)clearLifecycleReturn(connection,runId,characterId);
            connection.commit();return cleared;
        }catch(Exception error){connection.rollback();throw error;}}
    }
    /** Server-trusted acknowledgement for a member the runtime has just put back; only valid after the member left the roster. */
    public boolean acknowledgeExitReturn(UUID runId,UUID characterId) throws SQLException {
        try(var connection=DriverManager.getConnection(url,username,password)){connection.setAutoCommit(false);try{
            boolean cleared;
            try(var update=connection.prepareStatement("UPDATE dalmaru.run_participants p SET return_pending=FALSE WHERE p.run_id=? AND p.character_id=? AND p.return_pending AND EXISTS (SELECT 1 FROM dalmaru.run_member_lifecycle l WHERE l.run_id=p.run_id AND l.character_id=p.character_id AND l.left_at IS NOT NULL)")) {
                update.setObject(1,runId);update.setObject(2,characterId);cleared=update.executeUpdate()==1;
            }
            if(cleared)clearLifecycleReturn(connection,runId,characterId);
            connection.commit();return cleared;
        }catch(Exception error){connection.rollback();throw error;}}
    }
    private static void clearLifecycleReturn(Connection connection,UUID runId,UUID characterId) throws SQLException {
        try(var update=connection.prepareStatement("UPDATE dalmaru.run_member_lifecycle SET return_pending=FALSE WHERE run_id=? AND character_id=?")){update.setObject(1,runId);update.setObject(2,characterId);update.executeUpdate();}
    }
    Connection connect() throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        return DriverManager.getConnection(url, username, password);
    }
    public int expireTimedOut() throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        new AuctionRepository().settleExpired();
        try (var connection = DriverManager.getConnection(url, username, password); var update = connection.prepareStatement("UPDATE dalmaru.run_instances SET state='FAILED', finished_at=CURRENT_TIMESTAMP WHERE state IN ('ACTIVE', 'PAUSED') AND (deadline_at<=CURRENT_TIMESTAMP OR pause_expires_at<CURRENT_TIMESTAMP)")) { return update.executeUpdate(); }
    }
    public List<RunReward> rewards(UUID runId) throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try (var connection = DriverManager.getConnection(url, username, password)) { return rewards.rewards(connection, runId); }
    }
    private UUID activeCharacter(Connection connection, UUID accountId, long generation) throws SQLException { try (var statement = connection.prepareStatement("SELECT active_character_id FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? FOR UPDATE")) { statement.setObject(1, accountId); statement.setLong(2, generation); try (var result = statement.executeQuery()) { if (!result.next() || result.getObject(1) == null) throw new IllegalArgumentException("No active character."); return result.getObject(1, UUID.class); } } }
    private void validateParticipant(Connection connection, UUID characterId, RunDifficulty difficulty) throws SQLException { try (var statement = connection.prepareStatement("SELECT level FROM dalmaru.character_profiles WHERE character_id=?")) { statement.setObject(1, characterId); try (var result = statement.executeQuery()) { if (!result.next() || result.getInt(1) < difficulty.requiredLevel()) throw new IllegalArgumentException("Level requirement not met."); } } if (difficulty == RunDifficulty.TRAINING && !clearedFirstStep(connection, characterId)) throw new IllegalArgumentException("Training is locked."); }
    private boolean clearedFirstStep(Connection connection, UUID characterId) throws SQLException { try (var statement = connection.prepareStatement("SELECT 1 FROM dalmaru.character_run_weekly_clears WHERE character_id=? AND content_id=? AND difficulty='FIRST_STEP' AND clear_count>0 LIMIT 1")) { statement.setObject(1, characterId); statement.setString(2, CONTENT); try (var result = statement.executeQuery()) { return result.next(); } } }
    private void reserveAvailable(Connection connection, UUID characterId, RunDifficulty difficulty, LocalDate week) throws SQLException { try (var active = connection.prepareStatement("SELECT 1 FROM dalmaru.run_participants p JOIN dalmaru.run_instances r ON r.run_id=p.run_id WHERE p.character_id=? AND r.state IN ('ACTIVE','PAUSED') AND NOT EXISTS (SELECT 1 FROM dalmaru.run_member_lifecycle l WHERE l.run_id=p.run_id AND l.character_id=p.character_id AND l.left_at IS NOT NULL) FOR UPDATE")) { active.setObject(1, characterId); try (var result = active.executeQuery()) { if (result.next()) throw new IllegalArgumentException("Character already has an active run."); } } try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_run_weekly_clears (character_id, content_id, difficulty, week_id) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) { insert.setObject(1, characterId); insert.setString(2, CONTENT); insert.setString(3, difficulty.name()); insert.setObject(4, week); insert.executeUpdate(); } try (var statement = connection.prepareStatement("SELECT clear_count FROM dalmaru.character_run_weekly_clears WHERE character_id=? AND content_id=? AND difficulty=? AND week_id=? FOR UPDATE")) { statement.setObject(1, characterId); statement.setString(2, CONTENT); statement.setString(3, difficulty.name()); statement.setObject(4, week); try (var result = statement.executeQuery()) { result.next(); if (result.getInt(1) >= 3) throw new IllegalArgumentException("Weekly clear limit reached."); } } }
    private static com.google.gson.JsonObject entryBody(RunDifficulty difficulty, List<UUID> participants) {
        var body = new com.google.gson.JsonObject(); body.addProperty("difficulty", difficulty.name());
        var ids = new com.google.gson.JsonArray(); for (UUID id : participants) ids.add(id.toString()); body.add("participants", ids); return body;
    }
    private static com.google.gson.JsonObject entryOutcome(RunRecord run) {
        var json = new com.google.gson.JsonObject(); json.addProperty("runId", run.runId().toString()); json.addProperty("difficulty", run.difficulty().name()); json.addProperty("partySize", run.partySize()); json.addProperty("scalingTier", run.scalingTier()); json.addProperty("state", run.state()); return json;
    }
    private RunRecord acceptedReplay(Connection connection, UUID runId, RunDifficulty difficulty, List<UUID> participants) throws SQLException {
        RunRecord current = lockRun(connection, runId);
        if (!"ACTIVE".equals(current.state()) || current.difficulty() != difficulty || current.partySize() != participants.size()) throw new IllegalArgumentException("Entry replay is no longer active or has different content.");
        for (UUID id : participants) if (!participant(connection, current.runId(), id)) throw new IllegalArgumentException("Entry replay roster differs.");
        return new RunRecord(current.runId(), current.difficulty(), current.partySize(), current.scalingTier(), current.state(), true);
    }
    private RunRecord replay(Connection connection, UUID accountId, long generation, long requestId, String action) throws SQLException { try (var statement = connection.prepareStatement("SELECT r.run_id, r.difficulty, r.party_size, r.scaling_tier, q.state FROM dalmaru.run_requests q JOIN dalmaru.run_instances r ON r.run_id=q.run_id WHERE q.account_id=? AND q.connection_generation=? AND q.request_id=?")) { statement.setObject(1, accountId); statement.setLong(2, generation); statement.setLong(3, requestId); try (var result = statement.executeQuery()) { if (!result.next()) return null; if (!action.equals(result.getString(5).equals("ACTIVE") ? "ENTER" : result.getString(5).equals("COMPLETED") ? "COMPLETE" : "FAIL")) throw new IllegalArgumentException("Request ID was reused."); return new RunRecord(result.getObject(1, UUID.class), RunDifficulty.valueOf(result.getString(2)), result.getInt(3), result.getInt(4), result.getString(5), true); } } }
    private RunRecord lockRun(Connection connection, UUID runId) throws SQLException { try (var statement = connection.prepareStatement("SELECT run_id, difficulty, party_size, scaling_tier, state FROM dalmaru.run_instances WHERE run_id=? FOR UPDATE")) { statement.setObject(1, runId); try (var result = statement.executeQuery()) { if (!result.next()) throw new IllegalArgumentException("Run was not found."); return new RunRecord(result.getObject(1, UUID.class), RunDifficulty.valueOf(result.getString(2)), result.getInt(3), result.getInt(4), result.getString(5), false); } } }
    private RunRecord activeForUpdate(Connection connection, UUID characterId) throws SQLException { try (var statement = connection.prepareStatement("SELECT r.run_id, r.difficulty, r.party_size, r.scaling_tier, r.state FROM dalmaru.run_instances r JOIN dalmaru.run_participants p ON p.run_id=r.run_id WHERE p.character_id=? AND r.state IN ('ACTIVE', 'PAUSED') AND NOT EXISTS (SELECT 1 FROM dalmaru.run_member_lifecycle l WHERE l.run_id=p.run_id AND l.character_id=p.character_id AND l.left_at IS NOT NULL) ORDER BY r.started_at DESC LIMIT 1 FOR UPDATE")) { statement.setObject(1, characterId); try (var result = statement.executeQuery()) { return result.next() ? new RunRecord(result.getObject(1, UUID.class), RunDifficulty.valueOf(result.getString(2)), result.getInt(3), result.getInt(4), result.getString(5), false) : null; } } }
    private boolean hasConnectedParticipant(Connection connection, UUID runId, List<UUID> connectedCharacters) throws SQLException { if (connectedCharacters.isEmpty()) return false; try (var statement = connection.prepareStatement("SELECT 1 FROM dalmaru.run_participants p WHERE p.run_id=? AND p.character_id = ANY (?) AND NOT EXISTS (SELECT 1 FROM dalmaru.run_member_lifecycle l WHERE l.run_id=p.run_id AND l.character_id=p.character_id AND l.left_at IS NOT NULL) LIMIT 1")) { statement.setObject(1, runId); statement.setArray(2, connection.createArrayOf("uuid", connectedCharacters.toArray())); try (var result = statement.executeQuery()) { return result.next(); } } }
    private boolean participant(Connection connection, UUID runId, UUID characterId) throws SQLException { try (var statement = connection.prepareStatement("SELECT 1 FROM dalmaru.run_participants p WHERE p.run_id=? AND p.character_id=? AND NOT EXISTS (SELECT 1 FROM dalmaru.run_member_lifecycle l WHERE l.run_id=p.run_id AND l.character_id=p.character_id AND l.left_at IS NOT NULL)")) { statement.setObject(1, runId); statement.setObject(2, characterId); try (var result = statement.executeQuery()) { return result.next(); } } }
    private void request(Connection connection, UUID accountId, long generation, long requestId, String action, UUID runId, String state) throws SQLException { try (var statement = connection.prepareStatement("INSERT INTO dalmaru.run_requests VALUES (?, ?, ?, ?, ?, ?)")) { statement.setObject(1, accountId); statement.setLong(2, generation); statement.setLong(3, requestId); statement.setString(4, action); statement.setObject(5, runId); statement.setString(6, state); statement.executeUpdate(); } }
    static LocalDate weekId() { return RunEncounterRules.weekId(Instant.now()); }
}
