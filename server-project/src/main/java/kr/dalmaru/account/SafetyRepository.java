package kr.dalmaru.account;

import java.sql.*;
import java.util.UUID;

public final class SafetyRepository {
    private final String url="jdbc:postgresql://"+System.getenv().getOrDefault("DALMARU_DB_HOST","localhost")+":"+System.getenv().getOrDefault("DALMARU_DB_PORT","15432")+"/"+System.getenv().getOrDefault("DALMARU_DB_NAME","ProjectD")+"?connectTimeout=1&socketTimeout=2&options=-c%20statement_timeout%3D250ms%20-c%20lock_timeout%3D200ms";
    private Connection open()throws SQLException{String password=System.getenv("DALMARU_DB_PASSWORD");if(password==null||password.isBlank())throw new SQLException("DALMARU_DB_PASSWORD not configured.");return DriverManager.getConnection(url,System.getenv().getOrDefault("DALMARU_DB_USER","postgres"),password);}
    public SafeCheckpoint load(UUID character)throws SQLException{
        try(var c=open();var q=c.prepareStatement("SELECT point_id,dimension,x,y,z FROM dalmaru.character_safe_points WHERE character_id=?")){
            q.setObject(1,character);try(var row=q.executeQuery()){return row.next()?new SafeCheckpoint(row.getString(1),row.getString(2),row.getDouble(3),row.getDouble(4),row.getDouble(5)):null;}
        }
    }
    public void activate(UUID account,long generation,UUID character,SafeCheckpoint point)throws SQLException{
        try(var c=open()){c.setAutoCommit(false);try{
            try(var owner=c.prepareStatement("SELECT 1 FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? AND active_character_id=? FOR UPDATE")){owner.setObject(1,account);owner.setLong(2,generation);owner.setObject(3,character);try(var row=owner.executeQuery()){if(!row.next())throw new IllegalArgumentException("Stale safe point owner.");}}
            try(var q=c.prepareStatement("INSERT INTO dalmaru.character_safe_points(character_id,point_id,dimension,x,y,z) VALUES(?,?,?,?,?,?) ON CONFLICT(character_id) DO UPDATE SET point_id=EXCLUDED.point_id,dimension=EXCLUDED.dimension,x=EXCLUDED.x,y=EXCLUDED.y,z=EXCLUDED.z,activated_at=CURRENT_TIMESTAMP")){
                q.setObject(1,character);q.setString(2,point.pointId());q.setString(3,point.dimension());q.setDouble(4,point.x());q.setDouble(5,point.y());q.setDouble(6,point.z());q.executeUpdate();
            }c.commit();
        }catch(Exception error){c.rollback();throw error;}}
    }
}
