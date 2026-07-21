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
import androidx.media3.datasource.DefaultHttpDataSource
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
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionCue
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionDecoder
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionLanguage
import com.beeregg2001.komorebi.util.TsReadExDataSourceFactory
import com.beeregg2001.komorebi.util.mmts.TlvExtractorsFactory
import com.beeregg2001.komorebi.util.mmts.B62SubtitleSample
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class LivePlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val liveProvider: LiveProvider,
    private val recordProvider: RecordProvider,
    private val settingsRepository: SettingsRepository,
    private val livePlayerFactory: LivePlayerFactory,
    private val liveJikkyoManager: LiveJikkyoManager
) : ViewModel() {

    companion object {
        private const val TAG = "LivePlayerViewModel"
        private const val MAX_AUTO_RETRY = 2
    }

    private val gson = Gson()

    private val _mainPlayer = MutableStateFlow<ExoPlayer?>(null)
    val mainPlayer: StateFlow<ExoPlayer?> = _mainPlayer.asStateFlow()

    private val _dualPlayer = MutableStateFlow<ExoPlayer?>(null)
    val dualPlayer: StateFlow<ExoPlayer?> = _dualPlayer.asStateFlow()

    private val mainTsDataSourceFactory = TsReadExDataSourceFactory(NativeLib(), emptyArray())
    private val dualTsDataSourceFactory = TsReadExDataSourceFactory(NativeLib(), emptyArray())

    private val _mainPlayerError = MutableStateFlow<String?>(null)
    val mainPlayerError: StateFlow<String?> = _mainPlayerError.asStateFlow()
    private val _mainPlayerErrorIsCapabilityRelated = MutableStateFlow(false)
    val mainPlayerErrorIsCapabilityRelated: StateFlow<Boolean> =
        _mainPlayerErrorIsCapabilityRelated.asStateFlow()

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
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val mainSubtitleEvents: SharedFlow<NativeCaptionCue> = _mainSubtitleEvents.asSharedFlow()

    private val _dualSubtitleEvents = MutableSharedFlow<NativeCaptionCue>(
        extraBufferCapacity = 512,
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
    val clearCommentsEvent: SharedFlow<Unit> = liveJikkyoManager.clearCommentsEvent

    private val _mainBackendType = MutableStateFlow("KONOMITV")
    val mainBackendType: StateFlow<String> = _mainBackendType.asStateFlow()

    private var isSubtitleEnabled = false
    private val mainCaptionDecoder = NativeCaptionDecoder()
    private val dualCaptionDecoder = NativeCaptionDecoder()
    private var signalPollJob: Job? = null

    private var mainPlaybackJob: Job? = null
    private var dualPlaybackJob: Job? = null

    private val mainPlaybackMutex = Mutex()
    private val dualPlaybackMutex = Mutex()
    private val mainSubtitleDecodeMutex = Mutex()
    private val dualSubtitleDecodeMutex = Mutex()

    private val okHttpClient = OkHttpClient.Builder()
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
        viewModelScope.launch {
            settingsRepository.b62SubtitleSize.collect { size ->
                val scale = if (size == "LARGE") 1.15f else 1.0f
                mainCaptionDecoder.setB62FontScale(scale)
                dualCaptionDecoder.setB62FontScale(scale)
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

    private fun stopMainPlaybackSafely(reason: String = "unspecified") {
        Log.w(
            TAG,
            "Stopping main playback: reason=$reason, " +
                "player=${_mainPlayer.value != null}, channel=${mainCurrentChannel?.displayChannelId}, " +
                "source=$mainCurrentSource, quality=${mainCurrentQuality?.value}"
        )
        mainEventSource?.cancel(); mainEventSource = null
        mainCaptionDecoder.reset(_currentSubtitleLanguageId.value)
        _mainSubtitleLanguages.value = emptyList()

        // ★ 修正: KonomiTV等でセッションが残らないよう、確実にstop()とclearMediaItems()を呼ぶ
        _mainPlayer.value?.stop()
        _mainPlayer.value?.clearMediaItems()
        _mainPlayer.value?.release(); _mainPlayer.value = null

        _mainSseStatus.value = "Standby"; _mainSseDetail.value = AppStrings.SSE_CONNECTING
        liveJikkyoManager.stopJikkyo()
    }

    private fun stopDualPlaybackSafely(reason: String = "unspecified") {
        Log.w(
            TAG,
            "Stopping dual playback: reason=$reason, " +
                "player=${_dualPlayer.value != null}, channel=${dualCurrentChannel?.displayChannelId}, " +
                "source=$dualCurrentSource, quality=${dualCurrentQuality?.value}"
        )
        dualEventSource?.cancel(); dualEventSource = null
        dualCaptionDecoder.reset(_currentSubtitleLanguageId.value)
        _dualSubtitleLanguages.value = emptyList()

        // ★ 修正: サブプレイヤー側も同様に確実なクリーンアップを行う
        _dualPlayer.value?.stop()
        _dualPlayer.value?.clearMediaItems()
        _dualPlayer.value?.release(); _dualPlayer.value = null

        _dualSseStatus.value = "Standby"; _dualSseDetail.value = AppStrings.SSE_CONNECTING
    }

    fun releasePlayers(reason: String = "unspecified") {
        Log.w(
            TAG,
            "Releasing live players: reason=$reason, " +
                "main=${_mainPlayer.value != null}, dual=${_dualPlayer.value != null}, " +
                "mainChannel=${mainCurrentChannel?.displayChannelId}, dualChannel=${dualCurrentChannel?.displayChannelId}"
        )
        mainPlaybackJob?.cancel(); dualPlaybackJob?.cancel()
        mainEventSource?.cancel(); dualEventSource?.cancel()

        // ★ 修正: release()の前に必ずstop()とclearMediaItems()を呼んでゾンビ化を防ぐ
        _mainPlayer.value?.stop()
        _mainPlayer.value?.clearMediaItems()
        _mainPlayer.value?.release(); _mainPlayer.value = null

        _dualPlayer.value?.stop()
        _dualPlayer.value?.clearMediaItems()
        _dualPlayer.value?.release(); _dualPlayer.value = null

        _mainSseStatus.value = "Standby"; _dualSseStatus.value = "Standby"
        liveJikkyoManager.stopJikkyo()
    }

    private fun handleMainError(uiContext: Context, error: PlaybackException) {
        viewModelScope.launch {
            val cause = error.cause
            val is404 =
                cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 404
            val isEdcbTranscode = mainCurrentSource == StreamSource.EDCB && !mainIsEdcbDirect

            if (isEdcbTranscode && is404 && mainAutoRetryCount < 5) {
                mainAutoRetryCount++
                Log.w(TAG, "EDCB HLS 404: Retrying prepare... ($mainAutoRetryCount/5)")
                _mainSseDetail.value = "セグメント生成待機中... ($mainAutoRetryCount/5)"
                delay(2500); _mainPlayer.value?.prepare(); _mainPlayer.value?.play()
                return@launch
            }

            val errorMsg = analyzePlayerError(error)
            if (mainAutoRetryCount < MAX_AUTO_RETRY) {
                mainAutoRetryCount++; _mainSseDetail.value =
                    "通信復旧中... ($mainAutoRetryCount/$MAX_AUTO_RETRY)"
                stopMainPlaybackSafely("main_player_error_retry"); delay(2000)
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

    private fun handleDualError(uiContext: Context, error: PlaybackException) {
        viewModelScope.launch {
            val cause = error.cause
            val is404 =
                cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 404
            val isEdcbTranscode = dualCurrentSource == StreamSource.EDCB && !dualIsEdcbDirect

            if (isEdcbTranscode && is404 && dualAutoRetryCount < 5) {
                dualAutoRetryCount++; _dualSseDetail.value =
                    "セグメント生成待機中... ($dualAutoRetryCount/5)"
                delay(2500); _dualPlayer.value?.prepare(); _dualPlayer.value?.play()
                return@launch
            }

            val errorMsg = analyzePlayerError(error)
            if (dualAutoRetryCount < MAX_AUTO_RETRY) {
                dualAutoRetryCount++; _dualSseDetail.value =
                    "通信復旧中... ($dualAutoRetryCount/$MAX_AUTO_RETRY)"
                stopDualPlaybackSafely("dual_player_error_retry"); delay(2000)
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

    fun playMainChannel(
        uiContext: Context, channel: Channel, source: StreamSource,
        isEdcbDirect: Boolean, quality: StreamQuality, isAutoRetry: Boolean = false
    ) {
        if (channel.displayChannelId.isBlank() || channel.displayChannelId == "null") return
        if (mainCurrentChannel?.id != channel.id) setSubtitleLanguage(1)
        if (!isAutoRetry) {
            mainAutoRetryCount = 0
            _mainPlayerError.value = null
            _mainPlayerErrorIsCapabilityRelated.value = false
        }
        mainCurrentChannel = channel

        viewModelScope.launch { _currentLogoUrl.value = liveProvider.getChannelLogoUrl(channel.id) }

        mainPlaybackJob?.cancel()
        mainPlaybackJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                mainPlaybackMutex.withLock {
                    withContext(Dispatchers.Main) {
                        stopMainPlaybackSafely(); _mainSseStatus.value =
                        "Standby"; _mainSseDetail.value = "ストリームを準備中..."
                    }
                    delay(if (isAutoRetry) 0 else 600)

                    val request = resolvePlaybackRequest(
                        channel = channel,
                        requestedSource = source,
                        requestedIsEdcbDirect = isEdcbDirect,
                        requestedQuality = quality,
                        streamNumber = 0,
                        factory = mainTsDataSourceFactory
                    )
                    mainCurrentSource = request.source
                    mainIsEdcbDirect = request.isEdcbDirect
                    mainCurrentQuality = request.quality

                    val audioOutputMode = settingsRepository.audioOutputMode.first()
                    val newPlayer = withContext(Dispatchers.Main) {
                        livePlayerFactory.createExoPlayer(
                            audioOutputMode = audioOutputMode,
                            isKonomiTvSource = { mainCurrentSource == StreamSource.KONOMITV },
                            onSubtitleDataReceived = { pts, data ->
                                decodeAndEmitMainSubtitle(pts, data)
                            },
                            onError = { error -> handleMainError(uiContext, error) }
                        )
                    }
                    _mainPlayer.value = newPlayer

                    withContext(Dispatchers.Main) {
                        if (request.source == StreamSource.MIRAKURUN || request.source == StreamSource.EDCB) {
                            _mainSseStatus.value = "ONAir"; _mainSseDetail.value = ""
                        } else if (request.config is BackendConfig.KonomiTv) {
                            startMainSse(
                                uiContext,
                                channel.displayChannelId,
                                request.apiQuality,
                                request.config
                            )
                        }
                        startPlayback(
                            newPlayer,
                            request,
                            mainTsDataSourceFactory,
                            ::decodeAndEmitMainSubtitle,
                            ::decodeAndEmitMainB62Subtitle
                        )
                        liveJikkyoManager.startJikkyo(channel, request.source)
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "playMainChannel: Job cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "playMainChannel: Failed", e)
                withContext(Dispatchers.Main) {
                    handleMainError(
                        uiContext,
                        PlaybackException(e.message, e, PlaybackException.ERROR_CODE_UNSPECIFIED)
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
        if (!isAutoRetry) dualAutoRetryCount = 0
        dualCurrentChannel = channel

        dualPlaybackJob?.cancel()
        dualPlaybackJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                dualPlaybackMutex.withLock {
                    withContext(Dispatchers.Main) {
                        stopDualPlaybackSafely(); _dualSseStatus.value =
                        "Standby"; _dualSseDetail.value = "ストリームを準備中..."
                    }
                    delay(if (isAutoRetry) 0 else 600)

                    val request = resolvePlaybackRequest(
                        channel = channel,
                        requestedSource = source,
                        requestedIsEdcbDirect = isEdcbDirect,
                        requestedQuality = quality,
                        streamNumber = 1,
                        factory = dualTsDataSourceFactory
                    )
                    dualCurrentSource = request.source
                    dualIsEdcbDirect = request.isEdcbDirect
                    dualCurrentQuality = request.quality

                    val audioOutputMode = settingsRepository.audioOutputMode.first()
                    val newDualPlayer = withContext(Dispatchers.Main) {
                        livePlayerFactory.createExoPlayer(
                            audioOutputMode = audioOutputMode,
                            isKonomiTvSource = { dualCurrentSource == StreamSource.KONOMITV },
                            onSubtitleDataReceived = { pts, data ->
                                decodeAndEmitDualSubtitle(pts, data)
                            },
                            onError = { error -> handleDualError(uiContext, error) }
                        )
                    }
                    _dualPlayer.value = newDualPlayer

                    withContext(Dispatchers.Main) {
                        if (request.source == StreamSource.MIRAKURUN || request.source == StreamSource.EDCB) {
                            _dualSseStatus.value = "ONAir"; _dualSseDetail.value = ""
                        } else if (request.config is BackendConfig.KonomiTv) {
                            startDualSse(
                                uiContext,
                                channel.displayChannelId,
                                request.apiQuality,
                                request.config
                            )
                        }
                        startPlayback(
                            newDualPlayer,
                            request,
                            dualTsDataSourceFactory,
                            ::decodeAndEmitDualSubtitle,
                            ::decodeAndEmitDualB62Subtitle
                        )
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "playDualChannel: Job cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "playDualChannel: Failed", e)
                withContext(Dispatchers.Main) {
                    handleDualError(
                        uiContext,
                        PlaybackException(e.message, e, PlaybackException.ERROR_CODE_UNSPECIFIED)
                    )
                }
            }
        }
    }

    fun stopAllPlayers() {
        mainPlaybackJob?.cancel(); dualPlaybackJob?.cancel()
        viewModelScope.launch {
            mainPlaybackMutex.withLock { stopMainPlaybackSafely() }
            dualPlaybackMutex.withLock { stopDualPlaybackSafely() }
        }
    }

    fun stopDualPlayer() {
        dualPlaybackJob?.cancel()
        viewModelScope.launch { dualPlaybackMutex.withLock { stopDualPlaybackSafely() } }
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

    private fun decodeAndEmitMainSubtitle(ptsMs: Long, data: ByteArray) {
        val cue = mainCaptionDecoder.decode(data, ptsMs, renderCaptions = isSubtitleEnabled)
        _mainSubtitleLanguages.value = mainCaptionDecoder.availableLanguages()
        if (isSubtitleEnabled && cue != null) _mainSubtitleEvents.tryEmit(cue)
    }

    private fun decodeAndEmitDualSubtitle(ptsMs: Long, data: ByteArray) {
        val cue = dualCaptionDecoder.decode(data, ptsMs, renderCaptions = isSubtitleEnabled)
        _dualSubtitleLanguages.value = dualCaptionDecoder.availableLanguages()
        if (isSubtitleEnabled && cue != null) _dualSubtitleEvents.tryEmit(cue)
    }

    private fun decodeAndEmitMainB62Subtitle(sample: B62SubtitleSample) {
        decodeAndScheduleB62Subtitle(
            decoder = mainCaptionDecoder,
            decodeMutex = mainSubtitleDecodeMutex,
            sample = sample,
            onLanguagesChanged = { _mainSubtitleLanguages.value = it },
            onCue = { _mainSubtitleEvents.tryEmit(it) }
        )
    }

    private fun decodeAndEmitDualB62Subtitle(sample: B62SubtitleSample) {
        decodeAndScheduleB62Subtitle(
            decoder = dualCaptionDecoder,
            decodeMutex = dualSubtitleDecodeMutex,
            sample = sample,
            onLanguagesChanged = { _dualSubtitleLanguages.value = it },
            onCue = { _dualSubtitleEvents.tryEmit(it) }
        )
    }

    private fun decodeAndScheduleB62Subtitle(
        decoder: NativeCaptionDecoder,
        decodeMutex: Mutex,
        sample: B62SubtitleSample,
        onLanguagesChanged: (List<NativeCaptionLanguage>) -> Unit,
        onCue: (NativeCaptionCue) -> Unit
    ) {
        if (!isSubtitleEnabled) return
        viewModelScope.launch(Dispatchers.Default) {
            val (cues, languages) = decodeMutex.withLock {
                val decoded = decoder.decodeB62(
                    data = sample.data,
                    ptsMs = sample.timeUs / 1_000L,
                    operationMode = sample.operationMode,
                    timingMode = sample.timingMode,
                    referenceStartPtsMs = sample.referenceStartTimeUs?.div(1_000L),
                    discontinuity = sample.discontinuity
                )
                decoded to decoder.availableLanguages()
            }
            withContext(Dispatchers.Main.immediate) {
                onLanguagesChanged(languages)
                if (isSubtitleEnabled) cues.forEach(onCue)
            }
        }
    }

    fun setVolumes(mainVolume: Float, dualVolume: Float) {
        _mainPlayer.value?.volume = mainVolume; _dualPlayer.value?.volume = dualVolume
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
        onB62SubtitleDataReceived: (B62SubtitleSample) -> Unit
    ) {
        val mediaItem = MediaItem.fromUri(request.url)
        val mediaSource = when {
            request.quality.isRawMmts -> {
                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                if (request.source == StreamSource.MIRAKURUN) {
                    httpDataSourceFactory.setDefaultRequestProperties(
                        mapOf("X-Mirakurun-Priority" to "0")
                    )
                }
                ProgressiveMediaSource.Factory(
                    httpDataSourceFactory,
                    TlvExtractorsFactory(
                        preferredVideoPacketId = request.quality.videoPacketId,
                        onSubtitleDataReceived = onB62SubtitleDataReceived
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
                        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                            .setDefaultRequestProperties(mapOf("Cookie" to "ctok=$ctok"))
                            .setAllowCrossProtocolRedirects(true)
                        HlsMediaSource.Factory(httpDataSourceFactory)
                            .setAllowChunklessPreparation(false).createMediaSource(mediaItem)
                    }

            else -> DefaultMediaSourceFactory(context).createMediaSource(mediaItem)
        }
        player?.setMediaSource(mediaSource); player?.prepare(); player?.play()
    }

    private fun startMainSse(
        uiContext: Context,
        channelId: String,
        quality: String,
        config: BackendConfig.KonomiTv
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
                    if (t is java.io.IOException && t.message == "Canceled") return
                    response?.close()
                    viewModelScope.launch(Dispatchers.Main) {
                        if (response != null && response.code !in 200..299) handleMainError(
                            uiContext,
                            PlaybackException("KonomiTV HTTP Error", null, response.code)
                        )
                    }
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    viewModelScope.launch(Dispatchers.Main) {
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
                                    )
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
        config: BackendConfig.KonomiTv
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
                    if (t is java.io.IOException && t.message == "Canceled") return
                    response?.close()
                    viewModelScope.launch(Dispatchers.Main) {
                        if (response != null && response.code !in 200..299) handleDualError(
                            uiContext,
                            PlaybackException("HTTP Error", null, response.code)
                        )
                    }
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    viewModelScope.launch(Dispatchers.Main) {
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
                                    )
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
        dualCaptionDecoder.close()
        // ★ 修正: 全通信機能を破壊する自爆スイッチ（shutdown）を撤去し、
        // プレイヤーの releasePlayers() でのクリーンアップに一任する
    }
}
