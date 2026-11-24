@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.LauncherApp
import com.beeregg2001.komorebi.ui.home.components.LauncherAppCard
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.TvCardFocusBorderWidth
import com.beeregg2001.komorebi.ui.theme.TvCardRadiusListRow
import com.beeregg2001.komorebi.ui.theme.TvContentPaddingEnd
import com.beeregg2001.komorebi.ui.theme.TvContentPaddingStart
import com.beeregg2001.komorebi.ui.theme.tnum
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay

private const val TAG = "AppsTabContent"

internal object AppsTabLayout {
    val horizontalPadding = TvContentPaddingStart
    val endPadding = TvContentPaddingEnd
    val topPadding = 16.dp
    val viewportBottomPadding = 12.dp
    val cardWidth = 160.dp

    // 行高は「表示領域に整数行がぴったり収まる」ように動的算出する。
    // 1080p の Android TV は概ね 540dp 高のため、136dp 固定だと 3 行が
    // 入りきらず最終行で微スクロール（ガタつき）が起きる。
    val idealCardHeight = 136.dp
    // バナー無し（アイコン + ラベル）のカードでも縦に潰れない下限。
    val minCardHeight = 116.dp

    // カード内のラベル領域（上下パディング + ラベル + 余白）の概算。
    // バナー高 = 実効カード高 - この値。
    val labelAreaHeight = 44.dp
    val minBannerHeight = 48.dp

    val iconSize = 72.dp
    val horizontalSpacing = 12.dp
    val verticalSpacing = 12.dp

    // 最終行のフォーカスリングが下辺に接しないよう僅かな余白だけ確保する。
    // contentPadding をグリッドに持たせると収まっていてもスクロールするため、
    // 行高算出時のバッファとしてのみ使う。
    val gridBottomGap = 8.dp
    val scrollbarWidth = 4.dp
    val scrollbarEndPadding = 6.dp
}

