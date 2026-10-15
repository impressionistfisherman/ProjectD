package kr.dalmaru;

import com.google.gson.JsonObject;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.equipment.*;
import kr.dalmaru.request.*;

public final class RefinementJournalCheck {
    private interface Action{void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        if(!"oscar_p1_fixture".equals(System.getenv("DALMARU_DB_NAME")))throw new IllegalStateException("Dedicated P1 fixture required");
        UUID account=UUID.randomUUID();String trap="oscar_refine_fail_"+account.toString().replace("-","");
        try {
            var session=new AccountRepository().openSession(account);var characters=new CharacterRepository();long generation=session.connectionGeneration();
            UUID character=characters.create(account,generation,1,1,"refine_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            UUID inactive=characters.create(account,generation,99,2,"refother_"+account.toString().substring(0,8)).character().characterId();
            sql("INSERT INTO dalmaru.character_wallet(character_id,copper) VALUES(?,1000)",character);
            var repository=new EquipmentRepository();UUID item=repository.listOrCreate(character).getFirst().equipmentId(),foreign=repository.listOrCreate(inactive).getFirst().equipmentId();
            var first=envelope(UUID.randomUUID(),generation,character,item);var pool=Executors.newFixedThreadPool(2);EquipmentRepository.RefinementOutcome saved;
            try {
                var a=pool.submit(()->repository.refineJournaled(account,generation,3,item,first));
                var b=pool.submit(()->repository.refineJournaled(account,generation,4,item,first));
                var ar=a.get();var br=b.get();check(ar.original().replayed()!=br.original().replayed(),"Concurrent single attempt");saved=ar.original().replayed()?br:ar;
            }finally{pool.shutdownNow();}
            check(value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==990&&value("SELECT count(*) FROM dalmaru.character_currency_ledger WHERE event_id=?",saved.receipt().eventId())==1,"Single charge and journal event ledger");
            var second=envelope(UUID.randomUUID(),generation,character,item);var changed=repository.refineJournaled(account,generation,5,item,second);
            long wallet=value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character);
            var replay=repository.refineJournaled(account,generation,50,item,first);
            check(replay.original().replayed()&&replay.original().grade()==saved.original().grade()&&replay.original().failures()==saved.original().failures()&&replay.original().success()==saved.original().success()&&replay.currentGrade()==changed.currentGrade()&&replay.currentFailures()==changed.currentFailures(),"Original roll and latest refinement state");
            check(value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==wallet,"No replay charge");
            reject(()->repository.refineJournaled(account,generation,51,foreign,envelope(first.requestId(),generation,character,foreign)));
            reject(()->repository.refineJournaled(account,generation,51,foreign,envelope(UUID.randomUUID(),generation,character,foreign)));
            repository.refine(account,generation,6,item);
            sql("DELETE FROM dalmaru.operation_requests WHERE request_id=?",second.requestId());
            wallet=value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character);
            var legacy=repository.refineJournaled(account,generation,5,item,null);
            check(legacy.original().replayed()&&legacy.original().grade()==changed.original().grade()&&legacy.original().failures()==changed.original().failures()&&value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==wallet,"Legacy result adoption without charge");
            var failed=envelope(UUID.randomUUID(),generation,character,item);
            long grade=value("SELECT refinement_grade FROM dalmaru.character_equipment WHERE equipment_id=?",item),failures=value("SELECT refinement_failures FROM dalmaru.character_equipment WHERE equipment_id=?",item),ledger=value("SELECT count(*) FROM dalmaru.character_currency_ledger WHERE character_id=?",character);
            try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){
                q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.equipment_id='"+item+"'::uuid THEN RAISE EXCEPTION 'fixture refine rollback'; END IF; RETURN NEW; END $$");
                q.execute("CREATE TRIGGER "+trap+" BEFORE INSERT ON dalmaru.character_refinement_requests FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            }
            try{repository.refineJournaled(account,generation,7,item,failed);throw new AssertionError("Rollback not injected");}catch(SQLException expected){check("P0001".equals(expected.getSQLState()),"Unexpected SQL error");}
            check(value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==wallet&&value("SELECT refinement_grade FROM dalmaru.character_equipment WHERE equipment_id=?",item)==grade&&value("SELECT refinement_failures FROM dalmaru.character_equipment WHERE equipment_id=?",item)==failures&&value("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?",failed.requestId())==0&&value("SELECT count(*) FROM dalmaru.character_currency_ledger WHERE character_id=?",character)==ledger,"Charge/roll/journal/ledger rollback");
            drop(trap);repository.refineJournaled(account,generation,7,item,failed);
            var reconnect=new AccountRepository().openSession(account);
            reject(()->repository.refine(account,generation,5,item));reject(()->repository.refineJournaled(account,generation,60,item,first));
            reject(()->repository.refineJournaled(account,reconnect.connectionGeneration(),60,item,envelope(first.requestId(),reconnect.connectionGeneration(),character,item)));
            System.out.println("PASS refinement UUID/concurrent/legacy, frozen roll/latest state, charge and ledger once, owner/body/stale-session, atomic rollback");
        }finally{drop(trap);sql("DELETE FROM dalmaru.character_refinement_requests WHERE account_id=?",account);sql("DELETE FROM dalmaru.character_currency_ledger WHERE character_id IN(SELECT character_id FROM dalmaru.characters WHERE account_id=?)",account);EnhancementReplayCheck.cleanup(account);}
    }
    private static RequestEnvelope envelope(UUID id,long generation,UUID character,UUID item){var body=new JsonObject();body.addProperty("equipmentId",item.toString());return new RequestEnvelope(id,generation,character,"refine_item",body);}
    private static void drop(String trap)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.character_refinement_requests");q.execute("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}}
    private static void sql(String text,Object... args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
    private static long value(String text,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){q.setObject(1,id);try(var row=q.executeQuery()){row.next();return row.getLong(1);}}}
    private static void reject(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid refinement accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
