@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.setting

import com.beeregg2001.komorebi.data.PlayerClockMode
import com.beeregg2001.komorebi.data.StartupFocusTarget
import com.beeregg2001.komorebi.data.TifNoSignalStyle
import com.beeregg2001.komorebi.data.repository.tif.TifInput

import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.ui.components.LocalShowToast
import com.beeregg2001.komorebi.ui.home.LocalUpgradeTourActive
import com.beeregg2001.komorebi.ui.home.UpgradeTourTargetId
import com.beeregg2001.komorebi.ui.home.upgradeSpotlightTarget
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.beeregg2001.komorebi.data.model.DeviceAuthRequest

internal val TIME_FORMAT_OPTIONS = listOf(
    "28時間表記 (24:00–27:59)" to "28H",
    "24時間表記" to "24H",
    "12時間表記 (AM/PM)" to "12H"
)

internal const val SETTING_CATEGORY_COALESCE_MS = 200L

private data class SettingSidebarRow(
    val category: SettingsCategory,
    val title: String,
    val icon: ImageVector,
)

@OptIn(ExperimentalFoundationApi::class)
private object AppInfoBringIntoViewSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        when {
            offset < 0f -> offset
            offset + size > containerSize -> offset + size - containerSize
            else -> 0f
        }
}

@Composable
internal fun SettingsInitialFocusEffect(
    onRequestInitialFocus: suspend () -> Unit,
) {
    val isUpgradeTourActive = LocalUpgradeTourActive.current
    val currentOnRequestInitialFocus by rememberUpdatedState(onRequestInitialFocus)
    var hasAppliedInitialFocus by remember { mutableStateOf(false) }
    LaunchedEffect(isUpgradeTourActive) {
        if (isUpgradeTourActive || hasAppliedInitialFocus) return@LaunchedEffect
        hasAppliedInitialFocus = true
        currentOnRequestInitialFocus()
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onClearLastChannel: () -> Unit = {},
    onClearWatchHistory: () -> Unit = {},
    destination: SettingsDestination = SettingsDestination.Default,
    onOpenWhatsNew: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    channelViewModel: ChannelViewModel = hiltViewModel()
) {
    SettingsMenuScreen(
        onBack = onBack,
        onClearLastChannel = onClearLastChannel,
        onClearWatchHistory = onClearWatchHistory,
        destination = destination,
        onOpenWhatsNew = onOpenWhatsNew,
        viewModel = viewModel,
        channelViewModel = channelViewModel,
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SettingsMenuScreen(
    onBack: () -> Unit,
    onClearLastChannel: () -> Unit = {},
    onClearWatchHistory: () -> Unit = {},
    destination: SettingsDestination = SettingsDestination.Default,
    onOpenWhatsNew: () -> Unit = {},
    viewModel: SettingsViewModel,
    channelViewModel: ChannelViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context) }
    val homeEnvironment = remember(context) { LauncherEnvironmentDetector.detect(context) }
    var isDefaultHome by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val isUpgradeTourActive = LocalUpgradeTourActive.current
    val showToast = LocalShowToast.current

    val prefs = rememberSettingPreferences(repository)
    val themeSeason = when (prefs.currentThemeName) {
                                "SPRING", "SPRING_LIGHT" -> "SPRING"; "SUMMER", "SUMMER_LIGHT" -> "SUMMER"; "AUTUMN", "AUTUMN_LIGHT" -> "AUTUMN"; "WINTER_DARK", "WINTER_LIGHT" -> "WINTER"; "EPG_BLUE", "EPG_BLUE_LIGHT" -> "BLUE"; "KOMOREBI", "KOMOREBI_DAY", "KOMOREBI_NIGHT" -> "KOMOREBI"; "KYLE", "KYLE_DAY", "KYLE_NIGHT" -> "KYLE"; else -> "DEFAULT"
                            }
    val isLightTheme = prefs.currentThemeName.contains("LIGHT") || prefs.currentThemeName == "HIGHTONE"

    val uiState = rememberSettingUiState(destination.category)

    val receiveBetaUpdates by viewModel.receiveBetaUpdates.collectAsState()
    val playerUiMode by viewModel.playerUiMode.collectAsState()
    val autoCmSkip by viewModel.autoCmSkip.collectAsState()
    val recordedSeekStepSeconds by viewModel.recordedSeekStepSeconds.collectAsState()
    val seekStepFocusRequester = remember { FocusRequester() }
    val preferOriginalMpegTs by viewModel.preferOriginalMpegTs.collectAsState()
    val availableQualities by viewModel.availableQualities.collectAsState()
    val honomiSession by viewModel.honomiSession.collectAsState()
    val honomiLoginInProgress by viewModel.honomiLoginInProgress.collectAsState()
    val screensaverEnabled by viewModel.screensaverEnabled.collectAsState()
    val screensaverShowUpcoming by viewModel.screensaverShowUpcoming.collectAsState()
    val screensaverUpcomingR = remember { FocusRequester() }
    val screensaverTimeout by viewModel.screensaverTimeoutMinutes.collectAsState()
    val screensaverPlayerTimeout by viewModel.screensaverPlayerTimeoutMinutes.collectAsState()
    val screensaverPlayerStyle by viewModel.screensaverPlayerStyle.collectAsState()
    val playerEndSleepMinutes by viewModel.playerEndSleepMinutes.collectAsState()
    val screensaverInterval by viewModel.screensaverIntervalSeconds.collectAsState()
    val screensaverTransition by viewModel.screensaverTransition.collectAsState()
    val screensaverImages by viewModel.screensaverImageUris.collectAsState()
    val screensaverWebUploadR = remember { FocusRequester() }
    val screensaverGalleryR = remember { FocusRequester() }
    val screensaverPlayerPreviewR = remember { FocusRequester() }
    var showScreensaverGallery by remember { mutableStateOf(false) }
    var screensaverPreview by remember { mutableStateOf(false) }
    var screensaverPlayerPreview by remember { mutableStateOf(false) }
    var showInternalImagePicker by remember { mutableStateOf(false) }
    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val flatChannels = remember(groupedChannels) { groupedChannels.values.flatten() }

    var honomiLoginError by remember { mutableStateOf<String?>(null) }
    var honomiPairing by remember { mutableStateOf<DeviceAuthRequest?>(null) }

    // サイドバーの表示順。SettingsCategory.SIDEBAR_ORDER に従い、
    // isUiGroupChild の項目は直前にグループ見出しを挟んで UI 設定の子として表示する。
    val sidebarRows = remember {
        SettingsCategory.SIDEBAR_ORDER.map { category ->
            when (category) {
                SettingsCategory.GENERAL -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_GENERAL, Icons.Default.SettingsApplications)
                SettingsCategory.CONNECTION -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_CONNECTION, Icons.Default.Link)
                SettingsCategory.DEEP_TV -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_DEEP_TV, Icons.Default.SettingsInputHdmi)
                SettingsCategory.PLAYBACK -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_PLAYBACK, Icons.Default.PlayCircle)
                SettingsCategory.COMMENT -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_COMMENT, Icons.Default.Comment)
                SettingsCategory.DISPLAY -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_DISPLAY, Icons.Default.Dashboard)
                SettingsCategory.HOME -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_HOME, Icons.Default.Home)
                SettingsCategory.LAUNCHER -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_LAUNCHER, Icons.Default.Apps)
                SettingsCategory.RECORDING -> SettingSidebarRow(category, "録画設定", Icons.Default.VideoSettings)
                SettingsCategory.EPG -> SettingSidebarRow(category, "番組表設定", Icons.Default.GridOn)
                SettingsCategory.CACHE -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_CACHE, Icons.Default.DeleteSweep)
                SettingsCategory.APP_INFO -> SettingSidebarRow(category, AppStrings.SETTINGS_CATEGORY_APP_INFO, Icons.Default.Info)
            }
        }
    }
    val sidebarRowRequesters = remember { List(sidebarRows.size) { FocusRequester() } }
    val categoryFocusRequesters: Map<SettingsCategory, FocusRequester> = remember(sidebarRows) {
        sidebarRows.mapIndexed { i, row -> row.category to sidebarRowRequesters[i] }.toMap()
    }
    val playerClockModeRequester = remember { FocusRequester() }
    val tifSupported by produceState(false, context) {
        value = withContext(Dispatchers.IO) { TifInput.isSupported(context) }
    }
    val pipSupported by produceState(false, context) {
        value = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    val homeBackRequester = remember { FocusRequester() }

    val batchItemRs =
        remember(prefs.postRecordingBatchList) { List(prefs.postRecordingBatchList.size) { FocusRequester() } }
    val smbItemRs =
        remember(prefs.smbServerList) { List(prefs.smbServerList.size) { FocusRequester() } }

    // カテゴリごとの項目フォーカスリクエスタ
    val itemFocusRequesters: Map<SettingsCategory, List<FocusRequester>> = remember {
        mapOf(
            SettingsCategory.GENERAL to List(8) { FocusRequester() },
            SettingsCategory.CONNECTION to List(ConnectionItemIndex.COUNT) { FocusRequester() },
            SettingsCategory.PLAYBACK to List(12) { FocusRequester() },
            SettingsCategory.RECORDING to List(2) { FocusRequester() },
            SettingsCategory.EPG to List(4) { FocusRequester() },
            SettingsCategory.COMMENT to List(CommentItemIndex.COUNT) { FocusRequester() },
            SettingsCategory.HOME to List(3) { FocusRequester() },
            SettingsCategory.LAUNCHER to List(4) { FocusRequester() },
            SettingsCategory.DISPLAY to List(13) { FocusRequester() },
            SettingsCategory.DEEP_TV to List(DeepTvItemIndex.COUNT) { FocusRequester() },
            SettingsCategory.CACHE to List(7) { FocusRequester() },
            SettingsCategory.APP_INFO to List(AppInfoItemIndex.COUNT) { FocusRequester() },
        )
    }

    fun focusTargetRequester(target: SettingsFocusTarget): FocusRequester = when (target) {
        SettingsFocusTarget.CONNECTION_SETUP ->
            itemFocusRequesters.getValue(SettingsCategory.CONNECTION)[ConnectionItemIndex.WEB_SETUP]
        SettingsFocusTarget.COMMENT_ENGINE ->
            itemFocusRequesters.getValue(SettingsCategory.COMMENT)[CommentItemIndex.COMMENT_ENGINE]
        SettingsFocusTarget.APP_INFO_VERSION ->
            itemFocusRequesters.getValue(SettingsCategory.APP_INFO)[AppInfoItemIndex.VERSION]
        SettingsFocusTarget.APP_INFO_DEVICE_CAPABILITIES ->
            itemFocusRequesters.getValue(SettingsCategory.APP_INFO)[AppInfoItemIndex.DEVICE_CAPABILITIES]
    }

    val mainScrollState = rememberScrollState()
    val sidebarScrollState = rememberScrollState()
    var activeCategory by remember(destination.category) { mutableStateOf(destination.category) }
    var pendingContentFocusCategory by remember { mutableStateOf<SettingsCategory?>(null) }

    // Coalesce rapid D-pad scrubs on the settings sidebar before swapping the right-hand content tree.
    LaunchedEffect(uiState.selectedCategory, isUpgradeTourActive) {
        if (activeCategory != uiState.selectedCategory) {
            if (!isUpgradeTourActive) {
                delay(SETTING_CATEGORY_COALESCE_MS)
            }
            activeCategory = uiState.selectedCategory
        }
    }

    val commitAndFocusCategory: (SettingsCategory) -> Unit = { category ->
        uiState.selectedCategory = category
        if (activeCategory == category) {
            pendingContentFocusCategory = null
            itemFocusRequesters[category]?.firstOrNull()
                ?.safeRequestFocus("CategoryItem_Click")
        } else {
            activeCategory = category
            pendingContentFocusCategory = category
        }
    }

    LaunchedEffect(activeCategory, pendingContentFocusCategory) {
        if (pendingContentFocusCategory == activeCategory) {
            androidx.compose.runtime.withFrameNanos { }
            itemFocusRequesters[activeCategory]?.firstOrNull()
                ?.safeRequestFocusWithRetry(
                    tag = "SettingScreen_CommitCategory",
                    maxRetries = 4,
                    delayMillis = 40L
                )
            pendingContentFocusCategory = null
        }
    }

    LaunchedEffect(activeCategory, isUpgradeTourActive) {
        if (isUpgradeTourActive) {
            if (activeCategory == SettingsCategory.COMMENT) {
                androidx.compose.runtime.withFrameNanos { }
                mainScrollState.scrollTo(mainScrollState.maxValue)
            } else {
                mainScrollState.scrollTo(0)
            }
        } else if (destination !is SettingsDestination.Item || activeCategory != destination.category) {
            mainScrollState.scrollTo(0)
        }
    }

    SettingsInitialFocusEffect {
        if (destination is SettingsDestination.DeviceCapabilities) return@SettingsInitialFocusEffect
        delay(400)

        if (destination is SettingsDestination.Item) {
            uiState.isSidebarFocused = false
            val targetRequester = focusTargetRequester(destination.target)

            var success = false
            for (i in 0..5) {
                try {
                    targetRequester.requestFocus()
                    success = true
                    break
                } catch (e: Exception) {
                    delay(150)
                }
            }

            if (!success) {
                categoryFocusRequesters[destination.category]
                    ?.safeRequestFocus("Settings_Fallback")
            }
        } else {
            categoryFocusRequesters[uiState.selectedCategory]
                ?.safeRequestFocus("Settings_Initial")
        }
    }

    val closeDialog = {
        uiState.isRestoringFocus = true
        uiState.activeDialog = SettingDialogState.None
        scope.launch {
            delay(300)
            val restored = uiState.restoreFocusRequester
                ?.safeRequestFocusWithRetry("SettingScreen_Restore", maxRetries = 3, delayMillis = 50)
                ?: false
            delay(100)
            uiState.isRestoringFocus = false
            if (!restored) {
                delay(50)
                categoryFocusRequesters[uiState.selectedCategory]
                    ?.safeRequestFocusWithRetry("SettingScreen_Restore_Fallback")
            }
        }
    }

    LaunchedEffect(destination) {
        if (destination is SettingsDestination.DeviceCapabilities) {
            uiState.selectedCategory = SettingsCategory.APP_INFO
            activeCategory = SettingsCategory.APP_INFO
            uiState.restoreFocusRequester = focusTargetRequester(SettingsFocusTarget.APP_INFO_DEVICE_CAPABILITIES)
            uiState.restoreCategory = SettingsCategory.APP_INFO
            uiState.activeDialog = SettingDialogState.DeviceCapabilities
        }
    }

    val openCommentLayerDialog = {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.DIALOG_LAYER_ORDER_TITLE,
                                    listOf(
                                        AppStrings.DIALOG_LAYER_COMMENT_TOP to "CommentOnTop",
                                        AppStrings.DIALOG_LAYER_SUBTITLE_TOP to "SubtitleOnTop"
                                    ),
                                    prefs.subtitleCommentLayer
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.SUBTITLE_COMMENT_LAYER,
                                            it
                                        )
                                    }
                                }
    }

    val openCommentEngineDialog = {
        uiState.activeDialog = SettingDialogState.Selection(
            AppStrings.DIALOG_COMMENT_ENGINE_TITLE,
            listOf(
                AppStrings.DIALOG_COMMENT_ENGINE_DFM_HARDWARE to "DFM_HARDWARE",
                AppStrings.DIALOG_COMMENT_ENGINE_GLES to "GLES",
                AppStrings.DIALOG_COMMENT_ENGINE_DFM to "DFM"
            ),
            com.beeregg2001.komorebi.ui.player.DanmakuEngineMode.fromId(prefs.commentEngine).id
        ) {
            scope.launch {
                repository.saveString(
                    SettingsRepository.COMMENT_ENGINE,
                    it
                )
            }
        }
    }

    val isDialogOpen = uiState.activeDialog !is SettingDialogState.None || showInternalImagePicker || showScreensaverGallery || screensaverPreview || screensaverPlayerPreview

    // ダイアログ表示中の戻るキーは「ダイアログを閉じる」に割り当てる。
    // ここで消費しないと MainRoot 側の BackHandler が動いてホームへ戻ってしまう。
    // 個別ダイアログ側が自前で処理する場合はそちらが優先される。
    BackHandler(enabled = isDialogOpen) {
        closeDialog()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .focusProperties { canFocus = !isDialogOpen }
                .onKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK || it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_ESCAPE)) {
                        if (!uiState.isSidebarFocused) {
                            categoryFocusRequesters[uiState.selectedCategory]
                                ?.safeRequestFocus("Back_To_Sidebar")
                        } else {
                            onBack()
                        }
                        true
                    } else false
                }
        ) {
            Column(
                modifier = Modifier
                    .width(260.dp)
                    .fillMaxHeight()
                    .background(colors.surface1)
                    .padding(top = 32.dp, bottom = 32.dp, start = 16.dp, end = 16.dp)
                    .onFocusChanged { uiState.isSidebarFocused = it.hasFocus }
                    .focusProperties { canFocus = !uiState.isRestoringFocus && !isUpgradeTourActive }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 24.dp, start = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        null,
                        tint = colors.textPrimary,
                        modifier = Modifier.size(38.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        AppStrings.SETTINGS_TITLE,
                        style = MaterialTheme.typography.headlineMedium,
                        fontSize = 28.sp,
                        lineHeight = 36.sp,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer { clip = false }
                        .verticalScroll(sidebarScrollState)
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sidebarRows.forEachIndexed { index, row ->
                        key(row.category) {
                            if (row.category.isUiGroupChild && (index == 0 || !sidebarRows[index - 1].category.isUiGroupChild)) {
                                Text(
                                    text = AppStrings.SETTINGS_CATEGORY_UI,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.textSecondary,
                                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp)
                                )
                            }
                            val targetR = itemFocusRequesters[row.category]?.firstOrNull()
                                ?: FocusRequester.Default
                            CategoryItem(
                                title = row.title,
                                icon = row.icon,
                                isSelected = uiState.selectedCategory == row.category,
                                onFocused = {
                                    uiState.selectedCategory = row.category
                                },
                                onClick = { commitAndFocusCategory(row.category) },
                                enabled = !uiState.isRestoringFocus,
                                modifier = Modifier
                                    .padding(start = if (row.category.isUiGroupChild) 16.dp else 0.dp)
                                    .focusRequester(sidebarRowRequesters[index])
                                    .onPreviewKeyEvent { keyEvent ->
                                        if (keyEvent.type == KeyEventType.KeyDown &&
                                            keyEvent.key == Key.DirectionRight &&
                                            activeCategory != row.category
                                        ) {
                                            commitAndFocusCategory(row.category)
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    .focusProperties {
                                        left = FocusRequester.Cancel // ★ 修正: 左キーでフォーカスが迷子になるのを防ぐ
                                        right = if (activeCategory == row.category) targetR else FocusRequester.Cancel
                                        up = if (index == 0) FocusRequester.Cancel else sidebarRowRequesters[index - 1]
                                        down = if (index == sidebarRows.lastIndex) homeBackRequester else sidebarRowRequesters[index + 1]
                                    }
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
                    enabled = !uiState.isRestoringFocus,
                    modifier = Modifier
                        .focusRequester(homeBackRequester)
                        .focusProperties {
                            left = FocusRequester.Cancel // ★ 修正: こちらも左キーへの防波堤を追加
                            up = sidebarRowRequesters.lastOrNull() ?: FocusRequester.Default
                            down = FocusRequester.Cancel
                            right = itemFocusRequesters[activeCategory]
                                ?.firstOrNull() ?: FocusRequester.Default
                        }
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(colors.borderSubtle)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 48.dp, horizontal = 64.dp)
                    .upgradeSpotlightTarget(
                        UpgradeTourTargetId.DEEP_TV_SETTING,
                        enabled = activeCategory == SettingsCategory.DEEP_TV,
                    )
            ) {
                CompositionLocalProvider(
                    LocalBringIntoViewSpec provides if (activeCategory == SettingsCategory.APP_INFO) {
                        AppInfoBringIntoViewSpec
                    } else {
                        LocalBringIntoViewSpec.current
                    }
                ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .focusProperties { canFocus = !isUpgradeTourActive }
                        .graphicsLayer { clip = false }
                        .verticalScroll(mainScrollState)
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                ) {
                    when (activeCategory) {
                        SettingsCategory.GENERAL -> GeneralSettingsContent(
                            prefs.startupTab,
                            prefs.startupFocusTarget.label,
                            when (prefs.startupChannel) {
                                "OFF" -> AppStrings.SETTINGS_VALUE_STARTUP_OFF
                                "LAST_WATCHED" -> AppStrings.SETTINGS_VALUE_STARTUP_LAST
                                else -> flatChannels.find { it.id == prefs.startupChannel }?.name
                                    ?: prefs.startupChannel
                            },
                            itemFocusRequesters.getValue(SettingsCategory.GENERAL)[0],
                            itemFocusRequesters.getValue(SettingsCategory.GENERAL)[1],
                            itemFocusRequesters.getValue(SettingsCategory.GENERAL)[2],
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_STARTUP_TAB,
                                    listOf(
                                        "ホーム" to "ホーム",
                                        "ライブ" to "ライブ",
                                        "アプリ" to "アプリ",
                                        "放送中" to "放送中",
                                        "ビデオ" to "ビデオ",
                                        "番組表" to "番組表",
                                        "録画予約" to "録画予約"
                                    ),
                                    prefs.startupTab
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.STARTUP_TAB,
                                            it
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_STARTUP_FOCUS_TARGET,
                                    StartupFocusTarget.entries.map { it.label to it.name },
                                    prefs.startupFocusTarget.name
                                ) { viewModel.updateStartupFocusTarget(it) }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.DIALOG_STARTUP_CHANNEL_TITLE,
                                    (listOf(
                                        AppStrings.SETTINGS_VALUE_STARTUP_OFF to "OFF",
                                        AppStrings.SETTINGS_VALUE_STARTUP_LAST to "LAST_WATCHED"
                                    ) + flatChannels.map { it.name to it.id }),
                                    prefs.startupChannel
                                ) { viewModel.updateStartupChannel(it) }
                            },
                            viewModel.nhkExclusionState.collectAsState().value.mode.displayLabel,
                            itemFocusRequesters.getValue(SettingsCategory.GENERAL)[3],
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "N〇K除外モード",
                                    com.beeregg2001.komorebi.data.model.NHKExclusionMode.entries.map { it.displayLabel to it.name },
                                    viewModel.nhkExclusionState.value.mode.name,
                                ) { viewModel.setNHKExclusionMode(com.beeregg2001.komorebi.data.model.NHKExclusionMode.fromPreference(it)) }
                            },
                            prefs.lateNightWatchMode,
                            itemFocusRequesters.getValue(SettingsCategory.GENERAL)[4],
                            {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.LATE_NIGHT_WATCH_MODE,
                                        if (prefs.lateNightWatchMode == "ON") "OFF" else "ON"
                                    )
                                }
                            },
                            prefs.spoilerBlurEnabled,
                            itemFocusRequesters.getValue(SettingsCategory.GENERAL)[5],
                            {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.SPOILER_BLUR_ENABLED,
                                        if (prefs.spoilerBlurEnabled == "ON") "OFF" else "ON"
                                    )
                                }
                            },
                            prefs.jikkyoFestivalAlert,
                            itemFocusRequesters.getValue(SettingsCategory.GENERAL)[6],
                            {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.JIKKYO_FESTIVAL_ALERT,
                                        if (prefs.jikkyoFestivalAlert == "ON") "OFF" else "ON"
                                    )
                                }
                            },
                            prefs.cmPanicZapEnabled,
                            itemFocusRequesters.getValue(SettingsCategory.GENERAL)[7],
                            {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.CM_PANIC_ZAP_ENABLED,
                                        if (prefs.cmPanicZapEnabled == "ON") "OFF" else "ON"
                                    )
                                }
                            },
                            categoryFocusRequesters.getValue(SettingsCategory.GENERAL),
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategory = SettingsCategory.GENERAL }

                        SettingsCategory.CONNECTION -> ConnectionSettingsContent(
                            prefs = prefs,
                            honomiUserName = honomiSession?.userName,
                            onHonomiAccountClick = {
                                if (honomiSession == null) {
                                    honomiLoginError = null
                                    honomiPairing = null
                                    uiState.activeDialog = SettingDialogState.HonomiLogin
                                } else uiState.activeDialog = SettingDialogState.HonomiLogout
                            },
                            onAddSmbServer = { uiState.activeDialog = SettingDialogState.SmbSetup(null) },
                            onSmbServerClick = { uiState.activeDialog = SettingDialogState.SmbAction(it) },
                            smbItemRs = smbItemRs,
                            onSelectEdcbPlayMethod = {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "録画ファイルの再生方式",
                                    listOf("API経由 (api/xcode)" to "API", "直接アクセス (高速シーク可)" to "DIRECT"),
                                    prefs.edcbRecordPlayMethod,
                                ) { viewModel.updateEdcbRecordPlayMethod(it) }
                            },
                            onWebSetup = { uiState.activeDialog = SettingDialogState.ConnectionSetup(it?.backend) },
                            onEdit = { title, value ->
                                uiState.activeDialog = SettingDialogState.Input(title, value) { input ->
                                    when (title) {
                                        "KonomiTV (IPアドレス)" -> viewModel.updateKonomiIp(input)
                                        "KonomiTV (ポート)" -> viewModel.updateKonomiPort(input)
                                        "EDCB (IPアドレス)" -> viewModel.updateEdcbIp(input)
                                        "EDCB (TCPポート)" -> viewModel.updateEdcbPort(input)
                                        "Mirakurun (IPアドレス)" -> viewModel.updateMirakurunIp(input)
                                        else -> {
                                            val key = when (title) {
                                                "EDCB (HTTP/HTTPSポート)" -> SettingsRepository.EDCB_HTTP_PORT
                                                "Mirakurun (ポート)" -> SettingsRepository.MIRAKURUN_PORT
                                                else -> error("Unknown connection field: $title")
                                            }
                                            viewModel.updateConnectionField(key, input)
                                        }
                                    }
                                }
                            },
                            onSelectBackend = { sources ->
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "メインシステム",
                                    sources.map { it.label to it.backend },
                                    prefs.backendType,
                                ) { viewModel.updateBackendType(it) }
                            },
                            onRefreshTif = { channelViewModel.fetchChannels() },
                            onSelectSrc = {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_PREFERRED_SOURCE,
                                    listOf(
                                        "メインシステムに従う（トランスコード）" to "KONOMITV",
                                        "Mirakurun を優先" to "MIRAKURUN",
                                        "EDCB (ダイレクトストリーミング)" to "EDCB",
                                    ),
                                    prefs.preferredSource,
                                ) { value -> scope.launch { repository.saveString(SettingsRepository.PREFERRED_STREAM_SOURCE, value) } }
                            },
                            onEditCloudflareAccess = {
                                uiState.activeDialog = SettingDialogState.CloudflareAccess(
                                    prefs.cfAccessClientId, prefs.cfAccessClientSecret,
                                ) { id, secret -> repository.saveCloudflareAccessCredentials(id, secret) }
                            },
                            itemRs = itemFocusRequesters.getValue(SettingsCategory.CONNECTION),
                            sidebarR = categoryFocusRequesters.getValue(SettingsCategory.CONNECTION),
                            scrollState = mainScrollState,
                            isDialogOpen = isDialogOpen,
                            onClick = { uiState.restoreFocusRequester = it; uiState.restoreCategory = SettingsCategory.CONNECTION },
                            tifIntegrationEnabled = prefs.tifIntegrationEnabled,
                        )

                        SettingsCategory.DEEP_TV -> DeepTvIntegrationSettingsContent(
                            supported = tifSupported,
                            enabled = prefs.tifIntegrationEnabled,
                            lockedByMainSystem = prefs.backendType == "TIF",
                            hideEmptyInputs = prefs.tifHideEmptyInputs,
                            showTunerInputs = prefs.tifShowTunerInputs,
                            noSignalStyle = prefs.tifNoSignalStyle,
                            features = prefs.deepTvFeatures,
                            pipSupported = pipSupported,
                            itemRs = itemFocusRequesters.getValue(SettingsCategory.DEEP_TV),
                            sidebarR = categoryFocusRequesters.getValue(SettingsCategory.DEEP_TV),
                            onToggleFeature = { viewModel.toggleDeepTvFeature(it) },
                            onCycleBackgroundPlaybackMode = {
                                viewModel.setBackgroundPlaybackMode(
                                    prefs.deepTvFeatures.backgroundPlaybackMode.next(pipSupported)
                                )
                            },
                            onToggleEnabled = {
                                if (!tifSupported) {
                                    showToast("この端末はTV入力（TIF）に対応していません")
                                } else {
                                    viewModel.toggleTifIntegration()
                                }
                            },
                            onToggleHideEmpty = { viewModel.toggleTifHideEmptyInputs() },
                            onToggleShowTuners = {
                                scope.launch { repository.saveBoolean(SettingsRepository.TIF_SHOW_TUNER_INPUTS, !prefs.tifShowTunerInputs) }
                            },
                            onEditNoSignalStyle = {
                                uiState.activeDialog = SettingDialogState.NoSignalStylePreview
                            },
                            onRefreshTif = { channelViewModel.fetchChannels() },
                            onClick = { uiState.restoreFocusRequester = it; uiState.restoreCategory = SettingsCategory.DEEP_TV },
                        )

                        SettingsCategory.PLAYBACK -> PlaybackSettingsContent(
                            prefs.liveQuality,
                            prefs.videoQuality,
                            prefs.liveSubtitleDefault,
                            prefs.videoSubtitleDefault,
                            prefs.subtitleFont,
                            prefs.audioOutputMode,
                            playerUiMode,
                            autoCmSkip,
                            recordedSeekStepSeconds,
                            preferOriginalMpegTs,
                            prefs.labAllowMirakurunDual,
                            availableQualities,
                            itemFocusRequesters.getValue(SettingsCategory.PLAYBACK)[0],
                            itemFocusRequesters.getValue(SettingsCategory.PLAYBACK)[1],
                            itemFocusRequesters.getValue(SettingsCategory.PLAYBACK)[2],
                            itemFocusRequesters.getValue(SettingsCategory.PLAYBACK)[3],
                            itemFocusRequesters.getValue(SettingsCategory.PLAYBACK)[10],
                            itemFocusRequesters.getValue(SettingsCategory.PLAYBACK)[4],
                            itemFocusRequesters.getValue(SettingsCategory.PLAYBACK)[6],
                            itemFocusRequesters.getValue(SettingsCategory.PLAYBACK)[7],
                            seekStepFocusRequester,
                            itemFocusRequesters.getValue(SettingsCategory.PLAYBACK)[8],
                            itemFocusRequesters.getValue(SettingsCategory.PLAYBACK)[11],
                            categoryFocusRequesters.getValue(SettingsCategory.PLAYBACK),
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.DIALOG_QUALITY_TITLE,
                                    availableQualities.map { it.label to it.value },
                                    if (availableQualities.any { it.value == prefs.liveQuality }) prefs.liveQuality else availableQualities.firstOrNull()?.value
                                        ?: ""
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.LIVE_QUALITY,
                                            it
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.DIALOG_QUALITY_TITLE,
                                    availableQualities.map { it.label to it.value },
                                    if (availableQualities.any { it.value == prefs.videoQuality }) prefs.videoQuality else availableQualities.firstOrNull()?.value
                                        ?: ""
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.VIDEO_QUALITY,
                                            it
                                        )
                                    }
                                }
                            },
                            {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.LIVE_SUBTITLE_DEFAULT,
                                        if (prefs.liveSubtitleDefault == "ON") "OFF" else "ON"
                                    )
                                }
                            },
                            {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.VIDEO_SUBTITLE_DEFAULT,
                                        if (prefs.videoSubtitleDefault == "ON") "OFF" else "ON"
                                    )
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.DIALOG_SUBTITLE_FONT_TITLE,
                                    listOf(
                                        AppStrings.SETTINGS_VALUE_FONT_DEFAULT to "default",
                                        AppStrings.SETTINGS_VALUE_FONT_ARIB to "arib"
                                    ),
                                    prefs.subtitleFont
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.SUBTITLE_FONT,
                                            it
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.DIALOG_AUDIO_OUTPUT_TITLE,
                                    listOf(
                                        AppStrings.SETTINGS_VALUE_AUDIO_DOWNMIX_DESC to "DOWNMIX",
                                        AppStrings.SETTINGS_VALUE_AUDIO_PASSTHROUGH_DESC to "PASSTHROUGH"
                                    ),
                                    prefs.audioOutputMode
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.AUDIO_OUTPUT_MODE,
                                            it
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "プレイヤーUIモード",
                                    listOf(
                                        "モダン (オンスクリーン操作)" to "MODERN",
                                        "クラシック (D-Pad完結)" to "CLASSIC"
                                    ),
                                    playerUiMode
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.PLAYER_UI_MODE,
                                            it
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "CMスキップ",
                                    listOf(
                                        "オフ" to "OFF",
                                        "手動（CM開始から10秒間、決定ボタンでスキップ）" to "MANUAL",
                                        "自動" to "AUTO"
                                    ),
                                    autoCmSkip
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.AUTO_CM_SKIP,
                                            it
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "早送り・巻き戻し（1回）",
                                    listOf(
                                        "5秒" to "5",
                                        "10秒" to "10",
                                        "15秒" to "15",
                                        "30秒" to "30"
                                    ),
                                    recordedSeekStepSeconds.toString()
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.RECORDED_SEEK_STEP_SECONDS,
                                            it
                                        )
                                    }
                                }
                            },
                            {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.PREFER_ORIGINAL_MPEG_TS,
                                        if (preferOriginalMpegTs == "ON") "OFF" else "ON"
                                    )
                                }
                            },
                            {
                                if (prefs.labAllowMirakurunDual == "OFF") {
                                    uiState.activeDialog = SettingDialogState.ConfirmClear(
                                        "【警告】ハードウェア負荷について",
                                        "Mirakurunソースでの2画面再生やPiPモードは、端末に極めて高い負荷をかけます。よろしいですか？"
                                    ) {
                                        scope.launch {
                                            repository.saveString(SettingsRepository.LAB_ALLOW_MIRAKURUN_DUAL, "ON")
                                        }
                                    }
                                } else {
                                    scope.launch {
                                        repository.saveString(SettingsRepository.LAB_ALLOW_MIRAKURUN_DUAL, "OFF")
                                    }
                                }
                            }
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategory = SettingsCategory.PLAYBACK }

                        SettingsCategory.RECORDING -> RecordingSettingsContent(
                            prefs.defaultRecordListView,
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_DEFAULT_RECORD_VIEW,
                                    listOf(
                                        AppStrings.SETTINGS_VALUE_VIEW_LIST to "LIST",
                                        AppStrings.SETTINGS_VALUE_VIEW_GRID to "GRID"
                                    ),
                                    prefs.defaultRecordListView
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.DEFAULT_RECORD_LIST_VIEW,
                                            it
                                        )
                                    }
                                }
                            },
                            prefs.postRecordingBatchList,
                            {
                                uiState.activeDialog = SettingDialogState.BatchInput { n, p ->
                                    viewModel.addPostRecordingBatch(
                                        n,
                                        p
                                    )
                                }
                            },
                            { b ->
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    "バッチの削除",
                                    "「${b.name}」を削除しますか？"
                                ) { viewModel.deletePostRecordingBatch(b) }
                            },
                            itemFocusRequesters.getValue(SettingsCategory.RECORDING)[0],
                            itemFocusRequesters.getValue(SettingsCategory.RECORDING)[1],
                            batchItemRs,
                            categoryFocusRequesters.getValue(SettingsCategory.RECORDING)
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategory = SettingsCategory.RECORDING }

                        SettingsCategory.HOME -> HomeDisplaySettingsContent(
                            prefs.pickupGenre,
                            prefs.pickupTime,
                            prefs.excludePaid,
                            prefs.backendType != "TIF",
                            itemFocusRequesters.getValue(SettingsCategory.HOME)[0],
                            itemFocusRequesters.getValue(SettingsCategory.HOME)[1],
                            itemFocusRequesters.getValue(SettingsCategory.HOME)[2],
                            categoryFocusRequesters.getValue(SettingsCategory.HOME),
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.DIALOG_PICKUP_GENRE_TITLE,
                                    listOf(
                                        "アニメ" to "アニメ",
                                        "映画" to "映画",
                                        "ドラマ" to "ドラマ",
                                        "スポーツ" to "スポーツ",
                                        "音楽" to "音楽",
                                        "バラエティ" to "バラエティ",
                                        "ドキュメンタリー" to "ドキュメンタリー"
                                    ),
                                    prefs.pickupGenre
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.HOME_PICKUP_GENRE,
                                            it
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.DIALOG_PICKUP_TIME_TITLE,
                                    listOf(
                                        "自動" to "自動",
                                        "朝" to "朝",
                                        "昼" to "昼",
                                        "夜" to "夜"
                                    ),
                                    prefs.pickupTime
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.HOME_PICKUP_TIME,
                                            it
                                        )
                                    }
                                }
                            },
                            {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.EXCLUDE_PAID_BROADCASTS,
                                        if (prefs.excludePaid == "ON") "OFF" else "ON"
                                    )
                                }
                            },
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategory = SettingsCategory.HOME }

                        SettingsCategory.LAUNCHER -> LauncherSettingsContent(
                            showAppsTab = !prefs.hideAppsTab,
                            appColumns = prefs.launcherAppColumns,
                            showAppLabels = !prefs.hideLauncherAppLabels,
                            environment = homeEnvironment,
                            isDefaultHome = isDefaultHome,
                            itemRs = itemFocusRequesters.getValue(SettingsCategory.LAUNCHER),
                            sidebarR = categoryFocusRequesters.getValue(SettingsCategory.LAUNCHER),
                            onToggleAppsTab = { viewModel.toggleHideAppsTab() },
                            onEditColumns = {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_LAUNCHER_APP_COLUMNS,
                                    listOf(
                                        AppStrings.SETTINGS_VALUE_LAUNCHER_COLUMNS_AUTO to "AUTO",
                                        "4列" to "4",
                                        "5列" to "5",
                                        "6列" to "6",
                                        "7列" to "7",
                                        "8列" to "8"
                                    ),
                                    prefs.launcherAppColumns
                                ) { viewModel.updateLauncherAppColumns(it) }
                            },
                            onToggleAppLabels = {
                                scope.launch {
                                    repository.saveBoolean(
                                        SettingsRepository.HIDE_LAUNCHER_APP_LABELS,
                                        !prefs.hideLauncherAppLabels
                                    )
                                }
                            },
                            onOpenDefaultHomeGuide = {
                                isDefaultHome = LauncherEnvironmentDetector.isDefaultHome(context)
                                uiState.activeDialog = SettingDialogState.DefaultHomeGuide
                            },
                            onClick = {
                                uiState.restoreFocusRequester = it; uiState.restoreCategory = SettingsCategory.LAUNCHER
                            }
                        )

                        SettingsCategory.DISPLAY -> DisplaySettingsContent(
                            prefs.currentThemeName.contains("LIGHT") || prefs.currentThemeName == "HIGHTONE" || prefs.currentThemeName == "KOMOREBI_DAY" || prefs.currentThemeName == "KYLE_DAY",
                            themeSeason,
                            prefs,
                            itemFocusRequesters.getValue(SettingsCategory.DISPLAY)[0],
                            itemFocusRequesters.getValue(SettingsCategory.DISPLAY)[1],
                            itemFocusRequesters.getValue(SettingsCategory.DISPLAY)[2],
                            itemFocusRequesters.getValue(SettingsCategory.DISPLAY)[3],
                            categoryFocusRequesters.getValue(SettingsCategory.DISPLAY),
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_BASE_THEME,
                                    listOf(
                                        AppStrings.SETTINGS_VALUE_THEME_DARK to "DARK",
                                        AppStrings.SETTINGS_VALUE_THEME_LIGHT to "LIGHT",
                                        "時間連動テーマ" to "TIME_LINKED"
                                    ),
                                    if (prefs.currentThemeName.startsWith("KOMOREBI") || prefs.currentThemeName.startsWith(
                                            "KYLE"
                                        )
                                    ) "TIME_LINKED" else if (prefs.currentThemeName.contains("LIGHT") || prefs.currentThemeName == "HIGHTONE") "LIGHT" else "DARK"
                                ) {
                                    val nt = when (it) {
                                        "TIME_LINKED" -> "KOMOREBI"; "DARK" -> getThemeFromModeAndSeason(
                                            true,
                                            "DEFAULT"
                                        ); "LIGHT" -> getThemeFromModeAndSeason(
                                            false,
                                            "DEFAULT"
                                        ); else -> "MONOTONE"
                                    }
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.APP_THEME,
                                            nt
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_THEME_COLOR,
                                    if (prefs.currentThemeName.startsWith("KOMOREBI") || prefs.currentThemeName.startsWith(
                                            "KYLE"
                                        )
                                    ) listOf(
                                        "木漏れ日セット" to "KOMOREBI",
                                        "カイルセット" to "KYLE"
                                    ) else listOf(
                                        AppStrings.SETTINGS_VALUE_SEASON_DEFAULT to "DEFAULT",
                                        AppStrings.SETTINGS_VALUE_SEASON_SPRING to "SPRING",
                                        AppStrings.SETTINGS_VALUE_SEASON_SUMMER to "SUMMER",
                                        AppStrings.SETTINGS_VALUE_SEASON_AUTUMN to "AUTUMN",
                                        AppStrings.SETTINGS_VALUE_SEASON_WINTER to "WINTER",
                                        AppStrings.SETTINGS_VALUE_SEASON_BLUE to "BLUE"
                                    ),
                                    themeSeason
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.APP_THEME,
                                            if (it == "KOMOREBI" || it == "KYLE") it else getThemeFromModeAndSeason(
                                                !isLightTheme,
                                                it
                                            )
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "時刻の表示形式",
                                    TIME_FORMAT_OPTIONS,
                                    prefs.timeFormat
                                ) { viewModel.updateTimeFormat(it) }
                            },
                            { viewModel.toggleHideSubChannels() },
                            screensaverEnabled,
                            screensaverTimeout,
                            screensaverPlayerTimeout,
                            screensaverPlayerStyle,
                            playerEndSleepMinutes,
                            screensaverInterval,
                            screensaverTransition,
                            screensaverImages.size,
                            itemFocusRequesters.getValue(SettingsCategory.DISPLAY)[4],
                            itemFocusRequesters.getValue(SettingsCategory.DISPLAY)[5],
                            itemFocusRequesters.getValue(SettingsCategory.DISPLAY)[6],
                            itemFocusRequesters.getValue(SettingsCategory.DISPLAY)[7],
                            itemFocusRequesters.getValue(SettingsCategory.DISPLAY)[8],
                            itemFocusRequesters.getValue(SettingsCategory.DISPLAY)[9],
                            itemFocusRequesters.getValue(SettingsCategory.DISPLAY)[10],
                            itemFocusRequesters.getValue(SettingsCategory.DISPLAY)[11],
                            itemFocusRequesters.getValue(SettingsCategory.DISPLAY)[12],
                            { viewModel.setScreensaverEnabled(!screensaverEnabled) },
                            { viewModel.setScreensaverTimeoutMinutes(when (screensaverTimeout) { 1 -> 2; 2 -> 5; 5 -> 10; 10 -> 15; else -> 1 }) },
                            { viewModel.setScreensaverPlayerTimeoutMinutes(when (screensaverPlayerTimeout) { 1 -> 2; 2 -> 5; 5 -> 10; 10 -> 15; else -> 1 }) },
                            { viewModel.setScreensaverPlayerStyle(if (screensaverPlayerStyle == "PLAYER") "SLIDESHOW" else "PLAYER") },
                            { viewModel.setPlayerEndSleepMinutes(when (playerEndSleepMinutes) { 0 -> 15; 15 -> 30; 30 -> 60; 60 -> 120; else -> 0 }) },
                            { viewModel.setScreensaverIntervalSeconds(when (screensaverInterval) { 5 -> 15; 15 -> 30; 30 -> 60; else -> 5 }) },
                            { viewModel.setScreensaverTransition(when (screensaverTransition) { "FADE" -> "SLIDE"; "SLIDE" -> "NONE"; else -> "FADE" }) },
                            { showInternalImagePicker = true },
                            { screensaverPreview = true },
                            onClick = { uiState.restoreFocusRequester = it; uiState.restoreCategory = SettingsCategory.DISPLAY },
                            screensaverPlayerPreviewR = screensaverPlayerPreviewR,
                            onPreviewScreensaverPlayer = { screensaverPlayerPreview = true },
                            screensaverShowUpcoming = screensaverShowUpcoming,
                            screensaverUpcomingR = screensaverUpcomingR,
                            onToggleScreensaverUpcoming = { viewModel.setScreensaverShowUpcoming(!screensaverShowUpcoming) },
                            screensaverGalleryR = screensaverGalleryR,
                            onShowScreensaverGallery = { showScreensaverGallery = true },
                            playerClockModeR = playerClockModeRequester,
                            onEditPlayerClockMode = {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "プレイヤーの時計",
                                    PlayerClockMode.entries.map { it.label to it.name },
                                    prefs.playerClockMode.name,
                                ) { viewModel.updatePlayerClockMode(it) }
                            },
                            screensaverWebUploadR = screensaverWebUploadR,
                            onOpenScreensaverWebUpload = {
                                uiState.activeDialog = SettingDialogState.ScreensaverWebSetup
                            },
                        )

                        SettingsCategory.EPG -> EpgSettingsContent(
                            pref = prefs,
                            onEditColumn = {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "表示チャンネル数",
                                    listOf(
                                        "5チャンネル" to "5",
                                        "7チャンネル" to "7",
                                        "9チャンネル" to "9",
                                        "11チャンネル" to "11"
                                    ),
                                    prefs.epgColumnCount
                                ) { viewModel.updateEpgColumnCount(it) }
                            },
                            onEditHour = {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "表示時間数 (縦幅)",
                                    listOf(
                                        "4時間" to "4",
                                        "6時間" to "6",
                                        "8時間" to "8",
                                        "12時間" to "12"
                                    ),
                                    prefs.epgVisibleHours
                                ) { viewModel.updateEpgVisibleHours(it) }
                            },
                            onEditFontSize = {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "文字サイズ",
                                    listOf(
                                        "80%" to "0.8",
                                        "90%" to "0.9",
                                        "100% (標準)" to "1.0",
                                        "110%" to "1.1",
                                        "120%" to "1.2"
                                    ),
                                    prefs.epgFontSizeScale
                                ) { viewModel.updateEpgFontSizeScale(it) }
                            },
                            colR = itemFocusRequesters.getValue(SettingsCategory.EPG)[0],
                            hourR = itemFocusRequesters.getValue(SettingsCategory.EPG)[1],
                            fontR = itemFocusRequesters.getValue(SettingsCategory.EPG)[2],
                            sidebarR = categoryFocusRequesters.getValue(SettingsCategory.EPG),
                            onClick = {
                                uiState.restoreFocusRequester = it; uiState.restoreCategory = SettingsCategory.EPG
                            }
                        )

                        SettingsCategory.COMMENT -> CommentSettingsContent(
                            prefs.commentDefaultDisplay,
                            prefs.commentSpeed,
                            prefs.commentFontSize,
                            prefs.commentOpacity,
                            prefs.commentMaxLines,
                            prefs.subtitleCommentLayer,
                            prefs.commentEngine,
                            prefs.commentWordCounter,
                            openCommentLayerDialog,
                            openCommentEngineDialog,
                            {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.COMMENT_WORD_COUNTER,
                                        if (prefs.commentWordCounter == "ON") "OFF" else "ON"
                                    )
                                }
                            },
                            { t, v ->
                                uiState.activeDialog = SettingDialogState.Input(t, v) {
                                    scope.launch {
                                        repository.saveString(
                                            if (t == AppStrings.SETTINGS_INPUT_COMMENT_SPEED) SettingsRepository.COMMENT_SPEED else if (t == AppStrings.SETTINGS_INPUT_COMMENT_SIZE) SettingsRepository.COMMENT_FONT_SIZE else if (t == AppStrings.SETTINGS_INPUT_COMMENT_OPACITY) SettingsRepository.COMMENT_OPACITY else SettingsRepository.COMMENT_MAX_LINES,
                                            it
                                        )
                                    }
                                }
                            },
                            {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.COMMENT_DEFAULT_DISPLAY,
                                        if (prefs.commentDefaultDisplay == "ON") "OFF" else "ON"
                                    )
                                }
                            },
                            itemFocusRequesters.getValue(SettingsCategory.COMMENT)[CommentItemIndex.DEFAULT_DISPLAY],
                            itemFocusRequesters.getValue(SettingsCategory.COMMENT)[CommentItemIndex.SPEED],
                            itemFocusRequesters.getValue(SettingsCategory.COMMENT)[CommentItemIndex.FONT_SIZE],
                            itemFocusRequesters.getValue(SettingsCategory.COMMENT)[CommentItemIndex.OPACITY],
                            itemFocusRequesters.getValue(SettingsCategory.COMMENT)[CommentItemIndex.MAX_LINES],
                            itemFocusRequesters.getValue(SettingsCategory.COMMENT)[CommentItemIndex.LAYER_ORDER],
                            itemFocusRequesters.getValue(SettingsCategory.COMMENT)[CommentItemIndex.COMMENT_ENGINE],
                            itemFocusRequesters.getValue(SettingsCategory.COMMENT)[CommentItemIndex.WORD_COUNTER],
                            categoryFocusRequesters.getValue(SettingsCategory.COMMENT),
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategory = SettingsCategory.COMMENT }

                        SettingsCategory.CACHE -> CacheManagementContent(
                            onClearAll = {
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    "すべてのキャッシュの削除",
                                    "局ロゴ・サムネイル・番組表・録画リストのキャッシュをすべて削除します。よろしいですか？"
                                ) {
                                    viewModel.clearAllCache()
                                    showToast("すべてのキャッシュを削除しました")
                                }
                            },
                            onClearChannelLogo = {
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    "局ロゴキャッシュの削除",
                                    "端末に保存されている局ロゴを削除します。次回表示時に再取得されます。"
                                ) {
                                    viewModel.clearChannelLogoCache()
                                    showToast("局ロゴキャッシュを削除しました")
                                }
                            },
                            onClearThumbnail = {
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    "サムネイルキャッシュの削除",
                                    "番組・録画のサムネイル画像キャッシュを削除します。次回表示時に再取得されます。"
                                ) {
                                    viewModel.clearThumbnailCache()
                                    showToast("サムネイルキャッシュを削除しました")
                                }
                            },
                            onClearEpg = {
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    "番組表キャッシュの削除",
                                    "保存済みの番組表データを削除します。次回表示時に再取得されます。"
                                ) {
                                    viewModel.clearEpgCache()
                                    showToast("番組表キャッシュを削除しました")
                                }
                            },
                            onClearRecording = {
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    "録画リストキャッシュの削除",
                                    "端末内に残っている録画リストのキャッシュを削除します。録画リストはサーバーから必要なページだけ取得されます。"
                                ) {
                                    viewModel.triggerFullSync()
                                    showToast("録画リストキャッシュを削除しました")
                                }
                            },
                            // 履歴の削除はキャッシュ一括削除とは独立。グローバル削除では消さない。
                            onClearChannelHistory = {
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    AppStrings.DIALOG_CLEAR_HISTORY_TITLE,
                                    AppStrings.DIALOG_CLEAR_CHANNEL_HISTORY_MSG
                                ) { onClearLastChannel() }
                            },
                            onClearWatchHistory = {
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    AppStrings.DIALOG_CLEAR_HISTORY_TITLE,
                                    AppStrings.DIALOG_CLEAR_WATCH_HISTORY_MSG
                                ) { onClearWatchHistory() }
                            },
                            allR = itemFocusRequesters.getValue(SettingsCategory.CACHE)[0],
                            logoR = itemFocusRequesters.getValue(SettingsCategory.CACHE)[1],
                            thumbR = itemFocusRequesters.getValue(SettingsCategory.CACHE)[2],
                            epgR = itemFocusRequesters.getValue(SettingsCategory.CACHE)[3],
                            recordingR = itemFocusRequesters.getValue(SettingsCategory.CACHE)[4],
                            channelHistoryR = itemFocusRequesters.getValue(SettingsCategory.CACHE)[5],
                            watchHistoryR = itemFocusRequesters.getValue(SettingsCategory.CACHE)[6],
                            sidebarR = categoryFocusRequesters.getValue(SettingsCategory.CACHE)
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategory = SettingsCategory.CACHE }

                        SettingsCategory.APP_INFO -> AppInfoContent(
                            receiveBetaUpdates = receiveBetaUpdates,
                            updateState = viewModel.updateState.collectAsState().value,
                            onToggleBetaUpdates = { enabled ->
                                scope.launch {
                                    repository.saveBoolean(SettingsRepository.RECEIVE_BETA_UPDATES, enabled)
                                }
                            },
                            onCheckUpdates = viewModel::checkForUpdates,
                            onScrollToTop = { scope.launch { mainScrollState.animateScrollTo(0) } },
                            onShowProject = {
                                uiState.activeDialog = SettingDialogState.LinkQr(
                                    AppStrings.DIALOG_LINK_TITLE,
                                    AppStrings.PROJECT_GITHUB_URL
                                )
                            },
                            onShowLicenses = { uiState.activeDialog = SettingDialogState.Licenses },
                            onCapabilities = { uiState.activeDialog = SettingDialogState.DeviceCapabilities },
                            onOpenWhatsNew = onOpenWhatsNew,
                            capabilityR = itemFocusRequesters.getValue(SettingsCategory.APP_INFO)[AppInfoItemIndex.DEVICE_CAPABILITIES],
                            versionR = itemFocusRequesters.getValue(SettingsCategory.APP_INFO)[AppInfoItemIndex.VERSION],
                            whatsNewR = itemFocusRequesters.getValue(SettingsCategory.APP_INFO)[AppInfoItemIndex.WHATS_NEW],
                            betaR = itemFocusRequesters.getValue(SettingsCategory.APP_INFO)[AppInfoItemIndex.BETA_UPDATES],
                            checkR = itemFocusRequesters.getValue(SettingsCategory.APP_INFO)[AppInfoItemIndex.CHECK_UPDATES],
                            projectR = itemFocusRequesters.getValue(SettingsCategory.APP_INFO)[AppInfoItemIndex.PROJECT_GITHUB],
                            licR = itemFocusRequesters.getValue(SettingsCategory.APP_INFO)[AppInfoItemIndex.OSS_LICENSES],
                            sidebarR = categoryFocusRequesters.getValue(SettingsCategory.APP_INFO)
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategory = SettingsCategory.APP_INFO }
                    }
                    Spacer(Modifier.height(32.dp))
                }
                }
            }
        }

        // --- ダイアログ表示制御 ---
        LaunchedEffect(uiState.activeDialog, honomiPairing) {
            val pairing = honomiPairing ?: return@LaunchedEffect
            if (uiState.activeDialog !is SettingDialogState.HonomiLogin) return@LaunchedEffect
            val deadline = System.currentTimeMillis() + pairing.expiresIn * 1000L
            while (System.currentTimeMillis() < deadline && uiState.activeDialog is SettingDialogState.HonomiLogin) {
                delay(pairing.interval * 1000L)
                viewModel.pollHonomiPairing(pairing)
                    .onSuccess { session ->
                        if (session != null) {
                            showToast("${session.userName} とペアリングしました")
                            closeDialog()
                            return@LaunchedEffect
                        }
                    }
                    .onFailure {
                        honomiLoginError = it.message ?: "ペアリングに失敗しました"
                        return@LaunchedEffect
                    }
            }
            if (uiState.activeDialog is SettingDialogState.HonomiLogin) {
                honomiLoginError = "ペアリングコードの有効期限が切れました"
            }
        }

        when (val state = uiState.activeDialog) {
            is SettingDialogState.HonomiLogout -> HonomiLogoutDialog(
                onConfirm = {
                    viewModel.logoutHonomi()
                    showToast("HonomiTV からログアウトしました")
                    closeDialog()
                },
                onDismiss = { closeDialog() },
            )

            is SettingDialogState.Input -> InputDialog(
                state.title,
                state.initialValue,
                { closeDialog() },
                { state.onConfirm(it); closeDialog() })

            is SettingDialogState.CloudflareAccess -> CloudflareAccessDialog(
                state.clientId,
                state.clientSecret,
                { closeDialog() },
                { clientId, clientSecret -> state.onConfirm(clientId, clientSecret) },
            )

            is SettingDialogState.BatchInput -> BatchInputDialog(
                { closeDialog() },
                { n, p -> state.onConfirm(n, p); closeDialog() })

            is SettingDialogState.Selection -> SelectionDialog(
                state.title,
                state.options,
                state.current,
                { closeDialog() },
                { state.onSelect(it); closeDialog() })

            is SettingDialogState.MultiSelection -> MultiSelectionDialog(
                state.title,
                state.options,
                state.currentSelections,
                { closeDialog() },
                { state.onConfirm(it); closeDialog() })

            is SettingDialogState.ConfirmClear -> ConfirmClearDialog(
                state.title,
                state.message,
                if (state.title.contains("警告")) "有効にする" else "削除",
                { state.onConfirm(); closeDialog() },
                { closeDialog() })

            is SettingDialogState.Licenses -> OpenSourceLicensesScreen(onBack = { closeDialog() })
            is SettingDialogState.LinkQr -> LinkQrDialog(
                state.title,
                state.url,
                { closeDialog() })
            is SettingDialogState.DeviceCapabilities -> DeviceCapabilitiesScreen(onBack = { closeDialog() })
            is SettingDialogState.DefaultHomeGuide -> DefaultHomeGuideDialog(
                environment = homeEnvironment,
                isDefaultHome = isDefaultHome,
                onOpenHomeSettings = {
                    if (!LauncherEnvironmentDetector.openHomeSettings(context)) {
                        showToast("ホーム設定を開けませんでした")
                    }
                    closeDialog()
                },
                onDismiss = { closeDialog() }
            )
            is SettingDialogState.HonomiLogin -> HonomiLoginDialog(
                honomiLoginInProgress,
                honomiLoginError,
                { if (!honomiLoginInProgress) closeDialog() },
                {
                    scope.launch {
                        viewModel.createHonomiPairing()
                            .onSuccess { honomiPairing = it }
                            .onFailure { honomiLoginError = it.message ?: "ログインに失敗しました" }
                    }
                },
                honomiPairing,
                honomiPairing?.verificationUrl,
            )
            is SettingDialogState.ConnectionSetup -> {
                val localIp by viewModel.localIpAddress.collectAsState()
                val setupState by viewModel.connectionSetupState.collectAsState()
                ConnectionSetupDialog(
                    title = "接続先の設定", serverIp = localIp, state = setupState,
                    onStart = { viewModel.startConnectionSetup(state.source) },
                    onStop = { viewModel.stopGeminiLocalServer() },
                    onDismiss = { closeDialog() }, onManualInput = { closeDialog() },
                )
            }
            is SettingDialogState.ScreensaverWebSetup -> {
                val localIp by viewModel.localIpAddress.collectAsState()
                val setupState by viewModel.connectionSetupState.collectAsState()
                ScreensaverWebSetupDialog(
                    serverIp = localIp,
                    state = setupState,
                    savedImageCount = screensaverImages.size,
                    onStart = { viewModel.startScreensaverSetup() },
                    onStop = { viewModel.stopGeminiLocalServer() },
                    onDismiss = { closeDialog() },
                )
            }
            is SettingDialogState.GeminiSetup -> {
                val localIp by viewModel.localIpAddress.collectAsState()
                GeminiSetupDialog(
                    prefs.geminiApiKey,
                    localIp,
                    { viewModel.startGeminiLocalServer() },
                    { viewModel.stopGeminiLocalServer() },
                    { closeDialog() },
                    {
                        viewModel.stopGeminiLocalServer(); uiState.activeDialog =
                        SettingDialogState.Input(
                            "Gemini API Key",
                            prefs.geminiApiKey
                        ) { key ->
                            scope.launch {
                                repository.saveString(
                                    SettingsRepository.GEMINI_API_KEY,
                                    key
                                )
                            }
                        }
                    },
                    {
                        viewModel.stopGeminiLocalServer(); scope.launch {
                        repository.saveString(
                            SettingsRepository.GEMINI_API_KEY,
                            ""
                        )
                    }; closeDialog()
                    })
            }

            is SettingDialogState.SmbAction -> SelectionDialog(
                title = "${state.target.name} の操作",
                options = listOf("編集する" to "EDIT", "削除する" to "DELETE"),
                current = "",
                onDismiss = { closeDialog() },
                onSelect = { action ->
                    if (action == "EDIT") {
                        uiState.activeDialog = SettingDialogState.SmbSetup(state.target)
                    } else if (action == "DELETE") {
                        uiState.activeDialog = SettingDialogState.ConfirmClear(
                            "SMBサーバーの削除",
                            "「${state.target.name}」を削除しますか？"
                        ) { viewModel.deleteSmbServer(state.target.id) }
                    }
                })

            is SettingDialogState.SmbSetup -> {
                val localIp by viewModel.localIpAddress.collectAsState()
                val setupState by viewModel.connectionSetupState.collectAsState()
                ConnectionSetupDialog(
                    title = "接続設定", serverIp = localIp, state = setupState,
                    onStart = { viewModel.startConnectionSetup() },
                    onStop = { viewModel.stopGeminiLocalServer() },
                    onDismiss = { closeDialog() },
                    onManualInput = {
                        viewModel.stopGeminiLocalServer()
                        uiState.activeDialog = SettingDialogState.SmbManualInput(state.target) { viewModel.saveSmbServer(it) }
                    },
                )
            }

            is SettingDialogState.SmbManualInput -> {
                SmbManualInputDialog(
                    state.target,
                    { closeDialog() },
                    { viewModel.saveSmbServer(it); closeDialog() })
            }

            is SettingDialogState.NoSignalStylePreview -> TifNoSignalStylePreviewOverlay(
                currentStyle = prefs.tifNoSignalStyle,
                currentAccentColor = prefs.tifNoSignalAccentColor,
                screensaverImageUris = screensaverImages,
                screensaverIntervalSeconds = 5,
                screensaverTransition = screensaverTransition,
                onSelect = {
                    viewModel.updateTifNoSignalStyle(it.name)
                    closeDialog()
                },
                onSelectAccentColor = {
                    viewModel.updateTifNoSignalAccentColor(it.name)
                },
                onDismiss = { closeDialog() },
            )

            else -> {}
        }
        if (showInternalImagePicker) {
            ScreensaverImagePicker(
                onDismiss = { showInternalImagePicker = false; closeDialog() },
                onImport = { uris -> viewModel.importScreensaverImages(uris) },
            )
        }
        if (showScreensaverGallery) {
            ScreensaverImageGallery(
                imageUris = screensaverImages,
                onDelete = { removed -> viewModel.deleteScreensaverImages(removed) },
                onDismiss = { showScreensaverGallery = false; closeDialog() },
            )
        }
        if (screensaverPreview || screensaverPlayerPreview) {
            val isPlayerPreview = screensaverPlayerPreview
            val upcoming = com.beeregg2001.komorebi.ui.screensaver.rememberUpcomingRecordings(
                enabled = screensaverShowUpcoming && !(isPlayerPreview && screensaverPlayerStyle == "PLAYER"), visible = true,
            )
            androidx.compose.ui.window.Dialog(
                onDismissRequest = {
                    screensaverPreview = false
                    screensaverPlayerPreview = false
                    closeDialog()
                },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == androidx.compose.ui.input.key.Key.Back) {
                            screensaverPreview = false
                            screensaverPlayerPreview = false
                            closeDialog()
                            true
                        } else false
                    }.clickable {
                        screensaverPreview = false
                        screensaverPlayerPreview = false
                        closeDialog()
                    }
                ) {
                    if (isPlayerPreview && screensaverPlayerStyle == "PLAYER") {
                        com.beeregg2001.komorebi.ui.screensaver.PlayerScreensaverContent(
                            title = "プレビュー",
                            modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)
                        )
                    } else {
                        com.beeregg2001.komorebi.ui.screensaver.ScreensaverContent(
                            imageUris = screensaverImages, intervalSeconds = 5,
                            transition = screensaverTransition, upcomingRecordings = upcoming
                        )
                    }
                }
            }
        }
    }
}
