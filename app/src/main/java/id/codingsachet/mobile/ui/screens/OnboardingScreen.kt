package id.codingsachet.mobile.ui.screens

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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
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
import id.codingsachet.mobile.Graph
import id.codingsachet.mobile.api.Provider
import id.codingsachet.mobile.api.Providers
import id.codingsachet.mobile.runtime.RuntimeState
import id.codingsachet.mobile.ui.LocalStrings
import kotlinx.coroutines.launch

/** Welcome → provider → API key → environment install (bundled in the APK). */
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
            Text("Coding Sachet", style = MiuixTheme.textStyles.title1, color = MiuixTheme.colorScheme.primary)
            Text(s.appTagline, style = MiuixTheme.textStyles.body1)

            // language chips → TabRow segmented miuix (MIGRATION.md §3.2)
            val langs = listOf("id" to "Bahasa Indonesia", "en" to "English")
            TabRow(
                tabs = langs.map { it.second },
                selectedTabIndex = langs.indexOfFirst { it.first == Graph.prefs.lang }.coerceAtLeast(0),
                onTabSelected = { i ->
                    Graph.prefs.lang = langs[i].first
                    onLangChange(langs[i].first)
                },
            )

            Text(s.onboardingBody, style = MiuixTheme.textStyles.main)

            // provider chips + free-tier note
            Text(s.providerSection, style = MiuixTheme.textStyles.title3)
            TabRow(
                tabs = Providers.ALL.map { it.label },
                selectedTabIndex = Providers.ALL.indexOfFirst { it.id == provider.id }.coerceAtLeast(0),
                onTabSelected = { i -> switchProvider(Providers.ALL[i]) },
            )
            Text(
                "${provider.label} · ${provider.docsUrl.removePrefix("https://")}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            TextField(
                value = key,
                onValueChange = { key = it.trim(); keyError = null },
                label = provider.keyPrefix + "…",
                useLabelAsPlaceholder = true,
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (keyError != null) {
                Text(keyError!!, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2)
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
                    CircularProgressIndicator(strokeWidth = 2.dp, size = 18.dp)
                    Spacer(Modifier.height(8.dp))
                }
                Text(if (keyOk) s.keyOk else s.next, Modifier.padding(start = if (checking) 8.dp else 0.dp))
            }

            if (!storageGranted) {
                TextButton(onClick = { requestStorage() }, text = "${s.storagePermission} — ${s.storageHint}")
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
                        Text(s.installFailed + ": " + st.message, color = MiuixTheme.colorScheme.error)
                    }
                    Text(s.installHint, style = MiuixTheme.textStyles.body2)
                }
                is RuntimeState.Installing -> {
                    Text("${st.step}${if (st.detail.isNotEmpty()) " · ${st.detail}" else "")}")
                    if (st.progress != null) {
                        LinearProgressIndicator(progress = st.progress, modifier = Modifier.fillMaxWidth())
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
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
