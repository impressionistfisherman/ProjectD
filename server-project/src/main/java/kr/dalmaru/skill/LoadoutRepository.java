package kr.dalmaru.skill;

import java.sql.*;
import java.util.*;
import kr.dalmaru.account.CharacterProfession;
import com.google.gson.*;

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
    public SkillLoadout update(UUID account,long generation,UUID character,long request,long expected,List<String> slots)throws SQLException{
        if(generation<1||request<1)throw new IllegalArgumentException("Invalid loadout request.");
        try(var c=open()){c.setAutoCommit(false);try{
            try(var owner=c.prepareStatement("SELECT 1 FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? AND active_character_id=? FOR UPDATE")){
                owner.setObject(1,account);owner.setLong(2,generation);owner.setObject(3,character);try(var row=owner.executeQuery()){if(!row.next())throw new IllegalArgumentException("Stale loadout owner.");}
            }
            try(var run=c.prepareStatement("SELECT 1 FROM dalmaru.run_participants p JOIN dalmaru.run_instances r USING(run_id) WHERE p.character_id=? AND r.state IN ('ACTIVE','PAUSED') FOR UPDATE OF r")){
                run.setObject(1,character);try(var row=run.executeQuery()){if(row.next())throw new IllegalArgumentException("Run loadout is locked.");}
            }
            var current=load(c,character);
            try(var replay=c.prepareStatement("SELECT character_id,revision,slots::text FROM dalmaru.skill_loadout_requests WHERE account_id=? AND connection_generation=? AND request_id=?")){
                replay.setObject(1,account);replay.setLong(2,generation);replay.setLong(3,request);
                try(var row=replay.executeQuery()){if(row.next()){
                    if(!character.equals(row.getObject(1,UUID.class))||current.revision()!=row.getLong(2)||!slots.equals(decode(row.getString(3))))throw new IllegalArgumentException("Stale loadout replay.");
                    c.commit();return current;
                }}
            }
            if(current.revision()!=expected)throw new IllegalArgumentException("Stale loadout revision.");
            var profession=profession(c,character);if(profession==null)throw new IllegalArgumentException("Profession required.");
            SkillLoadout.validate(profession,ranks(c,character),slots);
            long revision=Math.addExact(current.revision(),1);
            try(var update=c.prepareStatement("UPDATE dalmaru.character_skill_loadouts SET slots=?::jsonb,revision=? WHERE character_id=?")){
                update.setString(1,encode(slots));update.setLong(2,revision);update.setObject(3,character);update.executeUpdate();
            }
            try(var replay=c.prepareStatement("INSERT INTO dalmaru.skill_loadout_requests VALUES(?,?,?,?,?,?::jsonb)")){
                replay.setObject(1,account);replay.setLong(2,generation);replay.setLong(3,request);replay.setObject(4,character);replay.setLong(5,revision);replay.setString(6,encode(slots));replay.executeUpdate();
            }
            c.commit();return new SkillLoadout(character,revision,slots);
        }catch(Exception e){c.rollback();throw e;}}
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