@Composable
fun AppsTabContent(
    homeViewModel: HomeViewModel,
    tabFocusRequester: FocusRequester,
    contentFirstItemRequester: FocusRequester,
    onUiReady: () -> Unit,
    focusMemory: FocusMemory,
    tabIndex: Int = HOME_TAB_TITLES.indexOf("アプリ"),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    topBarFocusRequester: FocusRequester? = null,
    isTopNavFocused: Boolean = false,
    onScrolledChanged: (Boolean) -> Unit = {},
) {
    val launcherApps by homeViewModel.launcherApps.collectAsState()
    val hiddenLauncherApps by homeViewModel.hiddenLauncherApps.collectAsState()
    val apps = remember(launcherApps) {
        launcherApps.filterNot { homeViewModel.isPinnedSystemApp(it) }
    }
    val hiddenApps = remember(hiddenLauncherApps) {
        hiddenLauncherApps.filterNot { homeViewModel.isPinnedSystemApp(it) }
    }
    val hideAppLabels by settingsViewModel.hideLauncherAppLabels.collectAsState()
    val launcherAppColumns by settingsViewModel.launcherAppColumns.collectAsState()
    var editingAppId by remember { mutableStateOf<String?>(null) }
    var actionMenuApp by remember { mutableStateOf<LauncherApp?>(null) }
    var hiddenActionMenuApp by remember { mutableStateOf<LauncherApp?>(null) }
    var dismissedActionAppId by remember { mutableStateOf<String?>(null) }
    var isHiddenCatalog by remember { mutableStateOf(false) }
    var hasEnteredCatalog by remember { mutableStateOf(false) }
    var pendingFocusAppId by remember { mutableStateOf<String?>(null) }
    var pendingFocusAppIndex by remember { mutableStateOf<Int?>(null) }
    val appFocusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val gridState = rememberLazyGridState()
    val isScrolled by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 24 }
    }
    LaunchedEffect(isScrolled) { onScrolledChanged(isScrolled) }

    val catalogApps = if (isHiddenCatalog) hiddenApps else apps

    LaunchedEffect(isTopNavFocused) {
        if (isTopNavFocused) {
            gridState.scrollToItem(0)
        }
    }

    LaunchedEffect(apps, hiddenApps) {
        val allIds = (apps + hiddenApps).map { it.stableId }.toSet()
        appFocusRequesters.keys
            .filter { it !in allIds }
            .forEach { appFocusRequesters.remove(it) }
    }

    BackHandler(
        enabled = isHiddenCatalog &&
            actionMenuApp == null &&
            hiddenActionMenuApp == null &&
            editingAppId == null
    ) {
        isHiddenCatalog = false
    }

    // ★ 修正: 固定スリープによる準備完了通知をやめ、先頭アプリが実際に描画された
    // (グリッドに項目が構築された)時点で即座に通知する描画駆動方式に変更。
    val isFirstItemRendered =
        remember { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.isNotEmpty() } }

    LaunchedEffect(isFirstItemRendered.value) {
        if (isFirstItemRendered.value) onUiReady()
    }

    LaunchedEffect(focusMemory.requestId, catalogApps) {
        val request = focusMemory.request ?: return@LaunchedEffect
        if (request.tab != tabIndex) return@LaunchedEffect
        if (request.section != "apps") return@LaunchedEffect
        val index = catalogApps.indexOfFirst { it.stableId == request.itemId }
            .takeIf { it >= 0 } ?: 0
        if (catalogApps.isEmpty()) {
            if (tabFocusRequester.safeRequestFocusWithRetry("AppsRestore_Empty")) {
                focusMemory.consume(request.id)
            }
        } else {
            gridState.scrollToItem(index)
            androidx.compose.runtime.withFrameNanos { }
            val requester = if (index == 0) contentFirstItemRequester
                else appFocusRequesters.getOrPut(catalogApps[index].stableId) { FocusRequester() }
            if (requester.safeRequestFocusWithRetry("AppsRestore")) {
                focusMemory.consume(request.id)
            }
        }
    }

    LaunchedEffect(isHiddenCatalog, hiddenApps.isEmpty()) {
        if (isHiddenCatalog && hiddenApps.isEmpty()) {
            isHiddenCatalog = false
        }
    }

    LaunchedEffect(isHiddenCatalog) {
        if (!hasEnteredCatalog) {
            hasEnteredCatalog = true
            return@LaunchedEffect
        }
        if (isHiddenCatalog && hiddenApps.isEmpty()) {
            isHiddenCatalog = false
            return@LaunchedEffect
        }
        gridState.scrollToItem(0)
        delay(80)
        contentFirstItemRequester.safeRequestFocusWithRetry(
            if (isHiddenCatalog) "AppsHiddenCatalog" else "AppsVisibleCatalog"
        )
    }

    LaunchedEffect(pendingFocusAppId, pendingFocusAppIndex, catalogApps) {
        val targetId = pendingFocusAppId ?: return@LaunchedEffect
        val index = catalogApps.indexOfFirst { it.stableId == targetId }
        if (index < 0) {
            pendingFocusAppId = null
            pendingFocusAppIndex = null
            return@LaunchedEffect
        }
        if (index != pendingFocusAppIndex) return@LaunchedEffect
        gridState.scrollToItem(index)
        androidx.compose.runtime.withFrameNanos { }
        val requester = if (index == 0) contentFirstItemRequester
            else appFocusRequesters.getOrPut(targetId) { FocusRequester() }
        if (requester.safeRequestFocusWithRetry("AppsEditMoved")) {
            pendingFocusAppId = null
            pendingFocusAppIndex = null
        }
    }

    // AlertDialog が閉じた後にだけ元カードへ戻す。ダイアログが存在する間に要求すると
    // ダイアログの FocusGroup に拒否され、背面のタブへフォーカスが落ちる。
    LaunchedEffect(dismissedActionAppId, catalogApps, actionMenuApp, hiddenActionMenuApp) {
        val targetId = dismissedActionAppId ?: return@LaunchedEffect
        if (actionMenuApp != null || hiddenActionMenuApp != null) return@LaunchedEffect
        val index = catalogApps.indexOfFirst { it.stableId == targetId }
        if (index < 0) {
            dismissedActionAppId = null
            return@LaunchedEffect
        }
        gridState.scrollToItem(index)
        androidx.compose.runtime.withFrameNanos { }
        val requester = if (index == 0) contentFirstItemRequester
            else appFocusRequesters.getOrPut(targetId) { FocusRequester() }
        if (requester.safeRequestFocusWithRetry("AppsActionDismiss")) {
            dismissedActionAppId = null
        }
    }

    fun moveVisibleApp(appIndex: Int, delta: Int) {
        val app = apps.getOrNull(appIndex) ?: return
        val targetIndex = (appIndex + delta).coerceIn(0, apps.lastIndex)
        if (targetIndex == appIndex) return
        pendingFocusAppIndex = targetIndex
        pendingFocusAppId = app.stableId
        homeViewModel.moveLauncherApp(app, targetIndex - appIndex)
    }

    val colors = KomorebiTheme.colors
    val upTarget = topBarFocusRequester ?: tabFocusRequester

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = AppsTabLayout.horizontalPadding,
                top = AppsTabLayout.topPadding,
                end = AppsTabLayout.endPadding,
                bottom = AppsTabLayout.viewportBottomPadding,
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isHiddenCatalog) "非表示一覧" else "${catalogApps.size} アプリ",
                style = MaterialTheme.typography.titleMedium.tnum,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val columnCount = launcherAppColumns.toIntOrNull()?.coerceIn(4, 7)
                ?: calculateAppGridColumns(
                    maxWidth,
                    AppsTabLayout.cardWidth,
                    AppsTabLayout.horizontalSpacing,
                )
            val cellWidth =
                ((maxWidth - AppsTabLayout.horizontalSpacing * (columnCount - 1)) / columnCount)
                    .coerceAtLeast(100.dp)
            val totalEntries = catalogApps.size

            // 表示領域に整数行が収まるよう行高を調整する。こうしないと
            // 3 行目が半端に切れ、フォーカス移動のたびに微スクロールして揺れる。
            val neededRows = ((totalEntries + columnCount - 1) / columnCount).coerceAtLeast(1)
            val maxCardHeightToFit =
                (maxHeight - AppsTabLayout.verticalSpacing * (neededRows - 1) - AppsTabLayout.gridBottomGap) / neededRows
            val effectiveCardHeight = AppsTabLayout.idealCardHeight
                .coerceAtMost(maxCardHeightToFit)
                .coerceAtLeast(AppsTabLayout.minCardHeight)
            val effectiveBannerHeight = (effectiveCardHeight - AppsTabLayout.labelAreaHeight)
                .coerceAtLeast(AppsTabLayout.minBannerHeight)

            if (totalEntries == 0) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .focusRequester(contentFirstItemRequester)
                        .focusProperties {
                            up = upTarget
                            left = tabFocusRequester
                        }
                        .onGloballyPositioned { onUiReady() }
                        .focusable(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isHiddenCatalog) {
                            "非表示のアプリはありません"
                        } else {
                            "表示できるアプリがありません"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = KomorebiTheme.colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columnCount),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(AppsTabLayout.horizontalSpacing),
                    verticalArrangement = Arrangement.spacedBy(AppsTabLayout.verticalSpacing),
                    modifier = Modifier
                        .fillMaxSize()
                        .simpleGridVerticalScrollbar(
                            state = gridState,
                            color = KomorebiTheme.colors.textPrimary,
                        ),
                ) {
                    itemsIndexed(
                        items = catalogApps,
                        key = { _, app -> "app_${app.stableId}" },
                    ) { index, app ->
                        val appFocusRequester = appFocusRequesters.getOrPut(app.stableId) {
                            FocusRequester()
                        }
                        val isFirstRow = index / columnCount == 0
                        val isLastRow = index / columnCount == (totalEntries - 1) / columnCount
                        LauncherAppCard(
                            app = app,
                            onClick = {
                                if (isHiddenCatalog && editingAppId == app.stableId) {
                                    editingAppId = null
                                } else if (isHiddenCatalog) {
                                    homeViewModel.launchApp(app)
                                } else if (editingAppId == app.stableId) {
                                    editingAppId = null
                                } else if (editingAppId == null) {
                                    homeViewModel.launchApp(app)
                                }
                            },
                            onManage = {
                                if (isHiddenCatalog) {
                                    hiddenActionMenuApp = app
                                } else {
                                    editingAppId = app.stableId
                                }
                            },
                            onFocus = {},
                            cardWidth = cellWidth,
                            cardHeight = effectiveCardHeight,
                            bannerWidth = cellWidth,
                            bannerHeight = effectiveBannerHeight,
                            iconSize = AppsTabLayout.iconSize,
                            showBorder = false,
                            fullBleedBanner = true,
                            showLabel = !hideAppLabels,
                            manualConfirmHandling = true,
                            isEditing = editingAppId == app.stableId,
                            onMoveLeft = { if (!isHiddenCatalog) moveVisibleApp(index, -1) },
                            onMoveRight = { if (!isHiddenCatalog) moveVisibleApp(index, 1) },
                            onMoveUp = {
                                if (!isHiddenCatalog) moveVisibleApp(index, -columnCount)
                            },
                            onMoveDown = {
                                if (!isHiddenCatalog) moveVisibleApp(index, columnCount)
                            },
                            onHide = {
                                val remaining = apps.filterNot { it.stableId == app.stableId }
                                dismissedActionAppId = if (remaining.isNotEmpty()) {
                                    remaining[index.coerceAtMost(remaining.lastIndex)].stableId
                                } else {
                                    null
                                }
                                homeViewModel.hideLauncherApp(app)
                                editingAppId = null
                            },
                            onOpenActions = {
                                editingAppId = null
                                if (isHiddenCatalog) {
                                    hiddenActionMenuApp = app
                                } else {
                                    actionMenuApp = app
                                }
                            },
                            onDoneEditing = { editingAppId = null },
                            modifier = Modifier
                                .then(
                                    if (index == 0) {
                                        Modifier.focusRequester(contentFirstItemRequester)
                                    } else {
                                        Modifier.focusRequester(appFocusRequester)
                                    }
                                )
                                .focusProperties {
                                    if (index % columnCount == 0) {
                                        left = if (editingAppId == null) tabFocusRequester else FocusRequester.Cancel
                                    }
                                    if ((index + 1) % columnCount == 0 || index == totalEntries - 1) {
                                        right = FocusRequester.Cancel
                                    }
                                    if (isLastRow && (isHiddenCatalog || hiddenApps.isEmpty())) {
                                        down = FocusRequester.Cancel
                                    }
                                }
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown &&
                                        event.key == Key.DirectionUp &&
                                        isFirstRow &&
                                        editingAppId == null
                                    ) {
                                        if (isHiddenCatalog) {
                                            isHiddenCatalog = false
                                        } else {
                                            upTarget.safeRequestFocus(TAG)
                                        }
                                        true
                                    } else if (event.type == KeyEventType.KeyDown &&
                                        event.key == Key.DirectionDown &&
                                        isLastRow &&
                                        hiddenApps.isNotEmpty() &&
                                        editingAppId == null &&
                                        !isHiddenCatalog
                                    ) {
                                        // 最終行から下へ: 非表示アプリ一覧へ遷移する。
                                        isHiddenCatalog = true
                                        true
                                    } else {
                                        false
                                    }
                                }
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        if (editingAppId != null &&
                                            editingAppId != app.stableId &&
                                            pendingFocusAppId == null
                                        ) {
                                            editingAppId = null
                                        }
                                        homeViewModel.lastClickedSection = "apps"
                                        homeViewModel.lastClickedItemId = app.stableId
                                    }
                                }
                        )
                    }
                }
            }
        }
    }

    actionMenuApp?.let { app ->
        LauncherAppActionDialog(
            app = app,
            onAppInfo = {
                homeViewModel.launchAppDetails(app)
                editingAppId = null
                actionMenuApp = null
            },
            onHide = {
                val currentIndex = apps.indexOfFirst { it.stableId == app.stableId }
                val remaining = apps.filterNot { it.stableId == app.stableId }
                dismissedActionAppId = if (remaining.isNotEmpty() && currentIndex >= 0) {
                    remaining[currentIndex.coerceAtMost(remaining.lastIndex)].stableId
                } else {
                    null
                }
                homeViewModel.hideLauncherApp(app)
                editingAppId = null
                actionMenuApp = null
            },
            onDismiss = {
                editingAppId = null
                dismissedActionAppId = app.stableId
                actionMenuApp = null
            },
            canShowHiddenList = hiddenApps.isNotEmpty(),
            onShowHiddenList = {
                editingAppId = null
                actionMenuApp = null
                isHiddenCatalog = true
            }
        )
    }

    hiddenActionMenuApp?.let { app ->
        HiddenLauncherAppActionDialog(
            app = app,
            onAppInfo = {
                homeViewModel.launchAppDetails(app)
                hiddenActionMenuApp = null
            },
            onRestore = {
                val currentIndex = hiddenApps.indexOfFirst { it.stableId == app.stableId }
                val remaining = hiddenApps.filterNot { it.stableId == app.stableId }
                dismissedActionAppId = if (remaining.isNotEmpty() && currentIndex >= 0) {
                    remaining[currentIndex.coerceAtMost(remaining.lastIndex)].stableId
                } else {
                    null
                }
                homeViewModel.restoreLauncherApp(app)
                hiddenActionMenuApp = null
                if (remaining.isEmpty()) {
                    isHiddenCatalog = false
                }
            },
            onDismiss = {
                dismissedActionAppId = app.stableId
                hiddenActionMenuApp = null
            }
        )
    }
}

