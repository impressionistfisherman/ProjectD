package kr.dalmaru;

import com.google.gson.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.content.*;
import kr.dalmaru.quest.StoryQuestRepository;
import kr.dalmaru.request.RequestEnvelope;

public final class P5GrowthAdvancementCheck {
    private static final String FLAG="dalmaru.p5.development";
    private static long correlation=40000;
    public static void main(String[] args)throws Exception {
        FixtureDatabase.databaseName();FixtureMigrations.apply();
        String previous=System.getProperty(FLAG);System.setProperty(FLAG,"true");
        try {
            run(CharacterProfession.WARRIOR,"warrior.twohand",true);
            run(CharacterProfession.ARCHER,"archer.bow",false);
            run(CharacterProfession.MAGE,"mage.fan",false);
            run(CharacterProfession.ROGUE,"rogue.bandit",false);
            check(CharacterRepository.TRIAL_LEVEL_CAP==20,"Product cap opened by S02");
        } finally {if(previous==null)System.clearProperty(FLAG);else System.setProperty(FLAG,previous);}
        System.out.println("PASS P5 advancement 30/50/60, four professions, 81 points, NPC/story atomicity, cap20, stale/forged requests, concurrent replay, rollback and V043 constraints");
    }
    private static void run(CharacterProfession profession,String branch,boolean detailed)throws Exception {
        UUID account=UUID.randomUUID();String trap="p5_growth_"+account.toString().replace("-","");
        try {
            var village=VillageCatalog.current().stream().filter(v->v.profession().equals(profession.name())).findFirst().orElseThrow();
            String npc=village.npcs().getFirst().id();var zone=village.zone();
            var position=new StoryQuestRepository.Position(zone.dimension(),zone.x(),zone.y(),zone.z());
            var catalog=catalog(npc);var story=new StoryQuestRepository((c,id)->false,catalog);
            var repo=new CharacterRepository(story);long generation=new AccountRepository().openSession(account).connectionGeneration();
            UUID character=repo.create(account,generation,next(),1,"p5_"+account.toString().substring(0,8)).character().characterId();
            repo.selectActive(account,generation,next(),character);
            var initial=repo.growthSnapshot(account,generation,character);
            check(initial.branch().isEmpty()&&!initial.advance30()&&!initial.advance50()&&!initial.advance60()&&!initial.rogueResetAvailable()&&initial.levelCap()==20,"Existing character defaults changed");
            sql("UPDATE dalmaru.character_profiles SET level=10 WHERE character_id=?",character);
            try(var c=connection()){ProfessionGateFixture.complete(c,character,profession);}
            repo.selectProfession(account,generation,next(),character,profession);
            seedEarlierStories(character,catalog);
            raise(repo,character,29);var at29=repo.profile(character);
            fail("NOT_READY","level_required",()->confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,branch,at29.revision(),position,null));
            raise(repo,character,30);
            var before=repo.profile(character);
            System.clearProperty(FLAG);
            fail("NOT_RELEASED","level_cap_closed",()->confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,branch,before.revision(),position,null));
            System.setProperty(FLAG,"true");
            fail("CONFLICT","stale_revision",()->confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,branch,before.revision()+1,position,null));
            fail("NOT_READY","quest_required",()->confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,branch,before.revision(),position,null));
            if(detailed) {
                fail("FORBIDDEN","profession_mismatch",()->confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,"archer.bow",before.revision(),position,null));
                fail("UNKNOWN_ID","unknown_id",()->confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,"unknown.branch",before.revision(),position,null));
                fail("STALE_SESSION","inactive_character",()->confirm(repo,account,generation+1,character,AdvancementStage.BRANCH_30,branch,before.revision(),position,null));
                UUID foreign=new CharacterRepository().create(account,generation,next(),2,"p5b_"+account.toString().substring(0,8)).character().characterId();
                check(repo.grantExperience(foreign,1_000_000).level()==20,"Development flag opened normal XP cap");
                fail("STALE_SESSION","inactive_character",()->confirm(repo,account,generation,foreign,AdvancementStage.BRANCH_30,branch,before.revision(),position,null));
                var forged=envelope(generation,character,AdvancementStage.BRANCH_30,branch,before.revision());
                var body=forged.payload();body.addProperty("grantedPoints",500);
                var bad=new RequestEnvelope(forged.requestId(),generation,character,"confirm_advancement",body);
                fail("FIELD_LIMIT","invalid_payload",()->confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,branch,before.revision(),position,bad));
                check(value("SELECT COUNT(*) FROM dalmaru.operation_requests WHERE account_id=? AND operation='confirm_advancement'",account)==0,"Rejected request committed journal");
                migrationConstraints(character);
                runGate(repo,account,generation,character,branch,before.revision(),position);
            }
            interact(story,account,generation,character,AdvancementStage.BRANCH_30,npc,position);
            if(detailed) {
                var reportBody=new JsonObject();reportBody.addProperty("chapterId",AdvancementStage.BRANCH_30.questId());reportBody.addProperty("npcId",npc);
                var report=new RequestEnvelope(UUID.randomUUID(),generation,character,"story_report",reportBody);
                fail("NOT_READY","advancement_confirmation_required",()->story.operate(account,generation,next(),character,report,position));
                var far=new StoryQuestRepository.Position("minecraft:the_nether",0,0,0);
                fail("NOT_READY","npc_required",()->confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,branch,before.revision(),far,null));
                try(var c=connection();var q=c.createStatement()) {
                    q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.character_id='"+character+"'::uuid AND NEW.unspent_skill_points>OLD.unspent_skill_points THEN RAISE EXCEPTION 'P5 rollback injection'; END IF; RETURN NEW; END $$");
                    q.execute("CREATE TRIGGER "+trap+" BEFORE UPDATE ON dalmaru.character_profiles FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
                }
                try{confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,branch,before.revision(),position,null);throw new AssertionError("Missing injected failure");}
                catch(SQLException error){check("P0001".equals(error.getSQLState()),"Unexpected SQL failure");}
                finally{drop(trap);}
                check(repo.profile(character).equals(before),"Quest XP or skill points leaked through rollback");
                check(!repo.growthSnapshot(account,generation,character).advance30(),"Advancement leaked through rollback");
                check(value("SELECT COUNT(*) FROM dalmaru.character_quest_progress WHERE character_id=? AND quest_id='story.branch_30' AND state='REWARDED'",character)==0,"Story completion leaked through rollback");
            }
            var request=envelope(generation,character,AdvancementStage.BRANCH_30,branch,before.revision());
            CharacterRepository.GrowthResult first;
            if(detailed) {
                var pool=Executors.newFixedThreadPool(2);long a=next(),b=next();
                try {
                    var x=pool.submit(()->repo.confirmAdvancementJournaled(account,generation,a,character,AdvancementStage.BRANCH_30,branch,before.revision(),position,request));
                    var y=pool.submit(()->repo.confirmAdvancementJournaled(account,generation,b,character,AdvancementStage.BRANCH_30,branch,before.revision(),position,request));
                    var left=x.get();var right=y.get();check(left.replayed()!=right.replayed(),"Concurrent UUID applied twice");
                    check(left.originalResult().equals(right.originalResult()),"Concurrent replay changed original result");first=left;
                } finally {pool.shutdownNow();}
            } else first=confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,branch,before.revision(),position,request);
            check(repo.profile(character).unspentSkillPoints()==before.unspentSkillPoints()+5,"30 bonus not exactly five");
            check(repo.profile(character).experience()==before.experience()+1,"Final story XP not atomic/exactly once");
            check(repo.profile(character).freeResetAvailable()==before.freeResetAvailable(),"General free reset was consumed");
            var snapshot=repo.growthSnapshot(account,generation,character);
            check(snapshot.advance30()&&snapshot.branch().equals(branch)&&snapshot.rogueResetAvailable()==(profession==CharacterProfession.ROGUE),"30 snapshot incorrect");
            check(snapshot.profile().revision()>before.revision(),"Profile revision not advanced");
            var immutable=first.originalResult();immutable.addProperty("skillPointsGranted",999);
            check(first.originalResult().get("skillPointsGranted").getAsInt()==5,"Stored result exposed mutable reference");
            long after30=repo.profile(character).revision();
            fail("DUPLICATE","already_advanced",()->confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,branch,after30,position,null));
            System.clearProperty(FLAG);
            check(confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,branch,before.revision(),position,request).replayed(),"Replay rechecked cap/revision");
            System.setProperty(FLAG,"true");
            if(detailed) {
                var changed=envelope(generation,character,AdvancementStage.BRANCH_30,"warrior.shield",before.revision());
                var conflict=new RequestEnvelope(request.requestId(),generation,character,changed.operation(),changed.payload());
                reject(()->confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,"warrior.shield",before.revision(),position,conflict));
                long rev=repo.profile(character).revision();
                fail("NOT_READY","level_required",()->confirm(repo,account,generation,character,AdvancementStage.MASTER_60,branch,rev,position,null));
            }
            for(var stage:List.of(AdvancementStage.ADVANCE_50,AdvancementStage.MASTER_60)) {
                raise(repo,character,stage.requiredLevel()-1);long low=repo.profile(character).revision();
                fail("NOT_READY","level_required",()->confirm(repo,account,generation,character,stage,branch,low,position,null));
                raise(repo,character,stage.requiredLevel());
                interact(story,account,generation,character,stage,npc,position);
                long revision=repo.profile(character).revision();
                if(detailed&&stage==AdvancementStage.ADVANCE_50)fail("FORBIDDEN","branch_mismatch",()->confirm(repo,account,generation,character,stage,"warrior.shield",revision,position,null));
                var result=confirm(repo,account,generation,character,stage,branch,revision,position,null);
                check(result.originalResult().get("skillPointsGranted").getAsInt()==10,"Later stage bonus incorrect");
            }
            check(repo.profile(character).unspentSkillPoints()==81,"Expected total 6+50+5+10+10 = 81");
            var finalState=repo.growthSnapshot(account,generation,character);
            check(finalState.advance30()&&finalState.advance50()&&finalState.advance60(),"Later advancement state missing");
            var replay=confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,branch,before.revision(),position,request);
            check(replay.replayed()&&replay.originalResult().equals(first.originalResult()),"Later progression changed original receipt");
            check(repo.profile(character).unspentSkillPoints()==81,"Old replay changed current points");
            if(detailed) {
                long oldExperience=repo.profile(character).experience();
                var capped=repo.grantExperience(character,1_000_000);
                check(capped.level()==60&&capped.experience()==oldExperience+1_000_000,"S02 modified release/test XP rules");
            }
        } finally {drop(trap);EnhancementReplayCheck.cleanup(account);}
    }
    private static StoryCatalog catalog(String npc)throws Exception {
        var root=JsonParser.parseString(Files.readString(Path.of("src/main/resources/content/story.json"))).getAsJsonObject();
        var pilot=new JsonArray();for(var chapter:root.getAsJsonArray("chapters"))if(AdvancementStage.forQuest(chapter.getAsJsonObject().get("id").getAsString())==null)pilot.add(chapter);root.add("chapters",pilot);
        for(var stage:AdvancementStage.values()) {
            var row=new JsonObject();row.addProperty("id",stage.questId());row.addProperty("title","[fixture] advancement");row.addProperty("questXP",1);row.addProperty("combatXP",0);row.addProperty("targetLevel",stage.requiredLevel());
            var npcs=new JsonArray();npcs.add(npc);row.add("npcs",npcs);row.addProperty("combatScope","FIELD");var proofs=new JsonArray();proofs.add("PROFESSION");row.add("proofs",proofs);
            row.addProperty("supplyGrant","");row.addProperty("dialogue","[fixture]");row.addProperty("rewardOwner","STORY");root.getAsJsonArray("chapters").add(row);
        }
        var result=StoryCatalog.parse(root.toString());
        var invalid=root.deepCopy();invalid.getAsJsonArray("chapters").get(invalid.getAsJsonArray("chapters").size()-1).getAsJsonObject().addProperty("targetLevel",59);
        reject(()->StoryCatalog.parse(invalid.toString()));return result;
    }
    private static void seedEarlierStories(UUID character,StoryCatalog catalog)throws SQLException {
        try(var c=connection();var q=c.prepareStatement("INSERT INTO dalmaru.character_quest_progress(character_id,quest_id,content_version,state) VALUES(?,?,?,'REWARDED') ON CONFLICT(character_id,quest_id,content_version) DO UPDATE SET state='REWARDED'")) {
            for(var chapter:catalog.chapters())if(AdvancementStage.forQuest(chapter.id())==null){q.setObject(1,character);q.setString(2,chapter.id());q.setString(3,catalog.version());q.executeUpdate();}
        }
    }
    private static void raise(CharacterRepository repo,UUID character,int target)throws SQLException {
        int level=repo.profile(character).level();long needed=0;for(int n=level;n<target;n++)needed=Math.addExact(needed,CharacterRepository.experienceNeededForNextLevel(n));
        try(var c=connection()){c.setAutoCommit(false);try(var q=c.prepareStatement("UPDATE dalmaru.character_profiles SET experience=? WHERE character_id=?")){q.setLong(1,needed);q.setObject(2,character);q.executeUpdate();}CharacterRepository.recalculateLevelForRelease(c,character);c.commit();}
        check(repo.profile(character).level()==target,"Fixture level not calculated by release formula");
    }
    private static void interact(StoryQuestRepository story,UUID account,long generation,UUID character,AdvancementStage stage,String npc,StoryQuestRepository.Position position)throws SQLException {
        var body=new JsonObject();body.addProperty("chapterId",stage.questId());body.addProperty("npcId",npc);
        story.operate(account,generation,next(),character,new RequestEnvelope(UUID.randomUUID(),generation,character,"story_interact",body),position);
    }
    private static RequestEnvelope envelope(long generation,UUID character,AdvancementStage stage,String branch,long revision) {
        var body=new JsonObject();body.addProperty("stage",stage.name());body.addProperty("branch",branch);body.addProperty("expectedRevision",revision);
        return new RequestEnvelope(UUID.randomUUID(),generation,character,"confirm_advancement",body);
    }
    private static CharacterRepository.GrowthResult confirm(CharacterRepository repo,UUID account,long generation,UUID character,AdvancementStage stage,String branch,long revision,StoryQuestRepository.Position position,RequestEnvelope request)throws SQLException {
        return repo.confirmAdvancementJournaled(account,generation,next(),character,stage,branch,revision,position,request==null?envelope(generation,character,stage,branch,revision):request);
    }
    private static void migrationConstraints(UUID character)throws Exception {
        try(var c=connection();var q=c.prepareStatement("INSERT INTO dalmaru.character_advancements(character_id,branch,advanced_50_at) VALUES(?,'warrior.twohand',CURRENT_TIMESTAMP)")) {
            q.setObject(1,character);try{q.executeUpdate();throw new AssertionError("V043 permitted stage skipping");}catch(SQLException error){check("23514".equals(error.getSQLState()),"Unexpected constraint failure");}
        }
    }
    private static void runGate(CharacterRepository repo,UUID account,long generation,UUID character,String branch,long revision,StoryQuestRepository.Position position)throws Exception {
        UUID run=UUID.randomUUID();
        try {
            try(var c=connection()) {
                try(var q=c.prepareStatement("INSERT INTO dalmaru.run_instances(run_id,content_id,difficulty,week_id,party_size,scaling_tier,state) VALUES(?,'run.bongsudae','FIRST_STEP',CURRENT_DATE,1,1,'ACTIVE')")){q.setObject(1,run);q.executeUpdate();}
                try(var q=c.prepareStatement("INSERT INTO dalmaru.run_participants(run_id,character_id) VALUES(?,?)")){q.setObject(1,run);q.setObject(2,character);q.executeUpdate();}
            }
            fail("FORBIDDEN","in_run",()->confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,branch,revision,position,null));
            sql("UPDATE dalmaru.run_instances SET state='PAUSED' WHERE run_id=?",run);
            fail("FORBIDDEN","in_run",()->confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,branch,revision,position,null));
        } finally {sql("DELETE FROM dalmaru.run_instances WHERE run_id=?",run);}
    }
    private static void drop(String trap)throws SQLException {try(var c=connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.character_profiles");q.execute("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}}
    private static void fail(String status,String reason,Action action)throws Exception {try{action.run();throw new AssertionError("Expected "+status+"/"+reason);}catch(CharacterRepository.GrowthException error){check(error.status().equals(status)&&error.reason().equals(reason),"Unexpected growth rejection: "+error.status()+"/"+error.reason());}}
    private static void reject(Action action)throws Exception {try{action.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException expected){}}
    private static synchronized long next(){return ++correlation;}
    private static Connection connection()throws SQLException{return EnhancementReplayCheck.connection();}
    private static void sql(String sql,UUID character)throws SQLException{try(var c=connection();var q=c.prepareStatement(sql)){q.setObject(1,character);q.executeUpdate();}}
    private static long value(String sql,UUID id)throws SQLException{try(var c=connection();var q=c.prepareStatement(sql)){q.setObject(1,id);try(var r=q.executeQuery()){if(!r.next())throw new AssertionError("Missing count");return r.getLong(1);}}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    @FunctionalInterface private interface Action{void run()throws Exception;}
}
