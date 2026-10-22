package com.beeregg2001.komorebi.data.repository

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.api.KonomiApi
import com.beeregg2001.komorebi.data.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "Komorebi_Repo"

/**
 * KonomiTVバックエンド（API）との通信を抽象化するリポジトリ。
 * ローカルDBに関する処理はそれぞれ WatchHistoryRepository, LastChannelRepository に分離しました。
 */
@Singleton
class KonomiRepository @Inject constructor(
    private val apiService: KonomiApi,
    // ★ 追加: URL生成のためにIP/Portを取得する SettingsRepository を Inject
    private val settingsRepository: SettingsRepository,
    @param:ApplicationContext private val context: Context
) : LiveProvider, RecordProvider, ReserveProvider, EpgProvider, OnAirProvider { // ★ インターフェースを実装

    // ==========================================
    // ユーザー設定・セッション管理
    // ==========================================
    private val _currentUser = MutableStateFlow<KonomiUser?>(null)
    val currentUser: StateFlow<KonomiUser?> = _currentUser.asStateFlow()
    private var jikkyoChannelsCache: JSONArray? = null

    /**
     * 現在ログインしているKonomiTVユーザーの情報を取得・更新します。
     * バックエンドのセッション維持（セッション切れ防止）の役割も兼ねています。
     */
    suspend fun refreshUser() {
        runCatching { apiService.getCurrentUser() }
            .onSuccess { _currentUser.value = it }
    }

    // ==========================================
    // チャンネル・録画リスト取得
    // ==========================================

    override suspend fun getChannels(): ChannelApiResponse {
        try {
            val response = apiService.getChannels()
            if (!response.isSuccessful) {
                throw java.io.IOException("HTTP ${response.code()}")
            }
            val channels = requireNotNull(response.body()) { "チャンネル一覧の応答本文がありません" }
            val sourceErrors = response.headers()["X-Channel-Source-Errors"]
                ?.let(::parseChannelSourceErrors)
                .orEmpty()
            return channels.copy(sourceErrors = sourceErrors)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to fetch KonomiTV channels", e)
            // ★ 修正: エラーを握りつぶして空リストを返さず、例外をスローして知らせる
            throw Exception("KonomiTVからのチャンネル一覧取得に失敗しました。\nサーバーが稼働しているか確認してください。\n[詳細]: ${e.message}", e)
        }
    }

    private fun parseChannelSourceErrors(header: String): Map<String, String?> = runCatching {
        val objectValue = JSONObject(header)
        buildMap {
            val keys = objectValue.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, if (objectValue.isNull(key)) null else objectValue.optString(key))
            }
        }
    }.getOrElse { error ->
        Log.w(TAG, "Ignoring malformed X-Channel-Source-Errors header", error)
        emptyMap()
    }

    override suspend fun getRecordedPrograms(
        page: Int,
        order: String,
        channelId: String?,
        genre: String?
    ): RecordedApiResponse {
        return try {
            val response = apiService.getRecordedPrograms(
                page = page,
                order = order,
                channelId = channelId,
                genre = genre
            )

            response.withThumbnailUrls()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to get recorded programs", e)
            // ★ 修正: 例外をスロー
            throw Exception("録画番組の取得に失敗しました。\nKonomiTVサーバーの状態を確認してください。\n[詳細]: ${e.message}", e)
        }
    }

    override suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram> {
        return try {
            val program = apiService.getRecordedProgram(videoId)
            val ip = settingsRepository.konomiIp.first()
            val port = settingsRepository.konomiPort.first()
            val fallbackUrl =
                UrlBuilder.getThumbnailUrl("KONOMITV", ip, port, program.id.toString())
            Result.success(program.copy(apiThumbnailUrl = fallbackUrl))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to fetch recorded program $videoId", e)
            // ★ 修正
            Result.failure(Exception("録画番組詳細の取得に失敗しました。\n[詳細]: ${e.message}", e))
        }
    }

    override suspend fun searchRecordedPrograms(
        keyword: String,
        page: Int,
        order: String
    ): RecordedApiResponse {
        return try {
            Log.d(TAG, "Calling API searchVideos. Keyword: $keyword, Page: $page")
            apiService.searchVideos(keyword = keyword, page = page, order = order)
                .withThumbnailUrls()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to search recorded programs", e)
            // ★ 修正: 例外をスロー
            throw Exception("録画番組の検索に失敗しました。\n[詳細]: ${e.message}", e)
        }
    }

    override suspend fun getRecordedProgramsBySeries(
        seriesId: Int,
        page: Int,
        order: String
    ): RecordedApiResponse {
        return apiService.getRecordedProgramsBySeries(seriesId = seriesId, page = page, order = order)
            .withThumbnailUrls()
    }

    override suspend fun getSeriesList(page: Int, order: String): SeriesApiResponse {
        return apiService.getSeriesList(page = page, order = order)
    }

    override suspend fun getOnAirSeries(): OnAirSeriesApiResponse =
        apiService.getOnAirSeries()

    override suspend fun getSeriesSummary(seriesId: Int): SeriesProgram =
        apiService.getSeriesSummary(seriesId)

    @OptIn(UnstableApi::class)
    override suspend fun keepAlive(videoId: Int, quality: String, sessionId: String) {
        try {
            val response = apiService.keepAlive(videoId, quality, sessionId)
            if (!response.isSuccessful) {
                Log.w(TAG, "KeepAlive Failed: ${response.code()}")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to keep alive", e)
        }
    }

    // ★ 追加: KonomiTV仕様のタイル画像URLを生成
    override suspend fun getTiledThumbnailUrl(videoId: Int): String? {
        val ip = settingsRepository.konomiIp.first()
        val port = settingsRepository.konomiPort.first()
        // 既存の UrlBuilder.getTiledThumbnailUrl をそのまま利用します
        return UrlBuilder.getTiledThumbnailUrl(ip, port, videoId)
    }

    // ==========================================
    // マイリスト・視聴履歴の管理 (API通信のみ)
    // ==========================================

    suspend fun getBookmarks(): Result<List<KonomiProgram>> =
        runCatching { apiService.getBookmarks() }

    suspend fun getWatchHistory(): Result<List<KonomiHistoryProgram>> =
        runCatching { apiService.getWatchHistory() }

    // ==========================================
    // ニコニコ実況 (コメント) 関連
    // ==========================================

    suspend fun getJikkyoInfo(channelId: String) = runCatching {
        apiService.getJikkyoInfo(channelId)
    }

    suspend fun syncPlaybackPosition(programId: String, position: Double) {
        runCatching { apiService.updateWatchHistory(HistoryUpdateRequest(programId, position)) }
    }

    /** HonomiTV ローカル拡張: 録画ファイル内の指定時刻直前のキーフレーム位置を解決する */
    suspend fun getSeekPosition(videoId: Int, timeSeconds: Double): SeekPositionResponse =
        apiService.getSeekPosition(videoId, timeSeconds)

    override suspend fun getArchivedJikkyo(videoId: Int): Result<List<ArchivedComment>> {
        return try {
            val response = apiService.getArchivedJikkyo(videoId)
            if (response.is_success) Result.success(response.comments)
            else Result.failure(java.io.IOException(response.detail ?: "実況コメントを取得できませんでした。"))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to fetch archived jikkyo", e)
            // ★ 修正
            Result.failure(Exception("過去ログ実況の取得に失敗しました。\n[詳細]: ${e.message}", e))
        }
    }

    override suspend fun getChaseJikkyoWatchSessionUrl(program: RecordedProgram): String? {
        val channel = program.channel ?: return null
        val jikkyoId = getJikkyoId(channel.networkId, channel.serviceId) ?: return null
        return "wss://nx-jikkyo.tsukumijima.net/api/v1/channels/jk$jikkyoId/ws/watch"
    }

    // ==========================================
    // 録画予約（EDCB連携）の管理
    // ==========================================

    override suspend fun getReserves(): Result<List<ReserveItem>> {
        return try {
            Result.success(apiService.getReserves().reservations)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to fetch reserves", e)
            // ★ 修正
            Result.failure(Exception("予約一覧の取得に失敗しました。\nKonomiTVサーバーの状態を確認してください。\n[詳細]: ${e.message}", e))
        }
    }

    override suspend fun addReserve(request: ReserveRequest): Result<Unit> {
        return try {
            val response = apiService.addReserve(request)
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                throw Exception("Reservation failed: ${response.code()} $errorBody")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to add reserve", e)
            // ★ 修正
            Result.failure(Exception("予約の追加に失敗しました。\n[詳細]: ${e.message}", e))
        }
    }

    override suspend fun updateReserve(reservationId: Int, request: ReserveRequest): Result<Unit> {
        return try {
            val response = apiService.updateReserve(reservationId, request)
            if (!response.isSuccessful) {
                val errorBody = response.errorBody()?.string()
                throw Exception("Update reservation failed: ${response.code()} $errorBody")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to update reserve", e)
            // ★ 修正
            Result.failure(Exception("予約の更新に失敗しました。\n[詳細]: ${e.message}", e))
        }
    }

    override suspend fun deleteReservation(reservationId: Int): Result<Unit> {
        return try {
            val response = apiService.deleteReservation(reservationId)
            if (!response.isSuccessful) {
                if (response.code() == 404) {
                    Log.w(TAG, "Reservation $reservationId not found (already deleted?)")
                    return Result.success(Unit)
                }
                throw Exception(
                    "Delete reservation failed: ${response.code()} ${
                        response.errorBody()?.string()
                    }"
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to delete reservation", e)
            // ★ 修正
            Result.failure(Exception("予約の削除に失敗しました。\n[詳細]: ${e.message}", e))
        }
    }

    override suspend fun getReservationConditions(): Result<List<ReservationCondition>> {
        return try {
            val response = apiService.getReservationConditions()
            Result.success(response.reservationConditions)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to fetch reservation conditions", e)
            // ★ 修正
            Result.failure(Exception("自動録画ルールの取得に失敗しました。\n[詳細]: ${e.message}", e))
        }
    }

    override suspend fun addReservationCondition(request: ReservationConditionAddRequest): Result<Unit> {
        return try {
            val response = apiService.addReservationCondition(request)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                throw Exception("Failed to add condition: ${response.code()}")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to add reservation condition", e)
            // ★ 修正
            Result.failure(Exception("自動録画ルールの追加に失敗しました。\n[詳細]: ${e.message}", e))
        }
    }

    override suspend fun updateReservationCondition(
        conditionId: Int,
        request: ReservationConditionUpdateRequest
    ): Result<ReservationCondition> {
        return try {
            val condition = apiService.updateReservationCondition(conditionId, request)
            Result.success(condition)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to update reservation condition", e)
            // ★ 修正
            Result.failure(Exception("自動録画ルールの更新に失敗しました。\n[詳細]: ${e.message}", e))
        }
    }

    override suspend fun deleteReservationCondition(conditionId: Int): Result<Unit> {
        return try {
            val response = apiService.deleteReservationCondition(conditionId)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                throw Exception("Failed to delete condition: ${response.code()}")
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to delete reservation condition", e)
            // ★ 修正
            Result.failure(Exception("自動録画ルールの削除に失敗しました。\n[詳細]: ${e.message}", e))
        }
    }

    // ==========================================
    // ★ 追加: UrlBuilderへの依存をリポジトリ内に隠蔽
    // ==========================================

    override suspend fun getLiveStreamUrl(
        channelId: String,
        quality: String,
        streamNumber: Int
    ): String {
        val ip = settingsRepository.konomiIp.first()
        val port = settingsRepository.konomiPort.first()
        return UrlBuilder.getKonomiTvLiveStreamUrl(ip, port, channelId, quality)
    }

    override suspend fun createLiveStreamSession(channelId: String, config: BackendConfig): LiveStreamSessionLease {
        val target = com.beeregg2001.komorebi.data.api.LiveSessionBackendTarget(
            UrlBuilder.formatBaseUrl(config.ip, config.port, "https")
        )
        val response = apiService.createLiveStreamSession(LiveStreamSessionRequest(channelId), target)
        return LiveStreamSessionLease(response) { id ->
            val result = apiService.closeLiveStreamSession(id, target)
            if (!result.isSuccessful && result.code() != 404) {
                throw java.io.IOException("LIVE_SESSION_RELEASE_HTTP_${result.code()}")
            }
        }
    }

    override suspend fun getChannelLogoUrl(channelId: String): String {
        val backend = settingsRepository.backendType.first()

        return if (backend == "MIRAKURUN_ONLY") {
            val ip = settingsRepository.mirakurunIp.first()
            val port = settingsRepository.mirakurunPort.first()

            // "mirakurun_32736_1024" のようなIDからネットワークIDとサービスIDを抽出
            val parts = channelId.split("_")
            val nid = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val sid = parts.getOrNull(2)?.toLongOrNull() ?: 0L

            UrlBuilder.getMirakurunLogoUrl(ip, port, nid, sid)
        } else {
            val ip = settingsRepository.konomiIp.first()
            val port = settingsRepository.konomiPort.first()
            UrlBuilder.getKonomiTvLogoUrl(ip, port, channelId)
        }
    }

    override suspend fun getRecordStreamUrl(
        videoId: Int,
        quality: String,
        sessionId: String,
        offsetSeconds: Double,
        isRecording: Boolean
    ): String {
        val ip = settingsRepository.konomiIp.first()
        val port = settingsRepository.konomiPort.first()
        return UrlBuilder.getVideoPlaylistUrl(ip, port, videoId, sessionId, quality, isRecording)
    }

    // ==========================================
    // ★ 追加: EpgProvider の実装
    // ※ 以前 EpgRepository 内にあった KonomiTvApiService の通信処理をここに移動
    // ==========================================
    override suspend fun getEpgPrograms(
        startTime: String?,
        endTime: String?,
        channelType: String?
    ): List<EpgChannelWrapper> {
        return try {
            apiService.getEpgPrograms(startTime, endTime, channelType).channels
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Failed to fetch EPG from KonomiTV", e)
            // ★ 修正: 例外をスロー
            throw Exception("KonomiTVからの番組表データ取得に失敗しました。\n[詳細]: ${e.message}", e)
        }
    }

    override suspend fun getPinnedEpgPrograms(pinnedChannelIds: String): List<EpgChannelWrapper> {
        return apiService.getEpgPrograms(pinnedChannelIds = pinnedChannelIds).channels
    }

    private suspend fun RecordedApiResponse.withThumbnailUrls(): RecordedApiResponse {
        val ip = settingsRepository.konomiIp.first()
        val port = settingsRepository.konomiPort.first()
        val updatedPrograms = recordedPrograms.map { program ->
            if (!program.apiThumbnailUrl.isNullOrBlank()) {
                program
            } else {
                val fallbackUrl =
                    UrlBuilder.getThumbnailUrl("KONOMITV", ip, port, program.id.toString())
                program.copy(apiThumbnailUrl = fallbackUrl)
            }
        }
        return copy(recordedPrograms = updatedPrograms)
    }

    private fun getJikkyoChannels(): JSONArray {
        jikkyoChannelsCache?.let { return it }
        val array = runCatching {
            val jsonString = context.assets.open("jikkyo_channels.json").bufferedReader().use { it.readText() }
            JSONArray(jsonString)
        }.getOrElse {
            Log.e(TAG, "Failed to load jikkyo_channels.json", it)
            JSONArray()
        }
        jikkyoChannelsCache = array
        return array
    }

    private fun getJikkyoId(networkId: Int?, serviceId: Int?): Int? {
        if (networkId == null || serviceId == null) return null
        val channels = getJikkyoChannels()

        for (i in 0 until channels.length()) {
            val channel = channels.optJSONObject(i) ?: continue
            val mappedNetworkId = channel.optInt("network_id", -1)
            val mappedServiceIdRaw = channel.opt("service_id")?.toString() ?: "-1"
            val mappedServiceId = if (mappedServiceIdRaw.startsWith("0x", ignoreCase = true)) {
                mappedServiceIdRaw.substring(2).toIntOrNull(16) ?: -1
            } else {
                mappedServiceIdRaw.toIntOrNull() ?: -1
            }

            val matched = if (networkId == mappedNetworkId && serviceId == mappedServiceId) {
                true
            } else {
                networkId in 0x7880..0x7FEF &&
                    mappedNetworkId == 15 &&
                    (serviceId == mappedServiceId ||
                        serviceId - 1 == mappedServiceId ||
                        serviceId - 2 == mappedServiceId)
            }

            if (matched) {
                return channel.optInt("jikkyo_id", -1).takeIf { it > 0 }
            }
        }

        return null
    }

}
