package kr.dalmaru.client.mixin;

import java.util.List;
import kr.dalmaru.client.DalmaruClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DeathScreen.class)
public abstract class RunDeathScreenMixin extends Screen {
    @Shadow @Final private List<Button> exitButtons;
    protected RunDeathScreenMixin(Component title){super(title);}
    @Inject(method="tick",at=@At("TAIL"))
    private void dalmaru$reviveButton(CallbackInfo callback){
        var status=DalmaruClient.runReviveState();
        if(status==null||!status.dead()||exitButtons.isEmpty())return;
        var button=exitButtons.getFirst();button.active=status.clickTicks()==0;
        button.setMessage(Component.literal(status.failed()?"관전":status.clickTicks()>0?((status.clickTicks()+19)/20)+"초 후 부활 가능":"부활"));
    }
    @Inject(method="extractRenderState",at=@At("TAIL"))
    private void dalmaru$reviveInfo(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta,CallbackInfo callback){
        var status=DalmaruClient.runReviveState();
        if(status==null||!status.dead())return;
        String count="남은 부활 "+status.remainingRevives()+"회";
        String wait=status.failed()?"부활 불가 · 관전 가능":"자동 부활까지 "+((status.autoTicks()+19)/20)+"초";
        graphics.text(font,count,(width-font.width(count))/2,height/4+116,0xFFFFFFFF,false);
        graphics.text(font,wait,(width-font.width(wait))/2,height/4+126,0xFFFFFFFF,false);
    }
}
