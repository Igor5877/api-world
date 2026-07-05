package com.skyblock.dynamic.teamsclient.mixin;

import dev.ftb.mods.ftbteams.api.client.KnownClientPlayer;
import dev.ftb.mods.ftbteams.client.gui.MemberButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = MemberButton.class, remap = false)
public interface MemberButtonAccessor {
    @Accessor("player")
    KnownClientPlayer nestworld$getPlayer();
}
