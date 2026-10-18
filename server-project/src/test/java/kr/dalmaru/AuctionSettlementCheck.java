package kr.dalmaru;

import com.google.gson.JsonObject;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import kr.dalmaru.account.*;
import kr.dalmaru.request.RequestEnvelope;
import kr.dalmaru.run.*;

public final class AuctionSettlementCheck {
    private record User(UUID account, UUID character, long generation) { }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static final List<User> USERS = new ArrayList<>();
    private static final List<UUID> ACCOUNTS = new ArrayList<>();
    private static final List<UUID> RUNS = new ArrayList<>();
    private static final AtomicLong REQUEST = new AtomicLong(100);
    private static final AuctionRepository AUCTIONS = new AuctionRepository();
    private static final RunRepository REPOSITORY = new RunRepository();
    private static final String TRAP = "auction_" + UUID.randomUUID().toString().replace("-", "");

    public static void main(String[] args) throws Exception {
        FixtureDatabase.databaseName();
        FixtureMigrations.apply();
        try {
            for (int i = 0; i < 4; i++) {
                UUID account = UUID.randomUUID(); ACCOUNTS.add(account); var session = new AccountRepository().openSession(account);
                UUID character = new CharacterRepository().create(account, session.connectionGeneration(), 1, 1, "au_" + account.toString().substring(0, 8)).character().characterId();
                USERS.add(new User(account, character, session.connectionGeneration()));
                new CharacterRepository().selectActive(account, session.connectionGeneration(), 2, character);
                sql("UPDATE dalmaru.character_profiles SET level=15 WHERE character_id=?", character);
            }
            User a = USERS.get(0), b = USERS.get(1), c = USERS.get(2), outsider = USERS.get(3);
            var terms = AuctionCatalog.current();
            check(terms.displayName().contains("임시") && terms.binding().equals("ACCOUNT") && terms.saleCopper() == 40
                    && terms.initialSeconds() == 60 && terms.extensionSeconds() == 10 && terms.maximumSeconds() == 120
                    && terms.minimumBid(41) == 44 && terms.minimumBid(Integer.MAX_VALUE) > Integer.MAX_VALUE, "Temporary terms/minimum overflow wrong");
            check(number("SELECT count(*) FROM information_schema.columns WHERE table_schema='dalmaru' AND table_name='character_mailbox' AND column_name='quantity' AND data_type='integer'") == 1, "Mailbox quantity not widened");

            UUID noAction = run(List.of());
            check(number("SELECT count(*) FROM dalmaru.run_auctions WHERE run_id=?", noAction) == 0, "No-action clear spawned a special item");
            UUID solo = run(List.of(a));
            var direct = AUCTIONS.forRun(a.account(), a.generation(), solo);
            check(direct.state().equals("DIRECT") && mail(solo, a, terms.itemId()) == 1 && mail(solo, a, "equipment_box.level_15") == 1, "Solo special replaced first box");
            UUID repeat = run(List.of(a));
            check(mail(repeat, a, terms.itemId()) == 1 && mail(repeat, a, "equipment_box.level_15") == 0, "Special was incorrectly first-clear only");

            UUID tradeRun = run(List.of(a, b, c)); UUID auction = AUCTIONS.forRun(a.account(), a.generation(), tradeRun).auctionId();
            check(snapshot(a, auction).endsAt().equals(snapshot(a, auction).startedAt().plusSeconds(60)), "Initial deadline wrong");
            check(snapshot(a, auction).maximumEndAt().equals(snapshot(a, auction).startedAt().plusSeconds(120))
                    && AUCTIONS.settle(auction).state().equals("OPEN"), "Maximum deadline or premature settlement wrong");
            reject(() -> AUCTIONS.snapshot(outsider.account(), outsider.generation(), auction));
            reject(() -> bid(outsider, auction, 40)); reject(() -> bid(a, auction, 39)); reject(() -> bid(a, auction, 1001));
            var request = envelope(a, auction, 40, false, UUID.randomUUID()); long firstId = REQUEST.incrementAndGet();
            var first = AUCTIONS.bidJournaled(a.account(), a.generation(), firstId, a.character(), auction, 40, request);
            check(wallet(a) == 960 && first.result().get("reservedCopper").getAsInt() == 40, "Bid did not reserve funds");
            var replay = AUCTIONS.bidJournaled(a.account(), a.generation(), firstId, a.character(), auction, 40, request);
            check(replay.replayed() && replay.result().equals(first.result()) && wallet(a) == 960, "Bid replay charged twice");
            reject(() -> AUCTIONS.bidJournaled(a.account(), a.generation(), firstId, a.character(), auction, 42, envelope(a, auction, 42, false, request.requestId())));
            reject(() -> AUCTIONS.bidJournaled(a.account(), a.generation()+1, firstId, a.character(), auction, 40, request));
            reject(() -> bid(b, auction, 40)); reject(() -> bid(b, auction, 41));
            bid(b, auction, 42); check(wallet(a) == 1000 && wallet(b) == 958, "Outbid escrow was not returned");
            bid(b, auction, 45); check(wallet(b) == 955, "Own raise charged full bid again");
            var lateReplay = AUCTIONS.bidJournaled(a.account(), a.generation(), firstId, a.character(), auction, 40, request);
            check(lateReplay.result().equals(first.result()) && snapshot(a, auction).highestBid() == 45 && wallet(a) == 1000, "Replay overwrote current auction");

            UUID rollbackId = UUID.randomUUID();
            trap("dalmaru.operation_requests", "NEW.request_id='" + rollbackId + "'::uuid AND NEW.state='pending_recovery'");
            reject(() -> AUCTIONS.bidJournaled(a.account(), a.generation(), REQUEST.incrementAndGet(), a.character(), auction, 48, envelope(a, auction, 48, false, rollbackId)));
            dropTrap("dalmaru.operation_requests");
            check(wallet(a) == 1000 && wallet(b) == 955 && snapshot(a, auction).highestBid() == 45
                    && number("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?", rollbackId) == 0, "Confirmation rollback leaked escrow/result");

            sql("UPDATE dalmaru.run_auctions SET started_at=clock_timestamp()-interval '55 seconds',ends_at=clock_timestamp()+interval '5 seconds',maximum_end_at=clock_timestamp()+interval '65 seconds' WHERE auction_id=?", auction);
            var before = snapshot(a, auction); bid(a, auction, 48);
            check(snapshot(a, auction).endsAt().equals(before.endsAt().plusSeconds(10)), "Last-ten-second bid did not add ten seconds");
            sql("UPDATE dalmaru.run_auctions SET ends_at=clock_timestamp()+interval '5 seconds',maximum_end_at=clock_timestamp()+interval '8 seconds' WHERE auction_id=?", auction);
            var cap = snapshot(a, auction).maximumEndAt(); bid(b, auction, 51);
            check(snapshot(a, auction).endsAt().equals(cap), "Extension exceeded cap");
            var leave = envelope(b, auction, 0, true, UUID.randomUUID()); long leaveId = REQUEST.incrementAndGet();
            AUCTIONS.leaveJournaled(b.account(), b.generation(), leaveId, b.character(), auction, leave);
            check(wallet(b) == 1000 && snapshot(a, auction).highestBid() == 0 && !snapshot(a, auction).eligible().contains(b.character()), "Leave did not refund/revoke");
            check(AUCTIONS.leaveJournaled(b.account(), b.generation(), leaveId, b.character(), auction, leave).replayed(), "Leave replay failed");
            reject(() -> bid(b, auction, 40));
            expire(auction); reject(() -> bid(a, auction, 40));
            REPOSITORY.expireTimedOut();
            check(snapshot(a, auction).state().equals("UNSOLD") && mail(tradeRun, a, "currency.copper") == 20 && mail(tradeRun, c, "currency.copper") == 20
                    && mail(tradeRun, b, "currency.copper") == 0 && mailTotal(tradeRun, terms.itemId()) == 0, "Unsold distribution included leaver or item");
            AUCTIONS.settle(auction); check(mailTotal(tradeRun, "currency.copper") == 40, "Repeated settlement duplicated money");

            UUID unsoldRun = run(List.of(a, b, c)); UUID unsold = AUCTIONS.forRun(a.account(), a.generation(), unsoldRun).auctionId();
            expire(unsold); new AuctionRepository().settleExpired();
            var ordered = new ArrayList<>(List.of(a, b, c)); ordered.sort(Comparator.comparing(user -> user.character().toString()));
            for (int i = 0; i < ordered.size(); i++) check(mail(unsoldRun, ordered.get(i), "currency.copper") == (i == 0 ? 14 : 13), "Unsold remainder was not UUID ascending");

            UUID concurrentRun = run(List.of(a, b, c)); UUID concurrent = AUCTIONS.forRun(a.account(), a.generation(), concurrentRun).auctionId();
            var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1); int accepted = 0;
            try {
                List<Future<Boolean>> results = new ArrayList<>();
                for (User user : List.of(a,b)) results.add(pool.submit(() -> { start.await(); try { bid(user, concurrent, 40); return true; } catch (IllegalArgumentException rejected) { return false; } }));
                start.countDown(); for (var result : results) if (result.get(30, TimeUnit.SECONDS)) accepted++;
            } finally { pool.shutdownNow(); }
            check(accepted == 1 && wallet(a)+wallet(b) == 1960, "Concurrent equal bids did not preserve first winner/conservation");
            UUID winner = snapshot(a, concurrent).highestCharacterId();
            expire(concurrent);
            trap("dalmaru.character_mailbox", "NEW.source_run_id='" + concurrentRun + "'::uuid AND NEW.item_id='currency.copper'");
            reject(() -> AUCTIONS.settle(concurrent)); dropTrap("dalmaru.character_mailbox");
            check(snapshot(a, concurrent).state().equals("OPEN") && mailTotal(concurrentRun, terms.itemId()) == 0, "Failed settlement partially delivered item");
            User offlineWinner = USERS.stream().filter(user -> user.character().equals(winner)).findFirst().orElseThrow();
            sql("UPDATE dalmaru.accounts SET active_character_id=NULL WHERE account_id=?",offlineWinner.account());
            new AuctionRepository().settle(concurrent); new AuctionRepository().settle(concurrent);
            sql("UPDATE dalmaru.accounts SET active_character_id=? WHERE account_id=?",offlineWinner.character(),offlineWinner.account());
            check(snapshot(a, concurrent).state().equals("WON") && mailTotal(concurrentRun, terms.itemId()) == 1 && mailTotal(concurrentRun, "currency.copper") == 40
                    && number("SELECT count(*) FROM dalmaru.character_mailbox WHERE source_run_id=? AND character_id=? AND item_id='currency.copper'", concurrentRun,winner) == 0, "Winner split/restart idempotency wrong");

            UUID parallelRun1=run(List.of(a,b,c)),parallelRun2=run(List.of(a,b,c));
            UUID parallel1=AUCTIONS.forRun(a.account(),a.generation(),parallelRun1).auctionId(),parallel2=AUCTIONS.forRun(a.account(),a.generation(),parallelRun2).auctionId();
            bid(a,parallel1,600); reject(()->bid(a,parallel2,600));
            check(wallet(a)==400 && snapshot(a,parallel2).highestBid()==0,"Parallel auctions overspent shared wallet");
            AUCTIONS.revokeParticipant(parallel1,a.character());bid(a,parallel2,600);AUCTIONS.revokeParticipant(parallel2,a.character());
            check(wallet(a)==1000,"Cross-auction escrow was not reusable after refund");

            UUID largeRun = run(List.of(a,b,c)); UUID large = AUCTIONS.forRun(a.account(), a.generation(), largeRun).auctionId();
            sql("UPDATE dalmaru.character_wallet SET copper=200000 WHERE character_id=?",a.character());
            bid(a, large, 100001); expire(large); AUCTIONS.settle(large);
            check(wallet(a) == 99999 && mailTotal(largeRun, "currency.copper") == 100001 && mailTotal(largeRun, terms.itemId()) == 1
                    && Math.abs(mail(largeRun,b,"currency.copper")-mail(largeRun,c,"currency.copper")) == 1, "Large integer mail/distribution conservation failed");

            // Exercise an amount above all current bid limits without adding a public mail insertion API.
            var send=AuctionRepository.class.getDeclaredMethod("mail",Connection.class,UUID.class,UUID.class,String.class,long.class,UUID.class);send.setAccessible(true);
            try(var connection=EnhancementReplayCheck.connection()) {
                connection.setAutoCommit(false); UUID event=UUID.randomUUID();long amount=2L*Integer.MAX_VALUE+7;
                send.invoke(null,connection,largeRun,a.character(),"currency.copper",amount,event);
                send.invoke(null,connection,largeRun,a.character(),"currency.copper",amount,event);
                try(var query=connection.prepareStatement("SELECT count(*),sum(quantity),max(quantity) FROM dalmaru.character_mailbox WHERE source_run_id=? AND character_id=? AND item_id='currency.copper'")) {
                    query.setObject(1,largeRun);query.setObject(2,a.character());try(var row=query.executeQuery()){row.next();check(row.getInt(1)==3&&row.getLong(2)==amount&&row.getInt(3)==Integer.MAX_VALUE,"Split mail event replay duplicated/truncated money");}
                }
                connection.rollback();
            }

            UUID capRun=run(List.of(a,b,c));UUID capped=AUCTIONS.forRun(a.account(),a.generation(),capRun).auctionId();
            bid(a,capped,40);sql("UPDATE dalmaru.character_wallet SET copper=? WHERE character_id=?",Integer.MAX_VALUE-10,a.character());
            var overtake=envelope(b,capped,42,false,UUID.randomUUID());long overtakeId=REQUEST.incrementAndGet();
            AUCTIONS.bidJournaled(b.account(),b.generation(),overtakeId,b.character(),capped,42,overtake);
            check(wallet(a)==Integer.MAX_VALUE&&mail(capRun,a,"currency.copper")==30,"Partial-cap refund did not mail remainder");
            check(AUCTIONS.bidJournaled(b.account(),b.generation(),overtakeId,b.character(),capped,42,overtake).replayed()&&mail(capRun,a,"currency.copper")==30,"Refund replay generated another event");
            AUCTIONS.revokeParticipant(capped,a.character());
            sql("UPDATE dalmaru.character_wallet SET copper=? WHERE character_id=?",Integer.MAX_VALUE,b.character());
            var capLeave=envelope(b,capped,0,true,UUID.randomUUID());long capLeaveId=REQUEST.incrementAndGet();
            AUCTIONS.leaveJournaled(b.account(),b.generation(),capLeaveId,b.character(),capped,capLeave);
            AUCTIONS.leaveJournaled(b.account(),b.generation(),capLeaveId,b.character(),capped,capLeave);
            check(wallet(b)==Integer.MAX_VALUE&&mail(capRun,b,"currency.copper")==42&&!snapshot(c,capped).eligible().contains(b.character()),"Full-cap leave was rejected or duplicated refund");
            check(number("SELECT COALESCE(sum(copper_delta),0) FROM dalmaru.character_currency_ledger WHERE source_run_id=? AND reason LIKE 'auction.%'",capRun)+mailTotal(capRun,"currency.copper")==0,"Capped refunds do not balance wallet/mail ledger");
            expire(capped);AUCTIONS.settle(capped);check(mail(capRun,c,"currency.copper")==40,"Unsold payout collided with refund events");

            UUID emptyRun = run(List.of(a,b,c)); UUID empty = AUCTIONS.forRun(a.account(), a.generation(), emptyRun).auctionId();
            bid(a, empty, 40); AUCTIONS.revokeParticipant(empty, a.character()); AUCTIONS.revokeParticipant(empty, a.character());
            check(wallet(a) == 1000 && snapshot(a, empty).highestBid() == 0, "Server expulsion did not refund exactly once");
            AUCTIONS.revokeParticipant(empty,b.character()); AUCTIONS.revokeParticipant(empty,c.character());
            check(snapshot(a,empty).state().equals("EMPTY") && mailTotal(emptyRun,terms.itemId()) == 0, "Empty auction generated an item");
            check(number("SELECT COALESCE(sum(copper_delta),0) FROM dalmaru.character_currency_ledger WHERE source_run_id=? AND reason LIKE 'auction.%'",emptyRun) == 0, "Refund ledger does not balance");
            UUID purgeRun=run(List.of(a,b,c));UUID purge=AUCTIONS.forRun(a.account(),a.generation(),purgeRun).auctionId();
            bid(b,purge,40);expire(purge);AUCTIONS.settle(purge);
            sql("DELETE FROM dalmaru.operation_requests WHERE account_id=?",b.account());EnhancementReplayCheck.cleanup(b.account());
            check(snapshot(a,purge).state().equals("WON")&&snapshot(a,purge).highestBid()==40&&snapshot(a,purge).highestCharacterId()==null
                    &&mail(purgeRun,a,"currency.copper")+mail(purgeRun,c,"currency.copper")==40,"Closed auction blocked character purge or erased other recipients' mail");
            AUCTIONS.settle(purge);check(mailTotal(purgeRun,"currency.copper")==40,"Post-purge recovery paid twice");
            System.out.println("Auction eligibility/direct/repeat/minimum/escrow/replay/rollback/concurrency/extension/leave/unsold/winner/large-mail/restart/ledger PASS");
        } finally {
            dropTrap("dalmaru.operation_requests"); dropTrap("dalmaru.character_mailbox");
            for(UUID run:RUNS) sql("DELETE FROM dalmaru.run_instances WHERE run_id=?",run);
            for(UUID account:ACCOUNTS) { sql("DELETE FROM dalmaru.operation_requests WHERE account_id=?",account); EnhancementReplayCheck.cleanup(account); }
        }
    }
    private static UUID run(List<User> eligible) throws Exception {
        User owner = USERS.getFirst();
        for (User user:USERS) {
            sql("UPDATE dalmaru.character_run_weekly_clears SET clear_count=0 WHERE character_id=?",user.character());
            sql("UPDATE dalmaru.character_wallet SET copper=0 WHERE character_id=?",user.character());
        }
        UUID run = REPOSITORY.enter(owner.account(),owner.generation(),REQUEST.incrementAndGet(),RunDifficulty.FIRST_STEP,USERS.stream().map(User::character).toList()).runId(); RUNS.add(run);
        Map<UUID,RunContribution.Action> actions=new HashMap<>(); eligible.forEach(user->actions.put(user.character(),RunContribution.Action.EFFECT));
        REPOSITORY.finish(owner.account(),owner.generation(),REQUEST.incrementAndGet(),run,true,actions);
        for(User user:USERS)sql("UPDATE dalmaru.character_wallet SET copper=1000 WHERE character_id=?",user.character()); return run;
    }
    private static AuctionRepository.Snapshot snapshot(User user, UUID auction)throws Exception {return AUCTIONS.snapshot(user.account(),user.generation(),auction);}
    private static AuctionRepository.Journaled bid(User user, UUID auction, int amount)throws Exception {return AUCTIONS.bidJournaled(user.account(),user.generation(),REQUEST.incrementAndGet(),user.character(),auction,amount,envelope(user,auction,amount,false,UUID.randomUUID()));}
    private static RequestEnvelope envelope(User user, UUID auction, int amount, boolean leave, UUID request) {
        var body=new JsonObject();body.addProperty("auctionId",auction.toString());if(!leave)body.addProperty("copper",amount);
        return new RequestEnvelope(request,user.generation(),user.character(),leave?"leave_auction":"bid_auction",body);
    }
    private static long wallet(User user)throws Exception{return number("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",user.character());}
    private static long mail(UUID run,User user,String item)throws Exception{return number("SELECT COALESCE(sum(quantity),0) FROM dalmaru.character_mailbox WHERE source_run_id=? AND character_id=? AND item_id=?",run,user.character(),item);}
    private static long mailTotal(UUID run,String item)throws Exception{return number("SELECT COALESCE(sum(quantity),0) FROM dalmaru.character_mailbox WHERE source_run_id=? AND item_id=?",run,item);}
    private static void expire(UUID auction)throws Exception{sql("UPDATE dalmaru.run_auctions SET started_at=clock_timestamp()-interval '61 seconds',ends_at=clock_timestamp()-interval '1 second',maximum_end_at=clock_timestamp()+interval '59 seconds' WHERE auction_id=?",auction);}
    private static void trap(String table,String condition)throws Exception {
        sql("CREATE FUNCTION dalmaru."+TRAP+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "+condition+" THEN RAISE EXCEPTION 'fixture failure'; END IF; RETURN NEW; END $$");
        sql("CREATE TRIGGER "+TRAP+" BEFORE INSERT OR UPDATE ON "+table+" FOR EACH ROW EXECUTE FUNCTION dalmaru."+TRAP+"()");
    }
    private static void dropTrap(String table)throws Exception{sql("DROP TRIGGER IF EXISTS "+TRAP+" ON dalmaru.operation_requests");sql("DROP TRIGGER IF EXISTS "+TRAP+" ON dalmaru.character_mailbox");sql("DROP FUNCTION IF EXISTS dalmaru."+TRAP+"()");}
    private static void sql(String text,Object... args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
    private static long number(String text,Object... args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);try(var row=q.executeQuery()){row.next();return row.getLong(1);}}}
    private static void reject(Operation operation)throws Exception{try{operation.run();}catch(IllegalArgumentException|SQLException expected){return;}throw new AssertionError("Invalid auction operation accepted");}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
