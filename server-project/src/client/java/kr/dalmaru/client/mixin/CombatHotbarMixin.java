package kr.dalmaru.client.mixin;

import kr.dalmaru.client.DalmaruClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Minecraft.class)
public abstract class CombatHotbarMixin {
    @Redirect(method="handleKeybinds",at=@At(value="INVOKE",target="Lnet/minecraft/client/KeyMapping;consumeClick()Z"))
    private boolean dalmaru$combatSlot(KeyMapping mapping){
        boolean pressed=mapping.consumeClick();if(!pressed)return false;
        Minecraft client=(Minecraft)(Object)this;
        for(int index=0;index<5;index++)if(mapping==client.options.keyHotbarSlots[index]&&DalmaruClient.combatHotbar(index+1))return false;
        return true;
    }
}
