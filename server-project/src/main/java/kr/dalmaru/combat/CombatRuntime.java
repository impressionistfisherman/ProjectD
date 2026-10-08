package kr.dalmaru.combat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kr.dalmaru.account.CharacterProfile;
import kr.dalmaru.equipment.EquipmentRecord;
import kr.dalmaru.equipment.PilotEquipment;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Ephemeral server state. Persistent RPG facts remain in PostgreSQL profiles. */
public final class CombatRuntime {
	private static final long COMBAT_TIMEOUT_TICKS = 200;
	private static final Identifier ATTACK_DAMAGE_ID = Identifier.fromNamespaceAndPath("dalmaru", "profile_attack_damage");
	private static final Identifier MAX_HEALTH_ID = Identifier.fromNamespaceAndPath("dalmaru", "profile_max_health");
	private final Map<UUID, RuntimeState> states = new HashMap<>();
	private final Map<UUID, EquipmentSnapshot> equipment = new HashMap<>();
	private static final ThreadLocal<LivingEntity> RPG_TARGET = new ThreadLocal<>();
	private final Map<UUID, Long> sweepCooldowns = new HashMap<>();
	private final Map<UUID, Long> advanceCooldowns = new HashMap<>();
	private final Map<UUID, Long> risingStrikeCooldowns = new HashMap<>();
	private final Map<UUID, Long> battleCryCooldowns = new HashMap<>();
	private final Map<UUID, Long> piercingShotCooldowns = new HashMap<>();
	private final Map<UUID, Long> spiritTalismanCooldowns = new HashMap<>();
	private final Map<UUID, Long> ambushCooldowns = new HashMap<>();
	private final Map<UUID, Long> retreatShotCooldowns = new HashMap<>();
	private final Map<UUID, Long> bindingTalismanCooldowns = new HashMap<>();
	private final Map<UUID, Long> smokeBombCooldowns = new HashMap<>();
	private final Map<UUID, Long> shackleStrikeCooldowns = new HashMap<>();
	private final Map<UUID, Long> escapeCooldowns = new HashMap<>();
	private final Map<UUID, Long> trackingMarkCooldowns = new HashMap<>();
	private final Map<UUID, Long> bindingShotCooldowns = new HashMap<>();
	private final Map<UUID, Long> protectiveTalismanCooldowns = new HashMap<>();
	private final Map<UUID, Long> shortStepCooldowns = new HashMap<>();

