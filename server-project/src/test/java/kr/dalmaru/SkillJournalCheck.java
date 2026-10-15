package kr.dalmaru;

import com.google.gson.JsonObject;
import java.sql.*;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.*;
import java.util.concurrent.*;
import javax.tools.ToolProvider;
import kr.dalmaru.account.*;
import kr.dalmaru.request.*;
import kr.dalmaru.skill.*;

public final class SkillJournalCheck {
    private interface Action{void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        if(!"oscar_p2_fixture".equals(System.getenv("DALMARU_DB_NAME")))throw new IllegalStateException("Dedicated P1 fixture required");
        UUID account=UUID.randomUUID();String trap="oscar_skill_fail_"+account.toString().replace("-","");
        try {
            var session=new AccountRepository().openSession(account);var characters=new CharacterRepository();var skills=new SkillRepository();long generation=session.connectionGeneration();
            UUID character=characters.create(account,generation,1,1,"skill_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            sql("UPDATE dalmaru.character_profiles SET level=15,unspent_skill_points=6 WHERE character_id=?",character);
            sql("INSERT INTO dalmaru.character_professions(character_id,base_job) VALUES(?,'WARRIOR')",character);
            var firstEnvelope=envelope(UUID.randomUUID(),generation,character,"skill.warrior.sweep");
            var first=skills.learnJournaled(account,generation,10,character,CharacterProfession.WARRIOR,"skill.warrior.sweep",firstEnvelope);
            check(first.rank()==1&&!first.replayed()&&first.profile().unspentSkillPoints()==5,"Initial charge differs");
            var second=skills.learnJournaled(account,generation,11,character,CharacterProfession.WARRIOR,"skill.warrior.sweep",envelope(UUID.randomUUID(),generation,character,"skill.warrior.sweep"));
            var replay=skills.learnJournaled(account,generation,99,character,CharacterProfession.WARRIOR,"skill.warrior.sweep",firstEnvelope);
            check(replay.rank()==1&&replay.replayed()&&replay.currentRanks().get("skill.warrior.sweep")==2&&replay.profile().unspentSkillPoints()==4&&replay.receipt().eventId().equals(first.receipt().eventId()),"Original replay regressed/recharged latest state");
            sql("UPDATE dalmaru.character_profiles SET vitality=vitality+1 WHERE character_id=?",character);cache(first,second,replay,characters.profile(character));
            reject(()->skills.learnJournaled(account,generation,13,character,CharacterProfession.WARRIOR,"skill.warrior.advance",envelope(firstEnvelope.requestId(),generation,character,"skill.warrior.advance")));
            reject(()->skills.learnJournaled(account,generation+1,14,character,CharacterProfession.WARRIOR,"skill.warrior.advance",envelope(UUID.randomUUID(),generation+1,character,"skill.warrior.advance")));
            reject(()->skills.learnJournaled(account,generation,15,character,CharacterProfession.MAGE,"skill.mage.spirit_talisman",envelope(UUID.randomUUID(),generation,character,"skill.mage.spirit_talisman")));
            sql("UPDATE dalmaru.character_profiles SET level=9 WHERE character_id=?",character);
            reject(()->skills.learn(account,generation,16,character,CharacterProfession.WARRIOR,"skill.warrior.advance"));sql("UPDATE dalmaru.character_profiles SET level=15 WHERE character_id=?",character);
            check(skills.learn(account,generation,20,character,CharacterProfession.WARRIOR,"skill.warrior.advance")==1&&skills.learn(account,generation,20,character,CharacterProfession.WARRIOR,"skill.warrior.advance")==1&&characters.profile(character).unspentSkillPoints()==3,"Legacy adapter charged twice");
            var concurrent=envelope(UUID.randomUUID(),generation,character,"skill.warrior.battle_cry");
            try(var pool=Executors.newFixedThreadPool(2)){
                var a=pool.submit(()->skills.learnJournaled(account,generation,30,character,CharacterProfession.WARRIOR,"skill.warrior.battle_cry",concurrent));
                var b=pool.submit(()->skills.learnJournaled(account,generation,31,character,CharacterProfession.WARRIOR,"skill.warrior.battle_cry",concurrent));
                var one=a.get(10,TimeUnit.SECONDS);var two=b.get(10,TimeUnit.SECONDS);
                check(one.rank()==1&&two.rank()==1&&one.replayed()!=two.replayed()&&one.receipt().eventId().equals(two.receipt().eventId())&&characters.profile(character).unspentSkillPoints()==2,"Concurrent duplicate charged twice");
            }
            UUID failed=UUID.randomUUID();
            try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){
                q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.character_id='"+character+"'::uuid AND NEW.skill_id='skill.warrior.rising_strike' THEN RAISE EXCEPTION 'fixture skill failure'; END IF; RETURN NEW; END $$");
                q.execute("CREATE TRIGGER "+trap+" BEFORE INSERT OR UPDATE ON dalmaru.character_skill_ranks FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            }
            try{skills.learnJournaled(account,generation,40,character,CharacterProfession.WARRIOR,"skill.warrior.rising_strike",envelope(failed,generation,character,"skill.warrior.rising_strike"));throw new AssertionError("Injected domain failure accepted");}catch(SQLException expected){check("P0001".equals(expected.getSQLState()),"Unexpected SQL failure");}
            check(characters.profile(character).unspentSkillPoints()==2&&!skills.ranks(character).containsKey("skill.warrior.rising_strike")&&count("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?",failed)==0,"Domain rollback left charge/rank/journal");
            System.out.println("Skill UUID/legacy/concurrent replay, original-vs-latest cache, DB profession/level and atomic rollback PASS");
        } finally {
            try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.character_skill_ranks");q.execute("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}
            EnhancementReplayCheck.cleanup(account);
        }
    }
    private static RequestEnvelope envelope(UUID request,long generation,UUID character,String skill){var body=new JsonObject();body.addProperty("skillId",skill);return new RequestEnvelope(request,generation,character,"learn_skill",body);}
    private static void sql(String text,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){q.setObject(1,id);q.executeUpdate();}}
    private static long count(String text,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){q.setObject(1,id);try(var row=q.executeQuery()){row.next();return row.getLong(1);}}}
    private static void reject(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid skill mutation accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void cache(SkillRepository.LearningResult first,SkillRepository.LearningResult second,SkillRepository.LearningResult replay,CharacterProfile unrelated)throws Exception {
        String source=Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));int start=source.indexOf("private static void installSkillRanks("),open=source.indexOf('{',start),depth=1,end=open+1;while(depth>0){char x=source.charAt(end++);if(x=='{')depth++;if(x=='}')depth--;}
        String code="import java.util.*;import kr.dalmaru.account.*;import kr.dalmaru.skill.SkillRepository.LearningResult;public class SkillCacheProof{static class ConnectionState{CharacterProfile activeProfile;Map<String,Integer>skillRanks=new HashMap<>();}"+source.substring(start,end)+"public static void run(LearningResult first,LearningResult second,LearningResult replay,CharacterProfile unrelated){var state=new ConnectionState();state.activeProfile=unrelated;state.skillRanks.put(\"skill.warrior.sweep\",1);installSkillRanks(state,second);state.activeProfile=second.profile();installSkillRanks(state,first);if(state.skillRanks.get(\"skill.warrior.sweep\")!=2)throw new AssertionError(\"Reverse response rolled ranks back\");installSkillRanks(state,replay);if(state.skillRanks.get(\"skill.warrior.sweep\")!=2)throw new AssertionError(\"Replay rank replaced latest ranks\");}}";
        Path temp=Files.createTempDirectory("skill-cache-proof-");
        try{Path file=temp.resolve("SkillCacheProof.java");Files.writeString(file,code);check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",temp.toString(),file.toString())==0,"Cache fixture compilation");try(var loader=new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()},SkillJournalCheck.class.getClassLoader())){loader.loadClass("SkillCacheProof").getMethod("run",SkillRepository.LearningResult.class,SkillRepository.LearningResult.class,SkillRepository.LearningResult.class,CharacterProfile.class).invoke(null,first,second,replay,unrelated);}}
        finally{try(var paths=Files.walk(temp)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
    }
}
