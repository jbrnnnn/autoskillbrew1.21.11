package com.example.autobrew;

import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.BrewingStandScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * State machine (per Job di antrean):
 *   BOTTLES -> INGREDIENT -> WAIT -> (INGREDIENT ...) -> TAKE -> BOTTLES (ulang)
 *   UNLOAD : mengeluarkan potion yang tidak sesuai antrean
 *   REFILL : mengisi ulang Water Bottle dari Glass Bottle (menutup Brewing Stand, mengarah ke air, kembali)
 *
 * Isi Brewing Stand SELALU dikenali dulu (reconcile): potion di stand dicocokkan dengan tahap job sekarang,
 * lalu job lain di antrean, jika tidak cocok dikeluarkan. Progres diingat saat berhenti.
 * Slot BrewingStand: 0-2 botol, 3 bahan, 4 blaze powder (fuel). Inventory pemain mulai slot 5.
 */
public class BrewController {
    public static boolean enabled = false;
    /** true bila pemain menutup Brewing Stand sendiri (ESC): auto-buka dihentikan sementara. */
    public static boolean pausedByUser = false;
    /** Diset sebelum mod menutup Brewing Stand sendiri (supaya tidak dianggap ESC pemain). */
    public static boolean expectClose = false;
    public static int cycles = 0;
    public static int sessionPotions = 0;

    private enum State {
        BOTTLES("isi botol"), INGREDIENT("isi bahan"), WAIT("menyeduh"), TAKE("ambil hasil"),
        UNLOAD("keluarkan potion lain"), REFILL("isi ulang air"), TRIP("chest");
        final String label;
        State(String l) { label = l; }
    }

    private static List<Job> plan = List.of();
    private static int jobIdx;
    private static int[] cyclesDone = new int[0];
    private static boolean[] done = new boolean[0];
    private static Job job;
    private static List<Item> stages = List.of();
    private static List<PotionRecipe.PState> states = List.of();

    private static State state = State.BOTTLES;
    private static int stage, wait, timeout, takeTries, cyclePotions, openCooldown, busyTicks;
    private static int lastBottleCount = -1, stableTicks;
    private static boolean started, needReconcile, waitingBottles;

    // isi ulang air
    private static int refillPhase, refillTicks, refillNoProgress, refillLastWater, refillOpenTries;

    // perjalanan ke chest: taruh potion jadi (DEPOSIT) / ambil bahan (SUPPLY)
    private enum TripKind { DEPOSIT, SUPPLY }

    private record SupplyItem(String name, Item item, boolean source, int target) {
        int count() { return source ? Materials.countSource(job) : Materials.count(item); }
        boolean matches(ItemStack s) { return source ? Materials.isSource(s, job) : s.isOf(item); }
    }

    private static TripKind tripKind;
    private static int tripPhase, tripTicks, tripChest, tripScan, tripOpenTries, tripGain, depositAttempts;
    private static final List<BlockPos> tripChests = new ArrayList<>();
    private static List<SupplyItem> supply = List.of();
    private static int[] supplyStart = new int[0];
    private static boolean supplyBlocked, tripFull;
    private static String afterTripNote;
    private static Set<PotionRecipe.PState> finals = new HashSet<>();   // wujud akhir tiap job (= "potion jadi")

    private static final int LOOKAHEAD = 2;     // cek bahan untuk sekian siklus ke depan
    private static final int DEPOSIT_FREE = 4;  // taruh potion ke chest bila slot kosong <= ini

    private static String statusCache = "AutoBrew";
    private static boolean statusDirty = true;

    private static final int PLAYER_START = 5;
    private static final int START_TIMEOUT = 60;   // brew harus mulai maks 3 detik setelah bahan masuk
    private static final int BREW_TIMEOUT = 800;
    private static final int HOPPER_STABLE = 60;   // tick tanpa perubahan jumlah botol sebelum jalan (mode hopper)

    private static void setState(State s) { state = s; statusDirty = true; }

    private static void setJob(int idx) {
        jobIdx = idx;
        job = plan.get(idx);
        stages = job.stages();
        states = job.states();
        statusDirty = true;
    }

    private static int firstNotDone() {
        for (int i = 0; i < done.length; i++) if (!done[i]) return i;
        return -1;
    }

    private static String potionName() { return job == null ? "" : job.name(); }

