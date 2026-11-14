# AUDIT TOKEN — T-ML28

> **Status implementasi (T-ML29, 2026-11-11):** fix #1 ✓ (stream_options.include_usage + parse chunk usage + event `usage` per putaran + log `ptok/ctok/rtok/cache` + chip "Pemakaian sesi" + sisa token ANGKA UTUH — menyolve backlog p2) · fix #2 ✓ (model hanya terima head 2 KB + tail 2 KB output tool via `forModel`; histori app/UI tetap lengkap; berlaku juga pada histori lama yang dikirim ulang) · fix #3 ✓ (kompaksi: hasil tool lama dilipat saat estimasi konteks > 16 rb token, 3 terbaru utuh) · fix #4 ✓ (cached_tokens & reasoning_tokens dibaca + masuk log) · fix #5 ✓ (log `reasoning=Nnch` di baris `gateway selesai`) · fix #6 ✓ (budget guard 100 rb token/giliran, pesan henti jelas — diperkuat T-ML31: pre-flight sebelum panggilan gateway + penutupan putaran terbuka dengan toolresult sintesis & pesan ter-persist, lihat AUDIT-STUCK.md §9) · tool `read_lines` baru (rentang baris bernomor, maks 400 baris/16 KB) · adaptif: gateway yang menolak `stream_options` dijawab 400 → otomatis diulang sekali tanpa field · **test server 67/67 hijau** (7 test baru + test workdir sesi dari implementasi backlog yang digabung) · CI run#29 SUCCESS · APK **1.0.29-29** (vCode 291/292/293; sha256 arm64 3-way match; verifikasi 26 PASS/0 FAIL — scripts/t-ml29-verify-apk.py) · **#7 menunggu verifikasi dari perangkat owner** (pantau `tokens_used` sebelum/sesudah satu sesi percobaan).

> **Pembaruan T-ML32 (2026-11-11):** fix #6 (budget guard 100 rb token/giliran) **DIHAPUS** atas perintah owner — APInex menyediakan 20 juta token gratis/hari, jadi batas per-giliran hanya memotong run panjang yang sehat (bukti lapangan: giliran sah terhenti di ≈113.678 token). Pelacakan usage (fix #1) beserta seluruh ekonomi token lain (fix #2/#3/#4/#5, `read_lines`) TETAP berjalan; `maxTurns` 24 tetap sebagai pengaman loop. APK **1.0.34-34** (CI run#34; run#33 terpakai push docs fe519ef sesi 21 — verifikasi 28 PASS/0 FAIL dari dalam APK).
>
> **Catatan merge:** implementasi ini di-rebase di atas commit remote `eee669d` (backlog p1-p5 dari sesi terpotong: folder proyek per chat, template skema, tab chat, angka token aktual, top-bar tanpa tombol) — p2 diambil dari `formatTokenCount` versi remote, sisa gabungan bersih tanpa duplikasi.

**Permintaan owner:** "AUDIT: investigasi saat running chat session + tools calling sederhana seperti git clone (dari log) memakan token 700.000 yang tergolong sangat boros."

**Tanggal:** 2026-11-11 · **Bukti:** `upload/logcat-20261110-{081427,095603}.txt` + `upload/Screenshot_20261110-{081601,081826,095616}.jpg` · **Kode:** HEAD 1154b13 (= APK 1.0.26-26)

---

## Ringkasan eksekutif

