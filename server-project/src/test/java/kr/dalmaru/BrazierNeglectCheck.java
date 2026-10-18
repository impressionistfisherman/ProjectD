package kr.dalmaru;

import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.run.*;
import kr.dalmaru.combat.CombatUnits;

public final class BrazierNeglectCheck {
    public static void main(String[] args){
        var rules=ContentCatalog.current().mechanics().brazier();var model=new BrazierNeglect(rules);
        for(int tick=1;tick<400;tick++)check(!model.tick(tick,false,false).pulse(),"Early damage");
        check(model.state()==RunMechanicAccess.Neglect.IDLE&&model.remaining()==1,"399 boundary");
        check(model.tick(400,false,false).warningStarted()&&model.remaining()==200,"20 second warning");
        check(!model.tick(400,false,false).warningStarted(),"Warning replay");
        for(int tick=401;tick<600;tick++)check(!model.tick(tick,true,false).pulse(),"Attempt cleared warning or caused early damage");
        check(model.state()==RunMechanicAccess.Neglect.WARNING&&model.remaining()==1,"599 boundary");
        check(!model.tick(600,true,false).pulse()&&model.state()==RunMechanicAccess.Neglect.DAMAGING&&model.remaining()==60,"30 second boundary");
        for(int tick=601;tick<660;tick++)check(!model.tick(tick,false,false).pulse(),"First damage before33 seconds");
        check(model.tick(660,false,false).pulse(),"33 second pulse missing");
        check(!model.tick(660,false,false).pulse()&&!model.tick(659,false,false).pulse(),"Duplicate/old tick pulse");
        for(int tick=661;tick<720;tick++)check(!model.tick(tick,false,false).pulse(),"Pulse interval");
        check(model.tick(720,true,false).pulse(),"Channel attempt cancelled hazard");
        check(model.tick(721,true,true).cleared()&&model.state()==RunMechanicAccess.Neglect.IDLE&&model.remaining()==400,"Success must clear warning");
        for(int tick=722;tick<=1200;tick++)check(!model.tick(tick,true,false).warningStarted(),"Active channel counted as idle");
        var paused=new BrazierNeglect(rules);for(int tick=1;tick<=399;tick++)paused.tick(tick,false,false);
        check(paused.tick(50000,false,false).warningStarted()&&paused.remaining()==200,"Paused wall time advanced mechanic");
        var successAtPulse=new BrazierNeglect(rules);for(int tick=1;tick<660;tick++)successAtPulse.tick(tick,false,false);
        check(!successAtPulse.tick(660,true,true).pulse()&&successAtPulse.state()==RunMechanicAccess.Neglect.IDLE,"Success and pulse race");
        for(boolean connected:new boolean[]{true,false})for(boolean dead:new boolean[]{true,false})for(boolean linger:new boolean[]{true,false})for(boolean hit:new boolean[]{true,false})
            check(BrazierNeglect.targetLinger(connected,dead,linger,hit)==(!connected&&!dead&&linger&&!hit),"Linger and online owner duplicate/dead targeting");
        check(BrazierNeglect.damage(1000,.15)==150&&CombatUnits.toEngine(BrazierNeglect.damage(CombatUnits.toRpg(50),.15))==7.5F,"Maximum health units");
        try{BrazierNeglect.damage(Double.NaN,.15);throw new AssertionError("Invalid maximum health");}catch(IllegalArgumentException expected){}
        System.out.println("Neglect20/30/33 seconds,pulse replay,success/attempt,pause,owner/body targeting and15% unit conversion PASS");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
