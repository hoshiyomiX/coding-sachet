package id.codingsachet.mobile.ui

import id.codingsachet.mobile.Graph
import id.codingsachet.mobile.Logx
import id.codingsachet.mobile.api.AgentApi
import id.codingsachet.mobile.data.Msg
import id.codingsachet.mobile.data.Session
import id.codingsachet.mobile.data.ToolCall
import id.codingsachet.mobile.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
    /** Set while the agent server waits out the gateway rate limit
     *  (client-side 15 RPM cap or a 429 backoff) — seconds remaining. */
    val waiting: Int? = null,
    /** Set while the agent server retries the gateway connect with a fresh
     *  budget (cold VPN path) — the retry attempt number. */
    val reconnect: Int? = null,
    /** Which gateway is being retried ("APInex" / "NVIDIA NIM"), when the
     *  agent server names it — null keeps the generic "gateway" wording. */
    val reconnectTo: String? = null,
    /** Set while a server-death retry is pending (T-ML27 P3): the stream
     *  broke because the agent server was killed (exit 137 in the field),
     *  the runtime is restarting it and the turn will continue on its own. */
    val restarting: Boolean = false,
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

    // Set as soon as ANY streamed content or persisted message arrives this
    // turn. A stream that closes without one (server restarted by its
    // watchdog, connection dropped mid-connect) must surface an error —
    // silently vanishing reads as "the model never replied".
    private var receivedAnything = false

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns false (caller keeps the input) when a turn cannot start.
     *  Runs synchronously on the caller's (main) thread so the busy flag is
     *  visible before the next tap — a coroutine here once allowed a fast
     *  double-tap to start two streams. */
    fun send(text: String, model: String): Boolean {
        if (busy || text.isBlank() || model.isBlank()) return false
        session.messages += Msg(role = "user", content = text.trim())
        session.model = model
        Graph.chats.autoTitle(session)
        persist()
        receivedAnything = false
        turnStartedAt = System.currentTimeMillis()
        retryCount = 0
        turnCancelled = false
        live.value = LiveTurn()
        Logx.i("chat", "kirim session=${session.id} model=$model msgs=${session.messages.size}")
        startStream(model)
        return true
    }

    private var turnStartedAt = 0L

    // ---- server-death auto-retry (T-ML27 P3) ----
    // Field evidence: the agent server gets SIGKILLed mid-turn (exit 137,
    // kernel/OEM memory manager striking while apt-get runs in the guest).
    // The runtime restarts it in seconds; the chat history up to the kill is
    // already persisted (interrupted rounds heal server-side in
    // toOpenAiMessages) — so the turn can simply continue instead of dying
    // with a bare "stream error".
    private var retryCount = 0
    @Volatile private var retryPending = false
    // Set by stop() so a pending auto-retry (suspended while waiting for the
    // server) is abandoned instead of firing a new stream the user cancelled.
    @Volatile private var turnCancelled = false

    private fun startStream(model: String) {
        val key = Graph.prefs.apiKey
        if (key.isBlank()) {
            Logx.w("chat", "kirim diblokir: API key belum diatur")
            live.value = LiveTurn(busy = false, error = "API key belum diatur (Pengaturan)")
            return
        }
        call = Graph.api.chatStream(
            apiKey = key,
            provider = Graph.prefs.active,
            model = model,
            messages = session.wireHistory(),
            systemPrompt = SYSTEM_PROMPT,
            options = requestOptions(),
            onEvent = { ev -> scope.launch { handle(ev) } },
            onError = { msg, kind -> scope.launch { onStreamError(msg, kind) } },
            onClosed = { scope.launch { finish() } },
        )
    }

    /** Konfigurasi JSON (Settings): structured options + the extra object
     *  merged last. Blank/invalid values never block sending — the agent
     *  server re-sanitizes everything anyway. */
    private fun requestOptions(): JsonObject? {
        val structured = parsePref(Graph.prefs.requestOptions) ?: return null
        val extra = parsePref(Graph.prefs.requestOptionsExtra)
        val merged = buildJsonObject {
            structured.forEach { (k, v) -> put(k, v) }
            extra?.forEach { (k, v) -> put(k, v) }
        }
        return merged.takeIf { it.isNotEmpty() }
    }

    private fun parsePref(raw: String): JsonObject? =
        if (raw.isBlank()) null
        else runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject

    private suspend fun handle(ev: JsonObject) {
        when (ev["type"]?.jsonPrimitive?.content) {
            "think" -> { receivedAnything = true; live.value = live.value?.copy(thinking = live.value!!.thinking + ev.str("delta"), waiting = null, reconnect = null, reconnectTo = null) }
            "text" -> { receivedAnything = true; live.value = live.value?.copy(text = live.value!!.text + ev.str("delta"), waiting = null, reconnect = null, reconnectTo = null) }
            "netretry" -> {
                // The agent server is retrying the gateway connect with a
                // fresh budget (cold VPN path) — show it instead of silence.
                val n = ev["attempt"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                val to = ev["provider"]?.jsonPrimitive?.contentOrNull
                live.value = live.value?.copy(reconnect = n, reconnectTo = to?.takeIf { it.isNotBlank() }, waiting = null)
            }
            "ratelimit" -> {
                // The agent server is waiting out the gateway RPM window
                // (hardcoded 15 RPM client cap, or a 429 backoff).
                val ms = ev["waitMs"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                live.value = live.value?.copy(waiting = ((ms + 999.0) / 1000.0).toInt().coerceAtLeast(1), reconnect = null)
            }
            "tool" -> {
                receivedAnything = true
                val card = ToolCardUi(
                    id = ev.str("id"),
                    name = ev.str("name"),
                    args = pretty(ev["args"]),
                )
                live.value = live.value?.copy(cards = live.value!!.cards + card, waiting = null, reconnect = null)
            }
            "toolend" -> {
                receivedAnything = true
                live.value = live.value?.let { l ->
                    l.copy(cards = l.cards.map {
                        if (it.id == ev.str("id")) it.copy(output = ev.str("output"), ok = ev.boolean("ok")) else it
                    }, waiting = null, reconnect = null)
                }
            }
            "message" -> {
                val m = ev["msg"]?.jsonObject ?: return
                receivedAnything = true
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
                receivedAnything = true
                session.messages += Msg(
                    role = "tool",
                    content = m.str("content"),
                    toolCallId = m.str("toolCallId"),
                    name = m.str("name"),
                )
                persist()
            }
            "error" -> {
                Logx.e("chat", "event error dari server: ${ev.str("message").take(160)}")
                live.value = live.value?.copy(error = ev.str("message"))
            }
        }
    }

    /** A stream error arrived. Server-death classes (EOF mid-stream, connect
     *  refused) get ONE automatic retry after the runtime brings the server
     * back; everything else surfaces as before. */
    private suspend fun onStreamError(msg: String, kind: AgentApi.ErrorKind) {
        val canRetry = retryCount < 1 &&
            (kind == AgentApi.ErrorKind.EOF || kind == AgentApi.ErrorKind.CONNECT)
        if (!canRetry) {
            val final = if (kind == AgentApi.ErrorKind.EOF) {
                // The server died mid-turn and the retry budget is spent.
                "Server agent mati saat memproses (kemungkinan dimatikan sistem). " +
                    "Tunggu indikator server hijau lalu kirim ulang pesanmu."
            } else msg
            live.value = live.value?.copy(error = final, restarting = false)
            return
        }
        retryCount++
        retryPending = true
        Logx.w("chat", "stream putus (${kind.name}) — server mati; tunggu pulih lalu lanjut otomatis (percobaan ulang $retryCount/1)")
        live.value = live.value?.copy(restarting = true, error = null)
        val recovered = awaitServerRecovery()
        retryPending = false
        if (turnCancelled) return
        if (!recovered) {
            Logx.e("chat", "server tidak pulih dalam batas waktu — berhenti mencoba ulang")
            live.value = live.value?.copy(
                busy = false,
                restarting = false,
                error = "Server agent tidak kembali dalam 45 detik. Cek status server di Pengaturan, lalu kirim ulang pesanmu.",
            )
            persist()
            onTurnFinished?.invoke()
            return
        }
        Logx.i("chat", "server pulih — melanjutkan giliran secara otomatis")
        live.value = live.value?.copy(restarting = false)
        startStream(session.model)
    }

    /** Wait (bounded) for the runtime to report the agent server healthy. */
    private suspend fun awaitServerRecovery(timeoutMs: Long = 45_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && live.value != null && !turnCancelled) {
            if (Graph.runtime.state.value is RuntimeState.Running) return true
            delay(500)
        }
        return !turnCancelled && Graph.runtime.state.value is RuntimeState.Running
    }

    private fun finish() {
        if (retryPending) return // a server-death retry is in flight; the turn continues
        val l = live.value ?: return
        val durMs = if (turnStartedAt > 0) System.currentTimeMillis() - turnStartedAt else 0
        if (l.error != null) {
            Logx.w("chat", "giliran selesai DGN ERROR setelah ${durMs}ms: ${l.error?.take(140)}")
            live.value = l.copy(busy = false)
        } else if (!receivedAnything) {
            if (retryCount < 1) {
                // The stream closed before a single event arrived while the
                // server was restarting mid-connect — same recovery path as
                // an EOF, instead of a dead-end error (T-ML27 P3).
                retryCount++
                retryPending = true
                Logx.w("chat", "stream tertutup tanpa event setelah ${durMs}ms — coba pulihkan otomatis")
                live.value = l.copy(restarting = true)
                scope.launch {
                    val recovered = awaitServerRecovery()
                    retryPending = false
                    if (turnCancelled) return@launch
                    if (recovered && live.value != null) {
                        Logx.i("chat", "server pulih — melanjutkan giliran secara otomatis")
                        live.value = live.value?.copy(restarting = false)
                        startStream(session.model)
                    } else {
                        live.value = live.value?.copy(
                            busy = false,
                            restarting = false,
                            error = "Koneksi ke server agent terputus sebelum jawaban diterima — coba kirim ulang pesanmu.",
                        )
                        persist()
                        onTurnFinished?.invoke()
                    }
                }
                return
            }
            Logx.e("chat", "stream tertutup tanpa satu pun event setelah ${durMs}ms")
            live.value = l.copy(busy = false, error = "Koneksi ke server agent terputus sebelum jawaban diterima — coba kirim ulang pesanmu.")
        } else {
            Logx.i("chat", "giliran selesai bersih ${durMs}ms (${session.messages.size} pesan tersimpan)")
            live.value = null
        }
        persist()
        onTurnFinished?.invoke()
    }

    fun stop() {
        Logx.i("chat", "stop ditekan — batalkan stream session=${session.id}")
        turnCancelled = true
        if (retryPending) {
            // Also abandon an in-flight auto-retry: the suspended recovery
            // coroutine sees the flag (or the cleared turn) and stands down.
            retryPending = false
            live.value = null
        }
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
            Folder kerja utama: /sdcard/coding-sachet di penyimpanan bersama HP (jika tidak
            tersedia, /root/work). Pastikan dengan pwd sebelum membuat file proyek.
            Kamu punya tools: bash, read_file, write_file, list_dir.
            Bekerja sistematis: lihat dulu (list_dir/read_file), baru ubah (write_file/bash),
            lalu verifikasi dengan menjalankan kode. Jawab dalam bahasa yang dipakai pengguna.
            Ringkas saat mengobrol, teliti saat menulis kode.
        """.trimIndent()
    }
}
