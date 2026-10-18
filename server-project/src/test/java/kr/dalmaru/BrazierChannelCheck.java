package kr.dalmaru;

import java.util.*;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.run.*;
import net.minecraft.world.phys.Vec3;

public final class BrazierChannelCheck {
    private static final UUID A=UUID.randomUUID(),B=UUID.randomUUID(),C=UUID.randomUUID();
    private static final Vec3 ORIGIN=Vec3.ZERO;
    private static final Map<UUID,Vec3> POSITIONS=Map.of(A,ORIGIN,B,ORIGIN,C,ORIGIN);
    public static void main(String[] args) {
        var solo=model(1);
        for(int i=0;i<100;i++)solo.tick(POSITIONS);
        check(!solo.complete(),"Proximity alone ignited brazier");
        check(solo.start(A,0,ORIGIN)==RunMechanicAccess.Result.STARTED,"Start rejected");
        for(int i=0;i<79;i++)check(solo.tick(POSITIONS).completed().isEmpty(),"Early completion");
        check(solo.remaining(A)==1,"79 tick progress");
        check(solo.start(A,0,ORIGIN)==RunMechanicAccess.Result.ALREADY_CHANNELING,"Replay reset channel");
        check(solo.tick(POSITIONS).completed().equals(Set.of(A))&&solo.complete(),"80 tick completion");
        check(solo.tick(POSITIONS).completed().isEmpty()&&solo.start(A,0,ORIGIN)==RunMechanicAccess.Result.ALREADY_LIT,"Success replay");
        var pair=model(2);pair.start(A,0,ORIGIN);
        check(pair.start(B,0,ORIGIN)==RunMechanicAccess.Result.BUSY,"Shared brazier ownership");
        check(pair.start(A,1,ORIGIN)==RunMechanicAccess.Result.ALREADY_CHANNELING,"Switch reset channel");
        tick(pair,80);check(pair.lit(0)&&!pair.complete(),"Two-player needs both braziers");
        pair.start(B,1,ORIGIN);tick(pair,80);check(pair.complete(),"Sequential pair failed");
        var moved=model(1);moved.start(A,0,ORIGIN);
        check(!moved.moved(A,new Vec3(.4999,0,0)),"Below threshold cancelled");
        check(moved.moved(A,new Vec3(.5,0,0)),"Exact threshold ignored");
        check(moved.tick(Map.of(A,new Vec3(0,.5,0))).cancelled().get(A)==RunMechanicAccess.CancelReason.MOVED,"Vertical threshold ignored");
        check(moved.start(A,0,ORIGIN)==RunMechanicAccess.Result.STARTED,"Restart failed");
        check(moved.cancel(A)&&!moved.cancel(A),"Cancellation replay");
        tick(moved,100);check(!moved.complete(),"Cancelled channel completed");
        moved.start(A,0,ORIGIN);check(moved.tick(Map.of()).cancelled().get(A)==RunMechanicAccess.CancelReason.DISCONNECT,"Absent member retained");
        for(int size:List.of(3,4)) {
            var coop=model(size);check(coop.count()==3,"Wrong brazier count");
            coop.start(A,0,ORIGIN);tick(coop,20);coop.start(B,1,ORIGIN);tick(coop,79);
            var result=coop.tick(POSITIONS);check(result.completed().equals(Set.of(A,B))&&coop.lit(0)&&coop.lit(1)&&!coop.complete(),"Overlap boundary failed");
            coop.start(C,2,ORIGIN);tick(coop,80);check(coop.complete(),"Last brazier cannot be finished");
            var late=model(size);late.start(A,0,ORIGIN);tick(late,21);late.start(B,1,ORIGIN);tick(late,78);
            check(late.tick(POSITIONS).unsynchronized().equals(Set.of(A))&&!late.lit(0),"Nonoverlapping completion accepted");
            tick(late,21);check(!late.lit(1),"Late solo completion accepted");
        }
        var split=model(4);split.start(A,0,ORIGIN);tick(split,20);split.start(B,1,ORIGIN);tick(split,20);split.start(C,2,ORIGIN);tick(split,60);
        check(split.lit(0)&&split.lit(1)&&!split.lit(2),"Overlapping pair did not preserve success");
        tick(split,20);check(split.complete(),"Residual requirement not reduced");
        System.out.println("Brazier explicit input,79/80 ticks,replay,ownership,0.5 movement,cancel,disconnect,1/2/3/4 tiers and overlap boundaries PASS");
    }
    private static BrazierMechanic model(int size){return new BrazierMechanic(ContentCatalog.current().mechanics().brazier(),size);}
    private static void tick(BrazierMechanic model,int count){for(int i=0;i<count;i++)model.tick(POSITIONS);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
