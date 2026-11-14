package id.codingsachet.mobile.ui

/**
 * Ready-made scheme templates (T-ML28 #1: "template siap pakai
 * konfigurasi json untuk beragam skema yang bisa dipilih & digonta-ganti").
 * Values re-tuned T-ML35 (owner: "set value paling optimal") against the
 * vendors' own guidance for the two default models — free/glm-5.3 (z.ai:
 * temperature default 1.0, top_p default 0.95, docs.z.ai migrate guide) and
 * nvidia/nemotron-3.5-bolt-e4b (NIM's official example: temperature 1,
 * top_p 0.95 with tool calling + thinking on). Reasoning-era models are
 * tuned for ~1.0 sampling (o1/GPT-5 fix it there; lower temperatures risk
 * repetition loops in chain-of-thought — extra relevant now that the agent
 * loop has NO turn ceiling, T-ML34). top_p stays inside z.ai's recommended
 * 0.8–0.95 diversity band; temperatures stay ≤ 1.0 because the GLM line
 * historically caps there (DeepSeek's 1.3–1.5 creative-writing table is
 * NOT safe cross-model through the relay).
 *
 * A scheme bundles TWO per-request knobs the app already sends on every
 * chat call — no server reset is needed to swap one (validated against the
 * code path: ChatEngine reads these at send time; the agent server applies
 * them per request; reserved keys are stripped server-side):
 *   - [optionsJson] → Settings → Konfigurasi JSON (temperature, top_p, …),
 *   - [systemPrompt] → the agent's persona (null = the default coding agent).
 *
 * Pure Kotlin, JVM-unit-testable (mirrors DumpFormat.kt's contract style).
 */
data class Scheme(
    val id: String,
    val labelId: String,
    val labelEn: String,
    /** Raw JSON object string ("" = gateway defaults). Must survive the
     *  server's sanitizeOptions: no reserved keys, values in range. */
    val optionsJson: String,
    /** Agent persona; null = the default coding-agent prompt. */
    val systemPrompt: String?,
)

object Schemes {

    /** The default scheme: exactly the pre-T-ML28 behaviour. */
    const val DEFAULT_ID = "coding"

