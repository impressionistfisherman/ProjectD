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
            var client=net.minecraft.client.Minecraft.getInstance();
            String mode=response.combatMode()?"전투 · 회피 "+response.dodgeCharges()+" · 방어 "+response.guardMeter():"생활";
            graphics.text(client.font,mode+(response.remainingCombatTicks()>0?" · "+((response.remainingCombatTicks()+19)/20)+"초":""),x,y-12,0xFFFFFFFF,false);
            if(response.combatMode()){
                int slotWidth=Math.min(70,(graphics.guiWidth()-20)/5),left=(graphics.guiWidth()-slotWidth*5)/2;
                var profile=ClientProfileState.current();
                for(int slot=0;slot<5;slot++){
                    String id=response.slots().get(slot),name=slot==4?"궁극 잠금":"비움";
                    boolean unlearned=!id.isEmpty()&&ClientSkillState.rank(id)<1;
                    if(profile!=null&&!profile.profile().profession().isBlank()&&!id.isEmpty())try{name=kr.dalmaru.skill.SkillDefinition.find(kr.dalmaru.account.CharacterProfession.valueOf(profile.profile().profession()),id).koreanName();}catch(IllegalArgumentException ignored){name="미해금";}
                    if(unlearned)name="미습득 (잠금)";
                    String key=client.options.keyHotbarSlots[slot].getTranslatedKeyMessage().getString();int cooldown=response.cooldowns().get(slot);
                    name=client.font.plainSubstrByWidth(name,Math.max(0,slotWidth-4-client.font.width(key+" ")));
                    graphics.text(client.font,key+" "+name,left+slot*slotWidth,y-34,unlearned||slot==4||cooldown>0?0xFFAAAAAA:0xFFFFFFFF,false);
                    if(cooldown>0)graphics.text(client.font,((cooldown+19)/20)+"초",left+slot*slotWidth,y-24,0xFFE28A2A,false);
                }
            }

		});
	}
}
