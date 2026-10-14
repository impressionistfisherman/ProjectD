package kr.dalmaru.network;

import kr.dalmaru.DalmaruMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DalmaruPayloads {
	private DalmaruPayloads() { }

	public record Hello(int protocolVersion) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Hello> TYPE = DalmaruPayloads.type("hello");
		public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Hello::protocolVersion, Hello::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record Welcome(int protocolVersion) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Welcome> TYPE = DalmaruPayloads.type("welcome");
		public static final StreamCodec<RegistryFriendlyByteBuf, Welcome> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Welcome::protocolVersion, Welcome::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record ProbeRequest(long requestId) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ProbeRequest> TYPE = DalmaruPayloads.type("probe_request");
		public static final StreamCodec<RegistryFriendlyByteBuf, ProbeRequest> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, ProbeRequest::requestId, ProbeRequest::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record ProbeResponse(long requestId, boolean rateLimited) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<ProbeResponse> TYPE = DalmaruPayloads.type("probe_response");
		public static final StreamCodec<RegistryFriendlyByteBuf, ProbeResponse> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, ProbeResponse::requestId,
			ByteBufCodecs.BOOL, ProbeResponse::rateLimited,
			ProbeResponse::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record CharacterSelectionReady(boolean ready) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CharacterSelectionReady> TYPE = DalmaruPayloads.type("character_selection_ready");
		public static final StreamCodec<RegistryFriendlyByteBuf, CharacterSelectionReady> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, CharacterSelectionReady::ready, CharacterSelectionReady::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record CharacterListRequest(long requestId) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CharacterListRequest> TYPE = DalmaruPayloads.type("character_list_request");
		public static final StreamCodec<RegistryFriendlyByteBuf, CharacterListRequest> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, CharacterListRequest::requestId, CharacterListRequest::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record CharacterSummary(UUID characterId, int slot, String displayName, boolean active) {
		public static final StreamCodec<RegistryFriendlyByteBuf, CharacterSummary> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, summary -> summary.characterId().getMostSignificantBits(),
			ByteBufCodecs.VAR_LONG, summary -> summary.characterId().getLeastSignificantBits(),
			ByteBufCodecs.VAR_INT, CharacterSummary::slot,
			ByteBufCodecs.STRING_UTF8, CharacterSummary::displayName,
			ByteBufCodecs.BOOL, CharacterSummary::active,
			(most, least, slot, name, active) -> new CharacterSummary(new UUID(most, least), slot, name, active));
	}

	public record CharacterListResponse(long requestId, String status, List<CharacterSummary> characters) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CharacterListResponse> TYPE = DalmaruPayloads.type("character_list_response");
		public static final StreamCodec<RegistryFriendlyByteBuf, CharacterListResponse> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, CharacterListResponse::requestId,
			ByteBufCodecs.STRING_UTF8, CharacterListResponse::status,
			ByteBufCodecs.collection(ArrayList::new, CharacterSummary.CODEC), CharacterListResponse::characters,
			CharacterListResponse::new);

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record CharacterSelectRequest(long requestId, UUID characterId) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CharacterSelectRequest> TYPE = DalmaruPayloads.type("character_select_request");
		public static final StreamCodec<RegistryFriendlyByteBuf, CharacterSelectRequest> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, CharacterSelectRequest::requestId,
			ByteBufCodecs.VAR_LONG, request -> request.characterId().getMostSignificantBits(),
			ByteBufCodecs.VAR_LONG, request -> request.characterId().getLeastSignificantBits(),
			(requestId, most, least) -> new CharacterSelectRequest(requestId, new UUID(most, least)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record CharacterSelectResponse(long requestId, UUID characterId, boolean accepted, boolean replayed, String status) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CharacterSelectResponse> TYPE = DalmaruPayloads.type("character_select_response");
		public static final StreamCodec<RegistryFriendlyByteBuf, CharacterSelectResponse> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, CharacterSelectResponse::requestId,
			ByteBufCodecs.VAR_LONG, response -> response.characterId().getMostSignificantBits(),
			ByteBufCodecs.VAR_LONG, response -> response.characterId().getLeastSignificantBits(),
			ByteBufCodecs.BOOL, CharacterSelectResponse::accepted,
			ByteBufCodecs.BOOL, CharacterSelectResponse::replayed,
			ByteBufCodecs.STRING_UTF8, CharacterSelectResponse::status,
			(requestId, most, least, accepted, replayed, status) -> new CharacterSelectResponse(requestId, new UUID(most, least), accepted, replayed, status));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record CharacterProfileData(int level, long experience, int unspentStatPoints, int strength, int dexterity, int arcana, int vitality, int spirit, boolean freeResetAvailable, int unspentSkillPoints, String profession) {
		public CharacterProfileData(int level, long experience, int unspentStatPoints, int strength, int dexterity, int arcana, int vitality, int spirit, boolean freeResetAvailable) { this(level, experience, unspentStatPoints, strength, dexterity, arcana, vitality, spirit, freeResetAvailable, 0, ""); }
		public static final StreamCodec<RegistryFriendlyByteBuf, CharacterProfileData> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, CharacterProfileData::level,
			ByteBufCodecs.VAR_LONG, CharacterProfileData::experience,
			ByteBufCodecs.VAR_INT, CharacterProfileData::unspentStatPoints,
			ByteBufCodecs.VAR_INT, CharacterProfileData::strength,
			ByteBufCodecs.VAR_INT, CharacterProfileData::dexterity,
			ByteBufCodecs.VAR_INT, CharacterProfileData::arcana,
			ByteBufCodecs.VAR_INT, CharacterProfileData::vitality,
			ByteBufCodecs.VAR_INT, CharacterProfileData::spirit,
			ByteBufCodecs.BOOL, CharacterProfileData::freeResetAvailable,
			ByteBufCodecs.VAR_INT, CharacterProfileData::unspentSkillPoints,
			ByteBufCodecs.STRING_UTF8, CharacterProfileData::profession,
			CharacterProfileData::new);
	}

	public record CharacterProfileResponse(UUID characterId, CharacterProfileData profile) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CharacterProfileResponse> TYPE = DalmaruPayloads.type("character_profile_response");
		public static final StreamCodec<RegistryFriendlyByteBuf, CharacterProfileResponse> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, response -> response.characterId().getMostSignificantBits(),
			ByteBufCodecs.VAR_LONG, response -> response.characterId().getLeastSignificantBits(),
			CharacterProfileData.CODEC, CharacterProfileResponse::profile,
			(most, least, profile) -> new CharacterProfileResponse(new UUID(most, least), profile));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record CharacterStatInvestmentRequest(long requestId, UUID characterId, int statId, int amount) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CharacterStatInvestmentRequest> TYPE = DalmaruPayloads.type("character_stat_investment_request");
		public static final StreamCodec<RegistryFriendlyByteBuf, CharacterStatInvestmentRequest> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, CharacterStatInvestmentRequest::requestId,
			ByteBufCodecs.VAR_LONG, request -> request.characterId().getMostSignificantBits(),
			ByteBufCodecs.VAR_LONG, request -> request.characterId().getLeastSignificantBits(),
			ByteBufCodecs.VAR_INT, CharacterStatInvestmentRequest::statId,
			ByteBufCodecs.VAR_INT, CharacterStatInvestmentRequest::amount,
			(requestId, most, least, statId, amount) -> new CharacterStatInvestmentRequest(requestId, new UUID(most, least), statId, amount));
		@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record CharacterStatInvestmentResponse(long requestId, UUID characterId, boolean accepted, boolean replayed, String status, CharacterProfileData profile) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CharacterStatInvestmentResponse> TYPE = DalmaruPayloads.type("character_stat_investment_response");
		public static final StreamCodec<RegistryFriendlyByteBuf, CharacterStatInvestmentResponse> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_LONG, CharacterStatInvestmentResponse::requestId,
			ByteBufCodecs.VAR_LONG, response -> response.characterId().getMostSignificantBits(),
			ByteBufCodecs.VAR_LONG, response -> response.characterId().getLeastSignificantBits(),
			ByteBufCodecs.BOOL, CharacterStatInvestmentResponse::accepted,
			ByteBufCodecs.BOOL, CharacterStatInvestmentResponse::replayed,
			ByteBufCodecs.STRING_UTF8, CharacterStatInvestmentResponse::status,
			CharacterProfileData.CODEC, CharacterStatInvestmentResponse::profile,
			(requestId, most, least, accepted, replayed, status, profile) -> new CharacterStatInvestmentResponse(requestId, new UUID(most, least), accepted, replayed, status, profile));
		@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record CharacterStatResetRequest(long requestId, UUID characterId) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CharacterStatResetRequest> TYPE = DalmaruPayloads.type("character_stat_reset_request");
		public static final StreamCodec<RegistryFriendlyByteBuf, CharacterStatResetRequest> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, CharacterStatResetRequest::requestId, ByteBufCodecs.VAR_LONG, request -> request.characterId().getMostSignificantBits(), ByteBufCodecs.VAR_LONG, request -> request.characterId().getLeastSignificantBits(), (requestId, most, least) -> new CharacterStatResetRequest(requestId, new UUID(most, least)));
		@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

