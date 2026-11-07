# Audit Fungsi Inti — AIHubMix

Tanggal: 2026-11-07 · Lingkup: server agent Node (`app/src/main/assets/server`), gateway produksi, alur data app.

## 1. API test

| Uji | Hasil |
|-----|-------|
| E2E mock gateway — agent loop (tool round-trip → final text) | ✅ PASS |
| E2E mock gateway — error gateway muncul sebagai event `error` | ✅ PASS |
| Route HTTP `/api/chat` — SSE `think`/`text`/`tool`/`done` | ✅ PASS |
| Route HTTP `/health`, `/api/models` (proxy gateway), `/api/fs` | ✅ PASS |
| **Total suite** | **12/12 PASS (748 ms, Node test runner)** |

Live probe `https://ai.mikulab.cloud/v1` (gateway lama, era T-ML2; digantikan AIHubMix sejak v1.0.9 — lihat §7):

| Endpoint | Tanpa key | Key dummy |
|----------|-----------|-----------|
| `GET /v1/models` | 401 `new_api_error: Invalid token` | 401 `Invalid token` |
| `POST /v1/chat/completions` | 401 `new_api_error` | — |
| `GET /` | 200 (UI web new-api) | — |

Latensi rata-rata 0,1–0,2 s. Kesimpulan: gateway aktif, autentikasi ditegakkan, format error OpenAI-compatible — sesuai dengan asumsi protokol di `AihubmixApi.kt` dan `agent.js`. Uji 200 terautentikasi tidak dilakukan (key API adalah input pengguna di dalam app, tidak tersedia saat audit); jalur tersebut tercakup oleh E2E mock gateway.

## 2. Storage access (akses penyimpanan)

- ✅ `safePath` menjaga semua path tetap di dalam allowed roots (`/root`, `/tmp`, `/sdcard`) — escape ditolak.
- ✅ Round-trip tulis→baca file via `/api/fs/file` (GET/PUT).
- ✅ `listDir` berjalan rekursif lintas subdirektori.
- ✅ File hilang dilaporkan sebagai error, bukan crash.
- Runtime-nya sendiri menulis sesi chat ke `filesDir/sessions` (Android) dan file kerja agent ke `/root/work` (guest proot).

## 3. Tools calling — read / write / execute

| Tool (fungsi) | Uji | Hasil |
|---------------|-----|-------|
| `read_file` (read) | baca hasil tulis, path luar sandbox ditolak, file hilang → error | ✅ |
| `write_file` (write) | tulis lalu baca balik, penolakan path luar sandbox | ✅ |
| `bash` (execute) | jalankan di workdir + laporkan exit code | ✅ |
| `bash` (execute) | output dipotong sesuai batas (truncation cap) | ✅ |
| `bash` (execute) | timeout mematikan proses anak | ✅ |
| `list_dir` (read) | traversal subdirektori | ✅ |

## 4. Catatan & batasan

- Key API **tidak pernah** di-hardcode; pengguna memasukkan key sendiri (disimpan lokal, dikirim via header `X-Aihubmix-Key` ke server lokal 127.0.0.1:8765).
- Build APK di sandbox dev terhambat rate-limit Maven Central (HTTP 429); build final didelegasikan ke GitHub Actions (`.github/workflows/release.yml`) yang juga menjalankan suite test ini sebelum assemble.
- Guest runtime: Ubuntu 24.04.5 arm64 + Node v26.13.0 (unduhan terkunci sha256 saat first-run app, bukan dibundel di repo).

Cara menjalankan ulang audit: `cd app/src/main/assets/server && node --test test/*.test.js`

## 5. T-ML3 — Perbaikan runtime: `libatomic.so.1` hilang (2026-11-07)

Laporan lapangan: pada 1.0.6, first-run gagal di smoke test — `node --version` keluar **kode 127** dengan pesan loader `error while loading shared libraries: libatomic.so.1`. Analisis statis (readelf NEEDED vs isi rootfs ubuntu-base 24.04.5 arm64, tarball diverifikasi sha256 sama dengan pin aplikasi):

