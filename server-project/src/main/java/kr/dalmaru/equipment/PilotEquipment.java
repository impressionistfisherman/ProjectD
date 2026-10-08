package kr.dalmaru.equipment;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import kr.dalmaru.account.CharacterProfile;

/** Explicit item definitions from PLT-003 section 4; no items are created here. */
public final class PilotEquipment {
    private static final Map<String, Definition> ITEMS = new HashMap<>();
    static {
        String[] weapons = {"two_handed_sword", "sword_shield", "bow", "crossbow", "fan", "ritual_sword", "dual_axes", "pirate_sword"};
        String[] professions = {"WARRIOR", "WARRIOR", "ARCHER", "ARCHER", "MAGE", "MAGE", "ROGUE", "ROGUE"};
        int[][] attacks = {{41,51,63},{30,37,46},{35,44,55},{57,71,88},{36,46,57},{31,39,48},{38,48,59},{34,43,53}};
        int[] intervals = {20,16,18,30,20,18,10,16};
        String[] armor = {"heavy", "light", "cloth"};
        int[][] physical = {{45,75,100},{35,60,80},{25,40,55}};
        int[][] magic = {{22,38,50},{35,60,80},{45,75,100}};
        String[] parts = {"head", "body", "arms", "feet"};
        double[] shares = {.2,.4,.2,.2};
        for (int tier = 0; tier < 3; tier++) {
            int level = new int[]{10,15,20}[tier];
            for (int w = 0; w < weapons.length; w++) {
                String id = "pilot.weapon." + weapons[w] + "." + level;
                ITEMS.put(id, new Definition(level, 0, professions[w], attacks[w][tier], 0, 0, .30, w == 4 || w == 5, w == 6 ? .55 : 1, intervals[w]));
            }
            for (int a = 0; a < armor.length; a++) for (int p = 0; p < parts.length; p++) {
                String id = "pilot.armor." + armor[a] + "." + parts[p] + "." + level;
                ITEMS.put(id, new Definition(level, p + 2, armor[a], 0, physical[a][tier] * shares[p], magic[a][tier] * shares[p], .45 * shares[p], false, 1, 0));
            }
            String[] accessories = {"neck", "ring", "charm"};
            for (int a = 0; a < accessories.length; a++) ITEMS.put("pilot.accessory." + accessories[a] + "." + level,
                new Definition(level, a + 6, "", 0, 0, 0, .25 / 3, false, 1, 0));
        }
    }
    private PilotEquipment() { }

    public static Stats equipped(CharacterProfile profile, List<EquipmentRecord> records) {
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
            Definition item = ITEMS.get(record.itemId());
            // Legacy placeholders and undefined items provide no invented combat stats.
            if (item == null) continue;
            if (item.slot != record.slotId() || item.level != record.requiredLevel() || profile.level() < item.level
                || (!record.profession().isBlank() && !record.profession().equals(profile.profession()))
                || (!item.requirement.isBlank() && !item.requirement.equals(profile.profession()) && !item.requirement.equals(armor))) continue;
            if (record.enhancement() < 0 || record.enhancement() > 15) throw new IllegalArgumentException("Invalid enhancement.");
            double scale = 1 + Math.min(record.enhancement(), 5) * .02 + Math.max(0, record.enhancement() - 5) * .03;
            attack += item.attack * scale; physical += item.physical * scale; magic += item.magic * scale;
            int tier = item.level == 10 ? 0 : item.level == 15 ? 1 : 2;
            primary += new int[]{5,8,12}[tier] * item.budget * scale;
            vitality += new int[]{4,6,8}[tier] * item.budget * scale;
            spirit += new int[]{3,4,6}[tier] * item.budget * scale;
            if (item.slot == 0) { magical = item.magical; basicMultiplier = item.basicMultiplier; basicTicks = item.basicTicks; }
        }
        return new Stats((int)Math.round(attack), (int)Math.round(physical), (int)Math.round(magic),
            (int)Math.round(primary), (int)Math.round(vitality), (int)Math.round(spirit), magical, basicMultiplier, basicTicks);
    }
    private record Definition(int level, int slot, String requirement, int attack, double physical, double magic, double budget, boolean magical, double basicMultiplier, int basicTicks) { }
    public record Stats(int attack, int physicalDefense, int magicDefense, int primary, int vitality, int spirit, boolean magical, double basicMultiplier, int basicTicks) {
        public static final Stats EMPTY = new Stats(0,0,0,0,0,0,false,1,5);
        public int defense(boolean magic) { return magic ? magicDefense : physicalDefense; }
    }
}
