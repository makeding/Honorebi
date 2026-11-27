@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.setting

import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.home.LocalUpgradeTourActive
import com.beeregg2001.komorebi.ui.home.UpgradeTourTargetId
import com.beeregg2001.komorebi.ui.home.upgradeSpotlightTarget
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

internal data class SettingSidebarRow(
    val category: SettingsCategory,
    val title: String,
    val icon: ImageVector,
)

// サイドバーの表示順。SettingsCategory.SIDEBAR_ORDER に従い、
// isUiGroupChild の項目は直前にグループ見出しを挟んで UI 設定の子として表示する。
internal fun buildSettingSidebarRows(showDebug: Boolean = false): List<SettingSidebarRow> =
    SettingsCategory.SIDEBAR_ORDER
        .filter { it != SettingsCategory.DEBUG || showDebug }
        .map { category ->
            when (category) {
                SettingsCategory.GENERAL -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_GENERAL, Icons.Default.SettingsApplications)
                SettingsCategory.CONNECTION -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_CONNECTION, Icons.Default.Link)
                SettingsCategory.DEEP_TV -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_DEEP_TV, Icons.Default.SettingsInputHdmi)
                SettingsCategory.PLAYBACK -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_PLAYBACK, Icons.Default.PlayCircle)
                SettingsCategory.DATA_BROADCASTING -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_DATA_BROADCASTING, Icons.Default.LiveTv)
                SettingsCategory.COMMENT -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_COMMENT, Icons.Default.Comment)
                SettingsCategory.DISPLAY -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_DISPLAY, Icons.Default.Dashboard)
                SettingsCategory.HOME -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_HOME, Icons.Default.Home)
                SettingsCategory.LAUNCHER -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_LAUNCHER, Icons.Default.Apps)
                SettingsCategory.RECORDING -> SettingSidebarRow(category, "録画設定", Icons.Default.VideoSettings)
                SettingsCategory.EPG -> SettingSidebarRow(category, "番組表設定", Icons.Default.GridOn)
                SettingsCategory.CACHE -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_CACHE, Icons.Default.DeleteSweep)
                SettingsCategory.APP_INFO -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_APP_INFO, Icons.Default.Info)
                SettingsCategory.DEBUG -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_DEBUG, Icons.Default.BugReport)
            }
        }

