package com.beeregg2001.komorebi.ui.live

import android.content.Intent
import android.media.tv.TvContentRating
import android.media.tv.TvInputManager
import android.media.tv.TvTrackInfo
import android.media.tv.TvView
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.data.TifNoSignalAccentColor
import com.beeregg2001.komorebi.data.TifNoSignalStyle
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.repository.tif.TifInput
import com.beeregg2001.komorebi.ui.home.DigitalClock
import com.beeregg2001.komorebi.ui.screensaver.PlayerIdleSession
import com.beeregg2001.komorebi.ui.screensaver.ScreensaverInteractionTracker
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.player.DanmakuEngineMode
import com.beeregg2001.komorebi.ui.player.DanmakuOverlay
import com.beeregg2001.komorebi.ui.player.DanmakuViewHandle
import com.beeregg2001.komorebi.ui.player.LivePlayerMenuTileStyle
import com.beeregg2001.komorebi.ui.player.PlayerMenuTile
import com.beeregg2001.komorebi.ui.player.PlayerSubMenuAutoDismissMs
import com.beeregg2001.komorebi.ui.player.PlayerSubMenuContainer
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** TvTrackInfoの説明を優先し、主副音声の意味をtrack IDから推測しない。 */
private fun audioLabel(track: TvTrackInfo, index: Int): String =
    track.description?.toString()?.takeIf { it.isNotBlank() }
        ?: track.language?.takeIf { it.isNotBlank() }
        ?: "音声トラック ${index + 1}"

internal const val TIF_NO_SIGNAL_THEMED_DELAY_MS = 800L

internal fun usesLiveOsd(channelId: String, channelType: String): Boolean =
    channelId.startsWith("tif:channel:") && channelType in setOf("GR", "BS", "CS")

internal sealed interface TifVideoStatus {
    val text: String

    data object Playing : TifVideoStatus {
        override val text: String = ""
    }

    data class Transient(override val text: String) : TifVideoStatus

    data class NoSignal(override val text: String) : TifVideoStatus

    data class Notice(override val text: String) : TifVideoStatus
}

internal fun videoUnavailableStatus(reason: Int, isPassthrough: Boolean): TifVideoStatus = when (reason) {
    TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING -> TifVideoStatus.Transient(
        if (isPassthrough) "信号を検出しています…" else "選局しています…"
    )
    TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> TifVideoStatus.NoSignal(
        if (isPassthrough) "信号がありません" else "電波が弱いか信号がありません"
    )
    TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING -> TifVideoStatus.Transient("読み込み中…")
    TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY -> TifVideoStatus.Notice("音声のみの放送です")
    5 -> TifVideoStatus.NoSignal("入力機器が接続されていません") // VIDEO_UNAVAILABLE_REASON_NOT_CONNECTED (API 30+)
    else -> TifVideoStatus.NoSignal(
        if (isPassthrough) "映像信号がありません (reason=$reason)" else "映像を利用できません (reason=$reason)"
    )
}

