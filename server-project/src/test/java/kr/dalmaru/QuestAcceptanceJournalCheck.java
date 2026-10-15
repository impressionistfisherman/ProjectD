package kr.dalmaru;

import com.google.gson.JsonObject;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.quest.*;
import kr.dalmaru.request.*;

public final class QuestAcceptanceJournalCheck {
    private interface Action{void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        if(!"oscar_p1_fixture".equals(System.getenv("DALMARU_DB_NAME")))throw new IllegalStateException("Dedicated P1 fixture required");
        UUID account=UUID.randomUUID();String trap="oscar_quest_accept_fail_"+account.toString().replace("-","");
        try {
            var session=new AccountRepository().openSession(account);var characters=new CharacterRepository();long generation=session.connectionGeneration();
            UUID character=characters.create(account,generation,1,1,"quest_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            UUID inactive=characters.create(account,generation,99,2,"qother_"+account.toString().substring(0,8)).character().characterId();
            var quests=new QuestRepository();var first=envelope(UUID.randomUUID(),generation,character,QuestRepository.contentVersion());var pool=Executors.newFixedThreadPool(2);
            try {
                var a=pool.submit(()->quests.acceptJournaled(account,generation,3,character,first));var b=pool.submit(()->quests.acceptJournaled(account,generation,4,character,first));
                var ar=a.get();var br=b.get();check(ar.replayed()!=br.replayed()&&ar.created()&&br.created(),"Concurrent one acceptance and original result");
            }finally{pool.shutdownNow();}
            check(count("SELECT count(*) FROM dalmaru.character_quest_progress WHERE character_id=?",character)==1,"Single quest row");
            var repeated=quests.acceptJournaled(account,generation,5,character,null);check(!repeated.created()&&!repeated.replayed()&&quests.acceptJournaled(account,generation,5,character,null).replayed(),"Already accepted and long replay");
            quests.recordZombieKill(character);quests.recordZombieKill(character);
            var progress=quests.acceptJournaled(account,generation,50,character,first);check(progress.created()&&progress.replayed()&&progress.currentStatus().contains("2/3")&&progress.currentStatus().contains("ACTIVE"),"Original acceptance/latest progress");
            check(quests.recordZombieKill(character).profile()!=null,"Fixture reward");var rewarded=characters.profile(character);
            var completed=quests.acceptJournaled(account,generation,51,character,first);check(completed.created()&&completed.replayed()&&completed.currentStatus().contains("REWARDED")&&characters.profile(character).equals(rewarded),"Replay does not restart quest or grant reward");
            reject(()->quests.acceptJournaled(account,generation,52,character,envelope(first.requestId(),generation,character,"changed-version")));
            reject(()->quests.acceptJournaled(account,generation,52,inactive,envelope(UUID.randomUUID(),generation,inactive,QuestRepository.contentVersion())));
            characters.selectActive(account,generation,60,inactive);
            var failed=envelope(UUID.randomUUID(),generation,inactive,QuestRepository.contentVersion());
            try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){
                q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.character_id='"+inactive+"'::uuid AND NEW.result IS NOT NULL THEN RAISE EXCEPTION 'fixture quest confirm rollback'; END IF; RETURN NEW; END $$");
                q.execute("CREATE TRIGGER "+trap+" BEFORE UPDATE ON dalmaru.operation_requests FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            }
            try{quests.acceptJournaled(account,generation,6,inactive,failed);throw new AssertionError("Rollback not injected");}catch(SQLException expected){check("P0001".equals(expected.getSQLState()),"Unexpected SQL error");}
            check(count("SELECT count(*) FROM dalmaru.character_quest_progress WHERE character_id=?",inactive)==0&&count("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?",failed.requestId())==0,"Quest insert/journal confirmation rollback");
            drop(trap);check(quests.acceptJournaled(account,generation,6,inactive,failed).created(),"Same UUID retry after rollback");
            reject(()->quests.acceptJournaled(account,generation,52,character,first));
            new AccountRepository().openSession(account);reject(()->quests.acceptJournaled(account,generation,6,inactive,failed));
            System.out.println("PASS quest acceptance UUID/concurrent/long, original/latest-progress/reward, owner/body/version/stale-session, post-insert journal rollback");
        }finally{drop(trap);EnhancementReplayCheck.cleanup(account);}
    }
    private static RequestEnvelope envelope(UUID id,long generation,UUID character,String version){var body=new JsonObject();body.addProperty("questId",QuestRepository.QUEST_ID);body.addProperty("contentVersion",version);return new RequestEnvelope(id,generation,character,"accept_quest",body);}
    private static void drop(String trap)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.operation_requests");q.execute("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}}
    private static long count(String text,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){q.setObject(1,id);try(var row=q.executeQuery()){row.next();return row.getLong(1);}}}
    private static void reject(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid quest acceptance accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
