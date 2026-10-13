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
    private final BossDamage bossDamage;
    private final java.util.function.Consumer<UUID> releaseDisconnected;
    public RunRuntime(BossDamage bossDamage) { this(bossDamage,id->{ }); }
    public RunRuntime(BossDamage bossDamage,java.util.function.Consumer<UUID> releaseDisconnected) {
        this.bossDamage=java.util.Objects.requireNonNull(bossDamage);
        this.releaseDisconnected=java.util.Objects.requireNonNull(releaseDisconnected);
    }
    @FunctionalInterface public interface BossDamage { void hit(ServerPlayer player, net.minecraft.world.entity.LivingEntity boss, float amount, boolean magic); }
    private final Map<UUID, State> playerRuns = new HashMap<>();
    private final Map<UUID, Encounter> encounters = new HashMap<>();
    private long tickCount;

    public void enter(ServerPlayer player, UUID runId, RunDifficulty difficulty, int partySize) {
        State previous=playerRuns.get(player.getUUID());
        if(previous!=null){
            if(!previous.runId.equals(runId))throw new IllegalArgumentException("Already in another run.");
            Encounter existing=encounters.get(runId);
            long now=System.currentTimeMillis();
            if(existing==null||existing.failed||now>=existing.deadline||(existing.pausedAt!=0&&now-existing.pausedAt>=300000))throw new IllegalStateException("Run is no longer available.");
            previous.connected=true; previous.disconnectedAt=0;
            if(existing.pausedAt!=0)existing.pausedAt=0;
            if(!inside(player,existing))teleport(player,existing);
            return;
        }
        Encounter encounter=encounters.get(runId);
        if(encounter==null){
            BlockPos position=floor(runId);
            for(Encounter occupied:encounters.values())if(position.equals(occupied.floor))throw new IllegalStateException("Run region is occupied.");
            encounter=new Encounter(runId,difficulty,position,(ServerLevel)player.level(),partySize);
            if(!encounter.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,bounds(encounter),entity->!(entity instanceof ServerPlayer)).isEmpty())throw new IllegalStateException("Occupied entities in run region.");
            encounter.terrain=BeaconTowerMap.capture(encounter.level,position);
            RunTerrainRecovery.prepare(encounter.level,runId,encounter.floor,encounter.terrain);
            encounters.put(runId,encounter);
            BeaconTowerMap.build(encounter.level,position);
            encounter.bossId=spawnBoss(encounter.level,position.offset(0,1,15),"철문수장",health(difficulty,10800));
            BeaconTowerBosses.style(encounter.level,encounter.bossId,false);
        }
        State state=new State(runId,difficulty==RunDifficulty.FIRST_STEP?5:3);
        state.returnLevel=(ServerLevel)player.level();state.returnPosition=player.position();state.returnMode=player.gameMode.getGameModeForPlayer();
        state.lastSafe=encounter.floor.getCenter().add(0,1,0);
        playerRuns.put(player.getUUID(),state);
        teleport(player,encounter);
    }
    public DeathResult recordDeath(ServerPlayer player) {
        State state=playerRuns.get(player.getUUID());
        if(state==null)return new DeathResult(null,false,false);
        if(state.dead)return new DeathResult(state.runId,false,false);
        state.dead=true;
        Encounter encounter=encounters.get(state.runId);
        boolean living=playerRuns.values().stream().anyMatch(member->member.runId.equals(state.runId)&&member.connected&&!member.dead);
        boolean defeated=!living&&(encounter.partySize>1||state.revivesRemaining==0);
        if(defeated)encounter.failed=true;
        return new DeathResult(state.runId,!defeated&&state.revivesRemaining>0,defeated);
    }
    public void afterRespawn(ServerPlayer oldPlayer,ServerPlayer newPlayer) {
        State state=playerRuns.get(newPlayer.getUUID());
        if(state==null)return;
        Encounter encounter=encounters.get(state.runId);
        if(encounter==null)return;
        if(!state.dead)return;
        if(state.revivesRemaining==0||encounter.failed){newPlayer.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);return;}
        state.revivesRemaining--;state.dead=false;
        teleport(newPlayer,encounter);
        newPlayer.setHealth(newPlayer.getMaxHealth()*.35F);
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
            // Keep the completed encounter until the database commit is acknowledged.
            return state.runId;
        }
        return null;
    }

    public void tick(MinecraftServer server) {
        tickCount++;
        for(ServerPlayer player:server.getPlayerList().getPlayers())if(runId(player)==null&&region(player)!=null)player.connection.disconnect(Component.literal("다른 공략 구역에는 입장할 수 없습니다."));
        long now=System.currentTimeMillis();
        for(Encounter encounter:new ArrayList<>(encounters.values())) {
            if(now>=encounter.deadline||(encounter.pausedAt!=0&&now-encounter.pausedAt>=300000)){close(server,encounter.runId);continue;}
            if(encounter.bossId!=null&&encounter.level.getEntity(encounter.bossId) instanceof Mob boss)boss.setNoAi(encounter.pausedAt!=0||encounter.failed);
        }
        for(var entry:playerRuns.entrySet()){
            ServerPlayer player=server.getPlayerList().getPlayer(entry.getKey());Encounter encounter=encounters.get(entry.getValue().runId);
            if(player==null||encounter==null||entry.getValue().dead)continue;
            if(!inside(player,encounter))player.teleportTo(encounter.level,entry.getValue().lastSafe.x,entry.getValue().lastSafe.y,entry.getValue().lastSafe.z,java.util.Set.of(),player.getYRot(),player.getXRot(),false);
            else if(player.onGround())entry.getValue().lastSafe=player.position();
        }
        for (Map.Entry<UUID, State> entry : playerRuns.entrySet()) {
            Encounter encounter = encounters.get(entry.getValue().runId);
            if (encounter == null || encounter.failed || encounter.pausedAt!=0 || entry.getValue().dead || !entry.getValue().connected || encounter.stage != Stage.BRAZIER) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            if (nearBrazier(player, encounter.floor)) encounter.brazierTicks.merge(entry.getKey(), 1, Integer::sum);
            else encounter.brazierTicks.remove(entry.getKey());
        }
        for (Encounter encounter : new ArrayList<>(encounters.values())) {
            if(encounter.failed||encounter.pausedAt!=0||encounter.stage==Stage.COMPLETED)continue;
            if (encounter.stage == Stage.BRAZIER) activateFinalBoss(encounter);
            tickBoss(server, encounter);
        }
    }

    public void leave(ServerPlayer player) { State state=playerRuns.get(player.getUUID());if(state!=null)close(((ServerLevel)player.level()).getServer(),state.runId); }
    public void disconnect(ServerPlayer player) {
        State state=playerRuns.get(player.getUUID());if(state==null)return;
        state.connected=false;state.disconnectedAt=System.currentTimeMillis();
        Encounter encounter=encounters.get(state.runId);
        if(encounter!=null&&playerRuns.values().stream().noneMatch(member->member.runId.equals(state.runId)&&member.connected)){encounter.pausedAt=System.currentTimeMillis();encounter.pausedTick=tickCount;}
    }
    public long pausedTicks(UUID runId){Encounter encounter=encounters.get(runId);return encounter==null||encounter.pausedAt==0?0:tickCount-encounter.pausedTick;}
    public java.util.List<UUID> participantIds(UUID runId){return playerRuns.entrySet().stream().filter(entry->entry.getValue().runId.equals(runId)).map(java.util.Map.Entry::getKey).toList();}
    public void closeAll(MinecraftServer server){for(UUID id:new ArrayList<>(encounters.keySet()))close(server,id);}
    public void close(MinecraftServer server,UUID runId) {
        Encounter encounter=encounters.get(runId);
        if(encounter!=null)encounter.failed=true;
        for(var entry:new ArrayList<>(playerRuns.entrySet()))if(entry.getValue().runId.equals(runId)){
            State state=entry.getValue();ServerPlayer player=server.getPlayerList().getPlayer(entry.getKey());
            if(player!=null){
                if(player.isSpectator())player.setGameMode(state.returnMode);
                player.teleportTo(state.returnLevel,state.returnPosition.x,state.returnPosition.y,state.returnPosition.z,java.util.Set.of(),player.getYRot(),player.getXRot(),false);
            }else releaseDisconnected.accept(entry.getKey());
            playerRuns.remove(entry.getKey());
        }
        if(encounter!=null){
            for(var entity:encounter.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,bounds(encounter),entity->!(entity instanceof ServerPlayer)))entity.discard();
            BeaconTowerMap.restore(encounter.level,encounter.terrain);
            RunTerrainRecovery.complete(encounter.level,runId);
            encounters.remove(runId);
        }
    }
    public java.util.Set<UUID> completedRuns(){return encounters.values().stream().filter(e->e.stage==Stage.COMPLETED).map(e->e.runId).collect(java.util.stream.Collectors.toSet());}
    public ServerPlayer participant(MinecraftServer server,UUID runId){for(var entry:playerRuns.entrySet())if(entry.getValue().runId.equals(runId)){var player=server.getPlayerList().getPlayer(entry.getKey());if(player!=null)return player;}return null;}
    public void loadedEntity(net.minecraft.world.entity.Entity entity) {
        if(entity instanceof ServerPlayer)return;
        UUID run=region(entity);
        if(entity.entityTags().stream().anyMatch(tag->tag.startsWith("dalmaru.run.")&&!tag.equals("dalmaru.run."+run)))entity.discard();
        else if(run!=null)entity.addTag("dalmaru.run."+run);
    }
    public boolean protectedBlock(ServerLevel level,BlockPos position){return encounters.values().stream().anyMatch(e->e.level==level&&bounds(e).contains(position.getCenter()));}
    private UUID region(net.minecraft.world.entity.Entity entity){for(var encounter:encounters.values())if(entity.level()==encounter.level&&bounds(encounter).contains(entity.position()))return encounter.runId;return null;}
    public boolean mayTarget(ServerPlayer player,net.minecraft.world.entity.LivingEntity target){
        if(target==player||target instanceof ServerPlayer||!player.isAlive()||player.isSpectator())return false;
        UUID run=runId(player),targetRun=region(target);
        State state=playerRuns.get(player.getUUID());
        return (state==null||state.connected&&!state.dead)&&java.util.Objects.equals(run,targetRun);
    }
    public boolean mayReceive(ServerPlayer player,net.minecraft.world.entity.Entity source){
        State state=playerRuns.get(player.getUUID());
        if(state!=null&&(state.dead||!state.connected))return false;
        return source==null||java.util.Objects.equals(runId(player),region(source));
    }
    public void move(ServerPlayer player,double distance,boolean backwards){
        if(!player.isAlive()||player.isSpectator()||!player.onGround())return;
        var look=player.getLookAngle();var horizontal=new net.minecraft.world.phys.Vec3(look.x,0,look.z).normalize().scale(backwards?-1:1);
        var origin=player.position();var safe=origin;State state=playerRuns.get(player.getUUID());Encounter encounter=state==null?null:encounters.get(state.runId);
        for(double step=.25;step<=distance;step+=.25){
            var candidate=origin.add(horizontal.scale(step));
            if(encounter!=null&&!bounds(encounter).deflate(1).contains(candidate))break;
            if(!player.level().noCollision(player,player.getBoundingBox().move(candidate.subtract(origin))))break;
            if(encounter==null&&encounters.values().stream().anyMatch(e->e.level==player.level()&&bounds(e).contains(candidate)))break;
            safe=candidate;
        }
        player.teleportTo(safe.x,safe.y,safe.z);player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);player.hurtMarked=true;
    }
    private static net.minecraft.world.phys.AABB bounds(Encounter e){return new net.minecraft.world.phys.AABB(e.floor.getX()-9,e.floor.getY(),e.floor.getZ()-6,e.floor.getX()+10,e.floor.getY()+6,e.floor.getZ()+75);}
    private static boolean inside(ServerPlayer player,Encounter encounter){return player.level()==encounter.level&&bounds(encounter).deflate(.6).contains(player.position());}
    public UUID runId(ServerPlayer player) { State state = playerRuns.get(player.getUUID()); return state == null ? null : state.runId; }
    public int revivesRemaining(ServerPlayer player) { State state = playerRuns.get(player.getUUID()); return state == null ? 0 : state.revivesRemaining; }
    public int bossDefense(UUID bossId, boolean magic) {
        for (Encounter encounter : encounters.values()) if (bossId.equals(encounter.bossId)) {
            int base = magic && encounter.stage == Stage.GATEKEEPER ? 55 : 80;
            return base + (encounter.difficulty == RunDifficulty.TRAINING ? 20 : 0);
        }
        return 0;
    }

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
            if (encounter.bossTicks % slamEvery == 0) damage(players, boss, 5, attack(encounter, 150), false, ParticleTypes.CRIT);
            else if (encounter.bossTicks % 60 == 0 && target.distanceToSqr(boss) <= 16) {
                bossDamage.hit(target, boss, attack(encounter, 130), false);
                target.knockback(.4, boss.getX() - target.getX(), boss.getZ() - target.getZ());
            }
            else if (encounter.bossTicks % 40 == 0 && target.distanceToSqr(boss) <= 16) {
                bossDamage.hit(target, boss, attack(encounter, 110), false);
            }
        } else if (encounter.stage == Stage.BEACON) {
            float ratio = boss.getHealth() / boss.getMaxHealth();
            int every = ratio > .7F ? 70 : ratio > .35F ? 50 : 35;
            if (encounter.bossTicks % every != 0) return;
            if (ratio > .7F) {
                bossDamage.hit(target, boss, attack(encounter, 130), false);
                encounter.level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, target.getX(), target.getY() + 1, target.getZ(), 24, .4, .6, .4, .03);
            } else if (ratio > .35F) damage(players, boss, 7, attack(encounter, 120), true, ParticleTypes.SOUL);
            else damage(players, boss, 6, attack(encounter, 180), true, ParticleTypes.SOUL_FIRE_FLAME);
        }
    }

    private void damage(List<ServerPlayer> players, Mob boss, double range, float amount, boolean magic, net.minecraft.core.particles.ParticleOptions particle) {
        ServerLevel level = (ServerLevel) boss.level();
        level.sendParticles(particle, boss.getX(), boss.getY() + 1, boss.getZ(), 32, 1.2, .8, 1.2, .04);
        for (ServerPlayer player : players) if (player.distanceToSqr(boss) <= range * range) bossDamage.hit(player, boss, amount, magic);
    }

    private List<ServerPlayer> players(MinecraftServer server, UUID runId, ServerLevel level) {
        List<ServerPlayer> result = new ArrayList<>();
        for (Map.Entry<UUID, State> entry : playerRuns.entrySet()) if (entry.getValue().runId.equals(runId)) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null && player.level() == level && player.isAlive() && !player.isSpectator() && entry.getValue().connected && !entry.getValue().dead) result.add(player);
        }
        return result;
    }

    private static BlockPos floor(UUID runId) { return new BlockPos(10000 + Math.floorMod(runId.hashCode(), 10000) * 128, 100, 10000 + Math.floorMod((int) (runId.getLeastSignificantBits() >>> 32), 10000) * 128); }
    private static void teleport(ServerPlayer player, Encounter encounter) {
        player.teleportTo(encounter.level,encounter.floor.getX()+.5,encounter.floor.getY()+1,encounter.floor.getZ()+.5,java.util.Set.of(),player.getYRot(),player.getXRot(),false);
    }
    private static UUID spawnBoss(ServerLevel level, BlockPos position, String name, double health) {
        Mob boss = EntityType.ZOMBIE.create(level, EntitySpawnReason.EVENT);
        if (boss == null) throw new IllegalStateException("Boss could not spawn.");
        float engineHealth = kr.dalmaru.combat.CombatUnits.maximumHealth(health);
        boss.getAttribute(Attributes.MAX_HEALTH).setBaseValue(engineHealth);
        boss.setHealth(engineHealth);
        // Only the scripted RPG patterns deal damage, not the zombie's extra melee attack.
        boss.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(0);
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
    private static float attack(Encounter encounter, int base) { return (float) (base * (encounter.difficulty == RunDifficulty.TRAINING ? 1.15 : 1.0)); }

    public record DeathResult(UUID runId, boolean canRespawn, boolean partyDefeated) { }
    private enum Stage { GATEKEEPER, BRAZIER, BEACON, COMPLETED }
    private static final class State { private final UUID runId; private int revivesRemaining; private boolean dead; private boolean connected=true; private long disconnectedAt; private net.minecraft.world.level.GameType returnMode; private ServerLevel returnLevel; private net.minecraft.world.phys.Vec3 returnPosition,lastSafe; private State(UUID runId, int revivesRemaining) { this.runId = runId; this.revivesRemaining = revivesRemaining; } }
    private static final class Encounter {
        private final UUID runId; private final RunDifficulty difficulty; private final BlockPos floor; private final ServerLevel level; private final int requiredInteractions;
        private final long deadline=System.currentTimeMillis()+2700000; private long pausedAt,pausedTick; private boolean failed; private int partySize; private Map<BlockPos,net.minecraft.world.level.block.state.BlockState> terrain;
        private Stage stage = Stage.GATEKEEPER; private UUID bossId; private int bossTicks; private final Map<UUID, Integer> brazierTicks = new HashMap<>();
        private Encounter(UUID runId, RunDifficulty difficulty, BlockPos floor, ServerLevel level, int partySize) { this.partySize=partySize; this.runId = runId; this.difficulty = difficulty; this.floor = floor; this.level = level; this.requiredInteractions = RunEncounterRules.requiredBrazierInteractions(partySize); }
    }
}
