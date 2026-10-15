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
		ClientRunState.resetForConnection();ClientLoadoutState.reset();runReviveState=null;ClientRequestState.reset();
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
			ClientRunState.resetForConnection();ClientLoadoutState.reset();runReviveState=null;ClientRequestState.reset();
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
                    ClientLoadoutState.selected(payload.characterId());ClientSkillState.selected();ClientProfileState.selected(payload.characterId());ClientEquipmentState.selected();ClientRequestState.selected(payload.characterId());requestLoadout();requestSkillRanks();
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

    public static void requestCharacterStatReset() {
        if(!ClientProfileState.beginReset())return;
        long correlation=ClientProfileState.currentResetRequestId();
        var request=ClientRequestState.begin(correlation,"reset_stats",new com.google.gson.JsonObject());
        if(request==null){ClientProfileState.completeReset(correlation,"캐릭터 정보 확인 중입니다.");return;}
        ClientProfileState.delegateResetRetry();ClientPlayNetworking.send(request);
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
		ClientPlayNetworking.send(new DalmaruPayloads.QuestRequest(ClientQuestState.beginRequest(), accept));
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
    private static void acceptEquipmentMoveOperation(DalmaruPayloads.OperationReceipt receipt){
        ClientEquipmentState.movementStatus("rejected".equals(receipt.state())?"장비 소유권·직업·레벨·전투 상태를 확인하세요.":"최신 장비 상태를 확인합니다.");requestEquipment();
    }

    private static void acceptLoadoutOperation(DalmaruPayloads.OperationReceipt receipt){
        ClientLoadoutState.completeOperation(receipt.correlationId());requestLoadout();
    }

    private static void acceptProfessionOperation(DalmaruPayloads.OperationRequest request,DalmaruPayloads.OperationReceipt receipt){
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
            if(!original.has("unspentStatPoints")||!original.get("unspentStatPoints").isJsonPrimitive()||!original.getAsJsonPrimitive("unspentStatPoints").isNumber()||original.get("unspentStatPoints").getAsBigDecimal().intValueExact()<0||!original.has("freeResetAvailable")||!original.get("freeResetAvailable").isJsonPrimitive()||!original.getAsJsonPrimitive("freeResetAvailable").isBoolean()||original.get("freeResetAvailable").getAsBoolean())throw new IllegalStateException("Invalid reset receipt");
            status=receipt.replayed()?"이미 처리된 초기화입니다. 최신 능력치를 표시합니다.":"능력치를 초기화했습니다.";
        }catch(IllegalArgumentException|IllegalStateException|ArithmeticException|com.google.gson.JsonParseException error){status="초기화 결과 확인에 실패했습니다.";}
        ClientProfileState.completeReset(receipt.correlationId(),status);
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
    private static void acceptOperation(DalmaruPayloads.OperationReceipt receipt){
        var request=ClientRequestState.accept(receipt);if(request==null)return;
        if("move_equipment".equals(request.operation())){acceptEquipmentMoveOperation(receipt);return;}
        if("update_loadout".equals(request.operation())){acceptLoadoutOperation(receipt);return;}
        if("select_profession".equals(request.operation())){acceptProfessionOperation(request,receipt);return;}
        if("reset_stats".equals(request.operation())){acceptResetOperation(request,receipt);return;}
        if("invest_stat".equals(request.operation())){acceptStatOperation(request,receipt);return;}
        if("learn_skill".equals(request.operation())){acceptSkillOperation(request,receipt);return;}
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

}
