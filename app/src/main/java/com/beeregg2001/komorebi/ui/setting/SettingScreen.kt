@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.setting

import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.ui.components.GlobalToast
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.getSeasonalBackgroundBrush
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import java.time.LocalTime
import com.beeregg2001.komorebi.data.model.DeviceAuthRequest

private data class SettingSidebarRow(
    val contentIndex: Int,
    val title: String,
    val icon: ImageVector,
    val isChild: Boolean = false,
)

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onClearLastChannel: () -> Unit = {},
    onClearWatchHistory: () -> Unit = {},
    initialCategoryIndex: Int = 0,
    initialFocusItemIndex: Int? = null,
    initialOpenDeviceCapabilities: Boolean = false,
    viewModel: SettingsViewModel = hiltViewModel(),
    channelViewModel: ChannelViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context) }
    val homeEnvironment = remember(context) { LauncherEnvironmentDetector.detect(context) }
    var isDefaultHome by remember { mutableStateOf(false) }
    val colors = KomorebiTheme.colors
    val currentTime = remember { LocalTime.now() }
    val backgroundBrush = getSeasonalBackgroundBrush(KomorebiTheme.theme, currentTime)

    val prefs = rememberSettingPreferences(repository)
    val uiState = rememberSettingUiState(initialCategoryIndex)

    val receiveBetaUpdates by viewModel.receiveBetaUpdates.collectAsState()
    val playerUiMode by viewModel.playerUiMode.collectAsState()
    val autoCmSkip by viewModel.autoCmSkip.collectAsState()
    val preferOriginalMpegTs by viewModel.preferOriginalMpegTs.collectAsState()
    val availableQualities by viewModel.availableQualities.collectAsState()
    val honomiSession by viewModel.honomiSession.collectAsState()
    val honomiLoginInProgress by viewModel.honomiLoginInProgress.collectAsState()
    val screensaverEnabled by viewModel.screensaverEnabled.collectAsState()
    val screensaverTimeout by viewModel.screensaverTimeoutMinutes.collectAsState()
    val screensaverInterval by viewModel.screensaverIntervalSeconds.collectAsState()
    val screensaverTransition by viewModel.screensaverTransition.collectAsState()
    val screensaverImages by viewModel.screensaverImageUris.collectAsState()
    var screensaverPreview by remember { mutableStateOf(false) }
    val screensaverImagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val accepted = uris.filter { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            }.getOrDefault(false)
        }.map { it.toString() }
        if (accepted.isNotEmpty()) viewModel.setScreensaverImageUris(screensaverImages + accepted)
    }
    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val flatChannels = remember(groupedChannels) { groupedChannels.values.flatten() }

    var toastMessage by remember { mutableStateOf<String?>(null) }
    var honomiLoginError by remember { mutableStateOf<String?>(null) }
    var honomiPairing by remember { mutableStateOf<DeviceAuthRequest?>(null) }
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(3500)
            toastMessage = null
        }
    }

    LaunchedEffect(Unit) {
        viewModel.smbServerAddedEvent.collect { name ->
            toastMessage = "設定を受信しました！\n「$name」"
        }
    }

    // サイドバーの表示順。contentIndex は下の when 分岐（既存の内容インデックス）に対応させ、
    // 内容ロジックとディープリンクを一切変えずに並び順だけ組み替えられるようにする。
    // isChild の項目は、直前にグループ見出しを挟んで UI 設定の子として表示する。
    val sidebarRows = remember {
        listOf(
            SettingSidebarRow(0, AppStrings.SETTINGS_CATEGORY_GENERAL, Icons.Default.SettingsApplications),
            SettingSidebarRow(1, AppStrings.SETTINGS_CATEGORY_CONNECTION, Icons.Default.CastConnected),
            SettingSidebarRow(2, AppStrings.SETTINGS_CATEGORY_PLAYBACK, Icons.Default.PlayCircle),
            SettingSidebarRow(5, AppStrings.SETTINGS_CATEGORY_COMMENT, Icons.Default.Tv),
            SettingSidebarRow(8, AppStrings.SETTINGS_CATEGORY_DISPLAY, Icons.Default.Dashboard, isChild = true),
            SettingSidebarRow(6, AppStrings.SETTINGS_CATEGORY_HOME, Icons.Default.Home, isChild = true),
            SettingSidebarRow(7, AppStrings.SETTINGS_CATEGORY_LAUNCHER, Icons.Default.Apps, isChild = true),
            SettingSidebarRow(3, "録画設定", Icons.Default.VideoSettings, isChild = true),
            SettingSidebarRow(4, "番組表設定", Icons.Default.GridOn, isChild = true),
            SettingSidebarRow(10, AppStrings.SETTINGS_CATEGORY_CACHE, Icons.Default.DeleteSweep),
            SettingSidebarRow(11, AppStrings.SETTINGS_CATEGORY_APP_INFO, Icons.Default.Info),
        )
    }
    val sidebarRowRequesters = remember { List(sidebarRows.size) { FocusRequester() } }
    // 内容インデックス → その内容に対応するサイドバー行の FocusRequester。
    // 既存コードの categoryFocusRequesters[x] / getOrNull(...) / lastOrNull() を
    // そのまま使えるよう、内容インデックスで引ける List として用意する。
    val categoryFocusRequesters = remember(sidebarRows) {
        val byContent = sidebarRows
            .mapIndexed { i, row -> row.contentIndex to sidebarRowRequesters[i] }
            .toMap()
        List(12) { byContent[it] ?: FocusRequester.Default }
    }
    val homeBackRequester = remember { FocusRequester() }

    val batchItemRs =
        remember(prefs.postRecordingBatchList) { List(prefs.postRecordingBatchList.size) { FocusRequester() } }
    val edcbPlayMethodR = remember { FocusRequester() }
    val smbItemRs =
        remember(prefs.smbServerList) { List(prefs.smbServerList.size) { FocusRequester() } }

    // カテゴリごとの項目フォーカスリクエスタ
    // [0] 基本設定: startupTab, startupChannel, excludePaid, NHK exclusion
    // [1] 接続設定
    // [2] 再生設定
    // [3] 録画設定: recordView, addBatch
    // [4] 番組表設定: column, hour, font
    // [5] コメント設定
    // [6] ホーム設定: genre, time
    // [7] ランチャー設定
    // [8] 表示設定: themeMode, themeColor, timeFormat, hideSubChannels
    // [9] 削除済み: アドオン・ラボの再生項目は [2] に統合
    // [10] データ管理: cache(all/logo/thumb/epg/recording) + history(channel/watch)
    // [11] アプリ情報
    val itemFocusRequesters = remember {
        listOf(
            List(5) { FocusRequester() },
            List(10) { FocusRequester() },
            List(12) { FocusRequester() },
            List(2) { FocusRequester() },
            List(4) { FocusRequester() },
            List(5) { FocusRequester() },
            List(2) { FocusRequester() },
            List(4) { FocusRequester() },
            List(11) { FocusRequester() },
            emptyList(),
            List(7) { FocusRequester() },
            List(7) { FocusRequester() }
        )
    }

    val mainScrollState = rememberScrollState()
    val sidebarScrollState = rememberScrollState()

    LaunchedEffect(uiState.selectedCategoryIndex) {
        if (initialFocusItemIndex == null || uiState.selectedCategoryIndex != initialCategoryIndex) {
            mainScrollState.scrollTo(0)
        }
    }

    LaunchedEffect(Unit) {
        delay(400)

        if (initialFocusItemIndex != null) {
            uiState.isSidebarFocused = false
            val targetRequester = itemFocusRequesters.getOrNull(initialCategoryIndex)
                ?.getOrNull(initialFocusItemIndex)

            var success = false
            for (i in 0..5) {
                try {
                    targetRequester?.requestFocus()
                    success = true
                    break
                } catch (e: Exception) {
                    delay(150)
                }
            }

            if (!success) {
                categoryFocusRequesters.getOrNull(initialCategoryIndex)
                    ?.safeRequestFocus("Settings_Fallback")
            }
        } else {
            categoryFocusRequesters.getOrNull(uiState.selectedCategoryIndex)
                ?.safeRequestFocus("Settings_Initial")
        }
    }

    val closeDialog = {
        uiState.isRestoringFocus = true
        uiState.activeDialog = SettingDialogState.None
        scope.launch {
            delay(300)
            uiState.restoreFocusRequester?.safeRequestFocus("SettingScreen_Restore")
            delay(100)
            uiState.isRestoringFocus = false
        }
    }

    LaunchedEffect(initialOpenDeviceCapabilities) {
        if (initialOpenDeviceCapabilities) {
            uiState.restoreFocusRequester = itemFocusRequesters[11][6]
            uiState.restoreCategoryIndex = 11
            uiState.activeDialog = SettingDialogState.DeviceCapabilities
        }
    }

    val isDialogOpen = uiState.activeDialog !is SettingDialogState.None

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
                .background(backgroundBrush)
                .focusProperties { canFocus = !isDialogOpen }
                .onKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK || it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_ESCAPE)) {
                        if (!uiState.isSidebarFocused) {
                            categoryFocusRequesters.getOrNull(uiState.selectedCategoryIndex)
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
                    .width(280.dp)
                    .fillMaxHeight()
                    .background(colors.surface.copy(alpha = 0.6f))
                    .padding(top = 32.dp, bottom = 32.dp, start = 24.dp, end = 24.dp)
                    .onFocusChanged { uiState.isSidebarFocused = it.hasFocus }
                    .focusProperties { canFocus = !uiState.isRestoringFocus }
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
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(sidebarScrollState),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sidebarRows.forEachIndexed { index, row ->
                        if (row.isChild && (index == 0 || !sidebarRows[index - 1].isChild)) {
                            Text(
                                text = AppStrings.SETTINGS_CATEGORY_UI,
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp)
                            )
                        }
                        val targetR =
                            itemFocusRequesters.getOrNull(row.contentIndex)?.firstOrNull()
                                ?: FocusRequester.Default
                        CategoryItem(
                            title = row.title,
                            icon = row.icon,
                            isSelected = uiState.selectedCategoryIndex == row.contentIndex,
                            onFocused = {
                                if (uiState.isSidebarFocused) {
                                    uiState.selectedCategoryIndex = row.contentIndex
                                }
                            },
                            onClick = { targetR.safeRequestFocus("CategoryItem_Click") },
                            enabled = !uiState.isRestoringFocus,
                            modifier = Modifier
                                .padding(start = if (row.isChild) 16.dp else 0.dp)
                                .focusRequester(sidebarRowRequesters[index])
                                .focusProperties {
                                    left = FocusRequester.Cancel // ★ 修正: 左キーでフォーカスが迷子になるのを防ぐ
                                    right = targetR
                                    if (index == 0) up = FocusRequester.Cancel
                                    if (index == sidebarRows.lastIndex) down = homeBackRequester
                                }
                        )
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
                            up = categoryFocusRequesters.lastOrNull() ?: FocusRequester.Default
                            down = FocusRequester.Cancel
                            right = itemFocusRequesters.getOrNull(uiState.selectedCategoryIndex)
                                ?.firstOrNull() ?: FocusRequester.Default
                        }
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 48.dp, horizontal = 64.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(mainScrollState)
                ) {
                    when (uiState.selectedCategoryIndex) {
                        0 -> GeneralSettingsContent(
                            prefs.startupTab,
                            when (prefs.startupChannel) {
                                "OFF" -> AppStrings.SETTINGS_VALUE_STARTUP_OFF
                                "LAST_WATCHED" -> AppStrings.SETTINGS_VALUE_STARTUP_LAST
                                else -> flatChannels.find { it.id == prefs.startupChannel }?.name
                                    ?: prefs.startupChannel
                            },
                            prefs.excludePaid,
                            itemFocusRequesters[0][0],
                            itemFocusRequesters[0][1],
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_STARTUP_TAB,
                                    listOf(
                                        "ホーム" to "ホーム",
                                        "ライブ" to "ライブ",
                                        "アプリ" to "アプリ",
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
                                    AppStrings.DIALOG_STARTUP_CHANNEL_TITLE,
                                    (listOf(
                                        AppStrings.SETTINGS_VALUE_STARTUP_OFF to "OFF",
                                        AppStrings.SETTINGS_VALUE_STARTUP_LAST to "LAST_WATCHED"
                                    ) + flatChannels.map { it.name to it.id }),
                                    prefs.startupChannel
                                ) { viewModel.updateStartupChannel(it) }
                            },
                            itemFocusRequesters[0][2],
                            {
                                scope.launch {
                                    repository.saveString(
                                        SettingsRepository.EXCLUDE_PAID_BROADCASTS,
                                        if (prefs.excludePaid == "ON") "OFF" else "ON"
                                    )
                                }
                            },
                            viewModel.nhkExclusionState.collectAsState().value.mode.displayLabel,
                            itemFocusRequesters[0][3],
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "N〇K除外モード",
                                    com.beeregg2001.komorebi.data.model.NHKExclusionMode.entries.map { it.displayLabel to it.name },
                                    viewModel.nhkExclusionState.value.mode.name,
                                ) { viewModel.setNHKExclusionMode(com.beeregg2001.komorebi.data.model.NHKExclusionMode.fromPreference(it)) }
                            },
                            categoryFocusRequesters[0]
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 0 }

                        1 -> ConnectionSettingsContent(
                            prefs.backendType,
                            prefs.edcbIp,
                            prefs.edcbPort,
                            prefs.edcbHttpPort,
                            prefs.epgStationIp,
                            prefs.epgStationPort,
                            prefs.konomiIp,
                            prefs.konomiPort,
                            prefs.mirakurunIp,
                            prefs.mirakurunPort,
                            prefs.preferredSource,
                            prefs.cfAccessClientId,
                            prefs.cfAccessClientSecret,
                            prefs.edcbRecordPlayMethod,
                            prefs.smbServerList,
                            honomiSession?.userName,
                            {
                                if (honomiSession == null) {
                                    honomiLoginError = null
                                    honomiPairing = null
                                    uiState.activeDialog = SettingDialogState.HonomiLogin
                                } else {
                                    viewModel.logoutHonomi()
                                    toastMessage = "HonomiTV からログアウトしました"
                                }
                            },
                            { uiState.activeDialog = SettingDialogState.SmbSetup(null) },
                            { uiState.activeDialog = SettingDialogState.SmbAction(it) },
                            itemFocusRequesters[1][7],
                            smbItemRs,
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "録画ファイルの再生方式",
                                    listOf(
                                        "API経由 (api/xcode)" to "API",
                                        "直接アクセス (高速シーク可)" to "DIRECT"
                                    ),
                                    if (listOf(
                                            "API",
                                            "DIRECT"
                                        ).any { it == prefs.edcbRecordPlayMethod }
                                    ) prefs.edcbRecordPlayMethod else "API"
                                ) {
                                    scope.launch(Dispatchers.IO) {
                                        repository.saveString(
                                            SettingsRepository.EDCB_RECORD_PLAY_METHOD,
                                            it
                                        )
                                    }
                                    viewModel.updateEdcbRecordPlayMethod(it)
                                }
                            },
                            edcbPlayMethodR,
                            { t, v ->
                                uiState.activeDialog = SettingDialogState.Input(t, v) { input ->
                                    scope.launch(Dispatchers.IO) {
                                        when (t) {
                                            "KonomiTV (IPアドレス)" -> repository.saveString(
                                                SettingsRepository.KONOMI_IP,
                                                input
                                            )

                                            "KonomiTV (ポート)" -> repository.saveString(
                                                SettingsRepository.KONOMI_PORT,
                                                input
                                            )

                                            "EDCB (IPアドレス)" -> repository.saveString(
                                                SettingsRepository.EDCB_IP,
                                                input
                                            )

                                            "EDCB (TCPポート)" -> repository.saveString(
                                                SettingsRepository.EDCB_PORT,
                                                input
                                            )

                                            "EDCB (HTTP/HTTPSポート)" -> repository.saveString(
                                                SettingsRepository.EDCB_HTTP_PORT,
                                                input
                                            )

                                            "Mirakurun (IPアドレス)" -> repository.saveString(
                                                SettingsRepository.MIRAKURUN_IP,
                                                input
                                            )

                                            "Mirakurun (ポート)" -> repository.saveString(
                                                SettingsRepository.MIRAKURUN_PORT,
                                                input
                                            )
                                        }
                                    }
                                    when (t) {
                                        "KonomiTV (IPアドレス)" -> viewModel.updateKonomiIp(input)
                                        "KonomiTV (ポート)" -> viewModel.updateKonomiPort(input)
                                        "EDCB (IPアドレス)" -> viewModel.updateEdcbIp(input)
                                        "EDCB (TCPポート)" -> viewModel.updateEdcbPort(input)
                                        "Mirakurun (IPアドレス)" -> viewModel.updateMirakurunIp(
                                            input
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "バックエンドシステムの選択",
                                    listOf(
                                        "KonomiTV" to "KONOMITV",
                                        "EDCB (EpgTimerSrv)" to "EDCB",
                                        "Mirakurun (録画なし)" to "MIRAKURUN_ONLY"
                                    ),
                                    if (listOf(
                                            "KONOMITV",
                                            "EDCB",
                                            "MIRAKURUN_ONLY"
                                        ).any { it == prefs.backendType }
                                    ) prefs.backendType else "KONOMITV"
                                ) {
                                    scope.launch(Dispatchers.IO) {
                                        repository.saveString(
                                            SettingsRepository.BACKEND_TYPE,
                                            it
                                        )
                                    }
                                    viewModel.updateBackendType(it)
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    AppStrings.SETTINGS_ITEM_PREFERRED_SOURCE,
                                    mutableListOf(
                                        "メインシステムに従う\n（トランスコード）" to "KONOMITV",
                                        "Mirakurun を優先" to "MIRAKURUN"
                                    ).apply {
                                        if (prefs.backendType != "EDCB") add("EDCB (TCP) を優先" to "EDCB") else add(
                                            "EDCB (ダイレクトストリーミング)" to "EDCB"
                                        )
                                    },
                                    if (listOf(
                                            "KONOMITV",
                                            "MIRAKURUN",
                                            "EDCB"
                                        ).any { it == prefs.preferredSource }
                                    ) prefs.preferredSource else "KONOMITV"
                                ) {
                                    scope.launch(Dispatchers.IO) {
                                        repository.saveString(
                                            SettingsRepository.PREFERRED_STREAM_SOURCE,
                                            it
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.CloudflareAccess(
                                    prefs.cfAccessClientId,
                                    prefs.cfAccessClientSecret,
                                ) { clientId, clientSecret -> repository.saveCloudflareAccessCredentials(clientId, clientSecret) }
                            },
                            itemFocusRequesters[1][0],
                            itemFocusRequesters[1][1],
                            itemFocusRequesters[1][2],
                            itemFocusRequesters[1][3],
                            itemFocusRequesters[1][4],
                            itemFocusRequesters[1][5],
                            itemFocusRequesters[1][6],
                            itemFocusRequesters[1][8],
                            itemFocusRequesters[1][9],
                            categoryFocusRequesters[1]
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 1 }

                        2 -> PlaybackSettingsContent(
                            prefs.liveQuality,
                            prefs.videoQuality,
                            prefs.liveSubtitleDefault,
                            prefs.videoSubtitleDefault,
                            prefs.subtitleFont,
                            prefs.subtitleCommentLayer,
                            prefs.audioOutputMode,
                            playerUiMode,
                            autoCmSkip,
                            preferOriginalMpegTs,
                            prefs.labAllowMirakurunDual,
                            availableQualities,
                            itemFocusRequesters[2][0],
                            itemFocusRequesters[2][1],
                            itemFocusRequesters[2][2],
                            itemFocusRequesters[2][3],
                            itemFocusRequesters[2][10],
                            itemFocusRequesters[2][4],
                            itemFocusRequesters[2][5],
                            itemFocusRequesters[2][6],
                            itemFocusRequesters[2][7],
                            itemFocusRequesters[2][8],
                            itemFocusRequesters[2][11],
                            categoryFocusRequesters[2],
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
                                        "手動（CM開始から5秒間、決定ボタンでスキップ）" to "MANUAL",
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
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 2 }

                        3 -> RecordingSettingsContent(
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
                            itemFocusRequesters[3][0],
                            itemFocusRequesters[3][1],
                            batchItemRs,
                            categoryFocusRequesters[3]
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 3 }

                        6 -> HomeDisplaySettingsContent(
                            prefs.pickupGenre,
                            prefs.pickupTime,
                            itemFocusRequesters[6][0],
                            itemFocusRequesters[6][1],
                            categoryFocusRequesters[6],
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
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 6 }

                        7 -> LauncherSettingsContent(
                            showAppsTab = !prefs.hideAppsTab,
                            appColumns = prefs.launcherAppColumns,
                            showAppLabels = !prefs.hideLauncherAppLabels,
                            environment = homeEnvironment,
                            isDefaultHome = isDefaultHome,
                            itemRs = itemFocusRequesters[7],
                            sidebarR = categoryFocusRequesters[7],
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
                                uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 7
                            }
                        )

                        8 -> DisplaySettingsContent(
                            prefs.currentThemeName.contains("LIGHT") || prefs.currentThemeName == "HIGHTONE" || prefs.currentThemeName == "KOMOREBI_DAY" || prefs.currentThemeName == "KYLE_DAY",
                            when (prefs.currentThemeName) {
                                "SPRING", "SPRING_LIGHT" -> "SPRING"; "SUMMER", "SUMMER_LIGHT" -> "SUMMER"; "AUTUMN", "AUTUMN_LIGHT" -> "AUTUMN"; "WINTER_DARK", "WINTER_LIGHT" -> "WINTER"; "EPG_BLUE", "EPG_BLUE_LIGHT" -> "BLUE"; "KOMOREBI", "KOMOREBI_DAY", "KOMOREBI_NIGHT" -> "KOMOREBI"; "KYLE", "KYLE_DAY", "KYLE_NIGHT" -> "KYLE"; else -> "DEFAULT"
                            },
                            prefs,
                            itemFocusRequesters[8][0],
                            itemFocusRequesters[8][1],
                            itemFocusRequesters[8][2],
                            itemFocusRequesters[8][3],
                            categoryFocusRequesters[8],
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
                                    "DEFAULT"
                                ) {
                                    scope.launch {
                                        repository.saveString(
                                            SettingsRepository.APP_THEME,
                                            if (it == "KOMOREBI" || it == "KYLE") it else getThemeFromModeAndSeason(
                                                true,
                                                it
                                            )
                                        )
                                    }
                                }
                            },
                            {
                                uiState.activeDialog = SettingDialogState.Selection(
                                    "時刻の表示形式",
                                    listOf(
                                        "24時間表記" to "24H",
                                        "12時間表記 (AM/PM)" to "12H",
                                        "28時間表記 (24:00–27:59)" to "28H"
                                    ),
                                    prefs.timeFormat
                                ) { viewModel.updateTimeFormat(it) }
                            },
                            { viewModel.toggleHideSubChannels() },
                            screensaverEnabled,
                            screensaverTimeout,
                            screensaverInterval,
                            screensaverTransition,
                            screensaverImages.size,
                            itemFocusRequesters[8][4], itemFocusRequesters[8][5], itemFocusRequesters[8][6],
                            itemFocusRequesters[8][7], itemFocusRequesters[8][8], itemFocusRequesters[8][9], itemFocusRequesters[8][10],
                            { viewModel.setScreensaverEnabled(!screensaverEnabled) },
                            { viewModel.setScreensaverTimeoutMinutes(when (screensaverTimeout) { 1 -> 2; 2 -> 5; 5 -> 10; 10 -> 15; else -> 1 }) },
                            { viewModel.setScreensaverIntervalSeconds(when (screensaverInterval) { 5 -> 15; 15 -> 30; 30 -> 60; else -> 5 }) },
                            { viewModel.setScreensaverTransition(when (screensaverTransition) { "FADE" -> "SLIDE"; "SLIDE" -> "NONE"; else -> "FADE" }) },
                            { screensaverImagePicker.launch(arrayOf("image/*")) },
                            {
                                screensaverImages.forEach { raw -> runCatching {
                                    context.contentResolver.releasePersistableUriPermission(
                                        android.net.Uri.parse(raw), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    )
                                } }
                                viewModel.setScreensaverImageUris(emptyList())
                            },
                            { screensaverPreview = true }
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 8 }

                        4 -> EpgSettingsContent(
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
                            colR = itemFocusRequesters[4][0],
                            hourR = itemFocusRequesters[4][1],
                            fontR = itemFocusRequesters[4][2],
                            sidebarR = categoryFocusRequesters[4],
                            onClick = {
                                uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 4
                            }
                        )

                        5 -> CommentSettingsContent(
                            prefs.commentDefaultDisplay,
                            prefs.commentSpeed,
                            prefs.commentFontSize,
                            prefs.commentOpacity,
                            prefs.commentMaxLines,
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
                            itemFocusRequesters[5][0],
                            itemFocusRequesters[5][1],
                            itemFocusRequesters[5][2],
                            itemFocusRequesters[5][3],
                            itemFocusRequesters[5][4],
                            categoryFocusRequesters[5]
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 5 }

                        10 -> CacheManagementContent(
                            onClearAll = {
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    "すべてのキャッシュの削除",
                                    "局ロゴ・サムネイル・番組表・録画リストのキャッシュをすべて削除します。よろしいですか？"
                                ) {
                                    viewModel.clearAllCache()
                                    toastMessage = "すべてのキャッシュを削除しました"
                                }
                            },
                            onClearChannelLogo = {
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    "局ロゴキャッシュの削除",
                                    "端末に保存されている局ロゴを削除します。次回表示時に再取得されます。"
                                ) {
                                    viewModel.clearChannelLogoCache()
                                    toastMessage = "局ロゴキャッシュを削除しました"
                                }
                            },
                            onClearThumbnail = {
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    "サムネイルキャッシュの削除",
                                    "番組・録画のサムネイル画像キャッシュを削除します。次回表示時に再取得されます。"
                                ) {
                                    viewModel.clearThumbnailCache()
                                    toastMessage = "サムネイルキャッシュを削除しました"
                                }
                            },
                            onClearEpg = {
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    "番組表キャッシュの削除",
                                    "保存済みの番組表データを削除します。次回表示時に再取得されます。"
                                ) {
                                    viewModel.clearEpgCache()
                                    toastMessage = "番組表キャッシュを削除しました"
                                }
                            },
                            onClearRecording = {
                                uiState.activeDialog = SettingDialogState.ConfirmClear(
                                    "録画リストキャッシュの削除",
                                    "端末内に残っている録画リストのキャッシュを削除します。録画リストはサーバーから必要なページだけ取得されます。"
                                ) {
                                    viewModel.triggerFullSync()
                                    toastMessage = "録画リストキャッシュを削除しました"
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
                            allR = itemFocusRequesters[10][0],
                            logoR = itemFocusRequesters[10][1],
                            thumbR = itemFocusRequesters[10][2],
                            epgR = itemFocusRequesters[10][3],
                            recordingR = itemFocusRequesters[10][4],
                            channelHistoryR = itemFocusRequesters[10][5],
                            watchHistoryR = itemFocusRequesters[10][6],
                            sidebarR = categoryFocusRequesters[10]
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 10 }

                        11 -> AppInfoContent(
                            receiveBetaUpdates = receiveBetaUpdates,
                            updateState = viewModel.updateState.collectAsState().value,
                            onToggleBetaUpdates = { enabled ->
                                scope.launch {
                                    repository.saveBoolean(SettingsRepository.RECEIVE_BETA_UPDATES, enabled)
                                }
                            },
                            onCheckUpdates = viewModel::checkForUpdates,
                            onInstallUpdate = viewModel::installAvailableUpdate,
                            onShowProject = {
                                uiState.activeDialog = SettingDialogState.LinkQr(
                                    AppStrings.DIALOG_LINK_TITLE,
                                    AppStrings.PROJECT_GITHUB_URL
                                )
                            },
                            onShowLicenses = { uiState.activeDialog = SettingDialogState.Licenses },
                            onCapabilities = { uiState.activeDialog = SettingDialogState.DeviceCapabilities },
                            capabilityR = itemFocusRequesters[11][6],
                            versionR = itemFocusRequesters[11][0],
                            betaR = itemFocusRequesters[11][1],
                            checkR = itemFocusRequesters[11][2],
                            installR = itemFocusRequesters[11][3],
                            projectR = itemFocusRequesters[11][4],
                            licR = itemFocusRequesters[11][5],
                            sidebarR = categoryFocusRequesters[11]
                        ) { uiState.restoreFocusRequester = it; uiState.restoreCategoryIndex = 11 }
                    }
                    Spacer(Modifier.height(32.dp))
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
                            toastMessage = "${session.userName} とペアリングしました"
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
                        toastMessage = "ホーム設定を開けませんでした"
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
                SmbSetupDialog(
                    target = state.target,
                    serverIp = localIp,
                    onStartServer = { viewModel.startGeminiLocalServer() },
                    onStopServer = { viewModel.stopGeminiLocalServer() },
                    onDismiss = { closeDialog() },
                    onManualInputClick = {
                        viewModel.stopGeminiLocalServer()
                        uiState.activeDialog =
                            SettingDialogState.SmbManualInput(state.target) { server ->
                                viewModel.saveSmbServer(server)
                            }
                    },
                    onShowToast = { msg -> toastMessage = msg })
            }

            is SettingDialogState.SmbManualInput -> {
                SmbManualInputDialog(
                    state.target,
                    { closeDialog() },
                    { viewModel.saveSmbServer(it); closeDialog() })
            }

            else -> {}
        }
        if (screensaverPreview) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { screensaverPreview = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == androidx.compose.ui.input.key.Key.Back) {
                            screensaverPreview = false
                            true
                        } else false
                    }.clickable { screensaverPreview = false }
                ) {
                    com.beeregg2001.komorebi.ui.screensaver.ScreensaverContent(
                        imageUris = screensaverImages, intervalSeconds = 5,
                        transition = screensaverTransition, nextRecording = null
                    )
                }
            }
        }
        GlobalToast(message = toastMessage)
    }
}
