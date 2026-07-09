@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.LauncherApp
import com.beeregg2001.komorebi.ui.home.components.LauncherAppCard
import com.beeregg2001.komorebi.ui.home.components.LauncherAppManagementDialog
import com.beeregg2001.komorebi.ui.home.components.SectionHeader
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
    val apps = remember(launcherApps) {
        launcherApps.filterNot { homeViewModel.isPinnedSystemApp(it) }
    }
    val gridState = rememberLazyGridState()
    var managedApp by remember { mutableStateOf<LauncherApp?>(null) }

    LaunchedEffect(apps.isNotEmpty()) {
        delay(250)
        onUiReady()
    }

    fun isFirstGridRow(index: Int): Boolean {
        val visibleItems = gridState.layoutInfo.visibleItemsInfo
        val firstItem = visibleItems.firstOrNull { it.index == 0 } ?: return index == 0
        val currentItem = visibleItems.firstOrNull { it.index == index } ?: return false
        return currentItem.offset.y == firstItem.offset.y
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 24.dp)
        ) {
            SectionHeader(
                "アプリ",
                Icons.Default.Apps,
                Modifier.padding(horizontal = 48.dp)
            )
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                itemsIndexed(apps, key = { _, app -> "app_${app.stableId}" }) { index, app ->
                    LauncherAppCard(
                        app = app,
                        onClick = { homeViewModel.launchApp(app) },
                        onManage = { managedApp = app },
                        onFocus = {},
                        cardWidth = 160.dp,
                        cardHeight = 118.dp,
                        bannerWidth = 144.dp,
                        bannerHeight = 72.dp,
                        iconSize = 72.dp,
                        showBorder = false,
                        fullBleedBanner = true,
                        modifier = Modifier
                            .then(
                                if (index == 0) {
                                    Modifier.focusRequester(contentFirstItemRequester)
                                } else {
                                    Modifier
                                }
                            )
                            .focusProperties {
                                if (index == 0) left = FocusRequester.Cancel
                            }
                            .onKeyEvent {
                                if (it.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN &&
                                    it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                                    isFirstGridRow(index)
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

    LaunchedEffect(apps) {
        if (apps.isNotEmpty()) {
            delay(350)
            contentFirstItemRequester.safeRequestFocusWithRetry("AppsTabInitialFocus")
        }
    }

    managedApp?.let { app ->
        val appIndex = apps.indexOfFirst { it.stableId == app.stableId }
        LauncherAppManagementDialog(
            app = app,
            canMoveLeft = appIndex > 0,
            canMoveRight = appIndex in 0 until apps.lastIndex,
            onMoveLeft = { homeViewModel.moveLauncherApp(app, -1) },
            onMoveRight = { homeViewModel.moveLauncherApp(app, 1) },
            onHide = {
                homeViewModel.hideLauncherApp(app)
                managedApp = null
            },
            onShowAll = { homeViewModel.showAllLauncherApps() },
            onDismiss = { managedApp = null }
        )
    }
}
