package com.skyblock.dynamic.teamsclient.gui;

import com.skyblock.dynamic.teamsclient.net.ClientNetworkHandler;
import com.skyblock.dynamic.teamsclient.net.TeamActionC2S;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Replacement for FTB Teams' InviteScreen on the Nestworld network: the stock
 * screen can only list players known to THIS island server, but friends are
 * usually on the hub or their own island. Here the player types a name and the
 * invite goes through the Nestworld API.
 */
public class InviteByNameScreen extends Screen {
    private final @Nullable Screen parent;
    private EditBox nameBox;

    public InviteByNameScreen(@Nullable Screen parent) {
        super(Component.translatable("nestworld_teams.gui.invite_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int cy = height / 2;

        nameBox = new EditBox(font, cx - 100, cy - 20, 200, 20,
                Component.translatable("nestworld_teams.gui.player_name"));
        nameBox.setMaxLength(16);
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        addRenderableWidget(Button.builder(Component.translatable("nestworld_teams.gui.invite_button"),
                        b -> sendInvite())
                .bounds(cx - 100, cy + 10, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(cx + 2, cy + 10, 98, 20).build());
    }

    private void sendInvite() {
        String name = nameBox.getValue().trim();
        if (!name.isEmpty()) {
            ClientNetworkHandler.send(TeamActionC2S.invite(name));
            onClose();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // Enter / numpad Enter
            sendInvite();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 45, 0xFFFFFF);
        graphics.drawCenteredString(font,
                Component.translatable("nestworld_teams.gui.invite_hint"),
                width / 2, height / 2 - 33, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
