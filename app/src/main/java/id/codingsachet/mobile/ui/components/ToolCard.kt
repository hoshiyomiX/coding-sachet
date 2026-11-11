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
import androidx.compose.ui.unit.dp
import id.codingsachet.mobile.ui.LocalStrings
import id.codingsachet.mobile.ui.ToolCardUi
import id.codingsachet.mobile.ui.sachetSuccess

/** Small monogram chip identifying a tool. Colors follow the theme so
 *  both light and dark modes keep readable contrast. */
@Composable
private fun ToolBadge(name: String) {
    val (letter, color) = when {
        name == "bash" -> "$" to MaterialTheme.colorScheme.primary
        name == "read_file" -> "R" to MaterialTheme.colorScheme.secondary
        name == "write_file" -> "W" to sachetSuccess()
        name == "list_dir" -> "L" to MaterialTheme.colorScheme.tertiary
        else -> "T" to MaterialTheme.colorScheme.secondary
    }
    Box(
        Modifier.size(28.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = color, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

private fun toolLabel(name: String, s: id.codingsachet.mobile.ui.SachetStrings): String = when (name) {
    "bash" -> s.toolBash
    "read_file" -> s.toolRead
    "write_file" -> s.toolWrite
    "list_dir" -> s.toolList
    else -> name
}

/** One line summary of the tool call shown when collapsed. */
private fun summarize(card: ToolCardUi): String = when (card.name) {
    "bash" -> firstJsonField(card.args, "command") ?: card.args.lineSequence().firstOrNull() ?: ""
    "read_file" -> firstJsonField(card.args, "path") ?: ""
    "write_file" -> firstJsonField(card.args, "path") ?: ""
    "list_dir" -> firstJsonField(card.args, "path") ?: ""
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
