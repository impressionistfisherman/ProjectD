package kr.dalmaru;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.gson.*;
import kr.dalmaru.account.*;
import kr.dalmaru.content.*;
import kr.dalmaru.quest.StoryQuestRepository;
import kr.dalmaru.request.RequestEnvelope;

public final class StoryQuestCurveCheck {
    public static void main(String[] args)throws Exception {
        check("oscar_p2_fixture".equals(FixtureDatabase.databaseName()),"Explicit P2 fixture required");
        definitions();StoryClientCheck.run();
        UUID account=UUID.randomUUID(),run=UUID.randomUUID();String trap="story_"+account.toString().replace("-","");
        var characters=new CharacterRepository();var investigation=new AtomicBoolean(false);
        var repo=new StoryQuestRepository((c,id)->{check(!c.getAutoCommit(),"Investigation evidence escaped report transaction");return investigation.get();});
        try {
            long generation=new AccountRepository().openSession(account).connectionGeneration();UUID character=characters.create(account,generation,1,1,"story_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            for(var village:VillageCatalog.current())if(!village.profession().isEmpty()){
                repo.visit(account,generation,character,position(village));
                check(value("SELECT count(*) FROM dalmaru.character_quest_progress WHERE character_id=? AND quest_id=? AND content_version='pilot-2' AND state='COMPLETED'",character,CharacterRepository.professionGateFlags(CharacterProfession.valueOf(village.profession())).getFirst())==1,"Village profession key casing mismatch");
            }
            long total=0;RequestEnvelope last=null;
            for(int index=0;index<StoryCatalog.current().chapters().size();index++) {
                var chapter=StoryCatalog.current().chapters().get(index);String npc=chapter.npcs().getFirst();var village=forNpc(npc);var here=position(village);
                check(repo.status(account,generation,character).chapter().id().equals(chapter.id()),"Story order changed");
                RequestEnvelope early=envelope(generation,character,"story_report",chapter.id(),npc);
                reject(()->operate(repo,account,generation,character,early,here));
                RequestEnvelope outside=envelope(generation,character,"story_interact",chapter.id(),npc);
                reject(()->operate(repo,account,generation,character,outside,new StoryQuestRepository.Position("test:wrong",0,0,0)));
                RequestEnvelope interact=envelope(generation,character,"story_interact",chapter.id(),npc);
                var interaction=operate(repo,account,generation,character,interact,here);check(!interaction.replayed(),"New interaction replayed");
                check(operate(repo,account,generation,character,interact,new StoryQuestRepository.Position("test:wrong",0,0,0)).replayed(),"Confirmed interaction could not recover after moving");
                if(chapter.combatScope()==StoryCatalog.CombatScope.FIRST_STEP_CLEAR) {
                    check(!repo.status(account,generation,character).ready(),"Missing real clear accepted");
                    try(var c=EnhancementReplayCheck.connection()){c.setAutoCommit(false);try{
                        execute(c,"INSERT INTO dalmaru.run_instances(run_id,content_id,difficulty,week_id,party_size,scaling_tier,state) VALUES(?,'beacon_tower','FIRST_STEP',CURRENT_DATE,1,1,'COMPLETED')",run);
                        execute(c,"INSERT INTO dalmaru.run_reward_grants(run_id,character_id,copper,ironstone,beacon_token) VALUES(?,?,0,0,0)",run,character);
                        CharacterRepository.grantExperience(c,character,chapter.combatXP());c.commit();
                    }catch(Exception error){c.rollback();throw error;}}
                } else if(chapter.combatXP()>0) {
                    UUID killed=UUID.randomUUID();repo.combat(account,generation,character,killed,chapter.combatXP(),here,here);
                    long xp=experience(characters.profile(character));
                    check(repo.combat(account,generation,character,killed,chapter.combatXP(),here,here)==null&&experience(characters.profile(character))==xp,"Combat UUID duplicated XP or progress");
                }
                if(chapter.proofs().contains(StoryCatalog.Proof.PROFESSION_TRIAL)) {
                    check(value("SELECT count(*) FROM dalmaru.character_quest_progress WHERE character_id=? AND quest_id='profession.warrior.trial' AND content_version='pilot-2' AND state='COMPLETED'",character)==1,"Profession trial proof missing");
                    characters.selectProfession(account,generation,20,character,CharacterProfession.WARRIOR);
                }
                if(chapter.rewardOwner()==StoryCatalog.RewardOwner.S07) {
                    check(!repo.status(account,generation,character).ready(),"Investigation evidence not required");
                    try(var c=EnhancementReplayCheck.connection()){c.setAutoCommit(false);CharacterRepository.grantExperience(c,character,600);StarterSupply.grant(c,character,StarterSupply.INVESTIGATION);c.commit();}investigation.set(true);
                    check(!new StoryQuestRepository().status(account,generation,character).ready(),"Unmerged S07 default should remain closed");
                }
                check(repo.status(account,generation,character).ready(),"Server evidence did not unlock chapter "+chapter.id());
                RequestEnvelope report=envelope(generation,character,"story_report",chapter.id(),npc);
                RequestEnvelope attemptedReport=report;
                if(chapter.supplyGrant().equals(StarterSupply.PROFESSION)) {
                    long before=experience(characters.profile(character));
                    sql("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.character_id='"+character+"'::uuid AND NEW.grant_key='profession' THEN RAISE EXCEPTION 'story kit rollback'; END IF; RETURN NEW; END $$");
                    sql("CREATE TRIGGER "+trap+" BEFORE INSERT ON dalmaru.character_supply_grants FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
                    fail(()->operate(repo,account,generation,character,attemptedReport,here));
                    check(experience(characters.profile(character))==before&&value("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=?",character)==1&&value("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?",report.requestId())==0,"Profession kit failure escaped story transaction");drop(trap);
                }
                if(chapter.rewardOwner()==StoryCatalog.RewardOwner.S07) {
                    long before=experience(characters.profile(character));
                    sql("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.request_id='"+report.requestId()+"'::uuid AND NEW.state='pending_recovery' THEN RAISE EXCEPTION 'story journal rollback'; END IF; RETURN NEW; END $$");
                    sql("CREATE TRIGGER "+trap+" BEFORE UPDATE ON dalmaru.operation_requests FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
                    fail(()->operate(repo,account,generation,character,attemptedReport,here));
                    check(experience(characters.profile(character))==before&&value("SELECT count(*) FROM dalmaru.character_quest_progress WHERE character_id=? AND quest_id=? AND state='ACTIVE'",character,chapter.id())==1,"Journal failure lost investigation chapter");drop(trap);
                }
                StoryQuestRepository.Result outcome;
                if(index==2)outcome=concurrent(repo,account,generation,character,chapter,npc,here);
                else outcome=operate(repo,account,generation,character,report,here);
                if(index==2)report=new RequestEnvelope(outcome.receipt().requestId(),generation,character,"story_report",report.payload());
                long paid=outcome.original().get("questXP").getAsLong();check(paid==(chapter.rewardOwner()==StoryCatalog.RewardOwner.S07?0:chapter.questXP()),"Investigation double paid or ordinary quest XP missing");
                total+=chapter.questXP()+chapter.combatXP();var profile=characters.profile(character);
                check(experience(profile)==total&&profile.level()==chapter.targetLevel(),"Growth curve mismatch at "+chapter.id()+" expectedXP="+total+" actual="+experience(profile));
                check(operate(repo,account,generation,character,report,here).replayed()&&experience(characters.profile(character))==total,"Report replay duplicated rewards");last=report;
            }
            check(total==7920&&repo.status(account,generation,character).chapter()==null,"Story did not finish at 7920 XP");
            var profile=characters.profile(character);check(profile.level()==20&&profile.experience()==35&&profile.unspentSkillPoints()==16,"Level20/overflow/skill points mismatch");
            check(value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==60&&value("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='supply.recovery'",character)==8,"Investigation supplies duplicated or missing");
            check(value("SELECT count(*) FROM dalmaru.character_supply_grants WHERE character_id=?",character)==3&&value("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=? AND required_level=15",character)==6,"Profession/investigation kit ownership mismatch");
            long newer=new AccountRepository().openSession(account).connectionGeneration();
            var finalEnvelope=last;reject(()->operate(repo,account,generation,character,finalEnvelope,position(VillageCatalog.current().getFirst())));
            var rebound=new RequestEnvelope(last.requestId(),newer,character,last.operation(),last.payload());check(operate(repo,account,newer,character,rebound,position(VillageCatalog.current().getFirst())).replayed()&&experience(characters.profile(character))==7920,"Reconnect duplicated chapter reward");
        }finally{drop(trap);sql("DELETE FROM dalmaru.run_instances WHERE run_id=?",run);EnhancementReplayCheck.cleanup(account);}
        trialBoundary();
        System.out.println("PASS story 11 chapters/7920 XP/level20+35 retained/16 skill points; configured NPC/position/order/profession keys; kill+report UUID replay/reconnect/concurrency; atomic kit+journal rollback; S07-owned600XP/supplies exactly once; actual client state/layout/codecs");
    }
    private static StoryQuestRepository.Result concurrent(StoryQuestRepository repo,UUID account,long generation,UUID character,StoryCatalog.Chapter chapter,String npc,StoryQuestRepository.Position here)throws Exception {
        var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);var futures=new ArrayList<Future<StoryQuestRepository.Result>>();
        try {for(int i=0;i<2;i++){var envelope=envelope(generation,character,"story_report",chapter.id(),npc);futures.add(executor.submit(()->{start.await();try{return operate(repo,account,generation,character,envelope,here);}catch(IllegalArgumentException expected){return null;}}));}start.countDown();StoryQuestRepository.Result result=null;int wins=0;for(var future:futures){var item=future.get(5,TimeUnit.SECONDS);if(item!=null){wins++;result=item;}}check(wins==1,"Concurrent reports rewarded twice");return result;}finally{executor.shutdownNow();}
    }
    private static void trialBoundary()throws Exception {
        UUID account=UUID.randomUUID();var repo=new StoryQuestRepository();var characters=new CharacterRepository();
        try {
            long generation=new AccountRepository().openSession(account).connectionGeneration();UUID character=characters.create(account,generation,1,1,"trial_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            for(var chapter:StoryCatalog.current().chapters().subList(0,5))sql("INSERT INTO dalmaru.character_quest_progress(character_id,quest_id,content_version,state) VALUES(?,?,'pilot-2','REWARDED')",character,chapter.id());
            var warrior=position(VillageCatalog.get("cheolwi"));var hub=position(VillageCatalog.get("taepyeong"));
            repo.combat(account,generation,character,UUID.randomUUID(),200,hub,hub);check(repo.status(account,generation,character).combatXP()==0,"Hub combat passed profession trial");
            repo.combat(account,generation,character,UUID.randomUUID(),200,warrior,hub);check(repo.status(account,generation,character).combatXP()==0,"Target outside profession village counted");
            repo.combat(account,generation,character,UUID.randomUUID(),199,warrior,warrior);check(value("SELECT count(*) FROM dalmaru.character_quest_progress WHERE character_id=? AND quest_id='profession.warrior.trial' AND state='COMPLETED'",character)==0,"Trial completed below 200");
            repo.combat(account,generation,character,UUID.randomUUID(),1,warrior,warrior);check(value("SELECT count(*) FROM dalmaru.character_quest_progress WHERE character_id=? AND quest_id='profession.warrior.trial' AND state='COMPLETED'",character)==1,"Trial did not complete at 200");
        }finally{EnhancementReplayCheck.cleanup(account);}
    }
    private static void definitions()throws Exception {
        var chapters=StoryCatalog.current().chapters();check(chapters.size()==11&&StoryCatalog.current().version().equals(CharacterRepository.PROFESSION_GATE_VERSION),"Story/gate contract version mismatch");
        int[] quest={100,220,250,300,360,400,450,600,800,700,400},combat={0,190,220,280,300,200,300,450,500,550,350};
        for(int i=0;i<11;i++)check(chapters.get(i).questXP()==quest[i]&&chapters.get(i).combatXP()==combat[i]&&chapters.get(i).dialogue().isEmpty(),"Approved XP/name-only content changed");
        String json=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/content/story.json"));
        reject(()->StoryCatalog.parse(json.replace("taepyeong.guide","unknown.npc")));
        reject(()->StoryCatalog.parse(json.replace("\"questXP\":100","\"questXP\":-1")));
        reject(()->StoryCatalog.parse(json.replace("\"story.waterway\"","\"story.dawn\"")));
    }
    private static StoryQuestRepository.Result operate(StoryQuestRepository repo,UUID account,long generation,UUID character,RequestEnvelope envelope,StoryQuestRepository.Position position)throws SQLException {long correlation=envelope.requestId().getLeastSignificantBits()&Long.MAX_VALUE;return repo.operate(account,generation,correlation==0?1:correlation,character,envelope,position);}
    private static VillageCatalog.Village forNpc(String npc){return VillageCatalog.current().stream().filter(v->v.npcs().stream().anyMatch(n->n.id().equals(npc))).findFirst().orElseThrow();}
    private static StoryQuestRepository.Position position(VillageCatalog.Village village){var zone=village.zone();return new StoryQuestRepository.Position(zone.dimension(),zone.x(),zone.y(),zone.z());}
    private static RequestEnvelope envelope(long generation,UUID character,String operation,String chapter,String npc){var body=new JsonObject();body.addProperty("chapterId",chapter);body.addProperty("npcId",npc);return new RequestEnvelope(UUID.randomUUID(),generation,character,operation,body);}
    private static long experience(CharacterProfile p){long xp=p.experience();for(int level=1;level<p.level();level++)xp+=CharacterRepository.experienceNeededForNextLevel(level);return xp;}
    private interface Action{void run()throws Exception;}
    private static void reject(Action action)throws Exception{try{action.run();throw new AssertionError("Invalid story request accepted");}catch(IllegalArgumentException expected){}}
    private static void fail(Action action)throws Exception{try{action.run();throw new AssertionError("Injected failure ignored");}catch(SQLException expected){}}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void execute(Connection c,String sql,Object...args)throws SQLException{try(var q=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.execute();}}
    private static void sql(String sql,Object...args)throws SQLException{try(var c=EnhancementReplayCheck.connection()){execute(c,sql,args);}}
    private static long value(String sql,Object...args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);try(var row=q.executeQuery()){check(row.next(),"Missing fixture row");return row.getLong(1);}}}
    private static void drop(String name)throws SQLException{sql("DROP TRIGGER IF EXISTS "+name+" ON dalmaru.character_supply_grants");sql("DROP TRIGGER IF EXISTS "+name+" ON dalmaru.operation_requests");sql("DROP FUNCTION IF EXISTS dalmaru."+name+"()");}
}
