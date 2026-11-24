package id.codingsachet.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.codingsachet.mobile.ui.CreateNewFolderIcon
import id.codingsachet.mobile.ui.FolderIcon
import id.codingsachet.mobile.ui.LocalStrings

/**
 * T-ML65 #2: the FIRST screen after onboarding — a homepage with two
 * options instead of dropping the user straight into the last chat.
 * "Proyek baru" starts the new-chat flow (project name dialog, then an
 * empty chat); "Proyek berjalan" enters the chat with the left-side
 * drawer already open over the project/session list.
 */
@Composable
fun HomeScreen(
    onNewProject: () -> Unit,
    onOngoingProject: () -> Unit,
) {
    val s = LocalStrings.current
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Coding Sachet", style = MiuixTheme.textStyles.title1)
        Text(
            s.appTagline,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(36.dp))
        HomeOption(
            icon = CreateNewFolderIcon,
            title = s.newProjectTitle,
            hint = s.homeNewProjectHint,
            onClick = onNewProject,
        )
        Spacer(Modifier.height(16.dp))
        HomeOption(
            icon = FolderIcon,
            title = s.homeOngoingProject,
            hint = s.homeOngoingProjectHint,
            onClick = onOngoingProject,
        )
    }
}

/** One big option card (T-ML65 #2). The padding lives INSIDE the Surface
 *  (lesson-T43) so the ripple/clip never crops the text. */
@Composable
private fun HomeOption(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    Surface(
        // shape default Surface miuix sudah squircle khas HyperOS (MIGRATION.md §3.1)
        color = MiuixTheme.colorScheme.surfaceContainer, // pengganti surfaceVariant M3
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(20.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = title,
                tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MiuixTheme.textStyles.title2)
                Text(
                    hint,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
