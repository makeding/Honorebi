package com.beeregg2001.komorebi.data.repository

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.LiveRowState
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.UiChannelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import com.beeregg2001.komorebi.data.api.interceptor.BackendRetryGate
import com.beeregg2001.komorebi.data.api.interceptor.backendApiFailure
import kotlinx.coroutines.flow.drop

private const val TAG = "AppContentStore"
private const val CHANNEL_REFRESH_INTERVAL_MS = 60_000L
private const val RECORDING_REFRESH_INTERVAL_MS = 30_000L
private const val PROGRESS_REFRESH_INTERVAL_MS = 15_000L

@RequiresApi(Build.VERSION_CODES.O)
@Singleton
class AppContentStore @Inject constructor(
    private val liveProvider: LiveProvider,
    private val recordProvider: RecordProvider,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isChannelsLoading = MutableStateFlow(true)
    val isChannelsLoading: StateFlow<Boolean> = _isChannelsLoading.asStateFlow()

    private val _groupedChannels = MutableStateFlow<Map<String, List<Channel>>>(emptyMap())
    val groupedChannels: StateFlow<Map<String, List<Channel>>> = _groupedChannels.asStateFlow()

    private val _liveRows = MutableStateFlow<List<LiveRowState>>(emptyList())
    val liveRows: StateFlow<List<LiveRowState>> = _liveRows.asStateFlow()

    private val _recentRecordings = MutableStateFlow<List<RecordedProgram>>(emptyList())
    val recentRecordings: StateFlow<List<RecordedProgram>> = _recentRecordings.asStateFlow()

    private val _isRecordingsLoading = MutableStateFlow(true)
    val isRecordingsLoading: StateFlow<Boolean> = _isRecordingsLoading.asStateFlow()

    private val _connectionError = MutableStateFlow(false)
    val connectionError: StateFlow<Boolean> = _connectionError.asStateFlow()
    private val _channelError = MutableStateFlow<String?>(null)
    val channelError = _channelError.asStateFlow()
    private val _recordingError = MutableStateFlow<String?>(null)
    val recordingError = _recordingError.asStateFlow()
    private val channelRetry = BackendRetryGate(CHANNEL_REFRESH_INTERVAL_MS)
    private val recordingRetry = BackendRetryGate(RECORDING_REFRESH_INTERVAL_MS)

    private val _sourceErrors = MutableStateFlow<Map<String, String?>>(emptyMap())
    val sourceErrors: StateFlow<Map<String, String?>> = _sourceErrors.asStateFlow()

    @Volatile
    private var isFetchingChannels = false
    @Volatile
    private var isFetchingRecordings = false

    private var channelFetchJob: Job? = null
    private var recordingFetchJob: Job? = null
    private var pollingJob: Job? = null
    private var progressUpdateJob: Job? = null
    private var lastChannelsFetchedAtMillis = 0L
    private var lastRecordingsFetchedAtMillis = 0L
    @Volatile
    private var isRecordingPollingEnabled = false

    init {
        startMaintenance()
        scope.launch {
            settingsRepository.cloudflareAccessConfiguration.drop(1).collect {
                channelRetry.reset()
                recordingRetry.reset()
                refreshChannels()
                if (isRecordingPollingEnabled) refreshRecentRecordings()
            }
        }
    }

    fun setPollingPaused(paused: Boolean) = Unit

    fun refreshChannels() {
        if (isFetchingChannels || channelFetchJob?.isActive == true) return
        channelRetry.reset()
        _isChannelsLoading.value = true
        channelFetchJob?.cancel()
        channelFetchJob = scope.launch {
            fetchChannelsInternal()
        }
    }

    fun refreshRecentRecordings() {
        // Recordings are intentionally lazy.  The first recording surface to call
        // this method opts into both the initial request and subsequent polling.
        isRecordingPollingEnabled = true
        if (isFetchingRecordings || recordingFetchJob?.isActive == true) return
        recordingRetry.reset()
        _isRecordingsLoading.value = true
        recordingFetchJob?.cancel()
        recordingFetchJob = scope.launch {
            fetchRecentRecordingsInternal()
        }
    }

    private fun startMaintenance() {
        if (progressUpdateJob?.isActive != true) {
            startProgressUpdater()
        }
        if (pollingJob?.isActive == true) return
        pollingJob?.cancel()
        pollingJob = scope.launch {
            fetchChannelsInternal()

            while (isActive) {
                delay(5_000L)

                if (!isActive) break
                val now = System.currentTimeMillis()
                if (channelRetry.canAttempt(now - lastChannelsFetchedAtMillis)) {
                    fetchChannelsInternal()
                }
                if (isRecordingPollingEnabled &&
                    recordingRetry.canAttempt(now - lastRecordingsFetchedAtMillis)
                ) {
                    fetchRecentRecordingsInternal()
                }
            }
        }
    }

    private fun startProgressUpdater() {
        progressUpdateJob?.cancel()
        progressUpdateJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_REFRESH_INTERVAL_MS)
                if (_groupedChannels.value.isNotEmpty()) {
                    val newRows = transformToUiState(_groupedChannels.value)
                    if (_liveRows.value != newRows) {
                        _liveRows.value = newRows
                    }
                }
            }
        }
    }

    private suspend fun fetchChannelsInternal() {
        if (isFetchingChannels) return
        isFetchingChannels = true
        try {
            val response = withContext(Dispatchers.IO) { liveProvider.getChannels() }
            _connectionError.value = false
            _channelError.value = null
            channelRetry.reset()
            _sourceErrors.value = response.sourceErrors
            val hideSubChannels = settingsRepository.hideSubChannels.first()

            val processed = withContext(Dispatchers.Default) {
                val rawChannels = listOfNotNull(
                    response.terrestrial,
                    response.bs,
                    response.cs,
                    response.sky,
                    response.bs4k,
                    response.iptv,
                ).flatten()

                val channels = rawChannels.map { apiChannel ->
                    Channel(
                        id = apiChannel.id,
                        name = apiChannel.name,
                        type = apiChannel.type,
                        channelNumber = apiChannel.channelNumber,
                        networkId = apiChannel.networkId,
                        serviceId = apiChannel.serviceId,
                        displayChannelId = apiChannel.displayChannelId ?: apiChannel.id,
                        isWatchable = apiChannel.isWatchable,
                        isDisplay = apiChannel.isDisplay,
                        programPresent = apiChannel.programPresent,
                        programFollowing = apiChannel.programFollowing,
                        remocon_Id = apiChannel.remocon_Id,
                        jikkyoForce = apiChannel.jikkyoForce,
                        is_subchannel = apiChannel.is_subchannel,
                        transportStreamId = apiChannel.transportStreamId,
                        source = apiChannel.source,
                        capabilities = apiChannel.capabilities,
                    )
                }

                val filtered = if (hideSubChannels) {
                    channels.filter { !it.is_subchannel }
                } else {
                    channels
                }

                filtered.filter { it.isDisplay }.groupBy { it.type }
            }

            if (_groupedChannels.value != processed) {
                _groupedChannels.value = processed
            }

            val newRows = transformToUiState(processed)
            if (_liveRows.value != newRows) {
                _liveRows.value = newRows
            }

        } catch (e: CancellationException) {
            Log.d(TAG, "fetchChannelsInternal cancelled")
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching channels", e)
            _connectionError.value = true
            _channelError.value = e.backendApiFailure()?.displayText ?: e.message ?: "チャンネルを取得できませんでした。接続を確認して再試行してください。"
            channelRetry.failed(e)
        } finally {
            lastChannelsFetchedAtMillis = System.currentTimeMillis()
            isFetchingChannels = false
            _isChannelsLoading.value = false
        }
    }

    private suspend fun fetchRecentRecordingsInternal() {
        if (isFetchingRecordings) return
        isFetchingRecordings = true
        try {
            val response = withContext(Dispatchers.IO) {
                recordProvider.getRecordedPrograms(page = 1, order = "desc")
            }
            val firstPage = response.recordedPrograms.take(20)
            _recordingError.value = null
            recordingRetry.reset()
            if (_recentRecordings.value != firstPage) {
                _recentRecordings.value = firstPage
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "fetchRecentRecordingsInternal cancelled")
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching recent recordings", e)
            _recordingError.value = e.backendApiFailure()?.displayText ?: e.message ?: "録画番組を取得できませんでした。接続を確認して再試行してください。"
            recordingRetry.failed(e)
        } finally {
            lastRecordingsFetchedAtMillis = System.currentTimeMillis()
            isFetchingRecordings = false
            _isRecordingsLoading.value = false
        }
    }

    private suspend fun transformToUiState(grouped: Map<String, List<Channel>>): List<LiveRowState> =
        withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            // Lists within each type retain /api/channels order; only the existing category
            // positions are fixed here. ネット sits directly to the right of BS4K.
            val orderedTypes = listOf("GR", "BS", "CS", "BS4K", "IPTV", "SKY")

            grouped.keys.sortedBy { key ->
                val index = orderedTypes.indexOf(key)
                if (index >= 0) index else Int.MAX_VALUE
            }.mapNotNull { type ->
                val channels = grouped[type] ?: return@mapNotNull null
                LiveRowState(
                    genreId = type,
                    genreLabel = when (type) {
                        "GR" -> "地デジ"
                        "BS" -> "BS"
                        "CS" -> "CS"
                        "BS4K" -> "BS4K"
                        "SKY" -> "スカパー"
                        "IPTV" -> "ネットテレビ"
                        else -> type
                    },
                    channels = channels.map { channel ->
                        val start = channel.programPresent?.startTime?.let {
                            runCatching {
                                OffsetDateTime.parse(it).toInstant().toEpochMilli()
                            }.getOrNull()
                        } ?: 0L
                        val duration = channel.programPresent?.duration ?: 0
                        val progress = if (start > 0 && duration > 0) {
                            ((now - start).toFloat() / (duration * 1000).toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }

                        UiChannelState(
                            channel = channel,
                            displayChannelId = channel.displayChannelId,
                            name = channel.name,
                            programTitle = channel.programPresent?.title ?: "放送休止中",
                            progress = progress,
                            hasProgram = channel.programPresent != null,
                            jikkyoForce = channel.jikkyoForce
                        )
                    }
                )
            }
        }
}
