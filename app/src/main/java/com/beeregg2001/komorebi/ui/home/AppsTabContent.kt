@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
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
    val viewportBottomPadding = 24.dp
    val cardWidth = 160.dp
    val cardHeight = 136.dp
    val bannerHeight = 90.dp
    val iconSize = 72.dp
    val horizontalSpacing = 12.dp
    val verticalSpacing = 14.dp
    val bottomScrollSafeArea = cardHeight
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
    val appScrollState = rememberScrollState()
    val catalogApps = if (isHiddenCatalog) hiddenApps else apps
    val context = LocalContext.current.applicationContext

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

    LaunchedEffect(apps.isNotEmpty(), hiddenApps.isNotEmpty()) {
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
        appScrollState.scrollTo(0)
        delay(80)
        contentFirstItemRequester.safeRequestFocusWithRetry(
            if (isHiddenCatalog) "AppsHiddenCatalog" else "AppsVisibleCatalog"
        )
    }

    LaunchedEffect(pendingFocusAppId, apps) {
        val targetId = pendingFocusAppId ?: return@LaunchedEffect
        delay(60)
        val targetIndex = apps.indexOfFirst { it.stableId == targetId }
        if (targetIndex == 0) {
            appFocusRequesters[targetId]?.safeRequestFocusWithRetry("AppsEditMovedFirst")
                ?: contentFirstItemRequester.safeRequestFocusWithRetry("AppsEditMovedFirst")
        } else {
            appFocusRequesters[targetId]?.safeRequestFocusWithRetry("AppsEditMoved")
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

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(appScrollState)
                    .padding(bottom = AppsTabLayout.bottomScrollSafeArea),
                verticalArrangement = Arrangement.spacedBy(AppsTabLayout.verticalSpacing),
            ) {
                val appRows = catalogApps.chunked(columnCount)
                appRows.forEachIndexed { rowIndex, rowApps ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AppsTabLayout.horizontalSpacing),
                    ) {
                        rowApps.forEachIndexed { columnIndex, app ->
                            val index = rowIndex * columnCount + columnIndex
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
                                onManage = {
                                    if (isHiddenCatalog) {
                                        editingAppId = app.stableId
                                    } else {
                                        editingAppId = app.stableId
                                    }
                                },
                                onFocus = {},
                                cardWidth = AppsTabLayout.cardWidth,
                                cardHeight = AppsTabLayout.cardHeight,
                                bannerWidth = AppsTabLayout.cardWidth,
                                bannerHeight = AppsTabLayout.bannerHeight,
                                iconSize = AppsTabLayout.iconSize,
                                showBorder = false,
                                fullBleedBanner = true,
                                showLabel = !hideAppLabels,
                                manualConfirmHandling = true,
                                isEditing = editingAppId == app.stableId,
                                onMoveLeft = { if (!isHiddenCatalog) moveVisibleApp(index, -1) },
                                onMoveRight = { if (!isHiddenCatalog) moveVisibleApp(index, 1) },
                                onMoveUp = { if (!isHiddenCatalog) moveVisibleApp(index, -columnCount) },
                                onMoveDown = { if (!isHiddenCatalog) moveVisibleApp(index, columnCount) },
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
                                        if (index == 0) Modifier.focusRequester(contentFirstItemRequester)
                                        else Modifier.focusRequester(appFocusRequester)
                                    )
                                    .focusProperties {
                                        if (columnIndex == 0) left = FocusRequester.Cancel
                                    }
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown &&
                                            event.key == Key.DirectionUp &&
                                            rowIndex == 0 &&
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
                                            rowIndex == appRows.lastIndex &&
                                            hiddenApps.isNotEmpty() &&
                                            editingAppId == null &&
                                            !isHiddenCatalog
                                        ) {
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
