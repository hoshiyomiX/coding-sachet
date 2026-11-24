# MIGRATION.md — Migrasi UI Material3 → Official miuix

> Status: **diimplementasikan di branch `feature/miuix`** · Target: `top.yukonga.miuix.kmp:miuix-ui:0.9.4` + `miuix-preference:0.9.4` ([compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix), Apache-2.0)
> Keputusan owner (2026-11-24): arsitektur **bertahap** (M3 boleh hidup berdampingan selama transisi), eksekusi **serentak di satu branch**, gap komponen pakai **padanan terdekat**, theming **ikut sistem** (light = default miuix, dark = skema lama dipetakan).

## 1. Keputusan versi (catatan penting)

Sesi klarifikasi memilih "0.8.8 (rilis terbaru)". Informasi itu ternyata **usang**: 0.8.8 adalah rilis terakhir **artefak lama** `top.yukonga.miuix.kmp:miuix` (Maret 2026). Sejak 0.9.0 official miuix memecah artefak menjadi modul (`miuix-ui`, `miuix-preference`, `miuix-icons`, dst.) dan rilis stabil terkini adalah **0.9.4** (metadata Maven Central, `miuix-ui`). Karena niat pemilihan adalah "rilis terbaru official", branch ini memakai **0.9.4**. Bila ingin kembali ke 0.8.8: ganti koordinat ke `top.yukonga.miuix.kmp:miuix:0.8.8` dan sesuaikan nama API (era 0.8: `SuperDialog`→`OverlayDialog`, `SuperSwitch`→`SwitchPreference`, dll.).

Kompatibilitas yang sudah diverifikasi terhadap source tag `v0.9.4`:
- Kotlin **2.4.20** (persis sama dengan toolchain repo ini)
- minSdk library **24** ≤ app minSdk 26
- Compose Multiplatform 1.12.0 (kompilabel dengan androidx Compose BOM 2026.09.00 via artifact Android)

## 2. Strategi bertahap

| Fase | Isi | Bisa di-merge mandiri? |
|---|---|---|
| 0 | Tambah dependensi miuix **berdampingan** dengan M3, `Theme.kt` baru (`MiuixTheme`), ikon vektor baru di `Icons.kt` | Ya — M3 tetap jalan |
| 1 | Migrasi per layar: `AppRoot` → `HomeScreen` → `OnboardingScreen` → `FilesScreen` → `SettingsScreen` → `ChatScreen` + `components/` | Ya per layar (M3 + miuix koexist) |
| 2 | Hapus `compose-material3` + `compose-material-icons-core` dari `libs.versions.toml` / `app/build.gradle.kts` | Hanya setelah semua layar bersih |

Eksekusi di branch ini menyelesaikan 0→2 sekaligus (pilihan owner: serentak), tetapi tabel di atas adalah urutan aman bila ingin diulang/dipecah per PR. Commit lokal branch mengikuti fase 0/1/2.

## 3. Tabel mapping komponen

### 3.1 Padanan langsung

