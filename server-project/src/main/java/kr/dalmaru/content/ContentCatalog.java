package kr.dalmaru.content;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import kr.dalmaru.account.CharacterProfession;
import kr.dalmaru.equipment.EnhancementRules;
import kr.dalmaru.skill.SkillDefinition;

public final class ContentCatalog {
    public record Item(int level,int slot,String requirement,int attack,double physical,double magic,double budget,boolean magical,double basicMultiplier,int basicTicks,boolean enabled){}
    public record Quest(String id,String version,String target,int goal,int experience,boolean enabled,List<String> requires){}
    public record Run(String id,String version,int minPlayers,int maxPlayers,boolean enabled,List<String> requires){}
    public record Mob(String id,String kind,String label,int health,int physicalDefense,int magicDefense,int damage,int interval,boolean magic,int range,boolean enabled){}
    public record EncounterDefinition(Map<String,Mob> mobs,List<List<String>> groups,List<Double> healthScales,List<Double> damageScales,int gatekeeperHealth,int finalBossHealth){
        public EncounterDefinition {mobs=Map.copyOf(mobs);groups=groups.stream().map(List::copyOf).toList();healthScales=List.copyOf(healthScales);damageScales=List.copyOf(damageScales);}
        public Mob mob(String kind){var value=mobs.get(kind);if(value==null||!value.enabled())throw new IllegalArgumentException("Unknown/disabled encounter mob");return value;}
    }
    private final EncounterDefinition encounter;
    public EncounterDefinition encounter(){return encounter;}
    private final String version,snapshot;
    private final Map<String,Item> items;
    private final Map<String,Quest> quests;
    private final Map<String,Run> runs;
    public record EquipmentBonuses(List<Integer> primary,List<Integer> vitality,List<Integer> spirit){}
    public record EnhancementPolicy(double firstFive,double later,int failureBonus){}
    private final EquipmentBonuses bonuses;
    private final EnhancementPolicy enhancementPolicy;
    private final List<Integer> chances;
    private final List<EnhancementRules.Cost> costs;
    private ContentCatalog(String version,String snapshot,Map<String,Item> items,Map<String,Quest> quests,Map<String,Run> runs,List<Integer> chances,List<EnhancementRules.Cost> costs,EquipmentBonuses bonuses,EnhancementPolicy enhancementPolicy,EncounterDefinition encounter){
        this.encounter=encounter;this.bonuses=bonuses;this.enhancementPolicy=enhancementPolicy;
        this.version=version;this.snapshot=snapshot;this.items=Map.copyOf(items);this.quests=Map.copyOf(quests);this.runs=Map.copyOf(runs);this.chances=List.copyOf(chances);this.costs=List.copyOf(costs);
    }
    private static final class Current{static final ContentCatalog VALUE=load();}
    public static ContentCatalog current(){return Current.VALUE;}
    private static ContentCatalog load(){
        try(var stream=ContentCatalog.class.getResourceAsStream("/content/pilot.json")){
            if(stream==null)throw new IllegalStateException("Missing pilot content definitions");
            byte[] bytes=stream.readNBytes(1_048_577);if(bytes.length>1_048_576)throw new IllegalArgumentException("Pilot content exceeds size limit");
            return parse(new String(bytes,StandardCharsets.UTF_8));
        }catch(IOException error){throw new IllegalStateException("Cannot load pilot content definitions",error);}
    }
    public String version(){return version;}
    public String snapshot(){return snapshot;}
    public Map<String,Item> items(){return items;}
    public Quest quest(String id){var value=quests.get(id);if(value==null)throw new IllegalArgumentException("Unknown quest: "+id);return value;}
    public Run run(String id){var value=runs.get(id);if(value==null)throw new IllegalArgumentException("Unknown run: "+id);return value;}
    public EquipmentBonuses bonuses(){return bonuses;}
    public EnhancementPolicy enhancementPolicy(){return enhancementPolicy;}
    public List<Integer> chances(){return chances;}
    public List<EnhancementRules.Cost> costs(){return costs;}