	public void refresh(ServerPlayer player, CharacterProfile profile) {
		EquipmentSnapshot snapshot = equipment.get(player.getUUID());
		if (snapshot != null && !snapshot.characterId.equals(profile.characterId())) { equipment.remove(player.getUUID()); snapshot = null; }
		PilotEquipment.Stats gear = snapshot == null ? PilotEquipment.Stats.EMPTY : PilotEquipment.equipped(profile, snapshot.records);
		CharacterProfile effective = new CharacterProfile(profile.characterId(), profile.level(), profile.experience(), profile.unspentStatPoints(),
			profile.strength(), profile.dexterity(), profile.arcana(), profile.vitality() + gear.vitality(), profile.spirit() + gear.spirit(),
			profile.freeResetAvailable(), profile.unspentSkillPoints(), profile.profession());
		CombatStats stats = CombatCalculator.derive(effective, primaryStat(profile) + gear.primary());
		var attackDamage = player.getAttribute(Attributes.ATTACK_DAMAGE);
		if (attackDamage != null) attackDamage.addOrReplacePermanentModifier(new AttributeModifier(ATTACK_DAMAGE_ID, stats.strengthMultiplier() - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		var maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
		if (maxHealth != null) {
			double healthRatio = player.getMaxHealth() == 0 ? 1.0 : player.getHealth() / player.getMaxHealth();
			maxHealth.addOrReplacePermanentModifier(new AttributeModifier(MAX_HEALTH_ID, CombatUnits.maximumHealth(stats.maximumHealth()) - 20.0, AttributeModifier.Operation.ADD_VALUE));
			player.setHealth((float) Math.min(player.getMaxHealth(), Math.max(0, healthRatio * player.getMaxHealth())));
		}
		RuntimeState previous = states.get(player.getUUID());
		double ratio = previous == null || previous.maximumStamina == 0 ? 1.0 : previous.stamina / previous.maximumStamina;
		RuntimeState refreshed = new RuntimeState(stats.maximumStamina(), Math.min(stats.maximumStamina(), Math.max(0, ratio * stats.maximumStamina())), stats.staminaRegenerationPerSecond(), previous == null ? Long.MIN_VALUE : previous.lastCombatTick, gear, stats.strengthMultiplier());
		if (previous != null) refreshed.nextBasicAttackTick = previous.nextBasicAttackTick;
		states.put(player.getUUID(), refreshed);
	}

	public void equipment(ServerPlayer player, CharacterProfile profile, java.util.List<EquipmentRecord> records) {
		PilotEquipment.equipped(profile, records);
		equipment.put(player.getUUID(), new EquipmentSnapshot(profile.characterId(), java.util.List.copyOf(records)));
		refresh(player, profile);
	}
	public void clearEquipment(ServerPlayer player, CharacterProfile profile) {
		equipment.remove(player.getUUID());
		refresh(player, profile);
	}
	public boolean managed(ServerPlayer player) { return states.containsKey(player.getUUID()); }
	public boolean magical(ServerPlayer player) { RuntimeState state = states.get(player.getUUID()); return state != null && state.gear.magical(); }
	public int defense(ServerPlayer player, boolean magic) { RuntimeState state = states.get(player.getUUID()); return state == null ? 0 : state.gear.defense(magic); }
	public int attack(ServerPlayer player, double multiplier, int defense) {
		RuntimeState state = states.get(player.getUUID());
		if (state == null) return 0;
		// Undefined gear grants no weapon stats; retain only the engine's bare-hand base.
		double attack = state.gear.attack() > 0 ? state.gear.attack() : CombatUnits.toRpg(player.getAttribute(Attributes.ATTACK_DAMAGE).getBaseValue());
		return CombatCalculator.damage(attack, state.strengthMultiplier, multiplier, false, defense);
	}
	public int basicAttack(ServerPlayer player, double multiplier, int defense, long tick) {
		RuntimeState state = states.get(player.getUUID());
		if (state == null || tick < state.nextBasicAttackTick) return 0;
		state.nextBasicAttackTick = tick + state.gear.basicTicks();
		return attack(player, multiplier * state.gear.basicMultiplier(), defense);
	}
	public void bossDamage(ServerPlayer player, LivingEntity boss, float amount, boolean magic) {
		int damage = CombatCalculator.damage((double) amount, 1, 1, false, defense(player, magic));
		hurtRpg(player, player.damageSources().mobAttack(boss), damage);
	}
	public static boolean explicitRpgDamage(LivingEntity target) { return RPG_TARGET.get() == target; }
	public static boolean hurtRpg(LivingEntity target, DamageSource source, double amount) {
		if (amount <= 0) return false;
		LivingEntity previous = RPG_TARGET.get();
		try {
			RPG_TARGET.set(target);
			return target.hurtServer((ServerLevel) target.level(), source, CombatUnits.toEngine(amount));
		} finally {
			if (previous == null) RPG_TARGET.remove(); else RPG_TARGET.set(previous);
		}
	}

	public void recordValidHit(ServerPlayer player, long tick) {
		RuntimeState state = states.get(player.getUUID());
		if (state != null) state.lastCombatTick = tick;
	}
	public SweepResult useSweep(UUID playerId, long tick) {
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < 20) return SweepResult.STAMINA;
		if (tick < sweepCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		state.stamina -= 20;
		state.lastCombatTick = tick;
		sweepCooldowns.put(playerId, tick + 90);
		return SweepResult.USED;
	}

	public SweepResult useAdvance(UUID playerId, long tick) {
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < 20) return SweepResult.STAMINA;
		if (tick < advanceCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		state.stamina -= 20;
		state.lastCombatTick = tick;
		advanceCooldowns.put(playerId, tick + 140);
		return SweepResult.USED;
	}

	public SweepResult useRisingStrike(UUID playerId, long tick) {
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < 30) return SweepResult.STAMINA;
		if (tick < risingStrikeCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		state.stamina -= 30;
		state.lastCombatTick = tick;
		risingStrikeCooldowns.put(playerId, tick + 180);
		return SweepResult.USED;
	}

	public SweepResult useBattleCry(UUID playerId, long tick) {
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < 25) return SweepResult.STAMINA;
		if (tick < battleCryCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		state.stamina -= 25;
		state.lastCombatTick = tick;
		battleCryCooldowns.put(playerId, tick + 360);
		return SweepResult.USED;
	}

	public SweepResult usePiercingShot(UUID playerId, long tick) {
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < 22) return SweepResult.STAMINA;
		if (tick < piercingShotCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		state.stamina -= 22;
		state.lastCombatTick = tick;
		piercingShotCooldowns.put(playerId, tick + 100);
		return SweepResult.USED;
	}

	public SweepResult useSpiritTalisman(UUID playerId, long tick) {
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < 20) return SweepResult.STAMINA;
		if (tick < spiritTalismanCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		state.stamina -= 20;
		state.lastCombatTick = tick;
		spiritTalismanCooldowns.put(playerId, tick + 80);
		return SweepResult.USED;
	}

	public SweepResult useAmbush(UUID playerId, long tick) {
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < 22) return SweepResult.STAMINA;
		if (tick < ambushCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		state.stamina -= 22;
		state.lastCombatTick = tick;
		ambushCooldowns.put(playerId, tick + 100);
		return SweepResult.USED;
	}

	public SweepResult useRetreatShot(UUID playerId, long tick) {
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < 18) return SweepResult.STAMINA;
		if (tick < retreatShotCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		state.stamina -= 18;
		state.lastCombatTick = tick;
		retreatShotCooldowns.put(playerId, tick + 140);
		return SweepResult.USED;
	}

	public SweepResult useBindingTalisman(UUID playerId, long tick) {
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < 30) return SweepResult.STAMINA;
		if (tick < bindingTalismanCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		state.stamina -= 30;
		state.lastCombatTick = tick;
		bindingTalismanCooldowns.put(playerId, tick + 240);
		return SweepResult.USED;
	}

	public SweepResult useSmokeBomb(UUID playerId, long tick) {
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < 25) return SweepResult.STAMINA;
		if (tick < smokeBombCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		state.stamina -= 25;
		state.lastCombatTick = tick;
		smokeBombCooldowns.put(playerId, tick + 320);
		return SweepResult.USED;
	}

	public SweepResult useShackleStrike(UUID playerId, long tick) {
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < 20) return SweepResult.STAMINA;
		if (tick < shackleStrikeCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		state.stamina -= 20;
		state.lastCombatTick = tick;
		shackleStrikeCooldowns.put(playerId, tick + 140);
		return SweepResult.USED;
	}

	public SweepResult useEscape(UUID playerId, long tick) {
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < 20) return SweepResult.STAMINA;
		if (tick < escapeCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		state.stamina -= 20;
		state.lastCombatTick = tick;
		escapeCooldowns.put(playerId, tick + 240);
		return SweepResult.USED;
	}

	public SweepResult useTrackingMark(UUID playerId, long tick) {
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < 20) return SweepResult.STAMINA;
		if (tick < trackingMarkCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		state.stamina -= 20;
		state.lastCombatTick = tick;
		trackingMarkCooldowns.put(playerId, tick + 240);
		return SweepResult.USED;
	}
	public SweepResult useBindingShot(UUID playerId, long tick) {
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < 25) return SweepResult.STAMINA;
		if (tick < bindingShotCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		state.stamina -= 25; state.lastCombatTick = tick; bindingShotCooldowns.put(playerId, tick + 280); return SweepResult.USED;
	}
	public SweepResult useProtectiveTalisman(UUID playerId, long tick) { return use(playerId, tick, 35, 280, protectiveTalismanCooldowns); }
	public SweepResult useShortStep(UUID playerId, long tick) { return use(playerId, tick, 25, 240, shortStepCooldowns); }
	private SweepResult use(UUID playerId, long tick, int cost, long cooldown, Map<UUID, Long> cooldowns) {
		RuntimeState state=states.get(playerId); if(state==null)return SweepResult.UNAVAILABLE; if(state.stamina<cost)return SweepResult.STAMINA; if(tick<cooldowns.getOrDefault(playerId,0L))return SweepResult.COOLDOWN; state.stamina-=cost; state.lastCombatTick=tick; cooldowns.put(playerId,tick+cooldown); return SweepResult.USED;
	}

	public void tick(long tick) {
		if (tick % 20 != 0) return;
		for (RuntimeState state : states.values()) {
			if (state.lastCombatTick != Long.MIN_VALUE && tick - state.lastCombatTick >= COMBAT_TIMEOUT_TICKS) state.stamina = Math.min(state.maximumStamina, state.stamina + state.staminaRegenerationPerSecond);
		}
	}

	/** Respawn builds a new entity with base attributes only; carry the profile modifiers over and respawn at full health. */
	public void restoreAfterRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
		for (var attribute : java.util.List.of(Attributes.ATTACK_DAMAGE, Attributes.MAX_HEALTH)) {
			var from = oldPlayer.getAttribute(attribute);
			var to = newPlayer.getAttribute(attribute);
			if (from == null || to == null) continue;
			for (Identifier id : java.util.List.of(ATTACK_DAMAGE_ID, MAX_HEALTH_ID)) {
				AttributeModifier modifier = from.getModifier(id);
				if (modifier != null) to.addOrReplacePermanentModifier(modifier);
			}
		}
		newPlayer.setHealth(newPlayer.getMaxHealth());
	}

	public void remove(UUID playerId) { states.remove(playerId); equipment.remove(playerId); sweepCooldowns.remove(playerId); }
	public StaminaState stamina(UUID playerId, long tick) {
		RuntimeState state = states.get(playerId);
		return state == null ? null : new StaminaState(state.stamina, state.maximumStamina, state.lastCombatTick != Long.MIN_VALUE && tick - state.lastCombatTick < COMBAT_TIMEOUT_TICKS);
	}

	private static int primaryStat(CharacterProfile profile) {
		return switch (profile.profession()) {
			case "WARRIOR" -> profile.strength();
			case "ARCHER" -> profile.dexterity();
			case "MAGE" -> profile.arcana();
			case "ROGUE" -> Math.max(profile.strength(), profile.dexterity());
			default -> Math.max(profile.strength(), Math.max(profile.dexterity(), profile.arcana()));
		};
	}

	private static final class RuntimeState {
		private final int maximumStamina;
		private final double staminaRegenerationPerSecond;
		private double stamina;
		private long lastCombatTick;
		private long nextBasicAttackTick;
		private final PilotEquipment.Stats gear;
		private final double strengthMultiplier;
		private RuntimeState(int maximumStamina, double stamina, double staminaRegenerationPerSecond, long lastCombatTick, PilotEquipment.Stats gear, double strengthMultiplier) {
			this.maximumStamina = maximumStamina;
			this.stamina = stamina;
			this.staminaRegenerationPerSecond = staminaRegenerationPerSecond;
			this.lastCombatTick = lastCombatTick;
			this.gear = gear;
			this.strengthMultiplier = strengthMultiplier;
		}
	}
	private record EquipmentSnapshot(UUID characterId, java.util.List<EquipmentRecord> records) { }
	public record StaminaState(double current, double maximum, boolean inCombat) { }
	public enum SweepResult { USED, STAMINA, COOLDOWN, UNAVAILABLE }
}
