package id.poketcoding.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import id.poketcoding.mobile.Graph
import id.poketcoding.mobile.data.Session
import id.poketcoding.mobile.runtime.RuntimeState
import id.poketcoding.mobile.ui.screens.ChatScreen
import id.poketcoding.mobile.ui.screens.FilesScreen
import id.poketcoding.mobile.ui.screens.OnboardingScreen
import id.poketcoding.mobile.ui.screens.SettingsScreen

/** Simple backstack of screens; no navigation library needed for four screens. */
sealed class Screen {
    data object Onboarding : Screen()
    data object Chat : Screen()
    data object Files : Screen()
    data object Settings : Screen()
}

class AppState(
    val backstack: SnapshotStateList<Screen> = mutableStateListOf(Screen.Chat),
) {
    val top: Screen get() = backstack.last()
    fun push(s: Screen) = backstack.add(s)
    fun pop() {
        if (backstack.size > 1) backstack.removeAt(backstack.lastIndex)
    }
}

@Composable
fun rememberAppState(): AppState = remember { AppState() }

@Composable
fun AppRoot(state: AppState) {
    val prefs = Graph.prefs
    var lang by remember { mutableStateOf(prefs.lang) }
    val runtimeState by Graph.runtime.state.collectAsState()
    var onboardingDone by remember { mutableStateOf(prefs.onboardingDone && prefs.apiKey.isNotBlank()) }

    val needsSetup = !onboardingDone ||
        runtimeState is RuntimeState.NotInstalled ||
        runtimeState is RuntimeState.Installing ||
        runtimeState is RuntimeState.InstallFailed

    // Auto-start the server when the main UI is reachable and it was not
    // stopped on purpose.
    LaunchedEffect(needsSetup) {
        if (!needsSetup && !Graph.runtime.userStopped) Graph.runtime.startServer()
    }

    PoketTheme {
        CompositionLocalProvider(LocalStrings provides stringsFor(lang)) {
            Surface(color = MaterialTheme.colorScheme.background) {
                if (needsSetup) {
                    OnboardingScreen(
                        onDone = {
                            onboardingDone = true
                            prefs.onboardingDone = true
                        },
                        onLangChange = { lang = it },
                    )
                } else {
                    when (state.top) {
                        is Screen.Chat -> MainScaffold(state)
                        is Screen.Files -> FilesScreen(onBack = { state.pop() })
                        is Screen.Settings -> SettingsScreen(
                            onBack = { state.pop() },
                            onLangChange = { lang = it },
                        )
                        is Screen.Onboarding -> MainScaffold(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScaffold(state: AppState) {
    val s = LocalStrings.current
    var currentSession by remember { mutableStateOf<Session?>(null) }
    Scaffold { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            ChatScreen(
                session = currentSession,
                onSessionChange = { currentSession = it },
                onOpenFiles = { state.push(Screen.Files) },
                onOpenSettings = { state.push(Screen.Settings) },
            )
        }
    }
}
