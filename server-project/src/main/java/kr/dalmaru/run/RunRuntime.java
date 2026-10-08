package kr.dalmaru.run;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;

public final class RunRuntime {
    private final Map<UUID, State> playerRuns = new HashMap<>();
    private final Map<UUID, Encounter> encounters = new HashMap<>();

    public void enter(ServerPlayer player, UUID runId, RunDifficulty difficulty, int partySize) {
        playerRuns.put(player.getUUID(), new State(runId, difficulty == RunDifficulty.FIRST_STEP ? 5 : 3));
        Encounter encounter = encounters.get(runId);
        if (encounter == null) {
            encounter = new Encounter(runId, difficulty, floor(runId), (ServerLevel) player.level(), partySize);
            encounters.put(runId, encounter);
            BeaconTowerMap.build(encounter.level, encounter.floor);
            encounter.bossId = spawnBoss(encounter.level, encounter.floor.offset(0, 1, 15), "철문수장", health(difficulty, 10800));
            BeaconTowerBosses.style(encounter.level, encounter.bossId, false);
        }
        teleport(player, encounter.floor);
    }

    public DeathResult recordDeath(ServerPlayer player) {
        State state = playerRuns.get(player.getUUID());
        if (state == null) return new DeathResult(null, false, false);
        if (state.revivesRemaining == 0) {
            playerRuns.remove(player.getUUID());
            return new DeathResult(state.runId, false, playerRuns.values().stream().noneMatch(other -> other.runId.equals(state.runId)));
        }
        state.revivesRemaining--;
        return new DeathResult(state.runId, true, false);
    }

