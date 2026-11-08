package id.codingsachet.mobile.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import id.codingsachet.mobile.Graph
import id.codingsachet.mobile.data.Session
import id.codingsachet.mobile.runtime.RuntimeState
import id.codingsachet.mobile.ui.screens.ChatScreen
import id.codingsachet.mobile.ui.screens.FilesScreen
import id.codingsachet.mobile.ui.screens.OnboardingScreen
import id.codingsachet.mobile.ui.screens.SettingsScreen

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
    /**
     * The chat session the user has open. Lives HERE (not in the chat
     * composable) so it survives opening Settings/Files and back — and is
     * restored from disk on launch via [Prefs.lastSessionId], so closing
     * the app or a process death never loses the current chat.
     */
    var currentSession: Session? = Graph.chats.get(Graph.prefs.lastSessionId)
        private set

    /** Switch the open session and persist it as the last one used. */
    fun selectSession(s: Session?) {
        currentSession = s
        Graph.prefs.lastSessionId = s?.id ?: ""
    }

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
    val context = LocalContext.current

    val needsSetup = !onboardingDone ||
        runtimeState is RuntimeState.NotInstalled ||
        runtimeState is RuntimeState.Installing ||
        runtimeState is RuntimeState.InstallFailed

    // Auto-start the server when the main UI is reachable and it was not
    // stopped on purpose.
    LaunchedEffect(needsSetup) {
        if (!needsSetup && !Graph.runtime.userStopped) Graph.runtime.startServer()
    }

    SachetTheme {
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
                    // System back handling. These handlers are composed
                    // BEFORE the screens, so a screen-level handler (e.g. the
                    // chat drawer) registered later still wins when enabled.
                    // 1) A pushed screen (Settings/Files) pops normally.
                    BackHandler(enabled = state.backstack.size > 1) { state.pop() }
                    // 2) On the chat root, back MINIMIZES the app instead of
                    //    finishing the activity — closing the APK on a back
                    //    gesture lost the whole UI state (and the open chat).
                    BackHandler(enabled = state.backstack.size <= 1) {
                        (context as? Activity)?.moveTaskToBack(true)
                    }

                    // The chat stays composed UNDER the pushed screens: its
                    // engines (running streams), scroll position and input
                    // survive a Settings/Files round trip instead of being
                    // disposed and reset to a blank session.
                    Box {
                        MainScaffold(state)
                        when (state.top) {
                            is Screen.Files -> FilesScreen(onBack = { state.pop() })
                            is Screen.Settings -> SettingsScreen(
                                onBack = { state.pop() },
                                onLangChange = { lang = it },
                            )
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScaffold(state: AppState) {
    Scaffold { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            ChatScreen(
                session = state.currentSession,
                onSessionChange = { state.selectSession(it) },
                onOpenFiles = { state.push(Screen.Files) },
                onOpenSettings = { state.push(Screen.Settings) },
            )
        }
    }
}
