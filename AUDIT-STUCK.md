# AUDIT STUCK — T-ML30

**Permintaan owner:** "Baca & analisa log, chat session stuck lagi"

**Tanggal:** 2026-11-11 · **Bukti:** `upload/logcat-2026-11-11-110055.txt` (dump 11:00:55 perangkat; panel log server in-app 132 baris — kebal pruning — + logcat uid-scoped) · **Kode:** HEAD `1def292` = **APK 1.0.30-30** di perangkat (build 301; CI run#30 = push commit status T-ML29, `head_sha` terkonfirmasi via GitHub API — perangkat menjalankan kode yang byte-sama dengan repo lokal, tidak ada kode tak dikenal).

---

## Ringkasan eksekutif

1. **Dua kematian server dalam ±3 menit** (±10:58:30 dan ±10:59:37–11:00:21 waktu perangkat), **keduanya persis saat `dpkg --configure -a` sedang berjalan**. Geometri kill-nya BERBEDA dari insiden T-ML27: kali ini hanya node (guest vpid 1) yang di-SIGKILL — proot selamat cukup lama untuk mencetak `proot info: vpid 1: terminated with signal 9` lalu exit **kode 0** (bukan 137), dan proses app SELAMAT (pid 1652 konstan sebelum-sesudah kill #2 di logcat). Pembunuh menarget satu proses tunggal — kandidat terkuat kernel OOM killer / manajer memori OEM; identitas pastinya tak terlihat dari dump uid-scoped (§7).
2. **Rantai pemicunya panjang dan semuanya terekam:** rootfs ubuntu-base murni **tanpa git** → model terpaksa `apt install git` → timeout 120 s + perpindahan jaringan (DNS berubah 10:56:07) → dpkg ditinggalkan setengah terkonfigurasi → model (dengan benar) menjalankan `dpkg --configure -a` → maintainer scripts menaikkan tekanan memori di bawah proot → node dibunuh di tengah giliran.
3. **Bug baru yang mengubah "server mati" menjadi "sesi stuck" (ini jawaban utamanya):** server SELALU mengirim event `done` sebelum menutup stream (server.js:338/395; agent.js:485/502/555), tetapi client **tidak pernah melacak event itu** — `handle()` tidak punya case `"done"`. Kematian node menghasilkan FIN TCP yang bersih → `readLine()` mengembalikan `null` → `onClosed()` — **BUKAN** `onError(EOF)` (AgentApi.kt:352; onError hanya hidup lewat exception). `finish()` lalu melihat "sudah ada event, tanpa error" → dianggap **"giliran selesai bersih"** → `live = null`: tanpa auto-retry T-ML27 P3 (path itu hanya bisa dicapai lewat onError), tanpa bubble error. Pesan assistant dengan `toolCalls: dpkg --configure -a` sudah ter-persist tanpa hasil → ToolCard merender `ok=null` → **label "berjalan…" selamanya**. Itulah stuck yang dilihat owner — kartu tool berputar abadi, tanpa pesan salah, tanpa kelanjutan.
4. **Dua HTTP 402 APInex sebelum kill #1 bukan bug app:** `free/glm-5.3-flashx` memang `allowFree: false` di katalog publik (butuh langganan — terkonfirmasi live dari katalog), dan `free/deepseek-v4.1-flash` (`allowFree: true`) menolak karena **check-in harian** belum dilakukan di apinex.bond/overview. Keduanya tersampaikan ke UI sebagai error yang jelas.
5. **Kabar baik — T-ML29 terverifikasi hidup di perangkat:** 7 baris `[agt] usage ptok=… run=…` (nemotron 4 putaran = 5.460 tok; deepseek 3 putaran = 5.041 tok), `reasoning=Nnch` terisi, dan restart fast-path T-ML27 bekerja 2× (gap ≤17 s dan ≤44 s).

**Cara menghidupkan sesi yang stuck hari ini (tanpa menunggu fix):** kirim pesan baru apa pun di sesi itu — histori putaran terputus dilipat/di-heal otomatis sisi server (`toOpenAiMessages`, agent.js:280–282). Perhatian: dpkg yatim dari sesi mati kemungkinan besar sudah menyelesaikan konfigurasinya sendiri (prosesnya terpisah pgid dan tetap hidup melewati kematian node) — `which git` bisa jadi sekarang sudah benar.

---

## §1 Rekonstruksi kejadian (jam perangkat = jam tamu + 8 j)

Panel log server in-app (132 baris terakhir, utuh) + logcat uid-scoped. Baris kill tidak berstempel waktu — gap dinyatakan sebagai batas atas.

| Waktu | Kejadian |
|---|---|
| 10:55:01 | Server #1 up (1.0.30, build 301) · provider apinex + nvidia |
| 10:55:24–31 | Verify nvidia 84 model (sweep latar) |
| 10:55:54 | **Chat #1** nemotron-3-ultra, proyek `tes`: putaran 1/24 `git clone` → GAGAL 247 ms `out=51ch` (**git tidak ada di rootfs**) |
| 10:55:55 | putaran 2/24 → `apt update && apt install -y git` berjalan… |
| 10:56:07 | **Blip jaringan**: EAI_AGAIN + ECONNRESET kedua provider, resolv.conf berubah (10.10.30.1 → fd00:aa:bb:100d::28, 192.168.143.30) — perangkat pindah jaringan; apt macet menunggu |
| 10:56:08 | verify nvidia gagal total 43.770 ms (ikut blip); pulih 10:56:10 |
| 10:57:57 | `bash timeout 120s — proses dibunuh` · GAGAL 120.041 ms `out=1107ch` — dpkg ditinggalkan setengah terkonfigurasi |
| 10:58:04 | putaran 3/24 selesai · `apt install -y git` → GAGAL 113 ms `out=185ch` (dpkg interrupted) |
| 10:58:06 | putaran 4/24 → **`dpkg --configure -a` berjalan** (tidak pernah selesai tercatat) |
| 10:58:10–29 | User buka chat baru proyek `testing`: `free/glm-5.3-flashx` → **HTTP 402** (subscription) · `free/deepseek-v4.1-flash` → **HTTP 402** (daily check-in) |
| ±10:58:30–46 | **KILL #1**: `proot info: vpid 1: terminated with signal 9` + `Server keluar (kode 0)` — node mati saat `dpkg --configure -a`; sesi nemotron putus diam-diam |
| 10:58:46 | Server #2 up (restart fast-path, gap ≤17 s) |
| 10:59:23 | **Chat #3** deepseek-v4.1-flash, proyek `testing` (msgs=3 — retry user setelah 402): putaran 1 `git clone` GAGAL 47 ms `out=51ch` (git tetap tidak ada) |
| 10:59:26 | putaran 2 → `apt-get update && apt-get install -y git` GAGAL 9.274 ms `out=1076ch` (dpkg interrupted lagi — sisa kill #1) |
| 10:59:37 | putaran 3/24 → **`dpkg --configure -a` berjalan** — baris terakhir panel |
| ±10:59:37–11:00:21 | **KILL #2**: tanda tangan sama persis |
| 11:00:21 | Server #3 up (gap ≤44 s — lambat; yatim apt/dpkg masih mengunyah CPU/IO, lihat §2) |
| 11:00:23–55 | Logcat: app hidup & responsif (pid 1652 konstan), user scroll/tap/back. **Tidak ada `chat mulai` baru** — sesi tidak pernah lanjut; dump dibuat 11:00:55 |

Pemakaian token tercatat (instrumen T-ML29): nemotron run=**5.460** (4 putaran, ptok 1053→1591), deepseek run=**5.041** (3 putaran, ptok 1456→1924). Kedua sesi mati di tengah putaran ke-4/ke-3 dari maks 24.

## §2 Geometri kill — apa yang berubah dari T-ML27

| | T-ML27 (1.0.23/1.0.25) | Hari ini (1.0.30) |
|---|---|---|
| Baris app | `Server keluar (kode 137)` | `vpid 1: terminated with signal 9` + `Server keluar (kode 0)` |
| Siapa mati | seluruh tree (proot+node) | **hanya node**; proot hidup cukup lama mencetak info lalu exit 0 |
| Proses app | (tak terverifikasi saat itu) | **selamat** — pid 1652 sama sebelum & sesudah kill #2 (logcat 10:59:14 ↔ 11:00:23) |
| Interpretasi | SIGKILL ke group (lmkd/killpg) | SIGKILL ke **satu proses** — kernel OOM killer memilih korban individual (node = proses paling gemuk saat dpkg menaikkan tekanan) atau lmkd OEM per-proses |
| Saat kejadian | apt-get/dpkg berjalan | `dpkg --configure -a` berjalan — **2/2 hari ini, 4/4 total insiden riil** |

Konsekuensi praktis dari exit 0: `exitCodeNote(0)` kosong — baris "Server keluar (kode 0)" tampak seperti berhenti normal, tanpa petunjuk "dimatikan paksa oleh sistem" yang dulu muncul di 137 (fix P1-5 di §6).

**Mengapa restart #3 butuh ≤44 s** padahal backoff-nya cuma 1,5 s: tool bash T-ML27 sengaja memisahkan child ke process group sendiri (agar bisa di-kill saat timeout) — tetapi saat NODE-nya yang mati, yatim apt/dpkg itu **tetap hidup** dan terus menyita CPU/IO di HP saat proot boot ulang. Ini trade-off yang terasa hari ini, bukan regresi.

## §3 Akar masalah berlapis (dari kode ke layar)

1. **Lapis 0 — rootfs tanpa git.** CI memakai ubuntu-base 24.04.5 pristine (repack xz) — tidak ada `git`. Bukti: dua sesi berbeda sama-sama `git clone` gagal instan `out=51ch` ("command not found"). Semua insiden kill yang pernah tercatat (T-ML27 ×2, T-ML28 ×2, hari ini ×2) berawal dari **apt-untuk-git**.
2. **Lapis 1 — timeout 120 s terlalu pendek untuk apt di proot.** `config.js:118` `bashTimeoutMs: 120000`. apt di atas proot+HP+jaringan gonta-ganti memakan >120 s → dibunuh → dpkg setengah terkonfigurasi (kondisi yang menggoda model untuk memperbaiki).
3. **Lapis 2 — `dpkg --configure -a` = puncak memori.** Maintainer scripts (perl, ldconfig, update-alternatives) di bawah proot menaikkan tekanan memori persis saat node sedang memegang histori sesi → korban OOM individual = node.
4. **Lapis 3 — bug client: FIN bersih tak terdeteksi (penyebab "stuck").** Rincian §4. Turn yang seharusnya auto-retry (T-ML27 P3) malah dianggap "selesai bersih".
5. **Lapis 4 — visual abadi.** `ChatScreen.kt:665-676`: pesan ter-persist dengan `toolCalls` dirender `ToolCard(output=null, ok=null)`; `ToolCard.kt:108`: `null → "berjalan…"`. Tanpa toolresult, kartu `dpkg --configure -a` berputar selamanya — **termasuk setelah app direstart**, karena pesannya sudah tersimpan di sesi.

Catatan margin: pemulihan `awaitServerRecovery` 45 s vs realita restart ≤44 s — nyaris tidak muat; dan kali ini jalannya bahkan tidak pernah dipakai karena bug lapis 3.

## §4 Bukti kode (HEAD 1def292)

```
agent.js:485,502,555 + server.js:338,395   → setiap terminasi yang dirancang SELALU emit {type:'done'} lalu res.end()
AgentApi.kt:352                            → val line = reader.readLine() ?: break     // FIN node = null, bukan exception
AgentApi.kt:360-372                        → onError(EOF) HANYA lewat catch exception; null jatuh ke onClosed()
ChatEngine.kt:189-269                      → handle(): TIDAK ADA case "done" (event dibuang diam-diam)
ChatEngine.kt:327-361                      → finish(): !receivedAnything → recovery; receivedAnything+!error → "giliran selesai bersih", live=null
ChatScreen.kt:665-676 + ToolCard.kt:108    → toolCalls tanpa hasil → ok=null → label "berjalan…" permanen
tools.js:130-213                           → detached pgid (desain T-ML27); yatim selamat dari kematian node
config.js:118                              → bashTimeoutMs 120 s
CI workflow                                → ubuntu-base pristine; tidak ada git yang dibakar
```

Mengapa P3 T-ML27 dulu terbukti dan sekarang tidak: insiden 1.0.25 menghasilkan `EOFException` (pembacaan terpotong di tengah baris → exception → onError(EOF) → retry jalan). Kematian node yang bersih hari ini menghasilkan FIN di batas event → `readLine()` null → cabang yang tidak punya penjaga. Beda rasa EOF, beda nasib.

## §5 Temuan samping

- **est= adalah bobot pesan saja** (`estimateTokens(messages)`, agent.js:127): est=173 vs ptok aktual 1053 — skema 5 tools (±870 tok) tidak dihitung. Cukup sebagai heuristik debug; jangan dipakai untuk billing.
- **APInex tidak mengembalikan `cached_tokens`** (field `cache=` absen di baris usage — memang hanya dicetak bila ada, agent.js:479); NVIDIA mengembalikan `cache=0` (belum ada prefix-cache yang kena). Pemeriksaan T-ML28 fix#4 bekerja.
- **deepseek-v4.1-flash di APInex menghitung hampir semua completion sebagai reasoning** (rtok 51/52, 34/35, 30/31) padahal `reasoning=0nch` di stream — reasoning token ter-billing tanpa konten reasoning yang mengalir. Catatan harga per model, bukan bug.
- **Blip jaringan 10:56:07** (DNS berubah dua kali dalam sesi) ikut mematikan apt pertama dan sweep verify 43,7 s — jaringan HP berpindah saat sesi berjalan; retry net.js sudah menangani provider, tapi apt tidak punya retry.
- **msgs=1→2→3** pada chat #3 memperlihatkan histori penuh dikirim ulang tiap `/api/chat` (desain dikenal dari AUDIT-TOKEN; angka di sini kecil, kompaksi belum terpicu).

## §6 Rencana fix — usulan T-ML31 (menunggu perintah owner)

| # | Prioritas | Perubahan | Efek |
|---|---|---|---|
| P0-1 | app, inti | ChatEngine: lacak `done` — case `"done"` di `handle()` set `receivedDone=true` (reset di `send()` dan setiap `startStream` ulang, termasuk jalur retry onError); `finish()`: bila `!receivedDone && receivedAnything && !turnCancelled` → masuk path server-death yang sama (retry 1× via `awaitServerRecovery`, gagal → bubble error "Server agent mati saat memproses…") | Menutup lubang FIN-bersih; turn otomatis lanjut setelah restart |
| P0-2 | app, 1 baris | `awaitServerRecovery` 45 s → **90 s** | Bukti lapangan: restart aktual ≤44 s |
| P0-3 | CI/rootfs | Bakar `git` ke rootfs saat build (ekstrak `.deb` target-arch ke dalam tree — `dpkg -x`, tanpa maintainer script; git jalan tanpa configure; ~+35-40 MB/pass) atau chroot+qemu bila mau konfigurasi penuh | Menghapus SELURUH kelas kegagalan apt-untuk-git (6/6 insiden kill berawal di sini) |
| P1-4 | server | Timeout adaptif di tools.js: perintah berprefix `apt`/`apt-get`/`dpkg` → 600 s; lainnya tetap 120 s | apt tidak lagi selalu gagal di 120 s |
| P1-5 | app | Kenali string `terminated with signal 9` di stdout server → tambahkan note pada "Server keluar (kode 0)" (kode 0 polos menyesatkan) | Diagnosa insiden berikutnya instan |
| P2-6 | app | Badge model berbayar (`allowFree=false` dari katalog) di picker + hint spesifik untuk 402 langganan/check-in | User tidak memilih model berbayar tanpa sadar |
| P2-7 | app | Saat memuat sesi lama: ToolCard `ok=null` yang tak sedang berjalan dirender "terputus", bukan "berjalan…" | Visual stuck palsu hilang dari sesi lama |

**Verifikasi berikutnya (dari perangkat):** (1) `adb shell pm grant id.codingsachet.mobile android.permission.READ_LOGS` SEKALI dari PC — dump berikutnya otomatis memuat baris kernel/lmkd, identitas pembunuh node akhirnya terlihat (konvensi T-ML25); (2) setelah P0-3, `git clone` harus langsung jalan tanpa apt sama sekali; (3) setelah P0-1, matikan server secara paksa di tengah turn (mis. `kill -9` dari adb pada pid node) → sesi harus lanjut sendiri dalam ≤90 s.

## §7 Batas bukti

- **Identitas pembunuh (OOM kernel vs lmkd OEM) tidak bisa dipastikan** dari dump uid-scoped — baris kernel tidak ikut (pelajaran T-ML27). Yang BISA dipastikan: sinyal 9, korban tunggal node, app selamat, korelasi 2/2 dengan dpkg.
- Logcat utama ter-rotasi tepat di jendela kill #2 (buffer `main` mulai 11:00:23) — baris tag `CS` app 10:59:37–11:00:23 hilang; panel log in-app (yang ter-embed di dump) menutup celerah ini dan menjadi sumber utama rekonstruksi.
- Baris kill tidak berstempel waktu → gap restart dilaporkan sebagai batas atas.
- Waktu tamu proot = perangkat −8 j (keterlacakan jam yang sudah diketahui); semua jam di §1 sudah dikonversi ke waktu perangkat.

---

# ADDENDUM — dump kedua (11:57:13) & implementasi T-ML31

**Permintaan owner:** "Analisa log, lalu temukan & fix session stuck" · dump `upload/logcat-2026-11-11-115713.txt` (app masih 1.0.30-30/301).

## §8 Temuan dump kedua — mode stuck KEDUA: budget guard

Jendela 11:07–11:57 **tidak memuat satu pun kill server** (server hidup terus; quota dijawab 11:56:26). Empat chat berjalan; chat #4 (11:37:35, proyek `tesoww`, 11 putaran eksplorasi kode) berhenti oleh **budget guard T-ML29**:

```
03:38:08 gateway selesai 10177ms content=0ch toolCalls=1 finish=tool_calls
03:38:08 usage ptok=14739 ctok=124 rtok=123 total=14863 run=105337
03:38:08 [WRN] budget sesi 100000 token terlampaui (≈105337) — giliran dihentikan
03:38:08 chat selesai turns=11 msgs+23 32662ms
```

Rantai "stuck" yang dilihat owner 19 menit (11:38 → 11:57, tanpa `chat mulai` baru):

1. Guard T-ML29 berhenti **setelah** menjawab putaran-11 yang meminta tool → pesan assistant `toolCalls` **ter-persist tanpa hasil** (bukti `msgs+23` = 22 + 1 gantung) → ToolCard `ok=null` merender **"berjalan…" abadi** — termasuk setelah app direstart (pesan sudah tersimpan di sesi).
2. Penjelasan berhenti hanya lewat **bubble error transient** (hilang saat navigasi/restart; tidak masuk histori).
3. Panggilan gateway putaran-11 (10,2 dtk + 14.863 token) **terbuang** — pre-flight `run 90.474 + est 11.173 = 101.647 > 100.000` akan mencegahnya.

Sekaligus terkonfirmasi dari dump yang sama: **T-ML29 hidup di perangkat** — 33 baris `[agt] usage ptok=… run=…`, `read_lines` dipakai model, kompaksi menjaga ptok (max 14,7 rb di run 105 rb token), dan `dpkg --configure -a` di chat #1 selesai sendiri tanpa kill (56,2 dtk — beruntung, kelas risikonya tetap ada).

## §9 Status implementasi T-ML31 (2026-11-11, APK 1.0.32-32)

| # | Perubahan | Status |
|---|---|---|
| P0-1 | ChatEngine: lacak event `done` — FIN bersih tanpa done = kematian server → jalur pemulihan yang sama dengan EOF (retry 1× `awaitServerRecovery`, gagal → bubble error); `receivedDone` di-reset per percobaan stream | ✅ |
| P0-2 | `awaitServerRecovery` 45 dtk → **90 dtk** (bukti lapangan restart ≤44 dtk + yatim apt mengunyah CPU) | ✅ |
| P0-3 | **git dibakar ke rootfs** di CI: closure 24 paket (git 2.43 + libcurl-gnutls + krb5 + perl) di-RESOLVE apt dengan `Dir`-override terhadap dpkg status rootfs itu sendiri (tanpa chroot/qemu), `dpkg-deb -x --root-owner-group` tanpa maintainer script; keyring dari rootfs; verifikasi `/usr/bin/git` dari dalam APK jadi; dry-run arm64 lokal 100% sukses; delta asset **+12,6 MB xz/APK** | ✅ |
| P1-4 | tools.js: timeout adaptif — perintah berkata `apt`/`apt-get`/`dpkg` (di mana pun di perintah majemuk) → **600 dtk**; lainnya tetap 120 dtk | ✅ |
| P1-5 | RuntimeController: baris proot `terminated with signal` di stdout → note "node dibunuh paksa sinyal 9 … walau proot keluar 0" pada "Server keluar (kode 0)"; reader diberi join terbatas 250 ms sebelum cancel agar baris terakhir tak hilang | ✅ |
| P2-7 | ToolCard yatim (tanpa hasil & tidak sedang berjalan) merender **"terputus…"** (warna redup), bukan "berjalan…" — sesi lama & pasca-restart bersih dari spinner palsu | ✅ |
| BARU-a | agent.js **budget closure**: guard post-hoc mensintesis `tool`+`toolend(ok=false)`+`toolresult` utk panggilan tertunda + pesan penjelasan **ter-persist** + `done` (bukan error transient); maxTurns juga kini pesan ter-persist | ✅ |
| BARU-b | agent.js **pre-flight**: `usedTokens + est×1,25 + min(1000, 10% budget)` > batas → berhenti SEBELUM memanggil gateway (putaran & token tak lagi terbuang) | ✅ |
| P2-6 | Badge model berbayar (allowFree=false) di picker | ⏸ ditunda — bukan kelas stuck |

Test server **68/68** (3 baru: closure post-hoc, pre-flight, maxTurns) · CI run#31 SUCCESS tapi asset rootfs 75 MB (apt 2.7 runner menulis `pkgcache.bin`+`srcpkgcache.bin` ±99 MB ×2 di bawah `Dir::Cache` — tak terlihat di dry-run lokal karena apt 3.0 sandbox tak menulisnya) → run#32 purge `var/cache/apt/*` → asset bersih 31,5 MB · verifikasi APK **32 PASS/0 FAIL** (sha256 3-way, badging 1.0.32 vCode 321, signer V2, agent/tools/config.js APK==repo, literal dex+js lengkap, **/usr/bin/git + git-core + libcurl-gnutls terbukti di dalam asset rootfs APK**).

Verifikasi dari perangkat yang menunggu: (1) `git clone` langsung jalan tanpa apt; (2) matikan paksa server di tengah turn → sesi lanjut sendiri ≤90 dtk; (3) sesi yang menabrak budget menampilkan pesan "Giliran dihentikan oleh pengaman budget…" dan kartu tool-nya berhenti (tidak berputar); (4) sesi lama yang dulu berputar "berjalan…" kini tampak "terputus…".

## §10 Addendum T-ML32 — budget guard dihapus (2026-11-11, APK 1.0.34-34)

Atas perintah owner ("Hapus pengaman budget karena tidak perlu. APInex sudah menyediakan token gratis sebanyak 20.000.000 token/day"), seluruh mekanisme budget guard dilepas dari agent: leg pre-flight, leg post-hoc, closure sintesis `stopForBudget`, `estimateCompletionTokens`, dan konstanta `sessionBudgetTokens` di config — run yang sah tidak lagi berhenti di ≈100 rb token/giliran. Yang TETAP: pelacakan usage + meter UI (event `usage` per putaran), pemangkasan `forModel`, kompaksi konteks, `maxTurns` 24, done-tracking jalur mati-server (recoverOrFail 90 dtk), dan kartu yatim "terputus…". Konsekuensi pada poin verifikasi perangkat (3) di §9: tidak lagi relevan — tidak ada batas budget yang bisa ditabrak; sisa poinnya tetap berlaku.

## §11 Addendum T-ML33 — crash ketiga: "terminated" mid-stream + server exit(1) (2026-11-11, APK 1.0.36-36)

Bukti: `upload/logcat-20261111-144405.txt` (app **1.0.35 build 351** = run#35 df274f4). Chat #2 (14:42:58, proyek tesoww, msgs=83) berjalan sehat 12 putaran (est stabil ±15,7 rb tok — kompaksi T-ML29 bekerja), lalu **putaran 13** (mulai 06:43:44,9) mati ±8 dtk kemudian: `chat gagal … 54861ms: terminated` → `unhandled: terminated` → **Server keluar (kode 1)** → fast-path restart 2 dtk (T-ML27 bekerja, sesi utuh). Giliran mati; est token putaran-13 (±15,7 rb) terbuang.

**Konteks jaringan** (jalur VPN aktif): `resolv.conf diperbarui` 06:42:13 — 45 dtk sebelum chat #2, plus 2× `connect retry UND_ERR_SOCKET` (06:28:38, 06:43:24) pada jendela yang sama — jalur ke api.apinex.bond memang fluktuatif hari itu.

**Akar teknis** (dari kode, HEAD df274f4): socket terputus di tengah stream SSE → undici melempar `TypeError: terminated` dari `for await (chunk of res.body)` → tertangkap handler chat (`chat gagal … terminated`, bubble error ke app — sampai sini perilaku benar). NAMUN `finally { try { res.body.cancel() } catch {} }` di agent.js: **`cancel()` pada stream yang SUDAH errored mengembalikan promise yang REJECT dengan error tersimpan** (spesifikasi WHATWG Streams) — try/catch di sekitar panggilannya tidak bisa menangkapnya karena penolakan terjadi asinkron → promise mengambang → `unhandledRejection` → handler server.js langsung `process.exit(1)`. Satu blip jaringan transient = seluruh server mati. Dua baris error pada detik yang sama di log (yang satu tertangkap, yang satu "unhandled", sama-sama berpesan `terminated`) adalah sidik jari mekanisme ini.

**Fix T-ML33** (72/72 test, 4 baru):

| # | Perubahan | Efek |
|---|---|---|
| F1 | `cancelQuietly()` — semua pembersihan body/reader (`callGatewayOnce`, `readUntilAlive`) memasang `.catch` pada promise `cancel()` | Regresi promise mengambang tertutup; tidak ada lagi `unhandledRejection` dari pembersihan stream |
| F2 | Catch khusus SSE mid-stream: `[DONE]` sudah terlihat → putaran tetap dipakai; user-stop → `dibatalkan`; error gateway (`j.error`, diberi tag) diteruskan apa adanya; **belum ada output terlihat** → tag `streamRetry`; sudah ada teks/reasoning tampil → pesan ramah "Koneksi ke X terputus di tengah balasan … kirim pesan lagi" | Error "terminated" polos tak lagi muncul; kasus parsial jelas bagi user |
| F3 | Wrapper `callGateway` (sekali-jalan `callGatewayOnce`): `streamRetry` → **1× retry transparan** lewat rate-limiter (permintaan nyata), hint `netretry` ke UI, jeda 1 dtk abortable | Potongan stream SEBELUM data apa pun sembuh sendiri — user lihat hint sambung-ulang, bukan giliran mati. Aman karena fragmen tool-call tidak pernah di-emit per-delta (hanya utuh setelah putaran selesai) |
| F4 | server.js: `unhandledRejection` tidak lagi langsung exit — dicatat + **selamat**; exit(1) hanya bila ledakan ≥5 dalam 60 dtk (`makeUnhandledPolicy`, pure + unit test) | Blip transient tak lagi membunuh server; proses benar-benar rusak tetap dapat restart bersih via supervisor |
| F5 | Handler level-proses dipasang sekali per proses (guard) | Suite test yang memanggil `start()` berulang tak menumpuk listener |

Sekaligus terverifikasi di lapangan dari dump yang sama: **T-ML32 hidup** — chat #2 berjalan sampai 240.947 token run tanpa berhenti di batas 100 rb (guard sudah tidak ada), `usage ptok/ctok/run` + meter sesi tetap jalan; T-ML29 (kompaksi, est stabil ±15 rb pada msgs=108) juga terkonfirmasi.
