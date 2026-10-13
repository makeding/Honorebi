package com.beeregg2001.komorebi.ui.setting

import android.net.Uri
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf<List<File>>(emptyList()) }
    var gallery by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<ScreensaverFileEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val navigationFocus = remember { FocusRequester() }
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        error = if (!ScreensaverImageFiles.hasAccess(context)) "画像へのアクセスが許可されていません。アクセスを許可してください。" else null
        refresh++
    }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val access = { accessLauncher.launch(ScreensaverImageFiles.permissions()) }
    val importFocus = remember { FocusRequester() }
    var returnToImport by remember { mutableStateOf(false) }
    LaunchedEffect(importing, returnToImport) {
        if (!importing && returnToImport) {
            importFocus.safeRequestFocus("ScreensaverBrowser_ImportFailure")
            returnToImport = false
        }
    }
    LaunchedEffect(path, gallery, refresh) {
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
    LaunchedEffect(Unit) { navigationFocus.safeRequestFocus("ScreensaverBrowser_Initial") }
    val goBack = {
        if (!importing) {
            when {
                path.isNotEmpty() -> { navigationFocus.safeRequestFocus("ScreensaverBrowser_Back"); path = path.dropLast(1) }
                gallery -> gallery = false
                else -> onDismiss()
            }
        }
    }
    BackHandler { goBack() }
    Dialog(onDismissRequest = { goBack() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
            Column(Modifier.width(820.dp).height(470.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("スクリーンセーバー画像", style = MaterialTheme.typography.headlineSmall)
                Text(path.lastOrNull()?.absolutePath ?: if (gallery) "画像一覧" else "ストレージ", maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(goBack, Modifier.focusRequester(navigationFocus), enabled = !importing) { Text("戻る") }
                    Button({ path = emptyList(); gallery = !gallery }, enabled = !importing) { Text(if (gallery) "ストレージ" else "画像一覧") }
                    Button(access, enabled = !importing) { Text("アクセスを許可") }
                    Button({
                        try { settingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
                        catch (_: android.content.ActivityNotFoundException) { error = "アプリ設定を開けません。テレビの設定から画像へのアクセスを許可してください。" }
                    }, enabled = !importing) { Text("アプリ設定") }
                    Button({ refresh++ }, enabled = !importing && !loading) { Text("再読み込み") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                        items(entries, key = { it.key }) { entry ->
                            Button(
                                onClick = {
                                    if (entry.directory != null) { navigationFocus.safeRequestFocus("ScreensaverBrowser_Folder"); path = path + entry.directory; gallery = false }
                                    else entry.uri?.let { selected = if (it in selected) selected - it else selected + it }
                                },
                                enabled = !importing,
                                modifier = Modifier.fillMaxWidth().height(58.dp),
                            ) {
                                if (entry.uri != null) AsyncImage(entry.uri, null, Modifier.size(42.dp))
                                Spacer(Modifier.width(12.dp))
                                Text((if (entry.directory != null) "▸  " else if (entry.uri in selected) "✓  " else "") + entry.name,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                // Status always has the same slot, including empty and failure states.
                Box(Modifier.fillMaxWidth().height(48.dp)) {
                    Text(when {
                        importing -> "画像を保存しています…"
                        error != null -> error!!
                        loading -> "読み込み中…"
                        entries.isEmpty() -> "画像がありません。別のフォルダーを選ぶか、アクセスを許可してください。"
                        else -> "${selected.size} 枚選択"
                    }, maxLines = 2)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onDismiss, enabled = !importing) { Text("キャンセル") }
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
                    }, modifier = Modifier.focusRequester(importFocus), enabled = selected.isNotEmpty() && !importing) { Text("追加 (${selected.size})") }
                }
            }
        }
    }
}
