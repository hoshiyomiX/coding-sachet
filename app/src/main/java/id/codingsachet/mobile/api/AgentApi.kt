package id.codingsachet.mobile.api

import id.codingsachet.mobile.BuildConfig
import id.codingsachet.mobile.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for the local agent server (127.0.0.1) and for key validation
 * against the selected provider (APInex or NVIDIA NIM). Chat and model
 * requests carry X-Sachet-Key + X-Sachet-Provider; the server routes them
 * to the right gateway.
 */
class AgentApi(private val port: () -> Int) {

    val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** For calls chained through the guest (its network path can cold-start
     *  slowly: DNS + TLS inside proot on mobile networks). Shares the pool. */
    private val slowClient: OkHttpClient = client.newBuilder()
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    /** No read timeout: chat responses are long-lived SSE streams. */
    private val sseClient: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun baseUrl(): String = "http://127.0.0.1:${port()}"

    // ---------- health ----------

    /** Returns the server version string, or null when it does not answer. */
    suspend fun health(): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(baseUrl() + "/health").build()).execute().use { r ->
                if (!r.isSuccessful) {
                    Logx.d("api", "health HTTP ${r.code}")
                    return@withContext null
                }
                val o = json.parseToJsonElement(r.body.string()).jsonObject
                o["version"]?.jsonPrimitive?.content
            }
        } catch (e: Exception) {
            Logx.d("api", "health tidak menjawab: ${e.javaClass.simpleName} ${e.message?.take(80)}")
            null
        }
    }

    /** The agent server's resolved workdir (from /health) — /sdcard/coding-sachet
     * when shared storage is writable, /root/work otherwise. Null = server
     * not reachable. */
    suspend fun workdir(): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(baseUrl() + "/health").build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val o = json.parseToJsonElement(r.body.string()).jsonObject
                o["workdir"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- models ----------

    /**
     * Model list through the local agent server (it proxies the provider).
     * If the guest's network path fails or is slow, fall back to calling the
     * provider directly — the same route validateKey already proved working.
     */
    suspend fun models(apiKey: String, provider: Provider): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val viaServer = runCatching { fetchModelsViaServer(apiKey, provider) }
            viaServer.getOrElse {
                Logx.w("api", "models via server gagal (${it.message?.take(100)}) — coba langsung ke ${provider.label}")
                fetchModelsDirect(apiKey, provider).getOrElse { direct ->
                    Logx.e("api", "models gagal dua jalur (${provider.label})")
                    throw IOException("${direct.message} · via server: ${it.message?.take(140)}")
                }
            }
        }
    }

    private fun fetchModelsViaServer(apiKey: String, provider: Provider): List<String> {
        val req = Request.Builder()
            .url(baseUrl() + "/api/models")
            .header("X-Sachet-Key", apiKey)
            .header("X-Sachet-Provider", provider.id)
            .build()
        slowClient.newCall(req).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) throw IOException(errorOf(body, r.code))
            return json.parseToJsonElement(body).jsonObject["models"]!!.jsonArray
                .map { it.jsonPrimitive.content }
        }
    }

    private fun fetchModelsDirect(apiKey: String, provider: Provider): Result<List<String>> = runCatching {
        val req = Request.Builder()
            .url(provider.baseUrl + "/models")
            .header("Authorization", "Bearer $apiKey")
            .build()
        // The direct fallback also rides the slow client: a cold VPN path
        // (first DNS + TLS through the tunnel) can outrun a 10 s read.
        slowClient.newCall(req).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) throw IOException(errorOf(body, r.code))
            json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        }
    }

    /** An upstream HTTP error status (not a transport failure) — never retried. */
    private class HttpUpstreamError(message: String) : IOException(message)

    /**
     * Validate a key directly against the provider (used during onboarding,
     * before the local server exists).
     *
     * APInex: GET /models with the bearer key (401/403 = invalid).
     * NVIDIA NIM: /v1/models is public, so the check is a minimal
     * POST /chat/completions — 401/403 = invalid, 2xx/429 = valid
     * (429 proves authentication passed; only the rate limit tripped).
     *
     * A cold VPN path can make the FIRST attempt time out while the tunnel
     * warms up (the same first-attempt pattern the server's warm-up sees) —
     * transport failures get one retry; HTTP error statuses never do.
     */
    suspend fun validateKey(apiKey: String, provider: Provider): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                validateKeyOnce(apiKey, provider)
                Logx.i("api", "validasi key ${provider.label}: OK")
            } catch (e: IOException) {
                if (e is HttpUpstreamError) {
                    Logx.w("api", "validasi key ${provider.label}: ditolak — ${e.message?.take(120)}")
                    throw e
                }
                Logx.w("api", "validasi key ${provider.label}: kegagalan jaringan (${e.message?.take(100)}) — satu kali retry")
                Thread.sleep(600)
                validateKeyOnce(apiKey, provider)
                Logx.i("api", "validasi key ${provider.label}: OK (setelah retry)")
            }
        }
    }

    private fun validateKeyOnce(apiKey: String, provider: Provider) {
        if (!provider.validateViaChat) {
            val req = Request.Builder()
                .url(provider.baseUrl + "/models")
                .header("Authorization", "Bearer $apiKey")
                .build()
            slowClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw HttpUpstreamError(errorOf(r.body.string(), r.code))
            }
        } else {
            val body = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    put("model", JsonPrimitive(provider.defaultModel))
                    put("messages", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive("hi"))
                        })
                    })
                    // A real inference roundtrip (NIM): keep the probe tiny
                    // but leave reasoning models a little room to open their
                    // stream — 1 token can be rejected outright.
                    put("max_tokens", JsonPrimitive(16))
                }
            )
            val req = Request.Builder()
                .url(provider.baseUrl + "/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            slowClient.newCall(req).execute().use { r ->
                // 2xx or 429 (rate-limited but authenticated) = the key is real.
                // 429 proves the bearer token passed auth — only the rate
                // limit tripped — so it must NOT be reported as an invalid key.
                if (r.code == 401 || r.code == 403 || (r.code != 429 && !r.isSuccessful)) {
                    throw HttpUpstreamError(errorOf(r.body.string(), r.code))
                }
            }
        }
    }

    // ---------- dns (VPN-aware resolver push) ----------

    /**
     * Tell the agent server which DNS servers the Android system currently
     * uses (VPN-aware). The server rewrites the guest's /etc/resolv.conf
     * so gateway fetches follow the active network/VPN instead of hitting
     * public resolvers many VPNs block ("fetch failed" under VPN).
     */
    suspend fun pushDns(servers: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (servers.isEmpty()) return@withContext false
        runCatching {
            val body = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    put("servers", kotlinx.serialization.json.buildJsonArray {
                        servers.forEach { add(JsonPrimitive(it)) }
                    })
                }
            )
            client.newCall(
                Request.Builder()
                    .url(baseUrl() + "/api/dns")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    // ---------- quota ----------

    /** Remaining-quota info for a provider's key (APInex only for now). */
    data class QuotaInfo(
        val unlimited: Boolean,
        val tokensRemaining: Long?,
        val tokensUsed: Long?,
        val tokenLimit: Long?,
    )

    /**
     * GET /api/quota through the local server. Returns null when the
     * provider has no quota API (NIM) — the UI hides the chip then.
     */
    suspend fun quota(apiKey: String, provider: Provider, fresh: Boolean = false): Result<QuotaInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(baseUrl() + "/api/quota" + if (fresh) "?fresh=1" else "")
                .header("X-Sachet-Key", apiKey)
                .header("X-Sachet-Provider", provider.id)
                .build()
            slowClient.newCall(req).execute().use { r ->
                val body = r.body.string()
                if (!r.isSuccessful) throw IOException(errorOf(body, r.code))
                val o = json.parseToJsonElement(body).jsonObject
                if (o["supported"]?.jsonPrimitive?.content != "true") return@use null
                QuotaInfo(
                    unlimited = o["unlimited"]?.jsonPrimitive?.content == "true",
                    tokensRemaining = o.num("tokensRemaining")?.toLong(),
                    tokensUsed = o.num("tokensUsed")?.toLong(),
                    tokenLimit = o.num("tokenLimit")?.toLong(),
                )
            }
        }
    }

    private fun JsonObject.num(key: String): Double? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p is kotlinx.serialization.json.JsonNull) return null
        return p.content.toDoubleOrNull()
    }

    // ---------- chat (SSE) ----------

    /** Why an SSE chat stream ended in an error (T-ML27 P3).
     *  EOF = the socket closed mid-stream: on this localhost hop that is
     *  the server-died signature (the server always writes error+done
     *  events before closing on real faults, and a user stop cancels the
     *  call instead). CONNECT = refused before a response (server down or
     *  restarting). Both are retryable once the server is healthy again. */
    enum class ErrorKind { EOF, CONNECT, HTTP }

    /**
     * POST /api/chat and stream agent events. [onEvent] runs on an IO thread.
     * The returned Call can be cancelled to abort the run.
     */
    fun chatStream(
        apiKey: String,
        provider: Provider,
        model: String,
        messages: List<id.codingsachet.mobile.data.Msg>,
        systemPrompt: String?,
        options: JsonObject? = null,
        onEvent: (JsonObject) -> Unit,
        onError: (String, ErrorKind) -> Unit,
        onClosed: () -> Unit,
    ): Call {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("model", JsonPrimitive(model))
            put("messages", json.encodeToJsonElement(messages))
            if (!systemPrompt.isNullOrBlank()) put("systemPrompt", JsonPrimitive(systemPrompt))
            // Konfigurasi JSON (Settings): OpenAI-compatible request options.
            // The agent server sanitizes them (reserved keys stripped).
            if (options != null && options.isNotEmpty()) put("options", options)
        }
        val wire = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), body)
        val req = Request.Builder()
            .url(baseUrl() + "/api/chat")
            .header("X-Sachet-Key", apiKey)
            .header("X-Sachet-Provider", provider.id)
            .post(wire.toRequestBody("application/json".toMediaType()))
            .build()

        val call = sseClient.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) {
                    Logx.e("api", "chat stream gagal (${e.javaClass.simpleName}: ${e.message?.take(120)})")
                    onError(e.message ?: "network error", ErrorKind.CONNECT)
                }
                onClosed()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val body = runCatching { it.body.string() }.getOrDefault("")
                        if (!call.isCanceled()) {
                            Logx.w("api", "chat stream HTTP ${it.code}: ${errorOf(body, it.code).take(140)}")
                            onError(errorOf(body, it.code), ErrorKind.HTTP)
                        }
                        onClosed()
                        return
                    }
                    try {
                        val reader = it.body.byteStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (call.isCanceled()) break
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload.isEmpty()) continue
                            runCatching { json.parseToJsonElement(payload).jsonObject }
                                .onSuccess { ev -> onEvent(ev) }
                        }
                    } catch (e: Exception) {
                        if (!call.isCanceled()) {
                            Logx.e("api", "chat stream putus saat membaca (${e.javaClass.simpleName}: ${e.message?.take(120)})")
                            // EOF mid-stream = the server process died (T-ML27
                            // field: exit 137 while apt-get ran). Give the caller
                            // the class, not just the empty message.
                            onError(
                                if (e.message.isNullOrBlank()) "stream error" else e.message!!,
                                ErrorKind.EOF,
                            )
                        }
                    }
                    onClosed()
                }
            }
        })
        return call
    }

    // ---------- model live verification (T-ML26) ----------

    /**
     * POST /api/verify-models and stream the sweep's SSE events
     * ({start|result|done|error}). Mirrors [chatStream]'s SSE reading on
     * the no-timeout client — a paced sweep can legitimately run for
     * minutes. The returned Call can be cancelled to abort the sweep
     * server-side (the server watches the socket close).
     */
    fun verifyModelsStream(
        apiKey: String,
        provider: Provider,
        models: List<String>,
        onEvent: (JsonObject) -> Unit,
        onError: (String?) -> Unit,
        onClosed: () -> Unit,
    ): Call {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("models", kotlinx.serialization.json.buildJsonArray {
                models.forEach { add(JsonPrimitive(it)) }
            })
        }
        val wire = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), body)
        val req = Request.Builder()
            .url(baseUrl() + "/api/verify-models")
            .header("X-Sachet-Key", apiKey)
            .header("X-Sachet-Provider", provider.id)
            .post(wire.toRequestBody("application/json".toMediaType()))
            .build()

        val call = sseClient.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) {
                    Logx.w("api", "verify stream gagal (${e.javaClass.simpleName}: ${e.message?.take(120)})")
                    onError(e.message)
                }
                onClosed()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val body = runCatching { it.body.string() }.getOrDefault("")
                        if (!call.isCanceled()) {
                            Logx.w("api", "verify stream HTTP ${it.code}: ${errorOf(body, it.code).take(140)}")
                            onError(errorOf(body, it.code))
                        }
                        onClosed()
                        return
                    }
                    try {
                        val reader = it.body.byteStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (call.isCanceled()) break
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload.isEmpty()) continue
                            runCatching { json.parseToJsonElement(payload).jsonObject }
                                .onSuccess { ev -> onEvent(ev) }
                        }
                    } catch (e: Exception) {
                        if (!call.isCanceled()) {
                            Logx.w("api", "verify stream putus saat membaca (${e.javaClass.simpleName}: ${e.message?.take(120)})")
                            onError(e.message)
                        }
                    }
                    onClosed()
                }
            }
        })
        return call
    }

    // ---------- file explorer ----------

    suspend fun fsList(path: String): Result<JsonObject> = fsGet("/api/fs?path=" + java.net.URLEncoder.encode(path, "UTF-8"))

    suspend fun fsFile(path: String): Result<JsonObject> = fsGet("/api/fs/file?path=" + java.net.URLEncoder.encode(path, "UTF-8"))

    private suspend fun fsGet(path: String): Result<JsonObject> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(baseUrl() + path).build()).execute().use { r ->
                val body = r.body.string()
                if (!r.isSuccessful) throw IOException(errorOf(body, r.code))
                json.parseToJsonElement(body).jsonObject
            }
        }
    }

    suspend fun fsWrite(path: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("content", JsonPrimitive(content))
                }
            )
            client.newCall(
                Request.Builder()
                    .url(baseUrl() + "/api/fs/file")
                    .put(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { r ->
                if (!r.isSuccessful) throw IOException(errorOf(r.body.string(), r.code))
            }
        }
    }

    suspend fun fsAction(action: String, path: String, to: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    put("action", JsonPrimitive(action))
                    put("path", JsonPrimitive(path))
                    if (to != null) put("to", JsonPrimitive(to))
                }
            )
            client.newCall(
                Request.Builder()
                    .url(baseUrl() + "/api/fs")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute().use { r ->
                if (!r.isSuccessful) throw IOException(errorOf(r.body.string(), r.code))
            }
        }
    }

    /** Error text from a gateway body: OpenAI {error:…}, NIM {detail:…}, or raw. */
    private fun errorOf(body: String, code: Int): String {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val msg = o?.let {
            (it["error"] as? JsonPrimitive)?.content
                ?: (it["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.content
                ?: (it["detail"] as? JsonPrimitive)?.content
                ?: (it["title"] as? JsonPrimitive)?.content
        }
        return "HTTP $code: ${msg ?: body.take(200)}"
    }
}
