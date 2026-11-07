package id.poketcoding.mobile.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import id.poketcoding.mobile.BuildConfig
import id.poketcoding.mobile.Graph
import id.poketcoding.mobile.api.Provider
import id.poketcoding.mobile.api.Providers
import id.poketcoding.mobile.runtime.RuntimeState
import id.poketcoding.mobile.ui.LocalStrings
import kotlinx.coroutines.launch

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
    var showReset by remember { mutableStateOf(false) }
    var installingTools by remember { mutableStateOf(false) }
    val toolLog = remember { mutableStateListOf<String>() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(s.settingsTitle) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
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
                "${provider.freeTierNote} · ${provider.docsUrl.removePrefix("https://")}",
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
            TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                }
            }) { Text(s.storagePermission + " (battery)") }

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

            // ---- reset ----
            SectionTitle(s.resetRuntime)
            Button(onClick = { showReset = true }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
            )) { Text(s.resetRuntime) }

            // ---- about ----
            SectionTitle(s.about)
            Text("Poket Coding ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text("${s.gateway}: ${provider.label} — ${provider.baseUrl}", style = MaterialTheme.typography.bodySmall)
            Providers.ALL.filter { it.id != provider.id }.forEach { other ->
                Text("${other.label}: ${other.baseUrl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${s.workdirLabel}: /root/work", style = MaterialTheme.typography.bodySmall)
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
