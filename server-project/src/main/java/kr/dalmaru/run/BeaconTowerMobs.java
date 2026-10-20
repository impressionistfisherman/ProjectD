package kr.dalmaru.run;

import java.util.List;
import kr.dalmaru.content.ContentCatalog;

public final class BeaconTowerMobs {
    private BeaconTowerMobs() { }
    public enum Kind { GUARD, ARCHER, EMBER, SHIELD, SOLDIER, COMMANDER }
    public static List<Kind> group(int group){return group(ContentCatalog.current(),group);}
    public static List<Kind> group(ContentCatalog content,int group){
        if(group<0||group>=content.encounter().groups().size())throw new IllegalArgumentException("Unknown group.");
        return content.encounter().groups().get(group).stream().map(Kind::valueOf).toList();
    }
    public static double healthScale(int partySize){return healthScale(ContentCatalog.current(),partySize);}
    public static double damageScale(int partySize){return damageScale(ContentCatalog.current(),partySize);}
    public static double healthScale(ContentCatalog content,int partySize){return content.encounter().healthScales().get(tier(partySize));}
    public static double damageScale(ContentCatalog content,int partySize){return content.encounter().damageScales().get(tier(partySize));}
    /** Party damage scale times the content-wide mob multiplier (P4 balance). Bosses do not use this. */
    public static double mobDamageScale(ContentCatalog content,int partySize){return damageScale(content,partySize)*content.encounter().mobDamageScale();}
    public static int tier(int size){if(size<1||size>4)throw new IllegalArgumentException("Invalid party size.");return size==1?0:size==2?1:2;}
}
