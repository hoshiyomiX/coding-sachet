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
        name = "SachetFolder",
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
            lineTo_relative(-2.0f, -2.0f)
            close()
        }
    }.build().also { folderIcon = it }

private var textFileIcon: ImageVector? = null
val TextFileIcon: ImageVector
    get() = textFileIcon ?: ImageVector.Builder(
        name = "SachetTextFile",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(14.0f, 2.0f)
            lineTo(6.0f, 2.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            lineTo_relative(0.0f, 16.0f)
            curveTo_relative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            lineTo_relative(12.0f, 0.0f)
            curveTo_relative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo_relative(0.0f, -12.0f)
            lineTo_relative(-6.0f, -6.0f)
            close()
            moveTo(16.0f, 18.0f)
            lineTo(8.0f, 18.0f)
            lineTo_relative(0.0f, -2.0f)
            lineTo_relative(8.0f, 0.0f)
            lineTo_relative(0.0f, 2.0f)
            close()
            moveTo(16.0f, 14.0f)
            lineTo(8.0f, 14.0f)
            lineTo_relative(0.0f, -2.0f)
            lineTo_relative(8.0f, 0.0f)
            lineTo_relative(0.0f, 2.0f)
            close()
            moveTo(13.0f, 9.0f)
            lineTo_relative(0.0f, -5.5f)
            lineTo_relative(5.5f, 5.5f)
            lineTo_relative(-5.5f, 0.0f)
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
            lineTo_relative(12.0f, 0.0f)
            lineTo_relative(0.0f, 12.0f)
            lineTo_relative(-12.0f, 0.0f)
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
            lineTorelative(0.0f, -4.0f)
            lineTorelative(-20.0f, 0.0f)
            lineTorelative(0.0f, 4.0f)
            close()
            moveTo(4.0f, 17.0f)
            lineTorelative(2.0f, 0.0f)
            lineTorelative(0.0f, 2.0f)
            lineTorelative(-2.0f, 0.0f)
            lineTorelative(0.0f, -2.0f)
            close()
            moveTo(2.0f, 4.0f)
            lineTorelative(0.0f, 4.0f)
            lineTorelative(20.0f, 0.0f)
            lineTorelative(0.0f, -4.0f)
            lineTorelative(-20.0f, 0.0f)
            close()
            moveTo(6.0f, 7.0f)
            lineTorelative(-2.0f, 0.0f)
            lineTorelative(0.0f, -2.0f)
            lineTorelative(2.0f, 0.0f)
            lineTorelative(0.0f, 2.0f)
            close()
            moveTo(2.0f, 14.0f)
            lineTorelative(20.0f, 0.0f)
            lineTorelative(0.0f, -4.0f)
            lineTorelative(-20.0f, 0.0f)
            lineTorelative(0.0f, 4.0f)
            close()
            moveTo(4.0f, 11.0f)
            lineTorelative(2.0f, 0.0f)
            lineTorelative(0.0f, 2.0f)
            lineTorelative(-2.0f, 0.0f)
            lineTorelative(0.0f, -2.0f)
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
            lineTorelative(-8.0f, 0.0f)
            lineTorelative(-2.0f, -2.0f)
            lineTo(4.0f, 4.0f)
            curveTorelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
            lineTo(2.0f, 18.0f)
            curveTorelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            lineTorelative(16.0f, 0.0f)
            curveTorelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(22.0f, 8.0f)
            curveTorelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(19.0f, 14.0f)
            lineTorelative(-3.0f, 0.0f)
            lineTorelative(0.0f, 3.0f)
            lineTorelative(-2.0f, 0.0f)
            lineTorelative(0.0f, -3.0f)
            lineTorelative(-3.0f, 0.0f)
            lineTorelative(0.0f, -2.0f)
            lineTorelative(3.0f, 0.0f)
            lineTorelative(0.0f, -3.0f)
            lineTorelative(2.0f, 0.0f)
            lineTorelative(0.0f, 3.0f)
            lineTorelative(3.0f, 0.0f)
            lineTorelative(0.0f, 2.0f)
            close()
        }
    }.build().also { createNewFolderIcon = it }

