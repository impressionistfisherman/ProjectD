package kr.dalmaru;

import com.google.gson.*;
import java.sql.*;
import java.util.*;
import kr.dalmaru.account.*;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.equipment.*;

public final class RefinementGradeCheck {
    private interface Action {void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        FixtureDatabase.databaseName();calculations();
        UUID account=UUID.randomUUID(),id=UUID.randomUUID();
        try {
            long generation=new AccountRepository().openSession(account).connectionGeneration();var characters=new CharacterRepository();
            UUID character=characters.create(account,generation,1,1,"grade_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            sql("UPDATE dalmaru.character_profiles SET level=20 WHERE character_id=?",character);
            sql("INSERT INTO dalmaru.character_professions(character_id,base_job) VALUES(?,'WARRIOR')",character);
            sql("UPDATE dalmaru.character_wallet SET copper=100 WHERE character_id=?",character);
            sql("INSERT INTO dalmaru.character_equipment(equipment_id,character_id,item_id,required_level,profession,location,bound,binding,refinement_grade) VALUES(?,?,'pilot.weapon.two_handed_sword.20',20,'WARRIOR','INVENTORY',false,'FREE',4)",id,character);
            var repository=new EquipmentRepository();
            check(find(repository.list(character),id).refinementGrade()==4,"List reads grade");
            repository.move(account,generation,3,id,true);
            check(find(repository.equipped(character),id).refinementGrade()==4,"Equipped reads grade");
            var enhanced=repository.enhance(account,generation,4,id);var replay=repository.enhance(account,generation,4,id);
            check(enhanced.equipment().refinementGrade()==4&&replay.equipment().refinementGrade()==4,"Enhancement and replay preserve grade");
            check(find(repository.list(character),id).refinementGrade()==4,"Enhancement leaves DB grade");
            System.out.println("PASS refinement grade: data0-5, attack or one primary only, dual per UUID, legacy constructors/snapshot, DB list/equipped/enhancement/replay");
        }finally{sql("DELETE FROM dalmaru.character_equipment_move_requests WHERE account_id=?",account);EnhancementReplayCheck.cleanup(account);}
    }
    private static void calculations()throws Exception {
        UUID character=UUID.randomUUID();var profile=new CharacterProfile(character,20,0,0,100,200,300,40,50,false,0,"WARRIOR");var content=ContentCatalog.current();
        double[] rates={0,.02,.03,.05,.08,.10};check(content.refinementGrades().size()==6,"Grade table missing");
        for(int grade=0;grade<6;grade++) {
            check(content.refinementBonus(grade)==rates[grade]&&content.refinementGrades().get(grade).name().contains("임시"),"Temporary grade data");
            var base=PilotEquipment.equipped(profile,List.of(record(character,0,"pilot.weapon.two_handed_sword.20",0,5)));
            var weapon=PilotEquipment.equipped(profile,List.of(record(character,0,"pilot.weapon.two_handed_sword.20",grade,5)));
            check(weapon.attack()==Math.round(63*1.10*(1+rates[grade]))&&weapon.primary()==base.primary()&&weapon.physicalDefense()==base.physicalDefense()&&weapon.vitality()==base.vitality()&&weapon.spirit()==base.spirit(),"Weapon grade affects only enhanced attack");
            var armor=PilotEquipment.equipped(profile,List.of(record(character,2,"pilot.armor.heavy.head.20",grade,0)));
            var plainArmor=PilotEquipment.equipped(profile,List.of(record(character,2,"pilot.armor.heavy.head.20",0,0)));
            check(armor.primary()==Math.round(12*.45*.2+100*rates[grade])&&armor.attack()==0&&armor.physicalDefense()==plainArmor.physicalDefense()&&armor.magicDefense()==plainArmor.magicDefense()&&armor.vitality()==plainArmor.vitality()&&armor.spirit()==plainArmor.spirit(),"Armor grade affects only one base primary");
        }
        var shield=PilotEquipment.equipped(profile,List.of(record(character,1,"pilot.offhand.shield.20",5,0)));
        check(shield.primary()==10&&shield.attack()==0&&shield.vitality()==0&&shield.spirit()==0,"Zero-base offhand still has one grade effect");
        var rogue=new CharacterProfile(character,20,0,0,100,200,300,40,50,false,0,"ROGUE");
        var axes=PilotEquipment.equipped(rogue,List.of(record(character,1,"pilot.weapon.dual_axes.20",5,5),record(character,0,"pilot.weapon.dual_axes.20",0,0)));
        check(axes.attack()==Math.round(59*.5+59*.5*1.10*1.10),"Each axe grade and enhancement independent");
        var mage=new CharacterProfile(character,20,0,0,100,200,300,40,50,false,0,"MAGE");
        check(PilotEquipment.equipped(mage,List.of(record(character,1,"pilot.offhand.talisman.20",5,0))).primary()==30,"Profession primary selection");
        var oldConstructor=new EquipmentRecord(UUID.randomUUID(),character,0,"pilot.weapon.two_handed_sword.20",20,"",0,0,false);
        check(oldConstructor.refinementGrade()==0,"Legacy constructor defaults grade0");
        reject(()->record(character,0,"pilot.weapon.two_handed_sword.20",6,0));reject(()->content.refinementBonus(-1));
        var archived=JsonParser.parseString(content.snapshot()).getAsJsonObject();archived.getAsJsonObject("equipment").remove("refinementGrades");
        check(PilotEquipment.equipped(profile,List.of(record(character,0,"pilot.weapon.two_handed_sword.20",5,0)),ContentCatalog.parse(archived.toString())).attack()==63,"Archived content ignores new grade effect");
        for(String mutation:List.of("missing","order","negative","descending")) {
            var root=JsonParser.parseString(content.snapshot()).getAsJsonObject();var grades=root.getAsJsonObject("equipment").getAsJsonArray("refinementGrades");
            switch(mutation){case "missing"->grades.remove(5);case "order"->grades.get(2).getAsJsonObject().addProperty("grade",3);case "negative"->grades.get(1).getAsJsonObject().addProperty("bonus",-.1);case "descending"->grades.get(4).getAsJsonObject().addProperty("bonus",.01);}
            reject(()->ContentCatalog.parse(root.toString()));
        }
    }
    private static EquipmentRecord record(UUID character,int slot,String id,int grade,int enhancement){return new EquipmentRecord(UUID.randomUUID(),character,slot,id,20,"",enhancement,0,false,EquipmentBinding.FREE,grade);}
    private static EquipmentRecord find(List<EquipmentRecord> rows,UUID id){return rows.stream().filter(r->r.equipmentId().equals(id)).findFirst().orElseThrow();}
    private static void sql(String text,Object... args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
    private static void reject(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid grade accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