| Material3 (lama) | miuix 0.9.4 (baru) | Catatan |
|---|---|---|
| `Scaffold` | `top.yukonga.miuix.kmp.basic.Scaffold` | slot `topBar`/`bottomBar`/`content(PaddingValues)` sama; miuix menambah `popupHost` (wajib untuk dialog/sheet overlay) |
| `TopAppBar` | `SmallTopAppBar` (layar sub) / `TopAppBar` (utama) | Title miuix bertipe `String`, bukan composable; slot `bottomContent` untuk konten di bawah bar |
| `Text` | `basic.Text` | API setara |
| `Button` | `basic.Button` | warna danger: `ButtonDefaults.buttonColors(color = colorScheme.error)` |
| `TextButton` | `basic.TextButton(text = …, onClick = …)` | teks jadi parameter `String`; tombol ber-ikon → `IconButton` |
| `IconButton` / `FilledIconButton` | `basic.IconButton` | tombol filled: `backgroundColor = colorScheme.primary` |
| `Icon` | `basic.Icon` | API setara |
| `Switch` | `basic.Switch` | API setara |
| `Surface` | `basic.Surface` | tanpa `tonalElevation` → pakai warna eksplisit (`surfaceContainer`/`surfaceVariant`) |
| `OutlinedTextField` | `basic.TextField` | `label` + `useLabelAsPlaceholder` menggantikan `placeholder`; mendukung `visualTransformation`, `singleLine`, `keyboardOptions`, `minLines`/`maxLines` |
| `CircularProgressIndicator` | `basic.CircularProgressIndicator` | indeterminate: `progress = null` |
| `LinearProgressIndicator` | `basic.LinearProgressIndicator` | `progress` bertipe `Float?`, bukan lambda |
| `HorizontalDivider` | `basic.HorizontalDivider` | setara |
| `MaterialTheme.typography.*` | `MiuixTheme.textStyles.*` | lihat §5 |
| `MaterialTheme.colorScheme.*` | `MiuixTheme.colorScheme.*` | lihat §4/§5 |
| `Scaffold` (AppRoot) | `basic.Scaffold` | — |

### 3.2 Padanan terdekat (gap, keputusan "padanan dekat")

| Material3 (lama) | Padanan miuix | Alasan |
|---|---|---|
| `FilterChip` (pilihan bahasa / provider / skema / reasoning effort) | `basic.TabRow` (segmented control miuix, scrollable) | miuix tidak punya chip; TabRow adalah kontrol pilihan ter-segment resmi gaya HyperOS |
| `FilterChip` (toggle "hanya gratis" di ModelPicker) | `basic.Switch` + label | toggle biner |
| `AlertDialog` (nama proyek, edit-kirim-ulang, reset runtime, viewer file, file/folder baru) | `overlay.OverlayDialog(show, title, onDismissRequest, content)` | dialog resmi miuix; tombol aksi jadi bagian `content` |
| `ModalBottomSheet` (ModelPicker, PiModelSheet) | `overlay.OverlayBottomSheet(show, title, onDismissRequest, content)` | sheet bawah resmi miuix (render via `MiuixPopupHost` Scaffold) |
| `ModalNavigationDrawer` + `NavigationDrawerItem` (drawer sesi) | **drawer samping kustom dari primitif miuix** (`Surface` + scrim + `AnimatedVisibility`) di `ChatScreen.kt` | miuix 0.9.4 tidak menyediakan side drawer; UX kiri-tutup-dengan-back dipertahankan persis, hanya dibangun dari komponen miuix |
| `ListItem` (baris model) | baris kustom `Surface` + `Row` (pola yang sudah dipakai `PiModelRow`) | miuix `BasicComponent` ada, tapi baris kustom mempertahankan dot status + badge billing |
| `SwitchRow` di Settings | `preference.SwitchPreference(title, summary, checked, onCheckedChange)` | komponen preferensi resmi miuix (modul `miuix-preference`) |
| `Icons.Filled.*` (material-icons-core) | vektor baru inline di `ui/Icons.kt` (`ArrowBackIcon`, `SendIcon`, dst.) | repo sudah punya pola ikon inline Apache-2.0; dependensi `material-icons-core` dihapus total |

### 3.3 Tanpa padanan (dibiarkan foundation)

`BasicTextField` (composer chat, editor file) dan `LazyColumn` tetap dari `androidx.compose.foundation` — miuix dibangun di atas foundation Compose yang sama, ini bukan Material3.

## 4. Theming (`ui/Theme.kt`)

