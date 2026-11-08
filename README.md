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
- **File browser & editor** untuk workdir `/root/work` di dalam guest
- **Model otomatis** ditarik dari penyedia aktif (`/v1/models`), bisa dicari; filter *gratis saja* untuk APInex (`free/`)
- **API key milik sendiri**: diinput pengguna, disimpan di perangkat, divalidasi langsung ke penyedia (NIM: POST `/chat/completions` mini — `/v1/models`-nya publik, tidak bisa memvalidasi key)
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

## Pengembangan

```bash
# uji server agent (mock gateway untuk kedua penyedia, tanpa perlu Android)
cd app/src/main/assets/server && node --test test/*.test.js

# build APK lokal (default arm64; environment assets dibangkitkan oleh CI, bukan ada di repo)
./gradlew assembleDebug
```

CI (`.github/workflows/release.yml`): test server → per ABI { unduh + verifikasi sha256 + `xz -9e` env → `assembleRelease` (+ debug untuk arm64) } → verifikasi `apksigner` → Release tag **beta** + artifact. APK release ditandatangani **keystore stabil yang dikommit di repo privat ini** (`keystore/release.jks`, PKCS12, CN=Coding Sachet) — runner CI membangkitkan kunci debug acak per-run sehingga tanpa keystore stabil, update in-place Android akan selalu gagal.
