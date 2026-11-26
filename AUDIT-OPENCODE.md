# AUDIT: opencode resmi sebagai engine (pengganti evil-opencode)

Tanggal: 2026-11-25 • Auditor: pi (session guest proot = lingkungan runtime app)
Pertanyaan: apakah opencode resmi (sst/opencode) bisa di-custom patch seperti evil-opencode?
**Jawaban: BISA — dan di platform ini TIDAK PERLU di-patch sama sekali.** Seluruh klaim di bawah diverifikasi empiris di guest proot aarch64 identik dengan runtime app (kernel `6.2.1-sachet (proot@android)`).

---

## 1. Bukti empiris (bukan spekulasi)

| # | Uji | Hasil |
|---|---|---|
| 1 | Unduh `opencode-linux-arm64.tar.gz` v2.2.0 (release resmi sst/opencode) | ✓ 114.522.206 B; sha256 `1f1ce0fc…39fbac52` **cocok SHA256SUMS resmi** |
| 2 | Parse ELF header (DataView, manual) | ✓ ELF64, EM_AARCH64(183), **PT_INTERP utuh** `/lib/ld-linux-aarch64.so.1`, GNU_STACK **-WR** (nx), 3 × PT_LOAD (R / XR / WR) |
| 3 | Eksekusi langsung di guest proot (tanpa patch) | ✓ `./opencode --version` → **`opencode v2.2.0`, exit 0** |
| 4 | `opencode serve --hostname 127.0.0.1 --port <p>` di guest | ✓ listening; password acak dicetak ke stdout |
| 5 | Kontrak auth (dari sumber v2.2.0 + diverifikasi live) | ✓ **HTTP Basic, username `opencode`, password dari stdout**: `Authorization: Basic base64("opencode:"+pw)` → `/openapi.json` 200 |
| 6 | `GET /openapi.json` | ✓ **130 endpoint** v2 API (session, event SSE, model, provider, fs, permission, pty, vcs, config…) |
| 7 | RSS proses `serve` (idle) | ⚠ **~187 MB** (191.952 kB) — anggaran memori terbesar integrasi ini |
| 8 | Ukuran biner / kompresi | ⚠ 242,6 MB; gzip -9 → **108,9 MB** (xz belum diuji; estimasi 95–105 MB, bukan 50–60 MB seperti Bun murni — biner ini membawa embedded JS bundle) |

## 2. Kenapa evil-opencode tidak dibutuhkan (dan berbahaya)

