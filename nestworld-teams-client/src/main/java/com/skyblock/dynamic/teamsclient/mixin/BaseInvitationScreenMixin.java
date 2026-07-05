package com.skyblock.dynamic.teamsclient.mixin;

import com.skyblock.dynamic.teamsclient.gui.InviteByNameScreen;
import com.skyblock.dynamic.teamsclient.net.ClientNetworkHandler;
import dev.ftb.mods.ftbteams.client.gui.BaseInvitationScreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * On the Nestworld network the stock invitation screen is useless (it lists
 * only players connected to this island server), so opening it is cancelled
 * and our invite-by-name screen is shown instead. Singleplayer and foreign
 * servers keep the stock behaviour.
 */
@Mixin(value = BaseInvitationScreen.class, remap = false)
public abstract class BaseInvitationScreenMixin {

    @Inject(method = "onInit", at = @At("HEAD"), cancellable = true)
    private void nestworld$redirectToInviteByName(CallbackInfoReturnable<Boolean> cir) {
        if (ClientNetworkHandler.isManagedNetwork()) {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> mc.setScreen(new InviteByNameScreen(null)));
            cir.setReturnValue(false);
        }
    }
}
