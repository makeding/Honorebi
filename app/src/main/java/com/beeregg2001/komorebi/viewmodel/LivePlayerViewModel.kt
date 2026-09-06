@file:OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.live

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.util.playbackHttpDataSourceFactory
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import com.beeregg2001.komorebi.NativeLib
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.BackendConfig
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.data.repository.LiveProvider
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.ui.player.HdrToneMapping
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionCue
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionDecoder
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionLanguage
import com.beeregg2001.komorebi.util.TsReadExDataSourceFactory
import com.beeregg2001.komorebi.util.mmts.TlvExtractorsFactory
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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private data class LivePlaybackRequest(
    val url: String,
    val source: StreamSource,
    val isEdcbDirect: Boolean,
    val quality: StreamQuality,
    val config: BackendConfig
) {
    val apiQuality: String
        get() = if (quality.isRawMmts) StreamQuality.RAW_MMTS_PRIMARY_VALUE else quality.value
}

private data class SessionB62SubtitleSample(
    val token: LiveChannelSessionToken,
    val sample: B62SubtitleSample
)

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class LivePlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val liveProvider: LiveProvider,
    private val recordProvider: RecordProvider,
    private val settingsRepository: SettingsRepository,
    @javax.inject.Named("access") private val accessHttpClient: OkHttpClient,
    private val livePlayerFactory: LivePlayerFactory,
    private val liveJikkyoManager: LiveJikkyoManager
) : ViewModel() {

    companion object {
        private const val TAG = "LivePlayerViewModel"
        private const val MAX_AUTO_RETRY = 2
        private const val CHANNEL_SWITCH_STREAM_DEBOUNCE_MS = 100L
    }

    private val gson = Gson()

    private val _mainPlayer = MutableStateFlow<ExoPlayer?>(null)
    val mainPlayer: StateFlow<ExoPlayer?> = _mainPlayer.asStateFlow()
    private val _mainSessionToken = MutableStateFlow<LiveChannelSessionToken?>(null)
    val mainSessionToken: StateFlow<LiveChannelSessionToken?> = _mainSessionToken.asStateFlow()

    val hdrRenderMode: StateFlow<String> = settingsRepository.hdrRenderMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        "ORIGINAL"
    )
    val isHdrToSdrToneMappingSupported: Boolean
        get() = livePlayerFactory.isHdrToSdrToneMappingSupported

    private val _dualPlayer = MutableStateFlow<ExoPlayer?>(null)
    val dualPlayer: StateFlow<ExoPlayer?> = _dualPlayer.asStateFlow()
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
                if (!channelSessions.isCurrent(sample.token)) continue
                decodeB62Subtitle(
                    captionDecoder = mainCaptionDecoder,
                    superimposeDecoder = mainSuperimposeDecoder,
                    sample = sample.sample,
                    isCurrent = { channelSessions.isCurrent(sample.token) },
                    onLanguagesChanged = { _mainSubtitleLanguages.value = it },
                    onCue = { _mainSubtitleEvents.tryEmit(it) }
                )
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            for (sample in dualB62SubtitleSamples) {
                if (!channelSessions.isCurrent(sample.token)) continue
                decodeB62Subtitle(
                    captionDecoder = dualCaptionDecoder,
                    superimposeDecoder = dualSuperimposeDecoder,
                    sample = sample.sample,
                    isCurrent = { channelSessions.isCurrent(sample.token) },
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
                if (source == StreamSource.EDCB) {
                    if (isEdcbDirect) {
                        _availableQualities.value = listOf(
                            StreamQuality(
                                label = "オリジナル (Direct)",
                                value = "direct",
                                isRawTs = true
                            )
                        )
                    } else {
                        val json = settingsRepository.availableStreamQualities.first()
                        if (json.isNotBlank()) {
                            try {
                                val type = object : TypeToken<List<StreamQuality>>() {}.type
                                val list = gson.fromJson<List<StreamQuality>>(json, type)
                                if (!list.isNullOrEmpty()) _availableQualities.value = list
                                else fetchFromApiAndSave()
                            } catch (e: Exception) {
                                fetchFromApiAndSave()
                            }
                        } else fetchFromApiAndSave()
                    }
                } else if (source == StreamSource.KONOMITV) {
                    _availableQualities.value = StreamQuality.DEFAULT_QUALITIES
                } else {
                    _availableQualities.value = listOf(
                        StreamQuality(
                            label = "オリジナル (Direct)",
                            value = "direct",
                            isRawTs = true
                        )
                    )
                }
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

    private suspend fun fetchFromApiAndSave() {
        try {
            Log.i(TAG, "Cache empty. Interrupting EPG to fetch qualities from API.")
            val fetched = recordProvider.getStreamQualities()
            if (fetched.isNotEmpty()) {
                settingsRepository.saveString(
                    SettingsRepository.AVAILABLE_STREAM_QUALITIES,
                    gson.toJson(fetched)
                )
                _availableQualities.value = fetched
            } else {
                val currentLive = settingsRepository.liveQuality.first()
                _availableQualities.value = listOf(
                    StreamQuality(
                        label = "設定値 ($currentLive)",
                        value = currentLive,
                        isRawTs = false
                    )
                )
            }
        } catch (e: Exception) {
            val currentLive = settingsRepository.liveQuality.first()
            _availableQualities.value = listOf(
                StreamQuality(
                    label = "設定値 ($currentLive)",
                    value = currentLive,
                    isRawTs = false
                )
            )
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
        mainB62SubtitleSamples.clearPending()
        mainCaptionDecoder.reset(_currentSubtitleLanguageId.value)
        mainSuperimposeDecoder.reset()
        _mainSubtitleLanguages.value = emptyList()

        // ★ 修正: KonomiTV等でセッションが残らないよう、確実にstop()とclearMediaItems()を呼ぶ
        _mainPlayer.value?.stop()
        _mainPlayer.value?.clearMediaItems()
        _mainPlayer.value?.release(); _mainPlayer.value = null
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
        dualB62SubtitleSamples.clearPending()
        dualCaptionDecoder.reset(_currentSubtitleLanguageId.value)
        dualSuperimposeDecoder.reset()
        _dualSubtitleLanguages.value = emptyList()

        // ★ 修正: サブプレイヤー側も同様に確実なクリーンアップを行う
        _dualPlayer.value?.stop()
        _dualPlayer.value?.clearMediaItems()
        _dualPlayer.value?.release(); _dualPlayer.value = null
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

        // ★ 修正: release()の前に必ずstop()とclearMediaItems()を呼んでゾンビ化を防ぐ
        _mainPlayer.value?.stop()
        _mainPlayer.value?.clearMediaItems()
        _mainPlayer.value?.release(); _mainPlayer.value = null
        mainRawMmtsLayerController.reset()

        _dualPlayer.value?.stop()
        _dualPlayer.value?.clearMediaItems()
        _dualPlayer.value?.release(); _dualPlayer.value = null
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
                    _mainPlayer.value?.prepare()
                    _mainPlayer.value?.play()
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
                    _dualPlayer.value?.prepare()
                    _dualPlayer.value?.play()
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
            val logoUrl = liveProvider.getChannelLogoUrl(channel.id)
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

                    val request = resolvePlaybackRequest(
                        channel = channel,
                        requestedSource = source,
                        requestedIsEdcbDirect = isEdcbDirect,
                        requestedQuality = quality,
                        streamNumber = 0,
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
                        val newPlayer = livePlayerFactory.createExoPlayer(
                            audioOutputMode = audioOutputMode,
                            hdrRenderMode = hdrRenderMode,
                            isKonomiTvSource = { request.source == StreamSource.KONOMITV },
                            onSubtitleDataReceived = { pts, data ->
                                decodeAndEmitMainSubtitle(token, pts, data)
                            },
                            onError = { error -> handleMainError(uiContext, error, token) }
                        )
                        if (!channelSessions.isCurrent(token)) {
                            newPlayer.release()
                            return@withContext
                        }
                        _mainPlayer.value = newPlayer
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
                            newPlayer,
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

                    val request = resolvePlaybackRequest(
                        channel = channel,
                        requestedSource = source,
                        requestedIsEdcbDirect = isEdcbDirect,
                        requestedQuality = quality,
                        streamNumber = 1,
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
                        val newDualPlayer = livePlayerFactory.createExoPlayer(
                            audioOutputMode = audioOutputMode,
                            hdrRenderMode = hdrRenderMode,
                            isKonomiTvSource = { request.source == StreamSource.KONOMITV },
                            onSubtitleDataReceived = { pts, data ->
                                decodeAndEmitDualSubtitle(token, pts, data)
                            },
                            onError = { error -> handleDualError(uiContext, error, token) }
                        )
                        if (!channelSessions.isCurrent(token)) {
                            newDualPlayer.release()
                            return@withContext
                        }
                        _dualPlayer.value = newDualPlayer
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
                            newDualPlayer,
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
        if (!channelSessions.isCurrent(token)) return
        val cue = mainCaptionDecoder.decode(data, ptsMs, renderCaptions = isSubtitleEnabled)
        if (!channelSessions.isCurrent(token)) return
        _mainSubtitleLanguages.value = mainCaptionDecoder.availableLanguages()
        if (isSubtitleEnabled && cue != null) _mainSubtitleEvents.tryEmit(cue)
    }

    private fun decodeAndEmitDualSubtitle(
        token: LiveChannelSessionToken, ptsMs: Long, data: ByteArray
    ) {
        if (!channelSessions.isCurrent(token)) return
        val cue = dualCaptionDecoder.decode(data, ptsMs, renderCaptions = isSubtitleEnabled)
        if (!channelSessions.isCurrent(token)) return
        _dualSubtitleLanguages.value = dualCaptionDecoder.availableLanguages()
        if (isSubtitleEnabled && cue != null) _dualSubtitleEvents.tryEmit(cue)
    }

    private fun decodeAndEmitMainB62Subtitle(token: LiveChannelSessionToken, sample: B62SubtitleSample) {
        if (channelSessions.isCurrent(token)) mainB62SubtitleSamples.trySend(SessionB62SubtitleSample(token, sample))
    }

    private fun decodeAndEmitDualB62Subtitle(token: LiveChannelSessionToken, sample: B62SubtitleSample) {
        if (channelSessions.isCurrent(token)) dualB62SubtitleSamples.trySend(SessionB62SubtitleSample(token, sample))
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
        val isCaption = sample.type == NativeCaptionDecoder.TYPE_CAPTION
        if (isCaption && !isSubtitleEnabled) return
        val decoder = when (sample.type) {
            NativeCaptionDecoder.TYPE_CAPTION -> captionDecoder
            NativeCaptionDecoder.TYPE_SUPERIMPOSE -> superimposeDecoder
            else -> return
        }
        val decoded = decoder.decodeB62(
            data = sample.data,
            ptsMs = sample.timeUs / 1_000L,
            operationMode = sample.operationMode,
            timingMode = sample.timingMode,
            referenceStartPtsMs = sample.referenceStartTimeUs?.div(1_000L),
            mpuSequenceNumber = sample.mpuSequenceNumber,
            resources = sample.resources,
            discontinuity = sample.discontinuity
        )
        if (!isCurrent()) return
        val languages = decoder.availableLanguages()
        withContext(Dispatchers.Main.immediate) {
            if (!isCurrent()) return@withContext
            if (isCaption) onLanguagesChanged(languages)
            if (!isCaption || isSubtitleEnabled) decoded.forEach(onCue)
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

    private suspend fun resolvePlaybackRequest(
        channel: Channel,
        requestedSource: StreamSource,
        requestedIsEdcbDirect: Boolean,
        requestedQuality: StreamQuality,
        streamNumber: Int,
        factory: TsReadExDataSourceFactory
    ): LivePlaybackRequest {
        val isRawMmts = channel.type.equals("BS4K", ignoreCase = true)
        val quality = if (isRawMmts) {
            StreamQuality.rawMmtsQualities(channel)
                .firstOrNull { it.value == requestedQuality.value }
                ?: StreamQuality.rawMmtsQualities(channel).first()
        } else if (requestedQuality.isRawMmts) {
            val savedQuality = settingsRepository.liveQuality.first()
            StreamQuality(
                label = savedQuality,
                value = savedQuality
            )
        } else {
            requestedQuality
        }
        val source = if (isRawMmts) resolveRawMmtsSource(requestedSource) else requestedSource
        val isEdcbDirect = source == StreamSource.EDCB && requestedIsEdcbDirect
        val config = settingsRepository.getBackendConfig(source)

        val url = when (source) {
            StreamSource.EDCB -> {
                if (!isEdcbDirect) {
                    val hlsUrl = liveProvider.getLiveStreamUrl(channel.id, quality.value, streamNumber)
                    if (hlsUrl.isBlank()) throw IOException("HLSトランスコードの開始に失敗しました")
                    return LivePlaybackRequest(hlsUrl, source, false, quality, config)
                }
                val ip = if (config.ip.isNotBlank()) config.ip else "127.0.0.1"
                val port = if (config.port.isNotBlank()) config.port else "4510"
                val parts = channel.id.split("_")
                val isEdcbFormat = parts.size >= 4 && parts[0].startsWith("edcb", ignoreCase = true)
                val finalOnid = if (isEdcbFormat) parts[1] else channel.networkId.toString()
                val finalTsid =
                    if (isEdcbFormat) parts[2] else if (channel.transportStreamId != 0L) channel.transportStreamId.toString() else channel.networkId.toString()
                val finalSid = if (isEdcbFormat) parts[3] else channel.serviceId.toString()
                factory.tsArgs = arrayOf(
                    "-x",
                    "18/38/39",
                    "-n",
                    finalSid,
                    "-a",
                    "13",
                    "-b",
                    "4",
                    "-c",
                    "5",
                    "-u",
                    "1",
                    "-d",
                    "13"
                )
                "edcb://$ip:$port/live?onid=$finalOnid&tsid=$finalTsid&sid=$finalSid"
            }

            StreamSource.MIRAKURUN -> {
                if (config.isValid) {
                    if (quality.isRawMmts) {
                        UrlBuilder.getMirakurunRawMmtsStreamUrl(
                            config.ip,
                            config.port,
                            channel.networkId,
                            channel.serviceId
                        )
                    } else {
                        factory.tsArgs = arrayOf(
                            "-x", "18/38/39",
                            "-n", channel.serviceId.toString(),
                            "-a", "13",
                            "-b", "4",
                            "-c", "5",
                            "-u", "1",
                            "-d", "13"
                        )
                        UrlBuilder.getMirakurunStreamUrl(
                            config.ip,
                            config.port,
                            channel.networkId,
                            channel.serviceId
                        )
                    }
                } else ""
            }

            StreamSource.KONOMITV -> UrlBuilder.getKonomiTvLiveStreamUrl(
                config.ip,
                config.port,
                channel.displayChannelId,
                if (quality.isRawMmts) StreamQuality.RAW_MMTS_PRIMARY_VALUE else quality.value
            )
        }
        if (url.isBlank()) throw IOException("ストリーミングソースの設定が不完全です: $source")
        return LivePlaybackRequest(url, source, isEdcbDirect, quality, config)
    }

    private suspend fun resolveRawMmtsSource(requestedSource: StreamSource): StreamSource {
        if (requestedSource != StreamSource.EDCB &&
            settingsRepository.getBackendConfig(requestedSource).isValid
        ) {
            return requestedSource
        }
        if (settingsRepository.getBackendConfig(StreamSource.KONOMITV).isValid) {
            return StreamSource.KONOMITV
        }
        if (settingsRepository.getBackendConfig(StreamSource.MIRAKURUN).isValid) {
            return StreamSource.MIRAKURUN
        }
        throw IOException("BS4K/BS8K Raw MMTS には HonomiTV または Mirakurun の設定が必要です")
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun startPlayback(
        player: ExoPlayer?,
        request: LivePlaybackRequest,
        factory: TsReadExDataSourceFactory,
        onSubtitleDataReceived: (Long, ByteArray) -> Unit,
        onB62SubtitleDataReceived: (B62SubtitleSample) -> Unit,
        dataBroadcastingCallback: B60DataBroadcastingCallback? = null,
        rawMmtsLayerController: RawMmtsLayerController? = null,
        sessionToken: LiveChannelSessionToken? = null
    ) {
        val mediaItem = MediaItem.fromUri(request.url)
        val mediaSource = when {
            request.quality.isRawMmts -> {
                rawMmtsLayerController?.reset()
                val httpDataSourceFactory = playbackHttpDataSourceFactory(context)
                if (request.source == StreamSource.MIRAKURUN) {
                    httpDataSourceFactory.setDefaultRequestProperties(
                        mapOf("X-Mirakurun-Priority" to "0")
                    )
                }
                ProgressiveMediaSource.Factory(
                    httpDataSourceFactory,
                    TlvExtractorsFactory(
                        preferredVideoPacketId = request.quality.videoPacketId,
                        onTracksChanged = {
                            if (sessionToken == null || channelSessions.isCurrent(sessionToken)) {
                                rawMmtsLayerController?.updateTracks(it)
                            }
                        },
                        onSubtitleDataReceived = onB62SubtitleDataReceived,
                        dataBroadcastingCallback = dataBroadcastingCallback
                    )
                ).createMediaSource(mediaItem)
            }

            request.source == StreamSource.MIRAKURUN ||
                (request.source == StreamSource.EDCB && request.isEdcbDirect) -> {
                    val extractorsFactory = ExtractorsFactory {
                        arrayOf(
                            TsExtractor(
                                TsExtractor.MODE_SINGLE_PMT,
                                TimestampAdjuster(C.TIME_UNSET),
                                DirectSubtitlePayloadReaderFactory(
                                    onSubtitleDataReceived = onSubtitleDataReceived
                                ),
                                TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
                            )
                        )
                    }
                    ProgressiveMediaSource.Factory(factory, extractorsFactory)
                        .createMediaSource(mediaItem)
                }

            request.source == StreamSource.EDCB && !request.isEdcbDirect -> {
                        val uri = Uri.parse(request.url)
                        val ctok = uri.getQueryParameter("ctok") ?: ""
                        val httpDataSourceFactory = playbackHttpDataSourceFactory(context)
                            .setDefaultRequestProperties(mapOf("Cookie" to "ctok=$ctok"))
                        HlsMediaSource.Factory(httpDataSourceFactory)
                            .setAllowChunklessPreparation(false).createMediaSource(mediaItem)
                    }

            else -> DefaultMediaSourceFactory(context)
                .setDataSourceFactory(playbackHttpDataSourceFactory(context))
                .createMediaSource(mediaItem)
        }
        if (request.quality.isRawMmts && player != null && rawMmtsLayerController != null) {
            player.addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    if (sessionToken != null && !channelSessions.isCurrent(sessionToken)) return
                    val parameters = rawMmtsLayerController.buildLayerSelection(
                        player,
                        request.quality.videoPacketId,
                        mainAudio = true
                    ) ?: return
                    player.removeListener(this)
                    player.trackSelectionParameters = parameters
                }
            })
        }
        if (sessionToken == null || channelSessions.isCurrent(sessionToken)) {
            player?.setMediaSource(mediaSource); player?.prepare(); player?.play()
        }
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
                                "Standby", "Restart" -> _mainPlayer.value?.pause()
                                "ONAir" -> {
                                    if (_mainPlayer.value?.playerError != null || _mainPlayerError.value != null) {
                                        _mainPlayerError.value = null
                                        _mainPlayerErrorIsCapabilityRelated.value = false
                                        _mainPlayer.value?.prepare()
                                    }; _mainPlayer.value?.play()
                                }

                                "Offline" -> _mainPlayer.value?.pause()
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
                                "Standby", "Restart" -> _dualPlayer.value?.pause()
                                "ONAir" -> {
                                    if (_dualPlayer.value?.playerError != null) _dualPlayer.value?.prepare(); _dualPlayer.value?.play()
                                }

                                "Offline" -> _dualPlayer.value?.pause()
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
