package kr.dalmaru;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.equipment.EnhancementRules;
import kr.dalmaru.equipment.PilotEquipment;

public final class ContentCatalogCheck {
    public static void main(String[] args)throws Exception{
        String source=Files.readString(Path.of("src/main/resources/content/pilot.json"));
        var catalog=ContentCatalog.current();check(catalog.items().size()==78&&catalog.version().equals("pilot-p4-1"),"Pilot IDs/version changed");
        check(catalog.quest("pilot1.zombie_hunt").version().equals("pilot-1")&&catalog.quest("pilot1.zombie_hunt").goal()==3&&catalog.quest("pilot1.zombie_hunt").experience()==100,"Legacy quest changed");
        check(catalog.run("run.bongsudae").version().equals("pilot-p4-1")&&catalog.run("run.bongsudae").minPlayers()==1&&catalog.run("run.bongsudae").maxPlayers()==4,"Run manifest differs");
        int[][] attacks={{41,51,63},{30,37,46},{35,44,55},{57,71,88},{36,46,57},{31,39,48},{38,48,59},{34,43,53}};
        String[] weapons={"two_handed_sword","sword_shield","bow","crossbow","fan","ritual_sword","dual_axes","pirate_sword"};int[] ticks={20,16,18,30,20,18,10,16},levels={10,15,20};
        for(int w=0;w<weapons.length;w++)for(int tier=0;tier<3;tier++){
            var item=catalog.items().get("pilot.weapon."+weapons[w]+"."+levels[tier]);check(item.attack()==attacks[w][tier]&&item.basicTicks()==ticks[w]&&item.magical()==(w==4||w==5)&&item.basicMultiplier()==(w==6?.55:1),"Weapon legacy values changed");
        }
        String[] armor={"heavy","light","cloth"},parts={"head","body","arms","feet"};double[] shares={.2,.4,.2,.2};int[][] physical={{45,75,100},{35,60,80},{25,40,55}},magic={{22,38,50},{35,60,80},{45,75,100}};
        for(int a=0;a<armor.length;a++)for(int tier=0;tier<3;tier++)for(int p=0;p<parts.length;p++){
            var item=catalog.items().get("pilot.armor."+armor[a]+"."+parts[p]+"."+levels[tier]);check(item.slot()==p+2&&item.physical()==physical[a][tier]*shares[p]&&item.magic()==magic[a][tier]*shares[p]&&item.budget()==.45*shares[p],"Armor legacy values changed");
        }
        for(var level:levels)for(String name:List.of("neck","ring","charm"))check(catalog.items().get("pilot.accessory."+name+"."+level).budget()==.25/3,"Accessory budget changed");
        int[] copper={8,12,18,26,36},ironstone={0,1,1,2,2};for(int i=0;i<5;i++)check(EnhancementRules.cost(i+1).copper()==copper[i]&&EnhancementRules.cost(i+1).ironstone()==ironstone[i],"Cost consumer changed");
        check(PilotEquipment.slot("pilot.weapon.fan.20")==0&&PilotEquipment.slot("pilot.armor.heavy.body.15")==3,"Equipment consumer not connected");
        check(!catalog.display("pilot.weapon.fan.20").name().equals("pilot.weapon.fan.20")&&!catalog.display("pilot.armor.heavy.body.15").description().isBlank(),"Item display templates not consumed");
        check(catalog.display("unknown.legacy").name().equals("unknown.legacy"),"Unknown legacy label invented");
        var legacyDisplay=JsonParser.parseString(source).getAsJsonObject();legacyDisplay.addProperty("schemaVersion",3);legacyDisplay.addProperty("contentVersion","pilot-p3-4");for(var run:legacyDisplay.getAsJsonArray("runs")){run.getAsJsonObject().addProperty("version","pilot-p3-4");run.getAsJsonObject().addProperty("contentVersion","pilot-p3-4");}
        check(ContentCatalog.parse(legacyDisplay.toString()).display("pilot.weapon.fan.20").name().equals("pilot.weapon.fan.20"),"Legacy display overwritten");
        reject(source,root->root.remove("display"));
        reject(source,root->root.getAsJsonObject("equipment").getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonObject("display").addProperty("name",""));
        reject(source,root->root.getAsJsonArray("runs").get(0).getAsJsonObject().addProperty("contentVersion","different"));
        reject(source,root->root.getAsJsonObject("enhancement").addProperty("enabled",false));
        reject(source,root->root.getAsJsonObject("equipment").getAsJsonArray("parts").get(0).getAsJsonObject().addProperty("enabled",false));
        reject(source,root->root.addProperty("id","skill.warrior.sweep"));
        UUID character=UUID.randomUUID();
        var profile=new kr.dalmaru.account.CharacterProfile(character,20,0,0,0,0,0,0,0,false,0,"WARRIOR");
        for(int enhancement:List.of(0,5,6,15)){
            var item=new kr.dalmaru.equipment.EquipmentRecord(UUID.randomUUID(),character,0,"pilot.weapon.two_handed_sword.20",20,"WARRIOR",enhancement,0,false);
            var stats=PilotEquipment.equipped(profile,List.of(item));
            double scale=1+Math.min(enhancement,5)*.02+Math.max(0,enhancement-5)*.03;
            check(stats.attack()==Math.round(63*scale)&&stats.primary()==Math.round(12*.30*scale)&&stats.vitality()==Math.round(8*.30*scale)&&stats.spirit()==Math.round(6*.30*scale),"Equipment bonus/scaling changed");
        }
        check(EnhancementRules.chance(14,Integer.MAX_VALUE)==100,"Pity calculation overflow");
        var gearVariant=JsonParser.parseString(source).getAsJsonObject();
        gearVariant.getAsJsonObject("equipment").getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonArray("attack").set(2,new JsonPrimitive(64));
        gearVariant.getAsJsonObject("equipment").getAsJsonArray("primary").set(2,new JsonPrimitive(16));
        gearVariant.getAsJsonObject("enhancement").addProperty("statIncreaseFirstFive",.04);
        var archivedGear=ContentCatalog.parse(gearVariant.toString());
        var equippedRecord=new kr.dalmaru.equipment.EquipmentRecord(UUID.randomUUID(),character,0,"pilot.weapon.two_handed_sword.20",20,"WARRIOR",5,0,false);
        var pinnedStats=PilotEquipment.equipped(profile,List.of(equippedRecord),archivedGear);
        var currentStats=PilotEquipment.equipped(profile,List.of(equippedRecord));
        check(pinnedStats.attack()==77&&pinnedStats.primary()==6&&currentStats.attack()==69&&currentStats.primary()==4,"Equipment ignored archived item/bonus/scaling definitions");
        String[] kinds={"GUARD","ARCHER","EMBER","SHIELD","SOLDIER","COMMANDER"};
        int[] health={330,280,310,520,360,3000},physicalDefense={30,15,20,70,25,55},magicDefense={20,35,25,35,25,55},damage={75,65,58,85,72,95},interval={32,40,40,40,30,40},range={3,12,8,3,3,3};
        for(int i=0;i<kinds.length;i++){
            var mob=catalog.encounter().mob(kinds[i]);
            check(mob.health()==health[i]&&mob.physicalDefense()==physicalDefense[i]&&mob.magicDefense()==magicDefense[i]&&mob.damage()==damage[i]&&mob.interval()==interval[i]&&mob.range()==range[i]&&mob.magic()==(i==1||i==2),"J02 mob values changed");
        }
        check(catalog.encounter().gatekeeperHealth()==9300&&catalog.encounter().finalBossHealth()==14400,"Boss health changed");
        check(catalog.encounter().mobDamageScale()==0.11&&catalog.encounter().gatekeeperDamageScale()==0.05,"P4 balance multipliers changed");
        var changed=JsonParser.parseString(source).getAsJsonObject();var encounter=changed.getAsJsonObject("encounter");
        encounter.getAsJsonArray("mobs").get(0).getAsJsonObject().addProperty("health",999);
        encounter.getAsJsonArray("groups").get(0).getAsJsonArray().set(0,new JsonPrimitive("SOLDIER"));
        encounter.getAsJsonArray("healthScales").set(0,new JsonPrimitive(2));
        var archived=ContentCatalog.parse(changed.toString());
        check(archived.encounter().mob("GUARD").health()==999&&kr.dalmaru.run.BeaconTowerMobs.group(archived,0).getFirst()==kr.dalmaru.run.BeaconTowerMobs.Kind.SOLDIER&&kr.dalmaru.run.BeaconTowerMobs.healthScale(archived,1)==2&&catalog.encounter().mob("GUARD").health()==330,"Archived encounter used current definitions");
        try{archived.encounter().groups().get(0).clear();throw new AssertionError("Mutable roster");}catch(UnsupportedOperationException expected){}
        reject(source,root->root.addProperty("schemaVersion",6));reject(source,root->root.addProperty("enabled",false));
        reject(source,root->root.getAsJsonArray("skills").add(root.getAsJsonArray("skills").get(0).deepCopy()));
        reject(source,root->root.getAsJsonArray("skills").get(0).getAsJsonObject().addProperty("id","skill.unknown"));
        reject(source,root->root.getAsJsonArray("quests").get(0).getAsJsonObject().getAsJsonArray("itemRefs").add("pilot.unknown"));
        reject(source,root->root.getAsJsonArray("quests").get(0).getAsJsonObject().getAsJsonArray("skillRefs").add("skill.unknown"));
        reject(source,root->root.getAsJsonArray("quests").get(0).getAsJsonObject().getAsJsonArray("requires").add("quest.unknown"));
        reject(source,root->root.getAsJsonArray("quests").get(0).getAsJsonObject().getAsJsonArray("requires").add("pilot1.zombie_hunt"));
        reject(source,root->root.getAsJsonObject("enhancement").getAsJsonArray("chances").set(0,new JsonPrimitive(101)));
        reject(source,root->root.getAsJsonObject("enhancement").getAsJsonArray("costs").get(0).getAsJsonObject().addProperty("copper",-1));
        reject(source,root->root.getAsJsonObject("equipment").getAsJsonArray("primary").remove(0));
        reject(source,root->root.getAsJsonObject("enhancement").addProperty("failureBonus",101));
        reject(source,root->root.getAsJsonObject("enhancement").addProperty("statIncreaseLater",-1));
        reject(source,root->root.getAsJsonObject("encounter").getAsJsonArray("mobs").get(0).getAsJsonObject().addProperty("interval",0));
        reject(source,root->root.getAsJsonObject("encounter").getAsJsonArray("mobs").get(0).getAsJsonObject().addProperty("enabled",false));
        reject(source,root->root.getAsJsonObject("encounter").getAsJsonArray("mobs").add(root.getAsJsonObject("encounter").getAsJsonArray("mobs").get(0).deepCopy()));
        reject(source,root->root.getAsJsonObject("encounter").getAsJsonArray("groups").get(0).getAsJsonArray().add("UNKNOWN"));
        reject(source,root->root.getAsJsonObject("encounter").getAsJsonArray("groups").get(0).getAsJsonArray().add("COMMANDER"));
        reject(source,root->root.getAsJsonObject("encounter").getAsJsonArray("healthScales").set(0,new JsonPrimitive(0)));
        reject(source,root->root.getAsJsonObject("encounter").addProperty("gatekeeperHealth",0));
        reject(source,root->root.getAsJsonObject("equipment").getAsJsonArray("weapons").get(0).getAsJsonObject().addProperty("profession","UNKNOWN"));
        reject(source,root->root.getAsJsonObject("equipment").getAsJsonArray("weapons").get(0).getAsJsonObject().getAsJsonArray("attack").set(0,new JsonPrimitive(1.5)));
        reject(source,root->root.getAsJsonObject("equipment").getAsJsonArray("weapons").add(root.getAsJsonObject("equipment").getAsJsonArray("weapons").get(0).deepCopy()));
        reject(source,root->root.getAsJsonArray("runs").get(0).getAsJsonObject().addProperty("maxPlayers",5));
        var disabled=JsonParser.parseString(source).getAsJsonObject();disabled.getAsJsonObject("equipment").getAsJsonArray("weapons").get(0).getAsJsonObject().addProperty("enabled",false);
        check(!ContentCatalog.parse(disabled.toString()).items().get("pilot.weapon.two_handed_sword.10").enabled(),"Disabled item flag lost");
        disabled.getAsJsonArray("quests").get(0).getAsJsonObject().getAsJsonArray("itemRefs").add("pilot.weapon.two_handed_sword.10");try{ContentCatalog.parse(disabled.toString());throw new AssertionError("Active reference to disabled item accepted");}catch(IllegalArgumentException expected){}
        try{catalog.items().clear();throw new AssertionError("Catalog is mutable");}catch(UnsupportedOperationException expected){}
        check(ContentCatalog.parse(catalog.snapshot()).items().equals(catalog.items()),"Snapshot roundtrip changed definitions");
        System.out.println("Versioned pilot definitions,78 item displays,6 J02 mobs, archived consumers and30 invalid-definition cases PASS");
    }
    private static void reject(String source,Consumer<JsonObject> change){var root=JsonParser.parseString(source).getAsJsonObject();change.accept(root);try{ContentCatalog.parse(root.toString());}catch(IllegalArgumentException expected){return;}throw new AssertionError("Invalid content definition accepted");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
