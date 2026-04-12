package RealMarket.realmarket.client;

import RealMarket.realmarket.network.ModMessages;
import RealMarket.realmarket.network.PacketOpenTradeUI;
import RealMarket.realmarket.network.PacketShopAction;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

public class TradeScreen extends Screen {

    private final double balance;
    private final UUID islandUuid;
    private final List<PacketOpenTradeUI.ItemEntry> items;

    private int selectedIndex = 0;
    private int scrollOffset  = 0;
    private int amount        = 1;

    private static final int ROWS        = 6;   // рядків в списку
    private static final int ROW_H       = 18;
    private static final int LIST_X_OFF  = -110;
    private static final int LIST_W      = 140;
    private static final int DETAIL_X_OFF = 40;

    public TradeScreen(double balance, UUID islandUuid, List<PacketOpenTradeUI.ItemEntry> items) {
        super(Component.literal("Market Terminal"));
        this.balance    = balance;
        this.islandUuid = islandUuid;
        this.items      = items;
    }

    @Override
    protected void init() {
        int cx = this.width  / 2;
        int cy = this.height / 2;

        // ── Скрол ──────────────────────────────────────────────────────────
        addRenderableWidget(Button.builder(Component.literal("▲"), b -> scroll(-1))
                .bounds(cx + LIST_X_OFF + LIST_W + 2, cy - 54, 14, 14).build());
        addRenderableWidget(Button.builder(Component.literal("▼"), b -> scroll(1))
                .bounds(cx + LIST_X_OFF + LIST_W + 2, cy - 54 + ROWS * ROW_H - 14, 14, 14).build());

        // ── Кількість ──────────────────────────────────────────────────────
        int qy = cy + 18;
        addRenderableWidget(Button.builder(Component.literal("-10"), b -> changeAmt(-10)).bounds(cx + DETAIL_X_OFF,      qy, 28, 16).build());
        addRenderableWidget(Button.builder(Component.literal("-1"),  b -> changeAmt(-1) ).bounds(cx + DETAIL_X_OFF + 30, qy, 22, 16).build());
        addRenderableWidget(Button.builder(Component.literal("+1"),  b -> changeAmt(1)  ).bounds(cx + DETAIL_X_OFF + 54, qy, 22, 16).build());
        addRenderableWidget(Button.builder(Component.literal("+10"), b -> changeAmt(10) ).bounds(cx + DETAIL_X_OFF + 78, qy, 28, 16).build());

        // ── Дії ────────────────────────────────────────────────────────────
        addRenderableWidget(Button.builder(Component.literal("§aКУПИТИ"), b -> doAction(0))
                .bounds(cx + DETAIL_X_OFF, qy + 22, 52, 18).build());
        addRenderableWidget(Button.builder(Component.literal("§6ПРОДАТИ"), b -> doAction(1))
                .bounds(cx + DETAIL_X_OFF + 56, qy + 22, 52, 18).build());

        // ── Закрити ────────────────────────────────────────────────────────
        addRenderableWidget(Button.builder(Component.literal("Закрити"), b -> onClose())
                .bounds(cx + DETAIL_X_OFF + 14, qy + 46, 80, 16).build());
    }

    // ── Scroll & кількість ─────────────────────────────────────────────────

    private void scroll(int dir) {
        scrollOffset = Math.max(0, Math.min(Math.max(0, items.size() - ROWS), scrollOffset + dir));
    }

    private void changeAmt(int d) {
        amount = Math.max(1, Math.min(64, amount + d));
    }

    // ── Кліки по рядках списку ─────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int cx = this.width  / 2;
        int cy = this.height / 2;
        int lx = cx + LIST_X_OFF;
        int ly = cy - 54;

