package kr.dalmaru;

import com.google.gson.JsonObject;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.request.*;

public final class ProfessionJournalCheck {
    private interface Action{void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        FixtureDatabase.databaseName();
        UUID account=UUID.randomUUID();String trap="oscar_profession_fail_"+account.toString().replace("-","");
        try {
            var session=new AccountRepository().openSession(account);var characters=new CharacterRepository();long generation=session.connectionGeneration();
            UUID character=characters.create(account,generation,1,1,"job_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            var first=envelope(UUID.randomUUID(),generation,character,CharacterProfession.WARRIOR);
            reject(()->characters.selectProfessionJournaled(account,generation,3,character,CharacterProfession.WARRIOR,first));
            sql("UPDATE dalmaru.character_profiles SET level=10 WHERE character_id=?",character);
            reject(()->characters.selectProfessionJournaled(account,generation,3,character,CharacterProfession.WARRIOR,first));
            sql("INSERT INTO dalmaru.character_quest_progress(character_id,quest_id,content_version,state,progress_count,claimed_at) VALUES(?,'pilot1.zombie_hunt','pilot-1','REWARDED',3,CURRENT_TIMESTAMP)",character);
            try(var c=EnhancementReplayCheck.connection()){ProfessionGateFixture.complete(c,character,CharacterProfession.WARRIOR);}
            try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()) {
                q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.character_id='"+character+"'::uuid AND NEW.unspent_skill_points>OLD.unspent_skill_points THEN RAISE EXCEPTION 'fixture profession rollback'; END IF; RETURN NEW; END $$");
                q.execute("CREATE TRIGGER "+trap+" BEFORE UPDATE ON dalmaru.character_profiles FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            }
            try{characters.selectProfessionJournaled(account,generation,3,character,CharacterProfession.WARRIOR,first);throw new AssertionError("Rollback not injected");}catch(SQLException expected){check("P0001".equals(expected.getSQLState()),"Unexpected SQL error");}
            check(characters.profession(character)==null&&characters.profile(character).unspentSkillPoints()==0&&count("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?",first.requestId())==0,"Profession/reward/journal rollback");
            drop(trap);var pool=Executors.newFixedThreadPool(2);
            try {
                var a=pool.submit(()->characters.selectProfessionJournaled(account,generation,4,character,CharacterProfession.WARRIOR,first));
                var b=pool.submit(()->characters.selectProfessionJournaled(account,generation,5,character,CharacterProfession.WARRIOR,first));
                check(a.get().replayed()!=b.get().replayed(),"Concurrent one mutation");
            }finally{pool.shutdownNow();}
            check(characters.profession(character)==CharacterProfession.WARRIOR&&characters.profile(character).unspentSkillPoints()==6,"Single initial reward");
            sql("UPDATE dalmaru.character_profiles SET unspent_skill_points=5 WHERE character_id=?",character);
            var replay=characters.selectProfessionJournaled(account,generation,60,character,CharacterProfession.WARRIOR,first);
            check(replay.replayed()&&replay.profession()==CharacterProfession.WARRIOR&&replay.profile().unspentSkillPoints()==5,"Original profession/latest points");
            reject(()->characters.selectProfessionJournaled(account,generation,61,character,CharacterProfession.MAGE,envelope(first.requestId(),generation,character,CharacterProfession.MAGE)));
            reject(()->characters.selectProfessionJournaled(account,generation+1,61,character,CharacterProfession.WARRIOR,envelope(first.requestId(),generation+1,character,CharacterProfession.WARRIOR)));
            reject(()->characters.selectProfessionJournaled(account,generation,61,character,CharacterProfession.MAGE,envelope(UUID.randomUUID(),generation,character,CharacterProfession.MAGE)));
            characters.selectProfession(account,generation,62,character,CharacterProfession.WARRIOR);characters.selectProfession(account,generation,62,character,CharacterProfession.WARRIOR);
            check(characters.profile(character).unspentSkillPoints()==5,"Legacy repeated reward");
            var snapshot=replay.profile();check(snapshot.profession().equals("WARRIOR"),"Coherent profession/profile snapshot");
            System.out.println("PASS profession UUID/concurrent/legacy replay, initial reward once, latest points, prerequisites/body/generation, atomic rollback");
        }finally{drop(trap);EnhancementReplayCheck.cleanup(account);}
    }
    private static RequestEnvelope envelope(UUID id,long generation,UUID character,CharacterProfession job){var body=new JsonObject();body.addProperty("profession",job.name());return new RequestEnvelope(id,generation,character,"select_profession",body);}
    private static void drop(String trap)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.character_profiles");q.execute("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}}
    private static void sql(String text,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){q.setObject(1,id);q.executeUpdate();}}
    private static long count(String text,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){q.setObject(1,id);try(var row=q.executeQuery()){row.next();return row.getLong(1);}}}
    private static void reject(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid profession accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