private fun Modifier.simpleGridVerticalScrollbar(
    state: LazyGridState,
    color: Color,
    width: Dp = AppsTabLayout.scrollbarWidth,
    paddingEnd: Dp = AppsTabLayout.scrollbarEndPadding,
): Modifier = drawWithContent {
    drawContent()
    val totalItems = state.layoutInfo.totalItemsCount
    val visibleItems = state.layoutInfo.visibleItemsInfo.size
    if (totalItems == 0 || visibleItems == 0 || visibleItems >= totalItems) return@drawWithContent

    val firstVisible = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0
    val thumbHeightRatio = (visibleItems.toFloat() / totalItems.toFloat()).coerceIn(0.05f, 0.8f)
    val thumbHeight = size.height * thumbHeightRatio
    val thumbOffsetRatio =
        (firstVisible.toFloat() / (totalItems - visibleItems).coerceAtLeast(1).toFloat())
            .coerceIn(0f, 1f)
    val thumbY = (size.height - thumbHeight) * thumbOffsetRatio

    drawRoundRect(
        color = color.copy(alpha = 0.5f),
        topLeft = Offset(size.width - width.toPx() - paddingEnd.toPx(), thumbY),
        size = Size(width.toPx(), thumbHeight),
        cornerRadius = CornerRadius(width.toPx() / 2f, width.toPx() / 2f),
    )
}