    // ------------------------------------------------------------------ start / stop
    /** @return null jika sukses, atau pesan error. */
    public static String start() {
        if (MinecraftClient.getInstance().player == null) return "Masuk ke dunia dulu untuk menjalankan.";
        List<Job> p = AutoBrewConfig.plan();
        for (int i = 0; i < p.size(); i++) {
            Job j = p.get(i);
            if (!j.valid()) {
                if (p.size() > 1) return "Antrean #" + (i + 1) + " tidak valid.";
                return j.recipe() == null ? "Pilih potion dulu."
                        : "Tidak ada yang di-upgrade: tier & bentuk sama dengan sumber.";
            }
        }

        AutoBrewConfig.Resume res = AutoBrewConfig.resume;
        boolean resuming = res != null && res.matches();
        if (res != null && !resuming) {
            BrewLog.log(BrewLog.Level.WARN, "", "Progres lama dibuang karena antrean/pengaturan berubah.");
            AutoBrewConfig.clearResume();
        }

        plan = p;
        cyclesDone = new int[p.size()];
        done = new boolean[p.size()];
        int idx = 0;
        sessionPotions = 0;
        if (resuming) {
            if (res.cyclesDone != null && res.cyclesDone.length == p.size()) cyclesDone = res.cyclesDone.clone();
            if (res.done != null && res.done.length == p.size()) done = res.done.clone();
            idx = Math.max(0, Math.min(res.jobIdx, p.size() - 1));
            sessionPotions = res.sessionPotions;
        }
        if (firstNotDone() < 0) {                       // semua sudah selesai -> ulang dari awal
            done = new boolean[p.size()];
            cyclesDone = new int[p.size()];
            idx = 0;
            resuming = false;
            sessionPotions = 0;
        } else if (done[idx]) {
            idx = firstNotDone();
        }
        cycles = 0;
        for (int c : cyclesDone) cycles += c;

        setJob(idx);
        stage = 0; wait = 0; timeout = 0; takeTries = 0; cyclePotions = 0; openCooldown = 0; busyTicks = 0;
        lastBottleCount = -1; stableTicks = 0; started = false; waitingBottles = false;
        pausedByUser = false; expectClose = false; refillPhase = 0;
        needReconcile = true;                           // isi Brewing Stand dikenali dulu saat dibuka
        setState(State.BOTTLES);
        supplyBlocked = false; tripFull = false; afterTripNote = null; tripPhase = 0; depositAttempts = 0;
        finals = new HashSet<>();
        for (Job j : p) {
            List<PotionRecipe.PState> sts = j.states();
            finals.add(sts.get(sts.size() - 1));
        }

        if (resuming) {
            BrewLog.log(BrewLog.Level.INFO, potionName(), "Melanjutkan progres: " + AutoBrewConfig.resumeSummary());
            msg("LANJUT: " + potionName());
        } else {
            BrewLog.log(BrewLog.Level.INFO, potionName(),
                    "Mulai" + (p.size() > 1 ? " antrean (" + p.size() + " potion)" : "") + ": " + potionName());
            msg("ON: " + potionName());
        }
        AutoBrewConfig.clearResume();                   // dipakai; disimpan lagi bila berhenti

        // Peringatan awal (tidak memblokir: botol/progres mungkin sudah ada di Brewing Stand / hopper)
        String miss = Materials.missingFrom(job, 0);
        if (miss != null) BrewLog.log(BrewLog.Level.WARN, potionName(), "Peringatan awal: " + miss);
        long blazeIngr = stages.stream().filter(i -> i == Items.BLAZE_POWDER).count();
        if (blazeIngr > 0 && Materials.count(Items.BLAZE_POWDER) < blazeIngr + 1) {
            BrewLog.log(BrewLog.Level.WARN, potionName(),
                    "Blaze Powder pas-pasan: butuh " + blazeIngr + " bahan + 1 fuel (jika fuel kosong), ada "
                            + Materials.count(Items.BLAZE_POWDER));
        }
        enabled = true;
        return null;
    }

    private static String context() {
        if (job == null) return "";
        int shown = Math.min(stage + 1, Math.max(1, stages.size()));
        return " [" + (plan.size() > 1 ? "antrean #" + (jobIdx + 1) + ", " : "") + "siklus "
                + cyclesDone[jobIdx] + ", tahap " + shown + "/" + stages.size() + ", " + state.label + "]";
    }

    private static void saveResume(String reason) {
        AutoBrewConfig.Resume r = new AutoBrewConfig.Resume();
        r.sig = AutoBrewConfig.signature(plan);
        r.jobIdx = jobIdx;
        r.stage = stage;
        r.state = state.name();
        r.sessionPotions = sessionPotions;
        r.cyclesDone = cyclesDone.clone();
        r.done = done.clone();
        r.reason = reason;
        r.savedAt = BrewLog.now();
        AutoBrewConfig.resume = r;
        AutoBrewConfig.markDirty();
    }

    private static void halt(String reason, boolean keepProgress) {
        boolean was = enabled;
        enabled = false;
        statusDirty = true;
        if (was && keepProgress && job != null) saveResume(reason);
        msg("OFF: " + reason);
    }

    /** Berhenti oleh pemain / keluar dunia. Progres diingat. */
    public static void stop(String reason) {
        boolean was = enabled;
        halt(reason, true);
        if (was) BrewLog.log(BrewLog.Level.INFO, potionName(), "Berhenti: " + reason + context());
        AutoBrewConfig.saveIfDirty();
    }

    /** Selesai normal. Progres dihapus (statistik tetap). */
    private static void finish(String reason) {
        halt(reason, false);
        AutoBrewConfig.clearResume();
        BrewLog.log(BrewLog.Level.OK, potionName(), reason);
        chat("Selesai " + BrewLog.clock() + ": " + reason);
        beep(true);
        AutoBrewConfig.saveIfDirty();
    }

    /** Berhenti karena masalah. Dicatat di log dengan jam, progres diingat. */
    private static void fail(String reason) {
        String full = reason + context();
        halt(reason, true);
        BrewLog.log(BrewLog.Level.FAIL, potionName(), full);
        chat("GAGAL " + BrewLog.clock() + ": " + full + " - progres disimpan");
        beep(false);
        AutoBrewConfig.saveIfDirty();
    }

    public static void resume() {
        pausedByUser = false;
        openCooldown = 0;
        msg("dilanjutkan");
    }

