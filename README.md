# Auto Potion Brewer (Fabric, Minecraft 1.21.11)

Mod client-side yang mengotomatiskan Brewing Stand.

## Build menjadi .jar
1. **GitHub Actions (tanpa install apa pun)**: upload SEMUA isi folder ini ke repo GitHub.
   Jika folder `.github` tidak ikut, buat file `.github/workflows/build.yml` lewat *Add file > Create new file*.
   Tab *Actions* -> workflow `build` -> unduh artifact `autobrew-jar` (pakai `autobrew-1.0.0.jar`).
2. **Lokal** (JDK 21 + Gradle 9.2+): `gradle build` -> `build/libs/autobrew-1.0.0.jar`.

## Fitur
- **Tab Potion**: 22 potion, tier I / I+ / II, biasa / splash / lingering, mode Buat Baru atau Upgrade.
- **Tab Botol**:
  - *Botol Inventory/Hopper*: Hopper = mod tidak memasukkan botol, menunggu botol dari hopper/manual.
  - *Mulai*: dari Water Bottle atau Awkward Potion (lewati Nether Wart).
  - *Isi air*: otomatis isi Water Bottle dari Glass Bottle (butuh posisi Stand & Air).
  - Kecepatan, HUD, bunyi, auto-buka Brewing Stand.
- **Tab Antrean**: beberapa potion berurutan. Klik item antrean untuk menghapus.
- **Pengenalan isi Brewing Stand**: potion di stand dikenali. Cocok dengan tahap -> lanjut dari tahap itu.
  Cocok dengan antrean lain -> diselesaikan dulu. Tidak cocok -> dikeluarkan ke inventory.
- Jika botol sudah ada di Brewing Stand tapi tidak ada di inventory, mod langsung jalan sesuai pengaturan GUI.
- **Ingatan progres**: berhenti karena bahan habis / keluar dunia -> tombol LANJUTKAN melanjutkan dari titik itu.
- **Log** (tombol Log): semua kejadian + jam, termasuk GAGAL; riwayat produksi. File: `config/autobrew-log.txt`.
- **Tab Chest**:
  - *Simpan*: potion jadi ditaruh ke chest Output saat inventory hampir penuh dan di akhir antrean.
  - *Isi bahan*: bahan yang menipis (bahan resep, Blaze Powder, botol awal, Glass Bottle) diambil dari chest Supply (maks 4 chest).
  - Chest yang didukung: chest, trapped chest, barrel. Chest harus bisa dibuka (tidak tertutup blok di atasnya).
  - Brewing Stand dan semua chest harus dalam jangkauan ~4.5 blok dari tempatmu berdiri. Posisi Stand wajib diset.
  - Cara set posisi: arahkan kursor ke blok, tekan G, buka tab, klik tombolnya.
- **Fuel pintar**: Blaze Powder dimasukkan 1 per 1; untuk Strength, Blaze Powder bahan dan fuel dihitung bersama.

## Cara pakai isi ulang air
1. Berdiri dekat Brewing Stand dan sumber air (keduanya dalam jangkauan ~4.5 blok dari satu titik).
2. Arahkan kursor ke Brewing Stand, tekan **G**, tab *Botol*, klik **Stand**.
3. Arahkan kursor ke sumber air (atau cauldron berisi air), tekan **G**, klik **Air**.
4. Aktifkan *Isi air*, siapkan Glass Bottle di inventory, lalu START.
Catatan: mod memutar pandangan pemain ke air dan ke Brewing Stand. Server dengan anti-cheat ketat bisa menganggapnya mencurigakan.

Tombol: **G** menu, **H** start / stop / lanjut (di luar menu). Config: `config/autobrew.json`.
