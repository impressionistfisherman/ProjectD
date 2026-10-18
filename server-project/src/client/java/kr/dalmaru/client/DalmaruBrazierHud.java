package kr.dalmaru.client;

import kr.dalmaru.DalmaruMod;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

final class DalmaruBrazierHud {
	private DalmaruBrazierHud() { }

	static void register() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(DalmaruMod.MOD_ID, "brazier"), (graphics, delta) -> {
			String line = ClientBrazierState.line();
			String neglect = ClientBrazierState.neglectLine();
			String boss = ClientBrazierState.bossLine();
			if (line.isEmpty() && neglect.isEmpty() && boss.isEmpty()) return;
			var client = net.minecraft.client.Minecraft.getInstance();
			int x = graphics.guiWidth() / 2 - 91;
			int y = graphics.guiHeight() - 84;
			if (!line.isEmpty()) graphics.text(client.font, line, x, y, 0xFFFFFFFF, false);
			if (!neglect.isEmpty()) graphics.text(client.font, neglect, x, y - 12, 0xFFFFCC55, false);
			if (!boss.isEmpty()) graphics.text(client.font, boss, x, y - (neglect.isEmpty() ? 12 : 24), 0xFFFF6666, false);
		});
	}
}
