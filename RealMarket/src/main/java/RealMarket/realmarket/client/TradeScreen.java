package RealMarket.realmarket.client;

import RealMarket.realmarket.network.ModMessages;
import RealMarket.realmarket.network.PacketShopAction;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class TradeScreen extends Screen {
    private final double balance;
    private final double unitPrice;
    private int amount = 1;

    public TradeScreen(double balance, double unitPrice) {
        super(Component.literal("Market Terminal"));
        this.balance = balance;
        this.unitPrice = unitPrice;
    }

    @Override
    protected void init() {
        int x = this.width / 2;
        int y = this.height / 2;

        // Кнопки вибору кількості (Центральний ряд)
        addRenderableWidget(Button.builder(Component.literal("-10"), b -> change(-10)).bounds(x - 95, y - 10, 35, 20).build());
        addRenderableWidget(Button.builder(Component.literal("-1"), b -> change(-1)).bounds(x - 55, y - 10, 25, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+1"), b -> change(1)).bounds(x + 30, y - 10, 25, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+10"), b -> change(10)).bounds(x + 60, y - 10, 35, 20).build());

        // Кнопки дій (Нижній ряд)
        addRenderableWidget(Button.builder(Component.literal("§aКУПИТИ"), b -> send(0)).bounds(x - 100, y + 35, 95, 20).build());
        addRenderableWidget(Button.builder(Component.literal("§6ПРОДАТИ"), b -> send(1)).bounds(x + 5, y + 35, 95, 20).build());

        // Кнопка закриття
        addRenderableWidget(Button.builder(Component.literal("Вихід"), b -> this.onClose()).bounds(x - 40, y + 70, 80, 20).build());
    }

    private void change(int v) {
        this.amount = Math.max(1, Math.min(64, this.amount + v));
    }

    private void send(int type) {
        ModMessages.sendToServer(new PacketShopAction(type, (double) amount));
        this.onClose();
    }

    @Override
    public void render(GuiGraphics gui, int mx, int my, float pt) {
        // Темний фон поверх гри
        this.renderBackground(gui);

        int x = this.width / 2;
        int y = this.height / 2;

        // Малюємо декоративну рамку терміналу (напівпрозора)
        gui.fill(x - 110, y - 90, x + 110, y + 100, 0x99000000);
        gui.renderOutline(x - 110, y - 90, 220, 190, 0xFF00AAFF);

        // Заголовок
        gui.drawCenteredString(this.font, "§b§lMARKET TERMINAL", x, y - 80, 0xFFFFFF);

        // Баланс
        String balText = (balance < 0) ? "§cAPI OFFLINE" : "§6" + balance + " Coins";
        gui.drawCenteredString(this.font, "Баланс: " + balText, x, y - 60, 0xFFFFFF);

        // Кількість
        gui.drawCenteredString(this.font, "§7Кількість:", x, y - 35, 0xAAAAAA);
        gui.drawCenteredString(this.font, "§e§l" + amount, x, y - 5, 0xFFFFFF);

        // Ціни (рознесені, щоб не залазили)
        gui.drawString(this.font, "Ціна од.: §a" + unitPrice, x - 100, y + 15, 0xFFFFFF);
        double total = unitPrice * amount;
        gui.drawString(this.font, "Разом: §e" + total, x + 10, y + 15, 0xFFFFFF);

        super.render(gui, mx, my, pt);
    }

    @Override
    public boolean isPauseScreen() {
        return false; // ЦЕ ПРИБИРАЄ ПАУЗУ ТА ЗБЕРЕЖЕННЯ ПРИ ВІДКРИТТІ
    }
}