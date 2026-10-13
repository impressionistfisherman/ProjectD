package kr.dalmaru;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import kr.dalmaru.account.*;
import kr.dalmaru.equipment.*;
import kr.dalmaru.quest.QuestRepository;
import kr.dalmaru.run.*;

/** Runs only against the disposable database, never the manual-play fixture or a Minecraft run. */
public final class PremergeDatabaseCheck {
    private static UUID account, character, second;
    private static long generation;
    private static final CharacterRepository characters=new CharacterRepository();
    private static final EquipmentRepository equipment=new EquipmentRepository();
    private static final RunRepository runs=new RunRepository();
    public static void main(String[] args) throws Exception {
        if(!"oscar_fixture".equals(System.getenv("DALMARU_DB_NAME")))throw new IllegalStateException("Disposable database required.");
        account=UUID.randomUUID();
        AccountSession old=new AccountRepository().openSession(account);
        AccountSession current=new AccountRepository().openSession(account);generation=current.connectionGeneration();
        reject(()->characters.create(account,old.connectionGeneration(),1,1,"old_generation"));
        require(number("SELECT count(*) FROM dalmaru.characters WHERE account_id=?",account)==0,"O01 stale generation wrote a character");
        pass("O01");
        character=characters.create(account,generation,2,1,"safety_"+account.toString().substring(0,8)).character().characterId();
        second=characters.create(account,generation,3,2,"other_"+account.toString().substring(0,8)).character().characterId();
        characters.selectActive(account,generation,4,character);
        sql("UPDATE dalmaru.character_profiles SET level=15,unspent_stat_points=10,unspent_skill_points=6 WHERE character_id=?",character);
        sql("INSERT INTO dalmaru.character_professions(character_id,base_job) VALUES(?,'WARRIOR')",character);
        var invested=characters.investStat(account,generation,10,character,CharacterStat.STRENGTH,1);
        require(invested.profile().profession().equals("WARRIOR")&&invested.profile().unspentSkillPoints()==6,"O02 investment erased profession/points");
        var newer=characters.investStat(account,generation,11,character,CharacterStat.STRENGTH,1).profile();
        require(newer.revision()>invested.profile().revision()&&!invested.profile().supersedes(newer),"R1 database revision allowed older profile");
        require(characters.investStat(account,generation,10,character,CharacterStat.STRENGTH,1).profile().strength()==2,"O03 investment replay regressed stats");
        var reset=characters.resetStats(account,generation,12,character);
        require(reset.profile().profession().equals("WARRIOR")&&reset.profile().unspentSkillPoints()==6,"O02 reset erased profession/points");
        characters.investStat(account,generation,13,character,CharacterStat.STRENGTH,1);
        require(characters.resetStats(account,generation,12,character).profile().strength()==1,"O03 reset replay regressed stats");
        var quests=new QuestRepository();quests.accept(character);quests.recordZombieKill(character);quests.recordZombieKill(character);
        var quest=quests.recordZombieKill(character);
        require(quest.profile().profession().equals("WARRIOR")&&quest.profile().unspentSkillPoints()==6,"O02 quest erased profession/points");
        pass("O02/O03");
        characters.registerTown("fixture","minecraft:overworld",0,100,0,5);
        require(characters.inTown("minecraft:overworld",5,100,0)&&!characters.inTown("minecraft:overworld",6,100,0)&&!characters.inTown("minecraft:the_nether",0,100,0),"O12 unsafe location accepted");
        UUID blockingRun=run(character);
        reject(()->characters.selectActive(account,generation,20,second));
        sql("UPDATE dalmaru.run_instances SET state='FAILED' WHERE run_id=?",blockingRun);
        pass("O12");
        String firstState="{\"inventory\":[{\"id\":\"minecraft:diamond\",\"count\":2}],\"ender\":[{\"id\":\"minecraft:emerald\",\"count\":1}],\"total\":123,\"level\":7,\"progress\":0.25,\"slot\":2}";
        String secondState="{\"inventory\":[],\"ender\":[],\"total\":456}";
        characters.selectActive(account,generation,21,second,firstState);
        var restored=characters.selectActive(account,generation,22,character,secondState);
        var restoredItems=com.google.gson.JsonParser.parseString(restored.vanillaState()).getAsJsonObject();restoredItems.remove("owner");
        require(restoredItems.equals(com.google.gson.JsonParser.parseString(firstState)),"O13 inventory/ender/XP state mixed");
        reject(()->characters.selectActive(account,generation,23,second,restored.vanillaState(),state->{throw new IllegalArgumentException("install failed");}));
        require(uuid("SELECT active_character_id FROM dalmaru.accounts WHERE account_id=?",account).equals(character),"O13 failed install committed selection");
        reject(()->characters.selectActive(account,generation,21,second,firstState));
        pass("O13");
        UUID weapon=item(character,"pilot.weapon.two_handed_sword.15",15,"WARRIOR");
        sql("INSERT INTO dalmaru.character_wallet(character_id,copper) VALUES(?,100)",character);
        var one=equipment.enhance(account,generation,30,weapon);
        require(one.success()&&number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==92,"O04 first cost wrong");
        equipment.enhance(account,generation,30,weapon);
        require(number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==92,"O04 replay charged twice");
        reject(()->equipment.enhance(account,generation,31,weapon));
        require(number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==92,"O04 insufficient material did not roll back money");
        sql("INSERT INTO dalmaru.character_materials(character_id,material_id,quantity,binding) VALUES(?,'material.ironstone',5,'ACCOUNT')",character);
        equipment.enhance(account,generation,31,weapon);
        require(number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==80&&number("SELECT quantity FROM dalmaru.character_materials WHERE character_id=?",character)==4,"O04 costs not atomic");
        require(equipment.list(character).getFirst().binding()==EquipmentBinding.ACCOUNT,"O15 material binding did not propagate");
        require(number("SELECT count(*) FROM dalmaru.character_currency_ledger WHERE character_id=?",character)==2,"O04 ledger missing or duplicated");
        sql("UPDATE dalmaru.character_equipment SET enhancement=5 WHERE equipment_id=?",weapon);
        reject(()->equipment.enhance(account,generation,32,weapon));
        require(number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==80,"O04 undefined price consumed assets");
        pass("O04");
        equipment.move(account,generation,40,weapon,true);
        UUID replacement=item(character,"pilot.weapon.two_handed_sword.15",15,"WARRIOR");
        equipment.move(account,generation,41,replacement,true);
        require(equipment.equipped(character).size()==1&&equipment.equipped(character).getFirst().equipmentId().equals(replacement),"O14 replacement not exclusive");
        require(equipment.list(character).stream().filter(e->e.equipmentId().equals(weapon)).findFirst().orElseThrow().slotId()==-1,"O14 displaced item not in inventory");
        equipment.move(account,generation,41,replacement,true);
        equipment.move(account,generation,42,replacement,false);
        require(equipment.equipped(character).isEmpty()&&equipment.list(character).stream().allMatch(e->e.binding()==EquipmentBinding.CHARACTER),"O15 wear/unequip weakened binding");
        UUID foreign=item(second,"pilot.weapon.two_handed_sword.15",15,"WARRIOR");
        reject(()->equipment.move(account,generation,43,foreign,true));
        UUID wrong=item(character,"pilot.weapon.bow.15",15,"");reject(()->equipment.move(account,generation,44,wrong,true));
        require(EquipmentBinding.ACCOUNT.merge(EquipmentBinding.FREE)==EquipmentBinding.ACCOUNT&&EquipmentBinding.FREE.merge(EquipmentBinding.CHARACTER)==EquipmentBinding.CHARACTER,"O15 binding order wrong");
        pass("O14/O15");
        UUID rewardRun=run(character,second);
        long before=totalExperience(characters.profile(character)),otherBefore=totalExperience(characters.profile(second));
        sql("CREATE FUNCTION dalmaru.fail_fixture_xp() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.character_id='"+second+"'::uuid THEN RAISE EXCEPTION 'fixture XP failure'; END IF; RETURN NEW; END $$");
        sql("CREATE TRIGGER fail_fixture_xp BEFORE UPDATE ON dalmaru.character_profiles FOR EACH ROW EXECUTE FUNCTION dalmaru.fail_fixture_xp()");
        reject(()->runs.finish(account,generation,50,rewardRun,true));
        require(number("SELECT count(*) FROM dalmaru.run_reward_grants WHERE run_id=?",rewardRun)==0&&totalExperience(characters.profile(character))==before,"J12 partial rewards escaped rollback");
        sql("DROP TRIGGER fail_fixture_xp ON dalmaru.character_profiles");sql("DROP FUNCTION dalmaru.fail_fixture_xp()");
        runs.finish(account,generation,50,rewardRun,true);
        require(totalExperience(characters.profile(character))==before+500&&totalExperience(characters.profile(second))==otherBefore+500,"J12 atomic XP missing");
        runs.finish(account,generation,50,rewardRun,true);runs.finish(account,generation,51,rewardRun,true);
        require(totalExperience(characters.profile(character))==before+500,"J12 finish replay duplicated XP");
        pass("J12");
        UUID paused=run(character);sql("UPDATE dalmaru.run_instances SET state='PAUSED',paused_at=CURRENT_TIMESTAMP-INTERVAL '6 minutes',pause_expires_at=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE run_id=?",paused);
        reject(()->runs.resume(character));require(runs.expireTimedOut()>=1,"J20 pause expiry not finalized");
        require(!uuid("SELECT active_character_id FROM dalmaru.accounts WHERE account_id=?",account).equals(second),"Selection changed unexpectedly");
        runs.saveReturn(paused,character,"minecraft:overworld",12,80,34);
        require(runs.pendingReturn(character).x()==12,"J21 offline return location lost");
        runs.acknowledgeReturn(account,generation,character,paused);require(runs.pendingReturn(character)==null,"J21 returned location replayed");
        pass("J20/J21");
        UUID expired=runs.enter(account,generation,60,RunDifficulty.FIRST_STEP,java.util.List.of(character)).runId();
        runs.saveReturn(expired,character,"minecraft:overworld",10,80,34,"survival");
        sql("UPDATE dalmaru.run_instances SET deadline_at=CURRENT_TIMESTAMP-INTERVAL '1 minute' WHERE run_id=?",expired);
        require(runs.expireTimedOut()>=1,"N6 online timeout not finalized");
        rejectPendingEntry(61,java.util.List.of(character));
        require(runs.pendingReturn(character).runId().equals(expired),"N6 denied entry changed pending return");
        // JOIN installs and saves this position before the exact ACK; no Minecraft is launched here.
        var timeoutReturn=runs.pendingReturn(character);require(timeoutReturn.x()==10&&timeoutReturn.mode().equals("survival"),"N6 old return lost");
        require(runs.acknowledgeReturn(account,generation,character,timeoutReturn.runId()),"N6 old saved return ACK failed");
        sql("UPDATE dalmaru.character_profiles SET level=15 WHERE character_id=?",second);
        UUID otherPending=run(second);runs.saveReturn(otherPending,second,"minecraft:overworld",30,80,34);
        sql("UPDATE dalmaru.run_instances SET state='FAILED',finished_at=CURRENT_TIMESTAMP WHERE run_id=?",otherPending);
        rejectPendingEntry(62,java.util.List.of(character,second));
        UUID nextRun=runs.enter(account,generation,63,RunDifficulty.FIRST_STEP,java.util.List.of(character)).runId();
        runs.saveReturn(nextRun,character,"minecraft:overworld",200,80,34,"creative");
        require(runs.enter(account,generation,63,RunDifficulty.FIRST_STEP,java.util.List.of(character)).replayed(),"N6 active return blocked valid entry replay");
        sql("UPDATE dalmaru.run_participants SET return_pending=TRUE WHERE run_id=? AND character_id=?",expired,character);
        rejectPendingEntry(63,java.util.List.of(character));
        require(runs.acknowledgeReturn(account,generation,character,expired),"N6 historical return ACK failed");
        require(number("SELECT count(*) FROM dalmaru.run_participants WHERE run_id=? AND return_pending",nextRun)==1,"N5 old ACK cleared current active return");
        runs.finish(account,generation,64,nextRun,true);
        var saved=runs.pendingReturn(character);require(saved.runId().equals(nextRun)&&saved.x()==200&&saved.mode().equals("creative"),"N6 new return changed");
        require(runs.acknowledgeReturn(account,generation,character,nextRun),"N6 new saved return ACK failed");
        require(runs.pendingReturn(character)==null&&runs.pendingReturn(second).runId().equals(otherPending),"N6 next JOIN replays old return or unrelated pending was cleared");
        pass("N6 timeout -> pending entry denied -> recovery ACK -> new completion -> next JOIN keeps x=200/mode=creative");
        System.out.println("Disposable PostgreSQL safety regressions PASS; no Minecraft entry performed.");
    }
    private static long totalExperience(CharacterProfile p){long total=p.experience();for(int l=1;l<p.level();l++)total+=CharacterRepository.experienceNeededForNextLevel(l);return total;}
    private static UUID item(UUID owner,String id,int level,String profession)throws Exception{UUID key=UUID.randomUUID();sql("INSERT INTO dalmaru.character_equipment(equipment_id,character_id,slot_id,item_id,required_level,profession,location) VALUES(?,?,NULL,?,?,?,'INVENTORY')",key,owner,id,level,profession);return key;}
    private static UUID run(UUID... participants)throws Exception{
        UUID key=UUID.randomUUID();sql("INSERT INTO dalmaru.run_instances(run_id,content_id,difficulty,week_id,party_size,scaling_tier,state) VALUES(?,'run.bongsudae','FIRST_STEP',CURRENT_DATE,?,?,'ACTIVE')",key,participants.length,participants.length);
        for(UUID id:participants){sql("INSERT INTO dalmaru.run_participants(run_id,character_id) VALUES(?,?)",key,id);sql("INSERT INTO dalmaru.character_run_weekly_clears(character_id,content_id,difficulty,week_id) VALUES(?,'run.bongsudae','FIRST_STEP',CURRENT_DATE) ON CONFLICT DO NOTHING",id);}
        return key;
    }
    private static Connection connection()throws Exception{return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:"+System.getenv("DALMARU_DB_PORT")+"/oscar_fixture","oscar_fixture",System.getenv("DALMARU_DB_PASSWORD"));}
    private static void sql(String sql,Object... parameters)throws Exception{try(var c=connection();var q=c.prepareStatement(sql)){for(int i=0;i<parameters.length;i++)q.setObject(i+1,parameters[i]);q.executeUpdate();}}
    private static long number(String sql,Object... parameters)throws Exception{try(var c=connection();var q=c.prepareStatement(sql)){for(int i=0;i<parameters.length;i++)q.setObject(i+1,parameters[i]);try(var row=q.executeQuery()){row.next();return row.getLong(1);}}}
    private static UUID uuid(String sql,Object... parameters)throws Exception{try(var c=connection();var q=c.prepareStatement(sql)){for(int i=0;i<parameters.length;i++)q.setObject(i+1,parameters[i]);try(var row=q.executeQuery()){row.next();return row.getObject(1,UUID.class);}}}
    @FunctionalInterface private interface Operation{void run()throws Exception;}
    private static void rejectPendingEntry(long request,java.util.List<UUID> participants)throws Exception{
        try{runs.enter(account,generation,request,RunDifficulty.FIRST_STEP,participants);}
        catch(IllegalArgumentException error){require("Pending run return requires recovery.".equals(error.getMessage()),"N6 entry failed for unrelated reason: "+error.getMessage());return;}
        throw new IllegalStateException("N6 FAIL: entry crossed an unacknowledged terminal return");
    }
    private static void reject(Operation op)throws Exception{try{op.run();}catch(IllegalArgumentException|java.sql.SQLException expected){return;}throw new IllegalStateException("Unsafe operation accepted");}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private static void pass(String ids){System.out.println(ids+" PASS");}
}
