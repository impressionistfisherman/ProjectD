package kr.dalmaru.client;

import com.mojang.blaze3d.platform.InputConstants;
import kr.dalmaru.DalmaruMod;
import kr.dalmaru.network.DalmaruNetworking;
import kr.dalmaru.network.DalmaruPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class DalmaruClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
		Identifier.fromNamespaceAndPath(DalmaruMod.MOD_ID, "general"));
	private static final KeyMapping OPEN_GUIDE = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.open_guide", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, CATEGORY));
	private static final KeyMapping OPEN_CHARACTERS = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.open_characters", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY));
	private static final KeyMapping OPEN_QUESTS = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.open_quests", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U, CATEGORY));
	private static final KeyMapping OPEN_EQUIPMENT = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.dalmaru.open_equipment", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_E, CATEGORY));
	private static final KeyMapping OPEN_RUN = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.dalmaru.open_run", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F6, CATEGORY));
	private static final KeyMapping SWEEP = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.sweep", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY));
	private static final KeyMapping ADVANCE = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.advance", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY));
	private static final KeyMapping RISING_STRIKE = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.rising_strike", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY));
	private static final KeyMapping BATTLE_CRY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.battle_cry", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, CATEGORY));
	private static final KeyMapping PIERCING_SHOT = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.piercing_shot", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY));
	private static final KeyMapping SPIRIT_TALISMAN = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.spirit_talisman", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY));
	private static final KeyMapping AMBUSH = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.ambush", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, CATEGORY));
	private static final KeyMapping RETREAT_SHOT = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.retreat_shot", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, CATEGORY));
	private static final KeyMapping BINDING_TALISMAN = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.binding_talisman", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY));
	private static final KeyMapping SMOKE_BOMB = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.smoke_bomb", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, CATEGORY));
	private static final KeyMapping SHACKLE_STRIKE = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.shackle_strike", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY));
	private static final KeyMapping ESCAPE = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.escape", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, CATEGORY));
	private static final KeyMapping TRACKING_MARK = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.tracking_mark", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, CATEGORY));
	private static final KeyMapping BINDING_SHOT = KeyMappingHelper.registerKeyMapping(new KeyMapping(
		"key.dalmaru.binding_shot", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, CATEGORY));
	private static final KeyMapping PROTECTIVE_TALISMAN = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.dalmaru.protective_talisman", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F, CATEGORY));
	private static final KeyMapping SHORT_STEP = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.dalmaru.short_step", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_T, CATEGORY));
	private static boolean profileKeyDown;

	@Override
	public void onInitializeClient() {
		DalmaruStaminaHud.register();
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			ClientConnectionState.resetForConnection();
		ClientCharacterState.resetForConnection();
		ClientProfileState.resetForConnection();
		ClientQuestState.resetForConnection();
			ClientCombatState.resetForConnection();
		ClientSkillState.resetForConnection();
		ClientEquipmentState.resetForConnection();
		ClientRunState.resetForConnection();
			profileKeyDown = false;
			ClientPlayNetworking.send(new DalmaruPayloads.Hello(DalmaruNetworking.PROTOCOL_VERSION));
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientConnectionState.resetForConnection();
		ClientCharacterState.resetForConnection();
		ClientProfileState.resetForConnection();
		ClientQuestState.resetForConnection();
			ClientCombatState.resetForConnection();
			ClientSkillState.resetForConnection();
			ClientEquipmentState.resetForConnection();
			ClientRunState.resetForConnection();
			profileKeyDown = false;
		});

		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.Welcome.TYPE,
			(payload, context) -> ClientConnectionState.acceptWelcome(payload.protocolVersion()));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.ProbeResponse.TYPE,
			(payload, context) -> ClientConnectionState.acceptProbeResponse(payload.requestId(), payload.rateLimited()));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterSelectionReady.TYPE, (payload, context) -> {
			if (!payload.ready()) return;
			ClientCharacterState.markSelectionReady();
			context.client().setScreen(new DalmaruCharacterScreen());
			requestCharacterList();
		});
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterListResponse.TYPE,
			(payload, context) -> ClientCharacterState.acceptList(payload));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterSelectResponse.TYPE,
			(payload, context) -> {
				boolean switchingCharacter = ClientCharacterState.isSelectionConfirmed();
				if (ClientCharacterState.acceptSelection(payload)) {
					if (switchingCharacter) context.client().disconnectFromWorld(net.minecraft.network.chat.Component.literal("캐릭터 전환이 완료되었습니다. 다시 접속해 새 캐릭터로 입장하세요."));
					else context.client().setScreen(null);
				}
			});
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterCreateResponse.TYPE,
			(payload, context) -> ClientCharacterState.acceptCreation(payload));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterProfileResponse.TYPE,
			(payload, context) -> ClientProfileState.accept(payload));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterStatInvestmentResponse.TYPE,
			(payload, context) -> ClientProfileState.acceptInvestment(payload));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterStatResetResponse.TYPE,
			(payload, context) -> ClientProfileState.acceptReset(payload));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterProfessionResponse.TYPE,
			(payload, context) -> ClientProfileState.acceptProfession(payload));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.SkillLearnResponse.TYPE,
			(payload, context) -> ClientProfileState.acceptSkill(payload));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.SkillRanksResponse.TYPE,
			(payload, context) -> ClientSkillState.accept(payload));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.EquipmentListResponse.TYPE,
			(payload, context) -> ClientEquipmentState.accept(payload));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.EquipmentEnhanceResponse.TYPE,
			(payload, context) -> ClientEquipmentState.accept(payload));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.RunResponse.TYPE,
			(payload, context) -> { if (ClientRunState.accept(payload) && context.client().screen instanceof DalmaruRunScreen) context.client().setScreen(null); });
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.QuestResponse.TYPE,
			(payload, context) -> ClientQuestState.accept(payload));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CombatStateResponse.TYPE,
			(payload, context) -> ClientCombatState.accept(payload));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.screen == null && OPEN_GUIDE.consumeClick()) {
				client.setScreen(new DalmaruGuideScreen());
			}
			if (client.screen == null && OPEN_CHARACTERS.consumeClick()) {
				client.setScreen(new DalmaruCharacterScreen());
				requestCharacterList();
			}
			if (client.screen == null && OPEN_QUESTS.consumeClick()) {
				client.setScreen(new DalmaruQuestScreen(null));
			}
			if (client.screen == null && OPEN_EQUIPMENT.consumeClick()) {
				client.setScreen(new DalmaruEquipmentScreen());
			}
			if (client.screen == null && OPEN_RUN.consumeClick()) {
				client.setScreen(new DalmaruRunScreen());
			}
			if (client.screen == null && SWEEP.consumeClick()) ClientPlayNetworking.send(new DalmaruPayloads.SweepRequest());
			if (client.screen == null && ADVANCE.consumeClick()) ClientPlayNetworking.send(new DalmaruPayloads.AdvanceRequest());
			if (client.screen == null && RISING_STRIKE.consumeClick()) ClientPlayNetworking.send(new DalmaruPayloads.RisingStrikeRequest());
			if (client.screen == null && BATTLE_CRY.consumeClick()) ClientPlayNetworking.send(new DalmaruPayloads.BattleCryRequest());
			if (client.screen == null && PIERCING_SHOT.consumeClick()) ClientPlayNetworking.send(new DalmaruPayloads.PiercingShotRequest());
			if (client.screen == null && SPIRIT_TALISMAN.consumeClick()) ClientPlayNetworking.send(new DalmaruPayloads.SpiritTalismanRequest());
			if (client.screen == null && AMBUSH.consumeClick()) ClientPlayNetworking.send(new DalmaruPayloads.AmbushRequest());
			if (client.screen == null && RETREAT_SHOT.consumeClick()) ClientPlayNetworking.send(new DalmaruPayloads.RetreatShotRequest());
			if (client.screen == null && BINDING_TALISMAN.consumeClick()) ClientPlayNetworking.send(new DalmaruPayloads.BindingTalismanRequest());
			if (client.screen == null && SMOKE_BOMB.consumeClick()) ClientPlayNetworking.send(new DalmaruPayloads.SmokeBombRequest());
			if (client.screen == null && SHACKLE_STRIKE.consumeClick()) ClientPlayNetworking.send(new DalmaruPayloads.ShackleStrikeRequest());
			if (client.screen == null && ESCAPE.consumeClick()) ClientPlayNetworking.send(new DalmaruPayloads.EscapeRequest());
			if (client.screen == null && TRACKING_MARK.consumeClick()) ClientPlayNetworking.send(new DalmaruPayloads.TrackingMarkRequest());
			if (client.screen == null && BINDING_SHOT.consumeClick()) ClientPlayNetworking.send(new DalmaruPayloads.BindingShotRequest());
			if (client.screen == null && PROTECTIVE_TALISMAN.consumeClick()) ClientPlayNetworking.send(new DalmaruPayloads.ProtectiveTalismanRequest());
			if (client.screen == null && SHORT_STEP.consumeClick()) ClientPlayNetworking.send(new DalmaruPayloads.ShortStepRequest());
			boolean profilePressed = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_J);
			if (profilePressed && !profileKeyDown && client.screen == null) {
				client.setScreen(new DalmaruProfileScreen());
			}
			profileKeyDown = profilePressed;
			ClientConnectionState.tick();
			ClientCharacterState.tick();
			if (ClientCharacterState.shouldRetryList()) requestCharacterList();
			ClientProfileState.tick();
			if (client.player != null && client.screen == null && ClientCharacterState.requiresSelection()) {
				client.setScreen(new DalmaruCharacterScreen());
			}
		});
	}

	public static void requestProbe() {
		if (!ClientConnectionState.beginProbe()) {
			return;
		}
		ClientPlayNetworking.send(new DalmaruPayloads.ProbeRequest(ClientConnectionState.currentRequestId()));
	}

	public static void requestCharacterList() {
		if (ClientCharacterState.beginList()) {
			ClientPlayNetworking.send(new DalmaruPayloads.CharacterListRequest(ClientCharacterState.currentRequestId()));
		}
	}

	public static void requestCharacterSelection(java.util.UUID characterId) {
		if (ClientCharacterState.beginSelection(characterId)) {
			ClientPlayNetworking.send(new DalmaruPayloads.CharacterSelectRequest(ClientCharacterState.currentSelectionRequestId(), characterId));
		}
	}

	public static void requestCharacterCreation(int slot, String displayName) {
		if (ClientCharacterState.beginCreation(slot, displayName)) ClientPlayNetworking.send(new DalmaruPayloads.CharacterCreateRequest(ClientCharacterState.currentCreationRequestId(), slot, displayName));
	}

	public static void requestCharacterStatInvestment(int statId) {
		DalmaruPayloads.CharacterProfileResponse profile = ClientProfileState.current();
		if (profile != null && ClientProfileState.beginInvestment()) {
			ClientPlayNetworking.send(new DalmaruPayloads.CharacterStatInvestmentRequest(ClientProfileState.currentInvestmentRequestId(), profile.characterId(), statId, 1));
		}
	}

	public static void requestCharacterStatReset() {
		DalmaruPayloads.CharacterProfileResponse profile = ClientProfileState.current();
		if (profile != null && ClientProfileState.beginReset()) ClientPlayNetworking.send(new DalmaruPayloads.CharacterStatResetRequest(ClientProfileState.currentResetRequestId(), profile.characterId()));
	}

	public static void requestProfession(String profession) {
		if (ClientProfileState.beginProfession()) ClientPlayNetworking.send(new DalmaruPayloads.CharacterProfessionRequest(ClientProfileState.currentProfessionRequestId(), profession));
	}

	public static void requestSkill(String skillId) {
		if (ClientProfileState.beginSkill()) ClientPlayNetworking.send(new DalmaruPayloads.SkillLearnRequest(ClientProfileState.currentSkillRequestId(), skillId));
	}

	public static void requestSkillRanks() {
		ClientPlayNetworking.send(new DalmaruPayloads.SkillRanksRequest(ClientSkillState.nextRequestId()));
	}

	public static void requestQuest(boolean accept) {
		ClientPlayNetworking.send(new DalmaruPayloads.QuestRequest(ClientQuestState.beginRequest(), accept));
	}

	public static void requestEquipment() {
		ClientPlayNetworking.send(new DalmaruPayloads.EquipmentListRequest(ClientEquipmentState.nextRequestId()));
	}

    public static void requestEquipmentMove(java.util.UUID equipmentId) {
        if (net.minecraft.client.Minecraft.getInstance().player == null) return;
        var item=ClientEquipmentState.equipment().stream().filter(record->record.equipmentId().equals(equipmentId)).findFirst().orElse(null);
        if(item==null)return;
        net.minecraft.client.Minecraft.getInstance().player.connection.sendCommand("dalmaru-equipment " + (item.slotId()<0?"equip":"unequip") + " " + equipmentId + " " + ClientEquipmentState.nextRequestId());
    }

	public static void requestEnhancement(java.util.UUID equipmentId) {
		ClientEquipmentState.enhance(equipmentId);
		ClientPlayNetworking.send(new DalmaruPayloads.EquipmentEnhanceRequest(ClientEquipmentState.nextRequestId(), equipmentId));
	}

	public static void requestRun(String difficulty) {
		long requestId = ClientRunState.nextRequestId();
		if (requestId > 0) ClientPlayNetworking.send(new DalmaruPayloads.RunEnterRequest(requestId, difficulty));
	}
}