| NEEDED `node v26.13.0 arm64` | Ada di ubuntu-base? |
|---|---|
| libatomic.so.1 | ❌ **tidak ada** (satu-satunya yang hilang) |
| libdl.so.2, libm.so.6, libstdc++.so.6, libgcc_s.so.1, libpthread.so.0, libc.so.6, ld-linux-aarch64.so.1 | ✅ semua ada |

Perbaikan:

- `app/src/main/assets/runtime-libs/libatomic.so.1` — diambil dari paket Ubuntu noble `libatomic1 14.2.0-4ubuntu2~24.04.1 arm64` (ports.ubuntu.com), SONAME `libatomic.so.1`, dependensinya (libc, ld-linux) sudah ada di rootfs. Digandakan di dalam APK (67 KB) sehingga first-run tidak butuh unduhan/`apt` tambahan.
- `RuntimeController.ensureRuntimeLibs()` — menyalin lib ke `/usr/lib/aarch64-linux-gnu` guest: (a) sebelum smoke test pada setiap instalasi/retry, dan (b) saat `ensureServerFilesCurrent()` (app update) untuk menyembuhkan rootfs versi lama tanpa unduh ulang.
- Pembaruan aplikasi 1.0.6 → 1.0.7 dengan rootfs yang masih ada: lib langsung disalin, **tanpa mengunduh ulang** ±80 MB.

Terkait: release kini ditandatangani keystore stabil yang dikommit (`keystore/release.jks`) — build ≤ 1.0.6 memakai kunci debug ephemeral runner sehingga upgrade in-place tidak mungkin; pengguna ≤ 1.0.6 uninstall sekali lalu pasang ≥ 1.0.7.

## 6. T-ML4 — Perbaikan "Could not load models: timeout" (2026-11-07)

Laporan lapangan: pada 1.0.7, membuka model picker gagal "Could not load models: timeout". Pengujian dengan key produksi (dari sandbox):

- Gateway `/v1/models` **200 dalam 0,12–0,19 s** (2 model) — gateway cepat dan sehat.
- E2E `server.js` → gateway asli: **200 dalam 0,07–0,17 s** — kode server benar.
- Kesimpulan: yang melebihi timeout 10 s OkHttp hanyalah **jalur jaringan guest proot di perangkat** (DNS/IPv6 spesifik-operator), bukan kode.

Perbaikan berlapis (semua lapis diuji):

| Lapis | Perubahan |
|---|---|
| App `AihubmixApi.models()` | read timeout 10 s → **35 s** untuk jalur guest + **fallback langsung app→gateway** (jalur yang sama dengan validasi key, terbukti jalan di perangkat) bila jalur guest gagal |
| Server `main()` | **warm-up DNS+TLS** fire-and-forget saat server start (log `warm-up gateway: HTTP …`) — request pertama user sudah hangat |
| Server `main()` | **resolv.conf dikeraskan**: `options timeout:2 attempts:2 single-request-reopen` + 3 resolver publik — memotong worst-case DNS paralel A/AAAA yang sering diganggu NAT operator |
| Server `main()` | `dns.setDefaultResultOrder('ipv4first')` — hindari black-hole IPv6 |
| Server `/api/models` | **cache in-memory 5 menit per key** (`cached: true`) — retry & buka-tutup picker jadi instan; kegagalan tidak di-cache |
| UI `ModelPicker` | tombol **Coba lagi** saat error (dulu harus tutup-buka sheet) |
| Server `fetchModels` | timeout 30 s → 20 s agar selalu di bawah read timeout app (35 s) — app menerima pesan error jelas, bukan "timeout" |

Regresi: **13/13 PASS** (12 lama + 1 baru: cache per-key diverifikasi via counter permintaan mock gateway). E2E produksi: call-1 fresh → call-2 `cached: true`.

