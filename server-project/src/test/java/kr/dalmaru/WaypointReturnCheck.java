package kr.dalmaru;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import kr.dalmaru.account.*;
import kr.dalmaru.network.DalmaruPayloads;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

public final class WaypointReturnCheck {
    public static void main(String[] args)throws Exception {
        FixtureDatabase.databaseName();
        channel();wire();WaypointApplicationCheck.run();
        var firstVillage=kr.dalmaru.content.VillageCatalog.current().get(0);var secondVillage=kr.dalmaru.content.VillageCatalog.current().get(1);var zone=firstVillage.zone();var secondZone=secondVillage.zone();
        UUID account=UUID.randomUUID(),run=UUID.randomUUID();String town=firstVillage.id(),other=secondVillage.id(),obsolete="obsolete_"+account,trap="wp_"+account.toString().replace("-","");
        var repo=new WaypointRepository();var characters=new CharacterRepository();
        try {
            long generation=new AccountRepository().openSession(account).connectionGeneration();
            UUID character=characters.create(account,generation,1,1,"wp_"+account.toString().substring(0,8)).character().characterId();characters.selectActive(account,generation,2,character);
            sql("INSERT INTO dalmaru.town_safe_zones(name,dimension,x,y,z,radius) VALUES(?,'minecraft:overworld',1000000,64,1000000,4)",obsolete);
            check(!characters.inTown("minecraft:overworld",1000000,64,1000000),"Obsolete DB boundary overrides village configuration");
            reject(()->repo.unlock(account,generation,character,obsolete,"minecraft:overworld",1000000,64,1000000));
            reject(()->repo.check(account,generation,character,town));
            reject(()->repo.unlock(account,generation,character,town,"test:wrong_dimension",zone.x(),zone.y(),zone.z()));
            reject(()->repo.unlock(account,generation,character,town,zone.dimension(),zone.x()+zone.radius()+1,zone.y(),zone.z()));
            reject(()->repo.unlock(account,generation+1,character,town,zone.dimension(),zone.x(),zone.y(),zone.z()));
            reject(()->repo.unlock(account,generation,character,town,zone.dimension(),Double.NaN,zone.y(),zone.z()));
            check(repo.list(account,generation,character).isEmpty(),"New character must start without a visited destination");
            reject(()->repo.unlockIsolatedExit(account,generation+1,character,town,zone.dimension(),zone.x(),zone.y(),zone.z()));
            reject(()->repo.unlockIsolatedExit(account,generation,character,town,"test:wrong_dimension",zone.x(),zone.y(),zone.z()));
            reject(()->repo.unlockIsolatedExit(account,generation,character,town,zone.dimension(),zone.x()+1000,zone.y(),zone.z()));
            String escape=repo.unlockIsolatedExit(account,generation,character,town,zone.dimension(),zone.x(),zone.y(),zone.z());
            check(!escape.equals(town)&&firstVillage.routes().contains(escape)&&repo.list(account,generation,character).equals(List.of(escape)),"Isolated exit must unlock one outbound route without a destination visit");
            sql("UPDATE dalmaru.character_waypoints SET cooldown_until=CURRENT_TIMESTAMP+INTERVAL '15 minutes' WHERE character_id=?",character);
            long isolatedCooldown=value("SELECT EXTRACT(EPOCH FROM cooldown_until)*1000 FROM dalmaru.character_waypoints WHERE character_id=? AND waypoint_id=?",character,escape);
            repo.unlockIsolatedExit(account,generation,character,town,zone.dimension(),zone.x(),zone.y(),zone.z());
            check(isolatedCooldown==value("SELECT EXTRACT(EPOCH FROM cooldown_until)*1000 FROM dalmaru.character_waypoints WHERE character_id=? AND waypoint_id=?",character,escape),"Repeated isolated unlock must preserve cooldown");
            reject(()->repo.check(account,generation,character,escape));
            repo.check(account,generation,character,escape,true);
            sql("INSERT INTO dalmaru.run_instances(run_id,content_id,difficulty,week_id,party_size,scaling_tier,state) VALUES(?,'waypoint-test','FIRST_STEP',CURRENT_DATE,1,1,'ACTIVE')",run);
            sql("INSERT INTO dalmaru.run_participants(run_id,character_id) VALUES(?,?)",run,character);
            reject(()->repo.check(account,generation,character,escape,true));
            sql("UPDATE dalmaru.run_instances SET state='PAUSED' WHERE run_id=?",run);
            reject(()->repo.prepare(account,generation,character,UUID.randomUUID(),escape,true));
            sql("DELETE FROM dalmaru.run_instances WHERE run_id=?",run);
            UUID isolatedRequest=UUID.randomUUID();var isolated=repo.prepare(account,generation,character,isolatedRequest,escape,true);
            check(isolated.pending()&&!isolated.target().pointId().equals(town),"Isolated return must target another village even during cooldown");
            check(repo.prepare(account,generation,character,isolatedRequest,escape,true).equals(isolated),"Isolated return UUID replay changed");
            repo.finish(account,generation,character,isolatedRequest,true);
            check(!repo.prepare(account,generation,character,isolatedRequest,escape,true).pending(),"Isolated completion replay must not teleport twice");
            reject(()->repo.check(account,generation,character,escape));
            sql("DELETE FROM dalmaru.character_waypoints WHERE character_id=?",character);
            repo.unlock(account,generation,character,town,zone.dimension(),zone.x(),zone.y(),zone.z());
            long unlocked=value("SELECT extract(epoch FROM unlocked_at)*1000 FROM dalmaru.character_waypoints WHERE character_id=? AND waypoint_id=?",character,town);
            repo.unlock(account,generation,character,town,zone.dimension(),zone.x(),zone.y(),zone.z());
            check(unlocked==value("SELECT extract(epoch FROM unlocked_at)*1000 FROM dalmaru.character_waypoints WHERE character_id=? AND waypoint_id=?",character,town),"Repeated visit changed unlock timestamp");
            check(repo.list(account,generation,character).equals(List.of(town)),"List includes locked towns");
            repo.unlock(account,generation,character,other,secondZone.dimension(),secondZone.x(),secondZone.y(),secondZone.z());
            sql("INSERT INTO dalmaru.run_instances(run_id,content_id,difficulty,week_id,party_size,scaling_tier,state) VALUES(?,'waypoint-test','FIRST_STEP',CURRENT_DATE,1,1,'ACTIVE')",run);
            sql("INSERT INTO dalmaru.run_participants(run_id,character_id) VALUES(?,?)",run,character);
            reject(()->repo.check(account,generation,character,town));
            sql("UPDATE dalmaru.run_instances SET state='PAUSED' WHERE run_id=?",run);reject(()->repo.prepare(account,generation,character,UUID.randomUUID(),town));
            sql("DELETE FROM dalmaru.run_instances WHERE run_id=?",run);
            UUID request=UUID.randomUUID();var first=repo.prepare(account,generation,character,request,town);
            check(first.pending()&&first.target().x()==zone.x()+.5&&first.target().z()==zone.z()+.5,"Return target must come from registered town");
            long cooldown=value("SELECT extract(epoch FROM cooldown_until)*1000 FROM dalmaru.character_waypoints WHERE character_id=? AND waypoint_id=?",character,town);
            long remaining=value("SELECT extract(epoch FROM cooldown_until-clock_timestamp()) FROM dalmaru.character_waypoints WHERE character_id=? AND waypoint_id=?",character,town);
            check(remaining>=895&&remaining<=900,"Cooldown is not 15 minutes");
            check(repo.prepare(account,generation,character,request,town).equals(first),"UUID replay changed target or pending status");
            check(cooldown==value("SELECT extract(epoch FROM cooldown_until)*1000 FROM dalmaru.character_waypoints WHERE character_id=? AND waypoint_id=?",character,town),"Replay charged cooldown twice");
            reject(()->repo.prepare(account,generation,character,request,other));
            reject(()->repo.prepare(account,generation,character,UUID.randomUUID(),other));
            long newer=new AccountRepository().openSession(account).connectionGeneration();
            reject(()->repo.finish(account,generation,character,request,true));
            check(repo.pending(account,newer,character).request().equals(request),"Reconnect lost pending request");
            check(repo.prepare(account,newer,character,request,town).pending(),"New generation replay rejected");
            repo.finish(account,newer,character,request,false);
            check(value("SELECT count(*) FROM dalmaru.character_waypoints WHERE character_id=? AND cooldown_until IS NOT NULL",character)==0,"Cancelled reservation retained cooldown");
            check(repo.pending(account,newer,character)==null&&!repo.prepare(account,newer,character,request,town).pending(),"Completed cancellation replayed teleport");
            UUID acknowledged=UUID.randomUUID();repo.prepare(account,newer,character,acknowledged,other);repo.finish(account,newer,character,acknowledged,true);repo.finish(account,newer,character,acknowledged,false);
            check(value("SELECT count(*) FROM dalmaru.character_waypoints WHERE character_id=? AND cooldown_until>CURRENT_TIMESTAMP",character)==1,"Cancel after ACK refunded successful return");
            sql("UPDATE dalmaru.character_waypoints SET cooldown_until=NULL WHERE character_id=?",character);
            UUID failed=UUID.randomUUID();
            sql("CREATE FUNCTION dalmaru."+trap+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.character_id='"+character+"'::uuid AND NEW.operation='waypoint_return' AND NEW.state='pending_recovery' THEN RAISE EXCEPTION 'waypoint confirmation rollback'; END IF; RETURN NEW; END $$");
            sql("CREATE TRIGGER "+trap+" BEFORE UPDATE ON dalmaru.operation_requests FOR EACH ROW EXECUTE FUNCTION dalmaru."+trap+"()");
            try{repo.prepare(account,newer,character,failed,town);throw new AssertionError("Confirmation failure accepted");}catch(SQLException expected){}
            check(value("SELECT count(*) FROM dalmaru.character_waypoints WHERE character_id=? AND cooldown_until IS NOT NULL",character)==0&&value("SELECT count(*) FROM dalmaru.operation_requests WHERE request_id=?",failed)==0,"Confirmation failed to roll back cooldown/journal");
            drop(trap);
            ExecutorService executor=Executors.newFixedThreadPool(2);
            try {
                var start=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();
                for(String target:List.of(town,other))results.add(executor.submit(()->{start.await();try{repo.prepare(account,newer,character,UUID.randomUUID(),target);return true;}catch(IllegalArgumentException expected){return false;}}));
                start.countDown();int successes=0;for(var result:results)if(result.get(5,TimeUnit.SECONDS))successes++;
                check(successes==1,"Concurrent towns bypassed character-wide cooldown");
            }finally{executor.shutdownNow();}
        } finally {
            drop(trap);sql("DELETE FROM dalmaru.run_instances WHERE run_id=?",run);sql("DELETE FROM dalmaru.town_safe_zones WHERE name=?",obsolete);EnhancementReplayCheck.cleanup(account);
        }
        System.out.println("PASS waypoint first visit/ownership/dimension/unlock timestamp; 8-second boundaries/cancel; dungeon ACTIVE+PAUSED; shared 15-minute cooldown/concurrency; UUID replay/reconnect/cancel/ACK/rollback; application failure paths; packet roundtrip");
    }
    private static void channel(){
        UUID id=UUID.randomUUID();var cast=new WaypointChannel(id,7,"town","world",1,2,3,100);
        check(cast.poll(id,7,"world",1,2,3,true,false,false,259)==WaypointChannel.Status.WAIT,"Returned before 8 seconds");
        check(cast.poll(id,7,"world",1,2,3,true,false,false,260)==WaypointChannel.Status.READY,"Did not return at 8 seconds");
        check(cast.poll(id,7,"world",1.02,2,3,true,false,false,260)==WaypointChannel.Status.CANCEL,"Movement did not cancel");
        check(cast.poll(id,8,"world",1,2,3,true,false,false,260)==WaypointChannel.Status.CANCEL,"Generation did not cancel");
        check(cast.poll(UUID.randomUUID(),7,"world",1,2,3,true,false,false,260)==WaypointChannel.Status.CANCEL,"Character did not cancel");
        check(cast.poll(id,7,"other",1,2,3,true,false,false,260)==WaypointChannel.Status.CANCEL,"Dimension did not cancel");
        for(int mode=0;mode<3;mode++)check(cast.poll(id,7,"world",1,2,3,mode!=0,mode==1,mode==2,260)==WaypointChannel.Status.CANCEL,"Eligibility/combat/dungeon did not cancel");
        check(cast.poll(id,7,"world",Double.NaN,2,3,true,false,false,260)==WaypointChannel.Status.CANCEL,"NaN accepted");
        check(cast.poll(id,7,"world",1,2,3,true,false,false,99)==WaypointChannel.Status.CANCEL,"Clock rollback accepted");
    }
    private static void wire(){
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{var request=new DalmaruPayloads.WaypointRequest(123,UUID.randomUUID(),"return","설원 역참");DalmaruPayloads.WaypointRequest.CODEC.encode(buffer,request);check(request.equals(DalmaruPayloads.WaypointRequest.CODEC.decode(buffer)),"Request roundtrip");buffer.clear();var response=new DalmaruPayloads.WaypointResponse(123,request.characterId(),"해금 역참 목록",List.of("설원 역참","초원 역참"));DalmaruPayloads.WaypointResponse.CODEC.encode(buffer,response);check(response.equals(DalmaruPayloads.WaypointResponse.CODEC.decode(buffer)),"Response roundtrip");}finally{buffer.release();}
    }
    private interface Action{void run()throws Exception;}
    private static void reject(Action action)throws Exception{try{action.run();throw new AssertionError("Invalid request accepted");}catch(IllegalArgumentException expected){}}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static void sql(String text,Object...args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);q.execute();}}
    private static long value(String text,Object...args)throws SQLException{try(var c=EnhancementReplayCheck.connection();var q=c.prepareStatement(text)){for(int i=0;i<args.length;i++)q.setObject(i+1,args[i]);try(var r=q.executeQuery()){check(r.next(),"Missing fixture row");return r.getLong(1);}}}
    private static void drop(String name)throws SQLException{sql("DROP TRIGGER IF EXISTS "+name+" ON dalmaru.operation_requests");sql("DROP FUNCTION IF EXISTS dalmaru."+name+"()");}
}
