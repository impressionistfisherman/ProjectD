package kr.dalmaru;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards the reviewed R1-R5 fix points against regression in the production call paths. */
public final class ReviewFixBoundaryCheck {
    public static void main(String[] args) throws Exception {
        String network = Files.readString(Path.of("src/main/java/kr/dalmaru/network/DalmaruNetworking.java"));
        String runtime = Files.readString(Path.of("src/main/java/kr/dalmaru/run/RunRuntime.java"));
        String combat = Files.readString(Path.of("src/main/java/kr/dalmaru/combat/CombatRuntime.java"));

        require(network.contains("if(changing){resetCharacterCaches(player,state);COMBAT.remove(player.getUUID());}"), "R1 character switch must clear per-character combat/loadout/checkpoint caches");
        require(network.contains("state.accountSession.activeCharacterId().equals(state.loadout.characterId())"), "R1 cached loadout must belong to active character");
        require(network.contains("state.accountSession.activeCharacterId().equals(state.checkpointCharacter)"), "R1 checkpoint must belong to active character");

        require(runtime.contains("if(!(entity instanceof Mob mob)||mob.isRemoved())continue;"), "R2 unloaded or absent entities must not count as kills");
        require(runtime.contains("if(mob.isDeadOrDying()){mobDied(mob);continue;}"), "R2 only dead entities may be removed from required kills");
        require(runtime.contains("encounter.mobs.containsKey(entity.getUUID())&&entity.entityTags().contains(\"dalmaru.run.\"+encounter.runId)"), "R2 loaded run entity must reconnect to its owning encounter");
        require(!runtime.contains("FLAG_PERSIST"), "R2 chunk tickets must remain transient");

        require(network.contains("validatePreparedRun(prep)"), "R3 preparation must be revalidated across the database wait");
        require(network.contains("state.startInvalidated=true"), "R3 death during entry must invalidate the pending entry");
        require(runtime.contains("kind.magic&&mob.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)"), "R4 smoke blindness must stop ranged scripted attacks");
        require(combat.contains("target.hasEffect(net.minecraft.world.effect.MobEffects.SLOWNESS)"), "R5 vanilla shackles slowness must enable expose weakness");

        System.out.println("R1-R5 reviewed production guards PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
