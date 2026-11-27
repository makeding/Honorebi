@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.setting

import android.os.Build
import android.webkit.WebStorage
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.DataBroadcastingPrefectures
import com.beeregg2001.komorebi.data.DataBroadcastingSettings
import com.beeregg2001.komorebi.data.DataBroadcastingVirtualRemoteMode
import com.beeregg2001.komorebi.data.PlayerClockMode
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.StartupFocusTarget
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.NHKExclusionMode
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.ui.components.LocalShowToast
import com.beeregg2001.komorebi.ui.player.DanmakuEngineMode
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
internal object AppInfoBringIntoViewSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        when {
            offset < 0f -> offset
            offset + size > containerSize -> offset + size - containerSize
            else -> 0f
        }
}

internal fun resolveSettingThemeSeason(themeName: String): String = when (themeName) {
    "SPRING", "SPRING_LIGHT" -> "SPRING"
    "SUMMER", "SUMMER_LIGHT" -> "SUMMER"
    "AUTUMN", "AUTUMN_LIGHT" -> "AUTUMN"
    "WINTER_DARK", "WINTER_LIGHT" -> "WINTER"
    "EPG_BLUE", "EPG_BLUE_LIGHT" -> "BLUE"
    "KOMOREBI", "KOMOREBI_DAY", "KOMOREBI_NIGHT" -> "KOMOREBI"
    "KYLE", "KYLE_DAY", "KYLE_NIGHT" -> "KYLE"
    else -> "DEFAULT"
}

internal fun isLightSettingTheme(themeName: String): Boolean =
    themeName.contains("LIGHT") || themeName == "HIGHTONE"

