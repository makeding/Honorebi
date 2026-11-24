package com.beeregg2001.komorebi.data.repository.tif

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.beeregg2001.komorebi.data.model.*
import com.beeregg2001.komorebi.data.repository.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

// 固定のTIF公開文字列の認識規則は番組行ごとに再生成しない。
private val ARIB_GENRE_ENTRY = Regex("""^ARIB\(0x[0-9A-Fa-f]+/0x[0-9A-Fa-f]+\):(.*)$""")
private val ARIB_GENRE_BOUNDARY = Regex("""、(?=ARIB\(0x[0-9A-Fa-f]+/0x[0-9A-Fa-f]+\):)""")
private val CANONICAL_GENRE_LABELS = mapOf(
    "NEWS" to "ニュース", "SPORTS" to "スポーツ",
            "MOVIES" to "映画", "DRAMA" to "ドラマ",
            "MUSIC" to "音楽", "COMEDY" to "バラエティ",
            "ENTERTAINMENT" to "バラエティ", "FAMILY_KIDS" to "キッズ",
            "ANIMAL_WILDLIFE" to "ドキュメンタリー", "EDUCATION" to "趣味/教育",
            "ARTS" to "趣味/教育", "TECH_SCIENCE" to "ドキュメンタリー",
            "LIFE_STYLE" to "情報", "TRAVEL" to "趣味/教育",
            "SHOPPING" to "情報", "GAMING" to "趣味/教育",
            "PREMIER" to "その他"
        )

/**
 * 特定のTV入力提供元を指定せず、frameworkの公開TvInputInfoから選局対象を解決する。
 * TvProviderのrow IDだけでなく、所有input IDもチャンネル識別子へ保持する。
 */
object TifInput {
    const val BACKEND_TYPE = "TIF"
    const val READ_LISTINGS_PERMISSION = "android.permission.READ_TV_LISTINGS"
    private const val CHANNEL_PREFIX = "tif:channel:"
    private const val PASSTHROUGH_PREFIX = "tif:input:"

    data class TuneTarget(val inputId: String, val channelUri: Uri)

    internal data class PassthroughRelation(
        val inputId: String,
        val parentInputId: String?,
        val isHdmi: Boolean,
    )

    /** A single nested HDMI source replaces its otherwise duplicate physical-port entry. */
    internal fun visiblePassthroughIds(relations: List<PassthroughRelation>): Set<String> {
        val hdmiIds = relations.filter { it.isHdmi }.mapTo(mutableSetOf()) { it.inputId }
        val singleChildParents = relations
            .filter { it.isHdmi && it.parentInputId != null && it.parentInputId in hdmiIds }
            .groupBy { it.parentInputId }
            .filterValues { it.size == 1 }
            .keys
        return relations.asSequence()
            .filterNot { it.isHdmi && it.inputId in singleChildParents }
            .mapTo(mutableSetOf()) { it.inputId }
    }

    internal fun visiblePassthroughInputs(infos: List<TvInputInfo>): List<TvInputInfo> {
        val inputs = infos.filter { it.isPassthroughInput }
        val visibleIds = visiblePassthroughIds(inputs.map { info ->
            PassthroughRelation(
                inputId = info.id,
                parentInputId = info.parentId,
                isHdmi = info.type == TvInputInfo.TYPE_HDMI,
            )
        })
        return inputs.filter { it.id in visibleIds }
    }

    /** OSが公開する入力ラベルを使う。独自ラベル管理は持たない。 */
    fun label(context: Context, info: TvInputInfo): String =
        info.loadCustomLabel(context)?.toString()
            ?: info.loadLabel(context)?.toString()
            ?: info.id