public record CharacterStatResetResponse(long requestId, UUID characterId, boolean accepted, boolean replayed, String status, CharacterProfileData profile) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CharacterStatResetResponse> TYPE = DalmaruPayloads.type("character_stat_reset_response");
		public static final StreamCodec<RegistryFriendlyByteBuf, CharacterStatResetResponse> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, CharacterStatResetResponse::requestId, ByteBufCodecs.VAR_LONG, response -> response.characterId().getMostSignificantBits(), ByteBufCodecs.VAR_LONG, response -> response.characterId().getLeastSignificantBits(), ByteBufCodecs.BOOL, CharacterStatResetResponse::accepted, ByteBufCodecs.BOOL, CharacterStatResetResponse::replayed, ByteBufCodecs.STRING_UTF8, CharacterStatResetResponse::status, CharacterProfileData.CODEC, CharacterStatResetResponse::profile, (requestId, most, least, accepted, replayed, status, profile) -> new CharacterStatResetResponse(requestId, new UUID(most, least), accepted, replayed, status, profile));
		@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record CharacterProfessionRequest(long requestId, String profession) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<CharacterProfessionRequest> TYPE = DalmaruPayloads.type("character_profession_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, CharacterProfessionRequest> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, CharacterProfessionRequest::requestId, ByteBufCodecs.STRING_UTF8, CharacterProfessionRequest::profession, CharacterProfessionRequest::new);
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record CharacterProfessionResponse(long requestId, boolean accepted, String status, CharacterProfileData profile) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<CharacterProfessionResponse> TYPE = DalmaruPayloads.type("character_profession_response");
	public static final StreamCodec<RegistryFriendlyByteBuf, CharacterProfessionResponse> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, CharacterProfessionResponse::requestId, ByteBufCodecs.BOOL, CharacterProfessionResponse::accepted, ByteBufCodecs.STRING_UTF8, CharacterProfessionResponse::status, CharacterProfileData.CODEC, CharacterProfessionResponse::profile, CharacterProfessionResponse::new);
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record QuestRequest(long requestId, boolean accept) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<QuestRequest> TYPE = DalmaruPayloads.type("quest_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, QuestRequest> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, QuestRequest::requestId, ByteBufCodecs.BOOL, QuestRequest::accept, QuestRequest::new);
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record QuestResponse(long requestId, String status) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<QuestResponse> TYPE = DalmaruPayloads.type("quest_response");
	public static final StreamCodec<RegistryFriendlyByteBuf, QuestResponse> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, QuestResponse::requestId, ByteBufCodecs.STRING_UTF8, QuestResponse::status, QuestResponse::new);
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record CombatStateResponse(int currentStamina,int maximumStamina,boolean inCombat,boolean combatMode,int remainingCombatTicks,List<Integer> cooldowns,List<String> slots,int dodgeCharges,int guardMeter) implements CustomPacketPayload {
    public CombatStateResponse(int current,int maximum,boolean combat){this(current,maximum,combat,combat,0,List.of(0,0,0,0,0),List.of("","","","",""),2,100);}
    public static final Type<CombatStateResponse> TYPE=DalmaruPayloads.type("combat_state_response");
    public static final StreamCodec<RegistryFriendlyByteBuf,CombatStateResponse> CODEC=new StreamCodec<>(){
        public CombatStateResponse decode(RegistryFriendlyByteBuf b){int current=b.readVarInt(),max=b.readVarInt();boolean combat=b.readBoolean(),mode=b.readBoolean();int remaining=b.readVarInt();var cooldowns=new ArrayList<Integer>();var slots=new ArrayList<String>();for(int i=0;i<5;i++){cooldowns.add(b.readVarInt());slots.add(b.readUtf(128));}return new CombatStateResponse(current,max,combat,mode,remaining,cooldowns,slots,b.readVarInt(),b.readVarInt());}
        public void encode(RegistryFriendlyByteBuf b,CombatStateResponse p){b.writeVarInt(p.currentStamina);b.writeVarInt(p.maximumStamina);b.writeBoolean(p.inCombat);b.writeBoolean(p.combatMode);b.writeVarInt(p.remainingCombatTicks);for(int i=0;i<5;i++){b.writeVarInt(p.cooldowns.get(i));b.writeUtf(p.slots.get(i));}b.writeVarInt(p.dodgeCharges);b.writeVarInt(p.guardMeter);}
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}

public record SweepRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<SweepRequest> TYPE = DalmaruPayloads.type("sweep_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, SweepRequest> CODEC = StreamCodec.unit(new SweepRequest());
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record AdvanceRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<AdvanceRequest> TYPE = DalmaruPayloads.type("advance_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, AdvanceRequest> CODEC = StreamCodec.unit(new AdvanceRequest());
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record RisingStrikeRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RisingStrikeRequest> TYPE = DalmaruPayloads.type("rising_strike_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, RisingStrikeRequest> CODEC = StreamCodec.unit(new RisingStrikeRequest());
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record BattleCryRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<BattleCryRequest> TYPE = DalmaruPayloads.type("battle_cry_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, BattleCryRequest> CODEC = StreamCodec.unit(new BattleCryRequest());
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record PiercingShotRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<PiercingShotRequest> TYPE = DalmaruPayloads.type("piercing_shot_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, PiercingShotRequest> CODEC = StreamCodec.unit(new PiercingShotRequest());
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record SpiritTalismanRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<SpiritTalismanRequest> TYPE = DalmaruPayloads.type("spirit_talisman_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, SpiritTalismanRequest> CODEC = StreamCodec.unit(new SpiritTalismanRequest());
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record AmbushRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<AmbushRequest> TYPE = DalmaruPayloads.type("ambush_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, AmbushRequest> CODEC = StreamCodec.unit(new AmbushRequest());
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record RetreatShotRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<RetreatShotRequest> TYPE = DalmaruPayloads.type("retreat_shot_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, RetreatShotRequest> CODEC = StreamCodec.unit(new RetreatShotRequest());
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record BindingTalismanRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<BindingTalismanRequest> TYPE = DalmaruPayloads.type("binding_talisman_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, BindingTalismanRequest> CODEC = StreamCodec.unit(new BindingTalismanRequest());
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record SmokeBombRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<SmokeBombRequest> TYPE = DalmaruPayloads.type("smoke_bomb_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, SmokeBombRequest> CODEC = StreamCodec.unit(new SmokeBombRequest());
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record ShackleStrikeRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ShackleStrikeRequest> TYPE = DalmaruPayloads.type("shackle_strike_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, ShackleStrikeRequest> CODEC = StreamCodec.unit(new ShackleStrikeRequest());
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record EscapeRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<EscapeRequest> TYPE = DalmaruPayloads.type("escape_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, EscapeRequest> CODEC = StreamCodec.unit(new EscapeRequest());
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record TrackingMarkRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<TrackingMarkRequest> TYPE = DalmaruPayloads.type("tracking_mark_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, TrackingMarkRequest> CODEC = StreamCodec.unit(new TrackingMarkRequest());
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record BindingShotRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<BindingShotRequest> TYPE = DalmaruPayloads.type("binding_shot_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, BindingShotRequest> CODEC = StreamCodec.unit(new BindingShotRequest());
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record ProtectiveTalismanRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ProtectiveTalismanRequest> TYPE = DalmaruPayloads.type("protective_talisman_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, ProtectiveTalismanRequest> CODEC = StreamCodec.unit(new ProtectiveTalismanRequest());
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record ShortStepRequest() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ShortStepRequest> TYPE = DalmaruPayloads.type("short_step_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, ShortStepRequest> CODEC = StreamCodec.unit(new ShortStepRequest());
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record EquipmentEnhanceRequest(long requestId, UUID equipmentId) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<EquipmentEnhanceRequest> TYPE = DalmaruPayloads.type("equipment_enhance_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, EquipmentEnhanceRequest> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, EquipmentEnhanceRequest::requestId, ByteBufCodecs.VAR_LONG, request -> request.equipmentId().getMostSignificantBits(), ByteBufCodecs.VAR_LONG, request -> request.equipmentId().getLeastSignificantBits(), (requestId, most, least) -> new EquipmentEnhanceRequest(requestId, new UUID(most, least)));
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record EquipmentEnhanceResponse(long requestId, boolean accepted, boolean success, int enhancement, int consecutiveFailures, String status) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<EquipmentEnhanceResponse> TYPE = DalmaruPayloads.type("equipment_enhance_response");
	public static final StreamCodec<RegistryFriendlyByteBuf, EquipmentEnhanceResponse> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, EquipmentEnhanceResponse::requestId, ByteBufCodecs.BOOL, EquipmentEnhanceResponse::accepted, ByteBufCodecs.BOOL, EquipmentEnhanceResponse::success, ByteBufCodecs.VAR_INT, EquipmentEnhanceResponse::enhancement, ByteBufCodecs.VAR_INT, EquipmentEnhanceResponse::consecutiveFailures, ByteBufCodecs.STRING_UTF8, EquipmentEnhanceResponse::status, EquipmentEnhanceResponse::new);
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

 public record EquipmentData(UUID equipmentId, int slotId, String itemId, int enhancement, int consecutiveFailures, boolean bound) { public static final StreamCodec<RegistryFriendlyByteBuf, EquipmentData> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, data -> data.equipmentId().getMostSignificantBits(), ByteBufCodecs.VAR_LONG, data -> data.equipmentId().getLeastSignificantBits(), ByteBufCodecs.VAR_INT, EquipmentData::slotId, ByteBufCodecs.STRING_UTF8, EquipmentData::itemId, ByteBufCodecs.VAR_INT, EquipmentData::enhancement, ByteBufCodecs.VAR_INT, EquipmentData::consecutiveFailures, ByteBufCodecs.BOOL, EquipmentData::bound, (most, least, slot, item, enhancement, failures, bound) -> new EquipmentData(new UUID(most, least), slot, item, enhancement, failures, bound)); }
 public record EquipmentListRequest(long requestId) implements CustomPacketPayload { public static final CustomPacketPayload.Type<EquipmentListRequest> TYPE = DalmaruPayloads.type("equipment_list_request"); public static final StreamCodec<RegistryFriendlyByteBuf, EquipmentListRequest> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, EquipmentListRequest::requestId, EquipmentListRequest::new); @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; } }
 public record EquipmentListResponse(long requestId, String status, List<EquipmentData> equipment) implements CustomPacketPayload { public static final CustomPacketPayload.Type<EquipmentListResponse> TYPE = DalmaruPayloads.type("equipment_list_response"); public static final StreamCodec<RegistryFriendlyByteBuf, EquipmentListResponse> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, EquipmentListResponse::requestId, ByteBufCodecs.STRING_UTF8, EquipmentListResponse::status, ByteBufCodecs.collection(ArrayList::new, EquipmentData.CODEC), EquipmentListResponse::equipment, EquipmentListResponse::new); @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; } }
 public record RunEnterRequest(long requestId, String difficulty) implements CustomPacketPayload { public static final CustomPacketPayload.Type<RunEnterRequest> TYPE = DalmaruPayloads.type("run_enter_request"); public static final StreamCodec<RegistryFriendlyByteBuf, RunEnterRequest> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, RunEnterRequest::requestId, ByteBufCodecs.STRING_UTF8, RunEnterRequest::difficulty, RunEnterRequest::new); @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; } }
 public record RunReviveState(boolean dead,int remainingRevives,int clickTicks,int autoTicks,boolean failed) implements CustomPacketPayload {
    public static final Type<RunReviveState> TYPE=DalmaruPayloads.type("run_revive_state");
    public static final StreamCodec<RegistryFriendlyByteBuf,RunReviveState> CODEC=StreamCodec.composite(
        ByteBufCodecs.BOOL,RunReviveState::dead,ByteBufCodecs.VAR_INT,RunReviveState::remainingRevives,
        ByteBufCodecs.VAR_INT,RunReviveState::clickTicks,ByteBufCodecs.VAR_INT,RunReviveState::autoTicks,
        ByteBufCodecs.BOOL,RunReviveState::failed,RunReviveState::new);
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
    public record RunResponse(long requestId, boolean accepted, UUID runId, int partySize, int scalingTier, String state, String status) implements CustomPacketPayload { public static final CustomPacketPayload.Type<RunResponse> TYPE = DalmaruPayloads.type("run_response"); public static final StreamCodec<RegistryFriendlyByteBuf, RunResponse> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, RunResponse::requestId, ByteBufCodecs.BOOL, RunResponse::accepted, ByteBufCodecs.VAR_LONG, response -> response.runId().getMostSignificantBits(), ByteBufCodecs.VAR_LONG, response -> response.runId().getLeastSignificantBits(), ByteBufCodecs.VAR_INT, RunResponse::partySize, ByteBufCodecs.VAR_INT, RunResponse::scalingTier, ByteBufCodecs.STRING_UTF8, RunResponse::state, ByteBufCodecs.STRING_UTF8, RunResponse::status, (requestId, accepted, most, least, partySize, tier, state, status) -> new RunResponse(requestId, accepted, new UUID(most, least), partySize, tier, state, status)); @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; } }
 public record SkillLearnRequest(long requestId, String skillId) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<SkillLearnRequest> TYPE = DalmaruPayloads.type("skill_learn_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, SkillLearnRequest> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, SkillLearnRequest::requestId, ByteBufCodecs.STRING_UTF8, SkillLearnRequest::skillId, SkillLearnRequest::new);
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record SkillLearnResponse(long requestId, boolean accepted, String status, CharacterProfileData profile) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<SkillLearnResponse> TYPE = DalmaruPayloads.type("skill_learn_response");
	public static final StreamCodec<RegistryFriendlyByteBuf, SkillLearnResponse> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, SkillLearnResponse::requestId, ByteBufCodecs.BOOL, SkillLearnResponse::accepted, ByteBufCodecs.STRING_UTF8, SkillLearnResponse::status, CharacterProfileData.CODEC, SkillLearnResponse::profile, SkillLearnResponse::new);
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record SkillRank(String skillId, int rank) {
	public static final StreamCodec<RegistryFriendlyByteBuf, SkillRank> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, SkillRank::skillId, ByteBufCodecs.VAR_INT, SkillRank::rank, SkillRank::new);
}

public record SkillRanksRequest(long requestId) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<SkillRanksRequest> TYPE = DalmaruPayloads.type("skill_ranks_request");
	public static final StreamCodec<RegistryFriendlyByteBuf, SkillRanksRequest> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, SkillRanksRequest::requestId, SkillRanksRequest::new);
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record SkillRanksResponse(long requestId, List<SkillRank> ranks) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<SkillRanksResponse> TYPE = DalmaruPayloads.type("skill_ranks_response");
	public static final StreamCodec<RegistryFriendlyByteBuf, SkillRanksResponse> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, SkillRanksResponse::requestId, ByteBufCodecs.collection(ArrayList::new, SkillRank.CODEC), SkillRanksResponse::ranks, SkillRanksResponse::new);
	@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}

public record CharacterCreateRequest(long requestId, int slot, String displayName) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CharacterCreateRequest> TYPE = DalmaruPayloads.type("character_create_request");
		public static final StreamCodec<RegistryFriendlyByteBuf, CharacterCreateRequest> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, CharacterCreateRequest::requestId, ByteBufCodecs.VAR_INT, CharacterCreateRequest::slot, ByteBufCodecs.STRING_UTF8, CharacterCreateRequest::displayName, CharacterCreateRequest::new);
		@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record CharacterCreateResponse(long requestId, UUID characterId, int slot, String displayName, boolean accepted, boolean replayed, String status) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<CharacterCreateResponse> TYPE = DalmaruPayloads.type("character_create_response");
		public static final StreamCodec<RegistryFriendlyByteBuf, CharacterCreateResponse> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, CharacterCreateResponse::requestId, ByteBufCodecs.VAR_LONG, response -> response.characterId().getMostSignificantBits(), ByteBufCodecs.VAR_LONG, response -> response.characterId().getLeastSignificantBits(), ByteBufCodecs.VAR_INT, CharacterCreateResponse::slot, ByteBufCodecs.STRING_UTF8, CharacterCreateResponse::displayName, ByteBufCodecs.BOOL, CharacterCreateResponse::accepted, ByteBufCodecs.BOOL, CharacterCreateResponse::replayed, ByteBufCodecs.STRING_UTF8, CharacterCreateResponse::status, (requestId, most, least, slot, name, accepted, replayed, status) -> new CharacterCreateResponse(requestId, new UUID(most, least), slot, name, accepted, replayed, status));
		@Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
		return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(DalmaruMod.MOD_ID, path));
	}
 public record LoadoutRequest(long requestId) implements CustomPacketPayload {
    public static final Type<LoadoutRequest> TYPE=DalmaruPayloads.type("loadout_request");
    public static final StreamCodec<RegistryFriendlyByteBuf,LoadoutRequest> CODEC=StreamCodec.composite(ByteBufCodecs.VAR_LONG,LoadoutRequest::requestId,LoadoutRequest::new);
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
 }
 public record LoadoutUpdate(long requestId,UUID characterId,long revision,List<String> slots) implements CustomPacketPayload {
    public static final Type<LoadoutUpdate> TYPE=DalmaruPayloads.type("loadout_update");
    public static final StreamCodec<RegistryFriendlyByteBuf,LoadoutUpdate> CODEC=new StreamCodec<>(){
        public LoadoutUpdate decode(RegistryFriendlyByteBuf b){long request=b.readVarLong();UUID character=b.readUUID();long revision=b.readVarLong();var slots=new ArrayList<String>();for(int i=0;i<5;i++)slots.add(b.readUtf(128));return new LoadoutUpdate(request,character,revision,slots);}
        public void encode(RegistryFriendlyByteBuf b,LoadoutUpdate p){if(p.slots.size()!=5)throw new IllegalArgumentException("Invalid slots");b.writeVarLong(p.requestId);b.writeUUID(p.characterId);b.writeVarLong(p.revision);p.slots.forEach(b::writeUtf);}
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
 }
 public record LoadoutResponse(long requestId,UUID characterId,long revision,List<String> slots,boolean editable,String status) implements CustomPacketPayload {
    public static final Type<LoadoutResponse> TYPE=DalmaruPayloads.type("loadout_response");
    public static final StreamCodec<RegistryFriendlyByteBuf,LoadoutResponse> CODEC=new StreamCodec<>(){
        public LoadoutResponse decode(RegistryFriendlyByteBuf b){long req=b.readVarLong();UUID character=b.readUUID();long revision=b.readVarLong();var slots=new ArrayList<String>();for(int i=0;i<5;i++)slots.add(b.readUtf(128));return new LoadoutResponse(req,character,revision,slots,b.readBoolean(),b.readUtf(512));}
        public void encode(RegistryFriendlyByteBuf b,LoadoutResponse p){b.writeVarLong(p.requestId);b.writeUUID(p.characterId);b.writeVarLong(p.revision);p.slots.forEach(b::writeUtf);b.writeBoolean(p.editable);b.writeUtf(p.status);}
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
 }
 public record SkillUseRequest(long requestId,UUID characterId,long revision,int slot) implements CustomPacketPayload {
    public static final Type<SkillUseRequest> TYPE=DalmaruPayloads.type("skill_use_request");
    public static final StreamCodec<RegistryFriendlyByteBuf,SkillUseRequest> CODEC=new StreamCodec<>(){
        public SkillUseRequest decode(RegistryFriendlyByteBuf b){return new SkillUseRequest(b.readVarLong(),b.readUUID(),b.readVarLong(),b.readVarInt());}
        public void encode(RegistryFriendlyByteBuf b,SkillUseRequest p){b.writeVarLong(p.requestId);b.writeUUID(p.characterId);b.writeVarLong(p.revision);b.writeVarInt(p.slot);}
    };
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
 }
 public record CombatModeRequest(boolean combat) implements CustomPacketPayload {
    public static final Type<CombatModeRequest> TYPE=DalmaruPayloads.type("combat_mode_request");
    public static final StreamCodec<RegistryFriendlyByteBuf,CombatModeRequest> CODEC=StreamCodec.composite(ByteBufCodecs.BOOL,CombatModeRequest::combat,CombatModeRequest::new);
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
 }
 public record RunReadyRequest(UUID preparationId,boolean ready) implements CustomPacketPayload {
    public static final Type<RunReadyRequest> TYPE=DalmaruPayloads.type("run_ready_request");
    public static final StreamCodec<RegistryFriendlyByteBuf,RunReadyRequest> CODEC=StreamCodec.composite(ByteBufCodecs.VAR_LONG,r->r.preparationId().getMostSignificantBits(),ByteBufCodecs.VAR_LONG,r->r.preparationId().getLeastSignificantBits(),ByteBufCodecs.BOOL,RunReadyRequest::ready,(most,least,ready)->new RunReadyRequest(new UUID(most,least),ready));
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
 }

 public record DodgeRequest(int direction) implements CustomPacketPayload {
    public static final Type<DodgeRequest> TYPE=DalmaruPayloads.type("dodge_request");
    public static final StreamCodec<RegistryFriendlyByteBuf,DodgeRequest> CODEC=StreamCodec.composite(ByteBufCodecs.VAR_INT,DodgeRequest::direction,DodgeRequest::new);
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
 }
 public record GuardRequest(boolean guard) implements CustomPacketPayload {
    public static final Type<GuardRequest> TYPE=DalmaruPayloads.type("guard_request");
    public static final StreamCodec<RegistryFriendlyByteBuf,GuardRequest> CODEC=StreamCodec.composite(ByteBufCodecs.BOOL,GuardRequest::guard,GuardRequest::new);
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
 }

}
