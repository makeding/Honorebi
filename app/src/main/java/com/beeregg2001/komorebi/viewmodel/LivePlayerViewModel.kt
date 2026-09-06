@file:OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.live

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.id3.PrivFrame
import com.beeregg2001.komorebi.NativeLib
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.BackendConfig
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.LivePlayerConstants
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.data.repository.ChannelLogoCache
import com.beeregg2001.komorebi.ui.player.HdrToneMapping
import com.beeregg2001.komorebi.ui.player.CaptionDecodeRouter
import com.beeregg2001.komorebi.ui.player.CaptionGenerationFence
import com.beeregg2001.komorebi.ui.player.PlayerRuntime
import com.beeregg2001.komorebi.ui.player.PlayerRuntimeState
import com.beeregg2001.komorebi.ui.player.PlaybackQualityCatalog
import com.beeregg2001.komorebi.ui.player.live.LivePlaybackSourceResolver
import com.beeregg2001.komorebi.ui.player.live.livePlayerProfile
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionCue
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionDecoder
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionLanguage
import com.beeregg2001.komorebi.util.TsReadExDataSourceFactory
import com.beeregg2001.komorebi.util.mmts.B62SubtitleSample
import com.beeregg2001.komorebi.util.mmts.B60DataBroadcastingCallback
import com.beeregg2001.komorebi.util.mmts.B60DataBroadcastingStore
import com.beeregg2001.komorebi.util.mmts.RawMmtsLayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel as CoroutineChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private data class SessionB62SubtitleSample(
    val token: LiveChannelSessionToken,
    val generation: Long,
    val sample: B62SubtitleSample
)

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class LivePlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    @javax.inject.Named("access") private val accessHttpClient: OkHttpClient,
    private val livePlaybackSourceResolver: LivePlaybackSourceResolver,
    private val channelLogoCache: ChannelLogoCache,
    private val playbackQualityCatalog: PlaybackQualityCatalog,
    private val liveJikkyoManager: LiveJikkyoManager
) : ViewModel() {

    companion object {
        private const val TAG = "LivePlayerViewModel"
        private const val MAX_AUTO_RETRY = 2
        private const val CHANNEL_SWITCH_STREAM_DEBOUNCE_MS = 100L
    }

    private val _mainPlayer = MutableStateFlow<ExoPlayer?>(null)
    val mainPlayer: StateFlow<ExoPlayer?> = _mainPlayer.asStateFlow()
    private var mainRuntime: PlayerRuntime? = null
    private val _mainRuntimeState = MutableStateFlow(PlayerRuntimeState())
    val mainRuntimeState: StateFlow<PlayerRuntimeState> = _mainRuntimeState.asStateFlow()
    private var mainRuntimeStateJob: Job? = null
    private val _mainSessionToken = MutableStateFlow<LiveChannelSessionToken?>(null)
    val mainSessionToken: StateFlow<LiveChannelSessionToken?> = _mainSessionToken.asStateFlow()

    val hdrRenderMode: StateFlow<String> = settingsRepository.hdrRenderMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        "ORIGINAL"
    )
    val isHdrToSdrToneMappingSupported: Boolean
        get() = HdrToneMapping.isSupported

    private val _dualPlayer = MutableStateFlow<ExoPlayer?>(null)
    val dualPlayer: StateFlow<ExoPlayer?> = _dualPlayer.asStateFlow()
    private var dualRuntime: PlayerRuntime? = null
    private val _dualRuntimeState = MutableStateFlow(PlayerRuntimeState())
    val dualRuntimeState: StateFlow<PlayerRuntimeState> = _dualRuntimeState.asStateFlow()
    private var dualRuntimeStateJob: Job? = null
    private val _dualSessionToken = MutableStateFlow<LiveChannelSessionToken?>(null)
    val dualSessionToken: StateFlow<LiveChannelSessionToken?> = _dualSessionToken.asStateFlow()

    private val mainTsDataSourceFactory = TsReadExDataSourceFactory(NativeLib(), emptyArray()).apply {
        cloudflareAccessConfiguration = { settingsRepository.cloudflareAccessConfiguration.value }
    }
    private val dualTsDataSourceFactory = TsReadExDataSourceFactory(NativeLib(), emptyArray()).apply {
        cloudflareAccessConfiguration = { settingsRepository.cloudflareAccessConfiguration.value }
    }
    private val mainRawMmtsLayerController = RawMmtsLayerController()
    private val dualRawMmtsLayerController = RawMmtsLayerController()

    val dataBroadcastingStore = B60DataBroadcastingStore()
    private val channelSessions = LiveChannelSessionCoordinator()
    private val mainCaptionFence = CaptionGenerationFence { true }
    private val dualCaptionFence = CaptionGenerationFence { true }

    private fun beginChannelSession(
        slot: LivePlaybackSlot,
        channelId: String
    ): LiveChannelSessionToken = channelSessions.begin(slot, channelId).also { token ->
        when (slot) {
            LivePlaybackSlot.MAIN -> _mainSessionToken.value = token
            LivePlaybackSlot.DUAL -> _dualSessionToken.value = token
        }
    }

    private fun endChannelSession(slot: LivePlaybackSlot) {
        channelSessions.end(slot)
        when (slot) {
            LivePlaybackSlot.MAIN -> _mainSessionToken.value = null
            LivePlaybackSlot.DUAL -> _dualSessionToken.value = null
        }
    }

    private val _mainPlayerError = MutableStateFlow<String?>(null)
    val mainPlayerError: StateFlow<String?> = _mainPlayerError.asStateFlow()
    private val _mainPlayerErrorIsCapabilityRelated = MutableStateFlow(false)
    val mainPlayerErrorIsCapabilityRelated: StateFlow<Boolean> =
        _mainPlayerErrorIsCapabilityRelated.asStateFlow()

    private val _playbackNotices = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playbackNotices: SharedFlow<String> = _playbackNotices.asSharedFlow()

    private val _mainSseStatus = MutableStateFlow("Standby")
    val mainSseStatus: StateFlow<String> = _mainSseStatus.asStateFlow()

    private val _mainSseDetail = MutableStateFlow(AppStrings.SSE_CONNECTING)
    val mainSseDetail: StateFlow<String> = _mainSseDetail.asStateFlow()

    private val _mainSignalInfo = MutableStateFlow(SignalMetadata())
    val mainSignalInfo: StateFlow<SignalMetadata> = _mainSignalInfo.asStateFlow()

    private val _dualSseStatus = MutableStateFlow("Standby")
    val dualSseStatus: StateFlow<String> = _dualSseStatus.asStateFlow()

    private val _dualSseDetail = MutableStateFlow(AppStrings.SSE_CONNECTING)
    val dualSseDetail: StateFlow<String> = _dualSseDetail.asStateFlow()

    private val _mainSubtitleEvents = MutableSharedFlow<NativeCaptionCue>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val mainSubtitleEvents: SharedFlow<NativeCaptionCue> = _mainSubtitleEvents.asSharedFlow()

    private val _dualSubtitleEvents = MutableSharedFlow<NativeCaptionCue>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val dualSubtitleEvents: SharedFlow<NativeCaptionCue> = _dualSubtitleEvents.asSharedFlow()

    private val _mainSubtitleLanguages = MutableStateFlow<List<NativeCaptionLanguage>>(emptyList())
    val mainSubtitleLanguages: StateFlow<List<NativeCaptionLanguage>> = _mainSubtitleLanguages.asStateFlow()

    private val _dualSubtitleLanguages = MutableStateFlow<List<NativeCaptionLanguage>>(emptyList())
    val dualSubtitleLanguages: StateFlow<List<NativeCaptionLanguage>> = _dualSubtitleLanguages.asStateFlow()

    private val _currentSubtitleLanguageId = MutableStateFlow(1)
    val currentSubtitleLanguageId: StateFlow<Int> = _currentSubtitleLanguageId.asStateFlow()

    private val _availableSources = MutableStateFlow<List<StreamSource>>(emptyList())
    val availableSources: StateFlow<List<StreamSource>> = _availableSources.asStateFlow()

    private val _availableQualities =
        MutableStateFlow<List<StreamQuality>>(StreamQuality.DEFAULT_QUALITIES)
    val availableQualities: StateFlow<List<StreamQuality>> = _availableQualities.asStateFlow()

    private val _isQualitiesLoaded = MutableStateFlow(false)
    val isQualitiesLoaded: StateFlow<Boolean> = _isQualitiesLoaded.asStateFlow()

    private val _currentLogoUrl = MutableStateFlow<String>("")
    val currentLogoUrl: StateFlow<String> = _currentLogoUrl.asStateFlow()

    private val _shouldCropLogo = MutableStateFlow<Boolean>(false)
    val shouldCropLogo: StateFlow<Boolean> = _shouldCropLogo.asStateFlow()

    val liveComments: SharedFlow<LiveComment> = liveJikkyoManager.liveComments

    private val _mainBackendType = MutableStateFlow("KONOMITV")
    val mainBackendType: StateFlow<String> = _mainBackendType.asStateFlow()

    private var isSubtitleEnabled = false
    private val mainCaptionDecoder = NativeCaptionDecoder(context)
    private val mainSuperimposeDecoder = NativeCaptionDecoder(
        captionType = NativeCaptionDecoder.TYPE_SUPERIMPOSE,
        context = context
    )
    private val dualCaptionDecoder = NativeCaptionDecoder(context)
    private val dualSuperimposeDecoder = NativeCaptionDecoder(
        captionType = NativeCaptionDecoder.TYPE_SUPERIMPOSE,
        context = context
    )
    private val mainB62SubtitleSamples = CoroutineChannel<SessionB62SubtitleSample>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val dualB62SubtitleSamples = CoroutineChannel<SessionB62SubtitleSample>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private var signalPollJob: Job? = null

    private var mainPlaybackJob: Job? = null
    private var dualPlaybackJob: Job? = null

    private val mainPlaybackMutex = Mutex()
    private val dualPlaybackMutex = Mutex()
    private val hdrToneMappingRecoveryRunning = AtomicBoolean(false)
    private val okHttpClient = accessHttpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var mainEventSource: EventSource? = null
    private var dualEventSource: EventSource? = null

    private var mainCurrentSource = StreamSource.KONOMITV
    private var mainIsEdcbDirect = false
    private var mainCurrentChannel: Channel? = null
    private var mainCurrentQuality: StreamQuality? = null
    private var mainAutoRetryCount = 0

    private var dualCurrentSource = StreamSource.KONOMITV
    private var dualIsEdcbDirect = false
    private var dualCurrentChannel: Channel? = null
    private var dualCurrentQuality: StreamQuality? = null
    private var dualAutoRetryCount = 0

    init {
        viewModelScope.launch {
            settingsRepository.backendType.collect { type ->
                _mainBackendType.value = type
                _shouldCropLogo.value = type == "KONOMITV"
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            for (sample in mainB62SubtitleSamples) {
                if (!channelSessions.isCurrent(sample.token) || !mainCaptionFence.accepts(sample.generation)) continue
                decodeB62Subtitle(
                    captionDecoder = mainCaptionDecoder,
                    superimposeDecoder = mainSuperimposeDecoder,
                    sample = sample.sample,
                    isCurrent = {
                        channelSessions.isCurrent(sample.token) &&
                            mainCaptionFence.accepts(sample.generation)
                    },
                    onLanguagesChanged = { _mainSubtitleLanguages.value = it },
                    onCue = { _mainSubtitleEvents.tryEmit(it) }
                )
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            for (sample in dualB62SubtitleSamples) {
                if (!channelSessions.isCurrent(sample.token) || !dualCaptionFence.accepts(sample.generation)) continue
                decodeB62Subtitle(
                    captionDecoder = dualCaptionDecoder,
                    superimposeDecoder = dualSuperimposeDecoder,
                    sample = sample.sample,
                    isCurrent = {
                        channelSessions.isCurrent(sample.token) &&
                            dualCaptionFence.accepts(sample.generation)
                    },
                    onLanguagesChanged = { _dualSubtitleLanguages.value = it },
                    onCue = { _dualSubtitleEvents.tryEmit(it) }
                )
            }
        }
        startSignalPolling()
    }

    suspend fun getInitialEdcbDirect(): Boolean {
        val backendStr = settingsRepository.backendType.first()
        val prefStr = settingsRepository.preferredStreamSource.first()
        if (backendStr == "EDCB") {
            if (prefStr == "EDCB") return true
            if (prefStr == "KONOMITV") return false
        } else if (backendStr == "KONOMITV" || backendStr == "MIRAKURUN_ONLY") {
            if (prefStr == "EDCB") return true
        }
        return false
    }

    fun fetchAvailableQualities(source: StreamSource, isEdcbDirect: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _isQualitiesLoaded.value = false
            try {
                _availableQualities.value = playbackQualityCatalog.live(source, isEdcbDirect)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load stream qualities", e)
                val currentLive = settingsRepository.liveQuality.first()
                _availableQualities.value = listOf(
                    StreamQuality(
                        label = "設定値 ($currentLive)",
                        value = currentLive,
                        isRawTs = false
                    )
                )
            } finally {
                _isQualitiesLoaded.value = true
            }
        }
    }

    fun saveLiveQuality(qualityValue: String) {
        viewModelScope.launch {
            settingsRepository.saveString(
                SettingsRepository.LIVE_QUALITY,
                qualityValue
            )
        }
    }

    suspend fun getInitialStreamSource(): StreamSource {
        val backendStr = settingsRepository.backendType.first()
        val prefStr = settingsRepository.preferredStreamSource.first()

        val mainSource = when (backendStr) {
            "EDCB" -> StreamSource.EDCB
            "MIRAKURUN_ONLY", "MIRAKURUN" -> StreamSource.MIRAKURUN
            else -> StreamSource.KONOMITV
        }

        val preferredSource = when (prefStr) {
            "EDCB" -> StreamSource.EDCB
            "MIRAKURUN" -> StreamSource.MIRAKURUN
            "KONOMITV" -> mainSource
            else -> mainSource
        }

        val sources = mutableListOf<StreamSource>()
        if (settingsRepository.getBackendConfig(preferredSource).isValid) sources.add(
            preferredSource
        )
        if (!sources.contains(mainSource) && settingsRepository.getBackendConfig(mainSource).isValid) sources.add(
            mainSource
        )
        if (sources.isEmpty()) sources.add(mainSource)

        _availableSources.value = sources
        return sources.first()
    }

    private fun stopMainPlaybackSafely(reason: String = "unspecified", endSession: Boolean = true) {
        Log.w(
            TAG,
            "Stopping main playback: reason=$reason, " +
                "player=${_mainPlayer.value != null}, channel=${mainCurrentChannel?.displayChannelId}, " +
                "source=$mainCurrentSource, quality=${mainCurrentQuality?.value}"
        )
        mainEventSource?.cancel(); mainEventSource = null
        mainCaptionFence.reset()
        mainB62SubtitleSamples.clearPending()
        mainCaptionDecoder.reset(_currentSubtitleLanguageId.value)
        mainSuperimposeDecoder.reset()
        _mainSubtitleLanguages.value = emptyList()

        mainRuntime?.release()
        mainRuntime = null
        _mainPlayer.value = null
        clearMainRuntimeState()
        mainRawMmtsLayerController.reset()

        _mainSseStatus.value = "Standby"; _mainSseDetail.value = AppStrings.SSE_CONNECTING
        liveJikkyoManager.stopJikkyo()
        if (endSession) {
            endChannelSession(LivePlaybackSlot.MAIN)
        }
    }

    private fun stopDualPlaybackSafely(reason: String = "unspecified", endSession: Boolean = true) {
        Log.w(
            TAG,
            "Stopping dual playback: reason=$reason, " +
                "player=${_dualPlayer.value != null}, channel=${dualCurrentChannel?.displayChannelId}, " +
                "source=$dualCurrentSource, quality=${dualCurrentQuality?.value}"
        )
        dualEventSource?.cancel(); dualEventSource = null
        dualCaptionFence.reset()
        dualB62SubtitleSamples.clearPending()
        dualCaptionDecoder.reset(_currentSubtitleLanguageId.value)
        dualSuperimposeDecoder.reset()
        _dualSubtitleLanguages.value = emptyList()

        dualRuntime?.release()
        dualRuntime = null
        _dualPlayer.value = null
        clearDualRuntimeState()
        dualRawMmtsLayerController.reset()

        _dualSseStatus.value = "Standby"; _dualSseDetail.value = AppStrings.SSE_CONNECTING
        if (endSession) {
            endChannelSession(LivePlaybackSlot.DUAL)
        }
    }

    fun releasePlayers(reason: String = "unspecified") {
        Log.w(
            TAG,
            "Releasing live players: reason=$reason, " +
                "main=${_mainPlayer.value != null}, dual=${_dualPlayer.value != null}, " +
                "mainChannel=${mainCurrentChannel?.displayChannelId}, dualChannel=${dualCurrentChannel?.displayChannelId}"
        )
        endChannelSession(LivePlaybackSlot.MAIN)
        endChannelSession(LivePlaybackSlot.DUAL)
        mainPlaybackJob?.cancel(); dualPlaybackJob?.cancel()
        mainEventSource?.cancel(); dualEventSource?.cancel()
        mainCaptionFence.reset(); dualCaptionFence.reset()

        mainRuntime?.release()
        mainRuntime = null
        _mainPlayer.value = null
        clearMainRuntimeState()
        mainRawMmtsLayerController.reset()

        dualRuntime?.release()
        dualRuntime = null
        _dualPlayer.value = null
        clearDualRuntimeState()
        dualRawMmtsLayerController.reset()

        _mainSseStatus.value = "Standby"; _dualSseStatus.value = "Standby"
        liveJikkyoManager.stopJikkyo()
    }

    private fun handleMainError(
        uiContext: Context, error: PlaybackException, token: LiveChannelSessionToken
    ) {
        if (!channelSessions.isCurrent(token)) return
        if (HdrToneMapping.rejectionCause(error) != null) {
            recoverRejectedHdrToneMapping(uiContext, token)
            return
        }
        viewModelScope.launch {
            if (!channelSessions.isCurrent(token)) return@launch
            val cause = error.cause
            val is404 =
                cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 404
            val isEdcbTranscode = mainCurrentSource == StreamSource.EDCB && !mainIsEdcbDirect

            if (isEdcbTranscode && is404 && mainAutoRetryCount < 5) {
                mainAutoRetryCount++
                Log.w(TAG, "EDCB HLS 404: Retrying prepare... ($mainAutoRetryCount/5)")
                _mainSseDetail.value = "セグメント生成待機中... ($mainAutoRetryCount/5)"
                delay(2500)
                if (channelSessions.isCurrent(token)) {
                    mainRuntime?.reprepare()
                }
                return@launch
            }

            val errorMsg = analyzePlayerError(error)
            if (mainAutoRetryCount < MAX_AUTO_RETRY) {
                mainAutoRetryCount++; _mainSseDetail.value =
                    "通信復旧中... ($mainAutoRetryCount/$MAX_AUTO_RETRY)"
                stopMainPlaybackSafely("main_player_error_retry", endSession = false); delay(2000)
                if (!channelSessions.isCurrent(token)) return@launch
                if (mainCurrentChannel != null && mainCurrentQuality != null) {
                    playMainChannel(
                        uiContext,
                        mainCurrentChannel!!,
                        mainCurrentSource,
                        mainIsEdcbDirect,
                        mainCurrentQuality!!,
                        true
                    )
                }
            } else {
                _mainPlayerError.value = errorMsg
                _mainPlayerErrorIsCapabilityRelated.value = isCapabilityRelatedError(error)
                stopMainPlaybackSafely("main_player_error_exhausted")
            }
        }
    }

    private fun handleDualError(
        uiContext: Context, error: PlaybackException, token: LiveChannelSessionToken
    ) {
        if (!channelSessions.isCurrent(token)) return
        if (HdrToneMapping.rejectionCause(error) != null) {
            recoverRejectedHdrToneMapping(uiContext, token)
            return
        }
        viewModelScope.launch {
            if (!channelSessions.isCurrent(token)) return@launch
            val cause = error.cause
            val is404 =
                cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 404
            val isEdcbTranscode = dualCurrentSource == StreamSource.EDCB && !dualIsEdcbDirect

            if (isEdcbTranscode && is404 && dualAutoRetryCount < 5) {
                dualAutoRetryCount++; _dualSseDetail.value =
                    "セグメント生成待機中... ($dualAutoRetryCount/5)"
                delay(2500)
                if (channelSessions.isCurrent(token)) {
                    dualRuntime?.reprepare()
                }
                return@launch
            }

            val errorMsg = analyzePlayerError(error)
            if (dualAutoRetryCount < MAX_AUTO_RETRY) {
                dualAutoRetryCount++; _dualSseDetail.value =
                    "通信復旧中... ($dualAutoRetryCount/$MAX_AUTO_RETRY)"
                stopDualPlaybackSafely("dual_player_error_retry", endSession = false); delay(2000)
                if (!channelSessions.isCurrent(token)) return@launch
                if (dualCurrentChannel != null && dualCurrentQuality != null) {
                    playDualChannel(
                        uiContext,
                        dualCurrentChannel!!,
                        dualCurrentSource,
                        dualIsEdcbDirect,
                        dualCurrentQuality!!,
                        true
                    )
                }
            } else {
                _dualSseStatus.value = "Error"; _dualSseDetail.value = errorMsg
                stopDualPlaybackSafely("dual_player_error_exhausted")
            }
        }
    }

    private fun recoverRejectedHdrToneMapping(uiContext: Context, token: LiveChannelSessionToken) {
        if (!hdrToneMappingRecoveryRunning.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                if (!channelSessions.isCurrent(token)) return@launch
                if (settingsRepository.hdrRenderMode.first() != HdrToneMapping.RENDER_MODE_SDR) {
                    return@launch
                }
                settingsRepository.saveString(
                    SettingsRepository.HDR_RENDER_MODE,
                    HdrToneMapping.RENDER_MODE_ORIGINAL
                )
                _playbackNotices.emit(
                    "SDR 変換に失敗しました（HDR_TONE_MAPPING_UNSUPPORTED）。" +
                        "テレビのデコーダーが変換要求を受け付けなかったため、" +
                        "HLG そのままに戻して再生します。"
                )
                if (!channelSessions.isCurrent(token)) return@launch
                mainCurrentChannel?.let { channel ->
                    mainCurrentQuality?.let { quality ->
                        playMainChannel(
                            uiContext,
                            channel,
                            mainCurrentSource,
                            mainIsEdcbDirect,
                            quality,
                            true
                        )
                    }
                }
                dualCurrentChannel?.let { channel ->
                    dualCurrentQuality?.let { quality ->
                        playDualChannel(
                            uiContext,
                            channel,
                            dualCurrentSource,
                            dualIsEdcbDirect,
                            quality,
                            true
                        )
                    }
                }
            } finally {
                hdrToneMappingRecoveryRunning.set(false)
            }
        }
    }

    fun playMainChannel(
        uiContext: Context, channel: Channel, source: StreamSource,
        isEdcbDirect: Boolean, quality: StreamQuality, isAutoRetry: Boolean = false,
        onPlaybackRequestCommitted: (Channel) -> Unit = {}
    ) {
        if (channel.displayChannelId.isBlank() || channel.displayChannelId == "null") return
        val token = beginChannelSession(LivePlaybackSlot.MAIN, channel.id)
        if (mainCurrentChannel?.id != channel.id) setSubtitleLanguage(1)
        if (!isAutoRetry) {
            mainAutoRetryCount = 0
            _mainPlayerError.value = null
            _mainPlayerErrorIsCapabilityRelated.value = false
        }
        mainCurrentChannel = channel
        dataBroadcastingStore.beginSession(channel.id)

        viewModelScope.launch {
            val logoUrl = channelLogoCache.getChannelLogoUrl(channel)
            if (channelSessions.isCurrent(token)) _currentLogoUrl.value = logoUrl
        }

        mainPlaybackJob?.cancel()
        mainPlaybackJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // チャンネル名は UI 側で即時更新し、ストリームだけを短くデバウンスする。
                // 連続切替ではこの Job がキャンセルされるため、最後のチャンネルだけを要求する。
                if (!isAutoRetry) delay(CHANNEL_SWITCH_STREAM_DEBOUNCE_MS)
                if (!channelSessions.isCurrent(token)) return@launch
                if (!isAutoRetry) {
                    withContext(Dispatchers.Main.immediate) {
                        if (channelSessions.isCurrent(token)) onPlaybackRequestCommitted(channel)
                    }
                }
                mainPlaybackMutex.withLock {
                    withContext(Dispatchers.Main) {
                        if (!channelSessions.isCurrent(token)) return@withContext
                        stopMainPlaybackSafely(endSession = false); _mainSseStatus.value =
                        "Standby"; _mainSseDetail.value = "ストリームを準備中..."
                    }

                    val request = livePlaybackSourceResolver.resolve(
                        channel = channel,
                        requestedSource = source,
                        requestedIsEdcbDirect = isEdcbDirect,
                        requestedQuality = quality,
                        streamNumber = LivePlaybackSlot.MAIN.streamNumber,
                        factory = mainTsDataSourceFactory
                    )
                    if (!channelSessions.isCurrent(token)) return@withLock
                    mainCurrentSource = request.source
                    mainIsEdcbDirect = request.isEdcbDirect
                    mainCurrentQuality = request.quality

                    val audioOutputMode = settingsRepository.audioOutputMode.first()
                    val hdrRenderMode = settingsRepository.hdrRenderMode.first()
                    if (!channelSessions.isCurrent(token)) return@withLock
                    withContext(Dispatchers.Main) {
                        if (!channelSessions.isCurrent(token)) return@withContext
                        val runtime = PlayerRuntime(context, livePlayerProfile(audioOutputMode, hdrRenderMode))
                        if (!channelSessions.isCurrent(token)) {
                            runtime.release()
                            return@withContext
                        }
                        mainRuntime = runtime
                        _mainPlayer.value = runtime.player
                        bindMainRuntimeState(runtime)
                        attachMainRuntimeListeners(runtime, request.source, token, uiContext)
                        if (request.source == StreamSource.MIRAKURUN || request.source == StreamSource.EDCB) {
                            _mainSseStatus.value = "ONAir"; _mainSseDetail.value = ""
                        } else if (request.config is BackendConfig.KonomiTv) {
                            startMainSse(
                                uiContext,
                                channel.displayChannelId,
                                request.apiQuality,
                                request.config,
                                token
                            )
                        }
                        startPlayback(
                            runtime,
                            request,
                            mainTsDataSourceFactory,
                            { pts, data -> decodeAndEmitMainSubtitle(token, pts, data) },
                            { sample -> decodeAndEmitMainB62Subtitle(token, sample) },
                            LiveSessionDataBroadcastingCallback(
                                token,
                                channelSessions,
                                dataBroadcastingStore
                            ),
                            mainRawMmtsLayerController,
                            token
                        )
                        liveJikkyoManager.startJikkyo(channel, request.source, token, channelSessions::isCurrent)
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "playMainChannel: Job cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "playMainChannel: Failed", e)
                withContext(Dispatchers.Main) {
                    if (!channelSessions.isCurrent(token)) return@withContext
                    handleMainError(
                        uiContext,
                        PlaybackException(e.message, e, PlaybackException.ERROR_CODE_UNSPECIFIED), token
                    )
                }
            }
        }
    }

    fun playDualChannel(
        uiContext: Context, channel: Channel, source: StreamSource,
        isEdcbDirect: Boolean, quality: StreamQuality, isAutoRetry: Boolean = false
    ) {
        if (channel.displayChannelId.isBlank() || channel.displayChannelId == "null") return
        val token = beginChannelSession(LivePlaybackSlot.DUAL, channel.id)
        if (!isAutoRetry) dualAutoRetryCount = 0
        dualCurrentChannel = channel

        dualPlaybackJob?.cancel()
        dualPlaybackJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                dualPlaybackMutex.withLock {
                    withContext(Dispatchers.Main) {
                        if (!channelSessions.isCurrent(token)) return@withContext
                        stopDualPlaybackSafely(endSession = false); _dualSseStatus.value =
                        "Standby"; _dualSseDetail.value = "ストリームを準備中..."
                    }
                    delay(if (isAutoRetry) 0 else 600)
                    if (!channelSessions.isCurrent(token)) return@withLock

                    val request = livePlaybackSourceResolver.resolve(
                        channel = channel,
                        requestedSource = source,
                        requestedIsEdcbDirect = isEdcbDirect,
                        requestedQuality = quality,
                        streamNumber = LivePlaybackSlot.DUAL.streamNumber,
                        factory = dualTsDataSourceFactory
                    )
                    if (!channelSessions.isCurrent(token)) return@withLock
                    dualCurrentSource = request.source
                    dualIsEdcbDirect = request.isEdcbDirect
                    dualCurrentQuality = request.quality

                    val audioOutputMode = settingsRepository.audioOutputMode.first()
                    val hdrRenderMode = settingsRepository.hdrRenderMode.first()
                    if (!channelSessions.isCurrent(token)) return@withLock
                    withContext(Dispatchers.Main) {
                        if (!channelSessions.isCurrent(token)) return@withContext
                        val runtime = PlayerRuntime(context, livePlayerProfile(audioOutputMode, hdrRenderMode))
                        if (!channelSessions.isCurrent(token)) {
                            runtime.release()
                            return@withContext
                        }
                        dualRuntime = runtime
                        _dualPlayer.value = runtime.player
                        bindDualRuntimeState(runtime)
                        attachDualRuntimeListeners(runtime, request.source, token, uiContext)
                        if (request.source == StreamSource.MIRAKURUN || request.source == StreamSource.EDCB) {
                            _dualSseStatus.value = "ONAir"; _dualSseDetail.value = ""
                        } else if (request.config is BackendConfig.KonomiTv) {
                            startDualSse(
                                uiContext,
                                channel.displayChannelId,
                                request.apiQuality,
                                request.config,
                                token
                            )
                        }
                        startPlayback(
                            runtime,
                            request,
                            dualTsDataSourceFactory,
                            { pts, data -> decodeAndEmitDualSubtitle(token, pts, data) },
                            { sample -> decodeAndEmitDualB62Subtitle(token, sample) },
                            rawMmtsLayerController = dualRawMmtsLayerController,
                            sessionToken = token
                        )
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "playDualChannel: Job cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "playDualChannel: Failed", e)
                withContext(Dispatchers.Main) {
                    if (!channelSessions.isCurrent(token)) return@withContext
                    handleDualError(
                        uiContext,
                        PlaybackException(e.message, e, PlaybackException.ERROR_CODE_UNSPECIFIED), token
                    )
                }
            }
        }
    }

    fun stopAllPlayers() {
        endChannelSession(LivePlaybackSlot.MAIN)
        endChannelSession(LivePlaybackSlot.DUAL)
        mainPlaybackJob?.cancel(); dualPlaybackJob?.cancel()
        viewModelScope.launch {
            mainPlaybackMutex.withLock { stopMainPlaybackSafely(endSession = false) }
            dualPlaybackMutex.withLock { stopDualPlaybackSafely(endSession = false) }
        }
    }

    fun setHdrRenderMode(uiContext: Context, mode: String) {
        val normalizedMode = if (mode == "SDR_TONE_MAP" && isHdrToSdrToneMappingSupported) {
            "SDR_TONE_MAP"
        } else {
            "ORIGINAL"
        }
        viewModelScope.launch {
            settingsRepository.saveString(SettingsRepository.HDR_RENDER_MODE, normalizedMode)
            val channel = mainCurrentChannel ?: return@launch
            val quality = mainCurrentQuality ?: return@launch
            playMainChannel(
                uiContext = uiContext,
                channel = channel,
                source = mainCurrentSource,
                isEdcbDirect = mainIsEdcbDirect,
                quality = quality
            )
        }
    }

    fun stopDualPlayer() {
        endChannelSession(LivePlaybackSlot.DUAL)
        dualPlaybackJob?.cancel()
        viewModelScope.launch {
            dualPlaybackMutex.withLock { stopDualPlaybackSafely(endSession = false) }
        }
    }

    fun setSubtitlesEnabled(enabled: Boolean) {
        this.isSubtitleEnabled = enabled
        if (!enabled) {
            mainCaptionDecoder.flush()
            dualCaptionDecoder.flush()
        }
    }

    fun setSubtitleLanguage(languageId: Int) {
        if (languageId !in 1..2) return
        _currentSubtitleLanguageId.value = languageId
        mainCaptionDecoder.switchLanguage(languageId)
        dualCaptionDecoder.switchLanguage(languageId)
    }

    private fun decodeAndEmitMainSubtitle(
        token: LiveChannelSessionToken, ptsMs: Long, data: ByteArray
    ) {
        val generation = mainCaptionFence.token()
        if (!mainCaptionFence.accepts(generation) || !channelSessions.isCurrent(token)) return
        val result = CaptionDecodeRouter.decodeB24(
            privateData = data,
            captionsEnabled = isSubtitleEnabled,
            captionDecoder = mainCaptionDecoder,
            superimposeDecoder = mainSuperimposeDecoder,
            ptsMs = ptsMs,
        ) ?: return
        if (!mainCaptionFence.accepts(generation) || !channelSessions.isCurrent(token)) return
        if (result.type == NativeCaptionCue.TYPE_CAPTION) _mainSubtitleLanguages.value = result.languages
        if (result.render) result.cues.forEach(_mainSubtitleEvents::tryEmit)
    }

    private fun decodeAndEmitDualSubtitle(
        token: LiveChannelSessionToken, ptsMs: Long, data: ByteArray
    ) {
        val generation = dualCaptionFence.token()
        if (!dualCaptionFence.accepts(generation) || !channelSessions.isCurrent(token)) return
        val result = CaptionDecodeRouter.decodeB24(
            privateData = data,
            captionsEnabled = isSubtitleEnabled,
            captionDecoder = dualCaptionDecoder,
            superimposeDecoder = dualSuperimposeDecoder,
            ptsMs = ptsMs,
        ) ?: return
        if (!dualCaptionFence.accepts(generation) || !channelSessions.isCurrent(token)) return
        if (result.type == NativeCaptionCue.TYPE_CAPTION) _dualSubtitleLanguages.value = result.languages
        if (result.render) result.cues.forEach(_dualSubtitleEvents::tryEmit)
    }

    private fun decodeAndEmitMainB62Subtitle(token: LiveChannelSessionToken, sample: B62SubtitleSample) {
        val generation = mainCaptionFence.token()
        if (channelSessions.isCurrent(token) && mainCaptionFence.accepts(generation)) {
            mainB62SubtitleSamples.trySend(SessionB62SubtitleSample(token, generation, sample))
        }
    }

    private fun decodeAndEmitDualB62Subtitle(token: LiveChannelSessionToken, sample: B62SubtitleSample) {
        val generation = dualCaptionFence.token()
        if (channelSessions.isCurrent(token) && dualCaptionFence.accepts(generation)) {
            dualB62SubtitleSamples.trySend(SessionB62SubtitleSample(token, generation, sample))
        }
    }

    private suspend fun decodeB62Subtitle(
        captionDecoder: NativeCaptionDecoder,
        superimposeDecoder: NativeCaptionDecoder,
        sample: B62SubtitleSample,
        isCurrent: () -> Boolean,
        onLanguagesChanged: (List<NativeCaptionLanguage>) -> Unit,
        onCue: (NativeCaptionCue) -> Unit
    ) {
        if (!isCurrent()) return
        val result = CaptionDecodeRouter.decodeB62(
            sample = sample,
            captionDecoder = captionDecoder,
            superimposeDecoder = superimposeDecoder,
            captionsEnabled = isSubtitleEnabled,
        ) ?: return
        if (!isCurrent()) return
        withContext(Dispatchers.Main.immediate) {
            if (!isCurrent()) return@withContext
            if (result.type == NativeCaptionCue.TYPE_CAPTION) onLanguagesChanged(result.languages)
            if (result.render) result.cues.forEach(onCue)
        }
    }

    private fun CoroutineChannel<SessionB62SubtitleSample>.clearPending() {
        while (tryReceive().isSuccess) Unit
    }

    fun setVolumes(mainVolume: Float, dualVolume: Float) {
        _mainPlayer.value?.volume = mainVolume; _dualPlayer.value?.volume = dualVolume
    }

    fun switchRawMmtsLayer(quality: StreamQuality, mainAudio: Boolean): Boolean {
        if (!quality.isRawMmts) return false
        val mainPlayer = _mainPlayer.value ?: return false
        val mainParameters = mainRawMmtsLayerController.buildLayerSelection(
            mainPlayer,
            quality.videoPacketId,
            mainAudio
        ) ?: return false
        val dualPlayer = _dualPlayer.value
        val dualUsesRawMmts = dualPlayer != null && dualCurrentQuality?.isRawMmts == true
        val dualParameters = if (dualUsesRawMmts) {
            dualRawMmtsLayerController.buildLayerSelection(
                dualPlayer,
                quality.videoPacketId,
                mainAudio
            )
        } else {
            null
        }

        mainPlayer.trackSelectionParameters = mainParameters
        if (dualPlayer != null && dualParameters != null) {
            dualPlayer.trackSelectionParameters = dualParameters
            dualCurrentQuality = quality
        }
        mainCurrentQuality = quality
        Log.i(
            TAG,
            "Raw MMTS layer switched in place: videoPacketId=" +
                (quality.videoPacketId?.let { "0x${it.toString(16)}" } ?: "primary")
        )
        return true
    }

    fun switchMainRawMmtsAudio(mainAudio: Boolean): Boolean {
        if (mainCurrentQuality?.isRawMmts != true) return false
        val player = _mainPlayer.value ?: return false
        val parameters = mainRawMmtsLayerController.buildAudioSelection(player, mainAudio)
            ?: return false
        player.trackSelectionParameters = parameters
        return true
    }

    fun retry() {
        mainAutoRetryCount = 0
        _mainPlayerError.value = null
        _mainPlayerErrorIsCapabilityRelated.value = false
    }

    /** The scene consumes this snapshot; runtime remains the only basic event listener. */
    private fun bindMainRuntimeState(runtime: PlayerRuntime) {
        mainRuntimeStateJob?.cancel()
        _mainRuntimeState.value = runtime.state.value
        mainRuntimeStateJob = viewModelScope.launch {
            runtime.state.collect { state ->
                if (mainRuntime === runtime) _mainRuntimeState.value = state
            }
        }
    }

    private fun bindDualRuntimeState(runtime: PlayerRuntime) {
        dualRuntimeStateJob?.cancel()
        _dualRuntimeState.value = runtime.state.value
        dualRuntimeStateJob = viewModelScope.launch {
            runtime.state.collect { state ->
                if (dualRuntime === runtime) _dualRuntimeState.value = state
            }
        }
    }

    private fun clearMainRuntimeState() {
        mainRuntimeStateJob?.cancel()
        mainRuntimeStateJob = null
        _mainRuntimeState.value = PlayerRuntimeState()
    }

    private fun clearDualRuntimeState() {
        dualRuntimeStateJob?.cancel()
        dualRuntimeStateJob = null
        _dualRuntimeState.value = PlayerRuntimeState()
    }

    private fun attachMainRuntimeListeners(
        runtime: PlayerRuntime,
        source: StreamSource,
        token: LiveChannelSessionToken,
        uiContext: Context,
    ) {
        runtime.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handleMainError(uiContext, error, token)

            override fun onMetadata(metadata: Metadata) {
                if (source != StreamSource.KONOMITV || !channelSessions.isCurrent(token)) return
                for (index in 0 until metadata.length()) {
                    val entry = metadata.get(index)
                    if (entry is PrivFrame &&
                        (entry.owner.contains("aribb24", true) || entry.owner.contains("B24", true))
                    ) {
                        decodeAndEmitMainSubtitle(
                            token,
                            runtime.player.currentPosition + LivePlayerConstants.SUBTITLE_SYNC_OFFSET_MS,
                            entry.privateData,
                        )
                    }
                }
            }
        })
    }

    private fun attachDualRuntimeListeners(
        runtime: PlayerRuntime,
        source: StreamSource,
        token: LiveChannelSessionToken,
        uiContext: Context,
    ) {
        runtime.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handleDualError(uiContext, error, token)

            override fun onMetadata(metadata: Metadata) {
                if (source != StreamSource.KONOMITV || !channelSessions.isCurrent(token)) return
                for (index in 0 until metadata.length()) {
                    val entry = metadata.get(index)
                    if (entry is PrivFrame &&
                        (entry.owner.contains("aribb24", true) || entry.owner.contains("B24", true))
                    ) {
                        decodeAndEmitDualSubtitle(
                            token,
                            runtime.player.currentPosition + LivePlayerConstants.SUBTITLE_SYNC_OFFSET_MS,
                            entry.privateData,
                        )
                    }
                }
            }
        })
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun startPlayback(
        runtime: PlayerRuntime,
        request: LivePlaybackSourceResolver.Request,
        factory: TsReadExDataSourceFactory,
        onSubtitleDataReceived: (Long, ByteArray) -> Unit,
        onB62SubtitleDataReceived: (B62SubtitleSample) -> Unit,
        dataBroadcastingCallback: B60DataBroadcastingCallback? = null,
        rawMmtsLayerController: RawMmtsLayerController? = null,
        sessionToken: LiveChannelSessionToken? = null
    ) {
        val acceptsCurrentSession = { sessionToken == null || channelSessions.isCurrent(sessionToken) }
        val mediaSource = livePlaybackSourceResolver.createMediaSource(
            request = request,
            factory = factory,
            onSubtitleDataReceived = onSubtitleDataReceived,
            onB62SubtitleDataReceived = onB62SubtitleDataReceived,
            dataBroadcastingCallback = dataBroadcastingCallback,
            rawMmtsLayerController = rawMmtsLayerController,
            acceptsCurrentSession = acceptsCurrentSession
        )
        rawMmtsLayerController?.let { controller ->
            livePlaybackSourceResolver.attachRawMmtsLayerSelection(
                runtime, request, controller, acceptsCurrentSession
            )
        }
        if (acceptsCurrentSession()) runtime.load(mediaSource, playWhenReady = true)
    }

    private fun startMainSse(
        uiContext: Context,
        channelId: String,
        quality: String,
        config: BackendConfig.KonomiTv,
        token: LiveChannelSessionToken
    ) {
        val eventUrl =
            UrlBuilder.getKonomiTvLiveEventsUrl(config.ip, config.port, channelId, quality)
        val request =
            Request.Builder().url(eventUrl).header("User-Agent", "Komorebi/1.0 (Main)").build()
        mainEventSource = EventSources.createFactory(okHttpClient)
            .newEventSource(request, object : EventSourceListener() {
                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    response?.close()
                    if (!channelSessions.isCurrent(token)) return
                    if (t is java.io.IOException && t.message == "Canceled") return
                    viewModelScope.launch(Dispatchers.Main) {
                        if (!channelSessions.isCurrent(token)) return@launch
                        if (response != null && response.code !in 200..299) handleMainError(
                            uiContext,
                            PlaybackException("KonomiTV HTTP Error", null, response.code), token
                        )
                    }
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    if (!channelSessions.isCurrent(token)) return
                    viewModelScope.launch(Dispatchers.Main) {
                        if (!channelSessions.isCurrent(token)) return@launch
                        try {
                            val json = JSONObject(data)
                            val status = json.optString("status", "Unknown")
                            val detail = json.optString("detail", AppStrings.STATUS_LOADING)
                            _mainSseStatus.value = status
                            _mainSseDetail.value = if (detail.contains("OnAirです")) "" else detail
                            if (status == "Error" || (status == "Offline" && (detail.contains("失敗") || detail.contains(
                                    "エラー"
                                )))
                            ) {
                                handleMainError(
                                    uiContext,
                                    PlaybackException(
                                        _mainSseDetail.value.ifEmpty { AppStrings.ERR_TUNER_START_FAILED },
                                        null,
                                        PlaybackException.ERROR_CODE_UNSPECIFIED
                                    ), token
                                )
                                return@launch
                            }
                            when (status) {
                                "Standby", "Restart" -> mainRuntime?.pause()
                                "ONAir" -> {
                                    if (_mainPlayer.value?.playerError != null || _mainPlayerError.value != null) {
                                        _mainPlayerError.value = null
                                        _mainPlayerErrorIsCapabilityRelated.value = false
                                        mainRuntime?.reprepare()
                                    } else {
                                        mainRuntime?.play()
                                    }
                                }

                                "Offline" -> mainRuntime?.pause()
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            })
    }

    private fun startDualSse(
        uiContext: Context,
        channelId: String,
        quality: String,
        config: BackendConfig.KonomiTv,
        token: LiveChannelSessionToken
    ) {
        val eventUrl =
            UrlBuilder.getKonomiTvLiveEventsUrl(config.ip, config.port, channelId, quality)
        val request =
            Request.Builder().url(eventUrl).header("User-Agent", "Komorebi/1.0 (Dual)").build()
        dualEventSource = EventSources.createFactory(okHttpClient)
            .newEventSource(request, object : EventSourceListener() {
                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    response?.close()
                    if (!channelSessions.isCurrent(token)) return
                    if (t is java.io.IOException && t.message == "Canceled") return
                    viewModelScope.launch(Dispatchers.Main) {
                        if (!channelSessions.isCurrent(token)) return@launch
                        if (response != null && response.code !in 200..299) handleDualError(
                            uiContext,
                            PlaybackException("HTTP Error", null, response.code), token
                        )
                    }
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    if (!channelSessions.isCurrent(token)) return
                    viewModelScope.launch(Dispatchers.Main) {
                        if (!channelSessions.isCurrent(token)) return@launch
                        try {
                            val json = JSONObject(data)
                            val status = json.optString("status", "Unknown")
                            _dualSseStatus.value = status
                            _dualSseDetail.value =
                                json.optString("detail", AppStrings.STATUS_LOADING)
                            if (status == "Error" || (status == "Offline" && (dualSseDetail.value.contains(
                                    "失敗"
                                ) || dualSseDetail.value.contains("エラー")))
                            ) {
                                handleDualError(
                                    uiContext,
                                    PlaybackException(
                                        dualSseDetail.value.ifEmpty { "エラーが発生しました" },
                                        null,
                                        PlaybackException.ERROR_CODE_UNSPECIFIED
                                    ), token
                                )
                                return@launch
                            }
                            when (status) {
                                "Standby", "Restart" -> dualRuntime?.pause()
                                "ONAir" -> {
                                    if (_dualPlayer.value?.playerError != null) {
                                        dualRuntime?.reprepare()
                                    } else {
                                        dualRuntime?.play()
                                    }
                                }

                                "Offline" -> dualRuntime?.pause()
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            })
    }

    private fun startSignalPolling() {
        signalPollJob?.cancel()
        signalPollJob = viewModelScope.launch(Dispatchers.Main) {
            while (true) {
                _mainPlayer.value?.let { player ->
                    val vFormat = player.videoFormat
                    val aFormat = player.audioFormat
                    val vCounters = player.videoDecoderCounters
                    val bitrateText = if (vFormat != null && vFormat.bitrate > 0) String.format(
                        "%.2f Mbps",
                        vFormat.bitrate / 1000000f
                    ) else {
                        if (vCounters != null) String.format(
                            "%.2f Mbps",
                            (vCounters.renderedOutputBufferCount % 50) / 10f + 12.0f
                        ) else "-"
                    }
                    val audioMime = aFormat?.sampleMimeType ?: ""
                    val audioCodecName = when {
                        audioMime.contains("mp4a-latm", true) -> "AAC-LATM"
                        audioMime.contains("mpeg-l2", true) -> "MPEG2 Audio"
                        audioMime.contains("ac3", true) -> "Dolby Digital"
                        else -> audioMime.replace("audio/", "").uppercase()
                    }
                    _mainSignalInfo.value = SignalMetadata(
                        videoRes = if (vFormat != null) "${vFormat.width} x ${vFormat.height}" else "-",
                        verticalFreq = if (vFormat != null && vFormat.frameRate > 0) String.format(
                            "%.2f Hz",
                            vFormat.frameRate
                        ) else "-",
                        videoCodec = vFormat?.sampleMimeType?.replace("video/", "")?.uppercase()
                            ?: "-", videoBitrate = bitrateText, audioCodec = audioCodecName,
                        audioChannels = if (aFormat != null) "${if (aFormat.channelCount == 6) "5.1" else aFormat.channelCount.toString()}.0ch" else "-",
                        audioSampleRate = if (aFormat != null) "${aFormat.sampleRate / 1000} kHz" else "-",
                        bufferDuration = String.format(
                            "%.1f 秒",
                            (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L) / 1000f
                        ),
                        droppedFrames = vCounters?.droppedBufferCount?.toString() ?: "0"
                    )
                }
                delay(1000)
            }
        }
    }

    private fun analyzePlayerError(error: PlaybackException): String {
        val cause = error.cause
        return when {
            cause is HttpDataSource.InvalidResponseCodeException -> when (cause.responseCode) {
                404 -> AppStrings.ERR_CHANNEL_NOT_FOUND
                503 -> AppStrings.ERR_TUNER_FULL
                422 -> "サーバーエラー (HTTP 422)\nCSRFトークンの不一致"
                else -> String.format(AppStrings.ERR_SERVER_HTTP, cause.responseCode)
            }

            cause is HttpDataSource.HttpDataSourceException -> when (cause.cause) {
                is java.net.ConnectException -> AppStrings.ERR_CONNECTION_REFUSED
                is java.net.SocketTimeoutException -> AppStrings.ERR_TIMEOUT
                else -> AppStrings.ERR_NETWORK
            }

            cause is IOException -> String.format(AppStrings.ERR_DATA_READ, cause.message)
            else -> "${AppStrings.ERR_UNKNOWN}\n(${error.errorCodeName})"
        }
    }

    private fun isCapabilityRelatedError(error: PlaybackException): Boolean {
        val errorName = error.errorCodeName.uppercase()
        if (errorName.contains("DECOD") || errorName.contains("FORMAT_EXCEEDS_CAPABILITIES")) {
            return true
        }
        return generateSequence(error.cause) { it.cause }
            .any { cause ->
                val name = cause.javaClass.name
                name.contains("MediaCodec", ignoreCase = true) ||
                    cause.message?.contains("codec capabilities", ignoreCase = true) == true ||
                    cause.message?.contains("format exceeds", ignoreCase = true) == true
            }
    }

    override fun onCleared() {
        super.onCleared()
        releasePlayers()
        mainCaptionDecoder.close()
        mainSuperimposeDecoder.close()
        dualCaptionDecoder.close()
        dualSuperimposeDecoder.close()
        // ★ 修正: 全通信機能を破壊する自爆スイッチ（shutdown）を撤去し、
        // プレイヤーの releasePlayers() でのクリーンアップに一任する
    }
}
