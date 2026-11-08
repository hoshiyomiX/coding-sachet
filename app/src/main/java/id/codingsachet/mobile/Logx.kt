package id.codingsachet.mobile

import android.util.Log

/**
 * Standardized app-side debug logging (owner order T-ML16 #2).
 *
 * Everything the app logs goes through here so one logcat filter shows the
 * whole picture:
 *
 *     adb logcat -s CS
 *
 * and every line carries the component that wrote it:
 *
 *     I/CS: [chat] kirim session=ab12cd34 model=free/glm-5.3 msgs=4
 *
 * This mirrors the agent server's own format (`HH:MM:SS [LVL] [mod] msg`,
 * see assets/server/log.js) — the timestamp and level come from logcat
 * itself on this side. Modules in use: api · chat · rt (runtime) ·
 * svc (service) · store.
 *
 * PRIVACY RULES (also enforced at the call sites):
 *   * never log API keys, tokens or auth headers,
 *   * never log chat message content or file contents,
 *   * counts, ids, model names, paths and error texts are fine.
 */
object Logx {

    private const val TAG = "CS"

    fun d(mod: String, msg: String) = Log.d(TAG, "[$mod] $msg")

    fun i(mod: String, msg: String) = Log.i(TAG, "[$mod] $msg")

    fun w(mod: String, msg: String) = Log.w(TAG, "[$mod] $msg")

    fun e(mod: String, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(TAG, "[$mod] $msg (${tr.javaClass.simpleName}: ${tr.message})")
        else Log.e(TAG, "[$mod] $msg")
    }

    /** Forward a line that already carries its own format — the agent
     *  server's stdout (`HH:MM:SS [LVL] [mod] msg`) is piped through the
     *  RuntimeController into logcat unchanged, so both worlds stay
     *  distinguishable and greppable under the same tag. */
    fun raw(line: String) = Log.i(TAG, line)
}
