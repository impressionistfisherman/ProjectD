package kr.dalmaru.skill;

import java.sql.*;
import java.util.*;
import kr.dalmaru.account.CharacterProfession;
import com.google.gson.*;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.request.RequestJournal;

public final class LoadoutRepository {
    private final String url="jdbc:postgresql://"+System.getenv().getOrDefault("DALMARU_DB_HOST","localhost")+":"+System.getenv().getOrDefault("DALMARU_DB_PORT","15432")+"/"+System.getenv().getOrDefault("DALMARU_DB_NAME","ProjectD")+"?connectTimeout=1&socketTimeout=2&options=-c%20statement_timeout%3D250ms%20-c%20lock_timeout%3D200ms";
    private final String username=System.getenv().getOrDefault("DALMARU_DB_USER","postgres"),password=System.getenv("DALMARU_DB_PASSWORD");
    private Connection open()throws SQLException{if(password==null||password.isBlank())throw new SQLException("DALMARU_DB_PASSWORD not configured.");return DriverManager.getConnection(url,username,password);}
    public SkillLoadout load(UUID character)throws SQLException{
        try(var c=open()){c.setAutoCommit(false);try{var result=load(c,character);c.commit();return result;}catch(Exception e){c.rollback();throw e;}}
    }
    public static SkillLoadout load(Connection c,UUID character)throws SQLException{
        try(var check=c.prepareStatement("SELECT revision,slots::text FROM dalmaru.character_skill_loadouts WHERE character_id=? FOR UPDATE")){
            check.setObject(1,character);try(var row=check.executeQuery()){if(row.next())return new SkillLoadout(character,row.getLong(1),decode(row.getString(2)));}
        }
        var profession=profession(c,character);var ranks=ranks(c,character);
        var slots=profession==null?List.of("","","","",""):SkillLoadout.defaults(profession,ranks);
        try(var insert=c.prepareStatement("INSERT INTO dalmaru.character_skill_loadouts(character_id,slots) VALUES(?,?::jsonb) ON CONFLICT DO NOTHING")){insert.setObject(1,character);insert.setString(2,encode(slots));insert.executeUpdate();}
        try(var check=c.prepareStatement("SELECT revision,slots::text FROM dalmaru.character_skill_loadouts WHERE character_id=? FOR UPDATE")){
            check.setObject(1,character);try(var row=check.executeQuery()){if(!row.next())throw new SQLException("Loadout missing.");return new SkillLoadout(character,row.getLong(1),decode(row.getString(2)));}
        }
    }
    public record UpdateResult(SkillLoadout current,SkillLoadout original,boolean replayed,RequestJournal.Receipt receipt) {}
    public SkillLoadout update(UUID account,long generation,UUID character,long request,long expected,List<String> slots)throws SQLException {
        return updateJournaled(account,generation,character,request,expected,slots,null).current();
    }
    public UpdateResult updateJournaled(UUID account,long generation,UUID character,long request,long expected,List<String> slots,RequestEnvelope envelope)throws SQLException {
        return updateJournaled(account,generation,character,request,expected,slots,envelope,true);
    }
    public UpdateResult updateJournaled(UUID account,long generation,UUID character,long request,long expected,List<String> slots,RequestEnvelope envelope,boolean editable)throws SQLException {
        if(generation<1||request<1||expected<0||character==null||slots==null||slots.size()!=5||slots.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Invalid loadout request.");
        slots=List.copyOf(slots);
        var body=new JsonObject();body.addProperty("revision",expected);body.add("slots",JsonParser.parseString(encode(slots)));
        if(envelope==null)envelope=RequestEnvelope.legacy(account,generation,character,"update_loadout",request,body);
        if(envelope.connectionGeneration()!=generation||!envelope.activeCharacterId().equals(character)||!envelope.operation().equals("update_loadout")||!envelope.payload().equals(body))throw new IllegalArgumentException("Loadout envelope does not match session/body");
        try(var c=open()){c.setAutoCommit(false);try{
            var stored=RequestJournal.begin(c,account,request,envelope);request=stored.legacyRequestId();
            if(stored.result()!=null){
                var result=stored.result();var original=new SkillLoadout(character,result.get("revision").getAsLong(),decode(result.get("slots").toString()));
                var current=load(c,character);c.commit();return new UpdateResult(current,original,true,stored.receipt());
            }
            try(var replay=c.prepareStatement("SELECT character_id,revision,slots::text FROM dalmaru.skill_loadout_requests WHERE account_id=? AND connection_generation=? AND request_id=?")){
                replay.setObject(1,account);replay.setLong(2,generation);replay.setLong(3,request);
                try(var row=replay.executeQuery()){if(row.next()){
                    if(!character.equals(row.getObject(1,UUID.class))||expected!=row.getLong(2)-1||!slots.equals(decode(row.getString(3))))throw new IllegalArgumentException("Loadout request ID was reused with another body.");
                    var original=new SkillLoadout(character,row.getLong(2),decode(row.getString(3)));
                    var receipt=RequestJournal.confirm(c,stored,outcome(original));var current=load(c,character);c.commit();return new UpdateResult(current,original,true,receipt);
                }}
            }
            if(!editable)throw new IllegalArgumentException("Loadout cannot be changed in the current world state.");
            try(var run=c.prepareStatement("SELECT 1 FROM dalmaru.run_participants p JOIN dalmaru.run_instances r USING(run_id) WHERE p.character_id=? AND r.state IN ('ACTIVE','PAUSED') FOR UPDATE OF r")){
                run.setObject(1,character);try(var row=run.executeQuery()){if(row.next())throw new IllegalArgumentException("Run loadout is locked.");}
            }
            var current=load(c,character);
            if(current.revision()!=expected)throw new IllegalArgumentException("Stale loadout revision.");
            var profession=profession(c,character);if(profession==null)throw new IllegalArgumentException("Profession required.");
            SkillLoadout.validate(profession,ranks(c,character),slots,current.slots());
            long revision=Math.addExact(current.revision(),1);
            try(var update=c.prepareStatement("UPDATE dalmaru.character_skill_loadouts SET slots=?::jsonb,revision=? WHERE character_id=?")){
                update.setString(1,encode(slots));update.setLong(2,revision);update.setObject(3,character);update.executeUpdate();
            }
            try(var replay=c.prepareStatement("INSERT INTO dalmaru.skill_loadout_requests VALUES(?,?,?,?,?,?::jsonb)")){
                replay.setObject(1,account);replay.setLong(2,generation);replay.setLong(3,request);replay.setObject(4,character);replay.setLong(5,revision);replay.setString(6,encode(slots));replay.executeUpdate();
            }
            var original=new SkillLoadout(character,revision,slots);var receipt=RequestJournal.confirm(c,stored,outcome(original));
            c.commit();return new UpdateResult(original,original,false,receipt);
        }catch(Exception error){c.rollback();throw error;}}
    }
    private static JsonObject outcome(SkillLoadout loadout){
        var result=new JsonObject();result.addProperty("revision",loadout.revision());result.add("slots",JsonParser.parseString(encode(loadout.slots())));return result;
    }
    public static String encode(List<String> slots){JsonArray array=new JsonArray();slots.forEach(array::add);return array.toString();}
    public static List<String> decode(String value){var array=JsonParser.parseString(value).getAsJsonArray();var slots=new ArrayList<String>();array.forEach(item->slots.add(item.getAsString()));return List.copyOf(slots);}
    private static CharacterProfession profession(Connection c,UUID character)throws SQLException{
        try(var q=c.prepareStatement("SELECT base_job FROM dalmaru.character_professions WHERE character_id=?")){q.setObject(1,character);try(var row=q.executeQuery()){return row.next()?CharacterProfession.valueOf(row.getString(1)):null;}}
    }
    private static Map<String,Integer> ranks(Connection c,UUID character)throws SQLException{
        try(var q=c.prepareStatement("SELECT skill_id,rank FROM dalmaru.character_skill_ranks WHERE character_id=?")){q.setObject(1,character);try(var rows=q.executeQuery()){Map<String,Integer>ranks=new HashMap<>();while(rows.next())ranks.put(rows.getString(1),rows.getInt(2));return ranks;}}
    }
}
