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
- Path tool dipenjara allowed roots `/root`, `/tmp`, `/sdcard`.
- proot: `--root-id --kill-on-exit --link2symlink`, bind /dev /proc /sys + fake loadavg/stat/uptime/version, /dev/shm dari tmp rootfs, mode kompatibilitas `PROOT_NO_SECCOMP` otomatis bila smoke-test gagal.

## §5 Hal yang belum terverifikasi

- Chat nyata kedua gateway dengan key resmi: APInex terblokir dari sandbox (Cloudflare), NIM tidak ada key `nvapi-` yang diunggah saat sesi ini. Suite mock 18/18 tetap hijau; validasi nyata menunggu first-run di perangkat.
- Perilaku armv7/x86_64 di perangkat nyata (biner sudah diverifikasi ELF-arch + NEEDED, tapi belum ada uji hardware).
