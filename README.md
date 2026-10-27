# RK Server — Android 4.4.2 (uji lokal)

Proyek ini membuat APK Android sederhana **RK Server** yang dirancang untuk perangkat lama seperti Android 4.4.2. Aplikasi menjalankan server HTTP lokal pada port `8080` dan menyediakan halaman web chat sederhana yang bisa dibuka dari browser HP lain yang berada di Wi-Fi yang sama.

## Yang sudah ada

- Tombol **Mulai Server** dan **Matikan Server**.
- Menampilkan alamat lokal `http://IP-TABLET:8080/`.
- Notifikasi permanen saat server berjalan.
- Halaman web chat ringan yang bisa dibuka dari browser HP.
- Beberapa HP pada Wi-Fi yang sama dapat bertukar pesan teks atau tautan.
- Riwayat maksimal 200 pesan disimpan di penyimpanan internal tablet.
- Tidak membutuhkan Play Store, AndroidX, atau pustaka pihak ketiga saat aplikasi berjalan.
- Target Android lama: `minSdk 19` (Android 4.4/API 19).

## Batasan penting versi 1

- Ini **prototipe lokal**, bukan WhatsApp dan bukan layanan internet publik.
- Belum ada akun/login, enkripsi ujung-ke-ujung, kirim foto, suara, panggilan, atau pemulihan akun.
- HTTP lokal belum terenkripsi. Siapa pun yang dapat mengakses Wi-Fi yang sama dan mengetahui alamat server dapat melihat dan mengirim pesan.
- Jangan gunakan untuk pesan pribadi/sensitif. Jangan melakukan port-forwarding router atau membuka port 8080 ke internet.
- Akses dari luar Wi-Fi rumah belum disediakan. Itu memerlukan desain server/relay dan keamanan tambahan.
- Tablet lama mungkin menghentikan proses karena kondisi sistem, panas, baterai, Wi-Fi, atau keterbatasan memori. Uji dahulu; jangan menganggapnya sudah cocok untuk produksi 24/7.

## Cara paling ringkas membuat APK lewat GitHub Actions

Ada dua cara. Cara A bila kamu nyaman mengunggah folder proyek; Cara B bila ingin mengunggah satu ZIP saja.

### Cara A — unggah folder proyek

1. Buat repositori GitHub baru, misalnya `rk-server-android`.
2. Unggah seluruh isi folder proyek ini ke akar repositori. Penting: `.github/workflows/build-apk.yml` harus berada di lokasi itu, bukan di folder tambahan yang salah.
3. Buka tab **Actions**, pilih **Build RK Server APK**, lalu **Run workflow**. Jika Actions dinonaktifkan, aktifkan terlebih dahulu.
4. Tunggu sampai run selesai dengan tanda centang hijau. Buka run tersebut dan cari **Artifacts**.
5. Unduh `RK-Server-APK.zip`, lalu ekstrak. File di dalamnya bernama `app-debug.apk`.

### Cara B — unggah ZIP sumber saja

1. Buat repositori GitHub baru.
2. Buat file workflow di GitHub dengan nama persis `.github/workflows/build-apk.yml`, lalu tempel isi workflow `build-apk.yml` yang disertakan di proyek ini.
3. Unggah file ZIP bernama **`RK-Server-Source.zip`** ke akar repositori. Gunakan ZIP sumber yang disertakan bersamaan dengan panduan ini.
4. Buka **Actions** → **Build RK Server APK** → **Run workflow**.
5. Unduh artifact `RK-Server-APK.zip` setelah proses berhasil. Ekstrak untuk mendapatkan `app-debug.apk`.

GitHub Actions mengunduh Gradle dan Android SDK lalu membuat APK debug. Proses ini memerlukan koneksi internet di GitHub Actions, tetapi tablet tidak memerlukan Play Store.

## Cara menguji setelah terpasang

1. Sambungkan tablet dan HP lain ke Wi-Fi rumah yang sama.
2. Buka **RK Server** di tablet lalu tekan **MULAI SERVER**.
3. Tunggu status **SERVER AKTIF** dan alamat seperti `http://192.168.1.20:8080/` muncul. Angka IP tiap jaringan bisa berbeda.
4. Di HP lain, buka Chrome dan masukkan alamat yang muncul secara persis.
5. Beri nama dan kirim pesan. Buka alamat yang sama dari HP kedua, gunakan nama lain, lalu balas.
6. Jika gagal, pastikan kedua perangkat berada pada Wi-Fi yang sama (bukan HP memakai data seluler), coba matikan VPN di perangkat uji, dan periksa apakah router mengaktifkan *AP/client isolation*.

## Catatan baterai

Ketika server hidup, aplikasi mencoba menjaga CPU dan Wi-Fi aktif. Ini dapat menguras baterai dan membuat tablet hangat. Untuk uji lama, gunakan pengisi daya yang baik, tempat berventilasi, dan matikan server saat tidak dipakai.


## Build fix (2026-10-28)
The server service includes a `sendJson(..., JSONObject)` overload that serializes JSON objects before writing the HTTP response. This fixes the compiler error `JSONObject cannot be converted to String` reported for the error responses.
