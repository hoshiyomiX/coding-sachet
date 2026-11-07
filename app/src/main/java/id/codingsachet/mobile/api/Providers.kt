package id.codingsachet.mobile.api

import id.codingsachet.mobile.BuildConfig

/**
 * The gateways this app talks to. Both are OpenAI-compatible, so the agent
 * server proxies either one unchanged; what differs is the base URL, the
 * key format and the default model.
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
