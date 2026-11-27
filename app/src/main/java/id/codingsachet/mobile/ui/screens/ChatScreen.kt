package id.codingsachet.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.codingsachet.mobile.Graph
import id.codingsachet.mobile.api.GatewayStatus
import id.codingsachet.mobile.api.ModelStatus
import id.codingsachet.mobile.data.Msg
import id.codingsachet.mobile.data.Session
import id.codingsachet.mobile.data.ToolCall
import id.codingsachet.mobile.runtime.RuntimeState
import id.codingsachet.mobile.ui.ChatEngine
import id.codingsachet.mobile.ui.FolderIcon
import id.codingsachet.mobile.ui.LiveTurn
import id.codingsachet.mobile.ui.LocalStrings
import id.codingsachet.mobile.ui.ROLE_EXECUTOR
import id.codingsachet.mobile.ui.ROLE_ORCHESTRATOR
import id.codingsachet.mobile.ui.ProjectNames
import id.codingsachet.mobile.ui.StopIcon
import id.codingsachet.mobile.ui.TextFileIcon
import id.codingsachet.mobile.ui.ToolCardUi
import id.codingsachet.mobile.ui.canContinueFromHistory
import id.codingsachet.mobile.ui.formatTokenCount
import id.codingsachet.mobile.ui.formatIdrAmount
import id.codingsachet.mobile.ui.projectDir
import id.codingsachet.mobile.ui.components.Markdownish
import id.codingsachet.mobile.ui.components.ToolCard
import id.codingsachet.mobile.ui.components.ToolCardGroup
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
import android.widget.Toast
import id.codingsachet.mobile.ui.ChatIntent
import id.codingsachet.mobile.ui.CopyIcon

