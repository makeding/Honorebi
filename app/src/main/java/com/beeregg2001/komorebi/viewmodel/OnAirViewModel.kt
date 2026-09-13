package com.beeregg2001.komorebi.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.OnAirSeries
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.SeriesProgram
import com.beeregg2001.komorebi.data.repository.OnAirProvider
import com.beeregg2001.komorebi.data.repository.OnAirUnsupportedException
import com.google.gson.JsonParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.ZoneId
import javax.inject.Inject

private const val ON_AIR_UNSUPPORTED = "ON_AIR_UNSUPPORTED"

sealed interface OnAirLoadState {
    data object Idle : OnAirLoadState
    data object Loading : OnAirLoadState
    data object Ready : OnAirLoadState
    data class Error(val message: String, val code: String) : OnAirLoadState
}

data class OnAirExpandedSeries(
    val seriesId: Int,
    val summary: SeriesProgram? = null,
    val summaryStatus: OnAirLoadState = OnAirLoadState.Idle,
    /** Present only after every server page has been received. */
    val programs: List<RecordedProgram> = emptyList(),
    val programsStatus: OnAirLoadState = OnAirLoadState.Idle,
)

data class OnAirUiState(
    val backendSupported: Boolean = true,
    val selectedWeekday: Int = currentJapanWeekday(),
    val query: String = "",
    /** All server results, in stable display order. */
    val series: List<OnAirSeries> = emptyList(),
    val listStatus: OnAirLoadState = OnAirLoadState.Idle,
    val expanded: OnAirExpandedSeries? = null,
    val gridFirstVisibleIndex: Int = 0,
    val gridFirstVisibleOffset: Int = 0,
    val matrixHorizontalScroll: Int = 0,
    val matrixVerticalScroll: Int = 0,
    val summaryScroll: Int = 0,
    val summaryExpanded: Boolean = false,
    val focusedSeriesId: Int? = null,
    /** channelId:episode-slot:recordingId; IDs keep restoration stable across reordering. */
    val focusedEpisodeCellKey: String? = null,
) {
    /** Search applies to the full week; this count map keeps every weekday label honest. */
    val weekdayCounts: Map<Int, Int>
        get() = filteredSeries.groupingBy { it.weekday }.eachCount()

    val filteredSeries: List<OnAirSeries>
        get() = series.filter { it.title.contains(query.trim(), ignoreCase = true) }

    val visibleSeries: List<OnAirSeries>
        get() = filteredSeries.filter { it.weekday == selectedWeekday }
}

data class OnAirReturnFocus(
    val seriesId: Int?,
    val episodeCellKey: String?,
)

