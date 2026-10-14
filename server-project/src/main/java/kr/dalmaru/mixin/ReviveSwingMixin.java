package kr.dalmaru.mixin;

import kr.dalmaru.network.DalmaruNetworking;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ReviveSwingMixin {
    @Shadow public ServerPlayer player;
    @Inject(method="handleAnimate",at=@At("TAIL"))
    private void dalmaru$attackEndsImmunity(ServerboundSwingPacket packet,CallbackInfo result){DalmaruNetworking.attackStarted(player);}
}
