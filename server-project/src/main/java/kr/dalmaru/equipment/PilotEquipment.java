package kr.dalmaru.equipment;

import java.util.List;
import java.util.Map;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.account.CharacterProfile;

/** Explicit item definitions from PLT-003 section 4; no items are created here. */
public final class PilotEquipment {
    private static final Map<String,ContentCatalog.Item> ITEMS=ContentCatalog.current().items();
    private PilotEquipment() { }

    public static boolean tradable(String itemId) { return kr.dalmaru.account.StarterSupply.tradable(itemId); }
    public static int slot(String itemId) {
        ContentCatalog.Item item=ITEMS.get(itemId);
        if(item==null||!item.enabled()) throw new IllegalArgumentException("Undefined equipment cannot be equipped.");
        return item.slot();
    }
    private static String handType(String id){int end=id.lastIndexOf('.');return end<0?"":id.substring(id.lastIndexOf('.',end-1)+1,end);}
    public static int destination(EquipmentRecord item,List<EquipmentRecord> equipped){
        int target=slot(item.itemId());var content=ContentCatalog.current();var rule=content.weaponSlot(item.itemId());
        if(item.slotId()>=0)return item.slotId();
        var main=equipped.stream().filter(record->record.slotId()==0).findFirst().orElse(null);
        if(rule!=null&&rule.hands().equals("DUAL")&&main!=null&&handType(main.itemId()).equals(handType(item.itemId()))&&equipped.stream().noneMatch(record->record.slotId()==1))target=1;
        if(target==1&&main!=null){var mainRule=content.weaponSlot(main.itemId());if(mainRule!=null&&!mainRule.hands().equals("TWO")&&!mainRule.offhands().contains(handType(item.itemId())))throw new IllegalArgumentException("보조 장비와 주무기의 계열이 맞지 않습니다.");}
        return target;
    }
    public static boolean displaced(EquipmentRecord existing,EquipmentRecord incoming,int target,boolean equip){
        if(existing.equipmentId().equals(incoming.equipmentId()))return false;
        var content=ContentCatalog.current();var rule=content.weaponSlot(incoming.itemId());
        if(!equip)return incoming.slotId()==0&&existing.slotId()==1&&rule!=null&&rule.hands().equals("DUAL");
        if(existing.slotId()==target)return true;
        if(target==0&&existing.slotId()==1&&rule!=null)return rule.hands().equals("TWO")||!rule.offhands().contains(handType(existing.itemId()));
        var oldRule=content.weaponSlot(existing.itemId());return target==1&&existing.slotId()==0&&oldRule!=null&&oldRule.hands().equals("TWO");
    }
    public static boolean hasShield(List<EquipmentRecord> records,ContentCatalog content){
        return content.weaponSlots().isEmpty()?records.stream().anyMatch(item->item.itemId().contains(".sword_shield.")):records.stream().anyMatch(item->item.slotId()==1&&item.itemId().startsWith("pilot.offhand.shield."));
    }
    public static boolean canGuard(List<EquipmentRecord> records,ContentCatalog content){return hasShield(records,content)||records.stream().anyMatch(item->item.slotId()==0&&item.itemId().contains(".two_handed_sword."));}
    private static void validateHands(List<EquipmentRecord> records,ContentCatalog content){
        if(content.weaponSlots().isEmpty())return;
        var main=records.stream().filter(item->item.slotId()==0).findFirst().orElse(null);var off=records.stream().filter(item->item.slotId()==1).findFirst().orElse(null);
        var mainRule=main==null?null:content.weaponSlot(main.itemId());
        if(main!=null&&content.items().containsKey(main.itemId())&&content.items().get(main.itemId()).slot()!=0)throw new IllegalArgumentException("주칸에는 주무기만 장착할 수 있습니다.");
        if(off!=null){
            var definition=content.items().get(off.itemId());var offRule=content.weaponSlot(off.itemId());
            if(definition!=null&&definition.slot()!=1&&(offRule==null||!offRule.hands().equals("DUAL")))throw new IllegalArgumentException("보조칸 장비 종류가 맞지 않습니다.");
            if(mainRule!=null&&(mainRule.hands().equals("TWO")||!mainRule.offhands().contains(handType(off.itemId()))))throw new IllegalArgumentException("두 무기칸이 충돌합니다.");
            if(offRule!=null&&offRule.hands().equals("DUAL")&&(mainRule==null||!handType(main.itemId()).equals(handType(off.itemId()))))throw new IllegalArgumentException("쌍도끼의 주무기가 없습니다.");
        }
    }
    public static void requireEquippable(CharacterProfile profile, EquipmentRecord record) {
        ContentCatalog.Item item=ITEMS.get(record.itemId());
        String armor=switch(profile.profession()){case "WARRIOR"->"heavy";case "ARCHER","ROGUE"->"light";case "MAGE"->"cloth";default->"";};
        if(item==null || !item.enabled() || !profile.characterId().equals(record.characterId()) || profile.level()<item.level() || item.level()!=record.requiredLevel()
            || (!record.profession().isBlank()&&!record.profession().equals(profile.profession()))
            || (!item.requirement().isBlank()&&!item.requirement().equals(profile.profession())&&!item.requirement().equals(armor))) throw new IllegalArgumentException("Incompatible equipment.");
    }