// Material Design "content_copy" (Apache-2.0) — T-ML65 #1 message copy.
private var copyIcon: ImageVector? = null
val CopyIcon: ImageVector
    get() = copyIcon ?: ImageVector.Builder(
        name = "SachetCopy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16.0f, 1.0f)
            lineTo(4.0f, 1.0f)
            curveTorelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            lineTorelative(0.0f, 14.0f)
            lineTorelative(2.0f, 0.0f)
            lineTo(4.0f, 3.0f)
            lineTorelative(12.0f, 0.0f)
            lineTo(16.0f, 1.0f)
            close()
            moveTo(19.0f, 5.0f)
            lineTo(8.0f, 5.0f)
            curveTorelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
            lineTorelative(0.0f, 14.0f)
            curveTorelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            lineTorelative(11.0f, 0.0f)
            curveTorelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            lineTo(21.0f, 7.0f)
            curveTorelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
            close()
            moveTo(19.0f, 21.0f)
            lineTo(8.0f, 21.0f)
            lineTo(8.0f, 7.0f)
            lineTorelative(11.0f, 0.0f)
            lineTorelative(0.0f, 14.0f)
            close()
        }
    }.build().also { copyIcon = it }

// ---------------------------------------------------------------------------
// Icons below (MIGRATION.md §6): standard Material Design icon paths
// (Apache-2.0), inlined so the app no longer needs material-icons-core
// after the miuix migration. Same builder pattern as the icons above.

private var _addIcon: ImageVector? = null
val AddIcon: ImageVector
    get() = _addIcon ?: ImageVector.Builder(
        name = "SachetAdd",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19.0f, 13.0f)
            lineTo(13.0f, 13.0f)
            lineTo(13.0f, 19.0f)
            lineTo(11.0f, 19.0f)
            lineTo(11.0f, 13.0f)
            lineTo(5.0f, 13.0f)
            lineTo(5.0f, 11.0f)
            lineTo(11.0f, 11.0f)
            lineTo(11.0f, 5.0f)
            lineTo(13.0f, 5.0f)
            lineTo(13.0f, 11.0f)
            lineTo(19.0f, 11.0f)
            lineTo(19.0f, 13.0f)
            close()
        }
    }.build().also { _addIcon = it }

private var _arrowBackIcon: ImageVector? = null
val ArrowBackIcon: ImageVector
    get() = _arrowBackIcon ?: ImageVector.Builder(
        name = "SachetArrowBack",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20.0f, 11.0f)
            lineTo(7.83f, 11.0f)
            lineTo(13.42f, 5.41f)
            lineTo(12.0f, 4.0f)
            lineTo(4.0f, 12.0f)
            lineTo(12.0f, 20.0f)
            lineTo(13.41f, 18.59f)
            lineTo(7.83f, 13.0f)
            lineTo(20.0f, 13.0f)
            lineTo(20.0f, 11.0f)
            close()
        }
    }.build().also { _arrowBackIcon = it }

private var _arrowDropDownIcon: ImageVector? = null
val ArrowDropDownIcon: ImageVector
    get() = _arrowDropDownIcon ?: ImageVector.Builder(
        name = "SachetArrowDropDown",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7.0f, 10.0f)
            lineTo(12.0f, 15.0f)
            lineTo(17.0f, 10.0f)
            close()
        }
    }.build().also { _arrowDropDownIcon = it }

private var _checkIcon: ImageVector? = null
val CheckIcon: ImageVector
    get() = _checkIcon ?: ImageVector.Builder(
        name = "SachetCheck",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(9.0f, 16.17f)
            lineTo(4.83f, 12.0f)
            lineTo(3.41f, 13.41f)
            lineTo(9.0f, 19.0f)
            lineTo(21.0f, 7.0f)
            lineTo(19.59f, 5.59f)
            close()
        }
    }.build().also { _checkIcon = it }