@RequiresApi(Build.VERSION_CODES.O)
@Composable
internal fun ColumnScope.SettingCategoryContentHost(
    activeCategory: SettingsCategory,
    controller: SettingNavigationController,
    prefs: SettingPreferences,
    repository: SettingsRepository,
    viewModel: SettingsViewModel,
    channelViewModel: ChannelViewModel,
    mainScrollState: ScrollState,
    flatChannels: List<Channel>,
    availableQualities: List<StreamQuality>,
    playerUiMode: String,
    autoCmSkip: String,
    recordedSeekStepSeconds: Int,
    preferOriginalMpegTs: String,
    receiveBetaUpdates: Boolean,
    debugMenuEnabled: Boolean,
    playerDrmEnabled: Boolean,
    honomiUserName: String?,
    onHonomiAccountClick: () -> Unit,
    homeEnvironment: HomeEnvironment,
    isDefaultHome: Boolean,
    onRefreshDefaultHome: () -> Unit,
    tifSupported: Boolean,
    pipSupported: Boolean,
    screensaverEnabled: Boolean,
    screensaverShowUpcoming: Boolean,
    screensaverTimeout: Int,
    screensaverPlayerTimeout: Int,
    screensaverPlayerStyle: String,
    playerEndSleepMinutes: Int,
    screensaverInterval: Int,
    screensaverTransition: String,
    screensaverImageCount: Int,
    batchItemRs: List<FocusRequester>,
    smbItemRs: List<FocusRequester>,
    onClearLastChannel: () -> Unit,
    onClearWatchHistory: () -> Unit,
    onOpenWhatsNew: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val showToast = LocalShowToast.current
    val focusRequesters = controller.focusRequesters
    val themeSeason = resolveSettingThemeSeason(prefs.currentThemeName)
    val isLightTheme = isLightSettingTheme(prefs.currentThemeName)

    val openCommentLayerDialog = {
        controller.activeDialog = SettingDialogState.Selection(
            AppStrings.DIALOG_LAYER_ORDER_TITLE,
            listOf(
                AppStrings.DIALOG_LAYER_COMMENT_TOP to "CommentOnTop",
                AppStrings.DIALOG_LAYER_SUBTITLE_TOP to "SubtitleOnTop",
            ),
            prefs.subtitleCommentLayer,
        ) {
            scope.launch {
                repository.saveString(
                    SettingsRepository.SUBTITLE_COMMENT_LAYER,
                    it,
                )
            }
        }
    }

    val openCommentEngineDialog = {
        controller.activeDialog = SettingDialogState.Selection(
            AppStrings.DIALOG_COMMENT_ENGINE_TITLE,
            listOf(
                AppStrings.DIALOG_COMMENT_ENGINE_DFM_HARDWARE to "DFM_HARDWARE",
                AppStrings.DIALOG_COMMENT_ENGINE_GLES to "GLES",
                AppStrings.DIALOG_COMMENT_ENGINE_DFM to "DFM",
            ),
            DanmakuEngineMode.fromId(prefs.commentEngine).id,
        ) {
            scope.launch {
                repository.saveString(
                    SettingsRepository.COMMENT_ENGINE,
                    it,
                )
            }
        }
    }

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
            focusRequesters.itemRequesters(SettingsCategory.GENERAL)[0],
            focusRequesters.itemRequesters(SettingsCategory.GENERAL)[1],
            focusRequesters.itemRequesters(SettingsCategory.GENERAL)[2],
            {
                controller.activeDialog = SettingDialogState.Selection(
                    AppStrings.SETTINGS_ITEM_STARTUP_TAB,
                    listOf(
                        "ホーム" to "ホーム",
                        "ライブ" to "ライブ",
                        "アプリ" to "アプリ",
                        "放送中" to "放送中",
                        "ビデオ" to "ビデオ",
                        "番組表" to "番組表",
                        "録画予約" to "録画予約",
                    ),
                    prefs.startupTab,
                ) {
                    scope.launch {
                        repository.saveString(
                            SettingsRepository.STARTUP_TAB,
                            it,
                        )
                    }
                }
            },
            {
                controller.activeDialog = SettingDialogState.Selection(
                    AppStrings.SETTINGS_ITEM_STARTUP_FOCUS_TARGET,
                    StartupFocusTarget.entries.map { it.label to it.name },
                    prefs.startupFocusTarget.name,
                ) { viewModel.updateStartupFocusTarget(it) }
            },
            {
                controller.activeDialog = SettingDialogState.Selection(
                    AppStrings.DIALOG_STARTUP_CHANNEL_TITLE,
                    (listOf(
                        AppStrings.SETTINGS_VALUE_STARTUP_OFF to "OFF",
                        AppStrings.SETTINGS_VALUE_STARTUP_LAST to "LAST_WATCHED",
                    ) + flatChannels.map { it.name to it.id }),
                    prefs.startupChannel,
                ) { viewModel.updateStartupChannel(it) }
            },
            viewModel.nhkExclusionState.collectAsState().value.mode.displayLabel,
            focusRequesters.itemRequesters(SettingsCategory.GENERAL)[3],
            {
                controller.activeDialog = SettingDialogState.Selection(
                    "N〇K除外モード",
                    NHKExclusionMode.entries.map { it.displayLabel to it.name },
                    viewModel.nhkExclusionState.value.mode.name,
                ) { viewModel.setNHKExclusionMode(NHKExclusionMode.fromPreference(it)) }
            },
            prefs.lateNightWatchMode,
            focusRequesters.itemRequesters(SettingsCategory.GENERAL)[4],
            {
                scope.launch {
                    repository.saveString(
                        SettingsRepository.LATE_NIGHT_WATCH_MODE,
                        if (prefs.lateNightWatchMode == "ON") "OFF" else "ON",
                    )
                }
            },
            prefs.spoilerBlurEnabled,
            focusRequesters.itemRequesters(SettingsCategory.GENERAL)[5],
            {
                scope.launch {
                    repository.saveString(
                        SettingsRepository.SPOILER_BLUR_ENABLED,
                        if (prefs.spoilerBlurEnabled == "ON") "OFF" else "ON",
                    )
                }
            },
            prefs.jikkyoFestivalAlert,
            focusRequesters.itemRequesters(SettingsCategory.GENERAL)[6],
            {
                scope.launch {
                    repository.saveString(
                        SettingsRepository.JIKKYO_FESTIVAL_ALERT,
                        if (prefs.jikkyoFestivalAlert == "ON") "OFF" else "ON",
                    )
                }
            },
            prefs.cmPanicZapEnabled,
            focusRequesters.itemRequesters(SettingsCategory.GENERAL)[7],
            {
                scope.launch {
                    repository.saveString(
                        SettingsRepository.CM_PANIC_ZAP_ENABLED,
                        if (prefs.cmPanicZapEnabled == "ON") "OFF" else "ON",
                    )
                }
            },
            focusRequesters.categoryRequester(SettingsCategory.GENERAL),
        ) { controller.recordRestoreTarget(it, SettingsCategory.GENERAL) }

        SettingsCategory.CONNECTION -> ConnectionSettingsContent(
            prefs = prefs,
            honomiUserName = honomiUserName,
            onHonomiAccountClick = onHonomiAccountClick,
            onAddSmbServer = { controller.activeDialog = SettingDialogState.SmbSetup(null) },
            onSmbServerClick = { controller.activeDialog = SettingDialogState.SmbAction(it) },
            smbItemRs = smbItemRs,
            onSelectEdcbPlayMethod = {
                controller.activeDialog = SettingDialogState.Selection(
                    "録画ファイルの再生方式",
                    listOf("API経由 (api/xcode)" to "API", "直接アクセス (高速シーク可)" to "DIRECT"),
                    prefs.edcbRecordPlayMethod,
                ) { viewModel.updateEdcbRecordPlayMethod(it) }
            },
            onWebSetup = { controller.activeDialog = SettingDialogState.ConnectionSetup(it?.backend) },
            onEdit = { title, value ->
                controller.activeDialog = SettingDialogState.Input(title, value) { input ->
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
                controller.activeDialog = SettingDialogState.Selection(
                    "メインシステム",
                    sources.map { it.label to it.backend },
                    prefs.backendType,
                ) { viewModel.updateBackendType(it) }
            },
            onRefreshTif = { channelViewModel.fetchChannels() },
            onSelectSrc = {
                controller.activeDialog = SettingDialogState.Selection(
                    AppStrings.SETTINGS_ITEM_PREFERRED_SOURCE,
                    listOf(
                        "メインシステムに従う（トランスコード）" to "KONOMITV",
                        "Mirakurun を優先" to "MIRAKURUN",
                        "EDCB (ダイレクトストリーミング)" to "EDCB",
                    ),
                    prefs.preferredSource,
                ) { value ->
                    scope.launch {
                        repository.saveString(SettingsRepository.PREFERRED_STREAM_SOURCE, value)
                    }
                }
            },
            onEditCloudflareAccess = {
                controller.activeDialog = SettingDialogState.CloudflareAccess(
                    prefs.cfAccessClientId,
                    prefs.cfAccessClientSecret,
                ) { id, secret -> repository.saveCloudflareAccessCredentials(id, secret) }
            },
            itemRs = focusRequesters.itemRequesters(SettingsCategory.CONNECTION),
            sidebarR = focusRequesters.categoryRequester(SettingsCategory.CONNECTION),
            scrollState = mainScrollState,
            isDialogOpen = controller.isDialogOpen,
            onClick = { controller.recordRestoreTarget(it, SettingsCategory.CONNECTION) },
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
            itemRs = focusRequesters.itemRequesters(SettingsCategory.DEEP_TV),
            sidebarR = focusRequesters.categoryRequester(SettingsCategory.DEEP_TV),
            onToggleFeature = { viewModel.toggleDeepTvFeature(it) },
            onCycleBackgroundPlaybackMode = {
                viewModel.setBackgroundPlaybackMode(
                    prefs.deepTvFeatures.backgroundPlaybackMode.next(pipSupported),
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
                scope.launch {
                    repository.saveBoolean(SettingsRepository.TIF_SHOW_TUNER_INPUTS, !prefs.tifShowTunerInputs)
                }
            },
            onEditNoSignalStyle = {
                controller.activeDialog = SettingDialogState.NoSignalStylePreview
            },
            onRefreshTif = { channelViewModel.fetchChannels() },
            onClick = { controller.recordRestoreTarget(it, SettingsCategory.DEEP_TV) },
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
            focusRequesters.itemRequesters(SettingsCategory.PLAYBACK)[0],
            focusRequesters.itemRequesters(SettingsCategory.PLAYBACK)[1],
            focusRequesters.itemRequesters(SettingsCategory.PLAYBACK)[2],
            focusRequesters.itemRequesters(SettingsCategory.PLAYBACK)[3],
            focusRequesters.itemRequesters(SettingsCategory.PLAYBACK)[10],
            focusRequesters.itemRequesters(SettingsCategory.PLAYBACK)[4],
            focusRequesters.itemRequesters(SettingsCategory.PLAYBACK)[6],
            focusRequesters.itemRequesters(SettingsCategory.PLAYBACK)[7],
            focusRequesters.seekStepRequester,
            focusRequesters.itemRequesters(SettingsCategory.PLAYBACK)[8],
            focusRequesters.itemRequesters(SettingsCategory.PLAYBACK)[11],
            focusRequesters.categoryRequester(SettingsCategory.PLAYBACK),
            {
                controller.activeDialog = SettingDialogState.Selection(
                    AppStrings.DIALOG_QUALITY_TITLE,
                    availableQualities.map { it.label to it.value },
                    if (availableQualities.any { it.value == prefs.liveQuality }) prefs.liveQuality else availableQualities.firstOrNull()?.value
                        ?: "",
                ) {
                    scope.launch {
                        repository.saveString(
                            SettingsRepository.LIVE_QUALITY,
                            it,
                        )
                    }
                }
            },
            {
                controller.activeDialog = SettingDialogState.Selection(
                    AppStrings.DIALOG_QUALITY_TITLE,
                    availableQualities.map { it.label to it.value },
                    if (availableQualities.any { it.value == prefs.videoQuality }) prefs.videoQuality else availableQualities.firstOrNull()?.value
                        ?: "",
                ) {
                    scope.launch {
                        repository.saveString(
                            SettingsRepository.VIDEO_QUALITY,
                            it,
                        )
                    }
                }
            },
            {
                scope.launch {
                    repository.saveString(
                        SettingsRepository.LIVE_SUBTITLE_DEFAULT,
                        if (prefs.liveSubtitleDefault == "ON") "OFF" else "ON",
                    )
                }
            },
            {
                scope.launch {
                    repository.saveString(
                        SettingsRepository.VIDEO_SUBTITLE_DEFAULT,
                        if (prefs.videoSubtitleDefault == "ON") "OFF" else "ON",
                    )
                }
            },
            {
                controller.activeDialog = SettingDialogState.Selection(
                    AppStrings.DIALOG_SUBTITLE_FONT_TITLE,
                    listOf(
                        AppStrings.SETTINGS_VALUE_FONT_DEFAULT to "default",
                        AppStrings.SETTINGS_VALUE_FONT_ARIB to "arib",
                    ),
                    prefs.subtitleFont,
                ) {
                    scope.launch {
                        repository.saveString(
                            SettingsRepository.SUBTITLE_FONT,
                            it,
                        )
                    }
                }
            },
            {
                controller.activeDialog = SettingDialogState.Selection(
                    AppStrings.DIALOG_AUDIO_OUTPUT_TITLE,
                    listOf(
                        AppStrings.SETTINGS_VALUE_AUDIO_DOWNMIX_DESC to "DOWNMIX",
                        AppStrings.SETTINGS_VALUE_AUDIO_PASSTHROUGH_DESC to "PASSTHROUGH",
                    ),
                    prefs.audioOutputMode,
                ) {
                    scope.launch {
                        repository.saveString(
                            SettingsRepository.AUDIO_OUTPUT_MODE,
                            it,
                        )
                    }
                }
            },
            {
                controller.activeDialog = SettingDialogState.Selection(
                    "プレイヤーUIモード",
                    listOf(
                        "モダン (オンスクリーン操作)" to "MODERN",
                        "クラシック (D-Pad完結)" to "CLASSIC",
                    ),
                    playerUiMode,
                ) {
                    scope.launch {
                        repository.saveString(
                            SettingsRepository.PLAYER_UI_MODE,
                            it,
                        )
                    }
                }
            },
            {
                controller.activeDialog = SettingDialogState.Selection(
                    "CMスキップ",
                    listOf(
                        "オフ" to "OFF",
                        "手動（CM開始から10秒間、決定ボタンでスキップ）" to "MANUAL",
                        "自動" to "AUTO",
                    ),
                    autoCmSkip,
                ) {
                    scope.launch {
                        repository.saveString(
                            SettingsRepository.AUTO_CM_SKIP,
                            it,
                        )
                    }
                }
            },
            {
                controller.activeDialog = SettingDialogState.Selection(
                    "早送り・巻き戻し（1回）",
                    listOf(
                        "5秒" to "5",
                        "10秒" to "10",
                        "15秒" to "15",
                        "30秒" to "30",
                    ),
                    recordedSeekStepSeconds.toString(),
                ) {
                    scope.launch {
                        repository.saveString(
                            SettingsRepository.RECORDED_SEEK_STEP_SECONDS,
                            it,
                        )
                    }
                }
            },
            {
                scope.launch {
                    repository.saveString(
                        SettingsRepository.PREFER_ORIGINAL_MPEG_TS,
                        if (preferOriginalMpegTs == "ON") "OFF" else "ON",
                    )
                }
            },
            {
                if (prefs.labAllowMirakurunDual == "OFF") {
                    controller.activeDialog = SettingDialogState.ConfirmClear(
                        "【警告】ハードウェア負荷について",
                        "Mirakurunソースでの2画面再生やPiPモードは、端末に極めて高い負荷をかけます。よろしいですか？",
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
            },
        ) { controller.recordRestoreTarget(it, SettingsCategory.PLAYBACK) }

        SettingsCategory.DATA_BROADCASTING -> DataBroadcastingSettingsContent(
            settings = prefs.dataBroadcasting,
            itemRs = focusRequesters.itemRequesters(SettingsCategory.DATA_BROADCASTING),
            sidebarR = focusRequesters.categoryRequester(SettingsCategory.DATA_BROADCASTING),
            onEditZipcode = {
                controller.activeDialog = SettingDialogState.Input(
                    title = "お住まいの郵便番号（例: 100-0001 / 空欄で解除）",
                    initialValue = prefs.dataBroadcasting.formattedZipcode,
                ) { raw ->
                    val normalized = DataBroadcastingSettings.normalizeZipcode(raw)
                    if (normalized == null) {
                        showToast("郵便番号は「000-0000」の形式で入力してください")
                    } else {
                        scope.launch {
                            repository.setDataBroadcastingZipcode(normalized)
                            showToast(if (normalized.isEmpty()) "郵便番号を未設定にしました" else "郵便番号を保存しました")
                        }
                    }
                }
            },
            onEditPrefecture = {
                controller.activeDialog = SettingDialogState.Selection(
                    title = "お住まいの都道府県",
                    options = DataBroadcastingPrefectures.selectionOptions(),
                    current = prefs.dataBroadcasting.prefectureCode?.toString()
                        ?: DataBroadcastingPrefectures.UNSET_PREFERENCE_VALUE,
                ) { selected ->
                    scope.launch {
                        repository.setDataBroadcastingPrefectureCode(selected.toIntOrNull())
                    }
                }
            },
            onToggleAllowExternalNetwork = {
                val next = !prefs.dataBroadcasting.allowExternalNetwork
                scope.launch {
                    repository.setDataBroadcastingAllowExternalNetwork(next)
                }
            },
            onEditVirtualRemoteMode = {
                controller.activeDialog = SettingDialogState.Selection(
                    title = "仮想リモコンバーの表示",
                    options = DataBroadcastingVirtualRemoteMode.entries.map { it.label to it.preferenceValue },
                    current = prefs.dataBroadcasting.virtualRemoteMode.preferenceValue,
                ) { selected ->
                    scope.launch {
                        repository.setDataBroadcastingVirtualRemoteMode(
                            DataBroadcastingVirtualRemoteMode.fromPreference(selected)
                        )
                    }
                }
            },
            onTogglePreloadB24 = {
                val next = !prefs.dataBroadcasting.preloadB24InBackground
                scope.launch {
                    repository.setDataBroadcastingPreloadB24(next)
                }
            },
            onResetStorage = {
                controller.activeDialog = SettingDialogState.ConfirmClear(
                    title = "データ放送の保存データをリセット",
                    message = "データ放送内の設定・ミニゲームの得点・スタンプ等の保存データをすべて消去します。元に戻すことはできません。",
                ) {
                    runCatching {
                        WebStorage.getInstance().deleteOrigin("https://appassets.androidplatform.net")
                    }
                    showToast("データ放送の保存データをリセットしました")
                }
            },
            onClick = { controller.recordRestoreTarget(it, SettingsCategory.DATA_BROADCASTING) },
        )

        SettingsCategory.RECORDING -> RecordingSettingsContent(
            prefs.defaultRecordListView,
            {
                controller.activeDialog = SettingDialogState.Selection(
                    AppStrings.SETTINGS_ITEM_DEFAULT_RECORD_VIEW,
                    listOf(
                        AppStrings.SETTINGS_VALUE_VIEW_LIST to "LIST",
                        AppStrings.SETTINGS_VALUE_VIEW_GRID to "GRID",
                    ),
                    prefs.defaultRecordListView,
                ) {
                    scope.launch {
                        repository.saveString(
                            SettingsRepository.DEFAULT_RECORD_LIST_VIEW,
                            it,
                        )
                    }
                }
            },
            prefs.postRecordingBatchList,
            {
                controller.activeDialog = SettingDialogState.BatchInput { n, p ->
                    viewModel.addPostRecordingBatch(n, p)
                }
            },
            { b ->
                controller.activeDialog = SettingDialogState.ConfirmClear(
                    "バッチの削除",
                    "「${b.name}」を削除しますか？",
                ) { viewModel.deletePostRecordingBatch(b) }
            },
            focusRequesters.itemRequesters(SettingsCategory.RECORDING)[0],
            focusRequesters.itemRequesters(SettingsCategory.RECORDING)[1],
            batchItemRs,
            focusRequesters.categoryRequester(SettingsCategory.RECORDING),
        ) { controller.recordRestoreTarget(it, SettingsCategory.RECORDING) }

        SettingsCategory.HOME -> HomeDisplaySettingsContent(
            prefs.pickupGenre,
            prefs.pickupTime,
            prefs.excludePaid,
            prefs.backendType != "TIF",
            focusRequesters.itemRequesters(SettingsCategory.HOME)[0],
            focusRequesters.itemRequesters(SettingsCategory.HOME)[1],
            focusRequesters.itemRequesters(SettingsCategory.HOME)[2],
            focusRequesters.categoryRequester(SettingsCategory.HOME),
            {
                controller.activeDialog = SettingDialogState.Selection(
                    AppStrings.DIALOG_PICKUP_GENRE_TITLE,
                    listOf(
                        "アニメ" to "アニメ",
                        "映画" to "映画",
                        "ドラマ" to "ドラマ",
                        "スポーツ" to "スポーツ",
                        "音楽" to "音楽",
                        "バラエティ" to "バラエティ",
                        "ドキュメンタリー" to "ドキュメンタリー",
                    ),
                    prefs.pickupGenre,
                ) {
                    scope.launch {
                        repository.saveString(
                            SettingsRepository.HOME_PICKUP_GENRE,
                            it,
                        )
                    }
                }
            },
            {
                controller.activeDialog = SettingDialogState.Selection(
                    AppStrings.DIALOG_PICKUP_TIME_TITLE,
                    listOf(
                        "自動" to "自動",
                        "朝" to "朝",
                        "昼" to "昼",
                        "夜" to "夜",
                    ),
                    prefs.pickupTime,
                ) {
                    scope.launch {
                        repository.saveString(
                            SettingsRepository.HOME_PICKUP_TIME,
                            it,
                        )
                    }
                }
            },
            {
                scope.launch {
                    repository.saveString(
                        SettingsRepository.EXCLUDE_PAID_BROADCASTS,
                        if (prefs.excludePaid == "ON") "OFF" else "ON",
                    )
                }
            },
        ) { controller.recordRestoreTarget(it, SettingsCategory.HOME) }

        SettingsCategory.LAUNCHER -> LauncherSettingsContent(
            showAppsTab = !prefs.hideAppsTab,
            appColumns = prefs.launcherAppColumns,
            showAppLabels = !prefs.hideLauncherAppLabels,
            uiSoundEffectsMode = prefs.uiSoundEffectsMode,
            environment = homeEnvironment,
            isDefaultHome = isDefaultHome,
            itemRs = focusRequesters.itemRequesters(SettingsCategory.LAUNCHER),
            sidebarR = focusRequesters.categoryRequester(SettingsCategory.LAUNCHER),
            onToggleAppsTab = { viewModel.toggleHideAppsTab() },
            onEditColumns = {
                controller.activeDialog = SettingDialogState.Selection(
                    AppStrings.SETTINGS_ITEM_LAUNCHER_APP_COLUMNS,
                    listOf(
                        AppStrings.SETTINGS_VALUE_LAUNCHER_COLUMNS_AUTO to "AUTO",
                        "4列" to "4",
                        "5列" to "5",
                        "6列" to "6",
                        "7列" to "7",
                        "8列" to "8",
                    ),
                    prefs.launcherAppColumns,
                ) { viewModel.updateLauncherAppColumns(it) }
            },
            onToggleAppLabels = {
                scope.launch {
                    repository.saveBoolean(
                        SettingsRepository.HIDE_LAUNCHER_APP_LABELS,
                        !prefs.hideLauncherAppLabels,
                    )
                }
            },
            onEditUiSoundEffectsMode = {
                controller.activeDialog = SettingDialogState.Selection(
                    "UI マイクロ効果音",
                    com.beeregg2001.komorebi.common.UiSoundEffectMode.entries.map {
                        it.label to it.name
                    },
                    prefs.uiSoundEffectsMode.name,
                ) { selected ->
                    viewModel.updateUiSoundEffectsMode(
                        com.beeregg2001.komorebi.common.UiSoundEffectMode.fromPreference(selected)
                    )
                }
            },
            onOpenDefaultHomeGuide = {
                onRefreshDefaultHome()
                controller.activeDialog = SettingDialogState.DefaultHomeGuide
            },
            onClick = { controller.recordRestoreTarget(it, SettingsCategory.LAUNCHER) },
        )

        SettingsCategory.DISPLAY -> DisplaySettingsContent(
            isLightTheme || prefs.currentThemeName == "KOMOREBI_DAY" || prefs.currentThemeName == "KYLE_DAY",
            themeSeason,
            prefs,
            focusRequesters.itemRequesters(SettingsCategory.DISPLAY)[0],
            focusRequesters.itemRequesters(SettingsCategory.DISPLAY)[1],
            focusRequesters.itemRequesters(SettingsCategory.DISPLAY)[2],
            focusRequesters.itemRequesters(SettingsCategory.DISPLAY)[3],
            focusRequesters.categoryRequester(SettingsCategory.DISPLAY),
            {
                controller.activeDialog = SettingDialogState.Selection(
                    AppStrings.SETTINGS_ITEM_BASE_THEME,
                    listOf(
                        AppStrings.SETTINGS_VALUE_THEME_DARK to "DARK",
                        AppStrings.SETTINGS_VALUE_THEME_LIGHT to "LIGHT",
                        "時間連動テーマ" to "TIME_LINKED",
                    ),
                    if (prefs.currentThemeName.startsWith("KOMOREBI") ||
                        prefs.currentThemeName.startsWith("KYLE")
                    ) {
                        "TIME_LINKED"
                    } else if (isLightTheme) {
                        "LIGHT"
                    } else {
                        "DARK"
                    },
                ) {
                    val nt = when (it) {
                        "TIME_LINKED" -> "KOMOREBI"
                        "DARK" -> getThemeFromModeAndSeason(true, "DEFAULT")
                        "LIGHT" -> getThemeFromModeAndSeason(false, "DEFAULT")
                        else -> "MONOTONE"
                    }
                    scope.launch {
                        repository.saveString(
                            SettingsRepository.APP_THEME,
                            nt,
                        )
                    }
                }
            },
            {
                controller.activeDialog = SettingDialogState.Selection(
                    AppStrings.SETTINGS_ITEM_THEME_COLOR,
                    if (prefs.currentThemeName.startsWith("KOMOREBI") ||
                        prefs.currentThemeName.startsWith("KYLE")
                    ) {
                        listOf(
                            "木漏れ日セット" to "KOMOREBI",
                            "カイルセット" to "KYLE",
                        )
                    } else {
                        listOf(
                            AppStrings.SETTINGS_VALUE_SEASON_DEFAULT to "DEFAULT",
                            AppStrings.SETTINGS_VALUE_SEASON_SPRING to "SPRING",
                            AppStrings.SETTINGS_VALUE_SEASON_SUMMER to "SUMMER",
                            AppStrings.SETTINGS_VALUE_SEASON_AUTUMN to "AUTUMN",
                            AppStrings.SETTINGS_VALUE_SEASON_WINTER to "WINTER",
                            AppStrings.SETTINGS_VALUE_SEASON_BLUE to "BLUE",
                        )
                    },
                    themeSeason,
                ) {
                    scope.launch {
                        repository.saveString(
                            SettingsRepository.APP_THEME,
                            if (it == "KOMOREBI" || it == "KYLE") {
                                it
                            } else {
                                getThemeFromModeAndSeason(!isLightTheme, it)
                            },
                        )
                    }
                }
            },
            {
                controller.activeDialog = SettingDialogState.Selection(
                    "時刻の表示形式",
                    TIME_FORMAT_OPTIONS,
                    prefs.timeFormat,
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
            screensaverImageCount,
            focusRequesters.itemRequesters(SettingsCategory.DISPLAY)[4],
            focusRequesters.itemRequesters(SettingsCategory.DISPLAY)[5],
            focusRequesters.itemRequesters(SettingsCategory.DISPLAY)[6],
            focusRequesters.itemRequesters(SettingsCategory.DISPLAY)[7],
            focusRequesters.itemRequesters(SettingsCategory.DISPLAY)[8],
            focusRequesters.itemRequesters(SettingsCategory.DISPLAY)[9],
            focusRequesters.itemRequesters(SettingsCategory.DISPLAY)[10],
            focusRequesters.itemRequesters(SettingsCategory.DISPLAY)[11],
            focusRequesters.itemRequesters(SettingsCategory.DISPLAY)[12],
            { viewModel.setScreensaverEnabled(!screensaverEnabled) },
            { viewModel.setScreensaverTimeoutMinutes(when (screensaverTimeout) { 1 -> 2; 2 -> 5; 5 -> 10; 10 -> 15; else -> 1 }) },
            { viewModel.setScreensaverPlayerTimeoutMinutes(when (screensaverPlayerTimeout) { 1 -> 2; 2 -> 5; 5 -> 10; 10 -> 15; else -> 1 }) },
            { viewModel.setScreensaverPlayerStyle(if (screensaverPlayerStyle == "PLAYER") "SLIDESHOW" else "PLAYER") },
            { viewModel.setPlayerEndSleepMinutes(when (playerEndSleepMinutes) { 0 -> 15; 15 -> 30; 30 -> 60; 60 -> 120; else -> 0 }) },
            { viewModel.setScreensaverIntervalSeconds(when (screensaverInterval) { 5 -> 15; 15 -> 30; 30 -> 60; else -> 5 }) },
            { viewModel.setScreensaverTransition(when (screensaverTransition) { "FADE" -> "SLIDE"; "SLIDE" -> "NONE"; else -> "FADE" }) },
            { controller.showInternalImagePicker = true },
            { controller.screensaverPreview = true },
            onClick = { controller.recordRestoreTarget(it, SettingsCategory.DISPLAY) },
            screensaverPlayerPreviewR = focusRequesters.screensaverPlayerPreviewRequester,
            onPreviewScreensaverPlayer = { controller.screensaverPlayerPreview = true },
            screensaverShowUpcoming = screensaverShowUpcoming,
            screensaverUpcomingR = focusRequesters.screensaverUpcomingRequester,
            onToggleScreensaverUpcoming = { viewModel.setScreensaverShowUpcoming(!screensaverShowUpcoming) },
            screensaverGalleryR = focusRequesters.screensaverGalleryRequester,
            onShowScreensaverGallery = { controller.showScreensaverGallery = true },
            playerClockModeR = focusRequesters.playerClockModeRequester,
            onEditPlayerClockMode = {
                controller.activeDialog = SettingDialogState.Selection(
                    "プレイヤーの時計",
                    PlayerClockMode.entries.map { it.label to it.name },
                    prefs.playerClockMode.name,
                ) { viewModel.updatePlayerClockMode(it) }
            },
            screensaverWebUploadR = focusRequesters.screensaverWebUploadRequester,
            onOpenScreensaverWebUpload = {
                controller.activeDialog = SettingDialogState.ScreensaverWebSetup
            },
        )

        SettingsCategory.EPG -> EpgSettingsContent(
            pref = prefs,
            onEditColumn = {
                controller.activeDialog = SettingDialogState.Selection(
                    "表示チャンネル数",
                    listOf(
                        "5チャンネル" to "5",
                        "7チャンネル" to "7",
                        "9チャンネル" to "9",
                        "11チャンネル" to "11",
                    ),
                    prefs.epgColumnCount,
                ) { viewModel.updateEpgColumnCount(it) }
            },
            onEditHour = {
                controller.activeDialog = SettingDialogState.Selection(
                    "表示時間数 (縦幅)",
                    listOf(
                        "4時間" to "4",
                        "6時間" to "6",
                        "8時間" to "8",
                        "12時間" to "12",
                    ),
                    prefs.epgVisibleHours,
                ) { viewModel.updateEpgVisibleHours(it) }
            },
            onEditFontSize = {
                controller.activeDialog = SettingDialogState.Selection(
                    "文字サイズ",
                    listOf(
                        "80%" to "0.8",
                        "90%" to "0.9",
                        "100% (標準)" to "1.0",
                        "110%" to "1.1",
                        "120%" to "1.2",
                    ),
                    prefs.epgFontSizeScale,
                ) { viewModel.updateEpgFontSizeScale(it) }
            },
            colR = focusRequesters.itemRequesters(SettingsCategory.EPG)[0],
            hourR = focusRequesters.itemRequesters(SettingsCategory.EPG)[1],
            fontR = focusRequesters.itemRequesters(SettingsCategory.EPG)[2],
            sidebarR = focusRequesters.categoryRequester(SettingsCategory.EPG),
            onClick = { controller.recordRestoreTarget(it, SettingsCategory.EPG) },
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
                        if (prefs.commentWordCounter == "ON") "OFF" else "ON",
                    )
                }
            },
            { t, v ->
                controller.activeDialog = SettingDialogState.Input(t, v) {
                    scope.launch {
                        repository.saveString(
                            when (t) {
                                AppStrings.SETTINGS_INPUT_COMMENT_SPEED -> SettingsRepository.COMMENT_SPEED
                                AppStrings.SETTINGS_INPUT_COMMENT_SIZE -> SettingsRepository.COMMENT_FONT_SIZE
                                AppStrings.SETTINGS_INPUT_COMMENT_OPACITY -> SettingsRepository.COMMENT_OPACITY
                                else -> SettingsRepository.COMMENT_MAX_LINES
                            },
                            it,
                        )
                    }
                }
            },
            {
                scope.launch {
                    repository.saveString(
                        SettingsRepository.COMMENT_DEFAULT_DISPLAY,
                        if (prefs.commentDefaultDisplay == "ON") "OFF" else "ON",
                    )
                }
            },
            focusRequesters.itemRequesters(SettingsCategory.COMMENT)[CommentItemIndex.DEFAULT_DISPLAY],
            focusRequesters.itemRequesters(SettingsCategory.COMMENT)[CommentItemIndex.SPEED],
            focusRequesters.itemRequesters(SettingsCategory.COMMENT)[CommentItemIndex.FONT_SIZE],
            focusRequesters.itemRequesters(SettingsCategory.COMMENT)[CommentItemIndex.OPACITY],
            focusRequesters.itemRequesters(SettingsCategory.COMMENT)[CommentItemIndex.MAX_LINES],
            focusRequesters.itemRequesters(SettingsCategory.COMMENT)[CommentItemIndex.LAYER_ORDER],
            focusRequesters.itemRequesters(SettingsCategory.COMMENT)[CommentItemIndex.COMMENT_ENGINE],
            focusRequesters.itemRequesters(SettingsCategory.COMMENT)[CommentItemIndex.WORD_COUNTER],
            focusRequesters.categoryRequester(SettingsCategory.COMMENT),
        ) { controller.recordRestoreTarget(it, SettingsCategory.COMMENT) }

        SettingsCategory.CACHE -> CacheManagementContent(
            onClearAll = {
                controller.activeDialog = SettingDialogState.ConfirmClear(
                    "すべてのキャッシュの削除",
                    "局ロゴ・サムネイル・番組表・録画リストのキャッシュをすべて削除します。よろしいですか？",
                ) {
                    viewModel.clearAllCache()
                    showToast("すべてのキャッシュを削除しました")
                }
            },
            onClearChannelLogo = {
                controller.activeDialog = SettingDialogState.ConfirmClear(
                    "局ロゴキャッシュの削除",
                    "端末に保存されている局ロゴを削除します。次回表示時に再取得されます。",
                ) {
                    viewModel.clearChannelLogoCache()
                    showToast("局ロゴキャッシュを削除しました")
                }
            },
            onClearThumbnail = {
                controller.activeDialog = SettingDialogState.ConfirmClear(
                    "サムネイルキャッシュの削除",
                    "番組・録画のサムネイル画像キャッシュを削除します。次回表示時に再取得されます。",
                ) {
                    viewModel.clearThumbnailCache()
                    showToast("サムネイルキャッシュを削除しました")
                }
            },
            onClearEpg = {
                controller.activeDialog = SettingDialogState.ConfirmClear(
                    "番組表キャッシュの削除",
                    "保存済みの番組表データを削除します。次回表示時に再取得されます。",
                ) {
                    viewModel.clearEpgCache()
                    showToast("番組表キャッシュを削除しました")
                }
            },
            onClearRecording = {
                controller.activeDialog = SettingDialogState.ConfirmClear(
                    "録画リストキャッシュの削除",
                    "端末内に残っている録画リストのキャッシュを削除します。録画リストはサーバーから必要なページだけ取得されます。",
                ) {
                    viewModel.triggerFullSync()
                    showToast("録画リストキャッシュを削除しました")
                }
            },
            // 履歴の削除はキャッシュ一括削除とは独立。グローバル削除では消さない。
            onClearChannelHistory = {
                controller.activeDialog = SettingDialogState.ConfirmClear(
                    AppStrings.DIALOG_CLEAR_HISTORY_TITLE,
                    AppStrings.DIALOG_CLEAR_CHANNEL_HISTORY_MSG,
                ) { onClearLastChannel() }
            },
            onClearWatchHistory = {
                controller.activeDialog = SettingDialogState.ConfirmClear(
                    AppStrings.DIALOG_CLEAR_HISTORY_TITLE,
                    AppStrings.DIALOG_CLEAR_WATCH_HISTORY_MSG,
                ) { onClearWatchHistory() }
            },
            allR = focusRequesters.itemRequesters(SettingsCategory.CACHE)[0],
            logoR = focusRequesters.itemRequesters(SettingsCategory.CACHE)[1],
            thumbR = focusRequesters.itemRequesters(SettingsCategory.CACHE)[2],
            epgR = focusRequesters.itemRequesters(SettingsCategory.CACHE)[3],
            recordingR = focusRequesters.itemRequesters(SettingsCategory.CACHE)[4],
            channelHistoryR = focusRequesters.itemRequesters(SettingsCategory.CACHE)[5],
            watchHistoryR = focusRequesters.itemRequesters(SettingsCategory.CACHE)[6],
            sidebarR = focusRequesters.categoryRequester(SettingsCategory.CACHE),
        ) { controller.recordRestoreTarget(it, SettingsCategory.CACHE) }

        SettingsCategory.APP_INFO -> AppInfoContent(
            receiveBetaUpdates = receiveBetaUpdates,
            debugMenuEnabled = debugMenuEnabled,
            updateState = viewModel.updateState.collectAsState().value,
            onToggleBetaUpdates = { enabled ->
                scope.launch {
                    repository.saveBoolean(SettingsRepository.RECEIVE_BETA_UPDATES, enabled)
                }
            },
            onUnlockDebugMenu = {
                scope.launch { repository.saveDebugMenuEnabled(true) }
            },
            onCheckUpdates = viewModel::checkForUpdates,
            onScrollToTop = { scope.launch { mainScrollState.animateScrollTo(0) } },
            onShowProject = {
                controller.activeDialog = SettingDialogState.LinkQr(
                    AppStrings.DIALOG_LINK_TITLE,
                    AppStrings.PROJECT_GITHUB_URL,
                )
            },
            onShowLicenses = { controller.activeDialog = SettingDialogState.Licenses },
            onCapabilities = { controller.activeDialog = SettingDialogState.DeviceCapabilities },
            onOpenWhatsNew = onOpenWhatsNew,
            capabilityR = focusRequesters.itemRequesters(SettingsCategory.APP_INFO)[AppInfoItemIndex.DEVICE_CAPABILITIES],
            versionR = focusRequesters.itemRequesters(SettingsCategory.APP_INFO)[AppInfoItemIndex.VERSION],
            whatsNewR = focusRequesters.itemRequesters(SettingsCategory.APP_INFO)[AppInfoItemIndex.WHATS_NEW],
            betaR = focusRequesters.itemRequesters(SettingsCategory.APP_INFO)[AppInfoItemIndex.BETA_UPDATES],
            checkR = focusRequesters.itemRequesters(SettingsCategory.APP_INFO)[AppInfoItemIndex.CHECK_UPDATES],
            projectR = focusRequesters.itemRequesters(SettingsCategory.APP_INFO)[AppInfoItemIndex.PROJECT_GITHUB],
            licR = focusRequesters.itemRequesters(SettingsCategory.APP_INFO)[AppInfoItemIndex.OSS_LICENSES],
            sidebarR = focusRequesters.categoryRequester(SettingsCategory.APP_INFO),
        ) { controller.recordRestoreTarget(it, SettingsCategory.APP_INFO) }

        SettingsCategory.DEBUG -> DebugSettingsContent(
            playerDrmEnabled = playerDrmEnabled,
            onTogglePlayerDrm = { enabled ->
                scope.launch { repository.savePlayerDrmEnabled(enabled) }
            },
            drmR = focusRequesters.itemRequesters(SettingsCategory.DEBUG)[DebugItemIndex.PLAYER_DRM],
            sidebarR = focusRequesters.categoryRequester(SettingsCategory.DEBUG),
            onScrollToTop = { scope.launch { mainScrollState.animateScrollTo(0) } },
        ) { controller.recordRestoreTarget(it, SettingsCategory.DEBUG) }
    }
}
