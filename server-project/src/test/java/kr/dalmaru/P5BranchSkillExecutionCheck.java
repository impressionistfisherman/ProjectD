package kr.dalmaru;

import java.util.*;
import kr.dalmaru.combat.CombatRuntime;
import kr.dalmaru.combat.CombatRuntime.ResourceOutcome;
import kr.dalmaru.content.BranchSkills;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.skill.SkillDefinition;

public final class P5BranchSkillExecutionCheck {
    public static void main(String[] args) {
        var skills = ContentCatalog.current().branchSkills();
        for (String path : skills.branches()) execute(skills, path);
        cooldownSurvivesPause(skills);
        stageBlocksLaterSkill(skills);
        System.out.println("PASS 8 branch paths execute: damage, resource or no rogue resource, cooldown, stage gate, stamina unchanged");
    }

    private static void execute(BranchSkills skills, String path) {
        var specs = skills.path(path);
        var actives = specs.stream().filter(spec -> spec.kind() == SkillDefinition.Kind.ACTIVE).toList();
        var ultimate = specs.stream().filter(spec -> spec.kind() == SkillDefinition.Kind.ULTIMATE).findFirst().orElseThrow();
        var trait = specs.stream().filter(spec -> spec.kind() == SkillDefinition.Kind.TRAIT).findFirst().orElseThrow();
        var ranks = new HashMap<String, Integer>();
        specs.forEach(spec -> ranks.put(spec.id(), 1));
        var slots = List.of(actives.get(0).id(), actives.get(1).id(), actives.get(2).id(), actives.get(3).id(), ultimate.id());
        var runtime = new CombatRuntime();
        var player = UUID.randomUUID();
        var character = UUID.randomUUID();
        runtime.armBranch(player, character, path, true, true, true, ranks, slots, true);
        fill(runtime, player, actives.get(0).resourceSpend());
        double stamina = runtime.stamina(player, 0).current();
        int before = runtime.branchResourceSnapshot(player).current();
        int bonus = specs.stream().filter(spec -> !spec.casts()).mapToInt(BranchSkills.Spec::effectAmount).sum();
        check(runtime.useBranchSkill(player, actives.get(0).id(), 0) == ResourceOutcome.APPLIED, "Active did not execute");
        check(runtime.lastBranchEffect(player) == actives.get(0).effectAmount() + bonus, "Active effect drifted");
        check(runtime.stamina(player, 0).current() == stamina, "Branch cast spent stamina");
        int after = runtime.branchResourceSnapshot(player).current();
        check(before - after == actives.get(0).resourceSpend(), "Resource spend drifted");
        check(runtime.useBranchSkill(player, actives.get(0).id(), 1) == ResourceOutcome.COOLDOWN && runtime.branchResourceSnapshot(player).current() == after, "Cooldown cast spent again");
        check(runtime.useBranchSkill(player, trait.id(), 1) == ResourceOutcome.UNAVAILABLE, "Trait cast occupied a slot");
        fill(runtime, player, ultimate.resourceSpend());
        int primed = runtime.branchResourceSnapshot(player).current();
        check(runtime.useBranchSkill(player, ultimate.id(), 1000) == ResourceOutcome.APPLIED, "Ultimate did not execute");
        check(runtime.lastBranchEffect(player) == ultimate.effectAmount() + bonus, "Ultimate effect drifted");
        check(primed - runtime.branchResourceSnapshot(player).current() == ultimate.resourceSpend(), "Ultimate resource spend drifted");
        check(runtime.stamina(player, 1000).current() == stamina, "Ultimate spent stamina");
        runtime.bindBranch(player, character, specs.getFirst().weaponProfile().equals("two_handed_sword") ? "sword_shield" : "two_handed_sword", true);
        check(runtime.useBranchSkill(player, actives.get(1).id(), 2000) == ResourceOutcome.UNAVAILABLE, "Wrong weapon executed");
    }

    private static void cooldownSurvivesPause(BranchSkills skills) {
        var active = skills.get("skill.branch.warrior.twohand.chain_cut");
        var runtime = new CombatRuntime();
        var player = UUID.randomUUID();
        runtime.armBranch(player, UUID.randomUUID(), "warrior.twohand", true, true, true, Map.of(active.id(), 1), List.of(active.id(), "", "", "", ""), true);
        fill(runtime, player, active.resourceSpend());
        check(runtime.useBranchSkill(player, active.id(), 0) == ResourceOutcome.APPLIED, "Pause sample did not cast");
        runtime.shiftCooldowns(player, 100);
        check(runtime.useBranchSkill(player, active.id(), 40) == ResourceOutcome.COOLDOWN, "Pause cleared the branch cooldown");
        fill(runtime, player, active.resourceSpend());
        check(runtime.useBranchSkill(player, active.id(), 140) == ResourceOutcome.APPLIED, "Shifted cooldown did not open");
    }

    private static void stageBlocksLaterSkill(BranchSkills skills) {
        var early = skills.get("skill.branch.warrior.shield.shield_bash");
        var later = skills.get("skill.branch.warrior.shield.rescue_charge");
        var runtime = new CombatRuntime();
        var player = UUID.randomUUID();
        runtime.armBranch(player, UUID.randomUUID(), "warrior.shield", true, false, false, Map.of(early.id(), 1, later.id(), 1), List.of(early.id(), later.id(), "", "", ""), true);
        fill(runtime, player, later.resourceSpend());
        check(runtime.useBranchSkill(player, later.id(), 0) == ResourceOutcome.UNAVAILABLE, "Unconfirmed stage executed");
        check(runtime.useBranchSkill(player, early.id(), 0) == ResourceOutcome.APPLIED, "Confirmed stage did not execute");
    }

    private static void fill(CombatRuntime runtime, UUID player, int need) {
        var snapshot = runtime.branchResourceSnapshot(player);
        if (need == 0 || snapshot.resourceKind().isEmpty()) return;
        int guard = 0;
        while (runtime.branchResourceSnapshot(player).current() < need) {
            runtime.grantBranchHit(player);
            if (++guard > 40) throw new AssertionError("Resource did not fill");
        }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
