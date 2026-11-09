package id.codingsachet.mobile.runtime

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import id.codingsachet.mobile.BuildConfig
import id.codingsachet.mobile.Logx
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The app's debug log dump (T-ML17 #2, widened in T-ML25 by owner order
 * "log terasa kurang berguna karena tidak selengkap logcat").
 *
 * v2 = as complete as logcat can be for this app:
 *
 *     logcat -d -v threadtime,year
 *
 *   * NO tag whitelist anymore — every line the UID is allowed to read is
 *     kept: the app's own `CS` lines (Logx), the agent-server stdout piped
 *     in by RuntimeController, plus chatty, System.err, art, libc variants
 *     and any native crash output that never used to match `-s CS`.
 *   * `threadtime` adds PID and TID per line (old `-v time` had neither
 *     TID nor year) — the same format `adb logcat` shows.
 *   * A context header is prepended (app version, device, Android version,
 *     network + VPN state, server state) so a shared dump is diagnosable
 *     on its own, without asking the sender for device details.
 *   * Android's logd still limits an unprivileged reader to its OWN UID's
 *     lines. The header therefore carries the adb command that unlocks the
 *     FULL system logcat (all processes) when the owner wants it:
 *
 *     adb shell pm grant id.codingsachet.mobile android.permission.READ_LOGS
 *
 *   * Output is capped to the newest [DumpFormat.MAX_BYTES] (10 MB), head
 *     first — `logcat -d` is oldest-first, so recent context always wins.
 *
 * The dump is written to <shared-storage>/coding-sachet/logs/ so it shows
 * up in the app's own Files screen (fallback: the app-private external
 * files dir when shared storage is unavailable).
 */
object LogcatDump {

    /** Lines kept in memory for the preview; the file always keeps everything. */
    private const val MAX_LINES = 20000

    data class Result(
        /** Dump lines (capped, newest last) — empty on failure. */
        val lines: List<String>,
        /** Where the full dump was written; null when saving failed. */
        val file: File?,
        /** Failure reason when logcat itself could not be run. */
        val error: String? = null,
        /** True when the raw logcat output exceeded the 10 MB cap and the
         *  oldest lines were dropped from the file. */
        val trimmed: Boolean = false,
    )

    /** Dump + persist. Runs the (short-lived) `logcat -d` synchronously —
     *  call from Dispatchers.IO. [serverState] is a short human label of
     *  the agent-server state to embed in the header. */
    fun dump(context: Context, serverState: String): Result {
        val bytes = try {
            runLogcat()
        } catch (e: Exception) {
            Logx.e("rt", "dump logcat gagal", e)
            return Result(emptyList(), null, e.message ?: e.javaClass.simpleName)
        }
        val start = DumpFormat.tailStart(bytes)
        val trimmed = start > 0
        val kept = if (trimmed) String(bytes, start, bytes.size - start, Charsets.UTF_8) else String(bytes, Charsets.UTF_8)
        val text = buildString {
            append(buildHeader(context, serverState))
            if (trimmed) {
                appendLine("# [dipotong] logcat mentah melebihi ${DumpFormat.MAX_BYTES / (1024 * 1024)} MB — ")
                appendLine("# hanya bagian TERBARU yang disimpan (logcat -d mencetak dari terlama).")
            }
            append(kept)
        }
        val lines = text.lines().filter { it.isNotBlank() }.takeLast(MAX_LINES)
        val file = save(context, text)
        Logx.i("rt", "dump logcat: ${lines.size} baris${if (trimmed) " (dipotong ke ekor 10 MB)" else ""} -> ${file?.absolutePath ?: "tidak tersimpan"}")
        return Result(lines, file, null, trimmed)
    }

    /** Context header so a shared dump diagnoses itself (T-ML25 #2). */
    private fun buildHeader(context: Context, serverState: String): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        return buildString {
            appendLine("==== Coding Sachet — dump log lengkap ====")
            appendLine("waktu     : $now")
            appendLine("app       : ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            appendLine("perangkat : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("jaringan  : ${networkSummary(context)}")
            appendLine("server    : $serverState")
            appendLine("sumber    : ${DumpFormat.COMMAND} — semua tag yang boleh dibaca UID aplikasi ini, tanpa filter tag")
            appendLine("catatan   : log proses lain / sistem hanya ikut terbaca bila izin READ_LOGS diberikan lewat adb:")
            appendLine("           adb shell pm grant ${context.packageName} android.permission.READ_LOGS")
            appendLine("==== mulai logcat ====")
        }
    }

    /** Short connectivity line incl. the VPN flag — the context the owner
     *  actually needs when debugging gateway failures (see T-ML23). */
    private fun networkSummary(context: Context): String {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "tidak diketahui"
            val net = cm.activeNetwork
            val caps = if (net != null) cm.getNetworkCapabilities(net) else null
            if (caps == null) return "offline (tidak ada jaringan aktif)"
            val transports = buildList {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("seluler")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN aktif")
            }
            val state = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) "terhubung" else "tanpa internet"
            if (transports.isEmpty()) state else state + " (" + transports.joinToString(" · ") + ")"
        }.getOrDefault("tidak diketahui")
    }

    private fun runLogcat(): ByteArray {
        val args = DumpFormat.COMMAND.split(" ").drop(1)
        // Absolute path first: ProcessBuilder's PATH lookup is not reliable
        // for system binaries on every OEM ("Cannot run program logcat").
        val proc = try {
            ProcessBuilder(listOf("/system/bin/logcat") + args).start()
        } catch (_: Exception) {
            ProcessBuilder(listOf("logcat") + args).start()
        }
        val out = ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                proc.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        synchronized(out) { out.write(buf, 0, n) }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "logcat-dump"
            start()
        }
        // `logcat -d` dumps the buffer and exits; the join is only a
        // watchdog (15 s — the unfiltered dump is bigger than v1's).
        reader.join(15_000)
        if (reader.isAlive) {
            proc.destroyForcibly()
            reader.join(1_000)
        }
        runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
        synchronized(out) { return out.toByteArray() }
    }

    private fun save(context: Context, text: String): File? {
        if (text.isBlank()) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "logcat-$stamp.txt"

        // Preferred: the shared-storage project folder — the dump is then
        // visible in the app's Files screen like any other project file.
        val proj = File(Environment.getExternalStorageDirectory(), "coding-sachet/logs")
        if (runCatching { proj.mkdirs() }.getOrDefault(false) || proj.isDirectory) {
            val f = File(proj, name)
            if (runCatching { f.writeText(text) }.isSuccess) return f
        }

        // Fallback: app-owned external dir — always writable, no permission.
        val appDir = context.getExternalFilesDir("logs") ?: return null
        runCatching { appDir.mkdirs() }
        val f = File(appDir, name)
        // NB: File.writeText returns Unit (kotlin.io) — the lambda must end
        // with the File itself so runCatching gives Result<File>.
        return runCatching { f.writeText(text); f }.getOrNull()
    }

    /** Share the saved dump through the system sheet (any app). */
    fun share(context: Context, file: File): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }.getOrNull() ?: run {
            Logx.w("rt", "bagikan log: FileProvider menolak ${file.path}")
            return false
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(intent, file.name))
            true
        }.getOrDefault(false)
    }
}
