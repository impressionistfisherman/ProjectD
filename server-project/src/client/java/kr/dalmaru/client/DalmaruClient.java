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
import net.minecraft.sounds.SoundEvents;
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
	private static final KeyMapping OPEN_PLAY = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.dalmaru.open_play", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, CATEGORY));
	private static boolean guardKeyDown;
	private static boolean brazierShift;
    private static final KeyMapping DODGE=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.dalmaru.dodge",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_LEFT_ALT,CATEGORY));
    private static final KeyMapping TOGGLE_MODE=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.dalmaru.toggle_mode",InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_GRAVE_ACCENT,CATEGORY));
	private static boolean profileKeyDown;

	@Override
	public void onInitializeClient() {
		DalmaruStaminaHud.register();
		DalmaruBrazierHud.register();
        ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.StoryStateResponse.TYPE,(payload,context)->ClientStoryState.accept(payload));
        ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.WaypointResponse.TYPE,(payload,context)->{
            if(context.client().screen instanceof DalmaruWaypointScreen screen)screen.accept(payload);
        });
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			ClientConnectionState.resetForConnection();
		ClientCharacterState.resetForConnection();
		ClientProfileState.resetForConnection();
		ClientQuestState.resetForConnection();
        ClientStoryState.reset();
			ClientCombatState.resetForConnection();
		ClientSkillState.resetForConnection();
		ClientEquipmentState.resetForConnection();
		ClientRunState.resetForConnection();ClientLoadoutState.reset();ClientPartyState.resetForConnection();ClientBrazierState.resetForConnection();runReviveState=null;ClientRequestState.reset();
			profileKeyDown = false;guardKeyDown=false;brazierShift=false;
			ClientPlayNetworking.send(new DalmaruPayloads.Hello(DalmaruNetworking.PROTOCOL_VERSION));
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientConnectionState.resetForConnection();
		ClientCharacterState.resetForConnection();
		ClientProfileState.resetForConnection();
		ClientQuestState.resetForConnection();
        ClientStoryState.reset();
			ClientCombatState.resetForConnection();
			ClientSkillState.resetForConnection();
			ClientEquipmentState.resetForConnection();
			ClientRunState.resetForConnection();ClientLoadoutState.reset();ClientPartyState.resetForConnection();ClientBrazierState.resetForConnection();runReviveState=null;ClientRequestState.reset();
			profileKeyDown = false;guardKeyDown=false;brazierShift=false;
		});

		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.Welcome.TYPE,
			(payload, context) -> ClientConnectionState.acceptWelcome(payload.protocolVersion()));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.P4State.TYPE,
			(payload, context) -> ClientPartyState.accept(payload));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.P4BrazierHud.TYPE, (payload, context) -> {
			String previous = ClientBrazierState.accept(payload);
			var player = context.client().player;
			if (player == null || payload.total() < 1) return;
			if ("WARNING".equals(payload.neglect()) && !"WARNING".equals(previous)) player.playSound(SoundEvents.BELL_BLOCK, 0.7F, 1.2F);
			else if ("DAMAGING".equals(payload.neglect()) && !"DAMAGING".equals(previous)) player.playSound(SoundEvents.PLAYER_HURT, 0.6F, 1.0F);
		});
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
                    ClientLoadoutState.selected(payload.characterId());ClientStoryState.selected(payload.characterId());ClientSkillState.selected();ClientQuestState.selected();ClientProfileState.selected(payload.characterId());ClientEquipmentState.selected();ClientRequestState.selected(payload.characterId());requestLoadout();requestSkillRanks();
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
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.LifeProfileResponse.TYPE, (payload, context) -> ClientLifeState.acceptProfile(payload.status(), payload.snapshot()));
		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.SkillRanksResponse.TYPE,
			(payload, context) -> ClientSkillState.accept(payload));
        ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.RefinementStateResponse.TYPE,(payload,context)->{var profile=ClientProfileState.current();if(profile!=null&&profile.characterId().equals(payload.characterId()))ClientEquipmentState.acceptRefinement(payload);});
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

		ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.RequestContext.TYPE,(payload,context)->ClientRequestState.accept(payload));
        ClientPlayNetworking.registerGlobalReceiver(DalmaruPayloads.OperationReceipt.TYPE,(payload,context)->acceptOperation(payload));
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
			if (client.screen == null && OPEN_PLAY.consumeClick()) {
				client.setScreen(new DalmaruPlayScreen(null));
			}
            if(client.screen==null&&TOGGLE_MODE.consumeClick())ClientPlayNetworking.send(new DalmaruPayloads.CombatModeRequest(ClientCombatState.current()==null||!ClientCombatState.current().combatMode()));
			if(client.player!=null){
                if(client.screen==null&&DODGE.consumeClick()&&ClientCombatState.current()!=null&&ClientCombatState.current().combatMode()){
                    int direction=client.options.keyDown.isDown()?1:client.options.keyLeft.isDown()?2:client.options.keyRight.isDown()?3:0;ClientPlayNetworking.send(new DalmaruPayloads.DodgeRequest(direction));
                }
                boolean guard=client.screen==null&&client.options.keyUse.isDown();if(guard!=guardKeyDown){guardKeyDown=guard;ClientPlayNetworking.send(new DalmaruPayloads.GuardRequest(guard));}
                boolean shift=client.options.keyShift.isDown();if(shift&&!brazierShift&&ClientBrazierState.channeling()&&ClientConnectionState.isCompatible())ClientPlayNetworking.send(new DalmaruPayloads.P4Request(ClientBrazierState.nextId(),"cancel_brazier","{}"));brazierShift=shift;
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
            for(var retry:ClientRequestState.tick())ClientPlayNetworking.send(retry);
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
        if(!ClientProfileState.beginInvestment())return;
        long correlation=ClientProfileState.currentInvestmentRequestId();
        var body=new com.google.gson.JsonObject();body.addProperty("statId",statId);body.addProperty("amount",1);
        var request=ClientRequestState.begin(correlation,"invest_stat",body);
        if(request==null){ClientProfileState.completeInvestment(correlation,"캐릭터 정보 확인 중입니다.");return;}
        ClientProfileState.delegateInvestmentRetry();ClientPlayNetworking.send(request);
    }

    public static void requestCharacterLife(java.util.UUID characterId, boolean restore) {
        var body=new com.google.gson.JsonObject();body.addProperty("characterId",characterId.toString());
        var request=ClientRequestState.begin(ClientCharacterState.nextLifeRequestId(),restore?"restore_character":"delete_character",body);
        if(request!=null)ClientPlayNetworking.send(request);
    }

    public static void requestLifeProfile() {
        if (!ClientConnectionState.isCompatible()) return;
        ClientPlayNetworking.send(new DalmaruPayloads.LifeProfileRequest(ClientLifeState.nextId()));
    }

    public static void requestLifeSpecialize(String job, String specialization) {
        var body = new com.google.gson.JsonObject();
        body.addProperty("job", job);
        body.addProperty("specialization", specialization);
        var request = ClientRequestState.begin(ClientLifeState.nextId(), "specialize_life", body);
        if (request != null) ClientPlayNetworking.send(request);
    }

    public static void requestLifeTask(String job) {
        var body = new com.google.gson.JsonObject();
        body.addProperty("job", job);
        var request = ClientRequestState.begin(ClientLifeState.nextId(), "complete_life_task", body);
        if (request != null) ClientPlayNetworking.send(request);
    }

    public static void requestCraftResearch() {
        var body = new com.google.gson.JsonObject();
        var materials = new com.google.gson.JsonArray();
        var line = new com.google.gson.JsonObject();
        line.addProperty("materialId", "material.iron_ingot");
        line.addProperty("binding", "FREE");
        line.addProperty("quantity", 1);
        materials.add(line);
        body.addProperty("clueId", "clue.blade");
        body.add("materials", materials);
        var request = ClientRequestState.begin(ClientCraftState.nextId(), "research_craft", body);
        if (request != null) ClientPlayNetworking.send(request);
    }

    public static void requestPrecisionBegin() {
        var body = new com.google.gson.JsonObject();
        body.addProperty("recipeId", "craft.precision_blade");
        body.addProperty("equipmentId", "00000000-0000-4000-8000-0000000000e1");
        var request = ClientRequestState.begin(ClientPrecisionState.nextId(), "begin_precision", body);
        if (request != null) ClientPlayNetworking.send(request);
    }

    public static void requestCraftOrderPlace() {
        var body = new com.google.gson.JsonObject();
        var materials = new com.google.gson.JsonArray();
        var line = new com.google.gson.JsonObject();
        line.addProperty("materialId", "material.iron_ingot");
        line.addProperty("binding", "FREE");
        line.addProperty("quantity", 1);
        materials.add(line);
        body.addProperty("recipeId", "craft.iron_blade");
        body.add("materials", materials);
        body.addProperty("copper", 25);
        var request = ClientRequestState.begin(ClientCraftOrderState.nextId(), "place_craft_order", body);
        if (request != null) ClientPlayNetworking.send(request);
    }

    public static void requestCraftOrderAccept() {
        var body = new com.google.gson.JsonObject();
        body.addProperty("orderId", "00000000-0000-4000-8000-0000000000e3");
        var request = ClientRequestState.begin(ClientCraftOrderState.nextId(), "accept_craft_order", body);
        if (request != null) ClientPlayNetworking.send(request);
    }

    public static void requestCraftOrderSettle() {
        var body = new com.google.gson.JsonObject();
        body.addProperty("orderId", "00000000-0000-4000-8000-0000000000e3");
        var request = ClientRequestState.begin(ClientCraftOrderState.nextId(), "settle_craft_order", body);
        if (request != null) ClientPlayNetworking.send(request);
    }

    public static void requestCraftOrderCancel() {
        var body = new com.google.gson.JsonObject();
        body.addProperty("orderId", "00000000-0000-4000-8000-0000000000e3");
        var request = ClientRequestState.begin(ClientCraftOrderState.nextId(), "cancel_craft_order", body);
        if (request != null) ClientPlayNetworking.send(request);
    }

    public static void requestPrecisionStep(int step, String choice) {
        var body = new com.google.gson.JsonObject();
        body.addProperty("orderId", "00000000-0000-4000-8000-0000000000e2");
        body.addProperty("stepIndex", step);
        body.addProperty("choice", choice);
        var request = ClientRequestState.begin(ClientPrecisionState.nextId(), "step_precision", body);
        if (request != null) ClientPlayNetworking.send(request);
    }

    public static void requestLearnedCraft() {
        var body = new com.google.gson.JsonObject();
        body.addProperty("recipeId", "craft.iron_blade");
        var request = ClientRequestState.begin(ClientCraftState.nextId(), "craft_learned", body);
        if (request != null) ClientPlayNetworking.send(request);
    }

    public static void requestLifeLearn(String job) {
        var body = new com.google.gson.JsonObject();
        body.addProperty("job", job);
        var request = ClientRequestState.begin(ClientLifeState.nextId(), "learn_life", body);
        if (request != null) ClientPlayNetworking.send(request);
    }

    public static void requestVillages() {
        var request=ClientRequestState.begin(ClientVillageState.nextId(),"list_villages",new com.google.gson.JsonObject());
        if(request!=null)ClientPlayNetworking.send(request);
    }

    public static void requestQuestStage(String stage, String action) {
        var body=new com.google.gson.JsonObject();body.addProperty("questId",kr.dalmaru.quest.QuestStageRepository.QUEST_ID);body.addProperty("stage",stage);body.addProperty("action",action);
        var request=ClientRequestState.begin(ClientQuestStageState.nextId(),"quest_stage",body);
        if(request!=null)ClientPlayNetworking.send(request);
    }

    public static void requestPlay(String operation, com.google.gson.JsonObject body) {
        var request=ClientRequestState.begin(ClientPlayAccessState.nextId(),operation,body);
        if(request!=null)ClientPlayNetworking.send(request);
    }

    public static void openHome() {
        var client = net.minecraft.client.Minecraft.getInstance();
        if (client != null) client.setScreen(new DalmaruHousingScreen(client.screen));
    }

    public static void cycleHomePlot() { ClientHousingState.cyclePlot(); }

    public static void cycleHomeCrop() { ClientHousingState.cycleCrop(); }

    public static void requestExchange(String operation) {
        var body = new com.google.gson.JsonObject();
        if ("search_market".equals(operation)) {
            body.addProperty("channel", "EXCHANGE");
            body.addProperty("query", ClientExchangeState.query());
            body.addProperty("offset", ClientExchangeState.offset());
        }
        if ("list_market".equals(operation)) {
            if (ClientExchangeState.stashId() == null) { ClientExchangeState.accept("{\"status\":\"ABSENT\"}", true); return; }
            body.addProperty("stashId", ClientExchangeState.stashId());
            body.addProperty("channel", "EXCHANGE");
            body.addProperty("price", ClientExchangeState.price());
        }
        if ("buy_market".equals(operation)) {
            if (ClientExchangeState.listingId() == null) { ClientExchangeState.accept("{\"status\":\"ABSENT\"}", true); return; }
            body.addProperty("listingId", ClientExchangeState.listingId());
            body.addProperty("price", ClientExchangeState.price());
        }
        var request = ClientRequestState.begin(ClientExchangeState.nextId(), operation, body);
        if (request != null) ClientPlayNetworking.send(request);
    }

    public static void requestMarket(String operation) {
        var body = new com.google.gson.JsonObject();
        if ("stash_market".equals(operation)) {
            body.addProperty("materialId", ClientMarketState.material());
            body.addProperty("quantity", ClientMarketState.quantity());
        }
        if ("list_market".equals(operation)) {
            if (ClientMarketState.stashId() == null) { ClientMarketState.accept("{\"status\":\"ABSENT\"}", true); return; }
            body.addProperty("stashId", ClientMarketState.stashId());
            body.addProperty("channel", "STALL");
            body.addProperty("price", ClientMarketState.price());
        }
        if ("buy_market".equals(operation)) {
            if (ClientMarketState.listingId() == null) { ClientMarketState.accept("{\"status\":\"ABSENT\"}", true); return; }
            body.addProperty("listingId", ClientMarketState.listingId());
            body.addProperty("price", ClientMarketState.price());
        }
        if ("cancel_market".equals(operation) || "claim_market".equals(operation)) {
            String id = "claim_market".equals(operation) ? ClientMarketState.stashId() : ClientMarketState.listingId();
            if (id == null) { ClientMarketState.accept("{\"status\":\"ABSENT\"}", true); return; }
            body.addProperty("claim_market".equals(operation) ? "stashId" : "listingId", id);
        }
        var request = ClientRequestState.begin(ClientMarketState.nextId(), operation, body);
        if (request != null) ClientPlayNetworking.send(request);
    }

    public static void requestProduction(String operation) {
        var body = new com.google.gson.JsonObject();
        if ("enqueue_production".equals(operation)) {
            if (ClientHousingState.housingId() == null) { ClientProductionState.accept("{\"status\":\"ABSENT\"}", true); return; }
            body.addProperty("housingId", ClientHousingState.housingId());
            body.addProperty("recipeId", "craft.iron_ingot");
            body.addProperty("quantity", 1);
        }
        if ("claim_production".equals(operation) || "cancel_production".equals(operation)) {
            if (ClientProductionState.jobId() == null) { ClientProductionState.accept("{\"status\":\"ABSENT\"}", true); return; }
            body.addProperty("jobId", ClientProductionState.jobId());
        }
        var request = ClientRequestState.begin(ClientProductionState.nextId(), operation, body);
        if (request != null) ClientPlayNetworking.send(request);
    }

    public static void requestHome(String operation) {
        var body = new com.google.gson.JsonObject();
        String wire = "tend_water".equals(operation) || "tend_fertilize".equals(operation) ? "tend_crop" : operation;
        if ("enter_home".equals(operation) || "grant_home".equals(operation) || "place_home".equals(operation) || "expand_home".equals(operation)) {
            if (ClientHousingState.housingId() == null) { ClientHousingState.accept("{\"status\":\"ABSENT\"}", true); return; }
            body.addProperty("housingId", ClientHousingState.housingId());
        }
        if ("grant_home".equals(operation)) {
            if (ClientHousingState.targetAccount() == null) { ClientHousingState.accept("{\"status\":\"ABSENT\"}", true); return; }
            body.addProperty("accountId", ClientHousingState.targetAccount());
            body.addProperty("permission", ClientHousingState.permission());
            if (ClientHousingState.targetId() != null) body.addProperty("targetId", ClientHousingState.targetId());
        }
        if ("revoke_home".equals(operation)) {
            if (ClientHousingState.grantId() == null) { ClientHousingState.accept("{\"status\":\"ABSENT\"}", true); return; }
            body.addProperty("grantId", ClientHousingState.grantId());
        }
        if ("place_home".equals(operation) || "expand_home".equals(operation)) {
            if (ClientHousingState.changeId() == null) { ClientHousingState.accept("{\"status\":\"ABSENT\"}", true); return; }
            body.addProperty("changeId", ClientHousingState.changeId());
        }
        if ("plant_crop".equals(wire) || "tend_crop".equals(wire) || "harvest_crop".equals(wire)) {
            if (ClientHousingState.housingId() == null) { ClientHousingState.accept("{\"status\":\"ABSENT\"}", true); return; }
            body.addProperty("housingId", ClientHousingState.housingId());
            body.addProperty("plot", ClientHousingState.plot());
            if ("plant_crop".equals(wire)) body.addProperty("crop", ClientHousingState.crop());
            if ("tend_crop".equals(wire)) body.addProperty("action", "tend_fertilize".equals(operation) ? "fertilize" : "water");
            if ("harvest_crop".equals(wire)) body.addProperty("generation", ClientHousingState.cropGeneration());
        }
        var request = ClientRequestState.begin(ClientHousingState.nextId(), wire, body);
        if (request != null) ClientPlayNetworking.send(request);
    }

    public static void requestVillage(String villageId) {
        var body=new com.google.gson.JsonObject();body.addProperty("villageId",villageId);
        var request=ClientRequestState.begin(ClientVillageState.nextId(),"open_village",body);
        if(request!=null)ClientPlayNetworking.send(request);
    }

    public static void requestCharacterStatReset() {
        if(!ClientProfileState.beginReset())return;
        long correlation=ClientProfileState.currentResetRequestId();
        var request=ClientRequestState.begin(correlation,"reset_stats",new com.google.gson.JsonObject());
        if(request==null){ClientProfileState.completeReset(correlation,"캐릭터 정보 확인 중입니다.");return;}
        ClientProfileState.delegateResetRetry();ClientPlayNetworking.send(request);
    }

    public static void requestAdvancement(String stage, String branch) {
        var current = ClientProfileState.current();
        if (current == null || stage == null || branch == null || branch.isBlank()) return;
        var body = new com.google.gson.JsonObject();
        body.addProperty("stage", stage); body.addProperty("branch", branch); body.addProperty("expectedRevision", current.profile().revision());
        long correlation = ClientProfileState.nextGrowth();
        var request = ClientRequestState.begin(correlation, "confirm_advancement", body);
        if (request != null) { ClientProfileState.note("승급을 확인하는 중입니다."); ClientPlayNetworking.send(request); }
    }

    public static void requestRogueReset(int strength, int dexterity) {
        var current = ClientProfileState.current();
        if (current == null || !current.profile().rogueResetAvailable()) return;
        var body = new com.google.gson.JsonObject();
        body.addProperty("strengthAllocated", strength); body.addProperty("dexterityAllocated", dexterity); body.addProperty("expectedRevision", current.profile().revision());
        long correlation = ClientProfileState.nextGrowth();
        var request = ClientRequestState.begin(correlation, "reallocate_rogue", body);
        if (request != null) { ClientProfileState.note("힘과 재주 재배분을 확인하는 중입니다."); ClientPlayNetworking.send(request); }
    }

    public static void requestProfession(String profession) {
        if(!ClientProfileState.beginProfession(profession))return;
        long correlation=ClientProfileState.currentProfessionRequestId();
        var body=new com.google.gson.JsonObject();body.addProperty("profession",profession.toUpperCase(java.util.Locale.ROOT));
        var request=ClientRequestState.begin(correlation,"select_profession",body);
        if(request==null){ClientProfileState.completeProfession(correlation,"캐릭터 정보 확인 중입니다.");return;}
        ClientProfileState.delegateProfessionRetry();ClientPlayNetworking.send(request);
    }

	public static void requestSkill(String skillId) {
        if(!ClientProfileState.beginSkill(skillId))return;long correlation=ClientProfileState.currentSkillRequestId();
        var body=new com.google.gson.JsonObject();body.addProperty("skillId",skillId);var request=ClientRequestState.begin(correlation,"learn_skill",body);
        if(request==null){ClientProfileState.completeSkill(correlation,"\uce90\ub9ad\ud130 \uc815\ubcf4 \ud655\uc778 \uc911\uc785\ub2c8\ub2e4.");return;}
        ClientProfileState.delegateSkillRetry();ClientPlayNetworking.send(request);
    }

	public static void requestSkillRanks() {
		ClientPlayNetworking.send(new DalmaruPayloads.SkillRanksRequest(ClientSkillState.nextRequestId()));
	}

    public static void requestQuest(boolean accept) {
        long correlation=ClientQuestState.beginRequest();
        if(!accept){ClientPlayNetworking.send(new DalmaruPayloads.QuestRequest(correlation,false));return;}
        var quest=kr.dalmaru.content.ContentCatalog.current().quest(kr.dalmaru.quest.QuestRepository.QUEST_ID);var body=new com.google.gson.JsonObject();body.addProperty("questId",kr.dalmaru.quest.QuestRepository.QUEST_ID);body.addProperty("contentVersion",quest.version());
        var request=ClientRequestState.begin(correlation,"accept_quest",body);
        if(request==null){ClientQuestState.accept(new DalmaruPayloads.QuestResponse(correlation,"캐릭터 정보 확인 중입니다."));return;}
        ClientPlayNetworking.send(request);
    }
	public static void requestEquipment() {
		long request=ClientEquipmentState.nextRequestId();ClientEquipmentState.queried(request);ClientPlayNetworking.send(new DalmaruPayloads.EquipmentListRequest(request));
	}

    public static void requestEquipmentMove(java.util.UUID equipmentId){
        var item=ClientEquipmentState.equipment().stream().filter(record->record.equipmentId().equals(equipmentId)).findFirst().orElse(null);if(item==null)return;
        long correlation=ClientEquipmentState.nextRequestId();var body=new com.google.gson.JsonObject();body.addProperty("equipmentId",equipmentId.toString());body.addProperty("equip",item.slotId()<0);
        var request=ClientRequestState.begin(correlation,"move_equipment",body);if(request==null)return;
        ClientEquipmentState.queried(correlation);ClientPlayNetworking.send(request);
    }
    public static void requestInheritance(java.util.UUID target,java.util.UUID source){
        var targetItem=ClientEquipmentState.equipment().stream().filter(item->item.equipmentId().equals(target)).findFirst().orElse(null);var sourceItem=ClientEquipmentState.equipment().stream().filter(item->item.equipmentId().equals(source)).findFirst().orElse(null);
        if(target==null||source==null||target.equals(source)||targetItem==null||sourceItem==null||targetItem.slotId()<0||sourceItem.slotId()>=0)return;
        long correlation=ClientEquipmentState.nextRequestId();var body=new com.google.gson.JsonObject();body.addProperty("targetEquipmentId",target.toString());body.addProperty("sourceEquipmentId",source.toString());var request=ClientRequestState.begin(correlation,"inherit_equipment",body);if(request==null)return;
        ClientEquipmentState.queried(correlation);ClientPlayNetworking.send(request);
    }
    private static void acceptQuestOperation(DalmaruPayloads.OperationRequest request,DalmaruPayloads.OperationReceipt receipt){
        if("rejected".equals(receipt.state())){ClientQuestState.accept(new DalmaruPayloads.QuestResponse(receipt.correlationId(),"의뢰 수락 조건을 확인하세요."));return;}
        try{
            var original=com.google.gson.JsonParser.parseString(receipt.result()).getAsJsonObject();var expected=com.google.gson.JsonParser.parseString(request.payload()).getAsJsonObject();
            for(String key:new String[]{"questId","contentVersion"})if(!original.has(key)||!original.get(key).isJsonPrimitive()||!original.getAsJsonPrimitive(key).isString()||!original.get(key).getAsString().equals(expected.get(key).getAsString()))throw new IllegalStateException("Invalid quest receipt identity");
            if(!original.has("created")||!original.get("created").isJsonPrimitive()||!original.getAsJsonPrimitive("created").isBoolean())throw new IllegalStateException("Invalid quest receipt result");
        }catch(IllegalArgumentException|IllegalStateException|com.google.gson.JsonParseException error){ClientQuestState.accept(new DalmaruPayloads.QuestResponse(receipt.correlationId(),"의뢰 결과 확인에 실패했습니다."));}
        requestQuest(false);
    }
    private static void acceptInheritanceOperation(DalmaruPayloads.OperationRequest request,DalmaruPayloads.OperationReceipt receipt){
        String message="계승 대상·원본·소유권 조건을 확인하세요.";
        if("rejected".equals(receipt.state())){try{var json=com.google.gson.JsonParser.parseString(receipt.result()).getAsJsonObject();if(json.has("message")&&json.get("message").isJsonPrimitive())message=json.get("message").getAsString();}catch(RuntimeException ignored){}ClientEquipmentState.movementStatus(message);requestEquipment();return;}
        try{
            var original=com.google.gson.JsonParser.parseString(receipt.result()).getAsJsonObject();var expected=com.google.gson.JsonParser.parseString(request.payload()).getAsJsonObject();
            for(String key:new String[]{"targetEquipmentId","sourceEquipmentId"})if(!original.has(key)||!original.get(key).isJsonPrimitive()||!original.getAsJsonPrimitive(key).isString()||!original.get(key).getAsString().equals(expected.get(key).getAsString()))throw new IllegalStateException("Invalid inheritance identity");
            if(!original.has("credit")||!original.get("credit").isJsonPrimitive()||!original.getAsJsonPrimitive("credit").isNumber()||original.get("credit").getAsBigDecimal().intValueExact()<0)throw new IllegalStateException("Invalid inheritance credit");
            message=receipt.replayed()?"이미 처리된 계승입니다. 최신 장비를 확인합니다.":"계승을 처리했습니다. 최신 장비를 확인합니다.";
        }catch(IllegalArgumentException|IllegalStateException|ArithmeticException|com.google.gson.JsonParseException error){message="계승 결과 확인에 실패했습니다.";}
        ClientEquipmentState.movementStatus(message);requestEquipment();
    }
    public static void requestRefinement(java.util.UUID equipment){
        if(ClientEquipmentState.equipment().stream().noneMatch(item->item.equipmentId().equals(equipment)))return;
        long correlation=ClientEquipmentState.nextRequestId();var body=new com.google.gson.JsonObject();body.addProperty("equipmentId",equipment.toString());var request=ClientRequestState.begin(correlation,"refine_item",body);if(request==null)return;
        ClientEquipmentState.queriedRefinement(correlation,equipment);ClientPlayNetworking.send(request);
    }
    private static void requestRefinementState(java.util.UUID equipment){long correlation=ClientEquipmentState.nextRequestId();ClientEquipmentState.queriedRefinement(correlation,equipment);ClientPlayNetworking.send(new DalmaruPayloads.RefinementStateRequest(correlation,equipment));}
    private static void acceptRefinementOperation(DalmaruPayloads.OperationRequest request,DalmaruPayloads.OperationReceipt receipt){
        var expected=com.google.gson.JsonParser.parseString(request.payload()).getAsJsonObject();var equipment=java.util.UUID.fromString(expected.get("equipmentId").getAsString());String message="재련 조건과 보유 동전을 확인하세요.";
        if(!"rejected".equals(receipt.state()))try{
            var original=com.google.gson.JsonParser.parseString(receipt.result()).getAsJsonObject();
            if(!original.has("equipmentId")||!original.get("equipmentId").isJsonPrimitive()||!original.getAsJsonPrimitive("equipmentId").isString()||!original.get("equipmentId").getAsString().equals(equipment.toString())||!original.has("grade")||!original.get("grade").isJsonPrimitive()||!original.getAsJsonPrimitive("grade").isNumber()||!original.has("failures")||!original.get("failures").isJsonPrimitive()||!original.getAsJsonPrimitive("failures").isNumber()||!original.has("success")||!original.get("success").isJsonPrimitive()||!original.getAsJsonPrimitive("success").isBoolean())throw new IllegalStateException("Invalid refinement receipt");
            int grade=original.get("grade").getAsBigDecimal().intValueExact(),failures=original.get("failures").getAsBigDecimal().intValueExact();if(grade<0||grade>5||failures<0)throw new IllegalStateException("Invalid refinement result");
            message=receipt.replayed()?"이미 처리된 재련입니다. 최신 상태를 확인합니다.":original.get("success").getAsBoolean()?"재련에 성공했습니다.":"재련에 실패했습니다.";
        }catch(IllegalArgumentException|IllegalStateException|ArithmeticException|com.google.gson.JsonParseException error){message="재련 결과 확인에 실패했습니다.";}
        ClientEquipmentState.movementStatus(message);requestRefinementState(equipment);
    }
    private static void acceptEquipmentMoveOperation(DalmaruPayloads.OperationReceipt receipt){
        ClientEquipmentState.movementStatus("rejected".equals(receipt.state())?"장비 소유권·직업·레벨·전투 상태를 확인하세요.":"최신 장비 상태를 확인합니다.");requestEquipment();
    }

    private static void acceptLoadoutOperation(DalmaruPayloads.OperationReceipt receipt){
        ClientLoadoutState.completeOperation(receipt.correlationId());requestLoadout();
    }

    static String rejectionText(String result,String fallback){
        try{var json=com.google.gson.JsonParser.parseString(result).getAsJsonObject();String status=json.has("status")&&json.get("status").isJsonPrimitive()?json.get("status").getAsString():"";String reason=json.has("reason")&&json.get("reason").isJsonPrimitive()?json.get("reason").getAsString():"";if(!reason.isEmpty())return reason;if(!status.isEmpty())return status;return fallback;}
        catch(RuntimeException exception){return fallback;}
    }
    private static void acceptProfessionOperation(DalmaruPayloads.OperationRequest request,DalmaruPayloads.OperationReceipt receipt){
        if("rejected".equals(receipt.state())){ClientProfileState.completeProfession(receipt.correlationId(),rejectionText(receipt.result(),"전직 조건을 확인하세요."));return;}
        String status="전직 조건을 확인하세요.";
        if(!"rejected".equals(receipt.state()))try{
            var original=com.google.gson.JsonParser.parseString(receipt.result()).getAsJsonObject();
            var expected=com.google.gson.JsonParser.parseString(request.payload()).getAsJsonObject();
            if(!original.has("profession")||!original.get("profession").isJsonPrimitive()||!original.getAsJsonPrimitive("profession").isString()||!original.get("profession").getAsString().equals(expected.get("profession").getAsString()))throw new IllegalStateException("Invalid profession receipt");
            status=receipt.replayed()?"이미 처리된 전직입니다. 최신 정보를 표시합니다.":"전직을 완료했습니다.";
        }catch(IllegalArgumentException|IllegalStateException|com.google.gson.JsonParseException error){status="전직 결과 확인에 실패했습니다.";}
        ClientProfileState.completeProfession(receipt.correlationId(),status);requestSkillRanks();requestLoadout();
    }

    private static void acceptResetOperation(DalmaruPayloads.OperationRequest request,DalmaruPayloads.OperationReceipt receipt){
        String status="요청을 처리할 수 없습니다.";
        if(!"rejected".equals(receipt.state()))try{
            var original=com.google.gson.JsonParser.parseString(receipt.result()).getAsJsonObject();
            for(String key:new String[]{"strength","dexterity","arcana","vitality","spirit"}){
                if(!original.has(key)||!original.get(key).isJsonPrimitive()||!original.getAsJsonPrimitive(key).isNumber()||original.get(key).getAsBigDecimal().intValueExact()!=0)throw new IllegalStateException("Invalid reset receipt");
            }
            if(!original.has("unspentStatPoints")||!original.get("unspentStatPoints").isJsonPrimitive()||!original.getAsJsonPrimitive("unspentStatPoints").isNumber()||original.get("unspentStatPoints").getAsBigDecimal().intValueExact()<0||!original.has("freeResetAvailable")||!original.get("freeResetAvailable").isJsonPrimitive()||!original.getAsJsonPrimitive("freeResetAvailable").isBoolean()||original.get("freeResetAvailable").getAsBoolean()||!original.has("copperCost")||!original.get("copperCost").isJsonPrimitive()||!original.getAsJsonPrimitive("copperCost").isNumber())throw new IllegalStateException("Invalid reset receipt");
            int copperCost=original.get("copperCost").getAsBigDecimal().intValueExact();
            status=receipt.replayed()?"이미 처리된 초기화입니다. 최신 능력치를 표시합니다.":copperCost==0?"능력치를 초기화했습니다.":copperCost+"전으로 초기화했습니다.";
        }catch(IllegalArgumentException|IllegalStateException|ArithmeticException|com.google.gson.JsonParseException error){status="초기화 결과 확인에 실패했습니다.";}
        ClientProfileState.completeReset(receipt.correlationId(),status);
    }

    private static void acceptLifeOperation(DalmaruPayloads.OperationRequest request,DalmaruPayloads.OperationReceipt receipt){
        if(!"rejected".equals(receipt.state()))try{
            var original=com.google.gson.JsonParser.parseString(receipt.result()).getAsJsonObject();
            var expected=com.google.gson.JsonParser.parseString(request.payload()).getAsJsonObject();
            if(!original.has("characterId")||!original.get("characterId").getAsString().equals(expected.get("characterId").getAsString())||!original.has("pending"))throw new IllegalStateException("Invalid life receipt");
        }catch(RuntimeException error){ClientCharacterState.acceptList(new DalmaruPayloads.CharacterListResponse(0,"삭제 결과를 확인하는 중입니다.",ClientCharacterState.characters()));}
        requestCharacterList();
    }

    private static void acceptStatOperation(DalmaruPayloads.OperationRequest request,DalmaruPayloads.OperationReceipt receipt){
        String status="요청을 처리할 수 없습니다.";
        if(!"rejected".equals(receipt.state()))try{
            var original=com.google.gson.JsonParser.parseString(receipt.result()).getAsJsonObject();
            var expected=com.google.gson.JsonParser.parseString(request.payload()).getAsJsonObject();
            for(String key:new String[]{"statId","amount"}){
                if(!original.has(key)||!original.get(key).isJsonPrimitive()||!original.getAsJsonPrimitive(key).isNumber()||original.get(key).getAsBigDecimal().intValueExact()!=expected.get(key).getAsInt())throw new IllegalStateException("Invalid stat receipt");
            }
            status=receipt.replayed()?"이미 처리된 투자입니다. 최신 능력치를 표시합니다.":"능력치를 투자했습니다.";
        }catch(IllegalArgumentException|IllegalStateException|ArithmeticException|com.google.gson.JsonParseException error){status="투자 결과 확인에 실패했습니다.";}
        ClientProfileState.completeInvestment(receipt.correlationId(),status);
    }

    private static void acceptSkillOperation(DalmaruPayloads.OperationRequest request,DalmaruPayloads.OperationReceipt receipt){
        if("rejected".equals(receipt.state())){ClientProfileState.completeSkill(receipt.correlationId(),rejectionText(receipt.result(),"기술을 습득할 수 없습니다."));return;}
        String status="\uc694\uccad\uc744 \ucc98\ub9ac\ud560 \uc218 \uc5c6\uc2b5\ub2c8\ub2e4.";
        if(!"rejected".equals(receipt.state()))try{
            var original=com.google.gson.JsonParser.parseString(receipt.result()).getAsJsonObject();
            var expected=com.google.gson.JsonParser.parseString(request.payload()).getAsJsonObject();
            if(!original.has("skillId")||!original.has("rank")||!original.get("skillId").isJsonPrimitive()||!original.getAsJsonPrimitive("skillId").isString()||!original.get("skillId").getAsString().equals(expected.get("skillId").getAsString())||!original.get("rank").isJsonPrimitive()||!original.getAsJsonPrimitive("rank").isNumber())throw new IllegalStateException("Invalid skill receipt");
            int rank=original.get("rank").getAsBigDecimal().intValueExact();if(rank<1||rank>5)throw new IllegalStateException("Invalid skill rank");
            status=receipt.replayed()?"\uc774\ubbf8 \ucc98\ub9ac\ub41c \uc694\uccad\uc785\ub2c8\ub2e4. \ucd5c\uc2e0 \uae30\uc220 \uc0c1\ud0dc\ub97c \ud655\uc778\ud569\ub2c8\ub2e4.":original.get("skillId").getAsString()+" "+rank+"\ub2e8\uacc4\ub97c \uc2b5\ub4dd\ud588\uc2b5\ub2c8\ub2e4.";
        }catch(IllegalArgumentException|IllegalStateException|ArithmeticException|com.google.gson.JsonParseException error){status="\uacb0\uacfc \ud655\uc778 \uc911\uc785\ub2c8\ub2e4. \ucd5c\uc2e0 \uae30\uc220 \uc0c1\ud0dc\ub97c \ubd88\ub7ec\uc635\ub2c8\ub2e4.";}
        ClientProfileState.completeSkill(receipt.correlationId(),status);requestSkillRanks();requestLoadout();
    }
    static void requestStoryState(){var loadout=ClientLoadoutState.current();if(!ClientConnectionState.isCompatible()||loadout==null)return;ClientStoryState.selected(loadout.characterId());long request=ClientLoadoutState.next();ClientStoryState.queried(request);ClientPlayNetworking.send(new DalmaruPayloads.StoryStateRequest(request,loadout.characterId()));}
    static void storyOperation(String operation,String npc){
        var state=ClientStoryState.current();if(state==null||ClientStoryState.waiting()||state.get("finished").getAsBoolean())return;
        long correlation=ClientLoadoutState.next();var body=new com.google.gson.JsonObject();body.addProperty("chapterId",state.get("chapterId").getAsString());body.addProperty("npcId",npc);var request=ClientRequestState.begin(correlation,operation,body);if(request!=null){ClientStoryState.pending(correlation);ClientPlayNetworking.send(request);}
    }
    private static void acceptOperation(DalmaruPayloads.OperationReceipt receipt){
        var request=ClientRequestState.accept(receipt);if(request==null)return;
        if(java.util.Set.of("story_interact","story_report").contains(request.operation())){ClientStoryState.complete(receipt.correlationId(),receipt.result(),"rejected".equals(receipt.state()));return;}
        if("accept_quest".equals(request.operation())){acceptQuestOperation(request,receipt);return;}
        if("inherit_equipment".equals(request.operation())){acceptInheritanceOperation(request,receipt);return;}
        if("refine_item".equals(request.operation())){acceptRefinementOperation(request,receipt);return;}
        if("move_equipment".equals(request.operation())){acceptEquipmentMoveOperation(receipt);return;}
        if("update_loadout".equals(request.operation())){acceptLoadoutOperation(receipt);return;}
        if("select_profession".equals(request.operation())){acceptProfessionOperation(request,receipt);return;}
        if("confirm_advancement".equals(request.operation())||"reallocate_rogue".equals(request.operation())){ClientProfileState.note("rejected".equals(receipt.state())?rejectionText(receipt.result(),"승급 또는 재배분 조건을 확인하세요."):receipt.replayed()?"이미 처리된 요청입니다.":"처리했습니다.");return;}
        if("reset_stats".equals(request.operation())){acceptResetOperation(request,receipt);return;}
        if("delete_character".equals(request.operation())||"restore_character".equals(request.operation())){acceptLifeOperation(request,receipt);return;}
        if("list_villages".equals(request.operation())||"open_village".equals(request.operation())){ClientVillageState.accept(request.operation(),receipt.result(),"rejected".equals(receipt.state()));return;}
        if("quest_stage".equals(request.operation())){ClientQuestStageState.accept(receipt.result(),"rejected".equals(receipt.state()));return;}
        if(java.util.Set.of("vault_move","shop_trade","gather_node","craft_recipe","direct_trade","claim_mail","view_auction","bid_auction","leave_auction").contains(request.operation())){ClientPlayAccessState.accept(receipt.result(),"rejected".equals(receipt.state()));return;}
        if("invest_stat".equals(request.operation())){acceptStatOperation(request,receipt);return;}
        if("learn_skill".equals(request.operation())){acceptSkillOperation(request,receipt);return;}
        if("learn_life".equals(request.operation())||"specialize_life".equals(request.operation())||"complete_life_task".equals(request.operation())){ClientLifeState.acceptLearn(receipt.result(),"rejected".equals(receipt.state()));return;}
        if("research_craft".equals(request.operation())||"craft_learned".equals(request.operation())){ClientCraftState.accept(receipt.result(),"rejected".equals(receipt.state()));return;}
        if("begin_precision".equals(request.operation())||"step_precision".equals(request.operation())){ClientPrecisionState.accept(receipt.result(),"rejected".equals(receipt.state()));return;}
        if("place_craft_order".equals(request.operation())||"accept_craft_order".equals(request.operation())||"settle_craft_order".equals(request.operation())||"cancel_craft_order".equals(request.operation())){ClientCraftOrderState.accept(receipt.result(),"rejected".equals(receipt.state()));return;}
        if("create_home".equals(request.operation())||"enter_home".equals(request.operation())||"leave_home".equals(request.operation())||"grant_home".equals(request.operation())||"revoke_home".equals(request.operation())||"place_home".equals(request.operation())||"expand_home".equals(request.operation())||"plant_crop".equals(request.operation())||"tend_crop".equals(request.operation())||"harvest_crop".equals(request.operation())){ClientHousingState.accept(receipt.result(),"rejected".equals(receipt.state()));return;}
        if("enqueue_production".equals(request.operation())||"claim_production".equals(request.operation())||"cancel_production".equals(request.operation())){ClientProductionState.accept(receipt.result(),"rejected".equals(receipt.state()));return;}
        if("stash_market".equals(request.operation())||"list_market".equals(request.operation())||"buy_market".equals(request.operation())||"cancel_market".equals(request.operation())||"claim_market".equals(request.operation())){ClientMarketState.accept(receipt.result(),"rejected".equals(receipt.state()));ClientExchangeState.accept(receipt.result(),"rejected".equals(receipt.state()));return;}
        if("search_market".equals(request.operation())){ClientExchangeState.accept(receipt.result(),"rejected".equals(receipt.state()));return;}
        if(!"enhance_item".equals(request.operation()))return;
        if("rejected".equals(receipt.state()))ClientEquipmentState.accept(new DalmaruPayloads.EquipmentEnhanceResponse(receipt.correlationId(),false,false,0,0,"\uc694\uccad\uc744 \ucc98\ub9ac\ud560 \uc218 \uc5c6\uc2b5\ub2c8\ub2e4."));
        else{
            try{
                var result=com.google.gson.JsonParser.parseString(receipt.result()).getAsJsonObject();
                if(!result.has("success")||!result.has("enhancement")||!result.has("consecutiveFailures"))throw new IllegalStateException("Missing operation outcome");
                boolean success=result.get("success").getAsBoolean();
                ClientEquipmentState.accept(new DalmaruPayloads.EquipmentEnhanceResponse(receipt.correlationId(),true,success,result.get("enhancement").getAsInt(),result.get("consecutiveFailures").getAsInt(),receipt.replayed()?"\uc774\ubbf8 \ucc98\ub9ac\ub41c \uc694\uccad":success?"\uac15\ud654 \uc131\uacf5":"\uac15\ud654 \uc2e4\ud328"));
            }catch(IllegalArgumentException|IllegalStateException|com.google.gson.JsonParseException invalid){
                ClientEquipmentState.accept(new DalmaruPayloads.EquipmentEnhanceResponse(receipt.correlationId(),false,false,0,0,"\uacb0\uacfc \ud655\uc778\uc774 \ud544\uc694\ud569\ub2c8\ub2e4."));
            }
        }
        requestEquipment();
    }

	public static void requestEnhancement(java.util.UUID equipmentId) {
		long correlation=ClientEquipmentState.nextRequestId();var body=new com.google.gson.JsonObject();body.addProperty("equipmentId",equipmentId.toString());
        var request=ClientRequestState.begin(correlation,"enhance_item",body);
        if(request!=null){ClientEquipmentState.accept(new DalmaruPayloads.EquipmentEnhanceResponse(correlation,false,false,0,0,"\uac15\ud654 \uacb0\uacfc \ud655\uc778 \uc911\uc785\ub2c8\ub2e4."));ClientPlayNetworking.send(request);}
        else ClientEquipmentState.accept(new DalmaruPayloads.EquipmentEnhanceResponse(correlation,false,false,0,0,"\uce90\ub9ad\ud130 \uc815\ubcf4 \ud655\uc778 \uc911\uc785\ub2c8\ub2e4."));
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
    static void updateLoadout(java.util.List<String> slots){
        var current=ClientLoadoutState.current();if(current==null||!current.editable()||ClientLoadoutState.waiting())return;
        long correlation=ClientLoadoutState.next();var body=new com.google.gson.JsonObject();body.addProperty("revision",current.revision());var array=new com.google.gson.JsonArray();slots.forEach(array::add);body.add("slots",array);
        var request=ClientRequestState.begin(correlation,"update_loadout",body);if(request==null)return;
        ClientLoadoutState.pending(correlation);ClientLoadoutState.delegateRetry();ClientPlayNetworking.send(request);
    }
    static void readyRun(){if(!ClientRunState.preparing()||ClientRunState.preparationId()==null)return;ClientPlayNetworking.send(new DalmaruPayloads.RunReadyRequest(ClientRunState.preparationId(),true));}
	public static void openParty(){var client=net.minecraft.client.Minecraft.getInstance();if(client!=null)client.setScreen(new DalmaruPartyScreen(client.screen));}
	public static void requestParty(String action){requestParty(action,"{}");}
	public static void requestParty(String action,String body){ClientPartyState.loading();if(!ClientConnectionState.isCompatible()){ClientPartyState.reject("거절: 서버 연결 후에 확인할 수 있습니다.");return;}ClientPlayNetworking.send(new DalmaruPayloads.P4Request(ClientPartyState.nextId(),action,body));}

}