@Composable
private fun LauncherAppActionDialog(
    app: LauncherApp,
    onAppInfo: () -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
    canShowHiddenList: Boolean,
    onShowHiddenList: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val colors = KomorebiTheme.colors
    val buttonShape = RoundedCornerShape(TvCardRadiusListRow)

    LaunchedEffect(app.stableId) {
        delay(80)
        focusRequester.safeRequestFocusWithRetry("LauncherAppAction")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.overlay,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        title = {
            Column {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(focusRequester),
                    shape = ButtonDefaults.shape(buttonShape),
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface1,
                        focusedContainerColor = colors.surface2,
                        contentColor = colors.textSecondary,
                        focusedContentColor = colors.textPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = buttonShape),
                        focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = buttonShape)
                    )
                ) { Text("閉じる") }
                Button(
                    onClick = onAppInfo,
                    shape = ButtonDefaults.shape(buttonShape),
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface1,
                        focusedContainerColor = colors.surface2,
                        contentColor = colors.textPrimary,
                        focusedContentColor = colors.textPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = buttonShape),
                        focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = buttonShape)
                    )
                ) { Text("アプリ情報") }
                Button(
                    onClick = onHide,
                    shape = ButtonDefaults.shape(buttonShape),
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface1,
                        focusedContainerColor = colors.danger.copy(alpha = 0.22f),
                        contentColor = colors.danger,
                        focusedContentColor = colors.textPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = Border(BorderStroke(1.dp, colors.danger.copy(alpha = 0.35f)), shape = buttonShape),
                        focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.danger), shape = buttonShape)
                    )
                ) { Text("非表示へ") }
            }
        },
        dismissButton = {
            if (canShowHiddenList) {
                Button(
                    onClick = onShowHiddenList,
                    shape = ButtonDefaults.shape(buttonShape),
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface1,
                        focusedContainerColor = colors.surface2,
                        contentColor = colors.textSecondary,
                        focusedContentColor = colors.textPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = buttonShape),
                        focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = buttonShape)
                    )
                ) { Text("非表示一覧") }
            }
        }
    )
}

