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
    private static DalmaruPayloads.RunReviveState runReviveState;
    public static DalmaruPayloads.RunReviveState runReviveState(){return runReviveState;}

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
	private static boolean guardKeyDown;
    private static final KeyMapping DODGE=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.dalmaru.dodge",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_LEFT_ALT,CATEGORY));
    private static final KeyMapping TOGGLE_MODE=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.dalmaru.toggle_mode",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_GRAVE_ACCENT,CATEGORY));
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
		ClientRunState.resetForConnection();ClientLoadoutState.reset();runReviveState=null;
			profileKeyDown = false;guardKeyDown=false;
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
			ClientRunState.resetForConnection();ClientLoadoutState.reset();runReviveState=null;
			profileKeyDown = false;guardKeyDown=false;
		});

		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.Welcome.TYPE,
			(payload, context) -> ClientConnectionState.acceptWelcome(payload.protocolVersion()));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.ProbeResponse.TYPE,
			(payload, context) -> ClientConnectionState.acceptProbeResponse(payload.requestId(), payload.rateLimited()));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterSelectionReady.TYPE, (payload, context) -> {
			if (!payload.ready()) return;
			ClientCharacterState.markSelectionReady();requestLoadout();requestSkillRanks();
			context.client().setScreen(new DalmaruCharacterScreen());
			requestCharacterList();
		});
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterListResponse.TYPE,
			(payload, context) -> ClientCharacterState.acceptList(payload));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CharacterSelectResponse.TYPE,
			(payload, context) -> {
				boolean switchingCharacter = ClientCharacterState.isSelectionConfirmed();
				if (ClientCharacterState.acceptSelection(payload)) {
                    ClientLoadoutState.selected(payload.characterId());ClientSkillState.selected();ClientProfileState.selected(payload.characterId());ClientEquipmentState.selected();requestLoadout();requestSkillRanks();
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
			(payload, context) -> {ClientProfileState.acceptSkill(payload);requestSkillRanks();requestLoadout();});
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.SkillRanksResponse.TYPE,
			(payload, context) -> ClientSkillState.accept(payload));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.EquipmentListResponse.TYPE,
			(payload, context) -> ClientEquipmentState.accept(payload));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.EquipmentEnhanceResponse.TYPE,
			(payload, context) -> {ClientEquipmentState.accept(payload);requestEquipment();});
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.RunResponse.TYPE,
			(payload, context) -> { if(ClientRunState.prepare(payload)){if(context.client().screen==null)context.client().setScreen(new DalmaruRunScreen());}else if (ClientRunState.accept(payload) && context.client().screen instanceof DalmaruRunScreen) context.client().setScreen(null); });
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.QuestResponse.TYPE,
			(payload, context) -> ClientQuestState.accept(payload));
ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.LoadoutResponse.TYPE,(payload,context)->ClientLoadoutState.accept(payload));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.CombatStateResponse.TYPE,
			(payload, context) -> ClientCombatState.accept(payload));

		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.RunReviveState.TYPE,(payload,context)->runReviveState=payload);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if(client.player==null||!client.player.isDeadOrDying())runReviveState=null;
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
            if(client.screen==null&&TOGGLE_MODE.consumeClick())ClientPlayNetworking.send(new DalmaruPayloads.CombatModeRequest(ClientCombatState.current()==null||!ClientCombatState.current().combatMode()));
			if(client.player!=null){
                if(client.screen==null&&DODGE.consumeClick()&&ClientCombatState.current()!=null&&ClientCombatState.current().combatMode()){
                    int direction=client.options.keyDown.isDown()?1:client.options.keyLeft.isDown()?2:client.options.keyRight.isDown()?3:0;ClientPlayNetworking.send(new DalmaruPayloads.DodgeRequest(direction));
                }
                boolean guard=client.screen==null&&client.options.keyUse.isDown();if(guard!=guardKeyDown){guardKeyDown=guard;ClientPlayNetworking.send(new DalmaruPayloads.GuardRequest(guard));}
            }
            boolean profilePressed = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_J);
			if (profilePressed && !profileKeyDown && client.screen == null) {
				client.setScreen(new DalmaruProfileScreen());
			}
			profileKeyDown = profilePressed;
			ClientConnectionState.tick();
			ClientCharacterState.tick();
			if (ClientCharacterState.shouldRetryList()) requestCharacterList();
			ClientProfileState.tick();
            var resetRetry=ClientProfileState.retryReset();if(resetRetry!=null)ClientPlayNetworking.send(resetRetry);
            var professionRetry=ClientProfileState.retryProfession();if(professionRetry!=null)ClientPlayNetworking.send(professionRetry);
            var skillRetry=ClientProfileState.retrySkill();if(skillRetry!=null)ClientPlayNetworking.send(skillRetry);
            if(ClientLoadoutState.tick())requestLoadout();
            if(ClientSkillState.tick())requestSkillRanks();
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
		if (ClientProfileState.beginProfession(profession)) ClientPlayNetworking.send(new DalmaruPayloads.CharacterProfessionRequest(ClientProfileState.currentProfessionRequestId(), profession));
	}

	public static void requestSkill(String skillId) {
		if (ClientProfileState.beginSkill(skillId)) ClientPlayNetworking.send(new DalmaruPayloads.SkillLearnRequest(ClientProfileState.currentSkillRequestId(), skillId));
	}

	public static void requestSkillRanks() {
		ClientPlayNetworking.send(new DalmaruPayloads.SkillRanksRequest(ClientSkillState.nextRequestId()));
	}

	public static void requestQuest(boolean accept) {
		ClientPlayNetworking.send(new DalmaruPayloads.QuestRequest(ClientQuestState.beginRequest(), accept));
	}

	public static void requestEquipment() {
		long request=ClientEquipmentState.nextRequestId();ClientEquipmentState.queried(request);ClientPlayNetworking.send(new DalmaruPayloads.EquipmentListRequest(request));
	}

    public static void requestEquipmentMove(java.util.UUID equipmentId) {
        if (net.minecraft.client.Minecraft.getInstance().player == null) return;
        var item=ClientEquipmentState.equipment().stream().filter(record->record.equipmentId().equals(equipmentId)).findFirst().orElse(null);
        if(item==null)return;
        net.minecraft.client.Minecraft.getInstance().player.connection.sendCommand("dalmaru-equipment " + (item.slotId()<0?"equip":"unequip") + " " + equipmentId + " " + ClientEquipmentState.nextRequestId());
    }

	public static void requestEnhancement(java.util.UUID equipmentId) {
		ClientPlayNetworking.send(new DalmaruPayloads.EquipmentEnhanceRequest(ClientEquipmentState.nextRequestId(), equipmentId));
	}

	public static void requestRun(String difficulty) {
		long requestId = ClientRunState.nextRequestId();
		if (requestId > 0) ClientPlayNetworking.send(new DalmaruPayloads.RunEnterRequest(requestId, difficulty));
	}
    public static boolean combatHotbar(int slot){
        var client=net.minecraft.client.Minecraft.getInstance();var combat=ClientCombatState.current();
        if(client.player==null||client.screen!=null||combat==null||!combat.combatMode())return false;
        var loadout=ClientLoadoutState.current();if(loadout!=null)ClientPlayNetworking.send(new DalmaruPayloads.SkillUseRequest(ClientLoadoutState.next(),loadout.characterId(),loadout.revision(),slot));return true;
    }
    static void requestLoadout(){long requestId=ClientLoadoutState.next();ClientLoadoutState.queried(requestId);ClientPlayNetworking.send(new DalmaruPayloads.LoadoutRequest(requestId));}
    static void updateLoadout(java.util.List<String> slots){var current=ClientLoadoutState.current();if(current==null||!current.editable()||ClientLoadoutState.waiting())return;long requestId=ClientLoadoutState.next();ClientLoadoutState.pending(requestId);ClientPlayNetworking.send(new DalmaruPayloads.LoadoutUpdate(requestId,current.characterId(),current.revision(),slots));}
    static void readyRun(){if(!ClientRunState.preparing()||ClientRunState.preparationId()==null)return;ClientPlayNetworking.send(new DalmaruPayloads.RunReadyRequest(ClientRunState.preparationId(),true));}

}
