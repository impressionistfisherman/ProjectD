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
    private final Map<UUID,Map<String,Integer>> skillRanks=new HashMap<>();
    private final Map<UUID,LivingEntity> controlled=new HashMap<>();
    private final Map<UUID,Long> staggerResistance=new HashMap<>();
    private static final Identifier CONTROL_SPEED=Identifier.fromNamespaceAndPath("dalmaru","control_speed");
    private final CombatEffects effects=new CombatEffects();
    private final Map<UUID,DefenseState> defenseStates=new HashMap<>();
    private final Map<UUID,Long> evasiveUntil=new HashMap<>();
    private static final Identifier GUARD_SPEED=Identifier.fromNamespaceAndPath("dalmaru","guard_speed");
    private static final Identifier EVASIVE_SPEED=Identifier.fromNamespaceAndPath("dalmaru","evasive_speed");
    private java.util.function.Function<UUID,kr.dalmaru.content.ContentCatalog> contentSource=player->kr.dalmaru.content.ContentCatalog.current();
    public void contentSource(java.util.function.Function<UUID,kr.dalmaru.content.ContentCatalog> source){contentSource=java.util.Objects.requireNonNull(source);}
    private java.util.function.BiConsumer<ServerPlayer,kr.dalmaru.run.RunContribution.Action> contribution=(player,action)->{};
    private java.util.function.Consumer<ServerPlayer> playerInput=player->{};
    public void contributions(java.util.function.BiConsumer<ServerPlayer,kr.dalmaru.run.RunContribution.Action> listener,java.util.function.Consumer<ServerPlayer> input){contribution=java.util.Objects.requireNonNull(listener);playerInput=java.util.Objects.requireNonNull(input);}
    private java.util.function.Consumer<UUID> skillUsed=player->{};
    private java.util.function.Consumer<ServerPlayer> receivedHit=player->{};
    public void mechanicActions(java.util.function.Consumer<UUID> skill,java.util.function.Consumer<ServerPlayer> hit){skillUsed=java.util.Objects.requireNonNull(skill);receivedHit=java.util.Objects.requireNonNull(hit);}
    /** Boss hooks (P4-S04): damage multiplier by target and one actual rising-strike posture hit. */
    @FunctionalInterface public interface BossPostureHit{boolean hit(UUID boss,UUID attacker,double amount);}
    private java.util.function.ToDoubleFunction<UUID> bossDamageTaken=id->1;
    private BossPostureHit bossPostureHit=(boss,attacker,amount)->false;
    public void bossHooks(java.util.function.ToDoubleFunction<UUID> damageTaken,BossPostureHit postureHit){bossDamageTaken=java.util.Objects.requireNonNull(damageTaken);bossPostureHit=java.util.Objects.requireNonNull(postureHit);}
    public kr.dalmaru.content.ContentCatalog content(UUID player){return contentSource.apply(player);}
    public kr.dalmaru.content.ContentCatalog.Skill skill(UUID player,String id){return content(player).skill(id);}
    public DefenseState defenseState(UUID player){return defenseStates.computeIfAbsent(player,id->new DefenseState());}
    public boolean hasShield(UUID player){var snapshot=equipment.get(player);return snapshot!=null&&PilotEquipment.hasShield(snapshot.records,content(player));}
    public boolean canGuard(UUID player){var snapshot=equipment.get(player);return snapshot!=null&&PilotEquipment.canGuard(snapshot.records,content(player));}
    public void guard(ServerPlayer player,boolean enabled,long tick){if(enabled)playerInput.accept(player);defenseState(player.getUUID()).guard(enabled&&canGuard(player.getUUID()),tick);movementEffects(player,tick);}
    public boolean dodge(ServerPlayer player,long tick){boolean used=managed(player)&&defenseState(player.getUUID()).dodge(tick,player.onGround()&&player.isAlive()&&!player.isSpectator());if(used)playerInput.accept(player);return used;}
    public void attackStarted(ServerPlayer player){playerInput.accept(player);defenseState(player.getUUID()).attack();}
    public void revived(ServerPlayer player,double staminaRatio,boolean combat,long tick){
        RuntimeState state=states.get(player.getUUID());if(state!=null){state.stamina=state.maximumStamina*staminaRatio;if(!combat)state.lastCombatTick=Long.MIN_VALUE;}
        effects.clear(player.getUUID());if(combat)defenseState(player.getUUID()).revive(tick);
        resetBranchOnDeath(player.getUUID());
    }
    public void movementEffects(ServerPlayer player,long tick){
        var speed=player.getAttribute(Attributes.MOVEMENT_SPEED);if(speed==null)return;
        boolean staggered=defenseState(player.getUUID()).staggered(tick);boolean guard=hasShield(player.getUUID())&&defenseState(player.getUUID()).guarding(tick);
        if(guard||staggered)speed.addOrUpdateTransientModifier(new AttributeModifier(GUARD_SPEED,staggered?-1:-.5,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));else speed.removeModifier(GUARD_SPEED);
        double bonus=evasiveUntil.getOrDefault(player.getUUID(),0L)>tick?skill(player.getUUID(),"skill.rogue.evasive_motion").parameter("speedBonus",rank(player.getUUID(),"skill.rogue.evasive_motion")):0;
        if(bonus>0)speed.addOrUpdateTransientModifier(new AttributeModifier(EVASIVE_SPEED,bonus,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));else speed.removeModifier(EVASIVE_SPEED);
    }
    public double protect(ServerPlayer player,DamageSource source,double damage,long tick,boolean validCombat){
        var defense=defenseState(player.getUUID());
        if(validCombat&&defense.dodgeImmune(tick)&&damage>0)evasiveUntil.put(player.getUUID(),tick+(long)skill(player.getUUID(),"skill.rogue.evasive_motion").parameter("durationTicks",1));
        var origin=source.getSourcePosition();boolean front=origin!=null&&player.getLookAngle().normalize().dot(origin.subtract(player.position()).normalize())>=.5;
        boolean blockable=source.getEntity()!=null&&!source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_SHIELD);
        double cost=damage>=180?60:damage>=130?40:damage>=100?20:10;
        double reduced=defense.reduce(damage,tick,blockable,front,hasShield(player.getUUID()),cost,resistsStagger(player.getUUID(),tick));
        if(defense.takePrecise())preciseDefense(player.getUUID(),tick);
        double beforeShield=reduced;
        reduced=effects.absorb(player.getUUID(),reduced,tick);
        if(validCombat&&reduced<beforeShield)receivedHit.accept(player);
        if(validCombat&&damage>0&&reduced<damage){if(!defense.immune(tick)||defense.dodgeImmune(tick))recordValidHit(player,tick);if(source.getEntity() instanceof ServerPlayer attacker)recordValidHit(attacker,tick);}
        movementEffects(player,tick);return reduced;
    }
    public void ranks(UUID player,Map<String,Integer> ranks){skillRanks.put(player,Map.copyOf(ranks));}
    private int rank(UUID player,String id){return skillRanks.getOrDefault(player,Map.of()).getOrDefault(id,0);}
    public CombatEffects effects(){return effects;}
    public int aimTicks(ServerPlayer player,int ticks){return (int)Math.floor(ticks/(1-skill(player.getUUID(),"skill.archer.breath_control").parameter("aimReduction",rank(player.getUUID(),"skill.archer.breath_control"))));}
    public int remainingCombatTicks(UUID player,long tick){RuntimeState state=states.get(player);return state==null||state.lastCombatTick==Long.MIN_VALUE?0:(int)Math.max(0,state.lastCombatTick+200-tick);}
    public int cooldown(UUID player,String skill,long tick){
        Map<UUID,Long> cooldown=switch(skill){
            case "skill.warrior.sweep"->sweepCooldowns;case "skill.warrior.advance"->advanceCooldowns;case "skill.warrior.rising_strike"->risingStrikeCooldowns;case "skill.warrior.battle_cry"->battleCryCooldowns;
            case "skill.archer.piercing_shot"->piercingShotCooldowns;case "skill.archer.retreat_shot"->retreatShotCooldowns;case "skill.archer.tracking_mark"->trackingMarkCooldowns;case "skill.archer.binding_shot"->bindingShotCooldowns;
            case "skill.mage.spirit_talisman"->spiritTalismanCooldowns;case "skill.mage.binding_talisman"->bindingTalismanCooldowns;case "skill.mage.protective_talisman"->protectiveTalismanCooldowns;case "skill.mage.short_step"->shortStepCooldowns;
            case "skill.rogue.ambush"->ambushCooldowns;case "skill.rogue.smoke_bomb"->smokeBombCooldowns;case "skill.rogue.shackle_strike"->shackleStrikeCooldowns;case "skill.rogue.escape"->escapeCooldowns;
            default->null;
        };return cooldown==null?0:(int)Math.max(0,cooldown.getOrDefault(player,0L)-tick);
    }
    public void control(ServerPlayer caster,LivingEntity target,CombatRules.TargetKind kind,boolean talisman,int rank,long tick){
        var definition=skill(caster.getUUID(),talisman?"skill.mage.binding_talisman":"skill.archer.binding_shot");boolean normal=kind==CombatRules.TargetKind.NORMAL;
        effects.control(target.getUUID(),(talisman?"binding_talisman:":"binding_shot:")+caster.getUUID(),definition.parameter(normal?"normalSlow":"restrictedSlow",1),tick+Math.round(definition.parameter(normal?"normalTicks":"restrictedTicks",rank)));
        controlled.put(target.getUUID(),target);recordValidHit(caster,tick);updateControl(target,tick);
    }
    private void updateControl(LivingEntity target,long tick){
        var speed=target.getAttribute(Attributes.MOVEMENT_SPEED);if(speed==null)return;
        double slow=effects.slow(target.getUUID(),tick);
        if(slow==0)speed.removeModifier(CONTROL_SPEED);
        else speed.addOrUpdateTransientModifier(new AttributeModifier(CONTROL_SPEED,-slow,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }
    public void tickEffects(long tick){
        effects.expire(tick);
        for(var target:new java.util.ArrayList<>(controlled.values())){
            updateControl(target,tick);
            if(!target.isAlive()||effects.slow(target.getUUID(),tick)==0)controlled.remove(target.getUUID());
        }
        staggerResistance.values().removeIf(until->until<=tick);
    }
    public boolean cleanse(ServerPlayer player,long tick){
        boolean removed=effects.cleanseOne(player.getUUID(),tick);
        if(!removed)removed=player.removeEffect(net.minecraft.world.effect.MobEffects.SLOWNESS);
        updateControl(player,tick);if(removed)contribution.accept(player,kr.dalmaru.run.RunContribution.Action.EFFECT);return removed;
    }
    public void battleResistance(ServerPlayer player,int rank,long tick){staggerResistance.put(player.getUUID(),tick+(long)skill(player.getUUID(),"skill.warrior.battle_cry").parameter("resistanceTicks",rank));}
    public boolean resistsStagger(UUID player,long tick){return staggerResistance.getOrDefault(player,0L)>tick;}
    public void protectiveTalisman(ServerPlayer player,int rank,long tick){
        RuntimeState state=states.get(player.getUUID());if(state==null)return;
        double attack=state.gear.attack();
        double mastery=skill(player.getUUID(),"skill.mage.talisman_mastery").parameter("damageBonus",rank(player.getUUID(),"skill.mage.talisman_mastery"));
        double amount=(skill(player.getUUID(),"skill.mage.protective_talisman").parameter("shieldBase",rank)+attack*skill(player.getUUID(),"skill.mage.protective_talisman").parameter("shieldAttackMultiplier",rank))*state.strengthMultiplier*(1+mastery);
        double change=effects.shield(player.getUUID(),"protective_talisman:"+player.getUUID(),amount,CombatUnits.toRpg(player.getMaxHealth()),tick,(long)skill(player.getUUID(),"skill.mage.protective_talisman").parameter("shieldTicks",rank));
        if(change>0){if(stamina(player.getUUID(),tick).inCombat())recordValidHit(player,tick);else contribution.accept(player,kr.dalmaru.run.RunContribution.Action.EFFECT);}
    }
    public double absorb(ServerPlayer player,double damage,long tick){return effects.absorb(player.getUUID(),damage,tick);}
    public int targetedAttack(ServerPlayer player,LivingEntity target,double multiplier,int defense,boolean basic,long tick){
        RuntimeState state=states.get(player.getUUID());if(state==null)return 0;
        if(basic){
            if(tick<state.nextBasicAttackTick)return 0;state.nextBasicAttackTick=tick+state.gear.basicTicks();
            multiplier*=state.gear.basicMultiplier();
            String mastery=state.profession.equals("WARRIOR")?"skill.warrior.martial_mastery":state.profession.equals("ARCHER")?"skill.archer.bow_crossbow_mastery":"";
            if(!mastery.isBlank())multiplier*=1+skill(player.getUUID(),mastery).parameter("damageBonus",rank(player.getUUID(),mastery));
        }
        if(!basic&&state.profession.equals("MAGE"))multiplier*=1+skill(player.getUUID(),"skill.mage.talisman_mastery").parameter("damageBonus",rank(player.getUUID(),"skill.mage.talisman_mastery"));
        if(state.profession.equals("ROGUE")&&(effects.slow(target.getUUID(),tick)>0||target.hasEffect(net.minecraft.world.effect.MobEffects.SLOWNESS)||target.getLookAngle().normalize().dot(player.position().subtract(target.position()).normalize())<.5))
            multiplier*=1+skill(player.getUUID(),"skill.rogue.expose_weakness").parameter("damageBonus",rank(player.getUUID(),"skill.rogue.expose_weakness"));
        multiplier*=1+effects.markBonus(player.getUUID(),target.getUUID(),tick);
        double attack=state.gear.attack()>0?state.gear.attack():CombatUnits.toRpg(player.getAttribute(Attributes.ATTACK_DAMAGE).getBaseValue());
        int damage=CombatCalculator.damage(attack,state.strengthMultiplier,multiplier,CombatRules.critical(.05,player.getRandom().nextDouble()),defense);
        double taken=bossDamageTaken.applyAsDouble(target.getUUID());
        if(taken!=1)damage=(int)Math.round(damage*taken);
        var mechanics=content(player.getUUID()).mechanics();
        if(!basic&&damage>0&&mechanics!=null&&risingStrikeCooldowns.getOrDefault(player.getUUID(),0L)-skill(player.getUUID(),"skill.warrior.rising_strike").cooldownTicks()==tick)
            bossPostureHit.hit(target.getUUID(),player.getUUID(),mechanics.boss().risingStrikePosture(rank(player.getUUID(),"skill.warrior.rising_strike")));
        return damage;
    }
    private final java.util.Set<UUID> disconnected=new java.util.HashSet<>();
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
	private final Map<UUID, Long> branchSkillCooldowns = new HashMap<>();

	public void refresh(ServerPlayer player, CharacterProfile profile) {
		EquipmentSnapshot snapshot = equipment.get(player.getUUID());
		if (snapshot != null && !snapshot.characterId.equals(profile.characterId())) { equipment.remove(player.getUUID()); snapshot = null; }
		PilotEquipment.Stats gear = snapshot == null ? PilotEquipment.Stats.EMPTY : PilotEquipment.equipped(profile, snapshot.records,content(player.getUUID()));
		CharacterProfile effective = new CharacterProfile(profile.characterId(), profile.level(), profile.experience(), profile.unspentStatPoints(),
			profile.strength(), profile.dexterity(), profile.arcana(), profile.vitality() + gear.vitality(), profile.spirit() + gear.spirit(),
			profile.freeResetAvailable(), profile.unspentSkillPoints(), profile.profession());
		CombatStats base = CombatCalculator.derive(effective, CombatCalculator.primaryStat(profile) + gear.primary());
        CombatStats stats=new CombatStats(base.maximumHealth()+(int)skill(player.getUUID(),"skill.warrior.body_training").parameter("maximumHealth",rank(player.getUUID(),"skill.warrior.body_training")),base.maximumStamina(),
            base.staminaRegenerationPerSecond()+skill(player.getUUID(),"skill.mage.breath_meditation").parameter("staminaRegeneration",rank(player.getUUID(),"skill.mage.breath_meditation")),base.strengthMultiplier());
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
        refreshed.profession=profile.profession();states.put(player.getUUID(), refreshed);
	}

	public void equipment(ServerPlayer player, CharacterProfile profile, java.util.List<EquipmentRecord> records) {
		PilotEquipment.equipped(profile, records,content(player.getUUID()));
		equipment.put(player.getUUID(), new EquipmentSnapshot(profile.characterId(), java.util.List.copyOf(records)));
		bindBranch(player.getUUID(), profile.characterId(), weaponProfile(records), false);
		refresh(player, profile);
	}
	public void clearEquipment(ServerPlayer player, CharacterProfile profile) {
		equipment.remove(player.getUUID());
		bindBranch(player.getUUID(), profile.characterId(), "", false);
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
		hurtRpg(player, boss==null?player.damageSources().magic():player.damageSources().mobAttack(boss), damage);
	}
	public void hurtLinger(UUID playerId, LivingEntity body, LivingEntity source, float amount, boolean magic) {
		RuntimeState state = states.get(playerId);
		int defense = state == null ? 0 : state.gear.defense(magic);
		int damage = CombatCalculator.damage((double) amount, 1, 1, false, defense);
		hurtRpg(body, source==null?body.damageSources().magic():body.damageSources().mobAttack(source), damage);
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
		if (state != null) {state.lastCombatTick = tick;contribution.accept(player,kr.dalmaru.run.RunContribution.Action.EFFECT);grantBranchHit(player.getUUID());}
	}
    public void recordIncomingHit(ServerPlayer player,long tick){
        receivedHit.accept(player);
        RuntimeState state=states.get(player.getUUID());
        if(state!=null){state.lastCombatTick=tick;contribution.accept(player,kr.dalmaru.run.RunContribution.Action.RECEIVED_HIT);}
    }
	public SweepResult useSweep(UUID playerId, long tick) {
        var definition=skill(playerId,"skill.warrior.sweep");
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < definition.energy()) return SweepResult.STAMINA;
		if (tick < sweepCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		defenseState(playerId).attack();state.stamina -= definition.energy();

		sweepCooldowns.put(playerId, tick + definition.cooldownTicks());
		skillUsed.accept(playerId);return SweepResult.USED;
	}

	public SweepResult useAdvance(UUID playerId, long tick) {
        var definition=skill(playerId,"skill.warrior.advance");
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < definition.energy()) return SweepResult.STAMINA;
		if (tick < advanceCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		defenseState(playerId).attack();state.stamina -= definition.energy();

		advanceCooldowns.put(playerId, tick + definition.cooldownTicks());
		skillUsed.accept(playerId);return SweepResult.USED;
	}

	public SweepResult useRisingStrike(UUID playerId, long tick) {
        var definition=skill(playerId,"skill.warrior.rising_strike");
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < definition.energy()) return SweepResult.STAMINA;
		if (tick < risingStrikeCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		defenseState(playerId).attack();state.stamina -= definition.energy();

		risingStrikeCooldowns.put(playerId, tick + definition.cooldownTicks());
		skillUsed.accept(playerId);return SweepResult.USED;
	}

	public SweepResult useBattleCry(UUID playerId, long tick) {
        var definition=skill(playerId,"skill.warrior.battle_cry");
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < definition.energy()) return SweepResult.STAMINA;
		if (tick < battleCryCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		defenseState(playerId).attack();state.stamina -= definition.energy();

		battleCryCooldowns.put(playerId, tick + definition.cooldownTicks());
		skillUsed.accept(playerId);return SweepResult.USED;
	}

	public SweepResult usePiercingShot(UUID playerId, long tick) {
        var definition=skill(playerId,"skill.archer.piercing_shot");
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < definition.energy()) return SweepResult.STAMINA;
		if (tick < piercingShotCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		defenseState(playerId).attack();state.stamina -= definition.energy();

		piercingShotCooldowns.put(playerId, tick + definition.cooldownTicks());
		skillUsed.accept(playerId);return SweepResult.USED;
	}

	public SweepResult useSpiritTalisman(UUID playerId, long tick) {
        var definition=skill(playerId,"skill.mage.spirit_talisman");
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < definition.energy()) return SweepResult.STAMINA;
		if (tick < spiritTalismanCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		defenseState(playerId).attack();state.stamina -= definition.energy();

		spiritTalismanCooldowns.put(playerId, tick + definition.cooldownTicks());
		skillUsed.accept(playerId);return SweepResult.USED;
	}

	public SweepResult useAmbush(UUID playerId, long tick) {
        var definition=skill(playerId,"skill.rogue.ambush");
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < definition.energy()) return SweepResult.STAMINA;
		if (tick < ambushCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		defenseState(playerId).attack();state.stamina -= definition.energy();

		ambushCooldowns.put(playerId, tick + definition.cooldownTicks());
		skillUsed.accept(playerId);return SweepResult.USED;
	}

	public SweepResult useRetreatShot(UUID playerId, long tick) {
        var definition=skill(playerId,"skill.archer.retreat_shot");
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < definition.energy()) return SweepResult.STAMINA;
		if (tick < retreatShotCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		defenseState(playerId).attack();state.stamina -= definition.energy();

		retreatShotCooldowns.put(playerId, tick + definition.cooldownTicks());
		skillUsed.accept(playerId);return SweepResult.USED;
	}

	public SweepResult useBindingTalisman(UUID playerId, long tick) {
        var definition=skill(playerId,"skill.mage.binding_talisman");
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < definition.energy()) return SweepResult.STAMINA;
		if (tick < bindingTalismanCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		defenseState(playerId).attack();state.stamina -= definition.energy();

		bindingTalismanCooldowns.put(playerId, tick + definition.cooldownTicks());
		skillUsed.accept(playerId);return SweepResult.USED;
	}

	public SweepResult useSmokeBomb(UUID playerId, long tick) {
        var definition=skill(playerId,"skill.rogue.smoke_bomb");
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < definition.energy()) return SweepResult.STAMINA;
		if (tick < smokeBombCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		defenseState(playerId).attack();state.stamina -= definition.energy();

		smokeBombCooldowns.put(playerId, tick + definition.cooldownTicks());
		skillUsed.accept(playerId);return SweepResult.USED;
	}

	public SweepResult useShackleStrike(UUID playerId, long tick) {
        var definition=skill(playerId,"skill.rogue.shackle_strike");
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < definition.energy()) return SweepResult.STAMINA;
		if (tick < shackleStrikeCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		defenseState(playerId).attack();state.stamina -= definition.energy();

		shackleStrikeCooldowns.put(playerId, tick + definition.cooldownTicks());
		skillUsed.accept(playerId);return SweepResult.USED;
	}

	public SweepResult useEscape(UUID playerId, long tick) {
        var definition=skill(playerId,"skill.rogue.escape");
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < definition.energy()) return SweepResult.STAMINA;
		if (tick < escapeCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		defenseState(playerId).attack();state.stamina -= definition.energy();

		escapeCooldowns.put(playerId, tick + definition.cooldownTicks());
		skillUsed.accept(playerId);return SweepResult.USED;
	}

	public SweepResult useTrackingMark(UUID playerId, long tick) {
        var definition=skill(playerId,"skill.archer.tracking_mark");
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < definition.energy()) return SweepResult.STAMINA;
		if (tick < trackingMarkCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		defenseState(playerId).attack();state.stamina -= definition.energy();

		trackingMarkCooldowns.put(playerId, tick + definition.cooldownTicks());
		skillUsed.accept(playerId);return SweepResult.USED;
	}
	public SweepResult useBindingShot(UUID playerId, long tick) {
        var definition=skill(playerId,"skill.archer.binding_shot");
		RuntimeState state = states.get(playerId);
		if (state == null) return SweepResult.UNAVAILABLE;
		if (state.stamina < definition.energy()) return SweepResult.STAMINA;
		if (tick < bindingShotCooldowns.getOrDefault(playerId, 0L)) return SweepResult.COOLDOWN;
		defenseState(playerId).attack();state.stamina -= definition.energy();  bindingShotCooldowns.put(playerId, tick + definition.cooldownTicks()); skillUsed.accept(playerId);return SweepResult.USED;
	}
	public SweepResult useProtectiveTalisman(UUID playerId, long tick) {
        var definition=skill(playerId,"skill.mage.protective_talisman"); return use(playerId, tick, definition.energy(), definition.cooldownTicks(), protectiveTalismanCooldowns); }
	public SweepResult useShortStep(UUID playerId, long tick) {
        var definition=skill(playerId,"skill.mage.short_step"); return use(playerId, tick, definition.energy(), definition.cooldownTicks(), shortStepCooldowns); }
	private SweepResult use(UUID playerId, long tick, int cost, long cooldown, Map<UUID, Long> cooldowns) {
		RuntimeState state=states.get(playerId); if(state==null)return SweepResult.UNAVAILABLE; if(state.stamina<cost)return SweepResult.STAMINA; if(tick<cooldowns.getOrDefault(playerId,0L))return SweepResult.COOLDOWN; defenseState(playerId).attack();state.stamina-=cost;  cooldowns.put(playerId,tick+cooldown); skillUsed.accept(playerId);return SweepResult.USED;
	}

	public void tick(long tick) {
		if (tick % 20 != 0) return;
		for (var entry : states.entrySet()) {
            if(disconnected.contains(entry.getKey()))continue;
            RuntimeState state=entry.getValue();
			state.stamina = Math.min(state.maximumStamina, state.stamina + state.staminaRegenerationPerSecond);
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

	}

    public void disconnect(UUID playerId){disconnected.add(playerId);}
    public void reconnect(UUID playerId){disconnected.remove(playerId);}
    public void shiftCooldowns(UUID playerId,long pausedTicks) {
        if(pausedTicks<=0)return;
        var defense=defenseStates.get(playerId);if(defense!=null)defense.shift(pausedTicks);
        for(var cooldown:java.util.List.of(sweepCooldowns,advanceCooldowns,risingStrikeCooldowns,battleCryCooldowns,piercingShotCooldowns,spiritTalismanCooldowns,ambushCooldowns,retreatShotCooldowns,bindingTalismanCooldowns,smokeBombCooldowns,shackleStrikeCooldowns,escapeCooldowns,trackingMarkCooldowns,bindingShotCooldowns,protectiveTalismanCooldowns,shortStepCooldowns,branchSkillCooldowns))cooldown.computeIfPresent(playerId,(id,until)->Math.addExact(until,pausedTicks));
        RuntimeState state=states.get(playerId);
        if(state!=null){state.nextBasicAttackTick=Math.addExact(state.nextBasicAttackTick,pausedTicks);if(state.lastCombatTick!=Long.MIN_VALUE)state.lastCombatTick=Math.addExact(state.lastCombatTick,pausedTicks);}
    }
    public void remove(UUID playerId) {
        states.remove(playerId);equipment.remove(playerId);disconnected.remove(playerId);skillRanks.remove(playerId);effects.clear(playerId);staggerResistance.remove(playerId);defenseStates.remove(playerId);evasiveUntil.remove(playerId);branches.remove(playerId);
        for(var cooldown:java.util.List.of(sweepCooldowns,advanceCooldowns,risingStrikeCooldowns,battleCryCooldowns,piercingShotCooldowns,spiritTalismanCooldowns,ambushCooldowns,retreatShotCooldowns,bindingTalismanCooldowns,smokeBombCooldowns,shackleStrikeCooldowns,escapeCooldowns,trackingMarkCooldowns,bindingShotCooldowns,protectiveTalismanCooldowns,shortStepCooldowns,branchSkillCooldowns))cooldown.remove(playerId);
    }

	public StaminaState stamina(UUID playerId, long tick) {
		RuntimeState state = states.get(playerId);
		return state == null ? null : new StaminaState(state.stamina, state.maximumStamina, state.lastCombatTick != Long.MIN_VALUE && tick - state.lastCombatTick < COMBAT_TIMEOUT_TICKS);
	}


	private static final class RuntimeState {
		private final int maximumStamina;
		private final double staminaRegenerationPerSecond;
		private double stamina;
		private long lastCombatTick;
		private long nextBasicAttackTick;
		private final PilotEquipment.Stats gear;
		private final double strengthMultiplier;
        private String profession="";
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
	public record BranchResourceSnapshot(String resourceKind,int current,int maximum){
		public BranchResourceSnapshot{if(resourceKind==null||current<0||maximum<current||resourceKind.isEmpty()&&(current!=0||maximum!=0))throw new IllegalArgumentException("Invalid branch resource snapshot");}
	}
	public enum ResourceOutcome { APPLIED, RESOURCE, STAMINA, COOLDOWN, UNAVAILABLE }
	private final Map<UUID,BranchPool> branches=new HashMap<>();
	private static final class BranchPool { private UUID character; private String profile=""; private int current; }
	public void bindBranch(UUID player,UUID character,String weaponProfile,boolean ensureStamina){
		java.util.Objects.requireNonNull(player);java.util.Objects.requireNonNull(character);
		String profile=weaponProfile==null?"":weaponProfile;
		var rule=profile.isEmpty()?kr.dalmaru.content.ContentCatalog.BranchResource.NONE:content(player).branchResource(profile);
		BranchPool pool=branches.get(player);
		if(pool==null||!character.equals(pool.character)){
			pool=new BranchPool();pool.character=character;pool.profile=profile;pool.current=rule.initial();branches.put(player,pool);branchSkillCooldowns.remove(player);
		}else if(!profile.equals(pool.profile)){pool.profile=profile;pool.current=rule.initial();branchSkillCooldowns.remove(player);}
		if(ensureStamina)states.computeIfAbsent(player,id->new RuntimeState(100,100,0,Long.MIN_VALUE,PilotEquipment.Stats.EMPTY,1));
	}
	public BranchResourceSnapshot branchResourceSnapshot(UUID player){
		BranchPool pool=branches.get(player);
		if(pool==null||pool.profile.isEmpty())return new BranchResourceSnapshot("",0,0);
		var rule=content(player).branchResource(pool.profile);
		if(rule.unused())return new BranchResourceSnapshot("",0,0);
		return new BranchResourceSnapshot(rule.resourceKind(),pool.current,rule.maximum());
	}
	public ResourceOutcome grantBranchHit(UUID player){return gainBranch(player,true);}
	public ResourceOutcome preciseDefense(UUID player,long tick){
		if(tick<0)return ResourceOutcome.UNAVAILABLE;
		BranchPool pool=branches.get(player);if(pool==null)return ResourceOutcome.UNAVAILABLE;
		var rule=content(player).branchResource(pool.profile);if(rule.unused())return ResourceOutcome.UNAVAILABLE;
		int cost=rule.preciseCost();if(pool.current<cost)return ResourceOutcome.RESOURCE;
		int next=pool.current-cost;
		try{next=Math.addExact(next,rule.gainOnPreciseDefense());}catch(ArithmeticException error){return ResourceOutcome.RESOURCE;}
		if(next<0)return ResourceOutcome.RESOURCE;
		pool.current=Math.min(rule.maximum(),next);return ResourceOutcome.APPLIED;
	}
	public ResourceOutcome spendBranchSkill(UUID player,long tick){
		BranchPool pool=branches.get(player);if(pool==null)return ResourceOutcome.UNAVAILABLE;
		var rule=content(player).branchResource(pool.profile);if(rule.unused())return ResourceOutcome.UNAVAILABLE;
		if(tick<branchSkillCooldowns.getOrDefault(player,0L))return ResourceOutcome.COOLDOWN;
		RuntimeState state=states.get(player);if(state==null)return ResourceOutcome.UNAVAILABLE;
		if(state.stamina<rule.staminaSpend())return ResourceOutcome.STAMINA;
		if(pool.current<rule.skillSpend())return ResourceOutcome.RESOURCE;
		state.stamina-=rule.staminaSpend();pool.current-=rule.skillSpend();
		if(rule.skillCooldownTicks()>0)branchSkillCooldowns.put(player,Math.addExact(tick,rule.skillCooldownTicks()));
		return ResourceOutcome.APPLIED;
	}
	public void resetBranchOnDeath(UUID player){
		BranchPool pool=branches.get(player);if(pool==null||pool.profile.isEmpty())return;
		pool.current=content(player).branchResource(pool.profile).initial();
	}
	public void dropPreservedBranch(UUID player){branches.remove(player);}
	private ResourceOutcome gainBranch(UUID player,boolean hit){
		BranchPool pool=branches.get(player);if(pool==null||pool.profile.isEmpty())return ResourceOutcome.UNAVAILABLE;
		var rule=content(player).branchResource(pool.profile);if(rule.unused())return ResourceOutcome.UNAVAILABLE;
		int amount=hit?rule.gainOnHit():rule.gainOnPreciseDefense();
		int next;try{next=Math.addExact(pool.current,amount);}catch(ArithmeticException error){return ResourceOutcome.RESOURCE;}
		if(next<0)return ResourceOutcome.RESOURCE;
		pool.current=Math.min(rule.maximum(),next);return ResourceOutcome.APPLIED;
	}
	private static String weaponProfile(java.util.List<EquipmentRecord> records){
		for(EquipmentRecord item:records){
			if(item.slotId()!=0)continue;
			for(String profile:java.util.List.of("two_handed_sword","sword_shield","crossbow","bow","ritual_sword","fan","dual_axes","pirate_sword"))
				if(item.itemId().contains("."+profile+"."))return profile;
		}
		return "";
	}
	public enum SweepResult { USED, STAMINA, COOLDOWN, UNAVAILABLE }
}
