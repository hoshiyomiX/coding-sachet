package id.codingsachet.mobile.api

import id.codingsachet.mobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
                if (!r.isSuccessful) return@withContext null
                val o = json.parseToJsonElement(r.body.string()).jsonObject
                o["version"]?.jsonPrimitive?.content
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
                fetchModelsDirect(apiKey, provider).getOrElse { direct ->
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
        client.newCall(req).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) throw IOException(errorOf(body, r.code))
            json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray
                .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        }
    }

    /**
     * Validate a key directly against the provider (used during onboarding,
     * before the local server exists).
     *
     * APInex: GET /models with the bearer key (401/403 = invalid).
     * NVIDIA NIM: /v1/models is public, so the check is a minimal
     * POST /chat/completions — 401/403 = invalid, 2xx/429 = valid
     * (429 proves authentication passed; only the rate limit tripped).
     */
    suspend fun validateKey(apiKey: String, provider: Provider): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!provider.validateViaChat) {
                val req = Request.Builder()
                    .url(provider.baseUrl + "/models")
                    .header("Authorization", "Bearer $apiKey")
                    .build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException(errorOf(r.body.string(), r.code))
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
                        put("max_tokens", JsonPrimitive(1))
                    }
                )
                val req = Request.Builder()
                    .url(provider.baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { r ->
                    // 2xx or 429 (rate-limited but authenticated) = the key is real.
                    if (r.code == 401 || r.code == 403 || !r.isSuccessful) {
                        throw IOException(errorOf(r.body.string(), r.code))
                    }
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
        val balanceUsd: Double?,
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
                    balanceUsd = o.num("balanceUsd"),
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
        onEvent: (JsonObject) -> Unit,
        onError: (String) -> Unit,
        onClosed: () -> Unit,
    ): Call {
        val body = kotlinx.serialization.json.buildJsonObject {
            put("model", JsonPrimitive(model))
            put("messages", json.encodeToJsonElement(messages))
            if (!systemPrompt.isNullOrBlank()) put("systemPrompt", JsonPrimitive(systemPrompt))
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
                if (!call.isCanceled()) onError(e.message ?: "network error")
                onClosed()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val body = runCatching { it.body.string() }.getOrDefault("")
                        if (!call.isCanceled()) onError(errorOf(body, it.code))
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
                        if (!call.isCanceled()) onError(e.message ?: "stream error")
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
