# APInex

Asisten **AI coding agent di Android** — lingkungan Ubuntu 24.04 (arm64) berjalan tanpa root lewat **proot**, dengan **agent server Node.js** buatan sendiri yang terhubung ke gateway [https://api.apinex.bond/v1](https://apinex.bond) (OpenAI-compatible; model berawalan `free/` gratis — 20 juta token per hari, 15 permintaan per menit; lihat [dokumen model](https://apinex.bond/developers/models)).

Terinspirasi dan mengambil pendekatan bootstrap-nya dari [opencodee](https://github.com/usermahiro/opencodee) — dengan server agent-nya ditulis ulang sepenuhnya (menggantikan `opencode serve`), hardcode ke gateway APInex, dan UI Jetpack Compose baru.

## Fitur

- **Chat + agent coding**: streaming SSE, thinking/reasoning, *tool calling* multi-putaran
- **Kartu tool yang bisa dibentangkan**: `bash`, `read_file`, `write_file`, `list_dir` — argumen + output lengkap
- **File browser & editor** untuk workdir `/root/work` di dalam guest
- **Model otomatis** ditarik dari gateway (`/v1/models`), bisa dicari
- **API key milik sendiri**: diinput pengguna, disimpan di perangkat, divalidasi langsung ke gateway
- **UI dwibahasa**: Indonesia (default) / English
- Nol dependensi npm di sisi server; Ubuntu base & Node.js diunduh saat first-run dengan **sha256 terkunci**

## Instalasi

Ambil APK dari [Releases](https://github.com/hoshiyomiX/apinex-mobile/releases) (aset `apinex-mobile-*.apk`).

- Perangkat **arm64** (praktis semua HP modern), Android 8.0+ (minSdk 26)
- `targetSdk 28` **disengaja**: Android 9+ melarang eksekusi biner dari direktori data aplikasi — syarat mutlak proot (alasan yang sama dengan Termux). Karena itu distribusikan sebagai file APK, bukan lewat Play Store.
- Saat first-run: izinkan storage (untuk `/sdcard`), masukkan API key APInex (buat melalui platform [apinex.bond](https://apinex.bond), format `sk-apx…`), tunggu unduhan rootfs ±30 MB + Node ±50 MB.

## Arsitektur

```
APK (Compose UI, Kotlin)
 ├─ jniLibs/arm64-v8a/libproot*.so, libtalloc.so, libandroid-shmem.so   ← dari Termux/opencodee
 ├─ assets/server/*.js   → dicopy ke /opt/apinex di dalam guest
 ├─ assets/runtime-libs/libatomic.so.1 → dicopy ke /usr/lib/aarch64-linux-gnu di guest
 │   (node arm64 membutuhkannya; ubuntu-base minimal tidak menyertakannya)
 └─ RuntimeController: unduh+ekstrak ubuntu-base 24.04 & node 26 → proot → watchdog
GUEST (proot Ubuntu): node /opt/apinex/server.js  ↔  127.0.0.1:8765
APP ↔ server lokal: /api/chat (SSE), /api/models, /api/fs*
SERVER ↔ GATEWAY: https://api.apinex.bond/v1 (OpenAI-compatible, function calling)
```

- Server lokal hanya mendengarkan `127.0.0.1`; API key dikirim via header `X-Apinex-Key` dan tidak pernah meninggalkan perangkat selain ke gateway.
- Daftar model: utamanya via server lokal (dengan cache 5 menit); bila jalur jaringan guest lambat/gangguan, app **fallback langsung ke gateway** — jalur yang sama dengan validasi key. Chat selalu via guest (butuh eksekusi tool).
- Semua path tool dipenjara di *allowed roots* (`/root`, `/tmp`, `/sdcard`).

## Pengembangan

```bash
# uji server agent (14 test, mock gateway, tanpa perlu Android)
cd app/src/main/assets/server && node --test test/*.test.js

# build APK
./gradlew assembleDebug
```

CI (`.github/workflows/release.yml`): test server → `assembleDebug` + `assembleRelease` → verifikasi `apksigner` → Release + artifact. APK release ditandatangani **keystore stabil yang dikommit di repo privat ini** (`keystore/release.jks`, PKCS12) — runner CI membangkitkan kunci debug acak per-run sehingga tanpa keystore stabil, update in-place Android akan selalu gagal. Rotasi ke GitHub Secrets (`RELEASE_KEYSTORE_BASE64` dkk.) tinggal diatur via API/Settings bila repo kelak dibuka publik.

> **Catatan untuk pengguna app lama (AIHubMix ≤ 1.0.10 / MikuLab AI)**: repo ini adalah **proyek baru** yang di-refactor dari skeleton app sebelumnya dengan basis APInex — applicationId berubah `id.aihubmix.mobile` → `id.apinex.mobile`, jadi Android memandangnya sebagai **aplikasi baru**: uninstall app lama, pasang `apinex-mobile-*.apk`, lalu first-run ulang (unduhan ±80 MB + masukkan API key APInex baru — key AIHubMix tidak berlaku di gateway ini). Tanda tangan rilis baru (keystore regenerasi CN=APInex); mulai rilis pertama repo ini keystore stabil untuk semua update berikutnya.

## Atribusi & lisensi

- **[opencodee](https://github.com/usermahiro/opencodee)** — referensi arsitektur proot-on-Android dan sumber asli pendekatan bootstrap-nya.
- **[Termux](https://termux.dev)** — `libproot`, `libtalloc`, `libandroid-shmem` di `app/src/main/jniLibs` berasal dari proyek Termux (keluarga lisensi GPL). Distribusikan ulang sesuai ketentuan lisensinya; sumber tersedia di repositori Termux.
- **[proot](https://proot-me.github.io)** by Simon McVittie et al., **Ubuntu Base** by Canonical, **Node.js** by OpenJS Foundation.
