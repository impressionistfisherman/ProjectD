package kr.dalmaru;

import com.google.gson.JsonObject;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.equipment.*;
import kr.dalmaru.request.*;

public final class InheritanceJournalCheck {
    private interface Action{void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        FixtureDatabase.databaseName();
        UUID account=UUID.randomUUID(),target=UUID.randomUUID(),source=UUID.randomUUID(),secondSource=UUID.randomUUID(),thirdSource=UUID.randomUUID(),foreign=UUID.randomUUID(),nextTarget=UUID.randomUUID();String trap="oscar_inherit_fail_"+account.toString().replace("-","");
        try {
            var session=new AccountRepository().openSession(account);var characters=new CharacterRepository();long generation=session.connectionGeneration();
            UUID character=characters.create(account,generation,1,1,"inherit_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            UUID inactive=characters.create(account,generation,99,2,"inhother_"+account.toString().substring(0,8)).character().characterId();
            var definition=ContentCatalog.current().items().entrySet().stream().filter(e->e.getValue().slot()==0&&e.getValue().requirement().equals("WARRIOR")).findFirst().orElseThrow();String item=definition.getKey();int level=definition.getValue().level();
            insert(target,character,item,level,true,0,5,"FREE");insert(source,character,item,level,false,3,0,"CHARACTER");insert(secondSource,character,item,level,false,4,0,"ACCOUNT");insert(thirdSource,character,item,level,false,2,0,"FREE");insert(foreign,inactive,item,level,false,4,0,"FREE");
            var repository=new EquipmentRepository();var first=envelope(UUID.randomUUID(),generation,character,target,source);var pool=Executors.newFixedThreadPool(2);
            try {
                var a=pool.submit(()->repository.inheritJournaled(account,generation,3,target,source,first));var b=pool.submit(()->repository.inheritJournaled(account,generation,4,target,source,first));
                check(a.get().original().replayed()!=b.get().original().replayed(),"Concurrent single source consumption");
            }finally{pool.shutdownNow();}
            check(value("SELECT inheritance_credit FROM dalmaru.character_equipment WHERE equipment_id=?",target)==53&&value("SELECT count(*) FROM dalmaru.character_equipment WHERE equipment_id=?",source)==0,"Single credit and source consumption");
            check(repository.list(character).stream().filter(e->e.equipmentId().equals(target)).findFirst().orElseThrow().binding()==EquipmentBinding.CHARACTER,"Binding merge");
            repository.inherit(account,generation,5,target,secondSource);
            var replay=repository.inheritJournaled(account,generation,50,target,source,first);
            check(replay.original().replayed()&&replay.original().credit()==48&&replay.currentCredit()==141&&replay.equipment().stream().noneMatch(e->e.equipmentId().equals(source)||e.equipmentId().equals(secondSource)),"Original credit and latest inventory");
            reject(()->repository.inheritJournaled(account,generation,51,target,thirdSource,envelope(first.requestId(),generation,character,target,thirdSource)));
            sql("UPDATE dalmaru.character_equipment SET location='EQUIPPED',slot_id=1 WHERE equipment_id=?",thirdSource);
            rejectUnequip(()->repository.inherit(account,generation,51,target,thirdSource));
            sql("UPDATE dalmaru.character_equipment SET location='INVENTORY',slot_id=NULL WHERE equipment_id=?",thirdSource);
            reject(()->repository.inheritJournaled(account,generation,51,target,foreign,envelope(UUID.randomUUID(),generation,character,target,foreign)));
            reject(()->repository.inherit(account,generation,51,target,target));
            sql("UPDATE dalmaru.character_equipment SET item_id='foreign.family' WHERE equipment_id=?",thirdSource);reject(()->repository.inherit(account,generation,51,target,thirdSource));sql("UPDATE dalmaru.character_equipment SET item_id=? WHERE equipment_id=?",item,thirdSource);
            sql("UPDATE dalmaru.character_equipment SET required_level=required_level+1 WHERE equipment_id=?",thirdSource);reject(()->repository.inherit(account,generation,51,target,thirdSource));sql("UPDATE dalmaru.character_equipment SET required_level=? WHERE equipment_id=?",level,thirdSource);
            long legacyId=value("SELECT legacy_request_id FROM dalmaru.operation_requests WHERE request_id=?",first.requestId());sql("DELETE FROM dalmaru.operation_requests WHERE request_id=?",first.requestId());
            var adopted=repository.inheritJournaled(account,generation,legacyId,target,source,null);check(adopted.original().replayed()&&adopted.original().credit()==48&&adopted.currentCredit()==141,"Legacy source-already-gone adoption");
            characters.selectActive(account,generation,60,inactive);reject(()->repository.inherit(account,generation,legacyId,target,source));characters.selectActive(account,generation,61,character);
            var failed=envelope(UUID.randomUUID(),generation,character,target,thirdSource);
            try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){
                q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.target_equipment_id='"+target+"'::uuid THEN RAISE EXCEPTION 'fixture inheritance rollback'; END IF; RETURN NEW; END $$");
                q.execute("CREATE TRIGGER "+trap+" BEFORE INSERT ON dalmaru.character_inheritance_requests FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            }
            try{repository.inheritJournaled(account,generation,6,target,thirdSource,failed);throw new AssertionError("Rollback not injected");}catch(SQLException expected){check("P0001".equals(expected.getSQLState()),"Unexpected SQL error");}
            check(value("SELECT inheritance_credit FROM dalmaru.character_equipment WHERE equipment_id=?",target)==141&&value("SELECT count(*) FROM dalmaru.character_equipment WHERE equipment_id=?",thirdSource)==1&&value("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?",failed.requestId())==0,"Credit/source deletion/journal rollback");
            drop(trap);repository.inheritJournaled(account,generation,6,target,thirdSource,failed);
            // Later consuming the original target must not erase its immutable UUID result.
            sql("UPDATE dalmaru.character_equipment SET location='INVENTORY',slot_id=NULL WHERE equipment_id=?",target);insert(nextTarget,character,item,level,true,0,0,"FREE");repository.inherit(account,generation,7,nextTarget,target);
            var afterConsumption=repository.inheritJournaled(account,generation,legacyId,target,source,null);
            check(afterConsumption.original().replayed()&&afterConsumption.original().credit()==48&&afterConsumption.currentCredit()==null&&afterConsumption.equipment().stream().noneMatch(e->e.equipmentId().equals(target)),"Deleted target original/current distinction");
            new AccountRepository().openSession(account);reject(()->repository.inherit(account,generation,legacyId,target,source));
            System.out.println("PASS inheritance UUID/concurrent/legacy, consumed source and target replay, original/latest, binding/owner/body/family/level/stale session, atomic rollback");
        }finally{drop(trap);sql("DELETE FROM dalmaru.character_inheritance_requests WHERE account_id=?",account);EnhancementReplayCheck.cleanup(account);}
    }
    private static RequestEnvelope envelope(UUID id,long generation,UUID character,UUID target,UUID source){var body=new JsonObject();body.addProperty("targetEquipmentId",target.toString());body.addProperty("sourceEquipmentId",source.toString());return new RequestEnvelope(id,generation,character,"inherit_equipment",body);}
    private static void insert(UUID id,UUID character,String item,int level,boolean equipped,int enhancement,int credit,String binding)throws SQLException{sql("INSERT INTO dalmaru.character_equipment(equipment_id,character_id,slot_id,item_id,required_level,profession,location,enhancement,inheritance_credit,binding,bound) VALUES(?,?,?,?,?,'WARRIOR',?,?,?,?,?)",id,character,equipped?0:null,item,level,equipped?"EQUIPPED":"INVENTORY",enhancement,credit,binding,!binding.equals("FREE"));}
    private static void drop(String trap)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.character_inheritance_requests");q.execute("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}}
    private static void sql(String text,Object... args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
    private static long value(String text,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){q.setObject(1,id);try(var row=q.executeQuery()){row.next();return row.getLong(1);}}}
    private static void reject(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid inheritance accepted");}
    private static void rejectUnequip(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){if(!EquipmentRepository.UNEQUIP_BEFORE_INHERIT.equals(expected.getMessage()))throw new AssertionError(expected.getMessage());return;}throw new AssertionError("Equipped source accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
