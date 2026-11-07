package id.aihubmix.mobile.data

import android.content.Context
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
)

@Serializable
data class Session(
    val id: String,
    var title: String,
    val created: Long,
    var model: String,
    val messages: MutableList<Msg> = mutableListOf(),
) {
    fun wireHistory(): List<Msg> = messages.toList()
}

/** Sessions persisted as one JSON file each under filesDir/sessions. */
class ChatStore(ctx: Context) {
    private val dir: File = File(ctx.filesDir, "sessions").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val index = LinkedHashMap<String, Session>()
    val sessions: List<Session> get() = index.values.sortedByDescending { it.created }

    init { reload() }

    fun reload() {
        index.clear()
        dir.listFiles { f -> f.extension == "json" }?.forEach { f ->
            runCatching { json.decodeFromString<Session>(f.readText()) }
                .onSuccess { index[it.id] = it }
        }
    }

    fun create(model: String): Session {
        val s = Session(
            id = UUID.randomUUID().toString().replace("-", "").take(16),
            title = "",
            created = System.currentTimeMillis(),
            model = model,
        )
        index[s.id] = s
        save(s)
        return s
    }

    fun get(id: String?): Session? = id?.let { index[it] }

    fun save(s: Session) {
        // Best effort synchronous write; files are small. Call from a background thread.
        runCatching { File(dir, s.id + ".json").writeText(json.encodeToString(s)) }
    }

    fun delete(s: Session) {
        index.remove(s.id)
        File(dir, s.id + ".json").delete()
    }

    /** Keep a short, human title derived from the first user message. */
    fun autoTitle(s: Session) {
        if (s.title.isNotBlank()) return
        val first = s.messages.firstOrNull { it.role == "user" }?.content ?: return
        s.title = first.lineSequence().firstOrNull()?.take(48)?.ifBlank { "Chat" } ?: "Chat"
    }
}
