package id.codingsachet.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Extra vector icons (paths from Google's Material Design Icons, Apache-2.0).
 * Kept inline so the app does not need the (large) extended-icons artifact.
 */

private var folderIcon: ImageVector? = null
val FolderIcon: ImageVector
    get() = folderIcon ?: ImageVector.Builder(
        name = "ApxFolder",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(10.0f, 4.0f)
            lineTo(4.0f, 4.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            lineToRelative(0.0f, 12.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            lineToRelative(16.0f, 0.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineToRelative(0.0f, -10.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            lineToRelative(-8.0f, 0.0f)
            lineToRelative(-2.0f, -2.0f)
            close()
        }
    }.build().also { folderIcon = it }

private var textFileIcon: ImageVector? = null
val TextFileIcon: ImageVector
    get() = textFileIcon ?: ImageVector.Builder(
        name = "ApxTextFile",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(14.0f, 2.0f)
            lineTo(6.0f, 2.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            lineToRelative(0.0f, 16.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            lineToRelative(12.0f, 0.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineToRelative(0.0f, -12.0f)
            lineToRelative(-6.0f, -6.0f)
            close()
            moveTo(16.0f, 18.0f)
            lineTo(8.0f, 18.0f)
            lineToRelative(0.0f, -2.0f)
            lineToRelative(8.0f, 0.0f)
            lineToRelative(0.0f, 2.0f)
            close()
            moveTo(16.0f, 14.0f)
            lineTo(8.0f, 14.0f)
            lineToRelative(0.0f, -2.0f)
            lineToRelative(8.0f, 0.0f)
            lineToRelative(0.0f, 2.0f)
            close()
            moveTo(13.0f, 9.0f)
            lineToRelative(0.0f, -5.5f)
            lineToRelative(5.5f, 5.5f)
            lineToRelative(-5.5f, 0.0f)
            close()
        }
    }.build().also { textFileIcon = it }

private var stopIcon: ImageVector? = null
val StopIcon: ImageVector
    get() = stopIcon ?: ImageVector.Builder(
        name = "SachetStop",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6.0f, 6.0f)
            lineToRelative(12.0f, 0.0f)
            lineToRelative(0.0f, 12.0f)
            lineToRelative(-12.0f, 0.0f)
            close()
        }
    }.build().also { stopIcon = it }

private var storageIcon: ImageVector? = null
val StorageIcon: ImageVector
    get() = storageIcon ?: ImageVector.Builder(
        name = "SachetStorage",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(2.0f, 20.0f)
            lineToRelative(20.0f, 0.0f)
            lineToRelative(0.0f, -4.0f)
            lineToRelative(-20.0f, 0.0f)
            lineToRelative(0.0f, 4.0f)
            close()
            moveTo(4.0f, 17.0f)
            lineToRelative(2.0f, 0.0f)
            lineToRelative(0.0f, 2.0f)
            lineToRelative(-2.0f, 0.0f)
            lineToRelative(0.0f, -2.0f)
            close()
            moveTo(2.0f, 4.0f)
            lineToRelative(0.0f, 4.0f)
            lineToRelative(20.0f, 0.0f)
            lineToRelative(0.0f, -4.0f)
            lineToRelative(-20.0f, 0.0f)
            close()
            moveTo(6.0f, 7.0f)
            lineToRelative(-2.0f, 0.0f)
            lineToRelative(0.0f, -2.0f)
            lineToRelative(2.0f, 0.0f)
            lineToRelative(0.0f, 2.0f)
            close()
            moveTo(2.0f, 14.0f)
            lineToRelative(20.0f, 0.0f)
            lineToRelative(0.0f, -4.0f)
            lineToRelative(-20.0f, 0.0f)
            lineToRelative(0.0f, 4.0f)
            close()
            moveTo(4.0f, 11.0f)
            lineToRelative(2.0f, 0.0f)
            lineToRelative(0.0f, 2.0f)
            lineToRelative(-2.0f, 0.0f)
            lineToRelative(0.0f, -2.0f)
            close()
        }
    }.build().also { storageIcon = it }

private var createNewFolderIcon: ImageVector? = null
val CreateNewFolderIcon: ImageVector
    get() = createNewFolderIcon ?: ImageVector.Builder(
        name = "SachetNewFolder",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20.0f, 6.0f)
            lineToRelative(-8.0f, 0.0f)
            lineToRelative(-2.0f, -2.0f)
            lineTo(4.0f, 4.0f)
            curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
            lineTo(2.0f, 18.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            lineToRelative(16.0f, 0.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(22.0f, 8.0f)
            curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(19.0f, 14.0f)
            lineToRelative(-3.0f, 0.0f)
            lineToRelative(0.0f, 3.0f)
            lineToRelative(-2.0f, 0.0f)
            lineToRelative(0.0f, -3.0f)
            lineToRelative(-3.0f, 0.0f)
            lineToRelative(0.0f, -2.0f)
            lineToRelative(3.0f, 0.0f)
            lineToRelative(0.0f, -3.0f)
            lineToRelative(2.0f, 0.0f)
            lineToRelative(0.0f, 3.0f)
            lineToRelative(3.0f, 0.0f)
            lineToRelative(0.0f, 2.0f)
            close()
        }
    }.build().also { createNewFolderIcon = it }
