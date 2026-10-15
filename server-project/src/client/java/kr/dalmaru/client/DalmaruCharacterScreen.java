package kr.dalmaru.client;

import java.util.HashMap;
import java.util.Map;
import kr.dalmaru.network.DalmaruPayloads;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruCharacterScreen extends Screen {
	private Button refreshButton;
	private Button deleteButton;
	private Button restoreButton;
	private Button statusButton;
	private Button createButton;
	private java.util.UUID chosen;
	private boolean chosenPending;
	private boolean confirmDelete;
	private final Map<Integer, Button> slotButtons = new HashMap<>();
	private EditBox nameField;

	DalmaruCharacterScreen() { super(Component.literal("달마루 캐릭터")); }

	@Override
	protected void init() {
		statusButton = addRenderableWidget(Button.builder(Component.literal(""), button -> { })
			.bounds(width / 2 - 150, height / 2 - 65, 300, 20).build());
		statusButton.active = false;
		nameField = addRenderableWidget(new EditBox(font, width / 2 - 150, height / 2 - 40, 200, 20, Component.literal("캐릭터 이름")));
		nameField.setHint(Component.literal("캐릭터 이름"));
		createButton = addRenderableWidget(Button.builder(Component.literal("생성하기"), button -> createFirstEmptySlot())
			.bounds(width / 2 + 55, height / 2 - 40, 95, 20).build());
		for (int slot = 1; slot <= 4; slot++) {
			int selectedSlot = slot;
			Button button = addRenderableWidget(Button.builder(Component.literal(""), ignored -> selectSlot(selectedSlot))
				.bounds(width / 2 - 150, height / 2 - 10 + (slot - 1) * 25, 300, 20).build());
			button.active = false;
			slotButtons.put(slot, button);
		}
		refreshButton = addRenderableWidget(Button.builder(Component.literal("목록 새로 고침"), button -> DalmaruClient.requestCharacterList())
			.bounds(width / 2 - 150, height / 2 + 100, 140, 20).build());
		deleteButton = addRenderableWidget(Button.builder(Component.literal("삭제"), button -> {
			if (chosen == null || chosenPending) return;
			if (!confirmDelete) { confirmDelete = true; return; }
			confirmDelete = false;
			DalmaruClient.requestCharacterLife(chosen, false);
		}).bounds(width / 2 - 5, height / 2 + 100, 70, 20).build());
		restoreButton = addRenderableWidget(Button.builder(Component.literal("복구"), button -> { if (chosen != null && chosenPending) DalmaruClient.requestCharacterLife(chosen, true); })
			.bounds(width / 2 + 70, height / 2 + 100, 80, 20).build());
	}

	@Override
	public void tick() {
		refreshButton.active = ClientCharacterState.canRequestList();
		statusButton.setMessage(Component.literal(ClientCharacterState.status()));
		Map<Integer, DalmaruPayloads.CharacterSummary> bySlot = new HashMap<>();
		for (DalmaruPayloads.CharacterSummary character : ClientCharacterState.characters()) bySlot.put(character.slot(), character);
		for (int slot = 1; slot <= 4; slot++) {
			DalmaruPayloads.CharacterSummary character = bySlot.get(slot);
			slotButtons.get(slot).setMessage(Component.literal(character == null ? "슬롯 " + slot + ": 비어 있음" : "슬롯 " + slot + ": " + character.displayName() + (character.pendingDeletion() ? " (삭제 대기)" : character.active() ? " (선택됨)" : "")));
			slotButtons.get(slot).active = character != null && (character.pendingDeletion() || (ClientCharacterState.canSelect() && (ClientCharacterState.requiresSelection() || !character.active())));
		}
		createButton.active = firstEmptySlot() != 0 && ClientCharacterState.canCreate() && !nameField.getValue().isBlank();
		deleteButton.setMessage(Component.literal(confirmDelete ? "확인: 삭제" : "삭제"));
		deleteButton.active = chosen != null && !chosenPending;
		restoreButton.active = chosen != null && chosenPending;
	}

	private void createFirstEmptySlot() {
		int slot = firstEmptySlot();
		if (slot != 0) DalmaruClient.requestCharacterCreation(slot, nameField.getValue());
	}

	private int firstEmptySlot() {
		for (int slot = 1; slot <= 4; slot++) {
			int candidateSlot = slot;
			boolean occupied = ClientCharacterState.characters().stream().anyMatch(character -> character.slot() == candidateSlot);
			if (!occupied) return slot;
		}
		return 0;
	}

	private void selectSlot(int slot) {
		for (DalmaruPayloads.CharacterSummary character : ClientCharacterState.characters()) {
			if (character.slot() == slot) {
				chosen = character.characterId();
				chosenPending = character.pendingDeletion();
				confirmDelete = false;
				if (!chosenPending) DalmaruClient.requestCharacterSelection(character.characterId());
				return;
			}
		}
	}

	@Override
	public void onClose() { minecraft.setScreen(null); }
}
