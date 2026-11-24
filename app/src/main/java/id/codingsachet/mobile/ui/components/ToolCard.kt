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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import id.codingsachet.mobile.ui.KeyboardArrowDownIcon
import id.codingsachet.mobile.ui.KeyboardArrowUpIcon
import id.codingsachet.mobile.ui.LocalStrings
import id.codingsachet.mobile.ui.ToolCardUi
import id.codingsachet.mobile.ui.sachetSuccess
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * T-ML67 (owner order "more compact & simple tools call session"): every
 * tool round renders as ONE flat line — colored monospace glyph, merged
 * "label · summary" ellipsized, an 8dp status dot, a small chevron. No
 * per-card Surface, no 28dp badge box, no status words: a long Pi run
 * reads like a tight log instead of a stack of full-width cards. Tap the
 * row to expand args/output (kept from T-ML58).
 */

/** Monogram letter + theme color identifying a tool (T-ML58 #2 lineage —
 *  engine Pi emits its native tool names too, not just legacy ones). */
@Composable
internal fun toolGlyph(name: String): Pair<String, Color> {
    val c = MiuixTheme.colorScheme
    return when (name) {
        "bash" -> "$" to c.primary
        "read_file", "read" -> "R" to c.secondary
        "write_file", "write" -> "W" to sachetSuccess()
        "edit" -> "E" to sachetSuccess()
        "list_dir" -> "L" to c.primaryVariant
        "todo" -> "C" to sachetSuccess()
        "grep" -> "G" to c.primaryVariant
        "glob" -> "*" to c.primaryVariant
        else -> "T" to c.secondary
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

/** Status color for one call: running = primary, interrupted = muted
 *  (T-ML31 P2-7), done = success, failed = error. */
@Composable
private fun statusColor(ok: Boolean?, interrupted: Boolean): Color = when (ok) {
    null -> if (interrupted) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.primary
    true -> sachetSuccess()
    false -> MiuixTheme.colorScheme.error
}

@Composable
private fun statusText(card: ToolCardUi): String {
    val s = LocalStrings.current
    return when (card.ok) {
        null -> if (card.interrupted) s.toolInterrupted else s.toolRunning
        true -> s.toolDone
        false -> s.toolFailed
    }
}

/** 8dp status dot (T-ML67): replaces the status word — the color carries
 *  the state, the text rides along as contentDescription for a11y. */
@Composable
private fun StatusDot(color: Color, description: String) {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = description },
    )
}

/** One tool call as a single flat expandable line. */
@Composable
fun ToolCard(card: ToolCardUi) {
    val s = LocalStrings.current
    var open by remember(card.id) { mutableStateOf(false) }
    val (letter, glyphColor) = toolGlyph(card.name)

    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                letter,
                color = glyphColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MiuixTheme.textStyles.body2,
            )
            val summary = summarize(card)
            val line = if (summary.isBlank()) toolLabel(card.name, s)
            else "${toolLabel(card.name, s)} · $summary"
            Text(
                line,
                style = MiuixTheme.textStyles.body2,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusDot(statusColor(card.ok, card.interrupted), statusText(card))
            Icon(
                if (open) KeyboardArrowUpIcon else KeyboardArrowDownIcon,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(16.dp),
            )
        }
        if (open) {
            if (card.args.isNotBlank()) {
                Text(
                    card.args.trim(),
                    fontFamily = FontFamily.Monospace,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                        .padding(6.dp),
                )
            }
            if (card.output != null) {
                Text(
                    card.output.ifBlank { "(empty)"}.take(6000),
                    fontFamily = FontFamily.Monospace,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MiuixTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                        .padding(6.dp),
                )
            }
        }
    }
}

/**
 * T-ML58 #2 → T-ML67: N completed tool rounds held by ONE compact group
 * header (single line: title · breakdown + status dot); opening it lays
 * out the individual flat rows. Field proof 1.0.58: a 418K-token Pi run
 * drew dozens of full cards — now it reads as one quiet line.
 */
@Composable
fun ToolCardGroup(cards: List<ToolCardUi>, title: String) {
    val s = LocalStrings.current
    var open by remember(cards.firstOrNull()?.id, cards.size) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MiuixTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.clickable { open = !open }.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Rincian jenis tool, mis. "Terminal ×3 · Baca file ×2".
                val breakdown = cards.groupingBy { it.name }.eachCount()
                    .entries.sortedByDescending { it.value }
                    .joinToString(" · ") { (n, c) -> "${toolLabel(n, s)} ×$c" }
                Text(
                    if (breakdown.isBlank()) title else "$title · $breakdown",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Status ringkas grup: ada gagal → gagal; ada yang masih
                // berjalan → berjalan; ada terputus (run mati) → terputus.
                val running = cards.count { it.ok == null && !it.interrupted }
                val failed = cards.count { it.ok == false }
                val interrupted = cards.count { it.interrupted }
                val (status, color) = when {
                    failed > 0 -> s.toolFailed to MiuixTheme.colorScheme.error
                    running > 0 -> s.toolRunning to MiuixTheme.colorScheme.primary
                    interrupted > 0 -> s.toolInterrupted to MiuixTheme.colorScheme.onSurfaceVariantSummary
                    else -> s.toolDone to sachetSuccess()
                }
                StatusDot(color, status)
                Icon(
                    if (open) KeyboardArrowUpIcon else KeyboardArrowDownIcon,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (open) cards.forEach { ToolCard(it) }
        }
    }
}
