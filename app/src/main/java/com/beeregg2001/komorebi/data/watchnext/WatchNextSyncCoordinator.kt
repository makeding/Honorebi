package com.beeregg2001.komorebi.data.watchnext

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.repository.AppContentStore
import com.beeregg2001.komorebi.data.repository.NHKExclusionRepository
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.beeregg2001.komorebi.data.repository.WatchHistoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@RequiresApi(Build.VERSION_CODES.O)
@Singleton
class WatchNextSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchHistoryRepository: WatchHistoryRepository,
    private val appContentStore: AppContentStore,
    private val recordProvider: RecordProvider,
    private val nhkExclusionRepository: NHKExclusionRepository,
    private val settingsRepository: SettingsRepository,
    private val publisher: WatchNextPublisher,
    private val artworkCache: WatchNextArtworkCache,
) {
    private val started = AtomicBoolean(false)
    private val syncMutex = Mutex()
    private var lastBackendType: String? = null
    private var cachedFallbackRecordings: List<RecordedProgram>? = null
    private var cachedFallbackFetchedAtMillis: Long = 0L
    private var cachedFallbackFetchFailed: Boolean = false
    private val programLookupCache = object : LinkedHashMap<Int, RecordedProgram>(
        PROGRAM_LOOKUP_CACHE_SIZE,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RecordedProgram>?): Boolean =
            size > PROGRAM_LOOKUP_CACHE_SIZE
    }
    private val missingProgramLookupCache = object : LinkedHashMap<Int, Long>(
        PROGRAM_LOOKUP_CACHE_SIZE,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Long>?): Boolean =
            size > PROGRAM_LOOKUP_CACHE_SIZE
    }
    private val seriesCandidateCache = object : LinkedHashMap<String, CachedSeries>(
        SERIES_CANDIDATE_CACHE_SIZE,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSeries>?): Boolean =
            size > SERIES_CANDIDATE_CACHE_SIZE
    }

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (!publisher.isSupported()) return
        if (!started.compareAndSet(false, true)) return

        scope.launch(Dispatchers.IO) {
            combine(
                watchHistoryRepository.getLocalWatchHistory(),
                appContentStore.recentRecordings,
                nhkExclusionRepository.state
                    .filter { it.isLoaded }
                    .map { it.isActive to it.channelRevision }
                    .distinctUntilChanged(),
                settingsRepository.backendType.distinctUntilChanged(),
                settingsRepository.deepTvFeatureSettings
                    .map { it.watchNextEnabled to it.nextEpisodeActive }
                    .distinctUntilChanged(),
            ) { history, recentRecordings, (exclusionActive, _), backendType, (watchNextEnabled, nextEpisodeActive) ->
                SyncSnapshot(
                    history = history,
                    recentRecordings = recentRecordings,
                    exclusionActive = exclusionActive,
                    backendType = backendType,
                    watchNextEnabled = watchNextEnabled,
                    nextEpisodeActive = nextEpisodeActive,
                )
            }
                .debounce(SYNC_DEBOUNCE_MILLIS)
                .collectLatest { snapshot ->
                    syncMutex.withLock {
                        try {
                            syncOnce(snapshot)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            Log.w(TAG, "WatchNext sync failed: ${t.message}", t)
                        }
                    }
                }
        }
    }

    private suspend fun syncOnce(snapshot: SyncSnapshot) {
        val backendChanged = lastBackendType != null && lastBackendType != snapshot.backendType
        if (lastBackendType != snapshot.backendType) {
            lastBackendType = snapshot.backendType
            programLookupCache.clear()
            missingProgramLookupCache.clear()
            seriesCandidateCache.clear()
            cachedFallbackRecordings = null
            cachedFallbackFetchedAtMillis = 0L
            cachedFallbackFetchFailed = false
        }

        if (snapshot.backendType.isBlank() || snapshot.backendType == "TIF") {
            publisher.clearAll()
            artworkCache.prune(emptySet())
            return
        }

        if (!snapshot.watchNextEnabled) {
            publisher.clearAll()
            artworkCache.prune(emptySet())
            return
        }

        val nowMillis = System.currentTimeMillis()
        var fetchFailed = false
        val recordings: List<RecordedProgram> = if (snapshot.recentRecordings.isNotEmpty()) {
            cachedFallbackRecordings = null
            cachedFallbackFetchedAtMillis = 0L
            cachedFallbackFetchFailed = false
            snapshot.recentRecordings
        } else if (
            cachedFallbackFetchedAtMillis > 0L &&
            (nowMillis - cachedFallbackFetchedAtMillis) in 0L until FALLBACK_CACHE_TTL_MILLIS
        ) {
            fetchFailed = cachedFallbackFetchFailed
            cachedFallbackRecordings.orEmpty()
        } else {
            try {
                val fetched = recordProvider.getRecordedPrograms(page = 1, order = "desc").recordedPrograms
                cachedFallbackRecordings = fetched
                cachedFallbackFetchedAtMillis = nowMillis
                cachedFallbackFetchFailed = false
                fetched
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to fetch recent recordings for WatchNext: ${t.message}")
                cachedFallbackRecordings = emptyList()
                cachedFallbackFetchedAtMillis = nowMillis
                cachedFallbackFetchFailed = true
                fetchFailed = true
                emptyList()
            }
        }

        val lookup = LinkedHashMap<Int, RecordedProgram>()
        lookup.putAll(programLookupCache)
        for (program in recordings) {
            lookup[program.id] = program
            programLookupCache[program.id] = program
            missingProgramLookupCache.remove(program.id)
        }

        val historyInputs = snapshot.history.map { entity ->
            WatchNextHistoryInput(
                id = entity.id,
                title = entity.title,
                durationSeconds = entity.duration,
                positionSeconds = entity.playbackPosition,
                watchedAtMillis = entity.watchedAt,
            )
        }

        val missingInProgressIds = historyInputs
            .sortedWith(
                compareByDescending<WatchNextHistoryInput> { it.watchedAtMillis }
                    .thenByDescending { it.id }
            )
            .distinctBy { it.id }
            .filter { entry ->
                val negativeCachedAt = missingProgramLookupCache[entry.id]
                val isNegativeCached = negativeCachedAt != null &&
                    (nowMillis - negativeCachedAt) in 0L until FALLBACK_CACHE_TTL_MILLIS
                entry.id !in lookup &&
                    !isNegativeCached &&
                    WatchProgressPolicy.isInProgress(
                        entry.positionSeconds,
                        entry.durationSeconds.takeIf { it.isFinite() && it > 0.0 },
                    )
            }
            .take(MAX_MISSING_PROGRAM_LOOKUPS)
            .map { it.id }

        var remainingProgramLookups = MAX_MISSING_PROGRAM_LOOKUPS
        for (id in missingInProgressIds) {
            remainingProgramLookups--
            try {
                val program = recordProvider.getRecordedProgram(id).getOrNull()
                if (program != null) {
                    lookup[id] = program
                    programLookupCache[id] = program
                    missingProgramLookupCache.remove(id)
                } else {
                    missingProgramLookupCache[id] = nowMillis
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                missingProgramLookupCache[id] = nowMillis
                Log.w(TAG, "Failed to lookup program $id for WatchNext: ${t.message}")
            }
        }

        val nextResolution = if (snapshot.nextEpisodeActive) {
            resolveNextEpisodes(
                historyInputs = historyInputs,
                lookup = lookup,
                recordings = recordings,
                exclusionActive = snapshot.exclusionActive,
                nowMillis = nowMillis,
                remainingProgramLookups = remainingProgramLookups,
            )
        } else {
            NextEpisodeResolution(inputs = emptyList(), fetchFailed = false)
        }

        val candidates = WatchNextPlanner.plan(
            history = historyInputs,
            recordings = recordings,
            lookup = lookup,
            exclusionActive = snapshot.exclusionActive,
            isExcluded = nhkExclusionRepository::isExcluded,
            nowMillis = System.currentTimeMillis(),
            nextEpisodes = nextResolution.inputs,
        )

        val posterUris = HashMap<Int, Uri?>(candidates.size)
        for (candidate in candidates) {
            val cached = artworkCache.ensure(candidate.recordedProgramId, candidate.thumbnailUrls)
            posterUris[candidate.recordedProgramId] = if (cached) {
                WatchNextArtworkCache.posterUri(context, candidate.recordedProgramId)
            } else {
                null
            }
        }

        val existing = publisher.queryExisting()
        val ops = WatchNextOps.planOps(
            existing = existing,
            desired = candidates,
            preserveNewRows = fetchFailed && !snapshot.exclusionActive && !backendChanged,
            preserveNextRows = nextResolution.fetchFailed && !snapshot.exclusionActive && !backendChanged,
        )
        publisher.apply(ops, posterUris)

        val deletedRowIds = ops.filterIsInstance<WatchNextOp.Delete>().mapTo(HashSet()) { it.rowId }
        val keepArtworkIds = buildSet {
            for (op in ops) {
                when (op) {
                    is WatchNextOp.Insert -> add(op.candidate.recordedProgramId)
                    is WatchNextOp.Update -> add(op.candidate.recordedProgramId)
                    is WatchNextOp.Delete -> Unit
                }
            }
            for (row in existing) {
                if (row.rowId !in deletedRowIds && row.browsable) {
                    WatchNextOps.parseRecordedProgramId(row.internalId)?.let(::add)
                }
            }
        }
        artworkCache.prune(keepArtworkIds)
    }

    private suspend fun resolveNextEpisodes(
        historyInputs: List<WatchNextHistoryInput>,
        lookup: MutableMap<Int, RecordedProgram>,
        recordings: List<RecordedProgram>,
        exclusionActive: Boolean,
        nowMillis: Long,
        remainingProgramLookups: Int,
    ): NextEpisodeResolution {
        val sortedHistory = historyInputs
            .sortedWith(
                compareByDescending<WatchNextHistoryInput> { it.watchedAtMillis }
                    .thenByDescending { it.id }
            )
            .distinctBy { it.id }
        val historyById = sortedHistory.associateBy { it.id }
        val cutoffMillis = nowMillis - COMPLETED_SOURCE_WINDOW_MILLIS

        val completedSources = sortedHistory
            .filter { entry ->
                if (entry.id <= 0 || entry.watchedAtMillis < cutoffMillis) return@filter false
                val effectiveDuration = entry.durationSeconds.takeIf { it.isFinite() && it > 0.0 }
                    ?: lookup[entry.id]?.let { WatchProgressPolicy.effectiveDurationSeconds(it) }
                WatchProgressPolicy.isCompleted(entry.positionSeconds, effectiveDuration)
            }
            .take(MAX_COMPLETED_SOURCE_LOOKUPS)

        var nextFetchFailed = false
        var lookupsLeft = remainingProgramLookups
        for (entry in completedSources) {
            if (lookupsLeft <= 0) break
            if (entry.id in lookup) continue
            val negativeCachedAt = missingProgramLookupCache[entry.id]
            val isNegativeCached = negativeCachedAt != null &&
                (nowMillis - negativeCachedAt) in 0L until FALLBACK_CACHE_TTL_MILLIS
            if (isNegativeCached) continue

            lookupsLeft--
            try {
                val result = recordProvider.getRecordedProgram(entry.id)
                val program = result.getOrNull()
                if (program != null) {
                    lookup[entry.id] = program
                    programLookupCache[entry.id] = program
                    missingProgramLookupCache.remove(entry.id)
                } else {
                    missingProgramLookupCache[entry.id] = nowMillis
                    if (result.isFailure) {
                        nextFetchFailed = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                missingProgramLookupCache[entry.id] = nowMillis
                nextFetchFailed = true
                Log.w(TAG, "Failed to lookup completed program ${entry.id} for WatchNext NEXT: ${t.message}")
            }
        }

        val seriesSources = completedSources
            .mapNotNull { entry ->
                val program = lookup[entry.id] ?: return@mapNotNull null
                if (!NextEpisodeResolver.isSeriesEpisode(program)) return@mapNotNull null
                if (exclusionActive && program.channel == null) return@mapNotNull null
                if (nhkExclusionRepository.isExcluded(program)) return@mapNotNull null
                val key = NextEpisodeResolver.seriesKey(program)
                if (key.isBlank()) return@mapNotNull null
                Triple(entry, program, key)
            }
            .distinctBy { it.third }
            .take(WatchNextPlanner.MAX_NEXT_ITEMS)

        val nextInputs = mutableListOf<WatchNextNextInput>()
        for ((entry, program, seriesKey) in seriesSources) {
            val (seriesPrograms, seriesFailed) = fetchSeriesCandidates(program, seriesKey, nowMillis)
            if (seriesFailed) {
                nextFetchFailed = true
            }
            val mergedCandidates = recordings + seriesPrograms
            val nextProgram = NextEpisodeResolver.resolveNext(
                source = program,
                candidates = mergedCandidates,
                history = historyById,
                exclusionActive = exclusionActive,
                isExcluded = nhkExclusionRepository::isExcluded,
            )
            if (nextProgram != null) {
                nextInputs += WatchNextNextInput(
                    sourceWatchedAtMillis = entry.watchedAtMillis,
                    program = nextProgram,
                )
            }
        }

        return NextEpisodeResolution(
            inputs = nextInputs,
            fetchFailed = nextFetchFailed,
        )
    }

    private suspend fun fetchSeriesCandidates(
        source: RecordedProgram,
        seriesKey: String,
        nowMillis: Long,
    ): Pair<List<RecordedProgram>, Boolean> {
        val cached = seriesCandidateCache[seriesKey]
        if (cached != null) {
            val ttl = if (cached.failed) SERIES_CACHE_FAILURE_TTL_MILLIS else SERIES_CACHE_TTL_MILLIS
            if ((nowMillis - cached.fetchedAtMillis) in 0L until ttl) {
                return cached.programs to cached.failed
            }
        }

        val seriesId = source.seriesId
        if (seriesId != null) {
            val collected = mutableListOf<RecordedProgram>()
            return try {
                for (page in 1..MAX_SERIES_PAGES) {
                    val response = recordProvider.getRecordedProgramsBySeries(
                        seriesId = seriesId,
                        page = page,
                        order = "asc",
                    )
                    if (response.recordedPrograms.isEmpty()) break
                    collected += response.recordedPrograms
                    if (response.total > 0 && collected.size >= response.total) break
                }
                seriesCandidateCache[seriesKey] = CachedSeries(
                    programs = collected,
                    fetchedAtMillis = nowMillis,
                    failed = false,
                )
                collected to false
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to fetch series $seriesId candidates for WatchNext NEXT: ${t.message}")
                seriesCandidateCache[seriesKey] = CachedSeries(
                    programs = collected,
                    fetchedAtMillis = nowMillis,
                    failed = true,
                )
                collected to true
            }
        }

        val query = NextEpisodeResolver.seriesDisplayTitle(source)
        if (query.isBlank()) {
            seriesCandidateCache[seriesKey] = CachedSeries(
                programs = emptyList(),
                fetchedAtMillis = nowMillis,
                failed = false,
            )
            return emptyList<RecordedProgram>() to false
        }

        return try {
            val fetched = recordProvider.searchRecordedPrograms(
                keyword = query,
                page = 1,
                order = "desc",
            ).recordedPrograms
            seriesCandidateCache[seriesKey] = CachedSeries(
                programs = fetched,
                fetchedAtMillis = nowMillis,
                failed = false,
            )
            fetched to false
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to search '$query' candidates for WatchNext NEXT: ${t.message}")
            seriesCandidateCache[seriesKey] = CachedSeries(
                programs = emptyList(),
                fetchedAtMillis = nowMillis,
                failed = true,
            )
            emptyList<RecordedProgram>() to true
        }
    }

    private data class CachedSeries(
        val programs: List<RecordedProgram>,
        val fetchedAtMillis: Long,
        val failed: Boolean,
    )

    private data class NextEpisodeResolution(
        val inputs: List<WatchNextNextInput>,
        val fetchFailed: Boolean,
    )

    private data class SyncSnapshot(
        val history: List<WatchHistoryEntity>,
        val recentRecordings: List<RecordedProgram>,
        val exclusionActive: Boolean,
        val backendType: String,
        val watchNextEnabled: Boolean,
        val nextEpisodeActive: Boolean,
    )

    private companion object {
        const val TAG = "WatchNext"
        const val SYNC_DEBOUNCE_MILLIS = 3_000L
        const val FALLBACK_CACHE_TTL_MILLIS = 15L * 60L * 1000L
        const val COMPLETED_SOURCE_WINDOW_MILLIS = 30L * 24L * 60L * 60L * 1000L
        const val MAX_COMPLETED_SOURCE_LOOKUPS = 6
        const val SERIES_CACHE_TTL_MILLIS = 15L * 60L * 1000L
        const val SERIES_CACHE_FAILURE_TTL_MILLIS = 5L * 60L * 1000L
        const val MAX_SERIES_PAGES = 3
        const val SERIES_CANDIDATE_CACHE_SIZE = 32
        const val MAX_MISSING_PROGRAM_LOOKUPS = 10
        const val PROGRAM_LOOKUP_CACHE_SIZE = 64
    }
}
