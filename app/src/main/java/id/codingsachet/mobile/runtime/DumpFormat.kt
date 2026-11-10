package id.codingsachet.mobile.runtime

/**
 * Pure, Android-free helpers for the logcat dump (T-ML25, tightened in
 * T-ML26 by owner order: "dump v2 filter Coding Sachet saja").
 *
 * Kept in its own object with ZERO android.* imports so JVM unit tests can
 * touch it directly — [LogcatDump] itself imports the framework, which is
 * not on the unit-test classpath.
 */
object DumpFormat {

    /**
     * Shared shape of every dump command (T-ML25): `threadtime` adds PID and
     * TID per line, `year` adds the full date — the same format `adb logcat`
     * shows. No tag whitelist: within the process filter below, every line
     * this app is allowed to read is kept (CS, server stdout, chatty,
     * System.err, art, libc, native crashes).
     */
    const val FORMAT_ARGS = "-d -v threadtime,year"

    /**
     * PRIMARY command (T-ML26): dump ONLY Coding Sachet's own lines.
     *
     * `--uid=<uid>` keeps every log record written by ANY process running as
     * this app's UID — the app process itself AND the proot/node server
     * children (their native-crash lines land in logd under this UID via
     * debuggerd), while other apps' and the system's lines are filtered out.
     *
     * Single source of truth: [LogcatDump] builds its process arguments from
     * these same functions, so the shipped command can never drift from the
     * tested one.
     */
    fun commandForUid(uid: Int): String = "logcat $FORMAT_ARGS --uid=$uid"

    /**
     * Fallback for OEMs whose logcat predates `--uid` (both flags ship with
     * Android 7/8; minSdk is 26, so this is belt-and-braces): the app's own
     * process only. Server-stdout is piped into `Logx` in the app process,
     * so it survives this filter; only direct logcat writes from the
     * proot/node children would be missed.
     */
    fun commandForPid(pid: Int): String = "logcat $FORMAT_ARGS --pid=$pid"

    /**
     * Last resort when BOTH filters are rejected: the unfiltered T-ML25 v2
     * command (everything the UID may read). Still better than no dump.
     */
    const val COMMAND_UNFILTERED = "logcat $FORMAT_ARGS"

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
