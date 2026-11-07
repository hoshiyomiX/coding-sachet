package id.apinex.mobile.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import id.apinex.mobile.Graph
import id.apinex.mobile.ui.FolderIcon
import id.apinex.mobile.ui.LocalStrings
import id.apinex.mobile.ui.TextFileIcon
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

private data class Entry(val name: String, val dir: Boolean, val size: Long, val symlink: Boolean = false)

/** Browse and edit files inside the Ubuntu workdir. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()

    var path by remember { mutableStateOf("/root/work") }
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

    LaunchedEffect(Unit) { load("/root/work") }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(s.filesTitle, style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = null) }
            },
            actions = {
                IconButton(onClick = { dialog = "file" to "" }) {
                    Icon(Icons.Filled.Add, contentDescription = s.newFile)
                }
            },
        )

        // Path bar + up
        Surface(tonalElevation = 1.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { load(parentOf(path)) }) { Text(s.filesUp + " ⌃") }
                Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
            }
        }

        when {
            error != null -> Column(Modifier.padding(16.dp)) {
                Text(s.fileLoadError, color = MaterialTheme.colorScheme.error)
                Text(error ?: "", style = MaterialTheme.typography.bodySmall)
            }
            entries == null -> Row(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
            ) {
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
                            tint = if (e.dir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            e.name + if (e.symlink) " →" else "",
                            Modifier.weight(1f),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!e.dir) {
                            Text(
                                humanSize(e.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (kind == "file") s.newFile else s.newFolder) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(s.namePlaceholder) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
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
                ) { Text(s.save) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(s.cancel) } },
        )
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
                        if (truncated) Text(s.fileTooLarge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        if (editing) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                BasicTextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
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
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    content ?: "",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(320.dp)
                                        .padding(8.dp),
                                )
                            }
                        }
                        saveNote?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (!binary && content != null && !editing) {
                    TextButton(onClick = { editing = true }) { Text(s.edit) }
                }
                if (editing) {
                    TextButton(
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
                    ) { Icon(Icons.Filled.Check, contentDescription = s.save, tint = MaterialTheme.colorScheme.primary) }
                }
            }
        },
        dismissButton = {
            Row {
                if (editing) {
                    IconButton(onClick = { editing = false; draft = content ?: "" }) {
                        Icon(Icons.Filled.Close, contentDescription = s.cancel)
                    }
                }
                TextButton(onClick = onClose) { Text(s.cancel) }
            }
        },
    )
}