- **Light**: `lightColorScheme()` **default miuix** (identitas HyperOS putih-biru #3482FF) — keputusan "ikut sistem".
- **Dark**: `darkColorScheme(...)` dengan parameter yang dipetakan dari `DarkScheme` lama (Schemes.kt tak berubah — murni data, bukan UI):
  - `primary = #38BDF8` (SachetSky), `onPrimary = #00344F`, `primaryContainer = #0369A1`, `onPrimaryContainer = #CDEBFF`
  - `background = #101418`, `onBackground = #E1E3E4`, `surface = #161D24`, `onSurface = #E1E3E4`
  - `surfaceVariant = #1E2833`, `onSurfaceVariantSummary = #B9C6D4` (teks sekunder di atas surface)
  - `secondaryContainer = #2A3B4D`, `onSecondaryContainer = #A8C6E8` (chip executor / baris terpilih)
  - `error = #FFB4AB`, `errorContainer = #4A1714`, `onErrorContainer = #FFDAD6`
- `sachetSuccess()` dan konstanta warna publik (`SachetSky`, dst.) tidak berubah — dipakai kartu tool.
- Nama wrapper tetap `SachetTheme` sehingga `AppRoot`/`MainActivity` tidak berubah kontraknya.

## 5. Mapping gaya teks & warna (referensi mekanis saat review)

| M3 | miuix |
|---|---|
| `displaySmall` / `headlineMedium` / `titleLarge` | `textStyles.title1` |
| `titleMedium` | `textStyles.title2` |
| `titleSmall` | `textStyles.title3` |
| `bodyLarge` | `textStyles.body1` |
| `bodyMedium` | `textStyles.main` |
| `bodySmall` / `labelSmall` / `labelMedium` | `textStyles.body2` |
| `labelLarge` | `textStyles.subtitle` |
| `colorScheme.onSurfaceVariant` | `colorScheme.onSurfaceVariantSummary` |
| `colorScheme.tertiary` (aksen hint) | `colorScheme.primaryVariant` |
| `colorScheme.tertiaryContainer` / `onTertiaryContainer` | sama-nama di miuix |
| `colorScheme.surface` (latar kode) | sama-nama; area "kode" memakai `surfaceContainer` bila kontras kurang |

## 6. Ikon

14 ikon Material dipakai lewat `material-icons-core`: `Add, ArrowBack, ArrowDropDown, Check, Close, Delete, Edit, KeyboardArrowDown, KeyboardArrowRight, KeyboardArrowUp, Menu, Refresh, Send, Settings`. Semua ditambahkan ke `ui/Icons.kt` sebagai `ImageVector` inline (path Material Design, Apache-2.0 — pola yang sudah ada di file itu), dengan penamaan `…Icon` (mis. `ArrowBackIcon`). Setelah migrasi selesai `compose-material-icons-core` dihapus — APK tidak lagi membawa artefak Material.

## 7. Risiko & mitigasi

| Risiko | Mitigasi |
|---|---|
| API 0.9.4 beda dari contoh dokumentasi lama (era 0.8: SuperDialog dll.) | Semua signature di branch ini diverifikasi langsung dari source tag `v0.9.4`, bukan dari blog/dokumen lama |
| `TopAppBar` miuix bertipe `String` — judul interaktif ChatScreen (selector model) tidak muat di `title` | Judul teks di bar; selector model + `TabRow` dipindah ke slot `bottomContent` |
| Overlay dialog/sheet hanya render lewat `MiuixPopupHost` di dalam `Scaffold` | FilesScreen & SettingsScreen (dulu `Column` polos) dibungkus `Scaffold` masing-masing; ChatScreen sudah di dalam Scaffold utama |
| Tidak ada build Android di sandbox tempat migrasi ditulis | Validasi struktural: nol import `material3`/`material.icons`, konsistensi simbol vs API 0.9.4, brace balance. **Compile + regression visual wajib di mesin owner** (`./gradlew :app:assembleDebug`) |
| Drawer kustom = komponen baru | Perilaku disalin persis dari `ModalNavigationDrawer` lama (scrim tap-to-close, back-to-close, swipe-off tidak diimplementasi — dicatat sebagai keterbatasan) |

## 8. Rollback

Branch ini murni UI-layer: tidak menyentuh `api/`, `runtime/`, `data/`, server Node, atau assets. Rollback = hapus branch / `git revert`. Data pengguna (Prefs, ChatStore) tidak berubah bentuknya.
