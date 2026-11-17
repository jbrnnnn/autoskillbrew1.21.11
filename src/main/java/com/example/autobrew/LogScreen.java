package com.example.autobrew;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Log kejadian (lengkap dengan jam, termasuk kegagalan) dan riwayat produksi. */
public class LogScreen extends Screen {
    private static int tab = 0;

    private final Screen parent;
    private int px, py, pw, ph, listX, listY, listW, listH;
    private int scroll = 0, contentH = 0;

    private record Row(List<OrderedText> lines, int color, int height) {}
    private final List<Row> rows = new ArrayList<>();

    public LogScreen(Screen parent) {
        super(Text.literal("Log & Riwayat"));
        this.parent = parent;
    }

    @Override public boolean shouldPause() { return false; }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    protected void init() {
        pw = Math.min(width - 12, 470);
        ph = Math.min(height - 12, 262);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        listX = px + 8;
        listY = py + 50;
        listW = pw - 16;
        listH = ph - 50 - 34;
        rebuildRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentH - listH)));

        addDrawableChild(new Ui.Segmented(px + 8, py + 28, 150, 16, new String[]{"Log", "Produksi"},
                new String[]{"Kejadian: mulai, selesai, GAGAL (lengkap dengan jam)", "Jumlah potion yang pernah dibuat"},
                new boolean[]{true, true}, tab, 0x8A6AE8, i -> { tab = i; scroll = 0; clearAndInit(); }));

        int by = py + ph - 26;
        addDrawableChild(new Ui.FancyButton(px + pw - 8 - 70, by, 70, 20, "Kembali", 0x6A6A7A, this::close));
        addDrawableChild(new Ui.FancyButton(px + pw - 8 - 70 - 4 - 120, by, 120, 20,
                tab == 0 ? "Hapus log" : "Reset statistik", 0xD64545, () -> {
            if (tab == 0) {
                BrewLog.clear();
            } else {
                AutoBrewConfig.produced.clear();
                AutoBrewConfig.totalPotions = 0;
                AutoBrewConfig.markDirty();
            }
            clearAndInit();
        }));
    }

    private void addRow(String text, int color, int w) {
        List<OrderedText> lines = Ui.tr().wrapLines(Text.literal(text), w);
        rows.add(new Row(lines, color, lines.size() * 10 + 3));
    }

    private void rebuildRows() {
        rows.clear();
        int w = listW - 12;
        if (tab == 0) {
            List<BrewLog.Entry> es = BrewLog.entries();
            if (es.isEmpty()) addRow("Belum ada log.", 0xFF8888A0, w);
            for (int i = es.size() - 1; i >= 0; i--) {          // terbaru di atas
                BrewLog.Entry e = es.get(i);
                String t = e.time().length() > 5 ? e.time().substring(5) : e.time();   // MM-dd HH:mm:ss
                String line = "[" + t + "] " + e.level().label
                        + (e.potion().isEmpty() ? "" : " " + e.potion()) + ": " + e.message();
                addRow(line, e.level().color, w);
            }
        } else {
            addRow("Total dibuat (seumur hidup): " + AutoBrewConfig.totalPotions + " potion", 0xFFFFFFFF, w);
            List<Map.Entry<String, Integer>> list = new ArrayList<>(AutoBrewConfig.produced.entrySet());
            list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            if (list.isEmpty()) addRow("Belum ada produksi.", 0xFF8888A0, w);
            for (Map.Entry<String, Integer> e : list) addRow(e.getKey() + "  x" + e.getValue(), 0xFFE0E0EC, w);
        }
        contentH = 0;
        for (Row r : rows) contentH += r.height();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            scroll -= (int) (vAmt * 14);
            scroll = Math.max(0, Math.min(scroll, Math.max(0, contentH - listH)));
            return true;
        }
        return super.mouseScrolled(mx, my, hAmt, vAmt);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        super.renderBackground(ctx, mx, my, delta);
        ctx.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, 0xFF6A48C0);
        ctx.fill(px, py, px + pw, py + ph, 0xF0121218);
        ctx.fillGradient(px, py, px + pw, py + 22, 0xFF3A2370, 0xFF1C1236);
        ctx.drawTextWithShadow(Ui.tr(), "Log & Riwayat Auto Potion", px + 8, py + 7, 0xFFFFFFFF);
        String fails = "Gagal: " + BrewLog.failCount();
        ctx.drawTextWithShadow(Ui.tr(), fails, px + pw - 8 - Ui.tr().getWidth(fails), py + 7,
                BrewLog.failCount() > 0 ? 0xFFFF7070 : 0xFF9C8FC4);

        ctx.fill(listX - 2, listY - 2, listX + listW + 2, listY + listH + 2, 0xFF08080C);
        ctx.enableScissor(listX, listY, listX + listW, listY + listH);
        int y = listY + 2 - scroll;
        for (Row r : rows) {
            if (y + r.height() >= listY && y <= listY + listH) {
                int ly = y;
                for (OrderedText line : r.lines()) {
                    ctx.drawTextWithShadow(Ui.tr(), line, listX + 4, ly, r.color());
                    ly += 10;
                }
            }
            y += r.height();
        }
        ctx.disableScissor();

        if (contentH > listH) {
            int barH = Math.max(14, listH * listH / contentH);
            int barY = listY + (listH - barH) * scroll / (contentH - listH);
            ctx.fill(listX + listW - 3, listY, listX + listW - 1, listY + listH, 0x66000000);
            ctx.fill(listX + listW - 3, barY, listX + listW - 1, barY + barH, 0xFFB8A0FF);
        }
    }
}
