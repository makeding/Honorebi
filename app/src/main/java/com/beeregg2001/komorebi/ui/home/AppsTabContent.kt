@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
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
    val viewportBottomPadding = 24.dp
    val cardWidth = 160.dp
    val cardHeight = 136.dp
    val bannerHeight = 90.dp
    val iconSize = 72.dp
    val horizontalSpacing = 12.dp
    val verticalSpacing = 14.dp
    val bottomScrollSafeArea = cardHeight

    // フォーカス中カードが沈み込んで見えないよう、グリッド最下段に確保する余白。
    val gridBottomPadding = 28.dp
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
    val appColumnsSetting by settingsViewModel.launcherAppColumns.collectAsState()
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
            val autoColumnCount = calculateAppGridColumns(
                maxWidth,
                AppsTabLayout.cardWidth,
                AppsTabLayout.horizontalSpacing,
            )
            val columnCount = (appColumnsSetting.toIntOrNull() ?: autoColumnCount)
                .coerceIn(1, autoColumnCount)
            val cellWidth =
                ((maxWidth - AppsTabLayout.horizontalSpacing * (columnCount - 1)) / columnCount)
                    .coerceAtLeast(AppsTabLayout.cardWidth)

            val showHiddenEntry = !isHiddenCatalog && hiddenApps.isNotEmpty()
            val showVisibleEntry = isHiddenCatalog
            val extraEntryCount = if (showHiddenEntry || showVisibleEntry) 1 else 0
            val totalEntries = catalogApps.size + extraEntryCount

            val gridItemModifier: (Int) -> Modifier = { index ->
                Modifier.focusProperties {
                    if (index < columnCount) up = tabFocusRequester
                    if (index % columnCount == 0) left = FocusRequester.Cancel
                    if (index == totalEntries - 1) right = FocusRequester.Cancel
                }
            }

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
                    contentPadding = PaddingValues(bottom = AppsTabLayout.gridBottomPadding),
                    horizontalArrangement = Arrangement.spacedBy(AppsTabLayout.horizontalSpacing),
                    verticalArrangement = Arrangement.spacedBy(AppsTabLayout.verticalSpacing),
                    modifier = Modifier
                        .fillMaxSize()
                        .focusGroup()
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
                            cardHeight = AppsTabLayout.cardHeight,
                            bannerWidth = cellWidth,
                            bannerHeight = AppsTabLayout.bannerHeight,
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
                                .then(gridItemModifier(index))
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

                    if (showHiddenEntry) {
                        item(key = "entry_hidden") {
                            AppsCatalogEntryTile(
                                label = "非表示のアプリ",
                                description = "${hiddenApps.size} 件",
                                icon = Icons.Default.VisibilityOff,
                                onClick = { isHiddenCatalog = true },
                                modifier = Modifier
                                    .then(gridItemModifier(catalogApps.size))
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            homeViewModel.lastClickedSection = "apps"
                                            homeViewModel.lastClickedItemId = HIDDEN_CATALOG_STABLE_ID
                                        }
                                    },
                                cardWidth = cellWidth,
                                cardHeight = AppsTabLayout.cardHeight,
                            )
                        }
                    }

                    if (showVisibleEntry) {
                        item(key = "entry_visible") {
                            AppsCatalogEntryTile(
                                label = "表示中のアプリ",
                                description = "${apps.size} 件",
                                icon = Icons.Default.Visibility,
                                onClick = { isHiddenCatalog = false },
                                modifier = Modifier
                                    .then(gridItemModifier(catalogApps.size))
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            homeViewModel.lastClickedSection = "apps"
                                            homeViewModel.lastClickedItemId = VISIBLE_CATALOG_STABLE_ID
                                        }
                                    },
                                cardWidth = cellWidth,
                                cardHeight = AppsTabLayout.cardHeight,
                            )
                        }
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

internal const val HIDDEN_CATALOG_STABLE_ID = "__catalog_hidden__"
internal const val VISIBLE_CATALOG_STABLE_ID = "__catalog_visible__"

@Composable
private fun AppsCatalogEntryTile(
    label: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp,
    cardHeight: Dp,
) {
    var isFocused by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors

    Surface(
        onClick = onClick,
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight)
            .onFocusChanged { isFocused = it.isFocused },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.textPrimary.copy(alpha = if (isFocused) 0.1f else 0.05f),
            focusedContainerColor = colors.textPrimary.copy(alpha = 0.1f),
            contentColor = colors.textSecondary,
            focusedContentColor = colors.textPrimary,
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.12f))),
            focusedBorder = Border(BorderStroke(2.5.dp, colors.accent)),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
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
