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
	public static final int PROTOCOL_VERSION = 27;
	private static final long HELLO_TIMEOUT_TICKS = 200;
	private static final long RATE_LIMIT_NANOS = 1_000_000_000L;
	private static final Map<UUID, ConnectionState> CONNECTIONS = new HashMap<>();
	private static final AccountRepository ACCOUNTS = new AccountRepository();
	private static final CharacterRepository CHARACTERS = new CharacterRepository();
	private static final CombatRuntime COMBAT = new CombatRuntime();
	private static final QuestRepository QUESTS = new QuestRepository();
	private static final EquipmentRepository EQUIPMENT = new EquipmentRepository();
	private static final RunRepository RUNS = new RunRepository();
	private static final RunRuntime RUN_RUNTIME = new RunRuntime(COMBAT::bossDamage);
	private static final PartyRuntime PARTIES = new PartyRuntime();
	private static final SkillRepository SKILLS = new SkillRepository();
	private static long currentTick;

	private DalmaruNetworking() { }

	public static void initialize() {
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
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.SweepRequest.TYPE, DalmaruPayloads.SweepRequest.CODEC);
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
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.EquipmentEnhanceResponse.TYPE, DalmaruPayloads.EquipmentEnhanceResponse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.EquipmentListRequest.TYPE, DalmaruPayloads.EquipmentListRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.EquipmentListResponse.TYPE, DalmaruPayloads.EquipmentListResponse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.RunEnterRequest.TYPE, DalmaruPayloads.RunEnterRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.RunResponse.TYPE, DalmaruPayloads.RunResponse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.SkillLearnRequest.TYPE, DalmaruPayloads.SkillLearnRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.SkillLearnResponse.TYPE, DalmaruPayloads.SkillLearnResponse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.SkillRanksRequest.TYPE, DalmaruPayloads.SkillRanksRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.SkillRanksResponse.TYPE, DalmaruPayloads.SkillRanksResponse.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DalmaruPayloads.CharacterCreateRequest.TYPE, DalmaruPayloads.CharacterCreateRequest.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DalmaruPayloads.CharacterCreateResponse.TYPE, DalmaruPayloads.CharacterCreateResponse.CODEC);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			UUID playerId = handler.getPlayer().getUUID();
			CONNECTIONS.put(playerId, new ConnectionState(currentTick));
			CompletableFuture.supplyAsync(() -> {
				try {
					return ACCOUNTS.openSession(playerId);
				} catch (Exception exception) {
					throw new IllegalStateException(exception);
				}
			}).thenAccept(session -> server.execute(() -> {
				ConnectionState state = CONNECTIONS.get(playerId);
				if (state != null) {
					state.accountSession = session;
					ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				if (player != null) { sendCharacterSelectionReady(player, state); if (session.activeCharacterId() != null) CompletableFuture.supplyAsync(() -> { try { return RUNS.active(session.activeCharacterId()); } catch (Exception exception) { throw new IllegalStateException(exception); } }).thenAccept(run -> server.execute(() -> { ServerPlayer current = server.getPlayerList().getPlayer(playerId); if (run != null && current != null && CONNECTIONS.get(playerId) == state && state.accountSession == session) RUN_RUNTIME.enter(current, run.runId(), run.difficulty(), run.partySize()); })); }
				}
			})).exceptionally(error -> {
				server.execute(() -> {
					ConnectionState state = CONNECTIONS.get(playerId);
					if (state != null) state.accountFailure = "계정 저장소에 연결하지 못했습니다. 서버 로그를 확인하세요.";
					LOGGER.error("Failed to open Dalmaru account session for {}.", playerId, error);
				});
				return null;
			});
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			ConnectionState state = CONNECTIONS.remove(handler.getPlayer().getUUID());
			if (state != null && state.accountSession != null && state.accountSession.activeCharacterId() != null) {
				UUID characterId = state.accountSession.activeCharacterId();
				java.util.List<UUID> connected = CONNECTIONS.values().stream().filter(member -> member.accountSession != null && member.accountSession.activeCharacterId() != null).map(member -> member.accountSession.activeCharacterId()).toList();
				CompletableFuture.runAsync(() -> { try { RUNS.pauseIfUnattended(characterId, connected); } catch (Exception exception) { LOGGER.warn("Failed to pause unattended run.", exception); } });
			}
			RUN_RUNTIME.disconnect(handler.getPlayer());
			COMBAT.remove(handler.getPlayer().getUUID());
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			// Restore profile modifiers before the run sets revive health.
			COMBAT.restoreAfterRespawn(oldPlayer, newPlayer);
			RUN_RUNTIME.afterRespawn(oldPlayer, newPlayer);
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> { expireMissingHello(server); COMBAT.tick(currentTick); RUN_RUNTIME.tick(server); if (currentTick % 20 == 0) { sendCombatStates(server); CompletableFuture.runAsync(() -> { try { RUNS.expireTimedOut(); } catch (Exception exception) { LOGGER.warn("Failed to expire timed out runs.", exception); } }); } });
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
			if (damageTaken > 0) {
				if (source.getEntity() instanceof ServerPlayer player) COMBAT.recordValidHit(player, currentTick);
			if (entity instanceof ServerPlayer player) COMBAT.recordValidHit(player, currentTick);
			}
		});
		// AFTER_DAMAGE is not fired for lethal damage, so revives are counted here.
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) failRunAfterFinalDeath(player, serverFor(player));
		});
		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((level, killer, killed, damageSource) -> {
			if (killer instanceof ServerPlayer player) {
				grantCombatExperience(player, level.getServer(), CombatRewards.experienceFor(killed.getType()));
				UUID completedRun = RUN_RUNTIME.bossKilled(player, killed.getUUID());
				if (completedRun != null) completeRun(player, level.getServer(), completedRun);
				if (killed.getType().getDescriptionId().equals("entity.minecraft.zombie")) recordQuestZombieKill(player, level.getServer());
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
		));

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
					return CHARACTERS.list(session.accountId()).stream()
						.map(character -> new DalmaruPayloads.CharacterSummary(character.characterId(), character.slot(), character.displayName(), character.characterId().equals(session.activeCharacterId())))
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
			AccountSession session = state.accountSession;
			CompletableFuture.supplyAsync(() -> {
				try {
					CharacterSelectionResult selection = CHARACTERS.selectActive(session.accountId(), session.connectionGeneration(), payload.requestId(), payload.characterId());
					return new SelectedCharacter(selection, CHARACTERS.profile(selection.characterId()), SKILLS.ranks(selection.characterId()));
				} catch (Exception exception) {
					throw new IllegalStateException(exception);
				}
			}).thenAccept(result -> context.server().execute(() -> {
				if (CONNECTIONS.get(context.player().getUUID()) == state && state.accountSession == session) {
					state.accountSession = new AccountSession(session.accountId(), result.selection().characterId(), session.connectionGeneration());
				state.activeProfile = result.profile();
				state.skillRanks = result.skillRanks();
				sendCharacterSelect(context, payload.requestId(), result.selection().characterId(), true, result.selection().replayed(), result.selection().replayed() ? "기존 선택 결과입니다." : "캐릭터를 선택했습니다.");
					sendCharacterProfile(context.player(), result.profile());
				}
			})).exceptionally(error -> {
				LOGGER.warn("Rejected character selection for {}.", session.accountId(), error);
				context.server().execute(() -> sendCharacterSelect(context, payload.requestId(), payload.characterId(), false, false, "캐릭터를 선택할 수 없습니다."));
				return null;
			});
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

		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterStatInvestmentRequest.TYPE, (payload, context) -> {
			ConnectionState state = stateFor(context.player().getUUID());
			if (payload.requestId() <= 0 || payload.amount() != 1 || !state.compatible || state.accountSession == null || state.accountFailure != null) {
				sendCharacterStatInvestment(context, payload.requestId(), payload.characterId(), false, false, "능력치 투자 준비가 완료되지 않았습니다.", null);
				return;
			}
			AccountSession session = state.accountSession;
			if (!payload.characterId().equals(session.activeCharacterId())) {
				sendCharacterStatInvestment(context, payload.requestId(), payload.characterId(), false, false, "현재 선택한 캐릭터만 투자할 수 있습니다.", null);
				return;
			}
			CompletableFuture.supplyAsync(() -> {
				try { return CHARACTERS.investStat(session.accountId(), session.connectionGeneration(), payload.requestId(), payload.characterId(), CharacterStat.fromId(payload.statId()), payload.amount()); }
				catch (Exception exception) { throw new IllegalStateException(exception); }
			}).thenAccept(result -> context.server().execute(() -> {
				if (CONNECTIONS.get(context.player().getUUID()) == state && state.accountSession == session) {
					 sendCharacterStatInvestment(context, payload.requestId(), payload.characterId(), true, result.replayed(), result.replayed() ? "기존 능력치 투자 결과입니다." : "능력치를 투자했습니다.", result.profile());
				}
			})).exceptionally(error -> {
				LOGGER.warn("Rejected character stat investment for {}.", session.accountId(), error);
				context.server().execute(() -> sendCharacterStatInvestment(context, payload.requestId(), payload.characterId(), false, false, "능력치를 투자할 수 없습니다.", null));
				return null;
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterStatResetRequest.TYPE, (payload, context) -> {
			ConnectionState state = stateFor(context.player().getUUID());
			if (payload.requestId() <= 0 || !state.compatible || state.accountSession == null || state.accountFailure != null || !payload.characterId().equals(state.accountSession.activeCharacterId())) {
				sendCharacterStatReset(context, payload.requestId(), payload.characterId(), false, false, "무료 초기화 요청을 처리할 수 없습니다.", null);
				return;
			}
			AccountSession session = state.accountSession;
			CompletableFuture.supplyAsync(() -> {
				try { return CHARACTERS.resetStats(session.accountId(), session.connectionGeneration(), payload.requestId(), payload.characterId()); }
				catch (Exception exception) { throw new IllegalStateException(exception); }
			}).thenAccept(result -> context.server().execute(() -> {
				if (CONNECTIONS.get(context.player().getUUID()) == state && state.accountSession == session) sendCharacterStatReset(context, payload.requestId(), payload.characterId(), true, result.replayed(), result.replayed() ? "기존 초기화 결과입니다." : "무료 초기화를 완료했습니다.", result.profile());
			})).exceptionally(error -> {
				LOGGER.warn("Rejected character stat reset for {}.", session.accountId(), error);
				context.server().execute(() -> sendCharacterStatReset(context, payload.requestId(), payload.characterId(), false, false, "무료 초기화를 할 수 없습니다.", null));
				return null;
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterProfessionRequest.TYPE, (payload, context) -> {
			ConnectionState state = stateFor(context.player().getUUID());
			if (payload.requestId() < 1 || !state.compatible || state.accountSession == null || state.accountFailure != null || state.accountSession.activeCharacterId() == null) {
				sendCharacterProfession(context, payload.requestId(), false, "전직할 캐릭터를 먼저 선택하세요.", null);
				return;
			}
			CharacterProfession profession;
			try { profession = CharacterProfession.valueOf(payload.profession().toUpperCase(java.util.Locale.ROOT)); }
			catch (IllegalArgumentException exception) { sendCharacterProfession(context, payload.requestId(), false, "선택할 수 없는 직업입니다.", null); return; }
			AccountSession session = state.accountSession;
			CompletableFuture.supplyAsync(() -> {
				try {
					CHARACTERS.selectProfession(session.accountId(), session.connectionGeneration(), payload.requestId(), session.activeCharacterId(), profession);
					return CHARACTERS.profile(session.activeCharacterId());
				} catch (Exception exception) { throw new IllegalStateException(exception); }
			}).thenAccept(profile -> context.server().execute(() -> {
				if (CONNECTIONS.get(context.player().getUUID()) == state && state.accountSession == session) sendCharacterProfession(context, payload.requestId(), true, profession.koreanName() + " 전직을 완료했습니다.", profile);
			})).exceptionally(error -> {
				LOGGER.warn("Rejected character profession for {}.", session.accountId(), error);
				context.server().execute(() -> sendCharacterProfession(context, payload.requestId(), false, "전직 조건을 확인하세요.", null));
				return null;
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.QuestRequest.TYPE, (payload, context) -> {
			ConnectionState state = stateFor(context.player().getUUID());
			if (payload.requestId() < 1 || !state.compatible || state.accountSession == null || state.accountFailure != null || state.accountSession.activeCharacterId() == null) {
				ServerPlayNetworking.send(context.player(), new DalmaruPayloads.QuestResponse(payload.requestId(), "퀘스트를 확인할 캐릭터를 먼저 선택하세요."));
				return;
			}
			AccountSession session = state.accountSession;
			CompletableFuture.supplyAsync(() -> {
				try { return payload.accept() ? QUESTS.accept(session.activeCharacterId()) : QUESTS.status(session.activeCharacterId()); }
				catch (Exception exception) { throw new IllegalStateException(exception); }
			}).thenAccept(status -> context.server().execute(() -> {
				if (CONNECTIONS.get(context.player().getUUID()) == state && state.accountSession == session) ServerPlayNetworking.send(context.player(), new DalmaruPayloads.QuestResponse(payload.requestId(), status));
			})).exceptionally(error -> {
				LOGGER.warn("Rejected quest request for {}.", session.accountId(), error);
				context.server().execute(() -> ServerPlayNetworking.send(context.player(), new DalmaruPayloads.QuestResponse(payload.requestId(), "퀘스트를 처리할 수 없습니다.")));
				return null;
			});
		});
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.SweepRequest.TYPE, (payload, context) -> useSweep(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.AdvanceRequest.TYPE, (payload, context) -> useAdvance(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.RisingStrikeRequest.TYPE, (payload, context) -> useRisingStrike(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.BattleCryRequest.TYPE, (payload, context) -> useBattleCry(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.PiercingShotRequest.TYPE, (payload, context) -> usePiercingShot(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.SpiritTalismanRequest.TYPE, (payload, context) -> useSpiritTalisman(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.AmbushRequest.TYPE, (payload, context) -> useAmbush(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.RetreatShotRequest.TYPE, (payload, context) -> useRetreatShot(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.BindingTalismanRequest.TYPE, (payload, context) -> useBindingTalisman(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.SmokeBombRequest.TYPE, (payload, context) -> useSmokeBomb(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.ShackleStrikeRequest.TYPE, (payload, context) -> useShackleStrike(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.EscapeRequest.TYPE, (payload, context) -> useEscape(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.TrackingMarkRequest.TYPE, (payload, context) -> useTrackingMark(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.BindingShotRequest.TYPE, (payload, context) -> useBindingShot(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.ProtectiveTalismanRequest.TYPE, (payload, context) -> useProtectiveTalisman(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.ShortStepRequest.TYPE, (payload, context) -> useShortStep(context.player()));
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.EquipmentEnhanceRequest.TYPE, (payload, context) -> {
			ConnectionState state = stateFor(context.player().getUUID());
			if (payload.requestId() < 1 || state.accountSession == null) return;
			AccountSession session = state.accountSession;
			CompletableFuture.supplyAsync(() -> { try { return EQUIPMENT.enhance(session.accountId(), session.connectionGeneration(), payload.requestId(), payload.equipmentId()); } catch (Exception exception) { throw new IllegalStateException(exception); } })
				.thenAccept(result -> context.server().execute(() -> {
					if (CONNECTIONS.get(context.player().getUUID()) != state || state.accountSession != session) return;
					if (state.activeProfile != null) refreshCombat(context.player(), state.activeProfile);
					ServerPlayNetworking.send(context.player(), new DalmaruPayloads.EquipmentEnhanceResponse(payload.requestId(), true, result.success(), result.equipment().enhancement(), result.equipment().consecutiveFailures(), result.success() ? "강화 성공" : "강화 실패"));
				}))
				.exceptionally(error -> { context.server().execute(() -> ServerPlayNetworking.send(context.player(), new DalmaruPayloads.EquipmentEnhanceResponse(payload.requestId(), false, false, 0, 0, "강화할 수 없습니다."))); return null; });
		});
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.EquipmentListRequest.TYPE, (payload, context) -> {
			ConnectionState state = stateFor(context.player().getUUID());
			if (payload.requestId() < 1 || !state.compatible || state.accountSession == null || state.accountSession.activeCharacterId() == null) return;
			AccountSession session = state.accountSession;
			CompletableFuture.supplyAsync(() -> { try { return EQUIPMENT.equipped(session.activeCharacterId()).stream().map(equipment -> new DalmaruPayloads.EquipmentData(equipment.equipmentId(), equipment.slotId(), equipment.itemId(), equipment.enhancement(), equipment.consecutiveFailures(), equipment.bound())).toList(); } catch (Exception exception) { throw new IllegalStateException(exception); } }).thenAccept(equipment -> context.server().execute(() -> { if (CONNECTIONS.get(context.player().getUUID()) == state && state.accountSession == session) ServerPlayNetworking.send(context.player(), new DalmaruPayloads.EquipmentListResponse(payload.requestId(), "", equipment)); })).exceptionally(error -> { context.server().execute(() -> ServerPlayNetworking.send(context.player(), new DalmaruPayloads.EquipmentListResponse(payload.requestId(), "장비를 불러올 수 없습니다.", java.util.List.of()))); return null; });
		});
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.RunEnterRequest.TYPE, (payload, context) -> {
			ConnectionState state = stateFor(context.player().getUUID());
			if (payload.requestId() < 1 || !state.compatible || state.accountSession == null || state.accountSession.activeCharacterId() == null) return;
			AccountSession session = state.accountSession;
			java.util.List<UUID> partyPlayers = PARTIES.members(context.player().getUUID());
			CompletableFuture.supplyAsync(() -> { try { return RUNS.enter(session.accountId(), session.connectionGeneration(), payload.requestId(), RunDifficulty.valueOf(payload.difficulty()), partyCharacterIds(context.player())); } catch (Exception exception) { throw new IllegalStateException(exception); } }).thenAccept(run -> context.server().execute(() -> { if (CONNECTIONS.get(context.player().getUUID()) == state && state.accountSession == session) { enterPartyRun(context.server(), partyPlayers, run); ServerPlayNetworking.send(context.player(), new DalmaruPayloads.RunResponse(payload.requestId(), true, run.runId(), run.partySize(), run.scalingTier(), run.state(), run.replayed() ? "기존 공략 예약입니다." : "폐봉수대 공략을 시작했습니다.")); } })).exceptionally(error -> { context.server().execute(() -> ServerPlayNetworking.send(context.player(), new DalmaruPayloads.RunResponse(payload.requestId(), false, new java.util.UUID(0, 0), 0, 0, "", "공략 입장 조건을 충족하지 못했습니다."))); return null; });
		});
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.SkillLearnRequest.TYPE, (payload, context) -> {
			ConnectionState state = stateFor(context.player().getUUID());
			if (payload.requestId() < 1 || !state.compatible || state.accountSession == null || state.accountFailure != null || state.activeProfile == null || state.activeProfile.profession().isBlank()) { sendSkillLearn(context, payload.requestId(), false, "전직한 캐릭터를 먼저 선택하세요.", null); return; }
			AccountSession session = state.accountSession;
			CharacterProfession profession = CharacterProfession.valueOf(state.activeProfile.profession());
			CompletableFuture.supplyAsync(() -> {
				try { int rank = SKILLS.learn(session.accountId(), session.connectionGeneration(), payload.requestId(), session.activeCharacterId(), profession, payload.skillId()); return new LearnedSkill(rank, CHARACTERS.profile(session.activeCharacterId())); }
				catch (Exception exception) { throw new IllegalStateException(exception); }
			}).thenAccept(result -> context.server().execute(() -> { if (CONNECTIONS.get(context.player().getUUID()) == state && state.accountSession == session) { state.skillRanks.put(payload.skillId(), result.rank()); sendSkillLearn(context, payload.requestId(), true, payload.skillId() + " " + result.rank() + "단계를 습득했습니다.", result.profile()); } }))
				.exceptionally(error -> { LOGGER.warn("Rejected skill learning for {}.", session.accountId(), error); context.server().execute(() -> sendSkillLearn(context, payload.requestId(), false, "기술을 습득할 수 없습니다.", null)); return null; });
		});
		ServerPlayNetworking.registerGlobalReceiver(DalmaruPayloads.SkillRanksRequest.TYPE, (payload, context) -> {
			ConnectionState state = stateFor(context.player().getUUID());
			if (payload.requestId() < 1 || state.accountSession == null || state.accountSession.activeCharacterId() == null) return;
			AccountSession session = state.accountSession;
			CompletableFuture.supplyAsync(() -> { try { return SKILLS.ranks(session.activeCharacterId()).entrySet().stream().map(entry -> new DalmaruPayloads.SkillRank(entry.getKey(), entry.getValue())).toList(); } catch (Exception exception) { throw new IllegalStateException(exception); } })
				.thenAccept(ranks -> context.server().execute(() -> { if (CONNECTIONS.get(context.player().getUUID()) == state && state.accountSession == session) ServerPlayNetworking.send(context.player(), new DalmaruPayloads.SkillRanksResponse(payload.requestId(), ranks)); }));
		});
	}

	private static MinecraftServer serverFor(ServerPlayer player) { return ((ServerLevel) player.level()).getServer(); }

	private static boolean playerAttack(DamageSource source) {
		return source.is(DamageTypes.PLAYER_ATTACK) || source.is(DamageTypeTags.IS_PROJECTILE);
	}
	public static boolean managedDamage(LivingEntity target, DamageSource source) {
		return CombatRuntime.explicitRpgDamage(target)
			|| (source.getEntity() instanceof ServerPlayer attacker && COMBAT.managed(attacker) && playerAttack(source))
			|| (target instanceof ServerPlayer player && COMBAT.managed(player));
	}
	public static float incomingDamage(LivingEntity target, DamageSource source, float amount) {
		if (CombatRuntime.explicitRpgDamage(target)) return amount;
		if (!Float.isFinite(amount) || amount < 0) throw new IllegalArgumentException("Invalid incoming damage.");
		if (amount == 0) return 0;
		if (source.getEntity() instanceof ServerPlayer attacker && COMBAT.managed(attacker) && playerAttack(source)) {
			boolean magic = COMBAT.magical(attacker);
			int defense = target instanceof ServerPlayer player ? COMBAT.defense(player, magic) : RUN_RUNTIME.bossDefense(target.getUUID(), magic);
			double multiplier = source.is(DamageTypeTags.IS_PROJECTILE) ? 1 : amount / attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);
			return CombatUnits.toEngine(COMBAT.basicAttack(attacker, multiplier, defense, currentTick));
		}
		if (target instanceof ServerPlayer player && COMBAT.managed(player)) {
			boolean magic = source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC) || source.is(DamageTypeTags.IS_FIRE);
			int defense = source.is(DamageTypeTags.BYPASSES_ARMOR) ? 0 : COMBAT.defense(player, magic);
			return CombatUnits.toEngine(CombatCalculator.damage(CombatUnits.toRpg(amount), 1, 1, false, defense));
		}
		return amount;
	}
	private static boolean rpgSkillDamage(ServerPlayer player, LivingEntity target, float amount) {
		boolean magic = COMBAT.magical(player);
		int defense = target instanceof ServerPlayer victim ? COMBAT.defense(victim, magic) : RUN_RUNTIME.bossDefense(target.getUUID(), magic);
		int damage = COMBAT.attack(player, amount / player.getAttributeValue(Attributes.ATTACK_DAMAGE), defense);
		return CombatRuntime.hurtRpg(target, player.damageSources().playerAttack(player), damage);
	}
	private static void refreshCombat(ServerPlayer player, CharacterProfile profile) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.accountSession == null || !profile.characterId().equals(state.accountSession.activeCharacterId())) return;
		COMBAT.clearEquipment(player, profile);
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

	private static java.util.List<UUID> partyCharacterIds(ServerPlayer player) {
		java.util.List<UUID> members = PARTIES.members(player.getUUID());
		if (!members.getFirst().equals(player.getUUID())) throw new IllegalArgumentException("Only the party leader can start the run.");
		java.util.List<UUID> characters = new java.util.ArrayList<>();
		for (UUID member : members) { ConnectionState state = CONNECTIONS.get(member); if (state == null || state.accountSession == null || state.accountSession.activeCharacterId() == null) throw new IllegalArgumentException("Party member is not ready."); characters.add(state.accountSession.activeCharacterId()); }
		return characters;
	}

	private static void enterPartyRun(MinecraftServer server, java.util.List<UUID> players, kr.dalmaru.run.RunRecord run) { for (UUID id : players) { ServerPlayer member = server.getPlayerList().getPlayer(id); if (member != null) RUN_RUNTIME.enter(member, run.runId(), run.difficulty(), run.partySize()); } }

	private static void completeRun(ServerPlayer player, MinecraftServer server, UUID runId) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.accountSession == null) return;
		AccountSession session = state.accountSession;
		CompletableFuture.supplyAsync(() -> { try { RUNS.finish(session.accountId(), session.connectionGeneration(), System.nanoTime() & Long.MAX_VALUE, runId, true); Map<UUID, CharacterProfile> profiles = new HashMap<>(); for (var reward : RUNS.rewards(runId)) profiles.put(reward.characterId(), CHARACTERS.grantExperience(reward.characterId(), 500)); return profiles; } catch (Exception exception) { throw new IllegalStateException(exception); } }).thenAccept(profiles -> server.execute(() -> {
			for (var entry : CONNECTIONS.entrySet()) { ConnectionState memberState = entry.getValue(); if (memberState.accountSession == null) continue; CharacterProfile profile = profiles.get(memberState.accountSession.activeCharacterId()); if (profile == null) continue; ServerPlayer member = server.getPlayerList().getPlayer(entry.getKey()); if (member == null) continue; RUN_RUNTIME.leave(member); sendCharacterProfile(member, profile); member.sendSystemMessage(Component.literal("폐봉수대를 완료했습니다. 경험치 500과 공략 보상을 획득했습니다.")); }
		})).exceptionally(error -> { LOGGER.error("Failed to complete run.", error); return null; });
	}

	private static void failRunAfterFinalDeath(ServerPlayer player, MinecraftServer server) {
		RunRuntime.DeathResult death = RUN_RUNTIME.recordDeath(player);
		if (death.runId() == null || death.canRespawn() || !death.partyDefeated()) return;
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.accountSession == null) return;
		AccountSession session = state.accountSession;
		CompletableFuture.supplyAsync(() -> { try { return RUNS.finish(session.accountId(), session.connectionGeneration(), System.nanoTime() & Long.MAX_VALUE, death.runId(), false); } catch (Exception exception) { throw new IllegalStateException(exception); } }).thenAccept(run -> server.execute(() -> player.sendSystemMessage(Component.literal("부활 횟수를 모두 사용해 폐봉수대 공략에 실패했습니다.")))).exceptionally(error -> { LOGGER.error("Failed to close run after final death.", error); return null; });
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
			float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * (1.0 + (10 + rank * 10) / 100.0));
			player.swing(InteractionHand.MAIN_HAND, true);
			((ServerLevel) player.level()).sendParticles(ParticleTypes.SWEEP_ATTACK, player.getX(), player.getY() + 1.0, player.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
			for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(3.0), target -> target != player && player.hasLineOfSight(target) && target.position().subtract(player.position()).normalize().dot(player.getLookAngle()) > 0.25)) {
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
		player.setDeltaMovement(direction.x, player.getDeltaMovement().y, direction.z);
		player.hurtMarked = true;
		player.swing(InteractionHand.MAIN_HAND, true);
		((ServerLevel) player.level()).sendParticles(ParticleTypes.CRIT, player.getX(), player.getY() + 1.0, player.getZ(), 12, 0.35, 0.15, 0.35, 0.1);
		player.playSound(SoundEvents.PLAYER_ATTACK_STRONG, 1.0F, 1.15F);
		float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * (0.85 + (rank - 1) * 0.075));
		LivingEntity target = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(3.0), entity -> entity != player && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(player.getLookAngle()) > 0.35).stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
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
		float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * (1.25 + (rank - 1) * 0.1125));
		for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(3.0), entity -> entity != player && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(player.getLookAngle()) > 0.25)) {
			rpgSkillDamage(player, target, damage);
			target.setDeltaMovement(target.getDeltaMovement().x, 0.45 + rank * 0.05, target.getDeltaMovement().z);
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
		player.removeEffect(MobEffects.SLOWNESS);
		player.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 40 + rank * 20, 0, false, true, true));
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
		float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * (1.15 + (rank - 1) * 0.10));
		for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(12.0), entity -> entity != player && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(direction) > 0.96)) rpgSkillDamage(player, target, damage);
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
		float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * (1.15 + (rank - 1) * 0.10));
		for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(10.0), entity -> entity != player && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(direction) > 0.96)) rpgSkillDamage(player, target, damage);
	}

	private static void useAmbush(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.activeProfile == null || !"ROGUE".equals(state.activeProfile.profession())) return;
		int rank = state.skillRanks.getOrDefault("skill.rogue.ambush", 0);
		if (rank < 1) { player.sendSystemMessage(Component.literal("급습을 먼저 습득하세요.")); return; }
		CombatRuntime.SweepResult result = COMBAT.useAmbush(player.getUUID(), currentTick);
		if (result != CombatRuntime.SweepResult.USED) { player.sendSystemMessage(Component.literal(result == CombatRuntime.SweepResult.STAMINA ? "기력이 부족합니다." : "급습을 아직 사용할 수 없습니다.")); return; }
		var direction = player.getLookAngle().normalize();
		player.setDeltaMovement(direction.x * 1.15, player.getDeltaMovement().y, direction.z * 1.15);
		player.hurtMarked = true;
		player.swing(InteractionHand.MAIN_HAND, true);
		ServerLevel level = (ServerLevel) player.level();
		level.sendParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + 1.0, player.getZ(), 18, 0.35, 0.2, 0.35, 0.04);
		player.playSound(SoundEvents.PLAYER_ATTACK_CRIT, 0.9F, 1.4F);
		float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * (1.15 + (rank - 1) * 0.10));
		LivingEntity target = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(3.0), entity -> entity != player && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(direction) > 0.35).stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
		if (target != null) {
			if (target.getLookAngle().normalize().dot(player.position().subtract(target.position()).normalize()) < 0) damage *= 1.2F;
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
		player.setDeltaMovement(-direction.x * 0.9, player.getDeltaMovement().y, -direction.z * 0.9);
		player.hurtMarked = true;
		ServerLevel level = (ServerLevel) player.level();
		level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 1.0, player.getZ(), 10, 0.3, 0.1, 0.3, 0.05);
		player.playSound(SoundEvents.ARROW_SHOOT, 0.9F, 1.25F);
		float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * (0.85 + (rank - 1) * 0.075));
		LivingEntity target = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(10.0), entity -> entity != player && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(direction) > 0.96).stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
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
		float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * (0.70 + (rank - 1) * 0.0875));
		LivingEntity target = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(10.0), entity -> entity != player && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(direction) > 0.96).stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
		if (target != null) {
			rpgSkillDamage(player, target, damage);
			target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 35 + rank * 5, 4, false, true, true));
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
		for (LivingEntity target : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(5.0), entity -> entity != player)) target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40 + rank * 10, 0, false, true, true));
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
		float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE) * (0.90 + (rank - 1) * 0.10));
		LivingEntity target = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(3.0), entity -> entity != player && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(direction) > 0.35).stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
		if (target != null) { rpgSkillDamage(player, target, damage); target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 30 + rank * 10, 2, false, true, true)); level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 1.0, target.getZ(), 16, 0.25, 0.5, 0.25, 0.04); }
		player.playSound(SoundEvents.CHAIN_HIT, 0.9F, 0.9F);
	}

	private static void useEscape(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.activeProfile == null || !"ROGUE".equals(state.activeProfile.profession())) return;
		if (state.skillRanks.getOrDefault("skill.rogue.escape", 0) < 1) { player.sendSystemMessage(Component.literal("긴급 이탈을 먼저 습득하세요.")); return; }
		CombatRuntime.SweepResult result = COMBAT.useEscape(player.getUUID(), currentTick);
		if (result != CombatRuntime.SweepResult.USED) { player.sendSystemMessage(Component.literal(result == CombatRuntime.SweepResult.STAMINA ? "기력이 부족합니다." : "긴급 이탈을 아직 사용할 수 없습니다.")); return; }
		var direction = player.getLookAngle().normalize();
		player.setDeltaMovement(-direction.x * 1.15, player.getDeltaMovement().y, -direction.z * 1.15);
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
		LivingEntity target = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(12.0), entity -> entity != player && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(player.getLookAngle()) > 0.96).stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
		if (target != null) { target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 80 + rank * 20, 0, false, true, true)); ((ServerLevel) player.level()).sendParticles(ParticleTypes.GLOW, target.getX(), target.getY() + 1.0, target.getZ(), 16, 0.3, 0.5, 0.3, 0.02); player.playSound(SoundEvents.ARROW_HIT_PLAYER, 0.8F, 1.2F); }
	}

	private static void useBindingShot(ServerPlayer player) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state == null || state.activeProfile == null || !"ARCHER".equals(state.activeProfile.profession()) || state.skillRanks.getOrDefault("skill.archer.binding_shot", 0) < 1) return;
		if (COMBAT.useBindingShot(player.getUUID(), currentTick) != CombatRuntime.SweepResult.USED) return;
		LivingEntity target = player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(12.0), entity -> entity != player && player.hasLineOfSight(entity) && entity.position().subtract(player.position()).normalize().dot(player.getLookAngle()) > 0.96).stream().min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player))).orElse(null);
		if (target != null) { int rank=state.skillRanks.get("skill.archer.binding_shot"); rpgSkillDamage(player, target, (float)(player.getAttributeValue(Attributes.ATTACK_DAMAGE)*(.7+(rank-1)*.075))); target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 20+rank*10, 2, false, true, true)); ((ServerLevel)player.level()).sendParticles(ParticleTypes.CRIT,target.getX(),target.getY()+1,target.getZ(),12,.2,.4,.2,.02); }
		player.playSound(SoundEvents.ARROW_HIT, .9F, .8F);
	}
	private static void useProtectiveTalisman(ServerPlayer player) {
		ConnectionState state=CONNECTIONS.get(player.getUUID()); if(state==null||state.activeProfile==null||!"MAGE".equals(state.activeProfile.profession()))return; int rank=state.skillRanks.getOrDefault("skill.mage.protective_talisman",0); if(rank<1||COMBAT.useProtectiveTalisman(player.getUUID(),currentTick)!=CombatRuntime.SweepResult.USED)return;
		player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 100+rank*20, rank/2, false,true,true)); ((ServerLevel)player.level()).sendParticles(ParticleTypes.ENCHANT,player.getX(),player.getY()+1,player.getZ(),30,.5,.8,.5,.05); player.playSound(SoundEvents.EVOKER_CAST_SPELL,.9F,.6F);
	}
	private static void useShortStep(ServerPlayer player) {
		ConnectionState state=CONNECTIONS.get(player.getUUID()); if(state==null||state.activeProfile==null||!"MAGE".equals(state.activeProfile.profession())||state.skillRanks.getOrDefault("skill.mage.short_step",0)<1||COMBAT.useShortStep(player.getUUID(),currentTick)!=CombatRuntime.SweepResult.USED)return;
		var d=player.getLookAngle().normalize(); player.setDeltaMovement(d.x*1.5,player.getDeltaMovement().y,d.z*1.5); player.hurtMarked=true; ((ServerLevel)player.level()).sendParticles(ParticleTypes.PORTAL,player.getX(),player.getY()+1,player.getZ(),24,.4,.5,.4,.08); player.playSound(SoundEvents.ENDERMAN_TELEPORT,.8F,1.3F);
	}

	private static void sendCombatStates(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			CombatRuntime.StaminaState stamina = COMBAT.stamina(player.getUUID(), currentTick);
			if (stamina != null) ServerPlayNetworking.send(player, new DalmaruPayloads.CombatStateResponse((int) Math.round(stamina.current()), (int) Math.round(stamina.maximum()), stamina.inCombat()));
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

	private static void sendCharacterProfile(ServerPlayer player, CharacterProfile profile) {
		ConnectionState state = CONNECTIONS.get(player.getUUID());
		if (state != null) state.activeProfile = profile;
		refreshCombat(player, profile);
		ServerPlayNetworking.send(player, new DalmaruPayloads.CharacterProfileResponse(profile.characterId(), profileData(profile)));
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
		CompletableFuture.supplyAsync(() -> { try { return QUESTS.accept(session.activeCharacterId()); } catch (Exception exception) { throw new IllegalStateException(exception); } })
			.thenAccept(message -> server.execute(() -> player.sendSystemMessage(Component.literal(message))));
	}

	private static void showQuestStatus(ServerPlayer player, MinecraftServer server) {
		AccountSession session = activeSession(player);
		if (session == null || session.activeCharacterId() == null) return;
		CompletableFuture.supplyAsync(() -> { try { return QUESTS.status(session.activeCharacterId()); } catch (Exception exception) { throw new IllegalStateException(exception); } })
			.thenAccept(message -> server.execute(() -> player.sendSystemMessage(Component.literal(message))));
	}

	private static void recordQuestZombieKill(ServerPlayer player, MinecraftServer server) {
		AccountSession session = activeSession(player);
		if (session == null || session.activeCharacterId() == null) return;
		CompletableFuture.supplyAsync(() -> { try { return QUESTS.recordZombieKill(session.activeCharacterId()); } catch (Exception exception) { throw new IllegalStateException(exception); } })
			.thenAccept(reward -> { if (reward != null) server.execute(() -> { player.sendSystemMessage(Component.literal(reward.message())); if (reward.profile() != null) sendCharacterProfile(player, reward.profile()); }); });
	}

	private static void selectProfession(ServerPlayer player, MinecraftServer server, String value) {
		AccountSession session = activeSession(player);
		if (session == null || session.activeCharacterId() == null) { player.sendSystemMessage(Component.literal("먼저 달마루 캐릭터를 선택하세요.")); return; }
		CharacterProfession profession;
		try { profession = CharacterProfession.valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
		catch (IllegalArgumentException exception) { player.sendSystemMessage(Component.literal("직업: WARRIOR, ARCHER, MAGE, ROGUE")); return; }
		long requestId = System.nanoTime() & Long.MAX_VALUE;
		CompletableFuture.supplyAsync(() -> { try { return CHARACTERS.selectProfession(session.accountId(), session.connectionGeneration(), requestId, session.activeCharacterId(), profession); } catch (Exception exception) { throw new IllegalStateException(exception); } })
			.thenAccept(saved -> server.execute(() -> player.sendSystemMessage(Component.literal(saved.koreanName() + " 전직을 완료했습니다. 주력 능력치: " + saved.primaryStat()))))
			.exceptionally(error -> { server.execute(() -> player.sendSystemMessage(Component.literal("전직 조건을 충족하지 못했습니다. 레벨 10과 전직 의뢰 완료가 필요합니다."))); return null; });
	}

	private static void sendCharacterStatInvestment(ServerPlayNetworking.Context context, long requestId, UUID characterId, boolean accepted, boolean replayed, String status, CharacterProfile profile) {
		if (accepted && profile != null) stateFor(context.player().getUUID()).activeProfile = profile;
		if (accepted && profile != null) refreshCombat(context.player(), profile);
		DalmaruPayloads.CharacterProfileData data = profile == null ? new DalmaruPayloads.CharacterProfileData(0, 0, 0, 0, 0, 0, 0, 0, false) : profileData(profile);
		ServerPlayNetworking.send(context.player(), new DalmaruPayloads.CharacterStatInvestmentResponse(requestId, characterId, accepted, replayed, status, data));
	}

	private static void sendCharacterStatReset(ServerPlayNetworking.Context context, long requestId, UUID characterId, boolean accepted, boolean replayed, String status, CharacterProfile profile) {
		if (accepted && profile != null) stateFor(context.player().getUUID()).activeProfile = profile;
		if (accepted && profile != null) refreshCombat(context.player(), profile);
		DalmaruPayloads.CharacterProfileData data = profile == null ? new DalmaruPayloads.CharacterProfileData(0, 0, 0, 0, 0, 0, 0, 0, false) : profileData(profile);
		ServerPlayNetworking.send(context.player(), new DalmaruPayloads.CharacterStatResetResponse(requestId, characterId, accepted, replayed, status, data));
	}

	private static void sendCharacterProfession(ServerPlayNetworking.Context context, long requestId, boolean accepted, String status, CharacterProfile profile) {
		if (accepted && profile != null) stateFor(context.player().getUUID()).activeProfile = profile;
		if (accepted && profile != null) refreshCombat(context.player(), profile);
		DalmaruPayloads.CharacterProfileData data = profile == null ? new DalmaruPayloads.CharacterProfileData(0, 0, 0, 0, 0, 0, 0, 0, false) : profileData(profile);
		ServerPlayNetworking.send(context.player(), new DalmaruPayloads.CharacterProfessionResponse(requestId, accepted, status, data));
	}

	private static void sendSkillLearn(ServerPlayNetworking.Context context, long requestId, boolean accepted, String status, CharacterProfile profile) {
		if (accepted && profile != null) stateFor(context.player().getUUID()).activeProfile = profile;
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
		private CharacterProfile activeProfile;
		private long equipmentRevision;
		private Map<String, Integer> skillRanks = new HashMap<>();
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
	private record LearnedSkill(int rank, CharacterProfile profile) { }
}
