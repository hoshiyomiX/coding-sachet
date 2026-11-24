package id.codingsachet.mobile.data

import android.content.Context
import id.codingsachet.mobile.Logx
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** One function call the model asked for. */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val args: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonObject(emptyMap()),
)

/** A chat message, in the wire format the agent server understands. */
@Serializable
data class Msg(
    val role: String,            // "user" | "assistant" | "tool"
    val content: String = "",
    val thinking: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null,
    /** Model yang MENULIS pesan ini (T-ML69 #1: label orchestrator/
     *  executor). Null = pesan lama pra-update. Hanya diisi untuk pesan
     *  assistant; kedua engine (Pi dual-model & legacy) memilih model yang
     *  memulai run, event 'model' (handoff executor) menggantinya.
     *  Aman di kabel: kedua engine server memetakan field eksplisit
     *  (toOpenAiMessages/canonicalMsg) — kunci tambahan tidak pernah bocor
     *  ke gateway dan tidak mengubah hash sesi Pi. */
    val model: String? = null,
    /** Peran model penulis: "orchestrator" | "executor" (T-ML69 #1) —
     *  null = pesan lama / tidak diketahui. Disimpan bersama Msg agar
     *  label tetap benar walau konfigurasi JSON dual-model berubah. */
    val modelRole: String? = null,
)

@Serializable
data class Session(
    val id: String,
    var title: String,
    val created: Long,
    var model: String,
    /** Project name (T-ML28 #4): this chat works inside
     *  <agent-workdir>/<ProjectNames.sanitize(project)> — one project per
     *  folder. Null = legacy session on the server's default workdir. */
    val project: String? = null,
    val messages: MutableList<Msg> = mutableListOf(),
    /** Gateway-reported tokens spent in THIS session (T-ML29 usage meter),
     *  accumulated from the per-round usage events. */
    var tokensUsed: Long = 0,
) {
    fun wireHistory(): List<Msg> = messages.toList()
}

/** Sessions persisted as one JSON file each under filesDir/sessions. */
class ChatStore(ctx: Context) {
    private val dir: File = File(ctx.filesDir, "sessions").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val index = LinkedHashMap<String, Session>()
    // T-ML58 #3: persist() dipanggil PER EVENT SSE dari koroutine IO yang
    // tidak dijadwalkan — dua writeText bersamaan pada file yang sama
    // saling menimpa tengah-tulisan. Semua tulisan dilewatkan kunci ini.
    private val writeLock = Any()
    val sessions: List<Session> get() = index.values.sortedByDescending { it.created }

    init { reload() }

    fun reload() {
        index.clear()
        dir.listFiles { f -> f.extension == "json" }?.forEach { f ->
            runCatching { json.decodeFromString<Session>(f.readText()) }
                .onSuccess { index[it.id] = it }
                .onFailure { Logx.w("store", "sesi ${f.name} tidak terbaca: ${it.message?.take(100)}") }
        }
    }

    fun create(model: String, project: String? = null): Session {
        val s = Session(
            id = UUID.randomUUID().toString().replace("-", "").take(16),
            title = "",
            created = System.currentTimeMillis(),
            model = model,
            project = project?.trim()?.takeIf { it.isNotEmpty() },
        )
        index[s.id] = s
        save(s)
        return s
    }

    fun get(id: String?): Session? = id?.let { index[it] }

    fun save(s: Session) {
        // Best effort synchronous write; files are small. Call from a background thread.
        // T-ML58 #3: atomic + serialized (snapshotSession + writeSessionFile) —
        // tulis in-place yang lama mengtruncate dulu, jadi proses yang dibunuh
        // di tengah tulis (LMK/OEM killer saat app di-background) meninggalkan
        // JSON terpotong → reload membuang seluruh sesi → "chat terhapus".
        synchronized(writeLock) {
            runCatching { writeSessionFile(dir, snapshotSession(s), json) }
                .onFailure { Logx.e("store", "gagal menyimpan sesi ${s.id}", it) }
        }
    }

    fun delete(s: Session) {
        index.remove(s.id)
        File(dir, s.id + ".json").delete()
        // Sisa tulis atomik yang belum sempat di-rename ikut dibuang.
        File(dir, s.id + ".json.tmp").delete()
    }

    /** Keep a short, human title derived from the first user message. */
    fun autoTitle(s: Session) {
        if (s.title.isNotBlank()) return
        val first = s.messages.firstOrNull { it.role == "user" }?.content ?: return
        s.title = first.lineSequence().firstOrNull()?.take(48)?.ifBlank { "Chat" } ?: "Chat"
    }
}

/**
 * T-ML58 #3: snapshot sesi yang aman diserialisasi dari thread lain —
 * `messages` adalah list hidup yang dimutasi thread utama selama SSE
 * mengalir; iterasi langsung rawan CME (lesson T-ML42). Copy-nya dicoba
 * ulang sebentar — window rasanya mikrodetik, dan kegagalan snapshot
 * TIDAK pernah merusak file lama (tulis atomik lewat file sementara).
 */
internal fun snapshotSession(s: Session): Session {
    var last: Exception? = null
    repeat(3) {
        try {
            return s.copy(messages = ArrayList(s.messages))
        } catch (e: Exception) {
            last = e
        }
    }
    throw last ?: IllegalStateException("snapshot sesi ${s.id} gagal")
}

/**
 * T-ML58 #3: tulis atomik — konten ditulis ke file sementara lalu
 * di-rename menggantikan file sesi. `File.writeText` biasa membuka file
 * LAMA dengan truncate sebelum menulis: proses dibunuh di tengah tulis
 * menyisakan JSON terpotong yang dibuang seluruhnya saat reload. Rename
 * dalam satu direktori bersifat atomik — file lama selalu utuh sampai
 * penggantinya siap penuh.
 */
internal fun writeSessionFile(dir: File, s: Session, json: Json) {
    val dst = File(dir, s.id + ".json")
    val tmp = File(dir, s.id + ".json.tmp")
    tmp.writeText(json.encodeToString(s))
    if (!tmp.renameTo(dst)) {
        // Fallback teoretis (rename antar-filesystem) — salin lalu hapus.
        tmp.copyTo(dst, overwrite = true)
        tmp.delete()
    }
}
