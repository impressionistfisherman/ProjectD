package kr.dalmaru;
import kr.dalmaru.run.BeaconTowerMobs;
public final class BeaconTowerMobsCheck {
    public static void main(String[] args) {
        int total=0;var kinds=java.util.EnumSet.noneOf(BeaconTowerMobs.Kind.class);
        int[] counts={5,5,9,5};
        for(int group=0;group<4;group++){var roster=BeaconTowerMobs.group(group);require(roster.size()==counts[group],"Group roster");total+=roster.size();kinds.addAll(roster);}
        require(total==24&&kinds.size()==6,"Fixed roster");
        require(BeaconTowerMobs.group(3).stream().filter(k->k==BeaconTowerMobs.Kind.COMMANDER).count()==1,"Single commander");
        require(BeaconTowerMobs.healthScale(1)==1&&BeaconTowerMobs.healthScale(2)==1.8&&BeaconTowerMobs.healthScale(4)==3.25,"Health scaling");
        require(BeaconTowerMobs.damageScale(1)==1&&BeaconTowerMobs.damageScale(2)==1.12&&BeaconTowerMobs.damageScale(3)==1.25,"Damage scaling");
        try{BeaconTowerMobs.group(4);throw new AssertionError("Invalid group accepted");}catch(IllegalArgumentException expected){}
        try{BeaconTowerMobs.healthScale(0);throw new AssertionError("Invalid party accepted");}catch(IllegalArgumentException expected){}
        for(var kind:kinds)require(kind.health>0&&kind.interval>=30&&kind.range>0&&!kind.label.contains("?"),"Invalid definition");
        System.out.println("J02 roster/scaling PASS; live entity and group lifecycle remain for headless verification.");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
