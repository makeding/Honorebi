package com.beeregg2001.komorebi.ui.live

import android.media.tv.TvContentRating
import android.media.tv.TvInputManager
import android.media.tv.TvTrackInfo
import android.media.tv.TvView
import android.os.Handler
import android.os.Looper
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
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.repository.tif.TifInput
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** TvTrackInfoの説明を優先し、主副音声の意味をtrack IDから推測しない。 */
private fun audioLabel(track: TvTrackInfo, index: Int): String =
    track.description?.toString()?.takeIf { it.isNotBlank() }
        ?: track.language?.takeIf { it.isNotBlank() }
        ?: "音声トラック ${index + 1}"

private fun videoUnavailableMessage(reason: Int, isPassthrough: Boolean): String = when (reason) {
    TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING ->
        if (isPassthrough) "信号を検出しています…" else "選局しています…"
    TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL ->
        if (isPassthrough) "信号がありません" else "電波が弱いか信号がありません"
    TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING -> "読み込み中…"
    TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY -> "音声のみの放送です"
    5 -> "入力機器が接続されていません" // VIDEO_UNAVAILABLE_REASON_NOT_CONNECTED (API 30+)
    else -> if (isPassthrough) "映像信号がありません (reason=$reason)" else "映像を利用できません (reason=$reason)"
}

