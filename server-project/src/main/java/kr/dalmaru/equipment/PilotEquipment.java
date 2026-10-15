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
        var items=content.items();
        double attack = 0, physical = 0, magic = 0, primary = 0, vitality = 0, spirit = 0;
        boolean magical = false;
        double basicMultiplier = 1;
        int basicTicks = 5;
        boolean[] slots = new boolean[9];
        String armor = switch (profile.profession()) { case "WARRIOR" -> "heavy"; case "ARCHER", "ROGUE" -> "light"; case "MAGE" -> "cloth"; default -> ""; };
        for (EquipmentRecord record : records) {
            if (!profile.characterId().equals(record.characterId())) throw new IllegalArgumentException("Equipment belongs to another character.");
            if (record.slotId() < 0 || record.slotId() > 8 || slots[record.slotId()]) throw new IllegalArgumentException("Invalid equipped slot.");
            slots[record.slotId()] = true;
            ContentCatalog.Item item = items.get(record.itemId());
            // Legacy placeholders and undefined items provide no invented combat stats.
            if (item == null) continue;
            if (!item.enabled()) throw new IllegalArgumentException("Disabled equipment.");
            if (item.slot() != record.slotId() || item.level() != record.requiredLevel() || profile.level() < item.level()
                || (!record.profession().isBlank() && !record.profession().equals(profile.profession()))
                || (!item.requirement().isBlank() && !item.requirement().equals(profile.profession()) && !item.requirement().equals(armor))) continue;
            if (record.enhancement() < 0 || record.enhancement() > 15) throw new IllegalArgumentException("Invalid enhancement.");
            var policy=content.enhancementPolicy();
            double scale = 1 + Math.min(record.enhancement(), 5) * policy.firstFive() + Math.max(0, record.enhancement() - 5) * policy.later();
            attack += item.attack() * scale; physical += item.physical() * scale; magic += item.magic() * scale;
            int tier = item.level() == 10 ? 0 : item.level() == 15 ? 1 : 2;
            var bonuses=content.bonuses();
            primary += bonuses.primary().get(tier) * item.budget() * scale;
            vitality += bonuses.vitality().get(tier) * item.budget() * scale;
            spirit += bonuses.spirit().get(tier) * item.budget() * scale;
            if (item.slot() == 0) { magical = item.magical(); basicMultiplier = item.basicMultiplier(); basicTicks = item.basicTicks(); }
        }
        return new Stats((int)Math.round(attack), (int)Math.round(physical), (int)Math.round(magic),
            (int)Math.round(primary), (int)Math.round(vitality), (int)Math.round(spirit), magical, basicMultiplier, basicTicks);
    }
    public record Stats(int attack, int physicalDefense, int magicDefense, int primary, int vitality, int spirit, boolean magical, double basicMultiplier, int basicTicks) {
        public static final Stats EMPTY = new Stats(0,0,0,0,0,0,false,1,5);
        public int defense(boolean magic) { return magic ? magicDefense : physicalDefense; }
    }
}