/**
 * Android TV Input Frameworkのライブ視聴画面。
 * ネットワーク配信/Media3/Komorebiネイティブライブラリには依存しない。
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun TifLivePlayerScreen(
    channel: Channel,
    groupedChannels: Map<String, List<Channel>>,
    isMiniListOpen: Boolean,
    onMiniListToggle: (Boolean) -> Unit,
    onChannelSelect: (Channel) -> Unit,
    onChannelPlaybackCommitted: (Channel) -> Unit,
    showOverlay: Boolean,
    onShowOverlayChange: (Boolean) -> Unit,
    initialCaptions: Boolean,
    videoOnly: Boolean = false,
    externalCommentsEnabled: Boolean = true,
    standaloneInput: Boolean = false,
    timeFormat: String = "24H",
    onLaunchSystemSettings: () -> Unit = {},
    onClockClick: () -> Unit = {},
    onExit: () -> Unit = {},
    noSignalStyle: TifNoSignalStyle = TifNoSignalStyle.BANNER,
    noSignalAccentColor: TifNoSignalAccentColor = TifNoSignalAccentColor.RANDOM,
    screensaverImageUris: List<String> = emptyList(),
    screensaverIntervalSeconds: Int = 30,
    screensaverTransition: String = "FADE",
    suppressNoSignalVisual: Boolean = false,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    jikkyoViewModel: TifJikkyoViewModel = hiltViewModel(),
    remoteControlViewModel: com.beeregg2001.komorebi.viewmodel.RemoteControlViewModel = hiltViewModel(),
) {
    val colors = KomorebiTheme.colors
    val context = LocalContext.current
    LaunchedEffect(channel.id) {
        remoteControlViewModel.client.sendState(contentType = "Live", title = channel.name, isBuffering = true)
        remoteControlViewModel.client.reportInputSignal(channel.id, "Loading", null)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val systemSleepAllowed by ScreensaverInteractionTracker.systemSleepAllowed.collectAsState()
    val playerIdleOwner = remember { Any() }
    var isStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(playerIdleOwner, isStarted) {
        ScreensaverInteractionTracker.updatePlayer(
            playerIdleOwner,
            PlayerIdleSession(active = isStarted),
        )
        onDispose {
            ScreensaverInteractionTracker.updatePlayer(playerIdleOwner, null)
        }
    }
    // Composeが停止中に描画フレームを持たなくても、TvViewを直ちにresetする。
    // 世代はSTART毎に変え、停止直後に再開した場合も旧セッションを再使用しない。
    var sessionGeneration by remember(lifecycleOwner) { mutableIntStateOf(0) }
    var activeViewForLifecycle by remember { mutableStateOf<TvView?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    activeViewForLifecycle?.reset()
                    activeViewForLifecycle = null
                    isStarted = false
                }
                Lifecycle.Event.ON_START -> {
                    sessionGeneration++
                    isStarted = true
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activeViewForLifecycle?.reset()
            activeViewForLifecycle = null
        }
    }

    // 明示的な再選局は新セッションを作成し、古いTvViewを解放する。
    // 同一 inputId 内のチャンネル切替は TvView を破棄せずデバウンス後にそのまま tune() する。
    var retuneGeneration by remember { mutableIntStateOf(0) }
    var lastTunedChannelId by remember(isStarted, sessionGeneration) { mutableStateOf<String?>(null) }
    var lastRetuneGeneration by remember(isStarted, sessionGeneration) { mutableIntStateOf(retuneGeneration) }
    var coldHandshakeRetries by remember(isStarted, sessionGeneration) { mutableIntStateOf(0) }
    var lastUnavailableReason by remember(isStarted, sessionGeneration) {
        mutableStateOf<Int?>(null)
    }
    var target by remember(isStarted, sessionGeneration) {
        mutableStateOf<TifInput.TuneTarget?>(null)
    }
    var resolutionComplete by remember(isStarted, sessionGeneration) { mutableStateOf(false) }
    var resolveError by remember(isStarted, sessionGeneration) { mutableStateOf<String?>(null) }

    var videoStatus by remember(isStarted, sessionGeneration) {
        mutableStateOf<TifVideoStatus>(TifVideoStatus.Transient("映像の受信を開始しています…"))
    }
    var handshakeRetryPending by remember(isStarted, sessionGeneration) {
        mutableStateOf(false)
    }
    val view = LocalView.current
    val density = LocalDensity.current
    val animationsEnabled = remember(context) { areSystemAnimatorsEnabled(context) }
    var noSignalDegraded by remember { mutableStateOf(false) }
    var containerWidthDp by remember { mutableFloatStateOf(0f) }
    var videoAvailable by remember(isStarted, sessionGeneration) { mutableStateOf(false) }
    var contentBlocked by remember(isStarted, sessionGeneration) { mutableStateOf(false) }
    var audioTracks by remember(isStarted, sessionGeneration) {
        mutableStateOf<List<TvTrackInfo>>(emptyList())
    }
    var selectedAudioId by remember(isStarted, sessionGeneration) { mutableStateOf<String?>(null) }
    var subtitleTracks by remember(isStarted, sessionGeneration) {
        mutableStateOf<List<TvTrackInfo>>(emptyList())
    }
    var selectedSubtitleId by remember(isStarted, sessionGeneration) {
        mutableStateOf<String?>(null)
    }
    var activeView by remember(isStarted, sessionGeneration) { mutableStateOf<TvView?>(null) }
    var hdmiBackDown by remember(activeView) { mutableStateOf<android.view.KeyEvent?>(null) }
    var hdmiBackLong by remember(activeView) { mutableStateOf(false) }
    LaunchedEffect(hdmiBackDown) {
        if (hdmiBackDown != null) {
            kotlinx.coroutines.delay(android.view.ViewConfiguration.getLongPressTimeout().toLong())
            hdmiBackLong = true
            onShowOverlayChange(true)
        }
    }

    LaunchedEffect(context, channel.id, isStarted, retuneGeneration, sessionGeneration) {
        if (!isStarted) {
            target = null
            resolveError = null
            resolutionComplete = true
            return@LaunchedEffect
        }
        val isRetune = retuneGeneration != lastRetuneGeneration
        val isChannelSwitch = lastTunedChannelId != null && lastTunedChannelId != channel.id && !isRetune
        if (isChannelSwitch) {
            // 連続切替では直前のチューニング要求をキャンセルし、旧 TvView の映像を保ったまま待機する。
            delay(LivePlayerViewModel.CHANNEL_SWITCH_STREAM_DEBOUNCE_MS)
        } else if (target == null) {
            resolutionComplete = false
            resolveError = null
        }
        var resolvedTarget: TifInput.TuneTarget? = null
        var resolvedError: String? = null
        try {
            resolvedTarget = withContext(Dispatchers.IO) { TifInput.resolveTarget(context, channel.id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            resolvedError = "TV入力の確認に失敗しました: " + error.message
        }
        if (lastTunedChannelId != channel.id) {
            coldHandshakeRetries = 0
        }
        lastTunedChannelId = channel.id
        lastRetuneGeneration = retuneGeneration
        lastUnavailableReason = null
        videoAvailable = false
        contentBlocked = false
        audioTracks = emptyList()
        selectedAudioId = null
        subtitleTracks = emptyList()
        selectedSubtitleId = null
        videoStatus = TifVideoStatus.Transient("映像の受信を開始しています…")
        resolveError = resolvedError
        target = resolvedTarget
        resolutionComplete = true
    }
    var captions by rememberSaveable(channel.id, initialCaptions) { mutableStateOf(initialCaptions) }
    LaunchedEffect(initialCaptions, videoOnly) { if (videoOnly) captions = initialCaptions }
    LaunchedEffect(activeView, captions, subtitleTracks) {
        val tv = activeView ?: return@LaunchedEffect
        try {
            tv.setCaptionEnabled(captions)
            val track = subtitleTracks.firstOrNull { it.id == selectedSubtitleId }
                ?: subtitleTracks.firstOrNull()
            tv.selectTrack(TvTrackInfo.TYPE_SUBTITLE, if (captions) track?.id else null)
        } catch (error: Exception) {
            videoStatus = TifVideoStatus.Notice("字幕設定に失敗しました: ${error.message}。字幕を切り替えるか再選局してください。")
        }
    }
    val commentDefault by settingsViewModel.commentDefaultDisplay.collectAsState()
    val commentSpeed by settingsViewModel.commentSpeed.collectAsState()
    val commentSize by settingsViewModel.commentFontSize.collectAsState()
    val commentOpacity by settingsViewModel.commentOpacity.collectAsState()
    val commentLines by settingsViewModel.commentMaxLines.collectAsState()
    val commentEngine by settingsViewModel.commentEngine.collectAsState()
    var commentsEnabled by rememberSaveable(commentDefault) { mutableStateOf(commentDefault == "ON") }
    var commentToken by remember { mutableStateOf<LiveChannelSessionToken?>(null) }
    val danmakuView = remember { mutableStateOf<DanmakuViewHandle?>(null) }
    val hasTunerChannel = channel.id.startsWith("tif:channel:")
    DisposableEffect(channel.id, isStarted, hasTunerChannel) {
        if (isStarted && hasTunerChannel) commentToken = jikkyoViewModel.start(channel)
        onDispose {
            jikkyoViewModel.stop()
            commentToken = null
        }
    }
    val runComments = isStarted && videoStatus is TifVideoStatus.Playing && commentsEnabled && externalCommentsEnabled && !suppressNoSignalVisual
    LiveDanmakuEffect(jikkyoViewModel.comments, commentToken, runComments,
        commentSize.toFloatOrNull() ?: 1f, danmakuView)

    val rootFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val firstControlFocus = remember { FocusRequester() }
    val captionControlFocus = remember { FocusRequester() }
    val exitControlFocus = remember { FocusRequester() }
    val systemSettingsFocus = remember { FocusRequester() }
    val clockFocus = remember { FocusRequester() }
    var controlHasFocus by remember { mutableStateOf(false) }
    var consumeNextOkKeyUp by remember { mutableStateOf(false) }
    var consumeNextBackKeyUp by remember { mutableStateOf(false) }
    var overlayInteractionTick by remember { mutableIntStateOf(0) }
    val displaySettingsIntent = remember(context) {
        Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).takeIf {
            context.packageManager.resolveActivity(it, 0) != null
        }
    }
    val soundSettingsIntent = remember(context) {
        Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).takeIf {
            context.packageManager.resolveActivity(it, 0) != null
        }
    }

    val isPassthroughInput = standaloneInput || channel.id.startsWith("tif:input:")
    val isCecInput = target?.inputId?.let { inputId ->
        context.getSystemService(TvInputManager::class.java)?.getTvInputInfo(inputId)?.let {
            usesMediaTekCec(it.serviceInfo.packageName, it.type == android.media.tv.TvInputInfo.TYPE_HDMI, it.parentId)
        }
    } == true
    val cecActivity = context as? com.beeregg2001.komorebi.MainActivity
    DisposableEffect(cecActivity, isCecInput, activeView, showOverlay, isMiniListOpen) {
        if (isCecInput && !showOverlay && !isMiniListOpen) {
            cecActivity?.cecKeyAction = { key ->
                if (key.keyCode in setOf(
                        android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_MENU,
                        android.view.KeyEvent.KEYCODE_TV_INPUT, android.view.KeyEvent.KEYCODE_VOLUME_UP,
                        android.view.KeyEvent.KEYCODE_VOLUME_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_MUTE,
                        android.view.KeyEvent.KEYCODE_POWER,
                    )) false else {
                    activeView?.dispatchKeyEvent(key)
                    true
                }
            }
            cecActivity?.cecHomeAction = {
                val now = android.os.SystemClock.uptimeMillis()
                activeView?.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HOME, 0))
                activeView?.dispatchKeyEvent(android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_HOME, 0))
            }
        }
        onDispose {
            cecActivity?.cecHomeAction = null
            cecActivity?.cecKeyAction = null
        }
    }
    LaunchedEffect(isCecInput, channel.id) {
        if (isCecInput && !videoOnly) android.widget.Toast.makeText(
            context, "CEC モード：戻る長押しでメニュー", android.widget.Toast.LENGTH_LONG
        ).show()
    }
    val useLiveOsd = usesLiveOsd(channel.id, channel.type)
    var showProgramDetails by remember(channel.id) { mutableStateOf(false) }
    val programScroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(showProgramDetails, showOverlay) {
        if (useLiveOsd && showOverlay && !isMiniListOpen) {
            delay(60)
            if (showProgramDetails) rootFocus.requestFocus() else firstControlFocus.requestFocus()
        }
    }
    val allChannels = remember(groupedChannels) {
        groupedChannels.values.flatten().filter { it.isWatchable && it.id.startsWith("tif:") }
    }

    // HDMI/AV の信号復帰・切断を TvInputManager.TvInputCallback で監視し、
    // 接続復帰時に映像が止まったままなら自動で再接続する。
    val currentInputId = target?.inputId
    DisposableEffect(context, currentInputId, isStarted) {
        val tvInputManager = if (isStarted && currentInputId != null) {
            context.getSystemService(TvInputManager::class.java)
        } else null
        if (tvInputManager == null || currentInputId == null) {
            onDispose {}
        } else {
            val callback = object : TvInputManager.TvInputCallback() {
                override fun onInputStateChanged(inputId: String, state: Int) {
                    if (inputId != currentInputId) return
                    when (state) {
                        TvInputManager.INPUT_STATE_CONNECTED -> {
                            if (!videoAvailable) {
                                videoStatus = TifVideoStatus.Transient("信号を検出しています…")
                                retuneGeneration++
                            }
                        }
                        TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> {
                            if (!videoAvailable) {
                                videoStatus = TifVideoStatus.NoSignal("入力機器が待機状態です")
                            }
                        }
                        TvInputManager.INPUT_STATE_DISCONNECTED -> {
                            videoAvailable = false
                            videoStatus = TifVideoStatus.NoSignal("入力機器が接続されていません")
                        }
                    }
                }
            }
            tvInputManager.registerCallback(callback, Handler(Looper.getMainLooper()))
            onDispose { tvInputManager.unregisterCallback(callback) }
        }
    }

    // 物理接続済み（INPUT_STATE_CONNECTED）のHDMI入力で初回ハンドシェイクが
    // TISの短いタイムアウト（約1秒）に間に合わず WEAK_SIGNAL になった場合、最大2回だけ自動で再接続する。
    LaunchedEffect(
        isPassthroughInput,
        currentInputId,
        isStarted,
        videoAvailable,
        contentBlocked,
        lastUnavailableReason,
        coldHandshakeRetries,
    ) {
        if (!isPassthroughInput || !isStarted || currentInputId == null || videoAvailable || contentBlocked) {
            return@LaunchedEffect
        }
        if (lastUnavailableReason != TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL) return@LaunchedEffect
        if (coldHandshakeRetries >= 2) return@LaunchedEffect
        val state = runCatching {
            context.getSystemService(TvInputManager::class.java)?.getInputState(currentInputId)
        }.getOrNull()
        if (state != TvInputManager.INPUT_STATE_CONNECTED) return@LaunchedEffect
        handshakeRetryPending = true
        try {
            delay(1_500L)
            if (!videoAvailable && !contentBlocked) {
                coldHandshakeRetries++
                videoStatus = TifVideoStatus.Transient("信号を検出しています…")
                retuneGeneration++
            }
        } finally {
            handshakeRetryPending = false
        }
    }

    val themedReady by produceState(false, target, videoStatus, isStarted, handshakeRetryPending) {
        value = false
        if (target != null && videoStatus is TifVideoStatus.NoSignal && isStarted && !handshakeRetryPending) {
            delay(TIF_NO_SIGNAL_THEMED_DELAY_MS)
            value = true
        }
    }

    val noSignalRenderMode = resolveNoSignalRenderMode(
        style = noSignalStyle,
        themedReady = themedReady,
        suppressed = suppressNoSignalVisual,
        hardwareAccelerated = view.isHardwareAccelerated,
        animationsEnabled = animationsEnabled,
        degraded = noSignalDegraded,
        containerWidthDp = containerWidthDp,
    )

    BackHandler(enabled = !videoOnly && (isMiniListOpen || showOverlay)) {
        when {
            isMiniListOpen -> onMiniListToggle(false)
            showProgramDetails -> showProgramDetails = false
            else -> onShowOverlayChange(false)
        }
    }
    LaunchedEffect(showOverlay) { if (!showOverlay) showProgramDetails = false }
    LaunchedEffect(isMiniListOpen, allChannels.isEmpty()) {
        if (!standaloneInput && isMiniListOpen && allChannels.isEmpty()) onMiniListToggle(false)
    }

    // 操作帯は無操作で自動的に隠す（上部タイル表示時は15秒、それ以外は5秒）。
    LaunchedEffect(showOverlay, isMiniListOpen, useLiveOsd, showProgramDetails, overlayInteractionTick) {
        if (showOverlay && !isMiniListOpen) {
            delay(if (useLiveOsd && !showProgramDetails) PlayerSubMenuAutoDismissMs else 5_000L)
            onShowOverlayChange(false)
        }
    }

    // 操作帯を隠した場合は必ず画面本体へフォーカスを戻し、決定キーで再表示できる。
    // ボタン操作中や入力切替ドロワー表示中はフォーカスを奪わない。
    LaunchedEffect(isMiniListOpen, showOverlay, allChannels.isEmpty(), isPassthroughInput) {
        if (videoOnly) return@LaunchedEffect
        if (!showOverlay || isMiniListOpen) controlHasFocus = false
        delay(60)
        runCatching {
            when {
                standaloneInput && isMiniListOpen -> Unit // TvInputSourceDialog が自身の初期フォーカスを管理する
                isMiniListOpen && allChannels.isNotEmpty() -> listFocus.requestFocus()
                !isMiniListOpen && showOverlay -> {
                    when {
                        standaloneInput || allChannels.isNotEmpty() -> firstControlFocus.requestFocus()
                        !isPassthroughInput -> captionControlFocus.requestFocus()
                        else -> exitControlFocus.requestFocus()
                    }
                }
                else -> rootFocus.requestFocus()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size ->
                containerWidthDp = with(density) { size.width.toDp().value }
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (hdmiBackDown != null && native.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (event.type == KeyEventType.KeyUp) {
                        if (!hdmiBackLong && !native.isCanceled) {
                            activeView?.dispatchKeyEvent(hdmiBackDown!!)
                            activeView?.dispatchKeyEvent(native)
                        }
                        hdmiBackDown = null
                        hdmiBackLong = false
                    }
                    return@onPreviewKeyEvent true
                }
                if (!videoOnly && isCecInput && !showOverlay && !isMiniListOpen) {
                    if (native.keyCode == android.view.KeyEvent.KEYCODE_TV_INPUT) {
                        if (event.type == KeyEventType.KeyUp) onMiniListToggle(true)
                        return@onPreviewKeyEvent true
                    }
                    if (native.keyCode == android.view.KeyEvent.KEYCODE_MENU) {
                        if (event.type == KeyEventType.KeyUp) onShowOverlayChange(true)
                        return@onPreviewKeyEvent true
                    }
                    if (native.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                        if (event.type == KeyEventType.KeyDown) hdmiBackDown = android.view.KeyEvent(native)
                        return@onPreviewKeyEvent true
                    }
                    if (native.keyCode !in setOf(
                            android.view.KeyEvent.KEYCODE_VOLUME_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                            android.view.KeyEvent.KEYCODE_VOLUME_MUTE, android.view.KeyEvent.KEYCODE_POWER,
                        )) {
                        activeView?.dispatchKeyEvent(native)
                        return@onPreviewKeyEvent true
                    }
                }
                val isBackKey = event.key == Key.Back ||
                    event.key == Key.Escape ||
                    event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                    event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE
                if (!videoOnly && isBackKey) {
                    if (event.type == KeyEventType.KeyUp && consumeNextBackKeyUp) {
                        consumeNextBackKeyUp = false
                        return@onPreviewKeyEvent true
                    }
                    if (event.type == KeyEventType.KeyDown) {
                        if (consumeNextBackKeyUp) return@onPreviewKeyEvent true
                        val canDismissMiniList = isMiniListOpen && !standaloneInput
                        val canDismissOverlay = !isMiniListOpen && showOverlay
                        if (canDismissMiniList || canDismissOverlay) {
                            if (event.nativeKeyEvent.repeatCount == 0) {
                                consumeNextBackKeyUp = true
                                when {
                                    canDismissMiniList -> onMiniListToggle(false)
                                    showProgramDetails -> showProgramDetails = false
                                    else -> onShowOverlayChange(false)
                                }
                            }
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                if (useLiveOsd && showOverlay && !isMiniListOpen && event.type == KeyEventType.KeyDown) {
                    if (event.key == Key.DirectionDown && !showProgramDetails) {
                        showProgramDetails = true
                        overlayInteractionTick++
                        return@onPreviewKeyEvent true
                    }
                    if (event.key == Key.DirectionDown && showProgramDetails) {
                        scope.launch { programScroll.animateScrollTo(programScroll.value + 120) }
                        overlayInteractionTick++
                        return@onPreviewKeyEvent true
                    }
                    if (event.key == Key.DirectionUp && showProgramDetails) {
                        showProgramDetails = false
                        overlayInteractionTick++
                        return@onPreviewKeyEvent true
                    }
                }
                val isOkKey = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (isOkKey && event.type == KeyEventType.KeyUp && consumeNextOkKeyUp) {
                    consumeNextOkKeyUp = false
                    return@onPreviewKeyEvent true
                }
                false
            }
            .onKeyEvent { event ->
                if (videoOnly) return@onKeyEvent false
                if (event.type != KeyEventType.KeyDown || isMiniListOpen || controlHasFocus) {
                    return@onKeyEvent false
                }
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        consumeNextOkKeyUp = true
                        if (showOverlay) {
                            when {
                                standaloneInput || allChannels.isNotEmpty() -> firstControlFocus.requestFocus()
                                !isPassthroughInput -> captionControlFocus.requestFocus()
                                else -> exitControlFocus.requestFocus()
                            }
                        } else {
                            overlayInteractionTick++
                            onShowOverlayChange(true)
                        }
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (showOverlay || isPassthroughInput) return@onKeyEvent false
                        val currentIndex = allChannels.indexOfFirst { it.id == channel.id }
                        if (currentIndex < 0 || allChannels.isEmpty()) false
                        else {
                            val step = if (event.key == Key.DirectionRight) 1 else -1
                            onChannelSelect(allChannels[(currentIndex + step + allChannels.size) % allChannels.size])
                            true
                        }
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusProperties {
                up = FocusRequester.Cancel
                down = FocusRequester.Cancel
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
            }
            .focusable(!videoOnly)
    ) {
        if (target == null) {
            TifStatusBanner(
                text = when {
                    context.getSystemService(TvInputManager::class.java) == null ->
                        "この端末はテレビ入力（TIF）に対応していません。"
                    !isStarted -> "バックグラウンドのためTV入力を停止しています"
                    !resolutionComplete -> "TV入力を確認しています…"
                    resolveError != null -> resolveError.orEmpty()
                    else -> "TV入力またはチャンネルが見つかりません。TV入力設定をご確認ください。"
                },
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            val tuneTarget = requireNotNull(target)
            val latestChannel by rememberUpdatedState(channel)
            val latestOnChannelPlaybackCommitted by rememberUpdatedState(onChannelPlaybackCommitted)
            val latestIsPassthroughInput by rememberUpdatedState(isPassthroughInput)
            key(tuneTarget.inputId, retuneGeneration, sessionGeneration) {
                var tunedUri by remember { mutableStateOf<android.net.Uri?>(null) }
                AndroidView(
                    factory = { viewContext ->
                        TvView(viewContext).apply {
                            isFocusable = false
                            isFocusableInTouchMode = false
                            keepScreenOn = isStarted && !systemSleepAllowed
                            setCallback(object : TvView.TvInputCallback() {
                                override fun onVideoAvailable(inputId: String) {
                                    remoteControlViewModel.client.sendState(contentType = "Live", title = latestChannel.name, isPlaying = true)
                                    remoteControlViewModel.client.reportInputSignal(latestChannel.id, "Available", null)
                                    latestOnChannelPlaybackCommitted(latestChannel)
                                    videoAvailable = true
                                    lastUnavailableReason = null
                                    coldHandshakeRetries = 0
                                    if (!contentBlocked) videoStatus = TifVideoStatus.Playing
                                }

                                override fun onContentBlocked(inputId: String, rating: TvContentRating) {
                                    contentBlocked = true
                                    videoStatus = TifVideoStatus.Notice(
                                        "視聴年齢制限によりブロックされています。端末のTV入力・保護者による使用制限の設定を確認し、許可後に「再選局」を選択してください。"
                                    )
                                }

                                override fun onContentAllowed(inputId: String) {
                                    if (contentBlocked) {
                                        contentBlocked = false
                                        videoStatus = if (videoAvailable) {
                                            TifVideoStatus.Playing
                                        } else {
                                            TifVideoStatus.Transient("映像の受信を開始しています…")
                                        }
                                    }
                                }

                                override fun onVideoSizeChanged(inputId: String, width: Int, height: Int) {
                                    if (width > 0 && height > 0) {
                                        videoAvailable = true
                                        if (!contentBlocked) videoStatus = TifVideoStatus.Playing
                                    }
                                }

                                override fun onVideoUnavailable(inputId: String, reason: Int) {
                                    remoteControlViewModel.client.reportInputSignal(latestChannel.id, "Unavailable", reason.toString())
                                    videoAvailable = false
                                    lastUnavailableReason = reason
                                    if (!contentBlocked) {
                                        videoStatus = videoUnavailableStatus(reason, latestIsPassthroughInput)
                                    }
                                }

                                override fun onConnectionFailed(inputId: String) {
                                    remoteControlViewModel.client.reportInputSignal(latestChannel.id, "Error", "CONNECTION_FAILED")
                                    videoAvailable = false
                                    videoStatus = TifVideoStatus.NoSignal("TV入力との接続に失敗しました")
                                }

                                override fun onDisconnected(inputId: String) {
                                    remoteControlViewModel.client.reportInputSignal(latestChannel.id, "Error", "DISCONNECTED")
                                    videoAvailable = false
                                    videoStatus = TifVideoStatus.NoSignal("TV入力との接続が切れました")
                                }

                                override fun onTracksChanged(inputId: String, tracks: MutableList<TvTrackInfo>) {
                                    audioTracks = tracks.filter { it.type == TvTrackInfo.TYPE_AUDIO }
                                    subtitleTracks = tracks.filter { it.type == TvTrackInfo.TYPE_SUBTITLE }
                                    android.util.Log.d("TifCaptions", "input=$inputId subtitleTracks=" +
                                        subtitleTracks.joinToString { "${it.id}:${it.language}" })
                                    if (audioTracks.none { it.id == selectedAudioId }) selectedAudioId = null
                                }

                                override fun onTrackSelected(inputId: String, type: Int, trackId: String?) {
                                    if (type == TvTrackInfo.TYPE_AUDIO) selectedAudioId = trackId
                                    if (type == TvTrackInfo.TYPE_SUBTITLE) {
                                        selectedSubtitleId = trackId
                                        android.util.Log.d("TifCaptions", "input=$inputId selectedSubtitle=$trackId")
                                    }
                                }
                            })
                            try {
                                setStreamVolume(1.0f)
                                tune(tuneTarget.inputId, tuneTarget.channelUri)
                                tunedUri = tuneTarget.channelUri
                                activeView = this
                                activeViewForLifecycle = this
                            } catch (error: Exception) {
                                videoStatus = TifVideoStatus.Notice("TV入力の選局に失敗しました: " + error.message)
                                reset()
                            }
                        }
                    },
                    update = { view ->
                        view.keepScreenOn = isStarted && !systemSleepAllowed
                        if (tunedUri != tuneTarget.channelUri) {
                            try {
                                view.setStreamVolume(1.0f)
                                view.tune(tuneTarget.inputId, tuneTarget.channelUri)
                                tunedUri = tuneTarget.channelUri
                            } catch (error: Exception) {
                                videoStatus = TifVideoStatus.Notice("TV入力の選局に失敗しました: " + error.message)
                            }
                        }
                    },
                    onRelease = { view ->
                        view.keepScreenOn = false
                        view.setCallback(null)
                        view.reset()
                        if (activeView === view) activeView = null
                        if (activeViewForLifecycle === view) activeViewForLifecycle = null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (hasTunerChannel && commentsEnabled && externalCommentsEnabled && !suppressNoSignalVisual) {
                DanmakuOverlay(
                    modifier = Modifier.fillMaxSize(),
                    engineMode = DanmakuEngineMode.fromId(commentEngine),
                    speed = commentSpeed.toFloatOrNull() ?: 1f,
                    opacity = commentOpacity.toFloatOrNull() ?: 1f,
                    maxLines = commentLines.toIntOrNull() ?: 0,
                    sessionKey = commentToken,
                    running = runComments,
                    onViewCreated = { danmakuView.value = it },
                    onViewReleased = { if (danmakuView.value?.view === it.view) danmakuView.value = null },
                )
            }

            if (videoStatus !is TifVideoStatus.Playing && !suppressNoSignalVisual) {
                TifNoSignalLayer(
                    mode = noSignalRenderMode,
                    style = noSignalStyle,
                    accentColor = noSignalAccentColor,
                    statusText = videoStatus.text,
                    inputLabel = channel.name,
                    isPassthroughInput = isPassthroughInput,
                    screensaverImageUris = screensaverImageUris,
                    screensaverIntervalSeconds = screensaverIntervalSeconds,
                    screensaverTransition = screensaverTransition,
                    showOverlay = showOverlay,
                    paused = !isStarted,
                    dimmed = showOverlay || isMiniListOpen,
                    onDegraded = { noSignalDegraded = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (showOverlay && !isMiniListOpen && useLiveOsd) {
            LiveOverlayUI(
                channel = channel,
                programTitle = channel.programPresent?.title ?: "番組情報なし",
                logoUrl = "",
                shouldCropLogo = false,
                showDesc = showProgramDetails,
                isRecording = false,
                scrollState = programScroll,
                timeFormatSetting = timeFormat,
            )
            if (!showProgramDetails) PlayerSubMenuContainer {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown) overlayInteractionTick++
                            false
                        }
                        .onFocusChanged { controlHasFocus = it.hasFocus }
                        .padding(horizontal = 32.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                ) {
                    PlayerMenuTile("チャンネル一覧", Icons.Default.List, "選局", { onMiniListToggle(true) },
                        LivePlayerMenuTileStyle, Modifier.focusRequester(firstControlFocus), enabled = allChannels.isNotEmpty())
                    PlayerMenuTile("字幕", Icons.Default.ClosedCaption, if (captions) "表示" else "非表示",
                        { captions = !captions }, LivePlayerMenuTileStyle, Modifier.focusRequester(captionControlFocus))
                    PlayerMenuTile("コメント", Icons.Default.Comment, if (commentsEnabled) "表示" else "非表示",
                        { commentsEnabled = !commentsEnabled }, LivePlayerMenuTileStyle)
                    if (audioTracks.size > 1) {
                        val selectedIndex = audioTracks.indexOfFirst { it.id == selectedAudioId }
                        PlayerMenuTile("音声切替", Icons.Default.VolumeUp,
                            if (selectedIndex >= 0) audioLabel(audioTracks[selectedIndex], selectedIndex) else "選択未通知",
                            {
                                val wanted = audioTracks[(selectedIndex + 1) % audioTracks.size].id
                                try {
                                    activeView?.selectTrack(TvTrackInfo.TYPE_AUDIO, wanted)
                                } catch (error: Exception) {
                                    videoStatus = TifVideoStatus.Notice("音声切替に失敗しました: " + error.message)
                                }
                            }, LivePlayerMenuTileStyle)
                    }
                    if (displaySettingsIntent != null) PlayerMenuTile("画質設定", Icons.Default.Tune, "設定を開く",
                        { context.startActivity(displaySettingsIntent) }, LivePlayerMenuTileStyle)
                    if (soundSettingsIntent != null) PlayerMenuTile("音声設定", Icons.Default.VolumeUp, "設定を開く",
                        { context.startActivity(soundSettingsIntent) }, LivePlayerMenuTileStyle)
                    PlayerMenuTile("再選局", Icons.Default.Refresh, "", { retuneGeneration++ }, LivePlayerMenuTileStyle)
                    PlayerMenuTile("終了", Icons.Default.Close, "", onExit, LivePlayerMenuTileStyle,
                        Modifier.focusRequester(exitControlFocus))
                }
            }
        }

        if (showOverlay && (!isMiniListOpen || standaloneInput) && !useLiveOsd) {
            val barShape = RoundedCornerShape(14.dp)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 24.dp, end = 168.dp, top = 18.dp, bottom = 18.dp)
                    .background(colors.surface1.copy(alpha = 0.72f), barShape)
                    .horizontalScroll(rememberScrollState())
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) overlayInteractionTick++
                        false
                    }
                    .onFocusChanged {
                        controlHasFocus = it.hasFocus
                        if (it.hasFocus) overlayInteractionTick++
                    }
                    .focusProperties {
                        up = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp),
                ) {
                    Icon(
                        imageVector = if (standaloneInput) Icons.Default.SettingsInputHdmi else Icons.Default.LiveTv,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        modifier = Modifier.widthIn(max = 180.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(colors.borderSubtle)
                )

                TifOsdActionChip(
                    label = if (standaloneInput) "入力切替" else "チャンネル一覧",
                    icon = if (standaloneInput) Icons.Default.SettingsInputHdmi else Icons.Default.List,
                    enabled = standaloneInput || allChannels.isNotEmpty(),
                    onClick = { onMiniListToggle(true) },
                    modifier = Modifier.focusRequester(firstControlFocus),
                )

                if (!isPassthroughInput) {
                    TifOsdActionChip(
                        label = if (captions) "字幕 ON" else "字幕 OFF",
                        icon = Icons.Default.ClosedCaption,
                        active = captions,
                        onClick = { captions = !captions },
                        modifier = Modifier.focusRequester(captionControlFocus),
                    )
                }

                if (hasTunerChannel) {
                    TifOsdActionChip(
                        label = if (commentsEnabled) "コメント ON" else "コメント OFF",
                        icon = Icons.Default.Comment,
                        active = commentsEnabled,
                        onClick = { commentsEnabled = !commentsEnabled },
                    )
                }

                if (audioTracks.size > 1) {
                    val selectedIndex = audioTracks.indexOfFirst { it.id == selectedAudioId }
                    val selection = if (selectedIndex >= 0) {
                        val labels = audioTracks.mapIndexed { index, track -> audioLabel(track, index) }
                        val base = labels[selectedIndex]
                        if (labels.count { it == base } > 1) "【${selectedIndex + 1}】$base" else base
                    } else "選択未通知"
                    TifOsdActionChip(
                        label = "音声: $selection",
                        icon = Icons.Default.VolumeUp,
                        onClick = {
                            val tracks = audioTracks
                            if (tracks.size >= 2) {
                                val currentIndex = tracks.indexOfFirst { it.id == selectedAudioId }
                                val wanted = tracks[(currentIndex + 1) % tracks.size].id
                                try {
                                    activeView?.selectTrack(TvTrackInfo.TYPE_AUDIO, wanted)
                                } catch (error: Exception) {
                                    videoStatus = TifVideoStatus.Notice("音声切替に失敗しました: " + error.message)
                                }
                            }
                        },
                    )
                }

                if (displaySettingsIntent != null) {
                    TifOsdActionChip(
                        label = "画質設定",
                        icon = Icons.Default.Tune,
                        onClick = {
                            overlayInteractionTick++
                            runCatching { context.startActivity(displaySettingsIntent) }
                        },
                    )
                }

                if (soundSettingsIntent != null) {
                    TifOsdActionChip(
                        label = "音声設定",
                        icon = Icons.Default.VolumeUp,
                        onClick = {
                            overlayInteractionTick++
                            runCatching { context.startActivity(soundSettingsIntent) }
                        },
                    )
                }

                TifOsdActionChip(
                    label = if (isPassthroughInput) "再接続" else "再選局",
                    icon = Icons.Default.Refresh,
                    onClick = { retuneGeneration++ },
                )

                TifOsdActionChip(
                    label = "終了",
                    icon = Icons.Default.Close,
                    onClick = onExit,
                    modifier = Modifier
                        .focusRequester(exitControlFocus)
                        .focusProperties { right = systemSettingsFocus },
                )
            }

            TifTopSystemStrip(
                timeFormat = timeFormat,
                settingsFocusRequester = systemSettingsFocus,
                clockFocusRequester = clockFocus,
                leftFocusRequester = exitControlFocus,
                onLaunchSystemSettings = onLaunchSystemSettings,
                onClockClick = onClockClick,
                onInteraction = { overlayInteractionTick++ },
                onHasFocusChanged = { hasFocus ->
                    if (hasFocus) {
                        controlHasFocus = true
                        overlayInteractionTick++
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(horizontal = 24.dp, vertical = 18.dp),
            )
        }

        // input/URI未解決時は解決失敗だけを表示し、映像受信待ちを重ねない。
        if (
            target != null &&
            videoStatus !is TifVideoStatus.Playing &&
            videoStatus.text.isNotEmpty() &&
            !suppressNoSignalVisual &&
            noSignalRenderMode == NoSignalRenderMode.BANNER
        ) {
            TifStatusBanner(
                text = videoStatus.text,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (standaloneInput && isMiniListOpen) {
            com.beeregg2001.komorebi.ui.home.TvInputSourceDialog(
                onOpenChannel = {
                    onMiniListToggle(false)
                    onChannelSelect(it)
                },
                onSystemPicker = null,
                onDismiss = { onMiniListToggle(false) },
                onReturnToHonorebi = {
                    onMiniListToggle(false)
                    onExit()
                },
            )
        } else if (isMiniListOpen && allChannels.isNotEmpty()) {
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                ChannelListOverlay(
                    groupedChannels = groupedChannels,
                    recentChannels = emptyList(),
                    recentRecordings = emptyList(),
                    backendType = "TIF",
                    konomiIp = "",
                    konomiPort = "",
                    currentChannelId = channel.id,
                    onChannelSelect = {
                        onChannelSelect(it)
                        onMiniListToggle(false)
                    },
                    onRecordingSelect = {},
                    logoUrls = emptyMap(),
                    shouldCropLogo = false,
                    focusRequester = listFocus
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun TifTopSystemStrip(
    timeFormat: String,
    settingsFocusRequester: FocusRequester,
    clockFocusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    onLaunchSystemSettings: () -> Unit,
    onClockClick: () -> Unit,
    onInteraction: () -> Unit,
    onHasFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    val barShape = RoundedCornerShape(14.dp)
    val itemShape = RoundedCornerShape(10.dp)
    val settingsInteraction = remember { MutableInteractionSource() }
    val clockInteraction = remember { MutableInteractionSource() }
    val isSettingsFocused by settingsInteraction.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .background(colors.surface1.copy(alpha = 0.72f), barShape)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) onInteraction()
                false
            }
            .onFocusChanged { onHasFocusChanged(it.hasFocus) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            onClick = {
                onInteraction()
                onLaunchSystemSettings()
            },
            modifier = Modifier
                .size(44.dp)
                .focusRequester(settingsFocusRequester)
                .focusProperties {
                    left = leftFocusRequester
                    right = clockFocusRequester
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                },
            interactionSource = settingsInteraction,
            shape = ClickableSurfaceDefaults.shape(itemShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = colors.surface2.copy(alpha = 0.92f),
                pressedContainerColor = colors.accentSoft,
                contentColor = colors.textSecondary,
                focusedContentColor = colors.textPrimary,
                pressedContentColor = colors.textPrimary,
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border.None,
                focusedBorder = Border(BorderStroke(2.dp, colors.accent), shape = itemShape),
                pressedBorder = Border(BorderStroke(2.dp, colors.accent), shape = itemShape),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "システム設定",
                    tint = if (isSettingsFocused) colors.accent else colors.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Surface(
            onClick = {
                onInteraction()
                onClockClick()
            },
            modifier = Modifier
                .height(44.dp)
                .focusRequester(clockFocusRequester)
                .focusProperties {
                    left = settingsFocusRequester
                    right = FocusRequester.Cancel
                    up = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                },
            interactionSource = clockInteraction,
            shape = ClickableSurfaceDefaults.shape(itemShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = colors.surface2.copy(alpha = 0.92f),
                pressedContainerColor = colors.accentSoft,
                contentColor = colors.textPrimary,
                focusedContentColor = colors.textPrimary,
                pressedContentColor = colors.textPrimary,
            ),
            border = ClickableSurfaceDefaults.border(
                border = Border.None,
                focusedBorder = Border(BorderStroke(2.dp, colors.accent), shape = itemShape),
                pressedBorder = Border(BorderStroke(2.dp, colors.accent), shape = itemShape),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                DigitalClock(timeFormat = timeFormat)
            }
        }
    }
}

@Composable
internal fun TifStatusBanner(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .padding(horizontal = 32.dp)
            .background(colors.surface1.copy(alpha = 0.92f), shape)
            .border(1.dp, colors.borderSubtle, shape)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TifOsdActionChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    val colors = KomorebiTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)

    Surface(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        modifier = modifier.padding(2.dp),
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) colors.accentSoft else Color.Transparent,
            focusedContainerColor = colors.surface2.copy(alpha = 0.92f),
            pressedContainerColor = colors.accentSoft,
            disabledContainerColor = Color.Transparent,
            contentColor = if (active) colors.accent else colors.textPrimary,
            focusedContentColor = colors.textPrimary,
            pressedContentColor = colors.textPrimary,
            disabledContentColor = colors.textSecondary.copy(alpha = 0.45f),
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
            pressedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    !enabled -> colors.textSecondary.copy(alpha = 0.45f)
                    isFocused || active -> colors.accent
                    else -> colors.textSecondary
                },
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isFocused || active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        }
    }
}
