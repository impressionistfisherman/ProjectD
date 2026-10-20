package kr.dalmaru.content;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;
import kr.dalmaru.account.StarterSupply;

public final class StoryCatalog {
    public enum CombatScope { FIELD,PROFESSION_VILLAGE,FIRST_STEP_CLEAR }
    public enum Proof { PROFESSION_VILLAGE,PROFESSION,PROFESSION_TRIAL,INVESTIGATION,FIRST_STEP_CLEAR }
    public enum RewardOwner { STORY,S07 }
    public record Chapter(String id,String title,int questXP,int combatXP,int targetLevel,List<String> npcs,CombatScope combatScope,Set<Proof> proofs,String supplyGrant,String dialogue,RewardOwner rewardOwner) {
        public Chapter {npcs=List.copyOf(npcs);proofs=Set.copyOf(proofs);var advancement=kr.dalmaru.account.AdvancementStage.forQuest(id);if(id.isBlank()||title.isBlank()||questXP<(advancement==null?1:0)||combatXP<0||targetLevel<2||(advancement==null?targetLevel>20:targetLevel!=advancement.requiredLevel()||rewardOwner!=RewardOwner.STORY||!supplyGrant.isEmpty())||npcs.isEmpty()||!Set.of("",StarterSupply.PROFESSION,StarterSupply.INVESTIGATION).contains(supplyGrant))throw new IllegalArgumentException("Invalid story chapter");}
    }
    private static final StoryCatalog CURRENT=load();
    private final String version;
    private final List<Chapter> chapters;
    private StoryCatalog(String version,List<Chapter> chapters){this.version=version;this.chapters=List.copyOf(chapters);}
    public static StoryCatalog current(){return CURRENT;}
    public String version(){return version;}
    public List<Chapter> chapters(){return chapters;}
    public static StoryCatalog parse(String source) {
        if(source.length()>131072)throw new IllegalArgumentException("Story content too large");
        var root=JsonParser.parseString(source).getAsJsonObject();String version=root.get("contentVersion").getAsString();
        if(version.isBlank())throw new IllegalArgumentException("Story version required");
        var chapters=new ArrayList<Chapter>();var ids=new HashSet<String>();
        var registered=VillageCatalog.current().stream().flatMap(v->v.npcs().stream()).map(VillageCatalog.Npc::id).collect(java.util.stream.Collectors.toSet());
        for(var entry:root.getAsJsonArray("chapters")){
            var row=entry.getAsJsonObject();var npcs=new ArrayList<String>();for(var npc:row.getAsJsonArray("npcs"))npcs.add(npc.getAsString());
            var proofs=EnumSet.noneOf(Proof.class);for(var proof:row.getAsJsonArray("proofs"))proofs.add(Proof.valueOf(proof.getAsString()));
            var chapter=new Chapter(row.get("id").getAsString(),row.get("title").getAsString(),row.get("questXP").getAsBigDecimal().intValueExact(),row.get("combatXP").getAsBigDecimal().intValueExact(),row.get("targetLevel").getAsBigDecimal().intValueExact(),npcs,CombatScope.valueOf(row.get("combatScope").getAsString()),proofs,row.get("supplyGrant").getAsString(),row.get("dialogue").getAsString(),RewardOwner.valueOf(row.get("rewardOwner").getAsString()));
            if(chapter.rewardOwner()==RewardOwner.S07&&(!chapter.supplyGrant().isEmpty()||!chapter.proofs().contains(Proof.INVESTIGATION)))throw new IllegalArgumentException("S07 owns investigation XP and supplies");
            if(!ids.add(chapter.id())||!registered.containsAll(npcs))throw new IllegalArgumentException("Duplicate chapter or unknown NPC");chapters.add(chapter);
        }
        if(chapters.isEmpty())throw new IllegalArgumentException("Story chapters required");
        var milestones=chapters.stream().filter(chapter->kr.dalmaru.account.AdvancementStage.forQuest(chapter.id())!=null).map(Chapter::id).toList();
        var expected=Arrays.stream(kr.dalmaru.account.AdvancementStage.values()).map(kr.dalmaru.account.AdvancementStage::questId).toList();
        if(!milestones.isEmpty()&&(!milestones.equals(expected)||!chapters.subList(chapters.size()-milestones.size(),chapters.size()).stream().map(Chapter::id).toList().equals(expected)))throw new IllegalArgumentException("Advancement chapters must end in 30/50/60 order");
        return new StoryCatalog(version,chapters);
    }
    private static StoryCatalog load(){
        try(var stream=StoryCatalog.class.getResourceAsStream("/content/story.json")){
            if(stream==null)throw new IllegalStateException("Missing story definitions");
            return parse(new String(stream.readNBytes(131073),StandardCharsets.UTF_8));
        }catch(IOException error){throw new IllegalStateException("Cannot load story definitions",error);}
    }
}
