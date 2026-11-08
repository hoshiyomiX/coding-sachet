package id.codingsachet.mobile.data

import android.content.Context
import android.content.SharedPreferences
import id.codingsachet.mobile.api.Provider
import id.codingsachet.mobile.api.Providers

/** Small preference store. The API keys live in app-private storage only. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("sachet", Context.MODE_PRIVATE)

    /** The active provider: "apinex" (default) or "nvidia". */
    var provider: String
        get() = sp.getString("provider", DEFAULT_PROVIDER) ?: DEFAULT_PROVIDER
        set(v) = sp.edit().putString("provider", v).apply()

    val active: Provider get() = Providers.byId(provider)

    // ---- per-provider API keys ----

    fun keyFor(providerId: String): String =
        sp.getString("key.$providerId", "") ?: ""

    fun setKeyFor(providerId: String, v: String) =
        sp.edit().putString("key.$providerId", v).apply()

    /** Key of the active provider (used by chat + model list). */
    var apiKey: String
        get() = keyFor(provider)
        set(v) = setKeyFor(provider, v)

    // ---- per-provider models ----

    fun modelFor(providerId: String): String =
        sp.getString("model.$providerId", "")?.ifBlank { null }
            ?: Providers.byId(providerId).defaultModel

    fun setModelFor(providerId: String, v: String) =
        sp.edit().putString("model.$providerId", v).apply()

    /** Model of the active provider. */
    var model: String
        get() = modelFor(provider)
        set(v) = setModelFor(provider, v)

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

    /** Rooted phones: run the server via su and expose /device in the
     *  file explorer. Only honored when a su binary is actually present. */
    var rootAccess: Boolean
        get() = sp.getBoolean("rootAccess", false)
        set(v) = sp.edit().putBoolean("rootAccess", v).apply()

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
        const val DEFAULT_PROVIDER = "apinex"
    }
}
