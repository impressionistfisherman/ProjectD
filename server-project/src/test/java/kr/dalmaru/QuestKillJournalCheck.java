package kr.dalmaru;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.quest.*;

public final class QuestKillJournalCheck {
    public static void main(String[] args)throws Exception {
        FixtureDatabase.databaseName();
        UUID account=UUID.randomUUID();String trap="oscar_quest_kill_fail_"+account.toString().replace("-","");
        try {
            long generation=new AccountRepository().openSession(account).connectionGeneration();
            var characters=new CharacterRepository();var quests=new QuestRepository();
            UUID character=characters.create(account,generation,1,1,"kill_"+account.toString().substring(0,8)).character().characterId();
            characters.selectActive(account,generation,2,character);
            UUID beforeAcceptance=UUID.randomUUID();
            check(quests.recordZombieKillJournaled(account,generation,character,beforeAcceptance)==null,"No quest before acceptance");
            quests.acceptJournaled(account,generation,3,character,null);
            check(quests.recordZombieKillJournaled(account,generation,character,beforeAcceptance)==null&&quests.status(character).contains("0/3"),"Pre-acceptance event stays a no-op");
            UUID first=UUID.randomUUID();var pool=Executors.newFixedThreadPool(2);
            try {
                var a=pool.submit(()->quests.recordZombieKillJournaled(account,generation,character,first));
                var b=pool.submit(()->quests.recordZombieKillJournaled(account,generation,character,first));
                check((a.get()==null)!=(b.get()==null),"Concurrent kill applied once");
            }finally{pool.shutdownNow();}
            check(quests.status(character).contains("1/3"),"Duplicate kill cannot advance twice");
            quests.recordZombieKillJournaled(account,generation,character,UUID.randomUUID());
            var before=characters.profile(character);UUID finalKill=UUID.randomUUID();
            try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){
                q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.account_id='"+account+"'::uuid AND NEW.result IS NOT NULL THEN RAISE EXCEPTION 'quest kill journal failure'; END IF; RETURN NEW; END $$");
                q.execute("CREATE TRIGGER "+trap+" BEFORE UPDATE ON dalmaru.operation_requests FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            }
            try{quests.recordZombieKillJournaled(account,generation,character,finalKill);throw new AssertionError("Rollback not injected");}
            catch(SQLException expected){check("P0001".equals(expected.getSQLState()),"Unexpected SQL error");}
            check(quests.status(character).contains("2/3")&&characters.profile(character).equals(before),"Reward and progress rolled back");
            check(count(account)==3,"Failed reservation rolled back");
            drop(trap);
            check(quests.recordZombieKillJournaled(account,generation,character,finalKill).profile()!=null,"Same entity retry rewards once");
            var rewarded=characters.profile(character);
            check(!rewarded.equals(before)&&quests.status(character).contains("REWARDED"),"Reward committed");
            check(quests.recordZombieKillJournaled(account,generation,character,finalKill)==null,"Replay suppresses old profile/message");
            check(quests.recordZombieKillJournaled(account,generation,character,UUID.randomUUID())==null&&characters.profile(character).equals(rewarded),"Completed quest never rewards again");
            try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement("SELECT count(*) FROM dalmaru.operation_requests WHERE account_id=? AND operation='quest_kill' AND (state<>'completed' OR applied_at IS NULL)")){
                q.setObject(1,account);try(var row=q.executeQuery()){row.next();check(row.getLong(1)==0,"Database-only events require no world ACK");}
            }
            new AccountRepository().openSession(account);
            try{quests.recordZombieKillJournaled(account,generation,character,UUID.randomUUID());throw new AssertionError("Stale session accepted");}catch(IllegalArgumentException expected){}
            System.out.println("PASS quest kill identity/concurrency/no-op/replay/reward/confirmation rollback/stale session; database-only events completed");
        }finally{drop(trap);EnhancementReplayCheck.cleanup(account);}
    }
    private static long count(UUID account)throws SQLException {
        try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement("SELECT count(*) FROM dalmaru.operation_requests WHERE account_id=? AND operation='quest_kill'")){
            q.setObject(1,account);try(var row=q.executeQuery()){row.next();return row.getLong(1);}
        }
    }
    private static void drop(String trap)throws SQLException {
        try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.operation_requests");q.execute("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
