package com.example.autobrew;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Memunculkan tombol "Konfigurasi" di daftar mod pada Mod Menu. Hanya dimuat jika Mod Menu terpasang. */
public class AutoBrewModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return PotionSelectScreen::new;   // menerima parent screen (layar Mod Menu)
    }
}
