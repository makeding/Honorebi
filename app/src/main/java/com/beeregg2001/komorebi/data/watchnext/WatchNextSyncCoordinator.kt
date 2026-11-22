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
            ) { history, recentRecordings, (exclusionActive, _), backendType ->
                SyncSnapshot(
                    history = history,
                    recentRecordings = recentRecordings,
                    exclusionActive = exclusionActive,
                    backendType = backendType,
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
            cachedFallbackRecordings = null
            cachedFallbackFetchedAtMillis = 0L
            cachedFallbackFetchFailed = false
        }

        if (snapshot.backendType.isBlank() || snapshot.backendType == "TIF") {
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

        for (id in missingInProgressIds) {
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

        val candidates = WatchNextPlanner.plan(
            history = historyInputs,
            recordings = recordings,
            lookup = lookup,
            exclusionActive = snapshot.exclusionActive,
            isExcluded = nhkExclusionRepository::isExcluded,
            nowMillis = System.currentTimeMillis(),
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

    private data class SyncSnapshot(
        val history: List<WatchHistoryEntity>,
        val recentRecordings: List<RecordedProgram>,
        val exclusionActive: Boolean,
        val backendType: String,
    )

    private companion object {
        const val TAG = "WatchNext"
        const val SYNC_DEBOUNCE_MILLIS = 3_000L
        const val FALLBACK_CACHE_TTL_MILLIS = 15L * 60L * 1000L
        const val MAX_MISSING_PROGRAM_LOOKUPS = 10
        const val PROGRAM_LOOKUP_CACHE_SIZE = 64
    }
}