@HiltViewModel
class OnAirViewModel private constructor(
    private val onAirProvider: OnAirProvider,
    private val backendConfigurations: kotlinx.coroutines.flow.Flow<OnAirBackendConfiguration>,
) : ViewModel() {
    @Inject
    constructor(onAirProvider: OnAirProvider, settingsRepository: SettingsRepository) : this(
        onAirProvider,
        combine(
            settingsRepository.backendType,
            settingsRepository.konomiIp,
            settingsRepository.konomiPort,
            settingsRepository.cloudflareAccessConfiguration,
        ) { backend, ip, port, access -> OnAirBackendConfiguration(backend, ip, port, access.toString()) },
    )

    internal constructor(
        onAirProvider: OnAirProvider,
        backendConfigurations: kotlinx.coroutines.flow.Flow<OnAirBackendConfiguration>,
        testOnly: Unit = Unit,
    ) : this(onAirProvider, backendConfigurations)
    private val _uiState = MutableStateFlow(OnAirUiState())
    val uiState: StateFlow<OnAirUiState> = _uiState.asStateFlow()

    private var listJob: Job? = null
    private var summaryJob: Job? = null
    private var programsJob: Job? = null
    private val listRequestGate = OnAirRequestGate()
    private val detailSelectionGate = OnAirRequestGate()
    private val summaryRequestGate = OnAirRequestGate()
    private val programsRequestGate = OnAirRequestGate()
    private var isPageActive = true
    private var currentBackendConfiguration: OnAirBackendConfiguration? = null
    private var appliedBackendConfiguration: OnAirBackendConfiguration? = null

    init {
        viewModelScope.launch {
            backendConfigurations.distinctUntilChanged()
                .collect { configuration ->
                    currentBackendConfiguration = configuration
                    if (isPageActive && configuration.requiresReloadFrom(appliedBackendConfiguration)) applyBackendConfiguration(configuration)
                }
        }
    }

    /** Resume after navigating back from the player while retaining day, query, and expansion identity. */
    fun onEnterPage() {
        isPageActive = true
        val configuration = currentBackendConfiguration ?: return
        if (configuration.requiresReloadFrom(appliedBackendConfiguration)) {
            applyBackendConfiguration(configuration)
        } else {
            resumeInterruptedRequests()
        }
    }

    private fun applyBackendConfiguration(configuration: OnAirBackendConfiguration) {
        // A changed backend, host, port, or auth setting makes all in-flight data stale.
        cancelRequests()
        appliedBackendConfiguration = configuration
        if (configuration.backend != "KONOMITV") {
                    _uiState.value = _uiState.value.copy(
                        backendSupported = false,
                        series = emptyList(),
                        expanded = null,
                        gridFirstVisibleIndex = 0,
                        gridFirstVisibleOffset = 0,
                        matrixHorizontalScroll = 0,
                        matrixVerticalScroll = 0,
                        summaryScroll = 0,
                        summaryExpanded = false,
                        focusedSeriesId = null,
                        focusedEpisodeCellKey = null,
                        listStatus = OnAirLoadState.Error(
                            "このバックエンドは「放送中」に対応していません。接続設定で HonomiTV を選択してください。",
                            ON_AIR_UNSUPPORTED,
                        ),
                    )
        } else {
            _uiState.value = _uiState.value.copy(
                backendSupported = true, series = emptyList(), expanded = null,
                gridFirstVisibleIndex = 0, gridFirstVisibleOffset = 0, matrixHorizontalScroll = 0,
                matrixVerticalScroll = 0, summaryScroll = 0, summaryExpanded = false,
                focusedSeriesId = null, focusedEpisodeCellKey = null,
            )
            loadList()
        }
    }

    private fun resumeInterruptedRequests() {
        when (_uiState.value.listStatus) {
            OnAirLoadState.Loading, OnAirLoadState.Idle -> loadList()
            else -> Unit
        }
        _uiState.value.expanded?.let { expanded ->
            if (expanded.summaryStatus == OnAirLoadState.Loading) loadSummary(expanded.seriesId)
            if (expanded.programsStatus == OnAirLoadState.Loading) loadPrograms(expanded.seriesId)
        }
    }

    fun selectWeekday(weekday: Int) {
        require(weekday in 0..6) { "weekday must be Monday (0) through Sunday (6)" }
        _uiState.value = _uiState.value.copy(selectedWeekday = weekday)
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun saveGridScroll(index: Int, offset: Int) {
        _uiState.value = _uiState.value.copy(
            gridFirstVisibleIndex = index.coerceAtLeast(0),
            gridFirstVisibleOffset = offset.coerceAtLeast(0),
        )
    }

    fun saveMatrixHorizontalScroll(value: Int) {
        _uiState.value = _uiState.value.copy(matrixHorizontalScroll = value.coerceAtLeast(0))
    }

    fun saveMatrixVerticalScroll(value: Int) {
        _uiState.value = _uiState.value.copy(matrixVerticalScroll = value.coerceAtLeast(0))
    }

    fun saveSummaryScroll(value: Int) {
        _uiState.value = _uiState.value.copy(summaryScroll = value.coerceAtLeast(0))
    }

    fun setSummaryExpanded(expanded: Boolean) {
        _uiState.value = _uiState.value.copy(summaryExpanded = expanded)
    }

    fun saveFocusedSeries(id: Int?) {
        _uiState.value = _uiState.value.copy(focusedSeriesId = id)
    }

    fun saveFocusedEpisodeCell(key: String?) {
        _uiState.value = _uiState.value.copy(focusedEpisodeCellKey = key)
    }

    /** Return the persisted focus identity; the screen decides when to replace it. */
    fun consumeReturnFocus(): OnAirReturnFocus = OnAirReturnFocus(
        seriesId = _uiState.value.focusedSeriesId,
        episodeCellKey = _uiState.value.focusedEpisodeCellKey,
    )

    fun refresh() {
        loadList()
        _uiState.value.expanded?.seriesId?.let { seriesId ->
            loadSummary(seriesId, retainExisting = true)
            loadPrograms(seriesId, retainExisting = true)
        }
    }

    fun retryList() = loadList()

    fun expandSeries(seriesId: Int) {
        if (_uiState.value.expanded?.seriesId == seriesId) return
        summaryJob?.cancel()
        programsJob?.cancel()
        detailSelectionGate.next()
        _uiState.value = _uiState.value.copy(
            focusedSeriesId = seriesId,
            matrixHorizontalScroll = 0,
            matrixVerticalScroll = 0,
            summaryScroll = 0,
            summaryExpanded = false,
            focusedEpisodeCellKey = null,
            expanded = OnAirExpandedSeries(
                seriesId = seriesId,
                summaryStatus = OnAirLoadState.Loading,
                programsStatus = OnAirLoadState.Loading,
            ),
        )
        loadSummary(seriesId)
        loadPrograms(seriesId)
    }

    fun collapseSeries() {
        summaryJob?.cancel()
        programsJob?.cancel()
        detailSelectionGate.next()
        _uiState.value = _uiState.value.copy(expanded = null)
    }

    fun retrySummary() {
        _uiState.value.expanded?.seriesId?.let(::loadSummary)
    }

    fun retryPrograms() {
        _uiState.value.expanded?.seriesId?.let(::loadPrograms)
    }

    /** Call when this destination leaves composition so late replies cannot retake its focus. */
    fun onLeavePage() {
        isPageActive = false
        cancelRequests()
    }

    private fun loadList() {
        if (!_uiState.value.backendSupported) return
        listJob?.cancel()
        val generation = listRequestGate.next()
        _uiState.value = _uiState.value.copy(listStatus = OnAirLoadState.Loading)
        listJob = viewModelScope.launch {
            try {
                val series = onAirProvider.getOnAirSeries().seriesList.sortedWith(onAirSeriesComparator)
                if (listRequestGate.isCurrent(generation) && isPageActive) {
                    _uiState.value = _uiState.value.copy(
                        series = series,
                        expanded = _uiState.value.expanded?.takeIf { expanded -> series.any { it.id == expanded.seriesId } },
                        listStatus = OnAirLoadState.Ready,
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (listRequestGate.isCurrent(generation) && isPageActive) {
                    val unsupported = error is OnAirUnsupportedException || error.isUnsupportedOnAirEndpoint()
                    _uiState.value = _uiState.value.copy(
                        backendSupported = !unsupported,
                        listStatus = if (unsupported) OnAirLoadState.Error(
                            "このバックエンドは「放送中」に対応していません。接続設定で HonomiTV を選択してください。",
                            ON_AIR_UNSUPPORTED,
                        ) else error.toLoadError(),
                    )
                }
            }
        }
    }

    private fun loadSummary(seriesId: Int, retainExisting: Boolean = false) {
        summaryJob?.cancel()
        val selectionGeneration = detailSelectionGate.current()
        val generation = summaryRequestGate.next()
        updateExpanded(seriesId) { it.copy(summaryStatus = OnAirLoadState.Loading) }
        summaryJob = viewModelScope.launch {
            try {
                val summary = onAirProvider.getSeriesSummary(seriesId)
                if (detailSelectionGate.isCurrent(selectionGeneration) && summaryRequestGate.isCurrent(generation) && isPageActive) {
                    updateExpanded(seriesId) { it.copy(summary = summary, summaryStatus = OnAirLoadState.Ready) }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (detailSelectionGate.isCurrent(selectionGeneration) && summaryRequestGate.isCurrent(generation) && isPageActive) {
                    updateExpanded(seriesId) { it.copy(summaryStatus = error.toLoadError()) }
                }
            }
        }
    }

    private fun loadPrograms(seriesId: Int, retainExisting: Boolean = false) {
        programsJob?.cancel()
        val selectionGeneration = detailSelectionGate.current()
        val generation = programsRequestGate.next()
        updateExpanded(seriesId) {
            it.copy(programs = if (retainExisting) it.programs else emptyList(), programsStatus = OnAirLoadState.Loading)
        }
        programsJob = viewModelScope.launch {
            try {
                val allPrograms = loadEveryProgramPage(seriesId)
                if (detailSelectionGate.isCurrent(selectionGeneration) && programsRequestGate.isCurrent(generation) && isPageActive) {
                    updateExpanded(seriesId) { it.copy(programs = allPrograms, programsStatus = OnAirLoadState.Ready) }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                // Partial rows could falsely look like a complete episode matrix, so never publish them.
                if (detailSelectionGate.isCurrent(selectionGeneration) && programsRequestGate.isCurrent(generation) && isPageActive) {
                    updateExpanded(seriesId) {
                        it.copy(programs = if (retainExisting) it.programs else emptyList(), programsStatus = error.toLoadError())
                    }
                }
            }
        }
    }

    private suspend fun loadEveryProgramPage(seriesId: Int): List<RecordedProgram> {
        val programs = mutableListOf<RecordedProgram>()
        var page = 1
        var expectedTotal: Int? = null
        while (expectedTotal == null || programs.size < expectedTotal) {
            val response = onAirProvider.getRecordedProgramsBySeries(seriesId, page, "asc")
            if (expectedTotal == null) expectedTotal = response.total
            if (response.total != expectedTotal) throw IllegalStateException("録画一覧の件数が取得中に変わりました。再試行してください。")
            if (response.recordedPrograms.isEmpty() && programs.size < expectedTotal) {
                throw IllegalStateException("録画一覧を最後まで取得できませんでした。再試行してください。")
            }
            programs += response.recordedPrograms
            if (programs.mapTo(mutableSetOf()) { it.id }.size != programs.size) {
                throw IllegalStateException("録画一覧に重複した録画 ID が含まれています。再試行してください。")
            }
            if (programs.size > expectedTotal) {
                throw IllegalStateException("録画一覧のページング結果が不正です。再試行してください。")
            }
            page++
        }
        return programs
    }

    private fun updateExpanded(seriesId: Int, transform: (OnAirExpandedSeries) -> OnAirExpandedSeries) {
        val expanded = _uiState.value.expanded ?: return
        if (expanded.seriesId == seriesId) _uiState.value = _uiState.value.copy(expanded = transform(expanded))
    }

    private fun cancelRequests() {
        listRequestGate.next()
        detailSelectionGate.next()
        summaryRequestGate.next()
        programsRequestGate.next()
        listJob?.cancel(); listJob = null
        summaryJob?.cancel(); summaryJob = null
        programsJob?.cancel(); programsJob = null
    }
}

internal val onAirSeriesComparator: Comparator<OnAirSeries> = compareBy<OnAirSeries>(
    { it.broadcastTime }, { it.title }, { it.id },
)

internal fun currentJapanWeekday(now: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Asia/Tokyo"))): Int =
    now.withZoneSameInstant(ZoneId.of("Asia/Tokyo")).dayOfWeek.value - DayOfWeek.MONDAY.value

internal data class OnAirBackendConfiguration(
    val backend: String,
    val host: String,
    val port: String,
    val authFingerprint: String,
) {
    fun requiresReloadFrom(previous: OnAirBackendConfiguration?): Boolean = this != previous
}

/** A non-cooperative HTTP call may return after cancellation, so UI writes must prove freshness. */
internal class OnAirRequestGate {
    private var generation = 0L
    fun next(): Long = ++generation
    fun current(): Long = generation
    fun isCurrent(value: Long): Boolean = value == generation
}

private fun Throwable.toLoadError(): OnAirLoadState.Error = when (this) {
    is OnAirUnsupportedException -> OnAirLoadState.Error(message.orEmpty(), ON_AIR_UNSUPPORTED)
    is HttpException -> httpSafeError(this)
    else -> OnAirLoadState.Error(
        message ?: "放送中の情報を取得できませんでした。接続を確認して再試行してください。",
        "ON_AIR_REQUEST_FAILED",
    )
}

private fun Throwable.isUnsupportedOnAirEndpoint(): Boolean =
    this is HttpException && code() in setOf(404, 405, 501)

private fun httpSafeError(error: HttpException): OnAirLoadState.Error = runCatching {
    val raw = error.response()?.errorBody()?.string()?.trim().orEmpty()
    val json = raw.takeIf { it.startsWith('{') }?.let { JsonParser.parseString(it).asJsonObject }
    val detail = json?.get("detail")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
    val safeCode = json?.get("code")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        .ifBlank { "HTTP_${error.code()}" }
    OnAirLoadState.Error(
        detail.ifBlank { "サーバーから放送中の情報を取得できませんでした（HTTP ${error.code()}）。再試行してください。" },
        safeCode,
    )
}.getOrElse {
    OnAirLoadState.Error(
        "サーバーから放送中の情報を取得できませんでした（HTTP ${error.code()}）。再試行してください。",
        "HTTP_${error.code()}",
    )
}
