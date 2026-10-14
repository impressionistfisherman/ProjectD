package kr.dalmaru.mixin;

import kr.dalmaru.network.DalmaruNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class RunRespawnMixin {
    @Shadow public ServerPlayer player;
    // This invocation follows PacketUtils.ensureRunningOnSameThread.
    @Inject(method="handleClientCommand",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;resetLastActionTime()V"),cancellable=true)
    private void dalmaru$waitForRunRevive(ServerboundClientCommandPacket packet,CallbackInfo callback){
        if(packet.getAction()==ServerboundClientCommandPacket.Action.PERFORM_RESPAWN&&!DalmaruNetworking.canRunRespawn(player))callback.cancel();
    }
}
