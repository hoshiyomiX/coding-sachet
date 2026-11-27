package id.codingsachet.mobile.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * STATUS KONEKSI GATEWAY real-time (T-ML73, owner order: "Fix dot colors
 * indikator ambigu. Only use 2 colors. Green = API connection gateway OK &
 * live, Red = lost connection to API gateway").
 *
 * Menggantikan semantik dot 3 warna per-model (hijau/merah/abu dari sweep
 * ModelStatus) yang ambigu: satu dot kini berarti KONEKSI — hanya 2 warna:
 *  - LIVE (hijau)  = gateway API provider aktif terjawab real-time;
 *  - LOST (merah)  = koneksi ke gateway terputus.
 *
 * Sumber state (semua menulis lewat [mark], dedupe aman dipanggil sering):
 *  1. Probe periodik [pingGateway] (AgentApi) — GET /models LANGSUNG ke
 *     provider, bypass server lokal, jadi jawabannya benar-benar jalur
 *     app→gateway. Dijalankan loop Compose (ChatScreen) — cepat 8 dtk saat
 *     LOST, santai 30 dtk saat LIVE.
 *  2. Feed real-time dari run chat (ChatEngine): event data apa pun dari
 *     stream = gateway upstream hidup (server hanya me-relay keluaran
 *     gateway); stream error (CONNECT/EOF/HTTP) = koneksi putus.
 *
 * Bebas android.* supaya JVM unit test bisa memeriksa kontrak 2-warnanya.
 */
object GatewayStatus {

    /** Hanya 2 keadaan — sengaja TIDAK ada "unknown": sebelum probe pertama
     *  berhasil, koneksi dianggap belum terbukti = LOST (merah). */
    enum class Conn { LIVE, LOST }

    // providerId -> flow status; Compose mengoleksikan ini untuk render.
    private val flows = ConcurrentHashMap<String, MutableStateFlow<Conn>>()

    fun flow(providerId: String): StateFlow<Conn> =
        flows.getOrPut(providerId) { MutableStateFlow(Conn.LOST) }

    fun conn(providerId: String): Conn = flow(providerId).value

    /** Satu pintu tulis untuk SEMUA sumber (probe, stream, test). */
    fun mark(providerId: String, live: Boolean) {
        val f = flows.getOrPut(providerId) { MutableStateFlow(Conn.LOST) }
        val next = if (live) Conn.LIVE else Conn.LOST
        if (f.value != next) f.value = next
    }

    /**
     * Loop probe periodik untuk provider aktif. [probe] return true saat
     * gateway menjawab. Interval adaptif: LOST dicoba ulang cepat (8 dtk)
     * supaya dot hijau kembali segera setelah jaringan pulih; LIVE cukup
     * dirawat 30 dtk. Provider/key dibaca ulang tiap putaran lewat [current]
     * — ganti provider di Settings langsung mengubah target probe.
     */
    fun monitor(
        scope: CoroutineScope,
        current: () -> Pair<String, String>, // (providerId, apiKey)
        probe: suspend (apiKey: String, providerId: String) -> Boolean,
    ) {
        scope.launch {
            while (isActive) {
                val (pid, key) = current()
                val ok = key.isNotBlank() && runCatching { probe(key, pid) }.getOrDefault(false)
                mark(pid, ok)
                delay(if (ok) 30_000L else 8_000L)
            }
        }
    }

    /** Test hook: forget everything. */
    fun reset() {
        flows.clear()
    }
}
