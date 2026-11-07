# Poket Coding

Asisten **AI coding agent di Android** — lingkungan Ubuntu 24.04 (arm64) berjalan tanpa root lewat **proot**, dengan **agent server Node.js** buatan sendiri yang terhubung ke **dua penyedia AI pilihan** (keduanya OpenAI-compatible):

- **APInex** — `https://api.apinex.bond/v1`; model berawalan `free/` gratis (20 juta token per hari, 15 permintaan per menit; [dokumen model](https://apinex.bond/developers/models))
- **NVIDIA NIM** — `https://integrate.api.nvidia.com/v1`; prototyping gratis untuk seluruh katalog (±40 permintaan per menit; [build.nvidia.com](https://build.nvidia.com))

Lingkungan (rootfs Ubuntu + Node.js) **dibundel di dalam APK** dengan kompresi ultra (xz) — first-run **tanpa unduhan apa pun**.

Terinspirasi dan mengambil pendekatan bootstrap-nya dari [opencodee](https://github.com/usermahiro/opencodee) — dengan server agent-nya ditulis ulang sepenuhnya (menggantikan `opencode serve`), dan UI Jetpack Compose baru.

## Fitur

- **Dua penyedia AI**: APInex & NVIDIA NIM — key dan model disimpan terpisah per penyedia, ganti kapan saja dari Pengaturan
- **Environment in-APK**: Ubuntu 24.04.5 + Node 26.13.0 (arm64) dikompres `xz -9e` (±50 MB) — dibongkar sekali ke penyimpanan aplikasi, tanpa unduhan berulang
- **Chat + agent coding**: streaming SSE, thinking/reasoning, *tool calling* multi-putaran
- **Kartu tool yang bisa dibentangkan**: `bash`, `read_file`, `write_file`, `list_dir` — argumen + output lengkap
- **File browser & editor** untuk workdir `/root/work` di dalam guest
- **Model otomatis** ditarik dari penyedia aktif (`/v1/models`), bisa dicari; filter *gratis saja* untuk APInex (`free/`)
- **API key milik sendiri**: diinput pengguna, disimpan di perangkat, divalidasi langsung ke penyedia (NIM: POST `/chat/completions` mini — `/v1/models`-nya publik, tidak bisa memvalidasi key)
- **UI dwibahasa**: Indonesia (default) / English
- Nol dependensi npm di sisi server

## Instalasi

Ambil APK dari [Releases](https://github.com/hoshiyomiX/poket-coding/releases) (aset `poket-coding-*.apk`).

- Perangkat **arm64** (praktis semua HP modern), Android 8.0+ (minSdk 26)
- `targetSdk 28` **disengaja**: Android 9+ melarang eksekusi biner dari direktori data aplikasi — syarat mutlak proot (alasan yang sama dengan Termux). Karena itu distribusikan sebagai file APK, bukan lewat Play Store.
- Saat first-run: izinkan storage (untuk `/sdcard`), pilih penyedia (APInex / NVIDIA NIM), masukkan API key-nya (APInex: `sk-apx…` dari [apinex.bond](https://apinex.bond) · NIM: `nvapi-…` dari [build.nvidia.com](https://build.nvidia.com)), lalu environment dibongkar dari APK (±50 MB, tanpa internet).

## Arsitektur

```
APK (Compose UI, Kotlin)
 ├─ jniLibs/arm64-v8a/libproot*.so, libtalloc.so, libandroid-shmem.so   ← dari Termux/opencodee
 ├─ assets/env/ubuntu-24.04.5-arm64.tar.xz   ← CI: gunzip | xz -9e (±17 MB)
 ├─ assets/env/node-26.13.0-linux-arm64.tar.xz ← official nodejs.org (±33 MB)
 ├─ assets/server/*.js   → dicopy ke /opt/poket di dalam guest
 ├─ assets/runtime-libs/libatomic.so.1 → dicopy ke /usr/lib/aarch64-linux-gnu di guest
 │   (node arm64 membutuhkannya; ubuntu-base minimal tidak menyertakannya)
 └─ RuntimeController: ekstrak env dari APK (dekoder xz pure-Java) → proot → watchdog
GUEST (proot Ubuntu): node /opt/poket/server.js  ↔  127.0.0.1:8765
APP ↔ server lokal: /api/chat (SSE), /api/models, /api/fs*   (header X-Poket-Key + X-Poket-Provider)
SERVER ↔ GATEWAY: api.apinex.bond/v1  atau  integrate.api.nvidia.com/v1 (OpenAI-compatible, function calling)
```

- Server lokal hanya mendengarkan `127.0.0.1`; API key dikirim via header `X-Poket-Key` dan tidak pernah meninggalkan perangkat selain ke penyedia yang dipilih.
- Per penyedia: base URL, hint batas laju 429, `max_tokens` (NIM memintanya eksplisit; APInex memakai default gateway). Default model NIM `nvidia/nemotron-3.5-bolt-e4b` (aktif, dirancang untuk tool calling + agentic chat; model EOL dijawab 410 oleh NIM dengan pesan jelas).
- Daftar model: utamanya via server lokal (cache 5 menit per penyedia+key); bila jalur jaringan guest lambat/gangguan, app **fallback langsung ke penyedia** — jalur yang sama dengan validasi key. Chat selalu via guest (butuh eksekusi tool).
- Semua path tool dipenjara di *allowed roots* (`/root`, `/tmp`, `/sdcard`).
- Keamanan unduhan env: sha256 rootfs & node **diverifikasi di CI** sebelum masuk APK; integritas berikutnya dijamin oleh tanda tangan APK itu sendiri.

## Pengembangan

```bash
# uji server agent (18 test, mock gateway untuk kedua penyedia, tanpa perlu Android)
cd app/src/main/assets/server && node --test test/*.test.js

# build APK (environment assets dibangkitkan oleh CI, bukan ada di repo)
./gradlew assembleDebug
```

CI (`.github/workflows/release.yml`): test server → siapkan env bundle (unduh + verifikasi sha256 + `xz -9e`) → `assembleDebug` + `assembleRelease` → verifikasi `apksigner` → Release + artifact. APK release ditandatangani **keystore stabil yang dikommit di repo privat ini** (`keystore/release.jks`, PKCS12) — runner CI membangkitkan kunci debug acak per-run sehingga tanpa keystore stabil, update in-place Android akan selalu gagal. Rotasi ke GitHub Secrets (`RELEASE_KEYSTORE_BASE64` dkk.) tinggal diatur via API/Settings bila repo kelak dibuka publik.

> **Catatan untuk pengguna app lama (APInex 1.0.x / AIHubMix / MikuLab AI)**: repo ini adalah **kelanjutan proyek yang sama** (di-rename dari `apinex-mobile`), namun applicationId berubah `id.apinex.mobile` → `id.poketcoding.mobile`, jadi Android memandangnya sebagai **aplikasi baru**: uninstall app lama, pasang `poket-coding-*.apk`, lalu first-run ulang (environment sekarang dibongkar dari APK — tanpa unduhan; masukkan ulang API key). Tanda tangan rilis baru (keystore regenerasi CN=Poket Coding); mulai rilis pertama repo ini keystore stabil untuk semua update berikutnya.

## Atribusi & lisensi

- **[opencodee](https://github.com/usermahiro/opencodee)** — referensi arsitektur proot-on-Android dan sumber asli pendekatan bootstrap-nya.
- **[Termux](https://termux.dev)** — `libproot`, `libtalloc`, `libandroid-shmem` di `app/src/main/jniLibs` berasal dari proyek Termux (keluarga lisensi GPL). Distribusikan ulang sesuai ketentuan lisensinya; sumber tersedia di repositori Termux.
- **[Ubuntu Base](https://cdimage.ubuntu.com/ubuntu-base/releases/)** & **[Node.js](https://nodejs.org)** — distribusi arm64 resmi, dibundel ulang di dalam APK.
- **[XZ for Java](https://tukaani.org/xz/java.html)** (org.tukaani:xz, domain publik) — dekoder xz pure-Java untuk membongkar environment.