@Composable
internal fun SettingSidebar(
    controller: SettingNavigationController,
    sidebarRows: List<SettingSidebarRow>,
    sidebarScrollState: ScrollState,
    isUpgradeTourActive: Boolean,
    onCommitCategory: (SettingsCategory) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    val focusRequesters = controller.focusRequesters

    Column(
        modifier = modifier
            .width(260.dp)
            .fillMaxHeight()
            .background(colors.surface1)
            .padding(top = 32.dp, bottom = 32.dp, start = 16.dp, end = 16.dp)
            .onFocusChanged { controller.isSidebarFocused = it.hasFocus }
            .focusProperties { canFocus = !controller.isRestoringFocus && !isUpgradeTourActive },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 24.dp, start = 8.dp),
        ) {
            Icon(
                Icons.Default.Settings,
                null,
                tint = colors.textPrimary,
                modifier = Modifier.size(38.dp),
            )
            Spacer(Modifier.width(14.dp))
            Text(
                AppStrings.SETTINGS_TITLE,
                style = MaterialTheme.typography.headlineMedium,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { clip = false }
                .verticalScroll(sidebarScrollState)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sidebarRows.forEachIndexed { index, row ->
                key(row.category) {
                    if (row.category.isUiGroupChild && (index == 0 || !sidebarRows[index - 1].category.isUiGroupChild)) {
                        Text(
                            text = AppStrings.SETTINGS_CATEGORY_UI,
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp),
                        )
                    }
                    val targetR = focusRequesters.itemRequesters(row.category).firstOrNull()
                        ?: FocusRequester.Default
                    val rowRequester = focusRequesters.categoryRequester(row.category)
                    CategoryItem(
                        title = row.title,
                        icon = row.icon,
                        isSelected = controller.selectedCategory == row.category,
                        onFocused = {
                            controller.onSidebarCategoryFocused(row.category)
                        },
                        onClick = { onCommitCategory(row.category) },
                        enabled = !controller.isRestoringFocus,
                        modifier = Modifier
                            .padding(start = if (row.category.isUiGroupChild) 16.dp else 0.dp)
                            .focusRequester(rowRequester)
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown &&
                                    keyEvent.key == Key.DirectionRight &&
                                    controller.activeCategory != row.category
                                ) {
                                    onCommitCategory(row.category)
                                    true
                                } else {
                                    false
                                }
                            }
                            .focusProperties {
                                left = FocusRequester.Cancel // ★ 修正: 左キーでフォーカスが迷子になるのを防ぐ
                                right = if (controller.activeCategory == row.category) targetR else FocusRequester.Cancel
                                up = if (index == 0) {
                                    FocusRequester.Cancel
                                } else {
                                    focusRequesters.categoryRequester(sidebarRows[index - 1].category)
                                }
                                down = if (index == sidebarRows.lastIndex) {
                                    focusRequesters.homeBackRequester
                                } else {
                                    focusRequesters.categoryRequester(sidebarRows[index + 1].category)
                                }
                            },
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        CategoryItem(
            title = AppStrings.SETTINGS_BACK_TO_HOME,
            icon = Icons.Default.Home,
            isSelected = false,
            onFocused = { },
            onClick = onBack,
            enabled = !controller.isRestoringFocus,
            modifier = Modifier
                .focusRequester(focusRequesters.homeBackRequester)
                .focusProperties {
                    left = FocusRequester.Cancel // ★ 修正: こちらも左キーへの防波堤を追加
                    up = sidebarRows.lastOrNull()?.let { focusRequesters.categoryRequester(it.category) }
                        ?: FocusRequester.Default
                    down = FocusRequester.Cancel
                    right = focusRequesters.itemRequesters(controller.activeCategory)
                        .firstOrNull() ?: FocusRequester.Default
                },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SettingScaffold(
    controller: SettingNavigationController,
    destination: SettingsDestination,
    sidebarRows: List<SettingSidebarRow>,
    mainScrollState: ScrollState,
    sidebarScrollState: ScrollState,
    onCloseDialog: () -> Unit,
    onBack: () -> Unit,
    categoryContent: @Composable ColumnScope.(SettingsCategory) -> Unit,
    dialogsContent: @Composable BoxScope.() -> Unit = {},
) {
    val colors = KomorebiTheme.colors
    val isUpgradeTourActive = LocalUpgradeTourActive.current

    SettingNavigationEffects(
        controller = controller,
        destination = destination,
        mainScrollState = mainScrollState,
    )

    // ダイアログ表示中の戻るキーは「ダイアログを閉じる」に割り当てる。
    // ここで消費しないと MainRoot 側の BackHandler が動いてホームへ戻ってしまう。
    // 個別ダイアログ側が自前で処理する場合はそちらが優先される。
    BackHandler(enabled = controller.isDialogOpen) {
        onCloseDialog()
    }

    val onCommitCategory: (SettingsCategory) -> Unit = { category ->
        controller.commitCategory(category)?.safeRequestFocus("CategoryItem_Click")
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .focusProperties { canFocus = !controller.isDialogOpen }
                .onKeyEvent {
                    if (it.type == KeyEventType.KeyDown &&
                        (it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK ||
                            it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_ESCAPE)
                    ) {
                        when (controller.resolveBackKeyDisposition()) {
                            SettingBackDisposition.RETURN_TO_SIDEBAR -> {
                                controller.focusRequesters
                                    .categoryRequester(controller.selectedCategory)
                                    .safeRequestFocus("Back_To_Sidebar")
                            }
                            SettingBackDisposition.EXIT_SETTINGS -> onBack()
                        }
                        true
                    } else {
                        false
                    }
                },
        ) {
            SettingSidebar(
                controller = controller,
                sidebarRows = sidebarRows,
                sidebarScrollState = sidebarScrollState,
                isUpgradeTourActive = isUpgradeTourActive,
                onCommitCategory = onCommitCategory,
                onBack = onBack,
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(colors.borderSubtle),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 48.dp, horizontal = 64.dp)
                    .upgradeSpotlightTarget(
                        UpgradeTourTargetId.DEEP_TV_SETTING,
                        enabled = controller.activeCategory == SettingsCategory.DEEP_TV,
                    ),
            ) {
                CompositionLocalProvider(
                    LocalBringIntoViewSpec provides if (controller.activeCategory == SettingsCategory.APP_INFO) {
                        AppInfoBringIntoViewSpec
                    } else {
                        LocalBringIntoViewSpec.current
                    },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .focusProperties { canFocus = !isUpgradeTourActive }
                            .graphicsLayer { clip = false }
                            .verticalScroll(mainScrollState)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        categoryContent(controller.activeCategory)
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }

        dialogsContent()
    }
}
