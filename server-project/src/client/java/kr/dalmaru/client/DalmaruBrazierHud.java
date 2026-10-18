package kr.dalmaru.client;

import kr.dalmaru.DalmaruMod;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

final class DalmaruBrazierHud {
	private DalmaruBrazierHud() { }

	static void register() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(DalmaruMod.MOD_ID, "brazier"), (graphics, delta) -> {
			var lines = ClientBrazierState.lines();
			if (lines.isEmpty()) return;
			var client = net.minecraft.client.Minecraft.getInstance();
			int x = graphics.guiWidth() / 2 - 91;
			int y = graphics.guiHeight() - 84;
			for (int index = lines.size() - 1; index >= 0; index--) {
				String text = lines.get(index);
				int color = text.contains("보스") ? 0xFFFF6666 : text.contains("방치") || text.contains("경고") || text.contains("피해") ? 0xFFFFCC55 : 0xFFFFFFFF;
				graphics.text(client.font, text, x, y - (lines.size() - 1 - index) * 10, color, false);
			}
		});
	}
}