    public static String statusLine() {
        if (statusDirty) {
            if (job == null) {
                statusCache = "AutoBrew";
            } else {
                String max = job.cycles > 0 ? "/" + job.cycles : "";
                statusCache = "AutoBrew: " + job.name()
                        + (plan.size() > 1 ? " [" + (jobIdx + 1) + "/" + plan.size() + "]" : "")
                        + " | siklus " + cyclesDone[jobIdx] + max + " | " + sessionPotions + " potion | " + state.label;
            }
            statusDirty = false;
        }
        return statusCache;
    }

    /** Petunjuk tambahan untuk HUD (konstanta, tanpa alokasi). */
    public static String hudHint(MinecraftClient mc) {
        if (!enabled || mc.player == null) return null;
        if (pausedByUser) return "DIJEDA - tekan H untuk lanjut";
        if (state == State.REFILL) return "Mengisi ulang Water Bottle...";
        if (state == State.TRIP) return "Mengurus chest (ambil bahan / taruh potion)...";
        if (waitingBottles) return "Menunggu botol dari hopper...";
        if (mc.player.currentScreenHandler instanceof BrewingStandScreenHandler) return null;
        return AutoBrewConfig.autoOpen
                ? "Dekati Brewing Stand (dibuka otomatis)"
                : "Buka Brewing Stand untuk melanjutkan";
    }

    private static int delay() { return AutoBrewConfig.speed.ticks; }
    private static boolean batch() { return AutoBrewConfig.speed.batch; }

    // ------------------------------------------------------------------ tick
    public static void tick(MinecraftClient mc) {
        if (!enabled || mc.player == null || mc.interactionManager == null || job == null) return;

        if (state == State.REFILL) { tickRefill(mc); return; }
        if (state == State.TRIP) { tickTrip(mc); return; }

        if (!(mc.player.currentScreenHandler instanceof BrewingStandScreenHandler h)) {
            tryAutoOpen(mc);
            return;
        }

        if (needReconcile) {
            if (reconcile(h)) { needReconcile = false; busyTicks = 0; }
            else {
                if (++busyTicks > 90) { fail("Brewing Stand sibuk menyeduh resep lain terlalu lama."); }
                wait = 10;
                return;
            }
        }
        if (wait > 0) { wait--; return; }

        switch (state) {
            case BOTTLES -> tickBottles(mc, h);
            case INGREDIENT -> tickIngredient(mc, h);
            case WAIT -> tickWait(h);
            case TAKE -> tickTake(mc, h);
            case UNLOAD -> tickUnload(mc, h);
            default -> { }
        }
    }

    private static void tickBottles(MinecraftClient mc, BrewingStandScreenHandler h) {
        int cnt = countBottles(h);
        if (cnt > 0) {
            waitingBottles = false;
            // Mode hopper: tunggu jumlah botol stabil dulu (hopper mengisi satu per satu)
            if (!AutoBrewConfig.useInventoryBottles && cnt < 3) {
                if (cnt != lastBottleCount) { lastBottleCount = cnt; stableTicks = 0; return; }
                if (++stableTicks < HOPPER_STABLE) return;
            }
            lastBottleCount = -1; stableTicks = 0;
            needReconcile = true;                       // kenali isi stand lalu tentukan tahap
            return;
        }
        lastBottleCount = -1; stableTicks = 0;

        // Inventory hampir penuh: taruh potion jadi ke chest Output
        if (AutoBrewConfig.depositEnabled && freeSlots(mc) <= DEPOSIT_FREE && countProducts(mc) > 0) {
            startTrip(TripKind.DEPOSIT, "Inventory hampir penuh: taruh potion jadi ke chest");
            return;
        }
        // Bahan hampir habis (untuk LOOKAHEAD siklus ke depan) atau kurang: ambil dari chest Supply
        String miss = Materials.missingFrom(job, 0);
        if (AutoBrewConfig.supplyEnabled && !supplyBlocked && (miss != null || isLow())) {
            startTrip(TripKind.SUPPLY, "Bahan hampir habis: ambil dari chest");
            return;
        }
        // Stand kosong: pastikan bahan satu siklus cukup SEBELUM mengisi botol (hindari setengah jadi)
        if (miss != null) { fail(miss + " (isi ulang lalu tekan LANJUTKAN)"); return; }

        if (!AutoBrewConfig.useInventoryBottles) {      // mode hopper/manual: mod tidak mengisi botol
            waitingBottles = true;
            statusDirty = true;
            return;
        }
        waitingBottles = false;

        int src = findSourceBottle(h);
        if (src < 0) {
            if (AutoBrewConfig.supplyEnabled && !supplyBlocked) {
                startTrip(TripKind.SUPPLY, "Botol awal habis: cari di chest");
                return;
            }
            boolean waterBase = job.startState().potion().equals("water")
                    && job.startState().form() == PotionRecipe.Form.NORMAL;
            if (AutoBrewConfig.autoRefill && waterBase && Materials.count(Items.GLASS_BOTTLE) > 0) {
                startRefill();
                return;
            }
            completeJob("botol awal habis");
            return;
        }
        for (int i = 0; i < 3; i++) {
            if (h.getSlot(i).hasStack()) continue;
            int s = findSourceBottle(h);
            if (s < 0) break;
            click(mc, h, s, 0, SlotActionType.QUICK_MOVE);
            if (!batch()) break;
        }
        wait = delay();       // tick berikutnya: stand terisi -> dikenali -> tahap awal
    }

