# AUDIT TOKEN — T-ML28

> **Status implementasi (T-ML29, 2026-11-11):** fix #1 ✓ (stream_options.include_usage + parse chunk usage + event `usage` per putaran + log `ptok/ctok/rtok/cache` + chip "Pemakaian sesi" + sisa token ANGKA UTUH — menyolve backlog p2) · fix #2 ✓ (model hanya terima head 2 KB + tail 2 KB output tool via `forModel`; histori app/UI tetap lengkap; berlaku juga pada histori lama yang dikirim ulang) · fix #3 ✓ (kompaksi: hasil tool lama dilipat saat estimasi konteks > 16 rb token, 3 terbaru utuh) · fix #4 ✓ (cached_tokens & reasoning_tokens dibaca + masuk log) · fix #5 ✓ (log `reasoning=Nnch` di baris `gateway selesai`) · fix #6 ✓ (budget guard 100 rb token/giliran, pesan henti jelas) · tool `read_lines` baru (rentang baris bernomor, maks 400 baris/16 KB) · adaptif: gateway yang menolak `stream_options` dijawab 400 → otomatis diulang sekali tanpa field · test server 57→65 hijau · **#7 menunggu verifikasi dari perangkat owner** (pantau `tokens_used` sebelum/sesudah satu sesi percobaan).

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
