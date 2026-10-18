package kr.dalmaru.run;

import kr.dalmaru.content.BeaconMechanics;

/** Active encounter time only; starting or cancelling a channel cannot clear a warning. */
public final class BrazierNeglect {
    public record Tick(boolean warningStarted, boolean pulse, boolean cleared) {}
    private final BeaconMechanics.Brazier rules;
    private RunMechanicAccess.Neglect state=RunMechanicAccess.Neglect.IDLE;
    private int elapsed;
    private long lastTick=Long.MIN_VALUE;
    public BrazierNeglect(BeaconMechanics.Brazier rules){this.rules=java.util.Objects.requireNonNull(rules);}
    public RunMechanicAccess.Neglect state(){return state;}
    public int remaining(){return switch(state){
        case IDLE->rules.idleTicks()-elapsed;
        case WARNING->rules.warningTicks()-elapsed;
        case DAMAGING->rules.pulseTicks()-elapsed;
    };}
    public Tick tick(long serverTick,boolean channeling,boolean success){
        if(serverTick<=lastTick)return new Tick(false,false,false);
        lastTick=serverTick;
        if(success){boolean cleared=state!=RunMechanicAccess.Neglect.IDLE;state=RunMechanicAccess.Neglect.IDLE;elapsed=0;return new Tick(false,false,cleared);}
        if(state==RunMechanicAccess.Neglect.IDLE){
            elapsed=channeling?0:elapsed+1;
            if(elapsed==rules.idleTicks()){state=RunMechanicAccess.Neglect.WARNING;elapsed=0;return new Tick(true,false,false);}
        }else if(state==RunMechanicAccess.Neglect.WARNING){
            if(++elapsed==rules.warningTicks()){state=RunMechanicAccess.Neglect.DAMAGING;elapsed=0;}
        }else if(++elapsed==rules.pulseTicks()){elapsed=0;return new Tick(false,true,false);}
        return new Tick(false,false,false);
    }
    public static double damage(double maximumHealth,double fraction){
        if(!Double.isFinite(maximumHealth)||maximumHealth<=0||!Double.isFinite(fraction)||fraction<=0||fraction>1)
            throw new IllegalArgumentException("Invalid neglect damage");
        return maximumHealth*fraction;
    }
    /** One owner can receive a pulse through either the online player or the lingering body. */
    public static boolean targetLinger(boolean connected,boolean dead,boolean lingering,boolean alreadyHit){return !connected&&!dead&&lingering&&!alreadyHit;}
}
