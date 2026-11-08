package id.codingsachet.mobile.ui

import id.codingsachet.mobile.Graph
import id.codingsachet.mobile.api.AgentApi
import id.codingsachet.mobile.data.Msg
import id.codingsachet.mobile.data.Session
import id.codingsachet.mobile.data.ToolCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call

/** One expandable tool card in the chat. */
data class ToolCardUi(
    val id: String,
    val name: String,
    val args: String,
    val output: String? = null,
    val ok: Boolean? = null,   // null = still running
)

/** What is being streamed right now, below the persisted history. */
data class LiveTurn(
    val thinking: String = "",
    val text: String = "",
    val cards: List<ToolCardUi> = emptyList(),
    val error: String? = null,
    val busy: Boolean = true,
)

/**
 * Drives one session: sends history to the agent server and folds the
 * SSE events into [live] plus persisted messages.
 */
class ChatEngine(val session: Session) {

    val live = MutableStateFlow<LiveTurn?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var call: Call? = null

    /** Notified after a turn finishes (success or error) — the screen uses
     *  it to refresh the remaining-token chip. */
    var onTurnFinished: (() -> Unit)? = null

    // A finished-but-errored turn keeps `live` non-null so the error stays
    // visible; it must NOT block the next send. Only an in-flight turn is
    // busy. (The old `live.value != null` check froze the engine forever
    // after the first error — the send button then ate the input silently.)
    val busy: Boolean get() = live.value?.busy == true

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns false (caller keeps the input) when a turn cannot start. */
    fun send(text: String, model: String): Boolean {
        if (busy || text.isBlank() || model.isBlank()) return false
        scope.launch {
            session.messages += Msg(role = "user", content = text.trim())
            session.model = model
            Graph.chats.autoTitle(session)
            persist()
            live.value = LiveTurn()
            startStream(model)
        }
        return true
    }

    private fun startStream(model: String) {
        val key = Graph.prefs.apiKey
        if (key.isBlank()) {
            live.value = LiveTurn(busy = false, error = "API key belum diatur (Pengaturan)")
            return
        }
        call = Graph.api.chatStream(
            apiKey = key,
            provider = Graph.prefs.active,
            model = model,
            messages = session.wireHistory(),
            systemPrompt = SYSTEM_PROMPT,
            onEvent = { ev -> scope.launch { handle(ev) } },
            onError = { msg -> scope.launch { live.value = live.value?.copy(error = msg) } },
            onClosed = { scope.launch { finish() } },
        )
    }

    private suspend fun handle(ev: JsonObject) {
        when (ev["type"]?.jsonPrimitive?.content) {
            "think" -> live.value = live.value?.copy(thinking = live.value!!.thinking + ev.str("delta"))
            "text" -> live.value = live.value?.copy(text = live.value!!.text + ev.str("delta"))
            "tool" -> {
                val card = ToolCardUi(
                    id = ev.str("id"),
                    name = ev.str("name"),
                    args = pretty(ev["args"]),
                )
                live.value = live.value?.copy(cards = live.value!!.cards + card)
            }
            "toolend" -> {
                live.value = live.value?.let { l ->
                    l.copy(cards = l.cards.map {
                        if (it.id == ev.str("id")) it.copy(output = ev.str("output"), ok = ev.boolean("ok")) else it
                    })
                }
            }
            "message" -> {
                val m = ev["msg"]?.jsonObject ?: return
                session.messages += Msg(
                    role = m.str("role"),
                    content = m.str("content"),
                    thinking = m.strOrNull("thinking"),
                    toolCalls = m["toolCalls"]?.jsonArray?.map { tc ->
                        val o = tc.jsonObject
                        ToolCall(id = o.str("id"), name = o.str("name"), args = o["args"] ?: JsonObject(emptyMap()))
                    },
                )
                // The streamed text and cards are now part of the persisted
                // history; clear the live copies so they are not shown twice.
                live.value = live.value?.copy(thinking = "", text = "", cards = emptyList())
                persist()
            }
            "toolresult" -> {
                val m = ev["msg"]?.jsonObject ?: return
                session.messages += Msg(
                    role = "tool",
                    content = m.str("content"),
                    toolCallId = m.str("toolCallId"),
                    name = m.str("name"),
                )
                persist()
            }
            "error" -> live.value = live.value?.copy(error = ev.str("message"))
        }
    }

    private fun finish() {
        val l = live.value ?: return
        if (l.error != null) {
            live.value = l.copy(busy = false)
        } else {
            live.value = null
        }
        persist()
        onTurnFinished?.invoke()
    }

    fun stop() {
        call?.cancel()
    }

    fun persist() {
        scope.launch(Dispatchers.IO) { Graph.chats.save(session) }
    }

    fun dispose() {
        call?.cancel()
        onTurnFinished = null
        scope.cancel()
    }

    private val prettyJson = Json { prettyPrint = true }

    private fun pretty(el: kotlinx.serialization.json.JsonElement?): String = when (el) {
        null, kotlinx.serialization.json.JsonNull -> ""
        is JsonPrimitive -> el.content
        else -> runCatching { prettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), el) }
            .getOrDefault(el.toString())
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content ?: ""
    private fun JsonObject.strOrNull(key: String): String? = this[key]?.jsonPrimitive?.content
    private fun JsonObject.boolean(key: String): Boolean = this[key]?.jsonPrimitive?.content == "true"

    companion object {
        val SYSTEM_PROMPT = """
            Kamu adalah agent coding yang berjalan di Ubuntu 24.04 (proot) pada HP pengguna.
            Folder kerja: /root/work. Kamu punya tools: bash, read_file, write_file, list_dir.
            Bekerja sistematis: lihat dulu (list_dir/read_file), baru ubah (write_file/bash),
            lalu verifikasi dengan menjalankan kode. Jawab dalam bahasa yang dipakai pengguna.
            Ringkas saat mengobrol, teliti saat menulis kode.
        """.trimIndent()
    }
}
