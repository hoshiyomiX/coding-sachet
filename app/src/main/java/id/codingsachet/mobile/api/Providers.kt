package id.codingsachet.mobile.api

import id.codingsachet.mobile.BuildConfig

/**
 * The gateways this app talks to. Both are OpenAI-compatible, so the agent
 * server proxies either one unchanged; what differs is the base URL, the
 * key format and the default model.
 *
 * T-ML26 (owner order: "mengecek actual bukan dari membaca hardcoded
 * list"): this file carries STATIC metadata only. Which models actually
 * WORK — the picker's green/red dots and the free badges — is decided at
 * RUNTIME by [ModelStatus], fed by a live verification sweep against the
 * real gateway (see the server's /api/verify-models). Baked "verified"
 * sets used to live here and went stale within a day (the NIM catalogue
 * fluctuates daily; the APInex allowFree set drifted 35 → 34 the day
 * after it was baked), which is exactly why they are gone.
 */
data class Provider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val keyPrefix: String,
    val docsUrl: String,
    val defaultModel: String,
    /** Model ids with this prefix are free (APInex); null = whole catalog (NIM). */
    val freeModelPrefix: String?,
    /** How a key is validated against this provider. */
    val validateViaChat: Boolean,
) {
    /** Free by NAME: the gateway's own free/ tier (intrinsic, needs no
     *  probe). The broader allowFree catalogue and NIM's working-free set
     *  are runtime facts — see [ModelStatus.isFree]. */
    fun isFreeByPrefix(id: String): Boolean =
        freeModelPrefix != null && id.startsWith(freeModelPrefix)
}

object Providers {
    val APINEX = Provider(
        id = "apinex",
        label = "APInex",
        baseUrl = BuildConfig.APINEX_BASE_URL,
        keyPrefix = "sk-apx",
        docsUrl = "https://apinex.bond",
        defaultModel = "free/glm-5.3",
        freeModelPrefix = "free/",
        validateViaChat = false,
    )

    val NVIDIA = Provider(
        id = "nvidia",
        label = "NVIDIA NIM",
        baseUrl = BuildConfig.NVIDIA_BASE_URL,
        keyPrefix = "nvapi-",
        docsUrl = "https://build.nvidia.com",
        defaultModel = "nvidia/nemotron-3.5-bolt-e4b",
        freeModelPrefix = null,
        validateViaChat = true,
    )

    val ALL = listOf(APINEX, NVIDIA)

    fun byId(id: String?): Provider = ALL.firstOrNull { it.id == id } ?: APINEX
}
