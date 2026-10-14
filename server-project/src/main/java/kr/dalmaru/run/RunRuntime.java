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
    private final java.util.concurrent.ConcurrentMap<UUID,RunLifecycle> lifecycles=new java.util.concurrent.ConcurrentHashMap<>();
    private long tickCount;
    private static final net.minecraft.server.level.TicketType RUN_CHUNKS=new net.minecraft.server.level.TicketType(net.minecraft.server.level.TicketType.NO_TIMEOUT,net.minecraft.server.level.TicketType.FLAG_LOADING|net.minecraft.server.level.TicketType.FLAG_SIMULATION|net.minecraft.server.level.TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private static void keepRegionLoaded(Encounter encounter,boolean keep){
        for(int x=Math.floorDiv(encounter.floor.getX()-9,16);x<=Math.floorDiv(encounter.floor.getX()+9,16);x++)for(int z=Math.floorDiv(encounter.floor.getZ()-6,16);z<=Math.floorDiv(encounter.floor.getZ()+74,16);z++){
            var chunk=new net.minecraft.world.level.ChunkPos(x,z);
            if(keep)encounter.level.getChunkSource().addTicketWithRadius(RUN_CHUNKS,chunk,2);else encounter.level.getChunkSource().removeTicketWithRadius(RUN_CHUNKS,chunk,2);
        }
    }

    public boolean prepareEntry(UUID runId,RunLifecycle.Owner owner){
        RunLifecycle created=new RunLifecycle(owner),existing=lifecycles.putIfAbsent(runId,created);
        return existing==null||existing.owns(owner)&&(existing.phase()==RunLifecycle.Phase.PREPARING||existing.phase()==RunLifecycle.Phase.ACTIVE);
    }
    public boolean ownsEntry(UUID runId,RunLifecycle.Owner owner){RunLifecycle state=lifecycles.get(runId);return state!=null&&state.owns(owner)&&(state.phase()==RunLifecycle.Phase.PREPARING||state.phase()==RunLifecycle.Phase.ACTIVE);}
    public boolean preparing(UUID runId,RunLifecycle.Owner owner){RunLifecycle state=lifecycles.get(runId);return state!=null&&state.preparing(owner);}
    public boolean joinable(UUID runId){RunLifecycle state=lifecycles.get(runId);return state!=null&&state.joinable();}
    public RunLifecycle.Phase lifecycle(UUID runId){RunLifecycle state=lifecycles.get(runId);return state==null?null:state.phase();}
    public void enter(ServerPlayer player, UUID runId, RunDifficulty difficulty, int partySize,RunLifecycle.Owner owner) {
        RunLifecycle lifecycle=lifecycles.get(runId);
        if(lifecycle==null||!lifecycle.owns(owner))throw new IllegalStateException("Run entry owner is missing or stale.");
        if(lifecycle.phase()==RunLifecycle.Phase.CLOSING||lifecycle.phase()==RunLifecycle.Phase.CLOSED)throw new IllegalStateException("Run is closing.");
        State previous=playerRuns.get(player.getUUID());
        if(previous!=null){
            if(!previous.runId.equals(runId))throw new IllegalArgumentException("Already in another run.");
            Encounter existing=encounters.get(runId);
            long now=System.currentTimeMillis();
            if(existing==null||existing.failed||now>=existing.deadline||(existing.pausedAt!=0&&now-existing.pausedAt>=300000)||!lifecycle.joinable())throw new IllegalStateException("Run is no longer available.");
            previous.connected=true; previous.disconnectedAt=0;
            if(existing.pausedAt!=0)existing.pausedAt=0;
            if(!inside(player,existing))teleport(player,existing);
            return;
        }
        Encounter encounter=encounters.get(runId);
        if(encounter==null){
            if(!lifecycle.preparing(owner))throw new IllegalStateException("Run is not preparing.");
            BlockPos position=floor(runId);
            for(Encounter occupied:encounters.values())if(position.equals(occupied.floor))throw new IllegalStateException("Run region is occupied.");
            encounter=new Encounter(runId,difficulty,position,(ServerLevel)player.level(),partySize);
            if(!encounter.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,bounds(encounter),entity->!(entity instanceof ServerPlayer)).isEmpty())throw new IllegalStateException("Occupied entities in run region.");
            encounter.terrain=BeaconTowerMap.capture(encounter.level,position);
            RunTerrainRecovery.prepare(encounter.level,runId,encounter.floor,encounter.terrain);
            encounters.put(runId,encounter);keepRegionLoaded(encounter,true);
            BeaconTowerMap.build(encounter.level,position);
            spawnGroup(encounter,0);
            if(!lifecycle.activate(owner))throw new IllegalStateException("Run preparation was invalidated.");
        }else if(!lifecycle.joinable()){
            throw new IllegalStateException("Run lifecycle does not allow another entrant.");
        }
        State state=new State(runId,difficulty==RunDifficulty.FIRST_STEP?5:3);
        state.returnLevel=(ServerLevel)player.level();state.returnPosition=player.position();state.returnMode=player.gameMode.getGameModeForPlayer();
        state.lastSafe=encounter.floor.getCenter().add(0,1,0);
        playerRuns.put(player.getUUID(),state);
        teleport(player,encounter);
    }
    public boolean reconnect(ServerPlayer player,UUID runId){
        RunLifecycle lifecycle=lifecycles.get(runId);Encounter encounter=encounters.get(runId);
        if(lifecycle==null||!lifecycle.joinable()||encounter==null||encounter.failed)return false;
        enterExisting(player,runId,encounter);return true;
    }
    private void enterExisting(ServerPlayer player,UUID runId,Encounter existing){
        State previous=playerRuns.get(player.getUUID());long now=System.currentTimeMillis();
        if(previous!=null){if(!previous.runId.equals(runId))throw new IllegalArgumentException("Already in another run.");if(existing.failed||now>=existing.deadline||(existing.pausedAt!=0&&now-existing.pausedAt>=300000))throw new IllegalStateException("Run is no longer available.");previous.connected=true;previous.disconnectedAt=0;if(existing.pausedAt!=0)existing.pausedAt=0;if(!inside(player,existing))teleport(player,existing);return;}
        if(existing.failed||now>=existing.deadline||(existing.pausedAt!=0&&now-existing.pausedAt>=300000))throw new IllegalStateException("Run is no longer available.");
        State state=new State(runId,existing.difficulty==RunDifficulty.FIRST_STEP?5:3);state.returnLevel=(ServerLevel)player.level();state.returnPosition=player.position();state.returnMode=player.gameMode.getGameModeForPlayer();state.lastSafe=existing.floor.getCenter().add(0,1,0);playerRuns.put(player.getUUID(),state);teleport(player,existing);
    }
    public DeathResult recordDeath(ServerPlayer player) {
        State state=playerRuns.get(player.getUUID());
        if(state==null)return new DeathResult(null,false,false);
        if(state.dead)return new DeathResult(state.runId,false,false);
        state.dead=true;state.deathTick=tickCount;state.deathPosition=player.position();
        Encounter encounter=encounters.get(state.runId);
        double relative=player.getZ()-encounter.floor.getZ();state.deathZone=relative<27?0:relative<45?1:2;
        boolean living=playerRuns.values().stream().anyMatch(member->member.runId.equals(state.runId)&&member.connected&&!member.dead);
        boolean defeated=!living&&(encounter.partySize>1||state.revivesRemaining==0);
        if(defeated)encounter.failed=true;
        return new DeathResult(state.runId,!defeated&&state.revivesRemaining>0,defeated);
    }
    public boolean canRespawn(ServerPlayer player){
        State state=playerRuns.get(player.getUUID());
        if(state==null||!state.dead)return true;
        Encounter encounter=encounters.get(state.runId);
        return encounter==null||encounter.failed||state.revivesRemaining==0||RunEncounterRules.clickRevive(tickCount-state.deathTick);
    }
    private boolean autoRespawn(ServerPlayer player){
        if(player==null)return false;
        State state=playerRuns.get(player.getUUID());
        if(state==null)return false;
        Encounter encounter=encounters.get(state.runId);
        if(encounter==null||!state.connected||!state.dead||encounter.failed||state.revivesRemaining==0||!player.isDeadOrDying()||!RunEncounterRules.autoRevive(tickCount-state.deathTick))return false;
        player.connection.handleClientCommand(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        return true;
    }
    public void afterRespawn(ServerPlayer oldPlayer,ServerPlayer newPlayer) {
        State state=playerRuns.get(newPlayer.getUUID());
        if(state==null)return;
        Encounter encounter=encounters.get(state.runId);
        if(encounter==null)return;
        if(!state.dead)return;
        if(state.revivesRemaining==0||encounter.failed){newPlayer.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);return;}
        var position=revivePosition(newPlayer,encounter,state);
        newPlayer.teleportTo(encounter.level,position.x,position.y,position.z,java.util.Set.of(),newPlayer.getYRot(),newPlayer.getXRot(),false);
        state.revivesRemaining--;state.dead=false;state.lastSafe=position;
        newPlayer.setHealth(newPlayer.getMaxHealth()*.35F);
    }
    private net.minecraft.world.phys.Vec3 revivePosition(ServerPlayer player,Encounter encounter,State state){
        var origin=state.deathPosition==null?state.lastSafe:state.deathPosition;
        if(safeRevive(player,encounter,state.deathZone,origin))return origin;
        var candidates=new java.util.ArrayList<net.minecraft.world.phys.Vec3>();
        int min=state.deathZone==0?-5:state.deathZone==1?28:46,max=state.deathZone==0?26:state.deathZone==1?44:63;
        for(int x=-8;x<=8;x++)for(int z=min;z<=max;z++)candidates.add(encounter.floor.offset(x,1,z).getCenter().add(0,-.5,0));
        return candidates.stream().filter(pos->safeRevive(player,encounter,state.deathZone,pos)).min(java.util.Comparator.comparingDouble(pos->pos.distanceToSqr(origin))).orElseThrow(()->new IllegalStateException("No safe revive floor in combat area."));
    }
    private boolean safeRevive(ServerPlayer player,Encounter encounter,int zone,net.minecraft.world.phys.Vec3 position){
        double z=position.z-encounter.floor.getZ();if(!bounds(encounter).deflate(1).contains(position)||z<(zone==0?-5:zone==1?28:46)||z>(zone==0?26:zone==1?44:63))return false;
        var feet=BlockPos.containing(position);var below=encounter.level.getBlockState(feet.below());
        if(!encounter.level.getFluidState(feet).isEmpty()||below.is(Blocks.MAGMA_BLOCK)||below.is(Blocks.CAMPFIRE)||below.getCollisionShape(encounter.level,feet.below()).isEmpty())return false;
        return encounter.level.noCollision(player,player.getBoundingBox().move(position.subtract(player.position())));
    }
    public UUID bossKilled(ServerPlayer player, UUID bossId) {
        State state = playerRuns.get(player.getUUID());
        if (state == null) return null;
        Encounter encounter = encounters.get(state.runId);
        if (encounter == null || !bossId.equals(encounter.bossId)) return null;
        if (encounter.stage == Stage.GATEKEEPER) {
            encounter.stage = Stage.BRAZIER;
            spawnGroup(encounter,2);
            encounter.bossId = null;
            BeaconTowerMap.open(encounter.level, encounter.floor, 27);
            for (int i = 0; i < Math.min(3, encounter.partySize); i++) encounter.level.setBlock(encounter.floor.offset(-4 + i * 4, 1, 35), Blocks.CAMPFIRE.defaultBlockState(), 3);
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
            if(autoRespawn(player))continue;
            if(player==null||encounter==null||entry.getValue().dead)continue;
            if(!inside(player,encounter))player.teleportTo(encounter.level,entry.getValue().lastSafe.x,entry.getValue().lastSafe.y,entry.getValue().lastSafe.z,java.util.Set.of(),player.getYRot(),player.getXRot(),false);
            else if(player.onGround())entry.getValue().lastSafe=player.position();
        }
        for (Map.Entry<UUID, State> entry : playerRuns.entrySet()) {
            Encounter encounter = encounters.get(entry.getValue().runId);
            if (encounter == null || encounter.failed || encounter.pausedAt!=0 || entry.getValue().dead || !entry.getValue().connected || encounter.stage != Stage.BRAZIER) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            if (encounter.mobs.isEmpty() && nearBrazier(player, encounter.floor, encounter.partySize)) encounter.brazierTicks.merge(entry.getKey(), 1, Integer::sum);
            else encounter.brazierTicks.remove(entry.getKey());
        }
        for (Encounter encounter : new ArrayList<>(encounters.values())) {
            if(encounter.failed||encounter.pausedAt!=0||encounter.stage==Stage.COMPLETED)continue;
            tickMobs(server,encounter);
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
    public void closeAll(MinecraftServer server){
        RuntimeException failure=null;
        for(UUID id:new ArrayList<>(lifecycles.keySet()))try{close(server,id);}catch(RuntimeException error){if(failure==null)failure=new IllegalStateException("Failed to close one or more run runtimes.");failure.addSuppressed(error);}
        if(failure!=null)throw failure;
    }
    public void close(MinecraftServer server,UUID runId,RunLifecycle.Owner owner){RunLifecycle lifecycle=lifecycles.get(runId);if(lifecycle!=null&&lifecycle.owns(owner))close(server,runId);}
    public void close(MinecraftServer server,UUID runId) {
        RunLifecycle lifecycle=lifecycles.get(runId);
        if(lifecycle==null||!lifecycle.beginClose())return;
        Encounter encounter=encounters.get(runId);
        RuntimeException failure=null;
        try{
            if(encounter!=null)encounter.failed=true;
            for(var entry:new ArrayList<>(playerRuns.entrySet()))if(entry.getValue().runId.equals(runId)){
                State state=entry.getValue();ServerPlayer player=server.getPlayerList().getPlayer(entry.getKey());
                try{if(player!=null){if(player.isSpectator())player.setGameMode(state.returnMode);player.teleportTo(state.returnLevel,state.returnPosition.x,state.returnPosition.y,state.returnPosition.z,java.util.Set.of(),player.getYRot(),player.getXRot(),false);}else releaseDisconnected.accept(entry.getKey());}
                catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
                finally{playerRuns.remove(entry.getKey());}
            }
            if(encounter!=null){
                try{
                    for(UUID id:encounter.mobs.keySet()){var mob=encounter.level.getEntity(id);if(mob!=null)try{mob.discard();}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}}
                    for(var entity:encounter.level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,bounds(encounter),entity->!(entity instanceof ServerPlayer)))try{entity.discard();}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
                    BeaconTowerMap.restore(encounter.level,encounter.terrain);RunTerrainRecovery.complete(encounter.level,runId);
                }catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
                finally{try{keepRegionLoaded(encounter,false);}finally{encounters.remove(runId);}}
            }
        }finally{
            lifecycle.closed();
        }
        if(failure!=null)throw failure;
    }
    public java.util.Set<UUID> completedRuns(){return encounters.values().stream().filter(e->e.stage==Stage.COMPLETED).map(e->e.runId).collect(java.util.stream.Collectors.toSet());}
    public ServerPlayer participant(MinecraftServer server,UUID runId){for(var entry:playerRuns.entrySet())if(entry.getValue().runId.equals(runId)){var player=server.getPlayerList().getPlayer(entry.getKey());if(player!=null)return player;}return null;}
    public void mobDied(net.minecraft.world.entity.LivingEntity mob){
        if(!mob.isDeadOrDying())return;
        for(Encounter encounter:encounters.values())if(mob.level()==encounter.level&&mob.entityTags().contains("dalmaru.run."+encounter.runId)&&encounter.mobs.remove(mob.getUUID())!=null)return;
    }
    public void loadedEntity(net.minecraft.world.entity.Entity entity) {
        if(entity instanceof ServerPlayer)return;
        for(Encounter encounter:encounters.values())if(entity.level()==encounter.level&&encounter.mobs.containsKey(entity.getUUID())&&entity.entityTags().contains("dalmaru.run."+encounter.runId)){
            if(!bounds(encounter).deflate(1).contains(entity.position()))entity.setPos(encounter.floor.getX()+.5,encounter.floor.getY()+1,encounter.floor.getZ()+(encounter.group<2?10:encounter.group==2?35:52)+.5);
            return;
        }
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
    public void moveDirection(ServerPlayer player,net.minecraft.world.phys.Vec3 direction,double distance){
        if(!player.isAlive()||player.isSpectator())return;
        var horizontal=new net.minecraft.world.phys.Vec3(direction.x,0,direction.z).normalize();
        var origin=player.position();var safe=origin;State state=playerRuns.get(player.getUUID());Encounter encounter=state==null?null:encounters.get(state.runId);
        for(double step=.1;step<=distance+.1001;step+=.1){
            var candidate=origin.add(horizontal.scale(Math.min(step,distance)));
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
    public boolean isBoss(UUID id){return encounters.values().stream().anyMatch(e->id.equals(e.bossId));}
    public int bossDefense(UUID bossId, boolean magic) {
        for(Encounter encounter:encounters.values()){var kind=encounter.mobs.get(bossId);if(kind!=null)return (magic?kind.magicDefense:kind.physicalDefense)+(encounter.difficulty==RunDifficulty.TRAINING?20:0);}
        for (Encounter encounter : encounters.values()) if (bossId.equals(encounter.bossId)) {
            int base = magic && encounter.stage == Stage.GATEKEEPER ? 55 : 80;
            return base + (encounter.difficulty == RunDifficulty.TRAINING ? 20 : 0);
        }
        return 0;
    }

    private void activateFinalBoss(Encounter encounter) {
        long ready = playerRuns.entrySet().stream().filter(entry -> entry.getValue().runId.equals(encounter.runId)).filter(entry -> encounter.brazierTicks.getOrDefault(entry.getKey(), 0) >= 80).count();
        if (ready < encounter.requiredInteractions || !encounter.mobs.isEmpty()) return;
        encounter.brazierTicks.clear();
        encounter.stage=Stage.APPROACH;
        BeaconTowerMap.open(encounter.level,encounter.floor,45);
        spawnGroup(encounter,3);
    }

    private void spawnFinalBoss(Encounter encounter) {
        encounter.stage = Stage.BEACON;
        BeaconTowerMap.open(encounter.level, encounter.floor, 45);
        encounter.bossId = spawnBoss(encounter.level, encounter.floor.offset(0, 1, 54), "원혼봉수장", health(encounter.difficulty, 14400));
        BeaconTowerBosses.style(encounter.level, encounter.bossId, true);
    }

    private void spawnGroup(Encounter encounter,int group) {
        encounter.group=group;
        int index=0;
        for(var kind:BeaconTowerMobs.group(group))spawnMob(encounter,kind,index++);
    }
    private void spawnMob(Encounter encounter,BeaconTowerMobs.Kind kind,int index) {
        int z=switch(encounter.group){case 0->6;case 1->18;case 2->31;default->49;};
        Mob mob=EntityType.HUSK.create(encounter.level,EntitySpawnReason.EVENT);
        if(mob==null)throw new IllegalStateException("Dungeon mob could not spawn.");
        float hp=kr.dalmaru.combat.CombatUnits.maximumHealth(health(encounter.difficulty,kind.health)*BeaconTowerMobs.healthScale(encounter.partySize));
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(hp);mob.setHealth(hp);
        mob.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(0);
        mob.setNoAi(true);mob.setPersistenceRequired();mob.setCustomName(Component.literal(kind.label));mob.setCustomNameVisible(true);
        mob.addTag("dalmaru.mob."+kind.name().toLowerCase(java.util.Locale.ROOT));mob.addTag("dalmaru.run."+encounter.runId);
        if(kind==BeaconTowerMobs.Kind.COMMANDER)mob.addTag("dalmaru.kind.elite");
        if(kind==BeaconTowerMobs.Kind.SHIELD)mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.SHIELD));
        if(kind==BeaconTowerMobs.Kind.ARCHER)mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOW));
        mob.setPos(encounter.floor.getX()-4+(index%5)*2+.5,encounter.floor.getY()+1,encounter.floor.getZ()+z+(index/5)*3+.5);
        encounter.mobs.put(mob.getUUID(),kind);
        if(!encounter.level.addFreshEntity(mob)){encounter.mobs.remove(mob.getUUID());throw new IllegalStateException("Dungeon mob registration failed.");}
    }
    private void tickMobs(MinecraftServer server,Encounter encounter) {
        encounter.mobTicks++;
        var participants=players(server,encounter.runId,encounter.level);
        for(var entry:new ArrayList<>(encounter.mobs.entrySet())) {
            var entity=encounter.level.getEntity(entry.getKey());
            if(!(entity instanceof Mob mob)||mob.isRemoved())continue;
            if(mob.isDeadOrDying()){mobDied(mob);continue;}
            if(!bounds(encounter).deflate(1).contains(mob.position()))mob.teleportTo(encounter.floor.getX()+.5,encounter.floor.getY()+1,encounter.floor.getZ()+(encounter.group<2?10:encounter.group==2?35:52)+.5);
            var kind=entry.getValue();
            if(kind==BeaconTowerMobs.Kind.COMMANDER&&!encounter.summoned&&mob.getHealth()<=mob.getMaxHealth()*.5F){encounter.summoned=true;spawnMob(encounter,BeaconTowerMobs.Kind.SOLDIER,5);spawnMob(encounter,BeaconTowerMobs.Kind.SOLDIER,6);}
            if(participants.isEmpty()||(kind.magic&&mob.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)))continue;
            var target=participants.stream().min(java.util.Comparator.comparingDouble(p->p.distanceToSqr(mob))).orElseThrow();
            mob.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,target.getEyePosition());
            double distance=target.distanceToSqr(mob);
            if(distance>4){
                var direction=target.position().subtract(mob.position()).multiply(1,0,1).normalize().scale(Math.min(.12,mob.getAttributeValue(Attributes.MOVEMENT_SPEED)*.5));
                var position=mob.position().add(direction);
                if(bounds(encounter).deflate(1).contains(position)&&encounter.level.noCollision(mob,mob.getBoundingBox().move(direction)))mob.teleportTo(position.x,position.y,position.z);
            }
            if(encounter.mobTicks%kind.interval==0&&distance<=kind.range*kind.range&&mob.hasLineOfSight(target)){
                bossDamage.hit(target,mob,(float)(attack(encounter,kind.damage)*BeaconTowerMobs.damageScale(encounter.partySize)),kind.magic);
                if(kind.magic)encounter.level.sendParticles(kind==BeaconTowerMobs.Kind.EMBER?ParticleTypes.FLAME:ParticleTypes.SOUL,target.getX(),target.getY()+1,target.getZ(),8,.2,.2,.2,.01);
            }
        }
        if(!encounter.mobs.isEmpty())return;
        if(encounter.stage==Stage.GATEKEEPER&&encounter.bossId==null){
            if(encounter.group==0){spawnGroup(encounter,1);return;}
            encounter.bossId=spawnBoss(encounter.level,encounter.floor.offset(0,1,22),"철문수장",health(encounter.difficulty,10800));BeaconTowerBosses.style(encounter.level,encounter.bossId,false);
        }else if(encounter.stage==Stage.APPROACH)spawnFinalBoss(encounter);
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
    private static boolean nearBrazier(ServerPlayer player, BlockPos floor, int partySize) {
        for (int i = 0; i < Math.min(3,partySize); i++) if (player.distanceToSqr(floor.getX() - 4 + i * 4 + .5, floor.getY() + 1, floor.getZ() + 35.5) <= 9) return true;
        return false;
    }
    private static double health(RunDifficulty difficulty, double base) { return RunEncounterRules.bossHealth(difficulty, base); }
    private static float attack(Encounter encounter, int base) { return (float) (base * (encounter.difficulty == RunDifficulty.TRAINING ? 1.15 : 1.0)); }

    public record DeathResult(UUID runId, boolean canRespawn, boolean partyDefeated) { }
    private enum Stage { GATEKEEPER, BRAZIER, APPROACH, BEACON, COMPLETED }
    private static final class State { private final UUID runId; private int revivesRemaining; private boolean dead; private boolean connected=true; private long disconnectedAt,deathTick; private net.minecraft.world.level.GameType returnMode; private ServerLevel returnLevel; private net.minecraft.world.phys.Vec3 returnPosition,lastSafe,deathPosition;private int deathZone; private State(UUID runId, int revivesRemaining) { this.runId = runId; this.revivesRemaining = revivesRemaining; } }
    private static final class Encounter {
        private final UUID runId; private final RunDifficulty difficulty; private final BlockPos floor; private final ServerLevel level; private final int requiredInteractions;
        private final long deadline=System.currentTimeMillis()+2700000; private long pausedAt,pausedTick; private boolean failed; private int partySize; private Map<BlockPos,net.minecraft.world.level.block.state.BlockState> terrain;
        private final Map<UUID,BeaconTowerMobs.Kind> mobs=new HashMap<>(); private int group,mobTicks; private boolean summoned;
        private Stage stage = Stage.GATEKEEPER; private UUID bossId; private int bossTicks; private final Map<UUID, Integer> brazierTicks = new HashMap<>();
        private Encounter(UUID runId, RunDifficulty difficulty, BlockPos floor, ServerLevel level, int partySize) { this.partySize=partySize; this.runId = runId; this.difficulty = difficulty; this.floor = floor; this.level = level; this.requiredInteractions = RunEncounterRules.requiredBrazierInteractions(partySize); }
    }
}
