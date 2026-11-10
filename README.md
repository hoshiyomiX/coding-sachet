# Coding Sachet

Asisten **AI coding agent di Android** — lingkungan Ubuntu 24.04 berjalan tanpa root lewat **proot**, dengan **agent server Node.js** buatan sendiri yang terhubung ke **dua penyedia AI pilihan** (keduanya OpenAI-compatible):

- **APInex** — `https://api.apinex.bond/v1` ([apinex.bond](https://apinex.bond))
- **NVIDIA NIM** — `https://integrate.api.nvidia.com/v1` ([build.nvidia.com](https://build.nvidia.com))

Lingkungan (rootfs Ubuntu + Node.js) **dibundel di dalam APK** dengan kompresi ultra (xz) — first-run **tanpa unduhan apa pun**.

## Fitur

- **Dua penyedia AI**: APInex & NVIDIA NIM — key dan model disimpan terpisah per penyedia, ganti kapan saja dari Pengaturan
- **Environment in-APK**: Ubuntu 24.04.5 + Node.js dikompres `xz -9e` — dibongkar sekali ke penyimpanan aplikasi, tanpa unduhan berulang
- **Split ABI**: satu APK per arsitektur (arm64-v8a, armeabi-v7a, x86_64) — pasang yang cocok dengan perangkatmu
- **Chat + agent coding**: streaming SSE, thinking/reasoning, *tool calling* multi-putaran — ganti model/penyedia di tengah sesi aman (riwayat lintas model di-*healing* otomatis: id tool-call disintesis, putaran terhenti dilipat rapi)
- **Ramah VPN**: daftar DNS sistem Android (ikut jaringan/VPN aktif) didorong ke guest lewat `POST /api/dns` + retry koneksi singkat — perbaiki "Koneksi ke gateway gagal: fetch failed" saat VPN menyala
- **Chip sisa token APInex**: indikator sisa kuota di kanan atas kolom chat (`/v1/subscription` + `/v1/balance`, fallback pasangan billing OpenAI-style), diperbarui tiap akhir giliran dan bisa diketuk untuk menyegarkan
- **Kartu tool yang bisa dibentangkan**: `bash`, `read_file`, `write_file`, `list_dir` — argumen + output lengkap
- **Mode root (HP rooted)**: aktifkan "Akses root perangkat" di Pengaturan — server berjalan via `su`, seluruh penyimpanan perangkat muncul sebagai folder `/device` dan bisa dijelajahi dari tab File (termasuk `/data/data`, `/system`)
- **File browser & editor** untuk workdir `/sdcard/coding-sachet` di penyimpanan bersama (fallback otomatis `/root/work` bila penyimpanan tidak tersedia) — plus folder baru, dan navigasi ke atas
- **Model otomatis** ditarik dari penyedia aktif (`/v1/models`), bisa dicari; filter *gratis saja* untuk APInex (`free/`)
- **API key milik sendiri**: diinput pengguna, disimpan di perangkat, divalidasi langsung ke penyedia (NIM: POST `/chat/completions` mini — `/v1/models`-nya publik, tidak bisa memvalidasi key)
- **Alat developer lengkap** (opsional, via apt): git, ripgrep, curl, wget, jq, tree, htop, zip/xz, python3 + pip + venv, build-essential (gcc/g++/make), sqlite3, pkg-config — dipasang sekali dari Pengaturan agar agent siap kerja
- **UI dwibahasa**: Indonesia (default) / English
- Nol dependensi npm di sisi server

## Instalasi

Ambil APK dari [Releases](https://github.com/hoshiyomiX/coding-sachet/releases) (tag **beta**, aset `coding-sachet-*.apk`), lalu pilih sesuai arsitektur perangkat:

| Aset | Perangkat | Node di dalam APK |
|---|---|---|
| `*-arm64-v8a.apk` | HP modern (praktis semua saat ini) | v26.13.0 |
| `*-armeabi-v7a.apk` | HP 32-bit lama | v22.20.0 |
| `*-x86_64.apk` | Emulator / Chromebook | v26.13.0 |

- Android 8.0+ (minSdk 26)
- `targetSdk 28` **disengaja**: Android 9+ melarang eksekusi biner dari direktori data aplikasi — syarat mutlak proot. Karena itu distribusikan sebagai file APK, bukan lewat Play Store.
- Aplikasi ini memakai applicationId dan tanda tangan baru — **uninstall aplikasi versi lamanya dulu** bila ada, lalu first-run: izinkan storage, pilih penyedia, masukkan API key (APInex: `sk-apx…` dari [apinex.bond](https://apinex.bond) · NIM: `nvapi-…` dari [build.nvidia.com](https://build.nvidia.com)), dan environment dibongkar dari APK (tanpa internet).

## Arsitektur

```
APK (Compose UI, Kotlin)
 ├─ jniLibs/<abi>/libproot*.so, libtalloc.so, libandroid-shmem.so
 ├─ assets/env/ubuntu-rootfs.tar.xz   ← CI: gunzip | xz -9e (±17 MB)
 ├─ assets/env/node-runtime.tar.xz    ← official nodejs.org
 ├─ assets/runtime-libs/libatomic-<arch>.so → dicopy ke guest /usr/lib/<triplet>
 ├─ assets/server/*.js   → dicopy ke /opt/sachet di dalam guest
 └─ RuntimeController: ekstrak env dari APK (dekoder xz pure-Java) → proot → watchdog
GUEST (proot Ubuntu): node /opt/sachet/server.js  ↔  127.0.0.1:8765
APP ↔ server lokal: /api/chat (SSE), /api/models, /api/fs*   (header X-Sachet-Key + X-Sachet-Provider)
SERVER ↔ GATEWAY: api.apinex.bond/v1  atau  integrate.api.nvidia.com/v1 (OpenAI-compatible, function calling)
```

- Satu build pass per ABI di CI: rootfs + node tarball yang sesuai arsitektur diisi ke nama aset yang sama, lalu Gradle memaketkan jniLibs ABI itu saja (`-Pabi`). `versionCode = run_number × 10 + indeks ABI` (arm64=1, armv7=2, x64=3).
- Server lokal hanya mendengarkan `127.0.0.1`; API key dikirim via header `X-Sachet-Key` dan tidak pernah meninggalkan perangkat selain ke penyedia yang dipilih.
- Per penyedia: base URL, hint batas laju 429, `max_tokens` (NIM memintanya eksplisit; APInex memakai default gateway). Default model NIM `nvidia/nemotron-3.5-bolt-e4b`; model EOL dijawab 410 oleh NIM dengan pesan jelas.
- Daftar model: utamanya via server lokal (cache 5 menit per penyedia+key); bila jalur jaringan guest lambat/gangguan, app **fallback langsung ke penyedia** — jalur yang sama dengan validasi key. Chat selalu via guest (butuh eksekusi tool).
- Semua path tool dipenjara di *allowed roots* (`/root`, `/tmp`, `/sdcard`).
- Keamanan unduhan env: sha256 rootfs & node **diverifikasi di CI** sebelum masuk APK; integritas berikutnya dijamin oleh tanda tangan APK itu sendiri.

## Format log (standar debug)

Semua baris log — sisi server (`assets/server/log.js`) maupun sisi app (`Logx.kt`) — memakai **satu format** agar mudah difilter dan dibaca saat menelusuri bug:

```
server (stdout → log aplikasi + logcat):   HH:MM:SS [LVL] [modul] pesan
app (logcat, tag CS):                      [modul] pesan        ← level & waktu dari logcat
```

- **LVL**: `DBG` (lifecycle verbos), `INF` (event kunci), `WRN` (masalah yang pulih sendiri), `ERR` (kegagalan yang terlihat user). `SACHET_QUIET=1` mematikan baris `DBG` server.
- **Modul server**: `cfg` config · `srv` HTTP/route · `agt` loop agent · `net` fetch/retry · `rlm` rate limiter · `tool` tool bash/file.
- **Modul app**: `app` boot · `api` AgentApi · `chat` ChatEngine · `rt` runtime/proot · `svc` foreground service · `store` ChatStore.
- Lihat semuanya dari PC: `adb logcat -s CS` (server ikut karena stdout-nya diteruskan ke logcat tag `CS`). Di dalam app: Pengaturan → *Log server* (30 baris terakhir).
- **Dump log di dalam app** (tanpa PC): Pengaturan → *Log debug (logcat)* → *Dump logcat*. Menjalankan `logcat -d -v threadtime,year --uid=<uid aplikasi>` — **hanya baris milik Coding Sachet** (proses app + server proot/node: log tag `CS`, `chatty`, `System.err`, crash native, dll), semua tag tanpa whitelist, lengkap dengan kolom PID+TID ala logcat sungguhan, plus header konteks (versi app, perangkat, Android, jaringan+status VPN, status server). Log aplikasi lain / sistem **difilter demi fokus**; logcat penuh semua proses tetap bisa diambil via `adb logcat` dari PC. Bila logcat OEM menolak `--uid`, otomatis mundur ke `--pid` lalu tanpa filter (header mencatat perintah yang benar-benar dipakai). Output dibatasi 10 MB terbaru (kepala terlama dibuang lebih dulu). Dump disimpan ke `/sdcard/coding-sachet/logs/logcat-<waktu>.txt` (muncul di layar Berkas) dan bisa dibagikan langsung.
- Aturan privasi: **tidak pernah** ada API key, isi pesan chat, atau isi file di log — hanya id sesi, nama model, hitungan, path, dan teks error.

## Status model NIM: dicek live di dalam app

Katalog `/v1/models` NIM memuat ±84 entri, tetapi mayoritas bukan endpoint chat yang terdeploy untuk key biasa (404 "Function … not found", embedding/rerank, atau timeout). Karena daftar model hidup/mati **berfluktuasi harian** (T-ML24: `minimax-m3.1-flash` mati, `google/diffusiongemma-26b-a4b-it` pulih, dalam 24 jam), titik hijau/merah di pemilih model TIDAK lagi membaca daftar yang dibakar di APK — aplikasi **memverifikasi langsung ke gateway** lewat `POST /api/verify-models` di server agent: tiap model diuji satu panggilan chat streaming mini (`max_tokens 16`, hidup begitu chunk data pertama tiba — jalur SSE yang sama dengan chat sungguhan, jadi model yang hanya menjawab via streaming pun terhitung hidup), di-pacing bucket 15 RPM yang sama dengan chat agar tidak pernah mengganggu percakapan.

Perilaku di pemilih model: saat daftar termuat dan hasil verifikasi sudah **basi (12 jam)** atau belum ada, sweep berjalan otomatis di latar belakang — titik terisi progresif (hijau = menjawab, merah = ditolak/timeout, abu-abu = belum diperiksa), ada tombol *Periksa ulang* dan *Batal*, dan waktu pemeriksaan terakhir tercatat. Hasil tersimpan di perangkat; sweep lanjut jalan walau sheet ditutup. Urutan pemeriksaan mengutamakan model yang sedang dipakai lalu model yang terakhir hijau. Di NIM model yang menjawab berlencana `free`; di APInex lencana `free` mengikuti prefiks `free/` + flag `allowFree` dari katalog publik (`apinex.bond/api/public/models`, juga dicek live — tanpa token chat). Uji ulang manual dari PC tetap bisa: `NIM_KEY=nvapi-… node tools/nim-models-probe.js` (key hanya via env, tidak pernah disimpan).

## Pengembangan

```bash
# uji server agent (mock gateway untuk kedua penyedia, tanpa perlu Android)
cd app/src/main/assets/server && node --test test/*.test.js

# build APK lokal (default arm64; environment assets dibangkitkan oleh CI, bukan ada di repo)
./gradlew assembleDebug
```

CI (`.github/workflows/release.yml`): test server → per ABI { unduh + verifikasi sha256 + `xz -9e` env → `assembleRelease` (+ debug untuk arm64) } → verifikasi `apksigner` → Release tag **beta** + artifact. APK release ditandatangani **keystore stabil yang dikommit di repo privat ini** (`keystore/release.jks`, PKCS12, CN=Coding Sachet) — runner CI membangkitkan kunci debug acak per-run sehingga tanpa keystore stabil, update in-place Android akan selalu gagal.
