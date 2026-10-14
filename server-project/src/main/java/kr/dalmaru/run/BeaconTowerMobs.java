package kr.dalmaru.run;

import java.util.List;

public final class BeaconTowerMobs {
    private BeaconTowerMobs() { }
    public enum Kind {
        GUARD("파수 잔영",330,30,20,75,32,false,3),
        ARCHER("혼백 궁수",280,15,35,65,40,true,12),
        EMBER("불씨 잔영",310,20,25,58,40,true,8),
        SHIELD("방패 잔영",520,70,35,85,40,false,3),
        SOLDIER("잿빛 군졸",360,25,25,72,30,false,3),
        COMMANDER("망령 지휘자",3000,55,55,95,40,false,3);
        public final String label;
        public final int health,physicalDefense,magicDefense,damage,interval,range;
        public final boolean magic;
        Kind(String label,int health,int physicalDefense,int magicDefense,int damage,int interval,boolean magic,int range) {
            this.label=label;this.health=health;this.physicalDefense=physicalDefense;this.magicDefense=magicDefense;this.damage=damage;this.interval=interval;this.magic=magic;this.range=range;
        }
    }
    public static List<Kind> group(int group) {
        return switch(group) {
            case 0 -> List.of(Kind.GUARD,Kind.GUARD,Kind.GUARD,Kind.ARCHER,Kind.ARCHER);
            case 1 -> List.of(Kind.GUARD,Kind.GUARD,Kind.SHIELD,Kind.SHIELD,Kind.ARCHER);
            case 2 -> List.of(Kind.EMBER,Kind.EMBER,Kind.EMBER,Kind.SOLDIER,Kind.SOLDIER,Kind.SOLDIER,Kind.SOLDIER,Kind.ARCHER,Kind.ARCHER);
            case 3 -> List.of(Kind.COMMANDER,Kind.SHIELD,Kind.SHIELD,Kind.SOLDIER,Kind.SOLDIER);
            default -> throw new IllegalArgumentException("Unknown group.");
        };
    }
    public static double healthScale(int partySize) { checkParty(partySize);return partySize==1?1:partySize==2?1.8:3.25; }
    public static double damageScale(int partySize) { checkParty(partySize);return partySize==1?1:partySize==2?1.12:1.25; }
    private static void checkParty(int size) { if(size<1||size>4)throw new IllegalArgumentException("Invalid party size."); }
}