private var _closeIcon: ImageVector? = null
val CloseIcon: ImageVector
    get() = _closeIcon ?: ImageVector.Builder(
        name = "SachetClose",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19.0f, 6.41f)
            lineTo(17.59f, 5.0f)
            lineTo(12.0f, 10.59f)
            lineTo(6.41f, 5.0f)
            lineTo(5.0f, 6.41f)
            lineTo(10.59f, 12.0f)
            lineTo(5.0f, 17.59f)
            lineTo(6.41f, 19.0f)
            lineTo(12.0f, 13.41f)
            lineTo(17.59f, 19.0f)
            lineTo(19.0f, 17.59f)
            lineTo(13.41f, 12.0f)
            close()
        }
    }.build().also { _closeIcon = it }

private var _deleteIcon: ImageVector? = null
val DeleteIcon: ImageVector
    get() = _deleteIcon ?: ImageVector.Builder(
        name = "SachetDelete",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6.0f, 19.0f)
            curveTo(6.0f, 20.1f, 6.9f, 21.0f, 8.0f, 21.0f)
            lineTo(16.0f, 21.0f)
            curveTo(17.1f, 21.0f, 18.0f, 20.1f, 18.0f, 19.0f)
            lineTo(18.0f, 7.0f)
            lineTo(6.0f, 7.0f)
            lineTo(6.0f, 19.0f)
            close()
            moveTo(19.0f, 4.0f)
            lineTo(15.5f, 4.0f)
            lineTo(14.5f, 3.0f)
            lineTo(9.5f, 3.0f)
            lineTo(8.5f, 4.0f)
            lineTo(5.0f, 4.0f)
            lineTo(5.0f, 6.0f)
            lineTo(19.0f, 6.0f)
            lineTo(19.0f, 4.0f)
            close()
        }
    }.build().also { _deleteIcon = it }

private var _editIcon: ImageVector? = null
val EditIcon: ImageVector
    get() = _editIcon ?: ImageVector.Builder(
        name = "SachetEdit",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3.0f, 17.25f)
            lineTo(3.0f, 21.0f)
            lineTo(6.75f, 21.0f)
            lineTo(17.81f, 9.94f)
            lineTo(14.059999999999999f, 6.1899999999999995f)
            lineTo(3.0f, 17.25f)
            close()
            moveTo(20.71f, 7.04f)
            curveTo(21.1f, 6.65f, 21.1f, 6.02f, 20.71f, 5.63f)
            lineTo(18.37f, 3.29f)
            curveTo(17.98f, 2.9f, 17.35f, 2.9f, 16.96f, 3.29f)
            lineTo(15.13f, 5.12f)
            lineTo(18.880000000000003f, 8.870000000000001f)
            lineTo(20.710000000000001f, 7.040000000000001f)
            close()
        }
    }.build().also { _editIcon = it }

private var _keyboardArrowDownIcon: ImageVector? = null
val KeyboardArrowDownIcon: ImageVector
    get() = _keyboardArrowDownIcon ?: ImageVector.Builder(
        name = "SachetKbdDown",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7.41f, 8.59f)
            lineTo(12.0f, 13.17f)
            lineTo(16.59f, 8.59f)
            lineTo(18.0f, 10.0f)
            lineTo(12.0f, 16.0f)
            lineTo(6.0f, 10.0f)
            lineTo(7.41f, 8.59f)
            close()
        }
    }.build().also { _keyboardArrowDownIcon = it }

private var _keyboardArrowRightIcon: ImageVector? = null
val KeyboardArrowRightIcon: ImageVector
    get() = _keyboardArrowRightIcon ?: ImageVector.Builder(
        name = "SachetKbdRight",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8.59f, 16.59f)
            lineTo(13.17f, 12.0f)
            lineTo(8.59f, 7.41f)
            lineTo(10.0f, 6.0f)
            lineTo(16.0f, 12.0f)
            lineTo(10.0f, 18.0f)
            lineTo(8.59f, 16.59f)
            close()
        }
    }.build().also { _keyboardArrowRightIcon = it }

