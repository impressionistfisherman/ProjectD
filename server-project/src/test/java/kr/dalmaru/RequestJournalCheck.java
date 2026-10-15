package kr.dalmaru;

import com.google.gson.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.equipment.*;
import kr.dalmaru.request.*;

public final class RequestJournalCheck {
    public static void main(String[] args)throws Exception{
        FixtureDatabase.databaseName();
        UUID account=UUID.randomUUID();
        try{
            var accounts=new AccountRepository();var session=accounts.openSession(account);var characters=new CharacterRepository();
            UUID character=characters.create(account,session.connectionGeneration(),1,1,"journal_"+account.toString().substring(0,8)).character().characterId();
            characters.selectActive(account,session.connectionGeneration(),2,character);
            UUID id=UUID.randomUUID();long generation=session.connectionGeneration();
            var body=JsonParser.parseString("{\"z\":1.0,\"a\":[1,2]}").getAsJsonObject();
            var envelope=new RequestEnvelope(id,generation,character,"test_operation",body);
            var reordered=new RequestEnvelope(id,generation,character,"test_operation",JsonParser.parseString("{\"a\":[1,2],\"z\":1}").getAsJsonObject());
            check(envelope.bodyHash().equals(reordered.bodyHash()),"Canonical order/numbers differ");
            body.addProperty("z",9);envelope.payload().addProperty("z",9);check(envelope.bodyHash().equals(reordered.bodyHash()),"Envelope body mutated");
            var changed=new RequestEnvelope(id,generation,character,"test_operation",body);check(!envelope.bodyHash().equals(changed.bodyHash()),"Changed body hash unchanged");
            var nested=new JsonObject();var cursor=nested;for(int i=0;i<18;i++){var child=new JsonObject();cursor.add("x",child);cursor=child;}
            reject(()->new RequestEnvelope(UUID.randomUUID(),generation,character,"test_operation",nested).bodyHash());
            var outcome=new JsonObject();outcome.addProperty("amount",1);
            RequestJournal.Receipt receipt;
            try(var c=EnhancementReplayCheck.connection()){
                c.setAutoCommit(false);var stored=RequestJournal.begin(c,account,77,envelope);check(stored.result()==null&&stored.receipt().state().equals("accepted"),"Initial reservation wrong");
                receipt=RequestJournal.confirm(c,stored,outcome);c.commit();
            }
            check(receipt.state().equals("pending_recovery"),"Confirmation falsely applied");
            try(var c=EnhancementReplayCheck.connection()){
                c.setAutoCommit(false);var replay=RequestJournal.begin(c,account,88,reordered);check(replay.legacyRequestId()==77&&replay.receipt().eventId().equals(receipt.eventId())&&replay.result().equals(outcome),"Replay changed result/event/correlation");
                replay.result().addProperty("amount",2);check(replay.result().equals(outcome),"Stored result mutated");
                var altered=outcome.deepCopy();altered.addProperty("amount",2);reject(()->RequestJournal.confirm(c,replay,altered));c.rollback();
            }
            rejectTransaction(c->RequestJournal.begin(c,account,77,changed));
            rejectTransaction(c->RequestJournal.begin(c,UUID.randomUUID(),77,envelope));
            rejectTransaction(c->RequestJournal.begin(c,account,77,new RequestEnvelope(id,generation,UUID.randomUUID(),"test_operation",envelope.payload())));
            UUID rolled=UUID.randomUUID();
            try(var c=EnhancementReplayCheck.connection()){
                c.setAutoCommit(false);var reserved=RequestJournal.begin(c,account,90,new RequestEnvelope(rolled,generation,character,"test_operation",envelope.payload()));RequestJournal.confirm(c,reserved,outcome);c.rollback();
            }
            check(value("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?",rolled)==0,"Rollback retained journal");
            var repository=new EquipmentRepository();UUID gear=repository.listOrCreate(character).getFirst().equipmentId();
            UUID failed=UUID.randomUUID();var gearBody=new JsonObject();gearBody.addProperty("equipmentId",gear.toString());
            reject(()->repository.enhance(account,generation,91,gear,new RequestEnvelope(failed,generation,character,"enhance_item",gearBody)));
            check(value("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?",failed)==0&&value("SELECT count(*) FROM dalmaru.character_enhancement_requests WHERE account_id=?",account)==0,"Failed domain retained journal/debit");
            execute("INSERT INTO dalmaru.character_wallet(character_id,copper) VALUES(?,1000)",character);
            var explicit=new RequestEnvelope(UUID.randomUUID(),generation,character,"enhance_item",gearBody);
            EnhancementResult enhanced;
            try(var workers=Executors.newFixedThreadPool(2)){
                var start=new CyclicBarrier(2);
                var one=workers.submit(()->{start.await();return repository.enhance(account,generation,92,gear,explicit);});
                var two=workers.submit(()->{start.await();return repository.enhance(account,generation,93,gear,explicit);});
                var a=one.get(10,TimeUnit.SECONDS);var b=two.get(10,TimeUnit.SECONDS);check(a.replayed()!=b.replayed(),"Concurrent UUID requests both ran/replayed");enhanced=a.replayed()?b:a;
            }
            check(enhanced.receipt().requestId().equals(explicit.requestId())&&enhanced.receipt().state().equals("pending_recovery"),"Explicit UUID enhancement receipt missing");
            long wallet=value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character);
            check(wallet==992,"Concurrent UUID retry charged more than once");
            var enhancedReplay=repository.enhance(account,generation,94,gear,explicit);
            check(enhancedReplay.replayed()&&enhancedReplay.receipt().eventId().equals(enhanced.receipt().eventId())&&value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==wallet&&value("SELECT count(*) FROM dalmaru.character_enhancement_requests WHERE account_id=?",account)==1,"UUID replay charged/new correlation reran domain");
            var nextSession=accounts.openSession(account);
            var rebound=new RequestEnvelope(id,nextSession.connectionGeneration(),character,"test_operation",envelope.payload());
            try(var c=EnhancementReplayCheck.connection()){
                c.setAutoCommit(false);var replay=RequestJournal.begin(c,account,99,rebound);check(replay.legacyRequestId()==77&&replay.receipt().eventId().equals(receipt.eventId())&&replay.result().equals(outcome),"Reconnect generation did not return the stored result");c.rollback();
            }
            check(value("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?",id)==1,"Reconnect replay inserted another journal row");
            var reboundGear=new RequestEnvelope(explicit.requestId(),nextSession.connectionGeneration(),character,"enhance_item",gearBody);
            var gearReplay=repository.enhance(account,nextSession.connectionGeneration(),95,gear,reboundGear);
            check(gearReplay.replayed()&&gearReplay.receipt().eventId().equals(enhanced.receipt().eventId())&&value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==wallet,"Reconnect replay charged again");
            rejectTransaction(c->RequestJournal.begin(c,account,77,new RequestEnvelope(id,nextSession.connectionGeneration(),character,"test_operation",changed.payload())));
            rejectTransaction(c->RequestJournal.begin(c,account,77,envelope));
            rejectTransaction(c->RequestJournal.applied(c,account,generation,id));
            try(var c=EnhancementReplayCheck.connection()){c.setAutoCommit(false);RequestJournal.applied(c,account,nextSession.connectionGeneration(),id);c.commit();}
            String applied=text("SELECT applied_at::text FROM dalmaru.operation_requests WHERE request_id=?",id);
            try(var c=EnhancementReplayCheck.connection()){c.setAutoCommit(false);RequestJournal.applied(c,account,nextSession.connectionGeneration(),id);c.commit();}
            check(applied!=null&&applied.equals(text("SELECT applied_at::text FROM dalmaru.operation_requests WHERE request_id=?",id))&&text("SELECT state FROM dalmaru.operation_requests WHERE request_id=?",id).equals("completed"),"Application acknowledgment not idempotent");
            System.out.println("UUID envelope/hash/ownership/generation/immutable replay/atomic rollback/application receipt PASS");
        }finally{EnhancementReplayCheck.cleanup(account);}
    }
    private interface Action{void run()throws Exception;}
    private interface Transaction{void run(Connection c)throws Exception;}
    private static void reject(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid request accepted");}
    private static void rejectTransaction(Transaction action)throws Exception{try(var c=EnhancementReplayCheck.connection()){c.setAutoCommit(false);try{reject(()->action.run(c));}finally{c.rollback();}}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static long value(String sql,UUID id)throws SQLException{return Long.parseLong(text(sql,id));}
    private static void execute(String sql,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var s=c.prepareStatement(sql)){s.setObject(1,id);s.executeUpdate();}}
    private static String text(String sql,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var s=c.prepareStatement(sql)){s.setObject(1,id);try(var row=s.executeQuery()){if(!row.next())throw new AssertionError("Missing fixture row");return row.getString(1);}}}
}
