package kr.dalmaru.combat;

/** Pilot rank values shared by server effects and deterministic checks. */
public final class CombatRules {
    private CombatRules() {}
    public static int remainingModeTicks(boolean field,int combatTicks,long manualUntil,long tick) {
        return field?Math.max(Math.max(0,combatTicks),(int)Math.clamp(manualUntil-tick,0,200)):0;
    }
    public static double sweepMultiplier(int rank) {
        if(rank<1||rank>5)throw new IllegalArgumentException("Invalid skill rank.");
        return 1.1+(rank-1)*.1;
    }
    public static double rank(double first,double last,int rank) {
        if(rank<0||rank>5)throw new IllegalArgumentException("Invalid skill rank.");
        return rank==0?0:first+(last-first)*(rank-1)/4;
    }
    public static boolean critical(double chance,double roll) {
        if(!Double.isFinite(chance)||!Double.isFinite(roll)||roll<0||roll>=1)throw new IllegalArgumentException("Invalid critical roll.");
        return roll<Math.max(0,Math.min(.60,chance));
    }
    public enum TargetKind { NORMAL,ELITE,BOSS }
    public static double bindingSlow(TargetKind kind,boolean talisman) {
        return talisman?(kind==TargetKind.NORMAL?1:.30):(kind==TargetKind.BOSS||kind==TargetKind.ELITE?.15:.45);
    }
    public static int bindingTicks(TargetKind kind,boolean talisman,int rank) {
        return kind==TargetKind.NORMAL?(int)Math.round(rank(talisman?40:30,talisman?60:50,rank)):talisman?40:20;
    }
}
