package com.beeregg2001.komorebi.data.repository

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.LiveRowState
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.UiChannelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.withTimeoutOrNull
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import com.beeregg2001.komorebi.data.api.interceptor.BackendRetryGate
import com.beeregg2001.komorebi.data.api.interceptor.backendApiFailure
import com.beeregg2001.komorebi.common.userFacingFailure
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

private const val TAG = "AppContentStore"
private const val CHANNEL_REFRESH_INTERVAL_MS = 60_000L
private const val RECORDING_REFRESH_INTERVAL_MS = 30_000L
private const val PROGRESS_REFRESH_INTERVAL_MS = 15_000L
private val RECOVERY_RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

@RequiresApi(Build.VERSION_CODES.O)
@Singleton
class AppContentStore internal constructor(
    private val liveProvider: LiveProvider,
    private val recordProvider: RecordProvider,
    private val settingsRepository: SettingsRepository,
    private val nhkExclusionRepository: NHKExclusionRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val elapsedRealtime: () -> Long,
) {
    @Inject constructor(
        liveProvider: LiveProvider,
        recordProvider: RecordProvider,
        settingsRepository: SettingsRepository,
        nhkExclusionRepository: NHKExclusionRepository,
    ) : this(
        liveProvider, recordProvider, settingsRepository, nhkExclusionRepository,
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        Dispatchers.IO, SystemClock::elapsedRealtime,
    )

    private val _isChannelsLoading = MutableStateFlow(true)
    val isChannelsLoading: StateFlow<Boolean> = _isChannelsLoading.asStateFlow()

    private val _groupedChannels = MutableStateFlow<Map<String, List<Channel>>>(emptyMap())
    val groupedChannels: StateFlow<Map<String, List<Channel>>> = combine(
        _groupedChannels, nhkExclusionRepository.state,
    ) { grouped, exclusion ->
        if (!exclusion.isLoaded) emptyMap() else grouped.mapValues { (_, channels) ->
            channels.filterNot(nhkExclusionRepository::isExcluded)
        }.filterValues { it.isNotEmpty() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _liveRows = MutableStateFlow<List<LiveRowState>>(emptyList())
    val liveRows: StateFlow<List<LiveRowState>> = combine(
        _liveRows, nhkExclusionRepository.state,
    ) { rows, exclusion ->
        if (!exclusion.isLoaded) emptyList() else rows.map { row ->
            row.copy(channels = row.channels.filterNot { nhkExclusionRepository.isExcluded(it.channel) })
        }.filter { it.channels.isNotEmpty() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _recentRecordings = MutableStateFlow<List<RecordedProgram>>(emptyList())
    val recentRecordings: StateFlow<List<RecordedProgram>> = combine(
        _recentRecordings, nhkExclusionRepository.state,
    ) { programs, exclusion ->
        if (!exclusion.isLoaded) emptyList() else programs.filterNot(nhkExclusionRepository::isExcluded).take(20)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

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

    private val channelRefreshRunner = ContentRefreshRunner()
    private val recordingRefreshRunner = ContentRefreshRunner()
    private var recordingPollingJob: Job? = null
    private var pollingJob: Job? = null
    private var progressUpdateJob: Job? = null
    private var lastChannelsFetchedAtMillis = 0L
    private var lastRecordingsFetchedAtMillis = 0L
    private var appAwake = true
    private val wakeGeneration = MutableStateFlow(0)
    private var channelRecoveryPending = false
    private var recordingRecoveryPending = false
    private var networkAvailable = true
    private val _recoveryVersion = MutableStateFlow(0)
    val recoveryVersion = _recoveryVersion.asStateFlow()

    fun setNetworkAvailable(available: Boolean) {
        if (networkAvailable == available) return
        networkAvailable = available
        wakeGeneration.value++
    }
    @Volatile
    private var isRecordingPollingEnabled = false

    init {
        scope.launch {
            nhkExclusionRepository.state.drop(1).collect { exclusion ->
                if (exclusion.isLoaded && isRecordingPollingEnabled) refreshRecentRecordings()
            }
        }
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

    fun setAppAwake(awake: Boolean) {
        if (appAwake == awake) return
        appAwake = awake
        wakeGeneration.value++
    }

    fun refreshChannels(afterNetworkRecovery: Boolean = false) {
        channelRecoveryPending = channelRecoveryPending || afterNetworkRecovery
        channelRetry.reset()
        _isChannelsLoading.value = true
        scope.launch {
            fetchChannelsInternal(queueIfRunning = afterNetworkRecovery)
        }
    }

    fun refreshRecentRecordings(afterRecovery: Boolean = false) {
        // Recordings are intentionally lazy.  The first recording surface to call
        // this method opts into both the initial request and subsequent polling.
        isRecordingPollingEnabled = true
        recordingRecoveryPending = recordingRecoveryPending || afterRecovery
        recordingRetry.reset()
        _isRecordingsLoading.value = true
        scope.launch {
            fetchRecentRecordingsInternal(queueIfRunning = afterRecovery)
        }
    }

    /** Recovery never opts a Live-only session into recording requests. */
    fun refreshAfterRecovery() {
        _recoveryVersion.value++
        refreshChannels(afterNetworkRecovery = true)
        if (isRecordingPollingEnabled) refreshRecentRecordings(afterRecovery = true)
    }

    private fun startMaintenance() {
        if (progressUpdateJob?.isActive != true) {
            startProgressUpdater()
        }
        if (recordingPollingJob?.isActive != true) {
            recordingPollingJob = scope.launch {
                while (isActive) {
                    delay(5_000L)
                    if (appAwake && networkAvailable && isRecordingPollingEnabled &&
                        recordingRetry.canAttempt(elapsedRealtime() - lastRecordingsFetchedAtMillis)
                    ) {
                        fetchRecentRecordingsInternal()
                    }
                }
            }
        }
        if (pollingJob?.isActive == true) return
        pollingJob?.cancel()
        pollingJob = scope.launch {
            fetchChannelsInternal()

            while (isActive) {
                delay(5_000L)

                if (!isActive) break
                val now = elapsedRealtime()
                if (appAwake && networkAvailable && channelRetry.canAttempt(now - lastChannelsFetchedAtMillis)) {
                    fetchChannelsInternal()
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

    private suspend fun fetchChannelsInternal(queueIfRunning: Boolean = false) {
        try {
            channelRefreshRunner.run(queueIfRunning) {
                val recovering = channelRecoveryPending
                channelRecoveryPending = false
                var retry = 0
                val generation = wakeGeneration.value
                while (appAwake && networkAvailable && generation == wakeGeneration.value) {
                    val failure = fetchChannelsOnce()
                    if (failure == null || !recovering ||
                        failure.backendApiFailure()?.automaticallyRetryable == false ||
                        retry >= RECOVERY_RETRY_DELAYS_MS.size ||
                        !appAwake || generation != wakeGeneration.value
                    ) break
                    // A new sleep/wake cancels the old wait so queued recovery is not delayed.
                    withTimeoutOrNull(RECOVERY_RETRY_DELAYS_MS[retry++]) {
                        wakeGeneration.first { it != generation }
                    }
                }
            }
        } finally {
            // A queued reconnect must finish before the UI accepts a result from this flight.
            if (!channelRefreshRunner.isRunning) _isChannelsLoading.value = false
        }
    }

    private suspend fun fetchChannelsOnce(): Throwable? {
        val generation = wakeGeneration.value
        try {
            val response = withContext(ioDispatcher) { liveProvider.getChannels() }
            if (!appAwake || !networkAvailable || generation != wakeGeneration.value) return null
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

                nhkExclusionRepository.updateChannels(channels)

                filtered.filter { it.isDisplay }.groupBy { it.type }
            }

            val newRows = transformToUiState(processed)
            if (!appAwake || !networkAvailable || generation != wakeGeneration.value) return null
            val serverRecovered = _connectionError.value
            _connectionError.value = false
            if (serverRecovered) {
                _recoveryVersion.value++
                if (isRecordingPollingEnabled) refreshRecentRecordings(afterRecovery = true)
            }
            _channelError.value = null
            channelRetry.reset()
            _sourceErrors.value = response.sourceErrors
            if (_groupedChannels.value != processed) {
                _groupedChannels.value = processed
            }
            if (_liveRows.value != newRows) {
                _liveRows.value = newRows
            }

            return null

        } catch (e: CancellationException) {
            Log.d(TAG, "fetchChannelsInternal cancelled")
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching channels", e)
            if (!appAwake || !networkAvailable || generation != wakeGeneration.value) return null
            _connectionError.value = true
            _channelError.value = e.backendApiFailure()?.let { failure ->
                "${failure.reason}（${failure.safeCode} / HTTP ${failure.status}）接続設定を確認するか、再試行してください。"
            } ?: userFacingFailure(e, "CHANNELS_LOAD_FAILED").let { failure ->
                "${failure.message}（${failure.code}）"
            }
            channelRetry.failed(e)
            return e
        } finally {
            lastChannelsFetchedAtMillis = elapsedRealtime()
        }
    }

    private suspend fun fetchRecentRecordingsInternal(queueIfRunning: Boolean = false) {
        try {
            recordingRefreshRunner.run(queueIfRunning) {
                val recovering = recordingRecoveryPending
                recordingRecoveryPending = false
                val generation = wakeGeneration.value
                var retry = 0
                while (appAwake && networkAvailable && generation == wakeGeneration.value) {
                    val failure = fetchRecentRecordingsOnce()
                    if (failure == null || !recovering ||
                        failure.backendApiFailure()?.automaticallyRetryable == false ||
                        retry >= RECOVERY_RETRY_DELAYS_MS.size ||
                        !appAwake || !networkAvailable || generation != wakeGeneration.value
                    ) break
                    withTimeoutOrNull(RECOVERY_RETRY_DELAYS_MS[retry++]) {
                        wakeGeneration.first { it != generation }
                    }
                }
            }
        } finally {
            if (!recordingRefreshRunner.isRunning) _isRecordingsLoading.value = false
        }
    }

    private suspend fun fetchRecentRecordingsOnce(): Throwable? {
        if (!appAwake || !networkAvailable) return null
        val generation = wakeGeneration.value
        try {
            val firstPage = withContext(ioDispatcher) {
                val raw = mutableListOf<RecordedProgram>()
                var page = 1
                do {
                    val response = recordProvider.getRecordedPrograms(page = page, order = "desc")
                    raw.addAll(response.recordedPrograms)
                    if (response.recordedPrograms.isEmpty() || raw.size >= response.total) break
                    page++
                } while (raw.count { !nhkExclusionRepository.isExcluded(it) } < 20)
                raw.toList()
            }
            if (!appAwake || !networkAvailable || generation != wakeGeneration.value) return null
            _recordingError.value = null
            recordingRetry.reset()
            if (_recentRecordings.value != firstPage) {
                _recentRecordings.value = firstPage
            }
            return null
        } catch (e: CancellationException) {
            Log.d(TAG, "fetchRecentRecordingsInternal cancelled")
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Error fetching recent recordings", e)
            if (!appAwake || !networkAvailable || generation != wakeGeneration.value) return null
            _recordingError.value = e.backendApiFailure()?.displayText ?: e.message ?: "録画番組を取得できませんでした。接続を確認して再試行してください。"
            recordingRetry.failed(e)
            return e
        } finally {
            lastRecordingsFetchedAtMillis = elapsedRealtime()
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
