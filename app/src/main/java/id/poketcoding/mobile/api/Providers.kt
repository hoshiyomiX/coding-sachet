package id.poketcoding.mobile.api

import id.poketcoding.mobile.BuildConfig

/**
 * The gateways this app talks to. Both are OpenAI-compatible, so the agent
 * server proxies either one unchanged; what differs is the base URL, the
 * key format, the free-tier semantics and the default model.
 */
data class Provider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val keyPrefix: String,
    val docsUrl: String,
    val defaultModel: String,
    /** Model ids with this prefix are free (APInex); null = all free to prototype (NIM). */
    val freeModelPrefix: String?,
    /** Free-tier terms shown in the UI. */
    val freeTierNote: String,
    /** How a key is validated against this provider. */
    val validateViaChat: Boolean,
)

object Providers {
    val APINEX = Provider(
        id = "apinex",
        label = "APInex",
        baseUrl = BuildConfig.APINEX_BASE_URL,
        keyPrefix = "sk-apx",
        docsUrl = "https://apinex.bond",
        defaultModel = "free/glm-5.3",
        freeModelPrefix = "free/",
        freeTierNote = "20 juta token/hari · 15 permintaan/menit (model free/)",
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
        freeTierNote = "prototyping gratis · ±40 permintaan/menit",
        validateViaChat = true,
    )

    val ALL = listOf(APINEX, NVIDIA)

    fun byId(id: String?): Provider = ALL.firstOrNull { it.id == id } ?: APINEX
}
