package com.beeregg2001.komorebi.ui.setting

import android.net.Uri
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.safeRequestFocus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun ScreensaverImagePicker(
    onDismiss: () -> Unit,
    onImport: suspend (List<Uri>) -> Unit,
) {
    val context = LocalContext.current
    ScreensaverImageBrowser(
        loadEntries = { directory, gallery -> withContext(Dispatchers.IO) {
            when {
                directory != null -> ScreensaverImageFiles.listDirectory(directory)
                gallery -> ScreensaverImageFiles.images(context)
                else -> ScreensaverImageFiles.roots(context)
            }
        } },
        onDismiss = onDismiss,
        onImport = onImport,
    )
}

@Composable
internal fun ScreensaverImageBrowser(
    loadEntries: suspend (File?, Boolean) -> List<ScreensaverFileEntry>,
    onDismiss: () -> Unit,
    onImport: suspend (List<Uri>) -> Unit,
    requireReadPermission: Boolean = true,
    loadFolderImages: suspend (File) -> List<Uri> = { ScreensaverImageFiles.folderImages(it) },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf<List<File>>(emptyList()) }
    var gallery by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<ScreensaverFileEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var permissionGranted by remember { mutableStateOf(!requireReadPermission || ScreensaverImageFiles.hasAccess(context)) }
    var permissionPending by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(permissionGranted) }
    var importing by remember { mutableStateOf(false) }
    var selectingFolder by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val navigationFocus = remember { FocusRequester() }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionPending = false
        permissionGranted = !requireReadPermission || ScreensaverImageFiles.hasAccess(context)
        refresh++
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionGranted = !requireReadPermission || ScreensaverImageFiles.hasAccess(context)
        refresh++
    }
    val access = {
        permissionPending = true
        accessLauncher.launch(ScreensaverImageFiles.permissions())
    }
    val importFocus = remember { FocusRequester() }
    var returnToImport by remember { mutableStateOf(false) }
    LaunchedEffect(importing, returnToImport) {
        if (!importing && returnToImport) {
            importFocus.safeRequestFocus("ScreensaverBrowser_ImportFailure")
            returnToImport = false
        }
    }
    LaunchedEffect(path, gallery, refresh, permissionGranted) {
        if (!permissionGranted) { entries = emptyList(); loading = false; return@LaunchedEffect }
        loading = true
        entries = emptyList()
        error = null
        try {
            entries = loadEntries(path.lastOrNull(), gallery)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            error = if (failure is SecurityException) "画像へのアクセスが許可されていません。アクセスを許可してください。"
                else failure.message ?: "画像一覧を読み取れません。再試行してください。"
        } finally {
            loading = false
        }
        listState.scrollToItem(0)
    }
    LaunchedEffect(Unit) {
        if (!permissionGranted) access()
        navigationFocus.safeRequestFocus("ScreensaverBrowser_Initial")
    }
    val goBack = {
        if (!importing && !selectingFolder) {
            when {
                path.isNotEmpty() -> { navigationFocus.safeRequestFocus("ScreensaverBrowser_Back"); path = path.dropLast(1) }
                gallery -> gallery = false
                else -> onDismiss()
            }
        }
    }
    BackHandler { goBack() }
    val configuration = LocalConfiguration.current
    val dialogWidth = minOf(720, configuration.screenWidthDp - 64).coerceAtLeast(280).dp
    val dialogHeight = minOf(470, configuration.screenHeightDp - 64).coerceAtLeast(280).dp
    val fixedScale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    Dialog(onDismissRequest = { goBack() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.width(dialogWidth).height(dialogHeight).testTag("screensaver-image-browser"),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("スクリーンセーバー画像", style = MaterialTheme.typography.headlineSmall)
                Text(path.lastOrNull()?.absolutePath ?: if (gallery) "画像一覧" else "ストレージ", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(goBack, Modifier.focusRequester(navigationFocus), scale = fixedScale, enabled = !importing && !selectingFolder) { Text("戻る") }
                    Button({ path = emptyList(); gallery = !gallery }, scale = fixedScale, enabled = permissionGranted && !importing && !selectingFolder) { Text(if (gallery) "ストレージ" else "画像一覧") }
                    Button({ refresh++ }, scale = fixedScale, enabled = permissionGranted && !importing && !selectingFolder && !loading) { Text("再読み込み") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp), modifier = Modifier.fillMaxSize()) {
                        items(entries, key = { it.key }) { entry ->
                            Button(
                                onClick = {
                                    if (entry.directory != null) { navigationFocus.safeRequestFocus("ScreensaverBrowser_Folder"); path = path + entry.directory; gallery = false }
                                    else entry.uri?.let { selected = if (it in selected) selected - it else selected + it }
                                },
                                enabled = !importing && !selectingFolder,
                                scale = fixedScale,
                                shape = ButtonDefaults.shape(shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("screensaver-file-row"),
                            ) {
                                if (entry.directory != null) {
                                    Icon(Icons.Default.Folder, contentDescription = "フォルダー", modifier = Modifier.size(28.dp))
                                } else {
                                    Icon(if (entry.uri in selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = if (entry.uri in selected) "選択済み" else "未選択", modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(10.dp))
                                    AsyncImage(entry.uri, null, Modifier.size(36.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(entry.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                // Status always has the same slot, including empty and failure states.
                Box(Modifier.fillMaxWidth().height(82.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when {
                        permissionPending -> "画像へのアクセスを確認しています…"
                        !permissionGranted -> "画像へのアクセスが許可されていません。アクセスを許可してください。"
                        importing -> "画像を保存しています…"
                        selectingFolder -> "フォルダーの画像を選択しています…"
                        error != null -> error!!
                        loading -> "読み込み中…"
                        entries.isEmpty() -> "画像がありません。別のフォルダーを選ぶか、アクセスを許可してください。"
                        else -> "${selected.size} 枚選択"
                    }, maxLines = 2)
                    if (!permissionGranted) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(access, scale = fixedScale, enabled = !permissionPending) { Text("アクセスを許可") }
                        Button({
                            try { settingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                            catch (_: android.content.ActivityNotFoundException) { error = "アプリ設定を開けません。テレビの設定から画像へのアクセスを許可してください。" }
                        }, scale = fixedScale, enabled = !permissionPending) { Text("アプリ設定") }
                    }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onDismiss, scale = fixedScale, enabled = !importing && !selectingFolder) { Text("キャンセル") }
                    Button({
                        val directory = path.lastOrNull() ?: return@Button
                        selectingFolder = true
                        error = null
                        scope.launch {
                            try {
                                val images = loadFolderImages(directory)
                                selected = selected + images
                                if (images.isEmpty()) error = "このフォルダーには画像がありません。別のフォルダーを選んでください。"
                            } catch (failure: CancellationException) { throw failure }
                            catch (failure: Exception) { error = "フォルダーを選択できません: ${failure.message ?: "読み取れません。別のフォルダーを選んでください。"}" }
                            finally { selectingFolder = false }
                        }
                    }, scale = fixedScale, enabled = permissionGranted && path.isNotEmpty() && !loading && !importing && !selectingFolder) { Text("このフォルダーを選択") }
                    Button({
                        importing = true
                        error = null
                        scope.launch {
                            try {
                                onImport(selected.toList())
                                importing = false
                                onDismiss()
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                importing = false
                                returnToImport = true
                                error = "画像を保存できません: ${failure.message ?: "別の画像を選んで再試行してください。"}"
                            } finally { importing = false }
                        }
                    }, modifier = Modifier.focusRequester(importFocus), scale = fixedScale, enabled = permissionGranted && selected.isNotEmpty() && !importing && !selectingFolder) { Text("追加 (${selected.size})") }
                }
            }
        }
    }
}
