# AUDIT — Coding Sachet

Audit teknis kondisi terkini (2026-11-07, pasca-rename penuh ke Coding Sachet). Riwayat keputusan lama tersimpan di git log.

## §1 Identitas

| Area | Nilai |
|---|---|
| Nama app / repo | Coding Sachet / `coding-sachet` |
| applicationId + namespace | `id.codingsachet.mobile` (dir `java/id/codingsachet/mobile/`) |
| Header API lokal | `X-Sachet-Key` + `X-Sachet-Provider` (app + server + test) |
| Env var server | `SACHET_*` (PORT, WORKDIR, MAX_TURNS, TIMEOUT_MS, BASH_TIMEOUT_MS, APINEX_BASE_URL, NVIDIA_BASE_URL) |
| Path guest | `/opt/sachet`, `.sachet-configured`, hostname `sachet`, kernel release palsu `6.2.1-sachet` |
| Resource Android | `Theme.CodingSachet`, `sachet_sky` (#38BDF8 / #0369A1), app_name Coding Sachet, SharedPrefs `"sachet"` |
| Service | wakelock `sachet:server`, notifikasi "Coding Sachet", `sachet-agent` |
| Keystore | `keystore/release.jks` PKCS12, CN=Coding Sachet, alias `sachet`, dikommit (repo privat) |
| UI class | `SachetApp`, `SachetStrings`, `SachetTheme`, `SachetSky(-Dark)` |

## §2 Penyedia ganda

- APInex: `https://api.apinex.bond/v1` · Bearer `sk-apx…` · default model `free/glm-5.3` · filter model gratis via prefiks `free/` · validasi key via `/v1/models`.
- NVIDIA NIM: `https://integrate.api.nvidia.com/v1` · Bearer `nvapi-…` · default model `nvidia/nemotron-3.5-bolt-e4b` · validasi key via POST chat mini (401/403) karena `/v1/models`-nya publik · `max_tokens: 4096` eksplisit.
- Hint 429 per penyedia netral (tanpa angka kuota); teks di `config.js`, diuji di `server.test.js`.
- Katalog diverifikasi 2026-11-07: `free/glm-5.3` ada di daftar model publik APInex (42 model); `nvidia/nemotron-3.5-bolt-e4b` ada di `/v1/models` NIM (84 model).
- Catatan lapangan: `api.apinex.bond` memblokir klien non-browser/datacenter (Cloudflare 403 di 0,05 s, teramati dari sandbox — curl, python, UA browser). Dari perangkat nyata (OkHttp + guest fetch) tidak ada laporan blokir.

## §3 Environment in-APK + split ABI

| ABI | rootfs Ubuntu 24.04.5 | Node | triplet guest | libatomic | versionCode |
|---|---|---|---|---|---|
| arm64-v8a | arm64 (a91d5a93…) | v26.13.0-arm64 (406fe3fb…) | aarch64-linux-gnu | libatomic-arm64.so | N×10+1 |
| armeabi-v7a | armhf (4fcee4d2…) | v22.20.0-armv7l (c599a33d…) | arm-linux-gnueabihf | libatomic-armhf.so | N×10+2 |
| x86_64 | amd64 (e77b6f10…) | v26.13.0-x64 (d28a96af…) | x86_64-linux-gnu | libatomic-amd64.so | N×10+3 |

- Node v26 tidak menyediakan build armv7l resmi → armeabi-v7a memakai v22.20.0 LTS (server agent nol-dependensi, kompatibel — suite test CI berjalan di node 22).
- Nama aset arch-neutral (`env/ubuntu-rootfs.tar.xz`, `env/node-runtime.tar.xz`) diisi CI per pass; app tidak perlu tahu arch. `BuildConfig.GUEST_TRIPLET` (dari `-PguestTriplet`) menentukan direktori lib guest + varian libatomic; `BuildConfig.NODE_VERSION` dari `-PnodeVersion`.
- Verifikasi readelf: node ketiga arch NEEDED `libatomic.so.1` (plus libstdc++/libgcc_s/dll yang SEMUA sudah ada di rootfs ubuntu-base masing-masing arch — diperiksa langsung ke hasil ekstraksi).
- jniLibs: arm64 = build 5.1.107 lama yang teruji; armeabi-v7a + x86_64 = pra-dibangun 5.1.107.102 (NEEDED: libtalloc.so.2, libandroid-shmem.so, libc bionic — semua dikirim app; loader di `usr/libexec/proot/loader` → `libproot-loader.so`).
- Rilis: tag tetap **beta**; aset diganti utuh tiap build (delete-asset lalu upload); debug hanya arm64.

## §4 Keamanan & jaringan

- Server lokal 127.0.0.1:8765 (SACHET_PORT), key tidak keluar perangkat selain ke penyedia.
- Dual-path: proxy via guest + fallback langsung OkHttp (jalur validasi key) — timeout 35 s, DNS hardened, cache 5 menit, retry.
- **DNS ramah VPN (T-ML10)**: resolv.conf guest tidak lagi hardcode resolver publik — app membaca DNS jaringan aktif via ConnectivityManager (ikut VPN) lalu `POST /api/dns` menulis ulang resolv.conf (DNS sistem ≤3 dulu, 1.1.1.1/8.8.8.8 cadangan, marker `# managed by coding-sachet`); push ulang tiap server hidup + setiap perubahan jaringan (throttle 10 s); productionSetup hanya menulis fallback bila belum ada marker. Semua fetch gateway (models/chat/quota) dibungkus `fetchWithRetry` — retry 2× khusus kegagalan connect-level (delay 400/900 ms, AbortError tak di-retry, stream tak pernah duplikat).
- **Riwayat lintas model (T-ML10)**: `toOpenAiMessages` melakukan healing — id tool-call kosong disintesis dan dipasangkan posisional dengan tool result; putaran terhenti (stop sebelum tool menjawab) dilipat jadi pesan assistant polos; tool result orphan di-drop — gateway ketat (NIM/OpenAI) tidak lagi menolak 400 saat ganti model di tengah sesi.
- **Kuota (T-ML20 — 1 API resmi)**: `GET /api/quota` (X-Sachet-Key/Provider, cache 60 s, `?fresh=1`) memanggil SATU endpoint resmi yang didokumentasikan apinex.bond/developers: **`/v1/subscription`** (tokens_remaining/tokens_used/token_limit/unlimited). Rantai fallback lama DIHAPUS atas perintah owner ("cukup gunakan 1 API sesuai official documents"): best-effort `/v1/balance` dan pasangan new-api `/v1/dashboard/billing/*` tidak pernah dipanggil lagi — kegagalan endpoint resmi langsung tampak sebagai kegagalan kuota. NIM: `{supported:false}` — chip disembunyikan. UI: chip "Sisa token" kanan-atas kolom chat (tanpa USD), refresh saat layar dibuka + server Running + tiap akhir giliran + ketuk (fresh). Endpoint lain yang dipakai app semuanya resmi: `/v1/models` (katalog, APInex juga utk validasi key), `/v1/chat/completions` (chat; NIM juga utk probe validasi karena /v1/models-nya publik).
- Path tool dipenjara allowed roots `/root`, `/tmp`, `/sdcard`.
- **Mode root (T-ML11)**: opt-in via Pengaturan (hanya tampil bila biner `su` terdeteksi; deteksi file-only, tak pernah mengeksekusi `su` untuk probe). Saat aktif: proot dijalankan lewat `su -c` (env proot dikutip POSIX di dalam string perintah), bind `/:/device`, `SACHET_EXTRA_ROOTS=";,/device"` — file explorer bisa merambah perangkat; **implikasi**: agent bash yang berjalan sebagai root punya akses tulis ke seluruh perangkat (risiko disadari pemilik, alat developer).
- proot: `--root-id --kill-on-exit --link2symlink`, bind /dev /proc /sys + fake loadavg/stat/uptime/version, /dev/shm dari tmp rootfs, mode kompatibilitas `PROOT_NO_SECCOMP` otomatis bila smoke-test gagal.

## §5 Hal yang belum terverifikasi

- Chat nyata kedua gateway dengan key resmi: APInex terblokir dari sandbox (Cloudflare), NIM tidak ada key `nvapi-` yang diunggah saat sesi ini. Suite mock 25/25 tetap hijau; validasi nyata (termasuk endpoint kuota natif APInex dan perilaku VPN nyata) menunggu first-run di perangkat.
- Perilaku armv7/x86_64 di perangkat nyata (biner sudah diverifikasi ELF-arch + NEEDED, tapi belum ada uji hardware).
- Mode root (`su -c` + bind `/:/device`) belum teruji di perangkat rooted sungguhan — jalur su dikutip POSIX-shape, tapi setiap implementasi su (Magisk/KernelSU/phh) punya keunikan sendiri.

## §6 Audit UI/theme (T-ML11)

Temuan → perbaikan yang diterapkan:

- **Kontras WCAG**: `SachetSky` (#38BDF8) dipakai langsung sebagai warna teks/ikon di atas background terang (≈2.0:1 — di bawah ambang 4.5:1). Semua pemakaian untuk teks/ikon diganti `MaterialTheme.colorScheme.primary` (light = sky-700 #0369A1, dark = sky-400) di: judul drawer, empty-state chat, chip runtime, ikon kirim, judul Onboarding.
- **Aksesibilitas**: ikon interaktif dengan `contentDescription = null` (menu, back ×2, hapus sesi, tambah chat/folder/file) kini berlabel; tombol stop memakai `Icons.Filled.Stop` + label (sebelumnya `Text("■")` tanpa semantics); QuotaChip mendapat pill Surface + `heightIn(min=32.dp)` + `semantics{contentDescription}` (sebelumnya teks kecil tanpa latar).
- **Warna hardcode di ToolCard** (0xFF7E9CFF/8BC48A/E0A55E) diganti warna tema (primary/secondary/tertiary + `sachetSuccess()` baru: hijau per-tema, kontras terjaga di light & dark).
- **FilesScreen**: tombol "Naik ⌃" (teks + karakter unicode) → IconButton panah; path bar kini `weight(1f)` + ellipsis (sebelumnya path panjang meluber); empty state "Folder kosong"; tombol folder baru (dialog mkdir sudah ada sejak awal tapi tak pernah terjangkau UI — dead code dihidupkan); nama file ellipsis.
- **SettingsScreen**: label "Akses penyimpanan (battery)" yang membingungkan → string sendiri "Abaikan optimasi baterai".
- **Markdownish**: docstring menjanjikan `headings` tapi tak diimplementasikan → kini baris `#`–`####` dirender titleSmall bold per baris.
- Tidak berubah (diterima): top bar model TextButton (cukup), lebar bubble 320dp (wajar di ponsel), tanpa animasi list (kinerja di perangkat low-end).

## §7 Audit kode + toolbox (T-ML11)

Insiden & perbaikan:

- **Insiden 1.0.6 (kritis)**: `net.js` terkirim di APK tapi tidak ada di `SERVER_FILES` → tak pernah disalin ke `/opt/sachet` → `MODULE_NOT_FOUND` crash loop. Fix: `SERVER_FILES` kini memuat semua file yang di-require saat boot + dua pengaman — `writeServerFiles` memverifikasi hasil salin non-kosong, `ensureServerFilesCurrent` menyembuhkan file hilang walau versi sama.
- **Race double-send**: `ChatEngine.send` menyetel `live` di dalam `scope.launch` → flag busy belum terlihat saat tap kedua. Fix: `send` kini sinkron di thread pemanggil (semua operasi main-safe; IO tetap di `persist()`).
- **readFile slurp penuh**: file 2 GB dibaca seluruhnya ke memori sebelum dipotong. Fix: baca maksimal `readMaxBytes` via `fs.readSync` dari fd (test: file 2 MB → konten terpotong tepat 256 KB).
- **safePath root "/"**: prefix `startsWith('/'+sep)` tak pernah cocok untuk sub-path dari "/" → mode root tak akan bisa menjelajah. Fix: cabang khusus `r === '/'` (test: root ekstra bisa diramban, sandbox default tetap tersegel 400).
- **Toolbox dev diperluas** (instalasi opsional via apt): git, ripgrep, curl, wget, ca-certificates, unzip, zip, xz-utils, less, nano, jq, tree, htop, python3, python3-pip, python3-venv, build-essential (gcc/g++/make), sqlite3, pkg-config — kover VCS, search, JSON, arsip, Python terisolasi (venv), toolchain C/C++, DB.
- Diketahui & diterima: abort klien tidak memotong tool bash yang sedang berjalan (selesai via timeout 120 s); file buatan mode root dimiliki uid 0 → `resetRuntime` membersihkan via `su rm -rf` (dengan fallback normal); watchdog `p.destroy()` di mode root membunuh `su` induk — node yatim ditangani `killLeftoverServer` (pkill via su) di iterasi supervise berikutnya.

## §8 Audit r3 — kesesuaian vs referensi android-apk-compose.md (T-ML19, 2026-11-09)

Tolok ukur: `skills/stellar-trail/references/android-apk-compose.md` (blueprint modul/version-catalog/CI untuk APK companion). Hasil per dimensi:

**Sesuai penuh (tanpa tindakan):**
- Version catalog `gradle/libs.versions.toml` = satu sumber kebenaran (AGP 9.4.1, Kotlin 2.4.20, Compose BOM 2026.09.00, semua ter-pin eksak).
- Root `build.gradle.kts` hanya plugin aliases (`apply false`); `settings.gradle.kts` memakai `RepositoriesMode.FAIL_ON_PROJECT_REPOS` (lebih ketat dari referensi).
- Gradle wrapper utuh (9.8.0, jar + properties); `gradle.properties` memuat flag AndroidX + parallel + caching.
- CI `release.yml`: filter trigger berbentuk list (`branches: [main]` — bukan bare-string foot-gun), blok `permissions` eksplisit, `concurrency` + `timeout-minutes` di semua job, `checkout@v4` + `setup-java@v4` (temurin) + `setup-gradle@v4` + `upload-artifact@v4`, test server Node berjalan di job terpisah yang di-`needs` sebelum build (test-first), verifikasi tanda tangan apksigner + checksum sha256 (melampaui referensi), rilis via `gh` + `GH_TOKEN`.
- UI single-activity Compose (`MainActivity` → `AppRoot`, navigasi `sealed class Screen` + backstack); layering bersih `api/ data/ runtime/ ui/`.
- Release build: `isMinifyEnabled` + `isShrinkResources` + proguard keeps serialisasi (persis pola referensi).
- Hygiene: sweep rahasia bersih (tanpa `nvapi-`/PAT di tree); `SERVER_FILES` 7/7 sinkron dengan file server; manifest rapi (komentar per permission, FileProvider + `file_paths.xml`); backup rules mengecualikan rootfs.

**Deviasi terkunci/terjustifikasi (dokumentasi, bukan bug):**
- `targetSdk 28` vs 35 referensi — keputusan owner (syarat exec proot; komentar in-code).
- Keystore dikommit vs "never commit" — keputusan owner (repo privat; CI debug-key per-run akan merusak update in-place; alasan di `.gitignore`).
- Modul tunggal `:app` vs `shared`+`composeApp` CMP — aplikasi Android-only tanpa target iOS/desktop; referensi memang blueprint companion stellar-trail ("reference only").
- Tanpa Koin/Voyager/Coil/Kermit — DI manual (`rememberAppState`), navigasi backstack sendiri, tanpa gambar async, logging via `Logx` (filosofi nol-dependensi proyek).
- JDK 21 vs 17 — disyaratkan AGP 9.x/Gradle 9.8; tag rilis tetap `beta` (keputusan owner); tanpa trigger `pull_request` (repo privat solo).
- `usesCleartextTraffic` — hanya untuk loopback 127.0.0.1 (diaudit T-ML18).

**Celah ditemukan & ditutup (fix r3):**
- **Nol unit test Kotlin + CI tanpa langkah test Kotlin** — satu-satunya ketidaksesuaian nyata vs doktrin "unit tests first" referensi. Fix: `app/src/test/java/id/codingsachet/mobile/CodingSachetUnitTest.kt` (13 test murni-JVM, nol dependensi baru: gating model free NIM/APInex + fallback provider, round-trip JSON `Session`/`Msg`/`ToolCall` + `ignoreUnknownKeys`, snapshot `wireHistory`, paritas lokalasi ID/EN via refleksi data class, fallback `stringsFor`) + langkah CI `:app:testDebugUnitTest` sebelum loop build ABI. Gate saat audit: `node --test` 47/47, `node --check` 7/7.

## §9 Audit r4 — API tunggal resmi, dot merah/hijau, redesign UI (T-ML20, 2026-11-09)

**#1 API (audit + validasi + 1 API resmi per fitur):** semua endpoint yang dipanggil app/server dienumerasi dan divalidasi terhadap dokumen resmi (apinex.bond/developers di-fetch; NIM OpenAI-compatible). Hasil: models=`/v1/models` ✓ resmi, chat=`/v1/chat/completions` ✓ resmi, validasi key APInex=`/v1/models` ✓ resmi, validasi key NIM=probe chat ✓ resmi (diperlukan karena /v1/models NIM publik), kuota → disederhanakan dari 3 pola (subscription + balance best-effort + fallback billing new-api) menjadi SATU: `/v1/subscription` (didokumentasikan resmi, memberi tokens_remaining yang menggerakkan chip "Sisa token"). Fallback non-resmi dihapus dari server.js/config.js/mock-gateway; test diperbarui (1 hit gateway per fetch; 404 → 502 tanpa fabrikasi angka).

**#2 Dot merah/hijau (model bekerja):** `Provider.isWorkingModel()` — NIM: hijau = 19 model verifiedModels (probe chat nyata 2026-11-08), merah = sisanya (embedding/rerank/404). APInex: katalog publik diprobe 2026-11-09 → 42/42 `health:live` → `catalogHealthy=true` (semua hijau); free tier = `allowFree` (35 model — LEBIH LUAS dari prefiks free/, mencakup model premium seperti claude-opus-5.5/gpt-6.1-terra) dibakar sebagai `verifiedFreeModels` + badge FREE. Baris model = ListItem: dot leading (hijau sachetSuccess / merah error), nama monospace, badge FREE, ikon ✓ untuk model aktif; sort model bekerja di atas; legenda diperbarui.

**#3 Redesign UI standar (Material 3 kanonik):** ChatScreen dibangun ulang — Scaffold + `TopAppBar` resmi (menu drawer, judul sesi, baris pendukung model+dot status runtime, aksi files/settings), judul-klik = pemilih model (pola top-center switcher + ArrowDropDown affordance), composer = bidang rounded-24dp + `FilledIconButton` bulat (kirim primer / stop error), drawer = header identitas app (nama + tagline) + `NavigationDrawerItem` "Chat baru" + `HorizontalDivider` + label seksi + `NavigationDrawerItem` per sesi (badge hapus), chip kuota tetap kanan-atas kolom chat (posisi order owner, tanpa USD). Settings/Files sudah TopAppBar sejak awal — tidak diubah.
