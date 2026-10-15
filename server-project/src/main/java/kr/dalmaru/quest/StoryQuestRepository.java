package kr.dalmaru.quest;

import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.*;
import kr.dalmaru.account.*;
import kr.dalmaru.content.*;
import kr.dalmaru.request.*;

public final class StoryQuestRepository {
    @FunctionalInterface public interface InvestigationEvidence {boolean completed(Connection connection,UUID character)throws SQLException;}
    public record Position(String dimension,double x,double y,double z) {
        public Position {if(dimension==null||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Invalid story position");}
        boolean inside(VillageCatalog.Village village){return village.zone().contains(dimension,(int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z));}
    }
    public record Result(RequestJournal.Receipt receipt,JsonObject original,boolean replayed){}
    public record Status(StoryCatalog.Chapter chapter,int combatXP,List<String> missing) {
        public boolean ready(){return chapter!=null&&missing.isEmpty();}
        public JsonObject json(){
            var data=new JsonObject();data.addProperty("finished",chapter==null);data.addProperty("chapterId",chapter==null?"":chapter.id());data.addProperty("title",chapter==null?"1~20 이야기 완료":chapter.title());data.addProperty("combatXP",combatXP);data.addProperty("combatGoal",chapter==null?0:chapter.combatXP());data.addProperty("ready",ready());
            var npcs=new JsonArray();if(chapter!=null)chapter.npcs().forEach(npcs::add);data.add("npcs",npcs);var requirements=new JsonArray();missing.forEach(requirements::add);data.add("missing",requirements);return data;
        }
    }
    private static final StoryCatalog CATALOG=StoryCatalog.current();
    private final InvestigationEvidence investigation;
    // shortcut: S07 supplies the real adapter after its merge; investigation reports stay closed until then.
    public StoryQuestRepository(){this((connection,character)->false);}
    public StoryQuestRepository(InvestigationEvidence investigation){this.investigation=Objects.requireNonNull(investigation);}
    private Connection connection()throws SQLException {
        String password=System.getenv("DALMARU_DB_PASSWORD");if(password==null||password.isBlank())throw new SQLException("DALMARU_DB_PASSWORD not configured");
        return DriverManager.getConnection("jdbc:postgresql://"+System.getenv().getOrDefault("DALMARU_DB_HOST","localhost")+":"+System.getenv().getOrDefault("DALMARU_DB_PORT","15432")+"/"+System.getenv().getOrDefault("DALMARU_DB_NAME","ProjectD")+"?connectTimeout=1&socketTimeout=2&options=-c%20statement_timeout%3D250ms%20-c%20lock_timeout%3D200ms",System.getenv().getOrDefault("DALMARU_DB_USER","postgres"),password);
    }
    private static void owner(Connection c,UUID account,long generation,UUID character)throws SQLException {
        try(var q=c.prepareStatement("SELECT 1 FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? AND active_character_id=? FOR UPDATE")){q.setObject(1,account);q.setLong(2,generation);q.setObject(3,character);try(var row=q.executeQuery()){if(!row.next())throw new IllegalArgumentException("이야기 캐릭터 세션이 바뀌었습니다.");}}
    }
    private static StoryCatalog.Chapter current(Connection c,UUID character)throws SQLException {
        var rewarded=new HashSet<String>();try(var q=c.prepareStatement("SELECT quest_id FROM dalmaru.character_quest_progress WHERE character_id=? AND content_version=? AND state='REWARDED'")){q.setObject(1,character);q.setString(2,CATALOG.version());try(var row=q.executeQuery()){while(row.next())rewarded.add(row.getString(1));}}
        var chapter=CATALOG.chapters().stream().filter(entry->!rewarded.contains(entry.id())).findFirst().orElse(null);
        if(chapter!=null)try(var q=c.prepareStatement("INSERT INTO dalmaru.character_quest_progress(character_id,quest_id,content_version,state) VALUES(?,?,?,'ACTIVE') ON CONFLICT DO NOTHING")){q.setObject(1,character);q.setString(2,chapter.id());q.setString(3,CATALOG.version());q.executeUpdate();}
        return chapter;
    }
    private static boolean completed(Connection c,UUID character,String id)throws SQLException {
        try(var q=c.prepareStatement("SELECT 1 FROM dalmaru.character_quest_progress WHERE character_id=? AND quest_id=? AND content_version=? AND state IN ('COMPLETED','REWARDED')")){q.setObject(1,character);q.setString(2,id);q.setString(3,CATALOG.version());try(var row=q.executeQuery()){return row.next();}}
    }
    private static void proof(Connection c,UUID character,String id,int progress,String state)throws SQLException {
        try(var q=c.prepareStatement("INSERT INTO dalmaru.character_quest_progress(character_id,quest_id,content_version,state,progress_count) VALUES(?,?,?,?,?) ON CONFLICT(character_id,quest_id,content_version) DO UPDATE SET progress_count=GREATEST(dalmaru.character_quest_progress.progress_count,EXCLUDED.progress_count),state=CASE WHEN dalmaru.character_quest_progress.state IN ('COMPLETED','REWARDED') THEN dalmaru.character_quest_progress.state ELSE EXCLUDED.state END,updated_at=CURRENT_TIMESTAMP")){q.setObject(1,character);q.setString(2,id);q.setString(3,CATALOG.version());q.setString(4,state);q.setInt(5,progress);q.executeUpdate();}
    }
    private static String profession(Connection c,UUID character)throws SQLException {
        try(var q=c.prepareStatement("SELECT base_job FROM dalmaru.character_professions WHERE character_id=?")){q.setObject(1,character);try(var row=q.executeQuery()){return row.next()?row.getString(1):"";}}
    }
    private static String professionKey(String job,String suffix){return "profession."+job.toLowerCase(Locale.ROOT)+"."+suffix;}
    private static int progress(Connection c,UUID character,String id)throws SQLException {
        try(var q=c.prepareStatement("SELECT progress_count FROM dalmaru.character_quest_progress WHERE character_id=? AND quest_id=? AND content_version=? FOR UPDATE")){q.setObject(1,character);q.setString(2,id);q.setString(3,CATALOG.version());try(var row=q.executeQuery()){return row.next()?row.getInt(1):0;}}
    }
    private static boolean firstClear(Connection c,UUID character)throws SQLException {
        try(var q=c.prepareStatement("SELECT 1 FROM dalmaru.run_reward_grants g JOIN dalmaru.run_instances r ON r.run_id=g.run_id WHERE g.character_id=? AND r.difficulty='FIRST_STEP' AND r.state='COMPLETED'")){q.setObject(1,character);try(var row=q.executeQuery()){return row.next();}}
    }
    private Status status(Connection c,UUID character,StoryCatalog.Chapter chapter)throws SQLException {
        if(chapter==null)return new Status(null,0,List.of());
        int combat=progress(c,character,chapter.id());boolean clear=firstClear(c,character);
        if(chapter.combatScope()==StoryCatalog.CombatScope.FIRST_STEP_CLEAR&&clear)combat=chapter.combatXP();
        var missing=new ArrayList<String>();if(combat<chapter.combatXP())missing.add("필수 전투 경험치 "+combat+"/"+chapter.combatXP());
        boolean npc=false;for(String id:chapter.npcs())if(completed(c,character,chapter.id()+".npc."+id)){npc=true;break;}if(!npc)missing.add("해당 마을 NPC 상호작용");
        String job=profession(c,character);
        for(var requirement:chapter.proofs())switch(requirement){
            case PROFESSION -> {if(job.isEmpty())missing.add("직업 선택");}
            case PROFESSION_TRIAL -> {if(job.isEmpty()||!completed(c,character,professionKey(job,"trial")))missing.add("선택 직업 마을 안 전직 시험 전투");}
            case PROFESSION_VILLAGE -> {boolean visited=false;for(var village:VillageCatalog.current())if(!village.profession().isEmpty()&&completed(c,character,professionKey(village.profession(),"village_visit"))){visited=true;break;}if(!visited)missing.add("직업 마을 직접 방문");}
            case INVESTIGATION -> {if(!investigation.completed(c,character))missing.add("조사 서버 완료 증거");}
            case FIRST_STEP_CLEAR -> {if(!clear)missing.add("폐봉수대 초행 완료");}
        }
        return new Status(chapter,combat,List.copyOf(missing));
    }
    public Status status(UUID account,long generation,UUID character)throws SQLException {
        try(var c=connection()){c.setAutoCommit(false);try{owner(c,account,generation,character);var result=status(c,character,current(c,character));c.commit();return result;}catch(SQLException|RuntimeException error){c.rollback();throw error;}}
    }
    public void visit(UUID account,long generation,UUID character,Position position)throws SQLException {
        try(var c=connection()){c.setAutoCommit(false);try{owner(c,account,generation,character);visit(c,character,position);c.commit();}catch(SQLException|RuntimeException error){c.rollback();throw error;}}
    }
    private static void visit(Connection c,UUID character,Position position)throws SQLException {
        for(var village:VillageCatalog.current())if(!village.profession().isEmpty()&&position.inside(village))proof(c,character,professionKey(village.profession(),"village_visit"),0,"COMPLETED");
    }
    public Result operate(UUID account,long generation,long correlation,UUID character,RequestEnvelope envelope,Position position)throws SQLException {
        var body=envelope.payload();
        if(!Set.of("story_interact","story_report").contains(envelope.operation())||envelope.connectionGeneration()!=generation||!envelope.activeCharacterId().equals(character)||body.size()!=2||!string(body,"chapterId")||!string(body,"npcId"))throw new IllegalArgumentException("Invalid story operation body");
        try(var c=connection()){c.setAutoCommit(false);try{
            var stored=RequestJournal.begin(c,account,correlation,envelope);
            if(stored.result()!=null){c.commit();return new Result(stored.receipt(),stored.result(),true);}
            var chapter=current(c,character);if(chapter==null||!chapter.id().equals(body.get("chapterId").getAsString()))throw new IllegalArgumentException("현재 이야기 구간을 확인하세요.");
            String npc=body.get("npcId").getAsString();var village=VillageCatalog.current().stream().filter(v->v.npcs().stream().anyMatch(n->n.id().equals(npc))).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown story NPC"));
            if(!chapter.npcs().contains(npc)||!position.inside(village))throw new IllegalArgumentException("해당 이야기 NPC가 있는 마을에서 상호작용하세요.");
            String job=profession(c,character);if(chapter.proofs().contains(StoryCatalog.Proof.PROFESSION)&&!job.isEmpty()&&!job.equals(village.profession()))throw new IllegalArgumentException("선택 직업의 마을 NPC를 찾아가세요.");
            visit(c,character,position);var outcome=body.deepCopy();outcome.addProperty("operation",envelope.operation());
            if(envelope.operation().equals("story_interact")){proof(c,character,chapter.id()+".npc."+npc,0,"COMPLETED");outcome.addProperty("questXP",0);}
            else {
                var state=status(c,character,chapter);if(!state.ready())throw new IllegalArgumentException(String.join(" · ",state.missing()));
                try(var q=c.prepareStatement("UPDATE dalmaru.character_quest_progress SET state='REWARDED',claimed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE character_id=? AND quest_id=? AND content_version=? AND state='ACTIVE'")){q.setObject(1,character);q.setString(2,chapter.id());q.setString(3,CATALOG.version());if(q.executeUpdate()!=1)throw new SQLException("Story report lost its active chapter");}
                int reward=chapter.rewardOwner()==StoryCatalog.RewardOwner.STORY?chapter.questXP():0;
                if(reward>0)CharacterRepository.grantExperience(c,character,reward);if(!chapter.supplyGrant().isEmpty())StarterSupply.grant(c,character,chapter.supplyGrant());outcome.addProperty("questXP",reward);outcome.addProperty("accountedQuestXP",chapter.questXP());current(c,character);
            }
            RequestJournal.confirm(c,stored,outcome);RequestJournal.applied(c,account,generation,envelope.requestId());c.commit();return new Result(new RequestJournal.Receipt(stored.receipt().requestId(),stored.receipt().eventId(),"completed"),outcome,false);
        }catch(SQLException|RuntimeException error){c.rollback();throw error;}}
    }
    private static boolean string(JsonObject body,String key){return body.has(key)&&body.get(key).isJsonPrimitive()&&body.getAsJsonPrimitive(key).isString()&&body.get(key).getAsString().length()<=128;}
    public CharacterProfile combat(UUID account,long generation,UUID character,UUID killedEntity,int amount,Position killer,Position killed)throws SQLException {
        if(amount<1||killedEntity==null||killedEntity.equals(new UUID(0,0)))throw new IllegalArgumentException("Invalid story combat event");
        UUID request=UUID.nameUUIDFromBytes(("story_combat:"+character+":"+killedEntity).getBytes(StandardCharsets.UTF_8));
        var body=new JsonObject();body.addProperty("killedEntity",killedEntity.toString());body.addProperty("amount",amount);
        var envelope=new RequestEnvelope(request,generation,character,"story_combat",body);long correlation=request.getLeastSignificantBits()&Long.MAX_VALUE;
        try(var c=connection()){c.setAutoCommit(false);try{
            var stored=RequestJournal.begin(c,account,correlation==0?1:correlation,envelope);if(stored.result()!=null){c.commit();return null;}
            var profile=CharacterRepository.grantExperience(c,character,amount);var chapter=current(c,character);
            if(chapter!=null&&chapter.combatXP()>0){
                boolean credit=chapter.combatScope()==StoryCatalog.CombatScope.FIELD;
                if(chapter.combatScope()==StoryCatalog.CombatScope.PROFESSION_VILLAGE)for(var village:VillageCatalog.current())if(!village.profession().isEmpty()&&killer.inside(village)&&killed.inside(village)){
                    String key=professionKey(village.profession(),"trial");int trial=(int)Math.min(chapter.combatXP(),(long)progress(c,character,key)+amount);proof(c,character,key,trial,trial>=chapter.combatXP()?"COMPLETED":"ACTIVE");credit=true;
                }
                if(credit)try(var q=c.prepareStatement("UPDATE dalmaru.character_quest_progress SET progress_count=LEAST(?,progress_count::bigint+?)::integer,updated_at=CURRENT_TIMESTAMP WHERE character_id=? AND quest_id=? AND content_version=? AND state='ACTIVE'")){q.setInt(1,chapter.combatXP());q.setInt(2,amount);q.setObject(3,character);q.setString(4,chapter.id());q.setString(5,CATALOG.version());q.executeUpdate();}
            }
            var outcome=body.deepCopy();outcome.addProperty("credited",true);RequestJournal.confirm(c,stored,outcome);RequestJournal.applied(c,account,generation,request);c.commit();return profile;
        }catch(SQLException|RuntimeException error){c.rollback();throw error;}}
    }
}
