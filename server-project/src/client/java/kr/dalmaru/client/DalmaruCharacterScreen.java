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
	private Button statusButton;
	private Button createButton;
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
			.bounds(width / 2 - 75, height / 2 + 100, 150, 20).build());
	}

	@Override
	public void tick() {
		refreshButton.active = ClientCharacterState.canRequestList();
		statusButton.setMessage(Component.literal(ClientCharacterState.status()));
		Map<Integer, DalmaruPayloads.CharacterSummary> bySlot = new HashMap<>();
		for (DalmaruPayloads.CharacterSummary character : ClientCharacterState.characters()) bySlot.put(character.slot(), character);
		for (int slot = 1; slot <= 4; slot++) {
			DalmaruPayloads.CharacterSummary character = bySlot.get(slot);
			slotButtons.get(slot).setMessage(Component.literal(character == null ? "슬롯 " + slot + ": 비어 있음" : "슬롯 " + slot + ": " + character.displayName() + (character.active() ? " (선택됨)" : "")));
			slotButtons.get(slot).active = character != null && ClientCharacterState.canSelect() && (ClientCharacterState.requiresSelection() || !character.active());
		}
		createButton.active = firstEmptySlot() != 0 && ClientCharacterState.canCreate() && !nameField.getValue().isBlank();
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
				DalmaruClient.requestCharacterSelection(character.characterId());
				return;
			}
		}
	}

	@Override
	public void onClose() { minecraft.setScreen(null); }
}