    private static void tickIngredient(MinecraftClient mc, BrewingStandScreenHandler h) {
        if (stage >= stages.size()) { setState(State.TAKE); return; }
        Item need = stages.get(stage);
        int blazeAvail = countItem(h, Items.BLAZE_POWDER);
        boolean fuelSlotEmpty = !h.getSlot(4).hasStack();
        int ingrBlaze = need == Items.BLAZE_POWDER ? 1 : 0;

        // 1) Fuel kosong: WAJIB isi 1 Blaze Powder. Sisihkan juga 1 untuk bahan bila tahap ini butuh Blaze Powder.
        if (h.getFuel() <= 0 && fuelSlotEmpty) {
            int required = 1 + ingrBlaze;
            if (blazeAvail < required) {
                if (AutoBrewConfig.supplyEnabled && !supplyBlocked) {
                    startTrip(TripKind.SUPPLY, "Blaze Powder kurang: ambil dari chest");
                    return;
                }
                fail("Blaze Powder kurang: butuh " + required
                        + (ingrBlaze > 0 ? " (1 fuel + 1 bahan)" : " (fuel)") + ", ada " + blazeAvail);
                return;
            }
            placeOne(mc, h, findItem(h, Items.BLAZE_POWDER), 4);   // hanya 1, bukan satu stack
            wait = delay();
            return;
        }
        // 2) Fuel hampir habis sebelum siklus selesai: isi cadangan, HANYA jika masih ada blaze sisa
        //    setelah kebutuhan bahan Blaze Powder di sisa tahap.
        int remaining = stages.size() - stage;
        if (fuelSlotEmpty && h.getFuel() < remaining) {
            int remBlaze = 0;
            for (int i = stage; i < stages.size(); i++) if (stages.get(i) == Items.BLAZE_POWDER) remBlaze++;
            if (blazeAvail - remBlaze >= 1) {
                placeOne(mc, h, findItem(h, Items.BLAZE_POWDER), 4);
                wait = delay();
                return;
            }
        }
        // 3) slot bahan harus kosong (mis. botol sisa Dragon's Breath)
        if (h.getSlot(3).hasStack()) {
            click(mc, h, 3, 0, SlotActionType.QUICK_MOVE);
            wait = delay();
            return;
        }
        // 4) taruh 1 bahan
        int src = findItem(h, need);
        if (src < 0) {
            if (AutoBrewConfig.supplyEnabled && !supplyBlocked) {
                startTrip(TripKind.SUPPLY, "Bahan habis: " + need.getName().getString() + ", ambil dari chest");
                return;
            }
            fail("Bahan habis: " + need.getName().getString());
            return;
        }
        placeOne(mc, h, src, 3);
        started = false;
        timeout = 0;
        setState(State.WAIT);
        wait = delay();
    }

    private static void tickWait(BrewingStandScreenHandler h) {
        timeout++;
        if (h.getBrewTime() > 0) started = true;
        if (started && h.getBrewTime() == 0) {
            stage++;
            takeTries = 0;
            if (stage >= stages.size()) {
                cyclePotions = countBottles(h);
                setState(State.TAKE);
            } else {
                setState(State.INGREDIENT);
            }
            wait = delay() * 2;
        } else if (!started && timeout > START_TIMEOUT) {
            fail("Brewing Stand tidak menerima bahan: " + stages.get(stage).getName().getString());
        } else if (timeout > BREW_TIMEOUT) {
            fail("Brewing tidak selesai (cek fuel).");
        }
    }

    private static void tickTake(MinecraftClient mc, BrewingStandScreenHandler h) {
        boolean any = false;
        for (int i = 0; i < 3; i++) {
            if (!h.getSlot(i).hasStack()) continue;
            any = true;
            click(mc, h, i, 0, SlotActionType.QUICK_MOVE);
            if (!batch()) break;
        }
        if (any) {
            if (++takeTries > 10) {
                if (AutoBrewConfig.depositEnabled && depositAttempts < 2) {
                    depositAttempts++;
                    startTrip(TripKind.DEPOSIT, "Inventory penuh saat mengambil hasil: taruh potion ke chest");
                    return;
                }
                fail("Inventory penuh.");
                return;
            }
            wait = delay();
            return;
        }
        cycles++;
        depositAttempts = 0;
        cyclesDone[jobIdx]++;
        sessionPotions += cyclePotions;
        AutoBrewConfig.totalPotions += cyclePotions;
        AutoBrewConfig.addProduced(job.name(), cyclePotions);
        AutoBrewConfig.markDirty();
        statusDirty = true;

        if (job.cycles > 0 && cyclesDone[jobIdx] >= job.cycles) {
            completeJob(cyclesDone[jobIdx] + " siklus tercapai");
            return;
        }
        msg("Siklus selesai: " + cyclesDone[jobIdx]);
        stage = 0;
        setState(State.BOTTLES);
        wait = delay();
    }

    private static void tickUnload(MinecraftClient mc, BrewingStandScreenHandler h) {
        boolean any = false;
        for (int i = 0; i < 3; i++) {
            if (!h.getSlot(i).hasStack()) continue;
            any = true;
            click(mc, h, i, 0, SlotActionType.QUICK_MOVE);
            if (!batch()) break;
        }
        if (any) {
            if (++takeTries > 10) { fail("Inventory penuh saat mengeluarkan potion lain."); return; }
            wait = delay();
            return;
        }
        stage = 0;
        setState(State.BOTTLES);       // sudah bersih -> lanjut potion berikutnya
        wait = delay();
    }

