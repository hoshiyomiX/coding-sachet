package id.aihubmix.mobile.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Lightweight message renderer: fenced code blocks in monospace cards,
 * inline `code`, **bold** and headings; anything fancier stays as plain text.
 */
@Composable
fun Markdownish(text: String) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textStyle = MaterialTheme.typography.bodyMedium

    Column {
        val blocks = splitFences(text)
        for (b in blocks) {
            if (b.second) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = codeBg,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        if (b.first.isNotBlank()) {
                            Text(
                                b.first.trimEnd(),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            } else if (b.first.isNotBlank()) {
                Text(inline(b.first, codeColor), style = textStyle)
            }
        }
    }
}

/** Split by ``` fences → (content, isCode) blocks. */
private fun splitFences(text: String): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    var i = 0
    var start = 0
    var inCode = false
    while (i < text.length) {
        if (text.startsWith("```", i)) {
            if (!inCode && i > start) out.add(text.substring(start, i) to false)
            if (inCode) out.add(text.substring(start, i) to true)
            inCode = !inCode
            // skip the fence and an optional language tag on the same line
            var j = i + 3
            if (!inCode) {
                // closing fence: continue right after it
            } else {
                while (j < text.length && text[j] != '\n') j++
            }
            i = if (j < text.length && text[j] == '\n') j + 1 else j
            start = i
        } else {
            i++
        }
    }
    if (start < text.length) out.add(text.substring(start) to inCode)
    return out
}

/** Inline `code` and **bold** → AnnotatedString. */
private fun inline(text: String, codeColor: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("`", i) -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(text[i]); i++
                }
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}
