package id.apinex.mobile.data

import android.content.Context
import android.content.SharedPreferences

/** Small preference store. The API key lives in app-private storage only. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("apinex", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v).apply()

    /** "id" (default) or "en" */
    var lang: String
        get() = sp.getString("lang", "id") ?: "id"
        set(v) = sp.edit().putString("lang", v).apply()

    var serverPort: Int
        get() = sp.getInt("serverPort", DEFAULT_PORT)
        set(v) = sp.edit().putInt("serverPort", v).apply()

    var runtimeReady: Boolean
        get() = sp.getBoolean("runtimeReady", false)
        set(v) = sp.edit().putBoolean("runtimeReady", v).apply()

    /** Compatibility mode for devices where proot traps crash with seccomp issues. */
    var noSeccomp: Boolean
        get() = sp.getBoolean("noSeccomp", false)
        set(v) = sp.edit().putBoolean("noSeccomp", v).apply()

    var keepAwake: Boolean
        get() = sp.getBoolean("keepAwake", true)
        set(v) = sp.edit().putBoolean("keepAwake", v).apply()

    var onboardingDone: Boolean
        get() = sp.getBoolean("onboardingDone", false)
        set(v) = sp.edit().putBoolean("onboardingDone", v).apply()

    /** Version code of the app that last wrote the agent-server files into the rootfs. */
    var serverFilesVersion: Int
        get() = sp.getInt("serverFilesVersion", 0)
        set(v) = sp.edit().putInt("serverFilesVersion", v).apply()

    companion object {
        const val DEFAULT_PORT = 8765

        /** The model this app is built around (APInex free GLM model). */
        const val DEFAULT_MODEL = "free/glm-5.3"
    }
}
