package id.codingsachet.mobile.runtime

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.FileProvider
import id.codingsachet.mobile.Logx
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The app's debug log, owner-defined (T-ML17 #2): a logcat DUMP filtered to
 * Coding Sachet only.
 *
 * Everything the app and its agent server log already goes through Logx
 * under the single tag `CS` (server stdout is piped into Logx.raw by the
 * RuntimeController), so a tag-filtered dump IS the app-only view:
 *
 *     logcat -d -v time -s CS AndroidRuntime libc
 *
 * Two filters work together, and neither needs a permission:
 *   * the tag whitelist keeps CS + this process's crash output
 *     (AndroidRuntime = Kotlin/Java FATAL EXCEPTION, libc = native signals),
 *   * Android's logd already limits an unprivileged reader to its OWN
 *     UID's lines — other apps and the system never appear, which is
 *     exactly the "Coding Sachet only" filter the owner asked for.
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
    )

    /** Dump + persist. Runs the (short-lived) `logcat -d` synchronously —
     *  call from Dispatchers.IO. */
    fun dump(context: Context): Result {
        val text = try {
            runLogcat()
        } catch (e: Exception) {
            Logx.e("rt", "dump logcat gagal", e)
            return Result(emptyList(), null, e.message ?: e.javaClass.simpleName)
        }
        val lines = text.lines().filter { it.isNotBlank() }.takeLast(MAX_LINES)
        val file = save(context, text)
        Logx.i("rt", "dump logcat: ${lines.size} baris -> ${file?.absolutePath ?: "tidak tersimpan"}")
        return Result(lines, file, null)
    }

    private fun runLogcat(): String {
        val args = listOf("-d", "-v", "time", "-s", "CS", "AndroidRuntime", "libc")
        // Absolute path first: ProcessBuilder's PATH lookup is not reliable
        // for system binaries on every OEM ("Cannot run program logcat").
        val proc = try {
            ProcessBuilder(listOf("/system/bin/logcat") + args).start()
        } catch (_: Exception) {
            ProcessBuilder(listOf("logcat") + args).start()
        }
        val sb = StringBuilder()
        val reader = Thread {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    // logcat's own "beginning of..." header stays blank-filtered later
                    synchronized(sb) { sb.appendLine(line) }
                }
            }
        }.apply {
            isDaemon = true
            name = "logcat-dump"
            start()
        }
        // `logcat -d` dumps the buffer and exits; the join is only a watchdog.
        reader.join(10_000)
        if (reader.isAlive) {
            proc.destroyForcibly()
            reader.join(1_000)
        }
        runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
        synchronized(sb) { return sb.toString() }
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