Catatan terkait key uji: key valid namun **quota akun gateway habis** (`insufficient_user_quota, IDR 0.000000`) — chat completion ditolak 403 sampai top-up; daftar model tidak terpengaruh. Key tidak pernah ditulis ke repo/memori/log.

## 7. Peralihan gateway → AIHubMix (v1.0.9, T-ML6)

Gateway produksi diganti `https://ai.mikulab.cloud/v1` → **`https://aihubmix.com/v1`** (BuildConfig + default `config.js`). Alasan: gateway lama tanpa free tier (user baru saldo 0, model "gratis" pun diblokir new-api saat `userQuota <= 0`), AIHubMix menyubsidi 71 model `-free` ($0/M token).

### Pengujian live seluruh model gratis (2026-11-07, key owner, transient)

- `GET /v1/models`: 200 — **662 model**, 42 berakhiran `-free`/`:free`.
- **42/42 model diuji** via `POST /v1/chat/completions` (prompt minimal, jeda 6,5 s):
  - **8 respons inferensi nyata** (2–5 s, ~126 token) sebelum kuota trial habis — jawaban benar (contoh `coding-glm-5.3-flash-free`: "Saya adalah model bahasa besar GLM yang dikembangkan oleh Z.ai…").
  - Setelah trial habis: **HTTP 200 palsu** — konten kaleng `"Sorry, to prevent abuse of free resources, accounts that have not been recharged can only try 10 times…"` (usage 0 token, id `chatcmpl-fake-*`), tiba sebagai **satu delta chunk** `finish_reason: stop`. 26 model menghasilkan catatan ini.
  - **7 gagal**: 6 model `:free` rute OpenRouter ("cannot be served at the moment": llama-3.1-405b/70b/8b, llama-3.2-11b/3b, mistral-7b) + `coding-glm-5-turbo-free` ("Unknown Model" — entri katalog basi).
- Ketentuan free tier terverifikasi: **10 panggilan trial** untuk akun belum top-up (tidak kedaluwarsa); setelah top-up sekali (≥ $1): **100 permintaan/hari, 10 permintaan/menit, 1 juta token/hari** (reset harian, dibagi seluruh model gratis).

### mitigasi di app

| Lapis | Perubahan |
|---|---|
| `agent.js` | Deteksi catatan paywall (`startsWith` prefix resmi) di akumulator delta **sebelum emit** + jaring pengaman di `runAgent` → dilempar sebagai error jelas berbahasa Indonesia (memuat link topup), tidak pernah tampil sebagai balasan chat |
| `ModelPicker` | Chip **"Model gratis saja"** default ON (filter `-free`/`:free`) + penghitung `n / total` — katalog 662 model tetap bisa diakses via toggle + pencarian |
| `Strings.kt` | Onboarding & label key menyebut AIHubMix (ID/EN) |
| Test | +1 tes: mock `kind: 'trial'` meniru bentuk live persis (satu chunk, stop, 0 token) → assert error jelas & **tidak ada delta teks bocor** |

Regresi: **14/14 PASS** (13 lama + 1 baru). Key uji tetap hanya di `upload/` milik owner — tidak pernah masuk repo/memori/log.

## 8. Rename total → AIHubMix + audit & fix (v1.0.10, T-ML6)

Perintah owner: "Rename total ke AIHubMix + audit & fix code". Lingkup: identitas penuh (package, branding, header, env, path rootfs, keystore, CI), audit menyeluruh seluruh sumber, dan perbaikan temuan.

### Peta rename (247 referensi diperiksa, grep akhir: 0 sisa di kode)

