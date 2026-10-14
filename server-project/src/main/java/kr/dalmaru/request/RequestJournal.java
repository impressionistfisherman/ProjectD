package kr.dalmaru.request;

import com.google.gson.*;
import java.sql.*;
import java.util.*;

public final class RequestJournal {
    private RequestJournal(){}
    public static boolean definitelyRejected(Throwable error){
        Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();
        return cause instanceof IllegalArgumentException||(cause instanceof SQLException sql&&"23505".equals(sql.getSQLState()));
    }
    public record Receipt(UUID requestId,UUID eventId,String state){}
    public record Stored(Receipt receipt,long legacyRequestId,JsonObject result){
        public Stored{result=result==null?null:result.deepCopy();}
        @Override public JsonObject result(){return result==null?null:result.deepCopy();}
    }
    // Account -> request -> domain locks use the same order for every writer.
    public static Stored begin(Connection c,UUID account,long legacyRequest,RequestEnvelope envelope)throws SQLException{
        if(c.getAutoCommit()||legacyRequest<1)throw new IllegalArgumentException("Journal requires a domain transaction");
        if(!envelope.activeCharacterId().equals(activeCharacter(c,account,envelope.connectionGeneration())))throw new IllegalArgumentException("Envelope character is not active");
        String hash=envelope.bodyHash();
        try(var query=c.prepareStatement("SELECT account_id,connection_generation,character_id,operation,legacy_request_id,payload_hash,event_id,state,result::text FROM dalmaru.operation_requests WHERE request_id=? FOR UPDATE")){
            query.setObject(1,envelope.requestId());try(var row=query.executeQuery()){
                if(row.next()){
                    if(!account.equals(row.getObject(1,UUID.class))||envelope.connectionGeneration()!=row.getLong(2)||!envelope.activeCharacterId().equals(row.getObject(3,UUID.class))||!envelope.operation().equals(row.getString(4))||!hash.equals(row.getString(6)))throw new IllegalArgumentException("Request ID was reused with another owner or body");
                    String result=row.getString(9);return new Stored(new Receipt(envelope.requestId(),row.getObject(7,UUID.class),row.getString(8)),row.getLong(5),result==null?null:JsonParser.parseString(result).getAsJsonObject());
                }
            }
        }
        UUID event=UUID.randomUUID();
        try(var insert=c.prepareStatement("INSERT INTO dalmaru.operation_requests(request_id,account_id,connection_generation,character_id,operation,legacy_request_id,payload_hash,event_id,state) VALUES(?,?,?,?,?,?,?,?,'accepted')")){
            insert.setObject(1,envelope.requestId());insert.setObject(2,account);insert.setLong(3,envelope.connectionGeneration());insert.setObject(4,envelope.activeCharacterId());insert.setString(5,envelope.operation());insert.setLong(6,legacyRequest);insert.setString(7,hash);insert.setObject(8,event);insert.executeUpdate();
        }
        return new Stored(new Receipt(envelope.requestId(),event,"accepted"),legacyRequest,null);
    }
    public static Receipt confirm(Connection c,Stored stored,JsonObject result)throws SQLException{
        if(c.getAutoCommit())throw new IllegalArgumentException("Confirmation requires a domain transaction");
        if(stored.result()!=null){if(!RequestEnvelope.canonicalJson(stored.result()).equals(RequestEnvelope.canonicalJson(result)))throw new IllegalArgumentException("Confirmed result cannot change");return stored.receipt();}
        try(var update=c.prepareStatement("UPDATE dalmaru.operation_requests SET result=?::jsonb,state='pending_recovery' WHERE request_id=? AND state='accepted'")){
            update.setString(1,result.toString());update.setObject(2,stored.receipt().requestId());if(update.executeUpdate()!=1)throw new SQLException("Request confirmation lost its reservation");
        }
        return new Receipt(stored.receipt().requestId(),stored.receipt().eventId(),"pending_recovery");
    }
    public static void applied(Connection c,UUID account,long generation,UUID request)throws SQLException{
        if(c.getAutoCommit())throw new IllegalArgumentException("Acknowledgment requires a transaction");
        UUID character=activeCharacter(c,account,generation);
        try(var update=c.prepareStatement("UPDATE dalmaru.operation_requests SET state='completed',applied_at=COALESCE(applied_at,CURRENT_TIMESTAMP) WHERE request_id=? AND account_id=? AND character_id=? AND state IN('pending_recovery','completed')")){
            update.setObject(1,request);update.setObject(2,account);update.setObject(3,character);if(update.executeUpdate()!=1)throw new IllegalArgumentException("Application receipt is not owned by active character");
        }
    }
    private static UUID activeCharacter(Connection c,UUID account,long generation)throws SQLException{
        try(var lock=c.prepareStatement("SELECT active_character_id FROM dalmaru.accounts WHERE account_id=? AND connection_generation=? FOR UPDATE")){
            lock.setObject(1,account);lock.setLong(2,generation);try(var row=lock.executeQuery()){if(!row.next()||row.getObject(1)==null)throw new IllegalArgumentException("Stale request session");return row.getObject(1,UUID.class);}
        }
    }
}
