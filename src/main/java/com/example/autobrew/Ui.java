package com.example.autobrew;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.IntConsumer;

final class Ui {
    private Ui() {}

    static TextRenderer tr() { return MinecraftClient.getInstance().textRenderer; }

    /** Gelapkan/terangkan warna RGB, hasil ARGB opaque. */
    static int shade(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 255) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 255) * f));
        int b = Math.min(255, (int) ((rgb & 255) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Widget yang punya tooltip (baris dipisah \n). */
    interface Tippable { String tipAt(double mx, double my); }

    /** Tombol berwarna dengan efek hover. */
    static class FancyButton extends ClickableWidget implements Tippable {
        private final Runnable action;
        private final int accent;
        private final String tip;

        FancyButton(int x, int y, int w, int h, String text, int accent, String tip, Runnable action) {
            super(x, y, w, h, Text.literal(text));
            this.accent = accent;
            this.tip = tip;
            this.action = action;
        }

        FancyButton(int x, int y, int w, int h, String text, int accent, Runnable action) {
            this(x, y, w, h, text, accent, null, action);
        }

        @Override public String tipAt(double mx, double my) { return tip; }

        @Override
        protected void renderWidget(DrawContext ctx, int mx, int my, float d) {
            boolean hov = isHovered();
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, hov ? 0xFFFFFFFF : shade(accent, 0.45f));
            ctx.fillGradient(x, y, x + w, y + h, shade(accent, hov ? 1.0f : 0.8f), shade(accent, hov ? 0.7f : 0.52f));
            ctx.drawCenteredTextWithShadow(tr(), getMessage(), x + w / 2, y + (h - 8) / 2, 0xFFFFFFFF);
        }

        @Override public void onClick(Click click, boolean doubled) { action.run(); }
        @Override protected void appendClickableNarrations(NarrationMessageBuilder b) { appendDefaultNarrations(b); }
    }

    /** Kontrol pilihan bersegmen (seperti tab). */
    static class Segmented extends ClickableWidget implements Tippable {
        private final String[] labels, tips;
        private final boolean[] enabled;
        private final int selected, accent;
        private final IntConsumer onChange;

        Segmented(int x, int y, int w, int h, String[] labels, String[] tips, boolean[] enabled,
                  int selected, int accent, IntConsumer onChange) {
            super(x, y, w, h, Text.empty());
            this.labels = labels; this.tips = tips; this.enabled = enabled;
            this.selected = selected; this.accent = accent; this.onChange = onChange;
        }

        private int indexAt(double mx) {
            int i = (int) ((mx - getX()) * labels.length / getWidth());
            return i >= 0 && i < labels.length ? i : -1;
        }

        @Override
        public String tipAt(double mx, double my) {
            int i = indexAt(mx);
            if (i < 0) return null;
            if (!enabled[i]) return "Tidak tersedia untuk pilihan ini";
            return tips != null ? tips[i] : null;
        }

        @Override
        protected void renderWidget(DrawContext ctx, int mx, int my, float d) {
            int n = labels.length, x = getX(), y = getY(), w = getWidth(), h = getHeight();
            ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF050508);
            for (int i = 0; i < n; i++) {
                int sx = x + w * i / n, ex = x + w * (i + 1) / n;
                boolean sel = i == selected, en = enabled[i];
                boolean hov = en && mx >= sx && mx < ex && my >= y && my < y + h;
                int top, bot;
                if (sel)      { top = shade(accent, 0.95f); bot = shade(accent, 0.62f); }
                else if (hov) { top = 0xFF3A3A52;           bot = 0xFF2A2A3C; }
                else          { top = 0xFF24243A;           bot = 0xFF1A1A2A; }
                ctx.fillGradient(sx, y, ex - (i < n - 1 ? 1 : 0), y + h, top, bot);
                int col = !en ? 0xFF666677 : sel ? 0xFFFFFFFF : 0xFFC8C8D8;
                ctx.drawCenteredTextWithShadow(tr(), labels[i], (sx + ex) / 2, y + (h - 8) / 2, col);
            }
        }

        @Override
        public void onClick(Click click, boolean doubled) {
            int i = indexAt(click.x());
            if (i >= 0 && enabled[i]) onChange.accept(i);
        }

        @Override protected void appendClickableNarrations(NarrationMessageBuilder b) { appendDefaultNarrations(b); }
    }

    /** Slider bilangan bulat min..max. */
    static class IntSlider extends SliderWidget {
        private final int min, max;
        private final java.util.function.IntFunction<String> fmt;
        private final IntConsumer onChange;

        IntSlider(int x, int y, int w, int h, int min, int max, int initial,
                  java.util.function.IntFunction<String> fmt, IntConsumer onChange) {
            super(x, y, w, h, Text.empty(), (initial - min) / (double) (max - min));
            this.min = min; this.max = max; this.fmt = fmt; this.onChange = onChange;
            updateMessage();
        }

        private int val() { return min + (int) Math.round(value * (max - min)); }

        @Override protected void updateMessage() {
            if (fmt == null) return;
            setMessage(Text.literal(fmt.apply(val())));
        }

        @Override protected void applyValue() {
            onChange.accept(val());
        }
    }
}
