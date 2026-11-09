package id.codingsachet.mobile.runtime

/**
 * Pure, Android-free helpers for the logcat dump (T-ML25, owner order:
 * "log terasa kurang berguna karena tidak selengkap logcat").
 *
 * Kept in its own object with ZERO android.* imports so JVM unit tests can
 * touch it directly — [LogcatDump] itself imports the framework, which is
 * not on the unit-test classpath.
 */
object DumpFormat {

    /**
     * The dump command (T-ML25): NO tag whitelist anymore — the dump keeps
     * EVERY line this app's UID is allowed to read (all tags: CS, chatty,
     * System.err, art, libc variants, native crashes, …), in `threadtime`
     * format (date time PID TID level tag: message) with the year included.
     *
     * Single source of truth: LogcatDump parses this same string when it
     * spawns the process, so the shipped command can never drift from the
     * tested one.
     */
    const val COMMAND = "logcat -d -v threadtime,year"

    /**
     * Byte cap for the kept tail. `logcat -d` prints oldest-first, so the
     * NEWEST lines are at the end — on overflow only the head (oldest) is
     * dropped, never the fresh context.
     */
    const val MAX_BYTES = 10 * 1024 * 1024

    /**
     * Index to start copying [bytes] from so the kept tail is at most
     * [maxBytes] long AND begins at a line boundary (a partial leading
     * line is skipped). Returns 0 when the whole array already fits.
     */
    fun tailStart(bytes: ByteArray, maxBytes: Int = MAX_BYTES): Int {
        if (bytes.size <= maxBytes) return 0
        var i = bytes.size - maxBytes
        while (i < bytes.size && bytes[i] != '\n'.code.toByte()) i++
        // i sits on the first newline inside the tail window (or at the
        // very end when the window has no line break at all) — keep from
        // just after that newline.
        return if (i < bytes.size) i + 1 else i
    }
}