    /** Tandai job selesai, pindah ke job berikutnya di antrean, atau selesai semua. */
    private static void completeJob(String note) {
        if (AutoBrewConfig.depositEnabled && AutoBrewConfig.outputPos != null && afterTripNote == null
                && countProducts(MinecraftClient.getInstance()) > 0) {
            afterTripNote = note;       // setelah taruh potion, lanjutkan penyelesaian job
            startTrip(TripKind.DEPOSIT, "Antrean selesai: taruh sisa potion ke chest");
            return;
        }
        completeJobCore(note);
    }

    private static void completeJobCore(String note) {
        BrewLog.log(BrewLog.Level.INFO, job.name(),
                (plan.size() > 1 ? "Antrean #" + (jobIdx + 1) + " selesai: " : "Selesai: ") + note);
        done[jobIdx] = true;
        int nx = firstNotDone();
        if (nx < 0) {
            finish((plan.size() > 1 ? "Semua antrean selesai" : "Selesai (" + note + ")")
                    + ". Total sesi: " + sessionPotions + " potion");
            return;
        }
        setJob(nx);
        stage = 0;
        setState(State.BOTTLES);
        needReconcile = true;
        wait = delay();
    }

    // ------------------------------------------------------------------ pengenalan isi Brewing Stand
    /**
     * Cocokkan potion di stand dengan antrean. @return true bila sudah ditentukan, false = stand sedang sibuk.
     * - cocok dengan tahap job sekarang  -> lanjut dari tahap itu
     * - cocok dengan job lain di antrean -> selesaikan dulu job itu, lalu kembali ke antrean
     * - tidak cocok                       -> dikeluarkan ke inventory
     */
    private static boolean reconcile(BrewingStandScreenHandler h) {
        List<PotionRecipe.PState> found = new ArrayList<>();
        boolean unknown = false;
        for (int i = 0; i < 3; i++) {
            var s = h.getSlot(i).getStack();
            if (s.isEmpty()) continue;
            PotionRecipe.PState c = Materials.classify(s);
            if (c == null) unknown = true; else found.add(c);
        }
        if (!unknown && found.isEmpty()) {
            stage = 0;
            setState(State.BOTTLES);
            return true;
        }
        boolean uniform = !unknown;
        for (PotionRecipe.PState f : found) if (!f.equals(found.get(0))) uniform = false;
        if (!uniform) {
            startUnload("Isi Brewing Stand campur / tidak dikenal");
            return true;
        }
        PotionRecipe.PState now = found.get(0);
        int[] hit = locate(now);
        if (hit == null) {
            startUnload("Potion di Brewing Stand tidak sesuai antrean (" + now.label() + ")");
            return true;
        }
        if (hit[0] != jobIdx) {
            BrewLog.log(BrewLog.Level.INFO, plan.get(hit[0]).name(),
                    "Potion di stand (" + now.label() + ") cocok dengan antrean #" + (hit[0] + 1)
                            + ": diselesaikan dulu, lalu lanjut antrean");
            setJob(hit[0]);
        }
        int k = hit[1], n = stages.size();
        if (h.getBrewTime() > 0) {
            if (k < n && h.getSlot(3).getStack().isOf(stages.get(k))) {
                stage = k; started = true; timeout = 0;
                setState(State.WAIT);
                return true;
            }
            return false;       // menyeduh sesuatu lain: tunggu selesai dulu
        }
        takeTries = 0;
        if (k >= n) {
            stage = n;
            cyclePotions = found.size();
            setState(State.TAKE);
        } else {
            stage = k;
            setState(State.INGREDIENT);
        }
        return true;
    }

    /** @return {indeksJob, indeksTahap} atau null. Job sekarang diprioritaskan, lalu job lain yang belum selesai. */
    private static int[] locate(PotionRecipe.PState s) {
        int k = plan.get(jobIdx).states().indexOf(s);
        if (k >= 0) return new int[]{jobIdx, k};
        for (int j = 0; j < plan.size(); j++) {
            if (j == jobIdx || done[j]) continue;
            k = plan.get(j).states().indexOf(s);
            if (k >= 0) return new int[]{j, k};
        }
        return null;
    }

    private static void startUnload(String reason) {
        BrewLog.log(BrewLog.Level.WARN, potionName(), reason + " - dikeluarkan ke inventory");
        takeTries = 0;
        setState(State.UNLOAD);
    }

