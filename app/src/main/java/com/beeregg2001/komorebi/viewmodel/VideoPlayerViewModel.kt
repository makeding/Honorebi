package com.beeregg2001.komorebi.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.CmSection
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import com.beeregg2001.komorebi.ui.video.player.ChapterInfo
import com.beeregg2001.komorebi.util.TitleNormalizer
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

data class QuickVideoCandidates(
    val seriesPrograms: List<RecordedProgram> = emptyList(),
    val recentPrograms: List<RecordedProgram> = emptyList(),
    val sourceProgramId: Int = 0,
    val seriesKey: String = "",
    val fetchedAtMillis: Long = 0L,
    val isLoading: Boolean = false
)

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    private val recordProvider: RecordProvider,
    private val historyRepository: WatchHistoryRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "VideoPlayerViewModel"
        private const val QUICK_VIDEO_CACHE_TTL_MS = 60_000L
        private const val QUICK_VIDEO_LIMIT = 48
    }

    private val gson = Gson()

    private val _programDetail = MutableStateFlow<RecordedProgram?>(null)
    val programDetail: StateFlow<RecordedProgram?> = _programDetail.asStateFlow()

    private val _tiledThumbnailUrl = MutableStateFlow<String?>(null)
    val tiledThumbnailUrl: StateFlow<String?> = _tiledThumbnailUrl.asStateFlow()

    private val _chapters = MutableStateFlow<List<ChapterInfo>>(emptyList())
    val chapters: StateFlow<List<ChapterInfo>> = _chapters.asStateFlow()

    // ★ 追加: 外部ファイルなどから取得したチャプター情報を保持するStateFlow
    private val _externalChapters = MutableStateFlow<List<ChapterInfo>>(emptyList())
    val externalChapters: StateFlow<List<ChapterInfo>> = _externalChapters.asStateFlow()

    private val _isLiveStream = MutableStateFlow(false)
    val isLiveStream: StateFlow<Boolean> = _isLiveStream.asStateFlow()

    private val _availableQualities =
        MutableStateFlow<List<StreamQuality>>(StreamQuality.DEFAULT_QUALITIES)
    val availableQualities: StateFlow<List<StreamQuality>> = _availableQualities.asStateFlow()

    private val _isQualitiesLoaded = MutableStateFlow(false)
    val isQualitiesLoaded: StateFlow<Boolean> = _isQualitiesLoaded.asStateFlow()

    private val _quickVideoCandidates = MutableStateFlow(QuickVideoCandidates())
    val quickVideoCandidates: StateFlow<QuickVideoCandidates> =
        _quickVideoCandidates.asStateFlow()

    private var detailFetchJob: Job? = null
    private var streamMaintenanceJob: Job? = null
    private var quickVideoFetchJob: Job? = null
    private val quickVideoCache = mutableMapOf<String, QuickVideoCandidates>()

    fun fetchAvailableQualities() {
        viewModelScope.launch(Dispatchers.IO) {
            _isQualitiesLoaded.value = false
            try {
                val backend = settingsRepository.backendType.first()
                if (backend == "EDCB") {
                    val playMethod = settingsRepository.edcbRecordPlayMethod.first()
                    if (playMethod == "DIRECT") {
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
                                if (!list.isNullOrEmpty()) {
                                    _availableQualities.value = list
                                } else {
                                    fetchFromApiAndSave()
                                }
                            } catch (e: Exception) {
                                fetchFromApiAndSave()
                            }
                        } else {
                            fetchFromApiAndSave()
                        }
                    }
                } else if (backend == "KONOMITV") {
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
                Log.e(TAG, "Failed to load stream qualities from cache", e)
                val currentVideo = settingsRepository.videoQuality.first()
                _availableQualities.value = listOf(
                    StreamQuality(
                        label = "設定値 ($currentVideo)",
                        value = currentVideo,
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
            Log.i(TAG, "Cache empty. Fetching qualities from API.")
            val fetched = recordProvider.getStreamQualities()
            if (fetched.isNotEmpty()) {
                settingsRepository.saveString(
                    SettingsRepository.AVAILABLE_STREAM_QUALITIES,
                    gson.toJson(fetched)
                )
                _availableQualities.value = fetched
            } else {
                val currentVideo = settingsRepository.videoQuality.first()
                _availableQualities.value = listOf(
                    StreamQuality(
                        label = "設定値 ($currentVideo)",
                        value = currentVideo,
                        isRawTs = false
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch from API", e)
            val currentVideo = settingsRepository.videoQuality.first()
            _availableQualities.value = listOf(
                StreamQuality(
                    label = "設定値 ($currentVideo)",
                    value = currentVideo,
                    isRawTs = false
                )
            )
        }
    }

    fun saveVideoQuality(qualityValue: String) {
        viewModelScope.launch {
            settingsRepository.saveString(SettingsRepository.VIDEO_QUALITY, qualityValue)
        }
    }

    suspend fun resolveStreamUrl(
        videoId: Int,
        quality: String,
        sessionId: String,
        offsetSeconds: Double = 0.0,
        isRecording: Boolean = false
    ): String {
        return try {
            withContext(Dispatchers.IO) {
                val url =
                    recordProvider.getRecordStreamUrl(
                        videoId,
                        quality,
                        sessionId,
                        offsetSeconds,
                        isRecording
                    )
                _isLiveStream.value = url.contains("/api/xcode") && quality != "10"
                url
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve stream URL", e)
            ""
        }
    }

    fun fetchProgramDetail(videoId: Int) {
        detailFetchJob?.cancel()
        detailFetchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            recordProvider.getRecordedProgram(videoId).onSuccess { program ->
                _programDetail.value = program

                Log.i(TAG, "[DataCheck] Fetched Program Detail. Title: ${program.title}")
                Log.i(
                    TAG,
                    "[DataCheck] CM Sections from API: ${program.recordedVideo.cmSections?.size ?: 0} sections found."
                )

                val tileUrl = recordProvider.getTiledThumbnailUrl(videoId)
                _tiledThumbnailUrl.value = tileUrl

                val durationMs = (program.recordedVideo.duration * 1000).toLong()
                val cmSections = program.recordedVideo.cmSections ?: emptyList()
                _chapters.value = calculateChapters(durationMs, cmSections)

            }.onFailure { Log.e(TAG, "Failed to fetch program detail", it) }
        }
    }

    fun clearProgramDetail() {
        _programDetail.value = null
        _tiledThumbnailUrl.value = null
        _chapters.value = emptyList()
        _externalChapters.value = emptyList() // ★ 追加: 外部チャプター情報もクリア
        _isLiveStream.value = false
    }

    fun refreshQuickVideoCandidates(
        program: RecordedProgram,
        localRecentPrograms: List<RecordedProgram>,
        force: Boolean = false
    ) {
        val seriesTitle = quickSeriesDisplayTitle(program)
        val seriesKey = normalizeQuickSeriesKey(seriesTitle)
        val cacheKey = seriesKey.ifBlank { "program:${program.id}" }
        val now = System.currentTimeMillis()
        val localCandidates = QuickVideoCandidates(
            seriesPrograms = buildSeriesProgramList(program, localRecentPrograms, seriesKey, seriesTitle),
            recentPrograms = buildRecentProgramList(program, localRecentPrograms),
            sourceProgramId = program.id,
            seriesKey = seriesKey,
            fetchedAtMillis = 0L
        )
        val current = _quickVideoCandidates.value
        if (
            !force &&
            current.isLoading &&
            current.sourceProgramId == program.id &&
            current.seriesKey == seriesKey
        ) {
            return
        }
        val cached = quickVideoCache[cacheKey]

        if (cached != null && now - cached.fetchedAtMillis < QUICK_VIDEO_CACHE_TTL_MS) {
            _quickVideoCandidates.value = cached.copy(
                sourceProgramId = program.id,
                isLoading = false,
                seriesPrograms = buildSeriesProgramList(
                    program,
                    cached.seriesPrograms + localCandidates.seriesPrograms,
                    seriesKey,
                    seriesTitle
                ),
                recentPrograms = buildRecentProgramList(program, cached.recentPrograms + localCandidates.recentPrograms)
            )
            if (!force) return
        } else {
            _quickVideoCandidates.value = localCandidates.copy(isLoading = true)
        }

        quickVideoFetchJob?.cancel()
        quickVideoFetchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val seriesDeferred = async {
                    runQuickVideoFetch {
                        fetchSeriesProgramsForQuickMenu(program, seriesTitle, seriesKey)
                    }
                }
                val recentDeferred = async {
                    runQuickVideoFetch {
                        recordProvider.getRecordedPrograms(page = 1, order = "desc")
                            .recordedPrograms
                    }
                }

                val fetchedSeries = seriesDeferred.await().getOrElse {
                    Log.w(TAG, "Failed to fetch quick series programs", it)
                    localCandidates.seriesPrograms
                }
                val fetchedRecent = recentDeferred.await().getOrElse {
                    Log.w(TAG, "Failed to fetch latest recorded programs", it)
                    localCandidates.recentPrograms
                }
                val fresh = QuickVideoCandidates(
                    seriesPrograms = buildSeriesProgramList(
                        program,
                        fetchedSeries + localCandidates.seriesPrograms,
                        seriesKey,
                        seriesTitle
                    ),
                    recentPrograms = buildRecentProgramList(
                        program,
                        fetchedRecent + localCandidates.recentPrograms
                    ),
                    sourceProgramId = program.id,
                    seriesKey = seriesKey,
                    fetchedAtMillis = System.currentTimeMillis(),
                    isLoading = false
                )
                quickVideoCache[cacheKey] = fresh
                _quickVideoCandidates.value = fresh
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh quick video candidates", e)
                _quickVideoCandidates.value = localCandidates.copy(isLoading = false)
            }
        }
    }

    private suspend fun <T> runQuickVideoFetch(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private suspend fun fetchSeriesProgramsForQuickMenu(
        program: RecordedProgram,
        seriesTitle: String,
        seriesKey: String
    ): List<RecordedProgram> {
        val keywords = listOf(
            seriesTitle,
            program.seriesName?.trim().orEmpty(),
            TitleNormalizer.extractDisplayTitle(program.title),
            program.title
        )
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val fetched = mutableListOf<RecordedProgram>()
        for (keyword in keywords.take(3)) {
            val result = recordProvider.searchRecordedPrograms(
                keyword = keyword,
                page = 1,
                order = "desc"
            ).recordedPrograms
            fetched += result
            if (buildSeriesProgramList(program, fetched, seriesKey, seriesTitle).size >= QUICK_VIDEO_LIMIT) {
                break
            }
        }
        return fetched
    }

    private fun buildSeriesProgramList(
        program: RecordedProgram,
        candidates: List<RecordedProgram>,
        seriesKey: String,
        seriesTitle: String
    ): List<RecordedProgram> =
        (listOf(program) + candidates)
            .distinctBy { it.id }
            .filter { candidate ->
                val candidateTitle = quickSeriesDisplayTitle(candidate)
                val candidateKey = normalizeQuickSeriesKey(candidateTitle)
                candidate.id == program.id ||
                        (seriesKey.isNotBlank() && candidateKey == seriesKey) ||
                        (seriesTitle.isNotBlank() && candidate.title.contains(seriesTitle))
            }
            .sortedByDescending { it.startTime }
            .take(QUICK_VIDEO_LIMIT)

    private fun buildRecentProgramList(
        program: RecordedProgram,
        candidates: List<RecordedProgram>
    ): List<RecordedProgram> =
        (listOf(program) + candidates)
            .distinctBy { it.id }
            .sortedByDescending { it.startTime }
            .take(QUICK_VIDEO_LIMIT)

    private fun quickSeriesDisplayTitle(program: RecordedProgram): String {
        val seriesName = program.seriesName?.trim().orEmpty()
        return seriesName.ifBlank { TitleNormalizer.extractDisplayTitle(program.title) }
    }

    private fun normalizeQuickSeriesKey(value: String): String =
        value
            .trim()
            .replace(Regex("[\\s　]+"), "")
            .lowercase()

    private fun calculateChapters(
        durationMs: Long,
        cmSections: List<CmSection>
    ): List<ChapterInfo> {
        if (cmSections.isEmpty()) return emptyList()

        val sortedMs = cmSections.map {
            CmSection(it.startTime * 1000.0, it.endTime * 1000.0)
        }.sortedBy { it.startTime }

        val mergedCmSections = mutableListOf<CmSection>()
        var currentStart = sortedMs[0].startTime
        var currentEnd = sortedMs[0].endTime

        for (i in 1 until sortedMs.size) {
            val next = sortedMs[i]
            if (next.startTime <= currentEnd + 2000.0) {
                currentEnd = maxOf(currentEnd, next.endTime)
            } else {
                mergedCmSections.add(CmSection(currentStart, currentEnd))
                currentStart = next.startTime
                currentEnd = next.endTime
            }
        }
        mergedCmSections.add(CmSection(currentStart, currentEnd))

        val boundaries = mutableSetOf(0L, durationMs)
        mergedCmSections.forEach {
            boundaries.add(it.startTime.toLong())
            boundaries.add(it.endTime.toLong())
        }
        val sortedBoundaries = boundaries.sorted()

        val list = mutableListOf<ChapterInfo>()
        for (i in 0 until sortedBoundaries.size - 1) {
            val start = sortedBoundaries[i]
            val end = sortedBoundaries[i + 1]

            if (end - start < 1000 && i != sortedBoundaries.size - 2) continue

            val midPoint = (start + end) / 2
            val isCm = mergedCmSections.any { cm ->
                midPoint >= cm.startTime.toLong() && midPoint <= cm.endTime.toLong()
            }
            list.add(ChapterInfo(start, end, isCm))
        }

        Log.i(
            TAG,
            "[DataCheck] Calculated ${list.size} chapters. (CM count: ${list.count { it.isCm }})"
        )
        return list
    }

    suspend fun getArchivedComments(videoId: Int): List<ArchivedComment> {
        return withContext(Dispatchers.IO) {
            recordProvider.getArchivedJikkyo(videoId).getOrDefault(emptyList()).sortedBy { it.time }
        }
    }

    fun updateWatchHistory(program: RecordedProgram, positionSeconds: Double) {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.saveWatchHistory(program, positionSeconds)
        }
    }

    @UnstableApi
    fun startStreamMaintenance(
        program: RecordedProgram,
        quality: String,
        sessionId: String,
        currentStreamUrlProvider: () -> String? = { null }
    ) {
        streamMaintenanceJob?.cancel()
        streamMaintenanceJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val streamSession = resolveCurrentStreamSession(
                        currentStreamUrl = currentStreamUrlProvider(),
                        fallbackQuality = quality,
                        fallbackSessionId = sessionId
                    )
                    if (streamSession == null) {
                        delay(5000L)
                        continue
                    }

                    recordProvider.keepAlive(
                        videoId = program.recordedVideo.id,
                        sessionId = streamSession.sessionId,
                        quality = streamSession.quality
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send Keep-Alive", e)
                }
                delay(5000L)
            }
        }
    }

    private data class StreamSession(
        val quality: String,
        val sessionId: String
    )

    private fun resolveCurrentStreamSession(
        currentStreamUrl: String?,
        fallbackQuality: String,
        fallbackSessionId: String
    ): StreamSession? {
        val fallback = StreamSession(
            quality = fallbackQuality,
            sessionId = fallbackSessionId
        ).takeIf { it.quality.isNotBlank() && it.sessionId.isNotBlank() }

        if (currentStreamUrl.isNullOrBlank()) {
            return fallback
        }

        return runCatching {
            val uri = Uri.parse(currentStreamUrl)
            val segments = uri.pathSegments
            val streamsIndex = segments.indexOf("streams")
            val videoIndex = if (
                streamsIndex >= 0 &&
                segments.getOrNull(streamsIndex + 1) == "video"
            ) {
                streamsIndex + 1
            } else {
                -1
            }
            val qualityFromUrl = if (videoIndex >= 0) {
                segments.getOrNull(videoIndex + 2)
            } else {
                null
            }
            val sessionIdFromUrl = uri.getQueryParameter("session_id")

            if (!qualityFromUrl.isNullOrBlank() && !sessionIdFromUrl.isNullOrBlank()) {
                StreamSession(qualityFromUrl, sessionIdFromUrl)
            } else {
                fallback
            }
        }.getOrElse {
            fallback
        }
    }

    fun stopStreamMaintenance() {
        streamMaintenanceJob?.cancel()
        streamMaintenanceJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopStreamMaintenance()
        detailFetchJob?.cancel()
        quickVideoFetchJob?.cancel()
    }
}
