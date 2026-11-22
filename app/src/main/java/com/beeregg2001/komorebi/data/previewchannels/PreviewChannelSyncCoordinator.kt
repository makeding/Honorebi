package com.beeregg2001.komorebi.data.previewchannels

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.repository.AppContentStore
import com.beeregg2001.komorebi.data.repository.ChannelLogoCache
import com.beeregg2001.komorebi.data.repository.NHKExclusionRepository
import com.beeregg2001.komorebi.data.watchnext.PreviewArtworkKind
import com.beeregg2001.komorebi.data.watchnext.WatchNextArtworkCache
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
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
class PreviewChannelSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appContentStore: AppContentStore,
    private val nhkExclusionRepository: NHKExclusionRepository,
    private val settingsRepository: SettingsRepository,
    private val publisher: PreviewChannelPublisher,
    private val artworkCache: WatchNextArtworkCache,
    private val channelLogoCache: ChannelLogoCache,
) {
    private val started = AtomicBoolean(false)
    private val syncMutex = Mutex()
    private var lastBackendType: String? = null
    private var lastExclusionActive: Boolean? = null
    private var lastExclusionRevision: Int? = null

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (!publisher.isSupported()) return
        if (!started.compareAndSet(false, true)) return

        scope.launch(Dispatchers.IO) {
            combine(
                appContentStore.groupedChannels,
                appContentStore.recentRecordings,
                nhkExclusionRepository.state
                    .filter { it.isLoaded }
                    .map { it.isActive to it.channelRevision }
                    .distinctUntilChanged(),
                settingsRepository.backendType.distinctUntilChanged(),
                settingsRepository.deepTvFeatureSettings
                    .map { it.previewLiveEnabled to it.previewRecordingsEnabled }
                    .distinctUntilChanged(),
            ) { groupedChannels, recentRecordings, (exclusionActive, exclusionRevision), backendType, (liveEnabled, recordingsEnabled) ->
                SyncSnapshot(
                    groupedChannels = groupedChannels,
                    recentRecordings = recentRecordings,
                    exclusionActive = exclusionActive,
                    exclusionRevision = exclusionRevision,
                    backendType = backendType,
                    liveEnabled = liveEnabled,
                    recordingsEnabled = recordingsEnabled,
                )
            }
                .debounce(SYNC_DEBOUNCE_MILLIS)
                .collectLatest { snapshot ->
                    while (true) {
                        val nextDelayMillis = syncMutex.withLock {
                            safeSync(snapshot)
                        } ?: break
                        delay(nextDelayMillis)
                    }
                }
        }
    }

    private suspend fun safeSync(snapshot: SyncSnapshot): Long? = try {
        syncOnce(snapshot)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.w(TAG, "Preview channel sync failed: ${t.message}", t)
        null
    }

    private suspend fun syncOnce(snapshot: SyncSnapshot): Long? {
        val backendChanged = lastBackendType != null && lastBackendType != snapshot.backendType
        val exclusionJustActivated = lastExclusionActive == false && snapshot.exclusionActive
        val exclusionRevisionChangedWhileActive = snapshot.exclusionActive &&
            lastExclusionRevision != null &&
            lastExclusionRevision != snapshot.exclusionRevision
        val forceSyncEmpty = backendChanged || exclusionJustActivated || exclusionRevisionChangedWhileActive

        lastBackendType = snapshot.backendType
        lastExclusionActive = snapshot.exclusionActive
        lastExclusionRevision = snapshot.exclusionRevision

        if (snapshot.backendType.isBlank() || snapshot.backendType == "TIF") {
            publisher.clearAll()
            artworkCache.prunePreview(PreviewArtworkKind.LIVE, emptySet())
            artworkCache.prunePreview(PreviewArtworkKind.RECORDING, emptySet())
            return null
        }

        val nowMillis = System.currentTimeMillis()
        val liveCandidates = syncLive(
            snapshot = snapshot,
            nowMillis = nowMillis,
            forceSyncEmpty = forceSyncEmpty,
        )
        syncRecordings(
            snapshot = snapshot,
            nowMillis = nowMillis,
            forceSyncEmpty = forceSyncEmpty,
        )

        return if (snapshot.liveEnabled) {
            PreviewChannelPlanner.nextRefreshDelayMillis(liveCandidates, System.currentTimeMillis())
        } else {
            null
        }
    }

    private suspend fun syncLive(
        snapshot: SyncSnapshot,
        nowMillis: Long,
        forceSyncEmpty: Boolean,
    ): List<PreviewProgramCandidate> {
        if (!snapshot.liveEnabled) {
            publisher.deleteChannel(PreviewChannelKind.LIVE)
            artworkCache.prunePreview(PreviewArtworkKind.LIVE, emptySet())
            return emptyList()
        }

        val channels = snapshot.groupedChannels.values.flatten()
        if (channels.isEmpty() && !forceSyncEmpty) {
            return emptyList()
        }

        val planned = PreviewChannelPlanner.planLive(
            channels = channels,
            nowMillis = nowMillis,
            isExcluded = nhkExclusionRepository::isExcluded,
        )

        val posterUris = HashMap<String, Uri?>(planned.size)
        for (candidate in planned) {
            val urls = resolveLiveLogoUrls(candidate.sourceChannel)
            val cached = artworkCache.ensurePreview(
                kind = PreviewArtworkKind.LIVE,
                key = candidate.artworkKey,
                urls = urls,
            )
            posterUris[candidate.internalId] = if (cached) {
                WatchNextArtworkCache.previewPosterUri(
                    context = context,
                    kind = PreviewArtworkKind.LIVE,
                    key = candidate.artworkKey,
                )
            } else {
                null
            }
        }

        val channelId = publisher.ensureChannel(PreviewChannelKind.LIVE) ?: return planned
        val existing = publisher.queryPrograms(channelId)
        val ops = PreviewChannelOps.planOps(existing = existing, desired = planned)
        publisher.apply(channelId = channelId, ops = ops, posterUris = posterUris)

        val keepKeys = collectKeepArtworkKeys(
            kind = PreviewChannelKind.LIVE,
            existing = existing,
            ops = ops,
        )
        artworkCache.prunePreview(PreviewArtworkKind.LIVE, keepKeys)
        return planned
    }

    private suspend fun syncRecordings(
        snapshot: SyncSnapshot,
        nowMillis: Long,
        forceSyncEmpty: Boolean,
    ) {
        if (!snapshot.recordingsEnabled) {
            publisher.deleteChannel(PreviewChannelKind.RECORDINGS)
            artworkCache.prunePreview(PreviewArtworkKind.RECORDING, emptySet())
            return
        }

        if (snapshot.recentRecordings.isEmpty() && !forceSyncEmpty) {
            return
        }

        val planned = PreviewChannelPlanner.planRecordings(
            recordings = snapshot.recentRecordings,
            nowMillis = nowMillis,
            exclusionActive = snapshot.exclusionActive,
            isExcluded = nhkExclusionRepository::isExcluded,
        )

        val posterUris = HashMap<String, Uri?>(planned.size)
        for (candidate in planned) {
            val cached = artworkCache.ensurePreview(
                kind = PreviewArtworkKind.RECORDING,
                key = candidate.artworkKey,
                urls = candidate.artworkUrls,
            )
            posterUris[candidate.internalId] = if (cached) {
                WatchNextArtworkCache.previewPosterUri(
                    context = context,
                    kind = PreviewArtworkKind.RECORDING,
                    key = candidate.artworkKey,
                )
            } else {
                null
            }
        }

        val channelId = publisher.ensureChannel(PreviewChannelKind.RECORDINGS) ?: return
        val existing = publisher.queryPrograms(channelId)
        val ops = PreviewChannelOps.planOps(existing = existing, desired = planned)
        publisher.apply(channelId = channelId, ops = ops, posterUris = posterUris)

        val keepKeys = collectKeepArtworkKeys(
            kind = PreviewChannelKind.RECORDINGS,
            existing = existing,
            ops = ops,
        )
        artworkCache.prunePreview(PreviewArtworkKind.RECORDING, keepKeys)
    }

    private suspend fun resolveLiveLogoUrls(channel: Channel?): List<String> {
        if (channel == null) return emptyList()
        val resolved = try {
            channelLogoCache.getChannelLogoUrl(channel)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resolve channel logo for ${channel.displayChannelId}: ${t.message}")
            ""
        }.trim()
        return if (
            resolved.startsWith("http://", ignoreCase = true) ||
            resolved.startsWith("https://", ignoreCase = true)
        ) {
            listOf(resolved)
        } else {
            emptyList()
        }
    }

    private fun collectKeepArtworkKeys(
        kind: PreviewChannelKind,
        existing: List<ExistingPreviewProgram>,
        ops: List<PreviewProgramOp>,
    ): Set<String> {
        val deletedRowIds = ops.filterIsInstance<PreviewProgramOp.Delete>().mapTo(HashSet()) { it.rowId }
        return buildSet {
            for (op in ops) {
                when (op) {
                    is PreviewProgramOp.Insert -> add(op.candidate.artworkKey)
                    is PreviewProgramOp.Update -> add(op.candidate.artworkKey)
                    is PreviewProgramOp.Delete -> Unit
                }
            }
            for (row in existing) {
                if (row.rowId !in deletedRowIds && row.browsable) {
                    PreviewChannelOps.artworkKeyFromInternalId(kind, row.internalId)?.let(::add)
                }
            }
        }
    }

    private data class SyncSnapshot(
        val groupedChannels: Map<String, List<Channel>>,
        val recentRecordings: List<RecordedProgram>,
        val exclusionActive: Boolean,
        val exclusionRevision: Int,
        val backendType: String,
        val liveEnabled: Boolean,
        val recordingsEnabled: Boolean,
    )

    private companion object {
        const val TAG = "PreviewChannels"
        const val SYNC_DEBOUNCE_MILLIS = 3_000L
    }
}
