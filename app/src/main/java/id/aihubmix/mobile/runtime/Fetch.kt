package id.aihubmix.mobile.runtime

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Downloads with progress reporting and a sha256 check; resumes safely via .part files. */
object Fetch {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 5xx answers from a CDN are retried a few times before giving up. */
    private class Transient(msg: String) : RuntimeException(msg)

    fun fetch(url: String, dest: File, sha256: String?, onProgress: (done: Long, total: Long) -> Unit) {
        var attempt = 0
        while (true) {
            try {
                fetchOnce(url, dest, sha256, onProgress)
                return
            } catch (e: Exception) {
                val retriable = e is Transient || e is java.io.IOException
                if (!retriable || ++attempt >= 4) throw e
                Thread.sleep(2000L * attempt)
            }
        }
    }

    private fun fetchOnce(url: String, dest: File, sha256: String?, onProgress: (done: Long, total: Long) -> Unit) {
        dest.parentFile?.mkdirs()
        // A cached download is reused only when the checksum is known and matches.
        if (dest.exists() && sha256 != null && sha256Of(dest).equals(sha256, ignoreCase = true)) {
            onProgress(dest.length(), dest.length())
            return
        }
        val part = File(dest.path + ".part")
        val req = Request.Builder().url(url).header("User-Agent", "aihubmix-mobile").build()
        client.newCall(req).execute().use { r ->
            if (r.code >= 500) throw Transient("GET $url: HTTP ${r.code}")
            if (!r.isSuccessful) error("GET $url: HTTP ${r.code}")
            val body = r.body
            val total = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var last = 0L
            part.outputStream().buffered(1 shl 16).use { out ->
                val buf = ByteArray(1 shl 16)
                val src = body.byteStream()
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    if (done - last > 256 * 1024) {
                        last = done
                        onProgress(done, total)
                    }
                }
            }
            if (total > 0 && done != total) {
                part.delete()
                error("Unduhan ${dest.name} terpotong ($done dari $total byte)")
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha256 != null && !got.equals(sha256, ignoreCase = true)) {
                part.delete()
                error("Checksum ${dest.name} tidak cocok: $got")
            }
            dest.delete()
            check(part.renameTo(dest)) { "Gagal menamai ulang ${part.name}" }
            onProgress(done, if (total > 0) total else done)
        }
    }

    fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(1 shl 16).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
