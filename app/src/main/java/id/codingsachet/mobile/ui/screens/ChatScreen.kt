package id.codingsachet.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.awaitCancellation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.codingsachet.mobile.Graph
import id.codingsachet.mobile.api.ModelStatus
import id.codingsachet.mobile.data.Msg
import id.codingsachet.mobile.data.Session
import id.codingsachet.mobile.runtime.RuntimeState
import id.codingsachet.mobile.ui.ChatEngine
import id.codingsachet.mobile.ui.FolderIcon
import id.codingsachet.mobile.ui.LiveTurn
import id.codingsachet.mobile.ui.LocalStrings
import id.codingsachet.mobile.ui.ProjectNames
import id.codingsachet.mobile.ui.StopIcon
import id.codingsachet.mobile.ui.TextFileIcon
import id.codingsachet.mobile.ui.ToolCardUi
import id.codingsachet.mobile.ui.formatTokenCount
import id.codingsachet.mobile.ui.projectDir
import id.codingsachet.mobile.ui.components.Markdownish
import id.codingsachet.mobile.ui.components.ToolCard
import id.codingsachet.mobile.ui.sachetSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val PrettyJson = Json { prettyPrint = true }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    session: Session?,
    onSessionChange: (Session?) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val s = LocalStrings.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val runtimeState by Graph.runtime.state.collectAsState()

    // Back closes the session drawer first (the AppRoot back handlers are
    // composed before this one, so this wins whenever the drawer is open).
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    // Re-initialize only when the RESTORED session id changes (activity
    // recreation after process death); switching chats from the drawer goes
    // through onSessionChange with the same live object.
    var current by remember(session?.id) { mutableStateOf<Session?>(session) }
    var model by remember(Graph.prefs.provider) { mutableStateOf(Graph.prefs.model) }
    var showModels by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var sessionsVersion by remember { mutableIntStateOf(0) }
    val nullLive = remember { MutableStateFlow<LiveTurn?>(null) }
    val nullTokens = remember { MutableStateFlow(0L) }

    // Two-tab chat session (T-ML28 #3): 0 = Prompt (chat), 1 = Project tree.
    var tab by remember { mutableIntStateOf(0) }
    // New-chat popup (T-ML28 #4): every new session names its project —
    // one project per folder in the agent's default path.
    var showNewProject by remember { mutableStateOf(false) }
    var pendingSend by remember { mutableStateOf(false) }
    // Bumped after a finished turn so the project tree reloads new files.
    var treeVersion by remember { mutableIntStateOf(0) }

    // One engine per session, kept for the lifetime of the screen so a
    // running stream survives switching back and forth between chats.
    val engines = remember { HashMap<String, ChatEngine>() }
    DisposableEffect(Unit) {
        onDispose { engines.values.forEach { it.dispose() } }
    }
    val engine = current?.let { engines.getOrPut(it.id) { ChatEngine(it) } }

    // ---------- sisa token APInex (chip di kanan atas kolom chat) ----------
    var quotaState by remember { mutableStateOf<QuotaState>(QuotaState.Idle) }
    fun refreshQuota(fresh: Boolean = false) {
        if (Graph.prefs.provider != "apinex" || Graph.prefs.apiKey.isBlank()) {
            quotaState = QuotaState.Idle
            return
        }
        quotaState = QuotaState.Loading
        val key = Graph.prefs.apiKey
        val provider = Graph.prefs.active
        scope.launch {
            Graph.api.quota(key, provider, fresh).fold(
                onSuccess = { q ->
                    quotaState = if (q == null) QuotaState.Failed("tidak tersedia")
                    else QuotaState.Ok(q.unlimited, q.tokensRemaining)
                },
                onFailure = { quotaState = QuotaState.Failed(it.message ?: "gagal") },
            )
        }
    }
    LaunchedEffect(engine) { engine?.onTurnFinished = { refreshQuota(); treeVersion++ } }
    LaunchedEffect(Unit) { refreshQuota() }
    LaunchedEffect(runtimeState) { if (runtimeState is RuntimeState.Running) refreshQuota() }

    val live by (engine?.live ?: nullLive).collectAsState()
    val sessionTokens by (engine?.sessionTokens ?: nullTokens).collectAsState()
    val msgs = current?.messages ?: emptyList()

    // Tool results of the persisted history, keyed by toolCallId.
    val toolResults = remember(msgs.size) {
        msgs.filter { it.role == "tool" && it.toolCallId != null }.associateBy { it.toolCallId!! }
    }

    // Tool calls that may STILL be running (T-ML31 P2-7): only the newest
    // assistant round while a turn is actually in flight. Every other
    // no-result card is an interrupted round (e.g. server killed mid-tool)
    // and must render "terputus…" instead of spinning
    // "berjalan…" forever — including after an app restart.
    val busyNow = live?.busy == true
    val activeCallIds = if (busyNow) {
        msgs.lastOrNull { it.role == "assistant" }?.toolCalls?.map { it.id }?.toSet() ?: emptySet()
    } else emptySet()

    val listState = rememberLazyListState()
    LaunchedEffect(live?.text?.length, live?.cards?.size, live?.thinking?.length, msgs.size) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    fun newChat(project: String? = null): Session {
        val ns = Graph.chats.create(model, project)
        Graph.chats.reload()
        sessionsVersion++
        current = ns
        onSessionChange(ns)
        return ns
    }

    /** Create a session (with or without a project) and — when the send
     *  button triggered the popup — deliver the buffered message. */
    fun createSessionWith(project: String?) {
        val ns = newChat(project)
        // Best-effort folder creation right away (idempotent); the agent
        // server creates it again on the first send either way.
        ns.project?.let { p ->
            val base = Graph.prefs.workdirHint
            if (base.isNotBlank()) {
                val dir = projectDir(base, p)
                scope.launch { runCatching { Graph.api.fsAction("mkdir", dir) } }
            }
        }
        if (pendingSend) {
            pendingSend = false
            if (input.isNotBlank()) {
                val e = engines.getOrPut(ns.id) { ChatEngine(ns) }
                if (e.send(input, model)) input = ""
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxHeight().padding(vertical = 12.dp)) {
                    // Standard M3 drawer header: app identity.
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
                    ) {
                        Text("Coding Sachet", style = MaterialTheme.typography.titleLarge)
                        Text(
                            s.appTagline,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    NavigationDrawerItem(
                        label = { Text(s.chatNew) },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        selected = false,
                        onClick = {
                            scope.launch { drawer.close() }
                            showNewProject = true
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Text(
                        s.sessionsTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 4.dp),
                    )
                    val sessions = remember(sessionsVersion) { Graph.chats.sessions }
                    LazyColumn(Modifier.weight(1f)) {
                        items(sessions, key = { it.id }) { sess ->
                            NavigationDrawerItem(
                                label = {
                                    Column {
                                        Text(
                                            sess.title.ifBlank { "…" },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault())
                                                .format(java.util.Date(sess.created))
                                                + (sess.project?.let { "  ·  $it" } ?: ""),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                selected = sess.id == current?.id,
                                onClick = {
                                    current = sess
                                    onSessionChange(sess)
                                    scope.launch { drawer.close() }
                                },
                                badge = {
                                    IconButton(onClick = {
                                        // A running stream keeps persisting the
                                        // session — without cancelling its engine
                                        // the deleted chat file would be rewritten
                                        // (resurrected) and the stream would keep
                                        // burning tokens in the background.
                                        engines.remove(sess.id)?.dispose()
                                        Graph.chats.delete(sess)
                                        Graph.chats.reload()
                                        sessionsVersion++
                                        if (current?.id == sess.id) {
                                            current = null
                                            onSessionChange(null)
                                        }
                                    }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = s.deleteSession,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                            )
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    // Top-bar buttons relocated here (T-ML28 #5): File and
                    // Settings stay reachable from the drawer.
                    NavigationDrawerItem(
                        label = { Text(s.filesTitle) },
                        icon = { Icon(TextFileIcon, contentDescription = s.filesTitle) },
                        selected = false,
                        onClick = {
                            scope.launch { drawer.close() }
                            onOpenFiles()
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                    NavigationDrawerItem(
                        label = { Text(s.settingsTitle) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = s.settingsTitle) },
                        selected = false,
                        onClick = {
                            scope.launch { drawer.close() }
                            onOpenSettings()
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawer.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = s.sessionsTitle)
                            }
                        },
                        // The title block is the model selector: tapping it opens
                        // the picker (standard chat-app top-center switcher).
                        // Top-bar actions (File/Settings) and the runtime status
                        // dot were removed by owner order T-ML28 #5 — the screens
                        // live in the drawer, the status in Settings.
                        title = {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { showModels = true }
                                    .padding(vertical = 2.dp),
                            ) {
                                Text(
                                    current?.title?.ifBlank { s.chatNew } ?: s.appTagline,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        model.ifBlank { s.selectModel },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Icon(
                                        Icons.Filled.ArrowDropDown,
                                        contentDescription = s.selectModel,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        },
                    )
                    // Two tabs (T-ML28 #3): the chat and the project's file tree.
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(s.tabPrompt) })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(s.tabProjectTree) })
                    }
                }
            },
            bottomBar = {
                if (tab == 0) {
                Column {
                    // ---------- meter token (T-ML29) ----------
                    // Kiri: pemakaian sesi ini (usage gateway, semua provider).
                    // Kanan: sisa token APInex (hanya provider apinex).
                    TokenMeterRow(
                        sessionTokens = sessionTokens,
                        showQuota = Graph.prefs.provider == "apinex" && Graph.prefs.apiKey.isNotBlank(),
                        quotaState = quotaState,
                        onRefreshQuota = { refreshQuota(fresh = true) },
                    )
                    // ---------- composer ----------
                    Surface(tonalElevation = 3.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).imePadding(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.weight(1f),
                            ) {
                                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    if (input.isEmpty()) {
                                        Text(
                                            s.inputPlaceholder,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    BasicTextField(
                                        value = input,
                                        onValueChange = { input = it },
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                        modifier = Modifier.fillMaxWidth(),
                                        maxLines = 6,
                                    )
                                }
                            }
                            if (live != null && live!!.busy) {
                                FilledIconButton(
                                    onClick = { engine?.stop() },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        StopIcon,
                                        contentDescription = s.stop,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            } else {
                                FilledIconButton(
                                    onClick = {
                                        if (current == null) {
                                            // New session → name its project first
                                            // (T-ML28 #4); the typed text survives
                                            // in the field and is sent on confirm.
                                            pendingSend = true
                                            showNewProject = true
                                            return@FilledIconButton
                                        }
                                        // Delegated var: smart-cast does not survive
                                        // the null branch above — the elvis keeps
                                        // the compiler's null-safety satisfied.
                                        val target = current ?: return@FilledIconButton
                                        val e = engines.getOrPut(target.id) { ChatEngine(target) }
                                        // Keep the typed text when the turn cannot
                                        // start (e.g. still busy) — it is not lost.
                                        if (e.send(input, model)) input = ""
                                    },
                                    enabled = input.isNotBlank() && model.isNotBlank(),
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(Icons.Filled.Send, contentDescription = s.send)
                                }
                            }
                        }
                    }
                }
                }
            },
        ) { padding ->
            // ---------- messages / project tree (two tabs, T-ML28 #3) ----------
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (tab == 1) {
                    ProjectTreePanel(
                        rootPath = current?.project?.let { p ->
                            projectDir(Graph.prefs.workdirHint.ifBlank { "/sdcard/coding-sachet" }, p)
                        } ?: "",
                        isProject = current?.project != null,
                        version = treeVersion,
                    )
                } else if (msgs.isEmpty() && live == null) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(s.chatEmpty, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(
                            s.chatEmptyHint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(msgs) { m -> MessageBubble(m, toolResults, activeCallIds) }
                        live?.let { l -> item { LiveBubble(l) } }
                    }
                }
            }
        }
    }

    if (showModels) {
        ModelPicker(
            current = model,
            onPick = {
                model = it
                Graph.prefs.model = it
                current?.model = it
                showModels = false
            },
            onDismiss = { showModels = false },
        )
    }

    // New-chat popup (T-ML28 #4): every new session names its project —
    // one project per folder in the agent's default path.
    if (showNewProject) {
        NewProjectDialog(
            onConfirm = { name ->
                showNewProject = false
                createSessionWith(name)
            },
            onDismiss = {
                showNewProject = false
                pendingSend = false
            },
        )
    }
}

/** Remaining-quota chip state (APInex only; hidden for NIM). */
private sealed class QuotaState {
    object Idle : QuotaState()
    object Loading : QuotaState()
    data class Ok(val unlimited: Boolean, val tokens: Long?) : QuotaState()
    data class Failed(val message: String) : QuotaState()
}

/** Token meter row above the composer (T-ML29): session usage on the left
 *  (gateway-reported via stream_options.include_usage, any provider),
 *  APInex remaining quota on the right. Both counts are the ACTUAL number
 *  (formatTokenCount, id-ID grouping — 19300000 → "19.300.000", T-ML28 #2),
 *  never an abbreviation — the old "±20,0 jt" rounding hid ±100k. */
@Composable
private fun TokenMeterRow(
    sessionTokens: Long,
    showQuota: Boolean,
    quotaState: QuotaState,
    onRefreshQuota: () -> Unit,
) {
    val showSession = sessionTokens > 0
    if (!showSession && !(showQuota && quotaState !is QuotaState.Idle)) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSession) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.heightIn(min = 32.dp).padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    "${LocalStrings.current.sessionUsageLabel}: ${formatTokenCount(sessionTokens)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (showQuota) QuotaChip(state = quotaState, onRefresh = onRefreshQuota)
    }
}

