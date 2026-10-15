package kr.dalmaru.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruGuideScreen extends Screen {
	private Button probeButton;

	DalmaruGuideScreen() {
		super(Component.literal("달마루"));
	}

	@Override
	protected void init() {
        addRenderableWidget(Button.builder(Component.literal("역참 귀환"),button->minecraft.setScreen(new DalmaruWaypointScreen()))
            .bounds(width/2-75,height/2+16,150,20).build());
		probeButton = addRenderableWidget(Button.builder(Component.literal("연결 확인"), button -> DalmaruClient.requestProbe())
			.bounds(width / 2 - 75, height / 2 - 10, 150, 20).build());
	}

	@Override
	public void tick() {
		probeButton.active = ClientConnectionState.canRequestProbe();
		probeButton.setMessage(Component.literal(ClientConnectionState.probeButtonLabel()));
	}
}
