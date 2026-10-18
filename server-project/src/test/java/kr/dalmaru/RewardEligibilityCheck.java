package kr.dalmaru;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.run.*;
import kr.dalmaru.run.RunContribution.Action;

public final class RewardEligibilityCheck {
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        FixtureDatabase.databaseName();
        var idle=new RunContribution();check(!idle.record(Action.RECEIVED_HIT,50)&&idle.action()==null,"AFK damage qualified");
        idle.input(51);check(!idle.record(Action.RECEIVED_HIT,252),"Old input qualified");
        idle.input(253);check(idle.record(Action.RECEIVED_HIT,254),"Input then damage rejected");
        idle.disconnect();check(idle.action()==Action.RECEIVED_HIT&&!idle.record(Action.EFFECT,255),"Death/disconnect lost or repeated evidence");
        var disconnected=new RunContribution();disconnected.input(1);disconnected.disconnect();check(!disconnected.record(Action.RECEIVED_HIT,2),"Disconnect retained input");
        var mechanic=new RunContribution();check(!mechanic.record(Action.MECHANIC,10),"Idle proximity qualified");mechanic.input(11);check(mechanic.record(Action.MECHANIC,91),"Completed mechanic rejected");
        var effect=new RunContribution();check(effect.record(Action.EFFECT,1),"Actual effect rejected");
        List<UUID> accounts=new ArrayList<>(),participants=new ArrayList<>(),ownedRuns=new ArrayList<>();
        UUID owner=null;long generation=0;var repo=new RunRepository();String trap="eligibility_"+UUID.randomUUID().toString().replace("-","");
        try {
            for(int i=0;i<4;i++){
                UUID account=UUID.randomUUID();accounts.add(account);var session=new AccountRepository().openSession(account);
                UUID character=new CharacterRepository().create(account,session.connectionGeneration(),1,1,"el_"+account.toString().substring(0,8)).character().characterId();
                new CharacterRepository().selectActive(account,session.connectionGeneration(),2,character);sql("UPDATE dalmaru.character_profiles SET level=15 WHERE character_id=?",character);participants.add(character);
                if(i==0){owner=account;generation=session.connectionGeneration();}
            }
            UUID actor=participants.get(0),afk=participants.get(1),fallen=participants.get(2),left=participants.get(3);
            long actorWallet=number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",actor),afkWallet=number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",afk);
            UUID run=repo.enter(owner,generation,10,RunDifficulty.FIRST_STEP,participants).runId();ownedRuns.add(run);
            repo.recordContribution(run,actor,Action.EFFECT);repo.recordContribution(run,actor,Action.MECHANIC);
            repo.recordContribution(run,left,Action.EFFECT);repo.revokeContribution(run,left);repo.recordContribution(run,left,Action.EFFECT);
            reject(()->repo.recordContribution(run,UUID.randomUUID(),Action.EFFECT));
            var snapshot=Map.of(fallen,Action.RECEIVED_HIT,left,Action.EFFECT);
            repo.finish(owner,generation,11,run,true,snapshot);
            check(repo.rewards(run).stream().map(RunReward::characterId).collect(java.util.stream.Collectors.toSet()).equals(Set.of(actor,fallen)),"Personal eligibility leaked to AFK/revoked participant");
            check(number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",actor)==actorWallet+70&&number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",afk)==afkWallet,"Wallet eligibility wrong");
            check(number("SELECT count(*) FROM dalmaru.character_mailbox WHERE source_run_id=?",run)==2&&number("SELECT count(*) FROM dalmaru.character_currency_ledger WHERE source_run_id=?",run)==2,"Mailbox/ledger eligibility wrong");
            check(number("SELECT clear_count FROM dalmaru.character_run_weekly_clears WHERE character_id=?",left)==0,"Voluntary leave consumed a clear");
            repo.finish(owner,generation,11,run,true,snapshot);repo.finish(owner,generation,12,run,true,Map.of(afk,Action.EFFECT));
            check(repo.rewards(run).size()==2&&number("SELECT count(*) FROM dalmaru.run_contributions WHERE run_id=? AND character_id='"+afk+"'",run)==0,"Replay granted late qualification");
            reject(()->repo.recordContribution(run,afk,Action.EFFECT));reject(()->repo.revokeContribution(run,actor));

            UUID atomic=repo.enter(owner,generation,20,RunDifficulty.FIRST_STEP,participants).runId();ownedRuns.add(atomic);
            sql("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.run_id='"+atomic+"'::uuid AND NEW.state='COMPLETED' THEN RAISE EXCEPTION 'fixture finish failure'; END IF; RETURN NEW; END $$");
            sql("CREATE TRIGGER "+trap+" BEFORE UPDATE ON dalmaru.run_instances FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            final UUID account=owner;final long gen=generation;
            reject(()->repo.finish(account,gen,21,atomic,true,Map.of(actor,Action.EFFECT)));
            check(number("SELECT count(*) FROM dalmaru.run_contributions WHERE run_id=?",atomic)==0&&repo.rewards(atomic).isEmpty()&&number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",actor)==actorWallet+70,"Failed finish leaked evidence or reward");
            drop(trap);
            var pool=Executors.newFixedThreadPool(2);
            try{
                var start=new CountDownLatch(1);List<Future<RunRecord>> results=new ArrayList<>();
                for(int i=0;i<2;i++)results.add(pool.submit(()->{start.await();return repo.finish(account,gen,21,atomic,true,Map.of(actor,Action.EFFECT));}));
                start.countDown();for(var result:results)check(result.get(30,TimeUnit.SECONDS).state().equals("COMPLETED"),"Concurrent completion failed");
            }finally{pool.shutdownNow();}
            check(repo.rewards(atomic).size()==1&&number("SELECT count(*) FROM dalmaru.run_contributions WHERE run_id=?",atomic)==1&&number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",actor)==actorWallet+140,"Last-hit snapshot or concurrent idempotency failed");
            UUID noAction=repo.enter(owner,generation,30,RunDifficulty.FIRST_STEP,participants).runId();ownedRuns.add(noAction);repo.revokeContribution(noAction,left);
            repo.finish(owner,generation,31,noAction,true);check(repo.rewards(noAction).isEmpty(),"No-action clear rewarded");
            System.out.println("Reward eligibility AFK/input/effect/mechanic/death/revoke/candidate/last-hit/rollback/concurrent replay PASS");
        }finally{
            drop(trap);
            for(UUID run:ownedRuns)sql("DELETE FROM dalmaru.run_instances WHERE run_id=?",run);
            for(UUID account:accounts){sql("DELETE FROM dalmaru.operation_requests WHERE account_id=?",account);EnhancementReplayCheck.cleanup(account);}
        }
    }
    private static void sql(String text,Object... args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
    private static long number(String text,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){q.setObject(1,id);try(var row=q.executeQuery()){row.next();return row.getLong(1);}}}
    private static void drop(String trap)throws SQLException{sql("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.run_instances");sql("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}
    private static void reject(Operation operation)throws Exception{try{operation.run();}catch(IllegalArgumentException|SQLException expected){return;}throw new AssertionError("Invalid action accepted");}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
