package kr.dalmaru;

import java.sql.*;
import java.util.*;
import kr.dalmaru.account.*;
import kr.dalmaru.skill.*;
import kr.dalmaru.run.*;

public final class LoadoutDatabaseCheck {
    public static void main(String[]args)throws Exception{
        if(!"oscar_fixture".equals(System.getenv("DALMARU_DB_NAME")))throw new IllegalStateException("Disposable database required.");
        UUID account=UUID.randomUUID();var session=new AccountRepository().openSession(account);var characters=new CharacterRepository();
        UUID character=characters.create(account,session.connectionGeneration(),1,1,"loadout_"+account.toString().substring(0,8)).character().characterId();
        characters.selectActive(account,session.connectionGeneration(),2,character);
        sql("UPDATE dalmaru.character_profiles SET level=15 WHERE character_id=?",character);
        sql("INSERT INTO dalmaru.character_professions(character_id,base_job) VALUES(?,'WARRIOR')",character);
        for(var skill:SkillDefinition.forProfession(CharacterProfession.WARRIOR))sql("INSERT INTO dalmaru.character_skill_ranks(character_id,skill_id,rank) VALUES(?,?,1)",character,skill.id());
        var repository=new LoadoutRepository();var first=repository.load(character);
        require(first.revision()==0&&first.slots().equals(List.of("skill.warrior.sweep","skill.warrior.advance","skill.warrior.rising_strike","skill.warrior.battle_cry","")),"Initial active-only slots");
        var slots=new ArrayList<>(first.slots());Collections.swap(slots,0,1);
        var changed=repository.update(account,session.connectionGeneration(),character,3,0,slots);
        require(changed.revision()==1&&repository.update(account,session.connectionGeneration(),character,3,0,slots).revision()==1,"Replay charged revision twice");
        reject(()->repository.update(account,session.connectionGeneration()+1,character,4,1,slots));
        reject(()->repository.update(account,session.connectionGeneration(),character,4,0,slots));
        var invalid=new ArrayList<>(slots);invalid.set(0,"skill.warrior.body_training");
        reject(()->repository.update(account,session.connectionGeneration(),character,4,1,invalid));
        invalid.set(0,slots.get(1));reject(()->repository.update(account,session.connectionGeneration(),character,4,1,invalid));
        var runRepository=new RunRepository();var run=runRepository.enter(account,session.connectionGeneration(),5,RunDifficulty.FIRST_STEP,List.of(character));
        require(runRepository.frozenLoadout(run.runId(),character).equals(changed),"ACTIVE loadout snapshot differs");
        reject(()->repository.update(account,session.connectionGeneration(),character,6,1,first.slots()));
        var reconnected=new AccountRepository().openSession(account);
        require(runRepository.frozenLoadout(run.runId(),character).equals(changed),"Reconnect lost frozen slots");
        runRepository.finish(account,reconnected.connectionGeneration(),7,run.runId(),false);
        var after=repository.update(account,reconnected.connectionGeneration(),character,8,1,first.slots());
        require(after.revision()==2&&runRepository.frozenLoadout(run.runId(),character).equals(changed),"Post-run edit rewrote history");
        System.out.println("C01 loadout owner/revision/replay/kind/ACTIVE/reconnect/frozen history PASS");
    }
    private static void sql(String text,Object...args)throws Exception{
        try(var c=DriverManager.getConnection("jdbc:postgresql://127.0.0.1:"+System.getenv("DALMARU_DB_PORT")+"/oscar_fixture","oscar_fixture",System.getenv("DALMARU_DB_PASSWORD"));var q=c.prepareStatement(text)){
            for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();
        }
    }
    private interface Operation{void run()throws Exception;}
    private static void reject(Operation operation)throws Exception{try{operation.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Unsafe loadout accepted");}
    private static void require(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
}
