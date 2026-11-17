package com.example.autobrew;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pengecekan bahan di inventory & pengenalan potion (tanpa perlu membuka Brewing Stand). */
final class Materials {
    private Materials() {}

    private static final int MAIN_SLOTS = 36;   // hotbar + inventory utama
    /** 1 Blaze Powder = 20 "pemakaian" fuel; tiap tahap menyeduh memakai 1. */
    static final int FUEL_PER_BLAZE = 20;

    /** Blok penyimpanan yang didukung (layar GenericContainer): chest, trapped chest, barrel. */
    static boolean isContainer(BlockState s) {
        return s.isOf(Blocks.CHEST) || s.isOf(Blocks.TRAPPED_CHEST) || s.isOf(Blocks.BARREL);
    }

    static int count(Item item) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) return 0;
        int n = 0;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isOf(item)) n += s.getCount();
        }
        return n;
    }

    /** Kenali potion pada stack: bentuk + id potion vanilla. null bila bukan potion. */
    static PotionRecipe.PState classify(ItemStack s) {
        if (s.isEmpty()) return null;
        Item it = s.getItem();
        PotionRecipe.Form f;
        if (it == Items.POTION) f = PotionRecipe.Form.NORMAL;
        else if (it == Items.SPLASH_POTION) f = PotionRecipe.Form.SPLASH;
        else if (it == Items.LINGERING_POTION) f = PotionRecipe.Form.LINGERING;
        else return null;
        PotionContentsComponent pc = s.get(DataComponentTypes.POTION_CONTENTS);
        if (pc == null || pc.potion().isEmpty()) return null;
        String path = pc.potion().get().getKey().map(k -> k.getValue().getPath()).orElse(null);
        return path == null ? null : new PotionRecipe.PState(f, path);
    }

    /** Apakah stack ini botol awal untuk job ini (Water Bottle / Awkward / potion tier I untuk upgrade)? */
    static boolean isSource(ItemStack s, Job job) {
        PotionRecipe.PState c = classify(s);
        return c != null && c.equals(job.startState());
    }

    static int countSource(Job job) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) return 0;
        int n = 0;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (isSource(s, job)) n += s.getCount();
        }
        return n;
    }

    static int countWater() {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) return 0;
        PotionRecipe.PState water = new PotionRecipe.PState(PotionRecipe.Form.NORMAL, "water");
        int n = 0;
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (water.equals(classify(s))) n += s.getCount();
        }
        return n;
    }

    /**
     * Total kebutuhan untuk N siklus, TERMASUK Blaze Powder sebagai fuel.
     * Contoh Strength: Blaze Powder sebagai bahan + sebagai fuel digabung jadi satu angka.
     */
    static Map<Item, Integer> totalNeeds(Job job, int cycles) {
        Map<Item, Integer> m = new LinkedHashMap<>();
        List<Item> st = job.stages();
        int from = job.startIndex();
        for (int i = from; i < st.size(); i++) m.merge(st.get(i), cycles, Integer::sum);
        int brews = (st.size() - from) * cycles;
        int fuelBlaze = (brews + FUEL_PER_BLAZE - 1) / FUEL_PER_BLAZE;
        if (fuelBlaze > 0) m.merge(Items.BLAZE_POWDER, fuelBlaze, Integer::sum);
        return m;
    }

    /** @return null jika bahan cukup untuk langkah [from..akhir] satu siklus, atau pesan apa yang kurang. */
    static String missingFrom(Job job, int from) {
        if (MinecraftClient.getInstance().player == null) return "Masuk ke dunia dulu untuk menjalankan.";
        Map<Item, Integer> need = new LinkedHashMap<>();
        List<Item> st = job.stages();
        for (int i = Math.max(from, job.startIndex()); i < st.size(); i++) need.merge(st.get(i), 1, Integer::sum);
        for (Map.Entry<Item, Integer> e : need.entrySet()) {
            if (count(e.getKey()) < e.getValue()) return "Bahan kurang: " + e.getKey().getName().getString();
        }
        return null;
    }
}
