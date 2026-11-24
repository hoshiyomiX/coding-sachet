package id.codingsachet.mobile.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T-ML70: pembangun seksi `"pi"` untuk options /api/chat — sumber tunggal
 * untuk ChatEngine (kirim) DAN preview Settings (tampil), jadi apa yang
 * dilihat user di preview PERSIS yang dikirim ke server.
 *
 * Konfigurasi dual-model engine Pi (T-ML68 #1): orchestrator merencanakan +
 * memutuskan tool, executor mengambil alih putaran berikutnya setelah putaran
 * tool pertama (via RPC set_model di pi-bridge). Kunci "pi" dipisahkan server
 * dari options legacy — tidak pernah bocor ke gateway.
 *
 * Aturan validasi (mirror parseDualConfig server, digandakan di sisi app
 * supaya kombinasi tak-valid tidak pernah dikirim — server tetap ber-WRN
 * sebagai jaring pengaman):
 *  - hanya aktif bila [piProviderId] == [activeProviderId] (batas v1: satu
 *    provider per run — models.json mendaftarkan kedua model di satu key);
 *  - keduanya kosong → null (tidak ada seksi pi, single-model);
 *  - orchestrator == executor → null (server menolak identik);
 *  - string dipangkas; kosong setelah trim diabaikan.
 */
object PiOptions {
    const val DEFAULT_ORCHESTRATOR = "ngodeai/glm-5.3-flash"
    const val DEFAULT_EXECUTOR = "ngodeai/minimax-m3.1-flash"
    const val DEFAULT_PROVIDER = "ngodeai"

    /** Objek `{ "pi": { orchestrator?, executor? } }`, atau null bila
     *  konfigurasi dual tidak berlaku untuk provider aktif. */
    fun build(
        activeProviderId: String,
        piProviderId: String,
        orchestrator: String,
        executor: String,
    ): JsonObject? {
        if (piProviderId.isBlank() || piProviderId != activeProviderId) return null
        val orch = orchestrator.trim()
        val exec = executor.trim()
        if (orch.isEmpty() && exec.isEmpty()) return null
        if (orch.isNotEmpty() && orch == exec) return null
        return buildJsonObject {
            put("pi", buildJsonObject {
                if (orch.isNotEmpty()) put("orchestrator", orch)
                if (exec.isNotEmpty()) put("executor", exec)
            })
        }
    }
}
