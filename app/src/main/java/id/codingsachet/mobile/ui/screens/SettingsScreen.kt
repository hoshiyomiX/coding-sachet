package id.codingsachet.mobile.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import id.codingsachet.mobile.BuildConfig
import id.codingsachet.mobile.Graph
import id.codingsachet.mobile.api.Provider
import id.codingsachet.mobile.api.Providers
import id.codingsachet.mobile.runtime.LogcatDump
import id.codingsachet.mobile.runtime.RuntimeState
import id.codingsachet.mobile.ui.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onLangChange: (String) -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val runtimeState by Graph.runtime.state.collectAsState()
    val serverLog by Graph.runtime.log.collectAsState()

    var provider by remember { mutableStateOf(Graph.prefs.active) }
    var key by remember { mutableStateOf(Graph.prefs.keyFor(provider.id)) }
    var keyNote by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var keepAwake by remember { mutableStateOf(Graph.prefs.keepAwake) }
    var noSeccomp by remember { mutableStateOf(Graph.prefs.noSeccomp) }
    var rootAccess by remember { mutableStateOf(Graph.prefs.rootAccess) }
    var showReset by remember { mutableStateOf(false) }
    var installingTools by remember { mutableStateOf(false) }
    val toolLog = remember { mutableStateListOf<String>() }

    // The agent server's resolved workdir — /sdcard/coding-sachet on shared
    // storage, /root/work when storage is unavailable (shows which one is
    // actually in use instead of a hardcoded path).
    var workdir by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { workdir = Graph.api.workdir() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(s.settingsTitle) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = s.settingsTitle) }
            },
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ---- language ----
            SectionTitle(s.language)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = Graph.prefs.lang == "id",
                    onClick = { Graph.prefs.lang = "id"; onLangChange("id") },
                    label = { Text("Bahasa Indonesia") },
                )
                FilterChip(
                    selected = Graph.prefs.lang == "en",
                    onClick = { Graph.prefs.lang = "en"; onLangChange("en") },
                    label = { Text("English") },
                )
            }

            // ---- provider ----
            SectionTitle(s.providerSection)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Providers.ALL.forEach { p: Provider ->
                    FilterChip(
                        selected = provider.id == p.id,
                        onClick = {
                            provider = p
                            Graph.prefs.provider = p.id
                            key = Graph.prefs.keyFor(p.id)
                            keyNote = null
                        },
                        label = { Text(p.label) },
                    )
                }
            }
            Text(
                "${provider.docsUrl.removePrefix("https://")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(s.providerSwitchNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // ---- api key ----
            SectionTitle(s.apiKeySection)
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim(); keyNote = null },
                label = { Text("${s.apiKeyLabel} ${provider.label}") },
                placeholder = { Text(provider.keyPrefix + "…") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        Graph.prefs.setKeyFor(provider.id, key)
                        keyNote = "OK"
                    },
                    enabled = key.isNotBlank(),
                ) { Text(s.save) }
                TextButton(
                    onClick = {
                        checking = true
                        scope.launch {
                            val r = Graph.api.validateKey(key, provider)
                            checking = false
                            keyNote = if (r.isSuccess) s.keyOk else (s.apiKeyInvalid + " (" + r.exceptionOrNull()?.message + ")")
                        }
                    },
                    enabled = key.isNotBlank() && !checking,
                ) {
                    if (checking) CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                    else Text(s.validateKey)
                }
                keyNote?.let { Text(it, Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium) }
            }

            // ---- json config (request body options) ----
            JsonConfigSection()

            // ---- server ----
            SectionTitle(s.serverSection)
            val stateLabel = when (runtimeState) {
                is RuntimeState.Running -> s.runtimeRunning + " (v" + (runtimeState as RuntimeState.Running).version + ")"
                is RuntimeState.Starting -> s.runtimeStarting
                is RuntimeState.Crashed -> s.runtimeCrashed
                is RuntimeState.Installing -> (runtimeState as RuntimeState.Installing).step
                else -> s.runtimeStopped
            }
            Text("${s.serverState}: $stateLabel", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Graph.runtime.startServer() }) { Text(s.serverStart) }
                TextButton(onClick = { Graph.runtime.stopServer() }) { Text(s.serverStopLabel) }
                TextButton(onClick = { Graph.runtime.restartServer() }) { Text(s.serverRestart) }
            }

            SwitchRow(
                label = s.keepAwake,
                hint = s.keepAwakeHint,
                checked = keepAwake,
                onChange = {
                    keepAwake = it
                    Graph.prefs.keepAwake = it
                },
            )
            SwitchRow(
                label = s.noSeccomp,
                hint = s.noSeccompHint,
                checked = noSeccomp,
                onChange = {
                    noSeccomp = it
                    Graph.prefs.noSeccomp = it
                },
            )
            // Only offered when a su binary is present — toggling it on a
            // non-rooted phone would be a dead switch.
            if (Graph.runtime.hasSu()) {
                SwitchRow(
                    label = s.rootAccess,
                    hint = s.rootAccessHint,
                    checked = rootAccess,
                    onChange = {
                        rootAccess = it
                        Graph.prefs.rootAccess = it
                    },
                )
            }
            TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                }
            }) { Text(s.batteryOptimization) }

            // ---- dev tools ----
            SectionTitle(s.devTools)
            Text(s.devToolsHint, style = MaterialTheme.typography.bodySmall)
            if (installingTools) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.padding(8.dp)) {
                        toolLog.takeLast(14).forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    }
                }
            }
            Button(
                onClick = {
                    installingTools = true
                    toolLog.clear()
                    scope.launch {
                        val code = Graph.runtime.installDevTools { line ->
                            scope.launch { toolLog.add(line.take(120)) }
                        }
                        toolLog.add("exit=$code")
                        installingTools = false
                    }
                },
                enabled = !installingTools,
            ) { if (installingTools) CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp) else Text(s.devTools) }

            // ---- server log ----
            SectionTitle(s.serverLog)
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(8.dp)) {
                    if (serverLog.isEmpty()) {
                        Text("—", style = MaterialTheme.typography.labelSmall)
                    } else {
                        serverLog.takeLast(30).forEach {
                            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                        }
                    }
                }
            }

            // ---- logcat dump (the app's debug log, owner-defined) ----
            SectionTitle(s.logcatSection)
            Text(s.logcatHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var dumping by remember { mutableStateOf(false) }
            var dump by remember { mutableStateOf<LogcatDump.Result?>(null) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        dumping = true
                        // Server label embedded in the dump header (T-ML25) —
                        // read once at click time, off the composition path.
                        val serverLine = when (val st = Graph.runtime.state.value) {
                            is RuntimeState.Running -> "berjalan (v${st.version})"
                            is RuntimeState.Starting -> "menyala"
                            is RuntimeState.Crashed -> "mati (crash)"
                            is RuntimeState.Installing -> "memasang (${st.step})"
                            is RuntimeState.InstallFailed -> "pemasangan gagal"
                            RuntimeState.NotInstalled -> "belum terpasang"
                            RuntimeState.Stopped -> "berhenti"
                        }
                        scope.launch {
                            dump = withContext(Dispatchers.IO) { LogcatDump.dump(context, serverLine) }
                            dumping = false
                        }
                    },
                    enabled = !dumping,
                ) {
                    if (dumping) CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                    else Text(s.logcatDump)
                }
                dump?.file?.let { f ->
                    TextButton(onClick = { LogcatDump.share(context, f) }) { Text(s.logcatShare) }
                }
            }
            dump?.let { r ->
                when {
                    r.error != null -> Text("${s.logcatFailed}: ${r.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    r.lines.isEmpty() -> Text(s.logcatEmpty, style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Text("${r.lines.size} ${s.logcatLinesUnit} · ${r.file?.absolutePath ?: "—"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (r.trimmed) Text(s.logcatTrimmed, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.padding(8.dp)) {
                                r.lines.takeLast(15).forEach {
                                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }

            // ---- reset ----
            SectionTitle(s.resetRuntime)
            Button(onClick = { showReset = true }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
            )) { Text(s.resetRuntime) }

            // ---- about ----
            SectionTitle(s.about)
            Text("Coding Sachet ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text("${s.gateway}: ${provider.label} — ${provider.baseUrl}", style = MaterialTheme.typography.bodySmall)
            Providers.ALL.filter { it.id != provider.id }.forEach { other ->
                Text("${other.label}: ${other.baseUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${s.workdirLabel}: ${workdir ?: "…"}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text(s.resetRuntime) },
            text = { Text(s.resetRuntimeConfirm) },
            confirmButton = {
                TextButton(onClick = {
                    Graph.runtime.resetRuntime()
                    showReset = false
                }) { Text(s.delete) }
            },
            dismissButton = { TextButton(onClick = { showReset = false }) { Text(s.cancel) } },
        )
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

/**
 * Konfigurasi JSON: OpenAI-compatible request-body options merged into
 * every gateway chat call (apinex.bond/developers/models/chat). Structured
 * fields + a free-form "JSON tambahan" object; the agent server strips
 * agent-owned keys (model, messages, stream, tools, …) either way.
 */
@Composable
private fun JsonConfigSection() {
    val s = LocalStrings.current
    val json = remember { Json { ignoreUnknownKeys = true } }

    fun storedField(pref: String, name: String): String {
        val raw = pref
        if (raw.isBlank()) return ""
        val o = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return ""
        return when (val v = o[name]) {
            null -> ""
            is JsonPrimitive -> v.content
            is JsonArray -> v.joinToString("\n") { (it as? JsonPrimitive)?.content ?: "" }
            else -> v.toString()
        }
    }

    var optTemp by remember { mutableStateOf(storedField(Graph.prefs.requestOptions, "temperature")) }
    var optTopP by remember { mutableStateOf(storedField(Graph.prefs.requestOptions, "top_p")) }
    var optMaxTok by remember { mutableStateOf(storedField(Graph.prefs.requestOptions, "max_tokens")) }
    var optPresence by remember { mutableStateOf(storedField(Graph.prefs.requestOptions, "presence_penalty")) }
    var optFrequency by remember { mutableStateOf(storedField(Graph.prefs.requestOptions, "frequency_penalty")) }
    var optSeed by remember { mutableStateOf(storedField(Graph.prefs.requestOptions, "seed")) }
    var optStop by remember { mutableStateOf(storedField(Graph.prefs.requestOptions, "stop")) }
    var optReasoning by remember { mutableStateOf(storedField(Graph.prefs.requestOptions, "reasoning_effort")) }
    var optExtra by remember { mutableStateOf(Graph.prefs.requestOptionsExtra) }
    var savedNote by remember { mutableStateOf<String?>(null) }

    /** Validate the structured fields; null + the first offending label. */
    fun validateStructured(): String? {
        fun chk(value: String, label: String, min: Double, max: Double, int: Boolean): Boolean {
            if (value.isBlank()) return true
            val d = if (int) value.toLongOrNull()?.toDouble() else value.toDoubleOrNull()
            return d != null && d in min..max
        }
        if (!chk(optTemp, s.optTemperature, 0.0, 2.0, false)) return s.optTemperature
        if (!chk(optTopP, s.optTopP, 0.0, 1.0, false)) return s.optTopP
        if (!chk(optMaxTok, s.optMaxTokens, 1.0, Double.MAX_VALUE, true)) return s.optMaxTokens
        if (!chk(optPresence, s.optPresencePenalty, -2.0, 2.0, false)) return s.optPresencePenalty
        if (!chk(optFrequency, s.optFrequencyPenalty, -2.0, 2.0, false)) return s.optFrequencyPenalty
        if (!chk(optSeed, s.optSeed, -Double.MAX_VALUE, Double.MAX_VALUE, true)) return s.optSeed
        val stops = optStop.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (stops.size > 4) return s.optStop
        return null
    }

    fun buildStructured(): JsonObject {
        val invalid = validateStructured()
        if (invalid != null) return buildJsonObject { } // caller shows the error
        return buildJsonObject {
            optTemp.trim().toDoubleOrNull()?.let { put("temperature", JsonPrimitive(it)) }
            optTopP.trim().toDoubleOrNull()?.let { put("top_p", JsonPrimitive(it)) }
            optMaxTok.trim().toLongOrNull()?.let { put("max_tokens", JsonPrimitive(it)) }
            optPresence.trim().toDoubleOrNull()?.let { put("presence_penalty", JsonPrimitive(it)) }
            optFrequency.trim().toDoubleOrNull()?.let { put("frequency_penalty", JsonPrimitive(it)) }
            optSeed.trim().toLongOrNull()?.let { put("seed", JsonPrimitive(it)) }
            val stops = optStop.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(4)
            if (stops.isNotEmpty()) put("stop", buildJsonArray { stops.forEach { add(JsonPrimitive(it)) } })
            if (optReasoning.isNotBlank()) put("reasoning_effort", JsonPrimitive(optReasoning))
        }
    }

    /** The structured object + the extra object merged last (extra wins). */
    fun buildMerged(): Pair<JsonObject?, String?> {
        validateStructured()?.let { return null to it }
        val structured = buildStructured()
        val extra = optExtra.trim()
        if (extra.isEmpty()) return structured to null
        val ex = runCatching { json.parseToJsonElement(extra) }.getOrNull() as? JsonObject
            ?: return null to s.jsonExtra
        return buildJsonObject {
            structured.forEach { (k, v) -> put(k, v) }
            ex.forEach { (k, v) -> put(k, v) }
        } to null
    }

    val (preview, error) = buildMerged()
    val pretty = remember(preview) {
        preview?.let { runCatching { Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), it) }.getOrDefault("{}") } ?: ""
    }

    SectionTitle(s.jsonConfigSection)
    Text(s.jsonConfigHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    val numKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
    OutlinedTextField(value = optTemp, onValueChange = { optTemp = it; savedNote = null },
        label = { Text(s.optTemperature) }, singleLine = true, isError = validateStructured() == s.optTemperature,
        keyboardOptions = numKeyboard, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = optTopP, onValueChange = { optTopP = it; savedNote = null },
        label = { Text(s.optTopP) }, singleLine = true,
        keyboardOptions = numKeyboard, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = optMaxTok, onValueChange = { optMaxTok = it; savedNote = null },
        label = { Text(s.optMaxTokens) }, singleLine = true,
        keyboardOptions = numKeyboard, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = optPresence, onValueChange = { optPresence = it; savedNote = null },
        label = { Text(s.optPresencePenalty) }, singleLine = true,
        keyboardOptions = numKeyboard, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = optFrequency, onValueChange = { optFrequency = it; savedNote = null },
        label = { Text(s.optFrequencyPenalty) }, singleLine = true,
        keyboardOptions = numKeyboard, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = optSeed, onValueChange = { optSeed = it; savedNote = null },
        label = { Text(s.optSeed) }, singleLine = true,
        keyboardOptions = numKeyboard, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = optStop, onValueChange = { optStop = it; savedNote = null },
        label = { Text(s.optStop) },
        modifier = Modifier.fillMaxWidth())

    Text(s.optReasoningEffort, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        listOf("" to s.optReasoningNone, "minimal" to "minimal", "low" to "low", "medium" to "medium", "high" to "high").forEach { (v, label) ->
            FilterChip(selected = optReasoning == v, onClick = { optReasoning = v; savedNote = null }, label = { Text(label) })
        }
    }

    OutlinedTextField(
        value = optExtra,
        onValueChange = { optExtra = it; savedNote = null },
        label = { Text(s.jsonExtra) },
        placeholder = { Text("{\"logprobs\":false}") },
        isError = error == s.jsonExtra,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(s.jsonExtraHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    // Live preview of the exact object that will be merged into the request.
    Text(s.jsonPreview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            error?.let { "✗ $it — ${s.jsonInvalid}" } ?: (if (preview != null && preview.keys.isEmpty()) "—" else pretty),
            Modifier.padding(8.dp).fillMaxWidth(),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = {
                val (merged, err) = buildMerged()
                if (err == null && merged != null) {
                    // Structured fields and the extra object are stored
                    // separately; ChatEngine merges them on send.
                    Graph.prefs.requestOptions = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), buildStructured())
                    Graph.prefs.requestOptionsExtra = optExtra.trim()
                    savedNote = s.jsonSaved
                }
            },
            enabled = error == null,
        ) { Text(s.save) }
        TextButton(onClick = {
            optTemp = ""; optTopP = ""; optMaxTok = ""; optPresence = ""; optFrequency = ""
            optSeed = ""; optStop = ""; optReasoning = ""; optExtra = ""
            Graph.prefs.requestOptions = ""
            Graph.prefs.requestOptionsExtra = ""
            savedNote = s.jsonSaved
        }) { Text(s.jsonReset) }
        savedNote?.let { Text(it, Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
