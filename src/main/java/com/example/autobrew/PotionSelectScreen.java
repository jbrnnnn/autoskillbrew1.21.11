package com.example.autobrew;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PotionSelectScreen extends Screen {
    private static final int COLS = 2, CARD_H = 24, GAP = 3, HDR_H = 54, Q_ROW = 13;
    private static int tab = 0;     // 0 = Potion, 1 = Botol, 2 = Antrean

    private final Screen parent;
    private BlockPos aimed;         // blok yang dilihat pemain saat menu dibuka (untuk simpan posisi Stand/Air)
    private int px, py, pw, ph;
    private int listX, listY, listW, listH, colW;
    private int rightX, rightW;
    private int rowH, wh, cx, cw;
    private int qx, qy, qw, qh;
    private int statusMaxW;
    private int scroll = 0, tickCount = 0;
    private String note = null;

    private record Lbl(String text, int x, int y) {}
    private record Seg(String text, int color) {}

    private final List<Lbl> labels = new ArrayList<>();
    private final List<ClickableWidget> tippable = new ArrayList<>();
    private final List<Seg> stock = new ArrayList<>();

    // cache agar tidak membuat objek/String baru tiap frame
    private static final Map<String, ItemStack> ICONS = new HashMap<>();
    private static ItemStack stand;
    private String[] cardNames = new String[0];
    private String[] queueLines = new String[0];
    private String hdrName, hdrResult, hdrSteps, idleText;

    /** Dibuka lewat keybind (tanpa parent). */
    public PotionSelectScreen() { this(null); }

    /** Dibuka dari Mod Menu: parent = layar daftar mod. */
    public PotionSelectScreen(Screen parent) {
        super(Text.literal("Auto Potion Brewer"));
        this.parent = parent;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.world != null
                && mc.crosshairTarget instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK) {
            aimed = b.getBlockPos();
        }
    }

    private static ItemStack icon(PotionRecipe r) {
        return ICONS.computeIfAbsent(r.id(), k -> new ItemStack(r.icon()));
    }

    @Override public boolean shouldPause() { return false; }

    /** Kembali ke layar sebelumnya (Mod Menu) atau game. */
    @Override
    public void close() {
        AutoBrewConfig.saveIfDirty();
        client.setScreen(parent);
    }

    @Override
    public void tick() {
        super.tick();
        if (++tickCount % 10 == 0) rebuildStock();   // stok inventory berubah-ubah
    }

    private <T extends ClickableWidget> T add(T w) {
        addDrawableChild(w);
        if (w instanceof Ui.Tippable) tippable.add(w);
        return w;
    }

    // ------------------------------------------------------------ layout & widgets
    @Override
    protected void init() {
        labels.clear();
        tippable.clear();
        pw = Math.min(width - 12, 470);
        ph = Math.min(height - 12, 262);
        px = (width - pw) / 2;
        py = (height - ph) / 2;

        listX = px + 8;
        listY = py + 30;
        listW = Math.min(240, pw * 51 / 100);
        listH = ph - 30 - 34;
        colW = (listW - GAP) / COLS;
        rightX = listX + listW + 10;
        rightW = px + pw - 8 - rightX;
        clampScroll();
        rebuildCache();
        rebuildStock();

        // --- tab di title bar + tombol Log
        add(new Ui.Segmented(px + 128, py + 3, 212, 16, new String[]{"Potion", "Botol", "Antrean", "Chest"},
                new String[]{"Pilih potion, tier & bentuk", "Botol, hopper, isi ulang air & opsi",
                             "Antrean beberapa potion", "Chest: taruh potion jadi & ambil bahan"},
                allTrue(4), tab, 0x8A6AE8, i -> { tab = i; note = null; clearAndInit(); }));
        int fails = BrewLog.failCount();
        add(new Ui.FancyButton(px + pw - 8 - 64, py + 3, 64, 16, fails > 0 ? "Log (" + fails + ")" : "Log",
                fails > 0 ? 0xC0392B : 0x6A4FB8, "Log kejadian + jam kegagalan\ndan riwayat produksi",
                () -> client.setScreen(new LogScreen(this))));

        PotionRecipe r = AutoBrewConfig.recipe();
        boolean up = AutoBrewConfig.mode == AutoBrewConfig.Mode.UPGRADE;
        int ry = py + 32 + HDR_H + 6;
        switch (tab) {
            case 0 -> buildPotionTab(ry, r, up);
            case 1 -> buildBottleTab(ry, r, up);
            case 2 -> buildQueueTab(ry);
            default -> buildChestTab(ry);
        }
        buildBottomBar();
    }

    private void geometry(int rows, int ry) {
        int avail = (py + ph - 34) - ry;
        rowH = Math.max(18, Math.min(22, avail / rows));
        wh = rowH - 3;
        int lblW = 54;
        cx = rightX + lblW;
        cw = rightW - lblW;
    }

    private void label(String t, int ry) { labels.add(new Lbl(t, rightX, ry + (wh - 8) / 2)); }

    // ---- tab 0: Potion
    private void buildPotionTab(int ry, PotionRecipe r, boolean up) {
        geometry(up ? 5 : 4, ry);

        label("Mode", ry);
        add(new Ui.Segmented(cx, ry, cw, wh, new String[]{"Buat Baru", "Upgrade"},
                new String[]{"Buat potion dari botol awal\n(Water Bottle / Awkward)",
                             "Naikkan tier/bentuk potion tier I\nyang sudah ada"},
                allTrue(2), up ? 1 : 0, 0xA070FF, i -> {
            AutoBrewConfig.mode = i == 0 ? AutoBrewConfig.Mode.SCRATCH : AutoBrewConfig.Mode.UPGRADE;
            changed();
        }));
        ry += rowH;

        if (up) {
            label("Sumber", ry);
            add(new Ui.Segmented(cx, ry, cw, wh, formLabels(),
                    new String[]{"Sumber: potion biasa tier I", "Sumber: Splash Potion tier I", "Sumber: Lingering Potion tier I"},
                    allTrue(3), AutoBrewConfig.sourceForm.ordinal(), 0x2CC5B0, i -> {
                AutoBrewConfig.sourceForm = PotionRecipe.Form.values()[i];
                if (AutoBrewConfig.form.ordinal() < i) AutoBrewConfig.form = AutoBrewConfig.sourceForm;
                changed();
            }));
            ry += rowH;
        }

        boolean[] tierEn = new boolean[3];
        for (int i = 0; i < 3; i++) tierEn[i] = r == null || r.supports(PotionRecipe.Modifier.values()[i]);
        label("Tier", ry);
        add(new Ui.Segmented(cx, ry, cw, wh, new String[]{"I", "I+", "II"},
                new String[]{"Tier I: potion standar", "Tier I+: durasi lebih lama\n(Redstone)", "Tier II: efek lebih kuat\n(Glowstone)"},
                tierEn, AutoBrewConfig.modifier.ordinal(), 0xE8A317, i -> {
            AutoBrewConfig.modifier = PotionRecipe.Modifier.values()[i];
            changed();
        }));
        ry += rowH;

        boolean[] formEn = new boolean[3];
        for (int i = 0; i < 3; i++) formEn[i] = !up || i >= AutoBrewConfig.sourceForm.ordinal();
        label(up ? "Jadi" : "Bentuk", ry);
        add(new Ui.Segmented(cx, ry, cw, wh, formLabels(),
                new String[]{"Potion biasa (diminum)", "Splash: dilempar\n(+ Gunpowder)",
                             "Lingering: awan efek\n(+ Gunpowder + Dragon's Breath)"},
                formEn, AutoBrewConfig.form.ordinal(), 0x3AA0FF, i -> {
            AutoBrewConfig.form = PotionRecipe.Form.values()[i];
            changed();
        }));
        ry += rowH;

        label("Siklus", ry);
        add(new Ui.IntSlider(cx, ry, cw, wh, 0, 64, AutoBrewConfig.maxCycles,
                v -> v == 0 ? "Tanpa batas" : v + " siklus",
                v -> { AutoBrewConfig.maxCycles = v; AutoBrewConfig.markDirty(); }));
    }

    // ---- tab 1: Botol & otomatisasi
    private void buildBottleTab(int ry, PotionRecipe r, boolean up) {
        geometry(7, ry);
        boolean awkOk = !up && r != null && !r.chain().isEmpty() && r.chain().get(0).equals("awkward");

        label("Botol", ry);
        add(new Ui.Segmented(cx, ry, cw, wh, new String[]{"Inventory", "Hopper"},
                new String[]{"Mod memasukkan botol dari inventory",
                             "Mod TIDAK mengisi botol (pakai hopper/manual)\nMenunggu jumlah botol stabil, lalu jalan"},
                allTrue(2), AutoBrewConfig.useInventoryBottles ? 0 : 1, 0x2CC5B0, i -> {
            AutoBrewConfig.useInventoryBottles = i == 0;
            changed();
        }));
        ry += rowH;

        label("Mulai", ry);
        add(new Ui.Segmented(cx, ry, cw, wh, new String[]{"Water", "Awkward"},
                new String[]{"Botol awal: Water Bottle\n(Nether Wart ditambah mod)",
                             "Botol awal: Awkward Potion\n(lewati Nether Wart)"},
                new boolean[]{true, awkOk}, awkOk ? AutoBrewConfig.base.ordinal() : 0, 0x3AA0FF, i -> {
            AutoBrewConfig.base = AutoBrewConfig.Base.values()[i];
            changed();
        }));
        ry += rowH;

        label("Isi air", ry);
        add(new Ui.Segmented(cx, ry, cw, wh, new String[]{"OFF", "ON"},
                new String[]{"Tidak mengisi ulang air",
                             "Isi ulang Water Bottle dari Glass Bottle saat habis\n(butuh posisi Stand & Air + Glass Bottle)"},
                allTrue(2), AutoBrewConfig.autoRefill ? 1 : 0, 0xE8A317, i -> {
            AutoBrewConfig.autoRefill = i == 1;
            changed();
        }));
        ry += rowH;

        label("Target", ry);
        add(new Ui.IntSlider(cx, ry, cw, wh, 3, 36, AutoBrewConfig.refillTarget, v -> v + " botol",
                v -> { AutoBrewConfig.refillTarget = v; AutoBrewConfig.markDirty(); }));
        ry += rowH;

        label("Posisi", ry);
        int half = (cw - 2) / 2;
        AutoBrewConfig.Pos sp = AutoBrewConfig.standPos, wp = AutoBrewConfig.waterPos;
        add(new Ui.FancyButton(cx, ry, half, wh, "Stand: " + (sp == null ? "-" : "ok"),
                sp == null ? 0x6A6A7A : 0x3C8D5A,
                (sp == null ? "Belum diset" : "Tersimpan: " + sp.text())
                        + "\nBuka menu (G) sambil mengarahkan kursor\nke Brewing Stand, lalu klik",
                () -> setPos(true)));
        add(new Ui.FancyButton(cx + half + 2, ry, cw - half - 2, wh, "Air: " + (wp == null ? "-" : "ok"),
                wp == null ? 0x6A6A7A : 0x3C8D5A,
                (wp == null ? "Belum diset" : "Tersimpan: " + wp.text())
                        + "\nBuka menu (G) sambil mengarahkan kursor\nke sumber air / cauldron berisi air, lalu klik",
                () -> setPos(false)));
        ry += rowH;

        String[] sp2 = new String[AutoBrewConfig.Speed.values().length];
        for (int i = 0; i < sp2.length; i++) sp2[i] = AutoBrewConfig.Speed.values()[i].label;
        label("Kecepatan", ry);
        add(new Ui.Segmented(cx, ry, cw, wh, sp2,
                new String[]{"1 aksi tiap 6 tick\nPaling aman untuk server",
                             "Klik digabung, jeda 3 tick",
                             "Klik digabung, jeda 1 tick\nBisa dianggap spam di server ketat"},
                allTrue(sp2.length), AutoBrewConfig.speed.ordinal(), 0x4CD964, i -> {
            AutoBrewConfig.speed = AutoBrewConfig.Speed.values()[i];
            changed();
        }));
        ry += rowH;

        label("Opsi", ry);
        int bw = (cw - 4) / 3;
        add(new Ui.FancyButton(cx, ry, bw, wh, "HUD", AutoBrewConfig.showHud ? 0x3C8D5A : 0x5A5A68,
                "Overlay status di layar\nSaat ini: " + onOff(AutoBrewConfig.showHud), () -> {
            AutoBrewConfig.showHud = !AutoBrewConfig.showHud;
            changed();
        }));
        add(new Ui.FancyButton(cx + bw + 2, ry, bw, wh, "Bunyi", AutoBrewConfig.sound ? 0x3C8D5A : 0x5A5A68,
                "Bunyi saat selesai / gagal\nSaat ini: " + onOff(AutoBrewConfig.sound), () -> {
            AutoBrewConfig.sound = !AutoBrewConfig.sound;
            changed();
        }));
        add(new Ui.FancyButton(cx + (bw + 2) * 2, ry, cw - (bw + 2) * 2, wh, "Buka",
                AutoBrewConfig.autoOpen ? 0x3C8D5A : 0x5A5A68,
                "Otomatis membuka Brewing Stand\nSaat ini: " + onOff(AutoBrewConfig.autoOpen), () -> {
            AutoBrewConfig.autoOpen = !AutoBrewConfig.autoOpen;
            changed();
        }));
    }

    // ---- tab 2: Antrean
    private void buildQueueTab(int ry) {
        int half = (rightW - 2) / 2;
        add(new Ui.FancyButton(rightX, ry, half, 18, "+ Tambah", 0x2FB35A,
                "Tambahkan pengaturan saat ini (potion, tier,\nbentuk, siklus) ke antrean", () -> {
            if (AutoBrewConfig.addCurrentToQueue()) changed();
            else { note = "Pilih potion yang valid dulu (maks " + AutoBrewConfig.MAX_QUEUE + " antrean)."; clearAndInit(); }
        }));
        add(new Ui.FancyButton(rightX + half + 2, ry, rightW - half - 2, 18, "Kosongkan", 0xD64545,
                "Hapus semua antrean", () -> { AutoBrewConfig.clearQueue(); changed(); }));
        qx = rightX;
        qy = ry + 24;
        qw = rightW;
        qh = (py + ph - 34) - qy;
    }

    // ---- tab 3: Chest
    private void buildChestTab(int ry) {
        geometry(5, ry);
        AutoBrewConfig.Pos op = AutoBrewConfig.outputPos;

        label("Simpan", ry);
        add(new Ui.Segmented(cx, ry, cw, wh, new String[]{"OFF", "ON"},
                new String[]{"Tidak menaruh potion ke chest",
                             "Taruh potion jadi ke chest Output saat inventory\nhampir penuh & di akhir antrean"},
                allTrue(2), AutoBrewConfig.depositEnabled ? 1 : 0, 0x2CC5B0, i -> {
            AutoBrewConfig.depositEnabled = i == 1;
            changed();
        }));
        ry += rowH;

        label("Output", ry);
        add(new Ui.FancyButton(cx, ry, cw, wh, "Output: " + (op == null ? "belum diset" : "ok"),
                op == null ? 0x6A6A7A : 0x3C8D5A,
                (op == null ? "Belum diset" : "Tersimpan: " + op.text())
                        + "\nBuka menu (G) sambil mengarahkan kursor ke\nchest/barrel tujuan, lalu klik",
                () -> setChest(true)));
        ry += rowH;

        label("Isi bahan", ry);
        add(new Ui.Segmented(cx, ry, cw, wh, new String[]{"OFF", "ON"},
                new String[]{"Tidak mengambil bahan dari chest",
                             "Ambil bahan dari chest Supply saat hampir habis\n(bahan resep, Blaze Powder, botol awal)"},
                allTrue(2), AutoBrewConfig.supplyEnabled ? 1 : 0, 0xE8A317, i -> {
            AutoBrewConfig.supplyEnabled = i == 1;
            changed();
        }));
        ry += rowH;

        label("Supply", ry);
        int half = (cw - 2) / 2;
        int n = AutoBrewConfig.supplyChests.size();
        StringBuilder tip = new StringBuilder(n == 0 ? "Belum ada chest Supply" : "Chest Supply tersimpan:");
        for (AutoBrewConfig.Pos q : AutoBrewConfig.supplyChests) tip.append("\n- ").append(q.text());
        tip.append("\nArahkan kursor ke chest lalu buka menu (G),\nklik untuk menambah (maks ").append(AutoBrewConfig.MAX_SUPPLY).append(")");
        add(new Ui.FancyButton(cx, ry, half, wh, "+ Chest (" + n + ")", n == 0 ? 0x6A6A7A : 0x3C8D5A,
                tip.toString(), () -> setChest(false)));
        add(new Ui.FancyButton(cx + half + 2, ry, cw - half - 2, wh, "Kosongkan", 0xD64545,
                "Hapus semua chest Supply", () -> { AutoBrewConfig.clearSupply(); changed(); }));
        ry += rowH;

        label("Target", ry);
        add(new Ui.IntSlider(cx, ry, cw, wh, 8, 128, AutoBrewConfig.supplyTarget, v -> v + " per bahan",
                v -> { AutoBrewConfig.supplyTarget = v; AutoBrewConfig.markDirty(); }));
    }

    private void setChest(boolean output) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (aimed == null || mc.world == null) {
            note = "Buka menu (G) sambil mengarahkan kursor ke chest/barrel.";
            clearAndInit();
            return;
        }
        if (!Materials.isContainer(mc.world.getBlockState(aimed))) {
            note = "Blok yang kamu lihat bukan chest / trapped chest / barrel.";
            clearAndInit();
            return;
        }
        AutoBrewConfig.Pos p = new AutoBrewConfig.Pos(aimed);
        if (output) {
            AutoBrewConfig.outputPos = p;
            note = "Chest Output disimpan: " + p.text();
        } else if (AutoBrewConfig.addSupplyChest(p)) {
            note = "Chest Supply ditambahkan: " + p.text();
        } else {
            note = "Chest sudah ada di daftar, atau sudah " + AutoBrewConfig.MAX_SUPPLY + " chest.";
            clearAndInit();
            return;
        }
        AutoBrewConfig.markDirty();
        clearAndInit();
    }

    private void buildBottomBar() {
        int by = py + ph - 26;
        int closeX = px + pw - 8 - 70;
        int startX = closeX - 4 - 120;
        boolean hasReset = AutoBrewConfig.resume != null && !BrewController.enabled;
        int resetX = startX - 4 - 56;
        statusMaxW = (hasReset ? resetX : startX) - (px + 10) - 6;

        String startLabel = BrewController.enabled ? "STOP" : AutoBrewConfig.resumeMatches() ? "LANJUTKAN" : "START";
        add(new Ui.FancyButton(startX, by, 120, 20, startLabel,
                BrewController.enabled ? 0xD64545 : 0x2FB35A, () -> {
            if (BrewController.enabled) {
                BrewController.stop("dihentikan");
                note = null;
                clearAndInit();
            } else {
                String err = BrewController.start();
                if (err != null) { note = err; clearAndInit(); }
                else { AutoBrewConfig.saveIfDirty(); client.setScreen(null); }
            }
        }));
        if (hasReset) {
            add(new Ui.FancyButton(resetX, by, 56, 20, "Reset", 0xB5651D,
                    "Hapus progres tersimpan\n(mulai lagi dari awal)", () -> {
                AutoBrewConfig.clearResume();
                BrewLog.log(BrewLog.Level.INFO, "", "Progres tersimpan direset oleh pemain");
                AutoBrewConfig.saveIfDirty();
                note = null;
                clearAndInit();
            }));
        }
        add(new Ui.FancyButton(closeX, by, 70, 20, parent != null ? "Kembali" : "Tutup", 0x6A6A7A, this::close));
    }

    private void setPos(boolean standPos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (aimed == null || mc.world == null) {
            note = "Buka menu (G) sambil mengarahkan kursor ke blok targetnya.";
            clearAndInit();
            return;
        }
        if (standPos) {
            if (!mc.world.getBlockState(aimed).isOf(Blocks.BREWING_STAND)) {
                note = "Blok yang kamu lihat bukan Brewing Stand.";
                clearAndInit();
                return;
            }
            AutoBrewConfig.standPos = new AutoBrewConfig.Pos(aimed);
            note = "Posisi Brewing Stand disimpan: " + AutoBrewConfig.standPos.text();
        } else {
            boolean water = mc.world.getFluidState(aimed).getFluid() == Fluids.WATER
                    || mc.world.getBlockState(aimed).isOf(Blocks.WATER_CAULDRON);
            if (!water) {
                note = "Blok yang kamu lihat bukan sumber air / cauldron berisi air.";
                clearAndInit();
                return;
            }
            AutoBrewConfig.waterPos = new AutoBrewConfig.Pos(aimed);
            note = "Posisi air disimpan: " + AutoBrewConfig.waterPos.text();
        }
        AutoBrewConfig.markDirty();
        clearAndInit();
    }

    private static String onOff(boolean b) { return b ? "ON" : "OFF"; }

    private void changed() {
        PotionRecipe r = AutoBrewConfig.recipe();
        if (r != null && !r.supports(AutoBrewConfig.modifier)) AutoBrewConfig.modifier = PotionRecipe.Modifier.NONE;
        AutoBrewConfig.markDirty();
        note = null;
        clearAndInit();
    }

    private static String[] formLabels() {
        PotionRecipe.Form[] f = PotionRecipe.Form.values();
        String[] s = new String[f.length];
        for (int i = 0; i < f.length; i++) s[i] = f[i].label;
        return s;
    }

    private static boolean[] allTrue(int n) {
        boolean[] b = new boolean[n];
        java.util.Arrays.fill(b, true);
        return b;
    }

    // ------------------------------------------------------------ cache
    private void rebuildCache() {
        var t = Ui.tr();
        cardNames = new String[PotionRecipe.ALL.size()];
        for (int i = 0; i < cardNames.length; i++) cardNames[i] = t.trimToWidth(PotionRecipe.ALL.get(i).name(), colW - 30);

        String rs = AutoBrewConfig.resumeSummary();
        if (rs != null) {
            idleText = AutoBrewConfig.resumeMatches()
                    ? "Tersimpan: " + rs + " - klik LANJUTKAN"
                    : "Progres lama tidak cocok dengan pengaturan (akan dibuang)";
        } else {
            idleText = "Siap" + (AutoBrewConfig.queue.isEmpty() ? "" : " - antrean " + AutoBrewConfig.queue.size() + " potion")
                    + ". Total dibuat: " + AutoBrewConfig.totalPotions + " potion";
        }

        int ql = AutoBrewConfig.queue.size();
        queueLines = new String[ql];
        for (int i = 0; i < ql; i++) {
            Job j = Job.fromData(AutoBrewConfig.queue.get(i));
            queueLines[i] = t.trimToWidth((i + 1) + ". " + j.name()
                    + (j.cycles > 0 ? "  x" + j.cycles : "  (tanpa batas)"), Math.max(20, rightW - 22));
        }

        Job job = Job.fromConfig();
        if (!job.valid()) { hdrName = hdrResult = hdrSteps = null; return; }
        int tw = rightW - 50;
        hdrName = t.trimToWidth(job.recipe().name(), tw);
        hdrResult = t.trimToWidth(job.name(), tw);
        StringBuilder sb = new StringBuilder();
        List<Item> st = job.stages();
        for (int i = job.startIndex(); i < st.size(); i++) {
            if (i > job.startIndex()) sb.append(" > ");
            sb.append(st.get(i).getName().getString());
        }
        hdrSteps = t.trimToWidth(sb.toString(), tw);
    }

    /** Stok bahan di inventory: hijau = cukup, merah = kurang. */
    private void rebuildStock() {
        stock.clear();
        Job job = Job.fromConfig();
        if (!job.valid()) return;
        if (MinecraftClient.getInstance().player == null) {
            stock.add(new Seg("Masuk dunia untuk cek stok", 0xFF8888A0));
            return;
        }
        int factor = Math.max(1, job.cycles);
        if (!AutoBrewConfig.useInventoryBottles) {
            stock.add(new Seg("Botol: hopper", 0xFF9AA0B8));
        } else {
            String nm = job.startState().potion().equals("water") ? "Botol" : "Sumber";
            stock.add(seg(nm, Materials.countSource(job), 3 * factor));
        }
        for (Map.Entry<Item, Integer> e : Materials.totalNeeds(job, factor).entrySet()) {
            stock.add(seg(e.getKey().getName().getString(), Materials.count(e.getKey()), e.getValue()));
        }
    }

    private static Seg seg(String name, int have, int need) {
        return new Seg(name + " " + have, have >= need ? 0xFF7CFF9E : 0xFFFF7070);
    }

    // ------------------------------------------------------------ scrolling & klik
    private int contentH() {
        int rows = (PotionRecipe.ALL.size() + COLS - 1) / COLS;
        return rows * (CARD_H + GAP);
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentH() - listH)));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            scroll -= (int) (vAmt * 14);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mx, my, hAmt, vAmt);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        double mx = click.x(), my = click.y();
        if (click.button() == 0) {
            // pilih potion
            if (mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
                double relY = my - listY + scroll;
                int row = (int) (relY / (CARD_H + GAP));
                int col = (int) ((mx - listX) / (colW + GAP));
                if (relY % (CARD_H + GAP) < CARD_H && (mx - listX) % (colW + GAP) < colW && col < COLS) {
                    int idx = row * COLS + col;
                    if (idx >= 0 && idx < PotionRecipe.ALL.size()) {
                        AutoBrewConfig.recipeId = PotionRecipe.ALL.get(idx).id();
                        changed();
                        return true;
                    }
                }
            }
            // hapus item antrean
            if (tab == 2 && mx >= qx && mx < qx + qw && my >= qy && my < qy + qh) {
                int idx = (int) ((my - qy) / Q_ROW);
                if (idx >= 0 && idx < AutoBrewConfig.queue.size()) {
                    AutoBrewConfig.removeFromQueue(idx);
                    changed();
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    // ------------------------------------------------------------ gambar
    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        // tooltip digambar paling akhir (di atas semua widget)
        for (ClickableWidget w : tippable) {
            if (!w.isMouseOver(mx, my)) continue;
            String tip = ((Ui.Tippable) w).tipAt(mx, my);
            if (tip == null) continue;
            List<Text> lines = new ArrayList<>();
            for (String s : tip.split("\n")) lines.add(Text.literal(s));
            ctx.drawTooltip(textRenderer, lines, mx, my);
            break;
        }
    }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        super.renderBackground(ctx, mx, my, delta);

        ctx.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, 0xFF6A48C0);
        ctx.fill(px, py, px + pw, py + ph, 0xF0121218);

        ctx.fillGradient(px, py, px + pw, py + 22, 0xFF3A2370, 0xFF1C1236);
        if (stand == null) stand = new ItemStack(Items.BREWING_STAND);
        ctx.drawItem(stand, px + 6, py + 3);
        ctx.drawTextWithShadow(Ui.tr(), "Auto Potion", px + 26, py + 7, 0xFFFFFFFF);

        drawList(ctx, mx, my);
        drawRight(ctx, mx, my);

        int by = py + ph - 26;
        String st; int col;
        if (BrewController.enabled)  { st = BrewController.statusLine(); col = 0xFF7CFF9E; }
        else if (note != null)       { st = note; col = 0xFFFFD166; }
        else                         { st = idleText; col = 0xFF8888A0; }
        ctx.drawTextWithShadow(Ui.tr(), Ui.tr().trimToWidth(st, statusMaxW), px + 10, by + 6, col);
    }

    private void drawList(DrawContext ctx, int mx, int my) {
        ctx.fill(listX - 2, listY - 2, listX + listW + 2, listY + listH + 2, 0xFF08080C);
        ctx.enableScissor(listX, listY, listX + listW, listY + listH);
        for (int i = 0; i < PotionRecipe.ALL.size(); i++) {
            PotionRecipe r = PotionRecipe.ALL.get(i);
            int x = listX + (i % COLS) * (colW + GAP);
            int y = listY + (i / COLS) * (CARD_H + GAP) - scroll;
            if (y + CARD_H < listY || y > listY + listH) continue;

            boolean sel = r.id().equals(AutoBrewConfig.recipeId);
            boolean hov = mx >= x && mx < x + colW && my >= y && my < y + CARD_H
                    && my >= listY && my < listY + listH;
            int border = sel ? 0xFFFFC040 : hov ? 0xFFFFFFFF : Ui.shade(r.color(), 0.4f);
            ctx.fill(x, y, x + colW, y + CARD_H, border);
            ctx.fillGradient(x + 1, y + 1, x + colW - 1, y + CARD_H - 1,
                    sel ? Ui.shade(r.color(), 0.38f) : hov ? 0xFF262638 : 0xFF1B1B28,
                    sel ? Ui.shade(r.color(), 0.2f) : 0xFF14141E);
            ctx.fill(x + 1, y + 1, x + 5, y + CARD_H - 1, Ui.shade(r.color(), 1.0f));   // strip warna potion
            ctx.drawItem(icon(r), x + 8, y + (CARD_H - 16) / 2);
            ctx.drawTextWithShadow(Ui.tr(), cardNames[i], x + 27, y + (CARD_H - 8) / 2,
                    sel ? 0xFFFFE9A8 : 0xFFE0E0EC);
        }
        ctx.disableScissor();

        int ch = contentH();
        if (ch > listH) {
            int barH = Math.max(14, listH * listH / ch);
            int barY = listY + (listH - barH) * scroll / (ch - listH);
            ctx.fill(listX + listW - 3, listY, listX + listW - 1, listY + listH, 0x66000000);
            ctx.fill(listX + listW - 3, barY, listX + listW - 1, barY + barH, 0xFFB8A0FF);
        }
    }

    private void drawRight(DrawContext ctx, int mx, int my) {
        ctx.fill(rightX - 4, py + 28, px + pw - 4, py + ph - 30, 0xFF181824);

        PotionRecipe r = AutoBrewConfig.recipe();
        int hx = rightX, hy = py + 32, hw = rightW;

        if (r == null || hdrName == null) {
            ctx.fill(hx, hy, hx + hw, hy + HDR_H, 0xFF222234);
            ctx.drawTextWithShadow(Ui.tr(), "Pilih potion di daftar kiri", hx + 8, hy + 23, 0xFFB0B0C8);
        } else {
            ctx.fillGradient(hx, hy, hx + hw, hy + HDR_H, Ui.shade(r.color(), 0.55f), 0xFF1E1E2E);
            ctx.fill(hx, hy, hx + 3, hy + HDR_H, Ui.shade(r.color(), 1.0f));

            var m = ctx.getMatrices();      // Matrix3x2fStack (1.21.6+)
            m.pushMatrix();
            m.translate(hx + 8f, hy + 8f);
            m.scale(2f, 2f);
            ctx.drawItem(icon(r), 0, 0);
            m.popMatrix();

            int tx = hx + 46;
            ctx.drawTextWithShadow(Ui.tr(), hdrName, tx, hy + 5, 0xFFFFFFFF);
            ctx.drawTextWithShadow(Ui.tr(), hdrResult, tx, hy + 16, 0xFF7FE3FF);
            ctx.drawTextWithShadow(Ui.tr(), hdrSteps, tx, hy + 27, 0xFFA0A0B8);

            int sx = tx, sy = hy + 39, limit = hx + hw - 4;
            for (Seg s : stock) {
                int w = Ui.tr().getWidth(s.text());
                if (sx + w > limit) break;
                ctx.drawTextWithShadow(Ui.tr(), s.text(), sx, sy, s.color());
                sx += w + 8;
            }
        }

        for (Lbl l : labels) ctx.drawTextWithShadow(Ui.tr(), l.text(), l.x(), l.y(), 0xFFC8C8DC);
        if (tab == 2) drawQueue(ctx, mx, my);
    }

    private void drawQueue(DrawContext ctx, int mx, int my) {
        ctx.fill(qx - 2, qy - 2, qx + qw + 2, qy + qh + 2, 0xFF08080C);
        if (queueLines.length == 0) {
            ctx.drawTextWithShadow(Ui.tr(), "Antrean kosong.", qx + 4, qy + 4, 0xFF8888A0);
            ctx.drawTextWithShadow(Ui.tr(), "START memakai pengaturan di tab Potion.", qx + 4, qy + 16, 0xFF8888A0);
            return;
        }
        for (int i = 0; i < queueLines.length; i++) {
            int y = qy + i * Q_ROW;
            if (y + Q_ROW > qy + qh) break;
            boolean hov = mx >= qx && mx < qx + qw && my >= y && my < y + Q_ROW;
            if (hov) ctx.fill(qx, y, qx + qw, y + Q_ROW, 0x66D64545);
            ctx.drawTextWithShadow(Ui.tr(), queueLines[i], qx + 3, y + 3, 0xFFE0E0EC);
            ctx.drawTextWithShadow(Ui.tr(), "x", qx + qw - 9, y + 3, hov ? 0xFFFFFFFF : 0xFFFF7070);
        }
    }
}
