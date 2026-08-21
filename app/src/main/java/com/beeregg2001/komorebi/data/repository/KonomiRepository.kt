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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
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
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) : LiveProvider, RecordProvider, ReserveProvider, EpgProvider { // ★ インターフェースを実装

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

    /**
     * HonomiTV に実再生位置を送り、連携済みの Bangumi エピソードを視聴済みにします。
     * 連携状態・90% 判定・重複排除は HonomiTV が一元管理します。
     */
    suspend fun updateBangumiPlaybackProgress(
        videoId: Int,
        positionSeconds: Double,
        durationSeconds: Double,
    ): BangumiPlaybackProgressResponse =
        apiService.updateBangumiPlaybackProgress(
            videoId,
            BangumiPlaybackProgressRequest(positionSeconds, durationSeconds),
        )

    // ==========================================
    // チャンネル・録画リスト取得
    // ==========================================

    override suspend fun getChannels(): ChannelApiResponse {
        try {
            return apiService.getChannels()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch KonomiTV channels", e)
            // ★ 修正: エラーを握りつぶして空リストを返さず、例外をスローして知らせる
            throw Exception("KonomiTVからのチャンネル一覧取得に失敗しました。\nサーバーが稼働しているか確認してください。\n[詳細]: ${e.message}")
        }
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
            Log.e(TAG, "Failed to get recorded programs", e)
            // ★ 修正: 例外をスロー
            throw Exception("録画番組の取得に失敗しました。\nKonomiTVサーバーの状態を確認してください。\n[詳細]: ${e.message}")
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
            Log.e(TAG, "Failed to fetch recorded program $videoId", e)
            // ★ 修正
            Result.failure(Exception("録画番組詳細の取得に失敗しました。\n[詳細]: ${e.message}"))
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
            Log.e(TAG, "Failed to search recorded programs", e)
            // ★ 修正: 例外をスロー
            throw Exception("録画番組の検索に失敗しました。\n[詳細]: ${e.message}")
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

    @OptIn(UnstableApi::class)
    override suspend fun keepAlive(videoId: Int, quality: String, sessionId: String) {
        try {
            val response = apiService.keepAlive(videoId, quality, sessionId)
            if (!response.isSuccessful) {
                Log.w(TAG, "KeepAlive Failed: ${response.code()}")
            }
        } catch (e: Exception) {
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

    override suspend fun getArchivedJikkyo(videoId: Int): Result<List<ArchivedComment>> {
        return try {
            val response = apiService.getArchivedJikkyo(videoId)
            Result.success(if (response.is_success) response.comments else emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch archived jikkyo", e)
            // ★ 修正
            Result.failure(Exception("過去ログ実況の取得に失敗しました。\n[詳細]: ${e.message}"))
        }
    }

    override suspend fun getChaseArchivedJikkyo(program: RecordedProgram): Result<List<ArchivedComment>> =
        withContext(Dispatchers.IO) {
            try {
                val channel = program.channel
                    ?: return@withContext Result.failure(Exception("チャンネル情報がないため追いかけ実況を取得できません。"))
                val jikkyoId = getJikkyoId(channel.networkId, channel.serviceId)
                    ?: return@withContext Result.failure(Exception("このチャンネルは実況(過去ログ)に対応していません。"))

                val programStart = OffsetDateTime.parse(program.startTime)
                val programEnd = OffsetDateTime.parse(program.endTime)
                val startUnix = programStart.toEpochSecond()
                val endUnix = minOf(
                    System.currentTimeMillis() / 1000L,
                    programEnd.toEpochSecond()
                ).coerceAtLeast(startUnix + 1L)

                val url =
                    "https://jikkyo.tsukumijima.net/api/kakolog/jk$jikkyoId?starttime=$startUnix&endtime=$endUnix&format=json"
                Log.i(
                    TAG,
                    "Fetching chase jikkyo past log for jk$jikkyoId (${program.id}: $startUnix ~ $endUnix)"
                )

                val request = Request.Builder().url(url).build()
                val client = okHttpClient.newBuilder()
                    .apply { interceptors().clear() }
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    Log.i(
                        TAG,
                        "Chase jikkyo HTTP response. [request_url=${request.url}, actual_url=${response.request.url}, " +
                            "code=${response.code}, content_type=${response.header("Content-Type")}, bytes=${responseBody.length}]"
                    )
                    if (!response.isSuccessful) {
                        Log.w(
                            TAG,
                            "Chase jikkyo HTTP failure body=${responseBody.take(240)}"
                        )
                        return@withContext Result.failure(Exception("NX-Jikkyo APIエラー: HTTP ${response.code}"))
                    }

                    val jsonObject = JSONObject(responseBody)
                    if (jsonObject.has("error")) {
                        Log.w(TAG, "Chase jikkyo API error payload=${jsonObject.optString("error")}")
                        return@withContext Result.failure(
                            Exception("NX-Jikkyo APIエラー: ${jsonObject.getString("error")}")
                        )
                    }

                    Log.i(
                        TAG,
                        "Chase jikkyo JSON payload. [packet_count=${jsonObject.optJSONArray("packet")?.length() ?: 0}]"
                    )

                    val comments = parseNxJikkyoPackets(jsonObject, startUnix)
                    Log.i(
                        TAG,
                        "Successfully mapped ${comments.size} chase jikkyo comments. [video=${program.id}]"
                    )
                    Result.success(comments)
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Failed to fetch chase archived jikkyo. [video=${program.id}, jikkyo_id=${program.channel?.let { getJikkyoId(it.networkId, it.serviceId) }}]",
                    e
                )
                Result.failure(e)
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
            Log.e(TAG, "Failed to fetch reserves", e)
            // ★ 修正
            Result.failure(Exception("予約一覧の取得に失敗しました。\nKonomiTVサーバーの状態を確認してください。\n[詳細]: ${e.message}"))
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
            Log.e(TAG, "Failed to add reserve", e)
            // ★ 修正
            Result.failure(Exception("予約の追加に失敗しました。\n[詳細]: ${e.message}"))
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
            Log.e(TAG, "Failed to update reserve", e)
            // ★ 修正
            Result.failure(Exception("予約の更新に失敗しました。\n[詳細]: ${e.message}"))
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
            Log.e(TAG, "Failed to delete reservation", e)
            // ★ 修正
            Result.failure(Exception("予約の削除に失敗しました。\n[詳細]: ${e.message}"))
        }
    }

    override suspend fun getReservationConditions(): Result<List<ReservationCondition>> {
        return try {
            val response = apiService.getReservationConditions()
            Result.success(response.reservationConditions)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch reservation conditions", e)
            // ★ 修正
            Result.failure(Exception("自動録画ルールの取得に失敗しました。\n[詳細]: ${e.message}"))
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
            Log.e(TAG, "Failed to add reservation condition", e)
            // ★ 修正
            Result.failure(Exception("自動録画ルールの追加に失敗しました。\n[詳細]: ${e.message}"))
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
            Log.e(TAG, "Failed to update reservation condition", e)
            // ★ 修正
            Result.failure(Exception("自動録画ルールの更新に失敗しました。\n[詳細]: ${e.message}"))
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
            Log.e(TAG, "Failed to delete reservation condition", e)
            // ★ 修正
            Result.failure(Exception("自動録画ルールの削除に失敗しました。\n[詳細]: ${e.message}"))
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
            Log.e(TAG, "Failed to fetch EPG from KonomiTV", e)
            // ★ 修正: 例外をスロー
            throw Exception("KonomiTVからの番組表データ取得に失敗しました。\n[詳細]: ${e.message}")
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

    private fun parseNxJikkyoPackets(jsonObject: JSONObject, startUnix: Long): List<ArchivedComment> {
        val packetArray = jsonObject.optJSONArray("packet") ?: JSONArray()
        val comments = mutableListOf<ArchivedComment>()
        var invalidDateCount = 0

        for (i in 0 until packetArray.length()) {
            val packet = packetArray.optJSONObject(i) ?: continue
            val chat = packet.optJSONObject("chat") ?: continue
            val content = chat.optString("content", "")
            if (content.isBlank()) continue
            if (chat.optString("deleted") == "1") continue
            if (content.startsWith("/") &&
                content.matches(Regex("^/[a-z][a-z0-9_-]*(?:\\s|$).*")) &&
                chat.optString("premium") == "3"
            ) {
                continue
            }

            var color = "#FFEAEA"
            var position = "right"
            var size = "medium"
            chat.optString("mail", "")
                .replace("184", "")
                .split(" ")
                .forEach { command ->
                    getCommentColor(command)?.let { color = it }
                    getCommentPosition(command)?.let { position = it }
                    getCommentSize(command)?.let { size = it }
                }

            val chatDate = chat.optString("date").toDoubleOrNull()
            val chatDateUsec = chat.optString("date_usec", "0").toDoubleOrNull()
            if (chatDate == null || chatDateUsec == null) {
                invalidDateCount++
                continue
            }
            val commentTime = (chatDate - startUnix) + (chatDateUsec / 1000000.0)

            comments.add(
                ArchivedComment(
                    time = commentTime,
                    text = content,
                    color = color,
                    author = chat.optString("user_id", ""),
                    type = position,
                    size = size
                )
            )
        }

        Log.i(
            TAG,
            "Parsed chase jikkyo packets. [packets=${packetArray.length()}, comments=${comments.size}, invalid_dates=$invalidDateCount, start_unix=$startUnix]"
        )
        return comments.sortedBy { it.time }
    }

    private fun getCommentColor(command: String): String? = when (command) {
        "red" -> "#F02840"
        "pink" -> "#FF8080"
        "orange" -> "#FFC000"
        "yellow" -> "#FFFF00"
        "green" -> "#00FF00"
        "cyan" -> "#00FFFF"
        "blue" -> "#0000FF"
        "purple" -> "#C000FF"
        "black" -> "#000000"
        "white", "" -> null
        "niconicowhite" -> "#CCCC99"
        "white2" -> "#CCCC99"
        "truered" -> "#CC0033"
        "red2" -> "#CC0033"
        "passionorange" -> "#FF6600"
        "orange2" -> "#FF6600"
        "madyellow" -> "#999900"
        "yellow2" -> "#999900"
        "elementalgreen" -> "#00CC66"
        "green2" -> "#00CC66"
        "marineblue" -> "#33FFFC"
        "blue2" -> "#33FFFC"
        "nobleviolet" -> "#6633CC"
        "purple2" -> "#6633CC"
        else -> null
    }

    private fun getCommentPosition(command: String): String? = when (command) {
        "ue" -> "top"
        "naka" -> "right"
        "shita" -> "bottom"
        else -> null
    }

    private fun getCommentSize(command: String): String? = when (command) {
        "big" -> "big"
        "medium" -> "medium"
        "small" -> "small"
        else -> null
    }
}
