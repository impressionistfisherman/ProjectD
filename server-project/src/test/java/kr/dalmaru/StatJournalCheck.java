package kr.dalmaru;

import com.google.gson.JsonObject;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.request.*;

public final class StatJournalCheck {
    private interface Action { void run() throws Exception; }
    public static void main(String[] args)throws Exception {
        if(!"oscar_p1_fixture".equals(System.getenv("DALMARU_DB_NAME")))throw new IllegalStateException("Dedicated P1 fixture required");
        UUID account=UUID.randomUUID();String trap="oscar_stat_fail_"+account.toString().replace("-","");
        try {
            var session=new AccountRepository().openSession(account);var characters=new CharacterRepository();long generation=session.connectionGeneration();
            UUID character=characters.create(account,generation,1,1,"stat_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            sql("UPDATE dalmaru.character_profiles SET unspent_stat_points=20 WHERE character_id=?",character);
            var first=envelope(UUID.randomUUID(),generation,character,2);
            var saved=characters.investStatJournaled(account,generation,3,character,CharacterStat.STRENGTH,2,first);
            check(!saved.replayed()&&saved.profile().strength()==2&&saved.profile().unspentStatPoints()==18,"first investment");
            characters.investStatJournaled(account,generation,4,character,CharacterStat.STRENGTH,1,envelope(UUID.randomUUID(),generation,character,1));
            var replay=characters.investStatJournaled(account,generation,55,character,CharacterStat.STRENGTH,2,first);
            check(replay.replayed()&&replay.profile().strength()==3&&replay.profile().unspentStatPoints()==17,"replay latest state");
            check(replay.originalResult().equals(saved.originalResult())&&replay.originalResult().get("strength").getAsInt()==2,"immutable original result");
            var legacy=characters.investStat(account,generation,6,character,CharacterStat.STRENGTH,1);
            check(!legacy.replayed()&&characters.investStat(account,generation,6,character,CharacterStat.STRENGTH,1).replayed(),"legacy replay");
            reject(()->characters.investStatJournaled(account,generation,7,character,CharacterStat.STRENGTH,3,envelope(first.requestId(),generation,character,3)));
            reject(()->characters.investStatJournaled(account,generation+1,7,character,CharacterStat.STRENGTH,2,envelope(first.requestId(),generation+1,character,2)));
            reject(()->characters.investStatJournaled(account,generation,7,character,CharacterStat.STRENGTH,99,envelope(UUID.randomUUID(),generation,character,99)));
            var concurrent=envelope(UUID.randomUUID(),generation,character,2);
            var pool=Executors.newFixedThreadPool(2);
            try {
                var a=pool.submit(()->characters.investStatJournaled(account,generation,8,character,CharacterStat.STRENGTH,2,concurrent));
                var b=pool.submit(()->characters.investStatJournaled(account,generation,9,character,CharacterStat.STRENGTH,2,concurrent));
                check(a.get().replayed()!=b.get().replayed(),"concurrent one write");
            } finally {pool.shutdownNow();}
            check(characters.profile(character).strength()==6,"concurrent no duplicate debit");
            // Adopt a request saved before UUID journals existed without another debit.
            var old=characters.investStat(account,generation,10,character,CharacterStat.STRENGTH,1);
            sql("DELETE FROM dalmaru.operation_requests WHERE account_id=? AND legacy_request_id=10",account);
            characters.investStat(account,generation,11,character,CharacterStat.STRENGTH,1);
            var adopted=characters.investStatJournaled(account,generation,10,character,CharacterStat.STRENGTH,1,null);
            check(adopted.replayed()&&adopted.profile().strength()==8&&adopted.originalResult().get("strength").getAsInt()==old.profile().strength(),"adopt legacy original snapshot");
            var failed=envelope(UUID.randomUUID(),generation,character,1);
            try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()) {
                q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.character_id='"+character+"'::uuid THEN RAISE EXCEPTION 'fixture rollback'; END IF; RETURN NEW; END $$");
                q.execute("CREATE TRIGGER "+trap+" BEFORE INSERT ON dalmaru.character_stat_investment_requests FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            }
            try{characters.investStatJournaled(account,generation,12,character,CharacterStat.STRENGTH,1,failed);throw new AssertionError("rollback failure not injected");}catch(SQLException expected){}
            check(characters.profile(character).strength()==8,"debit rollback");
            try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?")) {
                q.setObject(1,failed.requestId());try(var row=q.executeQuery()){row.next();check(row.getInt(1)==0,"journal rollback");}
            }
            System.out.println("PASS stat UUID replay, original/latest, legacy adoption, body/generation rejection, concurrency, rollback");
        } finally {
            try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()) {
                q.execute("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.character_stat_investment_requests");q.execute("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");
            }
            sql("DELETE FROM dalmaru.character_stat_investment_requests WHERE account_id=?",account);
            EnhancementReplayCheck.cleanup(account);
        }
    }
    private static RequestEnvelope envelope(UUID id,long generation,UUID character,int amount) {
        var body=new JsonObject();body.addProperty("statId",CharacterStat.STRENGTH.id());body.addProperty("amount",amount);
        return new RequestEnvelope(id,generation,character,"invest_stat",body);
    }
    private static void sql(String text,UUID id)throws SQLException {try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){q.setObject(1,id);q.executeUpdate();}}
    private static void reject(Action action)throws Exception {try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid stat mutation accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
