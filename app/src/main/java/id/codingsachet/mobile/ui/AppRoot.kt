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
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import id.codingsachet.mobile.Graph
import id.codingsachet.mobile.data.Session
import id.codingsachet.mobile.runtime.RuntimeState
import id.codingsachet.mobile.ui.screens.ChatScreen
import id.codingsachet.mobile.ui.screens.FilesScreen
import id.codingsachet.mobile.ui.screens.HomeScreen
import id.codingsachet.mobile.ui.screens.OnboardingScreen
import id.codingsachet.mobile.ui.screens.SettingsScreen

/** Simple backstack of screens; no navigation library needed for five screens. */
sealed class Screen {
    data object Onboarding : Screen()
    data object Home : Screen()
    data object Chat : Screen()
    data object Files : Screen()
    data object Settings : Screen()
}

/** What the chat should do when it becomes visible (T-ML65 #2): set by
 *  HomeScreen before Chat is pushed, consumed by ChatScreen's
 *  LaunchedEffect once it has mounted (or re-fired when the user comes
 *  BACK to an already-mounted chat from Home). */
enum class ChatIntent { None, NewProject, OpenDrawer }

class AppState(
    // T-ML65 #2: the app now OPENS on the homepage; the chat is only
    // composed after the user picks an option.
    val backstack: SnapshotStateList<Screen> = mutableStateListOf(Screen.Home),
) {
    /** Once true the chat stays composed UNDER the Home overlay — its
     *  engines (running streams) and scroll state survive a Home round
     *  trip instead of being disposed (same trick the old code used for
     *  Settings/Files). */
    var chatMounted: Boolean by mutableStateOf(false)
        private set

    /** Pending intent for ChatScreen; consumed there. */
    var chatIntent: ChatIntent by mutableStateOf(ChatIntent.None)
        private set

    fun enterChat(intent: ChatIntent) {
        chatIntent = intent
        chatMounted = true
        if (top != Screen.Chat) push(Screen.Chat)
    }

    /** ChatScreen has acted on the pending intent (T-ML65 #2). */
    fun consumeIntent() {
        chatIntent = ChatIntent.None
    }
    /**
     * The chat session the user has open. Lives HERE (not in the chat
     * composable) so it survives opening Settings/Files and back — and is
     * restored from disk on launch via [Prefs.lastSessionId], so closing
     * the app or a process death never loses the current chat.
     *
     * T-ML58 #3: when the saved id points nowhere (prefs write lost to a
     * process kill, or the file could not be read) fall back to the NEWEST
     * session instead of a blank screen — the conversation reopens, it does
     * not read as "deleted".
     */
    var currentSession: Session? = Graph.chats.get(Graph.prefs.lastSessionId)
        ?: Graph.chats.sessions.firstOrNull()
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
            Surface(color = MiuixTheme.colorScheme.background) {
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
                    // 1) A pushed screen (Chat/Settings/Files) pops normally —
                    //    from the chat, back returns to the homepage (T-ML65).
                    BackHandler(enabled = state.backstack.size > 1) { state.pop() }
                    // 2) On the homepage root, back MINIMIZES the app instead
                    //    of finishing the activity.
                    BackHandler(enabled = state.backstack.size <= 1) {
                        (context as? Activity)?.moveTaskToBack(true)
                    }

                    // The chat stays composed UNDER the pushed screens: its
                    // engines (running streams), scroll position and input
                    // survive a Home/Settings/Files round trip instead of being
                    // disposed and reset to a blank session.
                    Box {
                        if (state.chatMounted) MainScaffold(state)
                        when (state.top) {
                            // T-ML65 #2: homepage overlay — the entry screen.
                            is Screen.Home -> HomeScreen(
                                onNewProject = { state.enterChat(ChatIntent.NewProject) },
                                onOngoingProject = { state.enterChat(ChatIntent.OpenDrawer) },
                            )
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
                intent = state.chatIntent,
                onIntentConsumed = { state.consumeIntent() },
            )
        }
    }
}
