package com.skyblock.dynamic.teamsclient.mixin;

import com.skyblock.dynamic.teamsclient.net.ClientNetworkHandler;
import com.skyblock.dynamic.teamsclient.net.TeamActionC2S;
import dev.ftb.mods.ftbteams.api.client.KnownClientPlayer;
import dev.ftb.mods.ftbteams.client.gui.MemberButton;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * On the Nestworld network, clicking a team member in the FTB Teams GUI must
 * not use FTB Teams' own kick/promote packets (local-only, reverted by the
 * server addon's party guard). Instead a confirm dialog routes the kick
 * through the Nestworld API. Clicking yourself keeps the stock behaviour.
 */
@Mixin(value = MemberButton.class, remap = false)
public abstract class MemberButtonMixin {

    @Inject(method = "onClicked", at = @At("HEAD"), cancellable = true)
    private void nestworld$kickViaApi(MouseButton button, CallbackInfo ci) {
        if (!ClientNetworkHandler.isManagedNetwork()) {
            return;
        }
        KnownClientPlayer member = ((MemberButtonAccessor) this).nestworld$getPlayer();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || member.id().equals(mc.player.getUUID())) {
            return; // own entry: stock behaviour (leave etc. still go through /team or /nwteam)
        }
        ci.cancel();
        mc.execute(() -> mc.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                ClientNetworkHandler.send(TeamActionC2S.kick(member.id()));
            }
            mc.setScreen(null);
        },
                Component.translatable("nestworld_teams.gui.kick_title"),
                Component.translatable("nestworld_teams.gui.kick_question", member.name()))));
    }
}
