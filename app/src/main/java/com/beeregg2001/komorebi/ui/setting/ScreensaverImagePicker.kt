@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.setting

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SelectableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.components.DialogActionButton
import com.beeregg2001.komorebi.ui.components.DialogActionRole
import com.beeregg2001.komorebi.ui.components.DialogBackdropScrim
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal enum class ScreensaverPickerTab { ALBUMS, STORAGE }

private sealed interface PendingGridFocusTarget {
    data object None : PendingGridFocusTarget
    data object FirstItemOrTab : PendingGridFocusTarget
    data object FirstItemOrBack : PendingGridFocusTarget
    data class RestoreKey(val key: String) : PendingGridFocusTarget
}

@Composable
internal fun ScreensaverImagePicker(
    onDismiss: () -> Unit,
    onImport: suspend (List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    val hasSystemPicker = remember(context) { ScreensaverImageFiles.hasDocumentPicker(context) }
    ScreensaverImageBrowser(
        onDismiss = onDismiss,
        onImport = onImport,
        initialTab = ScreensaverPickerTab.ALBUMS,
        loadAlbums = { withContext(Dispatchers.IO) { ScreensaverImageFiles.albums(context) } },
        loadAlbumImages = { bucketId -> withContext(Dispatchers.IO) { ScreensaverImageFiles.albumImages(context, bucketId) } },
        loadStorageEntries = { directory ->
            withContext(Dispatchers.IO) {
                if (directory != null) ScreensaverImageFiles.listDirectory(directory)
                else ScreensaverImageFiles.roots(context)
            }
        },
        loadFolderImages = { directory -> ScreensaverImageFiles.folderImages(directory) },
        requireReadPermission = true,
        hasSystemPicker = hasSystemPicker,
    )
}

@Composable
internal fun ScreensaverImageBrowser(
    onDismiss: () -> Unit,
    onImport: suspend (List<Uri>) -> Unit,
    initialTab: ScreensaverPickerTab = ScreensaverPickerTab.ALBUMS,
    loadAlbums: suspend () -> List<ScreensaverFileEntry> = { emptyList() },
    loadAlbumImages: suspend (String) -> List<ScreensaverFileEntry> = { emptyList() },
    loadStorageEntries: suspend (File?) -> List<ScreensaverFileEntry> = { emptyList() },
    loadFolderImages: suspend (File) -> List<Uri> = { ScreensaverImageFiles.folderImages(it) },
    requireReadPermission: Boolean = true,
    hasSystemPicker: Boolean = false,
    onOpenSystemPicker: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(initialTab) }
    var activeAlbum by remember { mutableStateOf<ScreensaverFileEntry?>(null) }
    var path by remember { mutableStateOf<List<File>>(emptyList()) }
    var focusHistory by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingFocusTarget by remember { mutableStateOf<PendingGridFocusTarget>(PendingGridFocusTarget.FirstItemOrTab) }

    var refresh by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<ScreensaverFileEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }

    var permissionGranted by remember {
        mutableStateOf(!requireReadPermission || ScreensaverImageFiles.hasAccess(context))
    }
    var isPartialAccess by remember {
        mutableStateOf(
            requireReadPermission &&
                Build.VERSION.SDK_INT >= 34 &&
                permissionGranted &&
                !ScreensaverImageFiles.hasFullAccess(context),
        )
    }
    var permissionPending by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(permissionGranted) }
    var importing by remember { mutableStateOf(false) }
    var selectingFolder by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var returnToImport by remember { mutableStateOf(false) }

    val gridState = rememberLazyGridState()
    val gridItemRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val albumsTabFocus = remember { FocusRequester() }
    val storageTabFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val permissionFocus = remember { FocusRequester() }
    val importFocus = remember { FocusRequester() }

    val refreshPermissions = {
        val granted = !requireReadPermission || ScreensaverImageFiles.hasAccess(context)
        permissionGranted = granted
        isPartialAccess = requireReadPermission &&
            Build.VERSION.SDK_INT >= 34 &&
            granted &&
            !ScreensaverImageFiles.hasFullAccess(context)
    }

    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionPending = false
        refreshPermissions()
        if (permissionGranted) {
            pendingFocusTarget = PendingGridFocusTarget.FirstItemOrTab
        }
        refresh++
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refreshPermissions()
        if (permissionGranted) {
            pendingFocusTarget = PendingGridFocusTarget.FirstItemOrTab
        }
        refresh++
    }
    val requestAccess = {
        permissionPending = true
        accessLauncher.launch(ScreensaverImageFiles.permissions())
    }

    val runImport: (List<Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty() && !importing && !selectingFolder) {
            importing = true
            actionError = null
            scope.launch {
                try {
                    onImport(uris)
                    importing = false
                    onDismiss()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    importing = false
                    returnToImport = true
                    actionError = "画像を保存できません: ${failure.message ?: "別の画像を選んで再試行してください。"}"
                } finally {
                    importing = false
                }
            }
        }
    }

    val systemPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            runImport(uris)
        }
    }

    LaunchedEffect(importing, returnToImport) {
        if (!importing && returnToImport) {
            withFrameNanos { }
            importFocus.safeRequestFocus("ScreensaverBrowser_ImportFailure")
            returnToImport = false
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) {
            requestAccess()
            withFrameNanos { }
            permissionFocus.safeRequestFocus("ScreensaverBrowser_InitialPermission")
        }
    }

    LaunchedEffect(tab, activeAlbum, path, refresh, permissionGranted) {
        if (!permissionGranted) {
            entries = emptyList()
            loadError = null
            loading = false
            return@LaunchedEffect
        }
        loading = true
        entries = emptyList()
        loadError = null
        try {
            entries = when (tab) {
                ScreensaverPickerTab.ALBUMS -> {
                    val album = activeAlbum
                    if (album?.bucketId != null) {
                        loadAlbumImages(album.bucketId)
                    } else {
                        loadAlbums()
                    }
                }
                ScreensaverPickerTab.STORAGE -> loadStorageEntries(path.lastOrNull())
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            loadError = if (failure is SecurityException) {
                "画像へのアクセスが許可されていません。アクセスを許可してください。"
            } else {
                failure.message ?: "画像一覧を読み取れません。再試行してください。"
            }
        } finally {
            loading = false
        }
    }

    LaunchedEffect(loading, entries, permissionGranted, pendingFocusTarget) {
        if (!permissionGranted || loading) return@LaunchedEffect
        val target = pendingFocusTarget
        if (target == PendingGridFocusTarget.None) return@LaunchedEffect
        pendingFocusTarget = PendingGridFocusTarget.None

        if (entries.isNotEmpty()) {
            val targetIndex = when (target) {
                is PendingGridFocusTarget.RestoreKey ->
                    entries.indexOfFirst { it.key == target.key }.coerceAtLeast(0)
                PendingGridFocusTarget.FirstItemOrTab,
                PendingGridFocusTarget.FirstItemOrBack,
                PendingGridFocusTarget.None -> 0
            }
            gridState.scrollToItem(targetIndex)
            withFrameNanos { }
            val targetEntry = entries.getOrNull(targetIndex)
            if (targetEntry != null) {
                gridItemRequesters.getOrPut(targetEntry.key) { FocusRequester() }
                    .safeRequestFocus("ScreensaverBrowser_GridFocus")
            }
        } else {
            withFrameNanos { }
            when (target) {
                PendingGridFocusTarget.FirstItemOrBack,
                is PendingGridFocusTarget.RestoreKey ->
                    backFocus.safeRequestFocus("ScreensaverBrowser_EmptyBack")
                PendingGridFocusTarget.FirstItemOrTab -> {
                    val tabFocus = if (tab == ScreensaverPickerTab.ALBUMS) albumsTabFocus else storageTabFocus
                    tabFocus.safeRequestFocus("ScreensaverBrowser_EmptyTab")
                }
                PendingGridFocusTarget.None -> Unit
            }
        }
    }

    val goBack = {
        if (!importing && !selectingFolder) {
            when {
                tab == ScreensaverPickerTab.ALBUMS && activeAlbum != null -> {
                    val restoreKey = focusHistory.lastOrNull()
                    focusHistory = focusHistory.dropLast(1)
                    pendingFocusTarget = restoreKey?.let { PendingGridFocusTarget.RestoreKey(it) }
                        ?: PendingGridFocusTarget.FirstItemOrTab
                    activeAlbum = null
                }
                tab == ScreensaverPickerTab.STORAGE && path.isNotEmpty() -> {
                    val restoreKey = focusHistory.lastOrNull()
                    focusHistory = focusHistory.dropLast(1)
                    pendingFocusTarget = restoreKey?.let { PendingGridFocusTarget.RestoreKey(it) }
                        ?: PendingGridFocusTarget.FirstItemOrTab
                    path = path.dropLast(1)
                }
                else -> onDismiss()
            }
        }
    }

    val switchTab: (ScreensaverPickerTab) -> Unit = { newTab ->
        if (!importing && !selectingFolder && tab != newTab) {
            tab = newTab
            activeAlbum = null
            path = emptyList()
            focusHistory = emptyList()
            actionError = null
            pendingFocusTarget = PendingGridFocusTarget.None
        }
    }

    BackHandler { goBack() }

    val configuration = LocalConfiguration.current
    val dialogWidth = minOf(1040, configuration.screenWidthDp - 48).coerceAtLeast(320).dp
    val dialogHeight = minOf(640, configuration.screenHeightDp - 40).coerceAtLeast(320).dp
    val gridColumns = if (dialogWidth >= 820.dp) 4 else 3
    val currentImageUris = remember(entries) { entries.mapNotNull { it.uri } }
    val hasUnselectedInView = remember(currentImageUris, selected) {
        currentImageUris.any { it !in selected }
    }
    val breadcrumb = when (tab) {
        ScreensaverPickerTab.ALBUMS -> activeAlbum?.let { "アルバム / ${it.name}" } ?: "アルバム一覧"
        ScreensaverPickerTab.STORAGE -> path.lastOrNull()?.absolutePath ?: "ストレージ一覧"
    }

    Dialog(
        onDismissRequest = { goBack() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DialogBackdropScrim)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            contentAlignment = Alignment.Center,
        ) {
            val shellShape = RoundedCornerShape(18.dp)
            Surface(
                modifier = Modifier
                    .width(dialogWidth)
                    .height(dialogHeight)
                    .border(1.dp, colors.borderSubtle, shellShape)
                    .testTag("screensaver-image-browser"),
                shape = shellShape,
                colors = SurfaceDefaults.colors(
                    containerColor = colors.overlay,
                    contentColor = colors.textPrimary,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Header bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "スクリーンセーバー画像を選択",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PickerTabPill(
                                label = "アルバム",
                                selected = tab == ScreensaverPickerTab.ALBUMS,
                                enabled = !importing && !selectingFolder,
                                modifier = Modifier.focusRequester(albumsTabFocus),
                                onClick = { switchTab(ScreensaverPickerTab.ALBUMS) },
                            )
                            PickerTabPill(
                                label = "ストレージ",
                                selected = tab == ScreensaverPickerTab.STORAGE,
                                enabled = !importing && !selectingFolder,
                                modifier = Modifier.focusRequester(storageTabFocus),
                                onClick = { switchTab(ScreensaverPickerTab.STORAGE) },
                            )
                        }
                        DialogActionButton(
                            role = DialogActionRole.Secondary,
                            onClick = goBack,
                            modifier = Modifier.focusRequester(backFocus),
                            enabled = !importing && !selectingFolder,
                        ) { Text("戻る") }
                        DialogActionButton(
                            role = DialogActionRole.Secondary,
                            onClick = {
                                actionError = null
                                refresh++
                            },
                            enabled = permissionGranted && !importing && !selectingFolder && !loading,
                        ) { Text("再読み込み") }
                        if (isPartialAccess) {
                            DialogActionButton(
                                role = DialogActionRole.Secondary,
                                onClick = requestAccess,
                                enabled = !permissionPending && !importing && !selectingFolder,
                            ) { Text("許可する画像を追加") }
                        }
                        if (hasSystemPicker) {
                            DialogActionButton(
                                role = DialogActionRole.Secondary,
                                onClick = {
                                    if (onOpenSystemPicker != null) {
                                        onOpenSystemPicker()
                                    } else {
                                        systemPickerLauncher.launch(arrayOf("image/*"))
                                    }
                                },
                                enabled = !importing && !selectingFolder,
                            ) { Text("システム選択") }
                        }
                    }

                    // Subheader / Batch toolbar (fixed height for stable geometry)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = breadcrumb,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (currentImageUris.isNotEmpty()) {
                            DialogActionButton(
                                role = DialogActionRole.Secondary,
                                onClick = {
                                    selected = selected + currentImageUris
                                    actionError = null
                                },
                                enabled = permissionGranted && hasUnselectedInView && !loading && !importing && !selectingFolder,
                            ) { Text("すべて選択") }
                        }
                        if (tab == ScreensaverPickerTab.STORAGE && path.isNotEmpty()) {
                            DialogActionButton(
                                role = DialogActionRole.Secondary,
                                onClick = {
                                    val directory = path.lastOrNull() ?: return@DialogActionButton
                                    selectingFolder = true
                                    actionError = null
                                    scope.launch {
                                        try {
                                            val images = loadFolderImages(directory)
                                            selected = selected + images
                                            if (images.isEmpty()) {
                                                actionError = "このフォルダーには画像がありません。別のフォルダーを選んでください。"
                                            }
                                        } catch (failure: CancellationException) {
                                            throw failure
                                        } catch (failure: Exception) {
                                            actionError = "フォルダーを選択できません: ${failure.message ?: "読み取れません。別のフォルダーを選んでください。"}"
                                        } finally {
                                            selectingFolder = false
                                        }
                                    }
                                },
                                enabled = permissionGranted && !loading && !importing && !selectingFolder,
                            ) { Text("このフォルダーを選択") }
                        }
                        DialogActionButton(
                            role = DialogActionRole.Secondary,
                            onClick = {
                                selected = emptySet()
                                actionError = null
                            },
                            enabled = selected.isNotEmpty() && !importing && !selectingFolder,
                        ) { Text("選択解除") }
                    }

                    // Main content slot (fixed weight(1f) so header & footer never shift)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            !permissionGranted -> {
                                Column(
                                    modifier = Modifier
                                        .width(560.dp)
                                        .background(colors.surface1, RoundedCornerShape(14.dp))
                                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(14.dp))
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Text(
                                        text = if (permissionPending) {
                                            "画像へのアクセスを確認しています…"
                                        } else {
                                            "端末やUSB内の画像を表示するには、画像へのアクセス権限が必要です。"
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = colors.textPrimary,
                                        textAlign = TextAlign.Center,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        DialogActionButton(
                                            role = DialogActionRole.Primary,
                                            onClick = requestAccess,
                                            modifier = Modifier.focusRequester(permissionFocus),
                                            enabled = !permissionPending,
                                        ) { Text("アクセスを許可") }
                                        DialogActionButton(
                                            role = DialogActionRole.Secondary,
                                            onClick = {
                                                try {
                                                    settingsLauncher.launch(
                                                        Intent(
                                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                            Uri.parse("package:${context.packageName}"),
                                                        ),
                                                    )
                                                } catch (_: android.content.ActivityNotFoundException) {
                                                    actionError = "アプリ設定を開けません。テレビの設定から画像へのアクセスを許可してください。"
                                                }
                                            },
                                            enabled = !permissionPending,
                                        ) { Text("アプリ設定") }
                                    }
                                }
                            }
                            loading -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    CircularProgressIndicator(color = colors.accent)
                                    Text(
                                        text = "画像を読み込んでいます…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.textSecondary,
                                    )
                                }
                            }
                            loadError != null && entries.isEmpty() -> {
                                Column(
                                    modifier = Modifier.padding(horizontal = 32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Text(
                                        text = loadError.orEmpty(),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = colors.danger,
                                        textAlign = TextAlign.Center,
                                    )
                                    DialogActionButton(
                                        role = DialogActionRole.Primary,
                                        onClick = {
                                            pendingFocusTarget = PendingGridFocusTarget.FirstItemOrTab
                                            refresh++
                                        },
                                    ) { Text("再試行") }
                                }
                            }
                            entries.isEmpty() -> {
                                val emptyMessage = if (tab == ScreensaverPickerTab.ALBUMS && activeAlbum == null) {
                                    "アルバムに画像が見つかりません。\nUSBなどの画像は「ストレージ」タブから探すか、設定の「スマホから画像を管理」から追加できます。"
                                } else {
                                    "画像がありません。別の場所を選ぶか、設定の「スマホから画像を管理」から追加してください。"
                                }
                                Text(
                                    text = emptyMessage,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.textSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 32.dp),
                                )
                            }
                            else -> {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(gridColumns),
                                    state = gridState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("screensaver-picker-grid"),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    itemsIndexed(entries, key = { _, entry -> entry.key }) { _, entry ->
                                        val itemRequester = remember(entry.key) {
                                            gridItemRequesters.getOrPut(entry.key) { FocusRequester() }
                                        }
                                        when {
                                            entry.isAlbum -> AlbumGridCard(
                                                entry = entry,
                                                enabled = !importing && !selectingFolder,
                                                modifier = Modifier.focusRequester(itemRequester),
                                                onClick = {
                                                    focusHistory = focusHistory + entry.key
                                                    pendingFocusTarget = PendingGridFocusTarget.FirstItemOrBack
                                                    activeAlbum = entry
                                                },
                                            )
                                            entry.isFolder -> FolderGridCard(
                                                entry = entry,
                                                enabled = !importing && !selectingFolder,
                                                modifier = Modifier.focusRequester(itemRequester),
                                                onClick = {
                                                    val dir = entry.directory ?: return@FolderGridCard
                                                    focusHistory = focusHistory + entry.key
                                                    pendingFocusTarget = PendingGridFocusTarget.FirstItemOrBack
                                                    path = path + dir
                                                },
                                            )
                                            else -> ImageGridCard(
                                                entry = entry,
                                                selected = entry.uri in selected,
                                                enabled = !importing && !selectingFolder,
                                                modifier = Modifier.focusRequester(itemRequester),
                                                onClick = {
                                                    entry.uri?.let { uri ->
                                                        selected = if (uri in selected) selected - uri else selected + uri
                                                        actionError = null
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Footer bar (fixed height 56.dp for stable geometry)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val footerText = when {
                            importing -> "画像を保存しています…"
                            selectingFolder -> "フォルダーの画像を選択しています…"
                            actionError != null -> actionError!!
                            else -> "${selected.size} 枚選択中"
                        }
                        val footerColor = if (!importing && !selectingFolder && actionError != null) {
                            colors.danger
                        } else {
                            colors.textSecondary
                        }
                        Text(
                            text = footerText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = footerColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        DialogActionButton(
                            role = DialogActionRole.Secondary,
                            onClick = onDismiss,
                            enabled = !importing && !selectingFolder,
                        ) { Text("キャンセル") }
                        DialogActionButton(
                            role = DialogActionRole.Primary,
                            onClick = { runImport(selected.toList()) },
                            modifier = Modifier.focusRequester(importFocus),
                            enabled = permissionGranted && selected.isNotEmpty() && !importing && !selectingFolder,
                        ) { Text("追加 (${selected.size})") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerTabPill(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = SelectableSurfaceDefaults.shape(shape = shape),
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1f),
        colors = SelectableSurfaceDefaults.colors(
            containerColor = colors.surface1,
            contentColor = colors.textSecondary,
            selectedContainerColor = colors.accentSoft,
            selectedContentColor = colors.textPrimary,
            focusedContainerColor = colors.surface2,
            focusedContentColor = colors.textPrimary,
            focusedSelectedContainerColor = colors.accentSoft,
            focusedSelectedContentColor = colors.textPrimary,
        ),
        border = SelectableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape),
            selectedBorder = Border(BorderStroke(1.5.dp, colors.accent.copy(alpha = 0.6f)), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
            focusedSelectedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun AlbumGridCard(
    entry: ScreensaverFileEntry,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    val request = remember(entry.coverUri, context) {
        entry.coverUri?.let { uri ->
            ImageRequest.Builder(context)
                .data(uri)
                .size(480, 270)
                .precision(Precision.INEXACT)
                .allowHardware(true)
                .build()
        }
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .testTag("screensaver-album-card"),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface1,
            contentColor = colors.textPrimary,
            focusedContainerColor = colors.surface2,
            focusedContentColor = colors.textPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent), shape = shape),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        ),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${entry.itemCount ?: 0} 枚",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderGridCard(
    entry: ScreensaverFileEntry,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .testTag("screensaver-folder-card"),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface1,
            contentColor = colors.textPrimary,
            focusedContainerColor = colors.surface2,
            focusedContentColor = colors.textPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent), shape = shape),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "フォルダー",
                tint = colors.accent,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.name,
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "フォルダー",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ImageGridCard(
    entry: ScreensaverFileEntry,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    val request = remember(entry.uri, context) {
        entry.uri?.let { uri ->
            ImageRequest.Builder(context)
                .data(uri)
                .size(480, 270)
                .precision(Precision.INEXACT)
                .allowHardware(true)
                .build()
        }
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .testTag("screensaver-image-card"),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface1,
            contentColor = colors.textPrimary,
            focusedContainerColor = colors.surface2,
            focusedContentColor = colors.textPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                BorderStroke(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) colors.accent else colors.borderSubtle,
                ),
                shape = shape,
            ),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent), shape = shape),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (request != null) {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.accent else Color.Black.copy(alpha = 0.5f))
                    .then(
                        if (!selected) {
                            Modifier
                                .border(1.5.dp, Color.White, CircleShape)
                                .semantics { contentDescription = "未選択" }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "選択済み",
                        tint = colors.onAccent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
