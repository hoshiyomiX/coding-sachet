package id.codingsachet.mobile.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.codingsachet.mobile.ui.LocalStrings
import id.codingsachet.mobile.ui.ToolCardUi
import id.codingsachet.mobile.ui.sachetSuccess

/** Small monogram chip identifying a tool. Colors follow the theme so
 *  both light and dark modes keep readable contrast. T-ML58 #2: engine Pi
 *  memancarkan nama tool ASLINYA (read/write/edit/grep/glob) — petakan
 *  juga, bukan hanya nama tool engine legacy (read_file/write_file/…). */
@Composable
private fun ToolBadge(name: String) {
    val (letter, color) = when (name) {
        "bash" -> "$" to MaterialTheme.colorScheme.primary
        "read_file", "read" -> "R" to MaterialTheme.colorScheme.secondary
        "write_file", "write" -> "W" to sachetSuccess()
        "edit" -> "E" to sachetSuccess()
        "list_dir" -> "L" to MaterialTheme.colorScheme.tertiary
        "todo" -> "C" to sachetSuccess()
        "grep" -> "G" to MaterialTheme.colorScheme.tertiary
        "glob" -> "*" to MaterialTheme.colorScheme.tertiary
        else -> "T" to MaterialTheme.colorScheme.secondary
    }
    Box(
        Modifier.size(28.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

internal fun toolLabel(name: String, s: id.codingsachet.mobile.ui.SachetStrings): String = when (name) {
    "bash" -> s.toolBash
    "read_file", "read" -> s.toolRead
    "write_file", "write" -> s.toolWrite
    "edit" -> s.toolEdit
    "list_dir" -> s.toolList
    "todo" -> s.toolTodo
    "grep" -> s.toolGrep
    "glob" -> s.toolGlob
    "" -> s.toolUnknown
    else -> name
}

/** One line summary of the tool call shown when collapsed. */
internal fun summarize(card: ToolCardUi): String = when (card.name) {
    "bash" -> firstJsonField(card.args, "command") ?: card.args.lineSequence().firstOrNull() ?: ""
    "read_file", "read" -> firstJsonField(card.args, "path") ?: ""
    "write_file", "write" -> firstJsonField(card.args, "path") ?: ""
    "edit" -> firstJsonField(card.args, "path") ?: firstJsonField(card.args, "file_path") ?: ""
    "glob" -> firstJsonField(card.args, "pattern") ?: firstJsonField(card.args, "path") ?: ""
    "grep" -> firstJsonField(card.args, "pattern") ?: firstJsonField(card.args, "query") ?: ""
    "list_dir" -> firstJsonField(card.args, "path") ?: ""
    "todo" -> firstJsonField(card.args, "aksi") ?: ""
    else -> card.args.take(60)
}.take(72)

private fun firstJsonField(json: String, field: String): String? {
    val needle = "\"$field\""
    val i = json.indexOf(needle)
    if (i < 0) return null
    val rest = json.substring(i + needle.length).trimStart(' ', ':', ' ')
    val quote = rest.indexOf('"')
    if (quote < 0) return rest.take(40)
    val end = rest.indexOf('"', quote + 1)
    return if (end > quote) rest.substring(quote + 1, end) else rest.take(40)
}

/** Expandable card for one agent tool call. */
@Composable
fun ToolCard(card: ToolCardUi) {
    val s = LocalStrings.current
    var open by remember(card.id) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.clickable { open = !open }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToolBadge(card.name)
                Column(Modifier.weight(1f)) {
                    Text(toolLabel(card.name, s), style = MaterialTheme.typography.titleSmall)
                    Text(
                        summarize(card),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (open) 4 else 1,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (card.ok) {
                    null -> Text(
                        if (card.interrupted) s.toolInterrupted else s.toolRunning,
                        style = MaterialTheme.typography.labelSmall,
                        // Interrupted reads as a muted state, not the active
                        // primary color a running call shows (T-ML31 P2-7).
                        color = if (card.interrupted) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                    )
                    true -> Text(s.toolDone, style = MaterialTheme.typography.labelSmall, color = sachetSuccess())
                    false -> Text(s.toolFailed, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Icon(
                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (open) {
                if (card.args.isNotBlank()) {
                    Text(
                        card.args.trim(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                    )
                }
                if (card.output != null) {
                    Text(
                        card.output.ifBlank { "(empty)"}.take(6000),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}

/**
 * T-ML58 #2: N kartu tool diringkas jadi SATU baris grup yang bisa dibuka.
 * Bukti lapangan 1.0.58: sesi engine Pi 418K tok = puluhan putaran tool, tiap
 * putaran menggambar kartu penuh — chat dipenuhi baris "Terminal / Baca file
 * / Tulis file / read / write / …". Satu baris grup menahan semua kartu
 * putaran yang sudah lewat; mengetuknya membuka kartu individual.
 */
@Composable
fun ToolCardGroup(cards: List<ToolCardUi>, title: String) {
    val s = LocalStrings.current
    var open by remember(cards.firstOrNull()?.id, cards.size) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(
            Modifier.clickable { open = !open }.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    // Rincian jenis tool, mis. "Terminal ×3 · Baca file ×2".
                    val breakdown = cards.groupingBy { it.name }.eachCount()
                        .entries.sortedByDescending { it.value }
                        .joinToString(" · ") { (n, c) -> "${toolLabel(n, s)} ×$c" }
                    Text(
                        breakdown,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (open) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Status ringkas grup: ada gagal → gagal; ada yang masih
                // berjalan → berjalan; ada terputus (run mati) → terputus.
                val running = cards.count { it.ok == null && !it.interrupted }
                val failed = cards.count { it.ok == false }
                val interrupted = cards.count { it.interrupted }
                val (status, color) = when {
                    failed > 0 -> s.toolFailed to MaterialTheme.colorScheme.error
                    running > 0 -> s.toolRunning to MaterialTheme.colorScheme.primary
                    interrupted > 0 -> s.toolInterrupted to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> s.toolDone to sachetSuccess()
                }
                Text(status, style = MaterialTheme.typography.labelSmall, color = color)
                Icon(
                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (open) cards.forEach { ToolCard(it) }
        }
    }
}
