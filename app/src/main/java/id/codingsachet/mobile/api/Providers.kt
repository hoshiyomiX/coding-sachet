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
    /** Whether the "sisa token" chip applies (a quota API exists for this
     *  provider — APInex /v1/subscription, 100milionseek response headers;
     *  NIM has none and the chip stays hidden). T-ML52. */
    val supportsQuota: Boolean,
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
        // T-ML49: free/glm-5.3 kini allowFree:false (402 "subscription" —
        // dibuktikan probe langsung 2026-11-20); default berpindah ke model
        // jatah harian yang masih tier free.
        defaultModel = "free/glm-5.3-flashx",
        freeModelPrefix = "free/",
        validateViaChat = false,
        supportsQuota = true,
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
        supportsQuota = false,
    )

    // T-ML52 (owner order: "implementasi sebagai provider ketiga").
    // 100milionseek: free 100M tokens/account/UTC day (no card, no paid
    // tier — milestone bonuses lift it while they run; verified live
    // 2026-11-20). Whole catalog free → prefix-less free semantics like
    // NIM: the live sweep's free flag decides the badge. Quota counters
    // ride response headers (server-side fetchQuota), so the chip works.
    // Key check: GET /v1/models with Bearer (401 on a bad key — verified
    // live); a chat probe would waste ~630 prompt tokens of the allowance.
    val SEEK = Provider(
        id = "seek",
        label = "100milionseek",
        baseUrl = BuildConfig.SEEK_BASE_URL,
        keyPrefix = "hms-",
        docsUrl = "https://100milionseek.top",
        defaultModel = "deepseek-v4.1-flash-free",
        freeModelPrefix = null,
        validateViaChat = false,
        supportsQuota = true,
    )

    // T-ML64 (owner order: "implementasi provider Ngodingai.net").
    // NgodeAI: gateway IDR prepaid (llm.ngodeai.net) — free tier = model
    // whose own catalogue pricing is 0 (glm-5.3-flash, minimax-m3.1-flash,
    // …, verified live 2026-11-21); paid models metered per token against
    // the wallet (402 insufficient_balance under Rp 1). freeModelPrefix
    // null + server catalogueSweep: the badge's free flag comes from the
    // gateway's own pricing (NOT a live answer — paid models answer too
    // once the wallet has funds). Chip: GET /v1/balance saldo IDR (mode
    // "idr" → rendered as Rupiah). Key check: GET /v1/models (401 bad key).
    val NGODEAI = Provider(
        id = "ngodeai",
        label = "NgodeAI",
        baseUrl = BuildConfig.NGODEAI_BASE_URL,
        keyPrefix = "ngodeai_live_",
        docsUrl = "https://docs.ngodeai.net",
        defaultModel = "ngodeai/glm-5.3-flash",
        freeModelPrefix = null,
        validateViaChat = false,
        supportsQuota = true,
    )

    val ALL = listOf(APINEX, NVIDIA, SEEK, NGODEAI)

    fun byId(id: String?): Provider = ALL.firstOrNull { it.id == id } ?: APINEX
}
