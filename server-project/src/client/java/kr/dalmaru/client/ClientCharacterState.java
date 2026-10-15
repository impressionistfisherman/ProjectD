package kr.dalmaru.client;

import java.util.List;
import java.util.UUID;
import kr.dalmaru.network.DalmaruPayloads;

final class ClientCharacterState {
	private static long nextRequestId = 1;
	private static long currentRequestId;
	private static boolean waiting;
	private static int cooldownTicks;
	private static boolean listRetryPending;
	private static long nextSelectionRequestId = 1;
	private static long currentSelectionRequestId;
	private static boolean selectionWaiting;
	private static int selectionDeadlineTick;
	private static int tick;
	private static long nextCreationRequestId = 1;
	private static long currentCreationRequestId;
	private static boolean creationWaiting;
	private static int creationDeadlineTick;
	private static boolean selectionReady;
	private static boolean selectionConfirmed;
	private static String status = "캐릭터 목록을 불러오세요.";
	private static List<DalmaruPayloads.CharacterSummary> characters = List.of();

	private ClientCharacterState() { }

	static void resetForConnection() {
		nextRequestId = 1;
		currentRequestId = 0;
		waiting = false;
		cooldownTicks = 0;
		listRetryPending = false;
		nextSelectionRequestId = 1;
		currentSelectionRequestId = 0;
		selectionWaiting = false;
		selectionDeadlineTick = 0;
		tick = 0;
		nextCreationRequestId = 1;
		currentCreationRequestId = 0;
		creationWaiting = false;
		creationDeadlineTick = 0;
		selectionReady = false;
		selectionConfirmed = false;
		status = "캐릭터 목록을 불러오세요.";
		characters = List.of();
	}

	static void markSelectionReady() {
		selectionReady = true;
		status = "캐릭터를 선택하거나 생성하세요.";
	}

	static boolean beginCreation(int slot, String displayName) {
		if (!ClientConnectionState.isCompatible() || creationWaiting) return false;
		currentCreationRequestId = nextCreationRequestId++;
		creationWaiting = true;
		creationDeadlineTick = tick + 100;
		status = "캐릭터를 생성하는 중입니다.";
		return true;
	}

	static void acceptCreation(DalmaruPayloads.CharacterCreateResponse response) {
		if (!creationWaiting || response.requestId() != currentCreationRequestId) return;
		creationWaiting = false;
		status = response.status();
		if (response.accepted()) {
			characters = java.util.stream.Stream.concat(characters.stream(), java.util.stream.Stream.of(new DalmaruPayloads.CharacterSummary(response.characterId(), response.slot(), response.displayName(), false, false))).sorted(java.util.Comparator.comparingInt(DalmaruPayloads.CharacterSummary::slot)).toList();
		}
	}

	static boolean beginSelection(UUID characterId) {
		if (!ClientConnectionState.isCompatible() || selectionWaiting) return false;
		currentSelectionRequestId = nextSelectionRequestId++;
		selectionWaiting = true;
		selectionDeadlineTick = tick + 100;
		status = "캐릭터 선택을 저장하는 중입니다.";
		return true;
	}

	static boolean acceptSelection(DalmaruPayloads.CharacterSelectResponse response) {
		if (!selectionWaiting || response.requestId() != currentSelectionRequestId) return false;
		selectionWaiting = false;
		status = response.status();
		if (response.accepted()) {
			selectionConfirmed = true;
			characters = characters.stream()
				.map(character -> new DalmaruPayloads.CharacterSummary(character.characterId(), character.slot(), character.displayName(), character.characterId().equals(response.characterId()), character.pendingDeletion()))
				.toList();
		}
		return response.accepted();
	}

	static boolean beginList() {
		if (!ClientConnectionState.isCompatible() || waiting || cooldownTicks > 0) return false;
		currentRequestId = nextRequestId++;
		waiting = true;
		cooldownTicks = 25;
		status = "캐릭터 목록을 불러오는 중입니다.";
		return true;
	}

	static void acceptList(DalmaruPayloads.CharacterListResponse response) {
		if (!waiting || response.requestId() != currentRequestId) return;
		waiting = false;
		if (response.status().equals("요청이 너무 빠릅니다. 잠시 후 다시 시도하세요.")) {
			cooldownTicks = 25;
			listRetryPending = true;
			status = "캐릭터 목록을 다시 불러오는 중입니다.";
			return;
		}
		status = response.status();
		if (response.status().equals("캐릭터 목록을 불러왔습니다.")) characters = response.characters();
	}

	static void tick() {
		tick++;
		if (cooldownTicks > 0) cooldownTicks--;
		if (selectionWaiting && tick >= selectionDeadlineTick) {
			selectionWaiting = false;
			status = "캐릭터 선택 응답 시간이 초과되었습니다. 다시 시도하세요.";
		}
		if (creationWaiting && tick >= creationDeadlineTick) {
			creationWaiting = false;
			status = "캐릭터 생성 응답 시간이 초과되었습니다. 다시 시도하세요.";
		}
	}

	static boolean canRequestList() { return ClientConnectionState.isCompatible() && !waiting && cooldownTicks == 0; }
	static boolean shouldRetryList() {
		if (!listRetryPending || !canRequestList()) return false;
		listRetryPending = false;
		return true;
	}
	static long currentRequestId() { return currentRequestId; }
	static long currentSelectionRequestId() { return currentSelectionRequestId; }
	static long currentCreationRequestId() { return currentCreationRequestId; }
	static String status() { return status; }
	static List<DalmaruPayloads.CharacterSummary> characters() { return characters; }
	static boolean canSelect() { return ClientConnectionState.isCompatible() && !selectionWaiting; }
	static boolean canCreate() { return ClientConnectionState.isCompatible() && !creationWaiting; }
	static boolean requiresSelection() { return selectionReady && !selectionConfirmed; }
	static boolean isSelectionConfirmed() { return selectionConfirmed; }
	private static long lifeRequestId = 1;
	static long nextLifeRequestId() { return lifeRequestId++; }
}
