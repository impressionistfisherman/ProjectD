package kr.dalmaru;

import com.google.gson.*;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.equipment.*;
import kr.dalmaru.request.RequestEnvelope;

public final class RefinementKeepCheck {
    private interface Action {void run()throws Exception;}
    private static final class Roll extends SecureRandom {
        private final int value;int calls;
        Roll(int value){this.value=value;}
        @Override public int nextInt(int bound){calls++;return value;}
    }
    public static void main(String[] args)throws Exception {
        FixtureDatabase.databaseName();UUID account=UUID.randomUUID();String trap="keep_"+account.toString().replace("-","");
        try {
            long generation=new AccountRepository().openSession(account).connectionGeneration();var characters=new CharacterRepository();
            UUID character=characters.create(account,generation,1,1,"keep_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            sql("UPDATE dalmaru.character_profiles SET level=20 WHERE character_id=?",character);sql("INSERT INTO dalmaru.character_professions(character_id,base_job) VALUES(?,'WARRIOR')",character);
            sql("UPDATE dalmaru.character_wallet SET copper=1000 WHERE character_id=?",character);
            sql("INSERT INTO dalmaru.character_materials(character_id,material_id,quantity,binding) VALUES(?,'material.ironstone',100,'ACCOUNT') ON CONFLICT(character_id,material_id) DO UPDATE SET quantity=100,binding='ACCOUNT'",character);
            UUID item=insert(character,0);var winRoll=new Roll(0);var loseRoll=new Roll(99);var win=repository(winRoll);var lose=repository(loseRoll);
            win.enhance(account,generation,3,item);win.enhance(account,generation,4,item);
            check(value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==980,"Actual enhancement charge");
            sql("UPDATE dalmaru.character_materials SET binding='CHARACTER' WHERE character_id=? AND material_id='material.ironstone'",character);
            var failedRequest=envelope(UUID.randomUUID(),generation,character,item);var failed=lose.refineJournaled(account,generation,5,item,failedRequest);
            check(!failed.original().success()&&failed.currentGrade()==0&&failed.currentFailures()==1,"Deterministic refinement failure");
            var failedCost=outcome(failed.receipt().requestId());
            check(failedCost.get("source").getAsString().equals("journal")&&failedCost.get("keepCopperReserved").getAsInt()==2&&failedCost.get("keepIronstoneReserved").getAsInt()==1&&failedCost.get("keepCopperSpent").getAsInt()==0&&failedCost.get("keepIronstoneSpent").getAsInt()==0,"Failure releases upkeep reservation");
            check(wallet(character)==970&&material(character)==99&&find(lose,item,character).binding()==EquipmentBinding.ACCOUNT&&find(lose,item,character).enhancement()==2,"Failure preserves material, enhancement and binding");
            var successRequest=envelope(UUID.randomUUID(),generation,character,item);var success=win.refineJournaled(account,generation,6,item,successRequest);
            check(success.original().success()&&success.currentGrade()==1&&success.currentFailures()==0&&wallet(character)==958&&material(character)==98,"Success charges attempt and upkeep once");
            check(find(win,item,character).enhancement()==2&&find(win,item,character).binding()==EquipmentBinding.CHARACTER,"Success preserves enhancement and stronger material binding");
            check(outcome(success.receipt().requestId()).get("keepCopperSpent").getAsInt()==2&&outcome(success.receipt().requestId()).get("keepIronstoneSpent").getAsInt()==1,"Upkeep has separate journal fields");
            check(value("SELECT copper_delta FROM dalmaru.character_currency_ledger WHERE event_id=?",success.receipt().eventId())==-12,"Ledger includes actual charged upkeep");
            var replay=lose.refineJournaled(account,generation,7,item,successRequest);check(replay.original().replayed()&&replay.original().success()&&wallet(character)==958&&material(character)==98,"Stored success ignores a different roll");
            sql("UPDATE dalmaru.character_wallet SET copper=20 WHERE character_id=?",character);int rolls=loseRoll.calls;
            reject(()->lose.refine(account,generation,8,item));check(wallet(character)==20&&loseRoll.calls==rolls,"Reserve full success cost before rolling");
            sql("UPDATE dalmaru.character_wallet SET copper=1000 WHERE character_id=?",character);sql("UPDATE dalmaru.character_materials SET quantity=0 WHERE character_id=? AND material_id='material.ironstone'",character);
            reject(()->lose.refine(account,generation,9,item));check(wallet(character)==1000&&loseRoll.calls==rolls,"Material reservation rejects before attempt charge or roll");
            sql("UPDATE dalmaru.character_materials SET quantity=100 WHERE character_id=? AND material_id='material.ironstone'",character);
            var rollback=envelope(UUID.randomUUID(),generation,character,item);
            try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){
                q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.request_id='"+rollback.requestId()+"'::uuid THEN RAISE EXCEPTION 'refinement keep confirm rollback'; END IF; RETURN NEW; END $$");
                q.execute("CREATE TRIGGER "+trap+" BEFORE UPDATE ON dalmaru.operation_requests FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            }
            long ledger=value("SELECT count(*) FROM dalmaru.character_currency_ledger WHERE character_id=?",character);
            try{win.refineJournaled(account,generation,10,item,rollback);throw new AssertionError("Confirm did not fail");}catch(SQLException expected){check("P0001".equals(expected.getSQLState()),"Unexpected failure");}
            check(wallet(character)==1000&&material(character)==100&&find(win,item,character).refinementGrade()==1&&find(win,item,character).enhancement()==2&&value("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?",rollback.requestId())==0&&value("SELECT count(*) FROM dalmaru.character_currency_ledger WHERE character_id=?",character)==ledger,"Confirm rollback restores reservation, costs, grade and journals");drop(trap);
            var concurrent=envelope(UUID.randomUUID(),generation,character,item);int before=winRoll.calls;var pool=Executors.newFixedThreadPool(2);
            try{var x=pool.submit(()->win.refineJournaled(account,generation,11,item,concurrent));var y=pool.submit(()->win.refineJournaled(account,generation,12,item,concurrent));check(x.get().original().replayed()!=y.get().original().replayed(),"Concurrent UUID only one attempt");}finally{pool.shutdownNow();}
            check(winRoll.calls==before+1&&wallet(character)==977&&material(character)==99&&find(win,item,character).refinementGrade()==2,"Concurrent UUID charges one next grade, not earlier upkeep");
            UUID old=insert(character,3);var table=win.refineJournaled(account,generation,13,old,envelope(UUID.randomUUID(),generation,character,old));
            check(outcome(table.receipt().requestId()).get("source").getAsString().equals("table")&&outcome(table.receipt().requestId()).get("keepCopperReserved").getAsInt()==4&&outcome(table.receipt().requestId()).get("keepIronstoneReserved").getAsInt()==1&&find(win,old,character).enhancement()==3,"Legacy +3 uses fixed 1..3 table and marks source");
            UUID outside=insert(character,6);reject(()->win.refine(account,generation,14,outside));
            UUID undefined=insert(character,0);sql("UPDATE dalmaru.character_equipment SET item_id='starter.slot.0' WHERE equipment_id=?",undefined);reject(()->win.refine(account,generation,15,undefined));
            sql("UPDATE dalmaru.character_equipment SET refinement_grade=5 WHERE equipment_id=?",old);reject(()->win.refine(account,generation,16,old));
            var content=ContentCatalog.current();check(content.refinementPolicy().keepPercent().equals(List.of(10,15,20,25,30)),"Maintenance percent data");
            System.out.println("PASS refinement keep: defined one-step path, pre-roll reservation, failure release, success-only upkeep, independent cost ledger, deterministic/concurrent UUID, confirm rollback, legacy fixed table and bounds");
        }finally{drop(trap);sql("DELETE FROM dalmaru.character_refinement_requests WHERE account_id=?",account);EnhancementReplayCheck.cleanup(account);}
    }
    private static EquipmentRepository repository(Roll roll)throws Exception{var c=EquipmentRepository.class.getDeclaredConstructor(SecureRandom.class);c.setAccessible(true);return c.newInstance(roll);}
    private static UUID insert(UUID character,int enhancement)throws SQLException{UUID id=UUID.randomUUID();sql("INSERT INTO dalmaru.character_equipment(equipment_id,character_id,item_id,required_level,profession,location,bound,binding,enhancement) VALUES(?,?,'pilot.weapon.two_handed_sword.20',20,'WARRIOR','INVENTORY',false,'FREE',?)",id,character,enhancement);return id;}
    private static EquipmentRecord find(EquipmentRepository repo,UUID item,UUID character)throws SQLException{return repo.list(character).stream().filter(r->r.equipmentId().equals(item)).findFirst().orElseThrow();}
    private static JsonObject outcome(UUID request)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement("SELECT result::text FROM dalmaru.operation_requests WHERE request_id=?")){q.setObject(1,request);try(var row=q.executeQuery()){row.next();return JsonParser.parseString(row.getString(1)).getAsJsonObject();}}}
    private static long wallet(UUID character)throws SQLException{return value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character);}
    private static long material(UUID character)throws SQLException{return value("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='material.ironstone'",character);}
    private static RequestEnvelope envelope(UUID id,long generation,UUID character,UUID item){var body=new JsonObject();body.addProperty("equipmentId",item.toString());return new RequestEnvelope(id,generation,character,"refine_item",body);}
    private static void sql(String text,Object... args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
    private static long value(String text,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){q.setObject(1,id);try(var r=q.executeQuery()){r.next();return r.getLong(1);}}}
    private static void drop(String trap)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.operation_requests");q.execute("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}}
    private static void reject(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid refinement accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