@Composable
private fun HiddenLauncherAppActionDialog(
    app: LauncherApp,
    onAppInfo: () -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val colors = KomorebiTheme.colors
    val buttonShape = RoundedCornerShape(TvCardRadiusListRow)

    LaunchedEffect(app.stableId) {
        delay(80)
        focusRequester.safeRequestFocusWithRetry("HiddenLauncherAppAction")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.overlay,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        title = {
            Column {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(focusRequester),
                    shape = ButtonDefaults.shape(buttonShape),
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface1,
                        focusedContainerColor = colors.surface2,
                        contentColor = colors.textSecondary,
                        focusedContentColor = colors.textPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = buttonShape),
                        focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = buttonShape)
                    )
                ) { Text("閉じる") }
                Button(
                    onClick = onAppInfo,
                    shape = ButtonDefaults.shape(buttonShape),
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface1,
                        focusedContainerColor = colors.surface2,
                        contentColor = colors.textPrimary,
                        focusedContentColor = colors.textPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = buttonShape),
                        focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = buttonShape)
                    )
                ) { Text("アプリ情報") }
                Button(
                    onClick = onRestore,
                    shape = ButtonDefaults.shape(buttonShape),
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface1,
                        focusedContainerColor = colors.accentSoft,
                        contentColor = colors.accent,
                        focusedContentColor = colors.textPrimary
                    ),
                    border = ButtonDefaults.border(
                        border = Border(BorderStroke(1.dp, colors.accent.copy(alpha = 0.35f)), shape = buttonShape),
                        focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = buttonShape)
                    )
                ) { Text("表示へ戻す") }
            }
        },
        dismissButton = {}
    )
}

internal fun calculateAppGridColumns(maxWidth: Dp, cardWidth: Dp, spacing: Dp): Int {
    if (maxWidth <= cardWidth) return 1
    return ((maxWidth + spacing) / (cardWidth + spacing)).toInt().coerceAtLeast(1)
}