private var _keyboardArrowUpIcon: ImageVector? = null
val KeyboardArrowUpIcon: ImageVector
    get() = _keyboardArrowUpIcon ?: ImageVector.Builder(
        name = "SachetKbdUp",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(7.41f, 15.41f)
            lineTo(12.0f, 10.83f)
            lineTo(16.59f, 15.41f)
            lineTo(18.0f, 14.0f)
            lineTo(12.0f, 8.0f)
            lineTo(6.0f, 14.0f)
            lineTo(7.41f, 15.41f)
            close()
        }
    }.build().also { _keyboardArrowUpIcon = it }

private var _menuIcon: ImageVector? = null
val MenuIcon: ImageVector
    get() = _menuIcon ?: ImageVector.Builder(
        name = "SachetMenu",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3.0f, 18.0f)
            lineTo(21.0f, 18.0f)
            lineTo(21.0f, 16.0f)
            lineTo(3.0f, 16.0f)
            lineTo(3.0f, 18.0f)
            close()
            moveTo(3.0f, 13.0f)
            lineTo(21.0f, 13.0f)
            lineTo(21.0f, 11.0f)
            lineTo(3.0f, 11.0f)
            lineTo(3.0f, 13.0f)
            close()
            moveTo(3.0f, 6.0f)
            lineTo(3.0f, 8.0f)
            lineTo(21.0f, 8.0f)
            lineTo(21.0f, 6.0f)
            lineTo(3.0f, 6.0f)
            close()
        }
    }.build().also { _menuIcon = it }

private var _refreshIcon: ImageVector? = null
val RefreshIcon: ImageVector
    get() = _refreshIcon ?: ImageVector.Builder(
        name = "SachetRefresh",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(17.65f, 6.35f)
            curveTo(16.2f, 4.9f, 14.21f, 4.0f, 12.0f, 4.0f)
            curveTo(7.58f, 4.0f, 4.01f, 7.58f, 4.01f, 12.0f)
            curveTo(4.01f, 16.42f, 7.58f, 20.0f, 12.0f, 20.0f)
            curveTo(15.73f, 20.0f, 18.84f, 17.45f, 19.73f, 14.0f)
            lineTo(17.65f, 14.0f)
            curveTo(16.83f, 16.33f, 14.61f, 18.0f, 11.999999999999998f, 18.0f)
            curveTo(8.689999999999998f, 18.0f, 5.999999999999998f, 15.31f, 5.999999999999998f, 12.0f)
            curveTo(5.999999999999998f, 8.69f, 8.689999999999998f, 6.0f, 11.999999999999998f, 6.0f)
            curveTo(13.659999999999998f, 6.0f, 15.139999999999999f, 6.6899999999999995f, 16.22f, 7.78f)
            lineTo(13.0f, 11.0f)
            lineTo(20.0f, 11.0f)
            lineTo(20.0f, 4.0f)
            lineTo(17.65f, 6.35f)
            close()
        }
    }.build().also { _refreshIcon = it }

private var _sendIcon: ImageVector? = null
val SendIcon: ImageVector
    get() = _sendIcon ?: ImageVector.Builder(
        name = "SachetSend",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(2.01f, 21.0f)
            lineTo(23.0f, 12.0f)
            lineTo(2.01f, 3.0f)
            lineTo(2.0f, 10.0f)
            lineTo(17.0f, 12.0f)
            lineTo(2.0f, 14.0f)
            close()
        }
    }.build().also { _sendIcon = it }

