package kr.dalmaru.skill;

import com.google.gson.JsonObject;
import java.sql.*;
import java.util.*;
import kr.dalmaru.account.*;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.request.*;

public final class SkillRepository {
    private final String url="jdbc:postgresql://"+System.getenv().getOrDefault("DALMARU_DB_HOST","localhost")+":"+System.getenv().getOrDefault("DALMARU_DB_PORT","15432")+"/"+System.getenv().getOrDefault("DALMARU_DB_NAME","ProjectD");
    private final String username=System.getenv().getOrDefault("DALMARU_DB_USER","postgres");
    private final String password=System.getenv("DALMARU_DB_PASSWORD");
    public record LearningResult(int rank,boolean replayed,CharacterProfile profile,Map<String,Integer> currentRanks,RequestJournal.Receipt receipt){
        public LearningResult{currentRanks=Map.copyOf(currentRanks);}
    }
    public int learn(UUID account,long generation,long request,UUID character,CharacterProfession profession,String skill)throws SQLException {
        return learnJournaled(account,generation,request,character,profession,skill,null).rank();
    }
    public LearningResult learnJournaled(UUID account,long generation,long request,UUID character,CharacterProfession profession,String skillId,RequestEnvelope envelope)throws SQLException {
        if(generation<1||request<1||character==null||skillId==null)throw new IllegalArgumentException("Invalid skill learning request.");
        var skill=SkillDefinition.find(profession,skillId);var definition=ContentCatalog.current().skill(skillId);
        var body=new JsonObject();body.addProperty("skillId",skillId);
        if(envelope==null)envelope=RequestEnvelope.legacy(account,generation,character,"learn_skill",request,body);
        if(envelope.connectionGeneration()!=generation||!envelope.activeCharacterId().equals(character)||!envelope.operation().equals("learn_skill")||!envelope.payload().equals(body))throw new IllegalArgumentException("Skill envelope does not match session/body");
        try(var c=connection()){
            c.setAutoCommit(false);
            try {
                var stored=RequestJournal.begin(c,account,request,envelope);request=stored.legacyRequestId();
                if(stored.result()!=null){var result=latest(c,character,stored.result().get("rank").getAsInt(),true,stored.receipt());c.commit();return result;}
                try(var q=c.prepareStatement("SELECT character_id,skill_id,rank FROM dalmaru.character_skill_learning_requests WHERE account_id=? AND connection_generation=? AND request_id=?")){
                    q.setObject(1,account);q.setLong(2,generation);q.setLong(3,request);
                    try(var row=q.executeQuery()){
                        if(row.next()){
                            if(!character.equals(row.getObject(1,UUID.class))||!skill.id().equals(row.getString(2)))throw new IllegalArgumentException("Request ID was reused for another skill");
                            var result=confirm(c,stored,character,skillId,row.getInt(3),true);c.commit();return result;
                        }
                    }
                }
                var profile=CharacterRepository.profileForUpdate(c,character);
                if(!profession.name().equals(profile.profession())||profile.level()<definition.unlockLevel())throw new IllegalArgumentException("Skill profession/level does not match current profile");
                if(profile.unspentSkillPoints()<definition.skillPoints())throw new IllegalArgumentException("Not enough skill points.");
                int rank;
                try(var q=c.prepareStatement("SELECT rank FROM dalmaru.character_skill_ranks WHERE character_id=? AND skill_id=? FOR UPDATE")){
                    q.setObject(1,character);q.setString(2,skill.id());try(var row=q.executeQuery()){rank=row.next()?row.getInt(1):0;}
                }
                if(rank>=definition.maxRank())throw new IllegalArgumentException("Skill is already at maximum rank.");rank++;
                try(var q=c.prepareStatement("UPDATE dalmaru.character_profiles SET unspent_skill_points=unspent_skill_points-? WHERE character_id=?")){q.setInt(1,definition.skillPoints());q.setObject(2,character);q.executeUpdate();}
                try(var q=c.prepareStatement("INSERT INTO dalmaru.character_skill_ranks(character_id,skill_id,rank) VALUES(?,?,?) ON CONFLICT(character_id,skill_id) DO UPDATE SET rank=EXCLUDED.rank")){q.setObject(1,character);q.setString(2,skill.id());q.setInt(3,rank);q.executeUpdate();}
                try(var q=c.prepareStatement("INSERT INTO dalmaru.character_skill_learning_requests(account_id,connection_generation,request_id,character_id,skill_id,rank) VALUES(?,?,?,?,?,?)")){q.setObject(1,account);q.setLong(2,generation);q.setLong(3,request);q.setObject(4,character);q.setString(5,skill.id());q.setInt(6,rank);q.executeUpdate();}
                var result=confirm(c,stored,character,skillId,rank,false);c.commit();return result;
            }catch(Exception error){c.rollback();throw error;}
        }
    }
    private static LearningResult confirm(Connection c,RequestJournal.Stored stored,UUID character,String skill,int rank,boolean replayed)throws SQLException {
        var outcome=new JsonObject();outcome.addProperty("skillId",skill);outcome.addProperty("rank",rank);
        return latest(c,character,rank,replayed,RequestJournal.confirm(c,stored,outcome));
    }
    private static LearningResult latest(Connection c,UUID character,int rank,boolean replayed,RequestJournal.Receipt receipt)throws SQLException {
        var profile=CharacterRepository.profileForUpdate(c,character);
        return new LearningResult(rank,replayed,profile,ranks(c,character),receipt);
    }
    public Map<String,Integer> ranks(UUID character)throws SQLException {try(var c=connection()){return ranks(c,character);}}
    private static Map<String,Integer> ranks(Connection c,UUID character)throws SQLException {
        try(var q=c.prepareStatement("SELECT skill_id,rank FROM dalmaru.character_skill_ranks WHERE character_id=?")){
            q.setObject(1,character);try(var row=q.executeQuery()){var result=new HashMap<String,Integer>();while(row.next())result.put(row.getString(1),row.getInt(2));return result;}
        }
    }
    private Connection connection()throws SQLException {
        if(password==null||password.isBlank())throw new SQLException("DALMARU_DB_PASSWORD is not configured.");return DriverManager.getConnection(url,username,password);
    }
}