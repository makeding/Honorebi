package com.beeregg2001.komorebi.ui.setting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.safeRequestFocus

@Composable
internal fun ScreensaverImageGallery(imageUris: List<String>, onDismiss: () -> Unit) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var lastSelected by remember { mutableIntStateOf(0) }
    val previewIndex = selectedIndex?.takeIf { it in imageUris.indices }
    val tileFocus = remember(imageUris) { imageUris.map { FocusRequester() } }
    val backFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val configuration = LocalConfiguration.current
    val width = minOf(960, configuration.screenWidthDp - 64).coerceAtLeast(280).dp
    val height = minOf(560, configuration.screenHeightDp - 64).coerceAtLeast(280).dp
    val scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    val goBack = { if (previewIndex != null) selectedIndex = null else onDismiss() }
    BackHandler { goBack() }

    Dialog(onDismissRequest = goBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(previewIndex != null, imageUris) {
            if (previewIndex == null && imageUris.isNotEmpty()) {
                val index = lastSelected.coerceIn(imageUris.indices)
                gridState.scrollToItem(index)
                withFrameNanos { }
                tileFocus[index].safeRequestFocus("ScreensaverGallery_Thumbnail")
            } else {
                withFrameNanos { }
                backFocus.safeRequestFocus("ScreensaverGallery_Back")
            }
        }
        Surface(Modifier.width(width).height(height).testTag("screensaver-gallery"), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (previewIndex == null) "登録画像 (${imageUris.size} 枚)" else "画像 ${previewIndex + 1} / ${imageUris.size}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (previewIndex != null) {
                        Button({ selectedIndex = previewIndex - 1; lastSelected = previewIndex - 1 }, enabled = previewIndex > 0, scale = scale) { Text("前へ") }
                        Button({ selectedIndex = previewIndex + 1; lastSelected = previewIndex + 1 }, enabled = previewIndex < imageUris.lastIndex, scale = scale) { Text("次へ") }
                    }
                    Button(goBack, Modifier.focusRequester(backFocus), scale = scale) { Text(if (previewIndex == null) "閉じる" else "一覧に戻る") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (previewIndex != null) {
                        key(imageUris[previewIndex]) {
                            SavedScreensaverImage(imageUris[previewIndex], Modifier.fillMaxSize().testTag("saved-image-preview"), preview = true)
                        }
                    } else if (imageUris.isEmpty()) {
                        Text("登録画像はありません。設定の「画像を追加」から追加してください", Modifier.align(Alignment.Center))
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(180.dp),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            itemsIndexed(imageUris, key = { index, _ -> index }) { index, uri ->
                                Button(
                                    onClick = { lastSelected = index; selectedIndex = index },
                                    modifier = Modifier.fillMaxWidth().height(152.dp).focusRequester(tileFocus[index]).testTag("saved-image-$index"),
                                    scale = scale,
                                    contentPadding = PaddingValues(8.dp),
                                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                                ) {
                                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        SavedScreensaverImage(uri, Modifier.weight(1f).fillMaxWidth(), preview = false)
                                        Text("画像 ${index + 1}", maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedScreensaverImage(uri: String, modifier: Modifier, preview: Boolean) {
    var failure by remember(uri) { mutableStateOf<String?>(null) }
    var loading by remember(uri) { mutableStateOf(true) }
    var attempt by remember(uri) { mutableIntStateOf(0) }
    Box(modifier.background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        key(attempt) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onLoading = { loading = true; failure = null },
                onSuccess = { loading = false; failure = null },
                onError = { loading = false; failure = it.result.throwable.localizedMessage ?: "画像を読み取れません" },
            )
        }
        if (loading) Text("読み込み中", style = MaterialTheme.typography.bodySmall)
        if (failure != null) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("画像を表示できません", style = MaterialTheme.typography.bodyMedium)
                if (preview) {
                    Text(failure.orEmpty(), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Button({ loading = true; failure = null; attempt++ }, scale = ButtonDefaults.scale(focusedScale = 1f)) { Text("再読み込み") }
                }
            }
        }
    }
}
