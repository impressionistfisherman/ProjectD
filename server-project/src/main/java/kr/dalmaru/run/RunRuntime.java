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

public final class RunRuntime implements RunMechanicAccess {
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger(RunRuntime.class);
    private final BossDamage bossDamage;
    private final java.util.function.Consumer<UUID> releaseDisconnected;
    private final LingerDamage lingerDamage;
    private final Map<UUID, UUID> lingerBodies = new HashMap<>();
    public RunRuntime(BossDamage bossDamage) { this(bossDamage,id->{ }); }
    public RunRuntime(BossDamage bossDamage,java.util.function.Consumer<UUID> releaseDisconnected) { this(bossDamage,releaseDisconnected,(player,body,source,amount,magic)->{}); }
    public RunRuntime(BossDamage bossDamage,java.util.function.Consumer<UUID> releaseDisconnected,LingerDamage lingerDamage) {
        this.bossDamage=java.util.Objects.requireNonNull(bossDamage);
        this.releaseDisconnected=java.util.Objects.requireNonNull(releaseDisconnected);
        this.lingerDamage=java.util.Objects.requireNonNull(lingerDamage);
        current=this;
    }
    /** Null source denotes a run hazard; damage still uses the normal combat pipeline. */
    @FunctionalInterface public interface BossDamage { void hit(ServerPlayer player, net.minecraft.world.entity.LivingEntity boss, float amount, boolean magic); }
    @FunctionalInterface public interface LingerDamage { void hit(UUID playerId, net.minecraft.world.entity.LivingEntity body, net.minecraft.world.entity.LivingEntity source, float amount, boolean magic); }
    /** The roster closes to invites and accepts when the first combat stage (GATEKEEPER) starts. */
    private final PartyActivator partyActivator = new PartyActivator();
    private final Map<UUID, State> playerRuns = new HashMap<>();
    private static RunRuntime current;
    private final Map<UUID, Encounter> encounters = new HashMap<>();
    private final java.util.concurrent.ConcurrentMap<UUID,RunLifecycle> lifecycles=new java.util.concurrent.ConcurrentHashMap<>();
    private long tickCount;
    private java.util.function.Consumer<ServerPlayer> mechanicAction=player->{};
    public void mechanicAction(java.util.function.Consumer<ServerPlayer> listener){mechanicAction=java.util.Objects.requireNonNull(listener);}
    @Override public Result startBrazier(ServerPlayer player,UUID runId,long revision,BlockPos position) {
        Result rejected=validateMechanic(player,runId,revision);
        if(rejected!=null)return rejected;
        Encounter encounter=encounters.get(runId);
        int index=-1;for(int i=0;i<encounter.brazier.count();i++)if(brazierPosition(encounter,i).equals(position))index=i;
        if(index<0)return Result.WRONG_PHASE;
        double radius=encounter.content.mechanics().brazier().interactionRadius();
        if(player.distanceToSqr(position.getCenter())>radius*radius)return Result.OUT_OF_RANGE;
        var hit=encounter.level.clip(new net.minecraft.world.level.ClipContext(player.getEyePosition(),position.getCenter(),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,player));
        if(hit.getType()!=net.minecraft.world.phys.HitResult.Type.MISS&&!hit.getBlockPos().equals(position))return Result.NO_LINE_OF_SIGHT;
        Result result=encounter.brazier.start(player.getUUID(),index,player.position());
        if(result==Result.STARTED)contributionInput(player);
        return result;
    }
    @Override public Result cancelBrazier(ServerPlayer player,UUID runId,long revision) {
        Result rejected=validateMechanic(player,runId,revision);
        if(rejected!=null)return rejected;
        return cancelChannel(player,CancelReason.REQUEST)?Result.CANCELLED:Result.NOT_CHANNELING;
    }
    private Result validateMechanic(ServerPlayer player,UUID runId,long revision) {
        State state=playerRuns.get(player.getUUID());
        if(state==null||!state.runId.equals(runId)||!state.connected)return Result.NOT_PARTICIPANT;
        Encounter e=encounters.get(runId);
        if(e==null||e.brazier==null||e.failed||e.pausedAt!=0)return Result.UNAVAILABLE;
        if(e.revision!=revision)return Result.STALE_REVISION;
        if(e.stage!=Stage.BRAZIER||!e.mobs.isEmpty())return Result.WRONG_PHASE;
        if(state.dead||!player.isAlive()||player.isSpectator())return Result.DEAD;
        if(player.level()!=e.level)return Result.WRONG_PHASE;
        return null;
    }
    public void skillUsed(UUID playerId){
        State state=playerRuns.get(playerId);Encounter e=state==null?null:encounters.get(state.runId);
        if(e!=null){ServerPlayer player=e.level.getServer().getPlayerList().getPlayer(playerId);if(player!=null)skillUsed(player);}
    }
    @Override public void skillUsed(ServerPlayer player){cancelChannel(player,CancelReason.SKILL);}
    @Override public void receivedHit(ServerPlayer player){cancelChannel(player,CancelReason.HIT);}
    private boolean cancelChannel(ServerPlayer player,CancelReason reason) {
        State state=playerRuns.get(player.getUUID());Encounter e=state==null?null:encounters.get(state.runId);
        if(e==null||e.brazier==null||!e.brazier.cancel(player.getUUID()))return false;
        player.sendSystemMessage(Component.literal("화로 조작 취소: "+switch(reason){
            case REQUEST->"직접 취소";case MOVED->"0.5블록 이상 이동";case HIT->"피격";case SKILL->"기술 사용";
            case DEATH->"사망";case DISCONNECT->"연결 종료";case DIMENSION->"차원 변경";case PHASE->"단계 변경";}));
        return true;
    }
    @Override public Snapshot mechanicSnapshot(ServerPlayer player) {
        State state=playerRuns.get(player.getUUID());Encounter e=state==null?null:encounters.get(state.runId);
        if(e==null||!state.connected)return null;
        var braziers=new ArrayList<BrazierView>();
        if(e.brazier!=null&&e.stage==Stage.BRAZIER)for(int i=0;i<e.brazier.count();i++){
            UUID owner=e.brazier.owner(i);braziers.add(new BrazierView(brazierPosition(e,i),e.brazier.lit(i),owner,owner==null?0:e.brazier.remaining(owner)));
        }
        boolean neglectActive=e.neglect!=null&&!e.failed&&e.stage==Stage.BRAZIER&&e.mobs.isEmpty();
        return new Snapshot(e.runId,e.revision,Phase.valueOf(e.stage.name()),e.partySize,braziers,neglectActive?e.neglect.state():Neglect.IDLE,neglectActive?e.neglect.remaining():0,bossViews(e));
    }
    private static BlockPos brazierPosition(Encounter e,int index){return e.floor.offset(-4+index*4,1,35);}
    private void tickBraziers(MinecraftServer server,Encounter e) {
        if(e.brazier==null||!e.mobs.isEmpty())return;
        var positions=new HashMap<UUID,net.minecraft.world.phys.Vec3>();
        for(var entry:playerRuns.entrySet())if(entry.getValue().runId.equals(e.runId)){
            ServerPlayer player=server.getPlayerList().getPlayer(entry.getKey());
            if(player!=null&&entry.getValue().connected&&!entry.getValue().dead&&player.isAlive()&&!player.isSpectator()&&player.level()==e.level)positions.put(entry.getKey(),player.position());
        }
        var result=e.brazier.tick(positions);
        for(UUID id:result.completed()){
            ServerPlayer player=server.getPlayerList().getPlayer(id);if(player!=null)mechanicAction.accept(player);
        }
        if(!result.completed().isEmpty())for(int i=0;i<e.brazier.count();i++)if(e.brazier.lit(i))e.level.setBlock(brazierPosition(e,i),Blocks.CAMPFIRE.defaultBlockState(),3);
        tickNeglect(server,e,!result.completed().isEmpty());
        for(UUID id:result.unsynchronized()){
            ServerPlayer player=server.getPlayerList().getPlayer(id);if(player!=null)player.sendSystemMessage(Component.literal("동시 점화 시간이 맞지 않아 취소했습니다. 함께 다시 조작해 주세요."));
        }
    }
    private void tickNeglect(MinecraftServer server,Encounter e,boolean success) {
        var event=e.neglect.tick(tickCount,e.brazier.channeling(),success);
        var online=players(server,e.runId,e.level);
        if(event.warningStarted()||event.cleared())for(ServerPlayer player:online){
            player.sendSystemMessage(Component.literal(event.cleared()?"화로 점화 성공: 방치 경고를 해제했습니다."
                    :"화로 방치 경고: 10초 뒤 위험 단계, 이후 3초마다 최대 체력 15% 피해입니다. 점화에 성공해야 해제됩니다. (임시)"));
            player.playSound(net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH,.8F,event.cleared()?1.2F:.7F);
        }
        if(!event.pulse())return;
        double fraction=e.content.mechanics().brazier().maximumHealthFraction();
        var hitOwners=new java.util.HashSet<UUID>();
        for(ServerPlayer player:online){
            hitOwners.add(player.getUUID());
            bossDamage.hit(player,null,(float)BrazierNeglect.damage(
                    kr.dalmaru.combat.CombatUnits.toRpg(player.getMaxHealth()),fraction),true);
        }
        long now=System.currentTimeMillis();
        for(var entry:new ArrayList<>(lingerBodies.entrySet())){
            State member=playerRuns.get(entry.getKey());
            if(member==null||!member.runId.equals(e.runId)||!BrazierNeglect.targetLinger(member.connected,member.dead,
                    RunEncounterRules.lingering(member.disconnectedAt,now),hitOwners.contains(entry.getKey())))continue;
            if(!(e.level.getEntity(entry.getValue()) instanceof Mob body)||!body.isAlive())continue;
            lingerDamage.hit(entry.getKey(),body,null,(float)BrazierNeglect.damage(
                    kr.dalmaru.combat.CombatUnits.toRpg(body.getMaxHealth()),fraction),true);
        }
    }
    public void contributionInput(ServerPlayer player){State state=playerRuns.get(player.getUUID());if(state!=null&&state.connected&&!state.dead&&!player.isSpectator())state.contribution.input(tickCount);}
    public boolean recordContribution(ServerPlayer player,UUID characterId,RunContribution.Action action){
        State state=playerRuns.get(player.getUUID());if(state==null||state.dead||!state.connected||player.isSpectator())return false;
        Encounter encounter=encounters.get(state.runId);if(encounter==null||encounter.failed||encounter.pausedAt!=0||encounter.stage==Stage.COMPLETED||!inside(player,encounter))return false;
        if(!player.getLastClientInput().equals(net.minecraft.world.entity.player.Input.EMPTY))state.contribution.input(tickCount);
        if(!state.contribution.record(action,tickCount))return false;
        state.characterId=java.util.Objects.requireNonNull(characterId);return true;
    }
    public java.util.Map<UUID,RunContribution.Action> contributions(UUID runId){
        var result=new java.util.HashMap<UUID,RunContribution.Action>();
        for(State state:playerRuns.values())if(state.runId.equals(runId)&&state.characterId!=null&&state.contribution.action()!=null)result.put(state.characterId,state.contribution.action());
        return java.util.Map.copyOf(result);
    }
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
    public void enter(ServerPlayer player, UUID runId, RunDifficulty difficulty, int partySize,RunLifecycle.Owner owner,kr.dalmaru.content.ContentCatalog content) {
        RunLifecycle lifecycle=lifecycles.get(runId);
        if(lifecycle==null||!lifecycle.owns(owner))throw new IllegalStateException("Run entry owner is missing or stale.");
        if(lifecycle.phase()==RunLifecycle.Phase.CLOSING||lifecycle.phase()==RunLifecycle.Phase.CLOSED)throw new IllegalStateException("Run is closing.");
        if(!content.run("run.bongsudae").enabled())throw new IllegalStateException("Run content disabled.");
        Encounter pinned=encounters.get(runId);
        if(pinned!=null&&!pinned.content.snapshot().equals(content.snapshot()))throw new IllegalStateException("Run content changed.");
        State previous=playerRuns.get(player.getUUID());
        if(previous!=null){
            if(!previous.runId.equals(runId))throw new IllegalArgumentException("Already in another run.");
            Encounter existing=encounters.get(runId);
            long now=System.currentTimeMillis();
            if(existing==null||existing.failed||now>=existing.deadline||(kr.dalmaru.run.RunEncounterRules.pauseExpired(existing.pausedAt,now))||!lifecycle.joinable())throw new IllegalStateException("Run is no longer available.");
            if(!kr.dalmaru.run.RunEncounterRules.reconnectAllowed(previous.disconnectedAt,now))throw new IllegalStateException("Reconnect window has expired.");previous.connected=true; previous.disconnectedAt=0;dropLinger(player.getUUID());
            if(existing.pausedAt!=0)existing.pausedAt=0;
            if(!inside(player,existing))teleport(player,existing);
            return;
        }
        Encounter encounter=encounters.get(runId);
        if(encounter==null){
            if(!lifecycle.preparing(owner))throw new IllegalStateException("Run is not preparing.");
            BlockPos position=floor(runId);
            for(Encounter occupied:encounters.values())if(position.equals(occupied.floor))throw new IllegalStateException("Run region is occupied.");
            encounter=new Encounter(runId,difficulty,position,(ServerLevel)player.level(),partySize,content);
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
        if(removedMember(encounter,player.getUUID()))throw new IllegalStateException("Member was removed from this run.");
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
        if(previous!=null){if(!previous.runId.equals(runId))throw new IllegalArgumentException("Already in another run.");if(existing.failed||now>=existing.deadline||(kr.dalmaru.run.RunEncounterRules.pauseExpired(existing.pausedAt,now)))throw new IllegalStateException("Run is no longer available.");if(!kr.dalmaru.run.RunEncounterRules.reconnectAllowed(previous.disconnectedAt,now))throw new IllegalStateException("Reconnect window has expired.");previous.connected=true;previous.disconnectedAt=0;dropLinger(player.getUUID());if(existing.pausedAt!=0)existing.pausedAt=0;if(!inside(player,existing))teleport(player,existing);return;}
        if(existing.failed||now>=existing.deadline||(kr.dalmaru.run.RunEncounterRules.pauseExpired(existing.pausedAt,now)))throw new IllegalStateException("Run is no longer available.");
        if(removedMember(existing,player.getUUID()))throw new IllegalStateException("Member was removed from this run.");
        State state=new State(runId,existing.difficulty==RunDifficulty.FIRST_STEP?5:3);state.returnLevel=(ServerLevel)player.level();state.returnPosition=player.position();state.returnMode=player.gameMode.getGameModeForPlayer();state.lastSafe=existing.floor.getCenter().add(0,1,0);playerRuns.put(player.getUUID(),state);teleport(player,existing);
    }
    public DeathResult recordDeath(ServerPlayer player) {
        State state=playerRuns.get(player.getUUID());
        if(state==null)return new DeathResult(null,false,false);
        if(state.dead)return new DeathResult(state.runId,false,false);
        cancelChannel(player,CancelReason.DEATH);
        state.dead=true;state.deathTick=tickCount;state.deathPosition=player.position();
        Encounter encounter=encounters.get(state.runId);
        double relative=player.getZ()-encounter.floor.getZ();state.deathZone=relative<27?0:relative<45?1:2;
        boolean living=playerRuns.values().stream().anyMatch(member->member.runId.equals(state.runId)&&kr.dalmaru.run.RunEncounterRules.countsAsSurvivor(member.connected,member.dead,member.disconnectedAt,System.currentTimeMillis()));
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
    public ReviveStatus reviveStatus(ServerPlayer player){
        State state=playerRuns.get(player.getUUID());
        if(state==null||!state.dead||!player.isDeadOrDying())return null;
        Encounter encounter=encounters.get(state.runId);
        if(encounter==null)return null;
        boolean spectator=encounter.failed||state.revivesRemaining==0;
        long elapsed=tickCount-state.deathTick;
        return new ReviveStatus(state.revivesRemaining,spectator?0:(int)Math.max(0,100-elapsed),spectator?0:(int)Math.max(0,300-elapsed),spectator);
    }
    private boolean autoRespawn(ServerPlayer player){
        if(player==null)return false;
        State state=playerRuns.get(player.getUUID());
        if(state==null)return false;
        Encounter encounter=encounters.get(state.runId);
        if(encounter==null||!state.connected||!state.dead||encounter.failed||state.revivesRemaining==0||!player.isDeadOrDying()||!RunEncounterRules.autoRevive(tickCount-state.deathTick))return false;
        try {
            player.connection.handleClientCommand(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            return true;
        } catch(RuntimeException error) {
            LOGGER.warn("Automatic run respawn failed for {}: {}",player.getUUID(),error.getClass().getName());
            return false;
        }
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
            encounter.stage = Stage.BRAZIER;encounter.revision++;
            spawnGroup(encounter,2);
            encounter.bossId = null;
            BeaconTowerMap.open(encounter.level, encounter.floor, 27);
            for (int i = 0; i < Math.min(3, encounter.partySize); i++) encounter.level.setBlock(encounter.floor.offset(-4 + i * 4, 1, 35), Blocks.CAMPFIRE.defaultBlockState().setValue(net.minecraft.world.level.block.CampfireBlock.LIT,encounter.brazier==null), 3);
            player.sendSystemMessage(Component.literal(encounter.brazier==null
                    ? "봉수로를 정화하세요. 화로 근처에서 4초간 버티면 됩니다."
                    : "적을 처치한 뒤 화로를 우클릭해 4초간 조작하세요. 이동·피격·기술 사용 시 취소됩니다. (임시)"));
            return null;
        }
        if (encounter.stage == Stage.BEACON) {
            encounter.stage = Stage.COMPLETED;encounter.revision++;
            BeaconTowerMap.open(encounter.level, encounter.floor, 64);
            encounter.level.setBlock(encounter.floor.offset(0, 1, 70), Blocks.CHEST.defaultBlockState(), 3);
            // Keep the completed encounter until the database commit is acknowledged.
            return state.runId;
        }
        return null;
    }

    public void tick(MinecraftServer server) {
        tickCount++;
        for(ServerPlayer player:server.getPlayerList().getPlayers())if(!player.getLastClientInput().equals(net.minecraft.world.entity.player.Input.EMPTY))contributionInput(player);
        for(ServerPlayer player:server.getPlayerList().getPlayers())if(runId(player)==null&&region(player)!=null)player.connection.disconnect(Component.literal("다른 공략 구역에는 입장할 수 없습니다."));
        long now=System.currentTimeMillis();
        expireLinger(server,now);
        tickExits(server,now);
        for(Encounter encounter:new ArrayList<>(encounters.values())) {
            pauseIfQuiet(encounter,now);
            if(now>=encounter.deadline||(kr.dalmaru.run.RunEncounterRules.pauseExpired(encounter.pausedAt,now))){close(server,encounter.runId);continue;}
            if(encounter.bossId!=null&&encounter.level.getEntity(encounter.bossId) instanceof Mob boss)boss.setNoAi(encounter.pausedAt!=0||encounter.failed);
        }
        for(var entry:playerRuns.entrySet()){
            ServerPlayer player=server.getPlayerList().getPlayer(entry.getKey());Encounter encounter=encounters.get(entry.getValue().runId);
            if(autoRespawn(player))continue;
            if(player==null||encounter==null||entry.getValue().dead)continue;
            if(player.level()!=encounter.level)cancelChannel(player,CancelReason.DIMENSION);
            else if(encounter.brazier!=null&&encounter.brazier.moved(player.getUUID(),player.position()))cancelChannel(player,CancelReason.MOVED);
            if(!inside(player,encounter))player.teleportTo(encounter.level,entry.getValue().lastSafe.x,entry.getValue().lastSafe.y,entry.getValue().lastSafe.z,java.util.Set.of(),player.getYRot(),player.getXRot(),false);
            else if(player.onGround())entry.getValue().lastSafe=player.position();
        }
        for (Map.Entry<UUID, State> entry : playerRuns.entrySet()) {
            Encounter encounter = encounters.get(entry.getValue().runId);
            if (encounter == null || encounter.brazier!=null || encounter.failed || encounter.pausedAt!=0 || entry.getValue().dead || !entry.getValue().connected || encounter.stage != Stage.BRAZIER) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            if (encounter.mobs.isEmpty() && nearBrazier(player, encounter.floor, encounter.partySize)) {int ticks=encounter.brazierTicks.merge(entry.getKey(),1,Integer::sum);if(ticks>=80)mechanicAction.accept(player);}
            else encounter.brazierTicks.remove(entry.getKey());
        }
        for (Encounter encounter : new ArrayList<>(encounters.values())) {
            if(encounter.failed||encounter.pausedAt!=0||encounter.stage==Stage.COMPLETED)continue;
            tickMobs(server,encounter);
            if(encounter.group>0&&tickCount%100==0)partyActivator.request(encounter.runId);
            if (encounter.stage == Stage.BRAZIER) {tickBraziers(server,encounter);activateFinalBoss(encounter);}
            tickBoss(server, encounter);
            tickPosture(encounter);
            tickTelegraphs(server,encounter);
        }
    }

    // ---- P4-S07 individual exit: voluntary leave, 180-second reconnect expiry, safe return ----
    private static final long EXIT_RETRY_MS=5_000L,EXIT_BLOCKED_MS=30_000L;
    private final RunMemberService members=new RunMemberService();
    private final java.util.concurrent.ExecutorService exitWorker=java.util.concurrent.Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"dalmaru-run-exit");thread.setDaemon(true);return thread;});
    private final java.util.concurrent.ConcurrentLinkedQueue<java.util.function.Consumer<MinecraftServer>> exitDone=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Set<UUID> exitInFlight=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID,Long> exitRetryAt=new HashMap<>();
    private volatile java.util.function.BiConsumer<ServerPlayer,ExitReason> exitListener=(player,reason)->{};
    /** Called on the server thread after a member is removed and returned, so combat/UI state can be refreshed. */
    public void memberExited(java.util.function.BiConsumer<ServerPlayer,ExitReason> listener){exitListener=java.util.Objects.requireNonNull(listener);}
    /** The player asks to leave; the server resolves the character itself. Use -1 to skip the roster revision check. */
    public boolean requestVoluntaryExit(ServerPlayer player,long expectedRevision){
        State state=playerRuns.get(player.getUUID());
        if(state==null||!state.connected)return false;
        return requestExit(((ServerLevel)player.level()).getServer(),player.getUUID(),state.runId,ExitReason.VOLUNTARY,expectedRevision);
    }
    /** Publishes a committed exit (for example the S08 kick) by character id; call only after the transaction committed. */
    public boolean publishMemberExit(MinecraftServer server,MemberExit exit,ExitReason reason){
        if(exit==null||(exit.status()!=ExitStatus.REMOVED&&exit.status()!=ExitStatus.ALREADY_REMOVED))return false;
        for(var entry:playerRuns.entrySet())if(exit.characterId().equals(entry.getValue().characterId)&&entry.getValue().runId.equals(exit.runId())){publishExit(server,entry.getKey(),exit,reason);return true;}
        return false;
    }
    /** Lets the runtime map a player to the character it plays in this run; needed for publishMemberExit. */
    public void bindCharacter(UUID playerId,UUID characterId){State state=playerRuns.get(playerId);if(state!=null)state.characterId=characterId;}
    private boolean removedMember(Encounter encounter,UUID playerId){return encounter!=null&&encounter.removed.contains(playerId);}
    private void tickExits(MinecraftServer server,long now){
        for(java.util.function.Consumer<MinecraftServer> done;(done=exitDone.poll())!=null;)done.accept(server);
        for(var entry:new ArrayList<>(playerRuns.entrySet())){
            State state=entry.getValue();
            if(state.connected||state.disconnectedAt==0||kr.dalmaru.run.RunEncounterRules.reconnectAllowed(state.disconnectedAt,now))continue;
            Encounter encounter=encounters.get(state.runId);
            if(encounter==null||encounter.failed)continue;
            Long retry=exitRetryAt.get(entry.getKey());
            if(retry!=null&&now<retry)continue;
            requestExit(server,entry.getKey(),state.runId,ExitReason.RECONNECT_EXPIRED,RunMemberExit.ANY_REVISION);
        }
    }
    private boolean requestExit(MinecraftServer server,UUID playerId,UUID runId,ExitReason reason,long expectedRevision){
        if(!exitInFlight.add(playerId))return false;
        exitWorker.execute(()->{
            MemberExit result=null;Exception failure=null;
            try{result=members.exit(runId,playerId,reason,expectedRevision);}catch(Exception error){failure=error;}
            MemberExit outcome=result;Exception error=failure;
            exitDone.add(current->completeExit(current,playerId,runId,reason,outcome,error));
        });
        return true;
    }
    private void completeExit(MinecraftServer server,UUID playerId,UUID runId,ExitReason reason,MemberExit result,Exception failure){
        exitInFlight.remove(playerId);
        State state=playerRuns.get(playerId);
        if(state==null||!state.runId.equals(runId))return;
        ServerPlayer player=server.getPlayerList().getPlayer(playerId);
        long now=System.currentTimeMillis();
        if(failure!=null){
            LOGGER.warn("Run member exit failed for {}: {}",playerId,failure.getClass().getName());
            exitRetryAt.put(playerId,now+EXIT_RETRY_MS);
            if(player!=null&&reason==ExitReason.VOLUNTARY)player.sendSystemMessage(Component.literal("파티 이탈을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."));
            return;
        }
        switch(result.status()){
            case REMOVED,ALREADY_REMOVED->publishExit(server,playerId,result,reason);
            case STALE_REVISION->{
                exitRetryAt.put(playerId,now+EXIT_RETRY_MS);
                if(player!=null&&reason==ExitReason.VOLUNTARY)player.sendSystemMessage(Component.literal("파티 명단이 바뀌었습니다. 다시 시도해 주세요."));
            }
            case NOT_PARTICIPANT,WRONG_PHASE->{
                exitRetryAt.put(playerId,now+EXIT_BLOCKED_MS);
                if(player!=null&&reason==ExitReason.VOLUNTARY)player.sendSystemMessage(Component.literal("지금은 파티에서 나갈 수 없습니다."));
            }
        }
    }
    private void publishExit(MinecraftServer server,UUID playerId,MemberExit exit,ExitReason reason){
        State state=playerRuns.get(playerId);
        if(state==null||!state.runId.equals(exit.runId()))return;
        Encounter encounter=encounters.get(state.runId);
        ServerPlayer player=server.getPlayerList().getPlayer(playerId);
        if(player!=null)cancelChannel(player,CancelReason.PHASE);
        dropLinger(playerId);
        playerRuns.remove(playerId);
        exitRetryAt.remove(playerId);
        if(encounter!=null){encounter.removed.add(playerId);encounter.revision++;encounter.brazierTicks.remove(playerId);}
        if(player!=null){
            boolean returned=false;
            try{
                if(player.isSpectator())player.setGameMode(state.returnMode);
                player.teleportTo(state.returnLevel,state.returnPosition.x,state.returnPosition.y,state.returnPosition.z,java.util.Set.of(),player.getYRot(),player.getXRot(),false);
                returned=true;
            }catch(RuntimeException error){LOGGER.warn("Run exit return failed for {}: {}",playerId,error.getClass().getName());}
            player.sendSystemMessage(Component.literal(switch(reason){case VOLUNTARY->"파티를 나가 던전 밖으로 돌아왔습니다.";case KICKED->"파티에서 추방되어 던전 밖으로 돌아왔습니다.";case RECONNECT_EXPIRED->"재접속 시간이 지나 던전 밖으로 돌아왔습니다.";}));
            try{exitListener.accept(player,reason);}catch(RuntimeException error){LOGGER.warn("Run exit listener failed: {}",error.getClass().getName());}
            if(returned&&exit.returnPending()){
                UUID runId=exit.runId(),character=exit.characterId();
                exitWorker.execute(()->{try{members.acknowledgeReturn(runId,character);}catch(Exception error){LOGGER.warn("Run exit return acknowledgement failed for {}: {}",character,error.getClass().getName());}});
            }
        }else releaseDisconnected.accept(playerId);
        if(playerRuns.values().stream().noneMatch(member->member.runId.equals(exit.runId())))close(server,exit.runId());
    }
    public void leave(ServerPlayer player) { State state=playerRuns.get(player.getUUID());if(state!=null)close(((ServerLevel)player.level()).getServer(),state.runId); }
    public void disconnect(ServerPlayer player) {
        State state=playerRuns.get(player.getUUID());if(state==null)return;
        cancelChannel(player,CancelReason.DISCONNECT);
        state.contribution.disconnect();
        state.connected=false;state.disconnectedAt=System.currentTimeMillis();
        spawnLinger(player);
        pauseIfQuiet(encounters.get(state.runId),state.disconnectedAt);
    }
    private void pauseIfQuiet(Encounter encounter,long now){
        if(encounter==null||encounter.failed)return;
        boolean present=playerRuns.values().stream().anyMatch(member->member.runId.equals(encounter.runId)&&kr.dalmaru.run.RunEncounterRules.countsAsSurvivor(member.connected,member.dead,member.disconnectedAt,now));
        long next=kr.dalmaru.run.RunEncounterRules.pausedAfterCheck(encounter.pausedAt,present,now);
        if(encounter.pausedAt==0&&next!=0)encounter.pausedTick=tickCount;
        encounter.pausedAt=next;
    }
    private void spawnLinger(ServerPlayer player){
        State state=playerRuns.get(player.getUUID());
        if(state==null||state.dead||state.connected||lingerBodies.containsKey(player.getUUID()))return;
        Encounter encounter=encounters.get(state.runId);if(encounter==null)return;
        Mob body=EntityType.HUSK.create(encounter.level,EntitySpawnReason.EVENT);if(body==null)return;
        float max=Math.max(1F,player.getMaxHealth());
        var health=body.getAttribute(Attributes.MAX_HEALTH);if(health!=null)health.setBaseValue(max);
        body.setHealth(Math.min(max,Math.max(1F,player.getHealth())));
        var attack=body.getAttribute(Attributes.ATTACK_DAMAGE);if(attack!=null)attack.setBaseValue(0);
        body.setNoAi(true);body.setPersistenceRequired();
        body.setCustomName(Component.literal(player.getName().getString()));body.setCustomNameVisible(true);
        body.addTag("dalmaru.linger."+player.getUUID());body.addTag("dalmaru.mob.linger");body.addTag("dalmaru.run."+encounter.runId);
        body.setPos(player.getX(),player.getY(),player.getZ());body.setYRot(player.getYRot());
        if(!encounter.level.addFreshEntity(body))return;
        lingerBodies.put(player.getUUID(),body.getUUID());
    }
    private void dropLinger(UUID playerId){
        UUID entityId=lingerBodies.remove(playerId);if(entityId==null)return;
        State state=playerRuns.get(playerId);if(state==null)return;
        Encounter encounter=encounters.get(state.runId);if(encounter==null)return;
        var entity=encounter.level.getEntity(entityId);if(entity!=null)entity.discard();
    }
    private void expireLinger(MinecraftServer server,long now){
        for(UUID playerId:new ArrayList<>(lingerBodies.keySet())){
            State state=playerRuns.get(playerId);
            if(state!=null&&!state.connected&&!state.dead&&RunEncounterRules.lingering(state.disconnectedAt,now))continue;
            dropLinger(playerId);
        }
    }
    private List<Mob> lingerMobs(Encounter encounter){
        List<Mob> result=new ArrayList<>();
        for(var entry:lingerBodies.entrySet()){
            State state=playerRuns.get(entry.getKey());
            if(state==null||!state.runId.equals(encounter.runId))continue;
            if(encounter.level.getEntity(entry.getValue()) instanceof Mob body&&body.isAlive())result.add(body);
        }
        return result;
    }
    private void hurtLinger(Encounter encounter,Mob attacker,float amount,double range,boolean magic){
        double rangeSq=range*range;
        hurtLinger(encounter,attacker,amount,body->body.distanceToSqr(attacker)<=rangeSq,magic);
    }
    private void hurtLinger(Encounter encounter,Mob attacker,float amount,java.util.function.Predicate<Mob> inside,boolean magic){
        long now=System.currentTimeMillis();
        for(var entry:new ArrayList<>(lingerBodies.entrySet())){
            State state=playerRuns.get(entry.getKey());
            if(state==null||!state.runId.equals(encounter.runId)||state.dead||state.connected||!RunEncounterRules.lingering(state.disconnectedAt,now))continue;
            if(!(encounter.level.getEntity(entry.getValue()) instanceof Mob body)||!body.isAlive()||!inside.test(body))continue;
            lingerDamage.hit(entry.getKey(),body,attacker,amount,magic);
        }
    }
    private static UUID lingerOwner(net.minecraft.world.entity.LivingEntity mob){
        for(String tag:mob.entityTags())if(tag.startsWith("dalmaru.linger."))try{return UUID.fromString(tag.substring("dalmaru.linger.".length()));}catch(IllegalArgumentException error){return null;}
        return null;
    }
    private DeathResult recordAbsentDeath(UUID playerId,net.minecraft.world.phys.Vec3 position){
        State state=playerRuns.get(playerId);
        if(state==null||state.dead)return new DeathResult(state==null?null:state.runId,false,false);
        state.dead=true;state.deathTick=tickCount;state.deathPosition=position;
        Encounter encounter=encounters.get(state.runId);
        if(encounter!=null){double relative=position.z-encounter.floor.getZ();state.deathZone=relative<27?0:relative<45?1:2;}
        long now=System.currentTimeMillis();
        boolean living=playerRuns.values().stream().anyMatch(member->member.runId.equals(state.runId)&&RunEncounterRules.countsAsSurvivor(member.connected,member.dead,member.disconnectedAt,now));
        boolean defeated=encounter!=null&&!living&&(encounter.partySize>1||state.revivesRemaining==0);
        if(defeated){encounter.failed=true;UUID wiped=state.runId;java.util.concurrent.CompletableFuture.runAsync(()->{try{new RunRepository().failUnattendedWipe(wiped);}catch(Exception error){org.slf4j.LoggerFactory.getLogger("dalmaru").warn("Failed to mark wiped run.",error);}});}
        return new DeathResult(state.runId,!defeated&&state.revivesRemaining>0,defeated);
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
            partyActivator.forget(runId);
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
        UUID owner=lingerOwner(mob);
        if(owner!=null){lingerBodies.remove(owner);recordAbsentDeath(owner,mob.position());return;}
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
    public static boolean deniesNaturalSpawn(net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos) {
        RunRuntime runtime = current;
        if (runtime == null || level == null || pos == null) return false;
        for (Encounter encounter : runtime.encounters.values()) if (encounter.level == level && bounds(encounter).contains(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5)) return true;
        return false;
    }
    private static boolean inside(ServerPlayer player,Encounter encounter){return player.level()==encounter.level&&bounds(encounter).deflate(.6).contains(player.position());}
    public kr.dalmaru.content.ContentCatalog content(UUID player){
        State state=playerRuns.get(player);if(state==null)return kr.dalmaru.content.ContentCatalog.current();
        Encounter encounter=encounters.get(state.runId);if(encounter==null)throw new IllegalStateException("Mapped run lacks content snapshot.");
        return encounter.content;
    }
    public UUID runId(ServerPlayer player) { State state = playerRuns.get(player.getUUID()); return state == null ? null : state.runId; }
    public int revivesRemaining(ServerPlayer player) { State state = playerRuns.get(player.getUUID()); return state == null ? 0 : state.revivesRemaining; }
    public boolean isBoss(UUID id){return encounters.values().stream().anyMatch(e->id.equals(e.bossId));}
    public float scaleBossDamage(UUID id, float engineDamage) {
        for (Encounter encounter : encounters.values()) if (id.equals(encounter.bossId) && encounter.bossRpgMaximum > 0) return kr.dalmaru.combat.CombatUnits.scaleEngineDamage(engineDamage, encounter.bossRpgMaximum);
        return engineDamage;
    }
    public int bossDefense(UUID bossId, boolean magic) {
        for(Encounter encounter:encounters.values()){var kind=encounter.mobs.get(bossId);if(kind!=null)return (magic?encounter.content.encounter().mob(kind.name()).magicDefense():encounter.content.encounter().mob(kind.name()).physicalDefense())+(encounter.difficulty==RunDifficulty.TRAINING?20:0);}
        for (Encounter encounter : encounters.values()) if (bossId.equals(encounter.bossId)) {
            int base = magic && encounter.stage == Stage.GATEKEEPER ? 55 : 80;
            return base + (encounter.difficulty == RunDifficulty.TRAINING ? 20 : 0);
        }
        return 0;
    }

    private void activateFinalBoss(Encounter encounter) {
        if(encounter.brazier!=null){if(!encounter.brazier.complete()||!encounter.mobs.isEmpty())return;}
        else {
        long ready = playerRuns.entrySet().stream().filter(entry -> entry.getValue().runId.equals(encounter.runId)).filter(entry -> encounter.brazierTicks.getOrDefault(entry.getKey(), 0) >= 80).count();
        if (ready < encounter.requiredInteractions || !encounter.mobs.isEmpty()) return;
        encounter.brazierTicks.clear();
        }
        encounter.stage=Stage.APPROACH;encounter.revision++;
        BeaconTowerMap.open(encounter.level,encounter.floor,45);
        spawnGroup(encounter,3);
    }

    private void spawnFinalBoss(Encounter encounter) {
        encounter.stage = Stage.BEACON;encounter.revision++;
        BeaconTowerMap.open(encounter.level, encounter.floor, 45);
        encounter.bossId = spawnBoss(encounter, encounter.level, encounter.floor.offset(0, 1, 54), "원혼봉수장", bossHealth(encounter, encounter.content.encounter().finalBossHealth()));
        BeaconTowerBosses.style(encounter.level, encounter.bossId, true);
        beginPosture(encounter, true);
    }

    private void spawnGroup(Encounter encounter,int group) {
        encounter.group=group;
        int index=0;
        for(var kind:BeaconTowerMobs.group(encounter.content,group))spawnMob(encounter,kind,index++);
    }
    private void spawnMob(Encounter encounter,BeaconTowerMobs.Kind kind,int index) {
        var definition=encounter.content.encounter().mob(kind.name());
        int z=switch(encounter.group){case 0->6;case 1->18;case 2->31;default->49;};
        Mob mob=EntityType.HUSK.create(encounter.level,EntitySpawnReason.EVENT);
        if(mob==null)throw new IllegalStateException("Dungeon mob could not spawn.");
        float hp=kr.dalmaru.combat.CombatUnits.maximumHealth(health(encounter.difficulty,definition.health())*BeaconTowerMobs.healthScale(encounter.content,encounter.partySize));
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(hp);mob.setHealth(hp);
        mob.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(0);
        mob.setNoAi(true);mob.setPersistenceRequired();mob.setCustomName(Component.literal(definition.label()));mob.setCustomNameVisible(true);
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
            var kind=entry.getValue();var definition=encounter.content.encounter().mob(kind.name());
            if(kind==BeaconTowerMobs.Kind.COMMANDER&&!encounter.summoned&&mob.getHealth()<=mob.getMaxHealth()*.5F){encounter.summoned=true;spawnMob(encounter,BeaconTowerMobs.Kind.SOLDIER,5);spawnMob(encounter,BeaconTowerMobs.Kind.SOLDIER,6);}
            if(participants.isEmpty()&&lingerMobs(encounter).isEmpty()||(definition.magic()&&mob.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)))continue;
            if(!participants.isEmpty()){
            var target=participants.stream().min(java.util.Comparator.comparingDouble(p->p.distanceToSqr(mob))).orElseThrow();
            mob.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,target.getEyePosition());
            double distance=target.distanceToSqr(mob);
            if(distance>4){
                var direction=target.position().subtract(mob.position()).multiply(1,0,1).normalize().scale(Math.min(.12,mob.getAttributeValue(Attributes.MOVEMENT_SPEED)*.5));
                var position=mob.position().add(direction);
                if(bounds(encounter).deflate(1).contains(position)&&encounter.level.noCollision(mob,mob.getBoundingBox().move(direction)))mob.teleportTo(position.x,position.y,position.z);
            }
            if(encounter.mobTicks%definition.interval()==0&&distance<=(double)definition.range()*definition.range()&&mob.hasLineOfSight(target)){
                bossDamage.hit(target,mob,(float)(attack(encounter,definition.damage())*BeaconTowerMobs.mobDamageScale(encounter.content,encounter.partySize)),definition.magic());
                if(definition.magic())encounter.level.sendParticles(kind==BeaconTowerMobs.Kind.EMBER?ParticleTypes.FLAME:ParticleTypes.SOUL,target.getX(),target.getY()+1,target.getZ(),8,.2,.2,.2,.01);
            }
            }
            if(encounter.mobTicks%definition.interval()==0)hurtLinger(encounter,mob,(float)(attack(encounter,definition.damage())*BeaconTowerMobs.mobDamageScale(encounter.content,encounter.partySize)),definition.range(),definition.magic());
        }
        if(!encounter.mobs.isEmpty())return;
        if(encounter.stage==Stage.GATEKEEPER&&encounter.bossId==null){
            if(encounter.group==0){partyActivator.request(encounter.runId);spawnGroup(encounter,1);return;}
            encounter.bossId=spawnBoss(encounter,encounter.level,encounter.floor.offset(0,1,22),"철문수장",bossHealth(encounter,encounter.content.encounter().gatekeeperHealth()));BeaconTowerBosses.style(encounter.level,encounter.bossId,false);beginPosture(encounter,false);
        }else if(encounter.stage==Stage.APPROACH)spawnFinalBoss(encounter);
    }

    private void tickBoss(MinecraftServer server, Encounter encounter) {
        if (encounter.bossId == null || !(encounter.level.getEntity(encounter.bossId) instanceof Mob boss) || !boss.isAlive()) return;
        List<ServerPlayer> players = players(server, encounter.runId, encounter.level);
        if (players.isEmpty() && lingerMobs(encounter).isEmpty()) return;
        encounter.bossTicks++;
        ServerPlayer target = players.isEmpty()?null:players.stream().min(java.util.Comparator.comparingDouble(player -> player.distanceToSqr(boss))).orElseThrow();
        if (encounter.stage == Stage.GATEKEEPER) {
            int slamEvery = boss.getHealth() <= boss.getMaxHealth() * .5F ? 50 : 100;
            if (encounter.bossTicks % slamEvery == 0) damage(players, boss, 5, gatekeeperAttack(encounter, 150), false, ParticleTypes.CRIT);
            else if (encounter.bossTicks % 60 == 0) {
                if (target!=null && target.distanceToSqr(boss) <= 16) {
                    bossDamage.hit(target, boss, gatekeeperAttack(encounter, 130), false);
                    target.knockback(.4, boss.getX() - target.getX(), boss.getZ() - target.getZ());
                }
                hurtLinger(encounter, boss, gatekeeperAttack(encounter, 130), 4, false);
            }
            else if (encounter.bossTicks % 40 == 0) {
                if (target!=null && target.distanceToSqr(boss) <= 16) bossDamage.hit(target, boss, gatekeeperAttack(encounter, 110), false);
                hurtLinger(encounter, boss, gatekeeperAttack(encounter, 110), 4, false);
                startCharge(encounter, boss, target);
            }
        } else if (encounter.stage == Stage.BEACON) {
            float ratio = boss.getHealth() / boss.getMaxHealth();
            int every = ratio > .7F ? 70 : ratio > .35F ? 50 : 35;
            if (encounter.bossTicks % every != 0) return;
            if (encounter.content.mechanics()!=null) {
                Mob body=target==null?lingerMobs(encounter).get(0):null;
                startTelegraph(encounter,boss,target==null?body.getX():target.getX(),target==null?body.getZ():target.getZ(),ratio);
                return;
            }
            if (ratio > .7F) {
                if(target!=null)bossDamage.hit(target, boss, bossAttack(encounter, 130), false);
                hurtLinger(encounter, boss, bossAttack(encounter, 130), 4, false);
                if(target!=null)encounter.level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, target.getX(), target.getY() + 1, target.getZ(), 24, .4, .6, .4, .03);
            } else if (ratio > .35F) damage(players, boss, 7, bossAttack(encounter, 120), true, ParticleTypes.SOUL);
            else damage(players, boss, 6, bossAttack(encounter, 180), true, ParticleTypes.SOUL_FIRE_FLAME);
        }
    }

    private List<BossView> bossViews(Encounter e) {
        if(e.posture==null||e.bossId==null||!(e.level.getEntity(e.bossId) instanceof Mob boss)||!boss.isAlive())return List.of();
        float engineMaximum = boss.getMaxHealth();
        double rpgMaximum = e.bossRpgMaximum > 0 ? e.bossRpgMaximum : kr.dalmaru.combat.CombatUnits.toRpg(engineMaximum);
        double rpg = kr.dalmaru.combat.CombatUnits.hudRpg(rpgMaximum, boss.getHealth(), engineMaximum);
        return List.of(new BossView(e.bossId,rpg,rpgMaximum,e.posture.posture(),e.posture.maximum(),e.posture.state(),e.posture.remainingTicks()));
    }
    private static void beginPosture(Encounter encounter, boolean finalBoss) {
        encounter.charge.cancel();encounter.postureHits.clear();encounter.telegraphs.clear();encounter.bossCycles=0;
        var mechanics=encounter.content.mechanics();
        encounter.posture=mechanics==null?null:new BossPosture(BossScaling.maximumPosture(mechanics.boss(),finalBoss,encounter.difficulty,encounter.partySize),mechanics.boss());
    }
    private void startCharge(Encounter encounter, Mob boss, ServerPlayer target) {
        if(encounter.posture==null||target==null)return;
        double dx=target.getX()-boss.getX(),dz=target.getZ()-boss.getZ(),length=Math.sqrt(dx*dx+dz*dz);
        if(length<.5)return;
        encounter.chargeX=dx/length;encounter.chargeZ=dz/length;encounter.charge.start();
    }
    /** Advances posture time and the boss charge. A wall impact is a real block collision of the boss body. */
    private void tickPosture(Encounter encounter) {
        if(encounter.posture==null||encounter.bossId==null||!(encounter.level.getEntity(encounter.bossId) instanceof Mob boss)||!boss.isAlive())return;
        var before=encounter.posture.state();
        encounter.posture.tick(tickCount);
        if(encounter.charge.active()){
            long wall=encounter.charge.tick(boss.horizontalCollision);
            if(wall!=0)encounter.posture.wall(wall,encounter.content.mechanics().boss().wallPostureDamage());
            else if(encounter.charge.active())boss.setDeltaMovement(encounter.chargeX*.7,boss.getDeltaMovement().y,encounter.chargeZ*.7);
        }
        var after=encounter.posture.state();
        if(after!=before){
            encounter.revision++;
            if(after==Posture.COLLAPSED)announceCollapse(encounter,boss);
        }
    }
    private void startTelegraph(Encounter encounter, Mob boss, double targetX, double targetZ, float ratio) {
        var kind=BossTelegraph.pick(ratio,encounter.bossCycles++);
        int minimum=encounter.content.mechanics().minimumTelegraphTicks();
        var telegraph=new BossTelegraph.Active(kind,BossTelegraph.shape(kind,boss.getX(),boss.getZ(),targetX,targetZ),bossAttack(encounter,BossTelegraph.baseDamage(kind)),BossTelegraph.magic(kind),BossTelegraph.ticks(minimum),minimum,tickCount);
        encounter.telegraphs.add(telegraph);
        encounter.level.playSound(null,boss.getX(),boss.getY(),boss.getZ(),net.minecraft.sounds.SoundEvents.EVOKER_PREPARE_ATTACK,net.minecraft.sounds.SoundSource.HOSTILE,1.5F,1F);
        showTelegraph(encounter,boss,telegraph);
    }
    /** Ticks running telegraphs. The hit uses exactly the displayed shape and the positions at the firing tick. */
    private void tickTelegraphs(MinecraftServer server, Encounter encounter) {
        if(encounter.telegraphs.isEmpty())return;
        if(encounter.bossId==null||!(encounter.level.getEntity(encounter.bossId) instanceof Mob boss)||!boss.isAlive()){encounter.telegraphs.clear();return;}
        List<ServerPlayer> players=players(server,encounter.runId,encounter.level);
        for(var iterator=encounter.telegraphs.iterator();iterator.hasNext();){
            var telegraph=iterator.next();
            if(!telegraph.tick(tickCount)){showTelegraph(encounter,boss,telegraph);continue;}
            iterator.remove();
            var shape=telegraph.shape();
            encounter.level.playSound(null,boss.getX(),boss.getY(),boss.getZ(),net.minecraft.sounds.SoundEvents.BLAZE_SHOOT,net.minecraft.sounds.SoundSource.HOSTILE,1.5F,.8F);
            sendTelegraphPoints(encounter,boss,telegraph.kind(),shape.fill(1.0));
            for(ServerPlayer player:players)if(shape.contains(player.getX(),player.getZ()))bossDamage.hit(player,boss,telegraph.damage(),telegraph.magic());
            hurtLinger(encounter,boss,telegraph.damage(),body->shape.contains(body.getX(),body.getZ()),telegraph.magic());
        }
    }
    private void showTelegraph(Encounter encounter, Mob boss, BossTelegraph.Active telegraph) {
        if(telegraph.elapsed()%4==0)sendTelegraphPoints(encounter,boss,telegraph.kind(),telegraph.shape().outline(1.0));
        if(telegraph.total()-telegraph.elapsed()<=10&&telegraph.elapsed()%5==0)sendTelegraphPoints(encounter,boss,telegraph.kind(),telegraph.shape().fill(1.5));
    }
    private void sendTelegraphPoints(Encounter encounter, Mob boss, BossTelegraph.Kind kind, List<double[]> points) {
        var particle=switch(BossTelegraph.cue(kind)){case FLAME->ParticleTypes.FLAME;case SMOKE->ParticleTypes.SMOKE;case SOUL_FIRE_FLAME->ParticleTypes.SOUL_FIRE_FLAME;};
        for(double[] point:points)encounter.level.sendParticles(particle,point[0],boss.getY()+.1,point[1],1,0,0,0,0);
    }
    private void announceCollapse(Encounter encounter, Mob boss) {
        encounter.level.sendParticles(ParticleTypes.CRIT,boss.getX(),boss.getY()+1.5,boss.getZ(),40,.8,.8,.8,.2);
        for(var entry:playerRuns.entrySet())if(entry.getValue().runId.equals(encounter.runId)){
            ServerPlayer player=encounter.level.getServer().getPlayerList().getPlayer(entry.getKey());
            if(player!=null)player.sendSystemMessage(Component.literal("보스의 자세가 무너졌습니다! 4초간 받는 피해가 늘어납니다."));
        }
    }
    /** CombatRuntime hook: damage multiplier applied to HP damage dealt to this entity (1 unless it is a collapsed boss). */
    public double bossDamageTaken(UUID entityId) {
        for(Encounter e:encounters.values())if(entityId.equals(e.bossId)&&e.posture!=null)return e.posture.damageTakenScale();
        return 1;
    }
    /** CombatRuntime hook: one actual posture hit (rising strike). Repeated events of one attacker in a tick count once. */
    public boolean bossSkillHit(UUID entityId, UUID attacker, double amount) {
        for(Encounter e:encounters.values())if(entityId.equals(e.bossId)&&e.posture!=null){
            if(e.failed||e.pausedAt!=0)return false;
            Long last=e.postureHits.put(attacker,tickCount);
            if(last!=null&&last==tickCount)return false;
            boolean started=e.posture.damage(amount);
            if(started){
                e.revision++;
                if(e.level.getEntity(e.bossId) instanceof Mob boss)announceCollapse(e,boss);
            }
            return started;
        }
        return false;
    }
    private void damage(List<ServerPlayer> players, Mob boss, double range, float amount, boolean magic, net.minecraft.core.particles.ParticleOptions particle) {
        ServerLevel level = (ServerLevel) boss.level();
        level.sendParticles(particle, boss.getX(), boss.getY() + 1, boss.getZ(), 32, 1.2, .8, 1.2, .04);
        for (ServerPlayer player : players) if (player.distanceToSqr(boss) <= range * range) bossDamage.hit(player, boss, amount, magic);
        for(Encounter encounter:encounters.values())if(boss.getUUID().equals(encounter.bossId)){hurtLinger(encounter,boss,amount,range,magic);break;}
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
    private static UUID spawnBoss(Encounter encounter, ServerLevel level, BlockPos position, String name, double health) {
        Mob boss = EntityType.ZOMBIE.create(level, EntitySpawnReason.EVENT);
        if (boss == null) throw new IllegalStateException("Boss could not spawn.");
        encounter.bossRpgMaximum = health;
        float engineHealth = kr.dalmaru.combat.CombatUnits.cappedEngineMaximum(health);
        boss.getAttribute(Attributes.MAX_HEALTH).setBaseValue(engineHealth);
        boss.setHealth(engineHealth);
        // Only the scripted RPG patterns deal damage, not the zombie's extra melee attack.
        boss.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(0);
        boss.setCustomName(Component.literal(name));
        boss.setCustomNameVisible(true);
        boss.setPersistenceRequired(); // otherwise it despawns while the dying player respawns far away
        boss.addTag("dalmaru.mob.boss"); // dungeon bosses never drop vanilla loot/XP or count for field quests
        boss.setPos(position.getX() + .5, position.getY(), position.getZ() + .5);
        level.addFreshEntity(boss);
        return boss.getUUID();
    }
    private static boolean nearBrazier(ServerPlayer player, BlockPos floor, int partySize) {
        for (int i = 0; i < Math.min(3,partySize); i++) if (player.distanceToSqr(floor.getX() - 4 + i * 4 + .5, floor.getY() + 1, floor.getZ() + 35.5) <= 9) return true;
        return false;
    }
    private static double health(RunDifficulty difficulty, double base) { return RunEncounterRules.bossHealth(difficulty, base); }
    private static double bossHealth(Encounter encounter, double base) { return BossScaling.health(encounter.content, encounter.difficulty, base, encounter.partySize); }
    /** Gatekeeper slam/melee have no telegraph, so they carry the content gatekeeper scale; telegraphed patterns do not. */
    private static float gatekeeperAttack(Encounter encounter, int base) { return (float) (bossAttack(encounter, base) * encounter.content.encounter().gatekeeperDamageScale()); }
    private static float bossAttack(Encounter encounter, int base) { return (float) BossScaling.damage(encounter.content, encounter.difficulty, base, encounter.partySize); }
    private static float attack(Encounter encounter, int base) { return (float) (base * (encounter.difficulty == RunDifficulty.TRAINING ? 1.15 : 1.0)); }

    public record DeathResult(UUID runId, boolean canRespawn, boolean partyDefeated) { }
    public record ReviveStatus(int remainingRevives,int clickTicks,int autoTicks,boolean failed) { }
    private enum Stage { GATEKEEPER, BRAZIER, APPROACH, BEACON, COMPLETED }
    private static final class State { private final UUID runId; private UUID characterId; private final RunContribution contribution=new RunContribution(); private int revivesRemaining; private boolean dead; private boolean connected=true; private long disconnectedAt,deathTick; private net.minecraft.world.level.GameType returnMode; private ServerLevel returnLevel; private net.minecraft.world.phys.Vec3 returnPosition,lastSafe,deathPosition;private int deathZone; private State(UUID runId, int revivesRemaining) { this.runId = runId; this.revivesRemaining = revivesRemaining; } }
    private static final class Encounter {
        private final kr.dalmaru.content.ContentCatalog content; private final UUID runId; private final RunDifficulty difficulty; private final BlockPos floor; private final ServerLevel level; private final int requiredInteractions;
        private final long deadline=System.currentTimeMillis()+2700000; private long pausedAt,pausedTick; private boolean failed; private int partySize; private Map<BlockPos,net.minecraft.world.level.block.state.BlockState> terrain;
        private final Map<UUID,BeaconTowerMobs.Kind> mobs=new HashMap<>(); private int group,mobTicks; private boolean summoned;
        private final java.util.Set<UUID> removed=new java.util.HashSet<>(); private long revision=1; private BossPosture posture; private final List<BossTelegraph.Active> telegraphs=new ArrayList<>(); private int bossCycles; private final BossCharge charge=new BossCharge(); private double chargeX,chargeZ; private final Map<UUID,Long> postureHits=new HashMap<>(); private final BrazierMechanic brazier; private final BrazierNeglect neglect; private Stage stage = Stage.GATEKEEPER; private UUID bossId; private double bossRpgMaximum; private int bossTicks; private final Map<UUID, Integer> brazierTicks = new HashMap<>();
        private Encounter(UUID runId, RunDifficulty difficulty, BlockPos floor, ServerLevel level, int partySize,kr.dalmaru.content.ContentCatalog content) { this.content=content;this.partySize=partySize; this.runId = runId; this.difficulty = difficulty; this.floor = floor; this.level = level; this.requiredInteractions = RunEncounterRules.requiredBrazierInteractions(partySize);this.brazier=content.mechanics()==null?null:new BrazierMechanic(content.mechanics().brazier(),partySize);this.neglect=content.mechanics()==null?null:new BrazierNeglect(content.mechanics().brazier()); }
    }
}
