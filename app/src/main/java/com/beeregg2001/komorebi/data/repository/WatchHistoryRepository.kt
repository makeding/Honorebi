package com.beeregg2001.komorebi.data.repository

import android.os.Build
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.api.HistorySyncBackendTarget
import com.beeregg2001.komorebi.data.local.dao.LastChannelDao
import com.beeregg2001.komorebi.data.local.dao.WatchHistoryDao
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.WatchedHistoryItem
import com.beeregg2001.komorebi.data.model.WatchedHistoryPayload
import com.beeregg2001.komorebi.data.model.HistoryPlaybackProgress
import com.beeregg2001.komorebi.data.model.LiveHistoryPlaybackProgress
import com.beeregg2001.komorebi.data.auth.HonomiSessionStore
import com.beeregg2001.komorebi.data.auth.HonomiSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchHistoryRepository @Inject constructor(
    private val apiService: KonomiApi,
    private val watchHistoryDao: WatchHistoryDao,
    private val lastChannelDao: LastChannelDao,
    private val konomiRepository: KonomiRepository,
    private val sessionStore: HonomiSessionStore,
) {
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingSaveJobs = ConcurrentHashMap<Int, Job>()
    private val pendingLiveSaveJobs = ConcurrentHashMap<String, Job>()

    private fun target(session: HonomiSession) =
        HistorySyncBackendTarget(session.origin, "Bearer ${session.token}")

    @RequiresApi(Build.VERSION_CODES.O)
    fun getWatchHistoryFlow(): Flow<List<KonomiHistoryProgram>> {
        return watchHistoryDao.getAllHistory().map { entities ->
            entities.map { KonomiDataMapper.toUiModel(it) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun refreshHistoryFromApi() {
        val session = sessionStore.current() ?: return
        runCatching { apiService.getSyncedWatchHistory(target(session)) }.onSuccess {
            if (sessionStore.isCurrent(session)) applyRemoteHistory(it.items)
        }
    }

    suspend fun saveWatchHistory(program: RecordedProgram, positionSeconds: Double, durationSeconds: Double? = null) {
        // 1. ローカルDBに保存
        val entity = KonomiDataMapper.toEntity(program, positionSeconds)
        watchHistoryDao.insertOrUpdate(entity)

        val session = sessionStore.current()
        if (session != null) {
            val now = entity.watchedAt / 1000.0
            val duration = durationSeconds ?: program.recordedVideo.duration.takeUnless { program.requiresRawMmtsPlayback }
            val progress = duration?.takeIf { it.isFinite() && it > 0.0 && !program.isRecording && program.recordedVideo.status == "Recorded" }
                ?.let { HistoryPlaybackProgress(program.id, positionSeconds, it) }
            runCatching {
                apiService.updateSyncedWatchHistory(
                    WatchedHistoryPayload(listOf(WatchedHistoryItem(program.id, positionSeconds, now, now)), progress),
                    target(session),
                )
            }.onSuccess { response -> if (sessionStore.isCurrent(session)) applyRemoteHistory(response.items) }
        }
    }

    suspend fun syncWithServer() {
        val session = sessionStore.current() ?: return
        val previousOwner = sessionStore.historyOwner()
        if (previousOwner != null && !sessionStore.isHistoryOwner(session)) {
            val remoteItems = apiService.getSyncedWatchHistory(target(session)).items
            if (!sessionStore.isCurrent(session)) return
            watchHistoryDao.clearAll()
            applyRemoteHistory(remoteItems)
            sessionStore.markHistoryOwner(session)
            return
        }
        val localItems = watchHistoryDao.getAllHistoryOnce().map {
            WatchedHistoryItem(
                videoId = it.id,
                playbackPosition = it.playbackPosition,
                createdAt = it.watchedAt / 1000.0,
                updatedAt = it.watchedAt / 1000.0,
            )
        }
        val merged = apiService.updateSyncedWatchHistory(WatchedHistoryPayload(localItems), target(session))
        if (!sessionStore.isCurrent(session)) return
        applyRemoteHistory(merged.items)
        sessionStore.markHistoryOwner(session)
    }

    private suspend fun applyRemoteHistory(items: List<WatchedHistoryItem>) {
        items.forEach { remote ->
            val watchedAt = (remote.updatedAt * 1000).toLong()
            val local = watchHistoryDao.getById(remote.videoId)
            if (local != null && watchedAt > local.watchedAt) {
                watchHistoryDao.insertOrUpdate(
                    local.copy(playbackPosition = remote.playbackPosition, watchedAt = watchedAt)
                )
            } else if (local == null) {
                konomiRepository.getRecordedProgram(remote.videoId).getOrNull()?.let { program ->
                    watchHistoryDao.insertOrUpdate(
                        KonomiDataMapper.toEntity(program, remote.playbackPosition).copy(watchedAt = watchedAt)
                    )
                }
            }
        }
    }

    /**
     * Persists the latest position independently from a player/ViewModel lifecycle.
     * Jobs are conflated per programme so switching programmes cannot cancel the
     * final checkpoint of the programme being left.
     */
    fun enqueueWatchHistorySave(program: RecordedProgram, positionSeconds: Double, durationSeconds: Double? = null) {
        val programId = program.id
        val job = saveScope.launch(start = CoroutineStart.LAZY) {
            saveWatchHistory(program, positionSeconds, durationSeconds)
        }
        pendingSaveJobs.put(programId, job)?.cancel()
        job.invokeOnCompletion { pendingSaveJobs.remove(programId, job) }
        job.start()
    }

    /**
     * Sends one observed live media wall-clock through the ordinary history endpoint.
     * The captured session also pins the request to its original backend, so a delayed
     * lifecycle save cannot be authenticated as a later account or sent to a new server.
     */
    fun createLiveWatchHistorySaver(): ((LiveHistoryPlaybackProgress) -> Unit)? {
        val session = sessionStore.current() ?: return null
        return { progress -> enqueueLiveWatchHistorySave(session, progress) }
    }

    private fun enqueueLiveWatchHistorySave(session: HonomiSession, progress: LiveHistoryPlaybackProgress) {
        val key = "${session.origin}|${session.userId}|${progress.channel_id}"
        val job = saveScope.launch(start = CoroutineStart.LAZY) {
            if (!sessionStore.isCurrent(session)) return@launch
            runCatching {
                apiService.updateSyncedWatchHistory(
                    WatchedHistoryPayload(items = emptyList(), live_playback_progress = progress),
                    target(session),
                )
            }.onSuccess { response ->
                if (sessionStore.isCurrent(session)) applyRemoteHistory(response.items)
            }
        }
        pendingLiveSaveJobs.put(key, job)?.cancel()
        job.invokeOnCompletion { pendingLiveSaveJobs.remove(key, job) }
        job.start()
    }

    // ==========================================
    // ★ 追加: KonomiRepository から移行したローカルDB操作
    // ==========================================

    /**
     * ローカルDBにキャッシュされている視聴履歴（レジュームポイント）をFlowとして取得します。
     */
    fun getLocalWatchHistory(): Flow<List<WatchHistoryEntity>> = watchHistoryDao.getAllHistory()

    suspend fun getHistoryEntityById(id: Int): WatchHistoryEntity? {
        return watchHistoryDao.getById(id)
    }

    /**
     * アプリ内で最後に視聴したチャンネル（放送局）のリストをローカルDBから取得します。
     */
    fun getLastChannels() = lastChannelDao.getLastChannels()

    /**
     * 複数の録画番組の視聴履歴をローカルDBから一括で取得します。
     * 同期時の差分チェックなどでパフォーマンスを向上させるために使用します。
     */
    suspend fun getHistoryEntitiesByIds(ids: List<Int>): List<WatchHistoryEntity> {
        return watchHistoryDao.getByIds(ids)
    }

    suspend fun saveToLocalHistory(entity: WatchHistoryEntity) {
        watchHistoryDao.insertOrUpdate(entity)
    }

    /**
     * サーバーから取得した最新の視聴履歴リストをローカルDBへ一括保存します。
     */
    suspend fun saveAllToLocalHistory(entities: List<WatchHistoryEntity>) {
        watchHistoryDao.insertOrUpdateAll(entities)
    }

    // ★追加: 履歴の全削除
    suspend fun clearWatchHistory() {
        watchHistoryDao.clearAll()
    }
}
