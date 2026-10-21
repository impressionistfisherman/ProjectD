package kr.dalmaru.account;

import java.sql.*;
import java.util.*;
import com.google.gson.*;
import kr.dalmaru.request.*;

public final class WaypointRepository {
    public static final int COOLDOWN_SECONDS=15*60;
    public record Return(UUID request,SafeCheckpoint target,boolean pending){}
    private Connection open()throws SQLException {
        String password=System.getenv("DALMARU_DB_PASSWORD");if(password==null||password.isBlank())throw new SQLException("DALMARU_DB_PASSWORD not configured");
        return DriverManager.getConnection("jdbc:postgresql://"+System.getenv().getOrDefault("DALMARU_DB_HOST","localhost")+":"+System.getenv().getOrDefault("DALMARU_DB_PORT","15432")+"/"+System.getenv().getOrDefault("DALMARU_DB_NAME","ProjectD")+"?connectTimeout=1&socketTimeout=2&options=-c%20statement_timeout%3D250ms%20-c%20lock_timeout%3D200ms",System.getenv().getOrDefault("DALMARU_DB_USER","postgres"),password);
    }
    private static void owner(Connection c,UUID account,long generation,UUID character)throws SQLException {
        try(var q=c.prepareStatement("SELECT 1 FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? AND active_character_id=? FOR UPDATE")){
            q.setObject(1,account);q.setLong(2,generation);q.setObject(3,character);try(var row=q.executeQuery()){if(!row.next())throw new IllegalArgumentException("귀환 캐릭터 세션이 바뀌었습니다.");}
        }
    }
    private static TownZone town(String id) {
        if(id==null||id.isBlank()||id.length()>128)throw new IllegalArgumentException("역참 이름을 확인하세요.");
        return kr.dalmaru.content.VillageCatalog.get(id).zone();
    }
    public void unlock(UUID account,long generation,UUID character,String id,String dimension,double x,double y,double z)throws SQLException {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Invalid visit position");
        try(var c=open()){c.setAutoCommit(false);try{
            owner(c,account,generation,character);var zone=town(id);
            if(!zone.contains(dimension,(int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z)))throw new IllegalArgumentException("직접 방문한 역참만 해금됩니다.");
            try(var q=c.prepareStatement("INSERT INTO dalmaru.character_waypoints(character_id,waypoint_id) VALUES(?,?) ON CONFLICT DO NOTHING")){q.setObject(1,character);q.setString(2,id);q.executeUpdate();}c.commit();
        }catch(SQLException|RuntimeException error){c.rollback();throw error;}}
    }
    public List<String> list(UUID account,long generation,UUID character)throws SQLException {
        try(var c=open()){c.setAutoCommit(false);try{owner(c,account,generation,character);var result=new ArrayList<String>();
            var registered=kr.dalmaru.content.VillageCatalog.current().stream().map(kr.dalmaru.content.VillageCatalog.Village::id).collect(java.util.stream.Collectors.toSet());
            try(var q=c.prepareStatement("SELECT waypoint_id FROM dalmaru.character_waypoints WHERE character_id=? ORDER BY waypoint_id")){q.setObject(1,character);try(var row=q.executeQuery()){while(row.next())if(registered.contains(row.getString(1)))result.add(row.getString(1));}}
            c.commit();return List.copyOf(result);
        }catch(SQLException|RuntimeException error){c.rollback();throw error;}}
    }
    public String unlockIsolatedExit(UUID account,long generation,UUID character,String origin,String dimension,double x,double y,double z)throws SQLException {
        // The server caller proves isolation using the world marker and the player's current position.
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Invalid position");
        var source=kr.dalmaru.content.VillageCatalog.get(origin);
        if(!source.zone().contains(dimension,(int)Math.floor(x),(int)Math.floor(y),(int)Math.floor(z)))throw new IllegalArgumentException("Outside isolated village");
        String target=source.routes().getFirst();town(target);
        try(var c=open()){c.setAutoCommit(false);try{
            owner(c,account,generation,character);
            try(var q=c.prepareStatement("INSERT INTO dalmaru.character_waypoints(character_id,waypoint_id) VALUES(?,?) ON CONFLICT DO NOTHING")){q.setObject(1,character);q.setString(2,target);q.executeUpdate();}
            c.commit();return target;
        }catch(SQLException|RuntimeException error){c.rollback();throw error;}}
    }
    private static void available(Connection c,UUID character,String id,boolean isolatedExit)throws SQLException {
        try(var q=c.prepareStatement("SELECT 1 FROM dalmaru.character_waypoints WHERE character_id=? AND waypoint_id=? FOR UPDATE")){q.setObject(1,character);q.setString(2,id);try(var row=q.executeQuery()){if(!row.next())throw new IllegalArgumentException("먼저 직접 방문해 역참을 해금하세요.");}}
        try(var q=c.prepareStatement("SELECT 1 FROM dalmaru.run_participants p JOIN dalmaru.run_instances r ON r.run_id=p.run_id WHERE p.character_id=? AND r.state IN ('ACTIVE','PAUSED') FOR UPDATE OF r")){q.setObject(1,character);try(var row=q.executeQuery()){if(row.next())throw new IllegalArgumentException("던전에서는 일반 귀환을 사용할 수 없습니다.");}}
        if(!isolatedExit)try(var q=c.prepareStatement("SELECT MAX(cooldown_until)>CURRENT_TIMESTAMP FROM dalmaru.character_waypoints WHERE character_id=?")){q.setObject(1,character);try(var row=q.executeQuery()){row.next();if(row.getBoolean(1))throw new IllegalArgumentException("귀환 재사용 대기시간은 캐릭터 전체에 15분입니다.");}}
        try(var q=c.prepareStatement("SELECT 1 FROM dalmaru.operation_requests WHERE character_id=? AND operation='waypoint_return' AND state='pending_recovery'")){q.setObject(1,character);try(var row=q.executeQuery()){if(row.next())throw new IllegalArgumentException("이전 귀환을 복구한 뒤 다시 요청하세요.");}}
    }
    public void check(UUID account,long generation,UUID character,String id)throws SQLException {check(account,generation,character,id,false);}
    public void check(UUID account,long generation,UUID character,String id,boolean isolatedExit)throws SQLException {
        try(var c=open()){c.setAutoCommit(false);try{owner(c,account,generation,character);town(id);available(c,character,id,isolatedExit);c.commit();}catch(SQLException|RuntimeException error){c.rollback();throw error;}}
    }
    public Return prepare(UUID account,long generation,UUID character,UUID request,String id)throws SQLException {return prepare(account,generation,character,request,id,false);}
    public Return prepare(UUID account,long generation,UUID character,UUID request,String id,boolean isolatedExit)throws SQLException {
        var body=new JsonObject();body.addProperty("waypointId",id);var envelope=new RequestEnvelope(request,generation,character,"waypoint_return",body);long correlation=request.getLeastSignificantBits()&Long.MAX_VALUE;
        try(var c=open()){c.setAutoCommit(false);try{
            var stored=RequestJournal.begin(c,account,correlation==0?1:correlation,envelope);
            if(stored.result()!=null){var result=read(request,stored.result(),stored.receipt().state());c.commit();return result;}
            var zone=town(id);available(c,character,id,isolatedExit);
            var result=new JsonObject();result.addProperty("waypointId",id);result.addProperty("dimension",zone.dimension());result.addProperty("x",zone.x()+.5);result.addProperty("y",zone.y());result.addProperty("z",zone.z()+.5);
            try(var q=c.prepareStatement("SELECT cooldown_until FROM dalmaru.character_waypoints WHERE character_id=? AND waypoint_id=?")){q.setObject(1,character);q.setString(2,id);try(var row=q.executeQuery()){row.next();var previous=row.getTimestamp(1);if(previous==null)result.add("previousCooldown",JsonNull.INSTANCE);else result.addProperty("previousCooldown",previous.toInstant().toEpochMilli());}}
            try(var q=c.prepareStatement("UPDATE dalmaru.character_waypoints SET cooldown_until=date_trunc('milliseconds',clock_timestamp())+(? * interval '1 second') WHERE character_id=? AND waypoint_id=? RETURNING cooldown_until")){
                q.setInt(1,COOLDOWN_SECONDS);q.setObject(2,character);q.setString(3,id);try(var row=q.executeQuery()){if(!row.next())throw new SQLException("Waypoint disappeared");result.addProperty("cooldownUntil",row.getTimestamp(1).toInstant().toEpochMilli());}
            }
            var receipt=RequestJournal.confirm(c,stored,result);c.commit();return read(request,result,receipt.state());
        }catch(SQLException|RuntimeException error){c.rollback();throw error;}}
    }
    private static Return read(UUID request,JsonObject result,String state){return new Return(request,new SafeCheckpoint(result.get("waypointId").getAsString(),result.get("dimension").getAsString(),result.get("x").getAsDouble(),result.get("y").getAsDouble(),result.get("z").getAsDouble()),"pending_recovery".equals(state));}
    public Return pending(UUID account,long generation,UUID character)throws SQLException {
        try(var c=open()){c.setAutoCommit(false);try{owner(c,account,generation,character);Return result=null;
            try(var q=c.prepareStatement("SELECT request_id,result::text FROM dalmaru.operation_requests WHERE account_id=? AND character_id=? AND operation='waypoint_return' AND state='pending_recovery' ORDER BY created_at LIMIT 2 FOR UPDATE")){q.setObject(1,account);q.setObject(2,character);try(var row=q.executeQuery()){if(row.next()){result=read(row.getObject(1,UUID.class),JsonParser.parseString(row.getString(2)).getAsJsonObject(),"pending_recovery");if(row.next())throw new SQLException("Multiple pending waypoint returns");}}}
            c.commit();return result;
        }catch(SQLException|RuntimeException error){c.rollback();throw error;}}
    }
    public void finish(UUID account,long generation,UUID character,UUID request,boolean applied)throws SQLException {
        try(var c=open()){c.setAutoCommit(false);try{owner(c,account,generation,character);
            JsonObject result;
            try(var q=c.prepareStatement("SELECT state,result::text FROM dalmaru.operation_requests WHERE request_id=? AND account_id=? AND character_id=? AND operation='waypoint_return' FOR UPDATE")){q.setObject(1,request);q.setObject(2,account);q.setObject(3,character);try(var row=q.executeQuery()){if(!row.next())throw new IllegalArgumentException("귀환 요청 소유자가 다릅니다.");if("completed".equals(row.getString(1))){c.commit();return;}if(!"pending_recovery".equals(row.getString(1)))throw new SQLException("Unconfirmed waypoint return");result=JsonParser.parseString(row.getString(2)).getAsJsonObject();}}
            if(!applied)try(var q=c.prepareStatement("UPDATE dalmaru.character_waypoints SET cooldown_until=? WHERE character_id=? AND waypoint_id=? AND cooldown_until=?")){
                if(result.get("previousCooldown").isJsonNull())q.setNull(1,Types.TIMESTAMP_WITH_TIMEZONE);else q.setTimestamp(1,Timestamp.from(java.time.Instant.ofEpochMilli(result.get("previousCooldown").getAsLong())));
                q.setObject(2,character);q.setString(3,result.get("waypointId").getAsString());q.setTimestamp(4,Timestamp.from(java.time.Instant.ofEpochMilli(result.get("cooldownUntil").getAsLong())));if(q.executeUpdate()!=1)throw new SQLException("Waypoint cooldown reservation changed");
            }
            RequestJournal.applied(c,account,generation,request);c.commit();
        }catch(SQLException|RuntimeException error){c.rollback();throw error;}}
    }
}
