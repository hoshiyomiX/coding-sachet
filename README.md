# AIHubMix

Asisten **AI coding agent di Android** — lingkungan Ubuntu 24.04 (arm64) berjalan tanpa root lewat **proot**, dengan **agent server Node.js** buatan sendiri yang terhubung ke gateway [https://aihubmix.com/v1](https://aihubmix.com) (OpenAI-compatible; model berakhiran `-free` gratis dalam kuota harian).

Terinspirasi dan mengambil pendekatan bootstrap-nya dari [opencodee](https://github.com/usermahiro/opencodee) — dengan server agent-nya ditulis ulang sepenuhnya (menggantikan `opencode serve`), hardcode ke gateway AIHubMix, dan UI Jetpack Compose baru.

## Fitur

- **Chat + agent coding**: streaming SSE, thinking/reasoning, *tool calling* multi-putaran
- **Kartu tool yang bisa dibentangkan**: `bash`, `read_file`, `write_file`, `list_dir` — argumen + output lengkap
- **File browser & editor** untuk workdir `/root/work` di dalam guest
- **Model otomatis** ditarik dari gateway (`/v1/models`), bisa dicari
- **API key milik sendiri**: diinput pengguna, disimpan di perangkat, divalidasi langsung ke gateway
- **UI dwibahasa**: Indonesia (default) / English
- Nol dependensi npm di sisi server; Ubuntu base & Node.js diunduh saat first-run dengan **sha256 terkunci**

## Instalasi

Ambil APK dari [Releases](https://github.com/hoshiyomiX/aihubmix-mobile/releases) (aset `aihubmix-mobile-*.apk`).

- Perangkat **arm64** (praktis semua HP modern), Android 8.0+ (minSdk 26)
- `targetSdk 28` **disengaja**: Android 9+ melarang eksekusi biner dari direktori data aplikasi — syarat mutlak proot (alasan yang sama dengan Termux). Karena itu distribusikan sebagai file APK, bukan lewat Play Store.
- Saat first-run: izinkan storage (untuk `/sdcard`), masukkan API key AIHubMix (buat di [console.aihubmix.com](https://console.aihubmix.com)), tunggu unduhan rootfs ±30 MB + Node ±50 MB.

## Arsitektur

```
APK (Compose UI, Kotlin)
 ├─ jniLibs/arm64-v8a/libproot*.so, libtalloc.so, libandroid-shmem.so   ← dari Termux/opencodee
 ├─ assets/server/*.js   → dicopy ke /opt/aihubmix di dalam guest
 ├─ assets/runtime-libs/libatomic.so.1 → dicopy ke /usr/lib/aarch64-linux-gnu di guest
 │   (node arm64 membutuhkannya; ubuntu-base minimal tidak menyertakannya)
 └─ RuntimeController: unduh+ekstrak ubuntu-base 24.04 & node 26 → proot → watchdog
GUEST (proot Ubuntu): node /opt/aihubmix/server.js  ↔  127.0.0.1:8765
APP ↔ server lokal: /api/chat (SSE), /api/models, /api/fs*
SERVER ↔ GATEWAY: https://aihubmix.com/v1 (OpenAI-compatible, function calling)
```

- Server lokal hanya mendengarkan `127.0.0.1`; API key dikirim via header `X-Aihubmix-Key` dan tidak pernah meninggalkan perangkat selain ke gateway.
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

> **Catatan upgrade dari MikuLab AI (≤ 1.0.9)**: v1.0.10 adalah **rename total ke AIHubMix** — applicationId berubah `id.mikulab.mobile` → `id.aihubmix.mobile`, jadi Android memandangnya sebagai **aplikasi baru**: uninstall app lama, pasang `aihubmix-mobile-*.apk`, lalu first-run ulang (unduhan ±80 MB + masukkan API key lagi). Tanda tangan rilis juga baru (keystore regenerasi CN=AIHubMix); mulai 1.0.10 keystore ini kembali stabil untuk semua update berikutnya.

## Atribusi & lisensi

- **[opencodee](https://github.com/usermahiro/opencodee)** — referensi arsitektur proot-on-Android dan sumber asli pendekatan bootstrap-nya.
- **[Termux](https://termux.dev)** — `libproot`, `libtalloc`, `libandroid-shmem` di `app/src/main/jniLibs` berasal dari proyek Termux (keluarga lisensi GPL). Distribusikan ulang sesuai ketentuan lisensinya; sumber tersedia di repositori Termux.
- **[proot](https://proot-me.github.io)** by Simon McVittie et al., **Ubuntu Base** by Canonical, **Node.js** by OpenJS Foundation.