private var _settingsIcon: ImageVector? = null
val SettingsIcon: ImageVector
    get() = _settingsIcon ?: ImageVector.Builder(
        name = "SachetSettings",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19.14f, 12.94f)
            curveTo(19.18f, 12.639999999999999f, 19.2f, 12.33f, 19.2f, 12.0f)
            curveTo(19.2f, 11.68f, 19.18f, 11.36f, 19.13f, 11.06f)
            lineTo(21.16f, 9.48f)
            curveTo(21.34f, 9.34f, 21.39f, 9.07f, 21.28f, 8.870000000000001f)
            lineTo(19.36f, 5.550000000000001f)
            curveTo(19.24f, 5.330000000000001f, 18.99f, 5.260000000000001f, 18.77f, 5.330000000000001f)
            lineTo(16.38f, 6.290000000000001f)
            curveTo(15.879999999999999f, 5.910000000000001f, 15.35f, 5.590000000000001f, 14.759999999999998f, 5.350000000000001f)
            lineTo(14.4f, 2.81f)
            curveTo(14.360000000000001f, 2.5700000000000003f, 14.16f, 2.4f, 13.92f, 2.4f)
            lineTo(10.08f, 2.4f)
            curveTo(9.84f, 2.4f, 9.65f, 2.57f, 9.61f, 2.81f)
            lineTo(9.25f, 5.35f)
            curveTo(8.66f, 5.59f, 8.12f, 5.92f, 7.63f, 6.29f)
            lineTo(5.24f, 5.33f)
            curveTo(5.0200000000000005f, 5.25f, 4.7700000000000005f, 5.33f, 4.65f, 5.55f)
            lineTo(2.74f, 8.87f)
            curveTo(2.62f, 9.08f, 2.66f, 9.34f, 2.86f, 9.48f)
            lineTo(4.89f, 11.06f)
            curveTo(4.84f, 11.36f, 4.8f, 11.69f, 4.8f, 12.0f)
            curveTo(4.8f, 12.31f, 4.819999999999999f, 12.64f, 4.87f, 12.94f)
            lineTo(2.8400000000000003f, 14.52f)
            curveTo(2.66f, 14.66f, 2.6100000000000003f, 14.93f, 2.72f, 15.129999999999999f)
            lineTo(4.640000000000001f, 18.45f)
            curveTo(4.760000000000001f, 18.669999999999998f, 5.010000000000001f, 18.74f, 5.23f, 18.669999999999998f)
            lineTo(7.620000000000001f, 17.709999999999997f)
            curveTo(8.120000000000001f, 18.089999999999996f, 8.65f, 18.409999999999997f, 9.240000000000002f, 18.65f)
            lineTo(9.600000000000001f, 21.189999999999998f)
            curveTo(9.650000000000002f, 21.429999999999996f, 9.840000000000002f, 21.599999999999998f, 10.080000000000002f, 21.599999999999998f)
            lineTo(13.920000000000002f, 21.599999999999998f)
            curveTo(14.160000000000002f, 21.599999999999998f, 14.360000000000001f, 21.429999999999996f, 14.390000000000002f, 21.189999999999998f)
            lineTo(14.750000000000002f, 18.65f)
            curveTo(15.340000000000002f, 18.41f, 15.880000000000003f, 18.09f, 16.37f, 17.709999999999997f)
            lineTo(18.76f, 18.669999999999998f)
            curveTo(18.98f, 18.749999999999996f, 19.23f, 18.669999999999998f, 19.35f, 18.45f)
            lineTo(21.270000000000003f, 15.129999999999999f)
            curveTo(21.390000000000004f, 14.909999999999998f, 21.340000000000003f, 14.659999999999998f, 21.150000000000002f, 14.52f)
            lineTo(19.14f, 12.94f)
            close()
            moveTo(12.0f, 15.6f)
            curveTo(10.02f, 15.6f, 8.4f, 13.98f, 8.4f, 12.0f)
            curveTo(8.4f, 10.02f, 10.02f, 8.4f, 12.0f, 8.4f)
            curveTo(13.98f, 8.4f, 15.6f, 10.02f, 15.6f, 12.0f)
            curveTo(15.6f, 13.98f, 13.98f, 15.6f, 12.0f, 15.6f)
            close()
        }
    }.build().also { _settingsIcon = it }
