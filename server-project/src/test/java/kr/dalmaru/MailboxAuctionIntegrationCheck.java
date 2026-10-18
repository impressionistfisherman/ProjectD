package kr.dalmaru;

import com.google.gson.JsonObject;
import java.sql.*;
import java.util.*;
import kr.dalmaru.account.*;
import kr.dalmaru.mail.MailboxClaim;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.run.*;

/** V038 event rows must pass through the unchanged S13 receiver independently. */
public final class MailboxAuctionIntegrationCheck {
    public static void main(String[] args) throws Exception {
        FixtureDatabase.databaseName(); FixtureMigrations.apply(); UUID account=UUID.randomUUID(),run=null;
        try {
            var session=new AccountRepository().openSession(account);long generation=session.connectionGeneration();
            UUID character=new CharacterRepository().create(account,generation,1,1,"mi_"+account.toString().substring(0,8)).character().characterId();
            new CharacterRepository().selectActive(account,generation,2,character);
            sql("UPDATE dalmaru.character_profiles SET level=15 WHERE character_id=?",character);
            run=new RunRepository().enter(account,generation,10,RunDifficulty.FIRST_STEP,List.of(character)).runId();
            UUID first=UUID.randomUUID(),second=UUID.randomUUID(),remaining=UUID.randomUUID();
            for(var entry:Map.of(first,2,second,4,remaining,3).entrySet())
                sql("INSERT INTO dalmaru.character_mailbox(event_id,character_id,source_run_id,item_id,quantity) VALUES(?,?,?,'currency.copper',?)",entry.getKey(),character,run,entry.getValue());
            sql("UPDATE dalmaru.character_wallet SET copper=? WHERE character_id=?",Integer.MAX_VALUE-6,character);
            var receiver=new MailboxClaim();
            var paid=claim(receiver,account,generation,20,character,first);
            check(wallet(character)==Integer.MAX_VALUE-4 && claimed(first) && !claimed(second) && !claimed(remaining),"First row claimed another event");
            check(claim(receiver,account,generation,20,character,first).replayed() && wallet(character)==Integer.MAX_VALUE-4,"First receipt charged twice");
            claim(receiver,account,generation,21,character,second);
            check(wallet(character)==Integer.MAX_VALUE && claimed(second) && !claimed(remaining),"Second row was blocked or claimed the remainder");
            try { claim(receiver,account,generation,22,character,remaining); throw new AssertionError("Wallet cap accepted remaining row"); }
            catch(IllegalArgumentException expected){check(MailboxClaim.WALLET_LIMIT.equals(expected.getMessage()),"Unexpected cap failure");}
            check(!claimed(remaining) && wallet(character)==Integer.MAX_VALUE
                    && number("SELECT COALESCE(sum(copper_delta),0) FROM dalmaru.character_currency_ledger WHERE source_run_id=? AND reason='mail.claim'",run)==6
                    && number("SELECT count(*) FROM dalmaru.character_currency_ledger WHERE source_run_id=? AND reason='mail.claim'",run)==2,"Cap rejection lost pending mail or changed ledger");
            check(claim(receiver,account,generation,23,character,first).result().equals(paid.result()) && wallet(character)==Integer.MAX_VALUE,"New request re-claimed a paid row");
            sql("UPDATE dalmaru.character_wallet SET copper=? WHERE character_id=?",Integer.MAX_VALUE-3,character);
            claim(receiver,account,generation,22,character,remaining);
            check(wallet(character)==Integer.MAX_VALUE && claimed(remaining)
                    && number("SELECT COALESCE(sum(copper_delta),0) FROM dalmaru.character_currency_ledger WHERE source_run_id=? AND reason='mail.claim'",run)==9,"Pending row could not be claimed after spending");
            check(number("SELECT count(*) FROM dalmaru.character_mailbox WHERE source_run_id=?",run)==3,"Claim removed or merged event rows");
            System.out.println("V038 multiple same-run copper events / S13 per-row claim / cap preserves remainder / replay / later recovery PASS");
        } finally {
            if(run!=null)sql("DELETE FROM dalmaru.run_instances WHERE run_id=?",run);
            sql("DELETE FROM dalmaru.operation_requests WHERE account_id=?",account);EnhancementReplayCheck.cleanup(account);
        }
    }
    private static MailboxClaim.Journaled claim(MailboxClaim receiver,UUID account,long generation,long request,UUID character,UUID event)throws Exception{
        var body=new JsonObject();body.addProperty("eventId",event.toString());
        return receiver.actJournaled(account,generation,request,character,event,RequestEnvelope.legacy(account,generation,character,"claim_mail",request,body));
    }
    private static boolean claimed(UUID event)throws SQLException{return number("SELECT count(*) FROM dalmaru.character_mailbox WHERE event_id=? AND claimed_at IS NOT NULL",event)==1;}
    private static long wallet(UUID character)throws SQLException{return number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character);}
    private static void sql(String text,Object... args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
    private static long number(String text,Object... args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);try(var row=q.executeQuery()){row.next();return row.getLong(1);}}}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
