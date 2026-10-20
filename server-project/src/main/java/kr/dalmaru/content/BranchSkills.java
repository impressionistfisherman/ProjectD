package kr.dalmaru.content;

import com.google.gson.JsonObject;
import java.util.*;
import kr.dalmaru.account.AdvancementStage;
import kr.dalmaru.account.CharacterProfession;
import kr.dalmaru.skill.SkillDefinition;

/** Eleven branch skills on each of the eight paths. The original 24 stay in the legacy skill list. */
public final class BranchSkills {
    public record Spec(String id,String branch,String weaponProfile,CharacterProfession profession,SkillDefinition.Kind kind,String name,String description,int unlockLevel,int resourceSpend,int cooldownTicks,int effectAmount,ContentCatalog.Skill view) {
        public void admit(int level,String confirmedBranch,boolean advance30,boolean advance50,boolean advance60) {
            if (level < unlockLevel || confirmedBranch == null || !confirmedBranch.equals(branch)) throw new IllegalArgumentException("Branch skill profession/level/path does not match.");
            boolean ready = switch (unlockLevel) { case 30 -> advance30; case 50 -> advance50; case 60 -> advance60; default -> false; };
            if (!ready) throw new IllegalArgumentException("Branch skill stage is not confirmed.");
        }
        public boolean casts() { return kind == SkillDefinition.Kind.ACTIVE || kind == SkillDefinition.Kind.ULTIMATE; }
    }
    private static final Map<String,String> WEAPONS = Map.of(
        "warrior.twohand","two_handed_sword","warrior.shield","sword_shield",
        "archer.bow","bow","archer.crossbow","crossbow",
        "mage.fan","fan","mage.ritual","ritual_sword",
        "rogue.bandit","dual_axes","rogue.pirate","pirate_sword");
    private final Map<String,Spec> byId;
    private final Map<String,List<Spec>> byBranch;
    private BranchSkills(Map<String,Spec> byId,Map<String,List<Spec>> byBranch) { this.byId = Map.copyOf(byId); this.byBranch = Map.copyOf(byBranch); }
    public static BranchSkills empty() { return new BranchSkills(Map.of(), Map.of()); }
    public Spec get(String id) { return byId.get(id); }
    public Collection<Spec> all() { return byId.values(); }
    public List<Spec> path(String branch) { return byBranch.getOrDefault(branch, List.of()); }
    public Set<String> branches() { return byBranch.keySet(); }
    public ContentCatalog.Skill view(String id) { var spec = byId.get(id); return spec == null ? null : spec.view(); }
    public static BranchSkills parse(JsonObject root, String version) {
        if (!root.has("branchSkills")) return empty();
        var array = root.get("branchSkills");
        if (array == null || !array.isJsonArray()) throw new IllegalArgumentException("Invalid branch skill list");
        var byId = new LinkedHashMap<String,Spec>();
        var grouped = new LinkedHashMap<String,List<Spec>>();
        for (var entry : array.getAsJsonArray()) {
            var row = entry.getAsJsonObject();
            String id = text(row, "id"), branch = text(row, "branch");
            if (!id.matches("skill\\.branch\\.[a-z0-9_.]{1,96}") || !WEAPONS.containsKey(branch)) throw new IllegalArgumentException("Invalid branch skill id");
            String weapon = text(row, "weaponProfile");
            if (!weapon.equals(WEAPONS.get(branch))) throw new IllegalArgumentException("Branch skill weapon mismatch");
            var profession = CharacterProfession.valueOf(text(row, "profession"));
            if (AdvancementStage.profession(branch) != profession) throw new IllegalArgumentException("Branch skill profession mismatch");
            var kind = SkillDefinition.Kind.valueOf(text(row, "kind"));
            String name = text(row, "name"), description = text(row, "description");
            if (!name.startsWith("[임시]") || !description.startsWith("[임시]") || name.length() > 128 || description.length() > 4096) throw new IllegalArgumentException("Branch skill copy must be temporary");
            int level = integer(row, "unlockLevel"), spend = integer(row, "resourceSpend"), cooldown = integer(row, "cooldownTicks"), effect = integer(row, "effectAmount");
            if ((level != 30 && level != 50 && level != 60) || effect < 1) throw new IllegalArgumentException("Branch skill has no execution");
            if (!kindCasts(kind) && (spend != 0 || cooldown != 0)) throw new IllegalArgumentException("Passive branch skill spends a cast");
            var view = new ContentCatalog.Skill(id, version, profession, kind, name, description, List.of(weapon), level, 1, 1, 0, cooldown, Map.of());
            var spec = new Spec(id, branch, weapon, profession, kind, name, description, level, spend, cooldown, effect, view);
            if (byId.putIfAbsent(id, spec) != null || SkillDefinition.capabilities().containsKey(id)) throw new IllegalArgumentException("Duplicate branch skill");
            grouped.computeIfAbsent(branch, key -> new ArrayList<>()).add(spec);
        }
        if (byId.size() != 88 || !grouped.keySet().equals(WEAPONS.keySet())) throw new IllegalArgumentException("Branch skill set must be 8 paths by 11");
        for (var specs : grouped.values()) {
            if (specs.size() != 11) throw new IllegalArgumentException("Branch path is not 11 skills");
            long at30 = specs.stream().filter(spec -> spec.unlockLevel() == 30).count();
            long at50 = specs.stream().filter(spec -> spec.unlockLevel() == 50).count();
            long at60 = specs.stream().filter(spec -> spec.unlockLevel() == 60).count();
            long actives = specs.stream().filter(spec -> spec.kind() == SkillDefinition.Kind.ACTIVE).count();
            long passives = specs.stream().filter(spec -> spec.kind() == SkillDefinition.Kind.PASSIVE).count();
            long ultimates = specs.stream().filter(spec -> spec.kind() == SkillDefinition.Kind.ULTIMATE).count();
            long traits = specs.stream().filter(spec -> spec.kind() == SkillDefinition.Kind.TRAIT).count();
            if (at30 != 5 || at50 != 4 || at60 != 2 || actives != 5 || passives != 4 || ultimates != 1 || traits != 1) throw new IllegalArgumentException("Branch path shape drifted");
            boolean unused = specs.getFirst().weaponProfile().equals("dual_axes") || specs.getFirst().weaponProfile().equals("pirate_sword");
            for (var spec : specs) {
                if (unused && spec.resourceSpend() != 0) throw new IllegalArgumentException("Rogue path invented a resource spend");
                if (!unused && spec.casts() && spec.resourceSpend() < 1) throw new IllegalArgumentException("Branch cast does not spend its resource");
            }
        }
        var frozen = new LinkedHashMap<String,List<Spec>>();
        grouped.forEach((branch, specs) -> frozen.put(branch, List.copyOf(specs)));
        return new BranchSkills(byId, frozen);
    }
    private static boolean kindCasts(SkillDefinition.Kind kind) { return kind == SkillDefinition.Kind.ACTIVE || kind == SkillDefinition.Kind.ULTIMATE; }
    private static String text(JsonObject value, String key) {
        var field = value.get(key);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Missing branch skill string");
        return field.getAsString();
    }
    private static int integer(JsonObject value, String key) {
        var field = value.get(key);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Missing branch skill number");
        int number = field.getAsInt();
        if (number < 0) throw new IllegalArgumentException("Negative branch skill value");
        return number;
    }
}
