@file:OptIn(UnstableApi::class, ExperimentalAnimationApi::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.live

import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import com.beeregg2001.komorebi.media.SystemMediaSession
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.viewmodel.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionCue
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionOverlay
import com.beeregg2001.komorebi.ui.subtitle.rememberNativeCaptionCue
import com.beeregg2001.komorebi.ui.player.PlayerCropMode
import com.beeregg2001.komorebi.ui.player.PlayerSurface
import com.beeregg2001.komorebi.ui.player.PlayerZoomOrigin
import com.beeregg2001.komorebi.ui.player.B60MediaPlane
import com.beeregg2001.komorebi.ui.player.B60_INITIAL_MEDIA_PLANE
import com.beeregg2001.komorebi.ui.player.DataBroadcastingColorKey
import com.beeregg2001.komorebi.ui.player.DataBroadcastingColorSelectorOverlay
import com.beeregg2001.komorebi.ui.player.DataBroadcastingRemoteCommand
import com.beeregg2001.komorebi.ui.player.DataBroadcastingWebViewOverlay
import com.beeregg2001.komorebi.ui.player.DanmakuOverlay
import com.beeregg2001.komorebi.ui.player.b60MediaPlane
import com.beeregg2001.komorebi.ui.player.rememberDataBroadcastingInputState
import com.beeregg2001.komorebi.ui.player.isDataBroadcastingToggleKeyEvent
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.json.JSONObject
import master.flame.danmaku.controller.IDanmakuView

private const val TAG = "LivePlayerScreen"
private const val LIVE_SUBTITLE_AVOIDANCE_START_FRACTION = 0.75f
private val LIVE_PROGRAM_INFO_SUBTITLE_OFFSET = 200.dp

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun LivePlayerScreen(
    channel: Channel,
    initialQuality: String = "1080p-60fps",
    isMiniListOpen: Boolean,
    onMiniListToggle: (Boolean) -> Unit,
    showOverlay: Boolean,
    onShowOverlayChange: (Boolean) -> Unit,
    isManualOverlay: Boolean,
    onManualOverlayChange: (Boolean) -> Unit,
    isPinnedOverlay: Boolean,
    onPinnedOverlayChange: (Boolean) -> Unit,
    isSubMenuOpen: Boolean,
    onSubMenuToggle: (Boolean) -> Unit,
    onChannelSelect: (Channel) -> Unit,
    onChannelPlaybackCommitted: (Channel) -> Unit = {},
    onChasePlaybackSelect: (RecordedProgram) -> Unit = {},
    onBackPressed: () -> Unit,
    onCheckDeviceCapabilities: () -> Unit = {},
    onShowToast: (String) -> Unit,
    isPiPMode: Boolean = false,
    onPiPRequested: () -> Unit = {},
    channelViewModel: ChannelViewModel,
    reserveViewModel: ReserveViewModel,
    recordViewModel: RecordViewModel,
    settingsViewModel: SettingsViewModel,
    livePlayerViewModel: LivePlayerViewModel,
    timeFormat: String = "24H",
    isDataBroadcastingMode: Boolean = false,
    onDataBroadcastingBack: () -> Unit = {},
    onDataBroadcastingColorKey: (DataBroadcastingColorKey) -> Unit = {}
) {
    val uiContext = LocalContext.current
    val colors = KomorebiTheme.colors
    val scope = rememberCoroutineScope()

    val ps = rememberLivePlayerState(uiContext)

    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val lastWatchedChannels by channelViewModel.lastWatchedChannels.collectAsState()
    val channelNavigation = remember(channel, groupedChannels, lastWatchedChannels) {
        deriveLiveChannelNavigation(channel, groupedChannels, lastWatchedChannels)
    }
    val displayFlatChannels = channelNavigation.flatChannels
    val displayLastWatchedChannels = channelNavigation.recentChannels
    val currentChannelItem = channelNavigation.currentChannel

    val mirakurunIp by settingsViewModel.mirakurunIp.collectAsState()
    val mirakurunPort by settingsViewModel.mirakurunPort.collectAsState()
    val edcbIp by settingsViewModel.edcbIp.collectAsState()
    val edcbPort by settingsViewModel.edcbPort.collectAsState()
    val backendType by settingsViewModel.backendType.collectAsState()
    val konomiIp by settingsViewModel.konomiIp.collectAsState()
    val konomiPort by settingsViewModel.konomiPort.collectAsState()

    val availableSources by livePlayerViewModel.availableSources.collectAsState()
    val currentLogoUrl by livePlayerViewModel.currentLogoUrl.collectAsState()
    val channelLogoUrls by channelViewModel.channelLogoUrls.collectAsState()
    val shouldCropLogo by livePlayerViewModel.shouldCropLogo.collectAsState()
    val mainBackendType by livePlayerViewModel.mainBackendType.collectAsState()
    val sharedCurrentLogoUrl = remember(channelLogoUrls, currentChannelItem, currentLogoUrl) {
        channelLogoUrls.logoUrlFor(currentChannelItem).ifBlank { currentLogoUrl }
    }

    val commentSpeedStr by settingsViewModel.commentSpeed.collectAsState()
    val commentFontSizeStr by settingsViewModel.commentFontSize.collectAsState()
    val commentOpacityStr by settingsViewModel.commentOpacity.collectAsState()
    val commentMaxLinesStr by settingsViewModel.commentMaxLines.collectAsState()
    val commentDefaultDisplayStr by settingsViewModel.commentDefaultDisplay.collectAsState()
    val audioOutputMode by settingsViewModel.audioOutputMode.collectAsState()
    val hdrRenderMode by livePlayerViewModel.hdrRenderMode.collectAsState()
    val isHdrToSdrToneMappingSupported = remember(livePlayerViewModel) {
        livePlayerViewModel.isHdrToSdrToneMappingSupported
    }
    val liveSubtitleDefaultStr by settingsViewModel.liveSubtitleDefault.collectAsState()
    val allowMirakurunDual by settingsViewModel.labAllowMirakurunDual.collectAsState()

    val playerUiMode by settingsViewModel.playerUiMode.collectAsState()
    val isModern = playerUiMode == "MODERN"

    val commentSpeed = commentSpeedStr.toFloatOrNull() ?: 1.0f
    val commentFontSizeScale = commentFontSizeStr.toFloatOrNull() ?: 1.0f
    val commentOpacity = commentOpacityStr.toFloatOrNull() ?: 1.0f
    val commentMaxLines = commentMaxLinesStr.toIntOrNull() ?: 0

    var isCommentEnabled by rememberSaveable(commentDefaultDisplayStr) {
        mutableStateOf(commentDefaultDisplayStr == "ON")
    }
    val subtitleEnabledState =
        rememberSaveable(liveSubtitleDefaultStr) { mutableStateOf(liveSubtitleDefaultStr == "ON") }
    val isSubtitleEnabled by subtitleEnabledState
    var isMiniPlayerSelectionPending by rememberSaveable { mutableStateOf(false) }

    val reserves by reserveViewModel.reserves.collectAsState()
    val recentRecordings by recordViewModel.recentRecordings.collectAsState()
    val activeReserve = remember(reserves, currentChannelItem.programPresent?.id) {
        reserves.find { it.program.id == currentChannelItem.programPresent?.id }
    }
    val currentRecordingProgram = remember(currentChannelItem, recentRecordings) {
        recordViewModel.findCurrentRecordingForChannel(currentChannelItem, recentRecordings)
    }
    val isRecording = activeReserve != null || currentRecordingProgram != null
    var isChasePlaybackResolving by remember { mutableStateOf(false) }

    val currentIsManualOverlay by rememberUpdatedState(isManualOverlay)
    val currentIsPinnedOverlay by rememberUpdatedState(isPinnedOverlay)
    val currentIsSubMenuOpen by rememberUpdatedState(isSubMenuOpen)

    var isHeavyUiReady by remember { mutableStateOf(false) }
    val isEmulator =
        remember { Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("google_sdk") || Build.PRODUCT == "google_sdk" }

    val danmakuViewRef = remember { mutableStateOf<IDanmakuView?>(null) }
    val mainPlayer by livePlayerViewModel.mainPlayer.collectAsState()
    val dualPlayer by livePlayerViewModel.dualPlayer.collectAsState()
    val mainRuntimeState by livePlayerViewModel.mainRuntimeState.collectAsState()
    val dualRuntimeState by livePlayerViewModel.dualRuntimeState.collectAsState()
    val isMainPlaying = mainRuntimeState.isPlaying
    val isDualPlaying = dualRuntimeState.isPlaying
    val mainSessionToken by livePlayerViewModel.mainSessionToken.collectAsState()
    val dualSessionToken by livePlayerViewModel.dualSessionToken.collectAsState()
    val mainSubtitleLanguages by livePlayerViewModel.mainSubtitleLanguages.collectAsState()
    val dualSubtitleLanguages by livePlayerViewModel.dualSubtitleLanguages.collectAsState()
    val currentSubtitleLanguageId by livePlayerViewModel.currentSubtitleLanguageId.collectAsState()

    val previousChannel = channelNavigation.previousChannel
    val nextChannel = channelNavigation.nextChannel
    val activeSubtitleLanguages = if (ps.isDualDisplayMode && ps.activeDualPlayerIndex == 1) {
        dualSubtitleLanguages
    } else {
        mainSubtitleLanguages
    }
    val mainCaptionEvents = remember(livePlayerViewModel) {
        livePlayerViewModel.mainSubtitleEvents.filter {
            it.type == NativeCaptionCue.TYPE_CAPTION
        }
    }
    val mainSuperimposeEvents = remember(livePlayerViewModel) {
        livePlayerViewModel.mainSubtitleEvents.filter {
            it.type == NativeCaptionCue.TYPE_SUPERIMPOSE
        }
    }
    val dualCaptionEvents = remember(livePlayerViewModel) {
        livePlayerViewModel.dualSubtitleEvents.filter {
            it.type == NativeCaptionCue.TYPE_CAPTION
        }
    }
    val dualSuperimposeEvents = remember(livePlayerViewModel) {
        livePlayerViewModel.dualSubtitleEvents.filter {
            it.type == NativeCaptionCue.TYPE_SUPERIMPOSE
        }
    }
    val mainCaptionCue = rememberNativeCaptionCue(
        events = mainCaptionEvents,
        enabled = isSubtitleEnabled,
        resetKey = mainSessionToken to currentSubtitleLanguageId,
        clockRunning = isMainPlaying,
        positionMsProvider = { mainPlayer?.currentPosition ?: 0L }
    )
    val dualCaptionCue = rememberNativeCaptionCue(
        events = dualCaptionEvents,
        enabled = isSubtitleEnabled,
        resetKey = dualSessionToken to currentSubtitleLanguageId,
        clockRunning = isDualPlaying,
        positionMsProvider = { dualPlayer?.currentPosition ?: 0L }
    )
    val mainSuperimposeCue = rememberNativeCaptionCue(
        events = mainSuperimposeEvents,
        enabled = true,
        resetKey = mainSessionToken,
        clockRunning = isMainPlaying,
        positionMsProvider = { mainPlayer?.currentPosition ?: 0L }
    )
    val dualSuperimposeCue = rememberNativeCaptionCue(
        events = dualSuperimposeEvents,
        enabled = true,
        resetKey = dualSessionToken,
        clockRunning = isDualPlaying,
        positionMsProvider = { dualPlayer?.currentPosition ?: 0L }
    )

    val mainFocusRequester = remember { FocusRequester() }
    val listFocusRequester = remember { FocusRequester() }
    val subMenuFocusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    var localDataBroadcastingMode by rememberSaveable(currentChannelItem.id) {
        mutableStateOf(false)
    }
    val dataBroadcastingInput = rememberDataBroadcastingInputState(currentChannelItem.id)
    var dataBroadcastingRemoteSequence by rememberSaveable { mutableLongStateOf(0L) }
    var dataBroadcastingRemoteCommand by remember { mutableStateOf<DataBroadcastingRemoteCommand?>(null) }
    var dataBroadcastingMediaPlane by remember(currentChannelItem.id) {
        mutableStateOf<B60MediaPlane?>(null)
    }
    var isDataBroadcastingBlank by rememberSaveable(currentChannelItem.id) {
        mutableStateOf(false)
    }
    var hasShownDataBroadcastingHint by rememberSaveable { mutableStateOf(false) }

    val mainError by livePlayerViewModel.mainPlayerError.collectAsState()
    val mainErrorIsCapabilityRelated by livePlayerViewModel.mainPlayerErrorIsCapabilityRelated.collectAsState()

    LaunchedEffect(livePlayerViewModel) {
        livePlayerViewModel.playbackNotices.collect(onShowToast)
    }
    val mainStatus by livePlayerViewModel.mainSseStatus.collectAsState()
    val mainDetail by livePlayerViewModel.mainSseDetail.collectAsState()
    val mainSignal by livePlayerViewModel.mainSignalInfo.collectAsState()
    val dualStatus by livePlayerViewModel.dualSseStatus.collectAsState()
    val dualDetail by livePlayerViewModel.dualSseDetail.collectAsState()

    val availableQualities by livePlayerViewModel.availableQualities.collectAsState(initial = StreamQuality.DEFAULT_QUALITIES)
    val isQualitiesLoaded by livePlayerViewModel.isQualitiesLoaded.collectAsState()
    val effectiveAvailableQualities = remember(availableQualities, currentChannelItem) {
        effectiveLiveQualities(availableQualities, currentChannelItem)
    }
    val streamRestartQualityKey = if (ps.currentQuality.isRawMmts) {
        StreamQuality.RAW_MMTS_PRIMARY_VALUE
    } else {
        ps.currentQuality.value
    }

    val currentLiveQualityStr by settingsViewModel.liveQuality.collectAsState()
    val isB60Channel = currentChannelItem.type.equals("BS4K", ignoreCase = true)
    val isDataBroadcastingActive = isB60Channel &&
        (isDataBroadcastingMode || localDataBroadcastingMode)
    val dispatchDataBroadcastingRemoteKey: (String) -> Unit = { key ->
        dataBroadcastingRemoteSequence += 1L
        dataBroadcastingRemoteCommand = DataBroadcastingRemoteCommand(
            id = dataBroadcastingRemoteSequence,
            key = key
        )
    }
    val dispatchDataBroadcastingBack: () -> Unit = {
        onDataBroadcastingBack()
        dispatchDataBroadcastingRemoteKey("back")
    }
    val exitLocalDataBroadcasting: () -> Unit = {
        localDataBroadcastingMode = false
        isDataBroadcastingBlank = false
        dataBroadcastingRemoteCommand = null
        dataBroadcastingMediaPlane = null
        dataBroadcastingInput.reset()
        onShowToast("データ放送を終了しました")
    }
    val dispatchDataBroadcastingColorKey: (DataBroadcastingColorKey) -> Unit = { colorKey ->
        onDataBroadcastingColorKey(colorKey)
        dispatchDataBroadcastingRemoteKey(
            when (colorKey) {
                DataBroadcastingColorKey.Blue -> "blue"
                DataBroadcastingColorKey.Red -> "red"
                DataBroadcastingColorKey.Green -> "green"
                DataBroadcastingColorKey.Yellow -> "yellow"
            }
        )
    }
    val openLocalDataBroadcasting: () -> Unit = {
        if (!isDataBroadcastingActive && !isDataBroadcastingMode &&
            !ps.isDualDisplayMode && isB60Channel
        ) {
            localDataBroadcastingMode = true
            isDataBroadcastingBlank = false
            dataBroadcastingInput.closeColorSelector()
            if (!hasShownDataBroadcastingHint) {
                hasShownDataBroadcastingHint = true
                onShowToast(
                    "戻るを長押しで色ボタン（↑青 / ←赤 / →緑 / ↓黄）、戻る2回でテレビ画面"
                )
            }
        }
    }

    LaunchedEffect(mainError, mainStatus, mainDetail, mainSignal) {
        ps.playerError = mainError
        ps.sseStatus = mainStatus
        ps.sseDetail = mainDetail
        ps.signalInfo = mainSignal
    }

    LaunchedEffect(dualStatus, dualDetail) {
        ps.dualSseStatus = dualStatus
        ps.dualSseDetail = dualDetail
    }

    var isSourceInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!isSourceInitialized) {
            ps.currentStreamSource = livePlayerViewModel.getInitialStreamSource()
            ps.isEdcbDirect = livePlayerViewModel.getInitialEdcbDirect()
            isSourceInitialized = true
        }
    }

    LaunchedEffect(ps.currentStreamSource, ps.isEdcbDirect) {
        livePlayerViewModel.fetchAvailableQualities(ps.currentStreamSource, ps.isEdcbDirect)
    }

    LaunchedEffect(
        effectiveAvailableQualities,
        isQualitiesLoaded,
        currentLiveQualityStr,
        currentChannelItem.id
    ) {
        if (isQualitiesLoaded && effectiveAvailableQualities.isNotEmpty()) {
            val matched = effectiveAvailableQualities.find { it.value == currentLiveQualityStr }
            if (matched != null) {
                ps.currentQuality = matched
            } else {
                val fallback = effectiveAvailableQualities.first()
                ps.currentQuality = fallback
                if (!fallback.isRawMmts) {
                    Log.w(
                        TAG,
                        "User's liveQuality ($currentLiveQualityStr) is not in the list. Falling back to default."
                    )
                    livePlayerViewModel.saveLiveQuality(fallback.value)
                }
            }
        }
    }

    LaunchedEffect(isPiPMode) {
        if (isPiPMode) {
            if ((ps.currentStreamSource == StreamSource.MIRAKURUN || ps.currentStreamSource == StreamSource.EDCB) && allowMirakurunDual != "ON") {
                ps.previousStreamSource = ps.currentStreamSource
                if (availableSources.contains(StreamSource.KONOMITV)) {
                    ps.currentStreamSource = StreamSource.KONOMITV
                    onShowToast("負荷軽減のためKonomiTVソースに切り替えました")
                }
            }
        } else {
            if (!ps.isDualDisplayMode && ps.previousStreamSource != null) {
                if (availableSources.contains(ps.previousStreamSource!!)) {
                    ps.currentStreamSource = ps.previousStreamSource!!
                    onShowToast("元のストリーミングソースに復帰しました")
                }
                ps.previousStreamSource = null
            }
        }
    }

    val videoWidth = mainRuntimeState.videoSize.width
    val videoHeight = mainRuntimeState.videoSize.height
    val pixelWidthHeightRatio = mainRuntimeState.videoSize.pixelWidthHeightRatio
    val dualVideoWidth = dualRuntimeState.videoSize.width
    val dualVideoHeight = dualRuntimeState.videoSize.height
    val dualPixelWidthHeightRatio = dualRuntimeState.videoSize.pixelWidthHeightRatio
    val isMainBuffering = mainRuntimeState.playbackState == Player.STATE_BUFFERING
    val isDualBuffering = dualRuntimeState.playbackState == Player.STATE_BUFFERING
    val mainPlaybackState = mainRuntimeState.playbackState
    val hasMainRenderedFirstFrame = mainRuntimeState.renderedFrameGeneration > 0
    var lastChannelIdForSwitchHint by remember { mutableStateOf(currentChannelItem.id) }
    var isChannelSwitchDebouncing by remember { mutableStateOf(false) }

    LaunchedEffect(currentChannelItem.id) {
        if (currentChannelItem.id == lastChannelIdForSwitchHint) return@LaunchedEffect
        lastChannelIdForSwitchHint = currentChannelItem.id
        isChannelSwitchDebouncing = true
        delay(100L)
        isChannelSwitchDebouncing = false
    }

    LaunchedEffect(isMainPlaying) { ps.isPlayerPlaying = isMainPlaying }

    SystemMediaSession(
        player = mainPlayer,
        title = currentChannelItem.programPresent?.title ?: currentChannelItem.name,
        subtitle = currentChannelItem.name,
        artworkUrl = sharedCurrentLogoUrl,
        mediaType = MediaMetadata.MEDIA_TYPE_TV_CHANNEL,
        isLoading = isLiveMediaSessionLoading(
            currentChannelId = currentChannelItem.id,
            lastChannelIdForSwitchHint = lastChannelIdForSwitchHint,
            hasRenderedFirstFrame = hasMainRenderedFirstFrame,
            isBuffering = isMainBuffering
        ),
        onPrevious = previousChannel?.let { target -> { onChannelSelect(target) } },
        onNext = nextChannel?.let { target -> { onChannelSelect(target) } },
        onStop = onBackPressed
    )

    DisposableEffect(Unit) {
        channelViewModel.setPollingPaused(true)
        onDispose {
            Log.d(
                TAG,
                "LivePlayerScreen disposed: channel=${currentChannelItem.displayChannelId}, " +
                    "releasing players to free hardware decoders."
            )
            livePlayerViewModel.releasePlayers("screen_dispose")
            channelViewModel.setPollingPaused(false)
        }
    }

    LaunchedEffect(
        currentChannelItem.id,
        ps.currentStreamSource,
        ps.isEdcbDirect,
        ps.retryKey,
        streamRestartQualityKey,
        isSourceInitialized,
        isQualitiesLoaded
    ) {
        if (!isSourceInitialized || !isQualitiesLoaded) return@LaunchedEffect
        if (currentChannelItem.displayChannelId.isBlank() || currentChannelItem.displayChannelId == "null") return@LaunchedEffect

        if (ps.currentQuality.value.isBlank()) return@LaunchedEffect

        if (effectiveAvailableQualities.isNotEmpty() &&
            effectiveAvailableQualities.none { it.value == ps.currentQuality.value }
        ) {
            return@LaunchedEffect
        }

        livePlayerViewModel.playMainChannel(
            uiContext = uiContext,
            channel = currentChannelItem,
            source = ps.currentStreamSource,
            isEdcbDirect = ps.isEdcbDirect,
            quality = ps.currentQuality,
            onPlaybackRequestCommitted = onChannelPlaybackCommitted
        )
        delay(300); mainFocusRequester.safeRequestFocus(TAG)
    }

    LaunchedEffect(
        ps.dualRightChannel,
        ps.currentStreamSource,
        ps.isEdcbDirect,
        ps.isDualDisplayMode,
        ps.retryKey,
        streamRestartQualityKey,
        isSourceInitialized,
        isQualitiesLoaded
    ) {
        if (!isSourceInitialized || !isQualitiesLoaded) return@LaunchedEffect

        val rightChannel = ps.dualRightChannel
        if (ps.isDualDisplayMode && rightChannel != null) {
            if (rightChannel.displayChannelId.isBlank() || rightChannel.displayChannelId == "null") return@LaunchedEffect
            if (ps.currentQuality.value.isBlank()) return@LaunchedEffect

            livePlayerViewModel.playDualChannel(
                uiContext = uiContext,
                channel = rightChannel,
                source = ps.currentStreamSource,
                isEdcbDirect = ps.isEdcbDirect,
                quality = ps.currentQuality
            )
        } else {
            livePlayerViewModel.stopDualPlayer()
        }
    }

    LaunchedEffect(ps.isDualDisplayMode, ps.activeDualPlayerIndex, mainPlayer, dualPlayer) {
        val mainVol = if (ps.isDualDisplayMode && ps.activeDualPlayerIndex != 0) 0f else 1f
        val dualVol = if (ps.isDualDisplayMode && ps.activeDualPlayerIndex == 1) 1f else 0f
        livePlayerViewModel.setVolumes(mainVol, dualVol)
    }

    LaunchedEffect(isSubtitleEnabled) {
        livePlayerViewModel.setSubtitlesEnabled(isSubtitleEnabled)
    }

    LiveDanmakuEffect(
        comments = livePlayerViewModel.liveComments,
        sessionToken = mainSessionToken,
        enabled = isCommentEnabled,
        heavyUiReady = isHeavyUiReady,
        dualDisplay = ps.isDualDisplayMode,
        fontSizeScale = commentFontSizeScale,
        danmakuView = danmakuViewRef,
    )

    var hasStoppedByLifecycle by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                Log.w(
                    TAG,
                    "Live screen lifecycle ON_STOP: channel=${currentChannelItem.displayChannelId}, " +
                        "state=${lifecycleOwner.lifecycle.currentState}"
                )
                hasStoppedByLifecycle = true
                livePlayerViewModel.releasePlayers("lifecycle_on_stop")
            } else if (event == Lifecycle.Event.ON_START) {
                Log.i(
                    TAG,
                    "Live screen lifecycle ON_START: channel=${currentChannelItem.displayChannelId}, " +
                        "wasStopped=$hasStoppedByLifecycle"
                )
                if (hasStoppedByLifecycle) {
                    hasStoppedByLifecycle = false
                    ps.retryKey++
                    channelViewModel.fetchChannels()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(currentChannelItem.id, ps.retryKey) {
        onManualOverlayChange(false); onPinnedOverlayChange(false); onShowOverlayChange(true); scrollState.scrollTo(
        0
    )
        delay(4500)
        if (!currentIsManualOverlay && !currentIsPinnedOverlay && !currentIsSubMenuOpen) onShowOverlayChange(
            false
        )
    }

    LaunchedEffect(Unit) {
        delay(800); isHeavyUiReady = true
    }

    LaunchedEffect(
        isDataBroadcastingActive,
        dataBroadcastingInput.isColorSelectorVisible,
        dataBroadcastingInput.selectedColorKey
    ) {
        if (isDataBroadcastingActive && dataBroadcastingInput.isColorSelectorVisible) {
            delay(5000)
            dataBroadcastingInput.closeColorSelector()
        }
    }

    LaunchedEffect(
        isDataBroadcastingActive,
        isDataBroadcastingBlank,
        isPiPMode,
        ps.isDualDisplayMode
    ) {
        if (!isDataBroadcastingActive || isDataBroadcastingBlank ||
            isPiPMode || ps.isDualDisplayMode
        ) {
            dataBroadcastingInput.reset()
        }
    }

    val isUiVisible =
        isSubMenuOpen || isMiniListOpen || showOverlay || isPinnedOverlay || ps.crop.mode != PlayerCropMode.HIDDEN
    // Keep captions visible under lightweight overlays. The channel browser is a full-screen
    // surface, so captions must not be drawn over its cards.
    val isSubtitleBlockingUiVisible =
        ps.crop.mode != PlayerCropMode.HIDDEN || isMiniListOpen
    val subtitleBottomAvoidanceOffset by animateDpAsState(
        targetValue = when {
            showOverlay -> LIVE_PROGRAM_INFO_SUBTITLE_OFFSET
            else -> 0.dp
        },
        animationSpec = tween(durationMillis = 180),
        label = "liveOverlaySubtitleOffset"
    )

    LaunchedEffect(isUiVisible) {
        channelViewModel.setPollingPaused(!isUiVisible)
    }

    LaunchedEffect(isMiniListOpen) {
        if (!isMiniListOpen) {
            isMiniPlayerSelectionPending = false
            if (!currentIsManualOverlay && !currentIsSubMenuOpen && !isPiPMode && ps.crop.mode == PlayerCropMode.HIDDEN) {
                delay(100); mainFocusRequester.safeRequestFocus(TAG)
            }
        }
    }

    LaunchedEffect(isSubMenuOpen) {
        if (isSubMenuOpen && !isPiPMode) {
            delay(150); subMenuFocusRequester.safeRequestFocus(TAG)
        }
    }

    LaunchedEffect(currentChannelItem.id, currentChannelItem.displayChannelId) {
        channelViewModel.prefetchChannelLogoUrls(listOf(currentChannelItem))
    }

    LaunchedEffect(displayFlatChannels) {
        if (displayFlatChannels.isNotEmpty()) {
            channelViewModel.prefetchChannelLogoUrls(displayFlatChannels)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { keyEvent ->
                if (isPiPMode) return@onKeyEvent false
                if (isDataBroadcastingToggleKeyEvent(keyEvent)) {
                    dataBroadcastingInput.reset()
                    if (!isB60Channel) {
                        onShowToast("B60 データ放送は BS4K/BS8K で利用できます")
                        return@onKeyEvent true
                    }
                    if (!isDataBroadcastingActive &&
                        !isDataBroadcastingMode &&
                        !ps.isDualDisplayMode
                    ) {
                        openLocalDataBroadcasting()
                    } else if (isDataBroadcastingActive) {
                        dispatchDataBroadcastingRemoteKey("data")
                    }
                    return@onKeyEvent true
                }
                if (isDataBroadcastingActive && !isDataBroadcastingBlank &&
                    !ps.isDualDisplayMode && !isSubMenuOpen && !isMiniListOpen &&
                    dataBroadcastingInput.handleKeyEvent(
                        keyEvent = keyEvent,
                        scope = scope,
                        onBack = dispatchDataBroadcastingBack,
                        onBlank = { dispatchDataBroadcastingRemoteKey("data") },
                        onColorKey = dispatchDataBroadcastingColorKey,
                        onRemoteKey = dispatchDataBroadcastingRemoteKey,
                    )
                ) {
                    return@onKeyEvent true
                }
                ps.handleKeyEvent(
                    keyEvent = keyEvent,
                    isSubMenuOpen = isSubMenuOpen,
                    isMiniListOpen = isMiniListOpen,
                    showOverlay = showOverlay,
                    isManualOverlay = isManualOverlay,
                    isPinnedOverlay = isPinnedOverlay,
                    currentChannelItem = currentChannelItem,
                    groupedChannels = groupedChannels,
                    scrollState = scrollState,
                    scope = scope,
                    onChannelSelect = onChannelSelect,
                    onShowOverlayChange = onShowOverlayChange,
                    onManualOverlayChange = onManualOverlayChange,
                    onPinnedOverlayChange = onPinnedOverlayChange,
                    onSubMenuToggle = onSubMenuToggle,
                    onMiniListToggle = onMiniListToggle,
                    onShowToast = onShowToast,
                    onPiPRequested = onPiPRequested,
                    onBackPressed = onBackPressed,
                )
            }
    ) {
        if (ps.isDualDisplayMode) {
            DualDisplayPlayer(
                state = ps,
                leftChannel = currentChannelItem,
                getLogoUrl = { channelId -> channelViewModel.getChannelLogoUrl(channelId) },
                shouldCropLogo = shouldCropLogo,
                isMiniListOpen = isMiniListOpen,
                isSubtitleBlockingUiVisible = isSubtitleBlockingUiVisible,
                mainPlayer = mainPlayer,
                mainVideoWidth = videoWidth,
                mainVideoHeight = videoHeight,
                mainPixelRatio = pixelWidthHeightRatio,
                mainCaptionCue = mainCaptionCue.value,
                mainSuperimposeCue = mainSuperimposeCue.value,
                isMainBuffering = isMainBuffering,
                dualPlayer = dualPlayer,
                dualVideoWidth = dualVideoWidth,
                dualVideoHeight = dualVideoHeight,
                dualPixelRatio = dualPixelWidthHeightRatio,
                dualCaptionCue = dualCaptionCue.value,
                dualSuperimposeCue = dualSuperimposeCue.value,
                isDualBuffering = isDualBuffering,
                isSubtitleEnabled = isSubtitleEnabled
            )
        } else {
            if (isDataBroadcastingActive) {
                DataBroadcastingWebViewOverlay(
                    store = livePlayerViewModel.dataBroadcastingStore,
                    channel = currentChannelItem,
                    currentMediaTimeSeconds = {
                        (mainPlayer?.currentPosition ?: 0L).coerceAtLeast(0L) / 1000.0
                    },
                    remoteCommand = dataBroadcastingRemoteCommand,
                    onRemoteCommandConsumed = { consumedId ->
                        if (dataBroadcastingRemoteCommand?.id == consumedId) {
                            dataBroadcastingRemoteCommand = null
                        }
                    },
                    onStatus = { status -> Log.d(TAG, "B60: $status") },
                    onMediaPlane = { dataBroadcastingMediaPlane = it },
                    onBlankModeChanged = { isBlank -> isDataBroadcastingBlank = isBlank },
                    onApplicationExited = exitLocalDataBroadcasting,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isDataBroadcastingBlank) 0f else 1f)
                        .zIndex(1f)
                )
            }

            val mainPlayerModifier =
                if (isDataBroadcastingActive) {
                    val plane = dataBroadcastingMediaPlane
                    if (isDataBroadcastingBlank) {
                        Modifier.fillMaxSize()
                    } else if (plane == null) {
                        Modifier
                            .fillMaxSize()
                            .b60MediaPlane(B60_INITIAL_MEDIA_PLANE)
                            .zIndex(2f)
                    } else if (plane.visible && plane.width > 0f && plane.height > 0f) {
                        Modifier
                            .fillMaxSize()
                            .b60MediaPlane(plane)
                            .zIndex(2f)
                    } else {
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(24.dp)
                            .fillMaxWidth(0.34f)
                            .aspectRatio(16f / 9f)
                            .zIndex(2f)
                            .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                    }
                } else {
                    Modifier.fillMaxSize()
                }

            val mainResizeMode = if (videoWidth > 0 && videoHeight > 0) {
                val ratio = videoWidth.toFloat() / videoHeight.toFloat()
                val isAnamorphic = videoWidth == 1440 && videoHeight == 1080 && pixelWidthHeightRatio == 1.0f
                if (isAnamorphic || ratio >= 1.7f) {
                    AspectRatioFrameLayout.RESIZE_MODE_FILL
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            PlayerSurface(
                player = mainPlayer,
                resizeMode = mainResizeMode,
                modifier = mainPlayerModifier
                    .graphicsLayer {
                        if (ps.crop.isEnabled) {
                            scaleX = ps.crop.zoomPercent / 100f; scaleY = ps.crop.zoomPercent / 100f
                            translationX = size.width * (ps.crop.xPercent / 100f); translationY =
                                size.height * (ps.crop.yPercent / 100f)
                            transformOrigin = when (ps.crop.origin) {
                                PlayerZoomOrigin.TopLeft -> TransformOrigin(0f, 0f)
                                PlayerZoomOrigin.TopRight -> TransformOrigin(1f, 0f)
                                PlayerZoomOrigin.BottomLeft -> TransformOrigin(0f, 1f)
                                PlayerZoomOrigin.BottomRight -> TransformOrigin(1f, 1f)
                            }
                        } else {
                            scaleX = 1f; scaleY = 1f; translationX = 0f; translationY =
                                0f; transformOrigin = TransformOrigin.Center
                        }
                    }
                    .focusRequester(mainFocusRequester)
                    .focusable(!isPiPMode && !isMiniListOpen && !isSubMenuOpen && ps.crop.mode == PlayerCropMode.HIDDEN)
            )

            val isVideoVisible =
                ps.currentStreamSource == StreamSource.MIRAKURUN || ps.currentStreamSource == StreamSource.EDCB || ps.sseStatus == "ONAir"
            if (!isVideoVisible) Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )

            if (!isPiPMode) {
                if (isHeavyUiReady) {
                    NativeCaptionOverlay(
                        cue = mainCaptionCue.value,
                        visible = isSubtitleEnabled && !isSubtitleBlockingUiVisible,
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(
                                if (showOverlay || isPinnedOverlay || isMiniListOpen) 3f else 0f
                            ),
                        bottomAvoidanceOffset = subtitleBottomAvoidanceOffset,
                        bottomAvoidanceStartFraction = LIVE_SUBTITLE_AVOIDANCE_START_FRACTION
                    )
                }

                if (isHeavyUiReady && isCommentEnabled) {
                    DanmakuOverlay(
                        modifier = Modifier.fillMaxSize(),
                        useSoftwareRendering = isEmulator,
                        speed = commentSpeed,
                        opacity = commentOpacity,
                        maxLines = commentMaxLines,
                        sessionKey = mainSessionToken,
                    ) { view ->
                        if (mainSessionToken != null) danmakuViewRef.value = view
                        if (!ps.isPlayerPlaying) view.pause()
                    }
                }
                if (isHeavyUiReady) {
                    NativeCaptionOverlay(
                        cue = mainSuperimposeCue.value,
                        visible = !isSubtitleBlockingUiVisible,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode && ps.crop.mode != PlayerCropMode.HIDDEN,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LCropOverlay(
                state = ps,
                onClose = {
                    ps.crop.mode = PlayerCropMode.HIDDEN; scope.launch {
                    delay(200); mainFocusRequester.safeRequestFocus(
                    TAG
                )
                }
                })
        }

        val mainLoadingPresentation = remember(
            ps.currentStreamSource,
            ps.isEdcbDirect,
            ps.sseStatus,
            ps.sseDetail,
            ps.playerError,
            isMainBuffering,
            hasMainRenderedFirstFrame,
            mainPlaybackState
        ) {
            liveLoadingPresentation(
                streamSource = ps.currentStreamSource,
                isEdcbDirect = ps.isEdcbDirect,
                sseStatus = ps.sseStatus,
                sseDetail = ps.sseDetail,
                playerError = ps.playerError,
                isBuffering = isMainBuffering,
                hasRenderedFirstFrame = hasMainRenderedFirstFrame,
                isPlaybackReady = mainPlaybackState == Player.STATE_READY,
                statusLoadingText = AppStrings.STATUS_LOADING
            )
        }

        // ★ 修正: バッファリング中の黒画面を回避し、スピナーだけを表示する
        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode && !ps.isDualDisplayMode && mainLoadingPresentation.isVisible && !isChannelSwitchDebouncing,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(32.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = mainLoadingPresentation.message,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode && !ps.isDualDisplayMode && isChannelSwitchDebouncing,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(Modifier.fillMaxSize()) {
                Text(
                    text = currentChannelItem.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(32.dp)
                        .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode && !ps.isDualDisplayMode && ps.isSignalInfoVisible && ps.playerError == null && !isUiVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SignalInfoOverlay(ps.signalInfo)
        }

        androidx.compose.animation.AnimatedVisibility(visible = !isPiPMode && !ps.isDualDisplayMode && isPinnedOverlay && ps.playerError == null) {
            StatusOverlay(
                channel = currentChannelItem,
                logoUrl = sharedCurrentLogoUrl,
                shouldCropLogo = shouldCropLogo,
                timeFormatSetting = timeFormat
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode && !ps.isDualDisplayMode && showOverlay && ps.playerError == null && !isMiniListOpen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            LiveOverlayUI(
                channel = currentChannelItem,
                programTitle = currentChannelItem.programPresent?.title
                    ?: AppStrings.PROGRAM_INFO_NONE,
                logoUrl = sharedCurrentLogoUrl,
                shouldCropLogo = shouldCropLogo,
                showDesc = isManualOverlay,
                isRecording = isRecording,
                scrollState = scrollState,
                timeFormatSetting = timeFormat
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode && isMiniListOpen && ps.playerError == null,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxSize()
        ) {
            ChannelListOverlay(
                groupedChannels = groupedChannels,
                recentChannels = displayLastWatchedChannels,
                recentRecordings = recentRecordings.take(10),
                backendType = backendType,
                konomiIp = konomiIp,
                konomiPort = konomiPort,
                currentChannelId = currentChannelItem.id,
                onChannelSelect = { selectedChannel ->
                    val enterMiniPlayer = isMiniPlayerSelectionPending
                    isMiniPlayerSelectionPending = false
                    if (!ps.isDualDisplayMode) onChannelSelect(selectedChannel) else {
                        if (ps.activeDualPlayerIndex == 0) onChannelSelect(selectedChannel) else ps.dualRightChannel =
                            selectedChannel
                    }
                    onMiniListToggle(false)
                    if (enterMiniPlayer) {
                        onPiPRequested()
                    } else {
                        scope.launch {
                            delay(200)
                            mainFocusRequester.safeRequestFocus(TAG)
                        }
                    }
                },
                onRecordingSelect = { program ->
                    val enterMiniPlayer = isMiniPlayerSelectionPending
                    isMiniPlayerSelectionPending = false
                    onMiniListToggle(false)
                    onChasePlaybackSelect(program)
                    if (enterMiniPlayer) onPiPRequested()
                },
                logoUrls = channelLogoUrls,
                shouldCropLogo = shouldCropLogo,
                focusRequester = listFocusRequester
            )
        }

        AnimatedVisibility(
            visible = !isPiPMode && isSubMenuOpen && ps.playerError == null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            LiveTopSubMenuUI(
                mainBackendType = mainBackendType,
                currentStreamSource = ps.currentStreamSource,
                isEdcbDirect = ps.isEdcbDirect,
                availableSources = availableSources,
                currentAudioMode = ps.currentAudioMode,
                isSubtitleEnabled = isSubtitleEnabled,
                subtitleLanguages = activeSubtitleLanguages,
                currentSubtitleLanguageId = currentSubtitleLanguageId,
                currentQuality = ps.currentQuality,
                isCommentEnabled = isCommentEnabled,
                isLCropEnabled = ps.crop.isEnabled,
                isRecording = isRecording,
                canStartChasePlayback = currentRecordingProgram != null && !isChasePlaybackResolving,
                isSignalInfoVisible = ps.isSignalInfoVisible,
                hdrRenderMode = hdrRenderMode,
                isHdrToSdrToneMappingSupported = isHdrToSdrToneMappingSupported,
                isDualDisplayMode = ps.isDualDisplayMode,
                isDataBroadcastingAvailable = isB60Channel,
                groupedChannels = groupedChannels,
                currentChannelId = currentChannelItem.id,
                onMiniPlayerSelectionRequested = {
                    isMiniPlayerSelectionPending = true
                    onSubMenuToggle(false)
                    onMiniListToggle(true)
                },
                onDualDisplayToggle = {
                    ps.isDualDisplayMode = !ps.isDualDisplayMode
                    if (ps.isDualDisplayMode) {
                        ps.activeDualPlayerIndex = 1
                        if ((ps.currentStreamSource == StreamSource.MIRAKURUN || ps.currentStreamSource == StreamSource.EDCB) && allowMirakurunDual != "ON") {
                            ps.previousStreamSource = ps.currentStreamSource
                            if (availableSources.contains(StreamSource.KONOMITV)) {
                                ps.currentStreamSource = StreamSource.KONOMITV
                                onShowToast("負荷軽減のためKonomiTVソースに切り替えました")
                            }
                        }
                    } else {
                        ps.activeDualPlayerIndex = 0
                        ps.dualRightChannel = null
                        ps.leftScreenWeight = 1f
                        ps.rightScreenWeight = 1f
                        if (ps.previousStreamSource != null) {
                            if (availableSources.contains(ps.previousStreamSource!!)) {
                                ps.currentStreamSource = ps.previousStreamSource!!
                                onShowToast("元のストリーミングソースに復帰しました")
                            }
                            ps.previousStreamSource = null
                        }
                    }
                },
                onSwapScreens = {
                    if (ps.dualRightChannel != null) {
                        val temp = currentChannelItem
                        onChannelSelect(ps.dualRightChannel!!)
                        ps.dualRightChannel = temp
                    }
                },
                onChannelSelect = { selectedChannel ->
                    if (!ps.isDualDisplayMode || ps.activeDualPlayerIndex == 0) {
                        onChannelSelect(selectedChannel)
                    } else {
                        ps.dualRightChannel = selectedChannel
                    }
                },
                onChasePlayback = {
                    if (!isChasePlaybackResolving) {
                        scope.launch {
                            isChasePlaybackResolving = true
                            val program = currentRecordingProgram
                                ?: recordViewModel.fetchCurrentRecordingForChannel(currentChannelItem)
                            isChasePlaybackResolving = false

                            if (program == null) {
                                onShowToast("追いかけ再生の準備中です")
                            } else {
                                onSubMenuToggle(false)
                                onChasePlaybackSelect(program)
                            }
                        }
                    }
                },
                onRecordToggle = {
                    if (isRecording) {
                        if (activeReserve != null) {
                            reserveViewModel.deleteReservation(activeReserve.id) {}
                            onShowToast("録画を停止しました")
                        }
                    } else {
                        reserveViewModel.addReserve(currentChannelItem.id) {}
                        onShowToast("録画を開始しました")
                    }
                    onSubMenuToggle(false)
                },
                onSignalInfoToggle = {
                    ps.isSignalInfoVisible = !ps.isSignalInfoVisible
                    if (ps.isSignalInfoVisible) {
                        onShowToast("信号情報を表示します")
                    }
                },
                onHdrRenderModeToggle = {
                    val nextMode = if (hdrRenderMode == "SDR_TONE_MAP") "ORIGINAL" else "SDR_TONE_MAP"
                    livePlayerViewModel.setHdrRenderMode(uiContext, nextMode)
                    onShowToast(
                        if (nextMode == "SDR_TONE_MAP") {
                            "HDR 表示：ハードウェア SDR 変換を確認中"
                        } else {
                            "HDR 表示：HLG そのまま"
                        }
                    )
                },
                onDataBroadcastingToggle = {
                    openLocalDataBroadcasting()
                    onSubMenuToggle(false)
                },
                availableQualities = effectiveAvailableQualities,
                focusRequester = subMenuFocusRequester,
                logoUrls = channelLogoUrls,
                shouldCropLogo = shouldCropLogo,
                onSourceSelect = { source, isDirect ->
                    ps.currentStreamSource = source
                    ps.isEdcbDirect = isDirect
                    onSubMenuToggle(false)
                },
                onAudioToggle = {
                    val nextAudioMode =
                        if (ps.currentAudioMode == AudioMode.MAIN) AudioMode.SUB else AudioMode.MAIN
                    val switched = if (ps.currentQuality.isRawMmts) {
                        livePlayerViewModel.switchMainRawMmtsAudio(
                            mainAudio = nextAudioMode == AudioMode.MAIN
                        )
                    } else {
                        mainPlayer?.let { player ->
                            val tracks = player.currentTracks.groups
                                .filter { it.type == C.TRACK_TYPE_AUDIO }
                            if (tracks.size >= 2) {
                                player.trackSelectionParameters =
                                    player.trackSelectionParameters.buildUpon()
                                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                        .addOverride(
                                            TrackSelectionOverride(
                                                tracks[if (nextAudioMode == AudioMode.SUB) 1 else 0].mediaTrackGroup,
                                                0
                                            )
                                        )
                                        .build()
                            }
                        }
                        true
                    }
                    if (switched) {
                        ps.currentAudioMode = nextAudioMode
                        onShowToast(
                            "音声: ${if (nextAudioMode == AudioMode.MAIN) "主音声" else "副音声"}"
                        )
                    } else {
                        onShowToast("選択した音声トラックはこの放送では利用できません")
                    }
                },
                onSubtitleToggle = {
                    subtitleEnabledState.value = !subtitleEnabledState.value
                    onShowToast(
                        String.format(
                            AppStrings.TOAST_SUBTITLE_CHANGED,
                            if (subtitleEnabledState.value) AppStrings.STATE_SHOW else AppStrings.STATE_HIDE
                        )
                    )
                },
                onSubtitleLanguageToggle = {
                    val nextLanguageId = if (currentSubtitleLanguageId == 1) 2 else 1
                    livePlayerViewModel.setSubtitleLanguage(nextLanguageId)
                    val selectedLanguage = activeSubtitleLanguages.firstOrNull { it.id == nextLanguageId }
                    onShowToast(
                        "字幕言語: 第${nextLanguageId}言語" +
                            (selectedLanguage?.let { "・${it.displayName}" } ?: "")
                    )
                },
                onQualitySelect = {
                    if (ps.currentQuality != it) {
                        val switchedInPlace = ps.currentQuality.isRawMmts && it.isRawMmts &&
                            livePlayerViewModel.switchRawMmtsLayer(
                                quality = it,
                                mainAudio = ps.currentAudioMode == AudioMode.MAIN
                            )
                        if (switchedInPlace || !ps.currentQuality.isRawMmts || !it.isRawMmts) {
                            ps.currentQuality = it
                            if (!it.isRawMmts) livePlayerViewModel.saveLiveQuality(it.value)
                            if (!switchedInPlace) ps.retryKey++
                            onShowToast(
                                if (switchedInPlace) {
                                    "${it.label}へ切り替えます"
                                } else {
                                    String.format(AppStrings.TOAST_QUALITY_CHANGED, it.label)
                                }
                            )
                        } else {
                            onShowToast("映像と音声の切替準備がまだ完了していません。しばらくして再試行してください")
                        }
                    }
                    onSubMenuToggle(false)
                },
                onCommentToggle = {
                    isCommentEnabled = !isCommentEnabled
                    onShowToast(
                        String.format(
                            AppStrings.TOAST_COMMENT_CHANGED,
                            if (isCommentEnabled) AppStrings.STATE_SHOW else AppStrings.STATE_HIDE
                        )
                    )
                },
                onLCropToggle = {
                    ps.crop.isEnabled = !ps.crop.isEnabled
                    if (ps.crop.isEnabled) {
                        ps.crop.mode = PlayerCropMode.MENU
                        onSubMenuToggle(false)
                    } else {
                        ps.crop.mode = PlayerCropMode.HIDDEN
                        ps.crop.zoomPercent = 100f; ps.crop.xPercent = 0f; ps.crop.yPercent = 0f
                        ps.crop.origin = PlayerZoomOrigin.TopRight
                    }
                },
                onCloseMenu = {
                    onSubMenuToggle(false)
                }
            )
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = !isPiPMode &&
                !ps.isDualDisplayMode &&
                isDataBroadcastingActive &&
                dataBroadcastingInput.isColorSelectorVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            DataBroadcastingColorSelectorOverlay(
                selectedKey = dataBroadcastingInput.selectedColorKey,
                onColorSelected = { colorKey ->
                    dataBroadcastingInput.dispatchColorKey(colorKey, dispatchDataBroadcastingColorKey)
                },
                onDismiss = {
                    dataBroadcastingInput.closeColorSelector()
                }
            )
        }

        if (!isPiPMode && ps.playerError != null) {
            LiveErrorDialog(
                ps.playerError!!,
                { livePlayerViewModel.retry(); ps.retry() },
                onBackPressed,
                onCheckCapabilities = if (mainErrorIsCapabilityRelated) onCheckDeviceCapabilities else null
            )
        }
    }
}
