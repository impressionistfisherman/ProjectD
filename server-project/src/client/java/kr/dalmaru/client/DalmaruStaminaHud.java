package kr.dalmaru.client;

import kr.dalmaru.DalmaruMod;
import kr.dalmaru.network.DalmaruPayloads;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

final class DalmaruStaminaHud {
	private DalmaruStaminaHud() { }
	static void register() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(DalmaruMod.MOD_ID, "stamina"), (graphics, delta) -> {
			DalmaruPayloads.CombatStateResponse response = ClientCombatState.current();
			if (response == null || response.maximumStamina() < 1) return;
			int x = graphics.guiWidth() / 2 - 91;
			int y = graphics.guiHeight() - 68;
			graphics.fill(x, y, x + 182, y + 6, 0xAA202020);
			int filled = 180 * response.currentStamina() / response.maximumStamina();
			graphics.fill(x + 1, y + 1, x + 1 + filled, y + 5, response.inCombat() ? 0xFFE28A2A : 0xFF3696D6);
		});
	}
}
