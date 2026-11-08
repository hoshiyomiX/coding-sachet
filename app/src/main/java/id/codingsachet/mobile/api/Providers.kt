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
    /**
     * Chat models VERIFIED WORKING with a real key (owner order T-ML17 #1):
     * every id returned a 2xx chat completion (or an SSE first chunk) when
     * probed on 2026-11-08. The provider's /v1/models catalogue lists many
     * more entries that are not deployed for normal keys (404 "Function …
     * not found"), are embeddings/rerank endpoints, or time out — those are
     * still pickable, but they sort BELOW the verified free ones and carry
     * no badge. Empty for APInex (free/ prefix covers it).
     */
    val verifiedModels: Set<String> = emptySet(),
) {
    /** Free = worth showing on top with a badge: verified-working on NIM,
     *  the free/ tier on APInex. */
    fun isFreeModel(id: String): Boolean =
        (freeModelPrefix != null && id.startsWith(freeModelPrefix)) || id in verifiedModels
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
        // 19 of the 84 catalogue entries answered a real chat call on
        // 2026-11-08 (16 fast 2xx, deepseek-v4.1-flash at ~41 s, and
        // llama-3.2-90b-vision / nemoguard-content-safety via their SSE
        // first chunk — the app's actual streaming path). Everything else
        // 404s for normal keys or is an embedding/rerank endpoint.
        verifiedModels = setOf(
            "deepseek-ai/deepseek-v4.1-flash",
            "google/diffusiongemma-26b-a4b-it",
            "meta/llama-3.2-90b-vision-instruct",
            "meta/llama-guard-4-12b",
            "meta/muse-glimmer-30b",
            "minimaxai/minimax-m3.1-flash",
            "mistralai/mistral-large-4-1t-a52b",
            "nvidia/llama-3.1-nemoguard-8b-content-safety",
            "nvidia/llama-3.1-nemotron-safety-guard-8b-v3",
            "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
            "nvidia/nemotron-3-ultra-550b-a55b",
            "nvidia/nemotron-3.5-bolt-e4b",
            "nvidia/nemotron-3.5-lightning-30b-a3b",
            "nvidia/nemotron-3.5-super-vl-120b-a12b",
            "nvidia/nemotron-parse-2.0",
            "nvidia/riva-translate-4b-instruct-v2",
            "poolside/laguna-xs-2.1",
            "stepfun-ai/step-5-preview",
            "z-ai/glm-5.3-flash",
        ),
    )

    val ALL = listOf(APINEX, NVIDIA)

    fun byId(id: String?): Provider = ALL.firstOrNull { it.id == id } ?: APINEX
}
