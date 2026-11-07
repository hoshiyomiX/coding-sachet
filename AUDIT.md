# AUDIT — Refactor APInex (T-ML7)

Audit refactor total dari skeleton `aihubmix-mobile` ke basis **APInex** (https://apinex.bond), atas pesan owner #1–#3 (2026-11-07): simpan skeleton → hapus repo lama (GitHub + lokal) → refactor ulang dengan basis apinex → push sebagai repo baru.

## §1 — Hasil pelajaran platform APInex (recon 2026-11-07)

Sumber: halaman utama https://apinex.bond, dokumen https://apinex.bond/developers/models, dan probe live endpoint publik `https://apinex.bond/api/public/models` (HTTP 200, 0,76 s, health-checked 2026-11-07T10:09Z).

| Aspek | Temuan |
|---|---|
| Base URL API | `https://api.apinex.bond/v1` (subdomain `api`, BERBEDA dari root domain) |
| Kompatibilitas | OpenAI-compatible (`/v1/chat/completions`, `/v1/models`) |
| Autentikasi | `Authorization: Bearer sk-apx…` |
| Free tier | **20 juta token/hari** pada model kategori free; **15 RPM** untuk akun gratis |
| Berlangganan | $5/500jt token 10 hari s/d $100/20M token 30 hari; $20+ → 30 RPM prioritas |
| Model gratis | 21 model prefiks `free/` (semua `health: live`, harga input/output 0/0 saat probe) |
| Default model app | `free/glm-5.3` (lanjutan alami coding-glm-5.3-free; `allowFree:true`, tools, ctx 1M) |
| Endpoint publik | `/api/public/models` tanpa key — dipakai untuk verifikasi live daftar model |
| Gateway software | APInexGate (open-source milik mereka) — **BUKAN** new-api; semantik error beda dari mikulab/aihubmix |

## §2 — Peta rename mekanis (262 + 8 penggantian, 40 file)

| Area | Lama (AIHubMix) | Baru (APInex) |
|---|---|---|
| applicationId + namespace | `id.aihubmix.mobile` | `id.apinex.mobile` (dir `java/id/apinex/mobile/`) |
| Kelas/file | `AihubmixApp`, `AihubmixApi`, `AihubmixStrings`, `AihubmixTheme`, `AihubmixTeal(-Dark)` | `ApinexApp`, `ApinexApi`, `ApinexStrings`, `ApinexTheme`, `ApinexSky(-Dark)` |
| BuildConfig | `AIHUBMIX_BASE_URL` | `APINEX_BASE_URL` = `https://api.apinex.bond/v1` |
| Header API lokal | `X-Aihubmix-Key` (app + server + test) | `X-Apinex-Key` |
| Env server | `AIHUBMIX_*` | `APINEX_*` |
| Path guest | `/opt/aihubmix`, `.aihubmix-configured`, hostname `aihubmix` | `/opt/apinex`, `.apinex-configured`, hostname `apinex` |
| Kernel release palsu proot | `6.2.1-AIHubMix` | `6.2.1-APInex` |
| Resource Android | `Theme.AIHubMix`, `aihubmix_teal` (#39C5BB), `app_name` AIHubMix, SharedPrefs `"aihubmix"` | `Theme.APInex`, `apinex_sky` (#38BDF8), `app_name` APInex, SharedPrefs `"apinex"` |
| Service | wakelock `aihubmix:server`, notifikasi "AIHubMix", `aihubmix-agent` | `apinex:server`, "APInex", `apinex-agent` |
| CI/artefak | `aihubmix-mobile-*`, judul rilis AIHubMix | `apinex-mobile-*`, "APInex build N" |
| Ikon vektor Kotlin | `AhmFolder`, `AhmTextFile` | `ApxFolder`, `ApxTextFile` |
| Test tmpdir | `ahm-srv-`, `ahm-tools-`, `/etc/ahm-evil` | `apx-srv-`, `apx-tools-`, `/etc/apx-evil` |
| UA unduhan | `aihubmix-mobile` | `apinex-mobile` |

Bug yang ditemukan dan diperbaiki saat rename: cek `".git" in dirpath` pada skrip refactor cocok dengan `.github` → workflow CI sempat terlewati; diperbaiki jadi cek segmen direktori persis (kelas bug substring — sama dengan pelajaran "ttps://").

## §3 — Perubahan semantik (bukan sekadar rename)

1. **Filter model gratis** (ChatScreen, 2 lokasi): `endsWith("-free") || endsWith(":free")` → `startsWith("free/")` — skema penamaan model gratis APInex adalah prefiks, bukan sufiks.
2. **DEFAULT_MODEL** (Prefs): `coding-glm-5.3-free` → `free/glm-5.3` — first-run langsung bisa chat di model gratis.
3. **Deteksi paywall trial DIHAPUS** (agent.js + mock-gateway.js + server.test.js): trik "10 panggilan trial akun belum top-up" adalah perilaku spesifik AIHubMix (teks kaleng HTTP 200). APInex tidak mendokumentasikan perilaku serupa; free tier-nya 20jt token/hari.
4. **Hint rate-limit 429 DITAMBAHKAN** (agent.js): error HTTP 429 kini dibubuhi pesan jelas "Batas laju APInex tercapai (akun gratis: 15 permintaan/menit). Tunggu sebentar lalu kirim ulang pesanmu." — sesuai batas 15 RPM yang didokumentasikan APInex. Uji paywall lama diganti uji hint 429.
5. **Tema**: aksen teal #39C5BB → sky #38BDF8 (dark) / #0369A1 (light) — diambil dari palet situs APInex (sky-400 dominan 39× + emerald). Palet Material3 lengkap diturunkan dari sky.
6. **Teks onboarding** (id+en): semantik gratis diperbarui ("model berawalan free/ gratis — 20 juta token/hari, 15 permintaan/menit"), pembuatan key mengarah ke platform apinex.bond.
7. **Keystore regenerasi**: PKCS12, CN=APInex, alias `apinex`, RSA-2048, valid 30 tahun (2056) — app berubah applicationId sehingga tidak ada jalur upgrade; keystore stabil mulai rilis pertama repo ini.
8. **Release notes CI** (release.yml): menyebut free/, 20jt token/hari, 15 req/menit.

## §4 — Yang TIDAK berubah (disengaja)

- Arsitektur inti: proot Ubuntu 24.04 + jniLibs Termux + server agent Node.js nol-dependensi + Compose UI.
- Jalur jaringan ganda models (via server lokal + fallback direct) dan semua fix T-ML4 (timeout 35 s, warm-up, DNS hardening, cache 5 menit, retry).
- Semua 14 test case server (1 diganti: paywall → 429), API file explorer, sandbox allowed-roots.
- targetSdk 28 (syarat exec proot), minSdk 26, arm64-only.
- Keystore dikommit di repo privat (keputusan terkunci; rotasi ke Secrets bila publik).

## §5 — Hal yang belum terverifikasi (butuh key APInex owner)

- Bentuk nyata error saat kuota 20jt token/hari habis (díasumsikan 429/402 standar; hint 429 sudah menangani 429).
- Format balasan `/v1/models` terautentikasi (diasumsikan OpenAI-style `{data:[{id}]}`; mock mengikuti asumsi ini — sama seperti pola aihubmix yang terbukti benar).
- Nama field skema langganan di balasan chat (tidak dipakai app; hanya diteruskan).

Pemilik key dapat memverifikasi ketiganya lewat app itu sendiri (first-run → masukkan key → buka pemilih model → kirim chat).

## §6 — Verifikasi

- `node --check` semua JS server: lulus (lihat laporan sesi).
- `node --test test/*.test.js`: 14/14 lulus.
- Grep audit: 0 sisa `aihubmix|mikulab|Ahm` di kode (README/AUDIT hanya menyimpan catatan sejarah yang disengaja).
- CI GitHub Actions (repo baru `hoshiyomiX/apinex-mobile`): test-agent → build → apksigner → release.

## §7 — T-ML8: bundel environment + NVIDIA NIM + rename Poket Coding (2026-11-07)

**Permintaan owner**: #1 bundel environment inbuilt dalam APK (Ubuntu+Node) ultra-compression, tanpa unduhan berulang; #2 add NVIDIA NIM support (pelajari API lalu implementasi); #3 rename repo apinex-mobile → poket-coding.

### Riset NVIDIA NIM (live probe 2026-11-07, tanpa key)
- Base URL `https://integrate.api.nvidia.com/v1` (OpenAI-compatible, SSE + tools).
- `GET /v1/models` **PUBLIK** (200 tanpa auth, 84 model, format `data[].id` publisher/name) → tidak bisa dipakai memvalidasi key.
- Auth divalidasi di `POST /chat/completions`: key salah → **403** `{"status":403,"detail":"Authorization failed"}`; tanpa header → **401**. Model EOL → **410** dengan pesan jelas.
- Free tier (FAQ build.nvidia.com): seluruh model gratis untuk prototyping, ±40 RPM, tanpa per-tagihan token.
- Integrasi disarankan menyetel `max_tokens` eksplisit → diset 4096 untuk NIM saja.
- Default model NIM: `nvidia/nemotron-3.5-bolt-e4b` (aktif Okt-2026, "reasoning, tool calling, agentic chat").

### Keputusan kompresi (terukur di sandbox)
| Varian | Ukuran | Keputusan |
|---|---|---|
| ubuntu-base 24.04.5 gz (unduhan lama) | 29,9 MB | — |
| ubuntu-base → xz -9e (pristine) | **17,25 MB** | ✅ dipakai |
| ubuntu-base pruned (doc/man/locale) → xz -9e | 16,69 MB | ❌ hanya -0,56 MB, tak worth risiko |
| node 26.13.0 official .tar.gz (unduhan lama) | 62,6 MB | — |
| node 26.13.0 official **.tar.xz** | **33,05 MB** | ✅ dipakai apa adanya |
| node slim (tanpa include/doc) repack xz | 32,35 MB | ❌ hanya -0,7 MB, kehilangan header npm build |

Total env dalam APK ±50,3 MB (dulu unduhan first-run ±92 MB). SHA256 kedua sumber diverifikasi di CI sebelum dikompres. Dekoder: org.tukaani:xz (pure-Java), `noCompress "xz"` agar tidak dobel-kompresi dan `openFd` bisa melaporkan panjang aset.

### Perubahan
- **Rename total**: package `id.poketcoding.mobile`, app "Poket Coding", header `X-Poket-Key` + `X-Poket-Provider`, `/opt/poket`, service id `poket-agent`, hostname `poket`, prefs `poket`, tema `Theme.PoketCoding`/`poket_sky`, keystore regenerasi CN=Poket Coding alias poket (PKCS12, valid 2056). **FIX bug T-ML7**: pola pkill leftover masih `[a]ihubmix/server.js` → kini `[p]oket/server.js`.
- **Env bundling**: `RuntimeController` mengekstrak `assets/env/*.tar.xz` (TarExtract baru: deteksi magic gzip/xz + API stream); `Fetch.kt` dihapus (tak ada lagi unduhan env); CI step baru "Prepare bundled environment".
- **Multi-provider**: `Providers.kt` (APInex + NVIDIA); `AgentApi` menerima provider (models/validateKey/chatStream); validasi NIM via POST chat mini (2xx/429 = valid); `Prefs` key & model per penyedia; server JS: `config.providers` + routing header + cache per provider+baseUrl (bug cache lintas-test ditemukan & diperbaiki); agent.js: hint 429 per penyedia + max_tokens NIM.
- **UI**: onboarding pilih penyedia → key → instal dari APK; Settings bagian penyedia + key per penyedia; ModelPicker filter `free/` hanya untuk APInex.
- Test server: 14 → **18** (routing provider, hint 429 NIM, max_tokens, provider tak dikenal 400, cache per provider).

### Asumsi (pola keputusan proyek)
- "Rename repo" diartikan rename-total (pattern T-ML6/T-ML7) — app ikut bernama Poket Coding.
- Repo GitHub di-**rename** via API PATCH (bukan delete+create) → histori commit, run_number CI, dan release terpelihara; rilis berikutnya 1.0.3-3.
- Environment disiapkan CI saat build (repo tetap ramping; tidak commit blob 50 MB).

### Belum terverifikasi (menunggu key owner)
- Chat nyata & validasi key NIM dengan `nvapi-…` asli (semua semantik berbasis riset live endpoint publik + mock test).
- Bentuk error kuota NIM selain 429; perilaku model NIM tanpa dukungan tool (user memilih modelnya sendiri).
- Extraksi xz di perangkat nyata (estimasi 20–60 detik, sekali saja, dengan progress bar).