    public static ContentCatalog parse(String source){
        try{
            if(source.length()>1_048_576)throw new IllegalArgumentException("Content snapshot exceeds size limit");
            var root=JsonParser.parseString(source).getAsJsonObject();
            if(integer(root,"schemaVersion")!=1||!flag(root,"enabled"))throw new IllegalArgumentException("Unsupported/disabled content schema");
            String version=version(root,"contentVersion");Set<String> all=new HashSet<>(),skills=new HashSet<>(),knownSkills=new HashSet<>();
            for(var profession:CharacterProfession.values())for(var skill:SkillDefinition.forProfession(profession))knownSkills.add(skill.id());
            for(var entry:array(root,"skills")){
                var skill=entry.getAsJsonObject();String id=id(skill,"id");unique(all,id);
                if(!knownSkills.contains(id)||!flag(skill,"enabled"))throw new IllegalArgumentException("Unsupported/disabled pilot skill: "+id);skills.add(id);
            }
            if(!skills.equals(knownSkills))throw new IllegalArgumentException("Pilot skill set is incomplete");
            var equipment=root.getAsJsonObject("equipment");var levels=integers(equipment,"levels");
            if(!levels.equals(List.of(10,15,20)))throw new IllegalArgumentException("Unsupported pilot equipment tiers");
            var primary=integers(equipment,"primary");var vitality=integers(equipment,"vitality");var spirit=integers(equipment,"spirit");
            if(primary.size()!=3||vitality.size()!=3||spirit.size()!=3)throw new IllegalArgumentException("Equipment bonus tiers incomplete");
            var bonuses=new EquipmentBonuses(primary,vitality,spirit);
            var budgets=equipment.getAsJsonObject("budgets");double weaponBudget=number(budgets,"weapon"),armorBudget=number(budgets,"armor"),accessoryBudget=number(budgets,"accessories");
            if(Math.abs(weaponBudget+armorBudget+accessoryBudget-1)>1e-9)throw new IllegalArgumentException("Equipment budget must total one");
            var items=new LinkedHashMap<String,Item>();
            var parts=array(equipment,"parts");var accessories=array(equipment,"accessories");Set<Integer> armorSlots=new HashSet<>(),accessorySlots=new HashSet<>();double totalShare=0;
            for(var element:parts){var part=element.getAsJsonObject();int slot=integer(part,"slot");if(slot<2||slot>5||!armorSlots.add(slot))throw new IllegalArgumentException("Invalid/duplicate armor slot");totalShare+=number(part,"share");}
            if(armorSlots.size()!=4||Math.abs(totalShare-1)>1e-9)throw new IllegalArgumentException("Armor parts are incomplete");
            for(var element:accessories){var part=element.getAsJsonObject();int slot=integer(part,"slot");if(slot<6||slot>8||!accessorySlots.add(slot))throw new IllegalArgumentException("Invalid/duplicate accessory slot");}
            if(accessorySlots.size()!=3)throw new IllegalArgumentException("Accessory parts are incomplete");
            Set<String> equippedProfessions=new HashSet<>(),weaponNames=new HashSet<>(),armorNames=new HashSet<>();
            for(var element:array(equipment,"weapons")){
                var weapon=element.getAsJsonObject();String name=id(weapon,"id"),profession=text(weapon,"profession");CharacterProfession.valueOf(profession);
                weaponNames.add(name);
                var attacks=integers(weapon,"attack");if(attacks.size()!=3)throw new IllegalArgumentException("Weapon tiers incomplete");
                int ticks=integer(weapon,"basicTicks");double multiplier=number(weapon,"basicMultiplier");if(ticks<1||multiplier<=0)throw new IllegalArgumentException("Invalid basic attack");
                boolean enabled=flag(weapon,"enabled");if(enabled)equippedProfessions.add(profession);
                for(int tier=0;tier<3;tier++)put(items,all,"pilot.weapon."+name+"."+levels.get(tier),new Item(levels.get(tier),0,profession,attacks.get(tier),0,0,weaponBudget,flag(weapon,"magical"),multiplier,ticks,enabled));
            }
            if(equippedProfessions.size()!=CharacterProfession.values().length)throw new IllegalArgumentException("Weapon profession coverage incomplete");
            if(!weaponNames.equals(Set.of("two_handed_sword","sword_shield","bow","crossbow","fan","ritual_sword","dual_axes","pirate_sword")))throw new IllegalArgumentException("Pilot weapon definitions incomplete/unsupported");
            for(var element:array(equipment,"armor")){
                var armor=element.getAsJsonObject();String name=id(armor,"id");if(!Set.of("heavy","light","cloth").contains(name))throw new IllegalArgumentException("Unknown armor compatibility");
                armorNames.add(name);
                var physical=integers(armor,"physical");var magic=integers(armor,"magic");if(physical.size()!=3||magic.size()!=3)throw new IllegalArgumentException("Armor tiers incomplete");
                for(int tier=0;tier<3;tier++)for(var partElement:parts){var part=partElement.getAsJsonObject();double share=number(part,"share");put(items,all,"pilot.armor."+name+"."+id(part,"id")+"."+levels.get(tier),new Item(levels.get(tier),integer(part,"slot"),name,0,physical.get(tier)*share,magic.get(tier)*share,armorBudget*share,false,1,0,flag(armor,"enabled")));}
            }
            if(!armorNames.equals(Set.of("heavy","light","cloth")))throw new IllegalArgumentException("Pilot armor definitions incomplete");
            for(var element:accessories){var accessory=element.getAsJsonObject();for(var level:levels)put(items,all,"pilot.accessory."+id(accessory,"id")+"."+level,new Item(level,integer(accessory,"slot"),"",0,0,0,accessoryBudget/accessories.size(),false,1,0,flag(accessory,"enabled")));}
            var quests=new LinkedHashMap<String,Quest>();
            for(var element:array(root,"quests")){
                var quest=element.getAsJsonObject();String id=id(quest,"id");unique(all,id);String target=text(quest,"target");int goal=integer(quest,"goal");
                if(!"minecraft:zombie".equals(target)||goal<1)throw new IllegalArgumentException("Unsupported quest objective");
                quests.put(id,new Quest(id,version(quest,"version"),target,goal,integer(quest,"experience"),flag(quest,"enabled"),strings(quest,"requires")));
            }
            for(var element:array(root,"quests")){var quest=element.getAsJsonObject();references(quest,items,skills,quests);}
            for(var quest:quests.values())visit(quest.id(),quests,new HashSet<>(),new HashSet<>());
            var runs=new LinkedHashMap<String,Run>();
            for(var element:array(root,"runs")){
                var run=element.getAsJsonObject();String id=id(run,"id");unique(all,id);int min=integer(run,"minPlayers"),max=integer(run,"maxPlayers");if(min<1||max<min||max>4)throw new IllegalArgumentException("Unsupported run party size");
                String runVersion=version(run,"version");if(!runVersion.equals(version))throw new IllegalArgumentException("Run version differs from bundle");references(run,items,skills,quests);runs.put(id,new Run(id,runVersion,min,max,flag(run,"enabled"),strings(run,"requires")));
            }
            var encounterSource=root.getAsJsonObject("encounter");var mobs=new LinkedHashMap<String,Mob>();
            for(var element:array(encounterSource,"mobs")){
                var mob=element.getAsJsonObject();String id=id(mob,"id"),kind=text(mob,"kind");unique(all,id);
                if(!Set.of("GUARD","ARCHER","EMBER","SHIELD","SOLDIER","COMMANDER").contains(kind))throw new IllegalArgumentException("Unsupported mob kind");
                var definition=new Mob(id,kind,text(mob,"label"),integer(mob,"health"),integer(mob,"physicalDefense"),integer(mob,"magicDefense"),integer(mob,"damage"),integer(mob,"interval"),flag(mob,"magic"),integer(mob,"range"),flag(mob,"enabled"));
                if(definition.health()<1||definition.damage()<1||definition.interval()<1||definition.range()<1||mobs.putIfAbsent(kind,definition)!=null)throw new IllegalArgumentException("Invalid/duplicate mob definition");
            }
            if(mobs.size()!=6)throw new IllegalArgumentException("Pilot mob definitions incomplete");
            var groups=new ArrayList<List<String>>();int totalMobs=0,commanders=0;
            for(var group:array(encounterSource,"groups")){
                var names=new ArrayList<String>();
                for(var entry:group.getAsJsonArray()){
                    if(!entry.isJsonPrimitive()||!entry.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Invalid mob reference");
                    String kind=entry.getAsString();var mob=mobs.get(kind);if(mob==null||!mob.enabled())throw new IllegalArgumentException("Unknown/disabled mob reference");
                    if(kind.equals("COMMANDER")){if(groups.size()!=3)throw new IllegalArgumentException("Commander must be in final group");commanders++;}names.add(kind);
                }
                if(names.isEmpty())throw new IllegalArgumentException("Empty encounter group");totalMobs+=names.size();groups.add(List.copyOf(names));
            }
            int gatekeeper=integer(encounterSource,"gatekeeperHealth"),finalBoss=integer(encounterSource,"finalBossHealth");
            if(groups.size()!=4||totalMobs>64||commanders!=1||gatekeeper<1||finalBoss<1||!mobs.get("SOLDIER").enabled())throw new IllegalArgumentException("Invalid encounter roster/bosses");
            var encounter=new EncounterDefinition(mobs,groups,scales(encounterSource,"healthScales"),scales(encounterSource,"damageScales"),gatekeeper,finalBoss);
            var enhancement=root.getAsJsonObject("enhancement");var chances=integers(enhancement,"chances");if(chances.size()!=15||chances.stream().anyMatch(value->value>100))throw new IllegalArgumentException("Invalid enhancement probabilities");
            var costs=new ArrayList<EnhancementRules.Cost>();for(var element:array(enhancement,"costs")){var cost=element.getAsJsonObject();if(integer(cost,"target")!=costs.size()+1)throw new IllegalArgumentException("Enhancement cost tiers out of order");costs.add(new EnhancementRules.Cost(integer(cost,"copper"),integer(cost,"ironstone")));}
            if(costs.isEmpty()||costs.size()>15)throw new IllegalArgumentException("Pilot cost tiers incomplete");
            var policy=new EnhancementPolicy(number(enhancement,"statIncreaseFirstFive"),number(enhancement,"statIncreaseLater"),integer(enhancement,"failureBonus"));
            if(policy.firstFive()>1||policy.later()>1||policy.failureBonus()>100)throw new IllegalArgumentException("Invalid enhancement scaling");
            return new ContentCatalog(version,root.toString(),items,quests,runs,chances,costs,bonuses,policy,encounter);
        }catch(JsonParseException|IllegalStateException|NullPointerException|ArithmeticException error){throw new IllegalArgumentException("Invalid pilot content structure",error);}
    }
    private static void references(JsonObject source,Map<String,Item> items,Set<String> skills,Map<String,Quest> quests){
        for(String id:strings(source,"itemRefs"))if(!items.containsKey(id)||(flag(source,"enabled")&&!items.get(id).enabled()))throw new IllegalArgumentException("Unknown/disabled item reference: "+id);
        for(String id:strings(source,"skillRefs"))if(!skills.contains(id))throw new IllegalArgumentException("Unknown skill reference: "+id);
        for(String id:strings(source,"requires"))if(!quests.containsKey(id)||(flag(source,"enabled")&&!quests.get(id).enabled()))throw new IllegalArgumentException("Unknown/disabled prerequisite: "+id);
    }
    private static void visit(String id,Map<String,Quest> quests,Set<String> visiting,Set<String> visited){if(visited.contains(id))return;if(visiting.size()>128||!visiting.add(id))throw new IllegalArgumentException("Cyclic/excessive quest prerequisite");for(String next:quests.get(id).requires())visit(next,quests,visiting,visited);visiting.remove(id);visited.add(id);}
    private static void put(Map<String,Item> items,Set<String> all,String id,Item item){unique(all,id);items.put(id,item);}
    private static void unique(Set<String> ids,String id){if(!ids.add(id))throw new IllegalArgumentException("Duplicate content ID: "+id);}
    private static String text(JsonObject value,String key){var field=value.get(key);if(field==null||!field.isJsonPrimitive()||!field.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Missing string: "+key);return field.getAsString();}
    private static String id(JsonObject value,String key){String id=text(value,key);if(!id.matches("[a-z0-9_.]{1,128}"))throw new IllegalArgumentException("Invalid content ID");return id;}
    private static String version(JsonObject value,String key){String version=text(value,key);if(!version.matches("[a-z0-9.-]{1,64}"))throw new IllegalArgumentException("Invalid content version");return version;}
    private static boolean flag(JsonObject value,String key){var field=value.get(key);if(field==null||!field.isJsonPrimitive()||!field.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("Missing boolean: "+key);return field.getAsBoolean();}
    private static int integer(JsonObject value,String key){return integer(value.get(key));}
    private static int integer(JsonElement value){if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Expected integer");int number=new java.math.BigDecimal(value.getAsString()).intValueExact();if(number<0)throw new IllegalArgumentException("Negative content value");return number;}
    private static double number(JsonObject value,String key){var field=value.get(key);if(field==null||!field.isJsonPrimitive()||!field.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Expected number: "+key);double number=field.getAsDouble();if(!Double.isFinite(number)||number<0)throw new IllegalArgumentException("Invalid numeric content value");return number;}
    private static List<Double> scales(JsonObject source,String key){
        var values=new ArrayList<Double>();for(var entry:array(source,key)){
            if(!entry.isJsonPrimitive()||!entry.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Invalid encounter scale");
            double value=entry.getAsDouble();if(!Double.isFinite(value)||value<=0||value>100)throw new IllegalArgumentException("Invalid encounter scale");values.add(value);
        }if(values.size()!=3)throw new IllegalArgumentException("Encounter scale tiers incomplete");return List.copyOf(values);
    }
    private static JsonArray array(JsonObject value,String key){var field=value.get(key);if(field==null||!field.isJsonArray())throw new IllegalArgumentException("Missing list: "+key);return field.getAsJsonArray();}
    private static List<Integer> integers(JsonObject value,String key){var result=new ArrayList<Integer>();for(var entry:array(value,key))result.add(integer(entry));return List.copyOf(result);}
    private static List<String> strings(JsonObject value,String key){var result=new ArrayList<String>();for(var entry:array(value,key)){if(!entry.isJsonPrimitive()||!entry.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Invalid reference list");result.add(entry.getAsString());}return List.copyOf(result);}
}
