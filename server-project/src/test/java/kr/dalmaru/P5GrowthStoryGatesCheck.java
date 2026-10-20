package kr.dalmaru;

import com.google.gson.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import kr.dalmaru.account.*;
import kr.dalmaru.content.*;
import kr.dalmaru.quest.StoryQuestRepository;
import kr.dalmaru.request.RequestEnvelope;

public final class P5GrowthStoryGatesCheck {
    private static final String FLAG="dalmaru.p5.development";
    private static long correlation=80000;
    public static void main(String[] args)throws Exception {
        FixtureDatabase.databaseName();FixtureMigrations.apply();definitions();
        String previous=System.getProperty(FLAG);
        try {
            run(CharacterProfession.WARRIOR,"warrior.twohand",true);
            run(CharacterProfession.ARCHER,"archer.bow",false);
            run(CharacterProfession.MAGE,"mage.fan",false);
            run(CharacterProfession.ROGUE,"rogue.pirate",false);
        } finally {if(previous==null)System.clearProperty(FLAG);else System.setProperty(FLAG,previous);}
        System.out.println("PASS production story gates 30/50/60: temporary dialogue, four existing villages, cap20/level/prerequisite/position, no raid/party, no direct report reward, rollback/retry preserves confirmed stages and replay, zero new XP");
    }
    private static void definitions()throws Exception {
        var chapters=StoryCatalog.current().chapters();check(chapters.size()==14,"Expected first11 plus three gates");
        for(var stage:AdvancementStage.values()) {
            var chapter=chapters.get(11+stage.ordinal());
            check(chapter.id().equals(stage.questId())&&chapter.targetLevel()==stage.requiredLevel(),"Gate order/level mismatch");
            check(chapter.title().startsWith("[\uC784\uC2DC]")&&chapter.dialogue().startsWith("[\uC784\uC2DC]"),"Temporary prose not tagged");
            check(chapter.questXP()==0&&chapter.combatXP()==0&&chapter.supplyGrant().isEmpty(),"Gate added unapproved rewards/combat");
            check(chapter.proofs().equals(Set.of(StoryCatalog.Proof.PROFESSION))&&chapter.combatScope()==StoryCatalog.CombatScope.FIELD,"Gate requires new world/raid evidence");
            check(chapter.npcs().size()==4&&VillageCatalog.current().stream().filter(v->!v.profession().isEmpty()).allMatch(v->v.npcs().stream().anyMatch(n->chapter.npcs().contains(n.id()))),"Missing existing profession NPC path");
        }
        var root=JsonParser.parseString(Files.readString(Path.of("src/main/resources/content/story.json"))).getAsJsonObject();
        var list=root.getAsJsonArray("chapters");var swap=list.get(12);list.set(12,list.get(13));list.set(13,swap);
        reject(()->StoryCatalog.parse(root.toString()));
    }
    private static void run(CharacterProfession profession,String branch,boolean rollback)throws Exception {
        UUID account=UUID.randomUUID();String trap="p5_story_"+account.toString().replace("-","");
        try {
            System.setProperty(FLAG,"true");var repo=new CharacterRepository();var story=new StoryQuestRepository(kr.dalmaru.quest.QuestStageRepository::reportConfirmed);
            long generation=new AccountRepository().openSession(account).connectionGeneration();
            UUID character=repo.create(account,generation,next(),1,"story5_"+account.toString().substring(0,8)).character().characterId();
            repo.selectActive(account,generation,next(),character);
            raise(repo,character,10);try(var c=connection()){ProfessionGateFixture.complete(c,character,profession);}
            repo.selectProfession(account,generation,next(),character,profession);
            // P2's real progression is covered by verifyStoryQuestCurve; isolate the new production gates here.
            try(var c=connection();var q=c.prepareStatement("INSERT INTO dalmaru.character_quest_progress(character_id,quest_id,content_version,state) VALUES(?,?,?,'REWARDED') ON CONFLICT(character_id,quest_id,content_version) DO UPDATE SET state='REWARDED'")) {
                for(var chapter:StoryCatalog.current().chapters().subList(0,11)){q.setObject(1,character);q.setString(2,chapter.id());q.setString(3,StoryCatalog.current().version());q.executeUpdate();}
            }
            var village=VillageCatalog.current().stream().filter(v->v.profession().equals(profession.name())).findFirst().orElseThrow();String npc=village.npcs().getFirst().id();var here=position(village);
            RequestEnvelope first=null;JsonObject firstResult=null;
            for(var stage:AdvancementStage.values()) {
                var current=story.status(account,generation,character);check(current.chapter().id().equals(stage.questId()),"Progress skipped a gate");
                var wire=current.json();check(wire.get("advancementStage").getAsString().equals(stage.name())&&wire.get("requiredLevel").getAsInt()==stage.requiredLevel()&&wire.get("dialogue").getAsString().startsWith("[\uC784\uC2DC]"),"Wire dropped gate prose/metadata");
                raise(repo,character,stage.requiredLevel()-1);
                check(!story.status(account,generation,character).ready(),"Gate ready below required level");
                fail("NOT_READY","level_required",()->interact(story,account,generation,character,stage,npc,here));
                raise(repo,character,stage.requiredLevel());
                if(stage==AdvancementStage.BRANCH_30) {
                    System.clearProperty(FLAG);var before=repo.profile(character);
                    check(!story.status(account,generation,character).ready(),"Product cap20 did not lock gate");
                    fail("NOT_RELEASED","level_cap_closed",()->interact(story,account,generation,character,stage,npc,here));
                    fail("NOT_READY","advancement_confirmation_required",()->report(story,account,generation,character,stage,npc,here));
                    fail("NOT_RELEASED","level_cap_closed",()->confirm(repo,account,generation,character,stage,branch,here,null));
                    check(repo.profile(character).equals(before),"Closed gate paid XP or points");System.setProperty(FLAG,"true");
                }
                var other=VillageCatalog.current().stream().filter(v->!v.profession().isEmpty()&&!v.profession().equals(profession.name())).findFirst().orElseThrow();
                reject(()->interact(story,account,generation,character,stage,other.npcs().getFirst().id(),position(other)));
                reject(()->interact(story,account,generation,character,stage,npc,new StoryQuestRepository.Position("test:wrong",0,0,0)));
                var before=repo.profile(character);var interaction=interact(story,account,generation,character,stage,npc,here);
                check(interaction.original().get("questXP").getAsInt()==0&&repo.profile(character).equals(before),"Interaction gave rewards");
                check(story.status(account,generation,character).ready(),"Valid solo existing-village interaction did not unlock gate");
                fail("NOT_READY","advancement_confirmation_required",()->report(story,account,generation,character,stage,npc,here));
                check(repo.profile(character).equals(before),"Direct story report bypassed advancement");
                check(value("SELECT COUNT(*) FROM dalmaru.run_participants WHERE character_id=?",character)==0,"Fixture entered a dungeon/raid");
                var request=advancement(generation,character,stage,branch,before.revision());
                if(rollback&&stage==AdvancementStage.ADVANCE_50) {
                    try(var c=connection();var q=c.createStatement()) {
                        q.execute("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.character_id='"+character+"'::uuid AND NEW.unspent_skill_points>OLD.unspent_skill_points THEN RAISE EXCEPTION 'P5 story rollback injection'; END IF; RETURN NEW; END $$");
                        q.execute("CREATE TRIGGER "+trap+" BEFORE UPDATE ON dalmaru.character_profiles FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
                    }
                    try{confirm(repo,account,generation,character,stage,branch,here,request);throw new AssertionError("Missing injected failure");}
                    catch(SQLException error){check("P0001".equals(error.getSQLState()),"Unexpected SQL failure");}finally{drop(trap);}
                    var saved=repo.growthSnapshot(account,generation,character);
                    check(saved.advance30()&&!saved.advance50()&&repo.profile(character).equals(before),"Failed50 removed30 or paid partial reward");
                    check(story.status(account,generation,character).chapter().id().equals(stage.questId())&&story.status(account,generation,character).ready(),"Failed segment lost retry progress");
                }
                var result=confirm(repo,account,generation,character,stage,branch,here,request);
                check(!result.replayed()&&result.originalResult().getAsJsonObject("story").get("questXP").getAsInt()==0,"Gate reward incorrect");
                check(repo.profile(character).experience()==before.experience()&&repo.profile(character).unspentSkillPoints()==before.unspentSkillPoints()+stage.bonus(),"Gate duplicated XP or changed skill bonus");
                if(first==null){first=request;firstResult=result.originalResult();}
            }
            var done=story.status(account,generation,character);check(done.chapter()==null&&done.json().get("finished").getAsBoolean(),"Master completion not retained");
            var beforeReplay=repo.profile(character);var replay=confirm(repo,account,generation,character,AdvancementStage.BRANCH_30,branch,here,first);
            check(replay.replayed()&&replay.originalResult().equals(firstResult)&&repo.profile(character).equals(beforeReplay)&&story.status(account,generation,character).chapter()==null,"Old gate replay regressed confirmed stages");
            check(beforeReplay.unspentSkillPoints()==81,"Skill total81 changed");
        } finally {drop(trap);EnhancementReplayCheck.cleanup(account);}
    }
    private static void raise(CharacterRepository repo,UUID character,int target)throws SQLException {
        long xp=0;for(int level=repo.profile(character).level();level<target;level++)xp=Math.addExact(xp,CharacterRepository.experienceNeededForNextLevel(level));
        try(var c=connection()){c.setAutoCommit(false);try(var q=c.prepareStatement("UPDATE dalmaru.character_profiles SET experience=? WHERE character_id=?")){q.setLong(1,xp);q.setObject(2,character);q.executeUpdate();}CharacterRepository.recalculateLevelForRelease(c,character);c.commit();}
        check(repo.profile(character).level()==target,"Fixture release formula failed");
    }
    private static RequestEnvelope advancement(long generation,UUID character,AdvancementStage stage,String branch,long revision){var body=new JsonObject();body.addProperty("stage",stage.name());body.addProperty("branch",branch);body.addProperty("expectedRevision",revision);return new RequestEnvelope(UUID.randomUUID(),generation,character,"confirm_advancement",body);}
    private static CharacterRepository.GrowthResult confirm(CharacterRepository repo,UUID account,long generation,UUID character,AdvancementStage stage,String branch,StoryQuestRepository.Position position,RequestEnvelope request)throws SQLException {
        if(request==null)request=advancement(generation,character,stage,branch,repo.profile(character).revision());
        return repo.confirmAdvancementJournaled(account,generation,next(),character,stage,branch,request.payload().get("expectedRevision").getAsLong(),position,request);
    }
    private static StoryQuestRepository.Result interact(StoryQuestRepository story,UUID account,long generation,UUID character,AdvancementStage stage,String npc,StoryQuestRepository.Position position)throws SQLException{return operate(story,account,generation,character,"story_interact",stage,npc,position);}
    private static StoryQuestRepository.Result report(StoryQuestRepository story,UUID account,long generation,UUID character,AdvancementStage stage,String npc,StoryQuestRepository.Position position)throws SQLException{return operate(story,account,generation,character,"story_report",stage,npc,position);}
    private static StoryQuestRepository.Result operate(StoryQuestRepository story,UUID account,long generation,UUID character,String operation,AdvancementStage stage,String npc,StoryQuestRepository.Position position)throws SQLException{var body=new JsonObject();body.addProperty("chapterId",stage.questId());body.addProperty("npcId",npc);return story.operate(account,generation,next(),character,new RequestEnvelope(UUID.randomUUID(),generation,character,operation,body),position);}
    private static StoryQuestRepository.Position position(VillageCatalog.Village village){var z=village.zone();return new StoryQuestRepository.Position(z.dimension(),z.x(),z.y(),z.z());}
    private static void drop(String trap)throws SQLException{try(var c=connection();var q=c.createStatement()){q.execute("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.character_profiles");q.execute("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}}
    private static long value(String sql,UUID id)throws SQLException{try(var c=connection();var q=c.prepareStatement(sql)){q.setObject(1,id);try(var row=q.executeQuery()){row.next();return row.getLong(1);}}}
    private static Connection connection()throws SQLException{return EnhancementReplayCheck.connection();}
    private static synchronized long next(){return ++correlation;}
    private static void fail(String status,String reason,Action action)throws Exception{try{action.run();throw new AssertionError("Expected "+status+"/"+reason);}catch(CharacterRepository.GrowthException error){check(error.status().equals(status)&&error.reason().equals(reason),"Unexpected rejection "+error.status()+"/"+error.reason());}}
    private static void reject(Action action)throws Exception{try{action.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException expected){}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    @FunctionalInterface private interface Action{void run()throws Exception;}
}