private val PrettyJson = Json { prettyPrint = true }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    session: Session?,
    onSessionChange: (Session?) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSettings: () -> Unit,
    intent: ChatIntent = ChatIntent.None,
    onIntentConsumed: () -> Unit = {},
) {
    val s = LocalStrings.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val runtimeState by Graph.runtime.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // Back closes the session drawer first (the AppRoot back handlers are
    // composed before this one, so this wins whenever the drawer is open).
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    // Re-initialize only when the RESTORED session id changes (activity
    // recreation after process death); switching chats from the drawer goes
    // through onSessionChange with the same live object.
    var current by remember(session?.id) { mutableStateOf<Session?>(session) }
    // Role yang sedang dipilih di top bar — null = tutup sheet.
    var pickRole by remember { mutableStateOf<String?>(null) }
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

    // T-ML65 #1: edit-and-resend target (a user message in the open
    // history). The index is captured at tap time by identity — Msg is a
    // data class, so indexOf() could match a duplicated message.
    var editTarget by remember { mutableStateOf<Pair<Msg, Int>?>(null) }

    /** Copy [text] to the system clipboard with a light confirmation. */
    fun copyText(text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, s.copiedToClipboard, Toast.LENGTH_SHORT).show()
    }

    // T-ML65 #2: homepage entry points. "Proyek baru" opens the project
    // dialog (the send button buffers its text the same way); "Proyek
    // berjalan" opens the left drawer over the project/session list. The
    // effect re-fires whenever the intent CHANGES, so it also works when
    // ChatScreen was already mounted under the Home overlay.
    LaunchedEffect(intent) {
        when (intent) {
            ChatIntent.NewProject -> showNewProject = true
            ChatIntent.OpenDrawer -> scope.launch { drawer.open() }
            ChatIntent.None -> {}
        }
        if (intent != ChatIntent.None) onIntentConsumed()
    }

    // One engine per session, kept for the lifetime of the screen so a
    // running stream survives switching back and forth between chats.
    val engines = remember { HashMap<String, ChatEngine>() }
    DisposableEffect(Unit) {
        onDispose { engines.values.forEach { it.dispose() } }
    }
    val engine = current?.let { engines.getOrPut(it.id) { ChatEngine(it) } }

    // ---------- sisa token (chip di kanan atas kolom chat) ----------
    // APInex (/v1/subscription) dan 100milionseek (header x-quota-*, T-ML52);
    // NIM tanpa API kuota — chip disembunyikan.
    var quotaState by remember { mutableStateOf<QuotaState>(QuotaState.Idle) }
    fun refreshQuota(fresh: Boolean = false) {
        if (!Graph.prefs.active.supportsQuota || Graph.prefs.apiKey.isBlank()) {
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
                    else QuotaState.Ok(q.unlimited, q.tokensRemaining, q.tokenLimit, q.mode)
                },
                onFailure = { quotaState = QuotaState.Failed(it.message ?: "gagal") },
            )
        }
    }
    LaunchedEffect(engine) { engine?.onTurnFinished = { refreshQuota(); treeVersion++ } }
    LaunchedEffect(Unit) { refreshQuota() }
    LaunchedEffect(runtimeState) { if (runtimeState is RuntimeState.Running) refreshQuota() }

    // T-ML73: dot koneksi gateway real-time (2 warna). Probe periodik
    // GET /models langsung ke provider aktif + feed event stream chat.
    // Statusnya dibaca di picker model (ModelRow) — top bar tidak
    // menampilkannya (owner order T-ML73b).
    LaunchedEffect(Unit) {
        GatewayStatus.monitor(scope) {
            Pair(Graph.prefs.provider, Graph.prefs.apiKey)
        } { key, pid -> Graph.api.pingGateway(key, pid) }
    }

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

    // T-ML58 #2: history dipangkas jadi entri render — barisan pesan
    // assistant bertool TANPA teks (putaran-putaran murni tool engine Pi)
    // diringkas jadi satu grup; pesan bertext/user tampil apa adanya.
    val entries = remember(msgs.size, busyNow) {
        buildChatEntries(msgs, toolResults, activeCallIds)
    }

    val listState = rememberLazyListState()
    LaunchedEffect(live?.text?.length, live?.cards?.size, live?.thinking?.length, msgs.size) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    fun newChat(project: String? = null): Session {
        val ns = Graph.chats.create(Graph.prefs.orchestratorEffective, project)
        // T-ML75: reload() di sini dulu membangun ulang index dari disk —
        // objek di index jadi BERBEDA referensi dari ns yang dipakai engine
        // (autoTitle/persist menulis ns, drawer membaca objek disk: data
        // judul terputus). create() sudah menaruh ns di index + menyimpan
        // ke disk, jadi reload tidak diperlukan di jalur buat.
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
                // T-ML72: orchestrator efektif, bukan model mentah prefs.
                if (e.send(input, Graph.prefs.orchestratorEffective)) input = ""
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
                                            // T-ML58 #3: pilih sesi terbaru
                                            // berikutnya (konsisten dengan
                                            // fallback restore AppState) —
                                            // bukan layar kosong.
                                            val next = Graph.chats.sessions.firstOrNull()
                                            current = next
                                            onSessionChange(next)
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
                    // T-ML65 #1: salin percakapan penuh (transkrip teks).
                    NavigationDrawerItem(
                        label = { Text(s.copyChat) },
                        icon = { Icon(CopyIcon, contentDescription = s.copyChat) },
                        selected = false,
                        onClick = {
                            scope.launch { drawer.close() }
                            val text = buildTranscript(
                                current?.messages ?: emptyList(),
                                s.transcriptUser,
                                s.transcriptAssistant,
                            )
                            if (text.isNotBlank()) copyText(text)
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
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
                        // T-ML72: title block = dual-model selector — dua chip
                        // peran (Orchestrator/Executor) yang masing-masing
                        // membuka picker model untuk peran itu. Menggantikan
                        // selector model tunggal lama (T-ML28).
                        title = {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            ) {
                                Text(
                                    current?.title?.ifBlank { s.chatNew } ?: s.appTagline,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    RoleModelChip(
                                        label = s.dualOrchestrator,
                                        model = Graph.prefs.orchestratorEffective,
                                        highlight = MaterialTheme.colorScheme.primary,
                                        onClick = { pickRole = "orch" },
                                    )
                                    RoleModelChip(
                                        label = s.dualExecutor,
                                        model = Graph.prefs.executorEffective.ifBlank { s.executorOffShort },
                                        highlight = MaterialTheme.colorScheme.tertiary,
                                        onClick = { pickRole = "exec" },
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
                    // Kanan: sisa token (APInex + 100milionseek, T-ML52).
                    TokenMeterRow(
                        sessionTokens = sessionTokens,
                        showQuota = Graph.prefs.active.supportsQuota && Graph.prefs.apiKey.isNotBlank(),
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
                                        // T-ML72: kirim dengan orchestrator efektif
                                        // (top bar dual-model).
                                        if (e.send(input, Graph.prefs.orchestratorEffective)) input = ""
                                    },
                                    enabled = input.isNotBlank() && Graph.prefs.orchestratorEffective.isNotBlank(),
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
                        items(entries) { entry ->
                            when (entry) {
                                is ChatEntry.Single -> MessageBubble(
                                    entry.m,
                                    toolResults,
                                    activeCallIds,
                                    onCopy = {
                                        // T-ML65 #1: user text or the
                                        // assistant's text (tool cards are
                                        // rendered inside, not copied).
                                        if (entry.m.content.isNotBlank()) copyText(entry.m.content)
                                    },
                                    onEdit = if (entry.m.role == "user" && !busyNow) {
                                        {
                                            // Identity lookup — Msg is a data
                                            // class, indexOf() could match a
                                            // duplicate older message.
                                            val idx = msgs.indexOfFirst { it === entry.m }
                                            if (idx >= 0) editTarget = Pair(entry.m, idx)
                                        }
                                    } else null,
                                )
                                is ChatEntry.Group -> ToolCardGroup(
                                    entry.cards,
                                    String.format(s.toolGroupFmt, entry.cards.size),
                                )
                            }
                        }
                        live?.let { l -> item { LiveBubble(l, onResume = { engine?.continueRun() }) } }
                        // T-ML68 #2: baris lanjut PERMANEN — turun dari history
                        // yang dipersist (bukan live turn), jadi tetap ada
                        // setelah restart app / ganti sesi balik lagi. Muncul
                        // hanya saat tidak ada turn hidup DAN model masih
                        // berhutang jawaban (ujung history = hasil tool /
                        // panggilan tool tanpa hasil / pesan user belum dijawab).
                        if (live == null && !busyNow && canContinueFromHistory(msgs)) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            s.resumePendingHint,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Button(onClick = { engine?.continueRun() }) {
                                            Text(s.resumeRun)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // T-ML72: sheet pemilih model per-peran (orchestrator/executor).
    // Persist ke prefs dual-model engine aktif (oc atau pi) + provider
    // aktif — persis semantik Settings. Orchestrator kosong = model chat
    // aktif (Graph.prefs.model); executor kosong = single-model.
    pickRole?.let { role ->
        DualRoleModelPicker(
            isExecutor = role == "exec",
            current = if (role == "exec") Graph.prefs.executorEffective else Graph.prefs.orchestratorEffective,
            onPick = { m ->
                val p = Graph.prefs
                if (role == "exec") {
                    if (p.engineOc) p.ocExecutor = m else p.piExecutor = m
                } else {
                    // Orchestrator = model chat aktif juga (menimpa model
                    // pengirim, konsisten dengan semangat T-ML69 — model
                    // terpilih memimpin putaran pertama).
                    if (p.engineOc) p.ocOrchestrator = m else p.piOrchestrator = m
                    p.model = m
                    current?.model = m
                }
                // Tandai konfigurasi dual milik provider aktif (batas v1:
                // satu provider per run — lihat OcOptions/PiOptions).
                if (p.engineOc) p.ocProvider = p.active.id else p.piProvider = p.active.id
                pickRole = null
            },
            onDismiss = { pickRole = null },
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

    // T-ML65 #1: edit a sent user message → drop the rest of the history
    // and resend the edited text as a fresh turn.
    editTarget?.let { (target, idx) ->
        EditMessageDialog(
            initial = target.content,
            onConfirm = { newText ->
                editTarget = null
                val sess = current ?: return@EditMessageDialog
                val e = engines.getOrPut(sess.id) { ChatEngine(sess) }
                // Keep the dialog text when the turn cannot start (busy).
                // T-ML72: model pengirim = orchestrator efektif (model chat
                // aktif atau yang dipilih di top bar).
                if (e.resendFrom(idx, newText, Graph.prefs.orchestratorEffective)) Unit
            },
            onDismiss = { editTarget = null },
        )
    }
}

/** Remaining-quota chip state (APInex + 100milionseek; hidden for NIM). */
private sealed class QuotaState {
    object Idle : QuotaState()
    object Loading : QuotaState()
    data class Ok(val unlimited: Boolean, val tokens: Long?, val tokenLimit: Long? = null, val mode: String? = null) : QuotaState()
    data class Failed(val message: String) : QuotaState()
}

/** Token meter row above the composer (T-ML29): session usage pinned LEFT
 *  (gateway-reported via stream_options.include_usage, any provider),
 *  remaining quota/balance pinned RIGHT (T-ML74 — owner order: kiri bawah =
 *  pemakaian sesi, kanan bawah = Saldo/Token/Balance). Both counts are the
 *  ACTUAL number (formatTokenCount, id-ID grouping — 19300000 →
 *  "19.300.000", T-ML28 #2), never an abbreviation — the old "±20,0 jt"
 *  rounding hid ±100k.
 *
 *  T-ML43 fix retained: padding INSIDE the pill so rounded ends never crop
 *  the first/last glyph; the middle Spacer eats leftover width so long
 *  values ellipsize on their own side instead of crushing each other on
 *  360dp screens. */
@Composable
private fun TokenMeterRow(
    sessionTokens: Long,
    showQuota: Boolean,
    quotaState: QuotaState,
    onRefreshQuota: () -> Unit,
) {
    val showSession = sessionTokens > 0
    val showQuotaChip = showQuota && quotaState !is QuotaState.Idle
    if (!showSession && !showQuotaChip) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showSession) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.heightIn(min = 32.dp),
            ) {
                Text(
                    "${LocalStrings.current.sessionUsageLabel}: ${formatTokenCount(sessionTokens)}",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Spacer dorong chip kanan ke pojok kanan bawah.
        Spacer(Modifier.weight(1f))
        if (showQuotaChip) QuotaChip(state = quotaState, onRefresh = onRefreshQuota)
    }
}

/** The remaining-quota chip — a Surface only; the enclosing row owns
 *  alignment. Emits nothing while Idle. Tappable to refresh.
 *  T-ML43: padding INSIDE the pill, ripple clipped to the pill shape (clip
 *  before clickable), ellipsis as the ultra-narrow safety net.
 *  T-ML64: NgodeAI mode "idr" — the number is the IDR wallet balance, so
 *  the label becomes "Saldo" and the value a Rupiah figure (id-ID
 *  grouping), never a token abbreviation. */
@Composable
private fun QuotaChip(state: QuotaState, onRefresh: () -> Unit) {
    val s = LocalStrings.current
    if (state is QuotaState.Idle) return
    val idr = state is QuotaState.Ok && state.mode == "idr"
    val label = if (idr) s.balanceLabel else s.quotaLabel
    val value = when (state) {
        QuotaState.Idle -> return
        QuotaState.Loading -> "…"
        is QuotaState.Ok -> when {
            state.unlimited -> "∞"
            idr -> state.tokens?.let { "Rp " + formatIdrAmount(it) } ?: "?"
            // T-ML49b: KEY TERKUNCI vs JATAH HABIS dibedakan lewat tokenLimit.
            // Terkunci = tokens 0 DAN limit 0 (tidak ada alokasi sama sekali —
            // bukti probe 2026-11-20: 402 "top-up $5 to unlock 25M free tokens
            // daily for life", token_limit server = 0) → hint cara membuka.
            // Habis = limit > 0 tapi remaining 0 (25jt terpakai, reset tengah
            // malam UTC) → tetap angka polos, bukan hint top-up.
            state.tokens == 0L && state.tokenLimit == 0L && state.mode == "free" -> "0${s.quotaLockedHint}"
            state.tokens != null -> formatTokenCount(state.tokens)
            else -> "?"
        }
        is QuotaState.Failed -> "—"
    }
    val chipShape = RoundedCornerShape(16.dp)
    Surface(
        shape = chipShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(chipShape)
            .clickable(onClick = onRefresh)
            .semantics { contentDescription = "$label: $value" },
    ) {
        Text(
            "$label: $value",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (state is QuotaState.Failed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Persisted messages: user bubbles right, assistant content + tool cards left. */
@Composable
private fun MessageBubble(
    m: Msg,
    toolResults: Map<String, Msg>,
    activeCallIds: Set<String> = emptySet(),
    onCopy: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    when (m.role) {
        "user" -> {
            // T-ML66: long-press menu retired — Salin / Edit sit as compact
            // buttons directly under the bubble (right-aligned like the
            // bubble itself). Edit is passed only for user messages while
            // no turn is running; hidden otherwise.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Surface(
                    shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Text(m.content, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                MessageActionRow(onCopy = onCopy, onEdit = onEdit)
            }
        }
        "assistant" -> {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ModelTag(m.model, m.modelRole)
                ThinkingBlock(m.thinking)
                if (m.content.isNotBlank()) {
                    // T-ML66: Salin as a compact button under the answer
                    // (long-press menu retired).
                    Surface(
                        shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(Modifier.padding(12.dp)) { Markdownish(m.content) }
                    }
                    MessageActionRow(onCopy = onCopy, onEdit = null)
                }
                m.toolCalls?.let { calls ->
                    if (calls.size >= 3) {
                        // T-ML58 #2: satu putaran dengan banyak panggilan →
                        // satu grup (mis. batch eksplorasi engine legacy).
                        ToolCardGroup(
                            calls.map { call -> cardFor(call, toolResults, activeCallIds) },
                            String.format(s.toolGroupFmt, calls.size),
                        )
                    } else {
                        calls.forEach { call ->
                            ToolCard(cardFor(call, toolResults, activeCallIds))
                        }
                    }
                }
            }
        }
        // "tool" results are rendered inside their cards
    }
}

/** T-ML69 #1 (owner order: "labeling untuk orchestrator & executor —
 *  show each model names"): chip kecil di atas gelembung assistant —
 *  peran + nama model yang menulis pesan itu. Executor mendapat warna
 *  berbeda agar pergantian model mid-run terlihat sekilas. Null model
 *  (pesan lama pra-label / pesan non-assistant) = tanpa chip. */
@Composable
private fun ModelTag(model: String?, role: String?) {
    if (model.isNullOrBlank()) return
    val executor = role == ROLE_EXECUTOR
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (executor) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            "${if (executor) ROLE_EXECUTOR else ROLE_ORCHESTRATOR} · $model",
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (executor) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** T-ML66 (replaces the T-ML65 long-press dropdown): compact action
 *  buttons under one bubble — Salin (user + assistant text) and Edit &
 *  kirim ulang (user messages, only while no turn is running). Actions
 *  are always visible instead of hidden behind a long-press gesture;
 *  emits nothing when both actions are null. */
@Composable
private fun MessageActionRow(
    onCopy: (() -> Unit)?,
    onEdit: (() -> Unit)?,
) {
    val s = LocalStrings.current
    if (onCopy == null && onEdit == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        onCopy?.let { act -> MessageActionButton(s.copyMessage, CopyIcon, act) }
        onEdit?.let { act -> MessageActionButton(s.editMessage, Icons.Filled.Edit, act) }
    }
}

/** One small icon+label button for [MessageActionRow]; quiet onSurface-
 *  Variant colors so long transcripts stay readable. */
@Composable
private fun MessageActionButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(14.dp), tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

/** T-ML65 #1 (murni, teruji JVM): transkrip teks "Salin percakapan" —
 * hanya pesan user/assistant yang berisi teks (hasil tool dan putaran
 * tool-tanpa-teks sudah diringkas di UI, tidak masuk transkrip). */
internal fun buildTranscript(
    msgs: List<Msg>,
    userLabel: String,
    assistantLabel: String,
): String = msgs
    .filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
    .joinToString("\n\n") { m ->
        (if (m.role == "user") userLabel else assistantLabel) + ":\n" + m.content.trim()
    }

private fun prettyArgs(args: JsonElement): String =
    runCatching { PrettyJson.encodeToString(JsonElement.serializer(), args) }.getOrDefault(args.toString())

private fun resultOk(m: Msg): Boolean {
    val first = m.content.lineSequence().firstOrNull() ?: return false
    return Regex("exit=0(\\D|$)").containsMatchIn(first) || m.content.contains("\"ok\":true")
}

/** Kartu UI untuk satu panggilan tool tersimpan (dipakai MessageBubble dan
 *  grup entri history). */
private fun cardFor(call: ToolCall, toolResults: Map<String, Msg>, activeCallIds: Set<String>): ToolCardUi {
    val result = toolResults[call.id]
    return ToolCardUi(
        id = call.id,
        name = call.name,
        args = prettyArgs(call.args),
        output = result?.content,
        ok = result?.let { resultOk(it) },
        interrupted = result == null && call.id !in activeCallIds,
    )
}

/** Satu entri render kolom chat (T-ML58 #2). [Group] menampung barisan
 *  putaran tool tanpa teks — dipangkas jadi satu baris grup. */
sealed interface ChatEntry {
    data class Single(val m: Msg) : ChatEntry
    data class Group(val cards: List<ToolCardUi>, val msgs: List<Msg>) : ChatEntry
}

/**
 * T-ML58 #2 (murni, teruji JVM): pangkas history jadi entri render.
 * Barisan pesan assistant yang HANYA memanggil tool (tanpa teks — bentuk
 * khas engine Pi: satu tool per putaran, puluhan putaran) diringkas jadi
 * SATU [ChatEntry.Group]; pesan berikutnya yang bertext / dari user
 * memutus barisan. Pesan hasil tool (role "tool") tidak pernah memutus
 * barisan — mereka dirender di dalam kartu panggilnya.
 */
internal fun buildChatEntries(
    msgs: List<Msg>,
    toolResults: Map<String, Msg> = emptyMap(),
    activeCallIds: Set<String> = emptySet(),
): List<ChatEntry> {
    val out = ArrayList<ChatEntry>(msgs.size)
    var pending = ArrayList<Msg>()
    fun flush() {
        val cards = pending.flatMap { it.toolCalls.orEmpty() }.map { cardFor(it, toolResults, activeCallIds) }
        if (pending.size >= 2 && cards.size >= 2) {
            out += ChatEntry.Group(cards, pending)
        } else {
            pending.forEach { out += ChatEntry.Single(it) }
        }
        pending = ArrayList()
    }
    for (m in msgs) {
        when {
            // Hasil tool menyusul assistant-nya — tidak memutus barisan
            // (kartunya dirender dalam grup yang sama).
            m.role == "tool" -> if (pending.isNotEmpty()) pending += m else out += ChatEntry.Single(m)
            m.role == "assistant" && !m.toolCalls.isNullOrEmpty() && m.content.isBlank() -> pending += m
            else -> { flush(); out += ChatEntry.Single(m) }
        }
    }
    flush()
    return out
}

/** The in-flight turn rendered below the history. */
@Composable
private fun LiveBubble(l: LiveTurn, onResume: () -> Unit = {}) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxWidth().widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // T-ML69 #1: chip model yang sedang menulis stream ini — berganti
        // sendiri saat executor mengambil alih (event 'model').
        ModelTag(l.model, l.modelRole)
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
        // APInex and NIM failures look identical otherwise. The budget is
        // dynamic (T-ML37): 3 for the quick in-fetch phase, 10 for the
        // 10 × 10 s connect ladder.
        l.reconnect?.let {
            Text(
                "${s.reconnecting} ${l.reconnectTo ?: "gateway"} ($it/${l.reconnectMax ?: 3})…",
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
        // T-ML58 #2: kartu putaran BERJALAN — yang sudah selesai dalam
        // giliran ini diringkas; 2 kartu terakhir tetap tampil (yang sedang
        // dieksekusi + konteks terdekatnya).
        val tailCount = if (l.cards.size >= 5) 2 else l.cards.size
        val older = l.cards.dropLast(tailCount)
        if (older.isNotEmpty()) {
            ToolCardGroup(older, String.format(s.toolGroupPriorFmt, older.size))
        }
        l.cards.takeLast(tailCount).forEach { ToolCard(it) }
        if (l.error != null) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        l.error!!,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // T-ML37 P1-3 + T-ML68 #2: tombol Lanjutkan/retry tetap
                    // tampil di SEMUA kartu error (truncated, lost connection,
                    // gagal sebelum putaran pertama) selama tidak ada turn
                    // berjalan — engine.continueRun() yang memutus validitas
                    // (history kosong saja yang ditolak). Dulu gerbang
                    // canContinue menyembunyikan tombol persis pada kasus
                    // "Tekan Lanjutkan untuk mencoba lagi".
                    if (!l.busy) {
                        Button(
                            onClick = onResume,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(s.resumeRun)
                        }
                    }
                }
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

/** T-ML72: chip peran di top bar — label peran + model efektif, klik
 *  membuka picker untuk peran itu. Layout menyerupai chip dropdown. */
@Composable
private fun RoleModelChip(label: String, model: String, highlight: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = highlight,
                )
                Text(
                    model,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = label,
                tint = highlight,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** T-ML72: sheet pemilih model untuk satu peran (orchestrator/executor)
 *  dari top bar. Daftar model provider aktif + pencarian + baris
 *  "model chat aktif" (orchestrator) / "nonaktif" (executor). Dot baris =
 *  koneksi gateway real-time, 2 warna (T-ML73). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DualRoleModelPicker(isExecutor: Boolean, current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val provider = Graph.prefs.active
    // T-ML73: status koneksi gateway — satu dot untuk semua baris.
    val gatewayConn by GatewayStatus.flow(provider.id).collectAsState()
    var models by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        models = null
        error = null
        Graph.api.models(Graph.prefs.apiKey, provider)
            .onSuccess { models = it }
            .onFailure { error = it.message }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (isExecutor) s.dualExecutor else s.dualOrchestrator,
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(s.modelSearch) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
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
                    // Tanda pilihan untuk baris khusus saat prefs masih kosong
                    // (orchestrator = model chat aktif / executor nonaktif).
                    val blankLabel = if (current.isBlank()) {
                        if (isExecutor) s.dualExecutorOff else s.dualOrchestratorChatModel
                    } else ""
                    item {
                        ModelRow(
                            model = if (isExecutor) s.dualExecutorOff else s.dualOrchestratorChatModel,
                            tier = null,
                            free = false,
                            current = current.ifBlank { blankLabel },
                            onPick = { onPick("") },
                            conn = gatewayConn,
                        )
                    }
                    val filtered = models!!.filter { it.contains(query, ignoreCase = true) }
                    items(filtered) { m ->
                        ModelRow(
                            model = m,
                            tier = ModelStatus.tierOf(provider.id, m),
                            free = ModelStatus.isFree(provider, m),
                            current = current,
                            onPick = { onPick(m) },
                            conn = gatewayConn,
                        )
                    }
                }
            }
        }
    }
}

/** One model row: gateway connection dot (2 warna, T-ML73 — hijau =
 *  koneksi gateway OK & live, merah = putus), name, billing badge
 *  (T-ML49: free = jatah harian · langganan = butuh paket · top-up =
 *  saldo USD; dari flag tier sweep katalog), current check. */
@Composable
private fun ModelRow(model: String, tier: ModelStatus.Tier?, free: Boolean, current: String, onPick: () -> Unit, conn: GatewayStatus.Conn) {
    val s = LocalStrings.current
    ListItem(
        modifier = Modifier.clickable(onClick = onPick),
        colors = if (model == current) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = {
            // T-ML73: dot 2 warna — status KONEKSI gateway real-time
            // (bukan verdict per-model). Hijau = gateway OK & live;
            // merah = koneksi ke gateway terputus.
            Box(
                Modifier
                    .size(10.dp)
                    .background(
                        if (conn == GatewayStatus.Conn.LIVE) sachetSuccess()
                        else MaterialTheme.colorScheme.error,
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
                // Badge billing: hanya SATU per model. Tier sweep menang
                // (langganan/top-up); fallback lama = flag free (prefix).
                val badge: Pair<String, androidx.compose.ui.graphics.Color>? = when (tier) {
                    ModelStatus.Tier.SUBSCRIPTION -> s.modelSubBadge to MaterialTheme.colorScheme.secondaryContainer
                    ModelStatus.Tier.TOPUP -> s.modelTopupBadge to MaterialTheme.colorScheme.surfaceVariant
                    else -> if (free) s.modelFreeBadge to MaterialTheme.colorScheme.tertiaryContainer else null
                }
                badge?.let { (text, bg) ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = bg,
                    ) {
                        Text(
                            text,
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = when (tier) {
                                ModelStatus.Tier.SUBSCRIPTION -> MaterialTheme.colorScheme.onSecondaryContainer
                                ModelStatus.Tier.TOPUP -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onTertiaryContainer
                            },
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

/** T-ML65 #1: edit-and-resend — dialog dengan teks asli yang bisa
 *  diubah, tombol "Kirim ulang". */
@Composable
private fun EditMessageDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.editResendTitle) },
        text = {
            Column {
                Text(s.editResendHint, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 10,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text(s.editResendSend)
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
