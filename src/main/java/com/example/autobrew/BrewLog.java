package com.example.autobrew;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Log kejadian (mulai, selesai, gagal, lanjut) lengkap dengan jam. Disimpan ke config/autobrew-log.txt. */
final class BrewLog {
    private BrewLog() {}

    enum Level {
        INFO("INFO", 0xFFA0A0B8), OK("OK", 0xFF7CFF9E), WARN("PERINGATAN", 0xFFFFD166), FAIL("GAGAL", 0xFFFF7070);
        final String label; final int color;
        Level(String label, int color) { this.label = label; this.color = color; }
    }

    record Entry(String time, Level level, String potion, String message) {}

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX = 300;
    private static final List<Entry> ENTRIES = new ArrayList<>();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("autobrew-log.txt");
    }

    static String now() { return LocalDateTime.now().format(FMT); }

    /** Jam saja (HH:mm:ss) dari waktu sekarang. */
    static String clock() { return now().substring(11); }

    static List<Entry> entries() { return Collections.unmodifiableList(ENTRIES); }

    static int failCount() {
        int n = 0;
        for (Entry e : ENTRIES) if (e.level() == Level.FAIL) n++;
        return n;
    }

    static void log(Level level, String potion, String message) {
        String p = potion == null ? "" : potion;
        Entry e = new Entry(now(), level, p, message.replace('\t', ' ').replace('\n', ' '));
        ENTRIES.add(e);
        while (ENTRIES.size() > MAX) ENTRIES.remove(0);
        try {
            String line = e.time() + "\t" + e.level().name() + "\t" + e.potion() + "\t" + e.message() + "\n";
            Files.writeString(file(), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ex) {
            System.err.println("[AutoBrew] gagal menulis log: " + ex);
        }
    }

    static void load() {
        ENTRIES.clear();
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            List<String> lines = Files.readAllLines(f);
            for (String line : lines) {
                String[] p = line.split("\t", 4);
                if (p.length < 4) continue;
                Level lv;
                try { lv = Level.valueOf(p[1]); } catch (Exception e) { lv = Level.INFO; }
                ENTRIES.add(new Entry(p[0], lv, p[2], p[3]));
            }
            while (ENTRIES.size() > MAX) ENTRIES.remove(0);
            if (lines.size() > MAX * 2) rewrite();     // jaga ukuran file
        } catch (Exception e) {
            System.err.println("[AutoBrew] gagal membaca log: " + e);
        }
    }

    static void clear() {
        ENTRIES.clear();
        rewrite();
    }

    private static void rewrite() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Entry e : ENTRIES) {
                sb.append(e.time()).append('\t').append(e.level().name()).append('\t')
                  .append(e.potion()).append('\t').append(e.message()).append('\n');
            }
            Files.writeString(file(), sb.toString());
        } catch (Exception e) {
            System.err.println("[AutoBrew] gagal menulis ulang log: " + e);
        }
    }
}