    public void afterRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
        State state = playerRuns.remove(oldPlayer.getUUID());
        if (state == null) return;
        playerRuns.put(newPlayer.getUUID(), state);
        Encounter encounter = encounters.get(state.runId);
        if (encounter == null) return;
        teleport(newPlayer, encounter.floor);
        newPlayer.setHealth(newPlayer.getMaxHealth() * .35F);
    }

    public UUID bossKilled(ServerPlayer player, UUID bossId) {
        State state = playerRuns.get(player.getUUID());
        if (state == null) return null;
        Encounter encounter = encounters.get(state.runId);
        if (encounter == null || !bossId.equals(encounter.bossId)) return null;
        if (encounter.stage == Stage.GATEKEEPER) {
            encounter.stage = Stage.BRAZIER;
            encounter.bossId = null;
            BeaconTowerMap.open(encounter.level, encounter.floor, 27);
            for (int i = 0; i < 3; i++) encounter.level.setBlock(encounter.floor.offset(-4 + i * 4, 1, 35), Blocks.CAMPFIRE.defaultBlockState(), 3);
            player.sendSystemMessage(Component.literal("봉수로를 정화하세요. 화로 근처에서 4초간 버티면 됩니다."));
            return null;
        }
        if (encounter.stage == Stage.BEACON) {
            encounter.stage = Stage.COMPLETED;
            BeaconTowerMap.open(encounter.level, encounter.floor, 64);
            encounter.level.setBlock(encounter.floor.offset(0, 1, 70), Blocks.CHEST.defaultBlockState(), 3);
            encounters.remove(state.runId);
            return state.runId;
        }
        return null;
    }

    public void tick(MinecraftServer server) {
        for (Map.Entry<UUID, State> entry : playerRuns.entrySet()) {
            Encounter encounter = encounters.get(entry.getValue().runId);
            if (encounter == null || encounter.stage != Stage.BRAZIER) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            if (nearBrazier(player, encounter.floor)) encounter.brazierTicks.merge(entry.getKey(), 1, Integer::sum);
            else encounter.brazierTicks.remove(entry.getKey());
        }
        for (Encounter encounter : new ArrayList<>(encounters.values())) {
            if (encounter.stage == Stage.BRAZIER) activateFinalBoss(encounter);
            tickBoss(server, encounter);
        }
    }

    public void leave(ServerPlayer player) { playerRuns.remove(player.getUUID()); }
    public void disconnect(ServerPlayer player) { playerRuns.remove(player.getUUID()); }
    public UUID runId(ServerPlayer player) { State state = playerRuns.get(player.getUUID()); return state == null ? null : state.runId; }
    public int revivesRemaining(ServerPlayer player) { State state = playerRuns.get(player.getUUID()); return state == null ? 0 : state.revivesRemaining; }

    private void activateFinalBoss(Encounter encounter) {
        long ready = playerRuns.entrySet().stream().filter(entry -> entry.getValue().runId.equals(encounter.runId)).filter(entry -> encounter.brazierTicks.getOrDefault(entry.getKey(), 0) >= 80).count();
        if (ready < encounter.requiredInteractions) return;
        encounter.stage = Stage.BEACON;
        BeaconTowerMap.open(encounter.level, encounter.floor, 45);
        encounter.bossId = spawnBoss(encounter.level, encounter.floor.offset(0, 1, 54), "원혼봉수장", health(encounter.difficulty, 14400));
        BeaconTowerBosses.style(encounter.level, encounter.bossId, true);
    }

    private void tickBoss(MinecraftServer server, Encounter encounter) {
        if (encounter.bossId == null || !(encounter.level.getEntity(encounter.bossId) instanceof Mob boss) || !boss.isAlive()) return;
        List<ServerPlayer> players = players(server, encounter.runId, encounter.level);
        if (players.isEmpty()) return;
        encounter.bossTicks++;
        ServerPlayer target = players.stream().min(java.util.Comparator.comparingDouble(player -> player.distanceToSqr(boss))).orElseThrow();
        if (encounter.stage == Stage.GATEKEEPER) {
            int slamEvery = boss.getHealth() <= boss.getMaxHealth() * .5F ? 50 : 100;
            if (encounter.bossTicks % slamEvery == 0) damage(players, boss, 5, 150, ParticleTypes.CRIT);
            else if (encounter.bossTicks % 40 == 0 && target.distanceToSqr(boss) <= 16) {
                target.hurtServer(encounter.level, encounter.level.damageSources().mobAttack(boss), 110);
                target.push(target.getX() - boss.getX(), .2, target.getZ() - boss.getZ());
            }
        } else if (encounter.stage == Stage.BEACON) {
            float ratio = boss.getHealth() / boss.getMaxHealth();
            int every = ratio > .7F ? 70 : ratio > .35F ? 50 : 35;
            if (encounter.bossTicks % every != 0) return;
            if (ratio > .7F) {
                target.hurtServer(encounter.level, encounter.level.damageSources().mobAttack(boss), 130);
                encounter.level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, target.getX(), target.getY() + 1, target.getZ(), 24, .4, .6, .4, .03);
            } else if (ratio > .35F) damage(players, boss, 7, 120, ParticleTypes.SOUL);
            else damage(players, boss, 6, 100, ParticleTypes.SOUL_FIRE_FLAME);
        }
    }

    private static void damage(List<ServerPlayer> players, Mob boss, double range, float amount, net.minecraft.core.particles.ParticleOptions particle) {
        ServerLevel level = (ServerLevel) boss.level();
        level.sendParticles(particle, boss.getX(), boss.getY() + 1, boss.getZ(), 32, 1.2, .8, 1.2, .04);
        for (ServerPlayer player : players) if (player.distanceToSqr(boss) <= range * range) player.hurtServer(level, level.damageSources().mobAttack(boss), amount);
    }

    private List<ServerPlayer> players(MinecraftServer server, UUID runId, ServerLevel level) {
        List<ServerPlayer> result = new ArrayList<>();
        for (Map.Entry<UUID, State> entry : playerRuns.entrySet()) if (entry.getValue().runId.equals(runId)) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null && player.level() == level) result.add(player);
        }
        return result;
    }

    private static BlockPos floor(UUID runId) { return new BlockPos(10000 + Math.floorMod(runId.hashCode(), 10000) * 32, 100, 10000 + Math.floorMod((int) (runId.getLeastSignificantBits() >>> 32), 10000) * 32); }
    private static void teleport(ServerPlayer player, BlockPos floor) { player.teleportTo(floor.getX() + .5, floor.getY() + 1, floor.getZ() + .5); }
    private static UUID spawnBoss(ServerLevel level, BlockPos position, String name, double health) {
        Mob boss = EntityType.ZOMBIE.create(level, EntitySpawnReason.EVENT);
        if (boss == null) throw new IllegalStateException("Boss could not spawn.");
        boss.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        boss.setHealth((float) health);
        boss.setCustomName(Component.literal(name));
        boss.setCustomNameVisible(true);
        boss.setPersistenceRequired(); // otherwise it despawns while the dying player respawns far away
        boss.setPos(position.getX() + .5, position.getY(), position.getZ() + .5);
        level.addFreshEntity(boss);
        return boss.getUUID();
    }
    private static boolean nearBrazier(ServerPlayer player, BlockPos floor) {
        for (int i = 0; i < 3; i++) if (player.distanceToSqr(floor.getX() - 4 + i * 4 + .5, floor.getY() + 1, floor.getZ() + 35.5) <= 9) return true;
        return false;
    }
    private static double health(RunDifficulty difficulty, double base) { return RunEncounterRules.bossHealth(difficulty, base); }

    public record DeathResult(UUID runId, boolean canRespawn, boolean partyDefeated) { }
    private enum Stage { GATEKEEPER, BRAZIER, BEACON, COMPLETED }
    private static final class State { private final UUID runId; private int revivesRemaining; private State(UUID runId, int revivesRemaining) { this.runId = runId; this.revivesRemaining = revivesRemaining; } }
    private static final class Encounter {
        private final UUID runId; private final RunDifficulty difficulty; private final BlockPos floor; private final ServerLevel level; private final int requiredInteractions;
        private Stage stage = Stage.GATEKEEPER; private UUID bossId; private int bossTicks; private final Map<UUID, Integer> brazierTicks = new HashMap<>();
        private Encounter(UUID runId, RunDifficulty difficulty, BlockPos floor, ServerLevel level, int partySize) { this.runId = runId; this.difficulty = difficulty; this.floor = floor; this.level = level; this.requiredInteractions = RunEncounterRules.requiredBrazierInteractions(partySize); }
    }
}
