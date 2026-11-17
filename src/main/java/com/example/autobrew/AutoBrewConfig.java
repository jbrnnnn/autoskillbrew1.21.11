package com.example.autobrew;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Konfigurasi tersimpan di config/autobrew.json */
public class AutoBrewConfig {
    public enum Mode { SCRATCH, UPGRADE }

    /** Potion apa yang dipakai sebagai titik awal (mode Buat Baru). */
    public enum Base { WATER, AWKWARD }

    public enum Speed {
        // batch = beberapa klik slot digabung dalam 1 tick (lebih cepat, lebih sedikit jeda)
        SLOW("Lambat", 6, false), NORMAL("Normal", 3, true), FAST("Cepat", 1, true);
        public final String label; public final int ticks; public final boolean batch;
        Speed(String label, int ticks, boolean batch) { this.label = label; this.ticks = ticks; this.batch = batch; }
    }

    // --- pilihan potion saat ini
    public static String recipeId = "";
    public static Mode mode = Mode.SCRATCH;
    public static PotionRecipe.Modifier modifier = PotionRecipe.Modifier.NONE;
    public static PotionRecipe.Form form = PotionRecipe.Form.NORMAL;        // bentuk hasil
    public static PotionRecipe.Form sourceForm = PotionRecipe.Form.NORMAL;  // bentuk potion sumber (upgrade)
    public static Base base = Base.WATER;
    public static int maxCycles = 0;       // 0 = tanpa batas

    // --- botol & otomatisasi
    public static boolean useInventoryBottles = true;   // false = mode hopper/manual: mod tidak mengisi botol
    public static boolean autoRefill = false;           // isi ulang Water Bottle dari Glass Bottle
    public static int refillTarget = 27;
    public static Pos standPos = null;
    public static Pos waterPos = null;

    // --- chest
    public static boolean depositEnabled = false;   // taruh potion jadi ke chest Output
    public static boolean supplyEnabled = false;    // ambil bahan dari chest Supply
    public static int supplyTarget = 64;            // isi inventory sampai sebanyak ini per bahan
    public static Pos outputPos = null;
    public static final List<Pos> supplyChests = new ArrayList<>();
    public static final int MAX_SUPPLY = 4;

    // --- umum
    public static Speed speed = Speed.NORMAL;
    public static boolean showHud = true;
    public static boolean sound = true;      // bunyi saat selesai / gagal
    public static boolean autoOpen = true;   // otomatis buka Brewing Stand

    // --- statistik, antrean, progres
    public static int totalPotions = 0;
    public static final Map<String, Integer> produced = new LinkedHashMap<>();
    public static final List<JobData> queue = new ArrayList<>();
    public static Resume resume = null;

    public static final int MAX_QUEUE = 12;

    /** Posisi blok di dunia. */
    public static class Pos {
        public int x, y, z;
        public Pos() {}
        public Pos(BlockPos p) { x = p.getX(); y = p.getY(); z = p.getZ(); }
        public BlockPos toBlockPos() { return new BlockPos(x, y, z); }
        public String text() { return x + ", " + y + ", " + z; }
    }

    /** Bentuk Job yang bisa disimpan ke JSON. */
    public static class JobData {
        public String recipeId, mode, modifier, form, sourceForm, base;
        public int cycles;
    }

    /** Titik henti yang diingat agar bisa dilanjutkan setelah bahan diisi ulang. */
    public static class Resume {
        public String sig, state, reason, savedAt;
        public int jobIdx, stage, sessionPotions;
        public int[] cyclesDone;
        public boolean[] done;

        /** Hanya valid jika antrean/pengaturan sekarang sama dengan saat progres disimpan. */
        public boolean matches() { return sig != null && sig.equals(planSignature()); }
    }

    // ------------------------------------------------------------ rencana kerja
    public static PotionRecipe recipe() {
        for (PotionRecipe r : PotionRecipe.ALL) if (r.id().equals(recipeId)) return r;
        return null;
    }

    public static Job currentJob() { return Job.fromConfig(); }

    public static List<Item> currentStages() { return Job.fromConfig().stages(); }

    /** Antrean bila ada, kalau kosong: satu job dari pengaturan saat ini. Tidak pernah kosong. */
    public static List<Job> plan() {
        List<Job> l = new ArrayList<>();
        if (!queue.isEmpty()) { for (JobData d : queue) l.add(Job.fromData(d)); }
        else l.add(Job.fromConfig());
        return l;
    }

    public static String signature(List<Job> p) {
        StringBuilder sb = new StringBuilder();
        for (Job j : p) sb.append(j.key()).append(';');
        return sb.toString();
    }

    public static String planSignature() { return signature(plan()); }

    public static boolean addCurrentToQueue() {
        Job j = Job.fromConfig();
        if (!j.valid() || queue.size() >= MAX_QUEUE) return false;
        queue.add(j.toData());
        markDirty();
        return true;
    }

    public static void removeFromQueue(int i) {
        if (i >= 0 && i < queue.size()) { queue.remove(i); markDirty(); }
    }

    public static boolean addSupplyChest(Pos p) {
        for (Pos q : supplyChests) if (q.x == p.x && q.y == p.y && q.z == p.z) return false;
        if (supplyChests.size() >= MAX_SUPPLY) return false;
        supplyChests.add(p);
        markDirty();
        return true;
    }

    public static void clearSupply() {
        if (!supplyChests.isEmpty()) { supplyChests.clear(); markDirty(); }
    }