    public static Stats equipped(CharacterProfile profile, List<EquipmentRecord> records) {
        return equipped(profile,records,ContentCatalog.current());
    }
    public static Stats equipped(CharacterProfile profile,List<EquipmentRecord> records,ContentCatalog content) {
        validateHands(records,content);
        var items=content.items();
        double attack = 0, physical = 0, magic = 0, primary = 0, vitality = 0, spirit = 0;
        boolean magical = false;
        double basicMultiplier = 1;
        int basicTicks = 5;
        boolean[] slots = new boolean[9];
        var identities=new java.util.HashSet<java.util.UUID>();
        String armor = switch (profile.profession()) { case "WARRIOR" -> "heavy"; case "ARCHER", "ROGUE" -> "light"; case "MAGE" -> "cloth"; default -> ""; };
        for (EquipmentRecord record : records) {
            if (!identities.add(record.equipmentId())) throw new IllegalArgumentException("Duplicate equipment identity.");
            if (!profile.characterId().equals(record.characterId())) throw new IllegalArgumentException("Equipment belongs to another character.");
            if (record.slotId() < 0 || record.slotId() > 8 || slots[record.slotId()]) throw new IllegalArgumentException("Invalid equipped slot.");
            slots[record.slotId()] = true;
            ContentCatalog.Item item = items.get(record.itemId());
            // Legacy placeholders and undefined items provide no invented combat stats.
            if (item == null) continue;
            if (!item.enabled()) throw new IllegalArgumentException("Disabled equipment.");
            var handRule=content.weaponSlot(record.itemId());boolean dualOffhand=record.slotId()==1&&handRule!=null&&handRule.hands().equals("DUAL");
            if ((item.slot() != record.slotId()&&!dualOffhand) || item.level() != record.requiredLevel() || profile.level() < item.level()
                || (!record.profession().isBlank() && !record.profession().equals(profile.profession()))
                || (!item.requirement().isBlank() && !item.requirement().equals(profile.profession()) && !item.requirement().equals(armor))) continue;
            if (record.enhancement() < 0 || record.enhancement() > 15) throw new IllegalArgumentException("Invalid enhancement.");
            var policy=content.enhancementPolicy();
            double scale = 1 + Math.min(record.enhancement(), 5) * policy.firstFive() + Math.max(0, record.enhancement() - 5) * policy.later();
            if(handRule!=null)scale*=handRule.share();
            attack += item.attack() * scale; physical += item.physical() * scale; magic += item.magic() * scale;
            int tier = item.level() == 10 ? 0 : item.level() == 15 ? 1 : 2;
            var bonuses=content.bonuses();
            primary += bonuses.primary().get(tier) * item.budget() * scale;
            vitality += bonuses.vitality().get(tier) * item.budget() * scale;
            spirit += bonuses.spirit().get(tier) * item.budget() * scale;
            if (record.slotId() == 0) { magical = item.magical(); basicMultiplier = item.basicMultiplier(); basicTicks = item.basicTicks(); }
        }
        return new Stats((int)Math.round(attack), (int)Math.round(physical), (int)Math.round(magic),
            (int)Math.round(primary), (int)Math.round(vitality), (int)Math.round(spirit), magical, basicMultiplier, basicTicks);
    }
    public record Stats(int attack, int physicalDefense, int magicDefense, int primary, int vitality, int spirit, boolean magical, double basicMultiplier, int basicTicks) {
        public static final Stats EMPTY = new Stats(0,0,0,0,0,0,false,1,5);
        public int defense(boolean magic) { return magic ? magicDefense : physicalDefense; }
    }
}