    fun isSupported(context: Context): Boolean {
        if (context.getSystemService(TvInputManager::class.java) == null) return false
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_TV) ||
            inputs(context).isNotEmpty()
    }

    fun inputs(context: Context): List<TvInputInfo> {
        val manager = context.getSystemService(TvInputManager::class.java) ?: return emptyList()
        return manager.tvInputList.filterNot { it.isHidden(context) }
            .sortedWith(compareBy<TvInputInfo>(
                { physicalInputOrder(it.parentId ?: it.id) },
                { it.parentId ?: it.id },
                { if (it.parentId == null) 0 else 1 },
                { it.id },
            ))
    }

    internal fun physicalInputOrder(inputId: String): Int =
        inputId.substringAfterLast("/HW", "").toIntOrNull() ?: Int.MAX_VALUE

    fun channelId(inputId: String, rowId: Long): String =
        CHANNEL_PREFIX + rowId + ":" + Uri.encode(inputId)

    fun passthroughId(inputId: String): String =
        PASSTHROUGH_PREFIX + Uri.encode(inputId)

    fun resolveTarget(context: Context, id: String): TuneTarget? {
        val inputInfos = inputs(context).associateBy { it.id }
        if (id.startsWith(PASSTHROUGH_PREFIX)) {
            val inputId = Uri.decode(id.removePrefix(PASSTHROUGH_PREFIX))
            val info = inputInfos[inputId] ?: return null
            if (!info.isPassthroughInput) return null
            return TuneTarget(info.id, TvContract.buildChannelUriForPassthroughInput(info.id))
        }

        if (!id.startsWith(CHANNEL_PREFIX)) return null
        val suffix = id.removePrefix(CHANNEL_PREFIX)
        val splitIndex = suffix.indexOf(':')
        if (splitIndex <= 0) return null
        val rowId = suffix.substring(0, splitIndex).toLongOrNull()?.takeIf { it > 0 } ?: return null
        val inputId = Uri.decode(suffix.substring(splitIndex + 1))
        val info = inputInfos[inputId] ?: return null
        if (info.isPassthroughInput) return null
        // 再スキャンによるrow ID再利用などで、保存済みIDが別の入力へ乗り移らないようにする。
        val cursor = context.contentResolver.query(
            TvContract.buildChannelUri(rowId),
            arrayOf(
                TvContract.Channels.COLUMN_INPUT_ID,
                TvContract.Channels.COLUMN_BROWSABLE,
                TvContract.Channels.COLUMN_SEARCHABLE
            ),
            null, null, null
        ) ?: return null
        val stillOwned = cursor.use { rows ->
            rows.moveToFirst() && rows.getString(0) == info.id &&
                rows.getInt(1) == 1 && rows.getInt(2) == 1
        }
        return if (stillOwned) TuneTarget(info.id, TvContract.buildChannelUri(rowId)) else null
    }
}

/**
 * Android TVの公開TvProviderとTvInputManagerだけを利用するTIFアダプター。
 * メーカー固有のinternal_provider_data、Tuner SDK、HAL、CASの所有には入らない。
 */
@Singleton
class TifRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : LiveProvider, EpgProvider, RecordProvider, ReserveProvider {
    private data class ChannelRow(
        val id: Long, val inputId: String, val number: String, val name: String,
        val onid: Long, val tsid: Long, val sid: Long, val category: String,
        val audioOnly: Boolean
    )

    private data class ProgramRow(
        val id: Long, val eventId: Int, val start: Long, val end: Long,
        val title: String, val description: String, val scrambled: Boolean?,
        val genres: List<EpgGenre>?
    )

    /** 取得失敗の局と、正常に0番組だった局を区別する。 */
    data class EpgSnapshot(
        val channels: List<EpgChannelWrapper>,
        val failedChannelIds: Set<String>
    )

