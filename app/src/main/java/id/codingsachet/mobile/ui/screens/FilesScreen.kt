package id.codingsachet.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import id.codingsachet.mobile.ui.AddIcon
import id.codingsachet.mobile.ui.ArrowBackIcon
import id.codingsachet.mobile.ui.CheckIcon
import id.codingsachet.mobile.ui.CloseIcon
import id.codingsachet.mobile.ui.CreateNewFolderIcon
import id.codingsachet.mobile.ui.FolderIcon
import id.codingsachet.mobile.ui.KeyboardArrowUpIcon
import id.codingsachet.mobile.ui.LocalStrings
import id.codingsachet.mobile.ui.StorageIcon
import id.codingsachet.mobile.ui.TextFileIcon
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private data class Entry(val name: String, val dir: Boolean, val size: Long, val symlink: Boolean = false)

/** Browse and edit files inside the Ubuntu workdir. */
@Composable
fun FilesScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var viewer by remember { mutableStateOf<String?>(null) }   // path of the opened file
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) } // action, prefill

    fun load(p: String) {
        path = p
        entries = null
        error = null
        scope.launch {
            Graph.api.fsList(p)
                .onSuccess { obj ->
                    // Trust the server's resolved path: an empty request path
                    // means "the agent workdir" (now /sdcard/coding-sachet).
                    (obj["path"]?.jsonPrimitive?.content)?.takeIf { it.isNotBlank() }?.let { path = it }
                    val list = obj["entries"]?.jsonArray?.map { el ->
                        val o = el.jsonObject
                        Entry(
                            name = o["name"]?.jsonPrimitive?.content ?: "?",
                            dir = o["dir"]?.jsonPrimitive?.boolean ?: false,
                            size = o["size"]?.jsonPrimitive?.long ?: 0L,
                            symlink = o["symlink"]?.jsonPrimitive?.boolean ?: false,
                        )
                    }.orEmpty().sortedWith(compareByDescending<Entry> { it.dir }.thenBy { it.name.lowercase() })
                    entries = list
                }
                .onFailure { error = it.message }
        }
    }

    // Empty path = the server's resolved workdir (/sdcard/coding-sachet,
    // falling back to /root/work when shared storage is unavailable).
    LaunchedEffect(Unit) { load("") }

    // Scaffold miuix: wajib agar OverlayDialog punya MiuixPopupHost (MIGRATION.md §7)
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = s.filesTitle,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(ArrowBackIcon, contentDescription = s.filesTitle) }
                },
                actions = {
                    if (Graph.prefs.rootAccess) {
                        IconButton(onClick = { load("/device") }) {
                            Icon(StorageIcon, contentDescription = s.deviceRoot, tint = MiuixTheme.colorScheme.primary)
                        }
                    }
                    IconButton(onClick = { dialog = "folder" to "" }) {
                        Icon(CreateNewFolderIcon, contentDescription = s.newFolder)
                    }
                    IconButton(onClick = { dialog = "file" to "" }) {
                        Icon(AddIcon, contentDescription = s.newFile)
                    }
                },
            )
        },
    ) { padding ->
    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).padding(padding)) {

        // Path bar + up
        Surface(color = MiuixTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = { load(parentOf(path)) }) {
                    Icon(KeyboardArrowUpIcon, contentDescription = s.filesUp, tint = MiuixTheme.colorScheme.primary)
                }
                Text(
                    path,
                    Modifier.weight(1f),
                    fontFamily = FontFamily.Monospace,
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when {
            error != null -> Column(Modifier.padding(16.dp)) {
                Text(s.fileLoadError, color = MiuixTheme.colorScheme.error)
                Text(error ?: "", style = MiuixTheme.textStyles.body2)
            }
            entries == null -> Row(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
            ) {
                if (entries!!.isEmpty()) {
                    item {
                        Text(
                            s.filesEmpty,
                            Modifier.fillMaxWidth().padding(24.dp),
                            style = MiuixTheme.textStyles.main,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                items(entries!!, key = { it.name }) { e ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val child = if (path == "/") "/$e.name" else "$path/${e.name}"
                                if (e.dir) load(child) else viewer = child
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            if (e.dir) FolderIcon else TextFileIcon,
                            contentDescription = null,
                            tint = if (e.dir) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            e.name + if (e.symlink) " →" else "",
                            Modifier.weight(1f),
                            fontFamily = FontFamily.Monospace,
                            style = MiuixTheme.textStyles.main,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!e.dir) {
                            Text(
                                humanSize(e.size),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
    }
    }

    // ---- file viewer / editor ----
    viewer?.let { vp ->
        FileViewer(
            path = vp,
            onClose = { viewer = null },
            onSaved = { load(path) },
        )
    }

    // ---- new file / folder dialog ----
    dialog?.let { (kind, prefill) ->
        var name by remember { mutableStateOf(prefill) }
        OverlayDialog(
            show = true,
            onDismissRequest = { dialog = null },
            title = if (kind == "file") s.newFile else s.newFolder,
        ) {
            Column {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = s.namePlaceholder,
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = s.save,
                        onClick = {
                            val child = if (path == "/") "/$name" else "$path/$name"
                            scope.launch {
                                if (kind == "file") {
                                    Graph.api.fsWrite(child, "").onSuccess { load(path) }
                                } else {
                                    Graph.api.fsAction("mkdir", child).onSuccess { load(path) }
                                }
                                dialog = null
                            }
                        },
                        enabled = name.isNotBlank(),
                    )
                    TextButton(text = s.cancel, onClick = { dialog = null })
                }
            }
        }
    }
}

private fun parentOf(p: String): String {
    if (p == "/" || p.isEmpty()) return "/"
    val i = p.lastIndexOf('/')
    return if (i <= 0) "/" else p.substring(0, i)
}

private fun humanSize(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / (1024.0 * 1024.0))
}

@Composable
private fun FileViewer(path: String, onClose: () -> Unit, onSaved: () -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    var content by remember { mutableStateOf<String?>(null) }
    var binary by remember { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var saveNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        Graph.api.fsFile(path)
            .onSuccess { o: JsonObject ->
                binary = o["binary"]?.jsonPrimitive?.boolean ?: false
                truncated = o["truncated"]?.jsonPrimitive?.boolean ?: false
                content = o["content"]?.jsonPrimitive?.content ?: ""
                draft = content ?: ""
            }
            .onFailure { error = it.message }
    }

    OverlayDialog(
        show = true,
        onDismissRequest = onClose,
        title = path.substringAfterLast('/'),
    ) {
        Column {
            Text(path, style = MiuixTheme.textStyles.body2, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(8.dp))
            when {
                error != null -> Text(s.fileLoadError + ": " + error, color = MiuixTheme.colorScheme.error)
                content == null -> CircularProgressIndicator()
                binary -> Text(s.fileBinary)
                else -> {
                    if (truncated) Text(s.fileTooLarge, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
                    if (editing) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MiuixTheme.colorScheme.surfaceVariant,
                        ) {
                            BasicTextField(
                                value = draft,
                                onValueChange = { draft = it },
                                textStyle = MiuixTheme.textStyles.body2.copy(
                                    fontFamily = FontFamily.Monospace,
                                    color = MiuixTheme.colorScheme.onSurface,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(320.dp)
                                    .padding(8.dp),
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MiuixTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                content ?: "",
                                fontFamily = FontFamily.Monospace,
                                style = MiuixTheme.textStyles.body2,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(320.dp)
                                    .padding(8.dp),
                            )
                        }
                    }
                    saveNote?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.body2)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!binary && content != null && !editing) {
                    TextButton(text = s.edit, onClick = { editing = true })
                }
                if (editing) {
                    TextButton(
                        text = s.save,
                        onClick = {
                            scope.launch {
                                Graph.api.fsWrite(path, draft)
                                    .onSuccess {
                                        content = draft
                                        editing = false
                                        saveNote = s.fileSaved
                                        onSaved()
                                    }
                                    .onFailure { saveNote = it.message }
                            }
                        },
                    )
                }
                if (editing) {
                    IconButton(onClick = { editing = false; draft = content ?: "" }) {
                        Icon(CloseIcon, contentDescription = s.cancel)
                    }
                }
                TextButton(text = s.cancel, onClick = onClose)
            }
        }
    }
}