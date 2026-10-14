package kr.dalmaru;

import com.google.gson.JsonObject;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.request.*;

public final class ResetJournalCheck {
    private interface Action {void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        if(!"oscar_p1_fixture".equals(System.getenv("DALMARU_DB_NAME")))throw new IllegalStateException("Dedicated P1 fixture required");
        UUID account=UUID.randomUUID();String trap="oscar_reset_fail_"+account.toString().replace("-","");
        try {
            var session=new AccountRepository().openSession(account);var characters=new CharacterRepository();long generation=session.connectionGeneration();
            UUID character=characters.create(account,generation,1,1,"reset_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            sql("UPDATE dalmaru.character_profiles SET strength=3,dexterity=2,unspent_stat_points=10,free_reset_available=true WHERE character_id=?",character);
            var failed=envelope(UUID.randomUUID(),generation,character);
            try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()) {
                q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.character_id='"+character+"'::uuid THEN RAISE EXCEPTION 'fixture reset rollback'; END IF; RETURN NEW; END $$");
                q.execute("CREATE TRIGGER "+trap+" BEFORE INSERT ON dalmaru.character_stat_reset_requests FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            }
            try{characters.resetStatsJournaled(account,generation,3,character,failed);throw new AssertionError("Injected rollback not observed");}catch(SQLException expected){check("P0001".equals(expected.getSQLState()),"Unexpected SQL error");}
            var before=characters.profile(character);check(before.strength()==3&&before.dexterity()==2&&before.unspentStatPoints()==10&&before.freeResetAvailable(),"Reset/refund rollback");
            try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?")) {
                q.setObject(1,failed.requestId());try(var row=q.executeQuery()){row.next();check(row.getInt(1)==0,"Journal rollback");}
            }
            drop(trap);
            var first=envelope(UUID.randomUUID(),generation,character);var pool=Executors.newFixedThreadPool(2);
            try {
                var a=pool.submit(()->characters.resetStatsJournaled(account,generation,4,character,first));
                var b=pool.submit(()->characters.resetStatsJournaled(account,generation,5,character,first));
                check(a.get().replayed()!=b.get().replayed(),"Concurrent reset one write");
            }finally{pool.shutdownNow();}
            var reset=characters.profile(character);check(reset.strength()==0&&reset.dexterity()==0&&reset.unspentStatPoints()==15&&!reset.freeResetAvailable(),"Single refund");
            characters.investStat(account,generation,6,character,CharacterStat.STRENGTH,1);
            var replay=characters.resetStatsJournaled(account,generation,70,character,first);
            check(replay.replayed()&&replay.profile().strength()==1&&replay.profile().unspentStatPoints()==14&&replay.originalResult().get("strength").getAsInt()==0&&replay.originalResult().get("unspentStatPoints").getAsInt()==15,"Original reset versus latest profile");
            var altered=new JsonObject();altered.addProperty("unexpected",1);
            reject(()->characters.resetStatsJournaled(account,generation,71,character,new RequestEnvelope(first.requestId(),generation,character,"reset_stats",altered)));
            reject(()->characters.resetStatsJournaled(account,generation+1,71,character,envelope(first.requestId(),generation+1,character)));
            reject(()->characters.resetStatsJournaled(account,generation,71,character,envelope(UUID.randomUUID(),generation,character)));
            long legacyId;
            try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement("SELECT legacy_request_id FROM dalmaru.operation_requests WHERE request_id=?")){
                q.setObject(1,first.requestId());try(var row=q.executeQuery()){row.next();legacyId=row.getLong(1);}
            }
            // Remove only the journal to simulate a reset made by a pre-UUID client.
            sql("DELETE FROM dalmaru.operation_requests WHERE request_id=?",first.requestId());
            var legacy=characters.resetStatsJournaled(account,generation,legacyId,character,null);
            check(legacy.replayed()&&legacy.profile().strength()==1&&legacy.originalResult().get("strength").getAsInt()==0,"Legacy reset adoption");
            check(characters.resetStats(account,generation,legacyId,character).replayed(),"Legacy reset replay");
            System.out.println("PASS reset UUID/concurrent/legacy replay, original/latest, body/generation/free-reset rejection, atomic rollback");
        } finally {
            drop(trap);sql("DELETE FROM dalmaru.character_stat_reset_requests WHERE account_id=?",account);sql("DELETE FROM dalmaru.character_stat_investment_requests WHERE account_id=?",account);EnhancementReplayCheck.cleanup(account);
        }
    }
    private static RequestEnvelope envelope(UUID id,long generation,UUID character){return new RequestEnvelope(id,generation,character,"reset_stats",new JsonObject());}
    private static void drop(String trap)throws SQLException {try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.character_stat_reset_requests");q.execute("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}}
    private static void sql(String text,UUID id)throws SQLException {try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){q.setObject(1,id);q.executeUpdate();}}
    private static void reject(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid reset accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