    // ------------------------------------------------------------------ chest (taruh potion / ambil bahan)
    private static int freeSlots(MinecraftClient mc) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (mc.player.getInventory().getStack(i).isEmpty()) n++;
        return n;
    }

    private static boolean isProduct(ItemStack s) {
        PotionRecipe.PState c = Materials.classify(s);
        return c != null && finals.contains(c);
    }

    private static int countProducts(MinecraftClient mc) {
        if (mc.player == null) return 0;
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (isProduct(s)) n += s.getCount();
        }
        return n;
    }

    /** Bahan untuk LOOKAHEAD siklus ke depan sudah menipis? */
    private static boolean isLow() {
        for (var e : Materials.totalNeeds(job, LOOKAHEAD).entrySet()) {
            if (Materials.count(e.getKey()) < e.getValue()) return true;
        }
        return AutoBrewConfig.useInventoryBottles && !AutoBrewConfig.autoRefill
                && Materials.countSource(job) < 3 * LOOKAHEAD;
    }

    private static List<SupplyItem> buildSupply() {
        List<SupplyItem> l = new ArrayList<>();
        int target = AutoBrewConfig.supplyTarget;
        for (Item it : Materials.totalNeeds(job, 1).keySet()) {
            l.add(new SupplyItem(it.getName().getString(), it, false, target));
        }
        boolean hasBlaze = false;
        for (SupplyItem s : l) if (s.item() == Items.BLAZE_POWDER) hasBlaze = true;
        if (!hasBlaze) l.add(new SupplyItem("Blaze Powder", Items.BLAZE_POWDER, false, target));
        boolean waterBase = job.startState().potion().equals("water")
                && job.startState().form() == PotionRecipe.Form.NORMAL;
        if (AutoBrewConfig.useInventoryBottles) {
            l.add(new SupplyItem(waterBase ? "Water Bottle" : "Botol awal", null, true, Math.min(target, 27)));
        }
        if (AutoBrewConfig.autoRefill && waterBase) {
            l.add(new SupplyItem("Glass Bottle", Items.GLASS_BOTTLE, false, Math.min(target, 27)));
        }
        return l;
    }

    private static void startTrip(TripKind kind, String reason) {
        if (AutoBrewConfig.standPos == null) {
            fail("Fitur chest butuh posisi Brewing Stand (menu > tab Botol > Stand)");
            return;
        }
        tripChests.clear();
        if (kind == TripKind.DEPOSIT) {
            if (AutoBrewConfig.outputPos == null) { fail("Posisi chest Output belum diset (menu > tab Chest)"); return; }
            tripChests.add(AutoBrewConfig.outputPos.toBlockPos());
        } else {
            if (AutoBrewConfig.supplyChests.isEmpty()) { fail("Chest Supply belum diset (menu > tab Chest)"); return; }
            for (AutoBrewConfig.Pos p : AutoBrewConfig.supplyChests) tripChests.add(p.toBlockPos());
            supply = buildSupply();
            supplyStart = new int[supply.size()];
            for (int i = 0; i < supply.size(); i++) supplyStart[i] = supply.get(i).count();
        }
        tripKind = kind;
        tripPhase = 0; tripTicks = 0; tripChest = 0; tripScan = 0; tripOpenTries = 0; tripGain = 0;
        tripFull = false;
        BrewLog.log(BrewLog.Level.INFO, potionName(), reason);
        setState(State.TRIP);
        wait = 0;
    }

    private static String xyz(BlockPos p) { return p.getX() + "," + p.getY() + "," + p.getZ(); }

    private static void finishTrip() {
        if (tripKind == TripKind.SUPPLY) {
            int gain = 0;
            for (int i = 0; i < supply.size(); i++) gain += Math.max(0, supply.get(i).count() - supplyStart[i]);
            if (gain <= 0) {
                supplyBlocked = true;       // jangan bolak-balik ke chest yang kosong
                BrewLog.log(BrewLog.Level.WARN, potionName(), "Supply: tidak ada bahan yang dibutuhkan di chest");
            } else {
                supplyBlocked = false;
                BrewLog.log(BrewLog.Level.INFO, potionName(), "Supply selesai: +" + gain + " item dari chest");
            }
        } else {
            BrewLog.log(BrewLog.Level.INFO, potionName(), "Deposit selesai: " + tripGain + " potion dipindahkan ke chest");
        }
        tripPhase = 5; tripTicks = 0; tripOpenTries = 0;
    }

    private static void tickTrip(MinecraftClient mc) {
        PlayerEntity p = mc.player;
        if (wait > 0) { wait--; return; }
        switch (tripPhase) {
            case 0 -> {         // tutup layar yang sedang terbuka (Brewing Stand)
                if (mc.currentScreen != null) {
                    expectClose = true;
                    p.closeHandledScreen();
                    wait = 5;
                    return;
                }
                tripPhase = 1;
            }
            case 1 -> {         // buka chest berikutnya
                if (tripChest >= tripChests.size()) { finishTrip(); return; }
                BlockPos pos = tripChests.get(tripChest);
                if (!Materials.isContainer(mc.world.getBlockState(pos))) {
                    BrewLog.log(BrewLog.Level.WARN, potionName(), "Chest di " + xyz(pos) + " sudah tidak ada, dilewati");
                    tripChest++;
                    return;
                }
                if (p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) > 25.0) {
                    fail("Chest di " + xyz(pos) + " terlalu jauh (maks ~4.5 blok dari posisimu)");
                    return;
                }
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
                tripPhase = 2; tripTicks = 0; wait = 8;
            }
            case 2 -> {         // tunggu chest terbuka
                if (p.currentScreenHandler instanceof GenericContainerScreenHandler) {
                    tripScan = 0; tripPhase = 3; wait = 2;
                    return;
                }
                if (++tripTicks > 30) {
                    if (++tripOpenTries > 2) {
                        fail("Gagal membuka chest di " + xyz(tripChests.get(tripChest)) + " (terhalang blok / sneak?)");
                        return;
                    }
                    tripPhase = 1;
                }
            }
            case 3 -> transferItems(mc, p);
            case 4 -> {         // tutup chest -> chest berikutnya
                if (mc.currentScreen != null) {
                    expectClose = true;
                    p.closeHandledScreen();
                    wait = 4;
                    return;
                }
                tripChest++; tripScan = 0; tripOpenTries = 0; tripPhase = 1;
            }
            case 5 -> {         // kembali membuka Brewing Stand
                BlockPos sp = AutoBrewConfig.standPos.toBlockPos();
                if (p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(sp)) > 25.0) {
                    fail("Brewing Stand terlalu jauh dari tempatmu berdiri");
                    return;
                }
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(sp), Direction.UP, sp, false));
                tripPhase = 6; tripTicks = 0; wait = 8;
            }
            case 6 -> {
                if (p.currentScreenHandler instanceof BrewingStandScreenHandler) {
                    stage = 0;
                    needReconcile = true;       // kenali isi stand lagi
                    setState(State.BOTTLES);
                    wait = delay();
                    String n = afterTripNote;
                    afterTripNote = null;
                    if (n != null) completeJobCore(n);
                    return;
                }
                if (++tripTicks > 40) {
                    if (++tripOpenTries > 3) { fail("Gagal membuka Brewing Stand setelah dari chest"); return; }
                    tripPhase = 5;
                }
            }
            default -> tripPhase = 0;
        }
    }

    /** Pindahkan barang saat chest terbuka. SUPPLY: chest -> inventory. DEPOSIT: inventory -> chest. */
    private static void transferItems(MinecraftClient mc, PlayerEntity p) {
        if (!(p.currentScreenHandler instanceof GenericContainerScreenHandler g)) { tripPhase = 1; return; }
        int total = g.slots.size();
        int csize = total - 36;                 // sisanya = inventory pemain (27 + 9 hotbar)
        int budget = batch() ? 8 : 1;
        boolean chestDone;

        if (tripKind == TripKind.SUPPLY) {
            while (budget > 0 && tripScan < csize) {
                int idx = tripScan++;
                ItemStack s = g.getSlot(idx).getStack();
                if (s.isEmpty()) continue;
                SupplyItem hit = null;
                for (SupplyItem it : supply) {
                    if (it.count() < it.target() && it.matches(s)) { hit = it; break; }
                }
                if (hit == null) continue;
                int before = hit.count();
                click(mc, g, idx, 0, SlotActionType.QUICK_MOVE);
                budget--;
                if (hit.count() <= before) {            // tidak bertambah: inventory penuh
                    tripFull = true;
                    BrewLog.log(BrewLog.Level.WARN, potionName(), "Inventory penuh saat mengambil bahan dari chest");
                    tripScan = csize;
                }
            }
            boolean satisfied = true;
            for (SupplyItem it : supply) if (it.count() < it.target()) satisfied = false;
            if (satisfied || tripFull) { tripScan = csize; tripChest = tripChests.size() - 1; }   // tak perlu chest lain
            chestDone = tripScan >= csize;
        } else {
            while (budget > 0 && csize + tripScan < total) {
                int idx = csize + tripScan++;
                ItemStack s = g.getSlot(idx).getStack();
                if (!isProduct(s)) continue;
                int cnt = s.getCount();
                click(mc, g, idx, 0, SlotActionType.QUICK_MOVE);
                budget--;
                if (g.getSlot(idx).hasStack()) { fail("Chest Output penuh / tidak bisa menampung potion"); return; }
                tripGain += cnt;
            }
            chestDone = csize + tripScan >= total;
        }
        if (chestDone) { tripPhase = 4; wait = 3; } else { wait = delay(); }
    }

    // ------------------------------------------------------------------ isi ulang air
    private static void startRefill() {
        if (AutoBrewConfig.standPos == null) { fail("Isi ulang air butuh posisi Brewing Stand (menu > tab Botol > Stand)"); return; }
        if (AutoBrewConfig.waterPos == null) { fail("Isi ulang air butuh posisi sumber air (menu > tab Botol > Air)"); return; }
        refillPhase = 0; refillTicks = 0; refillNoProgress = 0; refillOpenTries = 0;
        BrewLog.log(BrewLog.Level.INFO, potionName(), "Water Bottle habis: isi ulang otomatis dimulai");
        setState(State.REFILL);
        wait = 0;
    }

    private static void tickRefill(MinecraftClient mc) {
        PlayerEntity p = mc.player;
        if (wait > 0) { wait--; return; }
        switch (refillPhase) {
            case 0 -> {         // tutup Brewing Stand
                if (mc.currentScreen != null) {
                    expectClose = true;
                    p.closeHandledScreen();
                    wait = 5;
                    return;
                }
                refillPhase = 1;
            }
            case 1 -> {         // siapkan Glass Bottle di hotbar & pilih
                var inv = p.getInventory();
                int hb = -1;
                for (int i = 0; i < 9; i++) if (inv.getStack(i).isOf(Items.GLASS_BOTTLE)) { hb = i; break; }
                if (hb < 0) {
                    int mi = -1;
                    for (int i = 9; i < 36; i++) if (inv.getStack(i).isOf(Items.GLASS_BOTTLE)) { mi = i; break; }
                    if (mi < 0) { fail("Glass Bottle tidak ditemukan untuk isi ulang air"); return; }
                    // tukar dengan slot hotbar yang sedang dipilih (id slot 9..35 sama dengan indeks inventory)
                    mc.interactionManager.clickSlot(0, mi, inv.getSelectedSlot(), SlotActionType.SWAP, p);
                    wait = 4;
                    return;
                }
                inv.setSelectedSlot(hb);
                refillLastWater = Materials.countWater();
                refillNoProgress = 0;
                refillTicks = 0;
                refillPhase = 2;
                wait = 3;
            }
            case 2 -> {         // arahkan ke air & isi botol
                BlockPos wp = AutoBrewConfig.waterPos.toBlockPos();
                aim(p, Vec3d.ofCenter(wp));
                if (++refillTicks % 4 != 0) return;
                int water = Materials.countWater();
                if (water > refillLastWater) { refillLastWater = water; refillNoProgress = 0; }
                else if (++refillNoProgress > 10) {
                    fail("Isi ulang air gagal: cek posisi Air (harus sumber air / cauldron berisi air, dalam jangkauan)");
                    return;
                }
                boolean bottlesLeft = Materials.count(Items.GLASS_BOTTLE) > 0;
                if (water >= AutoBrewConfig.refillTarget || !bottlesLeft) {
                    refillPhase = 3; refillTicks = 0; wait = 2;
                    return;
                }
                if (!p.getMainHandStack().isOf(Items.GLASS_BOTTLE)) { refillPhase = 1; return; }
                if (mc.world.getBlockState(wp).isOf(Blocks.WATER_CAULDRON)) {
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                            new BlockHitResult(Vec3d.ofCenter(wp), Direction.UP, wp, false));
                } else {
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                }
                p.swingHand(Hand.MAIN_HAND);
            }
            case 3 -> {         // kembali ke Brewing Stand & buka
                BlockPos sp = AutoBrewConfig.standPos.toBlockPos();
                aim(p, Vec3d.ofCenter(sp));
                if (++refillTicks < 6) return;
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(sp), Direction.UP, sp, false));
                refillPhase = 4; refillTicks = 0; wait = 10;
            }
            case 4 -> {         // tunggu terbuka
                if (p.currentScreenHandler instanceof BrewingStandScreenHandler) {
                    BrewLog.log(BrewLog.Level.INFO, potionName(), "Isi ulang air selesai: " + Materials.countWater() + " Water Bottle");
                    stage = 0;
                    needReconcile = true;
                    setState(State.BOTTLES);
                    wait = delay();
                    return;
                }
                if (++refillTicks > 40) {
                    if (++refillOpenTries > 3) { fail("Gagal membuka Brewing Stand setelah isi ulang air"); return; }
                    refillPhase = 3; refillTicks = 0;
                }
            }
            default -> refillPhase = 0;
        }
    }

    private static void aim(PlayerEntity p, Vec3d t) {
        Vec3d eye = p.getEyePos();
        double dx = t.x - eye.x, dy = t.y - eye.y, dz = t.z - eye.z;
        double hd = Math.sqrt(dx * dx + dz * dz);
        p.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        p.setPitch((float) (-Math.toDegrees(Math.atan2(dy, hd))));
    }

    // ------------------------------------------------------------------ helper
    /** Buka Brewing Stand: pakai posisi tersimpan (jika dekat), atau yang sedang dilihat pemain. */
    private static void tryAutoOpen(MinecraftClient mc) {
        if (!AutoBrewConfig.autoOpen || pausedByUser || mc.currentScreen != null || mc.world == null) return;
        if (openCooldown > 0) { openCooldown--; return; }
        if (mc.player.isSneaking()) return;
        BlockHitResult hit = null;
        AutoBrewConfig.Pos sp = AutoBrewConfig.standPos;
        if (sp != null) {
            BlockPos pos = sp.toBlockPos();
            if (mc.world.getBlockState(pos).isOf(Blocks.BREWING_STAND)
                    && mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= 25.0) {
                hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
            }
        }
        if (hit == null && mc.crosshairTarget instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK
                && mc.world.getBlockState(bhr.getBlockPos()).isOf(Blocks.BREWING_STAND)) {
            hit = bhr;
        }
        if (hit != null) {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            openCooldown = 20;
        }
    }

    private static void click(MinecraftClient mc, ScreenHandler h, int slot, int button, SlotActionType type) {
        mc.interactionManager.clickSlot(h.syncId, slot, button, type, mc.player);
    }

    /** Ambil stack, taruh TEPAT 1 item di slot tujuan, kembalikan sisanya. */
    private static void placeOne(MinecraftClient mc, BrewingStandScreenHandler h, int src, int dst) {
        click(mc, h, src, 0, SlotActionType.PICKUP);
        click(mc, h, dst, 1, SlotActionType.PICKUP);
        click(mc, h, src, 0, SlotActionType.PICKUP);
    }

    private static int findItem(BrewingStandScreenHandler h, Item item) {
        for (int i = PLAYER_START; i < h.slots.size(); i++) {
            if (h.getSlot(i).getStack().isOf(item)) return i;
        }
        return -1;
    }

    private static int countItem(BrewingStandScreenHandler h, Item item) {
        int n = 0;
        for (int i = PLAYER_START; i < h.slots.size(); i++) {
            var s = h.getSlot(i).getStack();
            if (s.isOf(item)) n += s.getCount();
        }
        return n;
    }

    private static int countBottles(BrewingStandScreenHandler h) {
        int n = 0;
        for (int i = 0; i < 3; i++) if (h.getSlot(i).hasStack()) n++;
        return n;
    }

    private static int findSourceBottle(BrewingStandScreenHandler h) {
        for (int i = PLAYER_START; i < h.slots.size(); i++) {
            if (Materials.isSource(h.getSlot(i).getStack(), job)) return i;
        }
        return -1;
    }

    private static void beep(boolean ok) {
        if (!AutoBrewConfig.sound) return;
        MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(
                ok ? SoundEvents.ENTITY_PLAYER_LEVELUP : SoundEvents.ENTITY_VILLAGER_NO, 1.0f, 1.0f));
    }

    private static void msg(String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("[AutoBrew] " + text), true);
    }

    /** Pesan di chat (tetap terlihat di riwayat chat, tidak hilang seperti action bar). */
    private static void chat(String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal("[AutoBrew] " + text), false);
    }
}
