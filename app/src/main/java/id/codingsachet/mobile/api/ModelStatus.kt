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

    /** Billing tier dari katalog APInex (T-ML49, probe langsung 2026-11-20):
     *  FREE = jatah harian (intrinsik free/ DAN allowFree) · SUBSCRIPTION =
     *  allowFree:false — HTTP 402 "only available with a subscription" ·
     *  TOPUP = premium, billed dari dompet USD (pay-as-you-go). */
    enum class Tier { FREE, SUBSCRIPTION, TOPUP }

    data class Entry(val state: State, val free: Boolean?, val tier: Tier?, val at: Long)

    /** How long a verification stays trusted before a re-sweep is wanted. */
    const val STALE_MS = 12L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    // providerId -> (model -> Entry); replaced wholesale on load, merged
    // per result during a sweep.
    private val store = ConcurrentHashMap<String, MutableMap<String, Entry>>()

    // providerId -> timestamp of the last sweep that ran to COMPLETION
    // (T-ML50). Entries alone must NOT count: an interrupted sweep (user
    // cancel, double connect-fail abort, app killed mid-run) leaves a few
    // real measurements behind, and the old isFresh — max(entry.at) —
    // treated that partial store as "freshly checked", silently blocking
    // every re-sweep for 12h while the rest of the catalogue stayed
    // UNKNOWN (device evidence 2026-11-20: NIM picker showed ONLY
    // nvidia/nemotron-3.5-bolt-e4b, sweep never started).
    private val sweptAtMap = ConcurrentHashMap<String, Long>()

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

    /** Tier billing sebuah model (null = sweep belum tahu — probe path atau
     *  id tak ada di katalog). Sumber: flag `tier` event sweep (katalog
     *  publik APInex), T-ML49. */
    fun tierOf(providerId: String, model: String): Tier? =
        entry(providerId, model)?.tier

    /**
     * Free = worth the badge. T-ML48: a provider with an INTRINSIC free
     * tier (APInex free/ prefix) decides free-ness BY NAME — the sweep's
     * free flag is only trusted for providers without a prefix (NIM:
     * every model that answers is free to use).
     *
     * T-ML49 (probe langsung 2026-11-20): prefix free/ TIDAK lagi menjamin
     * gratis — 8 model free/ kini allowFree:false dan menolak key
     * free-plan dengan 402 "subscription". Entri sweep tier=SUBSCRIPTION
     * meng-OVERRIDE prefix: model itu keluar dari filter "gratis saja"
     * dan badge-nya jadi "langganan". Enri tanpa tier (belum di-sweep /
     * probe fallback) tetap pakai prefix — perilaku lama.
     */
    fun isFree(provider: Provider, model: String): Boolean {
        if (provider.isFreeByPrefix(model)) {
            return entry(provider.id, model)?.tier != Tier.SUBSCRIPTION
        }
        return provider.freeModelPrefix == null && entry(provider.id, model)?.free == true
    }

    /**
     * Does this model belong in the "free only" (gratis saja) list?
     * (T-ML50.) Providers WITHOUT an intrinsic free prefix (NIM) cannot
     * know free-ness before a probe actually answers — an unchecked model
     * has free:null, so the old predicate hid EVERYTHING until the sweep
     * finished, and the picker opened showing one row (or none) for
     * minutes. Now: for prefix-less providers an unchecked/uncertain model
     * stays VISIBLE (gray dot already says "not checked yet") and only a
     * CONFIRMED-DEAD model is filtered out. Prefix providers (APInex)
     * keep the strict tier-based behavior.
     *
     * T-ML69 #2 (owner order: "fix filter which ngodeai model free &
     * model topup/pay"): a CONFIRMED-PAID model never passes — the lenient
     * prefix-less rule above was written for NIM/seek (whole service free,
     * alive ≈ free) but NgodeAI is a prepaid gateway where paid models are
     * alive too: its authenticated-catalogue sweep reports tier=topup /
     * free=false for them, and those verdicts now exclude the model from
     * the free-only list. Unchecked models stay visible as before.
     */
    fun passesFreeFilter(provider: Provider, model: String): Boolean {
        if (isFree(provider, model)) return true
        val e = entry(provider.id, model)
        if (e?.tier == Tier.TOPUP || e?.tier == Tier.SUBSCRIPTION) return false
        if (e?.free == false) return false
        return provider.freeModelPrefix == null && e?.state != State.DEAD
    }

    /** Record one sweep result and wake the listeners. */
    fun apply(providerId: String, model: String, state: State, free: Boolean?, tier: Tier? = null, now: Long = System.currentTimeMillis()): Entry {
        val e = Entry(state, free, tier, now)
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

    /** Record the completion of a FULL sweep (T-ML50) — only the genuine
     *  'done' event of verifyModels (server sends a bare done after an
     *  error/cancel abort; those must NOT mark the provider fresh). */
    fun markSwept(providerId: String, now: Long = System.currentTimeMillis()) {
        sweptAtMap[providerId] = now
    }

    /** When the last COMPLETED sweep finished (null = never completed). */
    fun sweptAt(providerId: String): Long? = sweptAtMap[providerId]

    /** True when a full sweep's results are recent enough to trust.
     *  T-ML50: freshness = a completed sweep, NOT any single entry — a
     *  partial store from an interrupted sweep must re-sweep on the next
     *  picker open (orderForSweep warm-starts from what it already knows). */
    fun isFresh(providerId: String, now: Long = System.currentTimeMillis()): Boolean =
        sweptAtMap[providerId]?.let { now - it < STALE_MS } == true

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
                    put("t", e.tier?.let { JsonPrimitive(it.name) } ?: JsonNull)
                    put("at", e.at)
                })
            }
        }
        return buildJsonObject {
            put("v", 3)
            put("doneAt", sweptAtMap[providerId]?.let { JsonPrimitive(it) } ?: JsonNull)
            put("models", models)
        }.toString()
    }

    /** Load persisted JSON; a malformed payload is ignored (empty store).
     *  T-ML49: v1 payloads (tanpa tier) sengaja DITOLAK — entri lama bisa
     *  meng-klaim 8 model langganan sebagai gratis; v2/v3 membawa tier.
     *  T-ML50: v2 (tanpa doneAt) dimuat TAPI dianggap belum pernah
     *  selesai di-sweep — partial store lama tidak lagi memblokir sweep. */
    fun fromJson(providerId: String, text: String) {
        if (text.isBlank()) return
        runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val v = (root["v"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (v == null || v < 2 || v > 3) return
            val doneAt = (root["doneAt"] as? JsonPrimitive)?.content?.toLongOrNull()
            if (doneAt != null) sweptAtMap[providerId] = doneAt
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
                    val tier = runCatching {
                        Tier.valueOf((o["t"] as? JsonPrimitive)?.content ?: "")
                    }.getOrNull()
                    val at = (o["at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                    put(id, Entry(state, free, tier, at))
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
        sweptAtMap.clear()
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
                            val tier = when ((ev["tier"] as? JsonPrimitive)?.content) {
                                "free" -> ModelStatus.Tier.FREE
                                "subscription" -> ModelStatus.Tier.SUBSCRIPTION
                                "topup" -> ModelStatus.Tier.TOPUP
                                else -> null
                            }
                            ModelStatus.apply(provider.id, model, ok, free, tier)
                            if (results.incrementAndGet() % 10 == 0) persist(provider.id)
                        }
                    }
                    "done" -> {
                        // T-ML50: only a GENUINE completion marks the
                        // provider fresh. The server sends done WITH counts
                        // ({ok,dead,unknown}) on success but a BARE done
                        // after an error/cancel abort — the bare one must
                        // not set the completion marker, or the partial
                        // store would block re-sweeps for 12h again.
                        val complete = ev.containsKey("ok")
                        if (complete) ModelStatus.markSwept(provider.id)
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
