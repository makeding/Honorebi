package com.beeregg2001.komorebi.data.repository

import android.os.Build
import androidx.annotation.RequiresApi
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.local.dao.LastChannelDao
import com.beeregg2001.komorebi.data.local.dao.WatchHistoryDao
import com.beeregg2001.komorebi.data.local.entity.WatchHistoryEntity
import com.beeregg2001.komorebi.data.mapper.KonomiDataMapper
import com.beeregg2001.komorebi.data.model.KonomiHistoryProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.WatchedHistoryItem
import com.beeregg2001.komorebi.data.model.WatchedHistoryPayload
import com.beeregg2001.komorebi.data.auth.HonomiSessionStore
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

    @RequiresApi(Build.VERSION_CODES.O)
    fun getWatchHistoryFlow(): Flow<List<KonomiHistoryProgram>> {
        return watchHistoryDao.getAllHistory().map { entities ->
            entities.map { KonomiDataMapper.toUiModel(it) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun refreshHistoryFromApi() {
        if (sessionStore.current() == null) return
        runCatching { apiService.getSyncedWatchHistory() }.onSuccess { applyRemoteHistory(it.items) }
    }

    suspend fun saveWatchHistory(program: RecordedProgram, positionSeconds: Double) {
        // 1. ローカルDBに保存
        val entity = KonomiDataMapper.toEntity(program, positionSeconds)
        watchHistoryDao.insertOrUpdate(entity)

        if (sessionStore.current() != null) {
            val now = entity.watchedAt / 1000.0
            runCatching {
                apiService.updateSyncedWatchHistory(
                    WatchedHistoryPayload(listOf(WatchedHistoryItem(program.id, positionSeconds, now, now)))
                )
            }
        }
    }

    suspend fun syncWithServer() {
        val session = sessionStore.current() ?: return
        val previousOwner = sessionStore.historyOwner()
        if (previousOwner != null && !sessionStore.isHistoryOwner(session)) {
            val remoteItems = apiService.getSyncedWatchHistory().items
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
        val merged = apiService.updateSyncedWatchHistory(WatchedHistoryPayload(localItems))
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
    fun enqueueWatchHistorySave(program: RecordedProgram, positionSeconds: Double) {
        val programId = program.id
        val job = saveScope.launch(start = CoroutineStart.LAZY) {
            saveWatchHistory(program, positionSeconds)
        }
        pendingSaveJobs.put(programId, job)?.cancel()
        job.invokeOnCompletion { pendingSaveJobs.remove(programId, job) }
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
