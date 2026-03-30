package net.market.realmarket.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class TradeScreen extends Screen {
    private final double balance;

    public TradeScreen(double balance) {
        super(Component.literal("Market Terminal"));
        this.balance = balance;
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.literal("Close Terminal"), b -> this.onClose())
                .bounds(this.width / 2 - 60, this.height / 2 + 40, 120, 20).build());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gui);
        int x = this.width / 2;
        int y = this.height / 2;

        gui.drawCenteredString(this.font, "§b§lMARKET TERMINAL", x, y - 50, 0xFFFFFF);
        gui.drawCenteredString(this.font, "§7Current Balance:", x, y - 20, 0xAAAAAA);

        String balanceStr = (balance < 0) ? "§cAPI OFFLINE" : "§6" + balance + " Coins";
        gui.drawCenteredString(this.font, balanceStr, x, y - 5, 0xFFFFFF);

        super.render(gui, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}