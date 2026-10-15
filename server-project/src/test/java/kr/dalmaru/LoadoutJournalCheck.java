package kr.dalmaru;

import com.google.gson.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.request.*;
import kr.dalmaru.skill.*;
import kr.dalmaru.run.*;

public final class LoadoutJournalCheck {
    private interface Action{void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        if(!"oscar_p1_fixture".equals(System.getenv("DALMARU_DB_NAME")))throw new IllegalStateException("Dedicated P1 fixture required");
        UUID account=UUID.randomUUID();String trap="oscar_loadout_fail_"+account.toString().replace("-","");var runs=new ArrayList<UUID>();
        try {
            var session=new AccountRepository().openSession(account);var characters=new CharacterRepository();long generation=session.connectionGeneration();
            UUID character=characters.create(account,generation,1,1,"slots_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            sql("UPDATE dalmaru.character_profiles SET level=15 WHERE character_id=?",character);
            sql("INSERT INTO dalmaru.character_professions(character_id,base_job) VALUES(?,'WARRIOR')",character);
            for(var skill:SkillDefinition.forProfession(CharacterProfession.WARRIOR))sql("INSERT INTO dalmaru.character_skill_ranks(character_id,skill_id,rank) VALUES(?,?,1)",character,skill.id());
            var repository=new LoadoutRepository();var defaults=repository.load(character).slots();var swapped=new ArrayList<>(defaults);Collections.swap(swapped,0,1);var slots=List.copyOf(swapped);
            var first=envelope(UUID.randomUUID(),generation,character,0,slots);var pool=Executors.newFixedThreadPool(2);
            try {
                var a=pool.submit(()->repository.updateJournaled(account,generation,character,3,0,slots,first));
                var b=pool.submit(()->repository.updateJournaled(account,generation,character,4,0,slots,first));
                check(a.get().replayed()!=b.get().replayed(),"Concurrent single revision increment");
            }finally{pool.shutdownNow();}
            check(repository.load(character).revision()==1,"First revision");
            repository.update(account,generation,character,5,1,defaults);
            var replay=repository.updateJournaled(account,generation,character,50,0,slots,first);
            check(replay.replayed()&&replay.original().revision()==1&&replay.original().slots().equals(slots)&&replay.current().revision()==2&&replay.current().slots().equals(defaults),"Original versus latest loadout");
            reject(()->repository.updateJournaled(account,generation,character,51,1,slots,envelope(first.requestId(),generation,character,1,slots)));
            reject(()->repository.updateJournaled(account,generation+1,character,51,0,slots,envelope(first.requestId(),generation+1,character,0,slots)));
            reject(()->repository.update(account,generation,character,51,0,slots));
            var invalid=new ArrayList<>(slots);invalid.set(0,"skill.warrior.body_training");reject(()->repository.update(account,generation,character,51,2,invalid));
            invalid.set(0,slots.get(1));reject(()->repository.update(account,generation,character,51,2,invalid));
            invalid.set(0,null);reject(()->repository.update(account,generation,character,51,2,invalid));
            repository.update(account,generation,character,6,2,slots);
            sql("DELETE FROM dalmaru.operation_requests WHERE account_id=? AND operation='update_loadout' AND legacy_request_id=5",account);
            var adopted=repository.updateJournaled(account,generation,character,5,1,defaults,null);
            check(adopted.replayed()&&adopted.original().revision()==2&&adopted.current().revision()==3,"Legacy original snapshot adoption");
            check(repository.update(account,generation,character,5,1,defaults).revision()==3,"Legacy wrapper returns latest");
            var failed=envelope(UUID.randomUUID(),generation,character,3,defaults);
            try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()) {
                q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.character_id='"+character+"'::uuid THEN RAISE EXCEPTION 'fixture loadout rollback'; END IF; RETURN NEW; END $$");
                q.execute("CREATE TRIGGER "+trap+" BEFORE INSERT ON dalmaru.skill_loadout_requests FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            }
            try{repository.updateJournaled(account,generation,character,7,3,defaults,failed);throw new AssertionError("Rollback not injected");}catch(SQLException expected){check("P0001".equals(expected.getSQLState()),"Unexpected SQL error");}
            check(repository.load(character).equals(new SkillLoadout(character,3,slots))&&count("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?",failed.requestId())==0,"Slots/revision/journal rollback");
            drop(trap);
            var runRepository=new RunRepository();var run=runRepository.enter(account,generation,20,RunDifficulty.FIRST_STEP,List.of(character));runs.add(run.runId());
            check(runRepository.frozenLoadout(run.runId(),character).equals(repository.load(character)),"Frozen loadout");
            reject(()->repository.update(account,generation,character,21,3,defaults));
            var activeReplay=repository.updateJournaled(account,generation,character,22,0,slots,first);
            check(activeReplay.replayed()&&activeReplay.original().revision()==1&&activeReplay.current().revision()==3&&runRepository.frozenLoadout(run.runId(),character).revision()==3,"Read-only replay during active run");
            runRepository.finish(account,generation,23,run.runId(),false);
            check(repository.update(account,generation,character,24,3,defaults).revision()==4&&runRepository.frozenLoadout(run.runId(),character).revision()==3,"Post-run change preserves frozen history");
            System.out.println("PASS loadout UUID/concurrent/legacy, original/latest, stale/body/generation/kind/duplicate/null, rollback, active lock and frozen history");
        }finally{
            drop(trap);for(UUID run:runs)sql("DELETE FROM dalmaru.run_instances WHERE run_id=?",run);
            sql("DELETE FROM dalmaru.skill_loadout_requests WHERE account_id=?",account);EnhancementReplayCheck.cleanup(account);
        }
    }
    private static RequestEnvelope envelope(UUID id,long generation,UUID character,long revision,List<String> slots){var body=new JsonObject();body.addProperty("revision",revision);body.add("slots",JsonParser.parseString(LoadoutRepository.encode(slots)));return new RequestEnvelope(id,generation,character,"update_loadout",body);}
    private static void drop(String trap)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.skill_loadout_requests");q.execute("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}}
    private static void sql(String text,Object... args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
    private static long count(String text,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){q.setObject(1,id);try(var row=q.executeQuery()){row.next();return row.getLong(1);}}}
    private static void reject(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid loadout accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
