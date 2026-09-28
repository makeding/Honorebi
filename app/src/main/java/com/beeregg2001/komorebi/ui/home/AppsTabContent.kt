@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.LauncherApp
import com.beeregg2001.komorebi.ui.home.components.LauncherAppCard
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay

private const val TAG = "AppsTabContent"

internal object AppsTabLayout {
    val horizontalPadding = 48.dp
    val topPadding = 36.dp
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
    settingsViewModel: SettingsViewModel = hiltViewModel(),
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
    var editingAppId by remember { mutableStateOf<String?>(null) }
    var actionMenuApp by remember { mutableStateOf<LauncherApp?>(null) }
    var hiddenActionMenuApp by remember { mutableStateOf<LauncherApp?>(null) }
    var isHiddenCatalog by remember { mutableStateOf(false) }
    var hasEnteredCatalog by remember { mutableStateOf(false) }
    var pendingFocusAppId by remember { mutableStateOf<String?>(null) }
    val appFocusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val gridState = rememberLazyGridState()
    val context = LocalContext.current.applicationContext

    val catalogApps = if (isHiddenCatalog) hiddenApps else apps

    DisposableEffect(context, homeViewModel) {
        val packageChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_PACKAGE_ADDED,
                    Intent.ACTION_PACKAGE_REMOVED,
                    Intent.ACTION_PACKAGE_CHANGED,
                    Intent.ACTION_PACKAGE_REPLACED -> homeViewModel.refreshLauncherApps()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(packageChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(packageChangeReceiver, filter)
        }

        onDispose {
            runCatching { context.unregisterReceiver(packageChangeReceiver) }
        }
    }

    LaunchedEffect(apps.map { it.stableId }) {
        val visibleIds = apps.map { it.stableId }.toSet()
        appFocusRequesters.keys
            .filter { it !in visibleIds }
            .forEach { appFocusRequesters.remove(it) }
    }

    LaunchedEffect(apps, hiddenApps, isHiddenCatalog) {
        delay(250)
        onUiReady()
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

    LaunchedEffect(pendingFocusAppId, catalogApps) {
        val targetId = pendingFocusAppId ?: return@LaunchedEffect
        delay(60)
        val targetIndex = catalogApps.indexOfFirst { it.stableId == targetId }
        if (targetIndex == 0) {
            appFocusRequesters[targetId]?.safeRequestFocusWithRetry("AppsEditMovedFirst")
                ?: contentFirstItemRequester.safeRequestFocusWithRetry("AppsEditMovedFirst")
        } else if (targetIndex > 0) {
            gridState.animateScrollToItem(targetIndex)
            delay(60)
            appFocusRequesters[targetId]?.safeRequestFocusWithRetry("AppsEditMoved")
                ?: contentFirstItemRequester.safeRequestFocusWithRetry("AppsEditMovedFallback")
        }
        pendingFocusAppId = null
    }

    fun moveVisibleApp(appIndex: Int, delta: Int) {
        val app = apps.getOrNull(appIndex) ?: return
        val targetIndex = (appIndex + delta).coerceIn(0, apps.lastIndex)
        if (targetIndex == appIndex) return
        pendingFocusAppId = app.stableId
        homeViewModel.moveLauncherApp(app, targetIndex - appIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = AppsTabLayout.horizontalPadding,
                top = AppsTabLayout.topPadding,
                end = AppsTabLayout.horizontalPadding,
                bottom = AppsTabLayout.viewportBottomPadding,
            )
    ) {
        if (isHiddenCatalog) {
            Text(
                text = "非表示一覧",
                style = MaterialTheme.typography.titleLarge,
                color = KomorebiTheme.colors.textPrimary
            )
            Spacer(Modifier.height(28.dp))
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columnCount = calculateAppGridColumns(
                maxWidth,
                AppsTabLayout.cardWidth,
                AppsTabLayout.horizontalSpacing,
            )
            val cellWidth =
                ((maxWidth - AppsTabLayout.horizontalSpacing * (columnCount - 1)) / columnCount)
                    .coerceAtLeast(AppsTabLayout.cardWidth)
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
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                            onManage = { editingAppId = app.stableId },
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
                                    if (index % columnCount == 0) left = FocusRequester.Cancel
                                    if (index == totalEntries - 1) right = FocusRequester.Cancel
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
                                            tabFocusRequester.safeRequestFocus(TAG)
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
                homeViewModel.hideLauncherApp(app)
                editingAppId = null
                actionMenuApp = null
            },
            onDismiss = {
                editingAppId = null
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
                homeViewModel.restoreLauncherApp(app)
                hiddenActionMenuApp = null
            },
            onDismiss = { hiddenActionMenuApp = null }
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

    LaunchedEffect(app.stableId) {
        delay(80)
        focusRequester.safeRequestFocusWithRetry("LauncherAppAction")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
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
        text = {
            Text(
                text = "アプリ操作",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(focusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface.copy(alpha = 0.7f),
                        focusedContainerColor = colors.textPrimary,
                        contentColor = colors.textSecondary,
                        focusedContentColor = if (colors.isDark) Color.Black else Color.White
                    )
                ) { Text("閉じる") }
                Button(
                    onClick = onAppInfo,
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface,
                        focusedContainerColor = colors.textPrimary,
                        contentColor = colors.textPrimary,
                        focusedContentColor = if (colors.isDark) Color.Black else Color.White
                    )
                ) { Text("アプリ情報") }
                Button(
                    onClick = onHide,
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface,
                        focusedContainerColor = Color(0xFFFF8AAE),
                        contentColor = colors.textPrimary,
                        focusedContentColor = Color.Black
                    )
                ) { Text("非表示へ") }
            }
        },
        dismissButton = {
            if (canShowHiddenList) {
                Button(
                    onClick = onShowHiddenList,
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface.copy(alpha = 0.7f),
                        focusedContainerColor = colors.textPrimary,
                        contentColor = colors.textSecondary,
                        focusedContentColor = if (colors.isDark) Color.Black else Color.White
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

    LaunchedEffect(app.stableId) {
        delay(80)
        focusRequester.safeRequestFocusWithRetry("HiddenLauncherAppAction")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
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
        text = {
            Text(
                text = "非表示アプリ操作",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(focusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface.copy(alpha = 0.7f),
                        focusedContainerColor = colors.textPrimary,
                        contentColor = colors.textSecondary,
                        focusedContentColor = if (colors.isDark) Color.Black else Color.White
                    )
                ) { Text("閉じる") }
                Button(
                    onClick = onAppInfo,
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface,
                        focusedContainerColor = colors.textPrimary,
                        contentColor = colors.textPrimary,
                        focusedContentColor = if (colors.isDark) Color.Black else Color.White
                    )
                ) { Text("アプリ情報") }
                Button(
                    onClick = onRestore,
                    colors = ButtonDefaults.colors(
                        containerColor = colors.surface,
                        focusedContainerColor = Color(0xFFFF8AAE),
                        contentColor = colors.textPrimary,
                        focusedContentColor = Color.Black
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
