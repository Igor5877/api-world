package com.nguyendevs.freesia.worker.mixin;

import com.mojang.authlib.GameProfile;
import com.nguyendevs.freesia.common.EntryPoint;
import com.nguyendevs.freesia.worker.ServerLoader;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {

    @Shadow
    @Nullable
    String requestedUsername;

    @Shadow
    abstract void startClientVerification(GameProfile gameProfile);

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    public void onHandleHello(@NotNull ServerboundHelloPacket serverboundHelloPacket, CallbackInfo ci) {
        this.requestedUsername = serverboundHelloPacket.name();
        final GameProfile requestedProfile = new GameProfile(serverboundHelloPacket.profileId(), this.requestedUsername);

        ServerLoader.workerConnection.getPlayerData(requestedProfile.getId(), data -> {
            if (data != null) {
                ServerLoader.playerDataCache.put(requestedProfile.getId(), data);
            }

            EntryPoint.LOGGER_INST.info("Pre-loaded player data for player {}.", requestedProfile.getName());
            this.startClientVerification(requestedProfile);
        });

        ci.cancel();
    }
}