/** The APInex remaining-quota chip — a Surface only; the enclosing row owns
 *  alignment. Emits nothing while Idle. */
@Composable
private fun QuotaChip(state: QuotaState, onRefresh: () -> Unit) {
    val s = LocalStrings.current
    if (state is QuotaState.Idle) return
    val value = when (state) {
        QuotaState.Idle -> return
        QuotaState.Loading -> "…"
        is QuotaState.Ok -> when {
            state.unlimited -> "∞"
            state.tokens != null -> formatTokenCount(state.tokens)
            else -> "?"
        }
        is QuotaState.Failed -> "—"
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clickable(onClick = onRefresh)
            .semantics { contentDescription = "${s.quotaLabel}: $value" }
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            "${s.quotaLabel}: $value",
            style = MaterialTheme.typography.labelMedium,
            color = if (state is QuotaState.Failed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Persisted messages: user bubbles right, assistant content + tool cards left. */
@Composable
private fun MessageBubble(m: Msg, toolResults: Map<String, Msg>, activeCallIds: Set<String> = emptySet()) {
    when (m.role) {
        "user" -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Text(m.content, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        "assistant" -> {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ThinkingBlock(m.thinking)
                if (m.content.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(Modifier.padding(12.dp)) { Markdownish(m.content) }
                    }
                }
                m.toolCalls?.forEach { call ->
                    val result = toolResults[call.id]
                    ToolCard(
                        ToolCardUi(
                            id = call.id,
                            name = call.name,
                            args = prettyArgs(call.args),
                            output = result?.content,
                            ok = result?.let { resultOk(it) },
                            interrupted = result == null && call.id !in activeCallIds,
                        )
                    )
                }
            }
        }
        // "tool" results are rendered inside their cards
    }
}

private fun prettyArgs(args: JsonElement): String =
    runCatching { PrettyJson.encodeToString(JsonElement.serializer(), args) }.getOrDefault(args.toString())

private fun resultOk(m: Msg): Boolean {
    val first = m.content.lineSequence().firstOrNull() ?: return false
    return Regex("exit=0(\\D|$)").containsMatchIn(first) || m.content.contains("\"ok\":true")
}

/** The in-flight turn rendered below the history. */
@Composable
private fun LiveBubble(l: LiveTurn) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxWidth().widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Rate-limit wait hint (client-side 15 RPM cap / 429 backoff).
        l.waiting?.let {
            Text(
                "${s.rateLimitWaiting} (${it}s)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        // Gateway connect retry hint (cold VPN path, fresh-budget retry).
        // Names the gateway when the server says which one it is retrying —
        // APInex and NIM failures look identical otherwise.
        l.reconnect?.let {
            Text(
                "${s.reconnecting} ${l.reconnectTo ?: "gateway"} ($it/3)…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        // Server-death recovery hint (T-ML27 P3): the agent server was
        // killed mid-turn (exit 137 in the field) and the turn will resume
        // by itself once the runtime has it healthy again.
        if (l.restarting) {
            Text(
                s.serverRestarting,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        ThinkingBlock(l.thinking)
        if (l.text.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(12.dp)) { Markdownish(l.text) }
            }
        }
        l.cards.forEach { ToolCard(it) }
        if (l.error != null) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    l.error!!,
                    Modifier.padding(10.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (l.busy && l.cards.isEmpty() && l.text.isBlank() && l.thinking.isBlank()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun ThinkingBlock(thinking: String?) {
    if (thinking.isNullOrBlank()) return
    var open by remember(thinking) { mutableStateOf(false) }
    Text(
        if (open) thinking else LocalStrings.current.thinking + " ▾",
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (open) 40 else 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val provider = Graph.prefs.active
    var models by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var freeOnly by remember { mutableStateOf(true) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        models = null
        error = null
        Graph.api.models(Graph.prefs.apiKey, provider)
            .onSuccess { models = it }
            .onFailure { error = it.message }
    }

    // Live verification results (T-ML26, owner order: "mengecek actual
    // bukan dari membaca hardcoded list"): the dots and free badges below
    // read this snapshot — filled by a real sweep against the gateway,
    // refreshed on every result while the sheet is open.
    var status by remember(provider.id) { mutableStateOf(ModelStatus.snapshot(provider.id)) }
    LaunchedEffect(provider.id) {
        val listener: (String) -> Unit = { pid ->
            if (pid == provider.id) status = ModelStatus.snapshot(provider.id)
        }
        ModelStatus.listen(listener)
        try { awaitCancellation() } finally { ModelStatus.unlisten(listener) }
    }
    // Once the catalogue loads, start a sweep when the stored results are
    // stale (or missing). It keeps running after the sheet closes.
    LaunchedEffect(models) {
        models?.let { Graph.verify.ensure(provider, Graph.prefs.apiKey, it, current) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.selectModel, style = MaterialTheme.typography.titleMedium)
            // Legend for the live dots: green = answered the sweep, red =
            // refused/hung, gray = not checked (yet).
            Text(s.modelVerifiedNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(s.modelSearch) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // "Free" = the gateway's free/ tier + the live sweep's free
                // flag (allowFree catalogue on APInex; answering models on
                // NIM are free to use).
                FilterChip(
                    selected = freeOnly,
                    onClick = { freeOnly = !freeOnly },
                    label = { Text(s.modelFreeOnly) },
                )
                val loaded = models
                if (loaded != null) {
                    val n = loaded.count { !freeOnly || provider.isFreeByPrefix(it) || status[it]?.free == true }
                    Text("$n / ${loaded.size} · ${provider.label}", style = MaterialTheme.typography.bodySmall)
                }
            }
            // Sweep status row: progress + cancel while running, timestamp
            // + recheck button when idle (T-ML26).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Graph.verify.isRunning(provider.id)) {
                    CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                    val p = Graph.verify.progress(provider.id)
                    Text(
                        if (p != null) String.format(s.modelCheckingFmt, p.first, p.second) else s.modelChecking,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { Graph.verify.cancel(provider.id) }) { Text(s.cancel) }
                } else {
                    val last = ModelStatus.lastCheckedAt(provider.id)
                    if (last != null) {
                        val stamp = java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(last))
                        Text(
                            String.format(s.modelLastCheckedFmt, stamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val loaded = models
                    if (loaded != null) {
                        TextButton(onClick = { Graph.verify.ensure(provider, Graph.prefs.apiKey, loaded, current, force = true) }) {
                            Text(s.modelRecheck)
                        }
                    }
                }
            }
            when {
                error != null -> Column {
                    Text("${s.modelFetchError}: $error", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { attempt++ }) { Text(s.retry) }
                }
                models == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.width(18.dp), strokeWidth = 2.dp)
                    Text(s.modelFetching)
                }
                else -> LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    // Live-verified models on top, never-checked next,
                    // confirmed-dead last; stable within each group.
                    val sorted = models!!.sortedWith(
                        compareByDescending { m ->
                            when (status[m]?.state) {
                                ModelStatus.State.OK -> 2
                                ModelStatus.State.UNKNOWN, null -> 1
                                ModelStatus.State.DEAD -> 0
                            }
                        }
                    )
                    val filtered = sorted.filter {
                        (!freeOnly || provider.isFreeByPrefix(it) || status[it]?.free == true) &&
                            it.contains(query, ignoreCase = true)
                    }
                    items(filtered) { m ->
                        ModelRow(
                            model = m,
                            state = status[m]?.state ?: ModelStatus.State.UNKNOWN,
                            free = provider.isFreeByPrefix(m) || status[m]?.free == true,
                            current = current,
                            onPick = { onPick(m) },
                        )
                    }
                }
            }
        }
    }
}

/** One model row: live dot (green/red/gray), name, FREE badge, current check. */
@Composable
private fun ModelRow(model: String, state: ModelStatus.State, free: Boolean, current: String, onPick: () -> Unit) {
    val s = LocalStrings.current
    ListItem(
        modifier = Modifier.clickable(onClick = onPick),
        colors = if (model == current) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = {
            Box(
                Modifier
                    .size(10.dp)
                    .background(
                        when (state) {
                            ModelStatus.State.OK -> sachetSuccess()
                            ModelStatus.State.DEAD -> MaterialTheme.colorScheme.error
                            ModelStatus.State.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        CircleShape,
                    ),
            )
        },
        headlineContent = {
            Text(
                model,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (free) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            s.modelFreeBadge,
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                if (model == current) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
    )
}

// ---------- new-project popup + project tree (T-ML28 #3/#4) ----------

/** One node of the project tree (lazy: children load on first expand). */
private data class TreeEntry(
    val name: String,
    val path: String,
    val dir: Boolean,
    val size: Long,
    val symlink: Boolean,
)

/** Popup shown on every new chat session: name the project — one project
 *  per folder inside the agent's default path. */
@Composable
private fun NewProjectDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    var name by remember { mutableStateOf("") }
    val folder = Graph.prefs.workdirHint.ifBlank { "/sdcard/coding-sachet" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.newProjectTitle) },
        text = {
            Column {
                Text(s.newProjectHint, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(s.newProjectName) },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "${s.newProjectFolder}: $folder/" +
                        (if (name.isBlank()) "…" else ProjectNames.sanitize(name)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(s.newProjectCreate)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

/** Read-only file preview for the project tree (editing stays in File tab). */
@Composable
private fun TreeFileViewer(path: String, onClose: () -> Unit) {
    val s = LocalStrings.current
    var content by remember { mutableStateOf<String?>(null) }
    var binary by remember { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        Graph.api.fsFile(path)
            .onSuccess { o: JsonObject ->
                binary = o["binary"]?.jsonPrimitive?.booleanOrNull ?: false
                truncated = o["truncated"]?.jsonPrimitive?.booleanOrNull ?: false
                content = o["content"]?.jsonPrimitive?.contentOrNull ?: ""
            }
            .onFailure { error = it.message }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                Text(path, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                when {
                    error != null -> Text(s.fileLoadError + ": " + error, color = MaterialTheme.colorScheme.error)
                    content == null -> CircularProgressIndicator()
                    binary -> Text(s.fileBinary)
                    else -> {
                        if (truncated) {
                            Text(s.fileTooLarge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                content ?: "",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(320.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(s.cancel) } },
    )
}

/** The "Project tree" tab: lazily-expanded folder tree of the session's
 *  project folder (or the main workdir for legacy project-less sessions).
 *  [version] bumps after every finished turn so freshly written files show
 *  up without a manual refresh. */
@Composable
private fun ProjectTreePanel(rootPath: String, isProject: Boolean, version: Int) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    var resolvedRoot by remember { mutableStateOf(rootPath) }
    var roots by remember { mutableStateOf<List<TreeEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val expanded = remember(rootPath) { mutableStateMapOf<String, Boolean>() }
    val children = remember(rootPath) { mutableStateMapOf<String, List<TreeEntry>?>() }
    var viewer by remember { mutableStateOf<String?>(null) }

    fun parseEntries(obj: JsonObject): List<TreeEntry> {
        val base = obj["path"]?.jsonPrimitive?.contentOrNull ?: rootPath
        return (obj["entries"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList()))
            .mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                TreeEntry(
                    name = name,
                    path = if (base == "/") "/$name" else "$base/$name",
                    dir = o["dir"]?.jsonPrimitive?.booleanOrNull ?: false,
                    size = o["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    symlink = o["symlink"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
            .sortedWith(compareByDescending<TreeEntry> { it.dir }.thenBy { it.name.lowercase() })
    }

    fun loadChildren(dir: String) {
        children[dir] = null // loading marker
        scope.launch {
            Graph.api.fsList(dir)
                .onSuccess { children[dir] = parseEntries(it) }
                .onFailure { children[dir] = emptyList() }
        }
    }

    fun loadRoot() {
        error = null
        roots = null
        scope.launch {
            // A project folder may not exist yet (session created, nothing
            // sent): create it first — idempotent, the agent server does the
            // same on every send.
            if (isProject && rootPath.isNotBlank()) {
                Graph.api.fsAction("mkdir", rootPath)
            }
            Graph.api.fsList(rootPath)
                .onSuccess { obj ->
                    (obj["path"]?.jsonPrimitive?.contentOrNull)?.takeIf { it.isNotBlank() }?.let { resolvedRoot = it }
                    roots = parseEntries(obj)
                }
                .onFailure { error = it.message }
        }
    }

    LaunchedEffect(rootPath, version) { loadRoot() }

    fun toggle(e: TreeEntry) {
        if (!e.dir) {
            viewer = e.path
            return
        }
        val open = expanded[e.path] == true
        expanded[e.path] = !open
        if (!open && !children.containsKey(e.path)) loadChildren(e.path)
    }

    Column(Modifier.fillMaxSize()) {
        // Header: resolved root path + refresh.
        Surface(tonalElevation = 1.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        resolvedRoot.ifBlank { s.treeNoProject },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!isProject) {
                        Text(
                            s.treeNoProject,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { loadRoot() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = s.treeRefresh)
                }
            }
        }
        when {
            error != null -> Column(Modifier.padding(16.dp)) {
                Text(s.treeLoadError, color = MaterialTheme.colorScheme.error)
                Text(error ?: "", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { loadRoot() }) { Text(s.retry) }
            }
            roots == null -> Row(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            roots!!.isEmpty() -> Text(
                s.treeEmpty,
                Modifier.fillMaxWidth().padding(24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                val visible = buildList {
                    fun walk(list: List<TreeEntry>, depth: Int) {
                        for (e in list) {
                            add(e to depth)
                            if (e.dir && expanded[e.path] == true) {
                                children[e.path]?.let { walk(it, depth + 1) }
                            }
                        }
                    }
                    walk(roots!!, 0)
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(visible, key = { it.first.path }) { (e, depth) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { toggle(e) }
                                .padding(
                                    start = (12 + depth * 20).dp,
                                    end = 12.dp,
                                    top = 8.dp,
                                    bottom = 8.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (e.dir) {
                                Icon(
                                    if (expanded[e.path] == true) Icons.Filled.KeyboardArrowDown
                                    else Icons.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Spacer(Modifier.width(18.dp))
                            }
                            Icon(
                                if (e.dir) FolderIcon else TextFileIcon,
                                contentDescription = null,
                                tint = if (e.dir) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                e.name + if (e.symlink) " →" else "",
                                Modifier.weight(1f),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!e.dir) {
                                Text(
                                    treeFileSize(e.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    viewer?.let { vp ->
        TreeFileViewer(path = vp, onClose = { viewer = null })
    }
}

private fun treeFileSize(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / (1024.0 * 1024.0))
}