| Kelas | Lama → Baru |
|---|---|
| applicationId + namespace | `id.mikulab.mobile` → `id.aihubmix.mobile` (direktori `java/id/aihubmix/mobile/`) |
| Kelas/file | `MikuApp` → `AihubmixApp`, `MikuApi` → `AihubmixApi`, `MikuStrings`/`MikuTheme`/`MikuTeal(-Dark)` → `Aihubmix*`, nama ikon vektor `MikuFolder`/`MikuTextFile` → `Ahm*` |
| BuildConfig | `MIKULAB_BASE_URL` → `AIHUBMIX_BASE_URL` (nilai tetap `https://aihubmix.com/v1`) |
| Header autentikasi lokal | `X-Miku-Key`/`x-miku-key` → `X-Aihubmix-Key`/`x-aihubmix-key` (Kotlin + server.js + test) |
| Env var server | `MIKU_*` (BASE_URL/PORT/WORKDIR/MAX_TURNS/TIMEOUT_MS/BASH_TIMEOUT_MS) → `AIHUBMIX_*` |
| Path guest | `/opt/mikulab` → `/opt/aihubmix`, marker `.miku-configured` → `.aihubmix-configured`, hostname `mikulab` → `aihubmix`, pkill pattern ikut |
| Kernel release palsu proot | `6.2.1-MikuLab` → `6.2.1-AIHubMix` |
| Resource Android | `Theme.MikuLab` → `Theme.AIHubMix`, `miku_teal` → `aihubmix_teal` (nilai teal dipertahankan), `app_name` "MikuLab AI" → "AIHubMix" (id+en), SharedPreferences `"miku"` → `"aihubmix"` |
| Service | action `id.aihubmix.mobile.START/STOP`, wakelock `aihubmix:server`, TAG log "AIHubMix", judul notifikasi "AIHubMix" |
| Server JS | `mikulab-agent` → `aihubmix-agent` (health `service` + banner log) |
| Build/CI | rootProject `aihubmix-mobile`, artifact `aihubmix-mobile-*.apk`, judul release "AIHubMix build N", UA unduhan `aihubmix-mobile` |
| Test | prefix tmpdir `ahm-srv-`/`ahm-tools-`, path negatif `/etc/ahm-evil` |

Dipertahankan disengaja: warna aksen teal dan ikon bubble (identitas visual netral, bukan aset bermerek); alias historis di §1–§7 dan domain `ai.mikulab.cloud` sebagai catatan sejarah.

### Temuan audit & perbaikan

1. **Release notes CI basi** — masih menulis "hardcoded to https://ai.mikulab.cloud/v1" pada rilis 1.0.9 (commit 2201567 melewatkan bagian ini) → diperbarui menyebut AIHubMix.
2. **Tanpa model default** — pengguna baru wajib buka picker sebelum bisa chat → `Prefs.DEFAULT_MODEL = "coding-glm-5.3-free"` (model pilihan owner; terverifikasi hidup di §7, upstream glm-5.3).
3. **Keystore lama CN=MikuLab AI, alias=mikulab** → regenerasi PKCS12 (CN=AIHubMix, alias=aihubmix, RSA-2048, valid 2056). Aman karena applicationId berubah (instalasi baru; tidak ada jalur upgrade dari app lama) — dan mulai 1.0.10 keystore ini stabil untuk update in-place berikutnya.
4. **README**: jumlah test dikoreksi 12 → 14; catatan upgrade lama (era debug-key 1.0.6) diganti catatan rename (uninstall app MikuLab lama + first-run ulang).
5. Audit lintas file (MikuApi/ChatEngine/ChatScreen/RuntimeController/server.js/agent.js/tools): tidak ditemukan defect fungsional lain — fallback direct + cache 5 menit + deteksi paywall trial (§7) tetap utuh dan hanya terkena rename identitas.

### Konsekuensi bagi pengguna (dilaporkan eksplisit)

ApplicationId berubah → Android memandang AIHubMix v1.0.10 sebagai **aplikasi baru**: uninstall MikuLab AI lama, instalasi bersih (unduhan rootfs ±80 MB, API key dimasukkan ulang, riwayat chat lama tidak dibawa — data lama tetap di app terhapus bila di-uninstall).

Regresi: **14/14 PASS** (`node --test test/*.test.js`, pasca-rename; header/env baru terverifikasi oleh test yang sama).
