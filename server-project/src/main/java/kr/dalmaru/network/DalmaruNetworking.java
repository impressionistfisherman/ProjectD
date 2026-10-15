package kr.dalmaru.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kr.dalmaru.account.AccountSession;
import kr.dalmaru.account.AccountRepository;
import kr.dalmaru.account.CharacterRepository;
import kr.dalmaru.account.CharacterSelectionResult;
import kr.dalmaru.account.CharacterProfile;
import kr.dalmaru.account.CharacterStat;
import kr.dalmaru.combat.CombatRewards;
import kr.dalmaru.combat.CombatRuntime;
import kr.dalmaru.combat.CombatCalculator;
import kr.dalmaru.combat.CombatUnits;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.tags.DamageTypeTags;
import kr.dalmaru.quest.QuestRepository;
import kr.dalmaru.equipment.EquipmentRepository;
import kr.dalmaru.run.RunDifficulty;
import kr.dalmaru.run.RunRepository;
import kr.dalmaru.run.RunRuntime;
import kr.dalmaru.run.PartyRuntime;
import kr.dalmaru.skill.SkillRepository;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import kr.dalmaru.account.CharacterProfession;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DalmaruNetworking {
	private static final Logger LOGGER = LoggerFactory.getLogger("dalmaru");
	public static final int PROTOCOL_VERSION = 32;
	private static final long HELLO_TIMEOUT_TICKS = 200;
	private static final long RATE_LIMIT_NANOS = 1_000_000_000L;
	private static final Map<UUID, ConnectionState> CONNECTIONS = new HashMap<>();
    private static final java.util.concurrent.ExecutorService LIFECYCLE_DB=java.util.concurrent.Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"dalmaru-session-db");thread.setDaemon(true);return thread;});
    private record JoinedSession(AccountSession session,String vanillaState,RunRepository.ReturnPosition position,kr.dalmaru.run.RunRecord run) { }
    private static boolean currentJoin(MinecraftServer server,UUID id,ConnectionState state,ServerPlayer player) {
        ServerPlayer current=server.getPlayerList().getPlayer(id);
        return CONNECTIONS.get(id)==state&&current!=null&&state.connection==current.connection&&state.connection==player.connection;
    }

	private static final AccountRepository ACCOUNTS = new AccountRepository();
	private static final CharacterRepository CHARACTERS = new CharacterRepository();
	private static final CombatRuntime COMBAT = new CombatRuntime();
	private static final QuestRepository QUESTS = new QuestRepository();
	private static final EquipmentRepository EQUIPMENT = new EquipmentRepository();
	private static final RunRepository RUNS = new RunRepository();
	private static final RunRuntime RUN_RUNTIME = new RunRuntime(COMBAT::bossDamage,COMBAT::remove,COMBAT::hurtLinger);
	private static final PartyRuntime PARTIES = new PartyRuntime();
	private static final SkillRepository SKILLS = new SkillRepository();
	private static long currentTick;
    private static final java.util.Set<UUID> FINISHING_RUNS=new java.util.HashSet<>();

	private DalmaruNetworking() { }

	public static void initialize() {
        COMBAT.contentSource(RUN_RUNTIME::content);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.Hello.TYPE, DalmaruPayloads.Hello.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.Welcome.TYPE, DalmaruPayloads.Welcome.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.ProbeRequest.TYPE, DalmaruPayloads.ProbeRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.ProbeResponse.TYPE, DalmaruPayloads.ProbeResponse.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.CharacterSelectionReady.TYPE, DalmaruPayloads.CharacterSelectionReady.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.CharacterListRequest.TYPE, DalmaruPayloads.CharacterListRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.CharacterListResponse.TYPE, DalmaruPayloads.CharacterListResponse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.CharacterSelectRequest.TYPE, DalmaruPayloads.CharacterSelectRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.CharacterSelectResponse.TYPE, DalmaruPayloads.CharacterSelectResponse.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.CharacterProfileResponse.TYPE, DalmaruPayloads.CharacterProfileResponse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.CharacterStatInvestmentRequest.TYPE, DalmaruPayloads.CharacterStatInvestmentRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.CharacterStatInvestmentResponse.TYPE, DalmaruPayloads.CharacterStatInvestmentResponse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.CharacterStatResetRequest.TYPE, DalmaruPayloads.CharacterStatResetRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.CharacterStatResetResponse.TYPE, DalmaruPayloads.CharacterStatResetResponse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.CharacterProfessionRequest.TYPE, DalmaruPayloads.CharacterProfessionRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.CharacterProfessionResponse.TYPE, DalmaruPayloads.CharacterProfessionResponse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.QuestRequest.TYPE, DalmaruPayloads.QuestRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.QuestResponse.TYPE, DalmaruPayloads.QuestResponse.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.CombatStateResponse.TYPE, DalmaruPayloads.CombatStateResponse.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.LoadoutResponse.TYPE,DalmaruPayloads.LoadoutResponse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.SweepRequest.TYPE, DalmaruPayloads.SweepRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.LoadoutRequest.TYPE,DalmaruPayloads.LoadoutRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.LoadoutUpdate.TYPE,DalmaruPayloads.LoadoutUpdate.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.SkillUseRequest.TYPE,DalmaruPayloads.SkillUseRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.CombatModeRequest.TYPE,DalmaruPayloads.CombatModeRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.RunReadyRequest.TYPE,DalmaruPayloads.RunReadyRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.DodgeRequest.TYPE,DalmaruPayloads.DodgeRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.GuardRequest.TYPE,DalmaruPayloads.GuardRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.AdvanceRequest.TYPE, DalmaruPayloads.AdvanceRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.RisingStrikeRequest.TYPE, DalmaruPayloads.RisingStrikeRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.BattleCryRequest.TYPE, DalmaruPayloads.BattleCryRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.PiercingShotRequest.TYPE, DalmaruPayloads.PiercingShotRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.SpiritTalismanRequest.TYPE, DalmaruPayloads.SpiritTalismanRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.AmbushRequest.TYPE, DalmaruPayloads.AmbushRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.RetreatShotRequest.TYPE, DalmaruPayloads.RetreatShotRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.BindingTalismanRequest.TYPE, DalmaruPayloads.BindingTalismanRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.SmokeBombRequest.TYPE, DalmaruPayloads.SmokeBombRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.ShackleStrikeRequest.TYPE, DalmaruPayloads.ShackleStrikeRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.EscapeRequest.TYPE, DalmaruPayloads.EscapeRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.TrackingMarkRequest.TYPE, DalmaruPayloads.TrackingMarkRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.BindingShotRequest.TYPE, DalmaruPayloads.BindingShotRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.ProtectiveTalismanRequest.TYPE, DalmaruPayloads.ProtectiveTalismanRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.ShortStepRequest.TYPE, DalmaruPayloads.ShortStepRequest.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.EquipmentEnhanceRequest.TYPE, DalmaruPayloads.EquipmentEnhanceRequest.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.RequestContext.TYPE,DalmaruPayloads.RequestContext.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.RefinementStateRequest.TYPE,DalmaruPayloads.RefinementStateRequest.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.RefinementStateResponse.TYPE,DalmaruPayloads.RefinementStateResponse.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.OperationRequest.TYPE,DalmaruPayloads.OperationRequest.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.OperationReceipt.TYPE,DalmaruPayloads.OperationReceipt.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.EquipmentEnhanceResponse.TYPE, DalmaruPayloads.EquipmentEnhanceResponse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.EquipmentListRequest.TYPE, DalmaruPayloads.EquipmentListRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.EquipmentListResponse.TYPE, DalmaruPayloads.EquipmentListResponse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.RunEnterRequest.TYPE, DalmaruPayloads.RunEnterRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.RunResponse.TYPE, DalmaruPayloads.RunResponse.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.RunReviveState.TYPE,DalmaruPayloads.RunReviveState.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.SkillLearnRequest.TYPE, DalmaruPayloads.SkillLearnRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.SkillLearnResponse.TYPE, DalmaruPayloads.SkillLearnResponse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.SkillRanksRequest.TYPE, DalmaruPayloads.SkillRanksRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.SkillRanksResponse.TYPE, DalmaruPayloads.SkillRanksResponse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.CharacterCreateRequest.TYPE, DalmaruPayloads.CharacterCreateRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.CharacterCreateResponse.TYPE, DalmaruPayloads.CharacterCreateResponse.CODEC);

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register((entity,world)->RUN_RUNTIME.loadedEntity(entity));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server->{
            try{kr.dalmaru.run.RunTerrainRecovery.recover(server);RUNS.cancelUnfinished();}catch(Exception error){LOGGER.error("Cannot recover unfinished runs safely.",error);throw new IllegalStateException("Run recovery failed.",error);}
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server->{
            for(var player:server.getPlayerList().getPlayers())clearFieldRevive(player);
            LIFECYCLE_DB.shutdown();
            try{if(!LIFECYCLE_DB.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS))LIFECYCLE_DB.shutdownNow();}catch(InterruptedException error){LIFECYCLE_DB.shutdownNow();Thread.currentThread().interrupt();}
            try{RUN_RUNTIME.closeAll(server);}catch(Exception error){LOGGER.error("Cannot close all run runtimes on shutdown.",error);}
            try{RUNS.cancelUnfinished();}catch(Exception error){LOGGER.error("Cannot cancel unfinished runs on shutdown.",error);}
        });
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register((world,player,pos,block,entity)-> !(world instanceof ServerLevel level)||!RUN_RUNTIME.protectedBlock(level,pos));
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player,world,hand,hit)-> world instanceof ServerLevel level && RUN_RUNTIME.protectedBlock(level,hit.getBlockPos()) ? net.minecraft.world.InteractionResult.FAIL : net.minecraft.world.InteractionResult.PASS);

        ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{
            ServerPlayer joinedPlayer=handler.getPlayer();UUID playerId=joinedPlayer.getUUID();
            ConnectionState joinedState=new ConnectionState(currentTick);joinedState.connection=handler;
            CONNECTIONS.put(playerId,joinedState);COMBAT.reconnect(playerId);
            String snapshot=kr.dalmaru.account.VanillaCharacterState.capture(joinedPlayer);
            CompletableFuture.supplyAsync(()->{
                try {
                    AccountSession session=ACCOUNTS.openSession(playerId);joinedState.openedSession=session;
                    String[] restored={null};CHARACTERS.recoverVanilla(session.accountId(),session.connectionGeneration(),snapshot,value->restored[0]=value);
                    var position=session.activeCharacterId()==null?null:RUNS.pendingReturn(session.activeCharacterId());
                    var run=session.activeCharacterId()==null?null:RUNS.active(session.activeCharacterId());
                    return new JoinedSession(session,restored[0],position,run);
                }catch(Exception error){throw new IllegalStateException(error);}
            },LIFECYCLE_DB).thenAccept(result->server.execute(()->{
                if(!currentJoin(server,playerId,joinedState,joinedPlayer))return;
                ServerPlayer current=server.getPlayerList().getPlayer(playerId);
                try {
                    joinedState.accountSession=result.session();
                    if(result.vanillaState()!=null)kr.dalmaru.account.VanillaCharacterState.restore(current,result.vanillaState());
                    restorePendingReturn(current,result.session(),result.position());sendCharacterSelectionReady(current,joinedState);
                    if(result.run()!=null)resumeRuntime(current,result.run());
                }catch(Exception error){joinedState.accountFailure="캐릭터 복구 실패";LOGGER.error("Cannot install recovered session safely.",error);joinedState.connection.disconnect(Component.literal("캐릭터 상태를 복구하지 못했습니다. 운영자에게 문의하세요."));}
            })).exceptionally(error->{
                server.execute(()->{
                    if(!currentJoin(server,playerId,joinedState,joinedPlayer))return;
                    joinedState.accountFailure="계정 저장소에 연결하지 못했습니다. 서버 로그를 확인하세요.";
                    LOGGER.error("Failed to open Dalmaru account session for {}.",playerId,error);
                    joinedState.connection.disconnect(Component.literal(joinedState.accountFailure));
                });return null;
            });
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{
            ConnectionState state=CONNECTIONS.get(handler.getPlayer().getUUID());
            if(state!=null&&state.connection!=null&&state.connection!=handler)return;
            clearFieldRevive(handler.getPlayer());
            cancelPreparation(handler.getPlayer().getUUID(),"파티원이 접속을 종료하여 준비가 취소되었습니다.");
            CONNECTIONS.remove(handler.getPlayer().getUUID());
            if(state!=null){
                AccountSession disconnectedSession=state.accountSession;
                var connected=CONNECTIONS.values().stream().filter(member->member.accountSession!=null&&member.accountSession.activeCharacterId()!=null).map(member->member.accountSession.activeCharacterId()).toList();
                var pending=CONNECTIONS.values().stream().filter(member->member.accountSession==null).toList();
                CompletableFuture.runAsync(()->{
                    AccountSession session=disconnectedSession!=null?disconnectedSession:state.openedSession;
                    if(session==null||session.activeCharacterId()==null)return;
                    var characters=new java.util.ArrayList<UUID>(connected);
                    for(ConnectionState member:pending){var opened=member.openedSession;if(opened!=null&&opened.activeCharacterId()!=null)characters.add(opened.activeCharacterId());}
                    try{RUNS.pauseIfUnattended(session.activeCharacterId(),characters);}catch(Exception error){LOGGER.warn("Failed to pause unattended run.",error);}
                },LIFECYCLE_DB);
            }
            RUN_RUNTIME.disconnect(handler.getPlayer());
            if(RUN_RUNTIME.runId(handler.getPlayer())==null)COMBAT.remove(handler.getPlayer().getUUID());else COMBAT.disconnect(handler.getPlayer().getUUID());
        });
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			// Restore profile modifiers before the run sets revive health.
			COMBAT.restoreAfterRespawn(oldPlayer,newPlayer);
            if(!alive){boolean dungeon=RUN_RUNTIME.runId(newPlayer)!=null;RUN_RUNTIME.afterRespawn(oldPlayer,newPlayer);if(dungeon){if(!newPlayer.isSpectator())COMBAT.revived(newPlayer,.25,true,currentTick);}else fieldRespawn(newPlayer);sendRunReviveState(newPlayer);}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> { expireMissingHello(server); COMBAT.tick(currentTick); COMBAT.tickEffects(currentTick); tickDodges(server); tickFieldRevives(server); RUN_RUNTIME.tick(server); if (currentTick % 20 == 0) { sendCombatStates(server);server.getPlayerList().getPlayers().forEach(DalmaruNetworking::sendRunReviveState);for(UUID runId:RUN_RUNTIME.completedRuns()){var member=RUN_RUNTIME.participant(server,runId);if(member!=null)completeRun(member,server,runId);} CompletableFuture.runAsync(() -> { try { RUNS.expireTimedOut(); } catch (Exception exception) { LOGGER.warn("Failed to expire timed out runs.", exception); } }); } });
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
			if (damageTaken > 0) {
				if (source.getEntity() instanceof ServerPlayer player && hostileCombatEntity(player,entity)) COMBAT.recordValidHit(player, currentTick);
				if (entity instanceof ServerPlayer player && hostileCombatEntity(player,source.getEntity())) COMBAT.recordValidHit(player, currentTick);
			}
		});
		// AFTER_DAMAGE is not fired for lethal damage, so revives are counted here.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if(source.getEntity() instanceof ServerPlayer player&&hostileCombatEntity(player,entity))COMBAT.recordValidHit(player,currentTick);
			if(entity instanceof LivingEntity living)RUN_RUNTIME.mobDied(living);
			if (entity instanceof ServerPlayer player){if(RUN_RUNTIME.runId(player)==null)fieldDeath(player);else {failRunAfterFinalDeath(player,serverFor(player));sendRunReviveState(player);}}
		});
		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, killed, damageSource) -> {
			if (killer instanceof ServerPlayer player) {
				if(!CombatRewards.excludesRewardTags(killed.entityTags()))grantCombatExperience(player, level.getServer(), CombatRewards.experienceFor(killed.getType()));
				UUID completedRun = RUN_RUNTIME.bossKilled(player, killed.getUUID());
				if (completedRun != null) completeRun(player, level.getServer(), completedRun);
				if (!CombatRewards.excludesRewardTags(killed.entityTags()) && killed.getType().getDescriptionId().equals("entity.minecraft.zombie")) recordQuestZombieKill(player, level.getServer(), killed.getUUID());
			}
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			literal("dalmaru")
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
				.then(literal("xp").then(argument("amount", IntegerArgumentType.integer(1, 100000))
					.executes(command -> { grantExperience(command.getSource().getPlayerOrException(), command.getSource().getServer(), IntegerArgumentType.getInteger(command, "amount")); return 1; })))
				.then(literal("quest")
					.then(literal("accept").executes(command -> { acceptQuest(command.getSource().getPlayerOrException(), command.getSource().getServer()); return 1; }))
					.then(literal("status").executes(command -> { showQuestStatus(command.getSource().getPlayerOrException(), command.getSource().getServer()); return 1; })))
				.then(literal("profession").then(argument("job", StringArgumentType.word()).executes(command -> { selectProfession(command.getSource().getPlayerOrException(), command.getSource().getServer(), StringArgumentType.getString(command, "job")); return 1; })))
				.then(kr.dalmaru.ops.OperatorAdjustCommand.command(player -> { var state = CONNECTIONS.get(player.getUUID()); return state == null ? null : state.accountSession; }, (server, profile) -> { for (var online : server.getPlayerList().getPlayers()) { var state = CONNECTIONS.get(online.getUUID()); if (state != null && state.accountSession != null && profile.characterId().equals(state.accountSession.activeCharacterId())) sendCharacterProfile(online, profile); } }))
		));

        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->dispatcher.register(literal("dalmaru-town")
            .requires(source->source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .then(argument("name",StringArgumentType.word()).then(argument("radius",IntegerArgumentType.integer(1,128)).executes(command->{
                var player=command.getSource().getPlayerOrException();
                try{CHARACTERS.registerTown(StringArgumentType.getString(command,"name"),player.level().dimension().identifier().toString(),player.blockPosition().getX(),player.blockPosition().getY(),player.blockPosition().getZ(),IntegerArgumentType.getInteger(command,"radius"));}
                catch(Exception error){LOGGER.error("Failed to register town safety boundary.",error);player.sendSystemMessage(Component.literal("마을 경계 저장에 실패했습니다."));}
                return 1;
            })))));
        CommandRegistrationCallback.EVENT.register((dispatcher,access,environment)->dispatcher.register(literal("dalmaru-equipment")
            .then(literal("equip").then(argument("id",StringArgumentType.word()).then(argument("request",com.mojang.brigadier.arguments.LongArgumentType.longArg(1)).executes(command->{moveEquipment(command.getSource().getPlayerOrException(),StringArgumentType.getString(command,"id"),com.mojang.brigadier.arguments.LongArgumentType.getLong(command,"request"),true);return 1;}))))
            .then(literal("unequip").then(argument("id",StringArgumentType.word()).then(argument("request",com.mojang.brigadier.arguments.LongArgumentType.longArg(1)).executes(command->{moveEquipment(command.getSource().getPlayerOrException(),StringArgumentType.getString(command,"id"),com.mojang.brigadier.arguments.LongArgumentType.getLong(command,"request"),false);return 1;}))))));

		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.Hello.TYPE, (payload, context) -> {
			ConnectionState state = stateFor(context.player().getUUID());
			if (payload.protocolVersion() != PROTOCOL_VERSION) {
				disconnect(context, "달마루 통신 버전이 맞지 않습니다.");
				return;
			}
			state.compatible = true;
			ServerPlayNetworking.send(context.player(), new DalmaruPayloads.Welcome(PROTOCOL_VERSION));
			sendCharacterSelectionReady(context.player(), state);
		});

		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.ProbeRequest.TYPE, (payload, context) -> {
			if (payload.requestId() <= 0) {
				disconnect(context, "잘못된 달마루 연결 확인 요청입니다.");
				return;
			}

			ConnectionState state = stateFor(context.player().getUUID());
			if (!state.compatible) {
				disconnect(context, "달마루 호환성 확인 전에 요청할 수 없습니다.");
				return;
			}

			long now = System.nanoTime();
			if (now - state.lastProbeNanos >= RATE_LIMIT_NANOS) {
				state.lastProbeNanos = now;
				ServerPlayNetworking.send(context.player(), new DalmaruPayloads.ProbeResponse(payload.requestId(), false));
			} else if (now - state.lastLimitedNanos >= RATE_LIMIT_NANOS) {
				state.lastLimitedNanos = now;
				ServerPlayNetworking.send(context.player(), new DalmaruPayloads.ProbeResponse(payload.requestId(), true));
			}
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(literal("party")
			.then(literal("invite").then(argument("player", StringArgumentType.word()).executes(command -> { partyInvite(command.getSource().getPlayerOrException(), StringArgumentType.getString(command, "player")); return 1; })))
			.then(literal("accept").executes(command -> { partyAccept(command.getSource().getPlayerOrException()); return 1; }))
			.then(literal("leave").executes(command -> { PARTIES.leave(command.getSource().getPlayerOrException().getUUID()); return 1; }))));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterListRequest.TYPE, (payload, context) -> {
			ConnectionState state = stateFor(context.player().getUUID());
			if (payload.requestId() <= 0 || !state.compatible) return;
			if (state.accountFailure != null) {
				sendCharacterList(context, payload.requestId(), state.accountFailure, java.util.List.of());
				return;
			}
			if (state.accountSession == null) {
				sendCharacterList(context, payload.requestId(), "계정 준비 중입니다. 잠시 후 다시 시도하세요.", java.util.List.of());
				return;
			}
			long now = System.nanoTime();
			if (now - state.lastCharacterListNanos < RATE_LIMIT_NANOS) {
				sendCharacterList(context, payload.requestId(), "요청이 너무 빠릅니다. 잠시 후 다시 시도하세요.", java.util.List.of());
				return;
			}
			state.lastCharacterListNanos = now;
			AccountSession session = state.accountSession;
			CompletableFuture.supplyAsync(() -> {
				try {
					var pending = CHARACTERS.pendingDeletions(session.accountId());
					return CHARACTERS.list(session.accountId()).stream()
						.map(character -> new DalmaruPayloads.CharacterSummary(character.characterId(), character.slot(), character.displayName(), character.characterId().equals(session.activeCharacterId()), pending.contains(character.characterId())))
						.toList();
				} catch (Exception exception) {
					throw new IllegalStateException(exception);
				}
			}).thenAccept(characters -> context.server().execute(() -> {
				if (CONNECTIONS.get(context.player().getUUID()) == state && state.accountSession == session) {
					sendCharacterList(context, payload.requestId(), "캐릭터 목록을 불러왔습니다.", characters);
				}
			})).exceptionally(error -> {
				LOGGER.error("Failed to load Dalmaru characters for {}.", session.accountId(), error);
				context.server().execute(() -> sendCharacterList(context, payload.requestId(), "캐릭터 목록을 불러오지 못했습니다.", java.util.List.of()));
				return null;
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterSelectRequest.TYPE, (payload, context) -> {
			ConnectionState state = stateFor(context.player().getUUID());
			if (payload.requestId() <= 0 || !state.compatible || state.accountSession == null) {
				sendCharacterSelect(context, payload.requestId(), payload.characterId(), false, false, "계정 준비가 완료되지 않았습니다.");
				return;
			}
			if (state.accountFailure != null) {
				sendCharacterSelect(context, payload.requestId(), payload.characterId(), false, false, state.accountFailure);
				return;
			}
            AccountSession session=state.accountSession;
            var player=context.player();
            boolean changing=!payload.characterId().equals(session.activeCharacterId());
            String original=kr.dalmaru.account.VanillaCharacterState.capture(player);
            boolean[] installed={false};boolean committed=false;
            try {
                var stamina=COMBAT.stamina(player.getUUID(),currentTick);
                if(state.startPending||FIELD_REVIVES.containsKey(player.getUUID()))throw new IllegalArgumentException("Character is awaiting entry or revival.");
                if(changing&&session.activeCharacterId()!=null&&(RUN_RUNTIME.runId(player)!=null||(stamina!=null&&stamina.inCombat())||player.containerMenu!=player.inventoryMenu
                    ||!CHARACTERS.inTown(player.level().dimension().identifier().toString(),player.blockPosition().getX(),player.blockPosition().getY(),player.blockPosition().getZ())))throw new IllegalArgumentException("Switch only in a registered town outside combat and storage.");
                CharacterProfile profile=CHARACTERS.profile(payload.characterId());
                var ranks=SKILLS.ranks(payload.characterId());
                CharacterSelectionResult selection=CHARACTERS.selectActive(session.accountId(),session.connectionGeneration(),payload.requestId(),payload.characterId(),original,
                    inventory->{installed[0]=true;kr.dalmaru.account.VanillaCharacterState.restore(player,inventory);});
                committed=true;
                if(changing){resetCharacterCaches(player,state);COMBAT.remove(player.getUUID());}
                state.accountSession=new AccountSession(session.accountId(),selection.characterId(),session.connectionGeneration());state.skillRanks=ranks;
                sendCharacterSelect(context,payload.requestId(),selection.characterId(),true,selection.replayed(),"캐릭터를 선택했습니다.");sendCharacterProfile(player,profile);loadout(player,0);
            } catch(Exception error) {
                if(closeUncertainSelection(player,state,installed[0],committed,error))return;
                if(!committed&&installed[0])kr.dalmaru.account.VanillaCharacterState.restore(player,original);
                LOGGER.warn("Rejected character selection for {}.",session.accountId(),error);
                sendCharacterSelect(context,payload.requestId(),payload.characterId(),false,false,"등록된 마을에서 전투·보관함을 종료한 뒤 선택하세요.");
            }
		});

		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterCreateRequest.TYPE, (payload, context) -> {
			ConnectionState state = stateFor(context.player().getUUID());
			if (payload.requestId() <= 0 || !state.compatible || state.accountSession == null || state.accountFailure != null) {
				sendCharacterCreate(context, payload.requestId(), new UUID(0, 0), payload.slot(), payload.displayName(), false, false, state.accountFailure == null ? "계정 준비가 완료되지 않았습니다." : state.accountFailure);
				return;
			}
			AccountSession session = state.accountSession;
			CompletableFuture.supplyAsync(() -> {
				try { return CHARACTERS.create(session.accountId(), session.connectionGeneration(), payload.requestId(), payload.slot(), payload.displayName()); }
				catch (Exception exception) { throw new IllegalStateException(exception); }
			}).thenAccept(result -> context.server().execute(() -> {
				if (CONNECTIONS.get(context.player().getUUID()) == state && state.accountSession == session) sendCharacterCreate(context, payload.requestId(), result.character().characterId(), result.character().slot(), result.character().displayName(), true, result.replayed(), result.replayed() ? "기존 생성 결과입니다." : "캐릭터를 생성했습니다.");
			})).exceptionally(error -> {
				LOGGER.warn("Rejected character creation for {}.", session.accountId(), error);
				context.server().execute(() -> sendCharacterCreate(context, payload.requestId(), new UUID(0, 0), payload.slot(), payload.displayName(), false, false, "캐릭터를 생성할 수 없습니다."));
				return null;
			});
		});

        ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterStatInvestmentRequest.TYPE,(payload,context)->
            investStatRequest(context,payload.requestId(),payload.characterId(),payload.statId(),payload.amount(),null));

        ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterStatResetRequest.TYPE,(payload,context)->
            resetStatsRequest(context,payload.requestId(),payload.characterId(),null));
        ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterProfessionRequest.TYPE,(payload,context)->
            professionRequest(context,payload.requestId(),payload.profession(),null));

		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.QuestRequest.TYPE, (payload, context) -> {
			ConnectionState state = stateFor(context.player().getUUID());
			if (payload.requestId() < 1 || !state.compatible || state.accountSession == null || state.accountFailure != null || state.accountSession.activeCharacterId() == null) {
				ServerPlayNetworking.send(context.player(), new DalmaruPayloads.QuestResponse(payload.requestId(), "퀘스트를 확인할 캐릭터를 먼저 선택하세요."));
				return;
			}
			if(payload.accept()){acceptQuestRequest(context.player(),context.server(),payload.requestId(),null);return;}
            AccountSession session = state.accountSession;
			CompletableFuture.supplyAsync(() -> {
				try { return QUESTS.status(session.activeCharacterId()); }
				catch (Exception exception) { throw new IllegalStateException(exception); }
			}).thenAccept(status -> context.server().execute(() -> {
				if (CONNECTIONS.get(context.player().getUUID()) == state && state.accountSession == session) ServerPlayNetworking.send(context.player(), new DalmaruPayloads.QuestResponse(payload.requestId(), status));
			})).exceptionally(error -> {
				LOGGER.warn("Rejected quest request for {}.", session.accountId(), error);
				context.server().execute(() -> ServerPlayNetworking.send(context.player(), new DalmaruPayloads.QuestResponse(payload.requestId(), "퀘스트를 처리할 수 없습니다.")));
				return null;
			});
		});
        ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.LoadoutRequest.TYPE,(payload,context)->loadout(context.player(),payload.requestId()));
        ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.LoadoutUpdate.TYPE,(payload,context)->updateLoadout(context.player(),payload));
        ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.SkillUseRequest.TYPE,(payload,context)->castSlot(context.player(),payload));
        ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CombatModeRequest.TYPE,(payload,context)->toggleMode(context.player(),payload.combat()));
        ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.RunReadyRequest.TYPE,(payload,context)->readyRun(context.player(),payload.preparationId(),payload.ready()));
        ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.DodgeRequest.TYPE,(payload,context)->dodge(context.player(),payload.direction()));
        ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.GuardRequest.TYPE,(payload,context)->guard(context.player(),payload.guard()));

        ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.EquipmentEnhanceRequest.TYPE,(payload,context)->enhanceRequest(context.player(),context.server(),payload.requestId(),payload.equipmentId(),null));
        ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.RefinementStateRequest.TYPE,(payload,context)->{
            var player=context.player();var state=CONNECTIONS.get(player.getUUID());if(payload.requestId()<1||state==null||!state.compatible||state.accountFailure!=null||state.accountSession==null||state.accountSession.activeCharacterId()==null)return;
            var session=state.accountSession;
            CompletableFuture.supplyAsync(()->{try{return EQUIPMENT.refinementState(session.accountId(),session.connectionGeneration(),payload.equipmentId());}catch(Exception error){throw new IllegalStateException(error);}})
                .thenAccept(result->context.server().execute(()->{if(CONNECTIONS.get(player.getUUID())==state&&state.accountSession==session)ServerPlayNetworking.send(player,new DalmaruPayloads.RefinementStateResponse(payload.requestId(),session.activeCharacterId(),result.equipmentId(),result.grade(),result.failures()));}))
                .exceptionally(error->{LOGGER.warn("Refinement state query failed for {}",session.accountId(),error);return null;});
        });
        ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.OperationRequest.TYPE,(payload,context)->{
            try{
                var envelope=new kr.dalmaru.request.RequestEnvelope(payload.requestId(),payload.connectionGeneration(),payload.activeCharacterId(),payload.operation(),com.google.gson.JsonParser.parseString(payload.payload()).getAsJsonObject());
                var body=envelope.payload();
                switch(envelope.operation()){
                    case "enhance_item"->{
                        if(body.size()!=1||!body.has("equipmentId")||!body.get("equipmentId").isJsonPrimitive()||!body.getAsJsonPrimitive("equipmentId").isString())throw new IllegalArgumentException("Invalid enhancement body");
                        enhanceRequest(context.player(),context.server(),payload.correlationId(),UUID.fromString(body.get("equipmentId").getAsString()),envelope);
                    }
                    case "accept_quest"->{
                    if(body.size()!=2||!body.has("questId")||!body.get("questId").isJsonPrimitive()||!body.getAsJsonPrimitive("questId").isString()||!body.has("contentVersion")||!body.get("contentVersion").isJsonPrimitive()||!body.getAsJsonPrimitive("contentVersion").isString())throw new IllegalArgumentException("Invalid quest acceptance body");
                    acceptQuestRequest(context.player(),context.server(),payload.correlationId(),envelope);
                }
                case "inherit_equipment"->{
                    if(body.size()!=2||!body.has("targetEquipmentId")||!body.get("targetEquipmentId").isJsonPrimitive()||!body.getAsJsonPrimitive("targetEquipmentId").isString()||!body.has("sourceEquipmentId")||!body.get("sourceEquipmentId").isJsonPrimitive()||!body.getAsJsonPrimitive("sourceEquipmentId").isString())throw new IllegalArgumentException("Invalid inheritance body");
                    inheritRequest(context.player(),context.server(),payload.correlationId(),UUID.fromString(body.get("targetEquipmentId").getAsString()),UUID.fromString(body.get("sourceEquipmentId").getAsString()),envelope);
                }
                case "refine_item"->{
                    if(body.size()!=1||!body.has("equipmentId")||!body.get("equipmentId").isJsonPrimitive()||!body.getAsJsonPrimitive("equipmentId").isString())throw new IllegalArgumentException("Invalid refinement body");
                    refineRequest(context.player(),context.server(),payload.correlationId(),UUID.fromString(body.get("equipmentId").getAsString()),envelope);
                }
                case "move_equipment"->{
                    if(body.size()!=2||!body.has("equipmentId")||!body.get("equipmentId").isJsonPrimitive()||!body.getAsJsonPrimitive("equipmentId").isString()||!body.has("equip")||!body.get("equip").isJsonPrimitive()||!body.getAsJsonPrimitive("equip").isBoolean())throw new IllegalArgumentException("Invalid equipment move body");
                    moveEquipment(context.player(),body.get("equipmentId").getAsString(),payload.correlationId(),body.get("equip").getAsBoolean(),envelope);
                }
                case "update_loadout"->{
                    if(body.size()!=2||!body.has("revision")||!body.get("revision").isJsonPrimitive()||!body.getAsJsonPrimitive("revision").isNumber()||!body.has("slots")||!body.get("slots").isJsonArray()||body.getAsJsonArray("slots").size()!=5)throw new IllegalArgumentException("Invalid loadout body");
                    long revision;try{revision=body.get("revision").getAsBigDecimal().longValueExact();}catch(ArithmeticException error){throw new IllegalArgumentException("Invalid loadout revision",error);}
                    var slots=new java.util.ArrayList<String>();for(var slot:body.getAsJsonArray("slots")){if(!slot.isJsonPrimitive()||!slot.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Invalid loadout slot");slots.add(slot.getAsString());}
                    updateLoadout(context.player(),new DalmaruPayloads.LoadoutUpdate(payload.correlationId(),envelope.activeCharacterId(),revision,slots),envelope);
                }
                case "select_profession"->{
                    if(body.size()!=1||!body.has("profession")||!body.get("profession").isJsonPrimitive()||!body.getAsJsonPrimitive("profession").isString())throw new IllegalArgumentException("Invalid profession body");
                    professionRequest(context,payload.correlationId(),body.get("profession").getAsString(),envelope);
                }
                case "reset_stats"->{
                    if(body.size()!=0)throw new IllegalArgumentException("Invalid reset body");
                    resetStatsRequest(context,payload.correlationId(),envelope.activeCharacterId(),envelope);
                }
                case "delete_character","restore_character"->{
                    if(body.size()!=1||!body.has("characterId")||!body.get("characterId").isJsonPrimitive()||!body.getAsJsonPrimitive("characterId").isString())throw new IllegalArgumentException("Invalid character life body");
                    characterLifeRequest(context,payload.correlationId(),UUID.fromString(body.get("characterId").getAsString()),"restore_character".equals(envelope.operation()),envelope);
                }
                case "list_villages"->{
                    if(body.size()!=0)throw new IllegalArgumentException("Invalid village list body");
                    villageRequest(context,payload.correlationId(),null,envelope);
                }
                case "open_village"->{
                    if(body.size()!=1||!body.has("villageId")||!body.get("villageId").isJsonPrimitive()||!body.getAsJsonPrimitive("villageId").isString())throw new IllegalArgumentException("Invalid village body");
                    villageRequest(context,payload.correlationId(),body.get("villageId").getAsString(),envelope);
                }
                case "invest_stat"->{
                    if(body.size()!=2||!body.has("statId")||!body.has("amount")||!body.get("statId").isJsonPrimitive()||!body.getAsJsonPrimitive("statId").isNumber()||!body.get("amount").isJsonPrimitive()||!body.getAsJsonPrimitive("amount").isNumber())throw new IllegalArgumentException("Invalid stat body");
                    int stat,amount;try{stat=body.get("statId").getAsBigDecimal().intValueExact();amount=body.get("amount").getAsBigDecimal().intValueExact();}catch(ArithmeticException error){throw new IllegalArgumentException("Invalid stat integer",error);}
                    investStatRequest(context,payload.correlationId(),envelope.activeCharacterId(),stat,amount,envelope);
                }
                case "learn_skill"->{
                        if(body.size()!=1||!body.has("skillId")||!body.get("skillId").isJsonPrimitive()||!body.getAsJsonPrimitive("skillId").isString())throw new IllegalArgumentException("Invalid skill body");
                        learnSkillRequest(context,payload.correlationId(),body.get("skillId").getAsString(),envelope);
                    }
                    default->throw new IllegalArgumentException("Unsupported operation");
                }
            }catch(IllegalArgumentException|IllegalStateException|com.google.gson.JsonParseException error){rejectOperation(context.player(),payload.correlationId(),payload.requestId());}
        });
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.EquipmentListRequest.TYPE, (payload, context) -> {
			ConnectionState state = stateFor(context.player().getUUID());
			if (payload.requestId() < 1 || !state.compatible || state.accountSession == null || state.accountSession.activeCharacterId() == null) return;
			AccountSession session = state.accountSession;
			CompletableFuture.supplyAsync(() -> { try { return EQUIPMENT.equipped(session.activeCharacterId()).stream().map(equipment -> new DalmaruPayloads.EquipmentData(equipment.equipmentId(), equipment.slotId(), equipment.itemId(), equipment.enhancement(), equipment.consecutiveFailures(), equipment.bound())).toList(); } catch (Exception exception) { throw new IllegalStateException(exception); } }).thenAccept(equipment -> context.server().execute(() -> { if (CONNECTIONS.get(context.player().getUUID()) == state && state.accountSession == session) ServerPlayNetworking.send(context.player(), new DalmaruPayloads.EquipmentListResponse(payload.requestId(), "", equipment)); })).exceptionally(error -> { context.server().execute(() -> ServerPlayNetworking.send(context.player(), new DalmaruPayloads.EquipmentListResponse(payload.requestId(), "장비를 불러올 수 없습니다.", java.util.List.of()))); return null; });
		});
        ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.RunEnterRequest.TYPE,(payload,context)->prepareRun(payload,context));

        ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.SkillLearnRequest.TYPE,(payload,context)->learnSkillRequest(context,payload.requestId(),payload.skillId(),null));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.SkillRanksRequest.TYPE, (payload, context) -> {
			ConnectionState state = stateFor(context.player().getUUID());
			if (payload.requestId() < 1 || state.accountSession == null || state.accountSession.activeCharacterId() == null) return;
			AccountSession session = state.accountSession;
			CompletableFuture.supplyAsync(() -> { try { return SKILLS.ranks(session.activeCharacterId()).entrySet().stream().map(entry -> new DalmaruPayloads.SkillRank(entry.getKey(), entry.getValue())).toList(); } catch (Exception exception) { throw new IllegalStateException(exception); } })
				.thenAccept(ranks -> context.server().execute(() -> { if (CONNECTIONS.get(context.player().getUUID()) == state && state.accountSession == session) ServerPlayNetworking.send(context.player(), new DalmaruPayloads.SkillRanksResponse(payload.requestId(), ranks)); }));
		});
	}

    private static void sendRunFailure(ServerPlayNetworking.Context context,long requestId,Throwable error){
        ServerPlayNetworking.send(context.player(),new DalmaruPayloads.RunResponse(requestId,false,new UUID(0,0),0,0,"",runEntryStatus(error)));
    }
    private static void moveEquipment(ServerPlayer player,String id,long requestId,boolean equip){moveEquipment(player,id,requestId,equip,null);}
    private static void moveEquipment(ServerPlayer player,String id,long requestId,boolean equip,kr.dalmaru.request.RequestEnvelope envelope){
        var state=CONNECTIONS.get(player.getUUID());
        if(state==null||!state.compatible||state.accountSession==null||state.accountFailure!=null||requestId<1){if(envelope!=null)rejectOperation(player,requestId,envelope.requestId());return;}
        var stamina=COMBAT.stamina(player.getUUID(),currentTick);boolean editable=!state.startPending&&RUN_RUNTIME.runId(player)==null&&(stamina==null||!stamina.inCombat());
        if(envelope==null&&!editable){player.sendSystemMessage(Component.literal("전투·공략 중 장비를 변경할 수 없습니다."));return;}
        var session=state.accountSession;
        kr.dalmaru.equipment.EquipmentRepository.MovementResult result;
        try{result=EQUIPMENT.moveJournaled(session.accountId(),session.connectionGeneration(),requestId,UUID.fromString(id),equip,envelope,editable);}
        catch(Exception error){
            LOGGER.warn("Equipment move outcome for {}",session.accountId(),error);
            if(envelope==null)player.sendSystemMessage(Component.literal("장비 소유권·직업·레벨 조건을 확인하세요."));
            else if(kr.dalmaru.request.RequestJournal.definitelyRejected(error))rejectOperation(player,requestId,envelope.requestId());
            else ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(requestId,envelope.requestId(),new UUID(0,0),"accepted","{}",false));
            return;
        }
        var items=result.equipment();
        try{
            if(editable&&state.activeProfile!=null)COMBAT.equipment(player,state.activeProfile,items.stream().filter(item->item.slotId()>=0).toList());
            ServerPlayNetworking.send(player,new DalmaruPayloads.EquipmentListResponse(requestId,"장비 이동 결과를 확인했습니다.",items.stream().map(item->new DalmaruPayloads.EquipmentData(item.equipmentId(),item.slotId(),item.itemId(),item.enhancement(),item.consecutiveFailures(),item.bound())).toList()));
        }catch(Exception error){
            LOGGER.error("Committed equipment move requires world recovery for {}",session.accountId(),error);
            if(envelope==null)player.sendSystemMessage(Component.literal("이동은 저장됐으나 반영 확인이 필요합니다. 장비 목록을 다시 확인하세요."));
        }
        if(envelope!=null){var original=new com.google.gson.JsonObject();original.addProperty("equipmentId",result.equipmentId().toString());original.addProperty("slotId",result.slotId());var receipt=result.receipt();ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(requestId,receipt.requestId(),receipt.eventId(),receipt.state(),original.toString(),result.replayed()));}
    }
	private static MinecraftServer serverFor(ServerPlayer player) { return ((ServerLevel) player.level()).getServer(); }

    private static boolean hostileCombatEntity(ServerPlayer player,net.minecraft.world.entity.Entity other){
        if(!(other instanceof LivingEntity living))return false;
        UUID run=RUN_RUNTIME.runId(player);
        return kr.dalmaru.combat.CombatRules.hostileAction(RUN_RUNTIME.mayTarget(player,living),living.getType().getCategory()==net.minecraft.world.entity.MobCategory.MONSTER,living instanceof net.minecraft.world.entity.Mob mob&&mob.getTarget()==player,run!=null&&living.entityTags().contains("dalmaru.run."+run));
    }
	private static boolean playerAttack(DamageSource source) {
		return source.is(DamageTypes.PLAYER_ATTACK) || source.is(DamageTypeTags.IS_PROJECTILE);
	}
	public static boolean managedDamage(LivingEntity target, DamageSource source) {
		return CombatRuntime.explicitRpgDamage(target)
			|| (source.getEntity() instanceof ServerPlayer attacker && COMBAT.managed(attacker) && playerAttack(source))
			|| (target instanceof ServerPlayer player && COMBAT.managed(player));
	}
	public static float incomingDamage(LivingEntity target, DamageSource source, float amount) {
		if(source.getEntity() instanceof ServerPlayer attacker && !RUN_RUNTIME.mayTarget(attacker,target))return 0;
        if(target instanceof ServerPlayer victim && !RUN_RUNTIME.mayReceive(victim,source.getEntity()))return 0;
        if (CombatRuntime.explicitRpgDamage(target)) return target instanceof ServerPlayer victim?CombatUnits.toEngine(COMBAT.protect(victim,source,CombatUnits.toRpg(amount),currentTick,hostileCombatEntity(victim,source.getEntity()))):amount;
		if (!Float.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Invalid incoming damage.");
		if (amount == 0) return 0;
		if (source.getEntity() instanceof ServerPlayer attacker && COMBAT.managed(attacker) && playerAttack(source)) {
			COMBAT.attackStarted(attacker);boolean magic = COMBAT.magical(attacker);
			int defense = target instanceof ServerPlayer player ? COMBAT.defense(player, magic) : RUN_RUNTIME.bossDefense(target.getUUID(), magic);
			double multiplier = source.is(DamageTypeTags.IS_PROJECTILE)&&source.getDirectEntity()!=null?Math.min(1,source.getDirectEntity().getDeltaMovement().length()/3.0):1;
			return CombatUnits.toEngine(COMBAT.targetedAttack(attacker,target,multiplier,defense,true,currentTick));
		}
		if (target instanceof ServerPlayer player && COMBAT.managed(player)) {
			boolean magic = source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC) || source.is(DamageTypeTags.IS_FIRE);
			int defense = source.is(DamageTypeTags.BYPASSES_ARMOR) ? 0 : COMBAT.defense(player, magic);
			return CombatUnits.toEngine(COMBAT.protect(player,source,CombatCalculator.damage(CombatUnits.toRpg(amount),1,1,false,defense),currentTick,hostileCombatEntity(player,source.getEntity())));
		}
		return amount;
	}
    public static int aimTicks(LivingEntity user,int ticks){return user instanceof ServerPlayer player?COMBAT.aimTicks(player,ticks):ticks;}
	private static boolean rpgSkillDamage(ServerPlayer player, LivingEntity target, float amount) {
		boolean magic = COMBAT.magical(player);
		int defense = target instanceof ServerPlayer victim ? COMBAT.defense(victim, magic) : RUN_RUNTIME.bossDefense(target.getUUID(), magic);
		int damage = COMBAT.targetedAttack(player,target,amount/player.getAttributeValue(Attributes.ATTACK_DAMAGE),defense,false,currentTick);
		return CombatRuntime.hurtRpg(target, player.damageSources().playerAttack(player), damage);
	}
    private static void rejectOperation(ServerPlayer player,long correlation,UUID request){
        ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,request,new UUID(0,0),"rejected","{}",false));
    }
    private static void professionRequest(ServerPlayNetworking.Context context,long correlation,String name,kr.dalmaru.request.RequestEnvelope envelope){
        var player=context.player();var state=stateFor(player.getUUID());
        if(correlation<1||!state.compatible||state.accountFailure!=null||state.accountSession==null||state.accountSession.activeCharacterId()==null){
            if(envelope!=null)rejectOperation(player,correlation,envelope.requestId());else sendCharacterProfession(context,correlation,false,"전직할 캐릭터를 먼저 선택하세요.",null);return;
        }
        CharacterProfession profession;
        try{profession=CharacterProfession.valueOf(name.toUpperCase(java.util.Locale.ROOT));}
        catch(IllegalArgumentException error){if(envelope!=null)rejectOperation(player,correlation,envelope.requestId());else sendCharacterProfession(context,correlation,false,"선택할 수 없는 직업입니다.",null);return;}
        var session=state.accountSession;
        CompletableFuture.supplyAsync(()->{
            try{return CHARACTERS.selectProfessionJournaled(session.accountId(),session.connectionGeneration(),correlation,session.activeCharacterId(),profession,envelope);}
            catch(Exception error){throw new IllegalStateException(error);}
        }).thenAccept(result->context.server().execute(()->{
            if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
            if(envelope==null)sendCharacterProfession(context,correlation,true,result.profession().koreanName()+" 전직을 완료했습니다.",result.profile());
            else{
                sendCharacterProfile(player,result.profile());var original=new com.google.gson.JsonObject();original.addProperty("profession",result.profession().name());var receipt=result.receipt();
                ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,receipt.requestId(),receipt.eventId(),receipt.state(),original.toString(),result.replayed()));
            }
        })).exceptionally(error->{context.server().execute(()->{
            if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
            if(envelope==null)sendCharacterProfession(context,correlation,false,"전직 조건을 확인하세요.",null);
            else if(kr.dalmaru.request.RequestJournal.definitelyRejected(error))rejectOperation(player,correlation,envelope.requestId());
            else ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,envelope.requestId(),new UUID(0,0),"accepted","{}",false));
            LOGGER.warn("Profession request failed for {}",session.accountId(),error);
        });return null;});
    }

    private static void characterLifeRequest(ServerPlayNetworking.Context context,long correlation,UUID characterId,boolean restore,kr.dalmaru.request.RequestEnvelope envelope){
        var player=context.player();var state=stateFor(player.getUUID());
        if(correlation<1||!state.compatible||state.accountFailure!=null||state.accountSession==null){rejectOperation(player,correlation,envelope.requestId());return;}
        var session=state.accountSession;
        CompletableFuture.supplyAsync(()->{try{return restore?CHARACTERS.restoreJournaled(session.accountId(),session.connectionGeneration(),correlation,characterId,envelope):CHARACTERS.deleteJournaled(session.accountId(),session.connectionGeneration(),correlation,characterId,envelope);}catch(Exception error){throw new IllegalStateException(error);}})
            .thenAccept(result->context.server().execute(()->{if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;var receipt=result.receipt();ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,receipt.requestId(),receipt.eventId(),receipt.state(),result.result().toString(),result.replayed()));}))
            .exceptionally(error->{context.server().execute(()->{if(CONNECTIONS.get(player.getUUID())==state&&state.accountSession==session&&kr.dalmaru.request.RequestJournal.definitelyRejected(error))rejectOperation(player,correlation,envelope.requestId());LOGGER.warn("Character life request failed for {}",session.accountId(),error);});return null;});
    }

    private static void villageRequest(ServerPlayNetworking.Context context,long correlation,String villageId,kr.dalmaru.request.RequestEnvelope envelope){
        var player=context.player();var state=stateFor(player.getUUID());
        if(correlation<1||!state.compatible||state.accountFailure!=null||state.accountSession==null){rejectOperation(player,correlation,envelope.requestId());return;}
        var session=state.accountSession;
        com.google.gson.JsonObject fresh;
        try{fresh=villageId==null?villageList():villageOpen(villageId);}catch(IllegalArgumentException error){rejectOperation(player,correlation,envelope.requestId());return;}
        CompletableFuture.supplyAsync(()->{try{return CHARACTERS.note(session.accountId(),session.connectionGeneration(),correlation,envelope,fresh);}catch(Exception error){throw new IllegalStateException(error);}})
            .thenAccept(result->context.server().execute(()->{if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;var receipt=result.receipt();ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,receipt.requestId(),receipt.eventId(),receipt.state(),result.result().toString(),result.replayed()));}))
            .exceptionally(error->{context.server().execute(()->{if(CONNECTIONS.get(player.getUUID())==state&&state.accountSession==session&&kr.dalmaru.request.RequestJournal.definitelyRejected(error))rejectOperation(player,correlation,envelope.requestId());LOGGER.warn("Village request failed for {}",session.accountId(),error);});return null;});
    }

    private static com.google.gson.JsonObject villageList(){
        var villages=new com.google.gson.JsonArray();
        for(var village:kr.dalmaru.content.VillageCatalog.current()){var item=new com.google.gson.JsonObject();item.addProperty("id",village.id());item.addProperty("name",village.name());villages.add(item);}
        var root=new com.google.gson.JsonObject();root.add("villages",villages);return root;
    }

    private static com.google.gson.JsonObject villageOpen(String id){
        var village=kr.dalmaru.content.VillageCatalog.get(id);var npc=village.npcs().getFirst();var root=new com.google.gson.JsonObject();
        root.addProperty("id",village.id());root.addProperty("name",village.name());root.addProperty("x",village.zone().x());root.addProperty("y",village.zone().y());root.addProperty("z",village.zone().z());root.addProperty("npc",npc.name());root.addProperty("line",npc.lines().getFirst());return root;
    }

    private static void resetStatsRequest(ServerPlayNetworking.Context context,long correlation,UUID character,kr.dalmaru.request.RequestEnvelope envelope){
        var player=context.player();var state=stateFor(player.getUUID());
        if(correlation<1||!state.compatible||state.accountFailure!=null||state.accountSession==null||!character.equals(state.accountSession.activeCharacterId())){
            if(envelope!=null)rejectOperation(player,correlation,envelope.requestId());else sendCharacterStatReset(context,correlation,character,false,false,"무료 초기화 준비가 완료되지 않았습니다.",null);return;
        }
        var session=state.accountSession;
        CompletableFuture.supplyAsync(()->{
            try{return CHARACTERS.resetStatsJournaled(session.accountId(),session.connectionGeneration(),correlation,character,envelope);}
            catch(Exception error){throw new IllegalStateException(error);}
        }).thenAccept(result->context.server().execute(()->{
            if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
            if(envelope==null)sendCharacterStatReset(context,correlation,character,true,result.replayed(),result.replayed()?"기존 초기화 결과입니다.":"능력치를 초기화했습니다.",result.profile());
            else{
                sendCharacterProfile(player,result.profile());var receipt=result.receipt();
                ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,receipt.requestId(),receipt.eventId(),receipt.state(),result.originalResult().toString(),result.replayed()));
            }
        })).exceptionally(error->{context.server().execute(()->{
            if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
            if(envelope==null)sendCharacterStatReset(context,correlation,character,false,false,"능력치를 초기화할 수 없습니다.",null);
            else if(kr.dalmaru.request.RequestJournal.definitelyRejected(error))rejectOperation(player,correlation,envelope.requestId());
            else ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,envelope.requestId(),new UUID(0,0),"accepted","{}",false));
            LOGGER.warn("Stat reset request failed for {}",session.accountId(),error);
        });return null;});
    }

    private static void investStatRequest(ServerPlayNetworking.Context context,long correlation,UUID character,int statId,int amount,kr.dalmaru.request.RequestEnvelope envelope){
        var player=context.player();var state=stateFor(player.getUUID());
        if(correlation<1||amount!=1||!state.compatible||state.accountFailure!=null||state.accountSession==null||!character.equals(state.accountSession.activeCharacterId())){
            if(envelope!=null)rejectOperation(player,correlation,envelope.requestId());else sendCharacterStatInvestment(context,correlation,character,false,false,"능력치 투자 준비가 완료되지 않았습니다.",null);return;
        }
        var session=state.accountSession;
        CompletableFuture.supplyAsync(()->{
            try{return CHARACTERS.investStatJournaled(session.accountId(),session.connectionGeneration(),correlation,character,CharacterStat.fromId(statId),amount,envelope);}
            catch(Exception error){throw new IllegalStateException(error);}
        }).thenAccept(result->context.server().execute(()->{
            if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
            if(envelope==null)sendCharacterStatInvestment(context,correlation,character,true,result.replayed(),result.replayed()?"기존 투자 결과입니다.":"능력치를 투자했습니다.",result.profile());
            else{
                sendCharacterProfile(player,result.profile());var receipt=result.receipt();
                ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,receipt.requestId(),receipt.eventId(),receipt.state(),result.originalResult().toString(),result.replayed()));
            }
        })).exceptionally(error->{context.server().execute(()->{
            if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
            if(envelope==null)sendCharacterStatInvestment(context,correlation,character,false,false,"능력치를 투자할 수 없습니다.",null);
            else if(kr.dalmaru.request.RequestJournal.definitelyRejected(error))rejectOperation(player,correlation,envelope.requestId());
            else ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,envelope.requestId(),new UUID(0,0),"accepted","{}",false));
            LOGGER.warn("Stat investment request failed for {}",session.accountId(),error);
        });return null;});
    }

    private static void learnSkillRequest(ServerPlayNetworking.Context context,long correlation,String skillId,kr.dalmaru.request.RequestEnvelope envelope){
        var player=context.player();var state=stateFor(player.getUUID());
        if(correlation<1||!state.compatible||state.accountFailure!=null||state.accountSession==null||state.accountSession.activeCharacterId()==null){
            if(envelope!=null)rejectOperation(player,correlation,envelope.requestId());else sendSkillLearn(context,correlation,false,"\uc804\uc9c1\ud55c \uce90\ub9ad\ud130\ub97c \uba3c\uc800 \uc120\ud0dd\ud558\uc138\uc694.",null);return;
        }
        var session=state.accountSession;
        var profession=kr.dalmaru.skill.SkillDefinition.capabilities().get(skillId);
        if(profession==null){if(envelope!=null)rejectOperation(player,correlation,envelope.requestId());else sendSkillLearn(context,correlation,false,"\uc54c \uc218 \uc5c6\ub294 \uae30\uc220\uc785\ub2c8\ub2e4.",null);return;}
        CompletableFuture.supplyAsync(()->{
            try{return SKILLS.learnJournaled(session.accountId(),session.connectionGeneration(),correlation,session.activeCharacterId(),profession,skillId,envelope);}
            catch(Exception error){throw new IllegalStateException(error);}
        }).thenAccept(result->context.server().execute(()->{
            if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
            installSkillRanks(state,result);
            if(envelope==null)sendSkillLearn(context,correlation,true,skillId+" "+result.rank()+"\ub2e8\uacc4\ub97c \uc2b5\ub4dd\ud588\uc2b5\ub2c8\ub2e4.",result.profile());
            else{
                sendCharacterProfile(player,result.profile());
                var original=new com.google.gson.JsonObject();original.addProperty("skillId",skillId);original.addProperty("rank",result.rank());var receipt=result.receipt();
                ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,receipt.requestId(),receipt.eventId(),receipt.state(),original.toString(),result.replayed()));
            }
        })).exceptionally(error->{context.server().execute(()->{
            if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
            if(envelope==null)sendSkillLearn(context,correlation,false,"\uae30\uc220\uc744 \uc2b5\ub4dd\ud560 \uc218 \uc5c6\uc2b5\ub2c8\ub2e4.",null);
            else if(kr.dalmaru.request.RequestJournal.definitelyRejected(error))rejectOperation(player,correlation,envelope.requestId());
            else ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,envelope.requestId(),new UUID(0,0),"accepted","{}",false));
            LOGGER.warn("Skill learning outcome for {}",session.accountId(),error);
        });return null;});
    }
    private static void acceptQuestRequest(ServerPlayer player,MinecraftServer server,long correlation,kr.dalmaru.request.RequestEnvelope envelope){
        var state=CONNECTIONS.get(player.getUUID());if(correlation<1||state==null||!state.compatible||state.accountFailure!=null||state.accountSession==null||state.accountSession.activeCharacterId()==null){
            if(envelope!=null)rejectOperation(player,correlation,envelope.requestId());else ServerPlayNetworking.send(player,new DalmaruPayloads.QuestResponse(correlation,"의뢰를 수락할 캐릭터를 먼저 선택하세요."));return;
        }
        var session=state.accountSession;
        CompletableFuture.supplyAsync(()->{try{return QUESTS.acceptJournaled(session.accountId(),session.connectionGeneration(),correlation,session.activeCharacterId(),envelope);}catch(Exception error){throw new IllegalStateException(error);}})
            .thenAccept(result->server.execute(()->{
                if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
                ServerPlayNetworking.send(player,new DalmaruPayloads.QuestResponse(correlation,result.currentStatus()));
                if(envelope!=null){var original=new com.google.gson.JsonObject();original.addProperty("questId",QuestRepository.QUEST_ID);original.addProperty("contentVersion",QuestRepository.contentVersion());original.addProperty("created",result.created());var receipt=result.receipt();ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,receipt.requestId(),receipt.eventId(),receipt.state(),original.toString(),result.replayed()));}
            }))
            .exceptionally(error->{server.execute(()->{if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
                LOGGER.warn("Quest acceptance outcome for {}",session.accountId(),error);
                if(envelope==null)ServerPlayNetworking.send(player,new DalmaruPayloads.QuestResponse(correlation,"의뢰 수락 조건을 확인하세요."));
                else if(kr.dalmaru.request.RequestJournal.definitelyRejected(error))rejectOperation(player,correlation,envelope.requestId());
                else ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,envelope.requestId(),new UUID(0,0),"accepted","{}",false));
            });return null;});
    }
    private static void inheritRequest(ServerPlayer player,MinecraftServer server,long correlation,UUID target,UUID source,kr.dalmaru.request.RequestEnvelope envelope){
        var state=CONNECTIONS.get(player.getUUID());if(correlation<1||state==null||!state.compatible||state.accountFailure!=null||state.accountSession==null||state.accountSession.activeCharacterId()==null){rejectOperation(player,correlation,envelope.requestId());return;}
        var session=state.accountSession;
        CompletableFuture.supplyAsync(()->{try{return EQUIPMENT.inheritJournaled(session.accountId(),session.connectionGeneration(),correlation,target,source,envelope);}catch(Exception error){throw new IllegalStateException(error);}})
            .thenAccept(result->server.execute(()->{
                if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
                ServerPlayNetworking.send(player,new DalmaruPayloads.EquipmentListResponse(correlation,result.currentCredit()==null?"계승 대상은 현재 목록에 없습니다.":"현재 계승 잔액: "+result.currentCredit(),result.equipment().stream().map(item->new DalmaruPayloads.EquipmentData(item.equipmentId(),item.slotId(),item.itemId(),item.enhancement(),item.consecutiveFailures(),item.bound())).toList()));
                var original=new com.google.gson.JsonObject();original.addProperty("targetEquipmentId",result.original().targetEquipmentId().toString());original.addProperty("sourceEquipmentId",result.sourceEquipmentId().toString());original.addProperty("credit",result.original().credit());var receipt=result.receipt();
                ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,receipt.requestId(),receipt.eventId(),receipt.state(),original.toString(),result.original().replayed()));
            }))
            .exceptionally(error->{server.execute(()->{if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
                LOGGER.warn("Inheritance request outcome for {}",session.accountId(),error);
                if(kr.dalmaru.request.RequestJournal.definitelyRejected(error))rejectOperation(player,correlation,envelope.requestId());
                else ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,envelope.requestId(),new UUID(0,0),"accepted","{}",false));
            });return null;});
    }
    private static void refineRequest(ServerPlayer player,MinecraftServer server,long correlation,UUID equipment,kr.dalmaru.request.RequestEnvelope envelope){
        var state=CONNECTIONS.get(player.getUUID());if(correlation<1||state==null||!state.compatible||state.accountFailure!=null||state.accountSession==null||state.accountSession.activeCharacterId()==null){rejectOperation(player,correlation,envelope.requestId());return;}
        var session=state.accountSession;
        CompletableFuture.supplyAsync(()->{try{return EQUIPMENT.refineJournaled(session.accountId(),session.connectionGeneration(),correlation,equipment,envelope);}catch(Exception error){throw new IllegalStateException(error);}})
            .thenAccept(result->server.execute(()->{
                if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
                ServerPlayNetworking.send(player,new DalmaruPayloads.RefinementStateResponse(correlation,session.activeCharacterId(),equipment,result.currentGrade(),result.currentFailures()));
                var original=result.original();var body=new com.google.gson.JsonObject();body.addProperty("equipmentId",equipment.toString());body.addProperty("grade",original.grade());body.addProperty("failures",original.failures());body.addProperty("success",original.success());var receipt=result.receipt();
                ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,receipt.requestId(),receipt.eventId(),receipt.state(),body.toString(),original.replayed()));
            }))
            .exceptionally(error->{server.execute(()->{if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
                LOGGER.warn("Refinement request outcome for {}",session.accountId(),error);
                if(kr.dalmaru.request.RequestJournal.definitelyRejected(error))rejectOperation(player,correlation,envelope.requestId());
                else ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,envelope.requestId(),new UUID(0,0),"accepted","{}",false));
            });return null;});
    }
    private static void enhanceRequest(ServerPlayer player,MinecraftServer server,long correlation,UUID equipment,kr.dalmaru.request.RequestEnvelope envelope){
        ConnectionState state=stateFor(player.getUUID());
        if(!state.compatible||state.accountFailure!=null||state.accountSession==null||correlation<1){if(envelope!=null)rejectOperation(player,correlation,envelope.requestId());return;}
        AccountSession session=state.accountSession;
        CompletableFuture.supplyAsync(()->{
            try{return EQUIPMENT.enhance(session.accountId(),session.connectionGeneration(),correlation,equipment,envelope);}
            catch(Exception error){throw new IllegalStateException(error);}
        }).thenAccept(result->server.execute(()->{
            if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
            if(state.activeProfile!=null)refreshCombat(player,state.activeProfile);
            if(envelope==null)ServerPlayNetworking.send(player,new DalmaruPayloads.EquipmentEnhanceResponse(correlation,true,result.success(),result.resultEnhancement(),result.resultFailures(),result.replayed()?"\uc774\ubbf8 \ucc98\ub9ac\ub41c \uc694\uccad":result.success()?"\uac15\ud654 \uc131\uacf5":"\uac15\ud654 \uc2e4\ud328"));
            else{
                var original=new com.google.gson.JsonObject();original.addProperty("equipmentId",equipment.toString());original.addProperty("success",result.success());original.addProperty("enhancement",result.resultEnhancement());original.addProperty("consecutiveFailures",result.resultFailures());
                var receipt=result.receipt();ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,receipt.requestId(),receipt.eventId(),receipt.state(),original.toString(),result.replayed()));
            }
        })).exceptionally(error->{server.execute(()->{
            if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
            if(envelope!=null){
                if(kr.dalmaru.request.RequestJournal.definitelyRejected(error))rejectOperation(player,correlation,envelope.requestId());
                else{
                    LOGGER.warn("Operation result requires recovery: {}",envelope.requestId(),error);
                    ServerPlayNetworking.send(player,new DalmaruPayloads.OperationReceipt(correlation,envelope.requestId(),new UUID(0,0),"accepted","{}",false));
                }
            }
            else ServerPlayNetworking.send(player,new DalmaruPayloads.EquipmentEnhanceResponse(correlation,false,false,0,0,"\uac15\ud654\ud560 \uc218 \uc5c6\uc2b5\ub2c8\ub2e4."));
        });return null;});
    }

	private static void refreshCombat(ServerPlayer player, CharacterProfile profile) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.accountSession == null || !profile.characterId().equals(state.accountSession.activeCharacterId())) return;
		COMBAT.ranks(player.getUUID(),state.skillRanks);COMBAT.clearEquipment(player, profile);
		MinecraftServer server = serverFor(player);
		UUID playerId = player.getUUID();
		AccountSession session = state.accountSession;
		long revision = ++state.equipmentRevision;
		CompletableFuture.supplyAsync(() -> {
			try { return EQUIPMENT.equipped(profile.characterId()); }
			catch (Exception error) { throw new IllegalStateException(error); }
		}).thenAccept(records -> server.execute(() -> {
			ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerId);
			if (currentPlayer == null || CONNECTIONS.get(playerId) != state || state.accountSession != session || revision != state.equipmentRevision
				|| state.activeProfile == null || !state.activeProfile.characterId().equals(profile.characterId())) return;
			try { COMBAT.equipment(currentPlayer, state.activeProfile, records); }
			catch (IllegalArgumentException invalid) {
				LOGGER.warn("Rejected equipped combat stats for {}.", profile.characterId(), invalid);
				currentPlayer.sendSystemMessage(Component.literal("장비 전투 수치가 유효하지 않습니다."));
			}
		})).exceptionally(error -> {
			LOGGER.warn("Failed to refresh equipped combat stats for {}.", profile.characterId(), error);
			server.execute(() -> {
				ServerPlayer currentPlayer = server.getPlayerList().getPlayer(playerId);
				if (currentPlayer != null && CONNECTIONS.get(playerId) == state && state.accountSession == session && revision == state.equipmentRevision)
					currentPlayer.sendSystemMessage(Component.literal("장비 전투 수치를 불러오지 못했습니다."));
			});
			return null;
		});
	}

	private static void partyInvite(ServerPlayer player, String targetName) {
		ServerPlayer target = serverFor(player).getPlayerList().getPlayerByName(targetName);
		if (target == null) { player.sendSystemMessage(Component.literal("대상 플레이어가 접속 중이 아닙니다.")); return; }
		try { PARTIES.invite(player.getUUID(), target.getUUID()); target.sendSystemMessage(Component.literal(player.getName().getString() + "님의 파티 초대입니다. /party accept")); player.sendSystemMessage(Component.literal("파티 초대를 보냈습니다.")); } catch (IllegalArgumentException exception) { player.sendSystemMessage(Component.literal("파티 초대를 보낼 수 없습니다.")); }
	}

	private static void partyAccept(ServerPlayer player) { try { PARTIES.accept(player.getUUID()); player.sendSystemMessage(Component.literal("파티에 참가했습니다.")); } catch (IllegalArgumentException exception) { player.sendSystemMessage(Component.literal("수락할 파티 초대가 없습니다.")); } }

	private static String runEntryStatus(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null) cause = cause.getCause();
		if (!(cause instanceof IllegalArgumentException)) { LOGGER.warn("Failed to enter run.", cause); return "공략 입장 처리에 실패했습니다. 잠시 후 다시 시도하세요."; }
		return switch (cause.getMessage() == null ? "" : cause.getMessage()) {
			case "Character already has an active run." -> "이미 진행 중인 공략이 있습니다. 입장창을 닫고 공략을 계속하세요.";
			case "Pending run return requires recovery." -> "파티원의 이전 공략 복귀를 확인해야 합니다. 재접속으로 복귀를 완료한 뒤 다시 입장하세요.";
			case "Level requirement not met." -> "초행은 15레벨, 수련은 20레벨부터 입장할 수 있습니다.";
			case "Training is locked." -> "초행을 완료한 뒤 수련에 입장할 수 있습니다.";
			case "Weekly clear limit reached." -> "이번 주 완료 횟수 3회를 모두 사용했습니다.";
			default -> "공략 입장 조건을 충족하지 못했습니다.";
		};
	}

	private static java.util.List<UUID> partyCharacterIds(ServerPlayer player) {
		java.util.List<UUID> members = PARTIES.members(player.getUUID());
		if (!members.getFirst().equals(player.getUUID())) throw new IllegalArgumentException("Only the party leader can start the run.");
		java.util.List<UUID> characters = new java.util.ArrayList<>();
		for (UUID member : members) { ConnectionState state = CONNECTIONS.get(member); if (state == null || state.accountSession == null || state.accountSession.activeCharacterId() == null) throw new IllegalArgumentException("Party member is not ready."); characters.add(state.accountSession.activeCharacterId()); }
		return characters;
	}

    private static void enterPartyRun(MinecraftServer server,java.util.List<UUID> players,kr.dalmaru.run.RunRecord run,kr.dalmaru.run.RunLifecycle.Owner owner,kr.dalmaru.content.ContentCatalog content){
        if(!RUN_RUNTIME.preparing(run.runId(),owner))throw new IllegalStateException("Run entry owner is no longer preparing.");
        for(UUID id:players){
            ServerPlayer member=server.getPlayerList().getPlayer(id);ConnectionState state=CONNECTIONS.get(id);
            if(member==null||state==null||state.accountSession==null)throw new IllegalStateException("Run member disconnected before entry.");
            UUID characterId=state.accountSession.activeCharacterId();
            try{RUNS.saveReturn(run.runId(),characterId,member.level().dimension().identifier().toString(),member.getX(),member.getY(),member.getZ(),member.gameMode.getGameModeForPlayer().getName());}
            catch(Exception error){throw new IllegalStateException("Cannot save safe run return.",error);}
        }
        for(UUID id:players){ServerPlayer member=server.getPlayerList().getPlayer(id);if(member!=null)RUN_RUNTIME.enter(member,run.runId(),run.difficulty(),run.partySize(),owner,content);}
        for(UUID id:players){var member=server.getPlayerList().getPlayer(id);var state=CONNECTIONS.get(id);if(member!=null&&state!=null&&state.activeProfile!=null)COMBAT.refresh(member,state.activeProfile);}
    }
    private static void resumeRuntime(ServerPlayer player,kr.dalmaru.run.RunRecord run){
        if(!RUN_RUNTIME.joinable(run.runId()))return;
        long paused=RUN_RUNTIME.pausedTicks(run.runId());
        if(paused>0)for(UUID id:RUN_RUNTIME.participantIds(run.runId()))COMBAT.shiftCooldowns(id,paused);
        if(!RUN_RUNTIME.reconnect(player,run.runId()))throw new IllegalStateException("Run runtime is no longer joinable.");
    }
    private static void restorePendingReturn(ServerPlayer player,AccountSession session,RunRepository.ReturnPosition position){
        if(session.activeCharacterId()==null)return;
        try{
            if(position==null)return;
            if(position.mode()==null)throw new IllegalStateException("Legacy return mode requires manual recovery.");
            var level=serverFor(player).getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.Identifier.parse(position.dimension())));
            if(level==null)throw new IllegalStateException("Return dimension unavailable.");
            player.teleportTo(level,position.x(),position.y(),position.z(),java.util.Set.of(),player.getYRot(),player.getXRot(),false);
            player.setGameMode(net.minecraft.world.level.GameType.byName(position.mode()));
            serverFor(player).getPlayerList().saveAll();
            CompletableFuture.runAsync(()->{try{RUNS.acknowledgeReturn(session.accountId(),session.connectionGeneration(),session.activeCharacterId(),position.runId());}catch(Exception error){LOGGER.warn("Failed to acknowledge restored return.",error);}},LIFECYCLE_DB);
        }catch(Exception error){ConnectionState state=CONNECTIONS.get(player.getUUID());if(state!=null)state.accountFailure="공략 복귀 실패";LOGGER.error("Failed to restore run return location.",error);player.connection.disconnect(Component.literal("공략 복귀 위치를 확인하지 못했습니다. 운영자에게 문의하세요."));throw new IllegalStateException("Run return recovery failed.",error);}
    }
    private static void acknowledgeReturns(MinecraftServer server,UUID runId){
        server.getPlayerList().saveAll();
        for(ServerPlayer player:server.getPlayerList().getPlayers()){
            ConnectionState state=CONNECTIONS.get(player.getUUID());
            if(state==null||state.accountFailure!=null||state.accountSession==null||state.accountSession.activeCharacterId()==null||RUN_RUNTIME.runId(player)!=null)continue;
            AccountSession captured=state.accountSession;
            CompletableFuture.runAsync(()->{try{RUNS.acknowledgeReturn(captured.accountId(),captured.connectionGeneration(),captured.activeCharacterId(),runId);}catch(Exception error){LOGGER.warn("Failed to acknowledge run return.",error);}},LIFECYCLE_DB);
        }
    }

	private static void completeRun(ServerPlayer player, MinecraftServer server, UUID runId) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.accountSession == null) return;
		AccountSession session = state.accountSession;
        if(!FINISHING_RUNS.add(runId))return;
		CompletableFuture.supplyAsync(() -> { try { RUNS.finish(session.accountId(), session.connectionGeneration(), System.nanoTime() & Long.MAX_VALUE, runId, true); Map<UUID, CharacterProfile> profiles = new HashMap<>(); for (var reward : RUNS.rewards(runId)) profiles.put(reward.characterId(), CHARACTERS.profile(reward.characterId())); return profiles; } catch (Exception exception) { throw new IllegalStateException(exception); } }).thenAccept(profiles -> server.execute(() -> {
			FINISHING_RUNS.remove(runId);RUN_RUNTIME.close(server,runId);acknowledgeReturns(server,runId);
            for (var entry : CONNECTIONS.entrySet()) { ConnectionState memberState = entry.getValue(); if (memberState.accountSession == null) continue; CharacterProfile profile = profiles.get(memberState.accountSession.activeCharacterId()); if (profile == null) continue; ServerPlayer member = server.getPlayerList().getPlayer(entry.getKey()); if (member == null) continue; sendCharacterProfile(member, profile); member.sendSystemMessage(Component.literal("폐봉수대를 완료했습니다. 경험치 500과 공략 보상을 획득했습니다.")); }
		})).exceptionally(error -> { LOGGER.error("Failed to complete run.", error);server.execute(()->FINISHING_RUNS.remove(runId)); return null; });
	}

    public static boolean canRunRespawn(ServerPlayer player){return RUN_RUNTIME.canRespawn(player);}
	private static void failRunAfterFinalDeath(ServerPlayer player, MinecraftServer server) {
		RunRuntime.DeathResult death = RUN_RUNTIME.recordDeath(player);
		if (death.runId() == null || death.canRespawn() || !death.partyDefeated()) return;
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.accountSession == null) return;
		AccountSession session = state.accountSession;
		CompletableFuture.supplyAsync(() -> { try { return RUNS.finish(session.accountId(), session.connectionGeneration(), System.nanoTime() & Long.MAX_VALUE, death.runId(), false); } catch (Exception exception) { throw new IllegalStateException(exception); } }).thenAccept(run -> server.execute(() -> { RUN_RUNTIME.close(server,death.runId());acknowledgeReturns(server,death.runId());player.sendSystemMessage(Component.literal("부활 횟수를 모두 사용해 폐봉수대 공략에 실패했습니다.")); })).exceptionally(error -> { LOGGER.error("Failed to close run after final death.", error); return null; });
	}

	private static kr.dalmaru.combat.CombatRules.TargetKind controlKind(LivingEntity target){
        if(RUN_RUNTIME.isBoss(target.getUUID()))return kr.dalmaru.combat.CombatRules.TargetKind.BOSS;
        return target.entityTags().contains("dalmaru.kind.elite")?kr.dalmaru.combat.CombatRules.TargetKind.ELITE:kr.dalmaru.combat.CombatRules.TargetKind.NORMAL;
    }
    private static void useSweep(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.accountSession == null || state.accountSession.activeCharacterId() == null) return;
		try {
			if (state.activeProfile == null || !"WARRIOR".equals(state.activeProfile.profession())) return;
			int rank = state.skillRanks.getOrDefault("skill.warrior.sweep", 0);
			if (rank < 1) { player.sendSystemMessage(Component.literal("횡참을 먼저 습득하세요.")); return; }
			CombatRuntime.SweepResult result = COMBAT.useSweep(player.getUUID(), currentTick);
			if (result != CombatRuntime.SweepResult.USED) {
				player.sendSystemMessage(Component.literal(result == CombatRuntime.SweepResult.STAMINA ? "기력이 부족합니다." : "횡참을 아직 사용할 수 없습니다."));
				return;
			}
			float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * COMBAT.skill(player.getUUID(),"skill.warrior.sweep").parameter("damageMultiplier",rank));
			player.swing(InteractionHand.MAIN_HAND, true);
			((ServerLevel) player.level()).sendParticles(ParticleTypes.SWEEP_ATTACK, player.getX(), player.getY() + 1.0, player.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
			for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(3.0), target -> RUN_RUNTIME.mayTarget(player,target) && player.hasLineOfSight(target) && target.position().subtract(player.position()).normalize().dot(player.getLookAngle()) > 0.25)) {
				rpgSkillDamage(player, target, damage);
			}
		} catch (Exception exception) { LOGGER.warn("Failed to use warrior sweep for {}.", player.getUUID(), exception); }
	}

	private static void useAdvance(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.activeProfile == null || !"WARRIOR".equals(state.activeProfile.profession())) return;
		int rank = state.skillRanks.getOrDefault("skill.warrior.advance", 0);
		if (rank < 1) { player.sendSystemMessage(Component.literal("진격을 먼저 습득하세요.")); return; }
		CombatRuntime.SweepResult result = COMBAT.useAdvance(player.getUUID(), currentTick);
		if (result != CombatRuntime.SweepResult.USED) { player.sendSystemMessage(Component.literal(result == CombatRuntime.SweepResult.STAMINA ? "기력이 부족합니다." : "진격을 아직 사용할 수 없습니다.")); return; }
		var direction = player.getLookAngle().normalize();
		RUN_RUNTIME.move(player,COMBAT.skill(player.getUUID(),"skill.warrior.advance").parameter("movementBlocks",rank),false);
		player.hurtMarked = true;
		player.swing(InteractionHand.MAIN_HAND, true);
		((ServerLevel) player.level()).sendParticles(ParticleTypes.CRIT, player.getX(), player.getY() + 1.0, player.getZ(), 12, 0.35, 0.15, 0.35, 0.1);
		player.playSound(SoundEvents.PLAYER_ATTACK_STRONG, 1.0F, 1.15F);
		float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * COMBAT.skill(player.getUUID(),"skill.warrior.advance").parameter("damageMultiplier",rank));
		LivingEntity target = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(3.0), entity -> hostileCombatEntity(player,entity) && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(player.getLookAngle()) > 0.35).stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
		if (target != null) rpgSkillDamage(player, target, damage);
	}

	private static void useRisingStrike(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.activeProfile == null || !"WARRIOR".equals(state.activeProfile.profession())) return;
		int rank = state.skillRanks.getOrDefault("skill.warrior.rising_strike", 0);
		if (rank < 1) { player.sendSystemMessage(Component.literal("승룡격을 먼저 습득하세요.")); return; }
		CombatRuntime.SweepResult result = COMBAT.useRisingStrike(player.getUUID(), currentTick);
		if (result != CombatRuntime.SweepResult.USED) { player.sendSystemMessage(Component.literal(result == CombatRuntime.SweepResult.STAMINA ? "기력이 부족합니다." : "승룡격을 아직 사용할 수 없습니다.")); return; }
		player.swing(InteractionHand.MAIN_HAND, true);
		((ServerLevel) player.level()).sendParticles(ParticleTypes.CRIT, player.getX(), player.getY() + 1.0, player.getZ(), 20, 0.45, 0.7, 0.45, 0.15);
		player.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 1.0F, 0.9F);
		float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * COMBAT.skill(player.getUUID(),"skill.warrior.rising_strike").parameter("damageMultiplier",rank));
		for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(3.0), entity -> hostileCombatEntity(player,entity) && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(player.getLookAngle()) > 0.25)) {
			rpgSkillDamage(player, target, damage);
			target.setDeltaMovement(target.getDeltaMovement().x, COMBAT.skill(player.getUUID(),"skill.warrior.rising_strike").parameter("knockUpVelocity",rank), target.getDeltaMovement().z);
			target.hurtMarked = true;
		}
	}

	private static void useBattleCry(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.activeProfile == null || !"WARRIOR".equals(state.activeProfile.profession())) return;
		int rank = state.skillRanks.getOrDefault("skill.warrior.battle_cry", 0);
		if (rank < 1) { player.sendSystemMessage(Component.literal("기합을 먼저 습득하세요.")); return; }
		CombatRuntime.SweepResult result = COMBAT.useBattleCry(player.getUUID(), currentTick);
		if (result != CombatRuntime.SweepResult.USED) { player.sendSystemMessage(Component.literal(result == CombatRuntime.SweepResult.STAMINA ? "기력이 부족합니다." : "기합을 아직 사용할 수 없습니다.")); return; }
		boolean cleansed=COMBAT.cleanse(player,currentTick);COMBAT.battleResistance(player,rank,currentTick);if(cleansed&&COMBAT.stamina(player.getUUID(),currentTick).inCombat())COMBAT.recordValidHit(player,currentTick);
		((ServerLevel) player.level()).sendParticles(ParticleTypes.ANGRY_VILLAGER, player.getX(), player.getY() + 1.2, player.getZ(), 10, 0.5, 0.6, 0.5, 0.02);
		player.playSound(SoundEvents.RAVAGER_ROAR, 0.7F, 1.35F);
	}

	private static void usePiercingShot(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.activeProfile == null || !"ARCHER".equals(state.activeProfile.profession())) return;
		int rank = state.skillRanks.getOrDefault("skill.archer.piercing_shot", 0);
		if (rank < 1) { player.sendSystemMessage(Component.literal("관통 사격을 먼저 습득하세요.")); return; }
		CombatRuntime.SweepResult result = COMBAT.usePiercingShot(player.getUUID(), currentTick);
		if (result != CombatRuntime.SweepResult.USED) { player.sendSystemMessage(Component.literal(result == CombatRuntime.SweepResult.STAMINA ? "기력이 부족합니다." : "관통 사격을 아직 사용할 수 없습니다.")); return; }
		var direction = player.getLookAngle().normalize();
		ServerLevel level = (ServerLevel) player.level();
		for (int distance = 1; distance <= 12; distance++) level.sendParticles(ParticleTypes.CRIT, player.getX() + direction.x * distance, player.getY() + 1.4 + direction.y * distance, player.getZ() + direction.z * distance, 2, 0.03, 0.03, 0.03, 0.0);
		player.playSound(SoundEvents.ARROW_SHOOT, 1.0F, 1.0F);
		float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * COMBAT.skill(player.getUUID(),"skill.archer.piercing_shot").parameter("damageMultiplier",rank));
		for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(12.0), entity -> hostileCombatEntity(player,entity) && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(direction) > 0.96)) rpgSkillDamage(player, target, damage);
	}

	private static void useSpiritTalisman(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.activeProfile == null || !"MAGE".equals(state.activeProfile.profession())) return;
		int rank = state.skillRanks.getOrDefault("skill.mage.spirit_talisman", 0);
		if (rank < 1) { player.sendSystemMessage(Component.literal("영부탄을 먼저 습득하세요.")); return; }
		CombatRuntime.SweepResult result = COMBAT.useSpiritTalisman(player.getUUID(), currentTick);
		if (result != CombatRuntime.SweepResult.USED) { player.sendSystemMessage(Component.literal(result == CombatRuntime.SweepResult.STAMINA ? "기력이 부족합니다." : "영부탄을 아직 사용할 수 없습니다.")); return; }
		var direction = player.getLookAngle().normalize();
		ServerLevel level = (ServerLevel) player.level();
		for (int distance = 1; distance <= 10; distance++) level.sendParticles(ParticleTypes.ENCHANT, player.getX() + direction.x * distance, player.getY() + 1.4 + direction.y * distance, player.getZ() + direction.z * distance, 4, 0.08, 0.08, 0.08, 0.02);
		player.playSound(SoundEvents.EVOKER_CAST_SPELL, 0.8F, 1.4F);
		float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * COMBAT.skill(player.getUUID(),"skill.mage.spirit_talisman").parameter("damageMultiplier",rank));
		for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(10.0), entity -> hostileCombatEntity(player,entity) && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(direction) > 0.96)) rpgSkillDamage(player, target, damage);
	}

	private static void useAmbush(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.activeProfile == null || !"ROGUE".equals(state.activeProfile.profession())) return;
		int rank = state.skillRanks.getOrDefault("skill.rogue.ambush", 0);
		if (rank < 1) { player.sendSystemMessage(Component.literal("급습을 먼저 습득하세요.")); return; }
		CombatRuntime.SweepResult result = COMBAT.useAmbush(player.getUUID(), currentTick);
		if (result != CombatRuntime.SweepResult.USED) { player.sendSystemMessage(Component.literal(result == CombatRuntime.SweepResult.STAMINA ? "기력이 부족합니다." : "급습을 아직 사용할 수 없습니다.")); return; }
		var direction = player.getLookAngle().normalize();
		RUN_RUNTIME.move(player,COMBAT.skill(player.getUUID(),"skill.rogue.ambush").parameter("movementBlocks",rank),false);
		player.hurtMarked = true;
		player.swing(InteractionHand.MAIN_HAND, true);
		ServerLevel level = (ServerLevel) player.level();
		level.sendParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + 1.0, player.getZ(), 18, 0.35, 0.2, 0.35, 0.04);
		player.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 0.9F, 1.4F);
		float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * COMBAT.skill(player.getUUID(),"skill.rogue.ambush").parameter("damageMultiplier",rank));
		LivingEntity target = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(3.0), entity -> hostileCombatEntity(player,entity) && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(direction) > 0.35).stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
		if (target != null) {
			if (target.getLookAngle().normalize().dot(player.position().subtract(target.position()).normalize()) < 0) damage *= (float)(1+COMBAT.skill(player.getUUID(),"skill.rogue.ambush").parameter("flankBonus",rank));
			rpgSkillDamage(player, target, damage);
		}
	}

	private static void useRetreatShot(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.activeProfile == null || !"ARCHER".equals(state.activeProfile.profession())) return;
		int rank = state.skillRanks.getOrDefault("skill.archer.retreat_shot", 0);
		if (rank < 1) { player.sendSystemMessage(Component.literal("후퇴 사격을 먼저 습득하세요.")); return; }
		CombatRuntime.SweepResult result = COMBAT.useRetreatShot(player.getUUID(), currentTick);
		if (result != CombatRuntime.SweepResult.USED) { player.sendSystemMessage(Component.literal(result == CombatRuntime.SweepResult.STAMINA ? "기력이 부족합니다." : "후퇴 사격을 아직 사용할 수 없습니다.")); return; }
		var direction = player.getLookAngle().normalize();
		RUN_RUNTIME.move(player,COMBAT.skill(player.getUUID(),"skill.archer.retreat_shot").parameter("movementBlocks",rank),true);
		player.hurtMarked = true;
		ServerLevel level = (ServerLevel) player.level();
		level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 1.0, player.getZ(), 10, 0.3, 0.1, 0.3, 0.05);
		player.playSound(SoundEvents.ARROW_SHOOT, 0.9F, 1.25F);
		float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * COMBAT.skill(player.getUUID(),"skill.archer.retreat_shot").parameter("damageMultiplier",rank));
		LivingEntity target = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(10.0), entity -> hostileCombatEntity(player,entity) && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(direction) > 0.96).stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
		if (target != null) rpgSkillDamage(player, target, damage);
	}

	private static void useBindingTalisman(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.activeProfile == null || !"MAGE".equals(state.activeProfile.profession())) return;
		int rank = state.skillRanks.getOrDefault("skill.mage.binding_talisman", 0);
		if (rank < 1) { player.sendSystemMessage(Component.literal("속박부를 먼저 습득하세요.")); return; }
		CombatRuntime.SweepResult result = COMBAT.useBindingTalisman(player.getUUID(), currentTick);
		if (result != CombatRuntime.SweepResult.USED) { player.sendSystemMessage(Component.literal(result == CombatRuntime.SweepResult.STAMINA ? "기력이 부족합니다." : "속박부를 아직 사용할 수 없습니다.")); return; }
		var direction = player.getLookAngle().normalize();
		ServerLevel level = (ServerLevel) player.level();
		float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * COMBAT.skill(player.getUUID(),"skill.mage.binding_talisman").parameter("damageMultiplier",rank));
		LivingEntity target = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(10.0), entity -> hostileCombatEntity(player,entity) && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(direction) > 0.96).stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
		if (target != null) {
			rpgSkillDamage(player, target, damage);
			COMBAT.control(player,target,controlKind(target),true,rank,currentTick);
			level.sendParticles(ParticleTypes.ENCHANT, target.getX(), target.getY() + 1.0, target.getZ(), 24, 0.4, 0.7, 0.4, 0.04);
		}
		player.playSound(SoundEvents.EVOKER_CAST_SPELL, 0.9F, 0.75F);
	}

	private static void useSmokeBomb(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.activeProfile == null || !"ROGUE".equals(state.activeProfile.profession())) return;
		int rank = state.skillRanks.getOrDefault("skill.rogue.smoke_bomb", 0);
		if (rank < 1) { player.sendSystemMessage(Component.literal("연막탄을 먼저 습득하세요.")); return; }
		CombatRuntime.SweepResult result = COMBAT.useSmokeBomb(player.getUUID(), currentTick);
		if (result != CombatRuntime.SweepResult.USED) { player.sendSystemMessage(Component.literal(result == CombatRuntime.SweepResult.STAMINA ? "기력이 부족합니다." : "연막탄을 아직 사용할 수 없습니다.")); return; }
		ServerLevel level = (ServerLevel) player.level();
		level.sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 1.0, player.getZ(), 45, 1.2, 0.7, 1.2, 0.05);
		player.playSound(SoundEvents.FIRE_EXTINGUISH, 1.0F, 0.7F);
		for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(5.0), entity -> hostileCombatEntity(player,entity))) target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, (int)Math.round(COMBAT.skill(player.getUUID(),"skill.rogue.smoke_bomb").parameter("durationTicks",rank)), 0, false, true, true));
	}

	private static void useShackleStrike(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.activeProfile == null || !"ROGUE".equals(state.activeProfile.profession())) return;
		int rank = state.skillRanks.getOrDefault("skill.rogue.shackle_strike", 0);
		if (rank < 1) { player.sendSystemMessage(Component.literal("족쇄격을 먼저 습득하세요.")); return; }
		CombatRuntime.SweepResult result = COMBAT.useShackleStrike(player.getUUID(), currentTick);
		if (result != CombatRuntime.SweepResult.USED) { player.sendSystemMessage(Component.literal(result == CombatRuntime.SweepResult.STAMINA ? "기력이 부족합니다." : "족쇄격을 아직 사용할 수 없습니다.")); return; }
		var direction = player.getLookAngle().normalize();
		ServerLevel level = (ServerLevel) player.level();
		float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * COMBAT.skill(player.getUUID(),"skill.rogue.shackle_strike").parameter("damageMultiplier",rank));
		LivingEntity target = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(3.0), entity -> hostileCombatEntity(player,entity) && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(direction) > 0.35).stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
		if (target != null) { rpgSkillDamage(player, target, damage); target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, (int)Math.round(COMBAT.skill(player.getUUID(),"skill.rogue.shackle_strike").parameter("durationTicks",rank)), 2, false, true, true)); level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 1.0, target.getZ(), 16, 0.25, 0.5, 0.25, 0.04); }
		player.playSound(SoundEvents.CHAIN_HIT, 0.9F, 0.9F);
	}

	private static void useEscape(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.activeProfile == null || !"ROGUE".equals(state.activeProfile.profession())) return;
		if (state.skillRanks.getOrDefault("skill.rogue.escape", 0) < 1) { player.sendSystemMessage(Component.literal("긴급 이탈을 먼저 습득하세요.")); return; }
		CombatRuntime.SweepResult result = COMBAT.useEscape(player.getUUID(), currentTick);
		if (result != CombatRuntime.SweepResult.USED) { player.sendSystemMessage(Component.literal(result == CombatRuntime.SweepResult.STAMINA ? "기력이 부족합니다." : "긴급 이탈을 아직 사용할 수 없습니다.")); return; }
		var direction = player.getLookAngle().normalize();
        int rank=state.skillRanks.get("skill.rogue.escape");
        RUN_RUNTIME.move(player,COMBAT.skill(player.getUUID(),"skill.rogue.escape").parameter("movementBlocks",rank),true);
		player.hurtMarked = true;
		player.removeEffect(MobEffects.SLOWNESS);
		((ServerLevel) player.level()).sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 1.0, player.getZ(), 18, 0.45, 0.15, 0.45, 0.05);
		player.playSound(SoundEvents.ENDERMAN_TELEPORT, 0.7F, 1.6F);
	}

	private static void useTrackingMark(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.activeProfile == null || !"ARCHER".equals(state.activeProfile.profession())) return;
		int rank = state.skillRanks.getOrDefault("skill.archer.tracking_mark", 0);
		if (rank < 1) { player.sendSystemMessage(Component.literal("추적 표식을 먼저 습득하세요.")); return; }
		CombatRuntime.SweepResult result = COMBAT.useTrackingMark(player.getUUID(), currentTick);
		if (result != CombatRuntime.SweepResult.USED) { player.sendSystemMessage(Component.literal(result == CombatRuntime.SweepResult.STAMINA ? "기력이 부족합니다." : "추적 표식을 아직 사용할 수 없습니다.")); return; }
		LivingEntity target = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(12.0), entity -> hostileCombatEntity(player,entity) && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(player.getLookAngle()) > 0.96).stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
        if (target != null) { rpgSkillDamage(player,target,(float)(player.getAttributeValue(Attributes.ATTACK_DAMAGE)*COMBAT.skill(player.getUUID(),"skill.archer.tracking_mark").parameter("damageMultiplier",rank)));COMBAT.effects().mark(player.getUUID(),target.getUUID(),rank,currentTick,COMBAT.skill(player.getUUID(),"skill.archer.tracking_mark"));COMBAT.recordValidHit(player,currentTick);target.addEffect(new MobEffectInstance(MobEffects.GLOWING,(int)Math.round(COMBAT.skill(player.getUUID(),"skill.archer.tracking_mark").parameter("markTicks",rank)),0,false,true,true)); ((ServerLevel) player.level()).sendParticles(ParticleTypes.GLOW, target.getX(), target.getY() + 1.0, target.getZ(), 16, 0.3, 0.5, 0.3, 0.02); player.playSound(SoundEvents.ARROW_HIT_PLAYER, 0.8F, 1.2F); }
	}

	private static void useBindingShot(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.activeProfile == null || !"ARCHER".equals(state.activeProfile.profession()) || state.skillRanks.getOrDefault("skill.archer.binding_shot", 0) < 1) return;
		if (COMBAT.useBindingShot(player.getUUID(), currentTick) != CombatRuntime.SweepResult.USED) return;
		LivingEntity target = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(12.0), entity -> hostileCombatEntity(player,entity) && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(player.getLookAngle()) > 0.96).stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
		if (target != null) { int rank=state.skillRanks.get("skill.archer.binding_shot"); rpgSkillDamage(player, target, (float)(player.getAttributeValue(Attributes.ATTACK_DAMAGE)*COMBAT.skill(player.getUUID(),"skill.archer.binding_shot").parameter("damageMultiplier",rank))); COMBAT.control(player,target,controlKind(target),false,rank,currentTick); ((ServerLevel)player.level()).sendParticles(ParticleTypes.CRIT,target.getX(),target.getY()+1,target.getZ(),12,.2,.4,.2,.02); }
		player.playSound(SoundEvents.ARROW_HIT, .9F, .8F);
	}
	private static void useProtectiveTalisman(ServerPlayer player) {
		ConnectionState state=CONNECTIONS.get(player.getUUID()); if(state==null||state.activeProfile==null||!"MAGE".equals(state.activeProfile.profession()))return; int rank=state.skillRanks.getOrDefault("skill.mage.protective_talisman",0); if(rank<1||COMBAT.useProtectiveTalisman(player.getUUID(),currentTick)!=CombatRuntime.SweepResult.USED)return;
		COMBAT.protectiveTalisman(player,rank,currentTick); ((ServerLevel)player.level()).sendParticles(ParticleTypes.ENCHANT,player.getX(),player.getY()+1,player.getZ(),30,.5,.8,.5,.05); player.playSound(SoundEvents.EVOKER_CAST_SPELL,.9F,.6F);
	}
	private static void useShortStep(ServerPlayer player) {
		ConnectionState state=CONNECTIONS.get(player.getUUID()); if(state==null||state.activeProfile==null||!"MAGE".equals(state.activeProfile.profession())||state.skillRanks.getOrDefault("skill.mage.short_step",0)<1||COMBAT.useShortStep(player.getUUID(),currentTick)!=CombatRuntime.SweepResult.USED)return;
		int rank=state.skillRanks.get("skill.mage.short_step"); var d=player.getLookAngle().normalize(); RUN_RUNTIME.move(player,COMBAT.skill(player.getUUID(),"skill.mage.short_step").parameter("movementBlocks",rank),false); player.hurtMarked=true; ((ServerLevel)player.level()).sendParticles(ParticleTypes.PORTAL,player.getX(),player.getY()+1,player.getZ(),24,.4,.5,.4,.08); player.playSound(SoundEvents.ENDERMAN_TELEPORT,.8F,1.3F);
	}

    private static void sendRunReviveState(ServerPlayer player){
        ConnectionState connection=CONNECTIONS.get(player.getUUID());if(connection==null||!connection.compatible||connection.accountFailure!=null)return;
        var status=RUN_RUNTIME.reviveStatus(player);
        ServerPlayNetworking.send(player,status==null?new DalmaruPayloads.RunReviveState(false,0,0,0,false):new DalmaruPayloads.RunReviveState(true,status.remainingRevives(),status.clickTicks(),status.autoTicks(),status.failed()));
    }
	private static void sendCombatStates(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ConnectionState state=CONNECTIONS.get(player.getUUID());if(state==null||!state.compatible||state.accountFailure!=null)continue;
			CombatRuntime.StaminaState stamina = COMBAT.stamina(player.getUUID(), currentTick);
            int remaining=kr.dalmaru.combat.CombatRules.remainingModeTicks(RUN_RUNTIME.runId(player)==null&&!town(player),COMBAT.remainingCombatTicks(player.getUUID(),currentTick),state.manualUntil,currentTick);
			if (stamina != null) ServerPlayNetworking.send(player, new DalmaruPayloads.CombatStateResponse((int)Math.round(stamina.current()),(int)Math.round(stamina.maximum()),stamina.inCombat(),combatMode(player,state),remaining,state.loadout==null?java.util.List.of(0,0,0,0,0):state.loadout.slots().stream().map(id->COMBAT.cooldown(player.getUUID(),id,currentTick)).toList(),state.loadout==null?java.util.List.of("","","","",""):state.loadout.slots(),COMBAT.defenseState(player.getUUID()).charges(currentTick),(int)Math.round(COMBAT.defenseState(player.getUUID()).meter(currentTick))));
		}
	}

	private static void sendCharacterList(ServerPlayNetworking.Context context, long requestId, String status, java.util.List<DalmaruPayloads.CharacterSummary> characters) {
		ServerPlayNetworking.send(context.player(), new DalmaruPayloads.CharacterListResponse(requestId, status, characters));
	}

	private static void sendCharacterSelectionReady(ServerPlayer player, ConnectionState state) {
		if (!state.compatible || state.accountSession == null || state.selectionReadySent) return;
		state.selectionReadySent = true;
		ServerPlayNetworking.send(player, new DalmaruPayloads.CharacterSelectionReady(true));
	}

	private static void sendCharacterSelect(ServerPlayNetworking.Context context, long requestId, UUID characterId, boolean accepted, boolean replayed, String status) {
		ServerPlayNetworking.send(context.player(), new DalmaruPayloads.CharacterSelectResponse(requestId, characterId, accepted, replayed, status));
	}

	private static void sendCharacterCreate(ServerPlayNetworking.Context context, long requestId, UUID characterId, int slot, String displayName, boolean accepted, boolean replayed, String status) {
		ServerPlayNetworking.send(context.player(), new DalmaruPayloads.CharacterCreateResponse(requestId, characterId, slot, displayName, accepted, replayed, status));
	}

    private static boolean closeUncertainSelection(ServerPlayer player,ConnectionState state,boolean installed,boolean committed,Exception error) {
        if(committed||!(error instanceof java.sql.SQLException))return false;
        LOGGER.warn("Character selection database outcome requires reconnect recovery.",error);
        state.accountFailure="캐릭터 저장 결과를 확인할 수 없습니다. 재접속해 복구하세요.";state.compatible=false;
        // Neither owned inventory can remain transferable while the database outcome is unknown.
        try{if(installed)kr.dalmaru.account.VanillaCharacterState.quarantine(player);}
        finally{player.connection.disconnect(Component.literal(state.accountFailure));}
        return true;
    }

    private static void installSkillRanks(ConnectionState state,kr.dalmaru.skill.SkillRepository.LearningResult result){
        // shortcut: selected-character pilot ranks only increase; use revisions when rank reset/revocation is added.
        result.currentRanks().forEach((id,rank)->state.skillRanks.merge(id,rank,Math::max));
    }

    private static CharacterProfile applyProfile(ServerPlayer player, CharacterProfile candidate) {
        ConnectionState state = stateFor(player.getUUID());
        if (state.accountSession == null || !candidate.characterId().equals(state.accountSession.activeCharacterId())) throw new IllegalStateException("Profile belongs to another active character.");
        if (candidate.supersedes(state.activeProfile)) state.activeProfile = candidate;
        return state.activeProfile;
    }

	private static void sendCharacterProfile(ServerPlayer player, CharacterProfile profile) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		profile = applyProfile(player, profile);
		refreshCombat(player, profile);
		ServerPlayNetworking.send(player, new DalmaruPayloads.CharacterProfileResponse(profile.characterId(), profileData(profile)));
        if(state!=null&&state.accountSession!=null)ServerPlayNetworking.send(player,new DalmaruPayloads.RequestContext(state.accountSession.connectionGeneration(),profile.characterId()));
	}

	private static void grantExperience(ServerPlayer player, MinecraftServer server, int amount) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.accountSession == null || state.accountSession.activeCharacterId() == null) {
			player.sendSystemMessage(Component.literal("먼저 달마루 캐릭터를 선택하세요."));
			return;
		}
		AccountSession session = state.accountSession;
		CompletableFuture.supplyAsync(() -> {
			try { return CHARACTERS.grantExperience(session.activeCharacterId(), amount); }
			catch (Exception exception) { throw new IllegalStateException(exception); }
		}).thenAccept(profile -> server.execute(() -> {
			if (CONNECTIONS.get(player.getUUID()) == state && state.accountSession == session) {
				sendCharacterProfile(player, profile);
				player.sendSystemMessage(Component.literal("경험치 " + amount + "을(를) 지급했습니다. 레벨 " + profile.level() + ", 미배분점 " + profile.unspentStatPoints()));
			}
		})).exceptionally(error -> {
			LOGGER.error("Failed to grant Dalmaru experience to {}.", player.getUUID(), error);
			server.execute(() -> player.sendSystemMessage(Component.literal("경험치를 지급하지 못했습니다.")));
			return null;
		});
	}

	private static void grantCombatExperience(ServerPlayer player, MinecraftServer server, int amount) {
		if (amount < 1) return;
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.accountSession == null || state.accountSession.activeCharacterId() == null) return;
		AccountSession session = state.accountSession;
		CompletableFuture.supplyAsync(() -> {
			try { return CHARACTERS.grantExperience(session.activeCharacterId(), amount); }
			catch (Exception exception) { throw new IllegalStateException(exception); }
		}).thenAccept(profile -> server.execute(() -> {
			if (CONNECTIONS.get(player.getUUID()) == state && state.accountSession == session) {
				sendCharacterProfile(player, profile);
				player.sendSystemMessage(Component.literal("처치 경험치 +" + amount + " (레벨 " + profile.level() + ")"));
			}
		})).exceptionally(error -> {
			LOGGER.error("Failed to grant Dalmaru combat experience to {}.", player.getUUID(), error);
			return null;
		});
	}

	private static AccountSession activeSession(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		return state == null ? null : state.accountSession;
	}

	private static void acceptQuest(ServerPlayer player, MinecraftServer server) {
		AccountSession session = activeSession(player);
		if (session == null || session.activeCharacterId() == null) { player.sendSystemMessage(Component.literal("먼저 달마루 캐릭터를 선택하세요.")); return; }
        long correlation=Math.max(1,System.nanoTime()&Long.MAX_VALUE);
		CompletableFuture.supplyAsync(() -> { try { return QUESTS.acceptJournaled(session.accountId(),session.connectionGeneration(),correlation,session.activeCharacterId(),null).currentStatus(); } catch (Exception exception) { throw new IllegalStateException(exception); } })
			.thenAccept(message -> server.execute(() -> {var current=CONNECTIONS.get(player.getUUID());if(current!=null&&current.accountSession==session)player.sendSystemMessage(Component.literal(message));}))
            .exceptionally(error->{LOGGER.warn("Quest command failed for {}",session.accountId(),error);return null;});
	}

	private static void showQuestStatus(ServerPlayer player, MinecraftServer server) {
		AccountSession session = activeSession(player);
		if (session == null || session.activeCharacterId() == null) return;
		CompletableFuture.supplyAsync(() -> { try { return QUESTS.status(session.activeCharacterId()); } catch (Exception exception) { throw new IllegalStateException(exception); } })
			.thenAccept(message -> server.execute(() -> player.sendSystemMessage(Component.literal(message))));
	}

	private static void recordQuestZombieKill(ServerPlayer player, MinecraftServer server, UUID killedEntity) {
		AccountSession session = activeSession(player);
		if (session == null || session.activeCharacterId() == null) return;
		CompletableFuture.supplyAsync(() -> { try { return QUESTS.recordZombieKillJournaled(session.accountId(),session.connectionGeneration(),session.activeCharacterId(),killedEntity); } catch (Exception exception) { throw new IllegalStateException(exception); } })
			.thenAccept(reward -> { if (reward != null) server.execute(() -> {
                    var current=CONNECTIONS.get(player.getUUID());if(current==null||current.accountSession!=session)return; player.sendSystemMessage(Component.literal(reward.message())); if (reward.profile() != null) sendCharacterProfile(player, reward.profile()); }); })
            .exceptionally(error->{LOGGER.error("Failed to record quest kill {} for {}.",killedEntity,session.accountId(),error);return null;});
	}

	private static void selectProfession(ServerPlayer player, MinecraftServer server, String value) {
		AccountSession session = activeSession(player);
		if (session == null || session.activeCharacterId() == null) { player.sendSystemMessage(Component.literal("먼저 달마루 캐릭터를 선택하세요.")); return; }
		CharacterProfession profession;
		try { profession = CharacterProfession.valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
		catch (IllegalArgumentException exception) { player.sendSystemMessage(Component.literal("직업: WARRIOR, ARCHER, MAGE, ROGUE")); return; }
		long requestId = System.nanoTime() & Long.MAX_VALUE;
		CompletableFuture.supplyAsync(() -> { try { return CHARACTERS.selectProfessionJournaled(session.accountId(), session.connectionGeneration(), requestId, session.activeCharacterId(), profession,null); } catch (Exception exception) { throw new IllegalStateException(exception); } })
			.thenAccept(saved -> server.execute(() -> {var current=CONNECTIONS.get(player.getUUID());if(current==null||current.accountSession!=session)return;sendCharacterProfile(player,saved.profile());player.sendSystemMessage(Component.literal(saved.profession().koreanName()+" 전직을 완료했습니다. 주력 능력치: "+saved.profession().primaryStat()));}))
			.exceptionally(error -> { server.execute(() -> player.sendSystemMessage(Component.literal("전직 조건을 충족하지 못했습니다. 레벨 10과 전직 의뢰 완료가 필요합니다."))); return null; });
	}

	private static void sendCharacterStatInvestment(ServerPlayNetworking.Context context, long requestId, UUID characterId, boolean accepted, boolean replayed, String status, CharacterProfile profile) {
		if (accepted && profile != null) profile = applyProfile(context.player(), profile);
		if (accepted && profile != null) refreshCombat(context.player(), profile);
		DalmaruPayloads.CharacterProfileData data = profile == null ? new DalmaruPayloads.CharacterProfileData(0, 0, 0, 0, 0, 0, 0, 0, false) : profileData(profile);
		ServerPlayNetworking.send(context.player(), new DalmaruPayloads.CharacterStatInvestmentResponse(requestId, characterId, accepted, replayed, status, data));
	}

	private static void sendCharacterStatReset(ServerPlayNetworking.Context context, long requestId, UUID characterId, boolean accepted, boolean replayed, String status, CharacterProfile profile) {
		if (accepted && profile != null) profile = applyProfile(context.player(), profile);
		if (accepted && profile != null) refreshCombat(context.player(), profile);
		DalmaruPayloads.CharacterProfileData data = profile == null ? new DalmaruPayloads.CharacterProfileData(0, 0, 0, 0, 0, 0, 0, 0, false) : profileData(profile);
		ServerPlayNetworking.send(context.player(), new DalmaruPayloads.CharacterStatResetResponse(requestId, characterId, accepted, replayed, status, data));
	}

	private static void sendCharacterProfession(ServerPlayNetworking.Context context, long requestId, boolean accepted, String status, CharacterProfile profile) {
		if (accepted && profile != null) profile = applyProfile(context.player(), profile);
		if (accepted && profile != null) refreshCombat(context.player(), profile);
		DalmaruPayloads.CharacterProfileData data = profile == null ? new DalmaruPayloads.CharacterProfileData(0, 0, 0, 0, 0, 0, 0, 0, false) : profileData(profile);
		ServerPlayNetworking.send(context.player(), new DalmaruPayloads.CharacterProfessionResponse(requestId, accepted, status, data));
	}

	private static void sendSkillLearn(ServerPlayNetworking.Context context, long requestId, boolean accepted, String status, CharacterProfile profile) {
		if (accepted && profile != null) profile = applyProfile(context.player(), profile);
		if (accepted && profile != null) refreshCombat(context.player(), profile);
		DalmaruPayloads.CharacterProfileData data = profile == null ? new DalmaruPayloads.CharacterProfileData(0, 0, 0, 0, 0, 0, 0, 0, false) : profileData(profile);
		ServerPlayNetworking.send(context.player(), new DalmaruPayloads.SkillLearnResponse(requestId, accepted, status, data));
	}

	private static DalmaruPayloads.CharacterProfileData profileData(CharacterProfile profile) {
		return new DalmaruPayloads.CharacterProfileData(profile.level(), profile.experience(), profile.unspentStatPoints(), profile.strength(), profile.dexterity(), profile.arcana(), profile.vitality(), profile.spirit(), profile.freeResetAvailable(), profile.unspentSkillPoints(), profile.profession());
	}

	private static ConnectionState stateFor(UUID playerId) {
		return CONNECTIONS.computeIfAbsent(playerId, ignored -> new ConnectionState(currentTick));
	}

	private static void expireMissingHello(MinecraftServer server) {
		currentTick++;
		CONNECTIONS.entrySet().removeIf(entry -> {
			ConnectionState state = entry.getValue();
			if (state.compatible || currentTick - state.joinedTick < HELLO_TIMEOUT_TICKS) {
				return false;
			}
			var player = server.getPlayerList().getPlayer(entry.getKey());
			if (player != null) {
				player.connection.disconnect(Component.literal("달마루 호환성 확인 시간이 초과되었습니다."));
			}
			return true;
		});
	}

	private static void disconnect(ServerPlayNetworking.Context context, String message) {
		context.player().connection.disconnect(Component.literal(message));
	}

	private static final class ConnectionState {
		private final long joinedTick;
		private boolean compatible;
		private AccountSession accountSession;
		private volatile AccountSession openedSession;
        private net.minecraft.server.network.ServerGamePacketListenerImpl connection;
		private CharacterProfile activeProfile;
		private long equipmentRevision;
		private Map<String, Integer> skillRanks = new HashMap<>();
        private kr.dalmaru.account.SafeCheckpoint checkpoint;private UUID checkpointCharacter;private String checkpointKey;private kr.dalmaru.skill.SkillLoadout loadout;private boolean manualCombat,startPending,startInvalidated;private long manualUntil,lastCastRequest,loadoutEpoch;private int loadoutWrites;
		private String accountFailure;
		private boolean selectionReadySent;
		private long lastProbeNanos = System.nanoTime() - RATE_LIMIT_NANOS;
		private long lastLimitedNanos = System.nanoTime() - RATE_LIMIT_NANOS;
		private long lastCharacterListNanos = System.nanoTime() - RATE_LIMIT_NANOS;

		private ConnectionState(long joinedTick) {
			this.joinedTick = joinedTick;
		}
	}

	private record SelectedCharacter(CharacterSelectionResult selection, CharacterProfile profile, Map<String, Integer> skillRanks) { }
    private static final kr.dalmaru.account.SafetyRepository SAFETY=new kr.dalmaru.account.SafetyRepository();
    private record LoadedSlots(kr.dalmaru.skill.SkillLoadout slots,kr.dalmaru.account.SafeCheckpoint checkpoint){}
    private static final kr.dalmaru.skill.LoadoutRepository LOADOUTS=new kr.dalmaru.skill.LoadoutRepository();
    private static volatile java.util.List<kr.dalmaru.account.TownZone> TOWNS=java.util.List.of();
    private static final Map<UUID,PreparedRun> PREPARATIONS=new java.util.HashMap<>();
    private record StartedRun(kr.dalmaru.run.RunRecord run,kr.dalmaru.content.ContentCatalog content,java.util.Map<UUID,kr.dalmaru.skill.SkillLoadout> loadouts,kr.dalmaru.run.RunLifecycle.Owner owner){}
    private static final class EntryFailure extends RuntimeException{final UUID runId;final kr.dalmaru.run.RunLifecycle.Owner owner;EntryFailure(UUID runId,kr.dalmaru.run.RunLifecycle.Owner owner,Throwable cause){super(cause);this.runId=runId;this.owner=owner;}}
    private static final class PreparedRun {
        final UUID preparationId=UUID.randomUUID();final DalmaruPayloads.RunEnterRequest payload;final ServerPlayNetworking.Context context;final java.util.List<UUID> players;final java.util.List<AccountSession> sessions;final java.util.Set<UUID> ready=new java.util.HashSet<>();
        PreparedRun(DalmaruPayloads.RunEnterRequest payload,ServerPlayNetworking.Context context,java.util.List<UUID> players){this.payload=payload;this.context=context;this.players=java.util.List.copyOf(players);this.sessions=players.stream().map(id->CONNECTIONS.get(id).accountSession).toList();}
    }
    private static boolean town(ServerPlayer player){var pos=player.blockPosition();return TOWNS.stream().anyMatch(zone->zone.contains(player.level().dimension().identifier().toString(),pos.getX(),pos.getY(),pos.getZ()));}
    private static boolean combatMode(ServerPlayer player,ConnectionState state){return RUN_RUNTIME.runId(player)!=null||(town(player)?state.manualCombat:COMBAT.remainingCombatTicks(player.getUUID(),currentTick)>0||state.manualUntil>currentTick);}
    private static void toggleMode(ServerPlayer player,boolean enabled){var state=CONNECTIONS.get(player.getUUID());if(state==null||!state.compatible||RUN_RUNTIME.runId(player)!=null||!player.isAlive()||player.isSpectator())return;if(!town(player)&&!enabled&&COMBAT.remainingCombatTicks(player.getUUID(),currentTick)>0)return;state.manualCombat=enabled;state.manualUntil=enabled?currentTick+200:0;}
    private static boolean cachedForActive(ConnectionState state){return state!=null&&state.accountSession!=null&&state.accountSession.activeCharacterId()!=null&&state.loadout!=null&&state.accountSession.activeCharacterId().equals(state.loadout.characterId());}
    private static kr.dalmaru.account.SafeCheckpoint activeCheckpoint(ConnectionState state){return state.accountSession!=null&&state.accountSession.activeCharacterId()!=null&&state.accountSession.activeCharacterId().equals(state.checkpointCharacter)?state.checkpoint:null;}
    private static void resetCharacterCaches(ServerPlayer player,ConnectionState state){
        cancelPreparation(player.getUUID(),"캐릭터 전환으로 준비가 취소되었습니다.");DODGES.remove(player.getUUID());
        state.loadout=null;state.checkpoint=null;state.checkpointCharacter=null;state.checkpointKey=null;state.loadoutWrites=0;state.loadoutEpoch++;state.manualCombat=false;state.manualUntil=0;state.activeProfile=null;
    }
    private static void loadout(ServerPlayer player,long request){
        var state=CONNECTIONS.get(player.getUUID());if(state==null||!state.compatible||state.accountSession==null||state.accountFailure!=null)return;var session=state.accountSession;if(session.activeCharacterId()==null)return;long epoch=++state.loadoutEpoch;
        CompletableFuture.supplyAsync(()->{try{TOWNS=CHARACTERS.towns();var active=RUNS.active(session.activeCharacterId());return new LoadedSlots(active==null?LOADOUTS.load(session.activeCharacterId()):RUNS.frozenLoadout(active.runId(),session.activeCharacterId()),SAFETY.load(session.activeCharacterId()));}catch(Exception error){throw new IllegalStateException(error);}},LIFECYCLE_DB)
            .thenAccept(result->serverFor(player).execute(()->{var current=serverFor(player).getPlayerList().getPlayer(player.getUUID());if(current==null||CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session||state.loadoutEpoch!=epoch||state.startPending||!session.activeCharacterId().equals(result.slots().characterId()))return;state.loadout=result.slots();state.checkpoint=result.checkpoint();state.checkpointCharacter=session.activeCharacterId();sendLoadout(current,state,request,"");}))
            .exceptionally(error->{LOGGER.warn("Failed to load skill slots.",error);return null;});
    }
    private static void sendLoadout(ServerPlayer player,ConnectionState state,long request,String status){if(cachedForActive(state))ServerPlayNetworking.send(player,new DalmaruPayloads.LoadoutResponse(request,state.loadout.characterId(),state.loadout.revision(),state.loadout.slots(),!state.startPending&&RUN_RUNTIME.runId(player)==null&&(town(player)||PREPARATIONS.containsKey(player.getUUID())),status));}
    private static void updateLoadout(ServerPlayer player,DalmaruPayloads.LoadoutUpdate payload){updateLoadout(player,payload,null);}
    private static void updateLoadout(ServerPlayer player,DalmaruPayloads.LoadoutUpdate payload,kr.dalmaru.request.RequestEnvelope envelope){
        var state=CONNECTIONS.get(player.getUUID());
        if(state==null||!state.compatible||state.accountFailure!=null||state.accountSession==null||payload.requestId()<1||!payload.characterId().equals(state.accountSession.activeCharacterId())){if(envelope!=null)rejectOperation(player,payload.requestId(),envelope.requestId());return;}
        var session=state.accountSession;boolean editable=!state.startPending&&RUN_RUNTIME.runId(player)==null&&(town(player)||PREPARATIONS.containsKey(player.getUUID()));
        if(editable){state.loadoutWrites++;++state.loadoutEpoch;}
        CompletableFuture.supplyAsync(()->{try{return LOADOUTS.updateJournaled(session.accountId(),session.connectionGeneration(),session.activeCharacterId(),payload.requestId(),payload.revision(),payload.slots(),envelope,editable);}catch(Exception error){throw new IllegalStateException(error);}},LIFECYCLE_DB)
            .thenAccept(result->serverFor(player).execute(()->{
                if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
                if(editable)state.loadoutWrites--;var current=serverFor(player).getPlayerList().getPlayer(player.getUUID());if(current==null)return;
                if(!state.startPending&&(state.loadout==null||result.current().revision()>=state.loadout.revision()))state.loadout=result.current();
                if(editable&&!result.replayed()){var preparation=PREPARATIONS.get(player.getUUID());if(preparation!=null){preparation.ready.clear();notifyPreparation(preparation,"장착 변경으로 전원 준비가 해제되었습니다.");}}
                sendLoadout(current,state,payload.requestId(),"기술 장착을 저장했습니다.");
                if(envelope!=null){var original=new com.google.gson.JsonObject();original.addProperty("revision",result.original().revision());original.add("slots",com.google.gson.JsonParser.parseString(kr.dalmaru.skill.LoadoutRepository.encode(result.original().slots())));var receipt=result.receipt();
                    ServerPlayNetworking.send(current,new DalmaruPayloads.OperationReceipt(payload.requestId(),receipt.requestId(),receipt.eventId(),receipt.state(),original.toString(),result.replayed()));}
            }))
            .exceptionally(error->{LOGGER.warn("Skill loadout outcome.",error);serverFor(player).execute(()->{
                if(CONNECTIONS.get(player.getUUID())!=state||state.accountSession!=session)return;
                if(editable)state.loadoutWrites--;var current=serverFor(player).getPlayerList().getPlayer(player.getUUID());if(current==null)return;
                if(envelope==null)sendLoadout(current,state,payload.requestId(),"장착 요청이 거부되었습니다. 목록을 다시 확인하세요.");
                else if(kr.dalmaru.request.RequestJournal.definitelyRejected(error))rejectOperation(current,payload.requestId(),envelope.requestId());
                else ServerPlayNetworking.send(current,new DalmaruPayloads.OperationReceipt(payload.requestId(),envelope.requestId(),new UUID(0,0),"accepted","{}",false));
            });return null;});
    }
    private static void castSlot(ServerPlayer player,DalmaruPayloads.SkillUseRequest payload){
        var state=CONNECTIONS.get(player.getUUID());if(state==null||!state.compatible||state.accountFailure!=null||state.accountSession==null||!cachedForActive(state)||!payload.characterId().equals(state.accountSession.activeCharacterId())||payload.revision()!=state.loadout.revision()||payload.requestId()<=state.lastCastRequest||payload.slot()<1||payload.slot()>5||!combatMode(player,state)||state.startPending||!player.isAlive()||player.isSpectator())return;
        state.lastCastRequest=payload.requestId();String skill=state.loadout.skill(payload.slot());if(skill.isEmpty())return;
        switch(skill){
            case "skill.warrior.sweep"->useSweep(player);case "skill.warrior.advance"->useAdvance(player);case "skill.warrior.rising_strike"->useRisingStrike(player);case "skill.warrior.battle_cry"->useBattleCry(player);
            case "skill.archer.piercing_shot"->usePiercingShot(player);case "skill.archer.retreat_shot"->useRetreatShot(player);case "skill.archer.tracking_mark"->useTrackingMark(player);case "skill.archer.binding_shot"->useBindingShot(player);
            case "skill.mage.spirit_talisman"->useSpiritTalisman(player);case "skill.mage.binding_talisman"->useBindingTalisman(player);case "skill.mage.protective_talisman"->useProtectiveTalisman(player);case "skill.mage.short_step"->useShortStep(player);
            case "skill.rogue.ambush"->useAmbush(player);case "skill.rogue.smoke_bomb"->useSmokeBomb(player);case "skill.rogue.shackle_strike"->useShackleStrike(player);case "skill.rogue.escape"->useEscape(player);default->throw new IllegalArgumentException("Unknown active skill.");
        }
    }
    private static void prepareRun(DalmaruPayloads.RunEnterRequest payload,ServerPlayNetworking.Context context){
        var state=CONNECTIONS.get(context.player().getUUID());if(state==null||!state.compatible||state.accountSession==null||payload.requestId()<1||state.startPending)return;
        try{var players=PARTIES.members(context.player().getUUID());partyCharacterIds(context.player());for(UUID id:players){var player=context.server().getPlayerList().getPlayer(id);var member=CONNECTIONS.get(id);if(player==null||member==null||member.accountFailure!=null||member.activeProfile==null||!player.isAlive()||player.isSpectator()||!town(player)||!cachedForActive(member)||FIELD_REVIVES.containsKey(id)||RUN_RUNTIME.runId(player)!=null||member.startPending)throw new IllegalArgumentException("Party must prepare in town with loaded slots.");}
            for(UUID id:players){cancelPreparation(id,"새 준비 요청으로 이전 준비가 취소되었습니다.");}
            var preparation=new PreparedRun(payload,context,players);for(UUID id:players){PREPARATIONS.put(id,preparation);ServerPlayNetworking.send(context.server().getPlayerList().getPlayer(id),new DalmaruPayloads.RunResponse(payload.requestId(),true,preparation.preparationId,players.size(),players.size()==3?4:players.size(),"READY","전원이 준비 완료를 누르면 공략을 시작합니다."));}
        }catch(Exception error){sendRunFailure(context,payload.requestId(),error);}
    }
    private static void cancelPreparation(UUID playerId,String reason){
        var prep=PREPARATIONS.get(playerId);if(prep==null)return;
        for(UUID id:prep.players){PREPARATIONS.remove(id);var player=prep.context.server().getPlayerList().getPlayer(id);if(player!=null)ServerPlayNetworking.send(player,new DalmaruPayloads.RunResponse(prep.payload.requestId(),false,prep.preparationId,prep.players.size(),prep.players.size()==3?4:prep.players.size(),"CANCELLED",reason));}
    }
    private static void notifyPreparation(PreparedRun prep,String reason){
        for(UUID id:prep.players){var player=prep.context.server().getPlayerList().getPlayer(id);if(player!=null)ServerPlayNetworking.send(player,new DalmaruPayloads.RunResponse(prep.payload.requestId(),true,prep.preparationId,prep.players.size(),prep.players.size()==3?4:prep.players.size(),"READY",reason));}
    }
    private static void readyRun(ServerPlayer player,UUID preparationId,boolean ready){
        var prep=PREPARATIONS.get(player.getUUID());if(prep==null||!prep.preparationId.equals(preparationId))return;
        if(!PARTIES.members(prep.players.getFirst()).equals(prep.players)){cancelPreparation(player.getUUID(),"파티 구성이 바뀌어 준비가 취소되었습니다.");return;}
        for(int i=0;i<prep.players.size();i++){
            var member=CONNECTIONS.get(prep.players.get(i));var current=prep.context.server().getPlayerList().getPlayer(prep.players.get(i));
            if(member==null||current==null||member.accountSession!=prep.sessions.get(i)||member.accountFailure!=null||member.activeProfile==null||!cachedForActive(member)||FIELD_REVIVES.containsKey(current.getUUID())||!current.isAlive()||current.isSpectator()||!town(current)||RUN_RUNTIME.runId(current)!=null){cancelPreparation(player.getUUID(),"파티 상태가 바뀌어 준비가 취소되었습니다.");return;}
            if(member.loadoutWrites!=0){notifyPreparation(prep,"장착 저장이 끝난 뒤 준비해 주세요.");return;}
        }
        if(ready)prep.ready.add(player.getUUID());else prep.ready.remove(player.getUUID());
        notifyPreparation(prep,"준비 "+prep.ready.size()+"/"+prep.players.size());
        if(prep.ready.size()!=prep.players.size())return;
        for(UUID id:prep.players){PREPARATIONS.remove(id);CONNECTIONS.get(id).startPending=true;CONNECTIONS.get(id).startInvalidated=false;}
        enterPreparedRun(prep);
    }
    private static void validatePreparedRun(PreparedRun prep){
        if(!PARTIES.members(prep.players.getFirst()).equals(prep.players))throw new IllegalStateException("Entry roster changed.");
        for(int i=0;i<prep.players.size();i++){
            UUID id=prep.players.get(i);var member=CONNECTIONS.get(id);var current=prep.context.server().getPlayerList().getPlayer(id);
            if(member==null||current==null||!member.compatible||member.accountSession!=prep.sessions.get(i)||member.accountFailure!=null||member.startInvalidated||member.activeProfile==null||!member.activeProfile.characterId().equals(member.accountSession.activeCharacterId())||!cachedForActive(member)||member.loadoutWrites!=0||!current.isAlive()||current.isSpectator()||!town(current)||FIELD_REVIVES.containsKey(id)||RUN_RUNTIME.runId(current)!=null)throw new IllegalStateException("Entry participant is no longer ready.");
        }
    }
    private static void enterPreparedRun(PreparedRun prep){
            var payload=prep.payload;var context=prep.context;

            ConnectionState state=CONNECTIONS.get(context.player().getUUID());
            if(state==null||!state.compatible||state.accountSession==null||payload.requestId()<1){clearStarting(prep);return;}
            AccountSession session=state.accountSession;
            java.util.List<UUID> players,characters;
            try{validatePreparedRun(prep);players=PARTIES.members(context.player().getUUID());characters=partyCharacterIds(context.player());}
            catch(Exception error){clearStarting(prep);sendRunFailure(context,payload.requestId(),error);return;}
            if(!players.equals(prep.players)){clearStarting(prep);sendRunFailure(context,payload.requestId(),new IllegalStateException("Entry roster changed."));return;}
            var owner=new kr.dalmaru.run.RunLifecycle.Owner(session.accountId(),session.connectionGeneration(),payload.requestId());
            CompletableFuture.supplyAsync(()->{
                kr.dalmaru.run.RunRecord run;
                try{run=RUNS.enter(session.accountId(),session.connectionGeneration(),payload.requestId(),RunDifficulty.valueOf(payload.difficulty()),characters);}
                catch(Exception error){throw new IllegalStateException(error);}
                if(!run.replayed()&&!RUN_RUNTIME.prepareEntry(run.runId(),owner))throw new EntryFailure(run.runId(),owner,new IllegalStateException("Run lifecycle already claimed."));
                if(!RUN_RUNTIME.ownsEntry(run.runId(),owner))throw new EntryFailure(run.runId(),owner,new IllegalStateException("Run owner changed."));
                try{
                    var frozen=new java.util.HashMap<UUID,kr.dalmaru.skill.SkillLoadout>();
                    for(UUID character:characters)frozen.put(character,RUNS.frozenLoadout(run.runId(),character));
                    if(!RUN_RUNTIME.ownsEntry(run.runId(),owner))throw new IllegalStateException("Run preparation was invalidated.");
                    return new StartedRun(run,RUNS.frozenContent(run.runId()),frozen,owner);
                }catch(Exception error){throw new EntryFailure(run.runId(),owner,error);}
            },LIFECYCLE_DB).thenAccept(started->context.server().execute(()->{
                var run=started.run();
                try{
                    if(CONNECTIONS.get(context.player().getUUID())!=state||state.accountSession!=session)throw new IllegalStateException("Entry session changed.");
                    if(run.replayed()&&RUN_RUNTIME.joinable(run.runId())){
                        clearStarting(prep);
                        var participants=java.util.Set.copyOf(RUN_RUNTIME.participantIds(run.runId()));
                        if(!RUN_RUNTIME.ownsEntry(run.runId(),started.owner())||!participants.equals(java.util.Set.copyOf(players))){sendRunFailure(context,payload.requestId(),new IllegalStateException("Active run replay owner or party changed."));return;}
                        for(UUID id:players){var member=CONNECTIONS.get(id);if(member==null||member.accountSession!=prep.sessions.get(players.indexOf(id))||!characters.get(players.indexOf(id)).equals(member.accountSession.activeCharacterId())||context.server().getPlayerList().getPlayer(id)==null){sendRunFailure(context,payload.requestId(),new IllegalStateException("Active run replay roster changed."));return;}}
                        for(UUID id:players)ServerPlayNetworking.send(context.server().getPlayerList().getPlayer(id),new DalmaruPayloads.RunResponse(payload.requestId(),true,run.runId(),run.partySize(),run.scalingTier(),run.state(),"?? ??? ?? ???????."));
                        return;
                    }
                    validatePreparedRun(prep);
                    for(int i=0;i<players.size();i++){var member=CONNECTIONS.get(players.get(i));if(member==null||member.accountSession!=prep.sessions.get(i)||!characters.get(i).equals(member.accountSession.activeCharacterId())||context.server().getPlayerList().getPlayer(players.get(i))==null)throw new IllegalStateException("Entry roster changed.");}
                    if(!RUN_RUNTIME.ownsEntry(run.runId(),started.owner()))throw new IllegalStateException("Run owner changed before activation.");
                    if(!RUN_RUNTIME.preparing(run.runId(),started.owner()))throw new IllegalStateException("Run is no longer preparing.");
                    for(UUID id:players){var member=CONNECTIONS.get(id);var frozen=started.loadouts().get(member.accountSession.activeCharacterId());if(frozen==null||!frozen.characterId().equals(member.accountSession.activeCharacterId()))throw new IllegalStateException("Entry snapshot owner changed.");}
                    for(UUID id:players){var member=CONNECTIONS.get(id);member.loadout=started.loadouts().get(member.accountSession.activeCharacterId());}
                    enterPartyRun(context.server(),players,run,started.owner(),started.content());clearStarting(prep);
                    for(UUID id:players)ServerPlayNetworking.send(context.server().getPlayerList().getPlayer(id),new DalmaruPayloads.RunResponse(payload.requestId(),true,run.runId(),run.partySize(),run.scalingTier(),run.state(),"???? ??? ??????."));
                }catch(Exception error){failPreparedRun(context.server(),prep,session,payload.requestId(),run.runId(),started.owner(),error);}
            })).exceptionally(error->{
                Throwable cause=error;while(cause.getCause()!=null&&(cause instanceof java.util.concurrent.CompletionException||cause instanceof java.util.concurrent.ExecutionException))cause=cause.getCause();
                Throwable failure=cause;
                if(failure instanceof EntryFailure failed)context.server().execute(()->failPreparedRun(context.server(),prep,session,payload.requestId(),failed.runId,failed.owner,failed));
                else context.server().execute(()->{clearStarting(prep);sendRunFailure(context,payload.requestId(),failure);});
                return null;
            });

    }

    private static void failPreparedRun(MinecraftServer server,PreparedRun prep,AccountSession session,long requestId,UUID runId,kr.dalmaru.run.RunLifecycle.Owner owner,Throwable error){
        clearStarting(prep);
        CompletableFuture.runAsync(()->{try{RUNS.cancelNewEntry(runId,session.accountId(),session.connectionGeneration(),requestId);}catch(Exception failure){LOGGER.error("Failed to cancel run entry.",failure);}},LIFECYCLE_DB)
            .whenComplete((ignored,failure)->server.execute(()->{try{RUN_RUNTIME.close(server,runId,owner);}catch(Exception cleanup){error.addSuppressed(cleanup);}sendRunFailure(prep.context,requestId,error);}));
    }

    private static void clearStarting(PreparedRun prep){for(int i=0;i<prep.players.size();i++){var member=CONNECTIONS.get(prep.players.get(i));if(member!=null&&member.accountSession==prep.sessions.get(i)){member.startPending=false;member.startInvalidated=false;}}}

    private record DodgeMotion(net.minecraft.world.phys.Vec3 direction,long until){}
    private static final Map<UUID,DodgeMotion> DODGES=new java.util.HashMap<>();
    public static void attackStarted(ServerPlayer player){COMBAT.attackStarted(player);}
    public static boolean managedGuard(LivingEntity entity){return entity instanceof ServerPlayer player&&COMBAT.managed(player);}
    public static boolean resistsStagger(LivingEntity entity){return entity instanceof ServerPlayer player&&COMBAT.resistsStagger(player.getUUID(),currentTick);}
    private static void dodge(ServerPlayer player,int direction){
        var state=CONNECTIONS.get(player.getUUID());if(direction<0||direction>3||state==null||!state.compatible||state.accountFailure!=null||!combatMode(player,state)||!COMBAT.dodge(player,currentTick))return;
        var look=player.getLookAngle();var forward=new net.minecraft.world.phys.Vec3(look.x,0,look.z).normalize();var vector=switch(direction){case 1->forward.scale(-1);case 2->new net.minecraft.world.phys.Vec3(forward.z,0,-forward.x);case 3->new net.minecraft.world.phys.Vec3(-forward.z,0,forward.x);default->forward;};
        DODGES.put(player.getUUID(),new DodgeMotion(vector,currentTick+9));
    }
    private static void guard(ServerPlayer player,boolean enabled){var state=CONNECTIONS.get(player.getUUID());if(state==null||!state.compatible||state.accountFailure!=null||!player.isAlive()||player.isSpectator())return;COMBAT.guard(player,enabled&&combatMode(player,state),currentTick);}
    private static void tickDodges(MinecraftServer server){
        for(var entry:new java.util.ArrayList<>(DODGES.entrySet())){var player=server.getPlayerList().getPlayer(entry.getKey());if(player==null||!player.isAlive()||entry.getValue().until()<=currentTick){DODGES.remove(entry.getKey());continue;}RUN_RUNTIME.moveDirection(player,entry.getValue().direction(),1.0/3);}
        for(var player:server.getPlayerList().getPlayers())if(COMBAT.managed(player))COMBAT.movementEffects(player,currentTick);
    }

    private record FieldRevive(AccountSession session,long due,kr.dalmaru.account.SafeCheckpoint checkpoint,net.minecraft.world.level.GameType mode){}
    private static final Map<UUID,FieldRevive> FIELD_REVIVES=new java.util.HashMap<>();
    private static void clearFieldRevive(ServerPlayer player){var pending=FIELD_REVIVES.remove(player.getUUID());if(pending!=null)player.setGameMode(pending.mode());}
    private static void fieldDeath(ServerPlayer player){var state=CONNECTIONS.get(player.getUUID());if(state!=null&&state.startPending)state.startInvalidated=true;if(state!=null&&state.accountSession!=null&&state.accountSession.activeCharacterId()!=null)FIELD_REVIVES.put(player.getUUID(),new FieldRevive(state.accountSession,currentTick+60,activeCheckpoint(state),player.gameMode.getGameModeForPlayer()));}
    private static void fieldRespawn(ServerPlayer player){
        var state=CONNECTIONS.get(player.getUUID());if(state==null||state.accountSession==null||state.accountSession.activeCharacterId()==null)return;
        var pending=FIELD_REVIVES.get(player.getUUID());if(pending==null||pending.session()!=state.accountSession)FIELD_REVIVES.put(player.getUUID(),new FieldRevive(state.accountSession,currentTick+60,activeCheckpoint(state),player.gameMode.getGameModeForPlayer()));
        player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);state.manualCombat=false;state.manualUntil=0;
    }
    private static kr.dalmaru.account.SafeCheckpoint fallback(MinecraftServer server){
        if(!TOWNS.isEmpty()){var town=TOWNS.getFirst();return new kr.dalmaru.account.SafeCheckpoint("town:"+town.x()+":"+town.z(),town.dimension(),town.x()+.5,town.y(),town.z()+.5);}
        var spawn=server.overworld().getRespawnData();var pos=spawn.pos();return new kr.dalmaru.account.SafeCheckpoint("world_entry",spawn.dimension().identifier().toString(),pos.getX()+.5,pos.getY(),pos.getZ()+.5);
    }
    private static net.minecraft.world.phys.Vec3 safeFieldPosition(ServerPlayer player,ServerLevel level,kr.dalmaru.account.SafeCheckpoint point){
        var origin=new net.minecraft.world.phys.Vec3(point.x(),point.y(),point.z());
        net.minecraft.world.phys.Vec3 nearest=null;double distance=Double.MAX_VALUE;
        var center=net.minecraft.core.BlockPos.containing(origin);
        for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)for(int y=-3;y<=3;y++){
            var feet=center.offset(x,y,z);var below=level.getBlockState(feet.below());
            if(!level.getFluidState(feet).isEmpty()||!level.getFluidState(feet.above()).isEmpty()||!level.getFluidState(feet.below()).isEmpty()||below.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)||below.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)||below.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)||below.getCollisionShape(level,feet.below()).isEmpty())continue;
            var candidate=feet.getCenter().add(0,-.5,0);double squared=candidate.distanceToSqr(origin);
            if(squared>=distance||RUN_RUNTIME.protectedBlock(level,feet)||!level.noCollision(player,player.getBoundingBox().move(candidate.subtract(player.position()))))continue;
            nearest=candidate;distance=squared;
        }
        return nearest;
    }
    private record FieldDestination(ServerLevel level,net.minecraft.world.phys.Vec3 position){}
    private static FieldDestination fieldDestination(ServerPlayer player,MinecraftServer server,kr.dalmaru.account.SafeCheckpoint active){
        var candidates=new java.util.ArrayList<kr.dalmaru.account.SafeCheckpoint>();
        if(active!=null)candidates.add(active);
        for(int pass=0;pass<2;pass++)for(var town:TOWNS){
            boolean same=active==null||town.dimension().equals(active.dimension());
            if(same!=(pass==0))continue;
            var point=new kr.dalmaru.account.SafeCheckpoint("town:"+town.x()+":"+town.z(),town.dimension(),town.x()+.5,town.y(),town.z()+.5);
            if(!candidates.contains(point))candidates.add(point);
        }
        var level=server.overworld();var spawn=level.getRespawnData().pos();
        candidates.add(new kr.dalmaru.account.SafeCheckpoint("world_entry",level.dimension().identifier().toString(),spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5));
        for(var point:candidates){
            var id=net.minecraft.resources.Identifier.tryParse(point.dimension());
            if(id==null){LOGGER.warn("Invalid revive dimension for checkpoint {}",point.pointId());continue;}
            var target=server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,id));
            if(target==null)continue;
            var safe=safeFieldPosition(player,target,point);
            if(safe!=null)return new FieldDestination(target,safe);
        }
        return null;
    }
    private static void tickFieldRevives(MinecraftServer server){
        for(var entry:new java.util.ArrayList<>(FIELD_REVIVES.entrySet())){
            var state=CONNECTIONS.get(entry.getKey());var player=server.getPlayerList().getPlayer(entry.getKey());
            if(state==null||player==null||state.accountSession!=entry.getValue().session()){FIELD_REVIVES.remove(entry.getKey());continue;}
            if(currentTick<entry.getValue().due())continue;
            if(!player.isAlive())player=server.getPlayerList().respawn(player,false,net.minecraft.world.entity.Entity.RemovalReason.KILLED);
            var destination=fieldDestination(player,server,entry.getValue().checkpoint());
            if(destination==null){player.setGameMode(entry.getValue().mode());LOGGER.error("No safe field revive floor or fallback for {}",player.getUUID());FIELD_REVIVES.remove(entry.getKey());player.sendSystemMessage(Component.literal("안전한 부활 위치를 찾지 못했습니다. 운영자에게 문의하세요."));continue;}
            var level=destination.level();var safe=destination.position();
            player.teleportTo(level,safe.x,safe.y,safe.z,java.util.Set.of(),player.getYRot(),player.getXRot(),false);player.setGameMode(entry.getValue().mode());player.setHealth(player.getMaxHealth()*.5F);COMBAT.revived(player,.5,false,currentTick);state.manualCombat=false;state.manualUntil=0;FIELD_REVIVES.remove(entry.getKey());
        }
        if(currentTick%20!=0)return;
        for(var player:server.getPlayerList().getPlayers()){
            var state=CONNECTIONS.get(player.getUUID());if(state==null||state.accountSession==null||state.accountSession.activeCharacterId()==null||state.accountFailure!=null||!player.isAlive()||player.isSpectator()||FIELD_REVIVES.containsKey(player.getUUID())||RUN_RUNTIME.runId(player)!=null)continue;
            var pos=player.blockPosition();var zone=TOWNS.stream().filter(town->town.contains(player.level().dimension().identifier().toString(),pos.getX(),pos.getY(),pos.getZ())).findFirst().orElse(null);if(zone==null)continue;
            String id=zone.dimension()+":"+zone.x()+":"+zone.y()+":"+zone.z();if(id.equals(state.checkpointKey)&&state.accountSession.activeCharacterId().equals(state.checkpointCharacter))continue;
            var checkpoint=new kr.dalmaru.account.SafeCheckpoint(id,zone.dimension(),zone.x()+.5,zone.y(),zone.z()+.5);var session=state.accountSession;state.checkpointKey=id;
            CompletableFuture.runAsync(()->{try{SAFETY.activate(session.accountId(),session.connectionGeneration(),session.activeCharacterId(),checkpoint);}catch(Exception error){throw new IllegalStateException(error);}},LIFECYCLE_DB)
                .thenRun(()->server.execute(()->{if(CONNECTIONS.get(player.getUUID())==state&&state.accountSession==session){state.checkpoint=checkpoint;state.checkpointCharacter=session.activeCharacterId();}}))
                .exceptionally(error->{LOGGER.warn("Failed to activate town safety checkpoint.",error);server.execute(()->{if(CONNECTIONS.get(player.getUUID())==state&&state.accountSession==session)state.checkpointKey=null;});return null;});
        }
    }

}