    val ALL: List<Scheme> = listOf(
        Scheme(
            id = "coding",
            labelId = "Coding",
            labelEn = "Coding",
            // Vendor-recommended baseline pinned explicitly (T-ML35): both
            // default models are reasoning/agentic models tuned at
            // temperature 1.0 + top_p 0.95 (z.ai GLM-5.3 defaults; NVIDIA's
            // official bolt-e4b tool-calling example). No penalties — token
            // repetition is legitimate in code.
            optionsJson = """{"temperature":1.0,"top_p":0.95}""",
            systemPrompt = null,
        ),
        Scheme(
            id = "brainstorming",
            labelId = "Brainstorming",
            labelEn = "Brainstorming",
            // Divergent ideation (T-ML35): temperature at the GLM-safe
            // ceiling, top_p at the top of z.ai's 0.8–0.95 diversity band,
            // presence 0.6 (OpenAI's classic "talk about new topics"
            // recommendation for ideation), frequency 0.3 for phrasing
            // variety without breaking coherence.
            optionsJson = """{"temperature":1.0,"top_p":0.95,"presence_penalty":0.6,"frequency_penalty":0.3}""",
            systemPrompt = """
                Kamu adalah partner brainstorming yang berjalan di Ubuntu 24.04 (proot) pada HP pengguna.
                Tugasmu membantu pengguna menggali dan memperluas ide — bukan langsung mengeksekusi kode.
                Utamakan: banyak alternatif (minimal 3 opsi berbeda), pertanyaan klarifikasi sebelum
                menyimpulkan, pro-kontra singkat per opsi, dan langkah kecil yang bisa langsung dicoba.
                Gunakan tools (bash, read_file, read_lines, write_file, list_dir) HANYA
                kalau pengguna meminta hasilnya disimpan atau diverifikasi. Jawab dalam
                bahasa yang dipakai pengguna.
            """.trimIndent(),
        ),
        Scheme(
            id = "menulis",
            labelId = "Menulis",
            labelEn = "Writing",
            // Editorial prose (T-ML35): temperature just under the default
            // for structure, top_p 0.95 for a rich-but-quality vocabulary
            // band, frequency 0.3 to fight verbatim phrasing in long-form
            // text. No presence penalty — prose must not hop topics.
            optionsJson = """{"temperature":0.8,"top_p":0.95,"frequency_penalty":0.3}""",
            systemPrompt = """
                Kamu adalah penulis dan editor yang berjalan di Ubuntu 24.04 (proot) pada HP pengguna.
                Utamakan prosa yang jernih dan terstruktur: alur logis, paragraf rapi, diksi sesuai
                tujuan (artikel, dokumen, catatan, pesan). Saat mengedit tulisan pengguna,
                pertahankan suara aslinya. Kode atau perintah hanya bila diminta atau memang bentuk
                keluarannya. Jawab dalam bahasa yang dipakai pengguna.
            """.trimIndent(),
        ),
        Scheme(
            id = "analisis",
            labelId = "Analisis",
            labelEn = "Analysis",
            // Systematic analysis (reviewed T-ML35 — already optimal, kept):
            // low temperature + narrow nucleus for determinism (DeepSeek's
            // coding/math 0.0 spirit), top_p at the floor of z.ai's band,
            // deep reasoning effort for evidence-first conclusions.
            optionsJson = """{"temperature":0.2,"top_p":0.8,"reasoning_effort":"high"}""",
            systemPrompt = """
                Kamu adalah analis sistematis yang berjalan di Ubuntu 24.04 (proot) pada HP pengguna.
                Pecah masalah menjadi langkah-langkah eksplisit, nyatakan asumsi, dan verifikasi klaim
                dengan bukti (data, log, atau percobaan) sebelum menyimpulkan. Bila butuh fakta dari
                perangkat atau file, periksa sendiri dengan tools (bash, read_file, read_lines,
                list_dir). Tandai ketidakpastian secara jujur — jangan mengarang angka. Jawab dalam
                bahasa yang dipakai pengguna.
            """.trimIndent(),
        ),
    )

    fun byId(id: String?): Scheme? = ALL.firstOrNull { it.id == id }

    /** Options JSON to persist into Prefs.requestOptions for a scheme. */
    fun storedOptions(scheme: Scheme): String = if (scheme.optionsJson.isEmpty()) "" else scheme.optionsJson

    /** The system prompt a scheme pins ("" = use the default coding prompt). */
    fun storedPrompt(scheme: Scheme): String = scheme.systemPrompt ?: ""
}

/** Folder-safe project names (T-ML28 #4: one project per folder). */
object ProjectNames {

    /** Default folder name when the user's input sanitizes to nothing. */
    const val FALLBACK = "proyek"

    private val SAFE_CHAR: (Char) -> Boolean = { c ->
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '_'
    }

    /**
     * Project display name → folder segment: ASCII letters/digits/._- only,
     * whitespace collapses to '-', runs of '-' collapse, no leading/trailing
     * '-' or '.', capped at 64 chars. Blank result falls back to "proyek".
     */
    fun sanitize(raw: String): String {
        var s = raw.trim().replace(Regex("\\s+"), "-")
        s = s.map { if (SAFE_CHAR(it)) it else '-' }.joinToString("")
        s = s.replace(Regex("-+"), "-").trim('-', '.')
        s = s.take(64).trim('-', '.')
        return s.ifBlank { FALLBACK }
    }
}

/** Absolute folder path of a project inside the agent's default workdir. */
fun projectDir(baseWorkdir: String, projectName: String): String {
    val base = baseWorkdir.trim().trimEnd('/')
    return if (base.isEmpty()) ProjectNames.sanitize(projectName)
    else base + "/" + ProjectNames.sanitize(projectName)
}

/**
 * Remaining-token display (T-ML28 #2: "dalam actual number") — the full
 * integer with Indonesian grouping, never an abbreviation: 19300000 →
 * "19.300.000". Pure so the unit test pins the contract.
 */
fun formatTokenCount(tokens: Long): String =
    java.text.NumberFormat.getIntegerInstance(java.util.Locale("id", "ID")).format(tokens)
