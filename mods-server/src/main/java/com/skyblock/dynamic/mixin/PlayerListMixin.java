package com.skyblock.dynamic.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public class PlayerListMixin {

    @Inject(method = "placeNewPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"), cancellable = true)
    private void onPlaceNewPlayer(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "remove", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"), cancellable = true)
    private void onRemove(net.minecraft.server.level.ServerPlayer p_11394_, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        ci.cancel();
    }
}
