package RealMarket.realmarket.client;

import RealMarket.realmarket.network.ModMessages;
import RealMarket.realmarket.network.PacketTrade;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class TradeScreen extends Screen {
    private final double balance;
    private int amount = 1;

    public TradeScreen(double balance) {
        super(Component.literal("Market Terminal"));
        this.balance = balance;
    }

    @Override
    protected void init() {
        int x = this.width / 2;
        int y = this.height / 2;

        // Кнопка КУПИТИ
        this.addRenderableWidget(Button.builder(Component.literal("BUY (Diamond)"), b -> {
            ModMessages.sendToServer(new PacketTrade(amount, true));
        }).bounds(x - 110, y + 10, 100, 20).build());

        // Кнопка ПРОДАТИ
        this.addRenderableWidget(Button.builder(Component.literal("SELL Hand"), b -> {
            ModMessages.sendToServer(new PacketTrade(amount, false));
        }).bounds(x + 10, y + 10, 100, 20).build());

        // Керування кількістю
        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> amount++).bounds(x + 50, y - 20, 20, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> { if(amount > 1) amount--; }).bounds(x - 70, y - 20, 20, 20).build());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gui);
        int x = this.width / 2;
        int y = this.height / 2;

        gui.drawCenteredString(this.font, "§b§lMARKET TERMINAL", x, y - 60, 0xFFFFFF);
        gui.drawCenteredString(this.font, "Balance: §6" + balance + " Coins", x, y - 45, 0xFFFFFF);
        gui.drawCenteredString(this.font, "Quantity: §e" + amount, x, y - 15, 0xFFFFFF);

        super.render(gui, mouseX, mouseY, partialTick);
    }
}