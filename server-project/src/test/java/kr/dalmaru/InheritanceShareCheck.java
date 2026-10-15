package kr.dalmaru;

import com.google.gson.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.equipment.*;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.shop.ShopCatalog;

public final class InheritanceShareCheck {
    private interface Action {void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        FixtureDatabase.databaseName();check(ShopCatalog.buyPrice("material.ironstone")==16,"NPC material value fixture");
        UUID account=UUID.randomUUID();String trap="share_"+account.toString().replace("-","");var repo=new EquipmentRepository();
        try {
            long generation=new AccountRepository().openSession(account).connectionGeneration();var characters=new CharacterRepository();
            UUID character=characters.create(account,generation,1,1,"share_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            sql("UPDATE dalmaru.character_profiles SET level=20 WHERE character_id=?",character);sql("INSERT INTO dalmaru.character_professions(character_id,base_job) VALUES(?,'WARRIOR')",character);
            sql("UPDATE dalmaru.character_wallet SET copper=1000 WHERE character_id=?",character);
            sql("INSERT INTO dalmaru.character_materials(character_id,material_id,quantity,binding) VALUES(?,'material.ironstone',100,'FREE') ON CONFLICT(character_id,material_id) DO UPDATE SET quantity=100,binding='FREE'",character);
            UUID target=weapon(character,20,true,0,5,"FREE"),source=weapon(character,15,false,3,999,"CHARACTER");
            var first=repo.inheritJournaled(account,generation,3,target,source,inheritance(UUID.randomUUID(),generation,character,target,source));
            check(first.original().credit()==48&&first.currentCredit()==53&&!exists(source),"Same family 15 to20, per-kind floor48, unused source credit excluded");
            check(find(repo,target,character).binding()==EquipmentBinding.CHARACTER&&find(repo,target,character).enhancement()==0&&wallet(character)==1000,"Strong binding, no enhancement copy or wallet mint");
            var original=outcome(first.receipt().requestId());check(original.get("sourceCopperSpent").getAsInt()==38&&original.get("sourceIronstoneSpent").getAsInt()==2&&original.get("ironstoneUnitPrice").getAsInt()==16,"Source value audit");
            UUID paid=weapon(character,15,false,0,0,"FREE");repo.enhance(account,generation,4,paid);repo.enhance(account,generation,5,paid);
            var paidResult=repo.inheritJournaled(account,generation,6,target,paid,inheritance(UUID.randomUUID(),generation,character,target,paid));
            check(paidResult.original().credit()==25&&paidResult.currentCredit()==78&&wallet(character)==980,"Actual journal spending uses wallet20 and iron1");
            var enhanceRequest=enhancement(UUID.randomUUID(),generation,character,target);
            var used=repo.enhance(account,generation,7,target,enhanceRequest);var repeated=repo.enhance(account,generation,8,target,enhanceRequest);
            var spending=outcome(used.receipt().requestId());
            check(credit(target)==70&&wallet(character)==980&&spending.get("copperSpent").getAsInt()==0&&spending.get("inheritanceSpent").getAsInt()==8&&repeated.replayed(),"Dedicated credit used once before wallet; actual spending excludes credit");
            check(value("SELECT l.copper_delta FROM dalmaru.character_currency_ledger l JOIN dalmaru.operation_requests o ON o.character_id=l.character_id AND l.reason='enhancement:'||o.connection_generation||':'||o.legacy_request_id WHERE o.request_id=?",used.receipt().requestId())==0,"Zero cash ledger on credit-funded attempt");
            repo.enhance(account,generation,9,target);check(credit(target)==58&&wallet(character)==980,"Second enhancement spends credit, physical iron separately");
            inventory(target);UUID next=weapon(character,20,true,0,0,"FREE");
            check(repo.inherit(account,generation,10,next,target).credit()==11&&credit(next)==11,"Credit-funded copper is not recovered again; only actual iron yields11");
            UUID concurrentSource=weapon(character,15,false,4,12345,"ACCOUNT");var request=inheritance(UUID.randomUUID(),generation,character,next,concurrentSource);var pool=Executors.newFixedThreadPool(2);
            try{var a=pool.submit(()->repo.inheritJournaled(account,generation,11,next,concurrentSource,request));var b=pool.submit(()->repo.inheritJournaled(account,generation,12,next,concurrentSource,request));check(a.get().original().replayed()!=b.get().original().replayed(),"Concurrent source consumption once");}finally{pool.shutdownNow();}
            check(credit(next)==99&&!exists(concurrentSource),"Single88 credit and unused source credit discarded");
            var failEnhancement=enhancement(UUID.randomUUID(),generation,character,next);
            try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){
                q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.request_id='"+failEnhancement.requestId()+"'::uuid THEN RAISE EXCEPTION 'inheritance credit confirm rollback'; END IF; RETURN NEW; END $$");
                q.execute("CREATE TRIGGER "+trap+" BEFORE UPDATE ON dalmaru.operation_requests FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            }
            try{repo.enhance(account,generation,13,next,failEnhancement);throw new AssertionError("Confirm failure not injected");}catch(SQLException expected){check("P0001".equals(expected.getSQLState()),"Unexpected SQL error");}
            check(credit(next)==99&&wallet(character)==980&&find(repo,next,character).enhancement()==0&&value("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?",failEnhancement.requestId())==0,"Credit, stage and journal rollback on failed confirm");drop(trap);
            repo.enhance(account,generation,13,next,failEnhancement);check(credit(next)==91&&wallet(character)==980,"Same request succeeds after rollback");
            UUID overflow=weapon(character,15,false,5,0,"FREE");sql("UPDATE dalmaru.character_equipment SET inheritance_credit=2147483647 WHERE equipment_id=?",next);
            reject(()->repo.inherit(account,generation,14,next,overflow));check(exists(overflow)&&credit(next)==Integer.MAX_VALUE,"Credit bound rejects before source consumption");sql("UPDATE dalmaru.character_equipment SET inheritance_credit=91 WHERE equipment_id=?",next);
            UUID other=insert(character,"pilot.weapon.sword_shield.15",15,false,0,3,0,"FREE");reject(()->repo.inherit(account,generation,15,next,other));
            UUID head=insert(character,"pilot.armor.heavy.head.20",20,true,2,0,0,"FREE"),body=insert(character,"pilot.armor.heavy.body.15",15,false,3,3,0,"FREE");reject(()->repo.inherit(account,generation,16,head,body));
            inventory(next);UUID lower=weapon(character,15,true,0,0,"FREE"),higher=weapon(character,20,false,3,0,"FREE");reject(()->repo.inherit(account,generation,17,lower,higher));
            inventory(lower);UUID oldTarget=weapon(character,20,true,0,21,"CHARACTER"),malformed=insert(character,"pilot.weapon.two_handed_sword.15",20,false,0,3,0,"FREE");reject(()->repo.inherit(account,generation,18,oldTarget,malformed));
            UUID missing=UUID.randomUUID();long oldRequest=9000000001L;
            sql("INSERT INTO dalmaru.character_inheritance_requests VALUES(?,?,?,?,?,21)",account,generation,oldRequest,oldTarget,missing);
            var legacy=repo.inherit(account,generation,oldRequest,oldTarget,missing);
            check(legacy.replayed()&&legacy.credit()==21&&credit(oldTarget)==21&&value("SELECT credit FROM dalmaru.character_inheritance_requests WHERE target_equipment_id=?",oldTarget)==21,"Old formula ledger replay remains21, not recalculated or credited again");
            System.out.println("PASS inheritance share: family/part15-to20, integer per-kind48, NPC16, actual spend, stronger binding, no credit recycling/wallet mint, concurrent UUID, confirm rollback, bounds, immutable old ledger");
        }finally{drop(trap);sql("DELETE FROM dalmaru.character_inheritance_requests WHERE account_id=?",account);EnhancementReplayCheck.cleanup(account);}
    }
    private static UUID weapon(UUID character,int level,boolean equipped,int enhancement,int credit,String binding)throws SQLException{return insert(character,"pilot.weapon.two_handed_sword."+level,level,equipped,0,enhancement,credit,binding);}
    private static UUID insert(UUID character,String item,int level,boolean equipped,int slot,int enhancement,int credit,String binding)throws SQLException{UUID id=UUID.randomUUID();sql("INSERT INTO dalmaru.character_equipment(equipment_id,character_id,item_id,required_level,profession,location,slot_id,enhancement,inheritance_credit,binding,bound) VALUES(?,?,?,?,?,?, ?,?,?,?,?)",id,character,item,level,"WARRIOR",equipped?"EQUIPPED":"INVENTORY",equipped?slot:null,enhancement,credit,binding,!binding.equals("FREE"));return id;}
    private static void inventory(UUID item)throws SQLException{sql("UPDATE dalmaru.character_equipment SET location='INVENTORY',slot_id=NULL WHERE equipment_id=?",item);}
    private static RequestEnvelope inheritance(UUID id,long generation,UUID character,UUID target,UUID source){var body=new JsonObject();body.addProperty("targetEquipmentId",target.toString());body.addProperty("sourceEquipmentId",source.toString());return new RequestEnvelope(id,generation,character,"inherit_equipment",body);}
    private static RequestEnvelope enhancement(UUID id,long generation,UUID character,UUID item){var body=new JsonObject();body.addProperty("equipmentId",item.toString());return new RequestEnvelope(id,generation,character,"enhance_item",body);}
    private static JsonObject outcome(UUID request)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement("SELECT result::text FROM dalmaru.operation_requests WHERE request_id=?")){q.setObject(1,request);try(var row=q.executeQuery()){row.next();return JsonParser.parseString(row.getString(1)).getAsJsonObject();}}}
    private static EquipmentRecord find(EquipmentRepository repo,UUID item,UUID character)throws SQLException{return repo.list(character).stream().filter(r->r.equipmentId().equals(item)).findFirst().orElseThrow();}
    private static long credit(UUID item)throws SQLException{return value("SELECT inheritance_credit FROM dalmaru.character_equipment WHERE equipment_id=?",item);}
    private static long wallet(UUID character)throws SQLException{return value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character);}
    private static boolean exists(UUID item)throws SQLException{return value("SELECT count(*) FROM dalmaru.character_equipment WHERE equipment_id=?",item)>0;}
    private static void sql(String text,Object... args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
    private static long value(String text,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){q.setObject(1,id);try(var row=q.executeQuery()){row.next();return row.getLong(1);}}}
    private static void drop(String trap)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.operation_requests");q.execute("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}}
    private static void reject(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid inheritance accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
