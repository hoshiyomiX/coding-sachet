package id.codingsachet.mobile.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pembangun seksi `"oc"` untuk options /api/chat — sumber tunggal untuk
 * ChatEngine (kirim) DAN preview Settings (tampil), jadi apa yang dilihat
 * user di preview PERSIS yang dikirim ke server.
 *
 * Konfigurasi dual-model engine OpenCode (oc-bridge.js): orchestrator
 * merencanakan + memutuskan tool, executor mengambil alih putaran berikutnya
 * setelah putaran tool pertama (via POST /api/session/{id}/model di
 * oc-bridge.js). Kunci "oc" dipisahkan server dari options legacy — tidak
 * pernah bocor ke gateway.
 *
 * Aturan validasi (mirror parseDualConfig di oc-bridge.js, digandakan di
 * sisi app supaya kombinasi tak-valid tidak pernah dikirim — server tetap
 * ber-WRN sebagai jaring pengaman):
 *  - hanya aktif bila [ocProviderId] == [activeProviderId] (batas v1: satu
 *    provider per run — models.json mendaftarkan kedua model di satu key);
 *  - keduanya kosong → null (tidak ada seksi oc, single-model);
 *  - orchestrator == executor → null (server menolak identik);
 *  - string dipangkas; kosong setelah trim diabaikan.
 */
object OcOptions {
    const val DEFAULT_ORCHESTRATOR = "free/glm-5.3-flash"
    const val DEFAULT_EXECUTOR = "free/glm-5.3-flash"
    const val DEFAULT_PROVIDER = "apinex"

    /** Objek `{ "oc": { orchestrator?, executor? } }`, atau null bila
     *  konfigurasi dual tidak berlaku untuk provider aktif. */
    fun build(
        activeProviderId: String,
        ocProviderId: String,
        orchestrator: String,
        executor: String,
    ): JsonObject? {
        if (ocProviderId.isBlank() || ocProviderId != activeProviderId) return null
        val orch = orchestrator.trim()
        val exec = executor.trim()
        if (orch.isEmpty() && exec.isEmpty()) return null
        if (orch.isNotEmpty() && orch == exec) return null
        return buildJsonObject {
            put("oc", buildJsonObject {
                if (orch.isNotEmpty()) put("orchestrator", orch)
                if (exec.isNotEmpty()) put("executor", exec)
            })
        }
    }
}
