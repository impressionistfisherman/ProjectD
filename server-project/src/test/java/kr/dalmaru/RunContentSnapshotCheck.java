package kr.dalmaru;

import com.google.gson.JsonParser;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import kr.dalmaru.account.*;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.run.*;

/** Database-only reservations; no game server or world entry. */
public final class RunContentSnapshotCheck {
    public static void main(String[] args)throws Exception {
        if(!"oscar_p1_fixture".equals(System.getenv("DALMARU_DB_NAME")))throw new IllegalStateException("Dedicated P1 fixture required");
        UUID account=UUID.randomUUID();var ownedRuns=new ArrayList<UUID>();
        try {
            var session=new AccountRepository().openSession(account);var characters=new CharacterRepository();
            UUID character=characters.create(account,session.connectionGeneration(),1,1,"snapshot_"+account.toString().substring(0,8)).character().characterId();
            characters.selectActive(account,session.connectionGeneration(),2,character);
            execute("UPDATE dalmaru.character_profiles SET level=15 WHERE character_id=?",character);
            var repository=new RunRepository();
            var run=repository.enter(account,session.connectionGeneration(),3,RunDifficulty.FIRST_STEP,List.of(character));ownedRuns.add(run.runId());
            var frozen=repository.frozenContent(run.runId());
            check(frozen.version().equals("pilot-j02-1")&&frozen.items().equals(ContentCatalog.current().items()),"New snapshot differs");
            var replay=repository.enter(account,session.connectionGeneration(),3,RunDifficulty.FIRST_STEP,List.of(character));
            check(replay.replayed()&&replay.runId().equals(run.runId())&&repository.frozenContent(run.runId()).snapshot().equals(frozen.snapshot()),"Replay changed content");
            execute("UPDATE dalmaru.run_instances SET state='PAUSED' WHERE run_id=?",run.runId());
            rejectSql(()->execute("UPDATE dalmaru.run_instances SET content_version='pilot-j02-2' WHERE run_id=?",run.runId()));
            rejectSql(()->execute("UPDATE dalmaru.run_instances SET content_snapshot='{}'::jsonb WHERE run_id=?",run.runId()));
            rejectSql(()->execute("UPDATE dalmaru.run_instances SET content_id='run.changed' WHERE run_id=?",run.runId()));
            UUID legacy=UUID.randomUUID();ownedRuns.add(legacy);insert(legacy,null,null);
            reject(()->repository.frozenContent(legacy));reject(()->repository.frozenContent(UUID.randomUUID()));
            try(var c=EnhancementReplayCheck.connection();var s=c.createStatement()){s.execute(Files.readString(Path.of("database/migrations/V026__run_content_snapshot.sql")));}
            try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement("SELECT content_version,content_snapshot FROM dalmaru.run_instances WHERE run_id=?")){
                q.setObject(1,legacy);try(var row=q.executeQuery()){check(row.next()&&row.getString(1).equals("pilot-1")&&row.getString(2)==null,"Migration stamped legacy content");}
            }
            var next=JsonParser.parseString(frozen.snapshot()).getAsJsonObject();next.addProperty("contentVersion","pilot-j02-2");next.getAsJsonArray("runs").get(0).getAsJsonObject().addProperty("version","pilot-j02-2");
            next.getAsJsonObject("equipment").getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonArray("attack").set(0,new com.google.gson.JsonPrimitive(42));
            UUID archived=UUID.randomUUID();ownedRuns.add(archived);insert(archived,"pilot-j02-2",next.toString());
            check(repository.frozenContent(archived).items().get("pilot.weapon.two_handed_sword.10").attack()==42&&repository.frozenContent(run.runId()).items().get("pilot.weapon.two_handed_sword.10").attack()==41,"Frozen reader used current bundle");
            UUID mismatch=UUID.randomUUID();ownedRuns.add(mismatch);insert(mismatch,"pilot-j02-1",next.toString());reject(()->repository.frozenContent(mismatch));
            rejectSql(()->insert(UUID.randomUUID(),"pilot-j02-1","[]"));
            System.out.println("New/replayed/archived snapshots, immutable fields, legacy preservation and migration rerun PASS");
        } finally {
            try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement("DELETE FROM dalmaru.run_instances WHERE run_id=?")){for(UUID run:ownedRuns){q.setObject(1,run);q.addBatch();}q.executeBatch();}
            EnhancementReplayCheck.cleanup(account);
        }
    }
    private static void insert(UUID run,String version,String snapshot)throws SQLException {
        try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement("INSERT INTO dalmaru.run_instances(run_id,content_id,difficulty,week_id,party_size,scaling_tier,state,content_version,content_snapshot) VALUES(?,'run.bongsudae','FIRST_STEP',CURRENT_DATE,1,1,'FAILED',COALESCE(?,'pilot-1'),?::jsonb)")){
            q.setObject(1,run);q.setString(2,version);q.setString(3,snapshot);q.executeUpdate();
        }
    }
    private static void execute(String sql,UUID id)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(sql)){q.setObject(1,id);q.executeUpdate();}}
    private interface Action{void run()throws Exception;}
    private static void reject(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid snapshot accepted");}
    private static void rejectSql(Action action)throws Exception{try{action.run();}catch(SQLException expected){if("23514".equals(expected.getSQLState()))return;throw expected;}throw new AssertionError("Immutable/invalid snapshot accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
