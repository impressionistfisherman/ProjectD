package kr.dalmaru;

import com.google.gson.JsonObject;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.equipment.EquipmentRepository;
import kr.dalmaru.request.RequestEnvelope;

public final class P5GrowthRogueResetCheck {
    private static long correlation=60000;
    public static void main(String[] args)throws Exception {
        FixtureDatabase.databaseName();FixtureMigrations.apply();
        run("rogue.bandit",true);run("rogue.pirate",false);
        System.out.println("PASS rogue redistribution: both branches, exact strength+dexterity budget, other stats/free-reset/wallet/equipment preservation, one-use, same/different UUID concurrency, stale/forged inputs, rollback and later-state replay");
    }
    private static void run(String branch,boolean freeReset)throws Exception {
        UUID account=UUID.randomUUID();String trap="p5_rogue_"+account.toString().replace("-","");
        try {
            var repo=new CharacterRepository();long generation=new AccountRepository().openSession(account).connectionGeneration();
            UUID character=repo.create(account,generation,next(),1,"rogue_"+account.toString().substring(0,8)).character().characterId();
            repo.selectActive(account,generation,next(),character);
            long initialRevision=repo.profile(character).revision();
            fail("FORBIDDEN","profession_mismatch",()->call(repo,account,generation,character,0,0,initialRevision,null));
            // Isolate S03 with an earned-milestone fixture; S02 tests the real award transaction separately.
            sql("UPDATE dalmaru.character_profiles SET level=30,unspent_stat_points=145 WHERE character_id=?",character);
            try(var c=connection()){ProfessionGateFixture.complete(c,character,CharacterProfession.ROGUE);}
            repo.selectProfession(account,generation,next(),character,CharacterProfession.ROGUE);
            if(!freeReset)repo.resetStats(account,generation,next(),character);
            repo.investStat(account,generation,next(),character,CharacterStat.STRENGTH,10);
            repo.investStat(account,generation,next(),character,CharacterStat.DEXTERITY,20);
            repo.investStat(account,generation,next(),character,CharacterStat.ARCANA,3);
            repo.investStat(account,generation,next(),character,CharacterStat.VITALITY,4);
            repo.investStat(account,generation,next(),character,CharacterStat.SPIRIT,5);
            var before=repo.profile(character);long revision=before.revision();
            check(before.freeResetAvailable()==freeReset&&before.unspentStatPoints()==103,"Fixture points/free reset wrong");
            var equipment=new EquipmentRepository().listOrCreate(character);
            long copper=value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character);
            long ledger=value("SELECT COUNT(*) FROM dalmaru.character_currency_ledger WHERE character_id=?",character);
            fail("NOT_READY","quest_required",()->call(repo,account,generation,character,30,0,revision,null));
            try(var c=connection();var q=c.prepareStatement("INSERT INTO dalmaru.character_advancements(character_id,branch,advanced_30_at) VALUES(?,?,CURRENT_TIMESTAMP)")){q.setObject(1,character);q.setString(2,branch);q.executeUpdate();}
            check(repo.growthSnapshot(account,generation,character).rogueResetAvailable(),"Earned redistribution unavailable");
            fail("FIELD_LIMIT","invalid_payload",()->call(repo,account,generation,character,-1,31,revision,null));
            fail("FIELD_LIMIT","invalid_payload",()->call(repo,account,generation,character,Integer.MAX_VALUE,Integer.MAX_VALUE,revision,null));
            fail("FIELD_LIMIT","invalid_payload",()->call(repo,account,generation,character,30,1,revision,null));
            fail("CONFLICT","stale_revision",()->call(repo,account,generation,character,30,0,revision+1,null));
            fail("STALE_SESSION","inactive_character",()->call(repo,account,generation+1,character,30,0,revision,null));
            var forged=body(30,0,revision);forged.addProperty("freeResetAvailable",true);
            var invalid=new RequestEnvelope(UUID.randomUUID(),generation,character,"reallocate_rogue",forged);
            fail("FIELD_LIMIT","invalid_payload",()->call(repo,account,generation,character,30,0,revision,invalid));
            check(value("SELECT COUNT(*) FROM dalmaru.operation_requests WHERE account_id=? AND operation='reallocate_rogue'",account)==0,"Rejected request committed a journal");
            if(freeReset) {
                inRun(repo,account,generation,character,revision);
                try(var c=connection();var q=c.createStatement()) {
                    q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.character_id='"+character+"'::uuid AND NEW.rogue_reset_used AND NOT OLD.rogue_reset_used THEN RAISE EXCEPTION 'P5 rogue rollback injection'; END IF; RETURN NEW; END $$");
                    q.execute("CREATE TRIGGER "+trap+" BEFORE UPDATE ON dalmaru.character_advancements FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
                }
                try{call(repo,account,generation,character,30,0,revision,null);throw new AssertionError("Missing rollback injection");}
                catch(SQLException error){check("P0001".equals(error.getSQLState()),"Unexpected SQL failure");}
                finally{drop(trap);}
                check(repo.profile(character).equals(before)&&repo.growthSnapshot(account,generation,character).rogueResetAvailable(),"Partial redistribution survived rollback");
                check(value("SELECT COUNT(*) FROM dalmaru.operation_requests WHERE account_id=? AND operation='reallocate_rogue'",account)==0,"Rolled-back request persisted");
            }
            var request=new RequestEnvelope(UUID.randomUUID(),generation,character,"reallocate_rogue",body(30,0,revision));
            var pool=Executors.newFixedThreadPool(2);CharacterRepository.GrowthResult original;RequestEnvelope winningRequest=request;
            try {
                long a=next(),b=next();
                var other=freeReset?request:new RequestEnvelope(UUID.randomUUID(),generation,character,"reallocate_rogue",body(0,30,revision));
                var x=pool.submit(()->repo.reallocateRogueJournaled(account,generation,a,character,30,0,revision,request));
                var y=pool.submit(()->repo.reallocateRogueJournaled(account,generation,b,character,freeReset?30:0,freeReset?0:30,revision,other));
                if(freeReset) {
                    var left=x.get();var right=y.get();check(left.replayed()!=right.replayed(),"Same UUID consumed right twice");
                    check(left.originalResult().equals(right.originalResult()),"Same UUID result changed");original=left;
                } else {
                    CharacterRepository.GrowthResult left=resultOrUsed(x),right=resultOrUsed(y);
                    check((left==null)!=(right==null),"Different UUIDs both consumed right");original=left==null?right:left;
                    if(left==null)winningRequest=other;
                }
            } finally {pool.shutdownNow();}
            var after=repo.profile(character);
            check(after.strength()+after.dexterity()==30&&(after.strength()==30||after.dexterity()==30),"Redistribution target/budget wrong");
            check(after.arcana()==before.arcana()&&after.vitality()==before.vitality()&&after.spirit()==before.spirit(),"Other stats changed");
            check(after.unspentStatPoints()==before.unspentStatPoints()&&after.unspentSkillPoints()==before.unspentSkillPoints(),"Unspent budget changed");
            check(after.freeResetAvailable()==freeReset,"General free reset consumed or restored");
            check(after.level()==before.level()&&after.experience()==before.experience()&&after.revision()>before.revision(),"Growth changed or revision stale");
            check(!repo.growthSnapshot(account,generation,character).rogueResetAvailable(),"One-use right remained available");
            check(copper==value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)&&ledger==value("SELECT COUNT(*) FROM dalmaru.character_currency_ledger WHERE character_id=?",character),"Redistribution charged copper");
            check(equipment.equals(new EquipmentRepository().listOrCreate(character)),"Redistribution modified equipment");
            fail("DUPLICATE","rogue_reset_used",()->call(repo,account,generation,character,0,30,after.revision(),null));
            var saved=original.originalResult();original.originalResult().addProperty("strengthAllocated",99);
            check(saved.equals(original.originalResult()),"Mutable result escaped");
            if(freeReset) {
                var ordinary=repo.resetStats(account,generation,next(),character);
                check(!ordinary.profile().freeResetAvailable()&&ordinary.profile().unspentStatPoints()==145,"General free reset no longer worked");
            } else repo.investStat(account,generation,next(),character,CharacterStat.STRENGTH,1);
            var later=repo.profile(character);int savedStrength=saved.get("strengthAllocated").getAsInt(),savedDexterity=saved.get("dexterityAllocated").getAsInt();
            var replay=call(repo,account,generation,character,savedStrength,savedDexterity,revision,winningRequest);
            check(replay.replayed()&&replay.originalResult().equals(saved)&&repo.profile(character).equals(later),"Replay rewrote later stats or original result");
            check(!repo.growthSnapshot(account,generation,character).rogueResetAvailable(),"General reset restored rogue right");
            var changed=new RequestEnvelope(winningRequest.requestId(),generation,character,"reallocate_rogue",body(15,15,revision));
            reject(()->call(repo,account,generation,character,15,15,revision,changed));
        } finally {drop(trap);cleanup(account);}
    }
    private static CharacterRepository.GrowthResult resultOrUsed(Future<CharacterRepository.GrowthResult> result)throws Exception {
        try{return result.get();}catch(ExecutionException error){if(error.getCause() instanceof CharacterRepository.GrowthException growth){check(growth.status().equals("DUPLICATE")||growth.status().equals("CONFLICT"),"Unexpected concurrent rejection");return null;}throw error;}
    }
    private static void inRun(CharacterRepository repo,UUID account,long generation,UUID character,long revision)throws Exception {
        UUID run=UUID.randomUUID();
        try {
            try(var c=connection()) {
                try(var q=c.prepareStatement("INSERT INTO dalmaru.run_instances(run_id,content_id,difficulty,week_id,party_size,scaling_tier,state) VALUES(?,'run.bongsudae','FIRST_STEP',CURRENT_DATE,1,1,'ACTIVE')")){q.setObject(1,run);q.executeUpdate();}
                try(var q=c.prepareStatement("INSERT INTO dalmaru.run_participants(run_id,character_id) VALUES(?,?)")){q.setObject(1,run);q.setObject(2,character);q.executeUpdate();}
            }
            fail("FORBIDDEN","in_run",()->call(repo,account,generation,character,30,0,revision,null));
            sql("UPDATE dalmaru.run_instances SET state='PAUSED' WHERE run_id=?",run);
            fail("FORBIDDEN","in_run",()->call(repo,account,generation,character,30,0,revision,null));
        } finally {sql("DELETE FROM dalmaru.run_instances WHERE run_id=?",run);}
    }
    private static CharacterRepository.GrowthResult call(CharacterRepository repo,UUID account,long generation,UUID character,int strength,int dexterity,long revision,RequestEnvelope envelope)throws SQLException {
        return repo.reallocateRogueJournaled(account,generation,next(),character,strength,dexterity,revision,envelope==null?new RequestEnvelope(UUID.randomUUID(),generation,character,"reallocate_rogue",body(strength,dexterity,revision)):envelope);
    }
    private static JsonObject body(int strength,int dexterity,long revision){var body=new JsonObject();body.addProperty("strengthAllocated",strength);body.addProperty("dexterityAllocated",dexterity);body.addProperty("expectedRevision",revision);return body;}
    private static void drop(String trap)throws SQLException{try(var c=connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.character_advancements");q.execute("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}}
    private static void cleanup(UUID account)throws SQLException {
        sql("DELETE FROM dalmaru.character_stat_investment_requests WHERE account_id=?",account);
        sql("DELETE FROM dalmaru.character_stat_reset_requests WHERE account_id=?",account);
        EnhancementReplayCheck.cleanup(account);
    }
    private static void fail(String status,String reason,Action action)throws Exception{try{action.run();throw new AssertionError("Expected "+status+"/"+reason);}catch(CharacterRepository.GrowthException error){check(error.status().equals(status)&&error.reason().equals(reason),"Unexpected rejection "+error.status()+"/"+error.reason());}}
    private static void reject(Action action)throws Exception{try{action.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException expected){}}
    private static Connection connection()throws SQLException{return EnhancementReplayCheck.connection();}
    private static void sql(String sql,UUID id)throws SQLException{try(var c=connection();var q=c.prepareStatement(sql)){q.setObject(1,id);q.executeUpdate();}}
    private static long value(String sql,UUID id)throws SQLException{try(var c=connection();var q=c.prepareStatement(sql)){q.setObject(1,id);try(var row=q.executeQuery()){if(!row.next())throw new AssertionError("Missing value");return row.getLong(1);}}}
    private static synchronized long next(){return ++correlation;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    @FunctionalInterface private interface Action{void run()throws Exception;}
}