    public static void clearQueue() {
        if (!queue.isEmpty()) { queue.clear(); markDirty(); }
    }

    // ------------------------------------------------------------ progres & statistik
    public static boolean resumeMatches() { return resume != null && resume.matches(); }

    public static void clearResume() {
        if (resume != null) { resume = null; markDirty(); }
    }

    public static void addProduced(String name, int n) {
        if (n <= 0 || name == null || name.isEmpty()) return;
        produced.merge(name, n, Integer::sum);
        markDirty();
    }

    private static String stateLabel(String s) {
        if (s == null) return "?";
        return switch (s) {
            case "BOTTLES" -> "isi botol";
            case "INGREDIENT" -> "isi bahan";
            case "WAIT" -> "menyeduh";
            case "TAKE" -> "ambil hasil";
            case "UNLOAD" -> "keluarkan";
            case "REFILL" -> "isi air";
            case "TRIP" -> "chest";
            default -> s;
        };
    }

    /** Ringkasan progres tersimpan untuk GUI, atau null. */
    public static String resumeSummary() {
        if (resume == null) return null;
        List<Job> p = plan();
        int idx = Math.max(0, Math.min(resume.jobIdx, p.size() - 1));
        int total = p.get(idx).stages().size();
        int shown = total == 0 ? 0 : Math.min(resume.stage + 1, total);
        int c = resume.cyclesDone != null && idx < resume.cyclesDone.length ? resume.cyclesDone[idx] : 0;
        return (p.size() > 1 ? "antrean #" + (idx + 1) + ", " : "") + "siklus " + c + ", "
                + stateLabel(resume.state) + " tahap " + shown + "/" + total;
    }

    // ------------------------------------------------------------ simpan / muat
    private static boolean dirty = false;

    /** Tandai berubah; penulisan file ditunda (tidak menulis disk di setiap geseran slider). */
    public static void markDirty() { dirty = true; }

    public static void saveIfDirty() {
        if (dirty) { dirty = false; save(); }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static class Data {
        String recipeId, mode, modifier, form, sourceForm, speed, base;
        int maxCycles, totalPotions, refillTarget = 27;
        boolean showHud = true, sound = true, autoOpen = true, useInventoryBottles = true, autoRefill = false;
        Pos standPos, waterPos;
        boolean depositEnabled = false, supplyEnabled = false;
        int supplyTarget = 64;
        Pos outputPos;
        List<Pos> supplyChests;
        Map<String, Integer> produced;
        List<JobData> queue;
        Resume resume;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("autobrew.json");
    }

    public static void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            Data d = GSON.fromJson(Files.readString(f), Data.class);
            if (d == null) return;
            if (d.recipeId != null) recipeId = d.recipeId;
            mode = parse(Mode.class, d.mode, mode);
            modifier = parse(PotionRecipe.Modifier.class, d.modifier, modifier);
            form = parse(PotionRecipe.Form.class, d.form, form);
            sourceForm = parse(PotionRecipe.Form.class, d.sourceForm, sourceForm);
            base = parse(Base.class, d.base, base);
            speed = parse(Speed.class, d.speed, speed);
            maxCycles = Math.max(0, Math.min(64, d.maxCycles));
            showHud = d.showHud; sound = d.sound; autoOpen = d.autoOpen;
            useInventoryBottles = d.useInventoryBottles;
            autoRefill = d.autoRefill;
            refillTarget = Math.max(3, Math.min(36, d.refillTarget));
            standPos = d.standPos; waterPos = d.waterPos;
            depositEnabled = d.depositEnabled; supplyEnabled = d.supplyEnabled;
            supplyTarget = Math.max(8, Math.min(128, d.supplyTarget == 0 ? 64 : d.supplyTarget));
            outputPos = d.outputPos;
            supplyChests.clear();
            if (d.supplyChests != null) supplyChests.addAll(d.supplyChests);
            totalPotions = Math.max(0, d.totalPotions);
            produced.clear();
            if (d.produced != null) produced.putAll(d.produced);
            queue.clear();
            if (d.queue != null) queue.addAll(d.queue);
            resume = d.resume;
        } catch (Exception e) {
            System.err.println("[AutoBrew] gagal membaca config: " + e);
        }
    }

    public static void save() {
        try {
            Data d = new Data();
            d.recipeId = recipeId; d.mode = mode.name(); d.modifier = modifier.name();
            d.form = form.name(); d.sourceForm = sourceForm.name(); d.base = base.name();
            d.speed = speed.name();
            d.maxCycles = maxCycles; d.showHud = showHud; d.sound = sound; d.autoOpen = autoOpen;
            d.useInventoryBottles = useInventoryBottles; d.autoRefill = autoRefill; d.refillTarget = refillTarget;
            d.standPos = standPos; d.waterPos = waterPos;
            d.depositEnabled = depositEnabled; d.supplyEnabled = supplyEnabled; d.supplyTarget = supplyTarget;
            d.outputPos = outputPos; d.supplyChests = new ArrayList<>(supplyChests);
            d.totalPotions = totalPotions;
            d.produced = new LinkedHashMap<>(produced);
            d.queue = new ArrayList<>(queue);
            d.resume = resume;
            Files.writeString(file(), GSON.toJson(d));
        } catch (Exception e) {
            System.err.println("[AutoBrew] gagal menyimpan config: " + e);
        }
    }

    static <E extends Enum<E>> E parse(Class<E> c, String v, E def) {
        if (v == null) return def;
        try { return Enum.valueOf(c, v); } catch (Exception e) { return def; }
    }
}
