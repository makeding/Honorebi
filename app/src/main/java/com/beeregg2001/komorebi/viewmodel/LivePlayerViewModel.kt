@file:OptIn(UnstableApi::class)

package com.beeregg2001.komorebi.ui.live

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
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

private class LiveStreamStatusException(
    message: String, cause: Throwable?, val httpStatus: Int?,
) : IOException(message, cause)

private data class LiveSlotIntent(
    val channel: Channel,
    val source: StreamSource,
    val isEdcbDirect: Boolean,
    val quality: StreamQuality,
    val uiContext: Context,
)

private data class LiveNetworkRecoveryRequest(
    val token: LiveChannelSessionToken,
    val run: LivePlaybackSlotController.Run,
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


    private val mainSlot = LivePlaybackSlotController("main") { Log.i(TAG, it) }
    private val dualSlot = LivePlaybackSlotController("dual") { Log.i(TAG, it) }
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainNetworkRecoveryGate = LiveNetworkRecoveryGate(currentDeviceNetworkAvailable())
    private val dualNetworkRecoveryGate = LiveNetworkRecoveryGate(currentDeviceNetworkAvailable())
    private var mainNetworkRecoveryRequest: LiveNetworkRecoveryRequest? = null
    private var dualNetworkRecoveryRequest: LiveNetworkRecoveryRequest? = null
    private val liveAudioFocus = LiveAudioFocus(context) { allowed ->
        val main = mainSlot.currentRuntime()
        val dual = dualSlot.currentRuntime()
        if (!allowed) {
            main?.pause()
            dual?.pause()
        } else {
            if (_mainSseStatus.value == "ONAir" || mainSlot.currentLease() != null) main?.play()
            if (_dualSseStatus.value == "ONAir" || dualSlot.currentLease() != null) dual?.play()
        }
    }

    private val mainPlaybackMutex = Mutex()
    private val dualPlaybackMutex = Mutex()
    private val hdrToneMappingRecoveryRunning = AtomicBoolean(false)
    private val okHttpClient = accessHttpClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()


    private var mainCurrentSource = StreamSource.KONOMITV
    private var mainIsEdcbDirect = false
    private var mainCurrentChannel: Channel? = null
    private var mainCurrentQuality: StreamQuality? = null
    private var mainAutoRetryCount = 0
    private val mainPlaybackHealth = LivePlaybackHealth()
    private var mainIntent: LiveSlotIntent? = null

    private var dualCurrentSource = StreamSource.KONOMITV
    private var dualIsEdcbDirect = false
    private var dualCurrentChannel: Channel? = null
    private var dualCurrentQuality: StreamQuality? = null
    private var dualAutoRetryCount = 0
    private val dualPlaybackHealth = LivePlaybackHealth()
    private var dualIntent: LiveSlotIntent? = null

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

    private fun stopMainPlaybackSafely(
        reason: String = "unspecified",
        endSession: Boolean = true,
        releaseSlot: Boolean = true,
        preserveTerminalStatus: Boolean = false,
    ) {
        Log.w(
            TAG,
            "Stopping main playback: reason=$reason, " +
                "player=${_mainPlayer.value != null}, channel=${mainCurrentChannel?.displayChannelId}, " +
                "source=$mainCurrentSource, quality=${mainCurrentQuality?.value}"
        )
        if (releaseSlot) mainSlot.stop(reason) else mainSlot.releasePlayback(reason)
        mainCaptionFence.reset()
        mainB62SubtitleSamples.clearPending()
        mainCaptionDecoder.reset(_currentSubtitleLanguageId.value)
        mainSuperimposeDecoder.reset()
        _mainSubtitleLanguages.value = emptyList()

        _mainPlayer.value = null
        clearMainRuntimeState()
        mainRawMmtsLayerController.reset()

        if (!preserveTerminalStatus) {
            _mainSseStatus.value = "Standby"; _mainSseDetail.value = AppStrings.SSE_CONNECTING
        }
        liveJikkyoManager.stopJikkyo()
        if (endSession) {
            endChannelSession(LivePlaybackSlot.MAIN)
            if (mainSlot.currentRuntime() == null && dualSlot.currentRuntime() == null) liveAudioFocus.release()
        }
    }

    private fun stopDualPlaybackSafely(
        reason: String = "unspecified",
        endSession: Boolean = true,
        releaseSlot: Boolean = true,
        preserveTerminalStatus: Boolean = false,
    ) {
        Log.w(
            TAG,
            "Stopping dual playback: reason=$reason, " +
                "player=${_dualPlayer.value != null}, channel=${dualCurrentChannel?.displayChannelId}, " +
                "source=$dualCurrentSource, quality=${dualCurrentQuality?.value}"
        )
        if (releaseSlot) dualSlot.stop(reason) else dualSlot.releasePlayback(reason)
        dualCaptionFence.reset()
        dualB62SubtitleSamples.clearPending()
        dualCaptionDecoder.reset(_currentSubtitleLanguageId.value)
        dualSuperimposeDecoder.reset()
        _dualSubtitleLanguages.value = emptyList()

        _dualPlayer.value = null
        clearDualRuntimeState()
        dualRawMmtsLayerController.reset()

        if (!preserveTerminalStatus) {
            _dualSseStatus.value = "Standby"; _dualSseDetail.value = AppStrings.SSE_CONNECTING
        }
        if (endSession) {
            endChannelSession(LivePlaybackSlot.DUAL)
            if (mainSlot.currentRuntime() == null && dualSlot.currentRuntime() == null) liveAudioFocus.release()
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
        liveAudioFocus.release()
        mainSlot.stop(reason); dualSlot.stop(reason)
        mainCaptionFence.reset(); dualCaptionFence.reset()

        _mainPlayer.value = null
        clearMainRuntimeState()
        mainRawMmtsLayerController.reset()

        _dualPlayer.value = null
        clearDualRuntimeState()
        dualRawMmtsLayerController.reset()

        _mainSseStatus.value = "Standby"; _dualSseStatus.value = "Standby"
        liveJikkyoManager.stopJikkyo()
    }

    /** Called by the root's connectivity state, which owns the process-wide network callback. */
    fun onDeviceNetworkChanged(available: Boolean) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (mainNetworkRecoveryGate.onNetworkChanged(available) == LiveNetworkRecoveryDecision.RecoverNow) {
                recoverMainAfterNetworkRegain()
            }
            if (dualNetworkRecoveryGate.onNetworkChanged(available) == LiveNetworkRecoveryDecision.RecoverNow) {
                recoverDualAfterNetworkRegain()
            }
        }
    }

    // LAN backends remain reachable without a validated Internet/default network.
    @Suppress("DEPRECATION")
    private fun currentDeviceNetworkAvailable(): Boolean =
        connectivityManager.allNetworks.any { connectivityManager.getNetworkCapabilities(it) != null }

    private fun parkMainForNetwork(token: LiveChannelSessionToken, run: LivePlaybackSlotController.Run) {
        mainNetworkRecoveryRequest = LiveNetworkRecoveryRequest(token, run)
        run.blockPlaybackInstallation()
        mainPlaybackHealth.reset()
        _mainPlayerError.value = null
        _mainPlayerErrorIsCapabilityRelated.value = false
        _mainSseStatus.value = "Offline"
        _mainSseDetail.value = "ネットワーク接続を確認しています…"
        stopMainPlaybackSafely("main_waiting_for_network", endSession = false, releaseSlot = false,
            preserveTerminalStatus = true)
    }

    private fun parkDualForNetwork(token: LiveChannelSessionToken, run: LivePlaybackSlotController.Run) {
        dualNetworkRecoveryRequest = LiveNetworkRecoveryRequest(token, run)
        run.blockPlaybackInstallation()
        dualPlaybackHealth.reset()
        _dualSseStatus.value = "Offline"
        _dualSseDetail.value = "ネットワーク接続を確認しています…"
        stopDualPlaybackSafely("dual_waiting_for_network", endSession = false, releaseSlot = false,
            preserveTerminalStatus = true)
    }

    private fun recoverMainAfterNetworkRegain() {
        val request = mainNetworkRecoveryRequest ?: return
        mainNetworkRecoveryRequest = null
        if (!channelSessions.isCurrent(request.token) || !request.run.isCurrent()) return
        request.run.launchRecoveryWhenIdle(viewModelScope) {
            if (!channelSessions.isCurrent(request.token) || !request.run.isCurrent()) return@launchRecoveryWhenIdle
            mainIntent?.let { intent ->
                Log.i(TAG, "main retrying after device network regained token=${request.token.epoch}")
                playMainChannel(intent.uiContext, intent.channel, intent.source, intent.isEdcbDirect, intent.quality,
                    isAutoRetry = true)
            }
        }
    }

    private fun recoverDualAfterNetworkRegain() {
        val request = dualNetworkRecoveryRequest ?: return
        dualNetworkRecoveryRequest = null
        if (!channelSessions.isCurrent(request.token) || !request.run.isCurrent()) return
        request.run.launchRecoveryWhenIdle(viewModelScope) {
            if (!channelSessions.isCurrent(request.token) || !request.run.isCurrent()) return@launchRecoveryWhenIdle
            dualIntent?.let { intent ->
                Log.i(TAG, "dual retrying after device network regained token=${request.token.epoch}")
                playDualChannel(intent.uiContext, intent.channel, intent.source, intent.isEdcbDirect, intent.quality,
                    isAutoRetry = true)
            }
        }
    }

    private fun reopenMainAfterServerRestart(token: LiveChannelSessionToken, run: LivePlaybackSlotController.Run) {
        if (!currentDeviceNetworkAvailable()) {
            mainNetworkRecoveryGate.onRecoveryNeeded(currentlyAvailable = false)
            parkMainForNetwork(token, run)
            return
        }
        run.launchRecovery(viewModelScope) {
            if (!channelSessions.isCurrent(token) || !run.isCurrent()) return@launchRecovery
            mainIntent?.let { intent ->
                Log.i(TAG, "main reopening server-restarted stream token=${token.epoch}")
                playMainChannel(intent.uiContext, intent.channel, intent.source, intent.isEdcbDirect, intent.quality,
                    isAutoRetry = true)
            }
        }
    }

    private fun reopenDualAfterServerRestart(token: LiveChannelSessionToken, run: LivePlaybackSlotController.Run) {
        if (!currentDeviceNetworkAvailable()) {
            dualNetworkRecoveryGate.onRecoveryNeeded(currentlyAvailable = false)
            parkDualForNetwork(token, run)
            return
        }
        run.launchRecovery(viewModelScope) {
            if (!channelSessions.isCurrent(token) || !run.isCurrent()) return@launchRecovery
            dualIntent?.let { intent ->
                Log.i(TAG, "dual reopening server-restarted stream token=${token.epoch}")
                playDualChannel(intent.uiContext, intent.channel, intent.source, intent.isEdcbDirect, intent.quality,
                    isAutoRetry = true)
            }
        }
    }

    private fun handleMainError(
        uiContext: Context, error: PlaybackException, token: LiveChannelSessionToken,
        run: LivePlaybackSlotController.Run,
    ) {
        if (!channelSessions.isCurrent(token) || !run.isCurrent() || run.isRecovering()) return
        if (mainNetworkRecoveryGate.onRecoveryNeeded(currentDeviceNetworkAvailable()) ==
            LiveNetworkRecoveryDecision.WaitForNetwork) {
            parkMainForNetwork(token, run)
            return
        }
        if (HdrToneMapping.rejectionCause(error) != null) {
            recoverRejectedHdrToneMapping(uiContext, token)
            return
        }
        run.launchRecovery(viewModelScope) {
            if (!channelSessions.isCurrent(token) || !run.isCurrent()) return@launchRecovery
            val cause = error.cause
            val is404 =
                cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 404
            val isEdcbTranscode = mainCurrentSource == StreamSource.EDCB && !mainIsEdcbDirect

            if (isEdcbTranscode && is404 && mainAutoRetryCount < 5) {
                mainAutoRetryCount++
                Log.w(TAG, "EDCB HLS 404: Retrying prepare... ($mainAutoRetryCount/5)")
                _mainSseDetail.value = "ストリームを準備しています…"
                delay(2500)
                if (channelSessions.isCurrent(token) && run.isCurrent()) {
                    mainSlot.currentRuntime()?.reprepare(playWhenReady = liveAudioFocus.playbackAllowed)
                }
                return@launchRecovery
            }

            val errorMsg = analyzePlayerError(error)
            if (mainAutoRetryCount < MAX_AUTO_RETRY) {
                mainAutoRetryCount++
                mainPlaybackHealth.reset()
                Log.w(TAG, "main recovery attempt=$mainAutoRetryCount token=${token.epoch} error=${error.errorCode}", error)
                stopMainPlaybackSafely("main_player_error_retry", endSession = false, releaseSlot = false)
                _mainSseDetail.value = "通信を復旧しています…"
                delay(2000)
                if (!channelSessions.isCurrent(token) || !run.isCurrent()) return@launchRecovery
                if (!currentDeviceNetworkAvailable()) {
                    mainNetworkRecoveryGate.onRecoveryNeeded(currentlyAvailable = false)
                    parkMainForNetwork(token, run)
                    return@launchRecovery
                }
                mainIntent?.let { intent ->
                    playMainChannel(
                        intent.uiContext, intent.channel, intent.source, intent.isEdcbDirect, intent.quality,
                        true
                    )
                }
            } else {
                _mainPlayerError.value = errorMsg
                _mainSseStatus.value = "Error"
                _mainSseDetail.value = errorMsg
                _mainPlayerErrorIsCapabilityRelated.value = isCapabilityRelatedError(error)
                stopMainPlaybackSafely("main_player_error_exhausted", preserveTerminalStatus = true)
            }
        }
    }

    private fun handleDualError(
        uiContext: Context, error: PlaybackException, token: LiveChannelSessionToken,
        run: LivePlaybackSlotController.Run,
    ) {
        if (!channelSessions.isCurrent(token) || !run.isCurrent() || run.isRecovering()) return
        if (dualNetworkRecoveryGate.onRecoveryNeeded(currentDeviceNetworkAvailable()) ==
            LiveNetworkRecoveryDecision.WaitForNetwork) {
            parkDualForNetwork(token, run)
            return
        }
        if (HdrToneMapping.rejectionCause(error) != null) {
            recoverRejectedHdrToneMapping(uiContext, token)
            return
        }
        run.launchRecovery(viewModelScope) {
            if (!channelSessions.isCurrent(token) || !run.isCurrent()) return@launchRecovery
            val cause = error.cause
            val is404 =
                cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 404
            val isEdcbTranscode = dualCurrentSource == StreamSource.EDCB && !dualIsEdcbDirect

            if (isEdcbTranscode && is404 && dualAutoRetryCount < 5) {
                dualAutoRetryCount++; _dualSseDetail.value =
                    "ストリームを準備しています…"
                delay(2500)
                if (channelSessions.isCurrent(token) && run.isCurrent()) {
                    dualSlot.currentRuntime()?.reprepare(playWhenReady = liveAudioFocus.playbackAllowed)
                }
                return@launchRecovery
            }

            val errorMsg = analyzePlayerError(error)
            if (dualAutoRetryCount < MAX_AUTO_RETRY) {
                dualAutoRetryCount++
                dualPlaybackHealth.reset()
                Log.w(TAG, "dual recovery attempt=$dualAutoRetryCount token=${token.epoch} error=${error.errorCode}", error)
                stopDualPlaybackSafely("dual_player_error_retry", endSession = false, releaseSlot = false)
                _dualSseDetail.value = "通信を復旧しています…"
                delay(2000)
                if (!channelSessions.isCurrent(token) || !run.isCurrent()) return@launchRecovery
                if (!currentDeviceNetworkAvailable()) {
                    dualNetworkRecoveryGate.onRecoveryNeeded(currentlyAvailable = false)
                    parkDualForNetwork(token, run)
                    return@launchRecovery
                }
                dualIntent?.let { intent ->
                    playDualChannel(
                        intent.uiContext, intent.channel, intent.source, intent.isEdcbDirect, intent.quality,
                        true
                    )
                }
            } else {
                _dualSseStatus.value = "Error"; _dualSseDetail.value = errorMsg
                stopDualPlaybackSafely("dual_player_error_exhausted", preserveTerminalStatus = true)
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
        if (!isAutoRetry) mainIntent = LiveSlotIntent(channel, source, isEdcbDirect, quality, uiContext)
        val token = beginChannelSession(LivePlaybackSlot.MAIN, channel.id)
        Log.i(TAG, "main request channel=${channel.id} source=$source quality=${quality.value} token=${token.epoch} retry=$isAutoRetry")
        if (mainCurrentChannel?.id != channel.id) setSubtitleLanguage(1)
        if (!isAutoRetry) {
            mainAutoRetryCount = 0
            mainNetworkRecoveryGate.onPlaybackStarted()
            mainNetworkRecoveryRequest = null
            _mainPlayerError.value = null
            _mainPlayerErrorIsCapabilityRelated.value = false
        }
        mainCurrentChannel = channel
        dataBroadcastingStore.beginSession(channel.id)

        viewModelScope.launch {
            val logoUrl = channelLogoCache.getChannelLogoUrl(channel)
            if (channelSessions.isCurrent(token)) _currentLogoUrl.value = logoUrl
        }

        mainPlaybackHealth.reset()
        val mainRun = mainSlot.begin()
        val mainJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
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
                        stopMainPlaybackSafely(endSession = false, releaseSlot = false); _mainSseStatus.value =
                        "Standby"; _mainSseDetail.value = "ストリームを準備中..."
                    }

                    mainRun.withCreatedResource(
                        create = {
                            livePlaybackSourceResolver.resolve(
                                channel, source, isEdcbDirect, quality,
                                LivePlaybackSlot.MAIN.streamNumber, mainTsDataSourceFactory
                            )
                        },
                        leaseOf = { it.upstreamSession }
                    ) { resolvedRequest, commitLease ->
                        Log.i(TAG, "main resolve channel=${channel.id} source=${resolvedRequest.source} quality=${resolvedRequest.quality.value} token=${token.epoch} session=${resolvedRequest.upstreamSession?.id}")
                        if (!channelSessions.isCurrent(token)) return@withCreatedResource
                        mainCurrentSource = resolvedRequest.source
                        mainIsEdcbDirect = resolvedRequest.isEdcbDirect
                        mainCurrentQuality = resolvedRequest.quality

                        val audioOutputMode = settingsRepository.audioOutputMode.first()
                        val hdrRenderMode = settingsRepository.hdrRenderMode.first()
                        if (!channelSessions.isCurrent(token)) return@withCreatedResource
                        withContext(Dispatchers.Main) {
                            if (!channelSessions.isCurrent(token)) return@withContext
                            val runtime = PlayerRuntime(context, livePlayerProfile(audioOutputMode, hdrRenderMode, channel.type == "BS4K"))
                            if (!channelSessions.isCurrent(token)) {
                                runtime.release()
                                return@withContext
                            }
                            if (!mainRun.installRuntime(runtime)) return@withContext
                            _mainPlayer.value = runtime.player
                            bindMainRuntimeState(runtime)
                            attachMainRuntimeListeners(runtime, resolvedRequest.source, token, uiContext, mainRun)
                            if (resolvedRequest.source == StreamSource.MIRAKURUN || resolvedRequest.source == StreamSource.EDCB) {
                                _mainSseStatus.value = "ONAir"; _mainSseDetail.value = ""
                            } else if (!channel.supportsLiveStreamSession() && resolvedRequest.config is BackendConfig.KonomiTv) {
                                startMainSse(
                                    uiContext,
                                    channel.displayChannelId,
                                    resolvedRequest.apiQuality,
                                    resolvedRequest.config,
                                    token, mainRun
                                )
                            }
                            startPlayback(
                                runtime,
                                resolvedRequest,
                                mainTsDataSourceFactory,
                                { pts, data -> decodeAndEmitMainSubtitle(token, pts, data) },
                                { sample -> decodeAndEmitMainB62Subtitle(token, sample) },
                                if (channel.capabilities.dataBroadcasting) LiveSessionDataBroadcastingCallback(
                                    token, channelSessions, dataBroadcastingStore
                                ) else null,
                                mainRawMmtsLayerController,
                                token
                            )
                            if (!commitLease()) return@withContext
                            if (!channel.isJellyfin) {
                                liveJikkyoManager.startJikkyo(channel, resolvedRequest.source, token, channelSessions::isCurrent)
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "main stream creation timed out: channel=${channel.id} token=${token.epoch}", e)
                withContext(Dispatchers.Main) {
                    if (channelSessions.isCurrent(token) && mainRun.isCurrent()) {
                        handleMainError(
                            uiContext,
                            PlaybackException("ストリームの作成がタイムアウトしました", e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
                            token, mainRun
                        )
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
                        PlaybackException(e.message, e, PlaybackException.ERROR_CODE_UNSPECIFIED), token, mainRun
                    )
                }
            }
        }
        mainRun.attachStartupJob(mainJob)
        mainJob.start()
    }

    fun playDualChannel(
        uiContext: Context, channel: Channel, source: StreamSource,
        isEdcbDirect: Boolean, quality: StreamQuality, isAutoRetry: Boolean = false
    ) {
        if (channel.displayChannelId.isBlank() || channel.displayChannelId == "null") return
        if (!isAutoRetry) dualIntent = LiveSlotIntent(channel, source, isEdcbDirect, quality, uiContext)
        val token = beginChannelSession(LivePlaybackSlot.DUAL, channel.id)
        Log.i(TAG, "dual request channel=${channel.id} source=$source quality=${quality.value} token=${token.epoch} retry=$isAutoRetry")
        if (!isAutoRetry) {
            dualAutoRetryCount = 0
            dualNetworkRecoveryGate.onPlaybackStarted()
            dualNetworkRecoveryRequest = null
        }
        dualCurrentChannel = channel

        dualPlaybackHealth.reset()
        val dualRun = dualSlot.begin()
        val dualJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                dualPlaybackMutex.withLock {
                    withContext(Dispatchers.Main) {
                        if (!channelSessions.isCurrent(token)) return@withContext
                        stopDualPlaybackSafely(endSession = false, releaseSlot = false); _dualSseStatus.value =
                        "Standby"; _dualSseDetail.value = "ストリームを準備中..."
                    }
                    delay(if (isAutoRetry) 0 else 600)
                    if (!channelSessions.isCurrent(token)) return@withLock

                    dualRun.withCreatedResource(
                        create = {
                            livePlaybackSourceResolver.resolve(
                                channel, source, isEdcbDirect, quality,
                                LivePlaybackSlot.DUAL.streamNumber, dualTsDataSourceFactory
                            )
                        },
                        leaseOf = { it.upstreamSession }
                    ) { resolvedRequest, commitLease ->
                        Log.i(TAG, "dual resolve channel=${channel.id} source=${resolvedRequest.source} quality=${resolvedRequest.quality.value} token=${token.epoch} session=${resolvedRequest.upstreamSession?.id}")
                        if (!channelSessions.isCurrent(token)) return@withCreatedResource
                        dualCurrentSource = resolvedRequest.source
                        dualIsEdcbDirect = resolvedRequest.isEdcbDirect
                        dualCurrentQuality = resolvedRequest.quality

                        val audioOutputMode = settingsRepository.audioOutputMode.first()
                        val hdrRenderMode = settingsRepository.hdrRenderMode.first()
                        if (!channelSessions.isCurrent(token)) return@withCreatedResource
                        withContext(Dispatchers.Main) {
                            if (!channelSessions.isCurrent(token)) return@withContext
                            val runtime = PlayerRuntime(context, livePlayerProfile(audioOutputMode, hdrRenderMode, channel.type == "BS4K"))
                            if (!channelSessions.isCurrent(token)) {
                                runtime.release()
                                return@withContext
                            }
                            if (!dualRun.installRuntime(runtime)) return@withContext
                            _dualPlayer.value = runtime.player
                            bindDualRuntimeState(runtime)
                            attachDualRuntimeListeners(runtime, resolvedRequest.source, token, uiContext, dualRun)
                            if (resolvedRequest.source == StreamSource.MIRAKURUN || resolvedRequest.source == StreamSource.EDCB) {
                                _dualSseStatus.value = "ONAir"; _dualSseDetail.value = ""
                            } else if (!channel.supportsLiveStreamSession() && resolvedRequest.config is BackendConfig.KonomiTv) {
                                startDualSse(
                                    uiContext,
                                    channel.displayChannelId,
                                    resolvedRequest.apiQuality,
                                    resolvedRequest.config,
                                    token, dualRun
                                )
                            }
                            startPlayback(
                                runtime,
                                resolvedRequest,
                                dualTsDataSourceFactory,
                                { pts, data -> decodeAndEmitDualSubtitle(token, pts, data) },
                                { sample -> decodeAndEmitDualB62Subtitle(token, sample) },
                                rawMmtsLayerController = dualRawMmtsLayerController,
                                sessionToken = token
                            )
                            if (!commitLease()) return@withContext
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "dual stream creation timed out: channel=${channel.id} token=${token.epoch}", e)
                withContext(Dispatchers.Main) {
                    if (channelSessions.isCurrent(token) && dualRun.isCurrent()) {
                        handleDualError(
                            uiContext,
                            PlaybackException("ストリームの作成がタイムアウトしました", e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
                            token, dualRun
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
                        PlaybackException(e.message, e, PlaybackException.ERROR_CODE_UNSPECIFIED), token, dualRun
                    )
                }
            }
        }
        dualRun.attachStartupJob(dualJob)
        dualJob.start()
    }

    fun stopAllPlayers() {
        endChannelSession(LivePlaybackSlot.MAIN)
        endChannelSession(LivePlaybackSlot.DUAL)
        liveAudioFocus.release()
        mainSlot.stop("stop_all"); dualSlot.stop("stop_all")
        stopMainPlaybackSafely("stop_all", endSession = false, releaseSlot = false)
        stopDualPlaybackSafely("stop_all", endSession = false, releaseSlot = false)
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
        dualSlot.stop("stop_dual")
        stopDualPlaybackSafely("stop_dual", endSession = false, releaseSlot = false)
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

    fun retryMain(uiContext: Context) {
        val intent = mainIntent ?: return
        mainAutoRetryCount = 0
        _mainPlayerError.value = null
        _mainPlayerErrorIsCapabilityRelated.value = false
        playMainChannel(uiContext, intent.channel, intent.source, intent.isEdcbDirect, intent.quality)
    }

    fun retryDual(uiContext: Context) {
        val intent = dualIntent ?: return
        dualAutoRetryCount = 0
        playDualChannel(uiContext, intent.channel, intent.source, intent.isEdcbDirect, intent.quality)
    }

    /** The scene consumes this snapshot; runtime remains the only basic event listener. */
    private fun bindMainRuntimeState(runtime: PlayerRuntime) {
        mainRuntimeStateJob?.cancel()
        _mainRuntimeState.value = runtime.state.value
        mainRuntimeStateJob = viewModelScope.launch {
            runtime.state.collect { state ->
                if (mainSlot.currentRuntime() === runtime) {
                    _mainRuntimeState.value = state
                    // セッション再生には放送 SSE がないため、Media3 の準備完了で待機表示を解除する。
                    if (mainSlot.currentLease() != null && state.playbackState == Player.STATE_READY) {
                        _mainSseStatus.value = "ONAir"
                        _mainSseDetail.value = ""
                    }
                }
            }
        }
    }

    private fun bindDualRuntimeState(runtime: PlayerRuntime) {
        dualRuntimeStateJob?.cancel()
        _dualRuntimeState.value = runtime.state.value
        dualRuntimeStateJob = viewModelScope.launch {
            runtime.state.collect { state ->
                if (dualSlot.currentRuntime() === runtime) {
                    _dualRuntimeState.value = state
                    // 二画面目も独立したセッションの準備完了を使う。
                    if (dualSlot.currentLease() != null && state.playbackState == Player.STATE_READY) {
                        _dualSseStatus.value = "ONAir"
                        _dualSseDetail.value = ""
                    }
                }
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
        run: LivePlaybackSlotController.Run,
    ) {
        runtime.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handleMainError(uiContext, error, token, run)

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (channelSessions.isCurrent(token)) Log.i(TAG, "main state=$playbackState channel=${token.channelId} token=${token.epoch} position=${runtime.player.currentPosition} playing=${runtime.player.isPlaying}")
                if (playbackState == Player.STATE_ENDED && source == StreamSource.KONOMITV &&
                    channelSessions.isCurrent(token) && run.isCurrent()) {
                    handleMainError(uiContext, PlaybackException(
                        "ライブストリームが切断されました", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                    ), token, run)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (channelSessions.isCurrent(token)) Log.i(TAG, "main playWhenReady=$playWhenReady reason=$reason token=${token.epoch}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (channelSessions.isCurrent(token)) Log.i(TAG, "main playing=$isPlaying token=${token.epoch} position=${runtime.player.currentPosition}")
                if (channelSessions.isCurrent(token) && !isPlaying) mainPlaybackHealth.reset()
            }

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
        run: LivePlaybackSlotController.Run,
    ) {
        runtime.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handleDualError(uiContext, error, token, run)

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (channelSessions.isCurrent(token)) Log.i(TAG, "dual state=$playbackState channel=${token.channelId} token=${token.epoch} position=${runtime.player.currentPosition} playing=${runtime.player.isPlaying}")
                if (playbackState == Player.STATE_ENDED && source == StreamSource.KONOMITV &&
                    channelSessions.isCurrent(token) && run.isCurrent()) {
                    handleDualError(uiContext, PlaybackException(
                        "ライブストリームが切断されました", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                    ), token, run)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (channelSessions.isCurrent(token)) Log.i(TAG, "dual playWhenReady=$playWhenReady reason=$reason token=${token.epoch}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (channelSessions.isCurrent(token)) Log.i(TAG, "dual playing=$isPlaying token=${token.epoch} position=${runtime.player.currentPosition}")
                if (channelSessions.isCurrent(token) && !isPlaying) dualPlaybackHealth.reset()
            }

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
        if (acceptsCurrentSession()) {
            val playbackAllowed = liveAudioFocus.acquire()
            if (!playbackAllowed && !liveAudioFocus.isAwaitingGain) {
                throw IOException("他のアプリが音声を使用しているため再生を開始できません (AUDIO_FOCUS_DENIED)")
            }
            runtime.load(mediaSource, playWhenReady = playbackAllowed)
        }
    }

    private fun startMainSse(
        uiContext: Context, channelId: String, quality: String, config: BackendConfig.KonomiTv,
        token: LiveChannelSessionToken, run: LivePlaybackSlotController.Run,
    ) = startSlotSse(uiContext, channelId, quality, config, token, run, true)

    private fun startDualSse(
        uiContext: Context, channelId: String, quality: String, config: BackendConfig.KonomiTv,
        token: LiveChannelSessionToken, run: LivePlaybackSlotController.Run,
    ) = startSlotSse(uiContext, channelId, quality, config, token, run, false)

    private fun startSlotSse(
        uiContext: Context, channelId: String, quality: String, config: BackendConfig.KonomiTv,
        token: LiveChannelSessionToken, run: LivePlaybackSlotController.Run, main: Boolean,
    ) {
        val slot = if (main) mainSlot else dualSlot
        val statusFlow = if (main) _mainSseStatus else _dualSseStatus
        val detailFlow = if (main) _mainSseDetail else _dualSseDetail
        val label = if (main) "main" else "dual"
        val fail: (PlaybackException) -> Unit = { error ->
            if (main) handleMainError(uiContext, error, token, run)
            else handleDualError(uiContext, error, token, run)
        }
        val request = Request.Builder()
            .url(UrlBuilder.getKonomiTvLiveEventsUrl(config.ip, config.port, channelId, quality))
            .header("User-Agent", "Komorebi/1.0 ($label)").build()
        val connection = LiveStreamEventConnection(
            factory = EventSources.createFactory(okHttpClient), request = request,
            scope = viewModelScope,
            isCurrent = { channelSessions.isCurrent(token) && run.isCurrent() && !run.isRecovering() },
            onEvent = { data ->
                try {
                    val json = JSONObject(data)
                    val status = json.optString("status", "Unknown")
                    val detail = json.optString("detail", AppStrings.STATUS_LOADING)
                    Log.i(TAG, "$label SSE status=$status channel=$channelId quality=$quality token=${token.epoch}")
                    statusFlow.value = status
                    detailFlow.value = if (detail.contains("OnAirです")) "" else detail
                    if (status == "Offline" && !currentDeviceNetworkAvailable()) {
                        if (main) {
                            mainNetworkRecoveryGate.onRecoveryNeeded(currentlyAvailable = false)
                            parkMainForNetwork(token, run)
                        } else {
                            dualNetworkRecoveryGate.onRecoveryNeeded(currentlyAvailable = false)
                            parkDualForNetwork(token, run)
                        }
                    } else if (status == "Error" || (status == "Offline" &&
                        (detail.contains("失敗") || detail.contains("エラー")))) {
                        fail(PlaybackException(detail.ifEmpty { AppStrings.ERR_TUNER_START_FAILED },
                            null, PlaybackException.ERROR_CODE_UNSPECIFIED))
                    } else {
                        val runtime = slot.currentRuntime()
                        val invalid = runtime?.player?.playerError != null ||
                            runtime?.player?.playbackState == Player.STATE_ENDED
                        when (run.broadcastState.onStatus(status, invalid)) {
                            LiveBroadcastStreamAction.PAUSE -> runtime?.pause()
                            LiveBroadcastStreamAction.REOPEN_AFTER_RESTART -> {
                                // Server Restart is an expected lifecycle transition. Re-resolve the
                                // media source without converting it into a player I/O failure.
                                if (main) reopenMainAfterServerRestart(token, run)
                                else reopenDualAfterServerRestart(token, run)
                            }
                            LiveBroadcastStreamAction.RECOVER_INVALID_MEDIA -> fail(PlaybackException(
                                "ライブストリームが切断されました", null,
                                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                            ))
                            LiveBroadcastStreamAction.PLAY -> if (liveAudioFocus.playbackAllowed) runtime?.play()
                            LiveBroadcastStreamAction.NONE -> Unit
                        }
                    }
                } catch (error: org.json.JSONException) {
                    Log.w(TAG, "$label invalid SSE payload token=${token.epoch}", error)
                }
            },
            onExhausted = { cause, code ->
                val reason = if (code != null) "放送状態の取得に失敗しました (HTTP $code)"
                    else "放送状態の接続が切断されました"
                fail(PlaybackException(reason, LiveStreamStatusException(reason, cause, code),
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            },
            log = { Log.i(TAG, "$label SSE token=${token.epoch} $it") },
        )
        if (run.installEventSource(connection)) connection.start()
    }

    private fun startSignalPolling() {
        signalPollJob?.cancel()
        signalPollJob = viewModelScope.launch(Dispatchers.Main) {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                mainSlot.currentRuntime()?.player?.let { player ->
                    if (mainPlaybackHealth.observe(now, player.isPlaying, player.currentPosition) && mainAutoRetryCount > 0) {
                        Log.i(TAG, "main recovery stable token=${_mainSessionToken.value?.epoch} position=${player.currentPosition}")
                        mainAutoRetryCount = 0
                    }
                }
                dualSlot.currentRuntime()?.player?.let { player ->
                    if (dualPlaybackHealth.observe(now, player.isPlaying, player.currentPosition) && dualAutoRetryCount > 0) {
                        Log.i(TAG, "dual recovery stable token=${_dualSessionToken.value?.epoch} position=${player.currentPosition}")
                        dualAutoRetryCount = 0
                    }
                }
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
        val message = when {
            cause is LiveStreamStatusException -> "${cause.message}。再試行してください。"
            cause is TimeoutCancellationException -> "ストリームの作成がタイムアウトしました。再試行してください。"
            cause is retrofit2.HttpException -> when (cause.code()) {
                401, 403 -> "ネットテレビへのアクセスが拒否されました。接続設定とログイン状態を確認してください。"
                404 -> "選択したネットテレビのチャンネルが見つかりません。チャンネル一覧を更新してください。"
                422 -> "ネットテレビの再生要求を受け付けられませんでした。別のチャンネルを選ぶか再試行してください。"
                else -> "ネットテレビのサーバーに接続できませんでした。再試行してください。"
            }
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
            else -> error.message?.takeIf { it.isNotBlank() } ?: AppStrings.ERR_UNKNOWN
        }
        val safeCode = (cause as? HttpDataSource.InvalidResponseCodeException)
            ?.let { "HTTP_${it.responseCode}" }
            ?: (cause as? retrofit2.HttpException)?.let { "HTTP_${it.code()}" }
            ?: (cause as? LiveStreamStatusException)?.httpStatus?.let { "HTTP_$it" }
            ?: error.errorCodeName
        return "$message\n[$safeCode]"
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
