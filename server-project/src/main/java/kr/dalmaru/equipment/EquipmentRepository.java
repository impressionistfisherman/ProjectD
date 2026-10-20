package kr.dalmaru.equipment;

import java.security.SecureRandom;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.google.gson.JsonObject;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

public final class EquipmentRepository {
    public static final String UNEQUIP_BEFORE_INHERIT = "장착 해제 후 계승할 수 있습니다.";
    private final String url = "jdbc:postgresql://" + System.getenv().getOrDefault("DALMARU_DB_HOST", "localhost") + ":" + System.getenv().getOrDefault("DALMARU_DB_PORT", "15432") + "/" + System.getenv().getOrDefault("DALMARU_DB_NAME", "ProjectD");
    private final String username = System.getenv().getOrDefault("DALMARU_DB_USER", "postgres");
    private final String password = System.getenv("DALMARU_DB_PASSWORD");
    private final SecureRandom random;
    public EquipmentRepository() { this(new SecureRandom()); }
    EquipmentRepository(SecureRandom random) { this.random=java.util.Objects.requireNonNull(random); }

    public EnhancementResult enhance(UUID accountId, long generation, long requestId, UUID equipmentId) throws SQLException {
        return enhance(accountId,generation,requestId,equipmentId,null);
    }
    public EnhancementResult enhance(UUID accountId,long generation,long requestId,UUID equipmentId,RequestEnvelope envelope)throws SQLException {
        if (generation < 1 || requestId < 1 || password == null || password.isBlank()) throw new IllegalArgumentException("Invalid enhancement request.");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.setAutoCommit(false);
            try {
                UUID characterId;
                try (var active = connection.prepareStatement("SELECT active_character_id FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? FOR UPDATE")) { active.setObject(1, accountId); active.setLong(2, generation); try (var result = active.executeQuery()) { if (!result.next() || result.getObject(1) == null) throw new IllegalArgumentException("Account has no active character."); characterId = result.getObject(1, UUID.class); } }
                var body=new JsonObject();body.addProperty("equipmentId",equipmentId.toString());
                if(envelope==null)envelope=RequestEnvelope.legacy(accountId,generation,characterId,"enhance_item",requestId,body);
                if(envelope.connectionGeneration()!=generation||!envelope.activeCharacterId().equals(characterId)||!envelope.operation().equals("enhance_item")||!envelope.payload().equals(body))throw new IllegalArgumentException("Enhancement envelope does not match active session/body");
                var stored=RequestJournal.begin(connection,accountId,requestId,envelope);
                requestId=stored.legacyRequestId();
                if(stored.result()!=null){
                    var latest=read(connection,equipmentId);if(!characterId.equals(latest.characterId()))throw new IllegalArgumentException("Replay is not owned by active character");
                    var original=stored.result();
                    var replay=new EnhancementResult(latest,original.get("success").getAsBoolean(),true,original.get("enhancement").getAsInt(),original.get("consecutiveFailures").getAsInt(),stored.receipt());
                    connection.commit();return replay;
                }
                try (var request = connection.prepareStatement("SELECT equipment_id, success, enhancement, consecutive_failures FROM dalmaru.character_enhancement_requests WHERE account_id=? AND connection_generation=? AND request_id=?")) {
                    request.setObject(1, accountId); request.setLong(2, generation); request.setLong(3, requestId);
                    try (var result = request.executeQuery()) { if (result.next()) { if (!equipmentId.equals(result.getObject(1, UUID.class))) throw new IllegalArgumentException("Request ID was reused."); EquipmentRecord equipment = read(connection, equipmentId); if(!characterId.equals(equipment.characterId()))throw new IllegalArgumentException("Replay is not owned by active character."); var replay=confirmEnhancement(connection,stored,new EnhancementResult(equipment,result.getBoolean(2),true,result.getInt(3),result.getInt(4)),null);connection.commit();return replay; } }
                }
                EquipmentRecord equipment = readForUpdate(connection, equipmentId);
                if (!characterId.equals(equipment.characterId())) throw new IllegalArgumentException("Equipment not owned by active character.");
                if (equipment.enhancement() >= 15) throw new IllegalArgumentException("Equipment is already at maximum enhancement.");
                validateRequirements(connection, equipment);
                EnhancementRules.Cost cost=EnhancementRules.cost(equipment.enhancement()+1);
                int credit;try(var query=connection.prepareStatement("SELECT inheritance_credit FROM dalmaru.character_equipment WHERE equipment_id=?")){query.setObject(1,equipmentId);try(var row=query.executeQuery()){row.next();credit=row.getInt(1);}}
                int creditUsed=Math.min(credit,cost.copper()),cashSpent=cost.copper()-creditUsed;
                if(creditUsed>0)try(var update=connection.prepareStatement("UPDATE dalmaru.character_equipment SET inheritance_credit=inheritance_credit-? WHERE equipment_id=?")){update.setInt(1,creditUsed);update.setObject(2,equipmentId);update.executeUpdate();}
                EquipmentBinding binding=equipment.binding();
                try(var wallet=connection.prepareStatement("UPDATE dalmaru.character_wallet SET copper=copper-? WHERE character_id=? AND copper>=?")) {
                    wallet.setInt(1,cashSpent);wallet.setObject(2,characterId);wallet.setInt(3,cashSpent);
                    if(wallet.executeUpdate()!=1)throw new IllegalArgumentException("Insufficient copper.");
                }
                if(cost.ironstone()>0) {
                    try(var material=connection.prepareStatement("SELECT quantity,binding FROM dalmaru.character_materials WHERE character_id=? AND material_id='material.ironstone' FOR UPDATE")) {
                        material.setObject(1,characterId);try(var row=material.executeQuery()){
                            if(!row.next()||row.getInt(1)<cost.ironstone())throw new IllegalArgumentException("Insufficient ironstone.");
                            binding=binding.merge(EquipmentBinding.valueOf(row.getString(2)));
                        }
                    }
                    try(var material=connection.prepareStatement("UPDATE dalmaru.character_materials SET quantity=quantity-? WHERE character_id=? AND material_id='material.ironstone'")) {
                        material.setInt(1,cost.ironstone());material.setObject(2,characterId);material.executeUpdate();
                    }
                }
                try(var bind=connection.prepareStatement("UPDATE dalmaru.character_equipment SET binding=?,bound=? WHERE equipment_id=?")) {
                    bind.setString(1,binding.name());bind.setBoolean(2,binding!=EquipmentBinding.FREE);bind.setObject(3,equipmentId);bind.executeUpdate();
                }
                try(var ledger=connection.prepareStatement("INSERT INTO dalmaru.character_currency_ledger(event_id,character_id,source_run_id,reason,copper_delta) VALUES(?,?,NULL,?,?)")) {
                    ledger.setObject(1,UUID.randomUUID());ledger.setObject(2,characterId);ledger.setString(3,"enhancement:"+generation+":"+requestId);ledger.setInt(4,-cashSpent);ledger.executeUpdate();
                }
                boolean success = random.nextInt(100) < EnhancementRules.chance(equipment.enhancement(), equipment.consecutiveFailures());
                int enhancement = success ? equipment.enhancement() + 1 : equipment.enhancement(); int failures = success ? 0 : equipment.consecutiveFailures() + 1;
                try (var update = connection.prepareStatement("UPDATE dalmaru.character_equipment SET enhancement=?, consecutive_failures=? WHERE equipment_id=?")) { update.setInt(1, enhancement); update.setInt(2, failures); update.setObject(3, equipmentId); update.executeUpdate(); }
                try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_enhancement_requests VALUES (?,?,?,?,?,?,?)")) { insert.setObject(1, accountId); insert.setLong(2, generation); insert.setLong(3, requestId); insert.setObject(4, equipmentId); insert.setInt(5, enhancement); insert.setInt(6, failures); insert.setBoolean(7, success); insert.executeUpdate(); }
                var outcome=confirmEnhancement(connection,stored,new EnhancementResult(new EquipmentRecord(equipmentId, characterId, equipment.slotId(), equipment.itemId(), equipment.requiredLevel(), equipment.profession(), enhancement, failures, binding!=EquipmentBinding.FREE, binding, equipment.refinementGrade()), success, false),cost,cashSpent);connection.commit();return outcome;
            } catch (Exception exception) { connection.rollback(); throw exception; }
        }
    }

    private static EnhancementResult confirmEnhancement(Connection c,RequestJournal.Stored stored,EnhancementResult result,EnhancementRules.Cost cost)throws SQLException{
        return confirmEnhancement(c,stored,result,cost,cost==null?0:cost.copper());
    }
    private static EnhancementResult confirmEnhancement(Connection c,RequestJournal.Stored stored,EnhancementResult result,EnhancementRules.Cost cost,int cashSpent)throws SQLException{
        var outcome=new JsonObject();outcome.addProperty("equipmentId",result.equipment().equipmentId().toString());outcome.addProperty("success",result.success());outcome.addProperty("enhancement",result.resultEnhancement());outcome.addProperty("consecutiveFailures",result.resultFailures());
        if(cost!=null){outcome.addProperty("copperSpent",cashSpent);outcome.addProperty("ironstoneSpent",cost.ironstone());outcome.addProperty("inheritanceSpent",cost.copper()-cashSpent);}else outcome.addProperty("legacyResult",true);
        var receipt=RequestJournal.confirm(c,stored,outcome);
        return new EnhancementResult(result.equipment(),result.success(),result.replayed(),result.resultEnhancement(),result.resultFailures(),receipt);
    }

    public record MovementResult(List<EquipmentRecord> equipment,UUID equipmentId,int slotId,boolean replayed,RequestJournal.Receipt receipt) {
        public MovementResult{equipment=List.copyOf(equipment);}
    }
    public List<EquipmentRecord> move(UUID accountId,long generation,long requestId,UUID equipmentId,boolean equip) throws SQLException {
        return moveJournaled(accountId,generation,requestId,equipmentId,equip,null,true).equipment();
    }
    public MovementResult moveJournaled(UUID accountId,long generation,long requestId,UUID equipmentId,boolean equip,RequestEnvelope envelope,boolean editable)throws SQLException {
        if(generation<1||requestId<1||equipmentId==null)throw new IllegalArgumentException("Invalid equipment move.");
        try(var connection=DriverManager.getConnection(url,username,password)) {
            connection.setAutoCommit(false);
            try {
                UUID characterId;
                try(var account=connection.prepareStatement("SELECT active_character_id FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? FOR UPDATE")) {
                    account.setObject(1,accountId);account.setLong(2,generation);try(var row=account.executeQuery()){
                        if(!row.next()||row.getObject(1)==null)throw new IllegalArgumentException("Stale equipment session.");characterId=row.getObject(1,UUID.class);
                    }
                }
                var body=new JsonObject();body.addProperty("equipmentId",equipmentId.toString());body.addProperty("equip",equip);
                if(envelope==null)envelope=RequestEnvelope.legacy(accountId,generation,characterId,"move_equipment",requestId,body);
                if(envelope.connectionGeneration()!=generation||!envelope.activeCharacterId().equals(characterId)||!envelope.operation().equals("move_equipment")||!envelope.payload().equals(body))throw new IllegalArgumentException("Equipment move envelope does not match active session/body");
                var stored=RequestJournal.begin(connection,accountId,requestId,envelope);requestId=stored.legacyRequestId();
                if(stored.result()!=null){
                    var item=read(connection,equipmentId);if(!characterId.equals(item.characterId()))throw new IllegalArgumentException("Foreign replay equipment.");
                    var current=list(connection,characterId);var original=stored.result();connection.commit();return new MovementResult(current,equipmentId,original.get("slotId").getAsInt(),true,stored.receipt());
                }
                EquipmentRecord item=readForUpdate(connection,equipmentId);
                if(!characterId.equals(item.characterId()))throw new IllegalArgumentException("Foreign equipment.");
                int slot=equip?PilotEquipment.slot(item.itemId()):-1;
                try(var replay=connection.prepareStatement("SELECT equipment_id,slot_id FROM dalmaru.character_equipment_move_requests WHERE account_id=? AND connection_generation=? AND request_id=?")) {
                    replay.setObject(1,accountId);replay.setLong(2,generation);replay.setLong(3,requestId);
                    try(var row=replay.executeQuery()){if(row.next()){
                        if(!equipmentId.equals(row.getObject(1,UUID.class))||equip!=(row.getInt(2)>=0))throw new IllegalArgumentException("Move request ID reused.");
                        int originalSlot=row.getInt(2);var receipt=RequestJournal.confirm(connection,stored,movementOutcome(equipmentId,originalSlot));var current=list(connection,characterId);connection.commit();return new MovementResult(current,equipmentId,originalSlot,true,receipt);
                    }}
                }
                if(!editable)throw new IllegalArgumentException("Equipment cannot be changed in the current world state.");
                var worn=new ArrayList<EquipmentRecord>();
                try(var locked=connection.prepareStatement("SELECT equipment_id, character_id, slot_id, item_id, required_level, COALESCE(profession,''), enhancement, consecutive_failures, bound, binding, refinement_grade FROM dalmaru.character_equipment WHERE character_id=? AND location='EQUIPPED' ORDER BY equipment_id FOR UPDATE")){locked.setObject(1,characterId);try(var rows=locked.executeQuery()){while(rows.next())worn.add(record(rows));}}
                if(equip){
                    validateRequirements(connection,item);
                    var profile=kr.dalmaru.account.CharacterRepository.profileForUpdate(connection,characterId);
                    PilotEquipment.requireEquippable(profile,item);
                    slot=PilotEquipment.destination(item,worn);
                }
                for(var other:worn)if(PilotEquipment.displaced(other,item,slot,equip))try(var displaced=connection.prepareStatement("UPDATE dalmaru.character_equipment SET location='INVENTORY',slot_id=NULL WHERE equipment_id=?")){displaced.setObject(1,other.equipmentId());displaced.executeUpdate();}
                try(var update=connection.prepareStatement("UPDATE dalmaru.character_equipment SET location=?,slot_id=?,binding=?,bound=? WHERE equipment_id=?")) {
                    EquipmentBinding binding=equip?EquipmentBinding.CHARACTER:item.binding();
                    update.setString(1,equip?"EQUIPPED":"INVENTORY");if(equip)update.setInt(2,slot);else update.setNull(2,java.sql.Types.SMALLINT);
                    update.setString(3,binding.name());update.setBoolean(4,binding!=EquipmentBinding.FREE);update.setObject(5,equipmentId);update.executeUpdate();
                }
                try(var request=connection.prepareStatement("INSERT INTO dalmaru.character_equipment_move_requests VALUES(?,?,?,?,?)")){
                    request.setObject(1,accountId);request.setLong(2,generation);request.setLong(3,requestId);request.setObject(4,equipmentId);request.setInt(5,slot);request.executeUpdate();
                }
                var receipt=RequestJournal.confirm(connection,stored,movementOutcome(equipmentId,slot));var current=list(connection,characterId);connection.commit();return new MovementResult(current,equipmentId,slot,false,receipt);
            }catch(Exception error){connection.rollback();throw error;}
        }
    }
    private static JsonObject movementOutcome(UUID equipment,int slot){var result=new JsonObject();result.addProperty("equipmentId",equipment.toString());result.addProperty("slotId",slot);return result;}
    public List<EquipmentRecord> list(UUID characterId) throws SQLException {
        if(password==null||password.isBlank())throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try(var connection=DriverManager.getConnection(url,username,password)){return list(connection,characterId);}
    }
    private static List<EquipmentRecord> list(Connection connection,UUID characterId)throws SQLException {
        try (var statement = connection.prepareStatement("SELECT equipment_id, character_id, slot_id, item_id, required_level, COALESCE(profession,''), enhancement, consecutive_failures, bound, binding, refinement_grade FROM dalmaru.character_equipment WHERE character_id=? ORDER BY slot_id")) { statement.setObject(1, characterId); try (var result = statement.executeQuery()) { List<EquipmentRecord> equipment = new ArrayList<>(); while (result.next()) equipment.add(record(result)); return equipment; } }
    }
    public List<EquipmentRecord> equipped(UUID characterId) throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.prepareStatement("SELECT equipment_id, character_id, slot_id, item_id, required_level, COALESCE(profession,''), enhancement, consecutive_failures, bound, binding, refinement_grade FROM dalmaru.character_equipment WHERE character_id=? AND location='EQUIPPED' ORDER BY slot_id")) {
            statement.setObject(1, characterId);
            try (var rows = statement.executeQuery()) {
                List<EquipmentRecord> result = new ArrayList<>();
                while (rows.next()) result.add(record(rows));
                return result;
            }
        }
    }
    public record RefinementOutcome(RefinementResult original,int currentGrade,int currentFailures,RequestJournal.Receipt receipt) {}
    public RefinementResult refine(UUID accountId,long generation,long requestId,UUID equipmentId)throws SQLException {
        return refineJournaled(accountId,generation,requestId,equipmentId,null).original();
    }
    public RefinementOutcome refineJournaled(UUID accountId,long generation,long requestId,UUID equipmentId,RequestEnvelope envelope)throws SQLException {
        if(generation<1||requestId<1||equipmentId==null)throw new IllegalArgumentException("Invalid refinement request.");
        if(password==null||password.isBlank())throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try(var connection=DriverManager.getConnection(url,username,password)){connection.setAutoCommit(false);try{
            UUID characterId; try (var active = connection.prepareStatement("SELECT active_character_id FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? FOR UPDATE")) { active.setObject(1, accountId); active.setLong(2, generation); try (var rows = active.executeQuery()) { if (!rows.next() || rows.getObject(1) == null) throw new IllegalArgumentException("No active character."); characterId = rows.getObject(1, UUID.class); } }
            var body=new JsonObject();body.addProperty("equipmentId",equipmentId.toString());
            if(envelope==null)envelope=RequestEnvelope.legacy(accountId,generation,characterId,"refine_item",requestId,body);
            if(envelope.connectionGeneration()!=generation||!envelope.activeCharacterId().equals(characterId)||!envelope.operation().equals("refine_item")||!envelope.payload().equals(body))throw new IllegalArgumentException("Refinement envelope does not match active session/body");
            var stored=RequestJournal.begin(connection,accountId,requestId,envelope);requestId=stored.legacyRequestId();
            if(stored.result()!=null){var original=stored.result();var result=latestRefinement(connection,characterId,new RefinementResult(equipmentId,original.get("grade").getAsInt(),original.get("failures").getAsInt(),original.get("success").getAsBoolean(),true),stored.receipt());connection.commit();return result;}
            try (var replay = connection.prepareStatement("SELECT equipment_id, grade, failures, success FROM dalmaru.character_refinement_requests WHERE account_id=? AND connection_generation=? AND request_id=?")) { replay.setObject(1, accountId); replay.setLong(2, generation); replay.setLong(3, requestId); try (var rows = replay.executeQuery()) { if (rows.next()) { if (!equipmentId.equals(rows.getObject(1, UUID.class))) throw new IllegalArgumentException("Request ID was reused."); var original=new RefinementResult(equipmentId,rows.getInt(2),rows.getInt(3),rows.getBoolean(4),true);var result=confirmRefinement(connection,stored,characterId,original);connection.commit();return result; } } }
            EquipmentRecord item=readForUpdate(connection,equipmentId);
            if(!characterId.equals(item.characterId()))throw new IllegalArgumentException("Equipment not owned by active character.");
            int grade=item.refinementGrade();var content=kr.dalmaru.content.ContentCatalog.current();content.requireRefinementPath(item.itemId(),grade);
            int failures;try(var query=connection.prepareStatement("SELECT refinement_failures FROM dalmaru.character_equipment WHERE equipment_id=?")){query.setObject(1,equipmentId);try(var row=query.executeQuery()){row.next();failures=row.getInt(1);}}
            var policy=content.refinementPolicy();int copper=policy.attemptCopper().get(grade),percent=policy.keepPercent().get(grade);
            var spend=enhancementSpend(connection,item);long keepCopper=Math.ceilDiv(Math.multiplyExact(spend.copper(),percent),100);int keepIronstone=Math.toIntExact(Math.ceilDiv(Math.multiplyExact(spend.ironstone(),percent),100));
            long reserved=Math.addExact(copper,keepCopper);
            try(var wallet=connection.prepareStatement("SELECT copper FROM dalmaru.character_wallet WHERE character_id=? FOR UPDATE")){wallet.setObject(1,characterId);try(var row=wallet.executeQuery()){if(!row.next()||row.getLong(1)<reserved)throw new IllegalArgumentException("Insufficient copper for refinement and upkeep reservation.");}}
            EquipmentBinding binding=item.binding();
            if(keepIronstone>0)try(var material=connection.prepareStatement("SELECT quantity,binding FROM dalmaru.character_materials WHERE character_id=? AND material_id='material.ironstone' FOR UPDATE")){material.setObject(1,characterId);try(var row=material.executeQuery()){if(!row.next()||row.getInt(1)<keepIronstone)throw new IllegalArgumentException("Insufficient ironstone for upkeep reservation.");binding=binding.merge(EquipmentBinding.valueOf(row.getString(2)));}}
            boolean success=random.nextInt(100)<RefinementRules.chance(grade,failures);int nextGrade=success?grade+1:grade,nextFailures=success?0:failures+1;
            long charged=Math.addExact(copper,success?keepCopper:0);
            try(var wallet=connection.prepareStatement("UPDATE dalmaru.character_wallet SET copper=copper-? WHERE character_id=?")){wallet.setLong(1,charged);wallet.setObject(2,characterId);wallet.executeUpdate();}
            if(success&&keepIronstone>0)try(var material=connection.prepareStatement("UPDATE dalmaru.character_materials SET quantity=quantity-? WHERE character_id=? AND material_id='material.ironstone'")){material.setInt(1,keepIronstone);material.setObject(2,characterId);material.executeUpdate();}
            if(!success)binding=item.binding();
            try(var update=connection.prepareStatement("UPDATE dalmaru.character_equipment SET refinement_grade=?,refinement_failures=?,binding=?,bound=? WHERE equipment_id=?")){update.setInt(1,nextGrade);update.setInt(2,nextFailures);update.setString(3,binding.name());update.setBoolean(4,binding!=EquipmentBinding.FREE);update.setObject(5,equipmentId);update.executeUpdate();}
            try (var request = connection.prepareStatement("INSERT INTO dalmaru.character_refinement_requests VALUES (?, ?, ?, ?, ?, ?, ?)")) { request.setObject(1, accountId); request.setLong(2, generation); request.setLong(3, requestId); request.setObject(4, equipmentId); request.setInt(5, nextGrade); request.setInt(6, nextFailures); request.setBoolean(7, success); request.executeUpdate(); }
            try (var ledger = connection.prepareStatement("INSERT INTO dalmaru.character_currency_ledger (event_id, character_id, source_run_id, reason, copper_delta) VALUES (?, ?, NULL, 'refinement', ?)")) { ledger.setObject(1, stored.receipt().eventId()); ledger.setObject(2, characterId); ledger.setLong(3, -charged); ledger.executeUpdate(); }
            var cost=new JsonObject();cost.addProperty("source",spend.source());cost.addProperty("attemptCopperSpent",copper);cost.addProperty("keepCopperReserved",keepCopper);cost.addProperty("keepIronstoneReserved",keepIronstone);cost.addProperty("keepCopperSpent",success?keepCopper:0);cost.addProperty("keepIronstoneSpent",success?keepIronstone:0);
            var result=confirmRefinement(connection,stored,characterId,new RefinementResult(equipmentId,nextGrade,nextFailures,success,false),cost);connection.commit();return result;
        } catch (Exception exception) { connection.rollback(); throw exception; } }
    }
    private static RefinementOutcome confirmRefinement(Connection connection,RequestJournal.Stored stored,UUID character,RefinementResult original)throws SQLException {
        return confirmRefinement(connection,stored,character,original,null);
    }
    private static RefinementOutcome confirmRefinement(Connection connection,RequestJournal.Stored stored,UUID character,RefinementResult original,JsonObject costs)throws SQLException {
        var outcome=new JsonObject();outcome.addProperty("equipmentId",original.equipmentId().toString());outcome.addProperty("grade",original.grade());outcome.addProperty("failures",original.failures());outcome.addProperty("success",original.success());
        if(costs!=null)for(var entry:costs.entrySet())outcome.add(entry.getKey(),entry.getValue());
        return latestRefinement(connection,character,original,RequestJournal.confirm(connection,stored,outcome));
    }
    private record EnhancementSpend(long copper,long ironstone,String source) {}
    private static EnhancementSpend enhancementSpend(Connection connection,EquipmentRecord item)throws SQLException {
        String complete="o.result->>'equipmentId'=e.equipment_id::text AND jsonb_typeof(o.result->'copperSpent')='number' AND jsonb_typeof(o.result->'ironstoneSpent')='number'";
        String sql="SELECT count(*),count(CASE WHEN "+complete+" THEN 1 END),COALESCE(sum(CASE WHEN "+complete+" THEN (o.result->>'copperSpent')::bigint ELSE 0 END),0),COALESCE(sum(CASE WHEN "+complete+" THEN (o.result->>'ironstoneSpent')::bigint ELSE 0 END),0) FROM dalmaru.character_enhancement_requests e LEFT JOIN dalmaru.operation_requests o ON o.account_id=e.account_id AND o.connection_generation=e.connection_generation AND o.legacy_request_id=e.request_id AND o.operation='enhance_item' WHERE e.equipment_id=?";
        try(var query=connection.prepareStatement(sql)){query.setObject(1,item.equipmentId());try(var row=query.executeQuery()){row.next();if(row.getLong(1)>0&&row.getLong(1)==row.getLong(2))return new EnhancementSpend(row.getLong(3),row.getLong(4),"journal");}}
        if(item.enhancement()<0||item.enhancement()>5)throw new IllegalArgumentException("Legacy enhancement cost is outside the fixed table.");
        long copper=0,ironstone=0;for(int level=1;level<=item.enhancement();level++){var cost=EnhancementRules.cost(level);copper=Math.addExact(copper,cost.copper());ironstone=Math.addExact(ironstone,cost.ironstone());}
        return new EnhancementSpend(copper,ironstone,"table");
    }
    public record RefinementState(UUID equipmentId,int grade,int failures) {}
    public RefinementState refinementState(UUID account,long generation,UUID equipment)throws SQLException {
        if(generation<1||equipment==null)throw new IllegalArgumentException("Invalid refinement query.");
        if(password==null||password.isBlank())throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try(var connection=DriverManager.getConnection(url,username,password)){connection.setAutoCommit(false);try{
            UUID character;try(var query=connection.prepareStatement("SELECT active_character_id FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? FOR UPDATE")){
                query.setObject(1,account);query.setLong(2,generation);try(var row=query.executeQuery()){if(!row.next()||row.getObject(1)==null)throw new IllegalArgumentException("Stale refinement query.");character=row.getObject(1,UUID.class);}
            }
            var state=currentRefinement(connection,character,equipment);connection.commit();return state;
        }catch(Exception error){connection.rollback();throw error;}}
    }
    private static RefinementOutcome latestRefinement(Connection connection,UUID character,RefinementResult original,RequestJournal.Receipt receipt)throws SQLException {
        var current=currentRefinement(connection,character,original.equipmentId());return new RefinementOutcome(original,current.grade(),current.failures(),receipt);
    }
    private static RefinementState currentRefinement(Connection connection,UUID character,UUID equipment)throws SQLException {
        try(var query=connection.prepareStatement("SELECT refinement_grade,refinement_failures FROM dalmaru.character_equipment WHERE equipment_id=? AND character_id=? FOR UPDATE")){
            query.setObject(1,equipment);query.setObject(2,character);try(var rows=query.executeQuery()){
                if(!rows.next())throw new IllegalArgumentException("Refinement equipment is not owned by active character.");
                return new RefinementState(equipment,rows.getInt(1),rows.getInt(2));
            }
        }
    }
    public record InheritanceOutcome(InheritanceResult original,UUID sourceEquipmentId,Integer currentCredit,List<EquipmentRecord> equipment,RequestJournal.Receipt receipt){
        public InheritanceOutcome{equipment=List.copyOf(equipment);}
    }
    public InheritanceResult inherit(UUID accountId,long generation,long requestId,UUID targetId,UUID sourceId)throws SQLException {
        return inheritJournaled(accountId,generation,requestId,targetId,sourceId,null).original();
    }
    public InheritanceOutcome inheritJournaled(UUID accountId,long generation,long requestId,UUID targetId,UUID sourceId,RequestEnvelope envelope)throws SQLException {
        if(generation<1||requestId<1||targetId==null||sourceId==null||targetId.equals(sourceId))throw new IllegalArgumentException("Invalid inheritance request.");
        if(password==null||password.isBlank())throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try(var connection=DriverManager.getConnection(url,username,password)){connection.setAutoCommit(false);try{
            UUID characterId; try (var active = connection.prepareStatement("SELECT active_character_id FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? FOR UPDATE")) { active.setObject(1, accountId); active.setLong(2, generation); try (var rows = active.executeQuery()) { if (!rows.next() || rows.getObject(1) == null) throw new IllegalArgumentException("No active character."); characterId = rows.getObject(1, UUID.class); } }
            var body=new JsonObject();body.addProperty("targetEquipmentId",targetId.toString());body.addProperty("sourceEquipmentId",sourceId.toString());
            if(envelope==null)envelope=RequestEnvelope.legacy(accountId,generation,characterId,"inherit_equipment",requestId,body);
            if(envelope.connectionGeneration()!=generation||!envelope.activeCharacterId().equals(characterId)||!envelope.operation().equals("inherit_equipment")||!envelope.payload().equals(body))throw new IllegalArgumentException("Inheritance envelope does not match active session/body");
            var stored=RequestJournal.begin(connection,accountId,requestId,envelope);requestId=stored.legacyRequestId();
            if(stored.result()!=null){var original=stored.result();var result=latestInheritance(connection,characterId,sourceId,new InheritanceResult(targetId,original.get("credit").getAsInt(),true),stored.receipt());connection.commit();return result;}
            try (var replay = connection.prepareStatement("SELECT target_equipment_id, source_equipment_id, credit FROM dalmaru.character_inheritance_requests WHERE account_id=? AND connection_generation=? AND request_id=?")) { replay.setObject(1, accountId); replay.setLong(2, generation); replay.setLong(3, requestId); try (var rows = replay.executeQuery()) { if (rows.next()) { if (!targetId.equals(rows.getObject(1, UUID.class)) || !sourceId.equals(rows.getObject(2, UUID.class))) throw new IllegalArgumentException("Request ID was reused."); if(!characterId.equals(read(connection,targetId).characterId()))throw new IllegalArgumentException("Legacy inheritance target is not owned by active character.");var original=new InheritanceResult(targetId,rows.getInt(3),true);var result=confirmInheritance(connection,stored,characterId,sourceId,original);connection.commit();return result; } } }
            String targetItem;int targetLevel,targetCredit;try(var target=connection.prepareStatement("SELECT item_id,required_level,inheritance_credit FROM dalmaru.character_equipment WHERE equipment_id=? AND character_id=? AND location='EQUIPPED' FOR UPDATE")){target.setObject(1,targetId);target.setObject(2,characterId);try(var row=target.executeQuery()){if(!row.next())throw new IllegalArgumentException("Target must be equipped by active character.");targetItem=row.getString(1);targetLevel=row.getInt(2);targetCredit=row.getInt(3);}}
            String sourceItem;int sourceLevel;try(var source=connection.prepareStatement("SELECT item_id,required_level,location FROM dalmaru.character_equipment WHERE equipment_id=? AND character_id=? FOR UPDATE")){source.setObject(1,sourceId);source.setObject(2,characterId);try(var row=source.executeQuery()){if(!row.next())throw new IllegalArgumentException("Source must be inventory equipment owned by active character.");if(!"INVENTORY".equals(row.getString(3)))throw new IllegalArgumentException(UNEQUIP_BEFORE_INHERIT);sourceItem=row.getString(1);sourceLevel=row.getInt(2);}}
            var definitions=kr.dalmaru.content.ContentCatalog.current().items();var targetDefinition=definitions.get(targetItem);var sourceDefinition=definitions.get(sourceItem);
            if(targetDefinition==null||sourceDefinition==null||!targetDefinition.enabled()||!sourceDefinition.enabled()||targetDefinition.slot()!=sourceDefinition.slot()||targetDefinition.level()!=targetLevel||sourceDefinition.level()!=sourceLevel||targetLevel<sourceLevel||!targetItem.substring(0,targetItem.lastIndexOf('.')).equals(sourceItem.substring(0,sourceItem.lastIndexOf('.'))))throw new IllegalArgumentException("Equipment family, part or level does not allow inheritance.");
            var spend=enhancementSpend(connection,read(connection,sourceId));int unitPrice=kr.dalmaru.shop.ShopCatalog.buyPrice("material.ironstone");
            if(spend.copper()<0||spend.ironstone()<0)throw new IllegalArgumentException("Negative enhancement spending history.");
            var copperShare=BigInteger.valueOf(spend.copper()).multiply(BigInteger.valueOf(7)).divide(BigInteger.TEN);
            var ironstoneShare=BigInteger.valueOf(spend.ironstone()).multiply(BigInteger.valueOf(unitPrice)).multiply(BigInteger.valueOf(7)).divide(BigInteger.TEN);
            var calculated=copperShare.add(ironstoneShare);if(calculated.compareTo(BigInteger.valueOf(Integer.MAX_VALUE-targetCredit))>0)throw new IllegalArgumentException("Inheritance credit limit exceeded.");int gained=calculated.intValueExact();
            try (var update = connection.prepareStatement("UPDATE dalmaru.character_equipment SET inheritance_credit=inheritance_credit+? WHERE equipment_id=?")) { update.setInt(1, gained); update.setObject(2, targetId); update.executeUpdate(); }
            EquipmentBinding inherited=read(connection,targetId).binding().merge(read(connection,sourceId).binding());
            try(var binding=connection.prepareStatement("UPDATE dalmaru.character_equipment SET binding=?,bound=? WHERE equipment_id=?")){binding.setString(1,inherited.name());binding.setBoolean(2,inherited!=EquipmentBinding.FREE);binding.setObject(3,targetId);binding.executeUpdate();}
            try (var delete = connection.prepareStatement("DELETE FROM dalmaru.character_equipment WHERE equipment_id=?")) { delete.setObject(1, sourceId); delete.executeUpdate(); }
            try (var request = connection.prepareStatement("INSERT INTO dalmaru.character_inheritance_requests VALUES (?, ?, ?, ?, ?, ?)")) { request.setObject(1, accountId); request.setLong(2, generation); request.setLong(3, requestId); request.setObject(4, targetId); request.setObject(5, sourceId); request.setInt(6, gained); request.executeUpdate(); }
            var costs=new JsonObject();costs.addProperty("source",spend.source());costs.addProperty("sourceCopperSpent",spend.copper());costs.addProperty("sourceIronstoneSpent",spend.ironstone());costs.addProperty("ironstoneUnitPrice",unitPrice);
            var result=confirmInheritance(connection,stored,characterId,sourceId,new InheritanceResult(targetId,gained,false),costs);connection.commit();return result;
        } catch (Exception exception) { connection.rollback(); throw exception; } }
    }
    private static InheritanceOutcome confirmInheritance(Connection connection,RequestJournal.Stored stored,UUID character,UUID source,InheritanceResult original)throws SQLException {
        return confirmInheritance(connection,stored,character,source,original,null);
    }
    private static InheritanceOutcome confirmInheritance(Connection connection,RequestJournal.Stored stored,UUID character,UUID source,InheritanceResult original,JsonObject costs)throws SQLException {
        var outcome=new JsonObject();outcome.addProperty("targetEquipmentId",original.targetEquipmentId().toString());outcome.addProperty("sourceEquipmentId",source.toString());outcome.addProperty("credit",original.credit());
        if(costs!=null)for(var entry:costs.entrySet())outcome.add(entry.getKey(),entry.getValue());
        return latestInheritance(connection,character,source,original,RequestJournal.confirm(connection,stored,outcome));
    }
    private static InheritanceOutcome latestInheritance(Connection connection,UUID character,UUID source,InheritanceResult original,RequestJournal.Receipt receipt)throws SQLException {
        Integer current=null;try(var query=connection.prepareStatement("SELECT inheritance_credit FROM dalmaru.character_equipment WHERE equipment_id=? AND character_id=?")){
            query.setObject(1,original.targetEquipmentId());query.setObject(2,character);try(var row=query.executeQuery()){if(row.next())current=row.getInt(1);}
        }
        return new InheritanceOutcome(original,source,current,list(connection,character),receipt);
    }
    public List<EquipmentRecord> listOrCreate(UUID characterId) throws SQLException {
        if (password == null || password.isBlank()) throw new SQLException("DALMARU_DB_PASSWORD not configured.");
        try (var connection = DriverManager.getConnection(url, username, password)) { connection.setAutoCommit(false); try (var insert = connection.prepareStatement("INSERT INTO dalmaru.character_equipment (equipment_id, character_id, slot_id, item_id, required_level, bound) VALUES (?, ?, ?, ?, 1, TRUE) ON CONFLICT (character_id, slot_id) DO NOTHING")) { for (int slot = 0; slot < 9; slot++) { insert.setObject(1, UUID.randomUUID()); insert.setObject(2, characterId); insert.setInt(3, slot); insert.setString(4, "starter.slot." + slot); insert.addBatch(); } insert.executeBatch(); } connection.commit(); }
        return list(characterId);
    }
    private EquipmentRecord read(Connection connection, UUID id) throws SQLException { try (var statement = connection.prepareStatement("SELECT equipment_id, character_id, slot_id, item_id, required_level, COALESCE(profession,''), enhancement, consecutive_failures, bound, binding, refinement_grade FROM dalmaru.character_equipment WHERE equipment_id=?")) { statement.setObject(1, id); try (var result = statement.executeQuery()) { if (!result.next()) throw new IllegalArgumentException("Equipment was not found."); return record(result); } } }
    private void validateRequirements(Connection connection, EquipmentRecord equipment) throws SQLException { try (var statement = connection.prepareStatement("SELECT p.level, COALESCE(j.base_job,'') FROM dalmaru.character_profiles p LEFT JOIN dalmaru.character_professions j ON j.character_id=p.character_id WHERE p.character_id=?")) { statement.setObject(1, equipment.characterId()); try (var result = statement.executeQuery()) { if (!result.next() || result.getInt(1) < equipment.requiredLevel() || (!equipment.profession().isBlank() && !equipment.profession().equals(result.getString(2)))) throw new IllegalArgumentException("Character does not meet equipment requirements."); } } }
    private EquipmentRecord readForUpdate(Connection connection, UUID id) throws SQLException { try (var statement = connection.prepareStatement("SELECT equipment_id, character_id, slot_id, item_id, required_level, COALESCE(profession,''), enhancement, consecutive_failures, bound, binding, refinement_grade FROM dalmaru.character_equipment WHERE equipment_id=? FOR UPDATE")) { statement.setObject(1, id); try (var result = statement.executeQuery()) { if (!result.next()) throw new IllegalArgumentException("Equipment was not found."); return record(result); } } }
    private static EquipmentRecord record(java.sql.ResultSet result) throws SQLException { return new EquipmentRecord(result.getObject(1, UUID.class), result.getObject(2, UUID.class), (result.getObject(3)==null ? -1 : result.getInt(3)), result.getString(4), result.getInt(5), result.getString(6), result.getInt(7), result.getInt(8), result.getBoolean(9), EquipmentBinding.valueOf(result.getBoolean(9) && "FREE".equals(result.getString(10)) ? "CHARACTER" : result.getString(10)), result.getInt(11)); }
}
