package kr.dalmaru;

import com.google.gson.JsonObject;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.equipment.*;
import kr.dalmaru.request.*;

public final class EquipmentMoveJournalCheck {
    private interface Action{void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        FixtureDatabase.databaseName();
        UUID account=UUID.randomUUID(),item=UUID.randomUUID(),replacement=UUID.randomUUID(),foreign=UUID.randomUUID();String trap="oscar_move_fail_"+account.toString().replace("-","");
        try {
            var session=new AccountRepository().openSession(account);var characters=new CharacterRepository();long generation=session.connectionGeneration();
            UUID character=characters.create(account,generation,1,1,"move_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            UUID inactive=characters.create(account,generation,99,2,"other_"+account.toString().substring(0,8)).character().characterId();
            sql("UPDATE dalmaru.character_profiles SET level=20 WHERE character_id=?",character);sql("INSERT INTO dalmaru.character_professions(character_id,base_job) VALUES(?,'WARRIOR')",character);
            var definition=ContentCatalog.current().items().entrySet().stream().filter(e->e.getValue().slot()==0&&e.getValue().level()>1&&e.getValue().level()<=20&&e.getValue().requirement().equals("WARRIOR")).findFirst().orElseThrow();
            insert(item,character,definition.getKey(),definition.getValue().level(),true);insert(replacement,character,definition.getKey(),definition.getValue().level(),false);insert(foreign,inactive,definition.getKey(),definition.getValue().level(),false);
            var repository=new EquipmentRepository();var first=envelope(UUID.randomUUID(),generation,character,item,false);var pool=Executors.newFixedThreadPool(2);
            try {
                var a=pool.submit(()->repository.moveJournaled(account,generation,3,item,false,first,true));
                var b=pool.submit(()->repository.moveJournaled(account,generation,4,item,false,first,true));
                check(a.get().replayed()!=b.get().replayed(),"Concurrent one move");
            }finally{pool.shutdownNow();}
            check(find(repository.list(character),item).slotId()==-1,"Initial unequip");
            repository.move(account,generation,5,item,true);
            var replay=repository.moveJournaled(account,generation,50,item,false,first,false);
            check(replay.replayed()&&replay.slotId()==-1&&find(replay.equipment(),item).slotId()==0,"Original move and latest equipment differ");
            reject(()->repository.moveJournaled(account,generation,51,item,true,envelope(first.requestId(),generation,character,item,true),true));
            reject(()->repository.moveJournaled(account,generation+1,51,item,false,envelope(first.requestId(),generation+1,character,item,false),true));
            reject(()->repository.moveJournaled(account,generation,51,item,false,envelope(UUID.randomUUID(),generation,character,item,false),false));
            reject(()->repository.moveJournaled(account,generation,51,foreign,true,envelope(UUID.randomUUID(),generation,character,foreign,true),true));
            sql("UPDATE dalmaru.character_profiles SET level=1 WHERE character_id=?",character);reject(()->repository.move(account,generation,51,replacement,true));sql("UPDATE dalmaru.character_profiles SET level=20 WHERE character_id=?",character);
            long legacyId=count("SELECT legacy_request_id FROM dalmaru.operation_requests WHERE request_id=?",first.requestId());
            sql("DELETE FROM dalmaru.operation_requests WHERE request_id=?",first.requestId());
            var adopted=repository.moveJournaled(account,generation,legacyId,item,false,null,false);
            check(adopted.replayed()&&adopted.slotId()==-1&&find(adopted.equipment(),item).slotId()==0,"Legacy original move adoption");
            check(find(repository.move(account,generation,legacyId,item,false),item).slotId()==0,"Legacy latest result");
            var failed=envelope(UUID.randomUUID(),generation,character,replacement,true);
            try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){
                q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.equipment_id='"+replacement+"'::uuid THEN RAISE EXCEPTION 'fixture move rollback'; END IF; RETURN NEW; END $$");
                q.execute("CREATE TRIGGER "+trap+" BEFORE INSERT ON dalmaru.character_equipment_move_requests FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            }
            try{repository.moveJournaled(account,generation,6,replacement,true,failed,true);throw new AssertionError("Rollback not injected");}catch(SQLException expected){check("P0001".equals(expected.getSQLState()),"Unexpected SQL error");}
            var after=repository.list(character);check(find(after,item).slotId()==0&&find(after,replacement).slotId()==-1&&find(after,replacement).binding()==EquipmentBinding.FREE&&count("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?",failed.requestId())==0,"Displacement/binding/journal rollback");
            drop(trap);var moved=repository.moveJournaled(account,generation,6,replacement,true,failed,true);
            check(!moved.replayed()&&moved.slotId()==0&&find(moved.equipment(),item).slotId()==-1&&find(moved.equipment(),replacement).binding()==EquipmentBinding.CHARACTER,"Displacement and bind on successful equip");
            System.out.println("PASS equipment move UUID/concurrent/legacy, original/latest, body/generation/owner/world/level, displacement/binding atomic rollback");
        }finally{drop(trap);sql("DELETE FROM dalmaru.character_equipment_move_requests WHERE account_id=?",account);EnhancementReplayCheck.cleanup(account);}
    }
    private static RequestEnvelope envelope(UUID id,long generation,UUID character,UUID item,boolean equip){var body=new JsonObject();body.addProperty("equipmentId",item.toString());body.addProperty("equip",equip);return new RequestEnvelope(id,generation,character,"move_equipment",body);}
    private static EquipmentRecord find(List<EquipmentRecord> equipment,UUID id){return equipment.stream().filter(item->item.equipmentId().equals(id)).findFirst().orElseThrow();}
    private static void insert(UUID id,UUID character,String template,int level,boolean equipped)throws SQLException{sql("INSERT INTO dalmaru.character_equipment(equipment_id,character_id,slot_id,item_id,required_level,profession,location,bound,binding) VALUES(?,?,?,?,?,'WARRIOR',?,?,?)",id,character,equipped?0:null,template,level,equipped?"EQUIPPED":"INVENTORY",equipped,equipped?"CHARACTER":"FREE");}
    private static void drop(String trap)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.character_equipment_move_requests");q.execute("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}}
    private static void sql(String text,Object... args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
    private static long count(String text,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){q.setObject(1,id);try(var row=q.executeQuery()){row.next();return row.getLong(1);}}}
    private static void reject(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid move accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
