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
    public record WeaponSlot(String hands,List<String> offhands,double share){
        public WeaponSlot {offhands=List.copyOf(offhands);if(!Set.of("ONE","TWO","DUAL").contains(hands)||!Double.isFinite(share)||share<=0||share>1||new HashSet<>(offhands).size()!=offhands.size()||!Set.of("shield","dagger","talisman","dual_axes").containsAll(offhands)||hands.equals("TWO")&&!offhands.isEmpty())throw new IllegalArgumentException("Invalid weapon slot rule");}
    }
    public record RefinementGrade(String name,double bonus) {}
    public record RefinementPolicy(Set<String> families,List<Integer> attemptCopper,List<Integer> keepPercent) { public RefinementPolicy {families=Set.copyOf(families);attemptCopper=List.copyOf(attemptCopper);keepPercent=List.copyOf(keepPercent);} }
    private final RefinementPolicy refinementPolicy;
    public RefinementPolicy refinementPolicy(){return refinementPolicy;}
    public void requireRefinementPath(String id,int grade){int dot=id.lastIndexOf('.');if(refinementPolicy==null||grade<0||grade>=5||dot<0||!items.containsKey(id)||!items.get(id).enabled()||!refinementPolicy.families().contains(id.substring(0,dot)))throw new IllegalArgumentException("Equipment has no next refinement path.");}
    private final List<RefinementGrade> refinementGrades;
    public List<RefinementGrade> refinementGrades(){return refinementGrades;}
    public double refinementBonus(int grade){if(grade<0||grade>5)throw new IllegalArgumentException("Invalid refinement grade");return refinementGrades.isEmpty()?0:refinementGrades.get(grade).bonus();}
    private final Map<String,WeaponSlot> weaponSlots;
    private final Map<String,BranchResource> branchResources;
    public BranchResource branchResource(String weaponProfile){if(weaponProfile==null||weaponProfile.isEmpty())return BranchResource.NONE;var value=branchResources.get(weaponProfile);if(value==null)throw new IllegalArgumentException("Unknown weapon resource path");return value;}
    public Map<String,BranchResource> branchResources(){return branchResources;}
    public Map<String,WeaponSlot> weaponSlots(){return weaponSlots;}
    public WeaponSlot weaponSlot(String itemId){if(!itemId.startsWith("pilot.weapon."))return null;int end=itemId.lastIndexOf('.');return end<13?null:weaponSlots.get(itemId.substring(13,end));}
    public record Quest(String id,String version,String target,int goal,int experience,boolean enabled,List<String> requires){}
    public record Run(String id,String version,int minPlayers,int maxPlayers,boolean enabled,List<String> requires){}
    public record Mob(String id,String kind,String label,int health,int physicalDefense,int magicDefense,int damage,int interval,boolean magic,int range,boolean enabled){}
    public record EncounterDefinition(Map<String,Mob> mobs,List<List<String>> groups,List<Double> healthScales,List<Double> damageScales,int gatekeeperHealth,int finalBossHealth,double mobDamageScale,double gatekeeperDamageScale){
        public EncounterDefinition {mobs=Map.copyOf(mobs);groups=groups.stream().map(List::copyOf).toList();healthScales=List.copyOf(healthScales);damageScales=List.copyOf(damageScales);}
        public Mob mob(String kind){var value=mobs.get(kind);if(value==null||!value.enabled())throw new IllegalArgumentException("Unknown/disabled encounter mob");return value;}
    }
    public record Skill(String id,String contentVersion,CharacterProfession profession,SkillDefinition.Kind kind,String name,String description,List<String> weaponProfiles,int unlockLevel,int skillPoints,int maxRank,int energy,int cooldownTicks,Map<String,RankValue> parameters){
        public Skill {weaponProfiles=List.copyOf(weaponProfiles);parameters=Map.copyOf(parameters);}
        public double parameter(String key,int rank){var value=parameters.get(key);if(value==null)throw new IllegalArgumentException("Unknown skill parameter: "+key);return value.at(rank);}
    }
    public record BranchResource(String weaponProfile,String resourceKind,String label,int maximum,int initial,int gainOnHit,int gainOnPreciseDefense,int preciseDefenseSpend,int preciseDefenseDiscountPercent,int skillSpend,int skillCooldownTicks,int staminaSpend){
        public static final BranchResource NONE=new BranchResource("","","",0,0,0,0,0,0,0,0,0);
        public BranchResource{
            if(weaponProfile==null||resourceKind==null||label==null)throw new IllegalArgumentException("Invalid branch resource");
            if(initial<0||maximum<initial||gainOnHit<0||gainOnPreciseDefense<0||preciseDefenseSpend<0||skillSpend<0||skillCooldownTicks<0||staminaSpend<0)throw new IllegalArgumentException("Invalid branch resource");
            if(preciseDefenseDiscountPercent!=0&&(!weaponProfile.equals("two_handed_sword")||preciseDefenseDiscountPercent!=75))throw new IllegalArgumentException("Precise defense discount is two-handed only");
            if(resourceKind.isEmpty()!=(maximum==0))throw new IllegalArgumentException("Empty branch resource must stay at zero");
        }
        public boolean unused(){return resourceKind.isEmpty();}
        public int preciseCost(){int discount=weaponProfile.equals("two_handed_sword")?preciseDefenseDiscountPercent:0;return Math.floorDiv(Math.multiplyExact(preciseDefenseSpend,100-discount),100);}
    }
    public record RankValue(double first,double last){public double at(int rank){return kr.dalmaru.combat.CombatRules.rank(first,last,rank);}}
    private final List<Skill> skillDefinitions;
    private final BranchSkills branchSkills;
    public List<Skill> skills(){return skillDefinitions;}
    public BranchSkills branchSkills(){return branchSkills;}
    public Skill skill(String id){var branch=branchSkills.view(id);if(branch!=null)return branch;return skillDefinitions.stream().filter(skill->skill.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown skill: "+id));}
    private final BeaconMechanics mechanics;
    public BeaconMechanics mechanics(){return mechanics;}
    private final EncounterDefinition encounter;
    public EncounterDefinition encounter(){return encounter;}
    public record Display(String name,String description){}
    private final Map<String,Display> displays;
    public Display display(String id){return displays.getOrDefault(id,new Display(id,""));}
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
    private ContentCatalog(String version,String snapshot,Map<String,Item> items,Map<String,Quest> quests,Map<String,Run> runs,List<Integer> chances,List<EnhancementRules.Cost> costs,EquipmentBonuses bonuses,EnhancementPolicy enhancementPolicy,EncounterDefinition encounter,List<Skill> skillDefinitions,Map<String,Display> displays,Map<String,WeaponSlot> weaponSlots,List<RefinementGrade> refinementGrades,RefinementPolicy refinementPolicy,BeaconMechanics mechanics,Map<String,BranchResource> branchResources,BranchSkills branchSkills){
        this.branchSkills=branchSkills;
        this.branchResources=Map.copyOf(branchResources);
        this.mechanics=mechanics;
        this.refinementPolicy=refinementPolicy;
        this.refinementGrades=List.copyOf(refinementGrades);
        this.weaponSlots=Map.copyOf(weaponSlots);
        this.displays=Map.copyOf(displays);this.skillDefinitions=List.copyOf(skillDefinitions);this.encounter=encounter;this.bonuses=bonuses;this.enhancementPolicy=enhancementPolicy;
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
            int schema=integer(root,"schemaVersion");
            if((schema<1||schema>5)||!flag(root,"enabled"))throw new IllegalArgumentException("Unsupported/disabled content schema");
            String version=version(root,"contentVersion");Set<String> all=new HashSet<>(),skills=new HashSet<>(),knownSkills=new HashSet<>();
            if(schema==1&&!version.equals("pilot-j02-1"))throw new IllegalArgumentException("Unknown schema1 skill revision");
            knownSkills.addAll(SkillDefinition.capabilities().keySet());
            var skillDefinitions=new ArrayList<Skill>();
            var legacy=schema==1?LegacySkills.VALUE:null;
            for(var entry:array(root,"skills")){
                var skill=entry.getAsJsonObject();String id=id(skill,"id");unique(all,id);
                if(!knownSkills.contains(id)||!flag(skill,"enabled"))throw new IllegalArgumentException("Unsupported/disabled pilot skill: "+id);skills.add(id);
                var definition=schema==1?legacy.get(id):skill;
                if(definition==null)throw new IllegalArgumentException("Unknown legacy skill");skillDefinitions.add(parseSkill(definition,id,schema));
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
            var refinementGrades=new ArrayList<RefinementGrade>();
            if(equipment.has("refinementGrades")){
                double previous=0;
                for(var element:array(equipment,"refinementGrades")){var grade=element.getAsJsonObject();double bonus=number(grade,"bonus");if(integer(grade,"grade")!=refinementGrades.size()||bonus<previous||bonus>1||refinementGrades.isEmpty()&&bonus!=0)throw new IllegalArgumentException("Invalid refinement grade table");refinementGrades.add(new RefinementGrade(text(grade,"name"),bonus));previous=bonus;}
                if(refinementGrades.size()!=6)throw new IllegalArgumentException("Refinement grade table incomplete");
            }
            var weaponSlots=new LinkedHashMap<String,WeaponSlot>();
            if(equipment.has("weaponSlots")){
                var rules=equipment.getAsJsonObject("weaponSlots");
                for(var entry:rules.entrySet()){var rule=entry.getValue().getAsJsonObject();var slotRule=new WeaponSlot(text(rule,"hands"),strings(rule,"offhands"),number(rule,"share"));if(slotRule.hands().equals("DUAL")&&!slotRule.offhands().equals(List.of(entry.getKey())))throw new IllegalArgumentException("Dual weapons must use the same family");weaponSlots.put(entry.getKey(),slotRule);}
                if(!weaponSlots.keySet().equals(weaponNames))throw new IllegalArgumentException("Weapon slot rules incomplete");
                var offhandNames=new HashSet<String>();
                for(var element:array(equipment,"offhands")){var offhand=element.getAsJsonObject();String name=id(offhand,"id");if(!offhandNames.add(name)||text(offhand,"name").length()>128)throw new IllegalArgumentException("Invalid offhand definition");for(int level:levels)put(items,all,"pilot.offhand."+name+"."+level,new Item(level,1,"",0,0,0,0,false,1,0,true));}
                if(!offhandNames.equals(Set.of("shield","dagger","talisman")))throw new IllegalArgumentException("Offhand types incomplete");
            }
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
            var encounter=new EncounterDefinition(mobs,groups,scales(encounterSource,"healthScales"),scales(encounterSource,"damageScales"),gatekeeper,finalBoss,number(encounterSource,"mobDamageScale"),number(encounterSource,"gatekeeperDamageScale"));
            if(encounter.mobDamageScale()<=0||encounter.mobDamageScale()>1||encounter.gatekeeperDamageScale()<=0||encounter.gatekeeperDamageScale()>1)throw new IllegalArgumentException("Invalid encounter damage multiplier");
            BeaconMechanics mechanics=null;
            if(schema>=5){
                var sourceMechanics=root.getAsJsonObject("mechanics");
                if(!flag(sourceMechanics,"temporary"))throw new IllegalArgumentException("P4 mechanics must be labelled temporary");
                var b=sourceMechanics.getAsJsonObject("brazier");var boss=sourceMechanics.getAsJsonObject("boss");
                mechanics=new BeaconMechanics(new BeaconMechanics.Brazier(integer(b,"channelTicks"),number(b,"cancelDistance"),number(b,"interactionRadius"),integer(b,"overlapTicks"),integers(b,"counts"),integers(b,"simultaneous"),integer(b,"idleTicks"),integer(b,"warningTicks"),integer(b,"pulseTicks"),number(b,"maximumHealthFraction")),
                    new BeaconMechanics.Boss(integer(boss,"gatekeeperPosture"),integer(boss,"beaconPosture"),integer(boss,"wallPostureDamage"),scales(boss,"postureScales"),number(boss,"trainingPostureScale"),integer(boss,"collapseTicks"),number(boss,"collapsedDamageScale"),integer(boss,"immunityTicks"),endpoints(boss,"risingStrikePosture"),optionalNumber(boss,"trialHealthScale",1),optionalNumber(boss,"extremeHealthScale",1),optionalNumber(boss,"trialDamageScale",1),optionalNumber(boss,"extremeDamageScale",1),optionalNumber(boss,"trialPostureScale",1),optionalNumber(boss,"extremePostureScale",1)),integer(sourceMechanics,"minimumTelegraphTicks"));
            }else if(version.startsWith("pilot-p4-"))throw new IllegalArgumentException("P4 requires mechanics schema");
            RefinementPolicy refinementPolicy=null;
            if(equipment.has("refinement")){
                var refinementSource=equipment.getAsJsonObject("refinement");var families=strings(refinementSource,"families");var attempts=integers(refinementSource,"attemptCopper");var keep=integers(refinementSource,"keepPercent");
                var known=new HashSet<String>();for(String item:items.keySet()){int dot=item.lastIndexOf('.');if(dot>0)known.add(item.substring(0,dot));}
                if(!flag(refinementSource,"temporary")||families.isEmpty()||new HashSet<>(families).size()!=families.size()||!known.containsAll(families)||attempts.size()!=5||keep.size()!=5||attempts.stream().anyMatch(value->value<1)||keep.stream().anyMatch(value->value>100))throw new IllegalArgumentException("Invalid refinement policy");
                refinementPolicy=new RefinementPolicy(new HashSet<>(families),attempts,keep);
            }
            var enhancement=root.getAsJsonObject("enhancement");var chances=integers(enhancement,"chances");if(chances.size()!=15||chances.stream().anyMatch(value->value>100))throw new IllegalArgumentException("Invalid enhancement probabilities");
            var costs=new ArrayList<EnhancementRules.Cost>();for(var element:array(enhancement,"costs")){var cost=element.getAsJsonObject();if(integer(cost,"target")!=costs.size()+1)throw new IllegalArgumentException("Enhancement cost tiers out of order");costs.add(new EnhancementRules.Cost(integer(cost,"copper"),integer(cost,"ironstone")));}
            if(costs.isEmpty()||costs.size()>15)throw new IllegalArgumentException("Pilot cost tiers incomplete");
            var policy=new EnhancementPolicy(number(enhancement,"statIncreaseFirstFive"),number(enhancement,"statIncreaseLater"),integer(enhancement,"failureBonus"));
            if(policy.firstFive()>1||policy.later()>1||policy.failureBonus()>100)throw new IllegalArgumentException("Invalid enhancement scaling");
            var displays=displays(root,schema,items,skillDefinitions,quests,runs,mobs);
            return new ContentCatalog(version,root.toString(),items,quests,runs,chances,costs,bonuses,policy,encounter,skillDefinitions,displays,weaponSlots,refinementGrades,refinementPolicy,mechanics,branchResources(root),BranchSkills.parse(root,version));
        }catch(JsonParseException|IllegalStateException|NullPointerException|ArithmeticException error){throw new IllegalArgumentException("Invalid pilot content structure",error);}
    }
    private static Map<String,Display> displays(JsonObject root,int schema,Map<String,Item> items,List<Skill> skills,Map<String,Quest> quests,Map<String,Run> runs,Map<String,Mob> mobs){
        var result=new LinkedHashMap<String,Display>();
        if(schema<4){
            for(String id:items.keySet())result.put(id,new Display(id,""));
            for(var skill:skills)result.put(skill.id(),new Display(skill.name(),skill.description()));
            for(var mob:mobs.values())result.put(mob.id(),new Display(mob.label(),""));
            for(String id:quests.keySet())result.put(id,new Display(id.equals("pilot1.zombie_hunt")?"\uc880\ube44 \ud1a0\ubc8c":id,""));
            for(String id:runs.keySet())result.put(id,new Display(id.equals("run.bongsudae")?"\ud3d0\ubd09\uc218\ub300":id,""));
            return result;
        }
        putDisplay(result,id(root,"id"),display(root,schema));
        var equipment=root.getAsJsonObject("equipment");var enhancement=root.getAsJsonObject("enhancement");
        if(equipment.has("weaponSlots"))for(var value:array(equipment,"offhands")){var offhand=value.getAsJsonObject();for(int level:integers(equipment,"levels"))putDisplay(result,"pilot.offhand."+id(offhand,"id")+"."+level,new Display(text(offhand,"name")+" "+level,"보조칸"));}
        if(!flag(equipment,"enabled")||!flag(enhancement,"enabled"))throw new IllegalArgumentException("Required content table disabled");
        putDisplay(result,id(equipment,"id"),display(equipment,1));putDisplay(result,id(enhancement,"id"),display(enhancement,1));
        for(String key:List.of("weapons","armor","parts","accessories"))for(var value:array(equipment,key)){
            var node=value.getAsJsonObject();if(key.equals("parts")&&!flag(node,"enabled"))throw new IllegalArgumentException("Unsupported disabled armor part");
            putDisplay(result,id(node,"id"),display(node,1));
        }
        for(var value:array(equipment,"weapons")){var node=value.getAsJsonObject();var info=result.get(id(node,"id"));for(int level:integers(equipment,"levels"))putDisplay(result,"pilot.weapon."+id(node,"id")+"."+level,new Display(info.name()+" "+level,info.description()));}
        for(var value:array(equipment,"armor")){var node=value.getAsJsonObject();var info=result.get(id(node,"id"));for(var partValue:array(equipment,"parts")){var part=partValue.getAsJsonObject();var partInfo=result.get(id(part,"id"));for(int level:integers(equipment,"levels"))putDisplay(result,"pilot.armor."+id(node,"id")+"."+id(part,"id")+"."+level,new Display(info.name()+" "+partInfo.name()+" "+level,info.description()));}}
        for(var value:array(equipment,"accessories")){var node=value.getAsJsonObject();var info=result.get(id(node,"id"));for(int level:integers(equipment,"levels"))putDisplay(result,"pilot.accessory."+id(node,"id")+"."+level,new Display(info.name()+" "+level,info.description()));}
        for(var value:array(root,"skills")){var node=value.getAsJsonObject();putDisplay(result,id(node,"id"),display(node,2));}
        for(String key:List.of("quests","runs"))for(var value:array(root,key)){var node=value.getAsJsonObject();if(!version(node,"contentVersion").equals(version(node,"version")))throw new IllegalArgumentException("Display/content revision mismatch");putDisplay(result,id(node,"id"),display(node,1));}
        for(var value:array(root.getAsJsonObject("encounter"),"mobs")){var node=value.getAsJsonObject();var info=display(node,1);if(!info.name().equals(text(node,"label")))throw new IllegalArgumentException("Mob label/display mismatch");putDisplay(result,id(node,"id"),info);}
        return result;
    }
    private static Display display(JsonObject node,int expectedSchema){
        if(integer(node,"schemaVersion")!=expectedSchema)throw new IllegalArgumentException("Unsupported display schema");
        version(node,"contentVersion");flag(node,"enabled");var source=node.getAsJsonObject("display");String name=text(source,"name"),description=text(source,"description");
        if(name.isBlank()||name.length()>128||description.isBlank()||description.length()>4096)throw new IllegalArgumentException("Invalid display metadata");return new Display(name,description);
    }
    private static void putDisplay(Map<String,Display> entries,String id,Display display){if(entries.putIfAbsent(id,display)!=null)throw new IllegalArgumentException("Duplicate display ID: "+id);}
    private static final class LegacySkills {
        static final Map<String,JsonObject> VALUE=load();
        private static Map<String,JsonObject> load(){
            try(var stream=ContentCatalog.class.getResourceAsStream("/content/schema1-skills.json")){
                if(stream==null)throw new IllegalStateException("Missing schema1 skill definitions");
                var result=new LinkedHashMap<String,JsonObject>();
                for(var entry:JsonParser.parseString(new String(stream.readNBytes(1_048_576),StandardCharsets.UTF_8)).getAsJsonArray()){var value=entry.getAsJsonObject();if(result.putIfAbsent(id(value,"id"),value)!=null)throw new IllegalArgumentException("Duplicate legacy skill");}
                return Map.copyOf(result);
            }catch(IOException error){throw new IllegalStateException("Cannot load schema1 skill definitions",error);}
        }
    }
    private static Skill parseSkill(JsonObject source,String expectedId,int bundleSchema){
        if(integer(source,"schemaVersion")!=(bundleSchema>=3?2:1)||!flag(source,"enabled")||!id(source,"id").equals(expectedId))throw new IllegalArgumentException("Invalid skill definition");
        var profession=CharacterProfession.valueOf(text(source,"profession"));var kind=SkillDefinition.Kind.valueOf(text(source,"kind"));
        if(SkillDefinition.capabilities().get(expectedId)!=profession||kind!=SkillDefinition.supportedKind(expectedId))throw new IllegalArgumentException("Unsupported skill profession/kind");
        var display=source.getAsJsonObject("display");var unlock=source.getAsJsonObject("unlock");var cast=source.getAsJsonObject("cast");
        String name=text(display,"name"),description=text(display,"description");
        if(name.isBlank()||name.length()>128||description.isBlank()||description.length()>4096)throw new IllegalArgumentException("Invalid skill display");
        int level=integer(unlock,"level"),points=integer(unlock,"skillPoints"),maxRank=integer(unlock,"maxRank"),energy=integer(cast,"energy"),cooldown=integer(cast,"cooldownTicks");
        if(level!=10||points!=1||maxRank!=5||kind==SkillDefinition.Kind.ACTIVE&&cooldown<1||kind==SkillDefinition.Kind.PASSIVE&&(energy!=0||cooldown!=0))throw new IllegalArgumentException("Unsupported skill unlock/cast policy");
        var weaponProfiles=strings(source,"weaponProfiles");
        Set<String> allowed=switch(profession){case WARRIOR->Set.of("two_handed_sword","sword_shield");case ARCHER->Set.of("bow","crossbow");case MAGE->Set.of("fan","ritual_sword");case ROGUE->Set.of("dual_axes","pirate_sword");};
        if(new HashSet<>(weaponProfiles).size()!=weaponProfiles.size()||!allowed.equals(new HashSet<>(weaponProfiles)))throw new IllegalArgumentException("Invalid skill weapon compatibility");
        var baseline=LegacySkills.VALUE.get(expectedId).getAsJsonObject("parameters");
        var parameterSource=bundleSchema>=3?source.getAsJsonObject("parameters"):baseline;
        if(parameterSource==null||!parameterSource.keySet().equals(baseline.keySet()))throw new IllegalArgumentException("Skill parameter set differs from capability");
        var parameters=new LinkedHashMap<String,RankValue>();
        for(var entry:parameterSource.entrySet()){
            var pair=entry.getValue().getAsJsonArray();if(pair.size()!=2)throw new IllegalArgumentException("Invalid rank endpoints");
            var wrapper=new JsonObject();wrapper.add("first",pair.get(0));wrapper.add("last",pair.get(1));
            double first=number(wrapper,"first"),last=number(wrapper,"last");
            if(first>1_000_000||last>1_000_000||last<first||entry.getKey().endsWith("Ticks")&&(first<1||first!=Math.rint(first)||last!=Math.rint(last))||entry.getKey().endsWith("Bonus")&&(first>1||last>1)||entry.getKey().endsWith("Slow")&&last>1||entry.getKey().equals("aimReduction")&&last>=1||entry.getKey().equals("movementBlocks")&&(first<1||last>16))throw new IllegalArgumentException("Invalid skill effect values");
            parameters.put(entry.getKey(),new RankValue(first,last));
        }
        return new Skill(expectedId,version(source,"contentVersion"),profession,kind,name,description,weaponProfiles,level,points,maxRank,energy,cooldown,parameters);
    }
    private static void references(JsonObject source,Map<String,Item> items,Set<String> skills,Map<String,Quest> quests){
        for(String id:strings(source,"itemRefs"))if(!items.containsKey(id)||(flag(source,"enabled")&&!items.get(id).enabled()))throw new IllegalArgumentException("Unknown/disabled item reference: "+id);
        for(String id:strings(source,"skillRefs"))if(!skills.contains(id))throw new IllegalArgumentException("Unknown skill reference: "+id);
        for(String id:strings(source,"requires"))if(!quests.containsKey(id)||(flag(source,"enabled")&&!quests.get(id).enabled()))throw new IllegalArgumentException("Unknown/disabled prerequisite: "+id);
    }
    private static void visit(String id,Map<String,Quest> quests,Set<String> visiting,Set<String> visited){if(visited.contains(id))return;if(visiting.size()>128||!visiting.add(id))throw new IllegalArgumentException("Cyclic/excessive quest prerequisite");for(String next:quests.get(id).requires())visit(next,quests,visiting,visited);visiting.remove(id);visited.add(id);}
    private static void put(Map<String,Item> items,Set<String> all,String id,Item item){unique(all,id);items.put(id,item);}
    private static void unique(Set<String> ids,String id){if(!ids.add(id))throw new IllegalArgumentException("Duplicate content ID: "+id);}
    private static Map<String,BranchResource> branchResources(JsonObject root){
        if(!root.has("branchResources"))return Map.of();
        var source=root.getAsJsonObject("branchResources");
        if(source==null||!flag(source,"temporary"))throw new IllegalArgumentException("Branch resources must be labelled temporary");
        var paths=source.getAsJsonObject("paths");
        var expected=List.of("two_handed_sword","sword_shield","bow","crossbow","fan","ritual_sword","dual_axes","pirate_sword");
        if(paths==null||!paths.keySet().equals(new HashSet<>(expected)))throw new IllegalArgumentException("Branch resource paths must match the eight weapons");
        var result=new LinkedHashMap<String,BranchResource>();
        for(String profile:expected){
            var row=paths.getAsJsonObject(profile);
            String kind=text(row,"resourceKind");
            String required=switch(profile){case "two_handed_sword"->"sword_force";case "sword_shield"->"guard_force";case "bow","crossbow"->"reload";case "fan","ritual_sword"->"seal";default->"";};
            if(!kind.equals(required))throw new IllegalArgumentException("Weapon path resource mismatch");
            String label=text(row,"label");
            if(!kind.isEmpty()&&!label.startsWith("[임시]"))throw new IllegalArgumentException("Branch resource label must be temporary");
            int maximum=integer(row,"maximum"),initial=integer(row,"initial"),discount=integer(row,"preciseDefenseDiscountPercent");
            if(initial!=0)throw new IllegalArgumentException("Branch resource initial state must be zero");
            if(profile.equals("two_handed_sword")&&(maximum!=100||discount!=75))throw new IllegalArgumentException("Two-handed sword force cap or discount drifted");
            if(!profile.equals("two_handed_sword")&&discount!=0)throw new IllegalArgumentException("Precise defense discount is two-handed only");
            result.put(profile,new BranchResource(profile,kind,label,maximum,initial,integer(row,"gainOnHit"),integer(row,"gainOnPreciseDefense"),integer(row,"preciseDefenseSpend"),discount,integer(row,"skillSpend"),integer(row,"skillCooldownTicks"),integer(row,"staminaSpend")));
        }
        return Map.copyOf(result);
    }
    private static String text(JsonObject value,String key){var field=value.get(key);if(field==null||!field.isJsonPrimitive()||!field.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Missing string: "+key);return field.getAsString();}
    private static String id(JsonObject value,String key){String id=text(value,key);if(!id.matches("[a-z0-9_.]{1,128}"))throw new IllegalArgumentException("Invalid content ID");return id;}
    private static String version(JsonObject value,String key){String version=text(value,key);if(!version.matches("[a-z0-9.-]{1,64}"))throw new IllegalArgumentException("Invalid content version");return version;}
    private static boolean flag(JsonObject value,String key){var field=value.get(key);if(field==null||!field.isJsonPrimitive()||!field.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("Missing boolean: "+key);return field.getAsBoolean();}
    private static int integer(JsonObject value,String key){return integer(value.get(key));}
    private static int integer(JsonElement value){if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Expected integer");int number=new java.math.BigDecimal(value.getAsString()).intValueExact();if(number<0)throw new IllegalArgumentException("Negative content value");return number;}
    private static double number(JsonObject value,String key){var field=value.get(key);if(field==null||!field.isJsonPrimitive()||!field.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Expected number: "+key);double number=field.getAsDouble();if(!Double.isFinite(number)||number<0)throw new IllegalArgumentException("Invalid numeric content value");return number;}
    private static double optionalNumber(JsonObject value,String key,double fallback){return value.get(key)==null?fallback:number(value,key);}
    private static List<Double> endpoints(JsonObject source,String key){var values=new ArrayList<Double>();for(var entry:array(source,key)){if(!entry.isJsonPrimitive()||!entry.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Invalid endpoint");double value=entry.getAsDouble();if(!Double.isFinite(value)||value<=0||value>10000)throw new IllegalArgumentException("Invalid endpoint");values.add(value);}return values;}
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
