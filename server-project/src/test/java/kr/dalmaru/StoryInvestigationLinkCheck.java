package kr.dalmaru;

import java.sql.*;
import java.util.*;
import com.google.gson.*;
import kr.dalmaru.account.*;
import kr.dalmaru.content.*;
import kr.dalmaru.quest.*;
import kr.dalmaru.request.RequestEnvelope;

public final class StoryInvestigationLinkCheck {
    public static void main(String[] args)throws Exception {
        FixtureDatabase.databaseName();
        UUID account=UUID.randomUUID();var characters=new CharacterRepository();var stages=new QuestStageRepository();var story=new StoryQuestRepository(QuestStageRepository::reportConfirmed);
        try {
            long generation=new AccountRepository().openSession(account).connectionGeneration();UUID character=characters.create(account,generation,1,1,"link_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            try(var c=EnhancementReplayCheck.connection()) {c.setAutoCommit(false);try{
                execute(c,"INSERT INTO dalmaru.character_professions(character_id,base_job) VALUES(?,'WARRIOR')",character);StarterSupply.grant(c,character,StarterSupply.PROFESSION);CharacterRepository.grantExperience(c,character,3570);
                for(var chapter:StoryCatalog.current().chapters().subList(0,7))execute(c,"INSERT INTO dalmaru.character_quest_progress(character_id,quest_id,content_version,state) VALUES(?,?,'pilot-2','REWARDED')",character,chapter.id());c.commit();
            }catch(Exception error){c.rollback();throw error;}}
            var chapter=StoryCatalog.current().chapters().get(7);var zone=VillageCatalog.get("taepyeong").zone();var position=new StoryQuestRepository.Position(zone.dimension(),zone.x(),zone.y(),zone.z());
            var interact=storyEnvelope(generation,character,"story_interact",chapter.id());story.operate(account,generation,10,character,interact,position);story.combat(account,generation,character,UUID.randomUUID(),450,position,position);
            check(!story.status(account,generation,character).ready(),"Story accepted missing S07 report");
            var report=storyEnvelope(generation,character,"story_report",chapter.id());
            try{story.operate(account,generation,11,character,report,position);throw new AssertionError("Story accepted unconfirmed investigation");}catch(IllegalArgumentException expected){}
            var site=JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/content/investigation.json"))).getAsJsonObject();
            var field=place(site,"field",true);var npcReport=place(site,"report",false);var away=new QuestStageRepository.Place("test:wrong_dimension",0,64,0,false);
            long beforeForgery=experience(characters.profile(character));
            for(String stage:List.of("field","evidence","report")){
                var forged=stageEnvelope(generation,character,stage);
                try{stages.actJournaled(account,generation,20+List.of("field","evidence","report").indexOf(stage),character,stage,"confirm",forged,away);throw new AssertionError("Forged confirm accepted: "+stage);}catch(IllegalArgumentException expected){}
                try(var c=EnhancementReplayCheck.connection()){c.setAutoCommit(false);check(!QuestStageRepository.reportConfirmed(c,character),"Forged confirm reached S06 evidence adapter");c.rollback();}
                check(!story.status(account,generation,character).ready(),"Forged confirm made S06 report ready");
                try{story.operate(account,generation,11,character,report,position);throw new AssertionError("Forged confirm unlocked S06 reward");}catch(IllegalArgumentException expected){}
                check(experience(characters.profile(character))==beforeForgery&&value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==20&&value("SELECT count(*) FROM dalmaru.character_supply_grants WHERE character_id=? AND grant_key='investigation'",character)==0&&value("SELECT count(*) FROM dalmaru.quest_stage_progress WHERE character_id=? AND confirmed",character)==0&&value("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?",forged.requestId())==0,"Forged confirm mutated rewards, stage or journal");
            }
            stages.open(character,"field");stages.confirm(character,"field",field);stages.open(character,"evidence");stages.confirm(character,"evidence",away);
            check(!story.status(account,generation,character).ready(),"Evidence alone accepted as final report");
            var body=new JsonObject();body.addProperty("questId",QuestStageRepository.QUEST_ID);body.addProperty("stage","report");body.addProperty("action","confirm");var finalReport=new RequestEnvelope(UUID.randomUUID(),generation,character,"quest_stage",body);
            try{stages.actJournaled(account,generation,12,character,"report","confirm",finalReport,away);throw new AssertionError("Final report accepted outside NPC radius");}catch(IllegalArgumentException expected){}
            long before=experience(characters.profile(character));var first=stages.actJournaled(account,generation,12,character,"report","confirm",finalReport,npcReport);
            check(first.result().get("rewarded").getAsBoolean()&&experience(characters.profile(character))==before+600,"S07 failed to pay exactly 600");
            try(var c=EnhancementReplayCheck.connection()){
                try{QuestStageRepository.reportConfirmed(c,character);throw new AssertionError("Evidence allowed autocommit");}catch(IllegalArgumentException expected){}
                c.setAutoCommit(false);check(QuestStageRepository.reportConfirmed(c,character),"Real S07 evidence missing on caller transaction");c.rollback();
            }
            check(story.status(account,generation,character).ready(),"Real S07 report did not unlock story");
            var result=story.operate(account,generation,11,character,report,position);check(result.original().get("questXP").getAsInt()==0&&experience(characters.profile(character))==4620,"S06 duplicated S07 600 or left a 600 gap");
            check(stages.actJournaled(account,generation,12,character,"report","confirm",finalReport,away).replayed()&&story.operate(account,generation,11,character,report,position).replayed()&&experience(characters.profile(character))==4620,"Cross-system replay duplicated investigation XP");
            check(value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==60&&value("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='supply.recovery'",character)==8&&value("SELECT count(*) FROM dalmaru.character_equipment WHERE character_id=? AND required_level=15",character)==6,"Cross-system replay duplicated investigation supplies");
            String network=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));check(network.contains("StoryQuestRepository(kr.dalmaru.quest.QuestStageRepository::reportConfirmed)"),"Production still uses fake evidence adapter");
        }finally{EnhancementReplayCheck.cleanup(account);}
        System.out.println("PASS actual S06/S07 adapter: forged field/evidence/report confirms blocked from S06 evidence/readiness/rewards; NPC report radius required; valid report unlocks; one600XP/40copper/recovery4/15kit, no double grant or gap, UUID recovery after moving, caller transaction required");
    }
    private static QuestStageRepository.Place place(JsonObject site,String key,boolean combat){var point=site.getAsJsonObject(key);return new QuestStageRepository.Place(site.get("dimension").getAsString(),point.get("x").getAsInt(),point.get("y").getAsInt(),point.get("z").getAsInt(),combat);}
    private static RequestEnvelope stageEnvelope(long generation,UUID character,String stage){var body=new JsonObject();body.addProperty("questId",QuestStageRepository.QUEST_ID);body.addProperty("stage",stage);body.addProperty("action","confirm");return new RequestEnvelope(UUID.randomUUID(),generation,character,"quest_stage",body);}
    private static RequestEnvelope storyEnvelope(long generation,UUID character,String operation,String chapter){var body=new JsonObject();body.addProperty("chapterId",chapter);body.addProperty("npcId","taepyeong.guide");return new RequestEnvelope(UUID.randomUUID(),generation,character,operation,body);}
    private static long experience(CharacterProfile p){long xp=p.experience();for(int level=1;level<p.level();level++)xp+=CharacterRepository.experienceNeededForNextLevel(level);return xp;}
    private static void execute(Connection c,String sql,Object...args)throws SQLException{try(var q=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.execute();}}
    private static long value(String sql,UUID character)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(sql)){q.setObject(1,character);try(var row=q.executeQuery()){check(row.next(),"Missing fixture row");return row.getLong(1);}}}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
