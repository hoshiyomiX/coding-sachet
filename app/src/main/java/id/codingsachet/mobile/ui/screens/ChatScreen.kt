package id.codingsachet.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.codingsachet.mobile.Graph
import id.codingsachet.mobile.data.Msg
import id.codingsachet.mobile.data.Session
import id.codingsachet.mobile.runtime.RuntimeState
import id.codingsachet.mobile.ui.ChatEngine
import id.codingsachet.mobile.ui.LiveTurn
import id.codingsachet.mobile.ui.LocalStrings
import id.codingsachet.mobile.ui.SachetSky
import id.codingsachet.mobile.ui.TextFileIcon
import id.codingsachet.mobile.ui.ToolCardUi
import id.codingsachet.mobile.ui.components.Markdownish
import id.codingsachet.mobile.ui.components.ToolCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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

    var current by remember { mutableStateOf<Session?>(session) }
    var model by remember(Graph.prefs.provider) { mutableStateOf(Graph.prefs.model) }
    var showModels by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var sessionsVersion by remember { mutableIntStateOf(0) }
    val nullLive = remember { MutableStateFlow<LiveTurn?>(null) }

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
                    else QuotaState.Ok(q.unlimited, q.tokensRemaining, q.balanceUsd)
                },
                onFailure = { quotaState = QuotaState.Failed(it.message ?: "gagal") },
            )
        }
    }
    LaunchedEffect(engine) { engine?.onTurnFinished = { refreshQuota() } }
    LaunchedEffect(Unit) { refreshQuota() }
    LaunchedEffect(runtimeState) { if (runtimeState is RuntimeState.Running) refreshQuota() }

    val live by (engine?.live ?: nullLive).collectAsState()
    val msgs = current?.messages ?: emptyList()

    // Tool results of the persisted history, keyed by toolCallId.
    val toolResults = remember(msgs.size) {
        msgs.filter { it.role == "tool" && it.toolCallId != null }.associateBy { it.toolCallId!! }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(live?.text?.length, live?.cards?.size, live?.thinking?.length, msgs.size) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    fun newChat(): Session {
        val ns = Graph.chats.create(model)
        Graph.chats.reload()
        sessionsVersion++
        current = ns
        onSessionChange(ns)
        return ns
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.sessionsTitle, style = MaterialTheme.typography.titleMedium, color = SachetSky)
                    TextButton(onClick = {
                        scope.launch { drawer.close() }
                        newChat()
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(s.chatNew)
                    }
                    val sessions = remember(sessionsVersion) { Graph.chats.sessions }
                    LazyColumn {
                        items(sessions, key = { it.id }) { sess ->
                            SessionRow(
                                sess = sess,
                                active = sess.id == current?.id,
                                onClick = {
                                    current = sess
                                    onSessionChange(sess)
                                    scope.launch { drawer.close() }
                                },
                                onDelete = {
                                    Graph.chats.delete(sess)
                                    Graph.chats.reload()
                                    sessionsVersion++
                                    if (current?.id == sess.id) {
                                        current = null
                                        onSessionChange(null)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---------- top bar ----------
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { scope.launch { drawer.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = null)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            current?.title?.ifBlank { s.chatNew } ?: s.appTagline,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        RuntimeChip(runtimeState)
                    }
                    TextButton(onClick = { showModels = true }) {
                        Text(model.ifBlank { s.selectModel }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onOpenFiles) { Icon(TextFileIcon, contentDescription = s.filesTitle) }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = s.settingsTitle) }
                }
            }

            // ---------- messages ----------
            Box(Modifier.weight(1f)) {
                if (msgs.isEmpty() && live == null) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(s.chatEmpty, style = MaterialTheme.typography.titleMedium, color = SachetSky)
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
                        items(msgs) { m -> MessageBubble(m, toolResults) }
                        live?.let { l -> item { LiveBubble(l) } }
                    }
                }
            }

            // ---------- sisa token APInex (di atas kolom chat, kanan) ----------
            if (Graph.prefs.provider == "apinex" && Graph.prefs.apiKey.isNotBlank()) {
                QuotaChip(state = quotaState, onRefresh = { refreshQuota(fresh = true) })
            }

            // ---------- composer ----------
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp).imePadding(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(22.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
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
                    if (live != null && live!!.busy) {
                        IconButton(onClick = { engine?.stop() }) {
                            Text("■", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleLarge)
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (current == null) newChat()
                                val target = current ?: return@IconButton
                                val e = engines.getOrPut(target.id) { ChatEngine(target) }
                                // Keep the typed text when the turn cannot
                                // start (e.g. still busy) — it is not lost.
                                if (e.send(input, model)) input = ""
                            },
                            enabled = input.isNotBlank() && model.isNotBlank(),
                        ) {
                            Icon(
                                Icons.Filled.Send,
                                contentDescription = s.send,
                                tint = if (input.isNotBlank() && model.isNotBlank()) SachetSky else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
}

@Composable
private fun SessionRow(sess: Session, active: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    sess.title.ifBlank { "…" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(sess.created)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RuntimeChip(state: RuntimeState) {
    val s = LocalStrings.current
    val (label, color) = when (state) {
        is RuntimeState.Running -> s.runtimeRunning to SachetSky
        is RuntimeState.Starting -> s.runtimeStarting to MaterialTheme.colorScheme.onSurfaceVariant
        is RuntimeState.Crashed -> s.runtimeCrashed to MaterialTheme.colorScheme.error
        else -> s.runtimeStopped to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
}

/** Remaining-quota chip state (APInex only; hidden for NIM). */
private sealed class QuotaState {
    object Idle : QuotaState()
    object Loading : QuotaState()
    data class Ok(val unlimited: Boolean, val tokens: Long?, val balanceUsd: Double?) : QuotaState()
    data class Failed(val message: String) : QuotaState()
}

/** Compact Indonesian token counts: 19300000 → "19,3 jt", 45000 → "45,0 rb". */
private fun formatTokens(tokens: Long): String {
    val loc = java.util.Locale("id", "ID")
    return when {
        tokens >= 1_000_000L -> String.format(loc, "%.1f jt", tokens / 1e6)
        tokens >= 1_000L -> String.format(loc, "%.1f rb", tokens / 1e3)
        else -> tokens.toString()
    }
}

/** "Sisa token" indicator above the chat composer, right-aligned. */
@Composable
private fun QuotaChip(state: QuotaState, onRefresh: () -> Unit) {
    val s = LocalStrings.current
    val value = when (state) {
        QuotaState.Idle -> return
        QuotaState.Loading -> "…"
        is QuotaState.Ok -> when {
            state.unlimited -> "∞"
            state.tokens != null -> {
                val usd = state.balanceUsd
                if (usd != null) {
                    "±${formatTokens(state.tokens)} · " + String.format(java.util.Locale("id", "ID"), "$%.2f", usd)
                } else {
                    "±${formatTokens(state.tokens)}"
                }
            }
            state.balanceUsd != null -> String.format(java.util.Locale("id", "ID"), "$%.2f", state.balanceUsd)
            else -> "?"
        }
        is QuotaState.Failed -> "—"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            "${s.quotaLabel}: $value",
            style = MaterialTheme.typography.labelSmall,
            color = if (state is QuotaState.Failed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onRefresh).padding(vertical = 2.dp),
        )
    }
}

/** Persisted messages: user bubbles right, assistant content + tool cards left. */
@Composable
private fun MessageBubble(m: Msg, toolResults: Map<String, Msg>) {
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
    Column(Modifier.fillMaxWidth().widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.selectModel, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(s.modelSearch) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The free/ filter only means something on APInex; NIM's whole
                // catalogue is free to prototype.
                if (provider.freeModelPrefix != null) {
                    FilterChip(
                        selected = freeOnly,
                        onClick = { freeOnly = !freeOnly },
                        label = { Text(s.modelFreeOnly) },
                    )
                }
                val loaded = models
                if (loaded != null) {
                    val n = loaded.count { provider.freeModelPrefix == null || !freeOnly || it.startsWith(provider.freeModelPrefix) }
                    Text("$n / ${loaded.size} · ${provider.label}", style = MaterialTheme.typography.bodySmall)
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
                    val filtered = models!!.filter {
                        (provider.freeModelPrefix == null || !freeOnly || it.startsWith(provider.freeModelPrefix)) &&
                            it.contains(query, ignoreCase = true)
                    }
                    items(filtered) { m ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (m == current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onPick(m) },
                        ) {
                            Text(m, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