        if (mx >= lx && mx <= lx + LIST_W) {
            int row = (int)((my - ly) / ROW_H);
            if (row >= 0 && row < ROWS) {
                int idx = scrollOffset + row;
                if (idx < items.size()) {
                    selectedIndex = idx;
                    amount = 1;
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll(delta < 0 ? 1 : -1);
        return true;
    }

    // ── Відправка пакету ───────────────────────────────────────────────────

    private void doAction(int type) {
        if (items.isEmpty()) return;
        PacketOpenTradeUI.ItemEntry sel = items.get(Math.min(selectedIndex, items.size() - 1));
        ModMessages.sendToServer(new PacketShopAction(type, amount, sel.itemId(), islandUuid));
        onClose();
    }

    // ── Рендер ────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        int cx = this.width  / 2;
        int cy = this.height / 2;

        // Зовнішня рамка
        g.fill(cx - 115, cy - 70, cx + 115, cy + 90, 0xCC111122);
        g.renderOutline(cx - 115, cy - 70, 230, 160, 0xFF0099FF);

        // Заголовок
        g.drawCenteredString(font, "§b§lMARKET TERMINAL", cx, cy - 64, 0xFFFFFF);

        // Баланс
        String balStr = balance < 0 ? "§cAPI OFFLINE" : "§6" + String.format("%.1f", balance) + " Coins";
        g.drawCenteredString(font, "Баланс: " + balStr, cx, cy - 54, 0xAAAAAA);

        // ── Список предметів ───────────────────────────────────────────────
        int lx = cx + LIST_X_OFF;
        int ly = cy - 54 + 12;
        g.fill(lx - 1, ly - 1, lx + LIST_W + 1, ly + ROWS * ROW_H + 1, 0x88000000);

        for (int i = 0; i < ROWS; i++) {
            int idx = scrollOffset + i;
            if (idx >= items.size()) break;

            PacketOpenTradeUI.ItemEntry entry = items.get(idx);
            int ry = ly + i * ROW_H;
            boolean selected = idx == selectedIndex;

            if (selected) g.fill(lx, ry, lx + LIST_W, ry + ROW_H, 0x550055FF);

            // Іконка предмету
            ItemStack stack = resolveStack(entry.itemId());
            g.renderItem(stack, lx + 1, ry + 1);

            // Назва (скорочена)
            String name = shortName(entry.itemId());
            g.drawString(font, "§f" + name, lx + 20, ry + 5, 0xFFFFFF);

            // Ціна і к-сть справа
            String info = "§a" + (int)entry.price() + "c §7x" + entry.quantity();
            g.drawString(font, info, lx + LIST_W - font.width(info) - 2, ry + 5, 0xFFFFFF);
        }

        // ── Деталі обраного ───────────────────────────────────────────────
        int dx = cx + DETAIL_X_OFF;
        if (!items.isEmpty()) {
            PacketOpenTradeUI.ItemEntry sel = items.get(Math.min(selectedIndex, items.size() - 1));
            ItemStack selStack = resolveStack(sel.itemId());

            g.fill(dx - 2, cy - 40, dx + 108, cy + 90, 0x44000000);

            // Велика іконка
            g.pose().pushPose();
            g.pose().scale(2f, 2f, 1f);
            g.renderItem(selStack, (dx + 44) / 2, (cy - 36) / 2);
            g.pose().popPose();

            g.drawCenteredString(font, "§e" + shortName(sel.itemId()), dx + 52, cy - 8,  0xFFFFFF);
            g.drawCenteredString(font, "Ціна: §a" + sel.price() + "c",  dx + 52, cy + 2,  0xAAAAAA);
            g.drawCenteredString(font, "К-сть: §f" + amount,            dx + 52, cy + 12, 0xFFFFFF);
            double total = sel.price() * amount;
            g.drawCenteredString(font, "Разом: §e" + String.format("%.1f", total) + "c", dx + 52, cy + 22, 0xFFFFFF);
        }

        super.render(g, mx, my, pt);
    }

    // ── Утиліти ───────────────────────────────────────────────────────────

    private static ItemStack resolveStack(String itemId) {
        try {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
            return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private static String shortName(String itemId) {
        int colon = itemId.indexOf(':');
        String raw = colon >= 0 ? itemId.substring(colon + 1) : itemId;
        // snake_case → Title Case
        String[] parts = raw.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