1. **Dua sesi git-clone yang terekam log TIDAK membakar 700 ribu token.** Dari jejak lengkap yang terlihat (7 + ±6 putaran, output tool 18–351 karakter karena model rajin memakai `| tail`), kedua sesi itu hanya menghabiskan **±6 ribu token** (0,9% dari klaim) — paling keterlaluan berasumsi reasoning 3.000 token/putaran pun cuma **±45 ribu (6%)**.
2. **Tapi desain agent-nya MEMANG boros secara struktural** — dan besarannya kebetulan persis di skala klaim owner: **satu `read_file` file 256 KB di tengah sesi 15 putaran = ±705.755 token** (kirim-ulang kuadratik histori), dan **24 putaran apt-get tanpa `tail` (cap output 128 KB) = ±6,93 juta token**. Mekanisme ini nyata di kode, tinggal menunggu sesi yang "kurang beruntung" (model lupa `tail`, file besar kebaca, chat multi-pesan panjang).
3. **Aplikasi tidak punya satu pun instrumen token** — `usage` SSE tidak pernah dibaca (0 tempat di seluruh server JS), reasoning tidak diukur, dan chip kuota membulatkan ke `±20,0 jt` (granularitas ±50–100 ribu). Angka "700 ribu" owner sendiri punya ketidakpastian ±100 ribu dari pembulatan ini, dan kuota APInex berlaku **per akun** (kemungkinan ikut memuat pemakaian perangkat/key lain — perlu verifikasi dari perangkat).
4. **Verdict:** klaim "sangat boros" benar secara desain, tetapi **belum terbukti bahwa kedua sesi terekam itu sumbernya**; pemborosan 700 ribu lebih mungkin berasal dari sesi lain di luar jendela log (kedua sesi ini crash di tengah jalan, owner pasti mencoba lebih dari dua kali) atau kombinasi mekanisme §3 di bawah. Solusi pertama bukan menebak, melainkan **memasang alat ukur** (fix #1), lalu memotong pemborosan struktural (fix #2–#4).

---

## §1 Rekonstruksi bukti (dari log + screenshot)

Jam tamu proot = jam perangkat − 8 jam. Panel log server in-app (kebal pruning logcat) adalah sumber terbaik untuk insiden-1; logcat uid-scoped untuk insiden-2.

### Sesi A — device 08:10–08:12 (1.0.23, panel log di screenshot 081601/081826)

| Waktu tamu | Kejadian |
|---|---|
| ±00:10:3x | User: `git clone https://github.com/hoshiyomiX/stellar-trail` · model **deepseek-v4-flash** (APInex) |
| t1 | `cd /sdcard/coding-sachet && git clone` → gagal (git belum ada) |
| t2 | "Git belum terinstal. Saya instal dulu." → `apt-get install -y git … \| tail -5; which git` gagal |
| t3 | `apt-get update 2>&1 \| tail -5` **OK 19.306 ms, out=351ch** |
| 00:10:50 | putaran 4/24, `msgs=8`, gateway selesai 2.234 ms, `content=0ch toolCalls=1` |
| 00:10:53–00:12:01 | `apt-get install -y git …` **GAGAL 67.896 ms out=172ch** (dpkg yatim pegang lock — akar T-ML27) |
| 00:12:01–03 | putaran 5/24 `msgs=10` → `which git` GAGAL 70 ms out=18ch |
| 00:12:03–06 | putaran 6/24 `msgs=12` → apt-get lagi GAGAL 370 ms out=166ch (lock fight) |
| 00:12:06 | putaran 7/24 `msgs=14` → `dpkg --configure -a && apt-get install…` **berjalan…** |
| 00:12:07+ | **Server keluar (kode 137)** — SIGKILL eksternal saat dpkg (OOM/lmkd/OEM) |

### Sesi B — device 09:54–09:55 (1.0.25, screenshot 095616 + logcat 095603)

User: `git clone …/blob/main/skills/stellar-trail/` · tetap deepseek-v4-flash. Urutan terminal: `pwd && ls -la` selesai → `git clone` selesai → `apt-get install` selesai → 2× `dpkg --configure -a && apt-get install` berjalan → **"stream error"**. Giliran terakhir 66.181 ms (±16 perintah guest/49 dtk = maintainer-script dpkg), server mati kode 137 pukul 09:55:58, restart 2,3 dtk. Chip kuota di kedua sesi menampilkan **"Sisa token: ±20,0 j(t)"**.

**Pola penting:** `msgs = 2 × nomor putaran` (sistem+user lalu +2 per putaran: assistant tool_call + hasil tool) — konfirmasi langsung bahwa **seluruh histori dikirim ulang setiap putaran** tanpa pemangkasan.

---

## §2 Matematika token (script `scripts/t-ml28-token-math.py`, 1 token ≈ 4 karakter)

| Skenario | Prompt kumulatif | Completion | Total |
|---|---|---|---|
| **Sesi A teramati** (7 putaran, output 18–351ch) | 3.125 | 372 | **±3.497** |
| **Sesi B teramati** (±6 putaran) | 2.159 | 300 | **±2.459** |
| Kedua sesi, reasoning 1.000 tok/putaran | — | — | ±18.956 (3%) |
| Kedua sesi, reasoning 3.000 tok/putaran | — | — | ±44.956 (6%) |
| **Counterfactual A:** 24 putaran, apt-get TANPA `tail` (cap bash 128 KB × 12) | 6.932.904 | 1.200 | **±6,93 jt** |
| **Counterfactual B:** satu `read_file` 256 KB di putaran ke-5, total 15 putaran | 705.005 | 750 | **±705.755 ≈ klaim owner** |

Kesimpulan aritmetika: angka 700 ribu **tidak mungkin** berasal dari dua sesi yang terekam, tetapi **sangat mungkin** dihasilkan oleh mekanisme yang sama pada sesi yang lebih panjang / berisi file besar.

---

## §3 Mekanisme pemborosan struktural (dengan referensi kode)

1. **Kirim-ulang kuadratik histori** — `agent.js` `runAgent`: array `openai` yang terus memanjang dilempar utuh ke `callGateway` tiap putaran (loop `while (turn < config.maxTurns)`). Ditambah lintas pesan user: app mengirim seluruh histori chat tersimpan di tiap `/api/chat`, dan `maxTurns: 24` berlaku **per pesan** — chat 10 pesan bisa mencapai 240 panggilan gateway, semuanya membawa semua output tool lama. (OpenAI-compatible API memang begitu cara kerjanya — tetapi tanpa mitigasi §4, ini sumber kuadratiknya.)
2. **Output tool mentah masuk histori penuh** — `tools.js` `runBash`: cap `bashMaxOutput = 128 KB` (config.js baris 119) dan **seluruhnya** dimasukkan sebagai `role:'tool'` ke histori (agent.js baris 415); potongan 4.000 karakter hanya untuk tampilan UI (`toolend`), model tetap menerima 128 KB. `read_file` lebih liar: `readMaxBytes = 256 KB` isi file langsung ke histori. Satu file 256 KB ≈ 65 ribu token, dikirim ulang di **setiap** putaran sesudahnya.
3. **Hasil tool GAGAL ikut permanen** — percobaan apt-get yang gagal (output error panjang) tidak pernah dibuang; loop retry model (terlihat 4 percobaan apt beruntun) menumpuk salinan gagal di histori.
4. **Reasoning tak terukur & tak dibatasi** — `deepseek-v4-flash` diduga model reasoning; `turn.reasoning` diakumulasi (agent.js baris 211–218) tapi **tidak pernah di-log panjangnya** (baris log hanya `content=Nch`), dan `maxTokens` APInex = `null` (default gateway). Billing reasoning (jika dihitung APInex) tak terlihat sama sekali.
5. **Crash = bakar ulang** — kedua sesi mati kena 137 di tengah tugas (akar T-ML27); owner mengulang tugas dari nol → seluruh konteks yang sudah dibayar terbuang dua-tiga kali. Catatan maju: auto-retry 1.0.26 (P3) menambah 1 kirim ulang konteks penuh per crash (masih wajar, cap 1×).
6. **Tanpa alat ukur sama sekali** — `grep usage` di seluruh server JS: **nol**; `stream_options.include_usage` tidak pernah diminta; chip kuota `formatTokens()` (ChatScreen.kt 464–471) membulatkan ke `%.1f jt` → pembacaan "20,7 jt → 20,0 jt" menyimpan ketidakpastian ±100 ribu. Angka 700 ribu owner = selisih dua pembacaan kasar.

---

## §4 Rekomendasi fix (terurut; #1–#2 = P0)

1. **Ukur dulu (P0, sinergi backlog p2 "sisa token actual number")** — `buildBody`: tambah `stream_options: {include_usage: true}` (field ekstra OpenAI-compatible diteruskan APInex per docs mereka); parse `j.usage` di loop SSE; emit event `usage` per putaran; kumulatifkan per sesi + persist; log `[agt] usage ptok=X ctok=Y rtok=Z`; tampilkan (a) pemakaian sesi ini di header chat, (b) sisa token sebagai **angka utuh** `19.963.842` (bukan `±20,0 jt`). Bila gateway tidak mengembalikan usage: estimasi lokal chars/4 dengan penanda "±estimasi".
2. **Potong injeksi output tool (P0)** — pisahkan "capture untuk UI" vs "kiriman untuk model": UI tetap dapat sampai 128 KB, model hanya **head 2 KB + tail 2 KB + `[N baris/baris tersembunyi]`**; `read_file` untuk model dibatasi ±4 KB pertama + tool `read_lines(path, from, to)` untuk menjelajah. Ini sendirian mematikan counterfactual A & B (705 ribu → ±15 ribu).
3. **Kompaksi konteks (P1)** — saat estimasi konteks > ±16 ribu token: hasil tool lama (kecuali K terakhir) diganti satu baris `[output lama dibuang: bash exit=0, 351ch]`; pesan assistant dipertahankan.
4. **Caching prompt (P1, verifikasi dari perangkat)** — histori append-only agen itu cache-friendly; cek `usage.prompt_tokens_details.cached_tokens` pada respons (menyala otomatis bila relay mendukung prefix caching). Tidak bisa dites dari sandbox (api.apinex.bond memblokir IP datacenter).
5. **Visibilitas reasoning (P1)** — tambah `reasoning=Nnch` di baris log `gateway selesai`; opsi `reasoning_effort` sudah tersedia lewat Konfigurasi JSON untuk model yang mendukung.
6. **Batas anggaran sesi (P2)** — hentikan giliran dengan peringatan bila estimasi pemakaian sesi > 100 ribu token (setelah fix #1 ada angka nyatanya).
7. **Verifikasi akun (P0 murah, dari perangkat owner)** — pantau `tokens_used` (/v1/subscription) sebelum/sesudah satu sesi percobaan terkontrol untuk memastikan apakah kuota benar per-akun lintas perangkat; ini menutup kemungkinan pemborosan yang tidak berasal dari HP ini.

---

## §5 Batasan audit

- Jendela logcat hanya ±80 detik (insiden-2) dan baris CS insiden-1 sudah terhapus rotasi buffer — rekonstruksi sesi A bergantung panel log in-app pada screenshot (utuh, kebal pruning).
- Billing APInex per-model (apakah reasoning dihitung, ada minimum per-request, cached tokens) **tidak dapat diverifikasi dari sandbox** (Cloudflare 403 untuk IP datacenter) — ditandai sebagai asumsi; verifikasi dari perangkat nyata.
- Aktivitas lain pada akun APInex owner di luar HP ini tidak terlihat dari bukti yang ada.

---

## §6 T-ML37 — Audit boros token: run 2.471.974 tok tanpa jawaban akhir (app 1.0.39, dump 09:43)

Sumber: `upload/logcat-20261112-094328.txt` (panel log in-app 300 baris + logcat uid-scoped), chat sesi "tesoww" 09:35:13–09:42:23 (419,9 dtk), model `free/glm-5.3-flash` via APInex, 89 putaran, mati di putaran 89 (blip minimize, lihat T-ML36). Skrip rekonstruksi: `scripts/t-ml37-audit-tokens.py` (di repo sandbox stellar-trail). Angka tervalidasi ulang dengan kode (rekonsiliasi run-counter per putaran cocok persis dengan total per putaran).

### §6.1 Angka terverifikasi (jendela terlihat: putaran 49–88, 40 putaran)

| metrik | nilai |
|---|---|
| ptok per putaran | min 27.766 · rata 30.264 · maks 33.728 (drift +150 tok/putaran) |
| ctok per putaran | 62–152 (rata 78); **rtok = 98,7% dari ctok** (reasoning tersembunyi; konten nyata ≈ 0–1 tok) |
| komposisi konsumsi | **prompt = 99,74%** (ptok 1.210.557 vs ctok 3.112 pada 40 putaran terlihat) |
| total run | 2.471.974 tok = **12,4% kuota 20 M/hari** dalam satu chat 7 menit |
| cache prompt | field `cache=` **tidak pernah muncul sekali pun** di 40 putaran → §4#4 TERJAWAB: relay tidak melaporkan cached_tokens / tidak ada prefix cache yang aktif |
| estimasi vs aktual | rasio ptok/est = 1,76–1,95 (rata **1,86**) → tokenizer efektif glm pada konten kode+ID = **2,15 chars/token**, bukan 4,00 |
| tool calls (40/40 terlihat) | **semuanya bash baca**: `sed -n`, `cat -n`, `grep` — `read_lines` **tidak pernah dipakai**; total keluaran 196.742 ch; terbesar `cat -n` file utuh 15.309 ch |
| bentuk history | msgs 411→489 (+2/putaran); ≥313 pesan pertama berasal dari histori sesi SEBELUM run ini; est duduk di 15,4–17,8 K (ambang kompaksi 16 K) |
| hasil akhir | 40 putaran terlihat SEMUA `finish=tool_calls content=0ch` — **2,47 jt tok menghasilkan nol kalimat jawaban** (kerja file di disk tetap utuh) |

### §6.2 Akar boros (terbukti dari bukti, terurut kontribusi)

1. **Kalibrasi estimator meleset 1,86× (dominan)** — `contextCompactTokens=16000` dinilai `estimateTokens()` dengan asumsi 4 chars/token; tokenizer glm-5.3-flash pada konten kode+Indonesia efektifnya 2,15 chars/token. Akibat: ambang 16 K "estimasi" = **29,6 K token aktual** — konteks dibiarkan duduk di 28–34 K aktual per putaran dan dikirim ulang penuh tiap putaran. Kompaksi T-ML29 BEKERJA (est terpatri di ambang, lipatan terjadi t60/t65/t76), tapi ambangnya sendiri salah skala.
2. **Lingkar setan jendela-baca ↔ baca-ulang** — model menjelajah kode dengan `sed -n`/`cat -n` berrentang 110–160 baris (5–7 K ch) dan `cat` file utuh (15,3 K ch), tapi `forModel()` memangkas keluaran jadi head 2 K + tail 2 K → **tengah rentang yang diminta model disembunyikan** → model meminta ulang bagian lain, tiap permintaan = +1 putaran = +30 K ptok. Dilengkapi: (a) kompaksi melipat hasil baca lama jadi satu baris → model kehilangan bacaannya dan membaca ulang; (b) SYSTEM_PROMPT hanya menyebut 4 tool (`bash, read_file, write_file, list_dir`) padahal terdaftar 5 — **`read_lines` tidak pernah diperkenalkan ke model** meski marker pangkasannya sendiri menyuruh "pakai read_lines"; (c) tidak ada peta proyek → jelajah sed/cat yang berkelana.
3. **Pesan assistant lama tidak pernah dipangkas** — `compactOldTools()` hanya melipat `role:'tool'`. Tiap putaran menambah pesan assistant ber-args perintah (~150–300 ch) yang menumpuk selamanya → drift +150 tok/putaran terlihat di data; pada run panjang ini ≈ 200+ pesan assistant hidup di konteks.
4. **Histori sesi lintas-kirim tak berbatas** — run ini dimulai dengan ≥313 pesan histori sesi "tesoww" dari chat sebelumnya (msgs=411 di putaran 49); seluruhnya ikut dinilai dan dikirim setiap putaran. Bersama #3, inilah bola salju yang membuat konteks dasar sudah ~28 K sebelum run ini menambah apa pun.

Komponen tetap minor (untuk lengkap): skema 5 tool = 2.085 ch ≈ 970 tok aktual + system prompt 620 ch ≈ 290 tok ≈ 4% dari ptok per putaran.

### §6.3 Matematika & kontrafaktual (89 putaran)

- Aktual: **2.471.974 tok** (99,7% sisi prompt).
- Kontrafaktual A — hanya kalibrasi ambang (konteks duduk di ~16 K aktual): 89 × 16 K ≈ **1.424.000 tok (−42%)**. Perubahan satu baris di `estimateTokens()` (divisor 4 → 2) + penyesuaian test.
- Kontrafaktual B — A + setengah ronde baca (read_lines dipakai, tidak ada pangkasan tengah yang memaksa baca ulang): 45 × 16 K ≈ **720.000 tok (−71%)**.
- Catatan caching: tanpa prefix cache (§6.1), satu-satunya tuas adalah mengecilkan konteks; bila suatu saat relay mengaktifkan caching, lipatan yang MEMUTASI pesan lama justru menggagalkan cache prefix — kalibrasi A (ambang turun) memperkecil frekuensi lipatan dan tetap kompatibel.

### §6.4 Rekomendasi (menunggu order owner; tidak ada yang otomatis diimplementasi T-ML37)

1. **(P0) Kalibrasi estimator**: `estimateTokens()` divisor 4 → **2** (konservatif vs 2,15 terukur) — ambang 16 K estimasi ≈ 16 K aktual. Efek −42%/run seperti kontrafaktual A. Satu baris + test.
2. **(P0) Putus lingkar baca-ulang**: (a) sebut `read_lines` di SYSTEM_PROMPT ChatEngine (5 tool, bukan 4); (b) naikkan `contextCompactKeep` 3 → 6 untuk hasil baca, ATAU lipat dengan ringkasan 1–2 baris ("file X baris 90–175 sudah dibaca") alih-alih satu baris generik; (c) opsional: baris peta proyek (output `list_dir` ringkas) di system prompt sesi berproyek.
3. **(P1) Lipat pesan assistant ber-tool_calls lama** jadi satu baris (mis. `[putaran N: bash sed …]`) — mematikan drift +150 tok/putaran.
4. **(P1) Riset prefix-cache APInex** dari perangkat (dokumentasi relay; uji `cached_tokens` pada model lain) — bila aktif, desain lipatan harus append-only-friendly.
5. **(P2) Batas histori sesi lintas-kirim**: ringkas/ pangkas histori sesi lama saat wire history melewati ambang (residu 313 pesan sesi "tesoww" ikut terbayar tiap putaran run baru).

### §6.5 Status rekomendasi §4 lama (diverifikasi lapangan oleh dump ini)

- §4#1 usage tracking + angka utuh: **HIDUP** (`[agt] usage` + meter sesi terlihat di log).
- §4#2 jendela head/tail + read_lines: **HIDUP** (marker pangkasan muncul di beban model) — tapi lihat §6.2#2: read_lines tidak pernah DIPAKAI model.
- §4#3 kompaksi: **HIDUP** (est terpatri di ambang) — tapi ambang salah skala (§6.2#1).
- §4#4 caching: **TERJAWAB — tidak ada** (cache= nol kali di 40 putaran).
- §4#5 reasoning=Nnch: **HIDUP** (`reasoning=0nch` di baris gateway selesai).
- §4#6 budget guard: **DIHAPUS owner** (T-ML32) — dicatat sebagai keputusan, bukan utang.

### §6.6 Status tindak lanjut (T-ML39, owner order sesi 27 — 2026-11-14)

Owner: "Hasil coding dari APK Coding-Sachet tidak optimal & aimless, karena tidak memiliki workflow, protocol, harness, dll." — jawaban klarifikasi: implement langsung; pain utama aimless + tanpa verifikasi + boros token; harness = gerbang sintaks; protocol = TODO file; budget prompt bebas.

- **R1 KALIBRASI ESTIMATOR: SELESAI** — `estimateTokens()` divisor 4 → 2 (agent.js); ambang kompaksi 16K estimasi kini ≈ 16K token nyata (dulunya ≈ 29,6K). Kontrafaktual A: −42%/run.
- **R2a read_lines DI PROMPT: SELESAI** — SYSTEM_PROMPT ChatEngine menyebut 5 tool lengkap + arahan eksplisit "baca file dengan read_file / read_lines (bukan cat/sed via bash)"; 3 skema persona ikut diperbarui (menulis kini juga menyebut roster lengkap).
- **R2b contextCompactKeep: SELESAI** — 3 → 6 (config.js): hasil baca bertahan 2× lebih lama di konteks, memutus lingkar baca-ulang (§6.2#2).
- **TAMBAHAN T-ML39 (di luar R1–R5, order sesi 27): TODO-file protocol + syntax harness** — RENCANA.md bercentang diajarkan di SYSTEM_PROMPT untuk kerja multi-langkah; enforcement di agent.js (todo-nudge: jawaban akhir ditolak selama ada langkah belum tercentang, pengingat wire-only maks 2× — model keras kepala tak pernah mengunci run, batas 15 RPM tetap satu-satunya limit); kontrak jawaban akhir (apa yang dikerjakan / file yang disentuh / cara verifikasi); gerbang sintaks otomatis pasca write_file (node --check .js/.mjs/.cjs, bash -n .sh/.bash, JSON in-process; file rusak = tool GAGAL + instruksi perbaiki-dulu).
- **R3 / R4 / R5: TETAP MENUNGGU ORDER OWNER** (lihat §6.4).
- **Verifikasi**: test server 83/83 (76 basis + 7 baru; KOREKSI KEDUA kelas hitungan: "75/75" worklog T-ML37 aktualnya 76 — runner menghitung entri file `test/mock-gateway.js` karena `node --test` mengeksekusi semua file di dalam direktori test/, selisih +1 historis kini dipecahkan) · app unit test 25 (+1 kontrak prompt) · CI run#42 gagal di unit test (skema menulis tanpa roster — diperbaiki run#43) · **run#43 SUCCESS → APK 1.0.43-43, t-ml39-verify-apk.py 63 PASS/0 FAIL** (sha 3-way, badging 431, signer V2, 6 file server APK==repo byte-identical, literal baru lengkap, prompt 4-tool lama ABSEN dari dex, regresi T-ML37/34/33/32 + rootfs utuh).
