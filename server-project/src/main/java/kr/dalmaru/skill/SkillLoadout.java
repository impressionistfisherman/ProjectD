package kr.dalmaru.skill;

import java.util.*;
import kr.dalmaru.account.CharacterProfession;

public record SkillLoadout(UUID characterId,long revision,List<String> slots) {
    public SkillLoadout {
        Objects.requireNonNull(characterId);
        if(revision<0||slots.size()!=5)throw new IllegalArgumentException("Invalid loadout.");
        slots=List.copyOf(slots);
    }
    public static List<String> defaults(CharacterProfession profession,Map<String,Integer> ranks){
        var slots=new ArrayList<String>();
        for(var skill:SkillDefinition.forProfession(profession))if(skill.kind()==SkillDefinition.Kind.ACTIVE&&slots.size()<4)slots.add(skill.id());
        while(slots.size()<5)slots.add("");return List.copyOf(slots);
    }
    public static void validate(CharacterProfession profession,Map<String,Integer> ranks,List<String> slots,List<String> previous){
        validate(profession,ranks,slots,previous,"");
    }
    public static void validate(CharacterProfession profession,Map<String,Integer> ranks,List<String> slots,List<String> previous,String branch){
        if(slots.size()!=5)throw new IllegalArgumentException("Exactly five slots required.");
        Set<String>seen=new HashSet<>();
        for(int index=0;index<5;index++){
            String id=slots.get(index);if(id.isEmpty())continue;
            var skill=SkillDefinition.find(profession,id);
            var spec=kr.dalmaru.content.ContentCatalog.current().branchSkills().get(id);
            if(spec!=null&&!spec.branch().equals(branch==null?"":branch))throw new IllegalArgumentException("Branch skill does not match the confirmed path.");
            // Locked initial skills may remain in place, but cannot be newly equipped or moved.
            if(!seen.add(id)||(ranks.getOrDefault(id,0)<1&&!id.equals(previous.get(index))))
                throw new IllegalArgumentException("Unlearned, duplicate or invalid slot skill.");
            requireSlot(skill.kind(),index);
        }
    }
    public static boolean occupiesSlot(SkillDefinition.Kind kind){
        return kind==SkillDefinition.Kind.ACTIVE||kind==SkillDefinition.Kind.ULTIMATE;
    }
    public static void requireSlot(SkillDefinition.Kind kind,int index){
        if(index<0||index>4)throw new IllegalArgumentException("Invalid skill slot.");
        if(!occupiesSlot(kind))throw new IllegalArgumentException("Traits and passives do not use a loadout slot.");
        if(kind!=(index==4?SkillDefinition.Kind.ULTIMATE:SkillDefinition.Kind.ACTIVE))throw new IllegalArgumentException("Ultimate skills use only the fifth slot.");
    }
    public String skill(int slot){
        if(slot<1||slot>5)throw new IllegalArgumentException("Invalid skill slot.");return slots.get(slot-1);
    }
}
