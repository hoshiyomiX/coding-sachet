package id.apinex.mobile.runtime

import android.system.Os
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal .tar.gz extractor (ustar + GNU long names + pax records,
 * symlinks and hard links), written for the Ubuntu base and Node tarballs.
 * Permissions are kept, but the owning app always retains access so files
 * can be replaced or removed later.
 */
object TarExtract {

    class Result(val files: Int, val skipped: Int)

    fun extract(
        archive: File,
        dest: File,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result {
        dest.mkdirs()
        val destRoot = dest.canonicalFile
        fun inside(f: File): Boolean {
            val p = f.canonicalFile.path
            return p == destRoot.path || p.startsWith(destRoot.path + File.separator)
        }

        val total = archive.length()
        var files = 0
        var skipped = 0
        var lastReport = 0L
        val dirModes = ArrayList<Pair<File, Int>>()

        val counted = CountingInput(FileInputStream(archive).buffered(1 shl 16))
        GZIPInputStream(counted, 1 shl 16).use { gz ->
            val header = ByteArray(512)
            var pendingLongName: String? = null
            var pendingLongLink: String? = null
            var paxPath: String? = null
            var paxLink: String? = null

            while (readHeader(gz, header)) {
                if (header.all { it == 0.toByte() }) break

                var name = cstring(header, 0, 100)
                val mode = octal(header, 100, 8).toInt()
                val size = octal(header, 124, 12)
                val typeChar = header[156].toInt().toChar()
                var link = cstring(header, 157, 100)
                if (cstring(header, 257, 5) == "ustar") {
                    val prefix = cstring(header, 345, 155)
                    if (prefix.isNotEmpty()) name = "$prefix/$name"
                }

                when (typeChar) {
                    'L' -> { pendingLongName = recordString(gz, size); continue }
                    'K' -> { pendingLongLink = recordString(gz, size); continue }
                    'x' -> {
                        for ((k, v) in paxRecords(recordString(gz, size))) {
                            if (k == "path") paxPath = v
                            if (k == "linkpath") paxLink = v
                        }
                        continue
                    }
                    'g' -> { skipBytes(gz, size + padding(size)); continue }
                }
                pendingLongName?.let { name = it }
                pendingLongLink?.let { link = it }
                (paxPath ?: pendingLongName)?.let { name = it }
                (paxLink ?: pendingLongLink)?.let { link = it }
                pendingLongName = null; pendingLongLink = null; paxPath = null; paxLink = null

                val rel = name.removePrefix("./").trimStart('/').trimEnd('/')
                if (rel.isEmpty() || rel.split('/').any { it == ".." }) {
                    skipBytes(gz, size + padding(size)); skipped++; continue
                }
                val target = File(dest, rel)
                val parent = target.parentFile
                parent?.mkdirs()
                if (parent != null && !inside(parent)) {
                    skipBytes(gz, size + padding(size)); skipped++; continue
                }

                when (typeChar) {
                    '5' -> {
                        target.mkdirs()
                        dirModes += target to mode
                    }
                    '2' -> { // symlink
                        target.delete()
                        Os.symlink(link, target.path)
                        files++
                    }
                    '1' -> { // hard link
                        val src = File(dest, link.trimStart('/').removePrefix("./"))
                        if (link.split('/').any { it == ".." } || !inside(src)) { skipped++; continue }
                        target.delete()
                        if (src.exists()) src.copyTo(target, overwrite = true)
                        runCatching { Os.chmod(target.path, (mode and 0x1FF) or 0x180) }
                        files++
                    }
                    '0', ' ', '7' -> {
                        target.delete()
                        target.outputStream().buffered(1 shl 16).use { out ->
                            var remaining = size
                            val buf = ByteArray(1 shl 16)
                            while (remaining > 0) {
                                val n = gz.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (n < 0) error("tar: unexpected end of archive in $rel")
                                out.write(buf, 0, n)
                                remaining -= n
                            }
                        }
                        skipBytes(gz, padding(size))
                        runCatching { Os.chmod(target.path, (mode and 0x1FF) or 0x180) }
                        files++
                    }
                    else -> { skipBytes(gz, size + padding(size)); skipped++ }
                }

                if (counted.count - lastReport > 512 * 1024) {
                    lastReport = counted.count
                    onProgress(counted.count, total)
                }
            }
        }

        // Directory permissions last: a locked-down directory would reject new children.
        for ((dir, mode) in dirModes.asReversed()) {
            runCatching { Os.chmod(dir.path, (mode and 0x1FF) or 0x1C0) }
        }
        onProgress(total, total)
        return Result(files, skipped)
    }

    private fun padding(size: Long) = (512 - size % 512) % 512

    private fun skipBytes(input: InputStream, n: Long) {
        var remaining = n
        val buf = ByteArray(1 shl 14)
        while (remaining > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (r < 0) return
            remaining -= r
        }
    }

    private fun recordString(input: InputStream, size: Long): String {
        if (size < 0 || size > 4_096_768) error("tar: oversized header record ($size bytes)")
        val bytes = ByteArray(size.toInt())
        if (!readHeader(input, bytes)) error("tar: truncated record")
        skipBytes(input, padding(size))
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000', '\n')
    }

    private fun paxRecords(s: String): List<Pair<String, String>> =
        s.split('\n').mapNotNull { line ->
            val sp = line.indexOf(' ')
            if (sp <= 0) return@mapNotNull null
            val kv = line.substring(sp + 1)
            val eq = kv.indexOf('=')
            if (eq <= 0) null else kv.substring(0, eq) to kv.substring(eq + 1)
        }

    private fun readHeader(input: InputStream, into: ByteArray): Boolean {
        var off = 0
        while (off < into.size) {
            val r = input.read(into, off, into.size - off)
            if (r < 0) {
                check(off == 0) { "tar: truncated header" }
                return false
            }
            off += r
        }
        return true
    }

    private fun cstring(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        // GNU base-256 encoding for large sizes
        if (b[off].toInt() and 0x80 != 0) {
            var v = (b[off].toInt() and 0x7F).toLong()
            for (i in 1 until len) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }
        var v = 0L
        var seen = false
        for (i in off until off + len) {
            val c = b[i].toInt().toChar()
            if (c in '0'..'7') { v = v * 8 + (c - '0'); seen = true }
            else if (seen || (c != ' ' && c != ' ')) break
        }
        return v
    }

    private class CountingInput(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }
    }
}
