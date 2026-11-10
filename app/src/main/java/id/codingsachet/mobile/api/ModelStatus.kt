package id.codingsachet.mobile.api

import id.codingsachet.mobile.Logx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * RUNTIME model status (T-ML26, owner order: "Providers.kt mengecek actual
 * bukan dari membaca hardcoded list").
 *
 * The picker's green/red dots and the free badges read THIS store, which is
 * filled by live verification sweeps against the real gateway — never by a
 * list baked into the APK. An entry is what the gateway ACTUALLY answered
 * at [Entry.at]; after [STALE_MS] the data is considered old and the next
 * picker open starts a fresh sweep.
 *
 * Kept free of android.* imports so JVM unit tests can exercise it
 * directly; [ModelVerify] below is the thin app-side orchestrator.
 */
object ModelStatus {

    /** Dot states: OK = answered live · DEAD = refused/hung · UNKNOWN = not
     *  checked yet (fresh install, mid-sweep, or the probe could not tell). */
    enum class State { OK, DEAD, UNKNOWN }

    data class Entry(val state: State, val free: Boolean?, val at: Long)

    /** How long a verification stays trusted before a re-sweep is wanted. */
    const val STALE_MS = 12L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    // providerId -> (model -> Entry); replaced wholesale on load, merged
    // per result during a sweep.
    private val store = ConcurrentHashMap<String, MutableMap<String, Entry>>()

    // Compose screens subscribe here to re-render as sweep results arrive.
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun listen(l: (String) -> Unit) {
        listeners.add(l)
    }

    fun unlisten(l: (String) -> Unit) {
        listeners.remove(l)
    }

    /** Wake every listener for [providerId] (result merges call this too). */
    fun notifyChanged(providerId: String) {
        listeners.forEach { runCatching { it(providerId) } }
    }

    /** Read-only copy for rendering. */
    fun snapshot(providerId: String): Map<String, Entry> =
        store[providerId]?.toMap() ?: emptyMap()

    fun entry(providerId: String, model: String): Entry? = store[providerId]?.get(model)

    /** Dot state for a model: UNKNOWN until a sweep says otherwise. */
    fun stateOf(providerId: String, model: String): State = entry(providerId, model)?.state ?: State.UNKNOWN

    /**
     * Free = worth the badge: the gateway's intrinsic free/ prefix (APInex)
     * OR the live sweep marked it free (NIM: every model that answers is
     * free to use; APInex: the public catalogue's allowFree flag).
     */
    fun isFree(provider: Provider, model: String): Boolean =
        provider.isFreeByPrefix(model) || entry(provider.id, model)?.free == true

    /** Record one sweep result and wake the listeners. */
    fun apply(providerId: String, model: String, state: State, free: Boolean?, now: Long = System.currentTimeMillis()): Entry {
        val e = Entry(state, free, now)
        store.computeIfAbsent(providerId) { ConcurrentHashMap() }[model] = e
        notifyChanged(providerId)
        return e
    }

    /** Replace a provider's whole store (loading persisted JSON). */
    fun replaceAll(providerId: String, entries: Map<String, Entry>) {
        store[providerId] = ConcurrentHashMap(entries)
        notifyChanged(providerId)
    }

    /** Newest verification timestamp for a provider (null = never swept). */
    fun lastCheckedAt(providerId: String): Long? =
        store[providerId]?.values?.maxOfOrNull { it.at }

    /** True when a full sweep's results are recent enough to trust. */
    fun isFresh(providerId: String, now: Long = System.currentTimeMillis()): Boolean =
        lastCheckedAt(providerId)?.let { now - it < STALE_MS } == true

    /**
     * Sweep order (warm start): the currently-selected model first (the one
     * the user is most likely to ask about), then previously-OK models (a
     * green dot reappears within seconds), then everything else in the
     * gateway's own order. Stable within each group.
     */
    fun orderForSweep(models: List<String>, providerId: String, current: String?): List<String> {
        val cached = store[providerId] ?: emptyMap()
        val head = listOfNotNull(current?.takeIf { it in models })
        val ok = models.filter { it !in head && cached[it]?.state == State.OK }
        val rest = models.filter { it !in head && cached[it]?.state != State.OK }
        return head + ok + rest
    }

    // ---------- persistence (compact JSON in Prefs) ----------

    fun toJson(providerId: String): String {
        val models = buildJsonObject {
            store[providerId]?.forEach { (id, e) ->
                put(id, buildJsonObject {
                    put("s", e.state.name)
                    put("f", e.free?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("at", e.at)
                })
            }
        }
        return buildJsonObject {
            put("v", 1)
            put("models", models)
        }.toString()
    }

    /** Load persisted JSON; a malformed payload is ignored (empty store). */
    fun fromJson(providerId: String, text: String) {
        if (text.isBlank()) return
        runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val models = root["models"] as? JsonObject ?: return
            val parsed = buildMap {
                models.forEach { (id, v) ->
                    val o = v as? JsonObject ?: return@forEach
                    val state = runCatching {
                        State.valueOf((o["s"] as? JsonPrimitive)?.content ?: "")
                    }.getOrDefault(State.UNKNOWN)
                    val fEl = o["f"]
                    val free = when {
                        fEl == null || fEl is JsonNull -> null
                        (fEl as? JsonPrimitive)?.content == "true" -> true
                        else -> false
                    }
                    val at = (o["at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                    put(id, Entry(state, free, at))
                }
            }
            replaceAll(providerId, parsed)
        }.onFailure {
            Logx.w("api", "status model $providerId gagal dibaca (${it.message?.take(60)}) — mulai kosong")
        }
    }

    /** Test hook: forget everything. */
    fun reset() {
        store.clear()
    }
}

/**
 * App-side sweep orchestrator (T-ML26): runs ONE live verification per
 * provider at a time through [AgentApi.verifyModelsStream], merges the SSE
 * results into [ModelStatus] and persists them (throttled) via [persist].
 * Lives at Graph level so a sweep keeps running while the model picker is
 * closed — the next open finds the dots already filled in.
 */
class ModelVerify(
    private val api: AgentApi,
    private val persist: (providerId: String) -> Unit,
) {
    private class Sweep(val call: Call, val total: AtomicInteger, val results: AtomicInteger)

    private val sweeps = ConcurrentHashMap<String, Sweep>()

    fun isRunning(providerId: String): Boolean = sweeps.containsKey(providerId)

    /** Progress of a running sweep: results so far / total (null = idle). */
    fun progress(providerId: String): Pair<Int, Int>? =
        sweeps[providerId]?.let { Pair(it.results.get(), it.total.get()) }

    /** Stop a running sweep (cancel button / provider switch). Partial
     *  results stay in the store — they were really measured. */
    fun cancel(providerId: String) {
        sweeps.remove(providerId)?.call?.cancel()
    }

    /**
     * Start a sweep when one is warranted. Skips when a sweep is already
     * running; without [force] it also skips while the stored results are
     * still fresh. The model list should be the list the picker displays,
     * in gateway order — [ModelStatus.orderForSweep] warm-starts it.
     */
    fun ensure(provider: Provider, apiKey: String, models: List<String>, current: String?, force: Boolean = false) {
        if (models.isEmpty() || isRunning(provider.id)) return
        if (!force && ModelStatus.isFresh(provider.id)) return
        val ordered = ModelStatus.orderForSweep(models, provider.id, current)
        val results = AtomicInteger(0)
        val total = AtomicInteger(ordered.size)
        val finished = AtomicBoolean(false)
        Logx.i("api", "verifikasi model live ${provider.label}: ${ordered.size} model")
        val call = api.verifyModelsStream(
            apiKey = apiKey,
            provider = provider,
            models = ordered,
            onEvent = { ev ->
                when ((ev["type"] as? JsonPrimitive)?.content) {
                    "start" -> {
                        (ev["total"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { total.set(it) }
                        ModelStatus.notifyChanged(provider.id)
                    }
                    "result" -> {
                        val model = (ev["model"] as? JsonPrimitive)?.content
                        if (model != null) {
                            val ok = when ((ev["ok"] as? JsonPrimitive)?.content) {
                                "true" -> ModelStatus.State.OK
                                "false" -> ModelStatus.State.DEAD
                                else -> ModelStatus.State.UNKNOWN
                            }
                            val free = when ((ev["free"] as? JsonPrimitive)?.content) {
                                "true" -> true
                                "false" -> false
                                else -> null
                            }
                            ModelStatus.apply(provider.id, model, ok, free)
                            if (results.incrementAndGet() % 10 == 0) persist(provider.id)
                        }
                    }
                    "done" -> {
                        persist(provider.id)
                        ModelStatus.notifyChanged(provider.id)
                    }
                }
            },
            onError = { msg ->
                Logx.w("api", "verifikasi model ${provider.label}: ${msg?.take(120) ?: "gagal"}")
                ModelStatus.notifyChanged(provider.id)
            },
            onClosed = {
                finished.set(true)
                sweeps.remove(provider.id)
                persist(provider.id)
                ModelStatus.notifyChanged(provider.id)
            },
        )
        // The APInex catalogue sweep can finish before verifyModelsStream
        // even returns — only register when it is still alive.
        if (!finished.get()) sweeps[provider.id] = Sweep(call, total, results)
    }
}
