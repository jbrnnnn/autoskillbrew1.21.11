package com.example.autobrew;

import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Resep potion.
 * Mode BUAT BARU : [bahan resep] -> [tier] -> [Gunpowder=Splash] -> [Dragon's Breath=Lingering]
 * Mode UPGRADE   : potion tier I di inventory -> [tier] -> [Gunpowder] -> [Dragon's Breath]
 *
 * "chain" = id potion setelah tiap bahan resep (untuk mengenali potion yang sedang ada di Brewing Stand).
 */
public record PotionRecipe(String id, String name, int color, Item icon,
                           List<Item> stages, List<String> chain, boolean canExtend, boolean canAmplify) {

    /** Tier potion. NONE = Tier I, EXTEND = Tier I+ (durasi lebih lama), AMPLIFY = Tier II. */
    public enum Modifier {
        NONE("Tier I", null),
        EXTEND("Tier I+", Items.REDSTONE),
        AMPLIFY("Tier II", Items.GLOWSTONE_DUST);

        public final String label;
        public final Item item;
        Modifier(String label, Item item) { this.label = label; this.item = item; }
    }

    public enum Form {
        NORMAL("Biasa", Items.POTION),
        SPLASH("Splash", Items.SPLASH_POTION),
        LINGERING("Linger", Items.LINGERING_POTION);

        public final String label;
        public final Item item;
        Form(String label, Item item) { this.label = label; this.item = item; }
    }

    /** Wujud potion: bentuk (biasa/splash/lingering) + id potion vanilla (mis. "strong_healing"). */
    public record PState(Form form, String potion) {
        public String label() { return form.label + " " + potion; }
    }

    public boolean supports(Modifier m) {
        return switch (m) {
            case NONE -> true;
            case EXTEND -> canExtend;
            case AMPLIFY -> canAmplify;
        };
    }

    public List<Item> build(Modifier mod, Form form) {
        List<Item> s = new ArrayList<>(stages);
        if (mod != Modifier.NONE && supports(mod)) s.add(mod.item);
        if (form != Form.NORMAL) s.add(Items.GUNPOWDER);
        if (form == Form.LINGERING) s.add(Items.DRAGON_BREATH);
        return s;
    }

    /** Upgrade potion yang sudah jadi (tier I) ke tier/bentuk yang lebih tinggi. */
    public List<Item> buildUpgrade(Modifier mod, Form from, Form to) {
        List<Item> s = new ArrayList<>();
        if (mod != Modifier.NONE && supports(mod)) s.add(mod.item);
        if (from == Form.NORMAL && to != Form.NORMAL) s.add(Items.GUNPOWDER);
        if (from != Form.LINGERING && to == Form.LINGERING) s.add(Items.DRAGON_BREATH);
        return s;
    }

    private String tierId(Modifier mod) {
        return (mod == Modifier.EXTEND ? "long_" : "strong_") + id;
    }

    /** Wujud potion di tiap titik proses (ukuran = build().size() + 1). Indeks 0 = botol air. */
    public List<PState> statesScratch(Modifier mod, Form form) {
        List<PState> s = new ArrayList<>();
        s.add(new PState(Form.NORMAL, "water"));
        String cur = "water";
        for (String c : chain) { cur = c; s.add(new PState(Form.NORMAL, cur)); }
        if (mod != Modifier.NONE && supports(mod)) { cur = tierId(mod); s.add(new PState(Form.NORMAL, cur)); }
        if (form != Form.NORMAL) s.add(new PState(Form.SPLASH, cur));
        if (form == Form.LINGERING) s.add(new PState(Form.LINGERING, cur));
        return s;
    }

    /** Wujud potion untuk mode upgrade (ukuran = buildUpgrade().size() + 1). */
    public List<PState> statesUpgrade(Modifier mod, Form from, Form to) {
        List<PState> s = new ArrayList<>();
        String cur = id;
        s.add(new PState(from, cur));
        if (mod != Modifier.NONE && supports(mod)) { cur = tierId(mod); s.add(new PState(from, cur)); }
        if (from == Form.NORMAL && to != Form.NORMAL) s.add(new PState(Form.SPLASH, cur));
        if (from != Form.LINGERING && to == Form.LINGERING) s.add(new PState(Form.LINGERING, cur));
        return s;
    }

    public String resultName(Modifier mod, Form form) {
        String f = switch (form) {
            case NORMAL -> "Potion";
            case SPLASH -> "Splash Potion";
            case LINGERING -> "Lingering Potion";
        };
        String t = !supports(mod) ? "" : switch (mod) {
            case NONE -> "";
            case EXTEND -> " (+)";
            case AMPLIFY -> " II";
        };
        if (id.equals("awkward") || id.equals("mundane") || id.equals("thick")) return name + " " + f;
        return f + " of " + name + t;
    }

    // ---------- definisi resep ----------
    private record Step(Item item, String after) {}

    private static Step st(Item i, String after) { return new Step(i, after); }

    private static final Step WART = new Step(Items.NETHER_WART, "awkward");

    private static PotionRecipe rec(String id, String name, int color, boolean ext, boolean amp, Step... steps) {
        List<Item> items = new ArrayList<>();
        List<String> chain = new ArrayList<>();
        for (Step s : steps) { items.add(s.item()); chain.add(s.after()); }
        return new PotionRecipe(id, name, color, items.get(items.size() - 1),
                List.copyOf(items), List.copyOf(chain), ext, amp);
    }

    public static final List<PotionRecipe> ALL = List.of(
            rec("healing",         "Healing",         0xF82423, false, true,  WART, st(Items.GLISTERING_MELON_SLICE, "healing")),
            rec("harming",         "Harming",         0x8B2F2F, false, true,  WART, st(Items.GLISTERING_MELON_SLICE, "healing"), st(Items.FERMENTED_SPIDER_EYE, "harming")),
            rec("swiftness",       "Swiftness",       0x33EBFF, true,  true,  WART, st(Items.SUGAR, "swiftness")),
            rec("slowness",        "Slowness",        0x8BAFE0, true,  true,  WART, st(Items.SUGAR, "swiftness"), st(Items.FERMENTED_SPIDER_EYE, "slowness")),
            rec("leaping",         "Leaping",         0x22FF4C, true,  true,  WART, st(Items.RABBIT_FOOT, "leaping")),
            rec("strength",        "Strength",        0xE0562B, true,  true,  WART, st(Items.BLAZE_POWDER, "strength")),
            rec("weakness",        "Weakness",        0x8A9A8A, true,  false, st(Items.FERMENTED_SPIDER_EYE, "weakness")),
            rec("regeneration",    "Regeneration",    0xCD5CAB, true,  true,  WART, st(Items.GHAST_TEAR, "regeneration")),
            rec("poison",          "Poison",          0x5FA83A, true,  true,  WART, st(Items.SPIDER_EYE, "poison")),
            rec("fire_resistance", "Fire Resistance", 0xFF9900, true,  false, WART, st(Items.MAGMA_CREAM, "fire_resistance")),
            rec("night_vision",    "Night Vision",    0x3A3AD0, true,  false, WART, st(Items.GOLDEN_CARROT, "night_vision")),
            rec("invisibility",    "Invisibility",    0xB0B8D0, true,  false, WART, st(Items.GOLDEN_CARROT, "night_vision"), st(Items.FERMENTED_SPIDER_EYE, "invisibility")),
            rec("water_breathing", "Water Breathing", 0x3A7BD5, true,  false, WART, st(Items.PUFFERFISH, "water_breathing")),
            rec("slow_falling",    "Slow Falling",    0xF3CFB9, true,  false, WART, st(Items.PHANTOM_MEMBRANE, "slow_falling")),
            rec("turtle_master",   "Turtle Master",   0x7C8F5A, true,  true,  WART, st(Items.TURTLE_HELMET, "turtle_master")),
            rec("wind_charged",    "Wind Charged",    0xBDC9FF, false, false, st(Items.BREEZE_ROD, "wind_charged")),
            rec("oozing",          "Oozing",          0x99FFA3, false, false, st(Items.SLIME_BLOCK, "oozing")),
            rec("weaving",         "Weaving",         0x78695A, false, false, st(Items.COBWEB, "weaving")),
            rec("infested",        "Infested",        0x8C9B8C, false, false, st(Items.STONE, "infested")),
            rec("awkward",         "Awkward",         0x385DC6, false, false, WART),
            rec("mundane",         "Mundane",         0x7B8FD0, false, false, st(Items.REDSTONE, "mundane")),
            rec("thick",           "Thick",           0x4F65B8, false, false, st(Items.GLOWSTONE_DUST, "thick"))
    );
}