/**
 * Android TV Input Frameworkのライブ視聴画面。
 * ネットワーク配信/Media3/Komorebiネイティブライブラリには依存しない。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
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
    standaloneInput: Boolean = false,
) {
    val colors = KomorebiTheme.colors
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
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
    var retuneGeneration by remember(channel.id) { mutableIntStateOf(0) }
    var coldHandshakeRetries by remember(channel.id, isStarted, sessionGeneration) { mutableIntStateOf(0) }
    var lastUnavailableReason by remember(channel.id, isStarted, retuneGeneration, sessionGeneration) {
        mutableStateOf<Int?>(null)
    }
    var target by remember(channel.id, isStarted, retuneGeneration, sessionGeneration) {
        mutableStateOf<TifInput.TuneTarget?>(null)
    }
    var resolutionComplete by remember(channel.id, isStarted, retuneGeneration, sessionGeneration) { mutableStateOf(false) }
    var resolveError by remember(channel.id, isStarted, retuneGeneration, sessionGeneration) { mutableStateOf<String?>(null) }
    LaunchedEffect(context, channel.id, isStarted, retuneGeneration, sessionGeneration) {
        target = null
        resolutionComplete = false
        resolveError = null
        if (isStarted) {
            try {
                target = withContext(Dispatchers.IO) { TifInput.resolveTarget(context, channel.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                resolveError = "TV入力の確認に失敗しました: " + error.message
            }
        }
        resolutionComplete = true
    }

    var videoStatus by remember(channel.id, isStarted, retuneGeneration, sessionGeneration) {
        mutableStateOf("映像の受信を開始しています…")
    }
    var videoAvailable by remember(channel.id, isStarted, retuneGeneration, sessionGeneration) { mutableStateOf(false) }
    var contentBlocked by remember(channel.id, isStarted, retuneGeneration, sessionGeneration) { mutableStateOf(false) }
    var audioTracks by remember(channel.id, isStarted, retuneGeneration, sessionGeneration) {
        mutableStateOf<List<TvTrackInfo>>(emptyList())
    }
    var selectedAudioId by remember(channel.id, isStarted, retuneGeneration, sessionGeneration) { mutableStateOf<String?>(null) }
    var activeView by remember(channel.id, isStarted, retuneGeneration, sessionGeneration) { mutableStateOf<TvView?>(null) }
    var captions by rememberSaveable(channel.id, initialCaptions) { mutableStateOf(initialCaptions) }

    val rootFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val firstControlFocus = remember { FocusRequester() }
    val captionControlFocus = remember { FocusRequester() }
    val hideControlFocus = remember { FocusRequester() }
    var controlHasFocus by remember { mutableStateOf(false) }

    val isPassthroughInput = standaloneInput || channel.id.startsWith("tif:input:")
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
                                videoStatus = "信号を検出しています…"
                                retuneGeneration++
                            }
                        }
                        TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> {
                            if (!videoAvailable) {
                                videoStatus = "入力機器が待機状態です"
                            }
                        }
                        TvInputManager.INPUT_STATE_DISCONNECTED -> {
                            videoAvailable = false
                            videoStatus = "入力機器が接続されていません"
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
        delay(1_500L)
        if (!videoAvailable && !contentBlocked) {
            coldHandshakeRetries++
            videoStatus = "信号を検出しています…"
            retuneGeneration++
        }
    }

    BackHandler(enabled = isMiniListOpen) { onMiniListToggle(false) }
    LaunchedEffect(isMiniListOpen, allChannels.isEmpty()) {
        if (!standaloneInput && isMiniListOpen && allChannels.isEmpty()) onMiniListToggle(false)
    }

    // 操作帯を隠した場合は必ず画面本体へフォーカスを戻し、決定キーで再表示できる。
    // ボタン操作中はフォーカスを移さず、子要素の決定/左右キーを奪わない。
    LaunchedEffect(isMiniListOpen, showOverlay, allChannels.isEmpty(), isPassthroughInput) {
        if (!showOverlay || isMiniListOpen) controlHasFocus = false
        delay(60)
        runCatching {
            when {
                isMiniListOpen && allChannels.isNotEmpty() -> listFocus.requestFocus()
                !isMiniListOpen && showOverlay -> {
                    when {
                        standaloneInput || allChannels.isNotEmpty() -> firstControlFocus.requestFocus()
                        !isPassthroughInput -> captionControlFocus.requestFocus()
                        else -> hideControlFocus.requestFocus()
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
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || isMiniListOpen || controlHasFocus) {
                    return@onKeyEvent false
                }
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        if (showOverlay) {
                            when {
                                standaloneInput || allChannels.isNotEmpty() -> firstControlFocus.requestFocus()
                                !isPassthroughInput -> captionControlFocus.requestFocus()
                                else -> hideControlFocus.requestFocus()
                            }
                        }
                        else onShowOverlayChange(true)
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
            .focusable()
    ) {
        if (target == null) {
            TifStatusBanner(
                text = when {
                    !isStarted -> "バックグラウンドのためTV入力を停止しています"
                    !resolutionComplete -> "TV入力を確認しています…"
                    resolveError != null -> resolveError.orEmpty()
                    else -> "TV入力またはチャンネルが見つかりません。TV入力設定をご確認ください。"
                },
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            val tuneTarget = requireNotNull(target)
            key(tuneTarget.inputId, channel.id, retuneGeneration, sessionGeneration) {
                AndroidView(
                    factory = { viewContext ->
                        TvView(viewContext).apply {
                            isFocusable = false
                            isFocusableInTouchMode = false
                            setCallback(object : TvView.TvInputCallback() {
                                override fun onVideoAvailable(inputId: String) {
                                    onChannelPlaybackCommitted(channel)
                                    videoAvailable = true
                                    lastUnavailableReason = null
                                    coldHandshakeRetries = 0
                                    if (!contentBlocked) videoStatus = ""
                                }

                                override fun onContentBlocked(inputId: String, rating: TvContentRating) {
                                    contentBlocked = true
                                    videoStatus =
                                        "視聴年齢制限によりブロックされています。端末のTV入力・保護者による使用制限の設定を確認し、許可後に「再選局」を選択してください。"
                                }

                                override fun onContentAllowed(inputId: String) {
                                    if (contentBlocked) {
                                        contentBlocked = false
                                        videoStatus = if (videoAvailable) "" else "映像の受信を開始しています…"
                                    }
                                }

                                override fun onVideoSizeChanged(inputId: String, width: Int, height: Int) {
                                    if (width > 0 && height > 0) {
                                        videoAvailable = true
                                        if (!contentBlocked) videoStatus = ""
                                    }
                                }

                                override fun onVideoUnavailable(inputId: String, reason: Int) {
                                    videoAvailable = false
                                    lastUnavailableReason = reason
                                    if (!contentBlocked) {
                                        videoStatus = videoUnavailableMessage(reason, isPassthroughInput)
                                    }
                                }

                                override fun onConnectionFailed(inputId: String) {
                                    videoAvailable = false
                                    videoStatus = "TV入力との接続に失敗しました"
                                }

                                override fun onDisconnected(inputId: String) {
                                    videoAvailable = false
                                    videoStatus = "TV入力との接続が切れました"
                                }

                                override fun onTracksChanged(inputId: String, tracks: MutableList<TvTrackInfo>) {
                                    audioTracks = tracks.filter { it.type == TvTrackInfo.TYPE_AUDIO }
                                    if (audioTracks.none { it.id == selectedAudioId }) selectedAudioId = null
                                }

                                override fun onTrackSelected(inputId: String, type: Int, trackId: String?) {
                                    if (type == TvTrackInfo.TYPE_AUDIO) selectedAudioId = trackId
                                }
                            })
                            try {
                                setStreamVolume(1.0f)
                                tune(tuneTarget.inputId, tuneTarget.channelUri)
                                activeView = this
                                activeViewForLifecycle = this
                            } catch (error: Exception) {
                                videoStatus = "TV入力の選局に失敗しました: " + error.message
                                reset()
                            }
                        }
                    },
                    update = { view ->
                        runCatching { view.setCaptionEnabled(captions) }
                    },
                    onRelease = { view ->
                        view.setCallback(null)
                        view.reset()
                        if (activeView === view) activeView = null
                        if (activeViewForLifecycle === view) activeViewForLifecycle = null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (showOverlay && !isMiniListOpen) {
            val barShape = RoundedCornerShape(14.dp)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 24.dp, vertical = 18.dp)
                    .background(colors.surface1.copy(alpha = 0.92f), barShape)
                    .border(1.dp, colors.borderSubtle, barShape)
                    .horizontalScroll(rememberScrollState())
                    .onFocusChanged { controlHasFocus = it.hasFocus }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 4.dp, end = 6.dp),
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
                        .height(22.dp)
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

                if (audioTracks.isNotEmpty()) {
                    val selectedIndex = audioTracks.indexOfFirst { it.id == selectedAudioId }
                    val selection = if (selectedIndex >= 0) {
                        val labels = audioTracks.mapIndexed { index, track -> audioLabel(track, index) }
                        val base = labels[selectedIndex]
                        if (labels.count { it == base } > 1) "【${selectedIndex + 1}】$base" else base
                    } else "選択未通知"
                    TifOsdActionChip(
                        label = "音声: $selection",
                        icon = Icons.Default.VolumeUp,
                        enabled = audioTracks.size > 1,
                        onClick = {
                            val tracks = audioTracks
                            if (tracks.size >= 2) {
                                val currentIndex = tracks.indexOfFirst { it.id == selectedAudioId }
                                val wanted = tracks[(currentIndex + 1) % tracks.size].id
                                try {
                                    activeView?.selectTrack(TvTrackInfo.TYPE_AUDIO, wanted)
                                } catch (error: Exception) {
                                    videoStatus = "音声切替に失敗しました: " + error.message
                                }
                            }
                        },
                    )
                }

                if (!isPassthroughInput) {
                    TifOsdActionChip(
                        label = "再選局",
                        icon = Icons.Default.Refresh,
                        onClick = { retuneGeneration++ },
                    )
                } else if (!videoAvailable) {
                    TifOsdActionChip(
                        label = "再接続",
                        icon = Icons.Default.Refresh,
                        onClick = { retuneGeneration++ },
                    )
                }

                TifOsdActionChip(
                    label = "操作を隠す",
                    icon = Icons.Default.VisibilityOff,
                    onClick = { onShowOverlayChange(false) },
                    modifier = Modifier.focusRequester(hideControlFocus),
                )
            }
        }

        // input/URI未解決時は解決失敗だけを表示し、映像受信待ちを重ねない。
        if (target != null && videoStatus.isNotEmpty()) {
            TifStatusBanner(
                text = videoStatus,
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

@Composable
private fun TifStatusBanner(
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
            containerColor = if (active) colors.accentSoft else colors.surface2,
            focusedContainerColor = colors.accent,
            disabledContainerColor = colors.surface2.copy(alpha = 0.4f),
            contentColor = if (active) colors.accent else colors.textPrimary,
            focusedContentColor = colors.onAccent,
            disabledContentColor = colors.textSecondary.copy(alpha = 0.45f),
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                BorderStroke(1.dp, if (active) colors.accent.copy(alpha = 0.45f) else colors.borderSubtle),
                shape = shape,
            ),
            focusedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    !enabled -> colors.textSecondary.copy(alpha = 0.45f)
                    isFocused -> colors.onAccent
                    active -> colors.accent
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
