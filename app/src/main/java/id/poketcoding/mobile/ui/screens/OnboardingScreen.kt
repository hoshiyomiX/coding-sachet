package id.poketcoding.mobile.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import id.poketcoding.mobile.Graph
import id.poketcoding.mobile.api.Provider
import id.poketcoding.mobile.api.Providers
import id.poketcoding.mobile.runtime.RuntimeState
import id.poketcoding.mobile.ui.LocalStrings
import id.poketcoding.mobile.ui.PoketSky
import kotlinx.coroutines.launch

/** Welcome → provider → API key → environment install (bundled in the APK). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit, onLangChange: (String) -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val runtimeState by Graph.runtime.state.collectAsState()

    var provider by remember { mutableStateOf(Graph.prefs.active) }
    var key by remember { mutableStateOf(Graph.prefs.keyFor(provider.id)) }
    var checking by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var keyOk by remember { mutableStateOf(Graph.prefs.keyFor(provider.id).isNotBlank()) }
    var storageGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        storageGranted = it
    }

    fun requestStorage() {
        storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun switchProvider(p: Provider) {
        provider = p
        Graph.prefs.provider = p.id
        key = Graph.prefs.keyFor(p.id)
        keyError = null
        keyOk = key.isNotBlank()
    }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Poket Coding", style = MaterialTheme.typography.displaySmall, color = PoketSky)
            Text(s.appTagline, style = MaterialTheme.typography.bodyLarge)

            // language chips
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = Graph.prefs.lang == "id", onClick = { Graph.prefs.lang = "id"; onLangChange("id") }, label = { Text("Bahasa Indonesia") })
                FilterChip(selected = Graph.prefs.lang == "en", onClick = { Graph.prefs.lang = "en"; onLangChange("en") }, label = { Text("English") })
            }

            Text(s.onboardingBody, style = MaterialTheme.typography.bodyMedium)

            // provider chips + free-tier note
            Text(s.providerSection, style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Providers.ALL.forEach { p ->
                    FilterChip(
                        selected = provider.id == p.id,
                        onClick = { switchProvider(p) },
                        label = { Text(p.label) },
                    )
                }
            }
            Text(
                "${provider.label}: ${provider.freeTierNote} · ${provider.docsUrl.removePrefix("https://")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim(); keyError = null },
                label = { Text("${s.apiKeyLabel} ${provider.label}") },
                placeholder = { Text(provider.keyPrefix + "…") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                isError = keyError != null,
                modifier = Modifier.fillMaxWidth(),
            )
            if (keyError != null) {
                Text(keyError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    checking = true; keyError = null
                    scope.launch {
                        val r = Graph.api.validateKey(key, provider)
                        checking = false
                        r.onSuccess {
                            Graph.prefs.setKeyFor(provider.id, key)
                            keyOk = true
                        }.onFailure {
                            keyOk = false
                            keyError = s.apiKeyInvalid + " (" + (it.message ?: "") + ")"
                        }
                    }
                },
                enabled = key.isNotBlank() && !checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (checking) {
                    CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(8.dp))
                }
                Text(if (keyOk) s.keyOk else s.next, Modifier.padding(start = if (checking) 8.dp else 0.dp))
            }

            if (!storageGranted) {
                TextButton(onClick = { requestStorage() }) {
                    Text("${s.storagePermission} — ${s.storageHint}")
                }
            }

            // ---- install phase (environment is bundled in the APK) ----
            when (val st = runtimeState) {
                is RuntimeState.NotInstalled, is RuntimeState.InstallFailed -> {
                    Button(
                        onClick = { scope.launch { Graph.runtime.install() } },
                        enabled = keyOk,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (st is RuntimeState.InstallFailed) s.retry else s.installTitle) }
                    if (st is RuntimeState.InstallFailed) {
                        Text(s.installFailed + ": " + st.message, color = MaterialTheme.colorScheme.error)
                    }
                    Text(s.installHint, style = MaterialTheme.typography.bodySmall)
                }
                is RuntimeState.Installing -> {
                    Text("${st.step}${if (st.detail.isNotEmpty()) " · ${st.detail}" else ""}")
                    if (st.progress != null) {
                        LinearProgressIndicator(progress = { st.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                is RuntimeState.Stopped, is RuntimeState.Starting, is RuntimeState.Running, is RuntimeState.Crashed -> {
                    // Ready: make sure the server is on and enter the app.
                    LaunchedEffect(Unit) {
                        if (!Graph.runtime.userStopped) Graph.runtime.startServer()
                        onDone()
                    }
                    Text(s.runtimeStarting)
                    CircularProgressIndicator()
                }
            }

            Text(
                "${s.gateway}: ${provider.baseUrl.removeSuffix("/v1")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
