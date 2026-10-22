package kr.dalmaru;

import com.google.gson.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.equipment.*;
import kr.dalmaru.request.RequestEnvelope;

public final class WeaponSlotsCheck {
    private interface Action { void run() throws Exception; }
    public static void main(String[] args)throws Exception {
        FixtureDatabase.databaseName(); calculations();
        UUID account=UUID.randomUUID();String trap="slots_"+account.toString().replace("-","");
        var repository=new EquipmentRepository();
        try {
            long generation=new AccountRepository().openSession(account).connectionGeneration();var characters=new CharacterRepository();
            UUID character=characters.create(account,generation,1,1,"slots_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            sql("UPDATE dalmaru.character_profiles SET level=20 WHERE character_id=?",character);
            sql("INSERT INTO dalmaru.character_professions(character_id,base_job) VALUES(?,'WARRIOR')",character);
            UUID sword=insert(character,"pilot.weapon.sword_shield.20"),shield=insert(character,"pilot.offhand.shield.20"),two=insert(character,"pilot.weapon.two_handed_sword.20");
            repository.move(account,generation,3,sword,true);repository.move(account,generation,4,shield,true);
            check(slot(repository,character,sword)==0&&slot(repository,character,shield)==1,"One hand + shield slots");
            check(PilotEquipment.hasShield(worn(repository,character),ContentCatalog.current()),"Actual shield missing");
            repository.move(account,generation,5,two,true);
            check(slot(repository,character,sword)==-1&&slot(repository,character,shield)==-1&&slot(repository,character,two)==0,"Two hand displaces both slots");
            repository.move(account,generation,6,shield,true);
            check(slot(repository,character,two)==-1&&slot(repository,character,shield)==1,"Offhand displaces two hand");
            UUID dagger=insert(character,"pilot.offhand.dagger.20");repository.move(account,generation,7,sword,true);
            reject(()->repository.move(account,generation,8,dagger,true));
            check(slot(repository,character,shield)==1&&slot(repository,character,dagger)==-1,"Incompatible offhand preserved state");
            sql("UPDATE dalmaru.character_professions SET base_job='ROGUE' WHERE character_id=?",character);
            UUID a=insert(character,"pilot.weapon.dual_axes.20"),b=insert(character,"pilot.weapon.dual_axes.20"),c=insert(character,"pilot.weapon.dual_axes.20");
            repository.move(account,generation,9,a,true);var request=envelope(UUID.randomUUID(),generation,character,b,true);
            var second=repository.moveJournaled(account,generation,10,b,true,request,true);
            check(second.slotId()==1&&slot(repository,character,a)==0&&slot(repository,character,shield)==-1,"Independent dual slots");
            repository.move(account,generation,11,b,true);check(slot(repository,character,a)==0&&slot(repository,character,b)==1,"Equipped offhand remains in slot");
            repository.move(account,generation,12,c,true);
            check(slot(repository,character,c)==0&&slot(repository,character,b)==1&&slot(repository,character,a)==-1,"Third axe replaces main");
            repository.move(account,generation,13,c,false);
            check(slot(repository,character,c)==-1&&slot(repository,character,b)==-1,"Main unequip removes orphan axe");
            var replay=repository.moveJournaled(account,generation,14,b,true,request,false);
            check(replay.replayed()&&replay.slotId()==1&&find(replay.equipment(),b).slotId()==-1,"Replay original slot and latest state");
            reject(()->repository.moveJournaled(account,generation,15,b,false,envelope(request.requestId(),generation,character,b,false),true));
            reject(()->repository.moveJournaled(account,generation+1,15,b,true,envelope(request.requestId(),generation+1,character,b,true),true));
            var pool=Executors.newFixedThreadPool(2);
            try {var x=pool.submit(()->repository.move(account,generation,16,a,true));var y=pool.submit(()->repository.move(account,generation,17,b,true));x.get();y.get();}finally{pool.shutdownNow();}
            var before=worn(repository,character);check(before.size()==2&&before.stream().map(EquipmentRecord::slotId).distinct().count()==2,"Concurrent distinct dual slots");
            PilotEquipment.equipped(new CharacterProfile(character,20,0,0,0,0,0,0,0,false,0,"ROGUE"),before);
            UUID fresh=insert(character,"pilot.weapon.dual_axes.20");var failed=envelope(UUID.randomUUID(),generation,character,fresh,true);
            try(var conn=EnhancementReplayCheck.connection();var q=conn.createStatement()) {
                q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.request_id='"+failed.requestId()+"'::uuid THEN RAISE EXCEPTION 'slot confirm rollback'; END IF; RETURN NEW; END $$");
                q.execute("CREATE TRIGGER "+trap+" BEFORE UPDATE ON dalmaru.operation_requests FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            }
            try {repository.moveJournaled(account,generation,18,fresh,true,failed,true);throw new AssertionError("Confirm failure not injected");}catch(SQLException expected){check("P0001".equals(expected.getSQLState()),"Unexpected failure");}
            check(new HashSet<>(before).equals(new HashSet<>(worn(repository,character)))&&find(repository.list(character),fresh).binding()==EquipmentBinding.FREE,"Confirm failure restores displacement/binding");
            check(count("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?",failed.requestId())==0&&count("SELECT count(*) FROM dalmaru.character_equipment_move_requests WHERE equipment_id=?",fresh)==0,"Confirm failure removes both journals");
            drop(trap);repository.moveJournaled(account,generation,18,fresh,true,failed,true);
            check(find(repository.list(character),fresh).binding()==EquipmentBinding.CHARACTER,"Successful bind");
            System.out.println("PASS weapon slots: compatibility, two hand displacement, independent dual UUIDs, concurrent equip, replay, confirm atomic rollback, legacy snapshot/stats/guard");
        }finally{drop(trap);sql("DELETE FROM dalmaru.character_equipment_move_requests WHERE account_id=?",account);EnhancementReplayCheck.cleanup(account);}
    }
    private static void calculations()throws Exception {
        UUID character=UUID.randomUUID();var profile=new CharacterProfile(character,20,0,0,0,0,0,0,0,false,0,"ROGUE");var current=ContentCatalog.current();
        var a=record(character,0,"pilot.weapon.dual_axes.20",0);var b=record(character,1,"pilot.weapon.dual_axes.20",5);
        var stats=PilotEquipment.equipped(profile,List.of(b,a));
        check(stats.attack()==Math.round(59*.5+59*.5*1.10)&&stats.basicTicks()==10&&stats.basicMultiplier()==.55,"Each axe enhancement, main timing independent of input order");
        check(PilotEquipment.equipped(profile,List.of(a,record(character,1,a.itemId(),0))).attack()==59,"Two +0 axes preserve total attack");
        reject(()->PilotEquipment.equipped(profile,List.of(b)));
        reject(()->PilotEquipment.equipped(profile,List.of(a,new EquipmentRecord(a.equipmentId(),character,1,a.itemId(),20,"",0,0,false))));
        var two=record(character,0,"pilot.weapon.two_handed_sword.20",0);var shield=record(character,1,"pilot.offhand.shield.20",0);
        reject(()->PilotEquipment.equipped(profile,List.of(two,shield)));
        reject(()->PilotEquipment.equipped(profile,List.of(record(character,1,"pilot.weapon.bow.20",0))));
        var sword=record(character,0,"pilot.weapon.sword_shield.20",0);
        check(!PilotEquipment.hasShield(List.of(sword),current)&&PilotEquipment.hasShield(List.of(sword,shield),current),"Shield requires real offhand");
        var legacy=JsonParser.parseString(current.snapshot()).getAsJsonObject();legacy.getAsJsonObject("equipment").remove("weaponSlots");legacy.getAsJsonObject("equipment").remove("offhands");legacy.getAsJsonObject("equipment").remove("refinementGrades");legacy.getAsJsonObject("equipment").remove("refinement");
        var frozen=ContentCatalog.parse(legacy.toString());
        check(frozen.weaponSlots().isEmpty()&&frozen.items().size()==70&&PilotEquipment.hasShield(List.of(sword),frozen)&&PilotEquipment.equipped(profile,List.of(a),frozen).attack()==59,"Legacy pinned snapshot retains behavior");
        for(String family:List.of("shield","dagger","talisman"))check(current.items().get("pilot.offhand."+family+".20").slot()==1,"Offhand definition");
    }
    private static EquipmentRecord record(UUID character,int slot,String id,int enhancement){return new EquipmentRecord(UUID.randomUUID(),character,slot,id,20,"",enhancement,0,false);}
    private static UUID insert(UUID character,String id)throws SQLException {UUID uuid=UUID.randomUUID();sql("INSERT INTO dalmaru.character_equipment(equipment_id,character_id,item_id,required_level,profession,location,bound,binding) VALUES(?,?,?,20,'','INVENTORY',false,'FREE')",uuid,character,id);return uuid;}
    private static RequestEnvelope envelope(UUID id,long generation,UUID character,UUID item,boolean equip){var body=new JsonObject();body.addProperty("equipmentId",item.toString());body.addProperty("equip",equip);return new RequestEnvelope(id,generation,character,"move_equipment",body);}
    private static List<EquipmentRecord> worn(EquipmentRepository r,UUID character)throws SQLException{return r.list(character).stream().filter(i->i.slotId()>=0).toList();}
    private static EquipmentRecord find(List<EquipmentRecord> items,UUID id){return items.stream().filter(i->i.equipmentId().equals(id)).findFirst().orElseThrow();}
    private static int slot(EquipmentRepository r,UUID character,UUID id)throws SQLException{return find(r.list(character),id).slotId();}
    private static void sql(String text,Object... args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
    private static long count(String text,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){q.setObject(1,id);try(var row=q.executeQuery()){row.next();return row.getLong(1);}}}
    private static void drop(String trap)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.operation_requests");q.execute("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}}
    private static void reject(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid layout accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
