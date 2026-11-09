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
     * a RED dot. Empty for APInex (the free/ prefix + verifiedFreeModels
     * cover it).
     */
    val verifiedModels: Set<String> = emptySet(),
    /**
     * APInex free tier per the public catalogue (allowFree=true, probed
     * 2026-11-09): ids usable on the free daily allowance — broader than
     * the free/ prefix (premium models like claude-opus-5.5 or gpt-6.1-terra
     * are included). Baked at audit time; the runtime list stays /v1/models.
     */
    val verifiedFreeModels: Set<String> = emptySet(),
    /**
     * True when the provider's whole catalogue was healthy at audit time
     * (APInex relay: 42/42 models health=live, probed 2026-11-09) — every
     * runtime-fetched id gets a GREEN dot. NIM is false: only the
     * [verifiedModels] set is green, the rest of the catalogue is RED.
     */
    val catalogHealthy: Boolean = false,
) {
    /** Free = worth showing on top with a badge: verified-working on NIM,
     *  the free/ tier + allowFree catalogue on APInex. */
    fun isFreeModel(id: String): Boolean =
        (freeModelPrefix != null && id.startsWith(freeModelPrefix)) ||
            id in verifiedModels || id in verifiedFreeModels

    /** Working = GREEN dot in the model picker (owner order T-ML20 #2);
     *  not-working/unverified = RED dot. */
    fun isWorkingModel(id: String): Boolean = catalogHealthy || id in verifiedModels
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
        // Free tier per the public catalogue's allowFree flag (probed
        // 2026-11-09, 35 of 42 models): premium models usable on the free
        // daily allowance are listed too — beyond the free/ prefix.
        verifiedFreeModels = setOf(
            "free/deepseek-v4.1-flash",
            "free/gemini-3.8-flash",
            "free/qwen-3.8-max",
            "free/glm-5.3-flash",
            "free/kimi-k3",
            "free/deepseek-v4-pro-0813",
            "free/deepseek-v4-flash-0731",
            "free/hy4",
            "free/muse-spark-1.3",
            "claude-opus-5.5",
            "gemini-4-argon",
            "free/mimo-v2.6-flash",
            "free/glm-5.3",
            "claude-sonnet-5.5",
            "claude-fable-5.1",
            "gpt-6.1-terra",
            "gpt-6.1-sol",
            "gpt-6-astra",
            "deepseek-v4.1-flash",
            "grok-4.7",
            "gemini-3.8-flash",
            "deepseek-v4-flash",
            "deepseek-v4-pro",
            "gemini-3.1-pro",
            "gpt-6-luna",
            "free/minimax-m3.1-flash",
            "free/gpt-6-luna",
            "free/jev-1.13",
            "glm-5.3-flash",
            "glm-5.3",
            "kimi-k3.1-pro",
            "drex-v2.0",
            "jev-1.13",
            "muse-spark-1.3",
            "hy4-preview",
        ),
        // Whole catalogue health=live on 2026-11-09 (42/42) — every model
        // the relay lists gets a green working dot.
        catalogHealthy = true,
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
        catalogHealthy = false,
    )

    val ALL = listOf(APINEX, NVIDIA)

    fun byId(id: String?): Provider = ALL.firstOrNull { it.id == id } ?: APINEX
}
