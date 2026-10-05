package com.beeregg2001.komorebi.ui.video.smb

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.TvCardFamily
import com.beeregg2001.komorebi.ui.theme.TvCardRadiusGrid
import com.beeregg2001.komorebi.ui.theme.TvCardRadiusListRow
import com.beeregg2001.komorebi.ui.theme.tvCardFocus
import com.beeregg2001.komorebi.ui.theme.tvCardMarquee
import com.beeregg2001.komorebi.ui.video.FocusTicket
import com.beeregg2001.komorebi.ui.video.FocusTicketManager
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import kotlinx.coroutines.delay

@SuppressLint("RememberInComposition")
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SmbListContent(
    items: List<SmbItem>,
    onItemClick: (SmbItem) -> Unit,
    focuses: com.beeregg2001.komorebi.ui.video.RecordListFocusRequesters,
    ticketManager: FocusTicketManager,
    onOpenRightMenu: (SmbItem) -> Unit,
    onLeftKey: () -> Unit,
    onFocusedItemChanged: (SmbItem) -> Unit,
    isMenuOpen: Boolean,
    targetPathToFocus: String? = null,
    onTargetFocusConsumed: () -> Unit = {},
    onBackPress: () -> Unit = {},
    onTopBarDownRequesterChanged: (FocusRequester) -> Unit = {}
) {
    val listState = rememberLazyListState()
    val colors = KomorebiTheme.colors
    // フォーカス演出 (スケール / 枠) は共通トークンに一元化
    val focusSpec = tvCardFocus(TvCardFamily.LIST_ROW)
    val isScrollInProgress = listState.isScrollInProgress
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    val focusPath = ticketManager.targetPath ?: targetPathToFocus

    val firstVisibleItemIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleItemIndex, items.size) {
        val firstVisibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull()
        val requester = if (firstVisibleItem != null) {
            val path = items.getOrNull(firstVisibleItem.index)?.path
            if (path != null) itemFocusRequesters[path] ?: focuses.firstItem else focuses.firstItem
        } else {
            focuses.firstItem
        }
        onTopBarDownRequesterChanged(requester)
    }

    LaunchedEffect(focusPath, items, ticketManager.requestGeneration) {
        val generation = ticketManager.requestGeneration
        if (focusPath != null && items.isNotEmpty()) {
            val index = items.indexOfFirst { it.path == focusPath }
            if (index != -1) {
                listState.scrollToItem(maxOf(0, index - 2))
                delay(200)
                if (ticketManager.restore(FocusTicket.TARGET_ID, generation, itemFocusRequesters[focusPath], "SMB_List_Focus")) {
                    onTargetFocusConsumed()
                }
            }
        }
    }

    LaunchedEffect(ticketManager.currentTicket, ticketManager.requestGeneration, items) {
        val generation = ticketManager.requestGeneration
        if (ticketManager.currentTicket == FocusTicket.LIST_TOP) {
            if (items.isNotEmpty()) {
                listState.scrollToItem(0)
                delay(150)
                ticketManager.restore(FocusTicket.LIST_TOP, generation, focuses.firstItem, "SmbList_Top")
            }
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 16.dp, end = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focuses.contentContainer)
            .focusGroup()
            .focusProperties { canFocus = !isMenuOpen }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                    onBackPress()
                    return@onKeyEvent true
                }
                false
            }
    ) {
        itemsIndexed(items, key = { _, item -> item.path }) { index, item ->
            val specificRequester = itemFocusRequesters.getOrPut(item.path) { FocusRequester() }
            var isFocused by remember { mutableStateOf(false) }

            Surface(
                onClick = { onItemClick(item) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .focusRequester(specificRequester)
                    .then(if (index == 0) Modifier.focusRequester(focuses.firstItem) else Modifier)
                    .onFocusChanged {
                        isFocused = it.isFocused
                        if (it.isFocused) onFocusedItemChanged(item)
                    }
                    .focusProperties {
                        left = FocusRequester.Cancel; right = FocusRequester.Cancel
                    }
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            if (event.key == Key.DirectionRight) {
                                if (!isScrollInProgress) {
                                    onOpenRightMenu(item)
                                }
                                return@onKeyEvent true
                            } else if (event.key == Key.DirectionLeft) {
                                if (!isScrollInProgress) {
                                    onLeftKey()
                                }
                                return@onKeyEvent true
                            }
                        }
                        false
                    },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(TvCardRadiusListRow)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = focusSpec.focusedScale),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isFocused) colors.textPrimary else Color.Transparent,
                    focusedContainerColor = colors.textPrimary,
                    contentColor = if (isFocused) (if (colors.isDark) Color.Black else Color.White) else colors.textPrimary,
                    focusedContentColor = if (colors.isDark) Color.Black else Color.White
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = focusSpec.focusedBorder
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.Movie,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = if (item.isDirectory) colors.accent else colors.textSecondary
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .tvCardMarquee(isFocused),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!item.isDirectory) {
                        Text(
                            text = formatFileSize(item.size),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.alpha(0.7f)
                        )
                    }
                    if (isFocused) {
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier
                                .size(32.dp)
                                .alpha(0.7f)
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("RememberInComposition")
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SmbGridContent(
    items: List<SmbItem>,
    onItemClick: (SmbItem) -> Unit,
    focuses: com.beeregg2001.komorebi.ui.video.RecordListFocusRequesters,
    ticketManager: FocusTicketManager,
    onOpenRightMenu: (SmbItem) -> Unit,
    onLeftKey: () -> Unit,
    onFocusedItemChanged: (SmbItem) -> Unit,
    isMenuOpen: Boolean,
    targetPathToFocus: String? = null,
    onTargetFocusConsumed: () -> Unit = {},
    onBackPress: () -> Unit = {},
    onTopBarDownRequesterChanged: (FocusRequester) -> Unit = {}
) {
    val gridState = rememberLazyGridState()
    val colors = KomorebiTheme.colors
    // フォーカス演出 (スケール / 枠 / スクリム) は共通トークンに一元化
    val focusSpec = tvCardFocus(TvCardFamily.GRID)
    val isScrollInProgress = gridState.isScrollInProgress
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    val focusPath = ticketManager.targetPath ?: targetPathToFocus

    val firstVisibleItemIndex by remember { derivedStateOf { gridState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleItemIndex, items.size) {
        val firstVisibleItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull()
        val requester = if (firstVisibleItem != null) {
            val path = items.getOrNull(firstVisibleItem.index)?.path
            if (path != null) itemFocusRequesters[path] ?: focuses.firstItem else focuses.firstItem
        } else {
            focuses.firstItem
        }
        onTopBarDownRequesterChanged(requester)
    }

    LaunchedEffect(focusPath, items, ticketManager.requestGeneration) {
        val generation = ticketManager.requestGeneration
        if (focusPath != null && items.isNotEmpty()) {
            val index = items.indexOfFirst { it.path == focusPath }
            if (index != -1) {
                val targetRowFirstIndex = index - (index % 4)
                gridState.scrollToItem(maxOf(0, targetRowFirstIndex - 4))
                delay(200)
                if (ticketManager.restore(FocusTicket.TARGET_ID, generation, itemFocusRequesters[focusPath], "SMB_List_Focus")) {
                    onTargetFocusConsumed()
                }
            }
        }
    }

    LaunchedEffect(ticketManager.currentTicket, ticketManager.requestGeneration, items) {
        val generation = ticketManager.requestGeneration
        if (ticketManager.currentTicket == FocusTicket.LIST_TOP) {
            if (items.isNotEmpty()) {
                gridState.scrollToItem(0)
                delay(150)
                ticketManager.restore(FocusTicket.LIST_TOP, generation, focuses.firstItem, "SmbGrid_Top")
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = gridState,
        contentPadding = PaddingValues(top = 16.dp, end = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focuses.contentContainer)
            .focusGroup()
            .focusProperties { canFocus = !isMenuOpen }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.Escape)) {
                    onBackPress()
                    return@onKeyEvent true
                }
                false
            }
    ) {
        itemsIndexed(items, key = { _, item -> item.path }) { index, item ->
            val specificRequester = itemFocusRequesters.getOrPut(item.path) { FocusRequester() }
            var isFocused by remember { mutableStateOf(false) }
            val inverseColor = if (colors.isDark) Color.Black else Color.White

            Surface(
                onClick = { onItemClick(item) },
                modifier = Modifier
                    .aspectRatio(16f / 9f)
                    .focusRequester(specificRequester)
                    .then(if (index == 0) Modifier.focusRequester(focuses.firstItem) else Modifier)
                    .onFocusChanged {
                        isFocused = it.isFocused
                        if (it.isFocused) {
                            onFocusedItemChanged(item)
                        }
                    }
                    .focusProperties {
                        if (index % 4 == 0) left = FocusRequester.Cancel
                    }
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            if (event.key == Key.DirectionLeft && index % 4 == 0) {
                                if (!isScrollInProgress) {
                                    onLeftKey()
                                }
                                return@onKeyEvent true
                            }
                        }
                        false
                    },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(TvCardRadiusGrid)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = focusSpec.focusedScale),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = colors.surface,
                    focusedContainerColor = colors.textPrimary,
                    contentColor = colors.textPrimary,
                    focusedContentColor = inverseColor
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = focusSpec.focusedBorder
                )
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // グリッドカードは文字可読性のため常設スクリム (フォーカス時は薄く / 非フォーカスは濃く)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                colors.background.copy(
                                    alpha = if (isFocused) focusSpec.scrimAlphaFocused else focusSpec.scrimAlphaUnfocused
                                )
                            )
                    )

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.Movie,
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .padding(bottom = 24.dp),
                            tint = if (item.isDirectory) colors.accent else colors.textSecondary
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(colors.surface.copy(alpha = 0.85f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            // ★ 修正: テキストの色を colors.textPrimary に固定（isFocused による分岐を削除）
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.tvCardMarquee(isFocused)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (item.isDirectory) "フォルダ" else "ファイル",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                // ★ 修正: サブテキストの色も colors.textPrimary.copy(alpha = 0.8f) に固定
                                color = colors.textPrimary.copy(alpha = 0.8f),
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            if (!item.isDirectory) {
                                Text(
                                    text = formatFileSize(item.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    // ★ 修正: サイズテキストの色も固定
                                    color = colors.textPrimary.copy(alpha = 0.8f),
                                    textAlign = TextAlign.End
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var digit = size.toDouble()
    var unitIndex = 0
    while (digit >= 1024 && unitIndex < units.size - 1) {
        digit /= 1024
        unitIndex++
    }
    return "%.1f %s".format(digit, units[unitIndex])
}