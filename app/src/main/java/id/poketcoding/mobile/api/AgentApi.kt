package id.poketcoding.mobile.api

import id.poketcoding.mobile.BuildConfig
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

/** Client for the local agent server (127.0.0.1) and for key validation against the gateway. */
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
     * Model list through the local agent server (it proxies the gateway).
     * If the guest's network path fails or is slow, fall back to calling the
     * gateway directly — the same route validateKey already proved working.
     */
    suspend fun models(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val viaServer = runCatching { fetchModelsViaServer(apiKey) }
            viaServer.getOrElse {
                fetchModelsDirect(apiKey).getOrElse { direct ->
                    throw IOException("${direct.message} · via server: ${it.message?.take(140)}")
                }
            }
        }
    }

    private fun fetchModelsViaServer(apiKey: String): List<String> {
        val req = Request.Builder()
            .url(baseUrl() + "/api/models")
            .header("X-Poket-Key", apiKey)
            .build()
        slowClient.newCall(req).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) throw IOException(errorOf(body, r.code))
            return json.parseToJsonElement(body).jsonObject["models"]!!.jsonArray
                .map { it.jsonPrimitive.content }
        }
    }

    private fun fetchModelsDirect(apiKey: String): Result<List<String>> = runCatching {
        val req = Request.Builder()
            .url(BuildConfig.POKET_APINEX_BASE_URL + "/models")
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
     * Validate a key directly against the gateway (used during onboarding,
     * before the local server exists).
     */
    suspend fun validateKey(apiKey: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(BuildConfig.POKET_APINEX_BASE_URL + "/models")
                .header("Authorization", "Bearer $apiKey")
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException(errorOf(r.body.string(), r.code))
            }
        }
    }

    // ---------- chat (SSE) ----------

    /**
     * POST /api/chat and stream agent events. [onEvent] runs on an IO thread.
     * The returned Call can be cancelled to abort the run.
     */
    fun chatStream(
        apiKey: String,
        model: String,
        messages: List<id.poketcoding.mobile.data.Msg>,
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
            .header("X-Poket-Key", apiKey)
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

    private fun errorOf(body: String, code: Int): String {
        val msg = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull()
        return "HTTP $code: ${msg ?: body.take(200)}"
    }
}
