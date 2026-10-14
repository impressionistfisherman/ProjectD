package kr.dalmaru.combat;

/** Fixed 20-tick server clock; no client-supplied immunity or charge timestamps. */
public final class DefenseState {
    private int charges=2;private long nextCharge,dodgeUntil,dodgeImmuneUntil,reviveUntil;
    private boolean holding;private long guardStart,lastPerfect=Long.MIN_VALUE,dropped,meterTick,disabledUntil,staggerUntil;
    private double meter=100;
    public void refresh(long tick){
        while(charges<2&&tick>=nextCharge){charges++;nextCharge+=100;}
        if(!holding&&tick>dropped+40){long from=Math.max(meterTick,dropped+40);meter=Math.min(100,meter+Math.max(0,tick-from));}
        meterTick=tick;
    }
    public boolean dodge(long tick,boolean grounded){
        refresh(tick);if(!grounded||charges==0||tick<dodgeUntil||tick<staggerUntil)return false;
        if(charges==2)nextCharge=tick+100;charges--;dodgeUntil=tick+9;dodgeImmuneUntil=tick+4;return true;
    }
    public int charges(long tick){refresh(tick);return charges;}
    public boolean dodging(long tick){return tick<dodgeUntil;}
    public void revive(long tick){reviveUntil=tick+60;holding=false;}
    public void attack(){reviveUntil=0;}
    public boolean immune(long tick){return tick<dodgeImmuneUntil||tick<reviveUntil;}
    public boolean dodgeImmune(long tick){return tick<dodgeImmuneUntil;}
    public void guard(boolean enabled,long tick){
        refresh(tick);
        if(!enabled){if(holding)dropped=tick;holding=false;return;}
        if(!holding&&tick>=disabledUntil&&tick>=staggerUntil){holding=true;guardStart=tick;}
    }
    public boolean guarding(long tick){return holding&&tick>=disabledUntil&&tick>=staggerUntil;}
    public boolean staggered(long tick){return tick<staggerUntil;}
    public double meter(long tick){refresh(tick);return meter;}
    public double reduce(double damage,long tick,boolean blockable,boolean front,boolean shield,double cost,boolean staggerResistance){
        refresh(tick);if(immune(tick))return 0;
        if(!blockable||!front||!guarding(tick))return damage;
        int window=shield?5:4;
        boolean perfect=tick-guardStart<window&&(lastPerfect==Long.MIN_VALUE||tick-lastPerfect>=16);
        if(perfect){lastPerfect=tick;if(shield){meter=Math.max(0,meter-cost*.25);if(meter==0)breakGuard(tick,staggerResistance);}return 0;}
        if(!shield)return tick-guardStart<9?damage*.7:damage;
        meter=Math.max(0,meter-cost);
        if(meter==0)breakGuard(tick,staggerResistance);
        return damage*.3;
    }
    private void breakGuard(long tick,boolean resist){holding=false;dropped=tick;disabledUntil=tick+60;if(!resist)staggerUntil=tick+20;}
    public void shift(long ticks){if(charges<2)nextCharge+=ticks;if(dodgeUntil!=0)dodgeUntil+=ticks;if(dodgeImmuneUntil!=0)dodgeImmuneUntil+=ticks;if(reviveUntil!=0)reviveUntil+=ticks;guardStart+=ticks;dropped+=ticks;meterTick+=ticks;if(disabledUntil!=0)disabledUntil+=ticks;if(staggerUntil!=0)staggerUntil+=ticks;if(lastPerfect!=Long.MIN_VALUE)lastPerfect+=ticks;}
}
