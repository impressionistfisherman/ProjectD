package kr.dalmaru;

import com.google.gson.*;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.equipment.*;
import kr.dalmaru.request.RequestEnvelope;

public final class EnhancementTailCheck {
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static final class WorstRoll extends SecureRandom { @Override public int nextInt(int bound){return bound-1;} }
    public static void main(String[] args)throws Exception {
        FixtureDatabase.databaseName();int[] copper={8,12,18,26,36,54,81,122,183,275,413,620,930,1395,2093},iron={0,1,1,2,2,3,5,8,12,18,27,41,62,93,140};
        int[] legacy={100,95,90,85,80};
        for(int i=0;i<15;i++){
            var cost=EnhancementRules.cost(i+1);check(cost.copper()==copper[i]&&cost.ironstone()==iron[i],"Fixed/temporary cost changed");
            check(EnhancementRules.chance(i,0)==(i<5?legacy[i]:100),"Legacy/tail probability changed");
        }
        var display=ContentCatalog.current().display("enhancement.pilot");check(display.name().contains("임시")||display.description().contains("임시"),"Temporary price label missing");
        UUID account=UUID.randomUUID();String trap="tail_"+account.toString().replace("-","");
        try {
            long generation=new AccountRepository().openSession(account).connectionGeneration();var characters=new CharacterRepository();
            UUID character=characters.create(account,generation,1,1,"tail_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            sql("UPDATE dalmaru.character_profiles SET level=20 WHERE character_id=?",character);sql("INSERT INTO dalmaru.character_professions(character_id,base_job) VALUES(?,'WARRIOR')",character);
            UUID equipment=UUID.randomUUID();sql("INSERT INTO dalmaru.character_equipment(equipment_id,character_id,item_id,required_level,profession,location,binding,bound,enhancement,consecutive_failures) VALUES(?,?,'pilot.weapon.two_handed_sword.20',20,'WARRIOR','INVENTORY','FREE',false,5,9)",equipment,character);
            var constructor=EquipmentRepository.class.getDeclaredConstructor(SecureRandom.class);constructor.setAccessible(true);var repo=constructor.newInstance(new WorstRoll());
            int totalCopper=0,totalIron=0;for(int i=5;i<15;i++){totalCopper+=copper[i];totalIron+=iron[i];}
            sql("UPDATE dalmaru.character_wallet SET copper=53 WHERE character_id=?",character);sql("INSERT INTO dalmaru.character_materials(character_id,material_id,quantity,binding) VALUES(?,'material.ironstone',?,'ACCOUNT') ON CONFLICT(character_id,material_id) DO UPDATE SET quantity=EXCLUDED.quantity,binding=EXCLUDED.binding",character,totalIron);
            var sixth=request(UUID.randomUUID(),generation,character,equipment);reject(()->repo.enhance(account,generation,10,equipment,sixth));
            check(value("SELECT enhancement FROM dalmaru.character_equipment WHERE equipment_id=?",equipment)==5&&value("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='material.ironstone'",character)==totalIron,"Insufficient wallet spent material");
            sql("UPDATE dalmaru.character_wallet SET copper=? WHERE character_id=?",totalCopper,character);sql("UPDATE dalmaru.character_materials SET quantity=2 WHERE character_id=? AND material_id='material.ironstone'",character);
            reject(()->repo.enhance(account,generation,10,equipment,sixth));check(value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==totalCopper,"Insufficient material spent wallet");
            sql("UPDATE dalmaru.character_materials SET quantity=? WHERE character_id=? AND material_id='material.ironstone'",totalIron,character);
            var first=repo.enhance(account,generation,10,equipment,sixth);check(first.success()&&first.resultEnhancement()==6&&first.resultFailures()==0&&first.equipment().binding()==EquipmentBinding.ACCOUNT,"Tail6 failed or lost binding");
            sql("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.character_id='"+character+"'::uuid AND NEW.operation='enhance_item' AND NEW.state='pending_recovery' THEN RAISE EXCEPTION 'fixture tail confirm'; END IF; RETURN NEW; END $$");sql("CREATE TRIGGER "+trap+" BEFORE UPDATE ON dalmaru.operation_requests FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            var seventh=request(UUID.randomUUID(),generation,character,equipment);reject(()->repo.enhance(account,generation,11,equipment,seventh));
            check(value("SELECT enhancement FROM dalmaru.character_equipment WHERE equipment_id=?",equipment)==6&&value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==totalCopper-54,"Confirm failure leaked state/payment");drop(trap);
            var pool=Executors.newFixedThreadPool(2);try{
                var start=new CountDownLatch(1);List<Future<EnhancementResult>> results=new ArrayList<>();
                for(int i=0;i<2;i++)results.add(pool.submit(()->{start.await();return repo.enhance(account,generation,11,equipment,seventh);}));
                start.countDown();for(var result:results)check(result.get(30,TimeUnit.SECONDS).resultEnhancement()==7,"Concurrent UUID changed original outcome");
            }finally{pool.shutdownNow();}
            for(int target=8;target<=15;target++){
                var result=repo.enhance(account,generation,100+target,equipment,request(UUID.randomUUID(),generation,character,equipment));
                check(result.success()&&result.resultEnhancement()==target&&result.resultFailures()==0,"Worst roll failed guaranteed tail");
            }
            var replay=repo.enhance(account,generation,999,equipment,sixth);check(replay.replayed()&&replay.resultEnhancement()==6&&replay.equipment().enhancement()==15,"Tail replay replaced saved outcome");
            check(value("SELECT copper FROM dalmaru.character_wallet WHERE character_id=?",character)==0&&value("SELECT quantity FROM dalmaru.character_materials WHERE character_id=? AND material_id='material.ironstone'",character)==0,"Tail costs charged incorrectly");
            check(value("SELECT count(*) FROM dalmaru.character_enhancement_requests WHERE equipment_id=?",equipment)==10&&value("SELECT count(*) FROM dalmaru.character_currency_ledger WHERE character_id=? AND reason LIKE 'enhancement:%'",character)==10,"Tail journal/ledger duplicated");
            check(value("SELECT sum((result->>'copperSpent')::bigint) FROM dalmaru.operation_requests WHERE character_id=? AND operation='enhance_item'",character)==totalCopper,"Tail actual spending audit wrong");
            reject(()->repo.enhance(account,generation,1000,equipment,request(UUID.randomUUID(),generation,character,equipment)));
            check(value("SELECT count(*) FROM dalmaru.character_currency_ledger WHERE character_id=? AND reason LIKE 'enhancement:%'",character)==10,"Maximum tier rejection wrote ledger");
            System.out.println("Enhancement tail fixed1-5/temporary6-15/guaranteed worst roll/insufficient funds+material/UUID concurrency/rollback/replay/max15 PASS");
        }finally{drop(trap);sql("DELETE FROM dalmaru.operation_requests WHERE account_id=?",account);EnhancementReplayCheck.cleanup(account);}
    }
    private static RequestEnvelope request(UUID id,long generation,UUID character,UUID equipment){var body=new JsonObject();body.addProperty("equipmentId",equipment.toString());return new RequestEnvelope(id,generation,character,"enhance_item",body);}
    private static void sql(String text,Object... args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.executeUpdate();}}
    private static long value(String text,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){q.setObject(1,id);try(var row=q.executeQuery()){row.next();return row.getLong(1);}}}
    private static void drop(String trap)throws SQLException{sql("DROP TRIGGER IF EXISTS "+trap+" ON dalmaru.operation_requests");sql("DROP FUNCTION IF EXISTS dalmaru."+trap+"()");}
    private static void reject(Operation operation)throws Exception{try{operation.run();}catch(IllegalArgumentException|SQLException expected){return;}throw new AssertionError("Invalid enhancement accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
