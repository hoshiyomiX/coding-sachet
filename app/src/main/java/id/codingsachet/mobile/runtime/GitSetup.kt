package id.codingsachet.mobile.runtime

/**
 * Git baseline written into the guest rootfs (T-ML59).
 *
 * Bug lapangan: `git status` / `git clone` / `git commit` di repo mana pun
 * yang hidup di shared storage mati dengan
 *
 *     fatal: detected dubious ownership in repository at '/sdcard/...'
 *     To add an exception for this directory, call:
 *         git config --global --add safe.directory /sdcard/...
 *
 * Penyebabnya struktural: file di /sdcard dimiliki uid Android aplikasi
 * (mis. 10236) dan tidak pernah dimiliki uid 0 guest, sementara proot
 * menjalankan git sebagai root (`--root-id`). Pemeriksaan kepemilikan git
 * karena itu SELALU gagal di setiap repo baru — dan "solusi" manual
 * (`safe.directory` per folder) harus diulang tiap kali agent membuat
 * proyek baru, plus memboroskan putaran tool.
 *
 * [merge] menambahkan satu blok terkelola ke /etc/gitconfig dan
 * /root/.gitconfig; isi lain milik pengguna dibiarkan utuh dan blok tidak
 * pernah ditulis dua kali (idempoten, aman dipanggil tiap boot).
 *
 * Kenapa `safe.directory = *` dan bukan daftar folder: proot guest ini
 * single-user, sekali pakai, dan folder proyek dibuat dinamis oleh agent
 * (`/sdcard/coding-sachet/<proyek>`), jadi daftar statis mustahil lengkap.
 *
 * Kenapa `core.fileMode = false`: FUSE /sdcard melaporkan 0777 untuk semua
 * file, sehingga git melihat "perubahan mode" palsu di setiap diff.
 *
 * Pure Kotlin tanpa android.* — bisa diuji JVM unit test (pola DumpFormat).
 */
object GitSetup {

    /** System-wide config: berlaku juga untuk `su -c` di mode root. */
    const val SYSTEM_CONFIG_GUEST = "/etc/gitconfig"

    /** Global config untuk user guest (HOME=/root). */
    const val GLOBAL_CONFIG_GUEST = "/root/.gitconfig"

    /** Penanda blok terkelola — dipakai [merge] untuk idempotensi. */
    const val MARKER = "# >>> coding-sachet git baseline (jangan diedit manual) >>>"

    /** Isi blok yang ditambahkan ke config git yang sudah ada. */
    fun block(): String = buildString {
        appendLine(MARKER)
        appendLine("[safe]")
        appendLine("\tdirectory = *")
        appendLine("[core]")
        appendLine("\tfileMode = false")
        appendLine("[init]")
        appendLine("\tdefaultBranch = main")
        appendLine("[advice]")
        appendLine("\tdetachedHead = false")
        appendLine("# <<< coding-sachet git baseline <<<")
    }

    /**
     * Existing config (null/blank = file belum ada) → config final.
     * Sudah memuat [MARKER]? dikembalikan apa adanya; kalau tidak, blok
     * ditambahkan di akhir dengan satu baris kosong pemisah.
     */
    fun merge(existing: String?): String {
        if (existing.isNullOrBlank()) return block()
        if (existing.contains(MARKER)) return existing
        return existing.trimEnd() + "\n\n" + block()
    }

    /** True bila isi config sudah memuat baseline ini. */
    fun isConfigured(text: String?): Boolean = text?.contains(MARKER) == true
}
