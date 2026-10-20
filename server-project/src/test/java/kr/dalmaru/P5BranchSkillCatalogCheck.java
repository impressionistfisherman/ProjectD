package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import kr.dalmaru.account.CharacterProfession;
import kr.dalmaru.content.BranchSkills;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.skill.SkillDefinition;
import kr.dalmaru.skill.SkillLoadout;

public final class P5BranchSkillCatalogCheck {
    public static void main(String[] args) throws Exception {
        var content = ContentCatalog.current();
        var branch = content.branchSkills();
        check(content.skills().size() == 24, "Legacy skill set changed");
        check(branch.all().size() == 88 && branch.branches().size() == 8, "Expected 88 branch skills on 8 paths");
        var ids = new HashSet<String>();
        content.skills().forEach(skill -> ids.add(skill.id()));
        branch.all().forEach(spec -> ids.add(spec.id()));
        check(ids.size() == 112, "Expected 112 distinct skill ids");
        for (var profession : CharacterProfession.values()) {
            long common = SkillDefinition.capabilities().entrySet().stream().filter(entry -> entry.getValue() == profession).count();
            check(common == 6, "Common skills drifted for " + profession);
        }
        check(name(branch, "warrior.twohand", "chain_cut").equals("[임시] 연환참") && name(branch, "warrior.twohand", "isle_slash").equals("[임시] 참마일섬"), "Two-hand names drifted");
        check(name(branch, "warrior.shield", "dragon_tiger").equals("[임시] 용호진"), "Shield ultimate drifted");
        check(name(branch, "archer.bow", "vermilion").equals("[임시] 주작비상") && name(branch, "archer.crossbow", "thunder_roar").equals("[임시] 벽력포효"), "Archer ultimates drifted");
        check(name(branch, "mage.fan", "five_elements").equals("[임시] 태일오행진") && name(branch, "mage.ritual", "seven_stars").equals("[임시] 칠성호법진"), "Mage ultimates drifted");
        check(name(branch, "rogue.bandit", "mountain_break").equals("[임시] 산악붕괴") && name(branch, "rogue.pirate", "sea_wave").equals("[임시] 사해격랑"), "Rogue ultimates drifted");
        for (String path : branch.branches()) {
            var specs = branch.path(path);
            check(specs.size() == 11, "Path is not 17 references");
            var spec = specs.getFirst();
            long common = SkillDefinition.capabilities().entrySet().stream().filter(entry -> entry.getValue() == spec.profession()).count();
            check(common + specs.size() == 17, "Path references are not 17");
            var active = specs.stream().filter(item -> item.kind() == SkillDefinition.Kind.ACTIVE).findFirst().orElseThrow();
            var ultimate = specs.stream().filter(item -> item.kind() == SkillDefinition.Kind.ULTIMATE).findFirst().orElseThrow();
            var trait = specs.stream().filter(item -> item.kind() == SkillDefinition.Kind.TRAIT).findFirst().orElseThrow();
            boolean unused = spec.weaponProfile().equals("dual_axes") || spec.weaponProfile().equals("pirate_sword");
            check(active.resourceSpend() == (unused ? 0 : 10) && ultimate.resourceSpend() == (unused ? 0 : 25) && trait.resourceSpend() == 0, "Temporary resource spend drifted");
            var slots = List.of(active.id(), "", "", "", "");
            var ranks = Map.of(active.id(), 1, ultimate.id(), 1);
            SkillLoadout.validate(spec.profession(), ranks, slots, slots, path);
            reject(() -> SkillLoadout.validate(spec.profession(), ranks, slots, slots, ""));
            var traitSlots = List.of(trait.id(), "", "", "", "");
            reject(() -> SkillLoadout.validate(spec.profession(), Map.of(trait.id(), 1), traitSlots, traitSlots, path));
            SkillLoadout.validate(spec.profession(), ranks, List.of("", "", "", "", ultimate.id()), List.of("", "", "", "", ultimate.id()), path);
            reject(() -> SkillLoadout.validate(spec.profession(), ranks, List.of(ultimate.id(), "", "", "", ""), List.of(ultimate.id(), "", "", "", ""), path));
            active.admit(active.unlockLevel(), path, true, true, true);
            reject(() -> active.admit(active.unlockLevel() - 1, path, true, true, true));
            reject(() -> active.admit(active.unlockLevel(), "warrior.twohand".equals(path) ? "warrior.shield" : "warrior.twohand", true, true, true));
        }
        var level30 = branch.get("skill.branch.warrior.twohand.chain_cut");
        reject(() -> level30.admit(30, "warrior.twohand", false, false, false));
        level30.admit(30, "warrior.twohand", true, false, false);
        var level50 = branch.get("skill.branch.warrior.twohand.charged_cut");
        reject(() -> level50.admit(50, "warrior.twohand", true, false, false));
        level50.admit(50, "warrior.twohand", true, true, false);
        String learn = Files.readString(Path.of("src/main/java/kr/dalmaru/skill/SkillRepository.java"));
        check(learn.contains("branchSkill!=null)admit("), "Learning does not use the branch gate");
        System.out.println("PASS branch catalog 112 ids, 8 paths by 17 references, temporary copy, slot and stage gates");
    }

    private static String name(BranchSkills skills, String path, String suffix) {
        return skills.get("skill.branch." + path + "." + suffix).name();
    }

    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid branch skill accepted");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
