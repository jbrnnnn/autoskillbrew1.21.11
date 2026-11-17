package com.example.autobrew;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.BrewingStandScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class AutoBrewClient implements ClientModInitializer {
    // 1.21.9+: keybind wajib punya Category (dibuat sekali). Terjemahan: key.category.autobrew.main
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("autobrew", "main"));

    public static KeyBinding openKey, toggleKey;
    private static int saveTimer = 0;
    private static long lastToggle = 0;

    @Override
    public void onInitializeClient() {
        AutoBrewConfig.load();
        BrewLog.load();

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autobrew.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autobrew.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) client.setScreen(new PotionSelectScreen());
            while (toggleKey.wasPressed()) toggle(client);
            BrewController.tick(client);
            if (++saveTimer >= 100) { saveTimer = 0; AutoBrewConfig.saveIfDirty(); }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> AutoBrewConfig.saveIfDirty());

        // Keluar dari dunia/server: hentikan otomatisasi
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (BrewController.enabled) BrewController.stop("keluar dari dunia");
        });

        // Overlay status (Fabric Hud API; HudRenderCallback sudah deprecated)
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.of("autobrew", "status"),
                (ctx, tickCounter) -> {
                    if (!AutoBrewConfig.showHud || !BrewController.enabled) return;
                    MinecraftClient mc = MinecraftClient.getInstance();
                    ctx.drawTextWithShadow(mc.textRenderer, BrewController.statusLine(), 6, 6, 0xFF7CFF9E);
                    String hint = BrewController.hudHint(mc);
                    if (hint != null) ctx.drawTextWithShadow(mc.textRenderer, hint, 6, 18, 0xFFFFD166);
                });

        // Layar Brewing Stand: tombol ON/OFF + deteksi ditutup pemain
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof BrewingStandScreen)) return;
            BrewController.pausedByUser = false;       // Brewing Stand terbuka lagi -> lanjut

            ButtonWidget[] ref = new ButtonWidget[1];
            ref[0] = ButtonWidget.builder(label(), b -> {
                toggle(client);
                ref[0].setMessage(label());
            }).dimensions(8, 8, 110, 20).build();
            Screens.getButtons(screen).add(ref[0]);

            // Label tombol mengikuti status (mis. saat selesai otomatis)
            boolean[] last = { BrewController.enabled };
            ScreenEvents.afterTick(screen).register(s -> {
                if (last[0] != BrewController.enabled) {
                    last[0] = BrewController.enabled;
                    ref[0].setMessage(label());
                }
            });

            // Pemain menutup Brewing Stand (ESC) saat berjalan -> jeda, jangan dibuka paksa
            ScreenEvents.remove(screen).register(s -> {
                if (BrewController.enabled && !BrewController.expectClose) BrewController.pausedByUser = true;
                BrewController.expectClose = false;     // ditutup oleh mod sendiri (isi ulang air) bukan ESC
            });
        });
    }

    /** Debounce 100 ms: handler layar bisa terdaftar ganda setelah resize. */
    private static void toggle(MinecraftClient client) {
        long now = System.nanoTime();
        if (now - lastToggle < 100_000_000L) return;
        lastToggle = now;

        if (BrewController.enabled) {
            if (BrewController.pausedByUser) BrewController.resume();
            else BrewController.stop("dihentikan");
        } else if (AutoBrewConfig.recipe() == null) {
            if (client.currentScreen == null) client.setScreen(new PotionSelectScreen());
            else if (client.player != null)
                client.player.sendMessage(Text.literal("[AutoBrew] Tekan G (di luar menu) untuk memilih potion dulu"), true);
        } else {
            String err = BrewController.start();
            if (err != null && client.player != null)
                client.player.sendMessage(Text.literal("[AutoBrew] " + err), true);
        }
    }

    private static Text label() {
        return Text.literal("Auto Brew: " + (BrewController.enabled ? "ON" : "OFF"));
    }
}