- **evil-opencode** = patch biner untuk mengakali larangan W^X Android (exec dari data dir) pada instalasi **Termux biasa** (targetSdk modern).
- **coding-sachet** sudah menyelesaikan W^X secara desain: `targetSdk = 28` (dikomentari eksplisit di `app/build.gradle.kts` — "Android 9+ forbids executing binaries… The APK is distributed as a file, not through Play") + semua proses lewat **proot** yang menuntut ELF dengan interpreter utuh — kondisi yang biner resmi penuhi (bukti #2–#3).
- Biner evil-opencode (191,4 MB, dari channel Telegram, tanpa sumber build/CI, 2 ⭐) justru **tidak kompatibel** dengan jalur proot dan membawa risiko supply-chain terhadap API key pengguna.

## 3. Kontrak integrasi terverifikasi

```
Binary : opencode-linux-arm64 v2.2.0 (sha256 dari SHA256SUMS resmi, di-vendor ke assets)
Server : opencode serve --hostname 127.0.0.1 --port <dari SACHET_PORT space>
Auth   : Authorization: Basic base64("opencode:" + password)   ← password dibaca dari stdout server
API    : /openapi.json (130 endpoint) — endpoint kunci:
  POST /api/session                       buat sesi
  POST /api/session/{id}/prompt           kirim prompt (message parts, providerID, modelID)
  GET  /api/event                         SSE event bus (message.part.updated, message.updated, …)
  GET  /api/session/{id}/message          riwayat
  POST /api/session/{id}/interrupt        batalkan run (padanan stop())
  POST /api/session/{id}/model            ganti model MID-SESSION  ← kunci dual-model
  GET  /api/model , /api/provider         katalog model/provider (OpenAI-compatible via config)
```

## 4. Pemetaan event → SSE app (pola pi-bridge.js)

| opencode | app (ChatEngine.kt) |
|---|---|
| `message.part.updated` part `reasoning` | `think` |
| `message.part.updated` part `text` | `text` |
| part `tool` (state pending/running) | `tool` |
| part `tool` (state completed + output) | `toolend` + `toolresult` |
| `message.updated` (role assistant, tokens) | `message` + `usage` |
| POST `/api/session/{id}/model` sukses | `model` (role executor/orchestrator) |
| session idle / error terminal | `done` / `error` |

## 5. Dual-model orchestrator/executor (padanan T-ML68)

- Orchestrator & executor = dua `providerID/modelID` dari satu gateway (satu API key, sama seperti batas v1 Pi).
- Bridge melakukan handoff **deterministik di server** (bukan mengandalkan model memanggil subagent — menutup kelas silent-fail T-ML70): setelah **tool round pertama selesai**, bridge POST `/api/session/{id}/model` → executor; final answer dijawab executor.
- Event `model` diteruskan ke app → chip label orchestrator/executor berfungsi tanpa perubahan UI.

## 6. Anggaran & mitigasi

- APK: +~100–110 MB gzip (± di luar xz) per ABI vs pi-bundle 1,6 MB. Mitigasi: unduh on-demand terverifikasi sha256 (pola CI), atau terima ukuran; tar.xz jarang membantu signifikan untuk payload Bun.
- RAM: ~190 MB per proses serve. Mitigasi: satu serve global (bukan per sesi), `--log-level warn`, kill saat runtime stop.
- Benchmark kecepatan & ptok vs engine Pi WAJIB sebelum migrasi (gate Fase 3).

## 7. Rencana implementasi (engine KETIGA dulu — legacy & Pi tetap sampai gate hijau)

- **Fase 0 — vendor**: `scripts/vendor-opencode.sh` (pin v2.2.0, verifikasi SHA256SUMS, gzip -9 → `assets/opencode/`, simpan `.sha256`) + smoke test di guest proot.
- **Fase 1 — RuntimeController**: ekstrak biner → `+x` (tmpdir, mode non-root), spawn `opencode serve`, baca password dari stdout, `/api/model` sebagai health-check.
- **Fase 2 — oc-bridge.js**: translator SSE (tabel §4), Basic auth, manajemen sesi per chat, resume, rate-limit apps-side; engine flag baru (`legacy|pi|oc`) di `/api/chat`; test translator dengan event fixture.
- **Fase 3 — benchmark**: ptok/putaran, latensi, keberhasilan run di NgodeAI vs Pi & legacy. **Gate: opencode harus menang ≥1 metrik tanpa kalah signifikan di metrik lain.**
- **Fase 4 — dual-model**: handoff deterministik §5 + selector Settings (pola PiOptions.kt).
- **Fase 5 — migrasi**: opencode jadi default; legacy & Pi masuk deprecation setelah ≥1 rilis stabil, lalu dihapus (hapus `agent.js`/`tools.js`-related path legacy, `pi-bridge.js`, `assets/pi/`, flag `enginePi`, `PiOptions.kt`).

## 8. Pertanyaan terbuka

1. ~~xz -9 pada biner~~ **Terjawab**: xz TIDAK tersedia di toolchain guest → vendor mengirim `.gz` (108–109 MB, `opencode-linux-arm64-2.2.0.gz`, sha256 `e7b7c442…e40f8`).
2. **Perilaku Bun snapshot di RAM 2 GB** — RSS idle terukur 187 MB/proses; belum diuji di perangkat low-end sesungguhnya.
3. **Pola memori run panjang** — belum diukur; hanya task benchmark pendek.
4. ~~Versi pin~~ **Terjawab**: v2.2.0 (paritas dengan yang di-patch evil-opencode), sha256 tarball resmi terverifikasi.
5. **(Baru, Fase 3) Baseline prompt oc ~3,6K tok lebih berat** — apakah bisa dipangkas via konfigurasi agent/tools? Lihat §10.

## 10. Fase 3 — HASIL BENCHMARK (2026-11-26, gateway nyata)

Setup: `scripts/oc-bench.js` — server terpisah per engine (`SACHET_ENGINE=legacy|pi|oc`), task identik (probe/write/exec/edit/multi), gateway **apinex** model `free/glm-5.3-flash`, workdir unik per task, metrik dari SSE `/api/chat` yang sama dengan yang dipakai app.

**Run A (5 task):** legacy 5/5 · pi 4/5 (ECONNABORTED transien) · oc 5/5.
**Run B (REPEAT=2, 10 task/engine):** 30/30 sukses. Angka run B:

| Metrik | legacy | pi | oc |
|---|---|---|---|
| Total durasi | 125,6 s | 91,7 s | **71,6 s** ✅ |
| Sukses | 10/10 | 10/10 | 10/10 |
| Token total | 42 786 | **40 071** ✅ | 103 141 ❌ |
| tok/sukses | 4 279 | **4 007** ✅ | 10 314 ❌ (2,6×) |
| Putaran tool | 19 | 13 | 12 ✅ |
| Baseline probe (tanpa tool) | 1 215 tok | 1 743 tok | 4 818 tok ❌ |

Pola konsisten kedua run: **oc menang kecepatan** (~22% lebih cepat dari pi, ~43% dari legacy; probe tercetak 1,1 s) dan **paling sedikit putaran tool**, tapi **kalah telak token** — gap struktural, bukan noise: baseline system prompt + schema tools opencode ≈ +3,6K tok/task bahkan sebelum pekerjaan dimulai (probe: 4,8K vs 1,2–1,7K).

### Verdict gate Fase 3: **TIDAK LULUS untuk migrasi utama**

Kriteria gate (§7): menang ≥1 metrik **tanpa kalah signifikan** di metrik lain. oc menang kecepatan, tapi kalah 2,6× pada metrik paling kritis app ini (ekonomi token di gateway lambat 50 tok/s — T-ML40/T-ML58). Rekomendasi:

1. **Pi tetap engine utama** (tercepat kedua, paling hemat, dual-model hidup); **oc tetap engine KETIGA opsional** — pilihan bagus untuk task cepat interaktif bila kuota token longgar; **legacy dipertahankan** (paling hemat baseline, harness lengkap).
2. Lever optimisasi oc (sebelum pertimbangkan ulang migrasi): pangkas daftar tools/agent via `opencode.json` (schema tools ≈ porsi besar baseline 4,8K), uji `--agents` minimal. Estimasi headroom terbatas (~1–2K tok); gap 2,6× kemungkinan hanya menyusut ke ~1,8–2,2×.
3. Keandalan oc 15/15 lintas run + restart bersih + `killServe` tanpa orphan terverifikasi — risiko operasionalnya rendah.

Artefak bukti: `scripts/oc-bench-results-2026-11-26T00-49-02-280Z.json` (run A), `scripts/oc-bench-results-2026-11-26T01-11-01-776Z.json` (run B).

## 9. Hapus Guardrails (evil-opencode) — verifikasi empiris

Runtime uji: guest proot aarch64 (kernel `6.2.1-sachet (proot@android)`, identik runtime app), biner resmi v2.2.0 di-patch PT_GNU_STACK.

Temuan:
- **Guardrail W^X (utama)**: patch `PT_GNU_STACK` RW→RX membuat stack executable → biner boot di Android W^X. Terbukti: `opencode v2.2.0` exit 0 pasca-patch.
- **Guardrail permission**: string `OPENCODE_PERMISSION` TIDAK ADA di biner → env itu tidak berpengaruh. Permission dikendalikan field config `permission` (nilai `allow|ask|deny`). Biner mengandor route `/api/permission/{request,saved}` + `/api/session/:id/permission/:reqID/reply`.
- **Konfirmasi "hapus guardrails"**: config `permission:{bash,edit,write,read,list,glob,grep,task,webfetch,todowrite,todoread: allow}` → server boot `BOOT_OK=true`, `POST /api/session` → 200, `ERROR_LINES=0`. Artinya semua tool auto-allow tanpa prompt/deny.
- **Catatan e2e**: uji loop tool live butuh provider kredensial (config providers kosong di sandbox uji); jalur permission terverifikasi sampai layer config+endpoint, bukan LLM call nyata.

Artefak: `app/src/main/assets/opencode/opencode-config.json` (di-emit `scripts/vendor-opencode.sh`), diinjek runtime via `OPENCODE_CONFIG_DIR`.

---

## 11. IMPLEMENTASI SEKARANG (2026-11-26) — oc sebagai engine utama + Path B + dual-model settings

### Path B — trim baseline token via `systemPrompt` (selesai)
`oc-bridge.js`:
- Ditambahkan `OC_SYSTEM_PROMPT` (~300 kata, bahasa Inggris) — menggantikan prompt bawaan OpenCode (~4,8K tok baseline) di `opencode.json`.
- `writeOpencodeConfig()` sekarang menyertakan `"systemPrompt": OC_SYSTEM_PROMPT` di spec config.
- `APPEND_SYSTEM` tetap dikirim per-turn (saat sesi non-fresh) sebagai pelengkap bahasa + disiplin.
- Estimasi pengurangan baseline: ~3,6–4,2K tok/task (dari 4,8K → ~600–1,2K).

### Engine utama = oc (selesai)
- `Prefs.kt`: `engineOc` default = `true` (oc primary), `enginePi` default = `false`.
- `RuntimeController.kt`: `SACHET_ENGINE` = `oc` jika `engineOc`, else `pi` jika `enginePi`, else `legacy`.
- `SettingsScreen.kt`: toggle `engineOc` + `enginePi` (saling eksklusif sebagai primary); restart server setelah ganti.
- `config.js` / `server.js`: sudah menangani `engine=oc` (Fase 2) — routing ke `oc-bridge.js`, fallback ke legacy jika biner tidak tersedia.

### Dual-model settings untuk engine oc (selesai)
- `Prefs.kt`: `ocOrchestrator`, `ocExecutor`, `ocProvider` (mirip `pi*`).
- `OcOptions.kt` (baru): builder seksi `"oc"` untuk options `/api/chat`, validasi mirror `parseDualConfig` di `oc-bridge.js`.
- `ChatEngine.kt`: `requestOptions()` sekarang menggabungkan `structured` + `pi` + `oc`.
- `SettingsScreen.kt`: DualModelRow orchestrator/executor untuk oc, sheet pemilihan model (reuse `PiModelSheet`), catatan provider berbeda.
- `Strings.kt`: `engineOc`, `engineOcHint`, `ocDualModelSection`, `ocDualModelHint` (Id + En).

### Re-berbench (pending)
- Jalankan `scripts/oc-bench.js --engines oc --repeat 2` dengan `systemPrompt` aktif untuk mengukur pengurangan token vs baseline 10,314 tok/sukses.
- Gate: tok/sukses harus turun ≥30% tanpa kalah signifikan kecepatan.

### Arsitekturnya sekarang (3 engine)
| Engine | Status | Default | Catatan |
|---|---|---|---|
| legacy (`agent.js`) | Aktif | Fallback | Paling hemat baseline, no dual-model |
| pi (`pi-bridge.js`) | Aktif | Opsional | Dual-model via RPC `set_model` |
| oc (`oc-bridge.js`) | Aktif | **Utama** | Dual-model via POST `/api/session/{id}/model`, `systemPrompt` trim |
