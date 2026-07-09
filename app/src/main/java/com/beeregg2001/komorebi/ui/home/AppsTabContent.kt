@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home

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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.LauncherApp
import com.beeregg2001.komorebi.ui.home.components.LauncherAppCard
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.HomeViewModel
import kotlinx.coroutines.delay

private const val TAG = "AppsTabContent"

@Composable
fun AppsTabContent(
    homeViewModel: HomeViewModel,
    tabFocusRequester: FocusRequester,
    contentFirstItemRequester: FocusRequester,
    onUiReady: () -> Unit,
) {
    val launcherApps by homeViewModel.launcherApps.collectAsState()
    val hiddenLauncherApps by homeViewModel.hiddenLauncherApps.collectAsState()
    val apps = remember(launcherApps) {
        launcherApps.filterNot { homeViewModel.isPinnedSystemApp(it) }
    }
    val hiddenApps = remember(hiddenLauncherApps) {
        hiddenLauncherApps.filterNot { homeViewModel.isPinnedSystemApp(it) }
    }
    var editingAppId by remember { mutableStateOf<String?>(null) }
    var actionMenuApp by remember { mutableStateOf<LauncherApp?>(null) }
    var showHiddenAppsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(apps.isNotEmpty()) {
        delay(250)
        onUiReady()
    }

    fun moveVisibleApp(appIndex: Int, delta: Int) {
        val app = apps.getOrNull(appIndex) ?: return
        val targetIndex = (appIndex + delta).coerceIn(0, apps.lastIndex)
        if (targetIndex == appIndex) return
        homeViewModel.moveLauncherApp(app, targetIndex - appIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, top = 36.dp, end = 48.dp, bottom = 80.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cardWidth = 160.dp
            val cardHeight = 136.dp
            val bannerHeight = 90.dp
            val iconSize = 72.dp
            val horizontalSpacing = 12.dp
            val verticalSpacing = 14.dp
            val columnCount = calculateAppGridColumns(maxWidth, cardWidth, horizontalSpacing)

            Column(verticalArrangement = Arrangement.spacedBy(verticalSpacing)) {
                apps.chunked(columnCount).forEachIndexed { rowIndex, rowApps ->
                    Row(horizontalArrangement = Arrangement.spacedBy(horizontalSpacing)) {
                        rowApps.forEachIndexed { columnIndex, app ->
                            val index = rowIndex * columnCount + columnIndex
                            LauncherAppCard(
                                app = app,
                                onClick = {
                                    if (editingAppId == app.stableId) {
                                        editingAppId = null
                                    } else if (editingAppId == null) {
                                        homeViewModel.launchApp(app)
                                    }
                                },
                                onManage = { editingAppId = app.stableId },
                                onFocus = {},
                                cardWidth = cardWidth,
                                cardHeight = cardHeight,
                                bannerWidth = cardWidth,
                                bannerHeight = bannerHeight,
                                iconSize = iconSize,
                                showBorder = false,
                                fullBleedBanner = true,
                                manualConfirmHandling = true,
                                isEditing = editingAppId == app.stableId,
                                onMoveLeft = { moveVisibleApp(index, -1) },
                                onMoveRight = { moveVisibleApp(index, 1) },
                                onMoveUp = { moveVisibleApp(index, -columnCount) },
                                onMoveDown = { moveVisibleApp(index, columnCount) },
                                onHide = {
                                    homeViewModel.hideLauncherApp(app)
                                    editingAppId = null
                                },
                                onOpenActions = {
                                    editingAppId = null
                                    actionMenuApp = app
                                },
                                onDoneEditing = { editingAppId = null },
                                modifier = Modifier
                                    .then(
                                        if (index == 0) {
                                            Modifier.focusRequester(contentFirstItemRequester)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .focusProperties {
                                        if (columnIndex == 0) left = FocusRequester.Cancel
                                    }
                                    .onKeyEvent {
                                        if (it.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                                            it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                                            rowIndex == 0 &&
                                            editingAppId == null
                                        ) {
                                            tabFocusRequester.safeRequestFocus(TAG)
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    .onFocusChanged {
                                        if (it.isFocused) {
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
                showHiddenAppsDialog = true
            }
        )
    }

    if (showHiddenAppsDialog) {
        HiddenLauncherAppsDialog(
            apps = hiddenApps,
            onRestore = { app ->
                homeViewModel.restoreLauncherApp(app)
                showHiddenAppsDialog = false
            },
            onDismiss = { showHiddenAppsDialog = false }
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
private fun HiddenLauncherAppsDialog(
    apps: List<LauncherApp>,
    onRestore: (LauncherApp) -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val colors = KomorebiTheme.colors

    LaunchedEffect(apps.firstOrNull()?.stableId) {
        delay(80)
        focusRequester.safeRequestFocusWithRetry("HiddenLauncherApps")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        title = {
            Text(
                text = "非表示一覧",
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                apps.forEachIndexed { index, app ->
                    Button(
                        onClick = { onRestore(app) },
                        modifier = if (index == 0) Modifier.focusRequester(focusRequester) else Modifier,
                        colors = ButtonDefaults.colors(
                            containerColor = colors.surface,
                            focusedContainerColor = colors.textPrimary,
                            contentColor = colors.textPrimary,
                            focusedContentColor = if (colors.isDark) Color.Black else Color.White
                        )
                    ) {
                        Text(app.label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = colors.surface.copy(alpha = 0.7f),
                    focusedContainerColor = colors.textPrimary,
                    contentColor = colors.textSecondary,
                    focusedContentColor = if (colors.isDark) Color.Black else Color.White
                )
            ) { Text("閉じる") }
        }
    )
}

private fun calculateAppGridColumns(maxWidth: Dp, cardWidth: Dp, spacing: Dp): Int {
    if (maxWidth <= cardWidth) return 1
    return ((maxWidth + spacing) / (cardWidth + spacing)).toInt().coerceAtLeast(1)
}