    private fun hasListingsPermission(): Boolean =
        context.checkSelfPermission(TifInput.READ_LISTINGS_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun Cursor.stringAt(index: Int): String =
        if (isNull(index)) "" else getString(index).orEmpty()

    private fun Cursor.longAt(index: Int): Long =
        if (isNull(index)) 0L else getLong(index)

    private fun category(channelType: String, onid: Long): String = when (channelType) {
        TvContract.Channels.TYPE_ISDB_T, TvContract.Channels.TYPE_1SEG -> "GR"
        TvContract.Channels.TYPE_ISDB_S -> if (onid == 6L || onid == 7L) "CS" else "BS"
        else -> "TV" // ATSC/DVB/IPTVなどは放送方式を推測せずその他のTV入力として表示する
    }

    private fun readTunerChannels(inputs: List<TvInputInfo>): List<ChannelRow> {
        val available = inputs.filterNot { it.isPassthroughInput }.map { it.id }.toSet()
        if (available.isEmpty()) return emptyList()
        if (!hasListingsPermission()) {
            Log.w("TifRepository", "READ_TV_LISTINGS未許可のためチューナー一覧を省略します")
            return emptyList()
        }

        val projection = arrayOf(
            TvContract.Channels._ID,
            TvContract.Channels.COLUMN_INPUT_ID,
            TvContract.Channels.COLUMN_DISPLAY_NUMBER,
            TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_ORIGINAL_NETWORK_ID,
            TvContract.Channels.COLUMN_TRANSPORT_STREAM_ID,
            TvContract.Channels.COLUMN_SERVICE_ID,
            TvContract.Channels.COLUMN_TYPE,
            TvContract.Channels.COLUMN_BROWSABLE,
            TvContract.Channels.COLUMN_SEARCHABLE,
            TvContract.Channels.COLUMN_SERVICE_TYPE
        )
        val cursor = context.contentResolver.query(
            TvContract.Channels.CONTENT_URI, projection, null, null, null
        ) ?: error("TvProviderがchannel queryにnullを返しました。")
        return cursor.use {
            val out = mutableListOf<ChannelRow>()
            while (it.moveToNext()) {
                val ownerInput = it.stringAt(1)
                if (ownerInput !in available) continue
                // 一覧公開の判断は各TIS/TvProviderに委ねる。ワンセグ等の独自除外はしない。
                if (it.longAt(8) != 1L || it.longAt(9) != 1L) continue
                val rowId = it.longAt(0)
                if (rowId <= 0) continue
                val onid = it.longAt(4)
                out += ChannelRow(
                    id = rowId, inputId = ownerInput,
                    number = it.stringAt(2), name = it.stringAt(3),
                    onid = onid, tsid = it.longAt(5), sid = it.longAt(6),
                    category = category(it.stringAt(7), onid),
                    audioOnly = it.stringAt(10) == TvContract.Channels.SERVICE_TYPE_AUDIO
                )
            }
            out.sortedWith(compareBy<ChannelRow>({ it.category }, { it.number }, { it.inputId }, { it.id }))
        }
    }

    /** 番組情報がないTISでもチャンネルを残す。権限不備だけは必ず呼出元へ伝える。 */
    private suspend fun readProgramsOrNull(
        rowId: Long, start: Long? = null, end: Long? = null, liveNow: Long? = null
    ): List<ProgramRow>? =
        try {
            currentCoroutineContext().ensureActive()
            if (liveNow != null) readLivePrograms(rowId, liveNow)
            else readPrograms(rowId, requireNotNull(start), requireNotNull(end))
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            Log.w("TifRepository", "番組情報取得失敗 rowId=" + rowId, error)
            null
        }

    /**
     * ライブ一覧は現在番組と次番組だけ必要。候補を時刻列だけで先に選び、
     * 説明/ジャンルの投影は選ばれた最大2行に限定する。
     */
    private fun readLivePrograms(rowId: Long, now: Long): List<ProgramRow> {
        val columns = mutableListOf(
            TvContract.Programs._ID,
            TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
            TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS,
            TvContract.Programs.COLUMN_TITLE,
            TvContract.Programs.COLUMN_BROADCAST_GENRE
        )
        val descriptionIndex = if (Build.VERSION.SDK_INT >= 26) {
            columns += TvContract.Programs.COLUMN_SHORT_DESCRIPTION
            columns.lastIndex
        } else -1
        val canonicalIndex = if (Build.VERSION.SDK_INT >= 26) {
            columns += TvContract.Programs.COLUMN_CANONICAL_GENRE
            columns.lastIndex
        } else -1
        val cursor = context.contentResolver.query(
            TvContract.buildProgramsUriForChannel(
                rowId, now, Long.MAX_VALUE
            ), columns.toTypedArray(), null, null, null
        ) ?: error("TvProviderがライブ用program queryにnullを返しました。")
        return cursor.use { rows ->
            var presentPosition = -1
            var nextPosition = -1
            var latestStart = Long.MIN_VALUE
            while (rows.moveToNext()) {
                val start = rows.longAt(1)
                val end = rows.longAt(2)
                if (start <= now && end > now && start > latestStart) {
                    presentPosition = rows.position
                    latestStart = start
                } else if (start > now && end > start) {
                    nextPosition = rows.position
                    // AOSP TvContract.Programsの既定順序は開始時刻の昇順。
                    // 最初の有効な未来番組以降を走査する必要はない。
                    break
                }
            }
            listOf(presentPosition, nextPosition)
                .filter { it >= 0 }
                .mapNotNull { position ->
                    if (!rows.moveToPosition(position)) null else ProgramRow(
                        id = rows.longAt(0),
                        eventId = 0, // ライブのProgram表示モデルではeventIdは未使用
                        start = rows.longAt(1),
                        end = rows.longAt(2),
                        title = rows.stringAt(3),
                        description = if (descriptionIndex >= 0) rows.stringAt(descriptionIndex) else "",
                        scrambled = null, // ライブカードは料金／スクランブルを表示しない
                        genres = programGenres(
                            if (canonicalIndex >= 0) rows.stringAt(canonicalIndex) else "",
                            rows.stringAt(4)
                        )
                    )
                }
                .sortedBy { it.start }
        }
    }

    private fun readPrograms(rowId: Long, start: Long, end: Long): List<ProgramRow> {
        // optional列の導入APIを守り、古いTvProviderへ未知の列を要求しない。
        val columns = mutableListOf(
            TvContract.Programs._ID,
            TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
            TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS,
            TvContract.Programs.COLUMN_TITLE
        )
        val descriptionIndex = if (Build.VERSION.SDK_INT >= 26) {
            columns.add(TvContract.Programs.COLUMN_SHORT_DESCRIPTION)
            columns.lastIndex
        } else -1
        val eventIdIndex = if (Build.VERSION.SDK_INT >= 30) {
            columns.add(TvContract.Programs.COLUMN_EVENT_ID)
            columns.lastIndex
        } else -1
        val broadcastGenreIndex = columns.size
        columns += TvContract.Programs.COLUMN_BROADCAST_GENRE
        val canonicalGenreIndex = if (Build.VERSION.SDK_INT >= 26) {
            columns += TvContract.Programs.COLUMN_CANONICAL_GENRE
            columns.lastIndex
        } else -1
        val scrambledIndex = if (Build.VERSION.SDK_INT >= 33) {
            columns.add(TvContract.Programs.COLUMN_SCRAMBLED)
            columns.lastIndex
        } else -1

        val cursor = context.contentResolver.query(
            TvContract.buildProgramsUriForChannel(rowId, start, end),
            columns.toTypedArray(), null, null, null
        ) ?: error("TvProviderがprogram queryにnullを返しました。")
        return cursor.use {
            val result = mutableListOf<ProgramRow>()
            while (it.moveToNext()) {
                val from = it.longAt(1)
                val to = it.longAt(2)
                if (from <= 0 || to <= from) continue
                result += ProgramRow(
                    id = it.longAt(0),
                    eventId = if (eventIdIndex >= 0) it.longAt(eventIdIndex).toInt() else 0,
                    start = from, end = to, title = it.stringAt(3),
                    description = if (descriptionIndex >= 0) it.stringAt(descriptionIndex) else "",
                    // AOSP COLUMN_SCRAMBLED はスクランブルの有無。
                    // API 32以下、NULL、未知値は不明のまま保持する（無料/有料と推測しない）。
                    scrambled = if (scrambledIndex >= 0 && !it.isNull(scrambledIndex)) {
                        when (it.getInt(scrambledIndex)) {
                            0 -> false
                            1 -> true
                            else -> null
                        }
                    } else null,
                    genres = programGenres(
                        if (canonicalGenreIndex >= 0) it.stringAt(canonicalGenreIndex) else "",
                        it.stringAt(broadcastGenreIndex)
                    )
                )
            }
            result.sortedBy { it.start }
        }
    }

    // TvProvider epochはUTC基準の瞬間。既存UIはOffsetDateTimeをゾーン変換せず表示・
    // 放送日04:00境界で評価するため、モデル境界で端末の表示ゾーンへ変換する。
    /**
     * 公開COLUMN_BROADCAST_GENREを優先し、未提供時のみcanonicalを補完する。
     * Genres.decode()後も#116は「、ARIB(...):」で複数ジャンルを1要素に連結している。
     * この既知の書式だけ分割し、その他のTISの文字列は改変せず残す。
     */
    private fun programGenres(canonicalEncoded: String, broadcastEncoded: String): List<EpgGenre>? {
        fun decode(encoded: String): List<String> = if (encoded.isBlank()) emptyList()
            else TvContract.Programs.Genres.decode(encoded).filter { it.isNotBlank() }

        val genres = linkedSetOf<EpgGenre>()
        var hasInterpretedBroadcastGenre = false

        for (value in decode(broadcastEncoded)) {
            for (part in value.split(ARIB_GENRE_BOUNDARY)) {
                val raw = part.trim()
                if (raw.isEmpty()) continue
                val name = ARIB_GENRE_ENTRY.matchEntire(raw)?.groupValues?.get(1)?.trim()
                if (!name.isNullOrEmpty()) {
                    val hierarchy = name.split('/').map { it.trim() }.filter { it.isNotEmpty() }
                    if (hierarchy.isNotEmpty()) {
                        genres += EpgGenre(hierarchy.first(), hierarchy.drop(1).joinToString("/"))
                        hasInterpretedBroadcastGenre = true
                        continue
                    }
                }
                // ARIB以外の放送文字列や認識不能なARIB値は元の公開値を保持する。
                genres += EpgGenre(raw, "")
            }
        }

        // 原文を残しただけでは分類を解釈できたことにはならない。
        // ARIBの具体分類が読めた場合にのみ広いcanonicalの誤分類を抑制する。
        // 未知のbroadcast文字列は原文のまま残し、独立したcanonicalも併用する。
        if (hasInterpretedBroadcastGenre) return genres.toList()

        // canonicalはAOSP公開の広い分類。broadcastが未解釈の場合の補完に使う。

        for (canonical in decode(canonicalEncoded)) {
            genres += EpgGenre(CANONICAL_GENRE_LABELS[canonical] ?: canonical, "")
        }
        return genres.takeIf { it.isNotEmpty() }?.toList()
    }

    private fun displayTime(ms: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).toString()

    private fun seconds(start: Long, end: Long): Int =
        ((end - start) / 1000).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun ProgramRow.toLive(): Program = Program(
        id = "tif-program:" + id, title = title, description = description,
        detail = null, startTime = displayTime(start), endTime = displayTime(end),
        duration = seconds(start, end), genres = genres?.map { Genre(it.major, it.middle) }, videoResolution = null
    )

    private fun ChannelRow.toLiveChannel(
        current: ProgramRow?, next: ProgramRow?, displayName: String
    ): Channel = Channel(
        id = TifInput.channelId(inputId, id),
        displayChannelId = number.ifBlank { sid.toString() },
        name = displayName.ifBlank { "TVチャンネル " + id },
        channelNumber = number.ifBlank { sid.toString() },
        networkId = onid, serviceId = sid, transportStreamId = tsid,
        type = category, isWatchable = true, is_subchannel = false,
        isDisplay = true, programPresent = current?.toLive(),
        programFollowing = next?.toLive(), remocon_Id = 0
    )

    /** 入力ラベルが重複しても、表示の先頭で外部入力を区別する。 */
    internal fun passthroughChannels(infos: List<TvInputInfo>): List<Channel> {
        val inputs = TifInput.visiblePassthroughInputs(infos)
        val byLabel = inputs.groupBy { TifInput.label(context, it) }
        return inputs.map { info ->
            val label = TifInput.label(context, info)
            val duplicates = byLabel[label].orEmpty().sortedBy { it.id }
            val name = if (duplicates.size > 1)
                "【入力${duplicates.indexOfFirst { it.id == info.id } + 1}】$label" else label
            info.toPassthroughChannel(name)
        }
    }

    /** ライブとEPGが同じ表示名・入力識別規則を利用する。 */
    private fun tunerDisplayNames(rows: List<ChannelRow>, infos: List<TvInputInfo>): Map<Long, String> {
        val inputNames = infos.associate { it.id to TifInput.label(context, it) }
        val groups = rows.groupBy { it.name to it.number }
        return rows.associate { row ->
            val collision = groups[row.name to row.number].orEmpty()
            val ids = collision.map { it.inputId }.distinct().sorted()
            val label = inputNames[row.inputId] ?: row.inputId
            row.id to if (collision.size > 1) {
                val prefix = if (ids.size > 1) "【入力${ids.indexOf(row.inputId) + 1}】" else ""
                prefix + row.name + "（" + label + "）"
            } else row.name
        }
    }

    private fun TvInputInfo.toPassthroughChannel(displayName: String): Channel {
        val label = displayName
        return Channel(
            id = TifInput.passthroughId(id),
            displayChannelId = label, name = label, channelNumber = label,
            networkId = 0L, serviceId = 0L, transportStreamId = 0L,
            type = "INPUT", isWatchable = true, is_subchannel = false,
            isDisplay = true, programPresent = null, programFollowing = null, remocon_Id = 0
        )
    }

    /** 既存の所有者・browsable・searchable判定を共有するEPGキャッシュ用スナップショット。 */
    internal suspend fun tunerInputChoices(infos: List<TvInputInfo>): Map<String, List<Channel>> =
        withContext(Dispatchers.IO) {
            val rows = readTunerChannels(infos)
            val names = tunerDisplayNames(rows, infos)
            rows.groupBy { it.inputId }.mapValues { (_, group) ->
                group.map { it.toLiveChannel(null, null, names.getValue(it.id)) }
            }
        }

    suspend fun visibleChannelIds(): Set<String> = withContext(Dispatchers.IO) {
        if (!hasListingsPermission()) return@withContext emptySet()
        readTunerChannels(TifInput.inputs(context))
            .mapTo(mutableSetOf()) { TifInput.channelId(it.inputId, it.id) }
    }

    /** 疎通確認用。入力・公開チャンネルだけを読み、全局EPG queryを行わない。 */
    suspend fun checkReady(): Boolean = withContext(Dispatchers.IO) {
        // OSのTV入力列挙はこのIO処理中の1回だけ行う。
        val infos = TifInput.inputs(context)
        if (infos.isEmpty()) return@withContext false // 未登録は再設定待ちであり障害ではない
        if (infos.any { !it.isPassthroughInput }) {
            if (!hasListingsPermission()) return@withContext false // 権限要求待ち
            readTunerChannels(infos)
        }
        true
    }

    override suspend fun getChannels(): ChannelApiResponse = withContext(Dispatchers.IO) {
        val infos = TifInput.inputs(context)
        // TV入力未登録でもホーム／SMBは利用可能。入力はOS設定で後から追加できる。
        if (infos.isEmpty()) return@withContext ChannelApiResponse(
            terrestrial = emptyList(), bs = emptyList(), cs = emptyList(),
            others = emptyList(), inputs = emptyList()
        )
        val passthrough = passthroughChannels(infos)
        val tunerRows = if (hasListingsPermission()) {
            readTunerChannels(infos)
        } else {
            if (infos.any { !it.isPassthroughInput }) {
                Log.w("TifRepository", "READ_TV_LISTINGS未許可のためチューナー一覧を省略します")
            }
            emptyList()
        }
        val now = System.currentTimeMillis()
        val displayNames = tunerDisplayNames(tunerRows, infos)
        val channels = tunerRows.map { row ->
            // 別TISがEPGを公開していなくても視聴チャンネルまで消さない。
            val programs = readProgramsOrNull(
                row.id, liveNow = now
            ).orEmpty()
            val displayName = displayNames[row.id] ?: row.name
            row.toLiveChannel(
                programs.firstOrNull { it.start <= now && it.end > now },
                programs.firstOrNull { it.start > now },
                displayName
            )
        }
        ChannelApiResponse(
            terrestrial = channels.filter { it.type == "GR" },
            bs = channels.filter { it.type == "BS" },
            cs = channels.filter { it.type == "CS" },
            others = channels.filter { it.type == "TV" },
            inputs = passthrough
        )
    }

    // TIFのchannel URIはMedia3が受け取るストリームURLではない。
    override suspend fun getLiveStreamUrl(channelId: String, quality: String, streamNumber: Int): String =
        throw UnsupportedOperationException("TIFはTvView.tune()によって視聴します。")
    override suspend fun getChannelLogoUrl(channelId: String): String {
        if (!channelId.startsWith("tif:channel:")) return ""
        val rowId = channelId.removePrefix("tif:channel:").substringBefore(':').toLong()
        return TvContract.buildChannelLogoUri(rowId).toString()
    }

    private fun toEpgWrapper(
        row: ChannelRow, programs: List<ProgramRow>, displayName: String
    ): EpgChannelWrapper {
            val id = TifInput.channelId(row.inputId, row.id)
            return EpgChannelWrapper(
                channel = EpgChannel(
                    id = id, display_channel_id = row.number.ifBlank { row.sid.toString() },
                    network_id = row.onid.toInt(), service_id = row.sid.toInt(),
                    transport_stream_id = row.tsid.toInt(), remocon_id = 0,
                    channel_number = row.number.ifBlank { row.sid.toString() },
                    type = row.category, name = displayName, jikkyo_force = null,
                    is_subchannel = false, is_radiochannel = row.audioOnly, is_watchable = true
                ),
                programs = programs.map { program ->
                    EpgProgram(
                        id = "tif-program:" + program.id, channel_id = id,
                        network_id = row.onid.toInt(), service_id = row.sid.toInt(),
                        event_id = program.eventId, title = program.title,
                        description = program.description, detail = null,
                        start_time = displayTime(program.start), end_time = displayTime(program.end),
                        duration = seconds(program.start, program.end),
                        is_free = false, // TIF reports scrambling, not pricing
                        is_scrambled = program.scrambled,
                        genres = program.genres, video_type = null, audio_type = null, audio_sampling_rate = null
                    )
                }
            )
    }

    /** 局単位の取得失敗は局を消さずに通知し、成功した空一覧とは区別する。 */
    suspend fun getEpgSnapshot(
        startTime: String?, endTime: String?, channelType: String?
    ): EpgSnapshot = withContext(Dispatchers.IO) {
        val from = startTime?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            ?: (System.currentTimeMillis() - 60 * 60_000L)
        val to = endTime?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
            ?: (from + 24 * 60 * 60_000L)
        if (to <= from || channelType == "INPUT")
            return@withContext EpgSnapshot(emptyList(), emptySet())
        val infos = TifInput.inputs(context)
        val rows = readTunerChannels(infos)
        val displayNames = tunerDisplayNames(rows, infos)
        val failed = mutableSetOf<String>()
        val channels = rows.filter { channelType.isNullOrBlank() || it.category == channelType }
            .map { row ->
                val id = TifInput.channelId(row.inputId, row.id)
                val programs = readProgramsOrNull(row.id, from, to)
                if (programs == null) failed += id
                toEpgWrapper(row, programs.orEmpty(), displayNames[row.id] ?: row.name)
            }
        EpgSnapshot(channels, failed)
    }

    override suspend fun getEpgPrograms(
        startTime: String?, endTime: String?, channelType: String?
    ): List<EpgChannelWrapper> = getEpgSnapshot(startTime, endTime, channelType).channels

    override suspend fun getPinnedEpgPrograms(pinnedChannelIds: String): List<EpgChannelWrapper> =
        withContext(Dispatchers.IO) {
            val wanted = pinnedChannelIds.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            if (wanted.isEmpty()) return@withContext emptyList()
            val now = System.currentTimeMillis()
            val from = now - 60 * 60_000L
            val to = from + 24 * 60 * 60_000L
            // 既存のTIF可視性・所有inputID照合を通した後、query前にrowを絞る。
            val infos = TifInput.inputs(context)
            val rows = readTunerChannels(infos)
            val displayNames = tunerDisplayNames(rows, infos)
            rows.filter { TifInput.channelId(it.inputId, it.id) in wanted }
                .map { row ->
                    toEpgWrapper(
                        row, readProgramsOrNull(row.id, from, to).orEmpty(),
                        displayNames[row.id] ?: row.name
                    )
                }
        }

    // 汎用TIFの録画対応可否と、Komorebiの録画UI実装可否は別問題。
    // このAdapterは視聴専用であり、他のネットワークバックエンドへ誤転送しない。
    private fun unsupported() = UnsupportedOperationException("TIFでの録画・予約はKomorebi側で未実装です。")

    override suspend fun getRecordedPrograms(page: Int, order: String, channelId: String?, genre: String?) = RecordedApiResponse(0, emptyList())
    override suspend fun getRecordedProgram(videoId: Int): Result<RecordedProgram> = Result.failure(unsupported())
    override suspend fun searchRecordedPrograms(keyword: String, page: Int, order: String) =
        RecordedApiResponse(0, emptyList())
    override suspend fun getRecordStreamUrl(
        videoId: Int, quality: String, sessionId: String, offsetSeconds: Double, isRecording: Boolean
    ): String = throw unsupported()
    override suspend fun getArchivedJikkyo(videoId: Int): Result<List<ArchivedComment>> =
        Result.failure(unsupported())
    override suspend fun keepAlive(videoId: Int, quality: String, sessionId: String) { throw unsupported() }
    override suspend fun getTiledThumbnailUrl(videoId: Int): String? = null

    override suspend fun getReserves(): Result<List<ReserveItem>> = Result.success(emptyList())
    override suspend fun addReserve(request: ReserveRequest): Result<Unit> = Result.failure(unsupported())
    override suspend fun updateReserve(reservationId: Int, request: ReserveRequest): Result<Unit> =
        Result.failure(unsupported())
    override suspend fun deleteReservation(reservationId: Int): Result<Unit> = Result.failure(unsupported())
    override suspend fun getReservationConditions(): Result<List<ReservationCondition>> =
        Result.success(emptyList())
    override suspend fun addReservationCondition(request: ReservationConditionAddRequest): Result<Unit> =
        Result.failure(unsupported())
    override suspend fun updateReservationCondition(
        conditionId: Int, request: ReservationConditionUpdateRequest
    ): Result<ReservationCondition> = Result.failure(unsupported())
    override suspend fun deleteReservationCondition(conditionId: Int): Result<Unit> =
        Result.failure(unsupported())
}

