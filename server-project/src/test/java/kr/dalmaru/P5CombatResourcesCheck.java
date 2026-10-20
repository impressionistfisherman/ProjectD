package kr.dalmaru;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.dalmaru.account.CharacterProfession;
import kr.dalmaru.combat.CombatRuntime;
import kr.dalmaru.combat.CombatRuntime.ResourceOutcome;
import kr.dalmaru.content.ContentCatalog;
import kr.dalmaru.skill.SkillDefinition;
import kr.dalmaru.skill.SkillLoadout;
import kr.dalmaru.skill.SkillRepository;

public final class P5CombatResourcesCheck {
    public static void main(String[] args) throws Exception {
        var content = ContentCatalog.current();
        var twoHand = content.branchResource("two_handed_sword");
        var shield = content.branchResource("sword_shield");
        check(twoHand.resourceKind().equals("sword_force") && twoHand.maximum() == 100 && twoHand.preciseDefenseDiscountPercent() == 75 && twoHand.preciseCost() == 5, "Two-handed sword force cap or discount drifted");
        check(shield.resourceKind().equals("guard_force") && shield.preciseDefenseDiscountPercent() == 0 && shield.preciseCost() == 20, "Shield path inherited the two-handed discount");
        check(content.branchResource("bow").resourceKind().equals("reload") && content.branchResource("crossbow").resourceKind().equals("reload"), "Reload path missing");
        check(content.branchResource("fan").resourceKind().equals("seal") && content.branchResource("ritual_sword").resourceKind().equals("seal"), "Seal path missing");
        check(content.branchResource("dual_axes").unused() && content.branchResource("pirate_sword").unused(), "Rogue path invented a resource");
        check(content.branchResource("").resourceKind().isEmpty(), "Missing path invented a resource");
        for (var rule : content.branchResources().values()) check(rule.initial() == 0 && (rule.label().isEmpty() || rule.label().startsWith("[임시]")), "Resource initial or label drifted");
        var root = JsonParser.parseString(Files.readString(Path.of("src/main/resources/content/pilot.json"))).getAsJsonObject();
        var broken = JsonParser.parseString(root.toString()).getAsJsonObject();
        broken.getAsJsonObject("branchResources").getAsJsonObject("paths").getAsJsonObject("sword_shield").addProperty("preciseDefenseDiscountPercent", 75);
        reject(() -> ContentCatalog.parse(broken.toString()));
        var negative = JsonParser.parseString(root.toString()).getAsJsonObject();
        negative.getAsJsonObject("branchResources").getAsJsonObject("paths").getAsJsonObject("two_handed_sword").addProperty("maximum", -1);
        reject(() -> ContentCatalog.parse(negative.toString()));

        var runtime = new CombatRuntime();
        UUID player = UUID.randomUUID();
        UUID character = UUID.randomUUID();
        check(runtime.branchResourceSnapshot(player).resourceKind().isEmpty() && runtime.branchResourceSnapshot(player).current() == 0, "Snapshot created a resource");
        runtime.bindBranch(player, character, "two_handed_sword", true);
        var before = runtime.branchResourceSnapshot(player);
        var again = runtime.branchResourceSnapshot(player);
        check(before.resourceKind().equals("sword_force") && before.current() == 0 && before.maximum() == 100 && again.current() == before.current(), "Snapshot refilled sword force");
        check(runtime.preciseDefense(player, 0) == ResourceOutcome.RESOURCE && runtime.branchResourceSnapshot(player).current() == 0, "Precise defense went negative");
        check(runtime.grantBranchHit(player) == ResourceOutcome.APPLIED && runtime.branchResourceSnapshot(player).current() == 10, "Hit did not grant sword force");
        check(runtime.grantBranchHit(player) == ResourceOutcome.APPLIED && runtime.branchResourceSnapshot(player).current() == 20, "Second hit grant drifted");
        check(runtime.preciseDefense(player, 1) == ResourceOutcome.APPLIED && runtime.branchResourceSnapshot(player).current() == 25, "Two-handed precise defense did not cost 5 and grant 10");
        for (int i = 0; i < 20; i++) runtime.grantBranchHit(player);
        check(runtime.branchResourceSnapshot(player).current() == 100, "Sword force exceeded 100");
        double stamina = runtime.stamina(player, 0).current();
        check(runtime.spendBranchSkill(player, 10) == ResourceOutcome.APPLIED && runtime.branchResourceSnapshot(player).current() == 85 && runtime.stamina(player, 0).current() == stamina, "Branch spend changed stamina");
        check(runtime.spendBranchSkill(player, 49) == ResourceOutcome.COOLDOWN && runtime.branchResourceSnapshot(player).current() == 85 && runtime.stamina(player, 0).current() == stamina, "Cooldown spend consumed a resource");
        check(runtime.spendBranchSkill(player, 50) == ResourceOutcome.APPLIED && runtime.branchResourceSnapshot(player).current() == 70, "Cooldown boundary did not spend once");
        check(runtime.useSweep(player, 1000) == CombatRuntime.SweepResult.USED && runtime.stamina(player, 1000).current() == stamina - 20 && runtime.branchResourceSnapshot(player).current() == 70, "Stamina spend changed sword force");
        double afterSweep = runtime.stamina(player, 1000).current();
        runtime.resetBranchOnDeath(player);
        check(runtime.branchResourceSnapshot(player).current() == 0 && runtime.stamina(player, 1000).current() == afterSweep, "Death reset stamina or kept sword force");
        check(runtime.spendBranchSkill(player, 51) == ResourceOutcome.COOLDOWN, "Death cleared the skill cooldown");
        runtime.grantBranchHit(player);
        runtime.grantBranchHit(player);
        int kept = runtime.branchResourceSnapshot(player).current();
        runtime.disconnect(player);
        runtime.reconnect(player);
        check(runtime.branchResourceSnapshot(player).current() == kept && runtime.branchResourceSnapshot(player).current() == kept, "Reconnect refilled sword force");
        runtime.dropPreservedBranch(player);
        check(runtime.branchResourceSnapshot(player).resourceKind().isEmpty() && runtime.stamina(player, 1000).current() == afterSweep, "Missing resource reset stamina");
        check(runtime.useSweep(player, 1001) == CombatRuntime.SweepResult.COOLDOWN, "Missing resource cleared the sweep cooldown");
        runtime.bindBranch(player, character, "two_handed_sword", true);
        check(runtime.branchResourceSnapshot(player).current() == 0 && runtime.stamina(player, 1000).current() == afterSweep, "Restored resource did not start at zero");
        runtime.grantBranchHit(player);
        runtime.bindBranch(player, character, "two_handed_sword", true);
        check(runtime.branchResourceSnapshot(player).current() == 10, "Same character bind cleared sword force");
        runtime.bindBranch(player, character, "sword_shield", true);
        check(runtime.branchResourceSnapshot(player).resourceKind().equals("guard_force") && runtime.branchResourceSnapshot(player).current() == 0, "Path switch kept sword force");
        runtime.bindBranch(player, character, "two_handed_sword", true);
        runtime.grantBranchHit(player);
        runtime.bindBranch(player, UUID.randomUUID(), "two_handed_sword", true);
        check(runtime.branchResourceSnapshot(player).current() == 0, "Character activation kept the previous resource");
        runtime.bindBranch(player, character, "sword_shield", true);
        check(runtime.branchResourceSnapshot(player).resourceKind().equals("guard_force") && runtime.branchResourceSnapshot(player).current() == 0 && runtime.branchResourceSnapshot(player).maximum() == 80, "Path switch kept sword force");
        runtime.grantBranchHit(player);
        runtime.grantBranchHit(player);
        runtime.grantBranchHit(player);
        check(runtime.branchResourceSnapshot(player).current() == 24, "Shield hit grant drifted");
        check(runtime.preciseDefense(player, 2) == ResourceOutcome.APPLIED && runtime.branchResourceSnapshot(player).current() == 12, "Shield precise defense used the two-handed discount");
        runtime.bindBranch(player, character, "dual_axes", true);
        check(runtime.branchResourceSnapshot(player).resourceKind().isEmpty() && runtime.grantBranchHit(player) == ResourceOutcome.UNAVAILABLE && runtime.preciseDefense(player, 3) == ResourceOutcome.UNAVAILABLE, "Rogue runtime invented a resource");
        String combat = Files.readString(Path.of("src/main/java/kr/dalmaru/combat/CombatRuntime.java"));
        check(combat.contains("grantBranchHit(player.getUUID())"), "Valid hits do not grant the branch resource");

        check(!SkillLoadout.occupiesSlot(SkillDefinition.Kind.TRAIT) && !SkillLoadout.occupiesSlot(SkillDefinition.Kind.PASSIVE), "Trait or passive occupies a slot");
        check(SkillLoadout.occupiesSlot(SkillDefinition.Kind.ACTIVE) && SkillLoadout.occupiesSlot(SkillDefinition.Kind.ULTIMATE), "Active or ultimate lost its slot");
        check(!SkillRepository.occupiesLoadoutSlot(SkillDefinition.Kind.TRAIT) && SkillRepository.occupiesLoadoutSlot(SkillDefinition.Kind.ACTIVE), "Learning gate disagrees with the loadout");
        reject(() -> SkillLoadout.requireSlot(SkillDefinition.Kind.TRAIT, 0));
        reject(() -> SkillLoadout.requireSlot(SkillDefinition.Kind.PASSIVE, 3));
        reject(() -> SkillLoadout.requireSlot(SkillDefinition.Kind.ULTIMATE, 0));
        reject(() -> SkillLoadout.requireSlot(SkillDefinition.Kind.ACTIVE, 4));
        SkillLoadout.requireSlot(SkillDefinition.Kind.ULTIMATE, 4);
        SkillLoadout.requireSlot(SkillDefinition.Kind.ACTIVE, 0);
        var passive = List.of("skill.warrior.body_training", "", "", "", "");
        reject(() -> SkillLoadout.validate(CharacterProfession.WARRIOR, Map.of(), passive, passive));
        var ultimateSlot = List.of("", "", "", "", "skill.warrior.sweep");
        reject(() -> SkillLoadout.validate(CharacterProfession.WARRIOR, Map.of("skill.warrior.sweep", 1), ultimateSlot, ultimateSlot));
        System.out.println("PASS branch resources stay separate from stamina: cap 100, no negative, two-handed precise cost 5, shield full cost, trait has no slot, cooldown and death/reconnect boundaries hold");
    }

    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid resource or slot accepted");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
